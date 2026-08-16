package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.eurDisplayPrice
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome
import kotlinx.serialization.json.Json

/**
 * The BetterTrack-server [MarketDataSource] — today's behaviour, extracted
 * (S3/S4 plan §1.3). Bodies moved verbatim out of
 * [at.bettertrack.app.data.repo.MarketRepository]; the server is the only price
 * source and nothing here computes a price.
 *
 * These reads are transient by design (never cached): they are online-only
 * surfaces. The `price_cache` table that lets a Drive-only install value a
 * portfolio offline arrives with W4/W6, alongside the sources that fill it.
 */
class ApiMarketDataSource(
    private val api: BtApi,
    private val json: Json,
) : MarketDataSource {

    override suspend fun search(query: String): BtResult<SearchOutcome> =
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

    override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> =
        when (val r = apiCall(json) { api.assetDetail(assetId) }) {
            is BtResult.Ok -> {
                val a = r.value.asset
                val quoteCurrency = r.value.quote?.currency ?: a.currency
                BtResult.Ok(
                    AssetSnapshot(
                        asset = MarketAsset(a.id, a.symbol, a.name, a.exchange, a.type, a.currency, a.isCustom),
                        nativePrice = r.value.quote?.price,
                        quoteCurrency = quoteCurrency,
                        dayChangePct = r.value.quote?.dayChangePct,
                        prevClose = r.value.quote?.prevClose,
                        // EUR-identity fallback — see [eurDisplayPrice]: the server
                        // omits `eurPrice` for quotes already IN euros, which left
                        // BMW.DE/BTC-EUR showing "—" beside a live percent.
                        eurPrice = eurDisplayPrice(r.value.eurPrice, r.value.quote?.price, quoteCurrency),
                        asOf = r.value.asOf ?: r.value.quote?.asOf,
                        stale = r.value.stale,
                    ),
                )
            }

            is BtResult.Err -> r
        }

    /** Daily closes (ascending by time) — feeds the form's date→price link. */
    override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        when (val r = apiCall(json) { api.assetDailyCloses(assetId) }) {
            is BtResult.Ok -> BtResult.Ok(
                r.value.points.mapNotNull { p ->
                    MarketRepository.parseIsoToMs(p.time)?.let { PricePoint(it, p.close) }
                }.sortedBy { it.timeMs },
            )

            is BtResult.Err -> r
        }

    override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        when (val r = apiCall(json) { api.assetHistory(assetId, range.wire) }) {
            is BtResult.Ok -> {
                val serverRange = AssetRange.fromWire(r.value.range) ?: range
                val points = r.value.points.mapNotNull { p ->
                    MarketRepository.parseIsoToMs(p.time)?.let { PricePoint(it, p.close) }
                }.sortedBy { it.timeMs }
                BtResult.Ok(AssetPriceSeries(serverRange, points))
            }

            is BtResult.Err -> r
        }

    /** Latest quote for one asset (watchlist rows, §6.6). */
    override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = assetDetail(assetId)
}
