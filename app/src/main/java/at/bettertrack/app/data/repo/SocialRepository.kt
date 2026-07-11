package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CreateFriendRequestRequest
import at.bettertrack.app.data.api.dto.SetActivityAlertRequest
import at.bettertrack.app.data.api.dto.SetAudienceRequest
import at.bettertrack.app.data.api.dto.SharedConglomerateDetailResponse
import at.bettertrack.app.data.api.dto.SharedPortfolioDetailResponse
import at.bettertrack.app.data.api.dto.SharedWatchlistDetailResponse
import at.bettertrack.app.data.api.parseApiError
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/**
 * Friends & sharing (Steps 14 + Social v2, §6.9 / V3 sharing). The whole social
 * system is now LIVE — the platform shipped friend writes (#341, `social:write`),
 * the unified 4-rung audience model + public links (#332), and the per-shared-item
 * activity-alert preference (V3-P6). This repository is a thin verbatim adapter:
 *
 *  - READS (`social:read`): friends, requests, shared-with-me, my-shared, the
 *    read-only detail views.
 *  - FRIEND WRITES (`social:write`): send/accept/decline/cancel request, unfriend.
 *  - AUDIENCE (`social:write`): `GET|PUT /social/audience/:kind/:subjectId` — the
 *    full ladder private / specific_friends / all_friends / public_link, with the
 *    hash-only public-link token surfaced ONCE at mint.
 *  - ACTIVITY PREFS (`social:write`): `PUT /social/shared/activity/:kind/:subjectId`.
 *
 * Enforcement note (§14): non-members get **404, never 403** on shared reads — the
 * detail screens treat 404 as "not shared with you".
 *
 * The delivery of friend-activity alerts is still platform-gated (Notifications-v2
 * #368) — the preference persists here; the bell lights up when #368 ships.
 */

/** The §16 audience ladder — a single-select rung of increasing exposure. */
enum class ShareAudience(val wire: String) {
    Private("private"),
    SpecificFriends("specific_friends"),
    AllFriends("all_friends"),
    PublicLink("public_link"),
    ;

    companion object {
        fun fromWire(w: String?): ShareAudience = entries.firstOrNull { it.wire == w } ?: Private
    }
}

/** What kind of thing an audience applies to (routes the unified endpoints). */
enum class ShareableKind(val wire: String) {
    Portfolio("portfolio"),
    Watchlist("watchlist"),
    Conglomerate("conglomerate"),
    ;

    companion object {
        fun fromWire(w: String?): ShareableKind = entries.firstOrNull { it.wire == w } ?: Portfolio
    }
}

// ── Domain models (clean; decoupled from the wire DTOs) ─────────────────────

data class Friend(
    val userId: String,
    val username: String,
    val since: String,
)

data class FriendRequest(
    val id: String,
    val username: String,
    val userId: String,
    /** `pending` | `accepted` | `declined` | `cancelled`. */
    val status: String,
    val createdAt: String,
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
    val count: Int get() = portfolios.size + conglomerates.size + watchlists.size
}

data class SharedPortfolioSummary(
    val portfolioId: String,
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val totalValueEur: Double,
    val activityAlertsEnabled: Boolean,
)

data class SharedConglomerateSummary(
    val conglomerateId: String,
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val status: String,
    val positionCount: Int,
    val activityAlertsEnabled: Boolean,
)

data class SharedWatchlistSummary(
    val watchlistId: String,
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val itemCount: Int,
    val activityAlertsEnabled: Boolean,
)

/** A single subject I own that I can share — with its current audience. */
data class MySharedItem(
    val id: String,
    val kind: ShareableKind,
    val name: String,
    val audience: ShareAudience,
    /** Named friends for [ShareAudience.SpecificFriends] (0 otherwise). */
    val friendCount: Int,
    /** Subtitle count: unused for portfolios, position count for conglomerates, item count for watchlists. */
    val count: Int,
)

data class MyShared(
    val items: List<MySharedItem>,
) {
    val sharedCount: Int get() = items.count { it.audience != ShareAudience.Private }
}

/** The owner's current audience for one item — seeds the picker with reality. */
data class AudienceState(
    val kind: ShareableKind,
    val subjectId: String,
    val audience: ShareAudience,
    val friendIds: Set<String>,
    val linkActive: Boolean,
    val linkCreatedAt: String?,
)

/** Result of a [SocialRepository.setAudience] — the new audience + a freshly-minted link once. */
data class ShareOutcome(
    val audience: ShareAudience,
    /** Absolute shareable web URL — present ONLY the first time a public link is minted. */
    val publicUrl: String?,
)

// ── Per-person grouping (Social v2 point 4 — "shared with me" by person) ─────

/** All items one friend shares with me, grouped under them (read-only). */
data class PersonShares(
    val ownerId: String,
    val ownerName: String,
    val portfolios: List<SharedPortfolioSummary>,
    val conglomerates: List<SharedConglomerateSummary>,
    val watchlists: List<SharedWatchlistSummary>,
) {
    val count: Int get() = portfolios.size + conglomerates.size + watchlists.size
}

