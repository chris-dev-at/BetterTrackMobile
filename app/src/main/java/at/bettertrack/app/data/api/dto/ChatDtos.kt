package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Chat DTOs (Step 15, §6.10). The platform has **no chat endpoints yet** — chat
 * is web PROJECTPLAN V3-P8 ("1:1 friend DMs … share-in-chat … no groups/
 * reactions/read-receipts", realtime over the §4.5 gateway with a poll fallback,
 * notification type `chat.message`, future scopes `chat:read`/`chat:write`).
 *
 * These shapes are defined NOW, aligned to that plan + the platform's conventions
 * (camelCase, `{error:{code,message,details?}}`, cursor pagination `?limit&cursor`),
 * so the eventual `ChatApi` adapter is a thin decode. The repository is entirely
 * stub-backed until the endpoints ship (see [at.bettertrack.app.data.repo.ChatFlags]).
 *
 * Anticipated endpoints (NOT yet live — do not wire):
 *   GET  /chat/conversations
 *   GET  /chat/conversations/{id}/messages?limit&cursor
 *   POST /chat/conversations/{id}/messages   { body?, attachment? }
 *   POST /chat/conversations                 { friendUserId }
 *   POST /chat/conversations/{id}/read
 *   WS   /ws  room user:{id}  → { type: "chat.message", message }
 */

@Serializable
data class ConversationDto(
    val id: String,
    val otherUser: SocialUserDto,
    val lastMessage: MessagePreviewDto? = null,
    val unreadCount: Int = 0,
    val updatedAt: String,
)

@Serializable
data class MessagePreviewDto(
    val body: String? = null,
    /** True when the preview is a share chip (rendered as "📎 Shared …"). */
    val hasAttachment: Boolean = false,
    val fromMe: Boolean = false,
)

@Serializable
data class ConversationListResponse(
    val conversations: List<ConversationDto> = emptyList(),
    val totalUnread: Int = 0,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val body: String? = null,
    val attachment: ShareChipDto? = null,
    val sentAt: String,
)

@Serializable
data class MessageListResponse(
    val messages: List<ChatMessageDto> = emptyList(),
    val nextCursor: String? = null,
)

/**
 * A share-in-chat chip: an asset reference or one of the sender's shareable items.
 * **Sending never widens access** — [viewable] is the RECIPIENT's resolution: the
 * server resolves the chip only if the item's audience already allows them, else
 * the chip renders as "not shared with you" with no data leak.
 */
@Serializable
data class ShareChipDto(
    /** `asset` | `portfolio` | `watchlist` | `conglomerate`. */
    val kind: String,
    val refId: String,
    /** Display label (asset name / item name); safe even when not viewable. */
    val label: String,
    val symbol: String? = null,
    val ownerName: String? = null,
    /** Recipient's access — false ⇒ show the "not shared with you" chip state. */
    val viewable: Boolean = true,
)

@Serializable
data class SendMessageRequest(
    val body: String? = null,
    val attachment: ShareChipDto? = null,
)
