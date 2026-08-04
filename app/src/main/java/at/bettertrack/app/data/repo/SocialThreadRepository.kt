package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CreateCommentRequest
import at.bettertrack.app.data.api.dto.ItemCommentDto
import at.bettertrack.app.data.api.dto.ReactionSummaryDto
import at.bettertrack.app.data.api.dto.ToggleReactionRequest
import at.bettertrack.app.data.api.parseApiError
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Comments + emoji reactions on shared items (V5, `social:*`).
 *
 * A thread hangs off any shareable subject — portfolio, conglomerate, watchlist
 * or idea — and its authorization is the item's own audience, re-derived per
 * request. A viewer who is no longer admitted gets **404**, never 403, so
 * [ThreadOutcome.NotShared] is a first-class state and not an error string.
 */

/** The six emojis the platform accepts. Anything else is a 400. */
val REACTION_EMOJIS: List<String> = listOf("👍", "❤️", "🎉", "🤔", "😂", "🔥")

/** Server-enforced comment bounds (the server trims before checking). */
const val COMMENT_BODY_MAX = 2000

// ── Domain models (the UI never sees wire DTOs) ─────────────────────────────

data class ReactionTally(
    val emoji: String,
    val count: Int,
    /** Whether *I* reacted with this emoji (wire field `reacted`). */
    val mine: Boolean,
)

data class ItemComment(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorIcon: String?,
    val body: String,
    val createdAt: String,
    /**
     * Server-computed: my own comment, or any comment on an item I own. Read,
     * never re-derived — the app does not reliably know the item's owner id on
     * every surface a thread appears on.
     */
    val canDelete: Boolean,
    val reactions: List<ReactionTally>,
)

data class ItemThread(
    val kind: ShareableKind,
    val subjectId: String,
    val commentCount: Int,
    /** Oldest first, as the server orders them. */
    val comments: List<ItemComment>,
    /** The item-level tally (each comment carries its own). */
    val reactions: List<ReactionTally>,
)

/** Loading a thread has three honest outcomes, and only one of them is an error. */
sealed interface ThreadOutcome {
    data class Loaded(val thread: ItemThread) : ThreadOutcome

    /** 404 — the item is no longer shared with me (or never was). */
    data object NotShared : ThreadOutcome

    data class Failed(val error: BtApiError) : ThreadOutcome
}

/**
 * Merge a fresh tally into a list, preserving a stable display order.
 *
 * Pure and top-level so the toggle's *visible* behaviour is unit-testable
 * without a server: the endpoints return the target's complete fresh tally, and
 * the app orders it by [REACTION_EMOJIS] rather than by the server's codepoint
 * ordering — a reaction bar whose chips reshuffle when a count changes is a bar
 * people mis-tap.
 */
fun orderReactions(reactions: List<ReactionTally>): List<ReactionTally> {
    val known = REACTION_EMOJIS.mapNotNull { e -> reactions.firstOrNull { it.emoji == e } }
    // An emoji the app doesn't model (a future addition) keeps its tally rather
    // than vanishing — it just sorts after the six it knows.
    val unknown = reactions.filter { r -> REACTION_EMOJIS.none { it == r.emoji } }
    return known + unknown
}

class SocialThreadRepository(
    private val api: BtApi,
    private val json: Json,
) {

    suspend fun thread(kind: ShareableKind, subjectId: String): ThreadOutcome =
        when (val r = apiCall(json) { api.itemThread(kind.wire, subjectId) }) {
            is BtResult.Ok -> ThreadOutcome.Loaded(
                ItemThread(
                    kind = ShareableKind.fromWire(r.value.kind),
                    subjectId = r.value.subjectId,
                    commentCount = r.value.commentCount,
                    comments = r.value.comments.map { it.toDomain() },
                    reactions = orderReactions(r.value.reactions.map { it.toDomain() }),
                ),
            )

            is BtResult.Err ->
                if (r.error.httpStatus == 404) ThreadOutcome.NotShared else ThreadOutcome.Failed(r.error)
        }

    /**
     * Post a comment. The body is trimmed here as well as server-side so the
     * client-side length guard and the server's agree on what counts.
     */
    suspend fun addComment(
        kind: ShareableKind,
        subjectId: String,
        body: String,
    ): BtResult<ItemComment> =
        when (
            val r = apiCall(json) {
                api.createItemComment(kind.wire, subjectId, CreateCommentRequest(body.trim()))
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    /** Toggle an item reaction; answers the item's complete fresh tally. */
    suspend fun toggleItemReaction(
        kind: ShareableKind,
        subjectId: String,
        emoji: String,
    ): BtResult<List<ReactionTally>> =
        when (
            val r = apiCall(json) {
                api.toggleItemReaction(kind.wire, subjectId, ToggleReactionRequest(emoji))
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(orderReactions(r.value.reactions.map { it.toDomain() }))
            is BtResult.Err -> r
        }

    /** Toggle a reaction on one comment; answers that comment's fresh tally. */
    suspend fun toggleCommentReaction(
        commentId: String,
        emoji: String,
    ): BtResult<List<ReactionTally>> =
        when (
            val r = apiCall(json) { api.toggleCommentReaction(commentId, ToggleReactionRequest(emoji)) }
        ) {
            is BtResult.Ok -> BtResult.Ok(orderReactions(r.value.reactions.map { it.toDomain() }))
            is BtResult.Err -> r
        }

    /** Delete a comment (own, or moderated as the item owner). 204. */
    suspend fun deleteComment(commentId: String): BtResult<Unit> =
        try {
            val resp = api.deleteComment(commentId)
            if (resp.isSuccessful) {
                BtResult.Ok(Unit)
            } else {
                BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
            }
        } catch (_: IOException) {
            BtResult.Err(
                BtApiError(0, BtApiError.Codes.NETWORK, "No connection. Check your network and try again."),
            )
        }

    private fun ItemCommentDto.toDomain() = ItemComment(
        id = id,
        authorId = author.id,
        authorName = author.username,
        authorIcon = author.profileIcon,
        body = body,
        createdAt = createdAt,
        canDelete = canDelete,
        reactions = orderReactions(reactions.map { it.toDomain() }),
    )

    private fun ReactionSummaryDto.toDomain() = ReactionTally(emoji, count, reacted)
}
