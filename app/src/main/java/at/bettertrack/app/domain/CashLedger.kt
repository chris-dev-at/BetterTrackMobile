package at.bettertrack.app.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sign

/**
 * Portfolio cash ledger ("Bargeld") — a **literal** Kotlin port of
 * `packages/domain/src/cashLedger.ts` at the commit pinned in
 * `tools/domain-vectors/PINNED_AT`.
 *
 * A **pure** money-math engine for the per-portfolio cash balance. Like the rest
 * of this package it has no DB, no HTTP, no clock and no Android dependency; the
 * only thing it borrows is [ValuePoint] / [FlowPoint] from `Holdings.kt`, so the
 * external-flow series composes directly with [timeWeightedReturn].
 *
 * **Cash sources.** The single ledger is **Main** plus named sibling sources:
 * every movement belongs to one source, the portfolio ledger is the union of all
 * sources' movements (so every roll-up here keeps working on the union), solvency
 * is checked **per source** ([projectCashLedgerBySource]), transfers between
 * sources are paired `transfer_out`/`transfer_in` legs that cancel to zero in
 * every sum and are never TWR flows ([pairedTransferMovements]), and
 * "set balance to X" reduces to a normal deposit/withdrawal carrying the computed
 * delta ([setBalanceMovement]).
 *
 * **Data model.** A movement is `kind + signed EUR amount + ISO-8601 timestamp`.
 * The sign is part of the data (not derived): inflow kinds carry a strictly
 * positive `amountEur`, outflow kinds a strictly negative one, and a sign/kind
 * mismatch or a zero amount fails loud with [CashLedgerError] — the ledger never
 * guesses a direction. With that invariant, **current cash = sum of signed
 * movements**, and [cashBalance] is literally that sum (in input order, full FP
 * precision — display rounding lives in the display layer).
 *
 * **No silent negative balances.** [applyCashMovement] is the single admission
 * gate: it returns the balance after a movement or throws the typed
 * [InsufficientCashError]. [projectCashLedger] replays a whole history
 * chronologically through that same gate. Balances within [CASH_EPSILON] of zero
 * count as zero, so FP dust from decimal EUR amounts (0.1 + 0.2 − 0.3) never
 * fabricates an insufficient-cash rejection; the check is a tolerance only —
 * amounts are never rounded or clamped mid-computation.
 *
 * **TWR integrity.** Buying from cash is **not** a new external cash flow: money
 * already inside the portfolio merely changed form (cash → shares).
 * [externalCashFlowsForTwr] is the authoritative classifier and returns **only**
 * `deposit` / `withdrawal` movements, in the exact [FlowPoint] shape
 * [timeWeightedReturn] consumes. [netWorthSeries] builds the value series that
 * must be fed alongside them (holdings value + end-of-day cash).
 *
 * Translation notes (plan §3.3) are inlined at every point the Kotlin had to
 * differ; each carries its rule number.
 */

// ---------------------------------------------------------------------------
// Movement kinds & constants
// ---------------------------------------------------------------------------

/**
 * The movement-kind discriminator.
 *
 * §3.3 rule 1: the TypeScript declares this as the string-literal union
 * `(typeof CASH_MOVEMENT_KINDS)[number]`, and [assertValidMovement] has a live,
 * vector-covered error path for a kind that is **not** a member (the vitest suite
 * probes it with `'jackpot' as CashMovementKind`). A Kotlin `enum` cannot express
 * that input at all, so the kind stays a `String` and membership is checked
 * exactly as the TypeScript checks it — against [CASH_MOVEMENT_KINDS].
 */
typealias CashMovementKind = String

/**
 * Every cash-movement kind, external and internal. `transfer_out` /
 * `transfer_in` are the paired legs of an internal transfer between two cash
 * sources of the same portfolio — see [pairedTransferMovements]. `dividend` /
 * `tax_withholding` / `tax_refund` are the tax engine's postings, all internal
 * for TWR purposes. `fee` is a standing cost of *holding* the portfolio —
 * custody/account/platform fees — also internal, for the reason spelled out on
 * [EXTERNAL_CASH_MOVEMENT_KINDS].
 *
 * Declaration order is contract (§3.3 rule 4): it is what the "expected one of …"
 * message enumerates.
 */
val CASH_MOVEMENT_KINDS: List<CashMovementKind> = listOf(
    "deposit",
    "withdrawal",
    "buy",
    "sell_proceeds",
    "transfer_out",
    "transfer_in",
    "dividend",
    "tax_withholding",
    "tax_refund",
    "fee",
)

/**
 * Required sign of `amountEur` per kind: inflows (`deposit`, `sell_proceeds`,
 * `transfer_in`, `dividend`, `tax_refund`) are strictly positive, outflows
 * (`withdrawal`, `buy`, `transfer_out`, `tax_withholding`, `fee`) strictly
 * negative. A `fee` is money **leaving** the portfolio's cash, so it is never
 * positive — "a fee that pays you" is not a fee, and admitting one would let a
 * mistyped sign silently *lift* the performance curve.
 *
 * A `LinkedHashMap` (§3.3 rule 4): the TypeScript object literal is
 * insertion-ordered, none of its keys are integer-like, and the order is
 * observable through the generated vectors.
 */
val CASH_MOVEMENT_SIGN: Map<CashMovementKind, Int> = linkedMapOf(
    "deposit" to 1,
    "sell_proceeds" to 1,
    "transfer_in" to 1,
    "dividend" to 1,
    "tax_refund" to 1,
    "withdrawal" to -1,
    "buy" to -1,
    "transfer_out" to -1,
    "tax_withholding" to -1,
    "fee" to -1,
)

