package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome

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
}
