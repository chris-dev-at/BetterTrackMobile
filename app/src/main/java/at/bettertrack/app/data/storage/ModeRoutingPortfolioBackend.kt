package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.HistoryRange
import kotlinx.serialization.json.JsonObject

/**
 * Picks the backend **per call**, from the mode as it is right now.
 *
 * ## Why this exists — the wizard broke the old assumption
 *
 * W1 resolved the backend once, lazily, and documented mode changes as
 * "restart-applied, like the S1 origin override". That was true while the mode
 * could only be set before the graph was used. W5 ends it: the first-run wizard
 * runs *inside* a live process that has already started `SessionInitializer`,
 * which touches `PortfolioRepository`, which forces the backend — as SERVER,
 * because the wizard has not been answered yet. A user finishing the Drive branch
 * would then have a DRIVE-mode install whose reads and writes still went to a
 * server it has no account on, until they happened to restart.
 *
 * Routing per call closes that hole outright rather than adding a "remember to
 * restart" rule the next feature has to remember too. The cost is one lambda
 * dereference per repository call, on operations that all do database or network
 * work anyway.
 *
 * ## Same doctrine as the op router
 *
 * This mirrors [at.bettertrack.app.sync.ModeRoutingOpExecutor], deliberately: the
 * write path already dispatched dynamically, and having the read path resolve
 * once while the write path resolved per op was an asymmetry waiting to produce a
 * bug. Note the one true difference — the op router dispatches on each op's own
 * persisted `backendTag` so queued work never changes destination, while a
 * *refresh* has no history and always belongs to the current mode.
 */
class ModeRoutingPortfolioBackend(
    private val mode: () -> StorageMode,
    private val server: () -> PortfolioBackend,
    private val vault: () -> PortfolioBackend,
) : PortfolioBackend {

    private fun active(): PortfolioBackend = if (mode().isDriveOnly) vault() else server()

    override suspend fun refreshPortfolios(): BtResult<Unit> = active().refreshPortfolios()

    override suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit> =
        active().refreshPortfolioDetail(portfolioId)

    override suspend fun refreshTransactions(portfolioId: String): BtResult<String?> =
        active().refreshTransactions(portfolioId)

    override suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?> =
        active().loadMoreTransactions(portfolioId, cursor)

    override suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit> =
        active().refreshHistory(portfolioId, range)

    override suspend fun refreshCash(portfolioId: String): BtResult<Unit> = active().refreshCash(portfolioId)

    override suspend fun refreshCustomAssets(): BtResult<Unit> = active().refreshCustomAssets()

    override suspend fun refreshValuePoints(assetId: String): BtResult<Unit> =
        active().refreshValuePoints(assetId)

    override suspend fun createPortfolio(name: String): BtResult<String> = active().createPortfolio(name)

    override suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit> =
        active().renamePortfolio(portfolioId, name)

    override suspend fun archivePortfolio(portfolioId: String): BtResult<Unit> =
        active().archivePortfolio(portfolioId)

    override suspend fun restorePortfolio(portfolioId: String): BtResult<Unit> =
        active().restorePortfolio(portfolioId)

    override suspend fun deletePortfolio(portfolioId: String): BtResult<Unit> =
        active().deletePortfolio(portfolioId)

    override suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit> =
        active().createCashSource(portfolioId, name, type)

    override suspend fun updateCashSource(
        portfolioId: String,
        sourceId: String,
        name: String?,
        type: String?,
    ): BtResult<Unit> = active().updateCashSource(portfolioId, sourceId, name, type)

    override suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        active().archiveCashSource(portfolioId, sourceId)

    override suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        active().restoreCashSource(portfolioId, sourceId)

    override suspend fun updateCashMovement(
        portfolioId: String,
        movementId: String,
        patch: JsonObject,
        idempotencyKey: String?,
    ): BtResult<Unit> = active().updateCashMovement(portfolioId, movementId, patch, idempotencyKey)

    override suspend fun deleteCashMovement(
        portfolioId: String,
        movementId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> = active().deleteCashMovement(portfolioId, movementId, idempotencyKey)

    override suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
    ): BtResult<String> = active().createCustomAsset(name, category, smoothing, initial)

    override suspend fun updateCustomAsset(
        id: String,
        name: String?,
        category: String?,
        smoothing: Boolean?,
    ): BtResult<Unit> = active().updateCustomAsset(id, name, category, smoothing)

    override suspend fun deleteCustomAsset(id: String): BtResult<Unit> = active().deleteCustomAsset(id)

    override suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit> =
        active().putValuePoints(assetId, points)

    override suspend fun updateTransaction(
        portfolioId: String,
        txId: String,
        body: UpdateTransactionRequest,
        idempotencyKey: String?,
    ): BtResult<Unit> = active().updateTransaction(portfolioId, txId, body, idempotencyKey)

    override suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> = active().deleteTransaction(portfolioId, txId, idempotencyKey)
}

/**
 * The same per-call routing for prices (plan §1.3).
 *
 * Split out for exactly one reason: a Drive-only install has no BetterTrack
 * account to ask for a quote, and the old lazy resolution had the same
 * first-run-wizard hole as the portfolio backend — a user who chose Drive in a
 * process that had already touched `MarketRepository` would keep hitting the API
 * with no bearer, producing a stream of 401s instead of the designed
 * "no live prices" state.
 */
class ModeRoutingMarketDataSource(
    private val mode: () -> StorageMode,
    private val server: () -> MarketDataSource,
    private val offline: () -> MarketDataSource,
) : MarketDataSource {

    private fun active(): MarketDataSource = if (mode().isDriveOnly) offline() else server()

    override suspend fun search(query: String): BtResult<at.bettertrack.app.data.repo.SearchOutcome> =
        active().search(query)

    override suspend fun assetDetail(assetId: String): BtResult<at.bettertrack.app.data.repo.AssetSnapshot> =
        active().assetDetail(assetId)

    override suspend fun assetDailyCloses(
        assetId: String,
    ): BtResult<List<at.bettertrack.app.data.repo.PricePoint>> = active().assetDailyCloses(assetId)

    override suspend fun assetHistory(
        assetId: String,
        range: at.bettertrack.app.data.repo.AssetRange,
    ): BtResult<at.bettertrack.app.data.repo.AssetPriceSeries> = active().assetHistory(assetId, range)

    override suspend fun quote(assetId: String): BtResult<at.bettertrack.app.data.repo.AssetSnapshot> =
        active().quote(assetId)
}
