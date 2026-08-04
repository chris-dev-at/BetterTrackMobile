package at.bettertrack.app.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Realized-P/L & tax engine — a **literal** Kotlin port of
 * `packages/domain/src/tax.ts` at the commit pinned in
 * `tools/domain-vectors/PINNED_AT`.
 *
 * The money-math rulebook behind Settings → Taxes: EUR-denominated moving-average
 * *and* FIFO cost basis, per-sell realized gain/loss, calendar-year bucketing
 * (Europe/Vienna), the Austrian flat-KESt settlement with same-year loss offset,
 * the German Abgeltungsteuer with dual loss pots and Sparer-Pauschbetrag, the
 * Finnish progressive pääomatulovero, the user-parameterized custom engine, and
 * the manual per-trade tax entry.
 *
 * Like the rest of this package it is **pure Kotlin** — no Android, no Room, no
 * clock, no I/O. Everything is a pure function of its inputs; the caller owns FX
 * conversion (transactions arrive here already in EUR at their trade-date rates),
 * persistence and movement posting.
 *
 * **Nothing consumes this file yet.** Drive-mode tax is a later package; this is
 * the conformance foundation, proven against
 * `app/src/test/resources/domain-vectors/tax.json` at exact `Double` equality.
 *
 * ## Translation notes (plan §3.3), collected
 *
 * 1. **`floorCents` is ported separately** (rule 3). `tax.ts` declares its own
 *    quantizer at line 174 — byte-identical in body to `cashLedger.ts:202`, but a
 *    *different function* that throws a *different* error class. Kotlin flattens
 *    the two TypeScript module namespaces into one package, so the tax copy is
 *    named [taxFloorCents]; that is the only naming deviation in this file, and
 *    `TaxHandPortedTest` pins the two against each other on every boundary case
 *    the vitest parity test uses.
 * 2. **No year-keyed objects exist in `tax.ts`** (rule 4). The module never keys
 *    anything by year: `settleAtYear` / `settleFiYear` / `settleDeYear` /
 *    `settleCustomYear` each see exactly ONE year, and the cross-year chains
 *    ([deCarryPots], [customCarryForYears]) take an **ordered array** of prior
 *    years supplied by the caller. Its single `Map` is `positions` in
 *    [realizedSellsEur], keyed by `assetId` and only ever `get`/`set` — never
 *    iterated — so no floating-point accumulation depends on a hash order. The
 *    ascending-numeric-key hazard the plan warns about therefore does not arise
 *    here; the traversal order that *does* matter is the caller's array order,
 *    and `List` reproduces it exactly.
 * 3. **No `Math.round`, no `Math.trunc`, no `toFixed` anywhere in `tax.ts`**
 *    (rule 3). The only quantizer is [taxFloorCents], whose single rounding
 *    operation is `Math.floor` of a **non-negative** magnitude — which
 *    `kotlin.math.floor` reproduces bit-for-bit. The negative and exact-half
 *    cases are covered by generated vectors regardless (`floorCents(-0.005)`,
 *    `(-1.005)`, `(-76.545)`, `(2.675)`, `(0.1+0.2)`), and the explicit
 *    `cents == 0.0` branch is what stops a floored negative returning `-0.0`.
 * 4. **String-typed discriminators** (rule 1). `side`, the event `kind`s, the DE
 *    pot `category` and the cost-basis `strategy` stay `String` rather than
 *    becoming enums, because every one of them has a live, vector-covered error
 *    path that a Kotlin enum could not even express — the vitest suites probe
 *    them with `'bogus' as 'dividend'`, `'weird' as never`, `'lifo' as 'fifo'`.
 * 5. **Error messages are reproduced character for character**, interpolated
 *    numbers included ([jsNumberToString]); they are part of the audited
 *    contract and the vectors compare them verbatim.
 * 6. Two guards are **unreachable in Kotlin** and are kept only as documentation:
 *    the `typeof params[flag] !== 'boolean'` check in [assertCustomParams] (the
 *    flags are `Boolean` by type) and the `Number.isFinite(year)` check in
 *    [viennaYearOf] (a `ZonedDateTime` year is an `Int` by construction). Both
 *    are called out at their sites.
 */

// ---------------------------------------------------------------------------
// Modes & constants
// ---------------------------------------------------------------------------

/**
 * Tax modes (§13.3 V3-P4b; §13.5 V5-P4c). `none` = exact pre-V3-P4 behavior;
 * `manual_per_trade` = optional user-entered tax per sell/dividend, zero
 * automation; `country_specific` = automated computation for AT/DE/FI; `custom` =
 * the user-parameterized rule-built engine ([settleCustomYear]).
 *
 * §3.3 rule 4: declaration order is contract and is asserted by a vector.
 */
typealias TaxMode = String

val TAX_MODES: List<TaxMode> = listOf("none", "manual_per_trade", "country_specific", "custom")

/** The first shipped country of `country_specific` mode (§13.3 V3-P4b). */
const val TAX_COUNTRY_AT: String = "AT"

/** The second shipped country of `country_specific` mode (§13.5 V5-P4, #580). */
const val TAX_COUNTRY_DE: String = "DE"

/** Austrian flat KESt rate on realized gains and dividends (§13.3 V3-P4b). */
const val AT_KEST_RATE: Double = 0.275

/** German flat Abgeltungsteuer rate on capital income (§32d Abs. 1 EStG). */
const val DE_KAPEST_RATE: Double = 0.25

/** Solidaritätszuschlag, levied on the KapESt itself (§3 Abs. 1 Nr. 5, §4 SolzG). */
const val DE_SOLI_RATE: Double = 0.055

/**
 * Sparer-Pauschbetrag per calendar year (§20 Abs. 9 EStG; €1,000 since VZ 2023).
 * Applied after loss offset, floored at zero; the unused remainder does NOT carry
 * into the next year — unlike the loss pots, which do.
 */
const val DE_SPARER_PAUSCHBETRAG_EUR: Double = 1000.0

/** The third shipped country of `country_specific` mode (#635). */
const val TAX_COUNTRY_FI: String = "FI"

/**
 * Finnish capital-income tax (pääomatulovero, TVL 124 §): 30 % up to the
 * progressive threshold, 34 % on the part above it. Gains, dividends and losses
 * share one within-year pool (losses offset gains first); v1 carries no losses
 * across years — extensible later like the DE pots.
 */
const val FI_CAPITAL_INCOME_RATE: Double = 0.3
const val FI_CAPITAL_INCOME_HIGH_RATE: Double = 0.34
const val FI_HIGH_RATE_THRESHOLD_EUR: Double = 30_000.0

/**
 * The countries the `country_specific` engine ships (#635). Adding a country =
 * add it here + a settle function below + the service-level engine entry.
 */
typealias SupportedTaxCountry = String

val SUPPORTED_TAX_COUNTRIES: List<SupportedTaxCountry> =
    listOf(TAX_COUNTRY_AT, TAX_COUNTRY_DE, TAX_COUNTRY_FI)

/**
 * Cost-basis strategies of the EUR tax replay (V5-P4, #580): `moving-average` is
 * the AT method (gleitender Durchschnittspreis — the pre-V5-P4 behavior,
 * byte-identical), `fifo` the German per-lot consumption (§20 Abs. 4 Satz 7
 * EStG). The strategy is selected by the active tax country via
 * [costBasisStrategyForCountry].
 *
 * §3.3 rule 1: a `String`, not an enum — `assertCustomParams` has a live error
 * path for a non-member value (`'lifo'`), which an enum could not accept.
 */
typealias CostBasisStrategy = String

val COST_BASIS_STRATEGIES: List<CostBasisStrategy> = listOf("moving-average", "fifo")

/** The cost-basis strategy a tax country mandates (DE/FI = FIFO, AT = average). */
fun costBasisStrategyForCountry(country: String?): CostBasisStrategy =
    if (country == TAX_COUNTRY_DE || country == TAX_COUNTRY_FI) "fifo" else "moving-average"

/**
 * The timezone whose calendar defines a tax year (§16 2026-07-08): trades are
 * bucketed by their trade date's year **in Europe/Vienna**, so a Dec-31 23:30 UTC
 * sell belongs to the new Vienna year.
 */
const val TAX_YEAR_TIME_ZONE: String = "Europe/Vienna"

/**
 * Quantity comparison tolerance, mirroring `holdings.QTY_EPSILON`.
 *
 * §3.3 rules 3 + 7: `tax.ts` **re-declares** this constant locally rather than
 * importing it (the `packages/domain` purity rule forbids value imports), so the port
 * re-declares it too instead of reusing [QTY_EPSILON] — a separate declaration
 * for a separately-owned contract. `TaxHandPortedTest` pins the two equal, which
 * is exactly the guarantee the TypeScript comment claims.
 */