/**
 * The kinds that are **external** flows for TWR purposes: money crossing the
 * portfolio boundary. `buy` / `sell_proceeds` are internal (cash ↔ shares form
 * change) and deliberately absent — as are `transfer_out` / `transfer_in`: a
 * transfer moves money between two sources *inside* the portfolio, so it is NEVER
 * an external flow. The tax kinds are internal too: a `dividend` is income the
 * portfolio's assets *generated* — counting it as a deposit would neutralize it
 * out of the performance curve and understate the true return — and
 * `tax_withholding` / `tax_refund` are costs of holding the portfolio, kept
 * inside the curve so performance reads net of taxes, exactly as it already reads
 * net of fees.
 *
 * **`fee` is internal, and that is the whole point of the kind.** A custody /
 * account / platform fee is a **cost of holding**, not money the owner chose to
 * take out: the portfolio is worth less afterwards *because of what it costs to
 * run*, so the fee must **drag** the return. Classifying it external — which is
 * all a `withdrawal` could ever have done — would divide it back out of the curve
 * and report a portfolio that silently eats 0.5 % a year as if it did not.
 */
val EXTERNAL_CASH_MOVEMENT_KINDS: List<CashMovementKind> = listOf("deposit", "withdrawal")

/**
 * EUR comparison tolerance for the non-negativity gate (mirrors `holdings`'
 * [VALUE_EPSILON]): a balance within this of zero is FP dust from decimal EUR
 * arithmetic, not a real overdraft. Used only for the *comparison* — balances
 * themselves are never rounded or clamped.
 *
 * Contract constant — copied, never re-derived (plan §3.3 rule 7).
 */
const val CASH_EPSILON: Double = 1e-9

/** The number of decimal places real money is denominated in (cents). */
const val CASH_DECIMALS: Int = 2

/**
 * JavaScript's `Number.EPSILON` — the gap between 1 and the next representable
 * double. Reproduced as its exact literal rather than re-derived (§3.3 rule 7),
 * because it is a *contract* input to [floorCents]' boundary nudge.
 */
private const val NUMBER_EPSILON: Double = 2.220446049250313e-16

/**
 * Quantize a EUR amount **down** to whole cents — the money-rounding policy.
 *
 * Cash is **real money** — it exists only in whole cents. The pure engine above
 * sums at full FP precision, but sub-cent residue must never survive to a
 * *stored* movement or a *reported* balance.
 *
 * **Floor, never round up.** Every cash amount floors toward zero to 2 decimals;
 * it is never rounded up. Flooring is the *conservative* direction: the amount
 * deducted is always ≤ the true product, so a live-price cost carrying many
 * decimals can never exceed the balance and cash never goes unexpectedly
 * negative. A balance of `100.006 €` reports as `100,00 €`, which *can* be
 * withdrawn in full — whereas rounding it up to `100,01 €` produces a withdrawal
 * that overdraws the true `100.006`, stranding the reported cent. This makes
 * "max / spend-all" flows exact.
 *
 * Applies to money **amounts** only — never to per-share prices, share
 * quantities, percentages or FX rates. It truncates the *magnitude* toward zero,
 * so a `buy`'s negative amount shrinks too (`−100.006 → −100.00`: deduct less,
 * never more).
 *
 * A value sitting a few ULPs below a cent boundary because its decimal literal
 * isn't representable (`8.61 → 860.999…9`) is nudged back onto the boundary
 * before truncating, so exact cents survive FP error while a genuine sub-cent
 * residue (`100.006 → 10000.6`) still floors away.
 *
 * **§3.3 rule 3 (the rounding trap).** There is deliberately **no** `Math.round`
 * here — the whole policy is `Math.floor` of a non-negative magnitude, which
 * `kotlin.math.floor` reproduces bit-for-bit (both are IEEE-754 `roundToIntegral`
 * toward −∞, and the argument is already `≥ 0` because of the `abs`). The sign is
 * re-applied afterwards, and the explicit `cents == 0.0` branch is what keeps a
 * floored negative from returning **−0.0** (`-1 * 0.0 / 100 == -0.0`); the vitest
 * suite pins that with `Object.is(floorCents(-0.005), 0)`. This is also why
 * `floorCents` is NOT shared with `tax.ts`'s same-named helper (plan §3.3 rule 3):
 * that one is a different function and gets its own port.
 */
