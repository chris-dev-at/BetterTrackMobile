package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Comments + emoji reactions on shared items (V5 social, `social:*`) — platform
 * `packages/contracts/src/social.ts`, routes `socialRoutes.ts`.
 *
 * Endpoints:
 *  - `GET  /social/items/{kind}/{subjectId}/thread`   → [CommentThreadResponse]
 *  - `POST /social/items/{kind}/{subjectId}/comments` → 201 [ItemCommentDto]
 *  - `POST /social/items/{kind}/{subjectId}/reactions`→ 200 [ReactionListResponse]
 *  - `POST /social/comments/{commentId}/reactions`    → 200 [ReactionListResponse]
 *  - `DELETE /social/comments/{commentId}`            → 204
 *
 * `kind` is the full share ladder including `idea`
 * (`SHARE_KINDS = ['portfolio','conglomerate','watchlist','idea']`).
 *
 * Two contract facts worth stating out loud because they shape the UI:
 *  - **Comments are not editable.** There is no `updatedAt`, no `edited` flag and
 *    no PATCH route — a comment is written once and can only be deleted.
 *  - **A thread has no pagination.** `listForItem` selects every live comment
 *    oldest-first with no LIMIT, so the app renders what it is given; the only
 *    throttle anywhere on this surface is the shared 30-writes-per-hour social
 *    rate limiter (429 `RATE_LIMITED`).
 */

/**
 * One emoji's tally on an item or a comment.
 *
 * The wire field is **`reacted`** (not `reactedByMe`), and only emojis with a
 * non-zero count appear at all — an unreacted emoji is simply absent from the
 * list rather than present with `count: 0`.
 */
@Serializable
data class ReactionSummaryDto(
    val emoji: String = "",
    val count: Int = 0,
    val reacted: Boolean = false,
)

@Serializable
data class ItemCommentDto(
    val id: String = "",
    val author: SocialUserDto = SocialUserDto(),
    val body: String = "",
    /** ISO datetime. */
    val createdAt: String = "",
    /**
     * Server-computed: true for the comment's author **or** the item's owner
     * (owners moderate their own item's thread). The app honours this flag
     * rather than re-deriving the rule from ids it may not have.
     */
    val canDelete: Boolean = false,
    val reactions: List<ReactionSummaryDto> = emptyList(),
)

/** `GET /social/items/{kind}/{subjectId}/thread`. */
@Serializable
data class CommentThreadResponse(
    val kind: String = "",
    val subjectId: String = "",
    val commentCount: Int = 0,
    /** Oldest first. */
    val comments: List<ItemCommentDto> = emptyList(),
    /** The ITEM-level reaction tally (comments carry their own). */
    val reactions: List<ReactionSummaryDto> = emptyList(),
)

/** Body of `POST .../comments`. Server trims, then enforces 1..2000. */
@Serializable
data class CreateCommentRequest(
    val body: String,
)

/** Body of both reaction toggles. `emoji` must be one of the six allowed. */
@Serializable
data class ToggleReactionRequest(
    val emoji: String,
)

/**
 * Both reaction endpoints answer with the target's **complete fresh tally**, not
 * a toggled boolean — so the app replaces its local list wholesale and never
 * has to reconcile an optimistic guess against a partial delta.
 */
@Serializable
data class ReactionListResponse(
    val reactions: List<ReactionSummaryDto> = emptyList(),
)
