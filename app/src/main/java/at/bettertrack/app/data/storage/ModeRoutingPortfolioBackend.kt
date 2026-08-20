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

    override suspend fun setPortfolioKind(portfolioId: String, kind: String): BtResult<Unit> =
        active().setPortfolioKind(portfolioId, kind)

    override suspend fun setDefaultPayFromCash(portfolioId: String, value: Boolean): BtResult<Unit> =
        active().setDefaultPayFromCash(portfolioId, value)

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
 *
 * ## W6: the price-lookup opt-in
 *
 * [lookupsActive] is the "Use BetterTrack for prices only" switch
 * ([PriceLookupStore]). When a Drive-mode user turns it on **and** a session
 * exists to authenticate with, the market seam — and only the market seam — moves
 * to the server. [ModeRoutingPortfolioBackend] above is untouched by it, which is
 * what makes the promise in the settings copy structurally true rather than a
 * claim: *"BetterTrack would see which assets you look up, never what you own."*
 * Holdings, transactions and cash keep routing to the vault because a different
 * router decides those, and this one cannot reach it.
 *
 * The session half of the condition is not politeness. `/search` and `/assets`
 * require the OAuth bearer (`market:read`), so calling them without one produces
 * 401s — the exact failure this class was split out to prevent. See
 * [priceLookupActive].
 */
class ModeRoutingMarketDataSource(
    private val mode: () -> StorageMode,
    private val server: () -> MarketDataSource,
    private val offline: () -> MarketDataSource,
    /**
     * Whether server price lookups are opted in AND authenticable right now.
     * Defaults to "never", so every existing caller keeps W5 behaviour exactly.
     */
    private val lookupsActive: () -> Boolean = { false },
) : MarketDataSource {

    private fun active(): MarketDataSource =
        if (mode().isDriveOnly && !lookupsActive()) offline() else server()

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

    /**
     * Answered by whichever source is active RIGHT NOW, never cached.
     *
     * The routing decision moves with the storage mode and the price-lookup
     * opt-in, so a caller that read this once and remembered it would size its
     * request for the source it met at start-up rather than the one that will
     * answer.
     */
    override val batchesAssetHistories: Boolean get() = active().batchesAssetHistories

    /**
     * Forwarded explicitly for the same reason [quotes] is: the interface's
     * default body fans out through [assetHistory], and inheriting it here would
     * route every asset individually through this class and never reach
     * [ApiMarketDataSource]'s single overlay call — an N+1 that looks collapsed
     * from the outside.
     */
    override suspend fun assetHistories(
        portfolioId: String,
        assetIds: List<String>,
        range: at.bettertrack.app.data.repo.HistoryRange,
    ): BtResult<Map<String, at.bettertrack.app.data.repo.AssetPriceSeries>> =
        active().assetHistories(portfolioId, assetIds, range)

    override suspend fun quote(assetId: String): BtResult<at.bettertrack.app.data.repo.AssetSnapshot> =
        active().quote(assetId)

    /**
     * Forwarded explicitly, and it has to be.
     *
     * [MarketDataSource.quotes] has a default body that loops [quote]. Inheriting
     * it here would route every row individually through this class and never
     * reach [ApiMarketDataSource]'s real batch call — the N+1 would survive the
     * fix while looking fixed from the outside.
     */
    override suspend fun quotes(
        assetIds: List<String>,
    ): BtResult<at.bettertrack.app.data.repo.BatchQuotes> = active().quotes(assetIds)
}
