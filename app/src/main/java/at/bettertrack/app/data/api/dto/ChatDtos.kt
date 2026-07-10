package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Chat DTOs (Step 15, §6.10) — now wired to the LIVE platform backend (#349 + #386,
 * `chat:read`/`chat:write`). Shapes mirror the web contracts `packages/contracts`
 * `chat.ts` verbatim (verified against `api.bettertrack.at/openapi.json`):
 *
 *   GET  /chat/conversations                              → ChatConversationListResponse
 *   POST /chat/conversations                { userId }    → ConversationResponse (201)
 *   GET  /chat/conversations/{id}/messages?cursor&limit   → ChatThreadResponse
 *   POST /chat/conversations/{id}/messages  { body?,chip?}→ SendChatMessageResponse (201)
 *   POST /chat/conversations/{id}/read                    → { ok: true }
 *
 * Privacy is contracts-first: a **share chip** is a bare `(kind, subjectId)`
 * reference — never a snapshot. Each viewer's chip is re-resolved by the server
 * through the ONE sharing-enforcement layer at read time: [ChatChipDto.viewable]
 * is the per-viewer decision and `title`/`subtitle` are `null` when not viewable,
 * so an unauthorized recipient never sees even the item's name. Sending a chip
 * writes nothing to the audience model (never grants/widens access).
 */

/** The four share-chip kinds (contract `CHAT_CHIP_KINDS`). */
object ChatChipKinds {
    const val ASSET = "asset"
    const val PORTFOLIO = "portfolio"
    const val CONGLOMERATE = "conglomerate"
    const val WATCHLIST = "watchlist"
}

/**
 * The bare chip reference a client attaches when SENDING — the server validates
 * the sender may reference it (owns the shareable, or can see the asset) and
 * stores only these two fields (no snapshot).
 */
@Serializable
data class ChatChipRefDto(
    val kind: String,
    val subjectId: String,
)

/**
 * A chip **as resolved for one viewer**. [viewable] is the enforcement result
 * recomputed for this viewer; when `false` the chip renders the "not shared with
 * you" state and `title`/`subtitle` are `null` (no name crosses).
 */
@Serializable
data class ChatChipDto(
    /** `asset` | `portfolio` | `conglomerate` | `watchlist`. */
    val kind: String,
    val subjectId: String,
    val viewable: Boolean,
    /** Headline (asset symbol / item name). `null` ⇒ not viewable. */
    val title: String? = null,
    /** Secondary line (asset name / owner username). `null` ⇒ not viewable or none. */
    val subtitle: String? = null,
)

/** One message in a thread. Carries text, a share [chip], or both. */
@Serializable
data class ChatMessageDto(
    val id: String,
    val conversationId: String,
    /** Nullable per contract: `null` when the sender's account was deleted (#362). */
    val senderId: String? = null,
    val body: String? = null,
    val chip: ChatChipDto? = null,
    val createdAt: String,
)

/**
 * The conversation-list preview of the newest message. A chip preview carries
 * only its [chipKind] (never resolved identity), so the list renders "Shared a
 * portfolio" client-side without leaking a non-shared item's name.
 */
@Serializable
data class ChatMessagePreviewDto(
    /** Nullable per contract: `null` when the sender's account was deleted (#362). */
    val senderId: String? = null,
    val body: String? = null,
    val chipKind: String? = null,
    val createdAt: String,
)

/**
 * A conversation as seen by the caller: the OTHER participant ([user] — public-safe
 * id + username), the caller's [unreadCount], and the last-message preview.
 * `lastMessage`/`lastMessageAt` are `null` for a freshly-opened empty thread.
 */
@Serializable
data class ChatConversationDto(
    val id: String,
    /** Nullable per contract: `null` when the other participant deleted their account (#362). */
    val user: SocialUserDto? = null,
    val unreadCount: Int = 0,
    val lastMessage: ChatMessagePreviewDto? = null,
    val lastMessageAt: String? = null,
)

/** `GET /chat/conversations` — the caller's threads, newest-active first + total badge. */
@Serializable
data class ChatConversationListResponse(
    val conversations: List<ChatConversationDto> = emptyList(),
    val unreadTotal: Int = 0,
)

/** `POST /chat/conversations` body — open/resolve the 1:1 conversation with a friend. */
@Serializable
data class OpenConversationRequest(val userId: String)

/** `POST /chat/conversations` response — the resolved conversation summary. */
@Serializable
data class ConversationResponse(val conversation: ChatConversationDto)

/**
 * `GET /chat/conversations/{id}/messages` — a page of the thread, newest-first,
 * plus the conversation summary (so a deep-linked open needs no second round-trip).
 * [nextCursor] `null` ⇒ no older page.
 */
@Serializable
data class ChatThreadResponse(
    val conversation: ChatConversationDto,
    val messages: List<ChatMessageDto> = emptyList(),
    val nextCursor: String? = null,
)

/**
 * `POST /chat/conversations/{id}/messages` body — text, a share chip, or both;
 * at least one required. `body` ≤ [CHAT_MESSAGE_MAX] chars (enforced client-side too).
 */
@Serializable
data class SendChatMessageRequest(
    val body: String? = null,
    val chip: ChatChipRefDto? = null,
)

/** `POST …/messages` response — the created message, resolved for the sender. */
@Serializable
data class SendChatMessageResponse(val message: ChatMessageDto)

/** Max characters in a single message body (contract `CHAT_MESSAGE_MAX`). */
const val CHAT_MESSAGE_MAX = 4000