const val TAX_QTY_EPSILON: Double = 1e-9

/**
 * One scale-8 storage quantum (#917). Quantities persist as `numeric(20,8)`: the
 * write path epsilon-validates the raw client values, then PostgreSQL rounds each
 * row independently to scale 8, so every stored row can sit up to one quantum
 * away from the raw value that was validated. A replayed position can therefore
 * show a spurious shortfall bounded by one quantum per contributing stored row —
 * [realizedSellsEur] waives exactly that envelope; anything beyond it fails
 * closed as a genuine oversell.
 */
const val QTY_STORAGE_QUANTUM: Double = 1e-8

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Invalid input to the tax engine — malformed timestamps, non-finite amounts, a
 * sell exceeding the held quantity, contradictory manual-tax input. Typed so the
 * caller can map mistakes to a 4xx instead of a 500.
 *
 * Port of the TypeScript `class TaxComputationError extends Error` whose
 * constructor sets `this.name = 'TaxComputationError'` — the vectors assert that
 * name (plan §3.3 rule 6).
 */
class TaxComputationError(message: String) : DomainException(message)

// ---------------------------------------------------------------------------
// Cent quantizer (local mirror of cashLedger.floorCents — see the file header)
// ---------------------------------------------------------------------------

/**
 * JavaScript's `Number.EPSILON`. Reproduced as its exact literal rather than
 * re-derived (§3.3 rule 7): it is a *contract* input to the boundary nudge below.
 *
 * Declared privately here rather than shared with `CashLedger.kt` for the same
 * reason the function is: `tax.ts` owns its own copy.
 */
private const val NUMBER_EPSILON: Double = 2.220446049250313e-16

/**
 * Quantize a EUR amount **down** to whole cents — floor toward zero, never round
 * up (the #370 money policy). Nudges a value a few ULPs below a cent boundary
 * (`8.61 → 860.999…9`) back onto it before truncating, so exact cents survive
 * float error while a genuine sub-cent residue still floors away.
 *
 * **This is `tax.ts`'s own `floorCents` (line 174), NOT `cashLedger.ts`'s** — plan
 * §3.3 rule 3 requires the two to be ported separately, and they genuinely differ
 * in one observable way: this one throws [TaxComputationError] where the ledger's
 * throws `CashLedgerError`. The Kotlin name carries the `tax` prefix only because
 * one Kotlin package cannot hold two top-level `floorCents` functions; the TS name
 * is `floorCents` and the vectors are emitted under that name.
 *
 * §3.3 rule 3, the rounding trap: there is deliberately no `Math.round` here — the
 * whole policy is `Math.floor` of a non-negative magnitude (the `abs` guarantees
 * it), which `kotlin.math.floor` reproduces bit-for-bit. The explicit
 * `cents == 0.0` branch is what stops a floored negative returning **−0.0**
 * (`-1 * 0.0 / 100 == -0.0`); JavaScript's `Object.is(floorCents(-0.005), 0)`
 * holds for the same reason.
 */
fun taxFloorCents(amountEur: Double): Double {
    if (!amountEur.isFinite()) {
        throw TaxComputationError(
            "Cannot floor a non-finite EUR amount, got ${jsNum(amountEur)}.",
        )
    }
    val sign = if (amountEur < 0) -1 else 1
    val cents = floor(abs(amountEur) * 100 * (1 + NUMBER_EPSILON * 8))
    return if (cents == 0.0) 0.0 else (sign * cents) / 100
}

// ---------------------------------------------------------------------------
// Vienna calendar year
// ---------------------------------------------------------------------------

/**
 * One shared zone: the TypeScript hoists a single `Intl.DateTimeFormat` out of the
 * function for the same reason (constructing one per call is costly), and the
 * mapping (UTC instant → Vienna year) is deterministic — no clock.
 */
private val VIENNA_ZONE: ZoneId = ZoneId.of(TAX_YEAR_TIME_ZONE)

/**
 * The Europe/Vienna calendar year a timestamp falls in (§16 2026-07-08) — the
 * tax-year bucket of a trade/dividend. Deterministic; unparseable input fails loud
 * with [TaxComputationError].
 *
 * §3.3 rule 8: parsing goes through [jsDateParse], the shared `Date.parse` shim,
 * so an unparseable string yields `NaN` and throws exactly where the TypeScript
 * throws. The zone lookup replaces `Intl.DateTimeFormat(…, { year: 'numeric' })`;
 * `ZonedDateTime.year` is an `Int`, which makes the TypeScript's second guard
 * (`!Number.isFinite(year)`, reachable only if `Intl` formatted something
 * non-numeric such as a BC era) unreachable here. It is retained as a comment
 * rather than as dead code.
 */
fun viennaYearOf(isoTimestamp: String): Int {
    val ms = jsDateParse(isoTimestamp)
    if (ms.isNaN()) {
        throw TaxComputationError(
            "Timestamp must be ISO-8601 date/time, got $isoTimestamp",
        )
    }
    // `Number(viennaYearFormatter.format(ms))`; always finite here — see the KDoc.
    return Instant.ofEpochMilli(ms.toLong()).atZone(VIENNA_ZONE).year
}

// ---------------------------------------------------------------------------
// EUR moving-average / FIFO cost basis & per-sell realizations
// ---------------------------------------------------------------------------

/**
 * One transaction pre-converted to EUR at its **own trade-date** FX rate (§5.4
 * historical rates — the caller converts; the domain never sees FX). [priceEur] is
 * per unit, [feeEur] the total fee.
 */
data class TaxableTransaction(
    /** Row id, so realizations can be joined back to their sells. */
    val id: String,
    val assetId: String,
    /**
     * `"buy"` or `"sell"`. §3.3 rule 1: a `String`, because the engine has an
     * explicit "Unknown transaction side" throw that an enum could not reach.
     */
    val side: String,
    /** Units transacted; strictly positive. */
    val quantity: Double,
    /** Price per unit in EUR at the trade date; non-negative. */
    val priceEur: Double,
    /** Total fee in EUR at the trade date; non-negative. */
    val feeEur: Double,
    /** ISO-8601 timestamp; orders the replay and buckets the tax year. */
    val executedAt: String,
    /**
     * Uncovered sell (issue #369). When true, a SELL exceeding the held quantity
     * is permitted instead of throwing: the covered shares realize against the
     * running basis, the uncovered remainder against [uncoveredEntryPriceEur] (or
     * the sale price when absent → 0 realized on that portion), and the position
     * closes at 0. `null` → the strict behavior (an oversell throws). Ignored on
     * buys / covered sells.
     *
     * `null` here is the TypeScript's `undefined`; every read site tests
     * `!= true`, exactly as the TypeScript tests `!t.allowUncovered`.
     */
    val allowUncovered: Boolean? = null,
    /**
     * EUR per-unit basis for the uncovered portion of an [allowUncovered] SELL
     * (issue #369). `null`/absent → the uncovered shares take the sale price as
     * their basis, so they book **no gain** — the AT ledger never taxes a phantom
     * acquisition.
     */
    val uncoveredEntryPriceEur: Double? = null,
)

/** The EUR outcome of one SELL against the running cost basis. */
data class SellRealizationEur(
    /** The sell transaction's id. */
    val id: String,
    val assetId: String,
    val executedAt: String,
    val quantity: Double,
    /** `quantity · priceEur − feeEur`: net proceeds, EUR. */
    val proceedsEur: Double,
    /**
     * The released cost basis, EUR. For a covered sell this is the strategy basis
     * of the sold units; for an uncovered sell (issue #369) it is
     * `covered · strategyBasis + uncovered · uncoveredBasis`, so the uncovered
     * shares are basised at their supplied entry price — or the sale price (→ they
     * add exactly their own proceeds, contributing 0 gain).
     */
    val costBasisEur: Double,
    /** `proceedsEur − costBasisEur`, EUR (signed). */
    val realizedPnlEur: Double,
    /**
     * Units of this SELL sold without a real, registered-buy basis (issue #369); 0
     * for a normal covered sell — and 0 for a waived storage-drift shortfall
     * (#917), whose dust had real recorded acquisitions.
     */
    val uncoveredQuantity: Double,
)

private fun assertFiniteNonNegative(value: Double, label: String, id: String) {
    if (!value.isFinite() || value < 0) {
        throw TaxComputationError(
            "$label must be a finite non-negative number, got ${jsNum(value)} (transaction $id).",
        )
    }
}

