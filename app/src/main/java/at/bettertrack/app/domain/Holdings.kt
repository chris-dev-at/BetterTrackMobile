package at.bettertrack.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Portfolio money-math core — a **literal** Kotlin port of `packages/domain/src/holdings.ts`
 * at the commit pinned in `tools/domain-vectors/PINNED_AT`.
 *
 * Transactions are the source of truth; **holdings are derived, never stored**.
 * This module owns the derivations that turn a transaction log into the numbers
 * a user sees:
 *
 *  1. [reducePosition] — average-cost basis and realized P/L from a single
 *     asset's transactions (BUY re-averages, SELL realizes against the running
 *     average and is rejected if it would push the held quantity negative).
 *  2. [deriveHoldings] — the per-asset holdings view: quantity, average cost,
 *     market value (EUR), unrealized P/L €/%, and day change.
 *  3. [valueOverTime] — the portfolio value-over-time series, daily from the
 *     first transaction to a given `today`.
 *  4. [costBasisOverTime] — the daily open-cost-basis companion series.
 *  5. [netFlowsOverTime] + [timeWeightedReturn] — the cash-flow-neutralized
 *     performance series. [rebasePerformance] re-bases a window slice to 0 %.
 *
 * **Purity is a hard requirement:** everything here is a pure function of its
 * inputs — no DB, no HTTP, no clock. Currency conversion is injected as a
 * [CurrencyConverter]; price history, the reporting day and FX all arrive as
 * parameters.
 *
 *  - **No rounding mid-computation.** Every value is returned at full `Double`
 *    precision; display rounding lives in the display layer, never here.
 *  - **Quantity comparisons use a tolerance** ([QTY_EPSILON]) so that selling
 *    exactly the held quantity is allowed despite floating-point dust, while a
 *    genuine over-sell is rejected. Stored-rounding drift within the
 *    per-contributing-row [HOLDINGS_QTY_STORAGE_QUANTUM] envelope (#917/#1094)
 *    is likewise not an over-sell.
 *
 * Translation notes (plan §3.3): arithmetic expressions keep the original
 * operation order; every keyed traversal that feeds a floating-point sum uses a
 * `LinkedHashMap` so insertion order matches JavaScript's; the default
 * string-comparison sorts are reproduced explicitly; `async` became `suspend`.
 */

// ---------------------------------------------------------------------------
// Tolerance
// ---------------------------------------------------------------------------

/**
 * Quantity comparison tolerance. Quantities are stored at scale 8, so the
 * smallest meaningful unit is 1e-8; this tolerance sits an order of magnitude
 * below that. Two uses, both on the money path:
 *
 *  - a SELL of `qty` is rejected only when `qty` exceeds the held quantity by
 *    *more* than this;
 *  - a held quantity within this of zero is treated as flat (clamped to 0).
 *
 * Contract constant — copied, never re-derived (plan §3.3 rule 7).
 */
const val QTY_EPSILON: Double = 1e-9

/**
 * One scale-8 storage quantum (#917, extended to holdings by #1094). Quantities
 * persist as `numeric(20,8)`: the write path epsilon-validates the raw client
 * values, then PostgreSQL rounds each row independently to scale 8, so every
 * stored row can sit up to one quantum away from the raw value that was
 * validated. A replayed position can therefore show a spurious shortfall bounded
 * by one quantum per contributing stored row — [reducePosition] waives exactly
 * that envelope; anything beyond it fails closed as a genuine oversell.
 *
 * §3.3 rules 3 + 7, the mirror of [TAX_QTY_EPSILON]'s situation: `holdings.ts`
 * **re-declares** `QTY_STORAGE_QUANTUM` locally rather than importing it (the
 * `packages/domain` purity rule forbids value imports), and its KDoc names
 * `tax.QTY_STORAGE_QUANTUM` as the original. Kotlin has no module scope inside a
 * package, so the *mirroring* declaration takes the prefix in both directions:
 * `tax.ts`'s copy of the epsilon is [TAX_QTY_EPSILON], and `holdings.ts`'s copy
 * of the quantum is this. The bare name always marks the origin module —
 * [QTY_EPSILON] here, [QTY_STORAGE_QUANTUM] in `Tax.kt`. `DomainHandPortedTest`
 * pins the two quanta equal, which is what the TypeScript comment claims.
 */
const val HOLDINGS_QTY_STORAGE_QUANTUM: Double = 1e-8

/**
 * EUR value comparison tolerance for the performance series: below this a day's
 * value or return denominator is float dust (or genuinely empty), not a
 * measurable amount, and the return for that segment is treated as flat.
 */
const val VALUE_EPSILON: Double = 1e-9

private const val MS_PER_DAY: Double = 86_400_000.0
private val ISO_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/**
 * Thrown when a SELL would push the held quantity negative ("you only hold 3.5
 * shares"). A typed error so the write path can map it to a rejection rather
 * than a crash; the message carries the offending quantities.
 */
class OversellError(
    val requested: Double,
    val held: Double,
    val assetId: String?,
) : DomainException(
    "Cannot sell ${jsNum(requested)} units" +
        (if (!assetId.isNullOrEmpty()) " of $assetId" else "") +
        ": only ${jsNum(held)} held.",
)

// ---------------------------------------------------------------------------
// Transactions & positions
// ---------------------------------------------------------------------------

enum class TransactionSide { BUY, SELL }

/**
 * A single portfolio transaction in its asset's **native currency**.
 *
 * [executedAt] is an ISO-8601 timestamp used for chronological ordering and —
 * via its date portion `YYYY-MM-DD` — as the day key for the value-over-time
 * series. The domain reads the date portion verbatim and does no timezone
 * conversion of its own, which keeps the day boundary off-by-one-free.
 */
data class Transaction(
    val assetId: String,
    val side: TransactionSide,
    /** Units transacted; strictly positive. */
    val quantity: Double,
    /** Price per unit, native currency; non-negative. */
    val price: Double,
    /** Total fee for the transaction, native currency; non-negative. */
    val fee: Double,
    /** ISO-8601 timestamp. */
    val executedAt: String,
    /**
     * Uncovered sell. When true, a SELL exceeding the held quantity (including a
     * zero holding) is **permitted** instead of throwing [OversellError]: the
     * covered shares realize against the running average, the uncovered
     * remainder against [uncoveredEntryPrice] (or the sale price when that is
     * absent → 0 realized on that portion), and the position closes at exactly
     * 0 — **no shorts**.
     */
    val allowUncovered: Boolean? = null,
    /**
     * Native per-unit cost basis for the uncovered portion of an
     * [allowUncovered] SELL. `null` → the sale [price] is used, so the uncovered
     * shares realize 0.
     */
    val uncoveredEntryPrice: Double? = null,
)

/** Realized P/L attributed to a single SELL, by its index in the input list. */
data class SellRealization(
    /** Index of the SELL in the original (unsorted) transaction list. */
    val index: Int,
    /** `quantity · (price − avg_cost) − fee`, native currency. */
    val realizedPnl: Double,
)

/** The outcome of reducing one asset's transaction log. */
data class PositionState(
    /** Net held quantity (≥ 0); exactly 0 when the position is flat. */
    val quantity: Double,
    /** Average cost per unit, native currency; 0 when flat. */
    val avgCost: Double,
    /** Cumulative realized P/L across every SELL, native currency. */
    val realizedPnl: Double,
    /** Per-SELL realized P/L, for the transaction rows. */
    val realizations: List<SellRealization>,
)

private fun assertFiniteNonNegative(value: Double, label: String) {
    if (!value.isFinite() || value < 0) {
        throw DomainException("$label must be a finite non-negative number, got ${jsNum(value)}")
    }
}

/** Epoch-ms of a transaction's `executedAt`; unparseable input fails loud. */
private fun executedAtToMs(executedAt: String): Double {
    val ms = jsDateParse(executedAt)
    if (ms.isNaN()) {
        throw DomainException(
            "Transaction executedAt must be an ISO-8601 date/time, got $executedAt",
        )
    }
    return ms
}

private class OrderedTransaction(
    val t: Transaction,
    val index: Int,
    val executedAtMs: Double,
)

/**
 * Average-cost basis and realized P/L for one asset's transactions.
 *
 * Processes transactions in chronological order (`executedAt`, ties broken by
 * input order for determinism):
 *
 *  - **BUY** re-averages: `avg = (held·avg + qty·price + fee) / (held + qty)` —
 *    the fee is capitalised into the cost basis.
 *  - **SELL** realizes `qty·(price − avg) − fee` and reduces the quantity; the
 *    average cost is unchanged. A SELL exceeding the held quantity (beyond
 *    [QTY_EPSILON]) throws [OversellError]. One exception (#917/#1094): a
 *    shortfall within one [HOLDINGS_QTY_STORAGE_QUANTUM] per contributing stored
 *    row is `numeric(20,8)` rounding drift, not an oversell — the write path
 *    validated the raw values before PostgreSQL rounded the rows apart. Such a
 *    sell closes the position like an exact one: the held shares realize against
 *    the running average, the dust remainder takes the sale price (0 gain). The
 *    envelope is per-row, never a blanket loosening — a shortfall beyond it
 *    still fails closed.
 *
 * The input may contain transactions for a single asset; mixing assets is a
 * programming error and throws.
 */
fun reducePosition(transactions: List<Transaction>): PositionState {
    // Tag with original indices, then stable-sort by (executedAt, index) so the
    // running average sees BUY/SELL in the true chronological order regardless of
    // how the caller supplied the list. Compare as epoch-ms, NOT as strings:
    // ISO-8601 admits mixed sub-second precision (`…T10:00:00Z` vs
    // `…T10:00:00.500Z`) and non-UTC offsets, neither of which sorts
    // lexicographically in time order ('.' < 'Z'), so a string comparison here
    // would replay sells before the buys that funded them.
    //
    // NOTE: the mapping runs over EVERY transaction before the sort, so an
    // unparseable timestamp anywhere in the list throws even if the sort would
    // never have compared it — matching the TypeScript exactly.
    val ordered = transactions
        .mapIndexed { index, t -> OrderedTransaction(t, index, executedAtToMs(t.executedAt)) }
        .sortedWith { a, b ->
            val delta = a.executedAtMs - b.executedAtMs
            if (delta < 0.0) -1 else if (delta > 0.0) 1 else a.index - b.index
        }

    var assetId: String? = null
    var held = 0.0
    var avg = 0.0
    var realizedPnl = 0.0
    var driftRows = 0
    val realizations = mutableListOf<SellRealization>()

    for (entry in ordered) {
        val t = entry.t
        val index = entry.index

        if (assetId == null) {
            assetId = t.assetId
        } else if (t.assetId != assetId) {
            throw DomainException(
                "reducePosition received transactions for multiple assets " +
                    "($assetId, ${t.assetId}); group by asset first.",
            )
        }

        if (!t.quantity.isFinite() || t.quantity <= 0) {
            throw DomainException(
                "Transaction quantity must be a finite positive number, got ${jsNum(t.quantity)}",
            )
        }
        assertFiniteNonNegative(t.price, "Transaction price")
        assertFiniteNonNegative(t.fee, "Transaction fee")

        // Every stored row of the open position — the current one included — can
        // carry up to one quantum of numeric(20,8) rounding drift (#917/#1094).
        driftRows += 1

        if (t.side == TransactionSide.BUY) {
            val newHeld = held + t.quantity
            // newHeld > 0 always (held ≥ 0, quantity > 0), so the division is safe.
            avg = (held * avg + t.quantity * t.price + t.fee) / newHeld
            held = newHeld
        } else {
            val oversell = t.quantity > held + QTY_EPSILON
            // Storage-rounding drift (#917, extended here by #1094): the write path
            // validated the raw values, then numeric(20,8) rounded each row
            // independently — a shortfall within one quantum per contributing stored
            // row is a persistence artifact. It closes the position like an exact
            // sell; beyond the envelope it is a genuine oversell and fails closed.
            val storageDrift =
                oversell &&
                    t.allowUncovered != true &&
                    t.quantity - held <= driftRows * HOLDINGS_QTY_STORAGE_QUANTUM + QTY_EPSILON
            if (storageDrift) {
                // The held shares realize against the running average; the dust
                // remainder takes the sale price (0 gain) — it is rounding residue of
                // covered shares, not a phantom acquisition. The fee applies once.
                val pnl = held * (t.price - avg) - t.fee
                realizedPnl += pnl
                realizations.add(SellRealization(index, pnl))
                held = 0.0
                avg = 0.0
            } else if (oversell) {
                // Over-selling the held quantity: rejected unless the caller explicitly
                // acknowledged an uncovered sell.
                if (t.allowUncovered != true) {
                    throw OversellError(t.quantity, held, assetId)
                }
                // Uncovered sell: the covered shares (the whole held position, ≥ 0)
                // realize against the running average; the uncovered remainder realizes
                // against its supplied entry price, or the sale price when none is given
                // (→ 0 on that portion). The fee applies once to the whole sell. No
                // shorts — the position closes at exactly 0.
                val covered = held
                val uncovered = t.quantity - covered
                val uncoveredBasis = t.uncoveredEntryPrice ?: t.price
                if (t.uncoveredEntryPrice != null) {
                    assertFiniteNonNegative(
                        t.uncoveredEntryPrice,
                        "Transaction uncovered entry price",
                    )
                }
                val pnl =
                    covered * (t.price - avg) + uncovered * (t.price - uncoveredBasis) - t.fee
                realizedPnl += pnl
                realizations.add(SellRealization(index, pnl))
                held = 0.0
                avg = 0.0
            } else {
                val pnl = t.quantity * (t.price - avg) - t.fee
                realizedPnl += pnl
                realizations.add(SellRealization(index, pnl))
                held -= t.quantity
                // Clamp float dust: a sell-everything leaves held at ~±1e-15, not 0.
                if (abs(held) <= QTY_EPSILON) {
                    held = 0.0
                    avg = 0.0
                }
            }
            // A closed position starts the next round trip clean — including its
            // storage-drift envelope (#917/#1094).
            if (held == 0.0) {
                driftRows = 0
            }
        }
    }

    return PositionState(
        quantity = held,
        avgCost = if (held == 0.0) 0.0 else avg,
        realizedPnl = realizedPnl,
        realizations = realizations,
    )
}

// ---------------------------------------------------------------------------
// Holdings view
// ---------------------------------------------------------------------------

/** A current quote for an asset, native currency. `null` when unavailable. */
data class HoldingQuote(
    val price: Double,
    /** Previous close, for day change; `null` when unknown. */
    val prevClose: Double? = null,
)

/** Per-asset inputs for [deriveHoldings]: identity, currency, live quote. */
data class HoldingAssetInput(
    val assetId: String,
    /** ISO-4217 native currency of the asset. */
    val currency: String,
    /** Current quote, or `null` when the provider has nothing. */
    val quote: HoldingQuote?,
)

/**
 * One row of the holdings view. Native-currency facts sit alongside
 * EUR-converted figures. Every EUR figure is `null` when it cannot be computed
 * (no quote, or a flat position).
 *
 * The EUR fields are `var` because the TypeScript builds the object and then
 * assigns into it; keeping the same shape keeps the translation line-for-line.
 */
data class Holding(
    val assetId: String,
    val currency: String,
    /** Net held quantity (≥ 0). */
    val quantity: Double,
    /** Average cost per unit, native currency; 0 when flat. */
    val avgCost: Double,
    /** Cumulative realized P/L, native currency. */
    val realizedPnl: Double,
    /** Current price per unit, native currency; `null` without a quote. */
    val price: Double?,
    /** Held quantity × price, in EUR. */
    var marketValueEur: Double? = null,
    /** Open cost basis (quantity × avg cost), in EUR at current FX. */
    var costBasisEur: Double? = null,
    /** `marketValueEur − costBasisEur`. */
    var unrealizedPnlEur: Double? = null,
    /** `(price − avgCost) / avgCost · 100`; `null` when avg cost is 0. */
    var unrealizedPnlPct: Double? = null,
    /** Held quantity × (price − prevClose), in EUR. */
    var dayChangeEur: Double? = null,
    /** `(price − prevClose) / prevClose · 100`; `null` without a prev close. */
    var dayChangePct: Double? = null,
)

/**
 * Derive the holdings view for a set of assets from their transactions.
 *
 * One [Holding] is produced per asset that has at least one transaction, in the
 * order the assets are supplied. Fully-closed positions (net quantity 0) are
 * included so their realized P/L is available; their EUR market figures are
 * `null`. Every transacted asset must have a matching entry in [assets] — a
 * missing currency/quote is a programming error and throws.
 */
suspend fun deriveHoldings(
    transactions: List<Transaction>,
    assets: List<HoldingAssetInput>,
    converter: CurrencyConverter,
): List<Holding> {
    val byAsset = LinkedHashMap<String, MutableList<Transaction>>()
    for (t in transactions) {
        val list = byAsset[t.assetId]
        if (list != null) list.add(t) else byAsset[t.assetId] = mutableListOf(t)
    }

    // Fail loud on the money path: a transacted asset with no currency/quote
    // input would otherwise silently vanish from the holdings view (and from the
    // portfolio totals built on it). Same contract as valueOverTime.
    val covered = LinkedHashSet(assets.map { it.assetId })
    val missing = byAsset.keys.filter { it !in covered }
    if (missing.isNotEmpty()) {
        throw DomainException(
            "deriveHoldings: transactions reference assets with no currency/quote input: " +
                "${missing.joinToString(", ")}.",
        )
    }

    val holdings = mutableListOf<Holding>()
    for (asset in assets) {
        val txns = byAsset[asset.assetId] ?: continue // no transactions → not a holding

        val pos = reducePosition(txns)
        val price = asset.quote?.price

        val holding = Holding(
            assetId = asset.assetId,
            currency = asset.currency,
            quantity = pos.quantity,
            avgCost = pos.avgCost,
            realizedPnl = pos.realizedPnl,
            price = price,
            marketValueEur = null,
            costBasisEur = null,
            unrealizedPnlEur = null,
            unrealizedPnlPct = null,
            dayChangeEur = null,
            dayChangePct = null,
        )

        if (pos.quantity > 0 && price != null) {
            // Current spot for both market value and cost basis: same rate, so
            // the EUR P/L is exactly the asset's native P/L converted once.
            val marketValueEur = converter.toBase(pos.quantity * price, asset.currency)
            val costBasisEur = converter.toBase(pos.quantity * pos.avgCost, asset.currency)
            holding.marketValueEur = marketValueEur
            holding.costBasisEur = costBasisEur
            holding.unrealizedPnlEur = marketValueEur - costBasisEur
            // FX-independent (numerator and denominator share the asset's currency).
            holding.unrealizedPnlPct =
                if (pos.avgCost > 0) ((price - pos.avgCost) / pos.avgCost) * 100 else null

            val prevClose = asset.quote?.prevClose
            if (prevClose != null) {
                holding.dayChangeEur =
                    converter.toBase(pos.quantity * (price - prevClose), asset.currency)
                holding.dayChangePct =
                    if (prevClose != 0.0) ((price - prevClose) / prevClose) * 100 else null
            }
        }

        holdings.add(holding)
    }

    return holdings
}

// ---------------------------------------------------------------------------
// Value over time
// ---------------------------------------------------------------------------

/** A daily close / custom-asset value point, native currency. */
data class PricePoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /** Close (market asset) or value point (custom asset), native currency. */
    val close: Double,
)

