package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.BatchQuotes
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.assetTwin
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

    /**
     * Whether [assetHistories] costs ONE request regardless of how many assets
     * are asked for.
     *
     * Callers read this to size their request, not to change their maths: a
     * surface that had to cap how many positions it fetched purely because the
     * only shape available was one call per position may lift that cap when this
     * is true. It is deliberately a capability question ("does this cost N?")
     * rather than a source-identity question ("is this the API source?"), so a
     * later source with its own batch answers it by being honest, not by being
     * special-cased at every call site.
     */
    val batchesAssetHistories: Boolean get() = false

    /**
     * Price series for MANY assets of one portfolio, over one window.
     *
     * The BetterTrack server answers this in a single call —
     * `GET /portfolios/{id}/history?overlay=true` carries every held asset's own
     * daily close series (see [ApiMarketDataSource.assetHistories]) — which is
     * why this takes a [portfolioId] the per-asset [assetHistory] has no use for.
     *
     * ## The default is the honest one, and it is not a hidden fan-out
     *
     * A source with no batch of its own answers by asking [assetHistory] per id,
     * bounded to [MARKET_HISTORY_FANOUT] in flight. For the Drive-autonomous
     * source that is free (it reads a local price cache), and for any source it
     * is exactly what the caller would have written itself. What callers must NOT
     * do is treat a failed batch on a batching source as a licence to fan out:
     * see [ApiMarketDataSource.assetHistories], which returns the error.
     *
     * ## What the map means
     *
     * A key is present only when its series was actually read. **Absence means
     * "unknown", never "flat"** — the per-id failure of a fan-out and the
     * server's own omission of an asset it has no prices for arrive the same way,
     * and both must be rendered as unavailable rather than as a zero move.
     * A batching source MAY return ids the caller did not name (its response is
     * per portfolio, not per id); callers are free to use or ignore those.
     *
     * @param range the window, in the PORTFOLIO history vocabulary — the one both
     *   endpoints can serve. [assetTwin][at.bettertrack.app.data.repo.assetTwin]
     *   maps it onto the per-asset endpoint for the fan-out.
     */
    suspend fun assetHistories(
        portfolioId: String,
        assetIds: List<String>,
        range: HistoryRange,
    ): BtResult<Map<String, AssetPriceSeries>> {
        val ids = assetIds.distinct().filter { it.isNotBlank() }
        if (ids.isEmpty()) return BtResult.Ok(emptyMap())
        val out = LinkedHashMap<String, AssetPriceSeries>()
        for (chunk in ids.chunked(MARKET_HISTORY_FANOUT)) {
            val answered = coroutineScope {
                chunk.map { id -> async { id to assetHistory(id, range.assetTwin) } }.awaitAll()
            }
            answered.forEach { (id, r) ->
                // An Err is left OUT of the map rather than mapped to an empty
                // series: an empty series is a series, and this one was not read.
                if (r is BtResult.Ok) out[id] = r.value
            }
        }
        return BtResult.Ok(out)
    }

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

/**
 * How many per-asset HISTORY calls the fallback path may have in flight.
 *
 * The same ceiling as [MARKET_QUOTE_FANOUT], for the same reason and against a
 * heavier response: a history body is a whole series, so a wide account fanning
 * out unbounded would both flood the API and hold every series in memory at once.
 */
const val MARKET_HISTORY_FANOUT: Int = 4
