package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Social DTOs (Steps 14–15, §6.9 + V3 sharing). Mirrors the LIVE platform
 * contract (`packages/contracts/src/social.ts` + `workboard.ts`, verified against
 * `openapi.json` 2026-07-09) EXACTLY so the repository's live paths are a verbatim
 * decode.
 *
 * Platform truth (Sharing v3 #332 + friend writes #341 + activity prefs V3-P6):
 *  - a shareable item's audience is the unified 4-rung ladder
 *    (`private` | `specific_friends` | `all_friends` | `public_link`), governed by
 *    ONE model across every portfolio / conglomerate / watchlist via
 *    `GET|PUT /social/audience/:kind/:subjectId`;
 *  - `public_link` mints a hash-only token ONCE (surfaced immediately, never
 *    re-fetchable); unauthenticated resolve at `GET /social/links/:token`;
 *  - friend WRITE routes (request/accept/decline/cancel/unfriend) are live under
 *    `social:write`;
 *  - a per-viewer activity-alert opt-in per shared item persists via
 *    `PUT /social/shared/activity/:kind/:subjectId` (delivery is Notifications-v2
 *    #368, still platform-gated).
 */

/** A minimal public user reference — id + username (no email/avatar, §6.9). */
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
    /** Viewer's per-item activity-alert opt-in (V3-P6); delivery deferred to #368. */
    val activityAlertsEnabled: Boolean = false,
)

@Serializable
data class SharedConglomerateSummaryDto(
    val conglomerateId: String,
    val name: String,
    val owner: SocialUserDto,
    /** `draft` | `active`. */
    val status: String,
    val positionCount: Int,
    val activityAlertsEnabled: Boolean = false,
)

@Serializable
data class SharedWatchlistSummaryDto(
    val watchlistId: String,
    val name: String,
    val owner: SocialUserDto,
    val itemCount: Int,
    val activityAlertsEnabled: Boolean = false,
)

// ── GET /social/my-shared — every shareable subject I own + its audience ─────

@Serializable
data class MySharedResponse(
    val portfolios: List<MySharedPortfolioDto> = emptyList(),
    val conglomerates: List<MySharedConglomerateDto> = emptyList(),
    val watchlists: List<MySharedWatchlistDto> = emptyList(),
)

@Serializable
data class MySharedPortfolioDto(
    val portfolioId: String,
    val name: String,
    /** `private` | `specific_friends` | `all_friends` | `public_link`. */
    val audience: String,
    /** Number of named friends — non-zero only for `specific_friends`. */
    val friendCount: Int = 0,
)

@Serializable
data class MySharedConglomerateDto(
    val conglomerateId: String,
    val name: String,
    val positionCount: Int = 0,
    val audience: String,
    val friendCount: Int = 0,
)

@Serializable
data class MySharedWatchlistDto(
    val watchlistId: String,
    val name: String,
    val itemCount: Int = 0,
    val audience: String,
    val friendCount: Int = 0,
)

// ── Unified audience model (V3-P5): GET|PUT /social/audience/:kind/:subjectId ─

/** Live public-link status for one audience (hash-only storage → shown once at mint). */
@Serializable
data class ShareLinkStateDto(
    val active: Boolean = false,
    val createdAt: String? = null,
)

/** `GET /social/audience/:kind/:subjectId` — the owner's current audience for one item. */
@Serializable
data class AudienceStateDto(
    /** `portfolio` | `conglomerate` | `watchlist`. */
    val kind: String,
    val subjectId: String,
    /** `private` | `specific_friends` | `all_friends` | `public_link`. */
    val audience: String,
    /** Populated only for `specific_friends`. */
    val friendIds: List<String> = emptyList(),
    val link: ShareLinkStateDto = ShareLinkStateDto(),
)

/**
 * `PUT /social/audience/:kind/:subjectId` body. `friendIds` honoured only for
 * `specific_friends`; `acknowledgePublic` MUST be `true` to select `public_link`
 * (the §16 explicit-acknowledgment gate, enforced server-side too).
 */
@Serializable
data class SetAudienceRequest(
    val audience: String,
    val friendIds: List<String>? = null,
    val acknowledgePublic: Boolean? = null,
)

/** The raw public link, returned EXACTLY ONCE when a `public_link` audience is minted. */
@Serializable
data class ShareLinkSecretDto(
    val token: String,
    /** Relative resolution path (`/api/v1/social/links/:token`); compose the shareable URL. */
    val url: String,
)

/** `PUT /social/audience/:kind/:subjectId` response — new state + the link secret once on mint. */
@Serializable
data class AudienceMutationResponse(
    val state: AudienceStateDto,
    val link: ShareLinkSecretDto? = null,
)

// ── Per-shared-item activity alerts (V3-P6): PUT /social/shared/activity/... ──

@Serializable
data class SetActivityAlertRequest(
    val enabled: Boolean,
)

@Serializable
data class ActivityAlertStateDto(
    val kind: String,
    val subjectId: String,
    val enabled: Boolean,
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

// ── GET /social/shared/watchlists/{watchlistId} ──────────────────────────────

@Serializable
data class SharedWatchlistDetailResponse(
    val watchlistId: String? = null,
    val name: String? = null,
    val owner: SocialUserDto,
    val items: List<SharedWatchlistItemDto> = emptyList(),
)

@Serializable
data class SharedWatchlistItemDto(
    val id: String,
    val watchlistId: String? = null,
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

// ── Named watchlists (V3-P5): /workboard/watchlists ──────────────────────────

@Serializable
data class WatchlistSummaryDto(
    val id: String,
    val name: String,
    val isDefault: Boolean,
    val itemCount: Int = 0,
    /** This list's share setting via the unified audience model. */
    val audience: String = "private",
)

@Serializable
data class WatchlistListResponse(
    val watchlists: List<WatchlistSummaryDto> = emptyList(),
)

/** `POST /workboard/watchlists` body — create a named list. */
@Serializable
data class CreateWatchlistRequest(
    val name: String,
)

/** `PATCH /workboard/watchlists/:watchlistId` body — rename (never the default). */
@Serializable
data class RenameWatchlistRequest(
    val name: String,
)

// ── Legacy watchlist sharing (superseded by the unified audience model) ──────
// Kept for the still-live `GET|PATCH /workboard/sharing` binary endpoint; the app
// now drives watchlist sharing through /social/audience/watchlist/:id instead.

@Serializable
data class UpdateWatchlistSharingRequest(
    /** `private` | `friends`. */
    val visibility: String,
)

@Serializable
data class WatchlistSharingResponse(
    /** `private` | `friends`. */
    val visibility: String,
)

/** PATCH /conglomerates/{id} — rename/describe (audience now via /social/audience). */
@Serializable
data class UpdateConglomerateRequest(
    val name: String? = null,
    val description: String? = null,
    /** Legacy `private` | `friends`; audience now set via the unified endpoint. */
    val visibility: String? = null,
)
