package at.bettertrack.app.debug

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CreatePortfolioRequest
import at.bettertrack.app.data.api.dto.TransactionDto
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.sync.TxOpPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The ONE portfolio name the destructive test-data cleanup will ever touch.
 *
 * This build runs against the owner's PRODUCTION account, so the throwaway
 * portfolio is identified by an exact name, never by position in a list.
 */
const val TEST_PORTFOLIO_NAME = "ZZ App Test"

/**
 * Pure arming rule for the destructive cleanup (S6 P0-2) — safe by construction
 * and unit-testable without Android:
 *
 *  * a portfolio must be EXPLICITLY selected (`null` = nothing tapped),
 *  * its name must be exactly [TEST_PORTFOLIO_NAME] (no prefix/case/whitespace
 *    leniency — a real portfolio called "ZZ App Test 2" is not the throwaway),
 *  * and the user must have typed that name to confirm.
 *
 * The previous version enabled the button on `selectedId != null` against a
 * silent first-live-portfolio default, i.e. one tap could archive the user's
 * real default portfolio.
 */
fun cleanupArmed(selectedName: String?, typedConfirmation: String): Boolean =
    selectedName == TEST_PORTFOLIO_NAME && typedConfirmation.trim() == TEST_PORTFOLIO_NAME

/**
 * Facade behind the Step-5 debug queue screen: live queue contents, real
 * connectivity + data age, a parameterizable test op, manual drain, and the
 * minimal retry/discard affordances (the full needs-attention UX is Step 8).
 */