/** Epoch-ms of `executedAt`; unparseable input fails loud. */
private fun executedAtToMs(executedAt: String, id: String): Double {
    val ms = jsDateParse(executedAt)
    if (ms.isNaN()) {
        throw TaxComputationError(
            "Transaction executedAt must be ISO-8601, got $executedAt (transaction $id).",
        )
    }
    return ms
}

/**
 * One FIFO tax lot (§20 Abs. 4 Satz 7 EStG): units still held from one BUY,
 * basised at the buy's per-unit price plus its pro-rated fee
 * (Anschaffungsnebenkosten capitalise into the lot, mirroring how the buy fee
 * enters the moving average).
 *
 * `units` is a `var`: [consumeFifoLots] mutates the head lot in place, exactly as
 * the TypeScript does.
 */
private class FifoLot(var units: Double, val perUnitEur: Double)

/**
 * Per-asset replay state — one variant per [CostBasisStrategy].
 *
 * §3.3 rule 1: the TypeScript discriminated union becomes a sealed class with the
 * same two shapes. Named `TaxPositionState` because `PositionState` is already
 * taken by the `holdings` port in this package; nothing else about it differs.
 *
 * `driftRows` counts the stored rows feeding the open position (each bounded to
 * one [QTY_STORAGE_QUANTUM] of rounding drift, #917); it resets when the position
 * closes so a clean round trip never widens the next envelope.
 */
private sealed class TaxPositionState {
    var driftRows: Double = 0.0

    class MovingAverage : TaxPositionState() {
        var held: Double = 0.0
        var avg: Double = 0.0
    }

    class Fifo : TaxPositionState() {
        val lots: MutableList<FifoLot> = mutableListOf()
    }
}

/** `lots.reduce((sum, lot) => sum + lot.units, 0)` — left fold, order preserved. */
private fun fifoHeld(lots: List<FifoLot>): Double {
    var sum = 0.0
    for (lot in lots) sum += lot.units
    return sum
}

/**
 * Consume `quantity` units from the front of the lot queue (oldest first, §20 Abs.
 * 4 Satz 7) and return the released cost basis, EUR. The caller has already
 * verified coverage; a shortfall beyond [TAX_QTY_EPSILON] would mean the queue and
 * the covered quantity disagree — fail loud, never fabricate.
 */
private fun consumeFifoLots(lots: MutableList<FifoLot>, quantity: Double, id: String): Double {
    var remaining = quantity
    var releasedEur = 0.0
    while (remaining > TAX_QTY_EPSILON) {
        val lot = lots.firstOrNull()
            ?: throw TaxComputationError(
                "FIFO lot queue exhausted with ${jsNum(remaining)} units unconsumed " +
                    "(transaction $id).",
            )
        val take = min(lot.units, remaining)
        releasedEur += take * lot.perUnitEur
        lot.units -= take
        remaining -= take
        // Drop the lot once fully consumed (float dust included).
        if (lot.units <= TAX_QTY_EPSILON) lots.removeAt(0)
    }
    return releasedEur
}

/** `{ t, index, ms }` of the TypeScript's pre-sort mapping. */
private class OrderedTaxable(val t: TaxableTransaction, val index: Int, val ms: Double)

/**
 * Replay a (multi-asset) EUR transaction log through the chosen cost-basis
 * strategy and return one [SellRealizationEur] per SELL, in chronological order
 * (`executedAt` ascending as epoch-ms — never a string compare — with ties broken
 * by input order, mirroring `holdings.reducePosition`).
 *
 * The default `moving-average` strategy is the AT method: BUY re-averages with the
 * fee capitalised into the basis; SELL realizes against the running average,
 * leaves the average unchanged, and clamps float dust when the position closes.
 * The `fifo` strategy (DE/FI, §20 Abs. 4 Satz 7 EStG) keeps per-buy lots instead.
 * Sell fees reduce proceeds identically under both.
 *
 * A sell exceeding the held quantity beyond [TAX_QTY_EPSILON] throws unless
 * acknowledged as uncovered (#369). One exception (#917): a shortfall within one
 * [QTY_STORAGE_QUANTUM] per contributing stored row is `numeric(20,8)` rounding
 * drift, not an oversell — such a sell closes the position like an exact one, its
 * dust takes the sale price (0 gain) and is not reported as uncovered. The
 * envelope is per-row, never a blanket loosening.
 *
 * Full FP precision throughout — quantize only the derived settlement deltas
 * ([settleAtYear] / [settleDeYear]), never the replay.
 *
 * §3.3 rule 5, the sort: the comparator is `a.ms - b.ms || a.index - b.index`,
 * i.e. numeric on epoch-ms with a stable index tie-break — NOT the string compare
 * a bare `Array.prototype.sort` would do, and not a lexicographic compare of the
 * ISO strings (mixed sub-second precision sorts `'.' < 'Z'`, which would replay
 * sells before the buys that funded them). The mapping runs over EVERY transaction
 * before the sort, so an unparseable timestamp anywhere throws even if the sort
 * would never have compared it.
 */
@JvmOverloads
fun realizedSellsEur(
    transactions: List<TaxableTransaction>,
    strategy: CostBasisStrategy = "moving-average",
): List<SellRealizationEur> {
    val ordered = transactions
        .mapIndexed { index, t -> OrderedTaxable(t, index, executedAtToMs(t.executedAt, t.id)) }
        .sortedWith { a, b ->
            val delta = a.ms - b.ms
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else a.index - b.index
        }

    // §3.3 rule 4: `new Map<string, PositionState>()` — insertion-ordered, keyed by
    // assetId, and only ever get/set. Never iterated, so no accumulation order
    // depends on it; LinkedHashMap matches the semantics regardless.
    val positions = LinkedHashMap<String, TaxPositionState>()
    val realizations = mutableListOf<SellRealizationEur>()
    fun emptyPosition(): TaxPositionState =
        if (strategy == "fifo") TaxPositionState.Fifo() else TaxPositionState.MovingAverage()

    for (entry in ordered) {
        val t = entry.t
        if (!t.quantity.isFinite() || t.quantity <= 0) {
            throw TaxComputationError(
                "Transaction quantity must be a finite positive number, " +
                    "got ${jsNum(t.quantity)} (transaction ${t.id}).",
            )
        }
        assertFiniteNonNegative(t.priceEur, "Transaction priceEur", t.id)
        assertFiniteNonNegative(t.feeEur, "Transaction feeEur", t.id)

        val pos = positions[t.assetId] ?: emptyPosition()
        // Every stored row of the open position — the current one included — can
        // carry up to one quantum of numeric(20,8) rounding drift (#917).
        pos.driftRows += 1

        if (t.side == "buy") {
            if (pos is TaxPositionState.MovingAverage) {
                val newHeld = pos.held + t.quantity
                // newHeld > 0 always (held ≥ 0, quantity > 0), so the division is safe.
                pos.avg = (pos.held * pos.avg + t.quantity * t.priceEur + t.feeEur) / newHeld
                pos.held = newHeld
            } else {
                // The lot's per-unit basis is price plus pro-rated buy fee — total lot
                // cost / units, so a fully consumed lot releases exactly qty·price + fee.
                (pos as TaxPositionState.Fifo).lots.add(
                    FifoLot(
                        units = t.quantity,
                        perUnitEur = (t.quantity * t.priceEur + t.feeEur) / t.quantity,
                    ),
                )
            }
        } else if (t.side == "sell") {
            val heldUnits =
                if (pos is TaxPositionState.MovingAverage) pos.held
                else fifoHeld((pos as TaxPositionState.Fifo).lots)
            val oversell = t.quantity > heldUnits + TAX_QTY_EPSILON
            // Storage-rounding drift (#917): the write path validated the raw values,
            // then numeric(20,8) rounded each row independently — a shortfall within
            // one quantum per contributing stored row is a persistence artifact. It
            // closes the position like an exact sell; beyond the envelope it is a
            // genuine oversell and fails closed.
            val storageDrift =
                oversell &&
                    t.allowUncovered != true &&
                    t.quantity - heldUnits <= pos.driftRows * QTY_STORAGE_QUANTUM + TAX_QTY_EPSILON
            if (oversell && t.allowUncovered != true && !storageDrift) {
                // Not an acknowledged uncovered sell (issue #369): a genuine oversell in
                // the replay means the caller fed an inconsistent log, and a silently
                // wrong basis would poison every tax figure downstream.
                throw TaxComputationError(
                    "Sell of ${jsNum(t.quantity)} exceeds the held ${jsNum(heldUnits)} units " +
                        "of ${t.assetId} (transaction ${t.id}); the transaction log is " +
                        "inconsistent.",
                )
            }
            // Covered shares release the strategy basis; the uncovered remainder is
            // basised at its supplied EUR entry price, or the sale price when none was
            // given (→ 0 gain, no phantom acquisition to tax). No shorts: the position
            // closes at 0 on an uncovered sell.
            val covered = if (oversell) heldUnits else t.quantity
            val uncovered = if (oversell) t.quantity - heldUnits else 0.0
            if (uncovered > 0 && t.uncoveredEntryPriceEur != null) {
                assertFiniteNonNegative(
                    t.uncoveredEntryPriceEur,
                    "Transaction uncoveredEntryPriceEur",
                    t.id,
                )
            }
            // Waived drift always basises its dust at the sale price (0 gain) — it is
            // rounding residue of covered shares, not a phantom acquisition (#917).
            val uncoveredBasisEur =
                if (storageDrift) t.priceEur else (t.uncoveredEntryPriceEur ?: t.priceEur)
            val proceedsEur = t.quantity * t.priceEur - t.feeEur
            val coveredBasisEur =
                if (pos is TaxPositionState.MovingAverage) {
                    covered * pos.avg
                } else if (oversell) {
                    // Full close: release every lot exactly (no dust left behind).
                    var sum = 0.0
                    for (lot in (pos as TaxPositionState.Fifo).lots) sum += lot.units * lot.perUnitEur
                    sum
                } else {
                    consumeFifoLots((pos as TaxPositionState.Fifo).lots, covered, t.id)
                }
            val costBasisEur = coveredBasisEur + uncovered * uncoveredBasisEur
            realizations.add(
                SellRealizationEur(
                    id = t.id,
                    assetId = t.assetId,
                    executedAt = t.executedAt,
                    quantity = t.quantity,
                    proceedsEur = proceedsEur,
                    costBasisEur = costBasisEur,
                    realizedPnlEur = proceedsEur - costBasisEur,
                    // Waived drift is not "basis unknown" — the shares had real recorded
                    // acquisitions; only their stored quantities rounded apart (#917).
                    uncoveredQuantity = if (storageDrift) 0.0 else uncovered,
                ),
            )
            if (pos is TaxPositionState.MovingAverage) {
                if (oversell) {
                    pos.held = 0.0
                    pos.avg = 0.0
                } else {
                    pos.held -= t.quantity
                    // Clamp float dust: selling everything leaves ~±1e-15, not 0.
                    if (abs(pos.held) <= TAX_QTY_EPSILON) {
                        pos.held = 0.0
                        pos.avg = 0.0
                    }
                }
            } else {
                // `else if (oversell || fifoHeld(pos.lots) <= QTY_EPSILON) pos.lots.length = 0`
                // — split into an else + if because Kotlin cannot smart-cast `pos`
                // inside a branch whose condition short-circuits on the cast.
                val fifo = pos as TaxPositionState.Fifo
                if (oversell || fifoHeld(fifo.lots) <= TAX_QTY_EPSILON) fifo.lots.clear()
            }
            // A closed position starts the next round trip clean — including its
            // storage-drift envelope (#917).
            val closed =
                if (pos is TaxPositionState.MovingAverage) pos.held == 0.0
                else (pos as TaxPositionState.Fifo).lots.isEmpty()
            if (closed) pos.driftRows = 0.0
        } else {
            throw TaxComputationError(
                "Unknown transaction side ${t.side} (transaction ${t.id}).",
            )
        }

        positions[t.assetId] = pos
    }

    return realizations
}