/** Per-asset inputs for [valueOverTime]: currency and its price history. */
data class ValueOverTimeAsset(
    val assetId: String,
    /** ISO-4217 native currency of the asset. */
    val currency: String,
    /**
     * Daily closes or custom-asset value points, native currency, any order.
     * Between points the value carries forward (step function).
     */
    val prices: List<PricePoint>,
)

data class ValueOverTimeInput(
    /** Every transaction across the portfolio (any order). */
    val transactions: List<Transaction>,
    /** One entry per transacted asset; a missing asset throws. */
    val assets: List<ValueOverTimeAsset>,
    /** The last day of the series, ISO `YYYY-MM-DD`. */
    val today: String,
    val converter: CurrencyConverter,
)

/** One point on the portfolio value-over-time series. */
data class ValuePoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /** Total portfolio value in EUR on that day. */
    val valueEur: Double,
)

/** Date portion of an ISO timestamp, validated. */
private fun dayOf(executedAt: String): String {
    val day = executedAt.take(10)
    if (!ISO_DATE.matches(day)) {
        throw DomainException(
            "Transaction executedAt must be an ISO-8601 date/time, got $executedAt",
        )
    }
    return day
}

private fun assertIsoDate(date: String, label: String) {
    if (!ISO_DATE.matches(date)) {
        throw DomainException("$label must be ISO YYYY-MM-DD, got $date")
    }
}

