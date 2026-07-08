package at.bettertrack.app.data.repo

import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CreateFriendRequestRequest
import at.bettertrack.app.data.api.dto.SharedPortfolioDetailResponse
import at.bettertrack.app.data.api.dto.SharedWatchlistDetailResponse
import at.bettertrack.app.data.api.dto.SharedConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.UpdateConglomerateRequest
import at.bettertrack.app.data.api.dto.UpdatePortfolioRequest
import at.bettertrack.app.data.api.dto.UpdateWatchlistSharingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Friends & sharing (Steps 14, §6.8/§6.9). The clean seam between the app's full
 * social experience and the platform's current coverage.
 *
 * LIVE (wired for real — verified empirically):
 *  - reads: `GET /social/friends`, `/social/requests`, `/social/shared`,
 *    `/social/my-shared`, and the read-only detail views — all gate on the
 *    `social:read` scope the client HAS.
 *  - sharing-visibility mutations for the two audience tiers the platform models
 *    (`private` ↔ `friends`): `PATCH /portfolios/{id}`, `/workboard/sharing`,
 *    `/conglomerates/{id}` — these ride `portfolio:write` / `workboard:write`,
 *    both HELD, so "Private" and "All friends" genuinely persist server-side.
 *
 * STUB (behind [SocialFlags], debug-only sample data + optimistic flows; a clean
 * "coming soon" in release):
 *  - friend-graph WRITES (send/accept/decline/cancel request, unfriend) require a
 *    `social:write` scope the mobile client is NOT yet granted;
 *  - the richer audience tiers "Specific friends" and "Public link" are app-ahead
 *    of the platform (which has only `private`/`friends`, no per-friend ACL and no
 *    public-link token) — the friction ladder is built now; these two tiers apply
 *    to a local overlay in debug and are marked coming-soon in release.
 *
 * When the platform grants `social:write` and ships per-friend / public-link
 * sharing, the stub overlay is deleted and the real calls slot into the same
 * methods — the UI never changes. Boundary documented in `docs/TODO.md`.
 */

/** Feature flags for the not-yet-granted social write surface (§6.8/§6.9 gaps). */
object SocialFlags {
    /** Friend-graph writes: optimistic debug overlay until `social:write` lands. */
    val friendWrites: Boolean = BuildConfig.DEBUG

    /** "Specific friends" + "Public link" audience tiers (app-ahead of the platform). */
    val advancedSharing: Boolean = BuildConfig.DEBUG
}

/** The §6.9 audience ladder. The platform models only [Private] and [AllFriends]. */
enum class ShareAudience {
    Private,
    SpecificFriends,
    AllFriends,
    PublicLink,
    ;

    /** The two tiers that persist to the platform today. */
    val isLivePersistable: Boolean get() = this == Private || this == AllFriends

    /** Maps to the platform `visibility` enum for the persistable tiers. */
    val visibility: String? get() = when (this) {
        Private -> "private"
        AllFriends -> "friends"
        else -> null
    }

    companion object {
        fun fromVisibility(v: String?): ShareAudience =
            if (v == "friends") AllFriends else Private
    }
}

/** What kind of thing an audience applies to (for the picker + my-shared audit). */
enum class ShareableKind { Portfolio, Watchlist, Conglomerate }

// ── Domain models (clean; decoupled from the wire DTOs) ─────────────────────

data class Friend(
    val userId: String,
    val username: String,
    val since: String,
    /** True for a debug sample friend (badged); false for a real live friend. */
    val isSample: Boolean = false,
)

data class FriendRequest(
    val id: String,
    val username: String,
    val userId: String,
    /** `pending` | `accepted` | `declined` | `cancelled`. */
    val status: String,
    val createdAt: String,
    val isSample: Boolean = false,
)

data class FriendRequests(
    val incoming: List<FriendRequest>,
    val outgoing: List<FriendRequest>,
)

data class SharedWithMe(
    val portfolios: List<SharedPortfolioSummary>,
    val conglomerates: List<SharedConglomerateSummary>,
    val watchlists: List<SharedWatchlistSummary>,
) {
    val isEmpty: Boolean get() = portfolios.isEmpty() && conglomerates.isEmpty() && watchlists.isEmpty()
}

data class SharedPortfolioSummary(
    val portfolioId: String,
    val name: String,
    val ownerName: String,
    val totalValueEur: Double,
)