// ---------------------------------------------------------------------------
// AT year settlement (flat KESt, same-year offset, hard Jan-1 reset)
// ---------------------------------------------------------------------------

/**
 * The tax a year's AT pool demands: `AT_KEST_RATE · max(0, pool)`, quantized to
 * cents (this *is* a boundary amount — the invariant every settlement steers the
 * held total to). A net-loss year clamps to €0.00: tax held is never negative, and
 * the loss does NOT carry into the next year (hard Jan-1 reset, §16).
 */
fun atYearTargetEur(poolEur: Double): Double {
    if (!poolEur.isFinite()) {
        throw TaxComputationError("Year pool must be a finite EUR amount, got ${jsNum(poolEur)}.")
    }
    return taxFloorCents(AT_KEST_RATE * max(0.0, poolEur))
}

/** One not-yet-recorded AT event entering a year's pool via [settleAtYear]. */
data class NewAtEvent(
    /**
     * `"sell_gain"` contributes a **signed** realized gain/loss; `"dividend"` a
     * strictly positive gross amount. Both in EUR.
     *
     * §3.3 rule 1: a `String` — the vitest suite probes `'bogus' as 'dividend'`.
     */
    val kind: String,
    val amountEur: Double,
)

/** Input of [settleAtYear] — one Vienna year of one portfolio. */
data class AtYearSettlementInput(
    /**
     * **Recomputed** realized gains/losses (EUR, signed) of the year's
     * already-persisted AT-taxed sells — recomputed against the *current*
     * transaction log, so a backdated buy that shifted the moving average is
     * reflected and the settlement self-corrects (§16: append-only re-derivation).
     */
    val existingGainsEur: List<Double>,
    /** Gross EUR amounts of the year's already-persisted AT-taxed dividends. */
    val existingDividendsEur: List<Double>,
    /**
     * Tax currently held for this year, EUR (cent-exact): what the year's
     * withholding movements minus refund movements sum to.
     */
    val heldEur: Double,
    /** New AT events being recorded now, in recording order (possibly empty). */
    val newEvents: List<NewAtEvent>,
)

/** Output of [settleAtYear]: the cent-exact deltas to post as movements. */
data class AtYearSettlementResult(
    /**
     * Delta (EUR, signed: positive = withhold, negative = refund) that brings the
     * already-persisted events' target in line with `heldEur` *before* any new
     * event applies — non-zero only when history was re-shaped (backdated buy,
     * deletion) and posts as an unattached correction movement.
     */
    val correctionDeltaEur: Double,
    /** Marginal delta per new event, in input order (same sign convention). */
    val newEventDeltasEur: List<Double>,
    /** Held after all deltas — always exactly the year's final target. */
    val heldAfterEur: Double,
)

private fun assertFiniteAmount(value: Double, label: String) {
    if (!value.isFinite()) {
        throw TaxComputationError("$label must be a finite EUR amount, got ${jsNum(value)}.")
    }
}

/**
 * Settle one Vienna year of one portfolio under AT mode: compute the cent-exact
 * withholding/refund deltas that keep the year's held tax equal to
 * [atYearTargetEur] of its pool after every event.
 *
 * The pool is `Σ existing gains + Σ existing dividends`, then each new event joins
 * in order and yields its **marginal** delta — so the sequence `+450 gain, −100
 * loss` produces `+123.75` then `−27.50`, landing on `27.5 % × 350 = 96.25` held,
 * while a loss-first year parks at €0.00 held (no negative tax) and later gains
 * are only taxed on the net. Since only ONE year's events ever enter, a February
 * loss can never see November-of-last-year gains: no cross-year carry by
 * construction.
 *
 * `correctionDeltaEur` reconciles drift *before* the new events; all deltas are
 * cent-quantized and `heldAfterEur` is exactly the final target.
 */
fun settleAtYear(input: AtYearSettlementInput): AtYearSettlementResult =
    settlePoolYear(::atYearTargetEur, input)

/**
 * The shared pool-style year settlement (#635): a within-year pool of signed gains
 * + positive dividends, a country-specific `targetOf(pool)` function, and the same
 * delta-steering contract as [settleAtYear] (which is the AT instantiation;
 * [settleFiYear] the FI one). Countries with per-event category state (DE's dual
 * pots) keep their own settle function instead.
 *
 * §3.3 rule 1: the accumulation order is load-bearing — existing gains first (in
 * list order), then existing dividends (in list order), then each new event — so
 * the `poolEur += …` sequence is transcribed exactly rather than replaced by a
 * `sum()`.
 */
private fun settlePoolYear(
    targetOf: (Double) -> Double,
    input: AtYearSettlementInput,
): AtYearSettlementResult {
    assertFiniteAmount(input.heldEur, "heldEur")
    var poolEur = 0.0
    for (gain in input.existingGainsEur) {
        assertFiniteAmount(gain, "Existing realized gain")
        poolEur += gain
    }
    for (dividend in input.existingDividendsEur) {
        assertFiniteAmount(dividend, "Existing dividend")
        if (dividend <= 0) {
            throw TaxComputationError(
                "Existing dividend gross amounts must be strictly positive, " +
                    "got ${jsNum(dividend)}.",
            )
        }
        poolEur += dividend
    }

    val correctionDeltaEur = taxFloorCents(targetOf(poolEur) - input.heldEur)
    var heldEur = taxFloorCents(input.heldEur + correctionDeltaEur)

    val newEventDeltasEur = mutableListOf<Double>()
    for (event in input.newEvents) {
        assertFiniteAmount(event.amountEur, "New event amount")
        if (event.kind == "dividend") {
            if (event.amountEur <= 0) {
                throw TaxComputationError(
                    "Dividend gross amounts must be strictly positive, " +
                        "got ${jsNum(event.amountEur)}.",
                )
            }
        } else if (event.kind != "sell_gain") {
            throw TaxComputationError("Unknown pool event kind ${event.kind}.")
        }
        poolEur += event.amountEur
        val deltaEur = taxFloorCents(targetOf(poolEur) - heldEur)
        newEventDeltasEur.add(deltaEur)
        heldEur = taxFloorCents(heldEur + deltaEur)
    }

    return AtYearSettlementResult(correctionDeltaEur, newEventDeltasEur, heldEur)
}

