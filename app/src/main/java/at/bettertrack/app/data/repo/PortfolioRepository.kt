package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.MetaEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.storage.PortfolioBackend
import at.bettertrack.app.sync.PostSyncRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Portfolio-scope repository (spec §7.1): screens read ONLY from Room via the
 * exposed Flows; the refresh methods fill Room with backend truth (in server
 * mode the server is the only calculator — nothing is computed here).
 * Step 5 wired the plumbing; Step 6 adds selection, history and the switcher
 * mutations; Step 7 adds ledger paging + per-asset reads.
 *
 * **V5 W1 (S3/S4 plan §1.2)** split this class in two without touching a single
 * ViewModel: everything that talks to a *store* moved behind [PortfolioBackend]
 * ([at.bettertrack.app.data.storage.ServerPortfolioBackend] is today's network
 * bodies, moved verbatim), while this class kept what is store-agnostic — the
 * Room reads, the portfolio selection, the post-drain refetch and the cache
 * purge — and delegates the rest one line each. The class stays concrete and
 * stays injected into every VM exactly as before, so the swap to a Drive-backed
 * backend (W4) needs no UI change at all.
 */
class PortfolioRepository(
    private val db: BtDatabase,
    private val json: Json,
    private val backend: PortfolioBackend,
) : PostSyncRefresher {

    // ── Room-first reads ─────────────────────────────────────────────────────

    val portfolios: Flow<List<PortfolioEntity>> = db.portfolioDao().observeAll()

    fun holdings(portfolioId: String): Flow<List<HoldingEntity>> =
        db.holdingDao().observeForPortfolio(portfolioId)

    fun transactions(portfolioId: String): Flow<List<TransactionEntity>> =
        db.transactionDao().observeForPortfolio(portfolioId)

    /** That asset's ledger rows only (holding detail, §6.1). */
    fun transactionsForAsset(portfolioId: String, assetId: String): Flow<List<TransactionEntity>> =
        db.transactionDao().observeForAsset(portfolioId, assetId)

    fun cashMovements(portfolioId: String): Flow<List<CashMovementEntity>> =
        db.cashDao().observeMovements(portfolioId)

    /**
     * Parsed cached history series for one portfolio × range (§6.1 graph).
     *
     * ## Why the `flowOn` is load-bearing (perf pass 2026-08-06)
     *
     * Room runs the QUERY on its own dispatcher, but nothing else: everything
     * downstream of the DAO flow executes in the **collector's** context, and
     * the collector here is a `stateIn(viewModelScope, …)` — i.e.
     * `Dispatchers.Main.immediate`. So [parsePortfolioHistory] — two
     * `decodeFromString` passes over the whole series plus an `Instant.parse`
     * per point — was running on the UI thread, for a 1D or MAX range that is
     * hundreds to thousands of points, every time the cached row changed and on
     * every range switch. Which is to say: precisely while the chart is
     * animating the range morph the user just asked for.
     *
     * `flowOn` moves the decode (and only the decode) to [Dispatchers.Default];
     * the state still lands on Main because `stateIn` collects there.
     */
    fun history(portfolioId: String, range: HistoryRange): Flow<PortfolioHistory?> =
        db.portfolioHistoryDao().observe(portfolioId, range.wire)
            .map { entity -> entity?.let { parsePortfolioHistory(it, json) } }
            .flowOn(Dispatchers.Default)

    /** Wall-clock ms of the last successful portfolio-scope sync (banner age, §7.4). */
    val portfolioDataAgeMs: Flow<Long?> =
        db.metaDao().observe(MetaEntity.KEY_PORTFOLIO_SYNCED_AT).map { it?.toLongOrNull() }

    // ── Portfolio selection (§6.1 — sticks across screens and restarts) ─────
    // Persisted in the account-scoped Room meta table: same observe/wipe
    // lifecycle a DataStore file would need wired by hand, one storage layer.

    /** The persisted switcher choice; null until the user ever picks one. */
    val selectedPortfolioId: Flow<String?> =
        db.metaDao().observe(MetaEntity.KEY_SELECTED_PORTFOLIO)

    suspend fun selectPortfolio(portfolioId: String) {
        db.metaDao().put(MetaEntity(MetaEntity.KEY_SELECTED_PORTFOLIO, portfolioId))
    }

    /** One-shot read of the persisted switcher choice. */
    suspend fun selectedPortfolioIdNow(): String? =
        db.metaDao().get(MetaEntity.KEY_SELECTED_PORTFOLIO)

    /** One-shot snapshot of every cached portfolio (initial-load resolution). */
    suspend fun portfoliosNow(): List<PortfolioEntity> = db.portfolioDao().getAll()

    /**
     * The portfolio that should govern right now, resolved from a ONE-SHOT read
     * (§6.1 rule) — never the WhileSubscribed selection StateFlow, which may not
     * have recomputed yet immediately after a list refresh writes Room. Used by
     * the login/cold-start initial load and the overview's own refresh so the
     * dependent cascade always targets a real portfolio instead of racing to
     * null on a fresh login.
     */
    suspend fun defaultSelection(): PortfolioEntity? =
        resolveSelection(portfoliosNow(), selectedPortfolioIdNow())

    // ── Sticky cash-coupling default (§6.2 — per portfolio) ─────────────────
    // Local sticky value in the account-scoped meta KV (works offline, wiped
    // with the account); when absent, the caller falls back to the portfolio's
    // server-side `defaultPayFromCash`.

    /** The locally-sticky toggle default for one portfolio; null = never set. */
    fun cashCouplingDefault(portfolioId: String): Flow<Boolean?> =
        db.metaDao().observe(MetaEntity.keyCashCouplingDefault(portfolioId))
            .map { it?.toBooleanStrictOrNull() }

    suspend fun setCashCouplingDefault(portfolioId: String, value: Boolean) {
        db.metaDao().put(MetaEntity(MetaEntity.keyCashCouplingDefault(portfolioId), value.toString()))
    }

    // ── Backend → Room refresh paths (one-line delegation) ───────────────────

    /** Refresh the portfolio LIST (archived included). */
    suspend fun refreshPortfolios(): BtResult<Unit> = backend.refreshPortfolios()

    /** Refresh holdings + totals for one portfolio. */
    suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit> =
        backend.refreshPortfolioDetail(portfolioId)

    /**
     * Refresh the newest ledger page (replaces the cache; resets any deeper
     * pages — [loadMoreTransactions] re-fetches them on scroll). Returns the
     * next-page cursor, null when the ledger is fully cached.
     */
    suspend fun refreshTransactions(portfolioId: String): BtResult<String?> =
        backend.refreshTransactions(portfolioId)

    /**
     * Fetch the next (older) ledger page after [cursor] and APPEND it to the
     * cache (§6.2 incremental load). Returns the following cursor.
     */
    suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?> =
        backend.loadMoreTransactions(portfolioId, cursor)

    /** Refresh the §6.1 graph series for one portfolio × range. */
    suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit> =
        backend.refreshHistory(portfolioId, range)

    /** Refresh cash for one portfolio: named sources + the full movement stream. */
    suspend fun refreshCash(portfolioId: String): BtResult<Unit> = backend.refreshCash(portfolioId)

    // ── Cash corrections (v5 — online-only, same rule as transaction edits) ──

    /**
     * PATCH one hand-typed cash movement, then refetch the scope.
     *
     * [patch] carries ONLY the changed keys (see
     * [at.bettertrack.app.ui.cash.buildCashMovementPatch]) because the server
     * schema is `.strict()` and rejects both unknown keys and an empty body.
     * [idempotencyKey] is a per-submission UUID so an in-form resend replays the
     * server's stored 2xx instead of applying the edit twice.
     *
     * Expected refusals, all surfaced verbatim to the caller: 409
     * `CASH_MOVEMENT_NOT_EDITABLE` on a derived row, 400 `INSUFFICIENT_CASH`
     * when the full-ledger replay says the edit would have overdrawn, 409
     * `MIRROR_CONFLICT` when `baseSeq` is stale.
     */
    suspend fun updateCashMovement(
        portfolioId: String,
        movementId: String,
        patch: JsonObject,
        idempotencyKey: String? = null,
    ): BtResult<Unit> =
        when (val r = backend.updateCashMovement(portfolioId, movementId, patch, idempotencyKey)) {
            is BtResult.Ok -> {
                afterDrain(setOf(portfolioId))
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /**
     * DELETE one hand-typed cash movement. The backend repaints the row and its
     * source from the response (no refetch needed for the ledger itself); a
     * scope refresh still follows to reconcile the portfolio totals the removal
     * also moved.
     *
     * [idempotencyKey] is stable per movement so a retry after a lost 200
     * replays the stored 2xx rather than 404-ing on the already-removed row.
     */
    suspend fun deleteCashMovement(
        portfolioId: String,
        movementId: String,
        idempotencyKey: String? = null,
    ): BtResult<Unit> =
        when (val r = backend.deleteCashMovement(portfolioId, movementId, idempotencyKey)) {
            is BtResult.Ok -> {
                afterDrain(setOf(portfolioId))
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    // ── Cash-source management (Step 9, §6.3 — online-only per §7.2) ────────

    suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit> =
        backend.createCashSource(portfolioId, name, type)

    suspend fun updateCashSource(
        portfolioId: String,
        sourceId: String,
        name: String?,
        type: String?,
    ): BtResult<Unit> = backend.updateCashSource(portfolioId, sourceId, name, type)

    /** Archive a source — the SERVER rejects Main and non-zero balances. */
    suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        backend.archiveCashSource(portfolioId, sourceId)

    suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        backend.restoreCashSource(portfolioId, sourceId)

    fun cashSources(portfolioId: String): Flow<List<CashSourceEntity>> =
        db.cashDao().observeSources(portfolioId)

    // ── Custom assets (Step 10, §6.4) ───────────────────────────────────────

    val customAssets: Flow<List<CustomAssetEntity>> = db.customAssetDao().observeAll()

    fun customAsset(id: String): Flow<CustomAssetEntity?> = db.customAssetDao().observeById(id)

    fun valuePoints(assetId: String): Flow<List<ValuePointEntity>> =
        db.customAssetDao().observeValuePoints(assetId)

    /** Refresh a custom asset's value points. */
    suspend fun refreshValuePoints(assetId: String): BtResult<Unit> =
        backend.refreshValuePoints(assetId)

    /**
     * The authoritative custom-asset list (#387) — replaces holdings inference so
     * a custom asset with NO holding still appears.
     */
    suspend fun refreshCustomAssets(): BtResult<Unit> = backend.refreshCustomAssets()

    /**
     * Create a custom asset (online-only §7.2); optionally with an initial buy
     * into [portfolioId], whose scope is refetched afterwards.
     */
    suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
        portfolioId: String?,
    ): BtResult<String> =
        when (val r = backend.createCustomAsset(name, category, smoothing, initial)) {
            is BtResult.Ok -> {
                if (initial != null && portfolioId != null) afterDrain(setOf(portfolioId))
                r
            }

            is BtResult.Err -> r
        }

    suspend fun updateCustomAsset(
        id: String,
        name: String?,
        category: String?,
        smoothing: Boolean?,
    ): BtResult<Unit> = backend.updateCustomAsset(id, name, category, smoothing)

    suspend fun deleteCustomAsset(id: String): BtResult<Unit> = backend.deleteCustomAsset(id)

    /**
     * Edit/delete value points (online-only full-replace, §7.2 — offline ADD
     * goes through the queue). [points] is the full desired set; the selected
     * portfolio's detail is refreshed after so custom holding values update.
     */
    suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit> =
        when (val r = backend.putValuePoints(assetId, points)) {
            is BtResult.Ok -> {
                refreshPortfoliosDetailForCustom()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /** Refresh detail of the selected portfolio so custom holding values update. */
    private suspend fun refreshPortfoliosDetailForCustom() {
        selectedPortfolioIdNow()?.let { backend.refreshPortfolioDetail(it) }
    }

    /** Refetch-and-reconcile after a drain (§7.3) — backend truth replaces local. */
    override suspend fun afterDrain(portfolioIds: Set<String>) {
        for (pid in portfolioIds) {
            backend.refreshPortfolioDetail(pid)
            backend.refreshTransactions(pid)
            backend.refreshCash(pid)
        }
    }

    // ── Synced-transaction edit / delete (Step 8, §6.2) ─────────────────────
    // ONLINE-ONLY by spec (§7.2 — the queue stays append-only in v1): direct
    // backend call, then refetch the portfolio scope so Room mirrors truth.

    /**
     * PATCH a synced transaction; refreshes ledger + totals + cash on success.
     * [idempotencyKey] is a per-submission UUID (minted by the form) so an
     * in-form resend of the same edit replays the server's stored 2xx.
     */
    suspend fun updateTransaction(
        portfolioId: String,
        txId: String,
        body: UpdateTransactionRequest,
        idempotencyKey: String? = null,
    ): BtResult<Unit> =
        when (val r = backend.updateTransaction(portfolioId, txId, body, idempotencyKey)) {
            is BtResult.Ok -> {
                afterDrain(setOf(portfolioId))
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /**
     * DELETE a synced transaction; refreshes the scope after.
     * [idempotencyKey] is a per-delete UUID so a retry after a lost 204 replays
     * the stored 2xx rather than 404-ing on the already-removed row.
     */
    suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String? = null,
    ): BtResult<Unit> =
        when (val r = backend.deleteTransaction(portfolioId, txId, idempotencyKey)) {
            is BtResult.Ok -> {
                afterDrain(setOf(portfolioId))
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    // ── Switcher management (§6.1 — create/rename/archive/restore) ──────────
    // Online-only by spec (§7.2); the UI disables them offline with a clear state.

    /** Create a portfolio; selects it as the current one. Returns its id. */
    suspend fun createPortfolio(name: String): BtResult<String> =
        when (val r = backend.createPortfolio(name)) {
            is BtResult.Ok -> {
                selectPortfolio(r.value)
                r
            }

            is BtResult.Err -> r
        }

    suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit> =
        backend.renamePortfolio(portfolioId, name)

    suspend fun archivePortfolio(portfolioId: String): BtResult<Unit> =
        backend.archivePortfolio(portfolioId)

    suspend fun restorePortfolio(portfolioId: String): BtResult<Unit> =
        backend.restorePortfolio(portfolioId)

    /**
     * Hard-delete a portfolio (platform #412, online-only §7.2). On success →
     * purge the local cache for that portfolio, then re-pull the LIST so Room
     * mirrors server truth (the server cascades everything and auto-promotes the
     * derived default — no client bookkeeping). The server rejects the last
     * ACTIVE portfolio with `400 LAST_ACTIVE_PORTFOLIO`; archived ones are always
     * deletable. Selection re-resolution (if the deleted one was current) is
     * handled by the caller.
     */
    suspend fun deletePortfolio(portfolioId: String): BtResult<Unit> =
        when (val r = backend.deletePortfolio(portfolioId)) {
            is BtResult.Ok -> {
                purgePortfolioCache(portfolioId)
                // The list refresh reconciles the portfolios table (promoted default,
                // the deleted row gone). Best-effort: a purged cache already reflects
                // the delete even if this refresh can't reach the network.
                backend.refreshPortfolios()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /** Drop every cached row that belonged to a hard-deleted portfolio (no orphans). */
    private suspend fun purgePortfolioCache(portfolioId: String) {
        db.holdingDao().deleteForPortfolio(portfolioId)
        db.transactionDao().deleteForPortfolio(portfolioId)
        db.cashDao().deleteSourcesForPortfolio(portfolioId)
        db.cashDao().deleteMovementsForPortfolio(portfolioId)
        db.portfolioHistoryDao().deleteForPortfolio(portfolioId)
        db.portfolioDao().deleteById(portfolioId)
        db.metaDao().delete(MetaEntity.keyCashCouplingDefault(portfolioId))
    }

    companion object {
        /**
         * Selection rule (§6.1): the stored choice while it exists and is active
         * → the platform default → the first active portfolio → null (no active
         * portfolios). The single source of truth for "which portfolio governs",
         * shared by the overview VM and the initial-load path.
         */
        fun resolveSelection(
            all: List<PortfolioEntity>,
            storedId: String?,
        ): PortfolioEntity? {
            val active = all.filter { it.archivedAt == null }
            return active.firstOrNull { it.id == storedId }
                ?: active.firstOrNull { it.isDefault }
                ?: active.firstOrNull()
        }

        /** Parse an ISO timestamp to epoch ms; 0 when unparseable (sort key only). */
        fun parseIsoMs(iso: String): Long = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(iso).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}