/**
 * Group everything shared with me by the friend who shares it (pure — unit-tested).
 * People are ordered by most-shared first, then name; each person's items keep
 * portfolio → conglomerate → watchlist order.
 */
fun SharedWithMe.groupByPerson(): List<PersonShares> {
    val ids = LinkedHashSet<String>()
    portfolios.forEach { ids.add(it.ownerId) }
    conglomerates.forEach { ids.add(it.ownerId) }
    watchlists.forEach { ids.add(it.ownerId) }
    return ids.map { ownerId ->
        val ps = portfolios.filter { it.ownerId == ownerId }
        val cs = conglomerates.filter { it.ownerId == ownerId }
        val ws = watchlists.filter { it.ownerId == ownerId }
        val name = ps.firstOrNull()?.ownerName
            ?: cs.firstOrNull()?.ownerName
            ?: ws.firstOrNull()?.ownerName
            ?: ""
        PersonShares(ownerId, name, ps, cs, ws)
    }.sortedWith(compareByDescending<PersonShares> { it.count }.thenBy { it.ownerName.lowercase() })
}

interface SocialRepository {
    // Reads.
    suspend fun friends(): BtResult<List<Friend>>
    suspend fun requests(): BtResult<FriendRequests>
    suspend fun sharedWithMe(): BtResult<SharedWithMe>
    suspend fun myShared(): BtResult<MyShared>
    suspend fun sharedPortfolio(portfolioId: String): BtResult<SharedPortfolioDetailResponse>
    suspend fun sharedWatchlist(watchlistId: String): BtResult<SharedWatchlistDetailResponse>
    suspend fun sharedConglomerate(conglomerateId: String): BtResult<SharedConglomerateDetailResponse>

    // Friend-graph writes (live under social:write).
    suspend fun sendRequest(identifier: String): BtResult<Unit>
    suspend fun acceptRequest(id: String): BtResult<Unit>
    suspend fun declineRequest(id: String): BtResult<Unit>
    suspend fun cancelRequest(id: String): BtResult<Unit>
    suspend fun unfriend(userId: String): BtResult<Unit>

    // Unified audience (live).
    suspend fun getAudience(kind: ShareableKind, subjectId: String): BtResult<AudienceState>
    suspend fun setAudience(
        kind: ShareableKind,
        subjectId: String,
        audience: ShareAudience,
        friendIds: Set<String>,
        acknowledgePublic: Boolean,
    ): BtResult<ShareOutcome>

    // Per-shared-item activity-alert preference (live; delivery gated on #368).
    suspend fun setActivityAlert(kind: ShareableKind, subjectId: String, enabled: Boolean): BtResult<Unit>
}

