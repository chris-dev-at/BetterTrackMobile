package at.bettertrack.app.data.storage

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CashSourceRequest
import at.bettertrack.app.data.api.dto.CreateCustomAssetRequest
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.HistoryPointDto
import at.bettertrack.app.data.api.dto.PerformancePointDto
import at.bettertrack.app.data.api.dto.PortfolioDetailResponse
import at.bettertrack.app.data.api.dto.PortfolioDto
import at.bettertrack.app.data.api.dto.PutValuePointsRequest
import at.bettertrack.app.data.api.dto.TransactionDto
import at.bettertrack.app.data.api.dto.UpdateCustomAssetRequest
import at.bettertrack.app.data.api.dto.UpdatePortfolioRequest
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.api.dto.ValuePointDto
import at.bettertrack.app.data.cash.encodeTagIds
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.MetaEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioHistoryEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.db.TransactionEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.toPortfolioMirror
import at.bettertrack.app.data.repo.toRowMirror
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The BetterTrack-server implementation of [PortfolioBackend] — today's
 * behaviour, extracted (S3/S4 plan §1.2).
 *
 * Every body here was **moved verbatim** out of
 * [at.bettertrack.app.data.repo.PortfolioRepository]: they only ever touched
 * `api`, `db`, `json` and `now`, which is exactly why the seam is cheap. The
 * server stays the only calculator in this mode (§7.1) — nothing here derives a
 * number; responses are mirrored into Room as they arrive. (The doctrine is
 * amended only for Drive mode, where the ported audited engine calculates —
 * plan §3.5; that is W4's `VaultPortfolioBackend`, not this class.)
 *
 * Projection follow-ups that are NOT backend-specific — selecting a new
 * portfolio, purging a deleted portfolio's cache, the post-mutation `afterDrain`
 * refetch — deliberately stayed on the repository; see [PortfolioBackend].
 */
class ServerPortfolioBackend(
    private val api: BtApi,
    private val db: BtDatabase,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) : PortfolioBackend {

    // ── Network → Room refresh paths ─────────────────────────────────────────

    /** Refresh the portfolio LIST (`GET /portfolios`, archived included). */
    override suspend fun refreshPortfolios(): BtResult<Unit> =
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
                        mirror = p.mirror.toPortfolioMirror(),
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
    override suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit> =
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
    override suspend fun refreshTransactions(portfolioId: String): BtResult<String?> =
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
    override suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?> =
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
    override suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit> =
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
    override suspend fun refreshCash(portfolioId: String): BtResult<Unit> =
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
                        dividendId = m.dividendId,
                        executedAt = m.executedAt,
                        executedAtMs = PortfolioRepository.parseIsoMs(m.executedAt),
                        note = m.note,
                        createdAt = m.createdAt,
                        source = m.source ?: "manual",
                        // v5 classification chips, cached so they render offline.
                        // A pre-v5 server omits `tags` entirely → "" (untagged).
                        tagIds = encodeTagIds(m.tags ?: emptyList()),
                        mirror = m.mirror.toRowMirror(),
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

    // ── Cash corrections (v5 — online-only, same rule as transaction edits) ──

    override suspend fun updateCashMovement(
        portfolioId: String,
        movementId: String,
        patch: JsonObject,
        idempotencyKey: String?,
    ): BtResult<Unit> =
        when (
            val r = apiCall(json) {
                api.updateCashMovement(portfolioId, movementId, patch, idempotencyKey)
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(Unit)

            is BtResult.Err -> {
                Log.w(TAG, "updateCashMovement($movementId) failed: ${r.error.message}")
                r
            }
        }

    /**
     * DELETE one hand-typed cash movement. Answers 200 with fresh balances, so
     * the row and its source are repainted from the RESPONSE (no refetch needed
     * for the ledger itself); the caller's scope refresh still follows to
     * reconcile the portfolio totals the removal also moved.
     */
    override suspend fun deleteCashMovement(
        portfolioId: String,
        movementId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> =
        when (
            val r = apiCall(json) {
                api.deleteCashMovement(portfolioId, movementId, idempotencyKey)
            }
        ) {
            is BtResult.Ok -> {
                db.cashDao().applyMovementDeletion(
                    movementId = movementId,
                    sourceId = r.value.sourceId,
                    sourceBalanceEur = r.value.sourceBalanceEur,
                )
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> {
                Log.w(TAG, "deleteCashMovement($movementId) failed: ${r.error.message}")
                r
            }
        }

    // ── Cash-source management (Step 9, §6.3 — online-only per §7.2) ────────

    override suspend fun createCashSource(
        portfolioId: String,
        name: String,
        type: String,
    ): BtResult<Unit> =
        when (val r = apiCall(json) { api.createCashSource(portfolioId, CashSourceRequest(name, type)) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    override suspend fun updateCashSource(
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
    override suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.archiveCashSource(portfolioId, sourceId) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    override suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.restoreCashSource(portfolioId, sourceId) }) {
            is BtResult.Ok -> {
                refreshCash(portfolioId)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    // ── Custom assets (Step 10, §6.4) ───────────────────────────────────────

    /** Refresh a custom asset's value points (verbatim server truth, §7.1). */
    override suspend fun refreshValuePoints(assetId: String): BtResult<Unit> =
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
     * The authoritative custom-asset list (#387) — replaces holdings inference so a
     * custom asset with NO holding still appears. On success we upsert every entry
     * and reconcile the cache to the server set; on failure the cache is untouched
     * (offline shows the last-known list).
     */
    override suspend fun refreshCustomAssets(): BtResult<Unit> =
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

    /**
     * Create a custom asset (online-only §7.2); optionally with an initial buy.
     * Caches the identity (incl. category) immediately.
     */
    override suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
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
                BtResult.Ok(a.id)
            }

            is BtResult.Err -> r
        }

    override suspend fun updateCustomAsset(
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

    override suspend fun deleteCustomAsset(id: String): BtResult<Unit> {
        val resp = try {
            api.deleteCustomAsset(id)
        } catch (e: Exception) {
            return at.bettertrack.app.data.api.transportErr(e)
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
    override suspend fun putValuePoints(
        assetId: String,
        points: List<ValuePointEntity>,
    ): BtResult<Unit> =
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
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    // ── Synced-transaction edit / delete (Step 8, §6.2) ─────────────────────
    // ONLINE-ONLY by spec (§7.2 — the queue stays append-only in v1): direct
    // API call; the caller refetches the portfolio scope so Room mirrors server
    // truth.

    override suspend fun updateTransaction(
        portfolioId: String,
        txId: String,
        body: UpdateTransactionRequest,
        idempotencyKey: String?,
    ): BtResult<Unit> =
        when (val r = apiCall(json) { api.updateTransaction(portfolioId, txId, body, idempotencyKey) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)

            is BtResult.Err -> {
                Log.w(TAG, "updateTransaction($txId) failed: ${r.error.message}")
                r
            }
        }

    override suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> {
        val resp = try {
            api.deleteTransaction(portfolioId, txId, idempotencyKey)
        } catch (e: Exception) {
            return at.bettertrack.app.data.api.transportErr(e)
        }
        return if (resp.isSuccessful) {
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

    /** Create a portfolio. Returns its id — the CALLER selects it. */
    override suspend fun createPortfolio(name: String): BtResult<String> =
        when (val r = apiCall(json) { api.createPortfolio(CreatePortfolioRequest(name)) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(r.value.portfolio.id)
            }

            is BtResult.Err -> r
        }

    override suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.updatePortfolio(portfolioId, UpdatePortfolioRequest(name = name)) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    override suspend fun archivePortfolio(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.archivePortfolio(portfolioId) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    override suspend fun restorePortfolio(portfolioId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.restorePortfolio(portfolioId) }) {
            is BtResult.Ok -> {
                upsertFromDto(r.value.portfolio)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /**
     * Hard-delete a portfolio (platform #412, online-only §7.2). 204 → the CALLER
     * purges the local cache for that portfolio and re-pulls the LIST so Room
     * mirrors server truth (the server cascades everything and auto-promotes the
     * derived default — no client bookkeeping). The server rejects the last ACTIVE
     * portfolio with `400 LAST_ACTIVE_PORTFOLIO`; archived ones are always
     * deletable.
     */
    override suspend fun deletePortfolio(portfolioId: String): BtResult<Unit> {
        val resp = try {
            api.deletePortfolio(portfolioId)
        } catch (e: Exception) {
            return at.bettertrack.app.data.api.transportErr(e)
        }
        return if (resp.isSuccessful) {
            BtResult.Ok(Unit)
        } else {
            val err = at.bettertrack.app.data.api.parseApiError(json, resp.code(), resp.errorBody())
            Log.w(TAG, "deletePortfolio($portfolioId) failed: ${err.message}")
            BtResult.Err(err)
        }
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
                    mirror = p.mirror.toPortfolioMirror(),
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

    private companion object {
        const val TAG = "BtPortfolioRepo"
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
        source = source ?: "manual",
        mirror = mirror.toRowMirror(),
    )
