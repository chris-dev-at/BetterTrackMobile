package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.MirrorActivityEntryDto
import at.bettertrack.app.data.api.dto.MirrorChainSummaryDto
import at.bettertrack.app.data.api.dto.MirrorConvertRequest
import at.bettertrack.app.data.api.dto.MirrorCreateInviteRequest
import at.bettertrack.app.data.api.dto.MirrorInviteDto
import at.bettertrack.app.data.api.dto.MirrorMemberDto
import at.bettertrack.app.data.api.dto.MirrorRenameChainRequest
import at.bettertrack.app.data.api.dto.MirrorSetRoleRequest
import at.bettertrack.app.data.api.dto.MirrorSyncStateDto
import at.bettertrack.app.data.api.dto.MirrorTransferRequest
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.serialization.json.Json

/**
 * Mirrorchain **participation** (V5, `mirrorchain:*`): the chains I'm in, who
 * else is in them, what has happened, and the three things I can actually do —
 * accept an invite, decline one, leave.
 *
 * Chain *administration* (rename, invite, revoke, roles, transfer, kick,
 * dissolve, convert) is session-only by a deliberate platform allowlist and
 * answers a bearer with `403 API_KEY_FORBIDDEN` — verified live against the dev
 * stack, and pinned platform-side by their own allowlist test.
 *
 * ## Why the methods exist anyway (changed 2026-08-06)
 *
 * They used to be absent, on the reasoning that a disabled button promises a
 * permission the client can never hold. That reasoning was right about the
 * BUTTON and wrong about the repository. The owner's ask is full management
 * parity, and the honest reading of "not yet" is not "pretend the feature does
 * not exist" — it is to build the surface, state plainly where the capability
 * currently lives, and make the switch-on a platform config change rather than
 * an app release.
 *
 * So: every admin call is modelled and reviewed against the contract, the UI is
 * drawn in a designed "manage on the web" state, and [adminCapability] probes
 * once per session to decide which of the two the user sees. The day the
 * platform allowlists these routes, the probe stops returning
 * [ChainAdminCapability.WebOnly] and the screens light up untouched.
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

/**
 * Whether this session may administer chains from the app.
 *
 * Three states rather than a Boolean, because "we asked and were refused" and
 * "we could not ask" have to look different on screen: the first is a settled
 * fact worth explaining once ("manage on the web"), the second is a transient
 * failure that should not harden into a permanent-sounding message.
 */
enum class ChainAdminCapability {
    /** The bearer is allowed through; the chain's own role rules still apply. */
    Allowed,

    /** Refused by the platform's bearer allowlist — administration lives on the web. */
    WebOnly,

    /** Offline or a server fault; ask again later rather than concluding anything. */
    Unknown,
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

    // ── Administration ───────────────────────────────────────────────────────

    /**
     * Whether this session's bearer may perform chain administration.
     *
     * Probed ONCE per process and cached, because the answer is a property of
     * the token's allowlist rather than of any chain: it cannot change while the
     * app runs, and re-asking on every screen would spend a round trip to be
     * told the same 403. A failure that is NOT a refusal (offline, 500) is left
     * uncached — that is a question we genuinely could not answer, and caching
     * "no" from a flaky network would strand the surface in its blocked state
     * for the rest of the session.
     *
     * The probe is [renameChain] against the chain the caller is looking at,
     * sending its CURRENT name: the platform checks the bearer allowlist before
     * it validates or applies anything, so a permitted call is a no-op rename to
     * the same string, and a refused one never reaches the service at all. That
     * is why the probe needs a real chain id and a real name rather than being a
     * standalone endpoint — there isn't one.
     */
    suspend fun adminCapability(chainId: String, currentName: String): ChainAdminCapability {
        cachedAdminCapability?.let { return it }
        val result = when (val r = apiCall(json) { api.renameMirrorChain(chainId, MirrorRenameChainRequest(currentName)) }) {
            is BtResult.Ok -> ChainAdminCapability.Allowed
            is BtResult.Err -> when {
                r.error.code == CODE_API_KEY_FORBIDDEN || r.error.isForbidden ->
                    ChainAdminCapability.WebOnly
                // A role refusal is not a CAPABILITY refusal: the bearer was
                // allowed through and the service said "you are only a member".
                // Caching that as WebOnly would tell an owner of another chain
                // that the app cannot administer chains at all.
                r.error.code == CODE_MIRROR_FORBIDDEN -> ChainAdminCapability.Allowed
                else -> ChainAdminCapability.Unknown
            }
        }
        if (result != ChainAdminCapability.Unknown) cachedAdminCapability = result
        return result
    }

    suspend fun renameChain(chainId: String, name: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.renameMirrorChain(chainId, MirrorRenameChainRequest(name)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    suspend fun invite(chainId: String, userId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.createMirrorInvite(chainId, MirrorCreateInviteRequest(userId)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    suspend fun revokeInvite(inviteId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.revokeMirrorInvite(inviteId) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    /** [role] is `manager` or `member`; `owner` moves only via [transferOwnership]. */
    suspend fun setRole(chainId: String, userId: String, role: MirrorRole): BtResult<Unit> =
        when (val r = apiCall(json) { api.setMirrorMemberRole(chainId, userId, MirrorSetRoleRequest(role.wire)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    suspend fun removeMember(chainId: String, userId: String): BtResult<Unit> =
        unitApiCall(json) { api.removeMirrorMember(chainId, userId) }

    suspend fun transferOwnership(chainId: String, toUserId: String): BtResult<Unit> =
        when (val r = apiCall(json) { api.transferMirrorChain(chainId, MirrorTransferRequest(toUserId)) }) {
            is BtResult.Ok -> BtResult.Ok(Unit)
            is BtResult.Err -> r
        }

    suspend fun dissolve(chainId: String): BtResult<Unit> =
        unitApiCall(json) { api.dissolveMirrorChain(chainId) }

    suspend fun convertPortfolio(portfolioId: String, name: String? = null): BtResult<String> =
        when (val r = apiCall(json) { api.convertPortfolioToChain(MirrorConvertRequest(portfolioId, name)) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.chainId)
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

        /**
         * 403 — the bearer allowlist refused the route outright. Distinct from
         * [CODE_MIRROR_FORBIDDEN], which is the chain's own role check: this one
         * says "no API key may do this", that one says "you specifically may not".
         */
        const val CODE_API_KEY_FORBIDDEN = "API_KEY_FORBIDDEN"

        /** 403/400 — the §5 role matrix, or an illegal self-targeting action. */
        const val CODE_MIRROR_FORBIDDEN = "MIRROR_FORBIDDEN"

        /**
         * Process-wide cache for [adminCapability]. A token's allowlist cannot
         * change while the app runs, so this is asked once and reused; it lives
         * in the companion rather than the instance because the repository is a
         * lazy singleton and this keeps the lifetime honest about being global.
         */
        @Volatile
        private var cachedAdminCapability: ChainAdminCapability? = null

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
