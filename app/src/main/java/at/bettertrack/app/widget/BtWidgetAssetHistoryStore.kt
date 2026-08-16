package at.bettertrack.app.widget

import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.MetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The asset hero's price-history cache (round 2b): one thinned close series per
 * CONFIGURED asset-widget asset, from the real `GET /assets/{id}/history` —
 * the server DOES serve a 3M range for assets, so the hero's chart is honest.
 *
 * Same `meta` blob pattern and reasoning as [BtWidgetQuoteStore]: asset reads
 * are transient in the app by design, a headless widget needs SOMETHING to
 * draw from, and the meta table's account-scoped wipe keeps one account's
 * series off the next account's launcher. Values are native-currency closes —
 * the chart is SHAPE only, plus the average-cost reference which arrives in the
 * same native unit from the server's own holding row.
 *
 * Bounded like every warm: only assets a placed configured asset widget shows
 * ([btWidgetConfiguredAssets]), series thinned to [BT_WIDGET_SPARK_MAX_POINTS]
 * before storing.
 */
@Serializable
data class BtWidgetAssetSeries(
    /** The RANGE the server actually answered with, as wire text ("3M"). */
    val range: String = "",
    val closes: List<Double> = emptyList(),
)

@Serializable
data class BtWidgetAssetHistoryCache(
    val cachedAtMs: Long = 0L,
    val series: Map<String, BtWidgetAssetSeries> = emptyMap(),
) {
    companion object {
        val EMPTY = BtWidgetAssetHistoryCache()
    }
}

object BtWidgetAssetHistoryStore {

    /** `meta` key. Namespaced so it is obviously not portfolio state. */
    const val KEY: String = "widget_asset_history_cache"

    suspend fun read(db: BtDatabase, json: Json): BtWidgetAssetHistoryCache {
        val raw = db.metaDao().get(KEY) ?: return BtWidgetAssetHistoryCache.EMPTY
        return runCatching { json.decodeFromString(BtWidgetAssetHistoryCache.serializer(), raw) }
            .getOrDefault(BtWidgetAssetHistoryCache.EMPTY)
    }

    suspend fun write(db: BtDatabase, json: Json, cache: BtWidgetAssetHistoryCache) {
        val raw = json.encodeToString(BtWidgetAssetHistoryCache.serializer(), cache)
        db.metaDao().put(MetaEntity(key = KEY, value = raw))
    }
}

/**
 * Merge a warm pass into the cache: fetched series replace, assets no longer
 * configured drop, a pass that fetched nothing keeps the clock — the same
 * policy as [btWidgetMergeQuotes], for the same "as of" honesty.
 */
fun btWidgetMergeAssetHistory(
    previous: BtWidgetAssetHistoryCache,
    fetched: Map<String, BtWidgetAssetSeries>,
    keep: Set<String>,
    nowMs: Long,
): BtWidgetAssetHistoryCache = BtWidgetAssetHistoryCache(
    cachedAtMs = if (fetched.isEmpty()) previous.cachedAtMs else nowMs,
    series = (previous.series.filterKeys { it in keep } + fetched),
)