fun floorCents(amountEur: Double): Double {
    if (!amountEur.isFinite()) {
        throw CashLedgerError("Cannot floor a non-finite EUR amount, got ${jsNum(amountEur)}.")
    }
    val sign = if (amountEur < 0) -1 else 1
    val cents = floor(abs(amountEur) * 100 * (1 + NUMBER_EPSILON * 8))
    return if (cents == 0.0) 0.0 else (sign * cents) / 100
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * One cash movement: kind + signed EUR amount + when it happened.
 *
 * §3.3 rule 1: `SourcedCashMovement extends CashMovement` in the TypeScript, and
 * a sourced movement is passed wherever a plain one is expected (the portfolio
 * ledger is the union of its sources). An `open class` + subclass reproduces that
 * substitutability with the original two names; a Kotlin `data class` cannot be
 * subclassed.
 */
open class CashMovement(
    val kind: CashMovementKind,
    /**
     * Signed EUR amount, full precision: strictly positive for `deposit` /
     * `sell_proceeds`, strictly negative for `withdrawal` / `buy` (see
     * [CASH_MOVEMENT_SIGN]). Never zero.
     */
    val amountEur: Double,
    /** ISO-8601 timestamp of the movement; unparseable input fails loud. */
    val occurredAt: String,
)

/** A cash movement attributed to the source it belongs to. */
class SourcedCashMovement(
    kind: CashMovementKind,
    amountEur: Double,
    occurredAt: String,
    /** The owning cash source's id. Never empty. */
    val sourceId: String,
) : CashMovement(kind, amountEur, occurredAt)

/** One step of a [projectCashLedger] replay. */
class CashLedgerEntry(
    val movement: CashMovement,
    /** Running balance in EUR **after** applying `movement`. */
    val balanceEur: Double,
)

/**
 * End-of-day cash balance, for composing with daily value series.
 *
 * §3.3 rule 1: `balanceEur` is a `var` because [cashBalanceOverTime] builds the
 * point and then overwrites it in place when a later movement lands on the same
 * day — exactly what the TypeScript does (`last.balanceEur = entry.balanceEur`).
 */
data class CashBalancePoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /** Balance after the day's last movement, EUR. */
    var balanceEur: Double,
)

/** One day's end-of-day balance per cash source. */
data class CashBySourcePoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /**
     * EOD balance per source id, full FP precision (quantize with [floorCents] at
     * the display boundary). Sources with no movement on or before the day are
     * absent, not 0 — callers supply zeroes for freshly-created sources exactly
     * like [cashBalancesBySource]. Insertion-ordered (§3.3 rule 4).
     */
    val balances: Map<String, Double>,
)

/** Input for [pairedTransferMovements]. */
data class CashTransferInput(
    /** Source the money leaves. Must differ from [toSourceId]. */
    val fromSourceId: String,
    /** Source the money enters. */
    val toSourceId: String,
    /** Positive EUR magnitude to move; quantized to whole cents here. */
    val amountEur: Double,
    /** ISO-8601 timestamp shared by both legs (same day ⇒ roll-ups never wobble). */
    val occurredAt: String,
)

/** The two legs of one transfer, double-entry style. */
data class CashTransferLegs(
    /** `transfer_out` on the from-source: strictly negative amount. */
    val outgoing: SourcedCashMovement,
    /** `transfer_in` on the to-source: the exact mirror amount, positive. */
    val incoming: SourcedCashMovement,
)

/**
 * Input for [setBalanceMovement].
 *
 * §3.3 rule 1: the TypeScript takes an inline object-literal parameter; Kotlin
 * needs a named type for it, and the field names are unchanged.
 */
data class SetBalanceInput(
    val sourceId: String,
    val currentBalanceEur: Double,
    val targetBalanceEur: Double,
    val occurredAt: String,
)

/** Input for [netWorthSeries]. */
data class NetWorthSeriesInput(
    /**
     * The holdings-only daily value curve in EUR ([valueOverTime] output):
     * **dense** — one point per calendar day — ascending, ending at the reporting
     * day. May be empty (a portfolio that holds only cash). A date absent from the
     * curve is a day with no holdings and counts as 0.
     */
    val holdingsValues: List<ValuePoint>,
    /** The portfolio's full cash ledger, any order. */
    val movements: List<CashMovement>,
    /**
     * The reporting day (ISO `YYYY-MM-DD`): the last day of the series when the
     * holdings curve is empty. With a non-empty curve the curve's own last day is
     * the end (it was built through the same reporting day).
     */
    val today: String,
)

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Invalid ledger input — unknown kind, non-finite or zero amount, a sign that
 * contradicts the kind, an unparseable timestamp, a bogus starting balance. A
 * typed error so the API can map caller mistakes to a 4xx instead of a 500.
 */
class CashLedgerError(message: String) : DomainException(message)

/**
 * A movement was rejected because it would drive the cash balance negative (no
 * silent negative balances). Deliberately **not** a [CashLedgerError] — the input
 * is well-formed, there just isn't enough cash — so the service layer can map it
 * to its own user-facing response. Carries everything an "available → after"
 * preview needs.
 *
 * §3.3 rule 6: the TypeScript computes `shortfallEur` before calling `super(...)`
 * and then stores it. Kotlin cannot read a property before the super constructor
 * runs, so the identical expression `-(balanceEur + movement.amountEur)` appears
 * twice — same operands, same operation order, therefore the same double.
 */