data class SharedConglomerateSummary(
    val conglomerateId: String,
    val name: String,
    val ownerName: String,
    val status: String,
    val positionCount: Int,
)

data class SharedWatchlistSummary(
    val ownerId: String,
    val ownerName: String,
    val itemCount: Int,
)

/** A single audited item I'm sharing (or could share). */
data class MySharedItem(
    val id: String,
    val kind: ShareableKind,
    val name: String,
    val audience: ShareAudience,
    val detail: String,
)

data class MyShared(
    val items: List<MySharedItem>,
) {
    val sharedCount: Int get() = items.count { it.audience != ShareAudience.Private }
}

interface SocialRepository {
    // Reads (live).
    suspend fun friends(): BtResult<List<Friend>>
    suspend fun requests(): BtResult<FriendRequests>
    suspend fun sharedWithMe(): BtResult<SharedWithMe>
    suspend fun myShared(): BtResult<MyShared>
    suspend fun sharedPortfolio(portfolioId: String): BtResult<SharedPortfolioDetailResponse>
    suspend fun sharedWatchlist(userId: String): BtResult<SharedWatchlistDetailResponse>
    suspend fun sharedConglomerate(conglomerateId: String): BtResult<SharedConglomerateDetailResponse>

    // Friend-graph writes (stub until social:write).
    suspend fun sendRequest(identifier: String): BtResult<Unit>
    suspend fun acceptRequest(id: String): BtResult<Unit>
    suspend fun declineRequest(id: String): BtResult<Unit>
    suspend fun cancelRequest(id: String): BtResult<Unit>
    suspend fun unfriend(userId: String): BtResult<Unit>

    // Sharing-visibility mutations.
    suspend fun watchlistAudience(): BtResult<ShareAudience>
    suspend fun setAudience(kind: ShareableKind, itemId: String, audience: ShareAudience): BtResult<Unit>

    /** Public share link for the [ShareAudience.PublicLink] tier (app-ahead → stub token). */
    fun publicLink(kind: ShareableKind, itemId: String): String
}

