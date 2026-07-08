package at.bettertrack.app.data.repo

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AddToWorkboardRequest
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

// ── Domain models (UI never sees wire DTOs) ─────────────────────────────────

/** A market asset identity (search result / asset header). */
data class MarketAsset(
    val id: String,
    val symbol: String,
    val name: String,
    val exchange: String?,
    /** stock | etf | index | fx | commodity | crypto | custom. */
    val type: String,
    val currency: String,
    val isCustom: Boolean,
)

/** One search response: server results + whether providers are still resolving. */
data class SearchOutcome(
    val results: List<MarketAsset>,
    val enriching: Boolean,
)

/** Asset header snapshot: identity + latest quote + server-converted EUR price. */
data class AssetSnapshot(
    val asset: MarketAsset,
    val nativePrice: Double?,
    val quoteCurrency: String,
    val dayChangePct: Double?,
    val prevClose: Double?,
    val eurPrice: Double?,
    val asOf: String?,
    val stale: Boolean,
)

/** One close observation of a price series (x is epoch-ms so intraday works). */
data class PricePoint(val timeMs: Long, val close: Double)

data class AssetPriceSeries(
    val range: AssetRange,
    val points: List<PricePoint>,
)

/**
 * Asset-chart ranges the platform serves (`GET /assets/{id}/history` — the FULL
 * spec set, unlike portfolio history). The server chooses the interval.
 */
enum class AssetRange(val wire: String, val label: String) {
    D1("1D", "1D"),
    W1("1W", "1W"),
    M1("1M", "1M"),
    M3("3M", "3M"),
    M6("6M", "6M"),
    Y1("1Y", "1Y"),
    Y5("5Y", "5Y"),
    MAX("MAX", "Max"),
    ;

    companion object {
        val DEFAULT = M1
        fun fromWire(wire: String): AssetRange? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Market repository (Step 11, §6.5 — online-only): global search, asset detail,
 * price history, and the single unnamed workboard watchlist (§6.6). Search and
 * asset reads are transient (never cached — they are online-only surfaces);
 * watchlist MEMBERSHIP is cached in Room so the star state renders offline.
 * The server is the only price source — nothing here computes a price.
 */
class MarketRepository(
    private val api: BtApi,
    private val db: BtDatabase,
    private val json: Json,
) {
    // ── Watchlist (workboard) membership — cached ────────────────────────────

    /** Asset ids currently on the workboard watchlist (drives the star state). */
    val watchlistAssetIds: Flow<Set<String>> =
        db.watchlistDao().observeItems(WatchlistEntity.WORKBOARD_ID)
            .map { items -> items.map { it.assetId }.toSet() }

    val watchlistItems: Flow<List<WatchlistItemEntity>> =
        db.watchlistDao().observeItems(WatchlistEntity.WORKBOARD_ID)

    // ── Search (§6.5) ────────────────────────────────────────────────────────

    suspend fun search(query: String): BtResult<SearchOutcome> =
        when (val r = apiCall(json) { api.search(query) }) {
            is BtResult.Ok -> BtResult.Ok(
                SearchOutcome(
                    results = r.value.results.map {
                        MarketAsset(it.id, it.symbol, it.name, it.exchange, it.type, it.currency, it.isCustom)
                    },
                    enriching = r.value.enriching,
                ),
            )

            is BtResult.Err -> r
        }

    // ── Asset detail + history (§6.5) ────────────────────────────────────────

    suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> =
        when (val r = apiCall(json) { api.assetDetail(assetId) }) {
            is BtResult.Ok -> {
                val a = r.value.asset
                BtResult.Ok(
                    AssetSnapshot(
                        asset = MarketAsset(a.id, a.symbol, a.name, a.exchange, a.type, a.currency, a.isCustom),
                        nativePrice = r.value.quote?.price,
                        quoteCurrency = r.value.quote?.currency ?: a.currency,
                        dayChangePct = r.value.quote?.dayChangePct,
                        prevClose = r.value.quote?.prevClose,
                        eurPrice = r.value.eurPrice,
                        asOf = r.value.asOf ?: r.value.quote?.asOf,
                        stale = r.value.stale,
                    ),
                )
            }

            is BtResult.Err -> r
        }

    /** Daily closes (ascending by time) — feeds the form's date→price link. */
    suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        when (val r = apiCall(json) { api.assetDailyCloses(assetId) }) {
            is BtResult.Ok -> BtResult.Ok(
                r.value.points.mapNotNull { p -> parseIsoToMs(p.time)?.let { PricePoint(it, p.close) } }
                    .sortedBy { it.timeMs },
            )

            is BtResult.Err -> r
        }

    suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        when (val r = apiCall(json) { api.assetHistory(assetId, range.wire) }) {
            is BtResult.Ok -> {
                val serverRange = AssetRange.fromWire(r.value.range) ?: range
                val points = r.value.points.mapNotNull { p ->
                    parseIsoToMs(p.time)?.let { PricePoint(it, p.close) }
                }.sortedBy { it.timeMs }
                BtResult.Ok(AssetPriceSeries(serverRange, points))
            }

            is BtResult.Err -> r
        }

    // ── Workboard watchlist mutations (§6.6, online-only) ────────────────────

    /** Refresh the single workboard watchlist into Room (membership + rows). */
    suspend fun refreshWorkboard(): BtResult<Unit> =
        when (val r = apiCall(json) { api.workboard() }) {
            is BtResult.Ok -> {
                db.watchlistDao().replaceList(
                    WatchlistEntity(WatchlistEntity.WORKBOARD_ID, "General", isDefault = true, sortOrder = 0),
                    r.value.items.map { it.toEntity() },
                )
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /** Add an asset to the watchlist; caches the returned item immediately. */
    suspend fun addToWatchlist(assetId: String): BtResult<Unit> {
        val resp = try {
            api.addToWorkboard(AddToWorkboardRequest(assetId))
        } catch (_: java.io.IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            resp.body()?.let { db.watchlistDao().insertItems(listOf(it.toEntity())) }
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    /** Remove an asset from the watchlist (looks up its item id from cache). */
    suspend fun removeFromWatchlist(assetId: String): BtResult<Unit> {
        val item = db.watchlistDao().itemForAsset(WatchlistEntity.WORKBOARD_ID, assetId)
            ?: return BtResult.Ok(Unit) // already absent
        val resp = try {
            api.removeFromWorkboard(item.id)
        } catch (_: java.io.IOException) {
            return networkErr()
        }
        return if (resp.isSuccessful) {
            db.watchlistDao().deleteItem(item.id)
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    private fun networkErr(): BtResult<Unit> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
    )

    private fun at.bettertrack.app.data.api.dto.WorkboardItemDto.toEntity() = WatchlistItemEntity(
        id = id,
        watchlistId = WatchlistEntity.WORKBOARD_ID,
        assetId = assetId,
        sortOrder = sortOrder,
        note = note,
        assetSymbol = asset.symbol,
        assetName = asset.name,
        assetExchange = asset.exchange,
        assetCurrency = asset.currency,
        assetType = asset.type,
    )

    companion object {
        private const val TAG = "BtMarketRepo"

        /** ISO timestamp → epoch ms; handles instant, offset, and bare-date forms. */
        fun parseIsoToMs(iso: String): Long? = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(iso).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable history time: $iso")
                    null
                }
            }
        }
    }
}