// ---------------------------------------------------------------------------
// FI year settlement (progressive pääomatulovero, same-year offset) — #635
// ---------------------------------------------------------------------------

/**
 * The tax a year's FI pool demands (TVL 124 §): 30 % of the positive pool up to
 * €30,000 and 34 % of the part above, quantized to cents. A net-loss year clamps
 * to €0.00 — held tax is never negative — and v1 carries no loss into the next
 * year (documented simplification; the tappiontasaus carry can join later the way
 * the DE pots chain).
 */
fun fiYearTargetEur(poolEur: Double): Double {
    if (!poolEur.isFinite()) {
        throw TaxComputationError("Year pool must be a finite EUR amount, got ${jsNum(poolEur)}.")
    }
    val taxableEur = max(0.0, poolEur)
    val baseEur = min(taxableEur, FI_HIGH_RATE_THRESHOLD_EUR)
    val highEur = taxableEur - baseEur
    return taxFloorCents(FI_CAPITAL_INCOME_RATE * baseEur + FI_CAPITAL_INCOME_HIGH_RATE * highEur)
}

/**
 * Settle one Vienna year of one portfolio under FI rules: identical pool semantics
 * to [settleAtYear] (within-year offset, refunds down to €0.00, hard Jan-1 reset)
 * with the progressive [fiYearTargetEur] as the target — a marginal event that
 * pushes the pool across the €30,000 threshold is taxed at 34 % on the excess by
 * construction.
 */
fun settleFiYear(input: AtYearSettlementInput): AtYearSettlementResult =
    settlePoolYear(::fiYearTargetEur, input)

// ---------------------------------------------------------------------------
// DE year settlement (Abgeltungsteuer + Soli, dual loss pots, allowance)
// ---------------------------------------------------------------------------

/**
 * Loss-pot category of a sale under §20 Abs. 6 EStG: `"aktien"` = shares (app
 * asset type `stock`), `"sonstige"` = everything else. Dividends carry no category
 * — they are always Sonstige-side income (§20 Abs. 1 Nr. 1).
 *
 * §3.3 rule 1: a `String` — the vitest suite probes `'weird' as never`.
 */
typealias DePotCategory = String

/** The DE loss pot an app asset type's sale P/L belongs to (#576: `stock` → aktien). */
fun dePotCategoryForAssetType(assetType: String): DePotCategory =
    if (assetType == "stock") "aktien" else "sonstige"

/**
 * One taxable DE event entering a year: a sell's **signed** FIFO realized
 * gain/loss with its pot [DePotCategory], or a strictly positive gross dividend
 * (always Sonstige-side — no category to pick).
 *
 * §3.3 rule 1: the TypeScript is a discriminated union where only the `sell_gain`
 * arm carries `category`. Kotlin gets one class with a nullable `category`; a
 * `null` category on a `sell_gain` renders as `"undefined"` in the error message,
 * which is what `String(undefined)` produces in the TypeScript.
 */
data class DeTaxableEvent(
    val kind: String,
    val amountEur: Double,
    val category: DePotCategory? = null,
)

/** Both DE loss pots, stored positive (a pot holds losses; ≥ 0 by construction). */
data class DePots(val aktienEur: Double, val sonstigeEur: Double)

/**
 * The aggregate inputs of one DE calendar year (field names mirror #576's
 * fixtures).
 *
 * `var` fields: [applyDeEvent] folds events into an aggregate in place, exactly as
 * the TypeScript mutates its object.
 */
data class DeYearAggregates(
    /** Aktien loss pot carried IN from the prior year (≥ 0). */
    var aktienPotInEur: Double,
    /** Sonstige loss pot carried IN from the prior year (≥ 0). */
    var sonstigePotInEur: Double,
    /** Signed Σ of the year's Aktien-sale realized P/L. */
    var aktienSalePnlEur: Double,
    /** Signed Σ of the year's Sonstige-sale realized P/L. */
    var sonstigeSalePnlEur: Double,
    /** Σ of the year's gross dividends (Sonstige-side income; ≥ 0). */
    var dividendsEur: Double,
)

/** The DE year-end state [deYearOutcome] derives from the aggregates. */
data class DeYearOutcome(
    /** Positive income remaining after both pots + the cross-offset. */
    val taxableBeforeAllowanceEur: Double,
    /** Sparer-Pauschbetrag consumed (≤ [DE_SPARER_PAUSCHBETRAG_EUR]). */
    val allowanceUsedEur: Double,
    /** Allowance left unused — lost at year end, never carried (§20 Abs. 9). */
    val allowanceRemainingEur: Double,
    /** `taxableBeforeAllowanceEur − allowanceUsedEur`. */
    val taxableBaseEur: Double,
    /** `floorCents(DE_KAPEST_RATE · taxableBaseEur)` (§32d Abs. 1 EStG). */
    val kapestEur: Double,
    /** `floorCents(DE_SOLI_RATE · kapestEur)` (§4 Satz 2 SolzG — statutory floor). */
    val soliEur: Double,
    /** `kapestEur + soliEur`, cent-exact — the year's held target. */
    val totalTaxEur: Double,
    /** Aktien loss pot carried OUT to the next year (≥ 0). */
    val aktienPotOutEur: Double,
    /** Sonstige loss pot carried OUT to the next year (≥ 0). */
    val sonstigePotOutEur: Double,
)

private fun assertDeAggregates(agg: DeYearAggregates) {
    assertFiniteAmount(agg.aktienSalePnlEur, "Aktien sale P/L")
    assertFiniteAmount(agg.sonstigeSalePnlEur, "Sonstige sale P/L")
    assertFiniteAmount(agg.dividendsEur, "Dividends sum")
    // §3.3 rule 4: the TypeScript walks a two-entry literal array — Aktien pot
    // first, Sonstige second — and the first failure wins, so the order is
    // observable through the error message and is preserved verbatim.
    for ((label, value) in listOf(
        "Aktien pot in" to agg.aktienPotInEur,
        "Sonstige pot in" to agg.sonstigePotInEur,
    )) {
        if (!value.isFinite() || value < 0) {
            throw TaxComputationError(
                "$label must be a finite non-negative EUR amount, got ${jsNum(value)}.",
            )
        }
    }
    if (agg.dividendsEur < 0) {
        throw TaxComputationError(
            "Dividends sum must be non-negative, got ${jsNum(agg.dividendsEur)}.",
        )
    }
}

/**
 * The German year-end function (§16 2026-07-17; the analog of [atYearTargetEur],
 * richer because DE state spans pots and allowance):
 *
 *     aktienRemainder   = Σ Aktien-sale P/L − aktienPotIn
 *     sonstigeRemainder = Σ dividends + Σ other-sale P/L − sonstigePotIn
 *     // one-directional cross-offset (§20 Abs. 6 Satz 4): a NEGATIVE
 *     // Sonstige remainder also offsets a positive Aktien remainder;
 *     // an Aktien loss NEVER offsets Sonstige income (the ring-fence).
 *     taxableBeforeAllowance = remaining positives after that offset
 *     allowanceUsed = min(SPARER_PAUSCHBETRAG, taxableBeforeAllowance)
 *     KapESt = floorCents(0.25 · (taxableBeforeAllowance − allowanceUsed))
 *     Soli   = floorCents(0.055 · KapESt)
 *     target = KapESt + Soli; negative remainders leave as potOut (carry).
 *
 * KapESt cent-flooring is the app's #370 floor-toward-zero money policy; the Soli
 * floor is statutory ("Bruchteile eines Cents bleiben außer Ansatz"). Aggregates
 * stay at full FP precision (§5.4); only the two tax figures — and their sum,
 * re-floored to kill float dust — are quantized.
 */
