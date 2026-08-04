package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CustomAssetInitialPurchase
import at.bettertrack.app.data.api.dto.UpdateTransactionRequest
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.domain.CurrencyConverter
import at.bettertrack.app.domain.HoldingQuote
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultPayloads
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decimal
import at.bettertrack.app.vault.moneyString
import at.bettertrack.app.vault.text
import at.bettertrack.app.vault.vaultNowIso
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Drive-mode [PortfolioBackend] (S3/S4 plan §2.5, §5 W4).
 *
 * Its whole job is to **fill the same Room read-model tables** the server
 * backend fills, from vault entities instead of API responses. The §7.1 doctrine
 * is what makes that a drop-in: screens read only Room, so swapping the filler
 * changes nothing above this line — no ViewModel, no Composable, no navigation.
 *
 * ## "Refresh" means "re-derive", not "re-fetch"
 *
 * There is no server to ask. Every `refresh*` here runs the ported engine over
 * the current vault and writes the result. That makes them cheap and always
 * available — including in airplane mode, which is the point of the mode — and
 * it makes pull-to-refresh a genuinely correct gesture rather than a no-op:
 * prices may have been cached since, and the derivation reads them.
 *
 * ## Mutations go through the queue, not through here
 *
 * The `PortfolioBackend` mutation methods that exist for the *server*'s
 * online-only writes (create/rename/archive a portfolio, cash sources, custom
 * assets) apply to the vault directly, because there is no network to be online
 * for. But the LEDGER writes — buys, sells, cash movements, value points — do
 * **not** appear here at all: they arrive through `SyncEngine.enqueue` →
 * `ModeRoutingOpExecutor` → `VaultOpExecutor`, exactly as they do on the server
 * path (plan §1.2). One write path, one place domain refusals surface, one
 * pending-sync screen.
 *
 * ## Cache discipline
 *
 * Derived series are keyed `(vaultVersion, priceWatermark, range)` (plan §2.5).
 * `vaultVersion` moves on every edit and `priceWatermark` on every cached price,
 * so a hit means nothing that could change the numbers has changed. The cache is
 * in-process only: Room already holds the last derivation durably.
 */
