package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.MirrorActivityEntryDto
import at.bettertrack.app.data.api.dto.MirrorChainSummaryDto
import at.bettertrack.app.data.api.dto.MirrorInviteDto
import at.bettertrack.app.data.api.dto.MirrorMemberDto
import at.bettertrack.app.data.api.dto.MirrorSyncStateDto
import kotlinx.serialization.json.Json

/**
 * Mirrorchain **participation** (V5, `mirrorchain:*`): the chains I'm in, who
 * else is in them, what has happened, and the three things I can actually do —
 * accept an invite, decline one, leave.
 *
 * Chain *administration* (create, rename, invite, revoke, roles, transfer, kick,
 * dissolve) is session-only by a deliberate platform allowlist and answers a
 * bearer with `403 API_KEY_FORBIDDEN`. This repository therefore has no methods
 * for it at all — the absence is the design. A disabled button would promise a
 * permission the mobile client can never hold; the app just doesn't draw one,
 * and points at the web where the capability really lives.
 */

/** Roles as the platform names them. Unknown → [Member] (the least authority). */
enum class MirrorRole(val wire: String) {
    Owner("owner"),
    Manager("manager"),
    Member("member"),
    ;

    companion object {
        fun fromWire(w: String?): MirrorRole = entries.firstOrNull { it.wire == w } ?: Member
    }
}

/** `active` | `dissolved`. A dissolved chain is read-only history. */
enum class MirrorChainStatus(val wire: String) {
    Active("active"),
    Dissolved("dissolved"),
    ;

    companion object {
        fun fromWire(w: String?): MirrorChainStatus = entries.firstOrNull { it.wire == w } ?: Active
    }
}

/**
 * How far a member has replayed the chain. [percent] arrives 0..100 already —
 * the app never divides `appliedSeq/lastSeq` itself, because a fresh chain has
 * `lastSeq == 0` and that division is the classic NaN.
 */
data class MirrorSync(
    val appliedSeq: Int,
    val lastSeq: Int,
    val percent: Int,
    val synced: Boolean,
)

data class MirrorChain(
    val chainId: String,
    val name: String,
    val status: MirrorChainStatus,
    /** My local copy of the group portfolio; null once that portfolio is gone. */
    val portfolioId: String?,
    /** MY role. */
    val role: MirrorRole,
    val memberCount: Int,
    /** MY catch-up state. */
    val sync: MirrorSync,
    val createdAt: String,
)

data class MirrorMember(
    /** Null when the account was deleted — the membership row survives. */
    val userId: String?,
    val username: String,
    val profileIcon: String?,
    val role: MirrorRole,
    val joinedAt: String,
    val isSelf: Boolean,
    val sync: MirrorSync,
)

data class MirrorRoster(
    val chainId: String,
    val name: String,
    val status: MirrorChainStatus,
    /** MY role in this chain. */
    val myRole: MirrorRole,
    val memberCap: Int,
    val members: List<MirrorMember>,
)

data class MirrorActivityEntry(
    val seq: Int,
    val kind: String,
    val actorUsername: String,
    /** The server's own rendered sentence — shown verbatim, never reconstructed. */
    val summary: String,
    val createdAt: String,
)

data class MirrorActivityPage(
    val entries: List<MirrorActivityEntry>,
    /** Pass back as `before` for the next older page; null at the log's start. */
    val nextCursor: Int?,
)

data class MirrorInvite(
    val id: String,
    val chainId: String,
    val chainName: String,
    val fromUsername: String?,
    val toUsername: String,
    val createdAt: String,
)

data class MirrorInvites(
    val incoming: List<MirrorInvite>,
    val outgoing: List<MirrorInvite>,
) {
    val isEmpty: Boolean get() = incoming.isEmpty() && outgoing.isEmpty()
}