fun deYearOutcome(agg: DeYearAggregates): DeYearOutcome {
    assertDeAggregates(agg)
    val aktienRemainder = agg.aktienSalePnlEur - agg.aktienPotInEur
    val sonstigeRemainder = agg.dividendsEur + agg.sonstigeSalePnlEur - agg.sonstigePotInEur
    var aktienPositive = max(0.0, aktienRemainder)
    val aktienPotOutEur = max(0.0, -aktienRemainder)
    var sonstigePotOutEur = 0.0
    if (sonstigeRemainder < 0) {
        val crossOffset = min(-sonstigeRemainder, aktienPositive)
        aktienPositive -= crossOffset
        sonstigePotOutEur = -sonstigeRemainder - crossOffset
    }
    val taxableBeforeAllowanceEur = aktienPositive + max(0.0, sonstigeRemainder)
    val allowanceUsedEur = min(DE_SPARER_PAUSCHBETRAG_EUR, taxableBeforeAllowanceEur)
    val taxableBaseEur = taxableBeforeAllowanceEur - allowanceUsedEur
    val kapestEur = taxFloorCents(DE_KAPEST_RATE * taxableBaseEur)
    val soliEur = taxFloorCents(DE_SOLI_RATE * kapestEur)
    return DeYearOutcome(
        taxableBeforeAllowanceEur = taxableBeforeAllowanceEur,
        allowanceUsedEur = allowanceUsedEur,
        allowanceRemainingEur = DE_SPARER_PAUSCHBETRAG_EUR - allowanceUsedEur,
        taxableBaseEur = taxableBaseEur,
        kapestEur = kapestEur,
        soliEur = soliEur,
        // Both addends are cent-exact; re-floor to normalize FP addition dust.
        totalTaxEur = taxFloorCents(kapestEur + soliEur),
        aktienPotOutEur = aktienPotOutEur,
        sonstigePotOutEur = sonstigePotOutEur,
    )
}

private fun assertDeEvent(event: DeTaxableEvent) {
    assertFiniteAmount(event.amountEur, "DE event amount")
    if (event.kind == "dividend") {
        if (event.amountEur <= 0) {
            throw TaxComputationError(
                "Dividend gross amounts must be strictly positive, got ${jsNum(event.amountEur)}.",
            )
        }
    } else if (event.kind == "sell_gain") {
        if (event.category != "aktien" && event.category != "sonstige") {
            // `String(undefined)` is "undefined" in JavaScript — see DeTaxableEvent.
            throw TaxComputationError(
                "Unknown DE pot category ${event.category ?: "undefined"}.",
            )
        }
    } else {
        throw TaxComputationError("Unknown DE event kind ${event.kind}.")
    }
}

/** Fold one event into a year's running aggregates (mutates `agg`). */
private fun applyDeEvent(agg: DeYearAggregates, event: DeTaxableEvent) {
    assertDeEvent(event)
    if (event.kind == "dividend") {
        agg.dividendsEur += event.amountEur
    } else if (event.category == "aktien") {
        agg.aktienSalePnlEur += event.amountEur
    } else {
        agg.sonstigeSalePnlEur += event.amountEur
    }
}

/**
 * Chain the DE loss pots across consecutive prior years (§20 Abs. 6 Sätze 2–3:
 * pots carry indefinitely; the allowance never does): fold each prior year's
 * events — ascending, gap years omitted (an empty year passes pots through
 * unchanged) — and return the pots entering the next year. Pots start at zero
 * before the first DE year by construction.
 *
 * §3.3 rule 4: the years arrive as an ORDERED list, not as a year-keyed object —
 * `List` reproduces the traversal exactly.
 */
fun deCarryPots(priorYearEvents: List<List<DeTaxableEvent>>): DePots {
    var aktienEur = 0.0
    var sonstigeEur = 0.0
    for (events in priorYearEvents) {
        val agg = DeYearAggregates(
            aktienPotInEur = aktienEur,
            sonstigePotInEur = sonstigeEur,
            aktienSalePnlEur = 0.0,
            sonstigeSalePnlEur = 0.0,
            dividendsEur = 0.0,
        )
        for (event in events) applyDeEvent(agg, event)
        val outcome = deYearOutcome(agg)
        aktienEur = outcome.aktienPotOutEur
        sonstigeEur = outcome.sonstigePotOutEur
    }
    return DePots(aktienEur, sonstigeEur)
}

/** Input of [settleDeYear] — one Vienna year of one portfolio under DE. */
data class DeYearSettlementInput(
    /** Aktien loss pot carried in from prior years (≥ 0; [deCarryPots]). */
    val aktienPotInEur: Double,
    /** Sonstige loss pot carried in from prior years (≥ 0). */
    val sonstigePotInEur: Double,
    /**
     * **Recomputed** events of the year's already-persisted DE-taxed rows — sells
     * with their FIFO gains re-derived from the *current* transaction log, plus
     * gross dividends. Order is irrelevant: the year target is a function of the
     * aggregates.
     */
    val existingEvents: List<DeTaxableEvent>,
    /**
     * Tax currently held for this year's **DE component**, EUR (cent-exact): what
     * the year's movements hold minus the AT rows' own target when both countries
     * coexist in the year (§16 cutover — the caller separates the components).
     */
    val heldEur: Double,
    /** New DE events being recorded now, in recording order (possibly empty). */
    val newEvents: List<DeTaxableEvent>,
)

/** Output of [settleDeYear] — same movement semantics as the AT engine. */
data class DeYearSettlementResult(
    /**
     * Delta (EUR, signed: positive = withhold, negative = refund) that brings the
     * already-persisted events' target in line with `heldEur` *before* any new
     * event applies — non-zero only when history was re-shaped.
     */
    val correctionDeltaEur: Double,
    /** Marginal delta per new event, in input order (same sign convention). */
    val newEventDeltasEur: List<Double>,
    /** Held after all deltas — always exactly the year's final DE target. */
    val heldAfterEur: Double,
    /** The year-end state after every event (existing + new) — feeds the report. */
    val yearEnd: DeYearOutcome,
)

/**
 * Settle one Vienna year of one portfolio under DE mode: compute the cent-exact
 * withholding/refund deltas that keep the year's held tax equal to
 * [deYearOutcome]'s target after every event — the same delta-steering as
 * [settleAtYear] (§43a Abs. 3 Satz 2 EStG obliges exactly this), with the pots and
 * the Sparer-Pauschbetrag folded into the target function. Losses park in their
 * pot (held never goes negative); a later same-year loss refunds tax already
 * withheld down to the year's net target; pots carry OUT of a net-loss year
 * instead of resetting (the DE difference to AT's hard Jan-1 reset).
 */
fun settleDeYear(input: DeYearSettlementInput): DeYearSettlementResult {
    assertFiniteAmount(input.heldEur, "heldEur")
    val agg = DeYearAggregates(
        aktienPotInEur = input.aktienPotInEur,
        sonstigePotInEur = input.sonstigePotInEur,
        aktienSalePnlEur = 0.0,
        sonstigeSalePnlEur = 0.0,
        dividendsEur = 0.0,
    )
    for (event in input.existingEvents) applyDeEvent(agg, event)

    val correctionDeltaEur = taxFloorCents(deYearOutcome(agg).totalTaxEur - input.heldEur)
    var heldEur = taxFloorCents(input.heldEur + correctionDeltaEur)

    val newEventDeltasEur = mutableListOf<Double>()
    for (event in input.newEvents) {
        applyDeEvent(agg, event)
        val deltaEur = taxFloorCents(deYearOutcome(agg).totalTaxEur - heldEur)
        newEventDeltasEur.add(deltaEur)
        heldEur = taxFloorCents(heldEur + deltaEur)
    }

    return DeYearSettlementResult(
        correctionDeltaEur = correctionDeltaEur,
        newEventDeltasEur = newEventDeltasEur,
        heldAfterEur = heldEur,
        yearEnd = deYearOutcome(agg),
    )
}

// ---------------------------------------------------------------------------
// Custom rule-built engine (V5-P4c, #584): the parameterized generalization of
// the AT settlement — "if we don't support your tax system, you can enter how it
// works".
// ---------------------------------------------------------------------------

/**
 * The custom engine's parameter set (V5-P4c). Two regimes fall out of `yearReset`:
 *
 *  - **reset on** (the AT shape): each Vienna year has its own pool;
 *    `carryForward` decides whether a year-end net LOSS survives Jan 1 as a pot
 *    that offsets later years (DE-pot-style) or is forfeited (AT).
 *  - **reset off**: ONE cumulative pool spans all years — the year boundary never
 *    clears it, so a loss inherently crosses Jan 1; `carryForward` off
 *    additionally forfeits a *negative* cumulative balance at each year end (gains
 *    always carry — they are already-taxed income the target function must keep
 *    seeing).
 *
 * `lossOffset` off drops losses from the pool entirely (they neither refund nor
 * accrue carry); `refund` off turns the held tax into a ratchet (a shrinking pool
 * never posts a refund — later gains only withhold past the high-water mark). The
 * ratchet gates taxable EVENTS only: history-reshape reconciliation in
 * [settleCustomYear] stays signed (a data correction, not an economic refund).
 */
