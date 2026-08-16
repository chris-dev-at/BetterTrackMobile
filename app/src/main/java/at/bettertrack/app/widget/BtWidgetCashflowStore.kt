package at.bettertrack.app.widget

import at.bettertrack.app.data.api.dto.CashTrendResponse
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One month of the trend window — `GET /cash/trends`' point, flattened. */
@Serializable
data class BtWidgetCashflowPoint(
    /** `YYYY-MM`. */
    val month: String = "",
    /** Positive magnitude of the month's inflows, server-computed EUR. */
    val inflow: Double = 0.0,
    /** Positive magnitude of the month's outflows, server-computed EUR. */
    val outflow: Double = 0.0,
)

/**
 * The persisted cash-flow blob for the Monthly-flow widget's bars mode
 * (reinstated in round 2 — the family merged spending and cash flow, so the
 * trend window is back): the window oldest→newest exactly as the server sent
 * it, the portfolio it belongs to (so a tap opens THAT ledger), and the same
 * [available] tri-state the budget cache carries — `false` for Drive-autonomous
 * mode and for a `/cash` 403, where the account genuinely has no ledger.
 */
@Serializable
data class BtWidgetCashflowCache(
    val cachedAtMs: Long = 0L,
    val available: Boolean = true,
    val portfolioId: String? = null,
    val points: List<BtWidgetCashflowPoint> = emptyList(),
) {
    companion object {
        /** A server account with nothing fetched yet — the empty board. */
        val EMPTY = BtWidgetCashflowCache()

        /** The cash layer cannot serve this install — Drive mode, or no cash scope. */
        val UNAVAILABLE = BtWidgetCashflowCache(available = false)
    }
}

/**
 * How many months the widget's trend window asks for. Six columns fit a 4x2
 * with legible month labels; a per-instance 6/12 knob would need per-window
 * caches for one shared blob, so the window stays fixed (reported as a dropped
 * knob in the round-2 report).
 */
const val BT_WIDGET_CASHFLOW_MONTHS: Int = 6

/**
 * Where the widget's trend window lives between refreshes — the `meta` `(key,
 * value)` JSON-blob pattern, for exactly the reasons [BtWidgetQuoteStore] and
 * [BtWidgetBudgetStore] already wrote down: trends are server-computed and
 * network-only, nothing else in Room persists them, and the `meta` table's
 * account-scoped wipe means one account's cash flow cannot outlive its session
 * on someone else's home screen. Reads are defensive — an old build's blob is a
 * cache miss, never a launcher crash.
 */
object BtWidgetCashflowStore {

    /** `meta` key. Namespaced so it is obviously not portfolio state. */
    const val KEY: String = "widget_cashflow_cache"

    suspend fun read(db: BtDatabase, json: Json): BtWidgetCashflowCache {
        val raw = db.metaDao().get(KEY) ?: return BtWidgetCashflowCache.EMPTY
        return runCatching { json.decodeFromString(BtWidgetCashflowCache.serializer(), raw) }
            .getOrDefault(BtWidgetCashflowCache.EMPTY)
    }

    suspend fun write(db: BtDatabase, json: Json, cache: BtWidgetCashflowCache) {
        val raw = json.encodeToString(BtWidgetCashflowCache.serializer(), cache)
        db.metaDao().put(MetaEntity(key = KEY, value = raw))
    }
}

/** Build the cache from a refresh pass. Pure, so the flattening is testable. */
fun btWidgetCashflowCache(
    portfolioId: String,
    trends: CashTrendResponse,
    nowMs: Long,
): BtWidgetCashflowCache = BtWidgetCashflowCache(
    cachedAtMs = nowMs,
    available = true,
    portfolioId = portfolioId,
    points = trends.points.map { p ->
        BtWidgetCashflowPoint(month = p.month, inflow = p.inflow, outflow = p.outflow)
    },
)
