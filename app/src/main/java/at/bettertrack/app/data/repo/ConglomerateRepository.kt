package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AllocateRequest
import at.bettertrack.app.data.api.dto.BacktestPreviewRequest
import at.bettertrack.app.data.api.dto.BacktestWeightDto
import at.bettertrack.app.data.api.dto.ConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.CreateConglomerateRequest
import at.bettertrack.app.data.api.dto.PositionWeightDto
import at.bettertrack.app.data.api.dto.ReplacePositionsRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

// ── Domain models ────────────────────────────────────────────────────────────

data class Conglomerate(
    val id: String,
    val name: String,
    val description: String?,
    val status: String,
    val positionCount: Int,
)

data class ConglomeratePosition(
    val assetId: String,
    val symbol: String,
    val name: String,
    val weightPct: Double,
    val currency: String,
    val type: String,
)

data class ConglomerateDetail(
    val id: String,
    val name: String,
    val description: String?,
    val status: String,
    val positions: List<ConglomeratePosition>,
)

data class AllocationLine(
    val assetId: String,
    val symbol: String,
    val name: String,
    val qty: Double,
    val costEur: Double,
    val nativePrice: Double,
    val currency: String,
    val actualPct: Double,
    val targetPct: Double,
    val unbuyable: Boolean,
    val note: String?,
)

data class Allocation(
    val lines: List<AllocationLine>,
    val totalCostEur: Double,
    val leftoverEur: Double,
    val warnings: List<String>,
    val quoteNotice: String?,
)

data class BacktestStats(
    val totalReturnPct: Double?,
    val cagrPct: Double?,
    val maxDrawdownPct: Double?,
    val volatilityPct: Double?,
)

data class Backtest(
    val series: List<PricePoint>,
    val stats: BacktestStats?,
    val notice: String?,
)

/**
 * Backtest windows. Labels are resources, not a field here — see [AssetRange] for
 * why (same bug, same fix: `3Y` has to be able to say `3J` in German).
 */
enum class BacktestRange(val wire: String) {
    Y1("1Y"), Y3("3Y"), Y5("5Y"), MAX("MAX");

    companion object {
        val DEFAULT = Y1
    }
}

/**
 * Budget-calculator buying mode (§6.7) — mirrors the web app's `AllocateRequest`
 * `mode` enum exactly (contracts/conglomerate.ts, live openapi `AllocateRequest`):
 *  - [WHOLE]      integer shares only; the "at least one share" opt-in applies.
 *  - [FRACTIONAL] fractional quantities to hit the exact target weights; an
 *                 optional `step` sets the quantity granularity (server default
 *                 when omitted). `atLeastOneShare` is ignored server-side here.
 * Default is [WHOLE], matching the web calculator's default.
 */
enum class AllocateMode(val wire: String) {
    WHOLE("whole"),
    FRACTIONAL("fractional");

    companion object {
        val DEFAULT = WHOLE
    }
}

/**
 * Build the `POST /conglomerates/:id/allocate` body, mirroring the web budget
 * calculator's exact rules (BudgetCalculator.tsx): [step] is sent ONLY in
 * fractional mode, [atLeastOneShare] ONLY in whole mode — the server ignores
 * each in the other mode and the web omits them there. Pure + unit-tested.
 */
fun buildAllocateRequest(
    budgetEur: Double,
    mode: AllocateMode,
    atLeastOneShare: Boolean,
    step: Double?,
): AllocateRequest = AllocateRequest(
    budgetEur = budgetEur,
    mode = mode.wire,
    step = if (mode == AllocateMode.FRACTIONAL) step else null,
    atLeastOneShare = mode == AllocateMode.WHOLE && atLeastOneShare,
)

/**
 * Conglomerates repository (Step 13, §6.7 — online-only). Weighted-basket
 * templates, their server-computed past-performance backtest, and the budget
 * allocator. Everything is the server's calculation (§7.1); the app renders it
 * and commits the resulting buy list through the normal transaction queue.
 */
