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
import at.bettertrack.app.data.api.parseApiError
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

enum class BacktestRange(val wire: String, val label: String) {
    Y1("1Y", "1Y"), Y3("3Y", "3Y"), Y5("5Y", "5Y"), MAX("MAX", "Max");

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
    suspend fun list(): BtResult<List<Conglomerate>> =
        when (val r = apiCall(json) { api.conglomerates() }) {
            is BtResult.Ok -> BtResult.Ok(
                r.value.conglomerates.map {
                    Conglomerate(it.id, it.name, it.description, it.status, it.positionCount)
                },
            )

            is BtResult.Err -> r
        }

    suspend fun create(name: String, description: String?): BtResult<ConglomerateDetail> =
        map(apiCall(json) { api.createConglomerate(CreateConglomerateRequest(name.trim(), description?.trim())) })

    suspend fun detail(id: String): BtResult<ConglomerateDetail> =
        map(apiCall(json) { api.conglomerateDetail(id) })

    suspend fun replacePositions(id: String, weights: List<Pair<String, Double>>): BtResult<ConglomerateDetail> =
        map(
            apiCall(json) {
                api.replaceConglomeratePositions(
                    id,
                    ReplacePositionsRequest(weights.map { PositionWeightDto(it.first, it.second) }),
                )
            },
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

    suspend fun delete(id: String): BtResult<Unit> {
        val resp = try {
            api.deleteConglomerate(id)
        } catch (_: java.io.IOException) {
            return BtResult.Err(
                at.bettertrack.app.data.api.BtApiError(0, at.bettertrack.app.data.api.BtApiError.Codes.NETWORK, "No connection."),
            )
        }
        return if (resp.isSuccessful) BtResult.Ok(Unit)
        else BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
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
