package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.BT_BATCH_QUOTES_MAX
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.BatchQuotes
import at.bettertrack.app.data.repo.eurDisplayPrice
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.QuoteSnapshot
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

    /**
     * The real batch read — `GET /assets/quotes?ids=a,b,c`.
     *
     * Chunked at the server's cap of [BT_BATCH_QUOTES_MAX]; nothing else is
     * appended to the query, because the server's schema is `.strict()` and
     * would answer `400` to a cache-buster.
     *
     * ## Why `eurPrice` comes out null so often here
     *
     * This response has no converted euro figure — not per row, not at the top.
     * All [eurDisplayPrice] can do is the identity read: a quote already priced
     * in euros is its own euro price. Everything else legitimately returns null,
     * and the CALLER decides what that means for it. The watchlist re-reads
     * those rows through [quote], which does convert. Converting here with a
     * rate of our own would be the one thing the app must never do.
     *
     * A transport-level failure returns [BtResult.Err] so the caller can fall
     * back to the path it trusts, rather than being handed a plausible-looking
     * empty result.
     */
    override suspend fun quotes(assetIds: List<String>): BtResult<BatchQuotes> {
        val ids = assetIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return BtResult.Ok(BatchQuotes(emptyMap(), emptySet()))
        val out = LinkedHashMap<String, QuoteSnapshot>()
        val failed = LinkedHashSet<String>()
        for (chunk in ids.chunked(BT_BATCH_QUOTES_MAX)) {
            when (val r = apiCall(json) { api.assetQuotes(chunk.joinToString(",")) }) {
                is BtResult.Ok -> {
                    r.value.quotes.forEach { row ->
                        val q = row.quote
                        val currency = q?.currency.orEmpty()
                        out[row.assetId] = QuoteSnapshot(
                            assetId = row.assetId,
                            nativePrice = q?.price,
                            quoteCurrency = currency,
                            dayChangePct = q?.dayChangePct,
                            prevClose = q?.prevClose,
                            eurPrice = eurDisplayPrice(null, q?.price, currency),
                            asOf = row.asOf ?: q?.asOf,
                            stale = row.stale,
                        )
                    }
                    failed += r.value.failed
                    // An id the server named in neither list is unanswered, which
                    // is a failure from the caller's side however politely the
                    // response was shaped.
                    failed += chunk.filter { it !in out }
                }

                is BtResult.Err -> return r
            }
        }
        return BtResult.Ok(BatchQuotes(out, failed - out.keys))
    }
}