class DefaultSocialRepository(
    private val api: BtApi,
    private val json: Json,
    private val webOrigin: String,
) : SocialRepository {

    // ── Reads ────────────────────────────────────────────────────────────────

    override suspend fun friends(): BtResult<List<Friend>> =
        when (val r = apiCall(json) { api.friends() }) {
            is BtResult.Ok -> BtResult.Ok(
                r.value.friends.map { Friend(it.user.id, it.user.username, it.createdAt) },
            )
            is BtResult.Err -> r
        }

    override suspend fun requests(): BtResult<FriendRequests> =
        when (val r = apiCall(json) { api.friendRequests() }) {
            is BtResult.Ok -> BtResult.Ok(
                FriendRequests(
                    incoming = r.value.incoming.map { FriendRequest(it.id, it.user.username, it.user.id, it.status, it.createdAt) },
                    outgoing = r.value.outgoing.map { FriendRequest(it.id, it.user.username, it.user.id, it.status, it.createdAt) },
                ),
            )
            is BtResult.Err -> r
        }

    override suspend fun sharedWithMe(): BtResult<SharedWithMe> =
        when (val r = apiCall(json) { api.sharedWithMe() }) {
            is BtResult.Ok -> BtResult.Ok(
                SharedWithMe(
                    portfolios = r.value.portfolios.map {
                        SharedPortfolioSummary(it.portfolioId, it.name, it.owner.id, it.owner.username, it.totalValueEur, it.activityAlertsEnabled)
                    },
                    conglomerates = r.value.conglomerates.map {
                        SharedConglomerateSummary(it.conglomerateId, it.name, it.owner.id, it.owner.username, it.status, it.positionCount, it.activityAlertsEnabled)
                    },
                    watchlists = r.value.watchlists.map {
                        SharedWatchlistSummary(it.watchlistId, it.name, it.owner.id, it.owner.username, it.itemCount, it.activityAlertsEnabled)
                    },
                ),
            )
            is BtResult.Err -> r
        }

    override suspend fun myShared(): BtResult<MyShared> =
        when (val r = apiCall(json) { api.mySharedItems() }) {
            is BtResult.Ok -> {
                val items = buildList {
                    r.value.portfolios.forEach { p ->
                        add(
                            MySharedItem(
                                id = p.portfolioId,
                                kind = ShareableKind.Portfolio,
                                name = p.name,
                                audience = ShareAudience.fromWire(p.audience),
                                friendCount = p.friendCount,
                                count = 0,
                            ),
                        )
                    }
                    r.value.conglomerates.forEach { c ->
                        add(
                            MySharedItem(
                                id = c.conglomerateId,
                                kind = ShareableKind.Conglomerate,
                                name = c.name,
                                audience = ShareAudience.fromWire(c.audience),
                                friendCount = c.friendCount,
                                count = c.positionCount,
                            ),
                        )
                    }
                    r.value.watchlists.forEach { w ->
                        add(
                            MySharedItem(
                                id = w.watchlistId,
                                kind = ShareableKind.Watchlist,
                                name = w.name,
                                audience = ShareAudience.fromWire(w.audience),
                                friendCount = w.friendCount,
                                count = w.itemCount,
                            ),
                        )
                    }
                }
                BtResult.Ok(MyShared(items))
            }
            is BtResult.Err -> r
        }

    override suspend fun sharedPortfolio(portfolioId: String): BtResult<SharedPortfolioDetailResponse> =
        apiCall(json) { api.sharedPortfolioDetail(portfolioId) }

    override suspend fun sharedWatchlist(watchlistId: String): BtResult<SharedWatchlistDetailResponse> =
        apiCall(json) { api.sharedWatchlistDetail(watchlistId) }

    override suspend fun sharedConglomerate(conglomerateId: String): BtResult<SharedConglomerateDetailResponse> =
        apiCall(json) { api.sharedConglomerateDetail(conglomerateId) }

    // ── Friend-graph writes (live) ───────────────────────────────────────────

    override suspend fun sendRequest(identifier: String): BtResult<Unit> =
        unitCall { api.createFriendRequest(CreateFriendRequestRequest(identifier.trim())) }

    override suspend fun acceptRequest(id: String): BtResult<Unit> = unitCall { api.acceptFriendRequest(id) }
    override suspend fun declineRequest(id: String): BtResult<Unit> = unitCall { api.declineFriendRequest(id) }
    override suspend fun cancelRequest(id: String): BtResult<Unit> = unitCall { api.cancelFriendRequest(id) }
    override suspend fun unfriend(userId: String): BtResult<Unit> = unitCall { api.removeFriend(userId) }

    // ── Unified audience (live) ──────────────────────────────────────────────

    override suspend fun getAudience(kind: ShareableKind, subjectId: String): BtResult<AudienceState> =
        when (val r = apiCall(json) { api.audience(kind.wire, subjectId) }) {
            is BtResult.Ok -> BtResult.Ok(
                AudienceState(
                    kind = ShareableKind.fromWire(r.value.kind),
                    subjectId = r.value.subjectId,
                    audience = ShareAudience.fromWire(r.value.audience),
                    friendIds = r.value.friendIds.toSet(),
                    linkActive = r.value.link.active,
                    linkCreatedAt = r.value.link.createdAt,
                ),
            )
            is BtResult.Err -> r
        }

    override suspend fun setAudience(
        kind: ShareableKind,
        subjectId: String,
        audience: ShareAudience,
        friendIds: Set<String>,
        acknowledgePublic: Boolean,
    ): BtResult<ShareOutcome> {
        val body = SetAudienceRequest(
            audience = audience.wire,
            friendIds = if (audience == ShareAudience.SpecificFriends) friendIds.toList() else null,
            acknowledgePublic = if (audience == ShareAudience.PublicLink) acknowledgePublic else null,
        )
        return when (val r = apiCall(json) { api.setAudience(kind.wire, subjectId, body) }) {
            is BtResult.Ok -> BtResult.Ok(
                ShareOutcome(
                    audience = ShareAudience.fromWire(r.value.state.audience),
                    publicUrl = r.value.link?.token?.let { publicShareUrl(it) },
                ),
            )
            is BtResult.Err -> r
        }
    }

    // ── Activity-alert preference (live; delivery gated on #368) ──────────────

    override suspend fun setActivityAlert(
        kind: ShareableKind,
        subjectId: String,
        enabled: Boolean,
    ): BtResult<Unit> =
        when (val r = apiCall(json) { api.setActivityAlert(kind.wire, subjectId, SetActivityAlertRequest(enabled)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    /** Compose the human, shareable web URL from a minted token (web route `/s/:token`). */
    fun publicShareUrl(token: String): String = "${webOrigin.trimEnd('/')}/s/$token"

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** For 200-with-empty-body writes (friend graph) that [apiCall] can't decode. */
    private suspend fun unitCall(call: suspend () -> Response<Unit>): BtResult<Unit> =
        try {
            val resp = call()
            if (resp.isSuccessful) {
                BtResult.Ok(Unit)
            } else {
                BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
            }
        } catch (_: IOException) {
            BtResult.Err(BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."))
        }
}