class DefaultSocialRepository(
    private val api: BtApi,
    private val json: Json,
    private val webOrigin: String,
) : SocialRepository {

    // ── Debug overlay (drives the demo without social:write) ─────────────────

    private data class Overlay(
        val friends: List<Friend>,
        val incoming: List<FriendRequest>,
        val outgoing: List<FriendRequest>,
        /** itemId → chosen app-ahead audience (SpecificFriends / PublicLink). */
        val advancedAudience: Map<String, ShareAudience> = emptyMap(),
    )

    private val overlay = MutableStateFlow(seedOverlay())

    private fun seedOverlay(): Overlay = if (SocialFlags.friendWrites) {
        Overlay(
            friends = listOf(
                Friend(sampleId(), "anna_m", "2026-05-02T10:00:00Z", isSample = true),
                Friend(sampleId(), "lukas.k", "2026-06-14T18:30:00Z", isSample = true),
            ),
            incoming = listOf(
                FriendRequest(sampleId(), "marie_w", sampleId(), "pending", "2026-07-07T09:12:00Z", isSample = true),
            ),
            outgoing = listOf(
                FriendRequest(sampleId(), "thomas99", sampleId(), "pending", "2026-07-06T20:41:00Z", isSample = true),
            ),
        )
    } else {
        Overlay(emptyList(), emptyList(), emptyList())
    }

    // ── Reads (live + debug overlay merge) ───────────────────────────────────

    override suspend fun friends(): BtResult<List<Friend>> {
        val live = apiCall(json) { api.friends() }
        return when (live) {
            is BtResult.Ok -> {
                val real = live.value.friends.map {
                    Friend(it.user.id, it.user.username, it.createdAt, isSample = false)
                }
                BtResult.Ok(real + overlay.value.friends)
            }
            is BtResult.Err ->
                // Reads shouldn't be scope-blocked (social:read is held); if the
                // network is down we still surface the demo overlay in debug.
                if (SocialFlags.friendWrites && live.error.isNetwork) BtResult.Ok(overlay.value.friends)
                else live
        }
    }

    override suspend fun requests(): BtResult<FriendRequests> {
        val live = apiCall(json) { api.friendRequests() }
        return when (live) {
            is BtResult.Ok -> {
                val incoming = live.value.incoming.map { it.toDomain() } + overlay.value.incoming
                val outgoing = live.value.outgoing.map { it.toDomain() } + overlay.value.outgoing
                BtResult.Ok(FriendRequests(incoming, outgoing))
            }
            is BtResult.Err ->
                if (SocialFlags.friendWrites && live.error.isNetwork) {
                    BtResult.Ok(FriendRequests(overlay.value.incoming, overlay.value.outgoing))
                } else {
                    live
                }
        }
    }

    override suspend fun sharedWithMe(): BtResult<SharedWithMe> =
        when (val r = apiCall(json) { api.sharedWithMe() }) {
            is BtResult.Ok -> BtResult.Ok(
                SharedWithMe(
                    portfolios = r.value.portfolios.map {
                        SharedPortfolioSummary(it.portfolioId, it.name, it.owner.username, it.totalValueEur)
                    },
                    conglomerates = r.value.conglomerates.map {
                        SharedConglomerateSummary(it.conglomerateId, it.name, it.owner.username, it.status, it.positionCount)
                    },
                    watchlists = r.value.watchlists.map {
                        SharedWatchlistSummary(it.owner.id, it.owner.username, it.itemCount)
                    },
                ),
            )
            is BtResult.Err -> r
        }

    override suspend fun myShared(): BtResult<MyShared> =
        when (val r = apiCall(json) { api.mySharedItems() }) {
            is BtResult.Ok -> {
                val items = buildList {
                    r.value.portfolios.filter { it.archivedAt == null }.forEach { p ->
                        add(
                            MySharedItem(
                                id = p.id,
                                kind = ShareableKind.Portfolio,
                                name = p.name,
                                audience = resolveAudience(p.id, p.visibility),
                                detail = if (p.isDefault) "Default portfolio" else "Portfolio",
                            ),
                        )
                    }
                    r.value.conglomerates.forEach { c ->
                        add(
                            MySharedItem(
                                id = c.id,
                                kind = ShareableKind.Conglomerate,
                                name = c.name,
                                audience = resolveAudience(c.id, c.visibility),
                                detail = if (c.positionCount == 1) "1 position" else "${c.positionCount} positions",
                            ),
                        )
                    }
                    add(
                        MySharedItem(
                            id = WATCHLIST_ITEM_ID,
                            kind = ShareableKind.Watchlist,
                            name = "General watchlist",
                            audience = resolveAudience(WATCHLIST_ITEM_ID, r.value.watchlist.visibility),
                            detail = if (r.value.watchlist.itemCount == 1) "1 asset" else "${r.value.watchlist.itemCount} assets",
                        ),
                    )
                }
                BtResult.Ok(MyShared(items))
            }
            is BtResult.Err -> r
        }

    override suspend fun sharedPortfolio(portfolioId: String): BtResult<SharedPortfolioDetailResponse> =
        apiCall(json) { api.sharedPortfolioDetail(portfolioId) }

    override suspend fun sharedWatchlist(userId: String): BtResult<SharedWatchlistDetailResponse> =
        apiCall(json) { api.sharedWatchlistDetail(userId) }

    override suspend fun sharedConglomerate(conglomerateId: String): BtResult<SharedConglomerateDetailResponse> =
        apiCall(json) { api.sharedConglomerateDetail(conglomerateId) }

    // ── Friend-graph writes (stub) ───────────────────────────────────────────

    override suspend fun sendRequest(identifier: String): BtResult<Unit> {
        if (!SocialFlags.friendWrites) return comingSoon()
        val handle = identifier.substringBefore('@').trim().ifBlank { identifier.trim() }
        overlay.value = overlay.value.copy(
            outgoing = overlay.value.outgoing + FriendRequest(
                id = sampleId(),
                username = handle,
                userId = sampleId(),
                status = "pending",
                createdAt = nowIso(),
                isSample = true,
            ),
        )
        return BtResult.Ok(Unit)
    }

    override suspend fun acceptRequest(id: String): BtResult<Unit> {
        if (!SocialFlags.friendWrites) return comingSoon()
        val req = overlay.value.incoming.firstOrNull { it.id == id }
        overlay.value = overlay.value.copy(
            incoming = overlay.value.incoming.filterNot { it.id == id },
            friends = if (req != null) {
                overlay.value.friends + Friend(req.userId, req.username, nowIso(), isSample = true)
            } else {
                overlay.value.friends
            },
        )
        return BtResult.Ok(Unit)
    }

    override suspend fun declineRequest(id: String): BtResult<Unit> {
        if (!SocialFlags.friendWrites) return comingSoon()
        overlay.value = overlay.value.copy(incoming = overlay.value.incoming.filterNot { it.id == id })
        return BtResult.Ok(Unit)
    }

    override suspend fun cancelRequest(id: String): BtResult<Unit> {
        if (!SocialFlags.friendWrites) return comingSoon()
        overlay.value = overlay.value.copy(outgoing = overlay.value.outgoing.filterNot { it.id == id })
        return BtResult.Ok(Unit)
    }

    override suspend fun unfriend(userId: String): BtResult<Unit> {
        if (!SocialFlags.friendWrites) return comingSoon()
        overlay.value = overlay.value.copy(friends = overlay.value.friends.filterNot { it.userId == userId })
        return BtResult.Ok(Unit)
    }

    // ── Sharing-visibility mutations ─────────────────────────────────────────

    override suspend fun watchlistAudience(): BtResult<ShareAudience> =
        when (val r = apiCall(json) { api.watchlistSharing() }) {
            is BtResult.Ok -> BtResult.Ok(resolveAudience(WATCHLIST_ITEM_ID, r.value.visibility))
            is BtResult.Err -> r
        }

    override suspend fun setAudience(
        kind: ShareableKind,
        itemId: String,
        audience: ShareAudience,
    ): BtResult<Unit> {
        // App-ahead tiers can't persist server-side (no per-friend ACL / link token).
        if (!audience.isLivePersistable) {
            if (!SocialFlags.advancedSharing) return comingSoon()
            // Debug: remember the choice locally so the audit reflects it.
            overlay.value = overlay.value.copy(
                advancedAudience = overlay.value.advancedAudience + (itemId to audience),
            )
            return BtResult.Ok(Unit)
        }
        // Live persist for Private / All friends. Clear any prior app-ahead choice.
        overlay.value = overlay.value.copy(
            advancedAudience = overlay.value.advancedAudience - itemId,
        )
        val visibility = audience.visibility!!
        return when (kind) {
            ShareableKind.Portfolio -> apiCall(json) {
                api.updatePortfolio(itemId, UpdatePortfolioRequest(visibility = visibility))
            }.map()
            ShareableKind.Conglomerate -> apiCall(json) {
                api.updateConglomerate(itemId, UpdateConglomerateRequest(visibility = visibility))
            }.map()
            ShareableKind.Watchlist -> apiCall(json) {
                api.updateWatchlistSharing(UpdateWatchlistSharingRequest(visibility = visibility))
            }.map()
        }
    }

    override fun publicLink(kind: ShareableKind, itemId: String): String {
        // App-ahead: the platform has no public-link token yet, so this is a
        // representative URL for the share-sheet demo. Shape matches the web's
        // planned `/{kind}/shared/{token}` route so the real token drops in later.
        val seg = when (kind) {
            ShareableKind.Portfolio -> "portfolio"
            ShareableKind.Watchlist -> "watchlist"
            ShareableKind.Conglomerate -> "conglomerate"
        }
        val token = "demo-" + itemId.take(8)
        return "${webOrigin.trimEnd('/')}/$seg/shared/$token"
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveAudience(itemId: String, visibility: String): ShareAudience =
        overlay.value.advancedAudience[itemId] ?: ShareAudience.fromVisibility(visibility)

    private fun at.bettertrack.app.data.api.dto.FriendRequestDto.toDomain() = FriendRequest(
        id = id,
        username = user.username,
        userId = user.id,
        status = status,
        createdAt = createdAt,
        isSample = false,
    )

    private fun <T> BtResult<T>.map(): BtResult<Unit> = when (this) {
        is BtResult.Ok -> BtResult.Ok(Unit)
        is BtResult.Err -> this
    }

    private fun comingSoon(): BtResult<Nothing> = BtResult.Err(
        BtApiError(0, BtApiError.Codes.UNKNOWN, "This is coming soon — the platform is still adding it."),
    )

    private companion object {
        const val WATCHLIST_ITEM_ID = "workboard"
        fun sampleId(): String = UUID.randomUUID().toString()
        fun nowIso(): String = java.time.Instant.now().toString()
    }
}