data class CustomTaxParams(
    /** Flat rate on the positive taxable pool, percent (0–100). */
    val ratePct: Double,
    val lossOffset: Boolean,
    val refund: Boolean,
    val yearReset: Boolean,
    val carryForward: Boolean,
    val costBasis: CostBasisStrategy,
)

/**
 * Austria expressed as a custom parameter set (§13.5 V5-P4c — the required
 * expressibility example): flat 27.5 % with same-year loss offset and refund, a
 * hard Jan-1 reset, no carry, moving-average basis. Pinned by test to reproduce
 * the AT fixtures exactly.
 */
val AT_AS_CUSTOM_PARAMS: CustomTaxParams = CustomTaxParams(
    ratePct = 27.5,
    lossOffset = true,
    refund = true,
    yearReset = true,
    carryForward = false,
    costBasis = "moving-average",
)

/** One taxable event of the custom engine — same shape as [NewAtEvent]. */
data class CustomTaxableEvent(
    /**
     * `"sell_gain"` contributes a **signed** realized gain/loss (under the
     * parameter set's own [CustomTaxParams.costBasis]); `"dividend"` a strictly
     * positive gross amount. Both in EUR.
     */
    val kind: String,
    val amountEur: Double,
)

/**
 * The state one custom parameter set hands across a year boundary. Which fields
 * are live depends on the regime: `potEur` (≥ 0) is the reset-on loss pot
 * ([CustomTaxParams.carryForward]); the `cumulative*` pair is the reset-off ledger
 * — the signed all-years pool and the tax already attributed to prior years.
 * Unused fields stay 0.
 */
data class CustomCarry(
    val potEur: Double,
    val cumulativePoolEur: Double,
    val cumulativeHeldEur: Double,
)

/** The empty carry — the state before a parameter set's first year. */
fun initialCustomCarry(): CustomCarry = CustomCarry(0.0, 0.0, 0.0)

private fun assertCustomParams(params: CustomTaxParams) {
    if (!params.ratePct.isFinite() || params.ratePct < 0 || params.ratePct > 100) {
        throw TaxComputationError(
            "Custom tax rate must be between 0 and 100, got ${jsNum(params.ratePct)}.",
        )
    }
    // §3.3 note: the TypeScript's `typeof params[flag] !== 'boolean'` loop over
    // ['lossOffset','refund','yearReset','carryForward'] is UNREACHABLE in Kotlin —
    // the four fields are `Boolean` by type, so no caller can supply a non-boolean.
    // Kept as documentation rather than as dead code.
    if (!COST_BASIS_STRATEGIES.contains(params.costBasis)) {
        throw TaxComputationError("Unknown cost-basis strategy ${params.costBasis}.")
    }
}

private fun assertCustomCarry(carry: CustomCarry) {
    // §3.3 rule 4: the TypeScript walks a three-entry literal array in this order
    // and the first failure wins — the order is observable in the error message.
    for ((label, value) in listOf(
        "Carry pot" to carry.potEur,
        "Cumulative pool" to carry.cumulativePoolEur,
        "Cumulative held" to carry.cumulativeHeldEur,
    )) {
        assertFiniteAmount(value, label)
    }
    if (carry.potEur < 0) {
        throw TaxComputationError("Carry pot must be non-negative, got ${jsNum(carry.potEur)}.")
    }
}

/**
 * The pool contribution of one event: a dividend's gross (validated strictly
 * positive), a sell's signed gain — or 0 for a loss when `lossOffset` is off (the
 * loss is ignored entirely: no refund, no pot accrual).
 */
private fun customEventAmount(params: CustomTaxParams, event: CustomTaxableEvent): Double {
    assertFiniteAmount(event.amountEur, "Custom event amount")
    if (event.kind == "dividend") {
        if (event.amountEur <= 0) {
            throw TaxComputationError(
                "Dividend gross amounts must be strictly positive, got ${jsNum(event.amountEur)}.",
            )
        }
        return event.amountEur
    }
    if (event.kind != "sell_gain") {
        throw TaxComputationError("Unknown custom event kind ${event.kind}.")
    }
    return if (params.lossOffset) event.amountEur else max(0.0, event.amountEur)
}

/**
 * One year of one parameter set as a sequential replay. Events fold in
 * CHRONOLOGICAL order because with `refund` off the year's held target is
 * path-dependent (a taxed gain followed by a loss ratchets; the aggregate would
 * not) — with refund on the fold lands on the aggregate target, so the order is
 * then irrelevant, matching [settleAtYear] exactly.
 */
private class CustomYearFold(
    /** The year's running pool (reset-on: this year only; reset-off: cumulative). */
    var poolEur: Double,
    /** What the year should hold after the folded events (its own component, signed). */
    var heldTargetEur: Double,
)

/** Fold `events` into the year, continuing from `fold` (mutates and returns it). */
private fun foldCustomEvents(
    params: CustomTaxParams,
    carry: CustomCarry,
    fold: CustomYearFold,
    events: List<CustomTaxableEvent>,
): CustomYearFold {
    val rate = params.ratePct / 100
    val potIn = if (params.yearReset && params.carryForward) carry.potEur else 0.0
    for (event in events) {
        fold.poolEur += customEventAmount(params, event)
        // The target the year's held steers to after this event: reset-on years
        // tax their own pool net of the pot; reset-off years own the cumulative
        // target minus what prior years already hold (signed — a shrunk cumulative
        // pool can demand a refund of prior years' tax).
        val targetEur =
            if (params.yearReset) {
                taxFloorCents(rate * max(0.0, fold.poolEur - potIn))
            } else {
                taxFloorCents(
                    taxFloorCents(rate * max(0.0, fold.poolEur)) - carry.cumulativeHeldEur,
                )
            }
        var deltaEur = taxFloorCents(targetEur - fold.heldTargetEur)
        // Refund off: held only ever ratchets up — a negative delta posts nothing.
        if (!params.refund && deltaEur < 0) deltaEur = 0.0
        fold.heldTargetEur = taxFloorCents(fold.heldTargetEur + deltaEur)
    }
    return fold
}

/** The fold at a year's start: the carried-in pool, nothing held yet. */
private fun startCustomFold(params: CustomTaxParams, carry: CustomCarry): CustomYearFold =
    CustomYearFold(
        poolEur = if (params.yearReset) 0.0 else carry.cumulativePoolEur,
        heldTargetEur = 0.0,
    )

/** The carry a finished year hands to the next (from its final fold state). */
private fun customCarryOut(
    params: CustomTaxParams,
    carry: CustomCarry,
    fold: CustomYearFold,
): CustomCarry {
    if (params.yearReset) {
        val potIn = if (params.carryForward) carry.potEur else 0.0
        // A net-negative remainder becomes (or passes through as) the pot.
        val potOut = if (params.carryForward) max(0.0, potIn - fold.poolEur) else 0.0
        return CustomCarry(potEur = potOut, cumulativePoolEur = 0.0, cumulativeHeldEur = 0.0)
    }
    return CustomCarry(
        potEur = 0.0,
        // Carry-forward off forfeits a NEGATIVE cumulative balance at the year
        // boundary; a positive pool always carries (it is already-taxed income).
        cumulativePoolEur = if (params.carryForward) fold.poolEur else max(0.0, fold.poolEur),
        cumulativeHeldEur = taxFloorCents(carry.cumulativeHeldEur + fold.heldTargetEur),
    )
}

/** The outcome of one closed year of one parameter set. */
data class CustomYearOutcome(
    /**
     * What the year should hold after all its events (signed: a reset-off year
     * whose loss shrank the cumulative pool holds a NET REFUND of prior years'
     * tax). This is the year's component of a portfolio-year's held target.
     */
    val targetEur: Double,
    /** The state handed to the next year. */
    val carryOut: CustomCarry,
)

/**
 * Derive one year's outcome (held target + carry-out) from its chronological
 * events under one parameter set — the custom analog of [atYearTargetEur] /
 * [deYearOutcome], path-dependent when `refund` is off.
 */
fun customYearOutcome(
    params: CustomTaxParams,
    carry: CustomCarry,
    events: List<CustomTaxableEvent>,
): CustomYearOutcome {
    assertCustomParams(params)
    assertCustomCarry(carry)
    val fold = foldCustomEvents(params, carry, startCustomFold(params, carry), events)
    return CustomYearOutcome(
        targetEur = fold.heldTargetEur,
        carryOut = customCarryOut(params, carry, fold),
    )
}