class VaultPortfolioBackend(
    private val db: BtDatabase,
    private val store: VaultStore,
    private val projector: VaultProjector,
    private val market: NoLivePricesMarketDataSource,
    private val converter: CurrencyConverter = EurOnlyCurrencyConverter(),
    private val today: () -> String = { LocalDate.now(ZoneOffset.UTC).toString() },
    private val now: () -> Long = System::currentTimeMillis,
    /** Called after any mutation so the coalescing Drive push is scheduled. */
    private val onVaultChanged: () -> Unit = {},
) : PortfolioBackend {

    private val derivationLock = Mutex()
    private var cacheKey: ProjectionCacheKey? = null

    // ── Projection refreshes ────────────────────────────────────────────────

    override suspend fun refreshPortfolios(): BtResult<Unit> = deriveAll()

    override suspend fun refreshPortfolioDetail(portfolioId: String): BtResult<Unit> =
        derive(portfolioId, listOf(HistoryRange.DEFAULT))

    /**
     * The vault holds every transaction locally, so the ledger is never paged —
     * a `null` cursor means "that was all of it", which is exactly what the
     * repository's paging logic already expects at the end of a list.
     */
    override suspend fun refreshTransactions(portfolioId: String): BtResult<String?> =
        when (val result = derive(portfolioId, listOf(HistoryRange.DEFAULT))) {
            is BtResult.Ok -> BtResult.Ok(null)
            is BtResult.Err -> result
        }

    override suspend fun loadMoreTransactions(portfolioId: String, cursor: String): BtResult<String?> =
        BtResult.Ok(null)

    override suspend fun refreshHistory(portfolioId: String, range: HistoryRange): BtResult<Unit> =
        derive(portfolioId, listOf(range))

    override suspend fun refreshCash(portfolioId: String): BtResult<Unit> =
        derive(portfolioId, listOf(HistoryRange.DEFAULT))

    override suspend fun refreshCustomAssets(): BtResult<Unit> = deriveAll()

    override suspend fun refreshValuePoints(assetId: String): BtResult<Unit> = deriveAll()

    // ── Portfolio lifecycle ─────────────────────────────────────────────────

    override suspend fun createPortfolio(name: String): BtResult<String> {
        val created = store.mutate { graph, context ->
            val id = context.newId()
            graph.create(
                kind = VaultKinds.PORTFOLIO,
                id = id,
                data = VaultPayloads.portfolio(userId = null, name = name),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
            // A portfolio with no cash source cannot record a pay-from-cash trade,
            // and the server creates a "Main" source with every portfolio — so the
            // vault does too, or the two modes would disagree on what a fresh
            // portfolio even contains.
            graph.create(
                kind = VaultKinds.CASH_SOURCE,
                id = context.newId(),
                data = VaultPayloads.cashSource(
                    portfolioId = id,
                    name = MAIN_SOURCE_NAME,
                    type = "cash",
                    isMain = true,
                    createdAt = context.now,
                ),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
            id
        }
        afterMutation()
        deriveAll()
        return BtResult.Ok(created.value)
    }

    override suspend fun renamePortfolio(portfolioId: String, name: String): BtResult<Unit> =
        editEntity(VaultKinds.PORTFOLIO, portfolioId) { it.with("name", JsonPrimitive(name)) }

    override suspend fun archivePortfolio(portfolioId: String): BtResult<Unit> =
        editEntity(VaultKinds.PORTFOLIO, portfolioId) { data ->
            data.with("archivedAt", JsonPrimitive(nowIsoDate()))
        }

    override suspend fun restorePortfolio(portfolioId: String): BtResult<Unit> =
        editEntity(VaultKinds.PORTFOLIO, portfolioId) { it.withNull("archivedAt") }

    /**
     * Tombstones the portfolio **and everything scoped to it**.
     *
     * Leaving the children behind would not merely litter: a transaction whose
     * portfolio is gone is still a live entity, so the next merge would carry it
     * to the other device, which would derive holdings for a portfolio it cannot
     * show. Tombstoning (never removing) is what makes the delete propagate.
     */
    override suspend fun deletePortfolio(portfolioId: String): BtResult<Unit> {
        store.mutate { graph, context ->
            for (kind in listOf(VaultKinds.TRANSACTION, VaultKinds.CASH_MOVEMENT, VaultKinds.CASH_SOURCE)) {
                graph.live(kind)
                    .filter { it.text("portfolioId") == portfolioId }
                    .forEach { graph.tombstone(kind, it.id, context.now, context.deviceId) }
            }
            graph.tombstone(VaultKinds.PORTFOLIO, portfolioId, context.now, context.deviceId)
        }
        afterMutation()
        return BtResult.Ok(Unit)
    }

    // ── Cash sources ────────────────────────────────────────────────────────

    override suspend fun createCashSource(portfolioId: String, name: String, type: String): BtResult<Unit> {
        store.mutate { graph, context ->
            graph.create(
                kind = VaultKinds.CASH_SOURCE,
                id = context.newId(),
                data = VaultPayloads.cashSource(
                    portfolioId = portfolioId,
                    name = name,
                    type = type,
                    isMain = false,
                    createdAt = context.now,
                ),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
        }
        afterMutation()
        return derive(portfolioId, listOf(HistoryRange.DEFAULT))
    }

    override suspend fun updateCashSource(
        portfolioId: String,
        sourceId: String,
        name: String?,
        type: String?,
    ): BtResult<Unit> = editEntity(VaultKinds.CASH_SOURCE, sourceId) { data ->
        var updated = data
        if (name != null) updated = updated.with("name", JsonPrimitive(name))
        if (type != null) updated = updated.with("type", JsonPrimitive(type))
        updated
    }

    override suspend fun archiveCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        editEntity(VaultKinds.CASH_SOURCE, sourceId) { it.with("archivedAt", JsonPrimitive(nowIsoDate())) }

    override suspend fun restoreCashSource(portfolioId: String, sourceId: String): BtResult<Unit> =
        editEntity(VaultKinds.CASH_SOURCE, sourceId) { it.withNull("archivedAt") }

    // ── Cash movement corrections ───────────────────────────────────────────

    override suspend fun updateCashMovement(
        portfolioId: String,
        movementId: String,
        patch: JsonObject,
        idempotencyKey: String?,
    ): BtResult<Unit> {
        store.mutate { graph, context ->
            graph.edit(VaultKinds.CASH_MOVEMENT, movementId, context.now, context.deviceId) { data ->
                JsonObject(LinkedHashMap(data).apply { putAll(patch) })
            }
        }
        afterMutation()
        return derive(portfolioId, listOf(HistoryRange.DEFAULT))
    }

    override suspend fun deleteCashMovement(
        portfolioId: String,
        movementId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> {
        store.mutate { graph, context ->
            graph.tombstone(VaultKinds.CASH_MOVEMENT, movementId, context.now, context.deviceId)
        }
        afterMutation()
        return derive(portfolioId, listOf(HistoryRange.DEFAULT))
    }

    // ── Custom assets ───────────────────────────────────────────────────────

    override suspend fun createCustomAsset(
        name: String,
        category: String,
        smoothing: Boolean,
        initial: CustomAssetInitialPurchase?,
    ): BtResult<String> {
        val created = store.mutate { graph, context ->
            val id = context.newId()
            graph.create(
                kind = VaultKinds.CUSTOM_ASSET,
                id = id,
                data = VaultPayloads.customAsset(
                    // A non-null ownerId is what marks this as the USER's asset
                    // rather than the vault's copy of a platform one — the
                    // projector splits the catalogue on exactly that field.
                    ownerId = store.vaultAccountId(),
                    type = category,
                    symbol = name,
                    name = name,
                    currency = VaultProjector.BASE_CURRENCY,
                ).with("smoothing", JsonPrimitive(smoothing)),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
            id
        }
        afterMutation()
        deriveAll()
        return BtResult.Ok(created.value)
    }

    override suspend fun updateCustomAsset(
        id: String,
        name: String?,
        category: String?,
        smoothing: Boolean?,
    ): BtResult<Unit> = editEntity(VaultKinds.CUSTOM_ASSET, id) { data ->
        var updated = data
        if (name != null) updated = updated.with("name", JsonPrimitive(name))
        if (category != null) updated = updated.with("type", JsonPrimitive(category))
        if (smoothing != null) updated = updated.with("smoothing", JsonPrimitive(smoothing))
        updated
    }

    override suspend fun deleteCustomAsset(id: String): BtResult<Unit> {
        store.mutate { graph, context ->
            graph.live(VaultKinds.CUSTOM_ASSET_VALUE)
                .filter { it.text("assetId") == id }
                .forEach { graph.tombstone(VaultKinds.CUSTOM_ASSET_VALUE, it.id, context.now, context.deviceId) }
            graph.tombstone(VaultKinds.CUSTOM_ASSET, id, context.now, context.deviceId)
        }
        afterMutation()
        return deriveAll()
    }

    /** Full replace of the point set, matching the API's PUT semantics exactly. */
    override suspend fun putValuePoints(assetId: String, points: List<ValuePointEntity>): BtResult<Unit> {
        store.mutate { graph, context ->
            val byDate = points.associateBy { it.date }
            graph.live(VaultKinds.CUSTOM_ASSET_VALUE)
                .filter { it.text("assetId") == assetId }
                .forEach { existing ->
                    val replacement = byDate[existing.text("date")]
                    if (replacement == null) {
                        graph.tombstone(VaultKinds.CUSTOM_ASSET_VALUE, existing.id, context.now, context.deviceId)
                    } else {
                        graph.edit(VaultKinds.CUSTOM_ASSET_VALUE, existing.id, context.now, context.deviceId) {
                            VaultPayloads.customAssetValue(assetId, replacement.date, replacement.value)
                        }
                    }
                }
            val known = graph.live(VaultKinds.CUSTOM_ASSET_VALUE)
                .filter { it.text("assetId") == assetId }
                .mapNotNull { it.text("date") }
                .toSet()
            points.filter { it.date !in known }.forEach { point ->
                graph.create(
                    kind = VaultKinds.CUSTOM_ASSET_VALUE,
                    id = context.newId(),
                    data = VaultPayloads.customAssetValue(assetId, point.date, point.value),
                    editedAt = context.now,
                    editedBy = context.deviceId,
                )
            }
        }
        afterMutation()
        return deriveAll()
    }

    // ── Synced-transaction edit / delete ────────────────────────────────────

    override suspend fun updateTransaction(
        portfolioId: String,
        txId: String,
        body: UpdateTransactionRequest,
        idempotencyKey: String?,
    ): BtResult<Unit> {
        store.mutate { graph, context ->
            graph.edit(VaultKinds.TRANSACTION, txId, context.now, context.deviceId) { data ->
                var updated = data
                body.quantity?.let { updated = updated.with("quantity", JsonPrimitive(money(it))) }
                body.price?.let { updated = updated.with("price", JsonPrimitive(money(it))) }
                body.fee?.let { updated = updated.with("fee", JsonPrimitive(money(it))) }
                body.executedAt?.let { updated = updated.with("executedAt", JsonPrimitive(it)) }
                body.note?.let { updated = updated.with("note", JsonPrimitive(it)) }
                updated
            }
        }
        afterMutation()
        return derive(portfolioId, listOf(HistoryRange.DEFAULT))
    }

    override suspend fun deleteTransaction(
        portfolioId: String,
        txId: String,
        idempotencyKey: String?,
    ): BtResult<Unit> {
        store.mutate { graph, context ->
            // The linked cash leg goes with it; leaving it would credit or debit
            // cash for a trade that no longer exists.
            graph.live(VaultKinds.CASH_MOVEMENT)
                .filter { it.text("transactionId") == txId }
                .forEach { graph.tombstone(VaultKinds.CASH_MOVEMENT, it.id, context.now, context.deviceId) }
            graph.tombstone(VaultKinds.TRANSACTION, txId, context.now, context.deviceId)
        }
        afterMutation()
        return derive(portfolioId, listOf(HistoryRange.DEFAULT))
    }

    // ── Derivation ──────────────────────────────────────────────────────────

    /** Re-derives every portfolio in the vault. */
    suspend fun deriveAll(): BtResult<Unit> {
        val portfolioIds = store.snapshot().graph.live(VaultKinds.PORTFOLIO).map { it.id }
        for (id in portfolioIds) {
            val result = derive(id, listOf(HistoryRange.DEFAULT))
            if (result is BtResult.Err) return result
        }
        return BtResult.Ok(Unit)
    }

    suspend fun derive(portfolioId: String, ranges: List<HistoryRange>): BtResult<Unit> = derivationLock.withLock {
        val snapshot = store.snapshot()
        val watermark = market.priceWatermark()
        val key = ProjectionCacheKey(snapshot.vaultVersion, watermark, ranges.firstOrNull() ?: HistoryRange.DEFAULT)
        // Nothing that could change a number has changed — and Room still holds
        // the previous derivation, so there is nothing to re-write either.
        if (cacheKey == key) return@withLock BtResult.Ok(Unit)

        val prices = market.cachedPrices()
        val inputs = VaultProjectionInputs(
            today = today(),
            market = buildMarketInputs(snapshot.graph, prices),
            converter = converter,
            syncedAtMs = now(),
        )
        val projected = try {
            projector.project(snapshot.graph, portfolioId, inputs, ranges)
        } catch (cause: NoExchangeRateException) {
            return@withLock BtResult.Err(noRateError(cause))
        } catch (cause: at.bettertrack.app.domain.DomainException) {
            // A vault the engine refuses to derive is a real, user-visible
            // problem — surfaced, never swallowed into a blank screen.
            return@withLock BtResult.Err(derivationError(cause))
        }
        writeProjection(portfolioId, projected)
        cacheKey = key
        BtResult.Ok(Unit)
    }

    /**
     * Market inputs per transacted asset.
     *
     * A custom asset's "prices" are the user's own value points — that is what
     * makes a manually-valued asset participate in the same curve as a quoted
     * one, with no special case anywhere downstream.
     */
    private fun buildMarketInputs(
        graph: VaultEntityGraph,
        cached: Map<String, List<at.bettertrack.app.domain.PricePoint>>,
    ): Map<String, AssetMarketData> {
        val valuePoints = graph.live(VaultKinds.CUSTOM_ASSET_VALUE)
            .groupBy { it.text("assetId").orEmpty() }
            .mapValues { (_, rows) ->
                rows.mapNotNull { row ->
                    val date = row.text("date") ?: return@mapNotNull null
                    val value = row.decimal("value") ?: return@mapNotNull null
                    at.bettertrack.app.domain.PricePoint(date, value)
                }.sortedBy { it.date }
            }

        val assetIds = (cached.keys + valuePoints.keys).toSet()
        return assetIds.associateWith { assetId ->
            val prices = valuePoints[assetId]?.takeIf { it.isNotEmpty() } ?: cached[assetId].orEmpty()
            AssetMarketData(
                prices = prices,
                // Today's close IS the live quote when there is no live source.
                // prevClose is the day before, so the day-change column is honest
                // rather than absent.
                quote = prices.lastOrNull()?.let { last ->
                    HoldingQuote(price = last.close, prevClose = prices.dropLast(1).lastOrNull()?.close)
                },
            )
        }
    }

    private suspend fun writeProjection(portfolioId: String, projected: ProjectedPortfolioData) {
        db.portfolioDao().upsertAll(projected.portfolios)
        db.portfolioDao().deleteNotIn(projected.portfolios.map { it.id })
        db.holdingDao().replaceForPortfolio(portfolioId, projected.holdings)
        db.transactionDao().replaceForPortfolio(portfolioId, projected.transactions)
        db.cashDao().replaceForPortfolio(portfolioId, projected.cashSources, projected.cashMovements)
        db.customAssetDao().upsertAll(projected.customAssets)
        for ((assetId, points) in projected.valuePoints.groupBy { it.assetId }) {
            db.customAssetDao().replaceValuePoints(assetId, points)
        }
        for (history in projected.history) db.portfolioHistoryDao().upsert(history)
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private suspend fun editEntity(
        kind: String,
        id: String,
        transform: (JsonObject) -> JsonObject,
    ): BtResult<Unit> {
        store.mutate { graph, context -> graph.edit(kind, id, context.now, context.deviceId, transform) }
        afterMutation()
        return deriveAll()
    }

    /** Invalidate the derivation cache and ask for a Drive push. */
    private fun afterMutation() {
        cacheKey = null
        onVaultChanged()
    }

    private fun nowIsoDate(): String = vaultNowIso()

    private fun money(value: Double): String = moneyString(value)

    companion object {
        const val MAIN_SOURCE_NAME = "Main"

        private fun noRateError(cause: NoExchangeRateException) = BtApiError(
            httpStatus = 0,
            code = "NO_EXCHANGE_RATE",
            userMessage = "BetterTrack has no ${cause.from} → ${cause.to} rate on this device, " +
                "so this portfolio can't be valued in ${cause.to} yet.",
            details = null,
            serverMessage = null,
        )

        private fun derivationError(cause: at.bettertrack.app.domain.DomainException) = BtApiError(
            httpStatus = 0,
            code = "VAULT_DERIVATION_FAILED",
            userMessage = cause.message ?: "This portfolio's data could not be calculated on this device.",
            details = null,
            serverMessage = cause.message,
        )
    }
}

private fun JsonObject.with(key: String, value: JsonPrimitive): JsonObject =
    JsonObject(LinkedHashMap(this).apply { put(key, value) })

private fun JsonObject.withNull(key: String): JsonObject =
    JsonObject(LinkedHashMap(this).apply { put(key, kotlinx.serialization.json.JsonNull) })