class InsufficientCashError(
    /** Balance available before the rejected movement, EUR. */
    val balanceEur: Double,
    /** The rejected movement. */
    val movement: CashMovement,
) : DomainException(
    "Insufficient cash: ${movement.kind} of ${jsNum(movement.amountEur)} € " +
        "at ${movement.occurredAt} exceeds the available balance of ${jsNum(balanceEur)} € " +
        "by ${jsNum(-(balanceEur + movement.amountEur))} €.",
) {
    /** How much cash is missing, EUR (> 0): `−(balanceEur + amountEur)`. */
    val shortfallEur: Double = -(balanceEur + movement.amountEur)
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

private val ISO_DAY_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** ISO `YYYY-MM-DD` of a movement's `occurredAt`; malformed input fails loud. */
private fun dayOf(occurredAt: String): String {
    val day = occurredAt.take(10)
    if (!ISO_DAY_RE.matches(day)) {
        throw CashLedgerError(
            "Movement occurredAt must be an ISO-8601 date/time, got $occurredAt",
        )
    }
    return day
}

/** Epoch-ms of a movement's `occurredAt`; unparseable input fails loud. */
private fun occurredAtToMs(occurredAt: String): Double {
    val ms = jsDateParse(occurredAt)
    if (ms.isNaN()) {
        throw CashLedgerError(
            "Movement occurredAt must be an ISO-8601 date/time, got $occurredAt",
        )
    }
    return ms
}

/** Fail-loud shape check for one movement; `at` names its position in errors. */
private fun assertValidMovement(movement: CashMovement, at: Int? = null) {
    val where = if (at == null) "" else " (movement $at)"
    if (!CASH_MOVEMENT_KINDS.contains(movement.kind)) {
        throw CashLedgerError(
            "Unknown movement kind ${movement.kind}$where; " +
                "expected one of ${CASH_MOVEMENT_KINDS.joinToString(", ")}.",
        )
    }
    if (!movement.amountEur.isFinite() || movement.amountEur == 0.0) {
        throw CashLedgerError(
            "Movement amountEur must be a finite non-zero number, " +
                "got ${jsNum(movement.amountEur)}$where.",
        )
    }
    val requiredSign = CASH_MOVEMENT_SIGN.getValue(movement.kind)
    // §3.3 rule 3: `Math.sign` on a finite non-zero double is exactly ±1, and
    // `kotlin.math.sign` has the same definition; the amount was already rejected
    // if it were 0 or non-finite, so the ±0/NaN corners cannot be reached.
    if (sign(movement.amountEur) != requiredSign.toDouble()) {
        throw CashLedgerError(
            "A ${movement.kind} must carry a strictly " +
                (if (requiredSign == 1) "positive" else "negative") +
                " amountEur, got ${jsNum(movement.amountEur)}$where.",
        )
    }
    occurredAtToMs(movement.occurredAt)
}

/**
 * Fail-loud check that a movement carries a usable source id.
 *
 * §3.3 rule 2/6: the TypeScript's `typeof movement.sourceId !== 'string'` half is
 * unrepresentable here — [SourcedCashMovement.sourceId] is a non-null `String` by
 * construction — so only the emptiness half survives, which is the half the
 * vitest suite exercises.
 */
private fun assertSourced(movement: SourcedCashMovement, at: Int? = null) {
    val where = if (at == null) "" else " (movement $at)"
    if (movement.sourceId.isEmpty()) {
        throw CashLedgerError("Movement sourceId must be a non-empty string$where.")
    }
}

// ---------------------------------------------------------------------------
// Balance & projection
// ---------------------------------------------------------------------------

/**
 * Current cash balance = **sum of signed movements** (the reconciliation
 * invariant, literally). Summed in input order at full FP precision; the sum is
 * what it is — non-negativity is [projectCashLedger]'s job, not this function's.
 * Throws [CashLedgerError] on any malformed movement.
 */
fun cashBalance(movements: List<CashMovement>): Double {
    var sum = 0.0
    for ((i, movement) in movements.withIndex()) {
        assertValidMovement(movement, i)
        sum += movement.amountEur
    }
    return sum
}

/**
 * Apply one movement to a balance: the single admission gate behind every
 * mutation and the primitive for a live "available → after" preview. Returns
 * `balanceEur + amountEur`, or throws [InsufficientCashError] when the result
 * would be negative beyond [CASH_EPSILON] — no silent negative balances. Throws
 * [CashLedgerError] on a malformed movement or a non-finite / already-negative
 * starting balance.
 */
fun applyCashMovement(balanceEur: Double, movement: CashMovement): Double {
    if (!balanceEur.isFinite() || balanceEur < -CASH_EPSILON) {
        throw CashLedgerError(
            "Starting balance must be a finite non-negative number of EUR, " +
                "got ${jsNum(balanceEur)}.",
        )
    }
    assertValidMovement(movement)
    val next = balanceEur + movement.amountEur
    if (next < -CASH_EPSILON) {
        throw InsufficientCashError(balanceEur, movement)
    }
    return next
}

/**
 * §3.3 rule 1: the TypeScript sorts an array of anonymous
 * `{ movement, index, ms }` records; Kotlin needs a name for the shape.
 */
private class OrderedMovement(
    val movement: CashMovement,
    val index: Int,
    val ms: Double,
)

/**
 * Replay a movement history chronologically (`occurredAt` ascending, ties broken
 * by input order — mirroring `holdings`' transaction ordering) through
 * [applyCashMovement], so a history that would ever dip negative is rejected with
 * [InsufficientCashError] at the offending movement. The input list is not
 * mutated.
 *
 * Returns one [CashLedgerEntry] per movement in replay order — the running
 * balance after every step, i.e. the balance-over-time series. The last entry's
 * `balanceEur` equals [cashBalance] up to FP summation order (identical when the
 * input is already chronological).
 */
fun projectCashLedger(movements: List<CashMovement>): List<CashLedgerEntry> {
    movements.forEachIndexed { i, movement -> assertValidMovement(movement, i) }
    // §3.3 rule 5: `a.ms - b.ms || a.index - b.index` is a JS numeric comparator;
    // Kotlin's must return an Int, so the double difference is reduced to its sign
    // and the index difference supplies the (stable) tie-break — identical
    // ordering, and `sortedWith` is stable exactly like `Array.prototype.sort`.
    val ordered = movements
        .mapIndexed { index, movement ->
            OrderedMovement(movement, index, occurredAtToMs(movement.occurredAt))
        }
        .sortedWith { a, b ->
            val delta = a.ms - b.ms
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else a.index - b.index
        }

    val entries = mutableListOf<CashLedgerEntry>()
    var balanceEur = 0.0
    for (entry in ordered) {
        balanceEur = applyCashMovement(balanceEur, entry.movement)
        entries.add(CashLedgerEntry(entry.movement, balanceEur))
    }
    return entries
}

/**
 * §3.3 rule 1: the anonymous `{ ms, amountEur }` record [spendableAsOf] sorts.
 */
private class TimedAmount(val ms: Double, val amountEur: Double)

/**
 * The maximum outflow that can be applied at instant `occurredAt` on a **single
 * source's** ledger while keeping it non-negative at **every** instant (the
 * backdated pay-from-cash rule). Inserting a spend of `C` at time `e` shifts the
 * whole running-balance curve at and after `e` down by `C`, so the ledger stays
 * valid exactly when `C ≤ min(runningBalance(t) : t ≥ e)`. This returns that
 * minimum — the cash "available as of" `e` — which is precisely what
 * [projectCashLedgerBySource] enforces at the write boundary, and the quantity a
 * live preview must size a backdated buy against instead of today's (usually
 * higher) balance.
 *
 * The write path replays chronologically and appends the proposed buy **last**
 * among same-timestamp movements, so every existing movement dated **at** `e` (a
 * same-day funding deposit included) is already booked when the buy applies —
 * hence the floor is the balance at and before `e`, and only strictly-later
 * movements can drag the surviving minimum below it. A spend dated at or after
 * the newest movement simply yields the current balance, so "record a buy now" is
 * unchanged. Malformed movements fail loud ([CashLedgerError]); the caller passes
 * one source's movements (any order).
 */
fun spendableAsOf(movements: List<CashMovement>, occurredAt: String): Double {
    movements.forEachIndexed { i, movement -> assertValidMovement(movement, i) }
    val eMs = occurredAtToMs(occurredAt)
    // Ascending by time; ties settle credits (positive) before debits (negative),
    // mirroring `projectCashLedger`'s replay order for same-instant movements.
    // §3.3 rule 5: the same numeric-comparator reduction as projectCashLedger,
    // here for `a.ms - b.ms || b.amountEur - a.amountEur`.
    val ordered = movements
        .map { movement -> TimedAmount(occurredAtToMs(movement.occurredAt), movement.amountEur) }
        .sortedWith { a, b ->
            val delta = a.ms - b.ms
            if (delta < 0.0) {
                -1
            } else if (delta > 0.0) {
                1
            } else {
                val byAmount = b.amountEur - a.amountEur
                if (byAmount < 0.0) -1 else if (byAmount > 0.0) 1 else 0
            }
        }

    // Floor: the balance at and before `e` (the buy applies after all of these).
    var floor = 0.0
    for (m in ordered) if (m.ms <= eMs) floor += m.amountEur
    // Then the lowest the balance dips at strictly-later instants — the spend,
    // which shifts every one of them down by its cost, must clear the minimum.
    var running = floor
    var minFromE = floor
    for (m in ordered) {
        if (m.ms > eMs) {
            running += m.amountEur
            if (running < minFromE) minFromE = running
        }
    }
    return minFromE
}

/**
 * End-of-day balance series: [projectCashLedger] condensed to the last balance of
 * each day with a movement (sparse, ascending) — the shape the overview wiring
 * needs to add cash to a daily value curve. Validates and rejects
 * negative-dipping histories exactly like the projection.
 */
fun cashBalanceOverTime(movements: List<CashMovement>): List<CashBalancePoint> {
    val points = mutableListOf<CashBalancePoint>()
    for (entry in projectCashLedger(movements)) {
        val date = dayOf(entry.movement.occurredAt)
        val last = points.lastOrNull()
        if (last != null && last.date == date) {
            last.balanceEur = entry.balanceEur
        } else {
            points.add(CashBalancePoint(date, entry.balanceEur))
        }
    }
    return points
}

// ---------------------------------------------------------------------------
// Cash sources
// ---------------------------------------------------------------------------
//
// The single ledger is **Main** plus named sibling sources. Every movement
// belongs to exactly one source; the *portfolio* ledger is simply the union of
// all sources' movements, so every roll-up above (cashBalance,
// projectCashLedger, cashBalanceOverTime, netWorthSeries,
// externalCashFlowsForTwr) keeps working unchanged when fed the union — a
// transfer's paired legs cancel to zero inside every sum. The *solvency* gate,
// however, is per source: each source is a real account, and money in "Bank"
// cannot cover an overdraft of "Main". The per-source projection below is the
// authoritative admission check; per-source validity implies portfolio-level
// validity (each source's running balance is ≥ 0, so their sum is too).

/**
 * Current balance of **each** source: map of `sourceId` → sum of its signed
 * movements (the reconciliation invariant, per source). Sources with no movements
 * are absent — the caller supplies zeroes for freshly created ones. Full FP
 * precision; quantize with [floorCents] at the boundary. Throws [CashLedgerError]
 * on any malformed movement.
 *
 * §3.3 rule 4: a `LinkedHashMap`, because a JavaScript `Map` is insertion-ordered
 * and the order is observable (and feeds a floating-point roll-up downstream).
 */
fun cashBalancesBySource(movements: List<SourcedCashMovement>): Map<String, Double> {
    val balances = LinkedHashMap<String, Double>()
    for ((i, movement) in movements.withIndex()) {
        assertValidMovement(movement, i)
        assertSourced(movement, i)
        balances[movement.sourceId] = (balances[movement.sourceId] ?: 0.0) + movement.amountEur
    }
    return balances
}

/**
 * Replay every source's own history chronologically through the
 * [applyCashMovement] admission gate — the per-source solvency check. A history
 * in which **any single source** ever dips negative is rejected with
 * [InsufficientCashError] at the offending movement (its `movement` retains the
 * `sourceId`), even when the other sources hold plenty. Returns the per-source
 * projections (`sourceId` → running-balance entries), each the exact shape
 * [projectCashLedger] produces for a single ledger.
 */
fun projectCashLedgerBySource(
    movements: List<SourcedCashMovement>,
): Map<String, List<CashLedgerEntry>> {
    movements.forEachIndexed { i, movement -> assertSourced(movement, i) }
    val bySource = LinkedHashMap<String, MutableList<SourcedCashMovement>>()
    for (movement in movements) {
        val list = bySource[movement.sourceId]
        if (list != null) list.add(movement) else bySource[movement.sourceId] = mutableListOf(movement)
    }
    val projections = LinkedHashMap<String, List<CashLedgerEntry>>()
    for ((sourceId, sourceMovements) in bySource) {
        projections[sourceId] = projectCashLedger(sourceMovements)
    }
    return projections
}

/**
 * Dense daily end-of-day balances of **each** source: one point per calendar day
 * from the first movement day through `endDay`, each carrying every touched
 * source's running balance carried forward between its movement days. The replay
 * order (chronological, ties by input order) and the movements-after-the-grid-end
 * exclusion mirror [netWorthSeries]'s cash leg exactly, so summing a day's
 * balances reproduces that day's cash component of the net-worth curve.
 * Deliberately no solvency gate — this is a display derivation (see
 * [netWorthSeries]).
 *
 * Returns an empty series when there are no movements on or before `endDay`.
 * Throws [CashLedgerError] on malformed input.
 */
fun cashBySourceOverTime(
    movements: List<SourcedCashMovement>,
    endDay: String,
): List<CashBySourcePoint> {
    if (!ISO_DAY_RE.matches(endDay)) {
        throw CashLedgerError("endDay must be ISO YYYY-MM-DD, got $endDay")
    }
    movements.forEachIndexed { i, movement ->
        assertValidMovement(movement, i)
        assertSourced(movement, i)
    }

    val endMs = isoDayToMs(endDay)
    val ordered = movements
        .mapIndexed { index, movement ->
            OrderedMovement(movement, index, occurredAtToMs(movement.occurredAt))
        }
        .sortedWith { a, b ->
            val delta = a.ms - b.ms
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else a.index - b.index
        }
        // Movements dated after the grid end never enter (netWorthSeries's rule).
        .filter { isoDayToMs(dayOf(it.movement.occurredAt)) <= endMs }
    val first = ordered.firstOrNull() ?: return emptyList()

    val running = LinkedHashMap<String, Double>()
    val series = mutableListOf<CashBySourcePoint>()
    var idx = 0
    var ms = isoDayToMs(dayOf(first.movement.occurredAt))
    while (ms <= endMs) {
        val date = jsIsoDay(ms)
        while (idx < ordered.size) {
            val entry = ordered[idx]
            if (isoDayToMs(dayOf(entry.movement.occurredAt)) > ms) break
            val movement = entry.movement as SourcedCashMovement
            running[movement.sourceId] = (running[movement.sourceId] ?: 0.0) + movement.amountEur
            idx += 1
        }
        series.add(CashBySourcePoint(date, LinkedHashMap(running)))
        ms += MS_PER_DAY
    }
    return series
}

/**
 * Build the paired movements of an internal transfer: `transfer_out` of `−X` on
 * the from-source and `transfer_in` of `+X` on the to-source, sharing one
 * timestamp — double-entry style, so both histories carry the transfer while
 * every roll-up sums the pair to exactly zero (net worth unchanged, and never a
 * TWR flow — see [EXTERNAL_CASH_MOVEMENT_KINDS]). The magnitude is quantized to
 * whole cents (cash exists only in cents); an amount that floors to zero, a
 * non-finite/negative amount, a same-source transfer, or an empty source id fails
 * loud with [CashLedgerError]. Solvency of the from-source is the per-source
 * projection's job, not this builder's.
 */
fun pairedTransferMovements(input: CashTransferInput): CashTransferLegs {
    val fromSourceId = input.fromSourceId
    val toSourceId = input.toSourceId
    val occurredAt = input.occurredAt
    // §3.3 rule 2/6: the `typeof … !== 'string'` halves are unrepresentable in
    // Kotlin (these are non-null `String`s by construction); the length checks —
    // the halves the suite exercises — are kept verbatim.
    if (fromSourceId.isEmpty()) {
        throw CashLedgerError("Transfer fromSourceId must be a non-empty string.")
    }
    if (toSourceId.isEmpty()) {
        throw CashLedgerError("Transfer toSourceId must be a non-empty string.")
    }
    if (fromSourceId == toSourceId) {
        throw CashLedgerError("A transfer needs two different cash sources.")
    }
    if (!input.amountEur.isFinite() || input.amountEur <= 0) {
        throw CashLedgerError(
            "Transfer amountEur must be a strictly positive number, got ${jsNum(input.amountEur)}.",
        )
    }
    val amountEur = floorCents(input.amountEur)
    if (amountEur == 0.0) {
        throw CashLedgerError(
            "Transfer amountEur floors to €0.00 (got ${jsNum(input.amountEur)}); nothing to move.",
        )
    }
    val outgoing = SourcedCashMovement(
        kind = "transfer_out",
        amountEur = -amountEur,
        occurredAt = occurredAt,
        sourceId = fromSourceId,
    )
    val incoming = SourcedCashMovement(
        kind = "transfer_in",
        amountEur = amountEur,
        occurredAt = occurredAt,
        sourceId = toSourceId,
    )
    // Reuse the single admission gate's shape checks (kind/sign/timestamp).
    assertValidMovement(outgoing)
    assertValidMovement(incoming)
    return CashTransferLegs(outgoing, incoming)
}

/**
 * The signed cent delta of a "set balance to X" operation: the movement amount
 * that takes `currentBalanceEur` to `targetBalanceEur`. Both inputs are quantized
 * to whole cents first — pass the *reported* (cent-exact) balance as `current` —
 * so the returned delta is itself cent-exact and the post-movement balance reads
 * exactly the target. Positive ⇒ record a deposit, negative ⇒ a withdrawal, `0` ⇒
 * record nothing (see [setBalanceMovement]). The target must be a finite,
 * non-negative EUR amount (no silent negative balances); malformed input fails
 * loud.
 */
fun setBalanceDelta(currentBalanceEur: Double, targetBalanceEur: Double): Double {
    if (!currentBalanceEur.isFinite()) {
        throw CashLedgerError(
            "Set-balance current balance must be a finite number of EUR, " +
                "got ${jsNum(currentBalanceEur)}.",
        )
    }
    if (!targetBalanceEur.isFinite() || targetBalanceEur < 0) {
        throw CashLedgerError(
            "Set-balance target must be a finite non-negative number of EUR, " +
                "got ${jsNum(targetBalanceEur)}.",
        )
    }
    // Quantize each operand, then the difference: 200.00 − 123.45 carries FP
    // noise (76.55000000000001) that must not survive into a stored amount.
    return floorCents(floorCents(targetBalanceEur) - floorCents(currentBalanceEur))
}

/**
 * The **normal movement** a set-balance records (the app computes the signed
 * difference itself and books it like any other movement, keeping the audit trail
 * intact): a `deposit` carrying a positive delta, a `withdrawal` carrying a
 * negative one, or `null` when the target already equals the current balance — a
 * no-op writes nothing. Set-balance deltas are external flows exactly like
 * hand-entered deposits/withdrawals: money appeared in (or left) the real-world
 * account, crossing the portfolio boundary.
 */
fun setBalanceMovement(input: SetBalanceInput): SourcedCashMovement? {
    if (input.sourceId.isEmpty()) {
        throw CashLedgerError("Set-balance sourceId must be a non-empty string.")
    }
    val deltaEur = setBalanceDelta(input.currentBalanceEur, input.targetBalanceEur)
    if (deltaEur == 0.0) return null
    val movement = SourcedCashMovement(
        kind = if (deltaEur > 0) "deposit" else "withdrawal",
        amountEur = deltaEur,
        occurredAt = input.occurredAt,
        sourceId = input.sourceId,
    )
    assertValidMovement(movement)
    return movement
}

// ---------------------------------------------------------------------------
// Net-worth series
// ---------------------------------------------------------------------------

private const val MS_PER_DAY: Double = 86_400_000.0

/**
 * UTC midnight epoch-ms of an ISO `YYYY-MM-DD` (deterministic, no clock).
 *
 * §3.3 rule 8: the TypeScript is `Date.parse(`${date}T00:00:00Z`)`, so a
 * well-shaped but impossible date yields `NaN` rather than throwing — reproduced
 * by [jsDateOnlyToMs], never by re-parsing through `LocalDate` and letting an
 * exception escape.
 */
private fun isoDayToMs(date: String): Double = jsDateOnlyToMs(date)

/**
 * §3.3 rule 1: the anonymous `{ dayMs, balanceEur }` record [netWorthSeries]
 * accumulates; `balanceEur` is a `var` because the TypeScript overwrites the last
 * entry in place for a same-day movement.
 */
private class EodBalance(val dayMs: Double, var balanceEur: Double)

/**
 * The portfolio's daily **net worth** curve: for every calendar day,
 * `holdings value + end-of-day cash balance`. Cash is a component of what the
 * portfolio is worth, so the absolute value graph carries it too. Two properties
 * follow directly and are the correctness anchors:
 *
 *  - a **deposit / withdrawal** moves the curve by exactly its amount on its day
 *    (cash changes, holdings don't);
 *  - a **cash-funded buy** leaves the curve unchanged at the trade moment —
 *    holdings rise by what cash falls by; money merely changed form.
 *
 * The grid spans from the earlier of (first holdings day, first movement day) to
 * the holdings curve's last day (or `today` when it is empty), one point per
 * calendar day: cash deposited before the first transaction is part of the
 * portfolio's worth from its deposit day, with holdings contributing 0 until they
 * exist. The cash balance carries forward between movement days (EOD balance,
 * ties by input order); movements dated after the grid end never enter. Full FP
 * precision throughout — no rounding, no clamping.
 *
 * **Deliberately no solvency gate.** [projectCashLedger] rejects negative-dipping
 * histories at the *write* boundary; this is a *display* derivation, and a ledger
 * reshaped after the fact (e.g. a cascade-deleted `sell_proceeds` that funded a
 * later withdrawal) must still render what the rows say rather than 500 the whole
 * graph. Malformed movements, dates or values still fail loud
 * ([CashLedgerError]).
 */
fun netWorthSeries(input: NetWorthSeriesInput): List<ValuePoint> {
    val holdingsValues = input.holdingsValues
    val movements = input.movements
    val today = input.today
    if (!ISO_DAY_RE.matches(today)) {
        throw CashLedgerError("today must be ISO YYYY-MM-DD, got $today")
    }
    for (point in holdingsValues) {
        if (!ISO_DAY_RE.matches(point.date)) {
            throw CashLedgerError("Holdings value date must be ISO YYYY-MM-DD, got ${point.date}")
        }
        if (!point.valueEur.isFinite()) {
            throw CashLedgerError(
                "Holdings value on ${point.date} must be a finite number, " +
                    "got ${jsNum(point.valueEur)}",
            )
        }
    }
    movements.forEachIndexed { i, movement -> assertValidMovement(movement, i) }

    // Sparse end-of-day balances: chronological replay (ties by input order,
    // mirroring projectCashLedger), plain running sum — see docstring for why the
    // insufficient-cash gate deliberately does not apply here.
    val ordered = movements
        .mapIndexed { index, movement ->
            OrderedMovement(movement, index, occurredAtToMs(movement.occurredAt))
        }
        .sortedWith { a, b ->
            val delta = a.ms - b.ms
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else a.index - b.index
        }
    val eodBalances = mutableListOf<EodBalance>()
    var balanceEur = 0.0
    for (entry in ordered) {
        balanceEur += entry.movement.amountEur
        val dayMs = isoDayToMs(dayOf(entry.movement.occurredAt))
        val last = eodBalances.lastOrNull()
        if (last != null && last.dayMs == dayMs) {
            last.balanceEur = balanceEur
        } else {
            eodBalances.add(EodBalance(dayMs, balanceEur))
        }
    }

    val firstHoldings = holdingsValues.firstOrNull()
    val lastHoldings = holdingsValues.lastOrNull()
    val firstCash = eodBalances.firstOrNull()
    if (firstHoldings == null && firstCash == null) return emptyList()

    val endMs = if (lastHoldings != null) isoDayToMs(lastHoldings.date) else isoDayToMs(today)
    val startMs = min(
        if (firstHoldings != null) isoDayToMs(firstHoldings.date) else Double.POSITIVE_INFINITY,
        firstCash?.dayMs ?: Double.POSITIVE_INFINITY,
    )
    // Nothing on or before the grid end (e.g. only future-dated movements).
    if (startMs > endMs) return emptyList()

    // §3.3 rule 4: `new Map(pairs)` keeps the FIRST insertion position and the
    // LAST value for a repeated date — exactly LinkedHashMap's `put` semantics.
    val holdingsByDate = LinkedHashMap<String, Double>()
    for (p in holdingsValues) holdingsByDate[p.date] = p.valueEur
    val series = mutableListOf<ValuePoint>()
    var cashIdx = 0
    var carriedCashEur = 0.0
    var ms = startMs
    while (ms <= endMs) {
        val date = jsIsoDay(ms)
        while (cashIdx < eodBalances.size) {
            val entry = eodBalances[cashIdx]
            if (entry.dayMs > ms) break
            carriedCashEur = entry.balanceEur
            cashIdx += 1
        }
        series.add(ValuePoint(date, (holdingsByDate[date] ?: 0.0) + carriedCashEur))
        ms += MS_PER_DAY
    }
    return series
}

// ---------------------------------------------------------------------------
// TWR classification
// ---------------------------------------------------------------------------

/** Whether a movement kind is an **external** flow for TWR (deposit/withdrawal). */
fun isExternalCashMovement(kind: CashMovementKind): Boolean =
    EXTERNAL_CASH_MOVEMENT_KINDS.contains(kind)

/**
 * The movements that count as **external** cash flows for the time-weighted
 * return: **only** `deposit` / `withdrawal`. `buy` and `sell_proceeds` are
 * internal — money already inside the portfolio changing form — and excluded,
 * which is precisely what keeps a cash-funded buy TWR-neutral. A `fee` is
 * excluded for the opposite reason: it is a real cost of holding, so it must stay
 * *inside* the curve and drag the return down.
 *
 * Output is `holdings`' [FlowPoint] shape and convention — net EUR flow per day,
 * money *into* the portfolio positive (a deposit's `amountEur` is already signed
 * that way), sparse (only days with an external flow), sorted ascending — ready
 * to feed [timeWeightedReturn] directly. Pure classification: solvency is
 * [projectCashLedger]'s job.
 */
fun externalCashFlowsForTwr(movements: List<CashMovement>): List<FlowPoint> {
    val flowByDay = LinkedHashMap<String, Double>()
    for ((i, movement) in movements.withIndex()) {
        assertValidMovement(movement, i)
        if (!isExternalCashMovement(movement.kind)) continue
        val day = dayOf(movement.occurredAt)
        flowByDay[day] = (flowByDay[day] ?: 0.0) + movement.amountEur
    }
    // §3.3 rule 5: the TypeScript comparator is `a[0] < b[0] ? -1 : 1` — it never
    // returns 0. Reproduced verbatim; the keys come out of a Map so they are
    // pairwise distinct, which makes the comparator antisymmetric and total on
    // this input (and `sortedWith` never compares an element with itself).
    return flowByDay.entries.toList()
        .sortedWith { a, b -> if (a.key < b.key) -1 else 1 }
        .map { FlowPoint(it.key, it.value) }
}
