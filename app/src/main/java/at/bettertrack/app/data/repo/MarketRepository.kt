package at.bettertrack.app.data.repo

import android.util.Log
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AddToWorkboardRequest
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.api.transportErr
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.data.storage.MarketDataSource
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

/**
 * The €-price a surface may display: the server's converted figure, or — when
 * the quote is already denominated in euros — the quote itself.
 *
 * The server only CONVERTS: for BMW.DE or BTC-EUR it sends `eurPrice = null`
 * because there is nothing to convert, and every consumer that equated
 * "no eurPrice" with "no price" drew "—" beside a live day-percent (device QA
 * 2026-08-16, home-screen widgets and the in-app watchlist alike). This is an
 * identity read, not client-side FX: no rate is invented, no math is done.
 */
fun eurDisplayPrice(eurPrice: Double?, nativePrice: Double?, quoteCurrency: String): Double? =
    eurPrice ?: nativePrice?.takeIf { quoteCurrency.equals("EUR", ignoreCase = true) }

/**
 * One row of a batch quote read — a price WITHOUT an asset identity.
 *
 * Deliberately not an [AssetSnapshot]: the batch endpoint answers with
 * `assetId` and a quote, and nothing else. Reusing [AssetSnapshot] would mean
 * inventing a [MarketAsset] out of blanks, and a half-filled identity object is
 * exactly the kind of thing a later screen reads as fact.
 *
 * [eurPrice] follows the same identity rule as everywhere else ([eurDisplayPrice])
 * and is therefore **null for every quote not already denominated in euros** —
 * the batch response has no converted figure at any level. That is not a defect
 * to paper over: the caller either has a use for the native price and currency,
 * or it re-reads that one row through the per-asset call that does convert.
 */
data class QuoteSnapshot(
    val assetId: String,
    val nativePrice: Double?,
    val quoteCurrency: String,
    val dayChangePct: Double?,
    val prevClose: Double?,
    val eurPrice: Double?,
    val asOf: String?,
    val stale: Boolean,
)

/**
 * The outcome of one batch quote read.
 *
 * [failed] is a per-row outcome, not an error: the server resolves what it can
 * and names the rest. An id that appears in neither map nor set was simply not
 * answered for, and callers should treat it exactly like a failed one.
 */
data class BatchQuotes(
    val quotes: Map<String, QuoteSnapshot>,
    val failed: Set<String>,
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
 *
 * The [wire] value is the ONLY string here. Display labels used to sit on this
 * enum as a second constructor field, which is how a German reader ended up
 * looking at `1Y` and `5Y` on an asset page while the portfolio hero beside it
 * said `1J` — the hero's `HistoryRange` resolved its labels from resources and
 * these did not. They now live in `ui/charts/ChartRangeLabels.kt` with every
 * other window label, so translating one window translates it everywhere.
 */
enum class AssetRange(val wire: String) {
    D1("1D"),
    W1("1W"),
    M1("1M"),
    M3("3M"),
    M6("6M"),
    Y1("1Y"),
    Y5("5Y"),
    MAX("MAX"),
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
 *
 * **V5 W1 (S3/S4 plan §1.3):** the market-DATA half now sits behind
 * [MarketDataSource] ([at.bettertrack.app.data.storage.ApiMarketDataSource] is
 * today's bodies, moved verbatim) because that is the half a Drive-autonomous
 * install cannot satisfy from a BetterTrack account. Watchlist membership —
 * which is Room-backed and mode-independent — stayed here, and every call site
 * is unchanged.
 */
class MarketRepository(
    private val api: BtApi,
    private val db: BtDatabase,
    private val json: Json,
    private val data: MarketDataSource,
) {
    // ── Watchlist (workboard) membership — cached ────────────────────────────

    /** Asset ids currently on the workboard watchlist (drives the star state). */
    val watchlistAssetIds: Flow<Set<String>> =
        db.watchlistDao().observeItems(WatchlistEntity.WORKBOARD_ID)
            .map { items -> items.map { it.assetId }.toSet() }

    val watchlistItems: Flow<List<WatchlistItemEntity>> =
        db.watchlistDao().observeItems(WatchlistEntity.WORKBOARD_ID)

    // ── Market data (§6.5) — one line each into the active [MarketDataSource] ─

    suspend fun search(query: String): BtResult<SearchOutcome> = data.search(query)

    suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> = data.assetDetail(assetId)

    /** Daily closes (ascending by time) — feeds the form's date→price link. */
    suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        data.assetDailyCloses(assetId)

    suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        data.assetHistory(assetId, range)

    /** Latest quote for one asset (watchlist rows, §6.6). */
    suspend fun quote(assetId: String): BtResult<AssetSnapshot> = data.quote(assetId)

    /**
     * Latest quotes for many assets at once — one call where a list surface used
     * to make one per row.
     *
     * Read [QuoteSnapshot.eurPrice]'s contract before using this on a
     * money-rendering surface: it is null for non-EUR quotes, because the batch
     * endpoint converts nothing.
     */
    suspend fun quotes(assetIds: List<String>): BtResult<BatchQuotes> = data.quotes(assetIds)

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
        } catch (e: Exception) {
            return transportErr(e)
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
        } catch (e: Exception) {
            return transportErr(e)
        }
        return if (resp.isSuccessful) {
            db.watchlistDao().deleteItem(item.id)
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

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
