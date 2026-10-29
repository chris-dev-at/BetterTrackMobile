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

/**
 * One stored row of a basket. The wire is a `oneOf` discriminated by [kind]
 * (platform #655, V5-P6 nesting):
 *
 *  - `kind: "asset"` — [assetId] + [asset];
 *  - `kind: "conglomerate"` — a NESTED basket: [childId] + [child], no asset.
 *
 * Modelled as one tolerant shape rather than a polymorphic hierarchy because
 * the app's single [kotlinx.serialization.json.Json] instance has no class
 * discriminator configured and is shared with the sync queue's payloads; the
 * flattened form decodes both branches without touching that setup. Before
 * this, `assetId`/`asset` were required, and ONE child basket in a basket made
 * the whole detail (and every write answering with it) fail with a
 * `MissingFieldException` — latent since nesting shipped.
 *
 * A missing [kind] reads as `asset`: that is every row a pre-nesting server
 * ever sent.
 */
@Serializable
data class ConglomeratePositionDto(
    val kind: String = CONGLOMERATE_POSITION_ASSET,
    val assetId: String? = null,
    val childId: String? = null,
    val weightPct: Double,
    val sortOrder: Int = 0,
    val asset: ConglomerateAssetDto? = null,
    val child: ConglomerateChildDto? = null,
) {
    /** True for a nested-basket row (`kind: "conglomerate"`). */
    val isNested: Boolean get() = kind == CONGLOMERATE_POSITION_NESTED
}

/** [ConglomeratePositionDto.kind] for an asset row. */
const val CONGLOMERATE_POSITION_ASSET = "asset"

/** [ConglomeratePositionDto.kind] for a nested-basket row. */
const val CONGLOMERATE_POSITION_NESTED = "conglomerate"

/** The child basket a nested row points at — identity only, never its rows. */
@Serializable
data class ConglomerateChildDto(
    val id: String,
    val name: String,
    /** draft | active. */
    val status: String = "draft",
    val positionCount: Int = 0,
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

/**
 * One row of `PUT /conglomerates/{id}/positions`: EXACTLY one of [assetId] /
 * [childId] (the request is an `anyOf` of the two `.strict()` shapes). The
 * other stays null and — `explicitNulls = false` — never reaches the wire.
 */
@Serializable
data class PositionWeightDto(
    val assetId: String? = null,
    val weightPct: Double,
    val childId: String? = null,
)

// ── GET /conglomerates/{id}/resolved (flattened effective weights) ───────────

/**
 * The basket flattened to plain assets (V5-P6): what a nested basket's
 * backtest has to run over, since its stored rows include children that are
 * not runnable positions. [unresolvedPct] is the share an empty child left
 * behind; the resolved weights are normalised among themselves.
 */
@Serializable
data class ConglomerateResolvedResponse(
    val conglomerateId: String,
    val nested: Boolean = false,
    val positions: List<ResolvedPositionDto> = emptyList(),
    val unresolvedPct: Double = 0.0,
)

@Serializable
data class ResolvedPositionDto(
    val assetId: String,
    val weightPct: Double,
    val asset: ConglomerateAssetDto? = null,
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
