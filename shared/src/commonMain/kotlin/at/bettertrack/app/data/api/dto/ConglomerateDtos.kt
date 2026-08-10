package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for conglomerates (Step 13, §6.7, online-only): the weighted-basket
 * template, its past-performance backtest, and the budget allocator. Field
 * names mirror the OpenAPI contract; the server is the only calculator (§7.1) —
 * weights, backtest and allocation math are all server-computed.
 */

// ── Detail / create / positions ──────────────────────────────────────────────
@Serializable
data class ConglomerateDetailResponse(
    val id: String,
    val name: String,
    val description: String? = null,
    val status: String,
    val visibility: String,
    val positionCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
    val positions: List<ConglomeratePositionDto> = emptyList(),
)

@Serializable
data class ConglomeratePositionDto(
    val assetId: String,
    val weightPct: Double,
    val sortOrder: Int = 0,
    val asset: ConglomerateAssetDto,
)

@Serializable
data class ConglomerateAssetDto(
    val symbol: String,
    val name: String,
    val currency: String,
    val type: String,
)

@Serializable
data class CreateConglomerateRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class ReplacePositionsRequest(
    val positions: List<PositionWeightDto>,
)

@Serializable
data class PositionWeightDto(
    val assetId: String,
    val weightPct: Double,
)

// ── POST /conglomerates/{id}/allocate (budget calculator) ────────────────────
@Serializable
data class AllocateRequest(
    val budgetEur: Double,
    /** "whole" (integer shares) | "fractional". */
    val mode: String,
    val step: Double? = null,
    val atLeastOneShare: Boolean = false,
)

@Serializable
data class AllocateResponse(
    val positions: List<AllocatePositionDto> = emptyList(),
    val totalCostEur: Double = 0.0,
    val leftoverEur: Double = 0.0,
    val warnings: List<String> = emptyList(),
    val stale: Boolean = false,
    val quoteNotice: String? = null,
)

@Serializable
data class AllocatePositionDto(
    val assetId: String,
    val symbol: String,
    val name: String,
    val qty: Double,
    val costEur: Double,
    val nativePrice: Double,
    val currency: String,
    val actualPct: Double,
    val targetPct: Double,
    val deltaPp: Double = 0.0,
    val unbuyable: Boolean = false,
    val note: String? = null,
)

// ── POST /backtest/preview (past-performance graph) ──────────────────────────
@Serializable
data class BacktestPreviewRequest(
    val positions: List<BacktestWeightDto>,
    /** 1Y | 3Y | 5Y | MAX. */
    val range: String,
)

@Serializable
data class BacktestWeightDto(
    val assetId: String,
    val weight: Double,
)

@Serializable
data class BacktestResponse(
    val startDate: String? = null,
    val endDate: String? = null,
    val series: List<BacktestPointDto> = emptyList(),
    val stats: BacktestStatsDto? = null,
    val notice: String? = null,
)

@Serializable
data class BacktestPointDto(
    val date: String,
    val value: Double,
)

@Serializable
data class BacktestStatsDto(
    val totalReturnPct: Double? = null,
    val cagrPct: Double? = null,
    val maxDrawdownPct: Double? = null,
    val volatilityPct: Double? = null,
)