class MirrorchainRepository(
    private val api: BtApi,
    private val json: Json,
) {

    suspend fun chains(): BtResult<List<MirrorChain>> =
        when (val r = apiCall(json) { api.mirrorChains() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.chains.map { it.toDomain() })
            is BtResult.Err -> r
        }

    suspend fun members(chainId: String): BtResult<MirrorRoster> =
        when (val r = apiCall(json) { api.mirrorChainMembers(chainId) }) {
            is BtResult.Ok -> BtResult.Ok(
                MirrorRoster(
                    chainId = r.value.chainId,
                    name = r.value.name,
                    status = MirrorChainStatus.fromWire(r.value.status),
                    myRole = MirrorRole.fromWire(r.value.role),
                    memberCap = r.value.memberCap,
                    members = r.value.members.map { it.toDomain() },
                ),
            )

            is BtResult.Err -> r
        }

    /** Newest-first page; pass the previous page's cursor as [before]. */
    suspend fun activity(
        chainId: String,
        before: Int? = null,
        limit: Int = ACTIVITY_PAGE,
    ): BtResult<MirrorActivityPage> =
        when (val r = apiCall(json) { api.mirrorChainActivity(chainId, before, limit) }) {
            is BtResult.Ok -> BtResult.Ok(
                MirrorActivityPage(
                    entries = r.value.entries.map { it.toDomain() },
                    nextCursor = r.value.nextCursor,
                ),
            )

            is BtResult.Err -> r
        }

    suspend fun invites(): BtResult<MirrorInvites> =
        when (val r = apiCall(json) { api.mirrorInvites() }) {
            is BtResult.Ok -> BtResult.Ok(
                MirrorInvites(
                    incoming = r.value.incoming.map { it.toDomain() },
                    outgoing = r.value.outgoing.map { it.toDomain() },
                ),
            )

            is BtResult.Err -> r
        }

    /**
     * Accept — the server materializes my own copy of the group portfolio and
     * returns its id, so the caller can refresh the portfolio list and even
     * navigate straight into it.
     */
    suspend fun accept(inviteId: String): BtResult<String> =
        when (val r = apiCall(json) { api.acceptMirrorInvite(inviteId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.portfolioId)
            is BtResult.Err -> r
        }

    suspend fun decline(inviteId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.declineMirrorInvite(inviteId) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    /**
     * Leave a chain.
     *
     * There is no last-admin refusal to handle: ownership succeeds to the oldest
     * manager, or the chain dissolves for everyone. The leaver keeps their copy
     * of the portfolio as an un-synced fork — which is exactly what the
     * confirmation dialog has to say, because "leave" reads like "delete" to
     * anyone who hasn't been told otherwise.
     */
    suspend fun leave(chainId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.leaveMirrorChain(chainId) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    private fun MirrorChainSummaryDto.toDomain() = MirrorChain(
        chainId = chainId,
        name = name,
        status = MirrorChainStatus.fromWire(status),
        portfolioId = portfolioId,
        role = MirrorRole.fromWire(role),
        memberCount = memberCount,
        sync = sync.toDomain(),
        createdAt = createdAt,
    )

    private fun MirrorMemberDto.toDomain() = MirrorMember(
        userId = userId,
        username = username,
        profileIcon = profileIcon,
        role = MirrorRole.fromWire(role),
        joinedAt = joinedAt,
        isSelf = isSelf,
        sync = sync.toDomain(),
    )

    private fun MirrorSyncStateDto.toDomain() =
        MirrorSync(appliedSeq, lastSeq, percent.coerceIn(0, 100), synced)

    private fun MirrorActivityEntryDto.toDomain() =
        MirrorActivityEntry(seq, kind, actorUsername, summary, createdAt)

    private fun MirrorInviteDto.toDomain() =
        MirrorInvite(id, chainId, chainName, fromUsername, toUsername, createdAt)

    companion object {
        /** Server allows 1..100; 30 is its own default and a sensible page. */
        const val ACTIVITY_PAGE = 30

        /** 404 — unknown chain, or a membership that is no longer active. */
        const val CODE_CHAIN_NOT_FOUND = "MIRROR_CHAIN_NOT_FOUND"

        /** 404 — invite missing, already answered, expired, or not mine. */
        const val CODE_INVITE_NOT_FOUND = "MIRROR_INVITE_NOT_FOUND"

        /** 409 — the chain already holds its 16 members. */
        const val CODE_MEMBER_CAP = "MIRROR_MEMBER_CAP_REACHED"

        /** 400 — an invite from someone who is no longer a friend. */
        const val CODE_NOT_FRIENDS = "MIRROR_NOT_FRIENDS"

        /** 503 — lock contention; transient, worth an immediate retry. */
        const val CODE_BUSY = "MIRROR_BUSY"
    }
}
