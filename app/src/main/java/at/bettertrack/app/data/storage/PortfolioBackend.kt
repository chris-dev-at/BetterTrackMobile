package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.HistoryRange
import kotlinx.serialization.json.JsonObject

/**
 * "How a refresh is satisfied and how a mutation is applied" (S3/S4 plan §1.2) —
 * the one seam that differs between a server-backed and a Drive-backed install.
 *
 * The §7.1 doctrine is unchanged and is what makes this seam cheap: **screens
 * read ONLY from Room**, so a backend's whole job is to fill the SAME read-model
 * tables. [at.bettertrack.app.data.repo.PortfolioRepository] stays concrete and
 * stays injected into every ViewModel exactly as before; it keeps the Room
 * reads, the portfolio selection, `afterDrain` and the cache purge, and delegates
 * each network body to this interface.
 *
 * **Contract split (read this before adding a method).** A backend method does
 * the mutation/refresh *and* the Room write that its own response drives (a
 * server backend mirrors the response row; a vault backend writes the entity and
 * re-projects). It does NOT do the projection follow-ups that are backend-
 * agnostic — selecting a freshly created portfolio, purging a deleted
 * portfolio's cache, or the post-mutation `afterDrain` refetch. Those live on
 * the repository and are composed on top, in the same order and under the same
 * success conditions as before the extraction, so behaviour is unchanged.
 *
 * Implementations: [ServerPortfolioBackend] (today's network bodies, moved
 * verbatim). `VaultPortfolioBackend` follows in W4.
 */
interface PortfolioBackend {

    // ── Projection refreshes: fill the SAME Room read-model tables ───────────

    suspend fun refreshPortfolios(): BtResult<Unit>

    suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit>

    /** Newest ledger page, replacing the cache. Returns the next-page cursor. */
    suspend fun refreshTransactions(portfolioId: String): BtResult<String?>

    /** Next (older) page after [cursor], APPENDED. Returns the following cursor. */
    suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?>

    suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit>

    suspend fun refreshCash(portfolioId: String): BtResult<Unit>

    suspend fun refreshCustomAssets(): BtResult<Unit>

    suspend fun refreshValuePoints(assetId: String): BtResult<Unit>

    // ── Portfolio lifecycle ─────────────────────────────────────────────────

    /** Creates and caches the portfolio; returns its id (the CALLER selects it). */
    suspend fun createPortfolio(name: String): BtResult<String>

    suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit>

    /**
     * Persist the portfolio's icon (`kind`) upstream.
     *
     * This is a SERVER field — `PATCH /portfolios/{id}.kind` — and the web has
     * always written it there. The phone used to keep its own copy in the Room
     * `meta` table instead, so the two clients silently disagreed and a reinstall
     * dropped the phone's choice entirely. Routing it through the backend is what
     * makes the icon one fact instead of two.
     */
    suspend fun setPortfolioKind(portfolioId: String, kind: String): BtResult<Unit>

    /** Persist the transaction sheet's "pay from cash" default upstream. */
    suspend fun setDefaultPayFromCash(portfolioId: String, value: Boolean): BtResult<Unit>

    suspend fun archivePortfolio(portfolioId: String): BtResult<Unit>

    suspend fun restorePortfolio(portfolioId: String): BtResult<Unit>

    /** Deletes it upstream only — the CALLER purges the cache and re-pulls the list. */
    suspend fun deletePortfolio(portfolioId: String): BtResult<Unit>

    // ── Cash sources ────────────────────────────────────────────────────────

    suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit>

    suspend fun updateCashSource(
        portfolioId: String,
        sourceId: String,
        name: String?,
        type: String?,
    ): BtResult<Unit>

    suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit>

    suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit>

    // ── Cash movement corrections (v5 — the CALLER refetches the scope) ──────

    suspend fun updateCashMovement(
        portfolioId: String,
        movementId: String,
        patch: JsonObject,
        idempotencyKey: String? = null,
    ): BtResult<Unit>

    suspend fun deleteCashMovement(
        portfolioId: String,
        movementId: String,
        idempotencyKey: String? = null,
    ): BtResult<Unit>

    // ── Custom assets ───────────────────────────────────────────────────────

    suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
    ): BtResult<String>

    suspend fun updateCustomAsset(
        id: String,
        name: String?,
        category: String?,
        smoothing: Boolean?,
    ): BtResult<Unit>

    suspend fun deleteCustomAsset(id: String): BtResult<Unit>

    /** Full-replace of the point set; also refreshes the points cache. */
    suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit>

    // ── Synced-transaction edit / delete (the CALLER refetches the scope) ────

    suspend fun updateTransaction(
        portfolioId: String,
        txId: String,
        body: UpdateTransactionRequest,
        idempotencyKey: String? = null,
    ): BtResult<Unit>

    suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String? = null,
    ): BtResult<Unit>
}
