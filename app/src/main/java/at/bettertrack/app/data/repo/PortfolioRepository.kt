package at.bettertrack.app.data.repo

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CashSourceRequest
import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PutValuePointsRequest
import at.bettertrack.app.data.api.dto.UpdateCustomAssetRequest
import at.bettertrack.app.data.api.dto.ValuePointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.api.dto.PortfolioDetailResponse
import at.bettertrack.app.data.api.dto.PortfolioDto
import at.bettertrack.app.data.api.dto.TransactionDto
import at.bettertrack.app.data.api.dto.UpdatePortfolioRequest
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.MetaEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.sync.PostSyncRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Portfolio-scope repository (spec §7.1): screens read ONLY from Room via the
 * exposed Flows; the refresh methods pull the API and overwrite Room with
 * server truth (the server is the only calculator — nothing is computed here).
 * Step 5 wired the plumbing; Step 6 adds selection, history and the switcher
 * mutations; Step 7 adds ledger paging + per-asset reads.
 */
class PortfolioRepository(
    private val api: BtApi,
    private val db: BtDatabase,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
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

    /** Parsed cached history series for one portfolio × range (§6.1 graph). */
    fun history(portfolioId: String, range: HistoryRange): Flow<PortfolioHistory?> =
        db.portfolioHistoryDao().observe(portfolioId, range.wire)
            .map { entity -> entity?.let { parsePortfolioHistory(it, json) } }

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

    // ── Network → Room refresh paths ─────────────────────────────────────────

    /** Refresh the portfolio LIST (`GET /portfolios`, archived included). */
    suspend fun refreshPortfolios(): BtResult<Unit> =
        when (val r = apiCall(json) { api.portfolios() }) {
            is BtResult.Ok -> {
                val fresh = r.value.portfolios.map { p ->
                    PortfolioEntity(
                        id = p.id,
                        name = p.name,
                        visibility = p.visibility,
                        sortOrder = p.sortOrder,
                        isDefault = p.isDefault,
                        defaultPayFromCash = p.defaultPayFromCash,
                        archivedAt = p.archivedAt,
                        baseCurrency = null,
                        totals = null,
                        detailSyncedAtMs = null,
                    )
                }
                db.portfolioDao().replaceListPreservingTotals(fresh)
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshPortfolios failed: ${r.error.message}")
                r
            }
        }

    /** Refresh holdings + server-computed totals for one portfolio. */
    suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.portfolioDetail(portfolioId) }) {
            is BtResult.Ok -> {
                applyDetail(portfolioId, r.value)
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshPortfolioDetail($portfolioId) failed: ${r.error.message}")
                r
            }
        }

    /**
     * Refresh the newest ledger page (replaces the cache; resets any deeper
     * pages — [loadMoreTransactions] re-fetches them on scroll). Returns the
     * next-page cursor, null when the ledger is fully cached.
     */
    suspend fun refreshTransactions(portfolioId: String): BtResult<String?> =
        when (val r = apiCall(json) { api.transactions(portfolioId) }) {
            is BtResult.Ok -> {
                val rows = r.value.items.map { it.toEntity(portfolioId) }
                db.transactionDao().replaceForPortfolio(portfolioId, rows)
                touchSyncedAt()
                BtResult.Ok(r.value.nextCursor)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshTransactions($portfolioId) failed: ${r.error.message}")
                r
            }
        }

    /**
     * Fetch the next (older) ledger page after [cursor] and APPEND it to the
     * cache (§6.2 incremental load). Returns the following cursor.
     */
    suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?> =
        when (val r = apiCall(json) { api.transactions(portfolioId, cursor = cursor) }) {
            is BtResult.Ok -> {
                db.transactionDao().insertAll(r.value.items.map { it.toEntity(portfolioId) })
                BtResult.Ok(r.value.nextCursor)
            }

            is BtResult.Err -> {
                Log.w(TAG, "loadMoreTransactions($portfolioId) failed: ${r.error.message}")
                r
            }
        }

    /** Refresh the §6.1 graph series for one portfolio × range (stored verbatim). */
    suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit> =
        when (val r = apiCall(json) { api.portfolioHistory(portfolioId, range.wire) }) {
            is BtResult.Ok -> {
                db.portfolioHistoryDao().upsert(
                    PortfolioHistoryEntity(
                        portfolioId = portfolioId,
                        range = range.wire,
                        baseCurrency = r.value.baseCurrency,
                        pointsJson = json.encodeToString(
                            ListSerializer(HistoryPointDto.serializer()),
                            r.value.points,
                        ),
                        performanceJson = json.encodeToString(
                            ListSerializer(PerformancePointDto.serializer()),
                            r.value.performance,
                        ),
                        syncedAtMs = now(),
                    ),
                )
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshHistory($portfolioId, ${range.wire}) failed: ${r.error.message}")
                r
            }
        }

    /**
     * Refresh cash for one portfolio (Step 9): real named sources (Main first,
     * per-source balances) + the full movement stream, mirrored verbatim.
     */
    suspend fun refreshCash(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.cash(portfolioId) }) {
            is BtResult.Ok -> {
                val sources = r.value.sources.map { s ->
                    CashSourceEntity(
                        id = s.id,
                        portfolioId = portfolioId,
                        name = s.name,
                        kind = s.type,
                        isMain = s.isMain,
                        balanceEur = s.balanceEur,
                        archivedAt = s.archivedAt,
                    )
                }
                val movements = r.value.movements.map { m ->
                    CashMovementEntity(
                        id = m.id,
                        portfolioId = portfolioId,
                        sourceId = m.sourceId ?: sources.firstOrNull { it.isMain }?.id ?: "main",
                        kind = m.kind,
                        amountEur = m.amountEur,
                        transactionId = m.transactionId,
                        transferId = m.transferId,
                        counterpartSourceId = m.counterpartSourceId,
                        executedAt = m.executedAt,
                        executedAtMs = parseIsoMs(m.executedAt),
                        note = m.note,
                        createdAt = m.createdAt,
                    )
                }
                db.cashDao().replaceForPortfolio(portfolioId, sources, movements)
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshCash($portfolioId) failed: ${r.error.message}")
                r
            }
        }

    // ── Cash-source management (Step 9, §6.3 — online-only per §7.2) ────────

    suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.createCashSource(portfolioId, CashSourceRequest(name, type)) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    suspend fun updateCashSource(
        portfolioId: String,
        sourceId: String,
        name: String?,
        type: String?,
    ): BtResult<Unit> =
        when (val r = apiCall(json) { api.updateCashSource(portfolioId, sourceId, CashSourceRequest(name, type)) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /** Archive a source — the SERVER rejects Main and non-zero balances. */
    suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.archiveCashSource(portfolioId, sourceId) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.restoreCashSource(portfolioId, sourceId) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    fun cashSources(portfolioId: String): Flow<List<CashSourceEntity>> =
        db.cashDao().observeSources(portfolioId)

    // ── Custom assets (Step 10, §6.4) ───────────────────────────────────────

    val customAssets: Flow<List<CustomAssetEntity>> = db.customAssetDao().observeAll()

    fun customAsset(id: String): Flow<CustomAssetEntity?> = db.customAssetDao().observeById(id)

    fun valuePoints(assetId: String): Flow<List<ValuePointEntity>> =
        db.customAssetDao().observeValuePoints(assetId)

    /** Refresh a custom asset's value points (verbatim server truth, §7.1). */
    suspend fun refreshValuePoints(assetId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.valuePoints(assetId) }) {
            is BtResult.Ok -> {
                db.customAssetDao().replaceValuePoints(
                    assetId,
                    r.value.points.map { ValuePointEntity(assetId, it.date, it.value) },
                )
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshValuePoints($assetId) failed: ${r.error.message}")
                r
            }
        }

    /**
     * Create a custom asset (online-only §7.2); optionally with an initial buy
     * into [portfolioId]. Caches the identity (incl. category) immediately.
     */
    /**
     * The authoritative custom-asset list (#387) — replaces holdings inference so a
     * custom asset with NO holding still appears. On success we upsert every entry
     * and reconcile the cache to the server set; on failure the cache is untouched
     * (offline shows the last-known list).
     */
    suspend fun refreshCustomAssets(): BtResult<Unit> =
        when (val r = apiCall(json) { api.customAssets() }) {
            is BtResult.Ok -> {
                val assets = r.value.assets
                db.customAssetDao().upsertAll(
                    assets.map {
                        CustomAssetEntity(it.id, it.symbol, it.name, it.category, it.currency, it.smoothing)
                    },
                )
                val keep = assets.map { it.id }
                if (keep.isEmpty()) db.customAssetDao().deleteAllCustomAssets()
                else db.customAssetDao().deleteNotIn(keep)
                touchSyncedAt()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "refreshCustomAssets failed: ${r.error.message}")
                r
            }
        }

    suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
        portfolioId: String?,
    ): BtResult<String> =
        when (
            val r = apiCall(json) {
                api.createCustomAsset(
                    CreateCustomAssetRequest(name.trim(), category, smoothing = smoothing, initialPurchase = initial),
                )
            }
        ) {
            is BtResult.Ok -> {
                val a = r.value.asset
                db.customAssetDao().upsertAll(
                    listOf(CustomAssetEntity(a.id, a.symbol, a.name, a.category, a.currency, a.smoothing)),
                )
                if (initial != null && portfolioId != null) afterDrain(setOf(portfolioId))
                BtResult.Ok(a.id)
            }

            is BtResult.Err -> r
        }

    suspend fun updateCustomAsset(
        id: String,
        name: String?,
        category: String?,
        smoothing: Boolean?,
    ): BtResult<Unit> =
        when (
            val r = apiCall(json) {
                api.updateCustomAsset(id, UpdateCustomAssetRequest(name?.trim(), category, smoothing))
            }
        ) {
            is BtResult.Ok -> {
                val a = r.value.asset
                db.customAssetDao().upsertAll(
                    listOf(CustomAssetEntity(a.id, a.symbol, a.name, a.category, a.currency, a.smoothing)),
                )
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    suspend fun deleteCustomAsset(id: String): BtResult<Unit> {
        val resp = try {
            api.deleteCustomAsset(id)
        } catch (_: java.io.IOException) {
            return BtResult.Err(
                at.bettertrack.app.data.api.BtApiError(
                    0,
                    at.bettertrack.app.data.api.BtApiError.Codes.NETWORK,
                    "No connection. Check your network and try again.",
                ),
            )
        }
        return if (resp.isSuccessful) {
            db.customAssetDao().delete(id)
            BtResult.Ok(Unit)
        } else {
            BtResult.Err(at.bettertrack.app.data.api.parseApiError(json, resp.code(), resp.errorBody()))
        }
    }

    /**
     * Edit/delete value points (online-only PUT full-replace, §7.2 — offline
     * ADD goes through the queue). [points] is the full desired set.
     */
    suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit> =
        when (
            val r = apiCall(json) {
                api.putValuePoints(
                    assetId,
                    PutValuePointsRequest(points.map { ValuePointDto(it.date, it.value) }.sortedBy { it.date }),
                )
            }
        ) {
            is BtResult.Ok -> {
                refreshValuePoints(assetId)
                refreshPortfoliosDetailForCustom()
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /** Refresh detail of the selected portfolio so custom holding values update. */
    private suspend fun refreshPortfoliosDetailForCustom() {
        selectedPortfolioIdNow()?.let { refreshPortfolioDetail(it) }
    }

    /** Refetch-and-reconcile after a drain (§7.3) — server truth replaces local. */
    override suspend fun afterDrain(portfolioIds: Set<String>) {
        for (pid in portfolioIds) {
            refreshPortfolioDetail(pid)
            refreshTransactions(pid)
            refreshCash(pid)
        }
    }

    // ── Synced-transaction edit / delete (Step 8, §6.2) ─────────────────────
    // ONLINE-ONLY by spec (§7.2 — the queue stays append-only in v1): direct
    // API call, then refetch the portfolio scope so Room mirrors server truth.

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
        when (val r = apiCall(json) { api.updateTransaction(portfolioId, txId, body, idempotencyKey) }) {
            is BtResult.Ok -> {
                afterDrain(setOf(portfolioId))
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "updateTransaction($txId) failed: ${r.error.message}")
                r
            }
        }

    /**
     * DELETE a synced transaction (204, no body); refreshes the scope after.
     * [idempotencyKey] is a per-delete UUID so a retry after a lost 204 replays
     * the stored 2xx rather than 404-ing on the already-removed row.
     */
    suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String? = null,
    ): BtResult<Unit> {
        val resp = try {
            api.deleteTransaction(portfolioId, txId, idempotencyKey)
        } catch (_: java.io.IOException) {
            return BtResult.Err(
                at.bettertrack.app.data.api.BtApiError(
                    0,
                    at.bettertrack.app.data.api.BtApiError.Codes.NETWORK,
                    "No connection. Check your network and try again.",
                ),
            )
        }
        return if (resp.isSuccessful) {
            afterDrain(setOf(portfolioId))
            BtResult.Ok(Unit)
        } else {
            val err = at.bettertrack.app.data.api.parseApiError(json, resp.code(), resp.errorBody())
            Log.w(TAG, "deleteTransaction($txId) failed: ${err.message}")
            BtResult.Err(err)
        }
    }

    // ── Switcher management (§6.1 — create/rename/archive/restore) ──────────
    // Online-only by spec (§7.2): these call the API directly and mirror the
    // response into Room; the UI disables them offline with a clear state.

    /** Create a portfolio; selects it as the current one. Returns its id. */
    suspend fun createPortfolio(name: String): BtResult<String> =
        when (val r = apiCall(json) { api.createPortfolio(CreatePortfolioRequest(name)) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                selectPortfolio(r.value.portfolio.id)
                BtResult.Ok(r.value.portfolio.id)
            }

            is BtResult.Err -> r
        }

    suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.updatePortfolio(portfolioId, UpdatePortfolioRequest(name = name)) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    suspend fun archivePortfolio(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.archivePortfolio(portfolioId) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    suspend fun restorePortfolio(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.restorePortfolio(portfolioId) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /**
     * Hard-delete a portfolio (platform #412, online-only §7.2). 204 → purge the
     * local cache for that portfolio, then re-pull the LIST so Room mirrors server
     * truth (the server cascades everything and auto-promotes the derived default —
     * no client bookkeeping). The server rejects the last ACTIVE portfolio with
     * `400 LAST_ACTIVE_PORTFOLIO`; archived ones are always deletable. Selection
     * re-resolution (if the deleted one was current) is handled by the caller.
     */
    suspend fun deletePortfolio(portfolioId: String): BtResult<Unit> {
        val resp = try {
            api.deletePortfolio(portfolioId)
        } catch (_: java.io.IOException) {
            return BtResult.Err(
                at.bettertrack.app.data.api.BtApiError(
                    0,
                    at.bettertrack.app.data.api.BtApiError.Codes.NETWORK,
                    "No connection. Check your network and try again.",
                ),
            )
        }
        return if (resp.isSuccessful) {
            purgePortfolioCache(portfolioId)
            // The list refresh reconciles the portfolios table (promoted default,
            // the deleted row gone). Best-effort: a purged cache already reflects
            // the delete even if this refresh can't reach the network.
            refreshPortfolios()
            BtResult.Ok(Unit)
        } else {
            val err = at.bettertrack.app.data.api.parseApiError(json, resp.code(), resp.errorBody())
            Log.w(TAG, "deletePortfolio($portfolioId) failed: ${err.message}")
            BtResult.Err(err)
        }
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

    // ── Internals ────────────────────────────────────────────────────────────

    /** Mirror a mutation response row into Room, preserving synced totals. */
    private suspend fun upsertFromDto(p: PortfolioDto) {
        val old = db.portfolioDao().getById(p.id)
        db.portfolioDao().upsertAll(
            listOf(
                PortfolioEntity(
                    id = p.id,
                    name = p.name,
                    visibility = p.visibility,
                    sortOrder = p.sortOrder,
                    isDefault = p.isDefault,
                    defaultPayFromCash = p.defaultPayFromCash,
                    archivedAt = p.archivedAt,
                    baseCurrency = old?.baseCurrency,
                    totals = old?.totals,
                    detailSyncedAtMs = old?.detailSyncedAtMs,
                ),
            ),
        )
    }

    private suspend fun applyDetail(portfolioId: String, detail: PortfolioDetailResponse) {
        val holdings = detail.holdings.map { h ->
            HoldingEntity(
                portfolioId = portfolioId,
                assetId = h.asset.id,
                assetSymbol = h.asset.symbol,
                assetName = h.asset.name,
                assetExchange = h.asset.exchange,
                assetCurrency = h.asset.currency,
                assetType = h.asset.type,
                assetIsCustom = h.asset.isCustom,
                quantity = h.quantity,
                avgCost = h.avgCost,
                realizedPnl = h.realizedPnl,
                price = h.price,
                marketValueEur = h.marketValueEur,
                costBasisEur = h.costBasisEur,
                unrealizedPnlEur = h.unrealizedPnlEur,
                unrealizedPnlPct = h.unrealizedPnlPct,
                dayChangeEur = h.dayChangeEur,
                dayChangePct = h.dayChangePct,
            )
        }
        db.holdingDao().replaceForPortfolio(portfolioId, holdings)

        // Custom-asset identities ride along on holdings (§6.4; the API has no
        // list endpoint) — cache them for the Step-10 screens, preserving any
        // category we already learned from a create/edit (holdings omit it).
        val customHoldings = detail.holdings.filter { it.asset.isCustom }
        for (h in customHoldings) {
            // Preserve category + smoothing already learned from the list/create/edit
            // (holdings omit them) so a portfolio refresh never wipes them.
            val existing = db.customAssetDao().getById(h.asset.id)
            db.customAssetDao().upsertAll(
                listOf(
                    CustomAssetEntity(
                        id = h.asset.id,
                        symbol = h.asset.symbol,
                        name = h.asset.name,
                        category = existing?.category,
                        currency = h.asset.currency,
                        smoothing = existing?.smoothing ?: false,
                    ),
                ),
            )
        }

        val existing = db.portfolioDao().getById(portfolioId)
        if (existing != null) {
            db.portfolioDao().upsertAll(
                listOf(
                    existing.copy(
                        baseCurrency = detail.baseCurrency,
                        totals = PortfolioTotals(
                            marketValueEur = detail.totals.marketValueEur,
                            investedEur = detail.totals.investedEur,
                            unrealizedPnlEur = detail.totals.unrealizedPnlEur,
                            unrealizedPnlPct = detail.totals.unrealizedPnlPct,
                            dayChangeEur = detail.totals.dayChangeEur,
                            dayChangePct = detail.totals.dayChangePct,
                            cashEur = detail.totals.cashEur,
                            totalValueEur = detail.totals.totalValueEur,
                        ),
                        detailSyncedAtMs = now(),
                    ),
                ),
            )
        }
    }

    private suspend fun touchSyncedAt() {
        db.metaDao().put(MetaEntity(MetaEntity.KEY_PORTFOLIO_SYNCED_AT, now().toString()))
    }

    companion object {
        private const val TAG = "BtPortfolioRepo"

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

/** Wire row → Room read model (asset identity flattened). */
private fun TransactionDto.toEntity(portfolioId: String): TransactionEntity =
    TransactionEntity(
        id = id,
        portfolioId = portfolioId,
        assetId = assetId,
        side = side,
        quantity = quantity,
        price = price,
        fee = fee,
        executedAt = executedAt,
        executedAtMs = PortfolioRepository.parseIsoMs(executedAt),
        note = note,
        assetSymbol = asset.symbol,
        assetName = asset.name,
        assetExchange = asset.exchange,
        assetCurrency = asset.currency,
        assetType = asset.type,
        assetIsCustom = asset.isCustom,
    )