/** UTC midnight epoch-ms of an ISO date (no clock read; deterministic). */
private fun dateToMs(date: String): Double = jsDateOnlyToMs(date)

/**
 * Expand a sparse price series into one close per calendar day over
 * `[startDay, endDay]` — the per-asset overlay series the portfolio graph draws
 * next to the value curve.
 *
 * **Carry-forward is the gap policy** (the same step function [valueOverTime]
 * applies): a weekend, market holiday or provider gap has no close of its own,
 * so the last known close before it is repeated. Days *before* the first
 * available close are omitted rather than invented.
 *
 * Pure and deterministic: unsorted input is sorted, later duplicates of a date
 * win (matching the provider-over-stored merge order upstream), and malformed
 * dates or non-finite closes throw rather than silently mis-plotting.
 */
fun dailyCloseSeries(
    prices: List<PricePoint>,
    startDay: String,
    endDay: String,
): List<PricePoint> {
    assertIsoDate(startDay, "startDay")
    assertIsoDate(endDay, "endDay")
    if (endDay < startDay || prices.isEmpty()) return emptyList()

    for (point in prices) {
        assertIsoDate(point.date, "price point date")
        if (!point.close.isFinite()) {
            throw DomainException(
                "Price point on ${point.date} must be a finite number, got ${jsNum(point.close)}",
            )
        }
    }
    // LinkedHashMap: a repeated date keeps its ORIGINAL insertion position but
    // takes the LATER value — exactly what a JS `Map.set` does.
    val byDate = LinkedHashMap<String, Double>()
    for (p in prices) byDate[p.date] = p.close
    val sorted = byDate.entries.toList()
        .sortedWith { a, b -> if (a.key < b.key) -1 else if (a.key > b.key) 1 else 0 }

    val series = mutableListOf<PricePoint>()
    var idx = 0
    var lastClose: Double? = null
    var ms = dateToMs(startDay)
    val endMs = dateToMs(endDay)
    while (ms <= endMs) {
        val day = jsIsoDay(ms)
        while (idx < sorted.size) {
            val entry = sorted[idx]
            if (entry.key > day) break
            lastClose = entry.value
            idx += 1
        }
        // before the first known close
        if (lastClose != null) series.add(PricePoint(day, lastClose))
        ms += MS_PER_DAY
    }
    return series
}