class ConglomerateRepository(
    private val api: BtApi,
    private val json: Json,
) {
    /**
     * The last known basket list, as a flow — the channel a mutation uses to
     * reach a list screen it cannot see (bug fix 2026-08-08).
     *
     * ## The report this answers
     *
     * Deleting a basket left it on screen until the app was restarted, even
     * though the `DELETE` had succeeded. Nothing was wrong with the delete; the
     * problem was that nothing existed to tell anyone about it. [list] was a pure
     * passthrough, so `ConglomerateListViewModel` loaded once into a
     * `MutableStateFlow` and the delete happened in a *different* view model, in
     * a sheet, whose `onDelete` does nothing but pop.
     *
     * Ordinarily a screen re-reads on resume, and that is exactly what the list
     * screen's `LaunchedEffect(Unit) { vm.load() }` was written for. Two
     * properties of this shell make it a once-per-process event instead: sheet
     * destinations are `FloatingWindow`, so the page underneath stays composed
     * while a sheet is over it, and the tab pager keeps every tab inside its
     * composition window. The list is never disposed, so the effect never runs
     * again.
     *
     * ## Why it lives here and not in the view model
     *
     * The repository is the one object both view models share — it is a process
     * singleton in `AppGraph` — so it is the only place a delete performed by one
     * screen can be observed by another. That is also the shape the rest of this
     * package already uses: `PortfolioRepository` and `DefaultWatchlistRepository`
     * prune their Room table inside the delete and let the observing query
     * re-emit, and `ChatRepository`, which has no table, keeps exactly this — a
     * repo-owned `StateFlow` written by every path that changes the collection.
     * Conglomerates are online-only with no table, so they take the latter.
     *
     * `null` means "never loaded", which is not the same as "loaded and empty":
     * an observer must not paint an empty state over a list that has not been
     * fetched yet.
     */
    private val _conglomerates = MutableStateFlow<List<Conglomerate>?>(null)
    val conglomerates: StateFlow<List<Conglomerate>?> = _conglomerates.asStateFlow()

    suspend fun list(): BtResult<List<Conglomerate>> =
        when (val r = apiCall(json) { api.conglomerates() }) {
            is BtResult.Ok -> {
                val items = r.value.conglomerates.map {
                    Conglomerate(it.id, it.name, it.description, it.status, it.positionCount)
                }
                _conglomerates.value = items
                BtResult.Ok(items)
            }

            is BtResult.Err -> r
        }

    suspend fun create(name: String, description: String?): BtResult<ConglomerateDetail> =
        publish(map(apiCall(json) { api.createConglomerate(CreateConglomerateRequest(name.trim(), description?.trim())) }))

    suspend fun detail(id: String): BtResult<ConglomerateDetail> =
        map(apiCall(json) { api.conglomerateDetail(id) })

    suspend fun replacePositions(id: String, weights: List<Pair<String, Double>>): BtResult<ConglomerateDetail> =
        publish(
            map(
                apiCall(json) {
                    api.replaceConglomeratePositions(
                        id,
                        ReplacePositionsRequest(weights.map { PositionWeightDto(it.first, it.second) }),
                    )
                },
            ),
        )

    /**
     * Turn a budget into a server-computed buy list (§7.1 — never client math).
     * The request mirrors the web calculator exactly: [step] only travels in
     * fractional mode, [atLeastOneShare] only in whole mode (the server ignores
     * each in the other mode, and the web omits them there too).
     */
    suspend fun allocate(
        id: String,
        budgetEur: Double,
        mode: AllocateMode,
        atLeastOneShare: Boolean,
        step: Double? = null,
    ): BtResult<Allocation> =
        when (
            val r = apiCall(json) {
                api.allocateConglomerate(id, buildAllocateRequest(budgetEur, mode, atLeastOneShare, step))
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(
                Allocation(
                    lines = r.value.positions.map {
                        AllocationLine(
                            it.assetId, it.symbol, it.name, it.qty, it.costEur, it.nativePrice,
                            it.currency, it.actualPct, it.targetPct, it.unbuyable, it.note,
                        )
                    },
                    totalCostEur = r.value.totalCostEur,
                    leftoverEur = r.value.leftoverEur,
                    warnings = r.value.warnings,
                    quoteNotice = r.value.quoteNotice,
                ),
            )

            is BtResult.Err -> r
        }

    suspend fun backtest(weights: List<Pair<String, Double>>, range: BacktestRange): BtResult<Backtest> =
        when (
            val r = apiCall(json) {
                api.backtestPreview(
                    BacktestPreviewRequest(
                        positions = weights.map { BacktestWeightDto(it.first, it.second) },
                        range = range.wire,
                    ),
                )
            }
        ) {
            is BtResult.Ok -> {
                val points = r.value.series.mapNotNull { p ->
                    MarketRepository.parseIsoToMs(p.date)?.let { PricePoint(it, p.value) }
                }.sortedBy { it.timeMs }
                BtResult.Ok(
                    Backtest(
                        series = points,
                        stats = r.value.stats?.let {
                            BacktestStats(it.totalReturnPct, it.cagrPct, it.maxDrawdownPct, it.volatilityPct)
                        },
                        notice = r.value.notice,
                    ),
                )
            }

            is BtResult.Err -> r
        }

    /**
     * Delete a basket, and drop it from [conglomerates] — the invalidation is
     * part of the delete, not something a caller has to remember.
     *
     * Pruned locally rather than by re-fetching the list: the server has already
     * confirmed the row is gone, so a second round trip would only re-derive a
     * fact this call just established, and it would do it *after* the sheet has
     * popped — a visible flicker of the deleted row on a slow link. The same
     * reasoning `PortfolioRepository.deletePortfolio` applies to its cache purge.
     *
     * A failed delete changes nothing, which is the point of doing this on the
     * `Ok` branch only: the basket is still there, and the list should still
     * say so.
     */
    suspend fun delete(id: String): BtResult<Unit> =
        when (val r = at.bettertrack.app.data.api.unitApiCall(json) { api.deleteConglomerate(id) }) {
            is BtResult.Ok -> {
                _conglomerates.value = _conglomerates.value?.filterNot { it.id == id }
                r
            }

            is BtResult.Err -> r
        }

    /**
     * Fold a successful detail write back into [conglomerates], and pass the
     * result through untouched.
     *
     * The same hole the delete had, on the other two mutations — verified on the
     * device 2026-08-08: a basket created in the builder did not appear in the
     * list either, for exactly the reason a deleted one did not disappear. A flow
     * that were accurate for one of three mutations would be worse than no flow
     * at all, because a caller could reasonably trust it.
     *
     * A [ConglomerateDetail] carries everything a list row needs — the row's
     * `positionCount` is `positions.size`, which is also why `replacePositions`
     * has to come through here: it is the call that turns a freshly created empty
     * draft into a two-position basket.
     *
     * Upsert rather than append, so `replacePositions` updates the existing row
     * instead of adding a second one. A `null` list stays null: "never loaded" is
     * not something one write can turn into a complete list, and inventing a
     * one-item list would make an observer paint a list that is missing every
     * basket the user already had.
     */
    private fun publish(r: BtResult<ConglomerateDetail>): BtResult<ConglomerateDetail> {
        if (r !is BtResult.Ok) return r
        val d = r.value
        val row = Conglomerate(d.id, d.name, d.description, d.status, d.positions.size)
        _conglomerates.value = _conglomerates.value?.let { current ->
            if (current.any { it.id == row.id }) {
                current.map { if (it.id == row.id) row else it }
            } else {
                current + row
            }
        }
        return r
    }

    private fun map(r: BtResult<ConglomerateDetailResponse>): BtResult<ConglomerateDetail> = when (r) {
        is BtResult.Ok -> BtResult.Ok(
            ConglomerateDetail(
                id = r.value.id,
                name = r.value.name,
                description = r.value.description,
                status = r.value.status,
                positions = r.value.positions.sortedBy { it.sortOrder }.map {
                    ConglomeratePosition(it.assetId, it.asset.symbol, it.asset.name, it.weightPct, it.asset.currency, it.asset.type)
                },
            ),
        )

        is BtResult.Err -> r
    }
}
