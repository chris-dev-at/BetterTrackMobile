package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Social DTOs (Steps 14–15, §6.8/§6.9). Mirrors the live platform contract
 * (the `/social` routes in `openapi.json`) EXACTLY so the repository's live paths are a
 * verbatim decode and the future write scopes plug in without shape churn.
 *
 * Platform truth captured 2026-07-08:
 *  - a shareable item's audience is a single [visibility] enum: `private` | `friends`
 *    (there is NO per-friend ACL nor public-link token yet — those richer audience
 *    tiers in §6.9 are app-ahead and live behind [at.bettertrack.app.data.repo.SocialFlags]);
 *  - friend WRITE routes (request/accept/decline/cancel/unfriend) require a
 *    `social:write` scope the mobile client is not yet granted — the app models
 *    them here so the adapter is ready, but the repository routes them through a
 *    stub source until the scope lands.
 */

/** A minimal public user reference — id + username (no email/avatar on the platform). */
@Serializable
data class SocialUserDto(
    val id: String,
    val username: String,
)

// ── GET /social/friends ──────────────────────────────────────────────────────

@Serializable
data class FriendsListResponse(
    val friends: List<FriendDto> = emptyList(),
)

@Serializable
data class FriendDto(
    val user: SocialUserDto,
    val createdAt: String,
)

// ── GET /social/requests ─────────────────────────────────────────────────────

@Serializable
data class FriendRequestListResponse(
    val incoming: List<FriendRequestDto> = emptyList(),
    val outgoing: List<FriendRequestDto> = emptyList(),
)

@Serializable
data class FriendRequestDto(
    val id: String,
    /** `incoming` | `outgoing`. */
    val direction: String,
    /** `pending` | `accepted` | `declined` | `cancelled`. */
    val status: String,
    val user: SocialUserDto,
    val createdAt: String,
    val respondedAt: String? = null,
)

// ── POST /social/requests ────────────────────────────────────────────────────

/** Username OR email; response is identical whether the target exists (no enumeration). */
@Serializable
data class CreateFriendRequestRequest(
    val identifier: String,
)

// ── GET /social/shared — everything my friends share with me ─────────────────

@Serializable
data class SharedWithMeResponse(
    val portfolios: List<SharedPortfolioSummaryDto> = emptyList(),
    val conglomerates: List<SharedConglomerateSummaryDto> = emptyList(),
    val watchlists: List<SharedWatchlistSummaryDto> = emptyList(),
)

@Serializable
data class SharedPortfolioSummaryDto(
    val portfolioId: String,
    val name: String,
    val owner: SocialUserDto,
    val totalValueEur: Double,
)

@Serializable
data class SharedConglomerateSummaryDto(
    val conglomerateId: String,
    val name: String,
    val owner: SocialUserDto,
    /** `draft` | `active`. */
    val status: String,
    val positionCount: Int,
)

@Serializable
data class SharedWatchlistSummaryDto(
    val owner: SocialUserDto,
    val itemCount: Int,
)

// ── GET /social/my-shared — everything I currently share ─────────────────────

@Serializable
data class MySharedResponse(
    val portfolios: List<MySharedPortfolioDto> = emptyList(),
    val conglomerates: List<MySharedConglomerateDto> = emptyList(),
    val watchlist: MySharedWatchlistDto,
)

@Serializable
data class MySharedPortfolioDto(
    val id: String,
    val name: String,
    /** `private` | `friends`. */
    val visibility: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val defaultPayFromCash: Boolean = false,
    val archivedAt: String? = null,
)

@Serializable
data class MySharedConglomerateDto(
    val id: String,
    val name: String,
    val description: String? = null,
    /** `draft` | `active`. */
    val status: String,
    /** `private` | `friends`. */
    val visibility: String,
    val positionCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MySharedWatchlistDto(
    /** `private` | `friends`. */
    val visibility: String,
    val itemCount: Int,
)

// ── GET /social/shared/{portfolioId} — read-only friend-shared portfolio ─────

@Serializable
data class SharedPortfolioDetailResponse(
    val portfolioId: String,
    val name: String,
    val owner: SocialUserDto,
    val baseCurrency: String,
    val totals: SharedPortfolioTotalsDto,
    val holdings: List<SharedHoldingDto> = emptyList(),
    val history: SharedPortfolioHistoryDto,
)

@Serializable
data class SharedPortfolioTotalsDto(
    val marketValueEur: Double,
    val investedEur: Double,
    val unrealizedPnlEur: Double,
    val unrealizedPnlPct: Double? = null,
    val dayChangeEur: Double,
    val dayChangePct: Double? = null,
    val cashEur: Double,
    val totalValueEur: Double,
)

@Serializable
data class SharedHoldingDto(
    val asset: SharedAssetDto,
    val quantity: Double,
    val avgCost: Double,
    val realizedPnl: Double,
    val price: Double? = null,
    val marketValueEur: Double? = null,
    val costBasisEur: Double? = null,
    val unrealizedPnlEur: Double? = null,
    val unrealizedPnlPct: Double? = null,
    val dayChangeEur: Double? = null,
    val dayChangePct: Double? = null,
)

@Serializable
data class SharedAssetDto(
    val id: String? = null,
    val symbol: String,
    val name: String,
    val exchange: String? = null,
    val currency: String,
    /** stock | etf | index | fx | commodity | crypto | custom. */
    val type: String,
    val isCustom: Boolean = false,
)

@Serializable
data class SharedPortfolioHistoryDto(
    /** 1M | 6M | 1Y | MAX. */
    val range: String,
    val points: List<SharedHistoryPointDto> = emptyList(),
)

@Serializable
data class SharedHistoryPointDto(
    val date: String,
    val valueEur: Double,
)

// ── GET /social/shared/watchlists/{userId} ───────────────────────────────────

@Serializable
data class SharedWatchlistDetailResponse(
    val owner: SocialUserDto,
    val items: List<SharedWatchlistItemDto> = emptyList(),
)

@Serializable
data class SharedWatchlistItemDto(
    val id: String,
    val assetId: String,
    val sortOrder: Int = 0,
    val note: String? = null,
    val asset: SharedAssetDto,
)

// ── GET /social/shared/conglomerates/{conglomerateId} ────────────────────────

@Serializable
data class SharedConglomerateDetailResponse(
    val conglomerateId: String,
    val name: String,
    val description: String? = null,
    /** draft | active. */
    val status: String,
    val owner: SocialUserDto,
    val positions: List<SharedConglomeratePositionDto> = emptyList(),
)

@Serializable
data class SharedConglomeratePositionDto(
    val assetId: String,
    val weightPct: Double,
    val sortOrder: Int = 0,
    val asset: SharedAssetDto,
)

// ── Sharing mutations (visibility PATCH — portfolio:write / workboard:write) ──

/** PATCH /workboard/sharing — set the single watchlist's audience. */
@Serializable
data class UpdateWatchlistSharingRequest(
    /** `private` | `friends`. */
    val visibility: String,
)

/** GET /workboard/sharing. */
@Serializable
data class WatchlistSharingResponse(
    /** `private` | `friends`. */
    val visibility: String,
)

/** PATCH /conglomerates/{id} — rename/describe and/or change audience. */
@Serializable
data class UpdateConglomerateRequest(
    val name: String? = null,
    val description: String? = null,
    /** `private` | `friends`. */
    val visibility: String? = null,
)