private class ValueCursor(
    val asset: ValueOverTimeAsset,
    /** sorted ascending by day */
    val txns: List<Transaction>,
    /** sorted ascending by date */
    val prices: List<PricePoint>,
    var txnIdx: Int = 0,
    var priceIdx: Int = 0,
    var qty: Double = 0.0,
    var lastClose: Double? = null,
)

/**
 * Reconstruct the daily portfolio value series in EUR.
 *
 * For every calendar day from the first transaction to `today`:
 * `value(d) = Σ over assets of qty_held(d) · price_native(d) · fx(currency, d)`,
 * where `qty_held(d)` is the net quantity through day `d`, `price_native(d)` is
 * the latest price on or before `d` (carried forward — the step function for
 * sparse data), and `fx` is that day's historical rate into EUR.
 *
 * Returns an empty series when there are no transactions, or when the first
 * transaction is after `today`.
 *
 * FX is **coalesced** to one conversion per (currency, day): the per-asset
 * native contributions are summed by currency first (synchronously), then each
 * distinct (currency, day) rate is fetched once via a memoised lookup. Because
 * conversion is linear, `Σ native · rate` is identical at full precision to
 * converting each asset's contribution individually.
 */
suspend fun valueOverTime(input: ValueOverTimeInput): List<ValuePoint> {
    val (transactions, assets, today, converter) = input
    assertIsoDate(today, "today")

    if (transactions.isEmpty()) return emptyList()

    val assetById = LinkedHashMap<String, ValueOverTimeAsset>()
    for (a in assets) assetById[a.assetId] = a

    // Group transactions by asset and find the series start (earliest day).
    val txnsByAsset = LinkedHashMap<String, MutableList<Transaction>>()
    var startDay: String? = null
    for (t in transactions) {
        val day = dayOf(t.executedAt)
        if (startDay == null || day < startDay) startDay = day
        if (!assetById.containsKey(t.assetId)) {
            throw DomainException(
                "valueOverTime: transaction references asset ${t.assetId} " +
                    "with no price/currency input.",
            )
        }
        val list = txnsByAsset[t.assetId]
        if (list != null) list.add(t) else txnsByAsset[t.assetId] = mutableListOf(t)
    }
    // startDay is non-null here (transactions is non-empty).
    if (startDay == null || startDay > today) return emptyList()

    // Per-asset cursors, walked forward in lockstep with the day loop.
    val cursors = mutableListOf<ValueCursor>()
    for ((assetId, txns) in txnsByAsset) {
        val asset = assetById[assetId] ?: continue // unreachable: validated above.
        // Within-day order is irrelevant here (quantities sum per day), so the day
        // key alone is a consistent sort key.
        val sortedTxns = txns.sortedWith { a, b ->
            val dayA = dayOf(a.executedAt)
            val dayB = dayOf(b.executedAt)
            if (dayA < dayB) -1 else if (dayA > dayB) 1 else 0
        }
        // Validate every point up front — a sort comparator never runs for 0/1
        // element arrays, so validation there would let a lone malformed date or a
        // NaN close silently mis-value the asset.
        for (point in asset.prices) {
            assertIsoDate(point.date, "price point date")
            if (!point.close.isFinite()) {
                throw DomainException(
                    "Price point for ${asset.assetId} on ${point.date} " +
                        "must be a finite number, got ${jsNum(point.close)}",
                )
            }
        }
        val sortedPrices = asset.prices.sortedWith { a, b ->
            if (a.date < b.date) -1 else if (a.date > b.date) 1 else 0
        }
        cursors.add(ValueCursor(asset = asset, txns = sortedTxns, prices = sortedPrices))
    }

    // Pass 1 (sync): per day, sum each asset's native contribution by currency.
    val startMs = dateToMs(startDay)
    val endMs = dateToMs(today)
    val days = mutableListOf<String>()
    val buckets = mutableListOf<LinkedHashMap<String, Double>>()
    var ms = startMs
    while (ms <= endMs) {
        val day = jsIsoDay(ms)
        days.add(day)
        val bucket = LinkedHashMap<String, Double>()

        for (c in cursors) {
            // Advance the holding through every transaction up to and including today.
            while (c.txnIdx < c.txns.size) {
                val txn = c.txns[c.txnIdx]
                if (dayOf(txn.executedAt) > day) break
                c.qty += if (txn.side == TransactionSide.BUY) txn.quantity else -txn.quantity
                // No shorts: an uncovered sell closes the position at 0, it never
                // goes negative — so a later buy rebuilds from 0, not from a
                // phantom debt. This also folds away the sell-everything float dust
                // (~±1e-15) that the display clamp below would otherwise handle.
                if (c.qty < QTY_EPSILON) c.qty = 0.0
                c.txnIdx += 1
            }
            // Advance the price to the latest close on or before today (carry forward).
            while (c.priceIdx < c.prices.size) {
                val point = c.prices[c.priceIdx]
                if (point.date > day) break
                c.lastClose = point.close
                c.priceIdx += 1
            }

            // Clamp float dust / closed positions to exactly flat.
            val heldQty = if (c.qty > QTY_EPSILON) c.qty else 0.0
            val lastClose = c.lastClose
            if (heldQty == 0.0 || lastClose == null) continue

            val native = heldQty * lastClose
            bucket[c.asset.currency] = (bucket[c.asset.currency] ?: 0.0) + native
        }

        buckets.add(bucket)
        ms += MS_PER_DAY
    }

    // Pass 2 (async): resolve each distinct (currency, day) rate exactly once.
    val rateCache = LinkedHashMap<String, Double>()
    suspend fun rateToBase(currency: String, date: String): Double {
        val key = "$currency|$date"
        val cached = rateCache[key]
        if (cached != null) return cached
        val resolved = converter.toBase(1.0, currency, date = date)
        rateCache[key] = resolved
        return resolved
    }

    // Pass 3: combine native sums with their rates into the EUR series.
    val series = mutableListOf<ValuePoint>()
    for (i in days.indices) {
        val day = days[i]
        val bucket = buckets[i]
        var valueEur = 0.0
        for ((currency, native) in bucket) {
            val rate = rateToBase(currency, day)
            if (!rate.isFinite() || rate <= 0) {
                throw DomainException("Invalid FX rate ${jsNum(rate)} for $currency on $day")
            }
            valueEur += native * rate
        }
        series.add(ValuePoint(day, valueEur))
    }

    return series
}

