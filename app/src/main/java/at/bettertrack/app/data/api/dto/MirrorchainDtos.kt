package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Mirrorchain — group-portfolio **participation** (V5, `mirrorchain:*`) —
 * platform `packages/contracts/src/mirrorchain.ts`, routes
 * `mirrorchainRoutes.ts` mounted at `/api/v1/mirrorchain`.
 *
 * **Only seven routes accept a bearer**, by a deliberate method-aware allowlist
 * in `bearerAuth.ts` (`MIRRORCHAIN_BEARER_ROUTE_ALLOWLIST`):
 *  - `GET  /mirrorchain/chains`                       → [MirrorChainListResponse]
 *  - `GET  /mirrorchain/chains/{chainId}/members`     → [MirrorMemberListResponse]
 *  - `GET  /mirrorchain/chains/{chainId}/activity`    → [MirrorActivityResponse]
 *  - `GET  /mirrorchain/invites`                      → [MirrorInviteListResponse]
 *  - `POST /mirrorchain/invites/{inviteId}/accept`    → [MirrorAcceptInviteResponse]
 *  - `POST /mirrorchain/invites/{inviteId}/decline`   → `{ok:true}`
 *  - `POST /mirrorchain/chains/{chainId}/leave`       → `{ok:true}`
 *
 * Everything else — create, convert, rename, invite, revoke, role changes,
 * transfer, kick, dissolve — is **session-only** and answers a bearer with
 * `403 API_KEY_FORBIDDEN`. That is why the app renders chain administration as
 * ABSENT rather than disabled: a greyed control implies a permission the client
 * could acquire, and this one it cannot.
 *
 * Note the allowlist is method-aware precisely because `GET /chains`
 * participates while `POST /chains` administers on the same path.
 */

// Catch-up state ([MirrorSyncStateDto]) is shared with the S2b portfolio
// overlays in `MirrorDtos.kt` — it is the same four-key shape on both surfaces,
// and one definition means the chain screen and a portfolio row can never
// disagree about what "synced" means.

/**
 * One chain as the CALLER sees it. [role] and [sync] are the caller's own;
 * [portfolioId] is the caller's local copy and is null once that portfolio has
 * been deleted, so it can never be assumed navigable.
 */
@Serializable
data class MirrorChainSummaryDto(
    val chainId: String = "",
    val name: String = "",
    /** `active` | `dissolved`. */
    val status: String = "",
    val portfolioId: String? = null,
    /** `owner` | `manager` | `member`. */
    val role: String = "",
    val memberCount: Int = 0,
    val sync: MirrorSyncStateDto = MirrorSyncStateDto(),
    val createdAt: String = "",
)

@Serializable
data class MirrorChainListResponse(
    val chains: List<MirrorChainSummaryDto> = emptyList(),
)

@Serializable
data class MirrorMemberDto(
    /** Null when the member's account was deleted — the row still exists. */
    val userId: String? = null,
    val username: String = "",
    /** Plain string here (the members contract does not narrow it to the icon enum). */
    val profileIcon: String? = null,
    /** `owner` | `manager` | `member`. */
    val role: String = "",
    val joinedAt: String = "",
    val isSelf: Boolean = false,
    val sync: MirrorSyncStateDto = MirrorSyncStateDto(),
)

@Serializable
data class MirrorMemberListResponse(
    val chainId: String = "",
    val name: String = "",
    val status: String = "",
    /** The CALLER's role in this chain. */
    val role: String = "",
    /** Server-side cap (16 today) — shown as "n of 16". */
    val memberCap: Int = 0,
    val members: List<MirrorMemberDto> = emptyList(),
)

/**
 * One activity-log entry.
 *
 * The op payload never crosses the wire: the server renders [summary] itself.
 * The app shows that sentence verbatim rather than reconstructing one from
 * [kind], which would drift from the platform's own wording. (It is English-only
 * today — noted for the DE error/string map backlog.)
 */
@Serializable
data class MirrorActivityEntryDto(
    val seq: Int = 0,
    /** One of the 25 `MIRROR_OP_KINDS` (ledger + chain lifecycle). */
    val kind: String = "",
    val actorUsername: String = "",
    val summary: String = "",
    val createdAt: String = "",
)

/**
 * Newest-first page. [nextCursor] is passed back as `before` for the next OLDER
 * page and is null at the start of the log.
 */
@Serializable
data class MirrorActivityResponse(
    val entries: List<MirrorActivityEntryDto> = emptyList(),
    val nextCursor: Int? = null,
)

/**
 * An invite in either direction. There is no `expiresAt` on the wire — expiry
 * is implicit (30 days) and expired rows are filtered out of the list entirely,
 * so anything the app receives is still actionable.
 */
@Serializable
data class MirrorInviteDto(
    val id: String = "",
    val chainId: String = "",
    val chainName: String = "",
    val fromUsername: String? = null,
    val toUsername: String = "",
    /** `incoming` | `outgoing`. */
    val direction: String = "",
    val createdAt: String = "",
)

@Serializable
data class MirrorInviteListResponse(
    val incoming: List<MirrorInviteDto> = emptyList(),
    val outgoing: List<MirrorInviteDto> = emptyList(),
)

/** Accepting materializes a local copy — [portfolioId] is that new portfolio. */
@Serializable
data class MirrorAcceptInviteResponse(
    val chainId: String = "",
    val portfolioId: String = "",
)

/** The `{ok:true}` acknowledgement shared by decline and leave. */
@Serializable
data class MirrorOkResponse(
    val ok: Boolean = false,
)