class SyncDebugController(
    private val db: BtDatabase,
    private val repo: PortfolioRepository,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    monitor: ConnectivityMonitor,
    private val json: Json,
    private val api: BtApi,
) {
    val ops: Flow<List<SyncOpEntity>> = db.syncOpDao().observeAll()

    /**
     * Prefill for the test-op dialog: any cached asset id (non-custom
     * preferred); falls back to a live ledger peek when holdings haven't been
     * synced yet (they arrive with the first post-drain detail refresh).
     */
    suspend fun anyKnownAssetId(): String? {
        db.holdingDao().anyAssetId()?.let { return it }
        val portfolios = db.portfolioDao().getAll().filter { it.archivedAt == null }
        for (p in portfolios) {
            val live = apiCall(json) { api.transactions(p.id, limit = 10) }
            if (live is BtResult.Ok) {
                live.value.items.firstOrNull { !it.asset.isCustom }?.let { return it.assetId }
                live.value.items.firstOrNull()?.let { return it.assetId }
            }
        }
        return null
    }
    val portfolios: Flow<List<PortfolioEntity>> = repo.portfolios
    val isOnline: StateFlow<Boolean> = monitor.isOnline
    val dataAgeMs: Flow<Long?> = repo.portfolioDataAgeMs

    /**
     * Enqueue a small test transaction (spec Step 5 — the real forms arrive in
     * Step 8). Enqueue is local-only and instant; scheduling then lets the
     * drain run whenever connectivity allows.
     */
    suspend fun enqueueTestOp(
        portfolioId: String,
        assetId: String,
        side: String,
        quantity: Double,
        price: Double,
        note: String?,
        payFromCash: Boolean,
    ) {
        val payload = TxOpPayload(
            assetId = assetId.trim(),
            side = side,
            quantity = quantity,
            price = price,
            executedAt = Instant.now().toString(),
            note = note?.takeIf { it.isNotBlank() },
            payFromCash = if (side == "buy" && payFromCash) true else null,
            addProceedsToCash = null,
        )
        engine.enqueue(
            type = if (side == "buy") OpType.TX_BUY else OpType.TX_SELL,
            portfolioId = portfolioId,
            payloadJson = json.encodeToString(TxOpPayload.serializer(), payload),
        )
        scheduler.scheduleDrain()
    }

    fun drainNow() = scheduler.scheduleDrain(manual = true)

    suspend fun retry(opId: Long) {
        engine.retryOp(opId)
        scheduler.scheduleDrain()
    }

    suspend fun discard(opId: Long) = engine.discardOp(opId)

    suspend fun refreshPortfolios() = repo.refreshPortfolios()

    // ── E2E test-data hygiene (debug-only; production account!) ─────────────
    // The Step-5 done-when requires an API-level round trip: create a throwaway
    // portfolio, verify the drained op landed via a LIVE api GET, then delete
    // the test transactions and archive the portfolio (the platform has no
    // portfolio DELETE — archive is its removal semantics).

    /** Create the throwaway E2E portfolio. Returns a human-readable outcome. */
    suspend fun createTestPortfolio(name: String): String =
        when (val r = apiCall(json) { api.createPortfolio(CreatePortfolioRequest(name)) }) {
            is BtResult.Ok -> {
                repo.refreshPortfolios()
                "Created \"${r.value.portfolio.name}\" (${r.value.portfolio.id.take(8)}…)"
            }

            is BtResult.Err -> "Create failed: HTTP ${r.error.httpStatus} ${r.error.code} — ${r.error.diagnostic.orEmpty()}"
        }

    /** LIVE ledger fetch straight from the API (bypasses Room) — E2E evidence. */
    suspend fun fetchLiveTransactions(portfolioId: String): BtResult<List<TransactionDto>> =
        when (val r = apiCall(json) { api.transactions(portfolioId, limit = 50) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.items)
            is BtResult.Err -> r
        }

    /**
     * Delete EVERY transaction in the throwaway portfolio, then archive it —
     * which is exactly what the button says it does. Returns a human-readable
     * summary.
     *
     * S6 P0-2, two fixes:
     *  * the scope is now the throwaway portfolio itself. It used to filter on
     *    the retired `[bt:` note marker (#417) — a filter that has matched
     *    nothing since the marker was dropped, so the "delete" phase silently
     *    did nothing while `archivePortfolio` still fired.
     *  * the name is re-checked HERE against Room, not only in the UI: the
     *    destructive call refuses any portfolio that is not [expectedName], so
     *    no future caller can point it at a real portfolio by mistake.
     */
    suspend fun cleanupTestData(
        portfolioId: String,
        expectedName: String = TEST_PORTFOLIO_NAME,
    ): String {
        val local = db.portfolioDao().getAll().firstOrNull { it.id == portfolioId }
            ?: return "Cleanup refused: portfolio $portfolioId is not in the local cache."
        if (local.name != expectedName) {
            return "Cleanup REFUSED: \"${local.name}\" is not the throwaway portfolio \"$expectedName\"."
        }
        val live = when (val r = apiCall(json) { api.transactions(portfolioId, limit = 200) }) {
            is BtResult.Ok -> r.value.items
            is BtResult.Err -> return "Cleanup failed at GET: ${r.error.diagnostic.orEmpty()}"
        }
        val testTxs = live
        var deleted = 0
        for (tx in testTxs) {
            // DELETE returns 204 No Content — use the raw response (apiCall would
            // treat the empty body as an error) and accept any 2xx.
            val resp = try {
                api.deleteTransaction(portfolioId, tx.id)
            } catch (e: Exception) {
                return "Deleted $deleted/${testTxs.size}, then delete failed: ${e.message}"
            }
            if (resp.isSuccessful) deleted++ else return "Deleted $deleted/${testTxs.size}, then delete HTTP ${resp.code()}."
        }
        val archived = when (val r = apiCall(json) { api.archivePortfolio(portfolioId) }) {
            is BtResult.Ok -> true
            is BtResult.Err -> return "Deleted $deleted tx; archive failed: ${r.error.diagnostic.orEmpty()}"
        }
        repo.refreshPortfolios()
        return "Deleted $deleted test transaction(s); portfolio archived=$archived"
    }
}