// ---------------------------------------------------------------------------
// Cost basis over time (daily snapshots)
// ---------------------------------------------------------------------------

/** One point on the daily open-cost-basis series. */
data class CostBasisPoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /** Open cost basis (Σ held qty · avg cost) in EUR at that day's FX rate. */
    val costBasisEur: Double,
)

/** Input for [costBasisOverTime] — deliberately the same shape as [ValueOverTimeInput]. */
data class CostBasisOverTimeInput(
    /** Every transaction across the portfolio (any order). */
    val transactions: List<Transaction>,
    /**
     * One entry per transacted asset — the SAME inputs [valueOverTime] takes.
     * Only the price series' *dates* matter here: an asset contributes cost basis
     * on a day exactly when it would contribute value. The close amounts
     * themselves are never read.
     */
    val assets: List<ValueOverTimeAsset>,
    /** The last day of the series, ISO `YYYY-MM-DD`. */
    val today: String,
    val converter: CurrencyConverter,
)

private class CostBasisState(val day: String, val quantity: Double, val avgCost: Double)

private class CostBasisCursor(
    val currency: String,
    /** Ascending distinct txn days, each with the reduced state through that day. */
    val states: List<CostBasisState>,
    /** sorted ascending */
    val priceDates: List<String>,
    var stateIdx: Int = 0,
    var priceIdx: Int = 0,
    var quantity: Double = 0.0,
    var avgCost: Double = 0.0,
    var priced: Boolean = false,
)