/**
 * Chain the carry state across consecutive prior years (ascending; gap years may
 * be omitted — an event-less year passes the pot/pool through unchanged). The
 * custom analog of [deCarryPots].
 */
fun customCarryForYears(
    params: CustomTaxParams,
    priorYearEvents: List<List<CustomTaxableEvent>>,
): CustomCarry {
    var carry = initialCustomCarry()
    for (events in priorYearEvents) {
        carry = customYearOutcome(params, carry, events).carryOut
    }
    return carry
}

/** Input of [settleCustomYear] — one Vienna year of one parameter set. */
data class CustomYearSettlementInput(
    val params: CustomTaxParams,
    /** The state entering this year ([customCarryForYears] over prior years). */
    val carry: CustomCarry,
    /**
     * The year's already-persisted events of THIS parameter set, chronological,
     * gains recomputed against the *current* transaction log under the set's own
     * cost basis (§16: append-only re-derivation).
     */
    val existingEvents: List<CustomTaxableEvent>,
    /**
     * Tax currently held for this year's component of THIS parameter set, EUR
     * (cent-exact): the caller separates coexisting regimes.
     */
    val heldEur: Double,
    /** New events being recorded now, in recording order (possibly empty). */
    val newEvents: List<CustomTaxableEvent>,
)

/** Output of [settleCustomYear] — same movement semantics as AT/DE. */
data class CustomYearSettlementResult(
    /**
     * Delta (EUR, signed: positive = withhold, negative = refund) that brings the
     * already-persisted events' target in line with `heldEur` *before* any new
     * event applies. The `refund` ratchet does NOT gate this delta (§16): a
     * history reshape is a data correction, not an economic refund.
     */
    val correctionDeltaEur: Double,
    /** Marginal delta per new event, in input order (same sign convention). */
    val newEventDeltasEur: List<Double>,
    /** Held after all deltas — the year's final component target. */
    val heldAfterEur: Double,
    /** The state handed to the next year after every event (existing + new). */
    val carryOut: CustomCarry,
)

/**
 * Settle one Vienna year of one parameter set: the cent-exact deltas that keep the
 * year's held component equal to the parameter set's target after every event —
 * [settleAtYear]'s delta-steering generalized to the custom rulebook. With
 * [AT_AS_CUSTOM_PARAMS] this reproduces the AT engine output for output (pinned by
 * test — the issue's required expressibility proof).
 */
fun settleCustomYear(input: CustomYearSettlementInput): CustomYearSettlementResult {
    assertCustomParams(input.params)
    assertCustomCarry(input.carry)
    assertFiniteAmount(input.heldEur, "heldEur")

    // Replay the persisted events to the year's pre-batch target, then reconcile
    // drift (history reshaped by a backdated buy / a deletion) against what is
    // actually held. The `refund` ratchet deliberately does NOT gate this
    // reconciliation (§16): the ratchet is the regime's economic rule for the
    // chronological event flow, while a reshape is a data correction — clamping
    // here would strand an excess no row represents.
    val fold = foldCustomEvents(
        input.params,
        input.carry,
        startCustomFold(input.params, input.carry),
        input.existingEvents,
    )
    val correctionDeltaEur = taxFloorCents(fold.heldTargetEur - input.heldEur)
    var heldEur = taxFloorCents(input.heldEur + correctionDeltaEur)

    // New events continue the same fold; held now sits exactly on the replay
    // target, so these deltas replicate foldCustomEvents' recursion (per-event
    // ratchet included) while attributing a marginal delta to each.
    val rate = input.params.ratePct / 100
    val potIn =
        if (input.params.yearReset && input.params.carryForward) input.carry.potEur else 0.0
    val newEventDeltasEur = mutableListOf<Double>()
    for (event in input.newEvents) {
        fold.poolEur += customEventAmount(input.params, event)
        val targetEur =
            if (input.params.yearReset) {
                taxFloorCents(rate * max(0.0, fold.poolEur - potIn))
            } else {
                taxFloorCents(
                    taxFloorCents(rate * max(0.0, fold.poolEur)) - input.carry.cumulativeHeldEur,
                )
            }
        var deltaEur = taxFloorCents(targetEur - heldEur)
        if (!input.params.refund && deltaEur < 0) deltaEur = 0.0
        newEventDeltasEur.add(deltaEur)
        heldEur = taxFloorCents(heldEur + deltaEur)
    }

    // The carry-out derives from the full fold with the year's FINAL held as its
    // component (the ratchet-aware value, so a reset-off chain attributes what
    // this year actually holds).
    fold.heldTargetEur = heldEur
    return CustomYearSettlementResult(
        correctionDeltaEur = correctionDeltaEur,
        newEventDeltasEur = newEventDeltasEur,
        heldAfterEur = heldEur,
        carryOut = customCarryOut(input.params, input.carry, fold),
    )
}

// ---------------------------------------------------------------------------
// Settlement delta → movement mapping
// ---------------------------------------------------------------------------

/** The two cash-movement kinds tax settlements post (§13.3 V3-P4b). */
typealias TaxMovementKind = String

/** A settlement delta expressed as the signed movement it must post. */
data class TaxMovementSpec(
    val kind: TaxMovementKind,
    /** Signed EUR amount per the ledger convention: withholding < 0, refund > 0. */
    val amountEur: Double,
)

/**
 * Map a settlement delta to the cash movement that posts it: a positive delta
 * (more tax due) is a `tax_withholding` carrying `−delta`, a negative one a
 * `tax_refund` carrying `+|delta|`, and a zero delta posts nothing (`null`). The
 * delta must already be cent-quantized (it always is — every delta above passes
 * through [taxFloorCents]).
 *
 * §3.3 rule 3: `deltaEur == 0.0` is true for `-0.0` too, exactly as JavaScript's
 * `deltaEur === 0` is — so a negative-zero delta posts nothing rather than a
 * zero-amount refund.
 */
fun taxMovementForDelta(deltaEur: Double): TaxMovementSpec? {
    assertFiniteAmount(deltaEur, "Settlement delta")
    if (deltaEur == 0.0) return null
    return if (deltaEur > 0) {
        TaxMovementSpec("tax_withholding", -deltaEur)
    } else {
        TaxMovementSpec("tax_refund", -deltaEur)
    }
}

// ---------------------------------------------------------------------------
// Manual per-trade tax (zero automation)
// ---------------------------------------------------------------------------

/** Input of [manualTaxEur]: at most one of amount / rate. */
data class ManualTaxInput(
    /**
     * The base a percentage applies to: the sell's realized gain (signed) or a
     * dividend's gross amount. Clamped at 0 for the percentage — a loss sell with
     * a rate entry records €0.00 tax, never a negative one.
     */
    val baseEur: Double,
    /** Absolute tax in EUR (≥ 0), as the user entered it. */
    val taxAmountEur: Double? = null,
    /** Percentage (0–100) applied to [baseEur]. */
    val taxRatePct: Double? = null,
)

/**
 * The manual-mode tax for one sell/dividend (§13.3 V3-P4b: "optional tax
 * amount-or-% entry, recorded + reported, zero automation"): the entered amount
 * as-is, or `pct · max(0, base) / 100`, cent-quantized — or `null` when the user
 * entered nothing (no tax recorded, no movement). Entering both an amount and a
 * rate, a negative amount, or a rate outside 0–100 fails loud.
 *
 * §3.3 rule 1: the TypeScript distinguishes `undefined` from `null` in the
 * `hasAmount` / `hasRate` predicates only to reject both spellings of "absent";
 * Kotlin's single `null` covers both, and the `!= null` tests are the literal
 * equivalent.
 */
fun manualTaxEur(input: ManualTaxInput): Double? {
    val hasAmount = input.taxAmountEur != null
    val hasRate = input.taxRatePct != null
    if (hasAmount && hasRate) {
        throw TaxComputationError("Provide a manual tax amount OR a rate, not both.")
    }
    if (!hasAmount && !hasRate) return null
    assertFiniteAmount(input.baseEur, "Manual tax base")
    if (hasAmount) {
        val amount = input.taxAmountEur!!
        if (!amount.isFinite() || amount < 0) {
            throw TaxComputationError(
                "Manual tax amount must be a finite non-negative EUR amount, " +
                    "got ${jsNum(amount)}.",
            )
        }
        return taxFloorCents(amount)
    }
    val rate = input.taxRatePct!!
    if (!rate.isFinite() || rate < 0 || rate > 100) {
        throw TaxComputationError(
            "Manual tax rate must be between 0 and 100, got ${jsNum(rate)}.",
        )
    }
    return taxFloorCents((rate / 100) * max(0.0, input.baseEur))
}
