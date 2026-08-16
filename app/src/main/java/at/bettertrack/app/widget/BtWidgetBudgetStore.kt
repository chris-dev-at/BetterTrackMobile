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
 * One per-tag outflow row of the month, for the Spending widget's donut —
 * flattened out of [at.bettertrack.app.data.api.dto.CashTagSummaryDto].
 *
 * [untagged] marks the summary's tagId-null bucket, whose label the widget
 * localises; [name] is "" on that row. The summary DTO's warning travels with
 * the data: a movement carrying two tags counts fully in BOTH rows, so these
 * are a breakdown whose sum may exceed [BtWidgetBudgetCache.totalOutflowEur] —
 * never derive a total from them.
 */
@Serializable
data class BtWidgetTagSpend(
    val name: String = "",
    val outflow: Double = 0.0,
    val untagged: Boolean = false,
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
 *
 * Since the Spending widget (2026-08-16) the same blob also carries the month's
 * summary breakdown ([tags], [totalInflowEur], [totalOutflowEur]) — both widgets
 * ride the SAME `/cash/summary` read the worker was already making for the
 * header, so caching them together costs no extra request and cannot let the
 * two widgets show two different months. Every new field defaults, so a blob
 * written by an older build still decodes (a missing breakdown is an empty
 * donut, not a crash).
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
    /** Server-computed month totals (EUR); null when the summary read failed. */
    val totalInflowEur: Double? = null,
    val totalOutflowEur: Double? = null,
    /** Per-tag outflow breakdown, as the summary sent it (outflow-heaviest first). */
    val tags: List<BtWidgetTagSpend> = emptyList(),
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
 * progress); [summary] is an optional companion read that fills the header's
 * month-net figure and the Spending widget's breakdown — when it failed the
 * budgets still render, so its absence is a missing header line and an empty
 * spending donut, never a missing widget.
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
    totalInflowEur = summary?.totalInflow,
    totalOutflowEur = summary?.totalOutflow,
    tags = summary?.tags.orEmpty().map { tag ->
        BtWidgetTagSpend(
            // tagId == null is the summary's UNTAGGED bucket (its name is null
            // with it); the widget supplies the localised label for that row.
            name = tag.name.orEmpty(),
            outflow = tag.outflow,
            untagged = tag.tagId == null,
        )
    },
)
