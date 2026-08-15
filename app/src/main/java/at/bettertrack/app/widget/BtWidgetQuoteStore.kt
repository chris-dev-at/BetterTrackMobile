package at.bettertrack.app.widget

import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One watched asset's last-known quote. EUR, exactly as the app's rows show it. */
@Serializable
data class BtWidgetQuote(
    val eurPrice: Double? = null,
    val dayChangePct: Double? = null,
)

/** The persisted blob: the quotes plus the wall clock they were captured at. */
@Serializable
data class BtWidgetQuoteCache(
    val cachedAtMs: Long = 0L,
    val quotes: Map<String, BtWidgetQuote> = emptyMap(),
) {
    companion object {
        val EMPTY = BtWidgetQuoteCache()
    }
}

/**
 * Where a widget's watchlist prices live between refreshes.
 *
 * ## Why the `meta` table and not a new one
 *
 * The widget needs a price per watched asset while the process is asleep, and
 * nothing in the app persists one: `watchlist_items` is identity only, and
 * `MarketRepository` states outright that asset reads "are transient by design
 * (never cached)". `price_cache` exists but is written ONLY by the Drive-mode
 * `NoLivePricesMarketDataSource`, holds daily closes rather than quotes, and
 * carries no day-change — so in server mode it is empty and in either mode it is
 * the wrong shape.
 *
 * A new Room entity would mean a schema migration (v10 → v11) and a new row in
 * the migration regression suite, for one map that a single feature reads. The
 * `meta` table is already a free-form `(key, value)` store with an upserting
 * `put`, and it already carries a JSON blob for exactly this reason
 * (`KEY_PORTFOLIO_KINDS`, written by `PortfolioRepository`). So this is a
 * code-only change that follows a precedent instead of setting one.
 *
 * It inherits `meta`'s account scoping for free, which is the behaviour you want
 * anyway: `AccountDataManager` wipes the table on logout and on an account
 * switch, so one user's prices cannot outlive their session on someone else's
 * home screen.
 *
 * Reads are defensive in the same style as `PortfolioRepository.decodeKinds`: a
 * blob written by an older build is a cache miss, never a crash in a background
 * process.
 */
object BtWidgetQuoteStore {

    /** `meta` key. Namespaced so it is obviously not portfolio state. */
    const val KEY: String = "widget_quote_cache"

    suspend fun read(db: BtDatabase, json: Json): BtWidgetQuoteCache {
        val raw = db.metaDao().get(KEY) ?: return BtWidgetQuoteCache.EMPTY
        return runCatching { json.decodeFromString(BtWidgetQuoteCache.serializer(), raw) }
            .getOrDefault(BtWidgetQuoteCache.EMPTY)
    }

    suspend fun write(db: BtDatabase, json: Json, cache: BtWidgetQuoteCache) {
        val raw = json.encodeToString(BtWidgetQuoteCache.serializer(), cache)
        db.metaDao().put(MetaEntity(key = KEY, value = raw))
    }
}

/**
 * Merge a refresh pass into the cache.
 *
 * Pure so the merge policy is testable, and the policy matters: assets whose
 * fetch FAILED keep their previous quote rather than blanking. One refused
 * request in a twelve-asset fan-out should age a single row, not empty the
 * widget — the "as of" note is what tells the user the figures have drifted.
 *
 * [keep] is the set of assets still on the board, so an asset the user removed
 * from their watchlist is dropped rather than cached forever.
 */
fun btWidgetMergeQuotes(
    previous: BtWidgetQuoteCache,
    fetched: Map<String, BtWidgetQuote>,
    keep: Set<String>,
    nowMs: Long,
): BtWidgetQuoteCache {
    val merged = (previous.quotes + fetched).filterKeys { it in keep }
    return BtWidgetQuoteCache(
        // Only a pass that landed something advances the clock; otherwise the
        // "as of" note would keep resetting while the data never changed.
        cachedAtMs = if (fetched.isEmpty()) previous.cachedAtMs else nowMs,
        quotes = merged,
    )
}