/**
 * Reconstruct the daily **open cost basis** series in EUR: for every calendar
 * day from the first transaction to `today`,
 * `cost(d) = Σ over assets of qty_held(d) · avg_cost(d) · fx(ccy, d)`.
 *
 * The position math is **not re-derived here**: each asset's `(qty, avgCost)` as
 * of a day is [reducePosition] replayed over exactly the transactions up to and
 * including that day (prefixes preserve the input's relative order, so
 * same-instant tie-breaking matches a full replay — no forked formulas). Between
 * transaction days the state carries forward. Conversion happens at each day's
 * **historical** FX rate, coalesced to one lookup per (currency, day), so the
 * derived `pl(d) = holdingsValue(d) − cost(d)` compares like with like day by day.
 *
 * An asset contributes only from the day its first price is known (the
 * [valueOverTime] gate): before that the value series carries 0 for it, and a
 * nonzero cost against a zero value would fake a total loss.
 */
suspend fun costBasisOverTime(input: CostBasisOverTimeInput): List<CostBasisPoint> {
    val (transactions, assets, today, converter) = input
    assertIsoDate(today, "today")

    if (transactions.isEmpty()) return emptyList()

    val assetById = LinkedHashMap<String, ValueOverTimeAsset>()
    for (a in assets) assetById[a.assetId] = a

    // Group transactions by asset (original order preserved) + find the start day.
    val txnsByAsset = LinkedHashMap<String, MutableList<Transaction>>()
    var startDay: String? = null
    for (t in transactions) {
        val day = dayOf(t.executedAt)
        if (startDay == null || day < startDay) startDay = day
        if (!assetById.containsKey(t.assetId)) {
            throw DomainException(
                "costBasisOverTime: transaction references asset ${t.assetId} " +
                    "with no price/currency input.",
            )
        }
        val list = txnsByAsset[t.assetId]
        if (list != null) list.add(t) else txnsByAsset[t.assetId] = mutableListOf(t)
    }
    if (startDay == null || startDay > today) return emptyList()

    val cursors = mutableListOf<CostBasisCursor>()
    for ((assetId, txns) in txnsByAsset) {
        val asset = assetById[assetId] ?: continue // unreachable: validated above
        // `[...new Set(...)].sort()` — a BARE JS sort, i.e. string comparison
        // (plan §3.3 rule 5); these are already strings, so natural order matches.
        val distinctDays = LinkedHashSet(txns.map { dayOf(it.executedAt) }).sorted()
        // Prefix replays reuse reducePosition verbatim — the money math has one
        // home. A filter preserves relative input order, so ties resolve exactly
        // as they would in a full replay.
        val states = distinctDays.map { day ->
            val prefix = txns.filter { dayOf(it.executedAt) <= day }
            val state = reducePosition(prefix)
            CostBasisState(day, state.quantity, state.avgCost)
        }
        for (point in asset.prices) assertIsoDate(point.date, "price point date")
        val priceDates = asset.prices.map { it.date }.sorted()
        cursors.add(
            CostBasisCursor(currency = asset.currency, states = states, priceDates = priceDates),
        )
    }

    // Pass 1 (sync): per day, sum each asset's native open cost by currency.
    val startMs = dateToMs(startDay)
    val endMs = dateToMs(today)
    val days = mutableListOf<String>()
    val buckets = mutableListOf<LinkedHashMap<String, Double>>()
    var ms = startMs
    while (ms <= endMs) {
        val day = jsIsoDay(ms)
        days.add(day)
        val bucket = LinkedHashMap<String, Double>()

        for (c in cursors) {
            while (c.stateIdx < c.states.size) {
                val state = c.states[c.stateIdx]
                if (state.day > day) break
                c.quantity = state.quantity
                c.avgCost = state.avgCost
                c.stateIdx += 1
            }
            while (c.priceIdx < c.priceDates.size) {
                val date = c.priceDates[c.priceIdx]
                if (date > day) break
                c.priced = true
                c.priceIdx += 1
            }
            if (!c.priced || c.quantity <= QTY_EPSILON) continue
            val native = c.quantity * c.avgCost
            if (native == 0.0) continue
            bucket[c.currency] = (bucket[c.currency] ?: 0.0) + native
        }

        buckets.add(bucket)
        ms += MS_PER_DAY
    }

    // Pass 2 (async): one FX resolution per distinct (currency, day) — the same
    // coalescing valueOverTime applies, and conversion is linear so summing
    // native amounts first is exact.
    val rateCache = LinkedHashMap<String, Double>()
    suspend fun rateToBase(currency: String, date: String): Double {
        val key = "$currency|$date"
        val cached = rateCache[key]
        if (cached != null) return cached
        val resolved = converter.toBase(1.0, currency, date = date)
        rateCache[key] = resolved
        return resolved
    }

    val series = mutableListOf<CostBasisPoint>()
    for (i in days.indices) {
        val day = days[i]
        val bucket = buckets[i]
        var costBasisEur = 0.0
        for ((currency, native) in bucket) {
            val rate = rateToBase(currency, day)
            if (!rate.isFinite() || rate <= 0) {
                throw DomainException("Invalid FX rate ${jsNum(rate)} for $currency on $day")
            }
            costBasisEur += native * rate
        }
        series.add(CostBasisPoint(day, costBasisEur))
    }

    return series
}

