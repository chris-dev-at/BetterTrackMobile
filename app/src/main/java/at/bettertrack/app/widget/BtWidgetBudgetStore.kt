package at.bettertrack.app.widget

import at.bettertrack.app.data.api.dto.CashBudgetListResponse
import at.bettertrack.app.data.api.dto.CashSummaryResponse
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One budget's evaluated progress for the current month — flattened out of
 * [at.bettertrack.app.data.api.dto.CashBudgetProgressDto], carrying only what a
 * progress bar draws.
 *
 * [spent] and [amount] are already the server's figures in [currency]; the widget
 * divides them for the bar fill but computes no money of its own (see
 * [btWidgetBudgetFraction]). [exceeded] is the server's `spent > amount` — the
 * widget trusts it rather than recomputing the comparison, so the colour and the
 * alert that fired agree by construction.
 */
@Serializable
data class BtWidgetBudget(
    val id: String,
    val tagName: String = "",
    val spent: Double = 0.0,
    val amount: Double = 0.0,
    val currency: String = "EUR",
    val exceeded: Boolean = false,
)

/**
 * The persisted budget blob: the rows, the month they were evaluated for, the
 * portfolio they belong to (so a tap can open THAT ledger), and whether the cash
 * layer is reachable at all.
 *
 * [available] is the one field that is not data: it is `false` in Drive-autonomous
 * mode (no server ledger to classify) and after a `/cash` 403 (the account has no
 * `cash:read` scope). The widget renders "not available" for that, distinct from
 * [budgets] being empty, which means "a server account with no budgets set yet".
 */
@Serializable
data class BtWidgetBudgetCache(
    val cachedAtMs: Long = 0L,
    val available: Boolean = true,
    val portfolioId: String? = null,
    /** The month the rows were evaluated for (`YYYY-MM`); "" until first fetch. */
    val period: String = "",
    /** Server-computed month net (inflow − outflow), EUR, for the header; null when unknown. */
    val netEur: Double? = null,
    val budgets: List<BtWidgetBudget> = emptyList(),
) {
    companion object {
        /** A server account with nothing fetched yet, or no budgets — the empty board. */
        val EMPTY = BtWidgetBudgetCache()

        /** The cash layer cannot serve this install — Drive mode, or no cash scope. */
        val UNAVAILABLE = BtWidgetBudgetCache(available = false)
    }
}

/**
 * Where the widget's budget snapshot lives between refreshes.
 *
 * ## Why the `meta` table and not a new one — same reasoning as [BtWidgetQuoteStore]
 *
 * Budgets are server-computed and NETWORK-only: [at.bettertrack.app.data.cash.CashClassificationRepository]
 * states outright that "a stale cached budget is a wrong number, not a degraded
 * one", so nothing in Room persists one. A headless widget needs SOMETHING to draw
 * from while the process is asleep, and the `meta` `(key, value)` store already
 * carries JSON blobs for exactly this shape (`KEY_PORTFOLIO_KINDS`, the widget
 * quote cache). So this follows a precedent instead of adding a v10→v11 migration
 * for one map a single widget reads.
 *
 * It inherits `meta`'s account scoping: `AccountDataManager` wipes the table on
 * logout and on an account switch, so one account's budgets cannot outlive their
 * session on someone else's home screen. Reads are defensive — a blob written by
 * an older build is a cache miss, never a crash in a background process.
 */
object BtWidgetBudgetStore {

    /** `meta` key. Namespaced so it is obviously not portfolio state. */
    const val KEY: String = "widget_budget_cache"

    suspend fun read(db: BtDatabase, json: Json): BtWidgetBudgetCache {
        val raw = db.metaDao().get(KEY) ?: return BtWidgetBudgetCache.EMPTY
        return runCatching { json.decodeFromString(BtWidgetBudgetCache.serializer(), raw) }
            .getOrDefault(BtWidgetBudgetCache.EMPTY)
    }

    suspend fun write(db: BtDatabase, json: Json, cache: BtWidgetBudgetCache) {
        val raw = json.encodeToString(BtWidgetBudgetCache.serializer(), cache)
        db.metaDao().put(MetaEntity(key = KEY, value = raw))
    }
}

/**
 * Build the cache from a refresh pass. Pure, so the flattening is testable.
 *
 * The budget rows come from `GET /cash/budgets` (the authoritative per-tag
 * progress); [summary] is an optional companion read that only fills the header's
 * month-net figure — when it failed the budgets still render, so its absence is a
 * missing header line, never a missing widget.
 */
fun btWidgetBudgetCache(
    portfolioId: String,
    budgets: CashBudgetListResponse,
    summary: CashSummaryResponse?,
    nowMs: Long,
): BtWidgetBudgetCache = BtWidgetBudgetCache(
    cachedAtMs = nowMs,
    available = true,
    portfolioId = portfolioId,
    period = budgets.period,
    netEur = summary?.net,
    budgets = budgets.budgets.map { row ->
        BtWidgetBudget(
            id = row.id,
            tagName = row.tagName,
            spent = row.spent,
            amount = row.amount,
            currency = row.currency,
            exceeded = row.exceeded,
        )
    },
)
