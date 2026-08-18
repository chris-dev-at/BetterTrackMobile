package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.BatchQuotes
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.QuoteSnapshot
import at.bettertrack.app.data.repo.SearchOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Where prices, quotes and search results come from (S3/S4 plan §1.3, mirroring
 * the platform's own binding seam name `lib/marketDataSource.ts`).
 *
 * `MarketRepository` mixes two unrelated concerns: market DATA (search / quote /
 * history) and workboard-watchlist MEMBERSHIP. Only the first differs by storage
 * mode — a Drive-autonomous install has no BetterTrack account to ask for a
 * price — so only the first sits behind this interface. Watchlist membership
 * stays on the repository, cached in Room exactly as before.
 *
 * Implementations: [ApiMarketDataSource] (today's bodies, moved verbatim).
 * `NoLivePricesMarketDataSource` and the manual-price path follow in W6.
 */
interface MarketDataSource {

    /** Global asset search (§6.5); [SearchOutcome.enriching] = providers still resolving. */
    suspend fun search(query: String): BtResult<SearchOutcome>

    /** Asset header: identity + latest quote + EUR-converted price. */
    suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot>

    /** Daily closes ascending by time — feeds the form's date→price link. */
    suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>>

    suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries>

    /** Latest quote for one asset (watchlist rows, §6.6). */
    suspend fun quote(assetId: String): BtResult<AssetSnapshot>

    /**
     * Latest quotes for many assets at once.
     *
     * The default is the honest one for a source with no batch of its own: ask
     * [quote] per id, bounded so a long watchlist cannot open forty sockets at
     * once. A source that HAS a batch endpoint overrides this
     * ([ApiMarketDataSource]); a Drive-autonomous source reading a local price
     * cache is already cheap and correctly keeps the default.
     *
     * A per-id failure lands in [BatchQuotes.failed] rather than failing the
     * whole read, because that is what the server-side batch does and callers
     * must not have to branch on which source answered them.
     */
    suspend fun quotes(assetIds: List<String>): BtResult<BatchQuotes> {
        val ids = assetIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return BtResult.Ok(BatchQuotes(emptyMap(), emptySet()))
        val out = LinkedHashMap<String, QuoteSnapshot>()
        val failed = LinkedHashSet<String>()
        for (chunk in ids.chunked(MARKET_QUOTE_FANOUT)) {
            val answered = coroutineScope {
                chunk.map { id -> async { id to quote(id) } }.awaitAll()
            }
            answered.forEach { (id, r) ->
                when (r) {
                    is BtResult.Ok -> out[id] = QuoteSnapshot(
                        assetId = id,
                        nativePrice = r.value.nativePrice,
                        quoteCurrency = r.value.quoteCurrency,
                        dayChangePct = r.value.dayChangePct,
                        prevClose = r.value.prevClose,
                        // The per-asset call DOES convert, so unlike the batch
                        // endpoint this fallback can hand back a real euro price.
                        eurPrice = r.value.eurPrice,
                        asOf = r.value.asOf,
                        stale = r.value.stale,
                    )

                    is BtResult.Err -> failed += id
                }
            }
        }
        return BtResult.Ok(BatchQuotes(out, failed))
    }
}

/**
 * How many per-asset quote calls the fallback path may have in flight.
 *
 * Matches the widget refresher's existing ceiling rather than inventing a second
 * number; the in-app watchlist used to fan out with no limit at all.
 */
const val MARKET_QUOTE_FANOUT: Int = 4