// ---------------------------------------------------------------------------
// Performance over time — time-weighted return
// ---------------------------------------------------------------------------

/** One day's net **external** cash flow into the portfolio, EUR. */
data class FlowPoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /**
     * Net flow that day in EUR: money moving *into* the portfolio is positive
     * (a BUY costs `qty · price + fee`), money moving *out* is negative (a SELL
     * returns `qty · price − fee`). Fees therefore stay inside the flow, so the
     * derived performance is **net of transaction costs**.
     */
    val flowEur: Double,
)

/** Input for [netFlowsOverTime]. */
data class NetFlowsInput(
    /** Every transaction across the portfolio (any order). */
    val transactions: List<Transaction>,
    /** ISO-4217 native currency per transacted asset id; a missing asset throws. */
    val currencyByAsset: Map<String, String>,
    val converter: CurrencyConverter,
)

/**
 * The portfolio's daily net external cash flows in EUR — the companion series
 * [timeWeightedReturn] needs to strip deposits and withdrawals out of the value
 * curve.
 *
 * In BetterTrack there is no cash balance at this layer: transactions *are* the
 * external flows. A BUY is money entering (cost plus fee), a SELL is money
 * leaving (proceeds net of fee). Same-day flows aggregate per (currency, day)
 * first — conversion is linear, so summing native amounts before converting is
 * exact — and each distinct (currency, day) rate is fetched once.
 *
 * Returns a **sparse** series (only days with a flow), sorted ascending.
 */
suspend fun netFlowsOverTime(input: NetFlowsInput): List<FlowPoint> {
    val (transactions, currencyByAsset, converter) = input

    // Pass 1 (sync): signed native flow summed per (day, currency).
    val nativeByDay = LinkedHashMap<String, LinkedHashMap<String, Double>>()
    for (t in transactions) {
        val day = dayOf(t.executedAt)
        val currency = currencyByAsset[t.assetId]
            ?: throw DomainException(
                "netFlowsOverTime: transaction references asset ${t.assetId} " +
                    "with no currency input.",
            )
        if (!t.quantity.isFinite() || !t.price.isFinite() || !t.fee.isFinite()) {
            throw DomainException(
                "netFlowsOverTime: non-finite quantity/price/fee on ${t.executedAt}",
            )
        }
        val native =
            if (t.side == TransactionSide.BUY) {
                t.quantity * t.price + t.fee
            } else {
                -(t.quantity * t.price - t.fee)
            }
        val bucket = nativeByDay[day] ?: LinkedHashMap()
        bucket[currency] = (bucket[currency] ?: 0.0) + native
        nativeByDay[day] = bucket
    }

    // Pass 2 (async): one FX resolution per distinct (currency, day).
    val rateCache = LinkedHashMap<String, Double>()
    suspend fun rateToBase(currency: String, date: String): Double {
        val key = "$currency|$date"
        val cached = rateCache[key]
        if (cached != null) return cached
        val resolved = converter.toBase(1.0, currency, date = date)
        rateCache[key] = resolved
        return resolved
    }

    // `[...keys()].sort()` — bare JS sort, i.e. string comparison (rule 5).
    val days = nativeByDay.keys.sorted()
    val flows = mutableListOf<FlowPoint>()
    for (day in days) {
        val bucket = nativeByDay[day] ?: continue // unreachable
        var flowEur = 0.0
        for ((currency, native) in bucket) {
            val rate = rateToBase(currency, day)
            if (!rate.isFinite() || rate <= 0) {
                throw DomainException("Invalid FX rate ${jsNum(rate)} for $currency on $day")
            }
            flowEur += native * rate
        }
        flows.add(FlowPoint(day, flowEur))
    }
    return flows
}

/** One point on the performance (time-weighted return) series. */
data class PerformancePoint(
    /** ISO `YYYY-MM-DD`. */
    val date: String,
    /** Cumulative time-weighted return since the series start, in percent (0 = flat). */
    val pct: Double,
)

/**
 * Cash-flow-neutralized performance of the value series: the daily
 * **time-weighted return**, chain-linked and expressed as a cumulative
 * percentage. A 1 000 € deposit causes **no** jump — the curve moves only when
 * holdings move.
 *
 * Daily linking uses the robust hybrid flow convention: **inflows count at the
 * start of the day, outflows at the end** —
 *
 *     r_d = (V_d − min(F_d, 0)) / (V_{d−1} + max(F_d, 0))
 *
 * so a buy's execution→close move on the new money is genuine day-`d`
 * performance, while a full liquidation still books its final day correctly
 * (`V_d = 0` with the proceeds in the numerator) instead of collapsing to
 * −100 %. Degenerate segments — a zero denominator (nothing invested yet, or a
 * flat stretch after selling everything) or a zero numerator (a day whose value
 * is 0 only because no price is known yet) — carry no performance information
 * and link as flat (`r = 1`); the curve simply resumes when data does. This
 * keeps the chained index strictly positive, so a later rebase is always
 * well-defined.
 *
 * Inflows on such pre-price days are not lost: while the value is unmeasurable,
 * incoming cash accumulates into the linking base, so the first real value point
 * links against the money actually put in.
 *
 * A zero-value day **without** a flow is treated as a data gap, not a
 * liquidation, and the previous real value is kept as the next day's linking
 * base. Flows on days outside the value series are ignored.
 */
fun timeWeightedReturn(
    values: List<ValuePoint>,
    flows: List<FlowPoint>,
): List<PerformancePoint> {
    val flowByDate = LinkedHashMap<String, Double>()
    for (f in flows) {
        assertIsoDate(f.date, "flow point date")
        if (!f.flowEur.isFinite()) {
            throw DomainException(
                "Flow on ${f.date} must be a finite number, got ${jsNum(f.flowEur)}",
            )
        }
        flowByDate[f.date] = (flowByDate[f.date] ?: 0.0) + f.flowEur
    }

    val sorted = values.sortedWith { a, b ->
        if (a.date < b.date) -1 else if (a.date > b.date) 1 else 0
    }
    val series = mutableListOf<PerformancePoint>()
    var index = 1.0
    var prevValue = 0.0
    for (point in sorted) {
        assertIsoDate(point.date, "value point date")
        if (!point.valueEur.isFinite()) {
            throw DomainException(
                "Value on ${point.date} must be a finite number, got ${jsNum(point.valueEur)}",
            )
        }
        val flow = flowByDate[point.date] ?: 0.0
        val numerator = point.valueEur - min(flow, 0.0)
        val denominator = prevValue + max(flow, 0.0)
        val r =
            if (numerator > VALUE_EPSILON && denominator > VALUE_EPSILON) {
                numerator / denominator
            } else {
                1.0
            }
        index *= r
        series.add(PerformancePoint(point.date, (index - 1) * 100))
        // Next day's linking base (see docstring):
        //  - a real value is the base;
        //  - a flow-less zero-value day is a data gap — keep the last base;
        //  - an INFLOW day with no measurable value means the assets have no
        //    price yet: the cash that came in IS the invested basis, accumulate it;
        //  - an OUTFLOW day with no value is a genuine liquidation and the base
        //    resets (that day's return was already booked via the numerator).
        if (point.valueEur > VALUE_EPSILON) {
            prevValue = point.valueEur
        } else if (flow > 0) {
            prevValue += flow
        } else if (flow < 0) {
            prevValue = 0.0
        }
    }
    return series
}

/**
 * Re-express a performance series relative to its own first point: the first
 * point becomes 0 % and every later point the TWR **since that window start** —
 * what a range-sliced (1M/6M/1Y) performance chart shows. Compounding, not
 * subtraction: percentages don't add across time.
 */
fun rebasePerformance(points: List<PerformancePoint>): List<PerformancePoint> {
    val first = points.firstOrNull() ?: return emptyList()
    val base = 1 + first.pct / 100
    // timeWeightedReturn keeps the chained index strictly positive, so a
    // non-positive base means corrupted input — fail loud on the money path.
    if (!base.isFinite() || base <= 0) {
        throw DomainException(
            "rebasePerformance: non-positive base index ${jsNum(base)} at ${first.date}",
        )
    }
    return points.map { p -> PerformancePoint(p.date, ((1 + p.pct / 100) / base - 1) * 100) }
}
