package at.bettertrack.app.ui.social

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.repo.COMMENT_BODY_MAX
import at.bettertrack.app.data.repo.REACTION_EMOJIS
import at.bettertrack.app.data.repo.ReactionTally
import at.bettertrack.app.data.repo.orderReactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thread section's Compose-free logic: the composer's rules, the optimistic
 * reaction maths, reaction ordering stability, and how a failed write is spoken
 * about. All of it is reachable without a device precisely because it was kept
 * top-level and out of the composables.
 */
class SocialThreadLogicTest {

    // ── orderReactions: a stable bar people can aim at ───────────────────────

    @Test
    fun `the six known emojis always render in REACTION_EMOJIS order`() {
        // Deliberately shuffled the way a server ordering by codepoint might.
        val fromServer = listOf(
            ReactionTally("🔥", 1, false),
            ReactionTally("🤔", 2, false),
            ReactionTally("👍", 3, true),
            ReactionTally("😂", 4, false),
            ReactionTally("❤️", 5, false),
            ReactionTally("🎉", 6, false),
        )

        assertEquals(REACTION_EMOJIS, orderReactions(fromServer).map { it.emoji })
    }

    @Test
    fun `ordering is stable no matter which subset arrives`() {
        val a = orderReactions(listOf(ReactionTally("🔥", 1, false), ReactionTally("👍", 1, false)))
        val b = orderReactions(listOf(ReactionTally("👍", 1, false), ReactionTally("🔥", 1, false)))

        assertEquals(listOf("👍", "🔥"), a.map { it.emoji })
        assertEquals(a, b)
    }

    @Test
    fun `an unknown future emoji is kept and sorted after the six`() {
        val ordered = orderReactions(
            listOf(
                ReactionTally("🫡", 2, true),
                ReactionTally("🔥", 1, false),
                ReactionTally("🚀", 9, false),
                ReactionTally("👍", 4, false),
            ),
        )

        assertEquals(listOf("👍", "🔥", "🫡", "🚀"), ordered.map { it.emoji })
        // Kept whole — count and `mine` survive, so a future emoji never silently
        // loses somebody's reaction.
        assertEquals(ReactionTally("🫡", 2, true), ordered[2])
    }

    @Test
    fun `an empty tally stays empty`() {
        assertTrue(orderReactions(emptyList()).isEmpty())
    }

    // ── optimisticToggle: the guess is shaped exactly like the truth ─────────

    @Test
    fun `reacting to an emoji nobody used adds it as mine with count one`() {
        assertEquals(
            listOf(ReactionTally("🎉", 1, mine = true)),
            optimisticToggle(emptyList(), "🎉"),
        )
    }

    @Test
    fun `joining an existing reaction increments it and marks it mine`() {
        assertEquals(
            listOf(ReactionTally("👍", 4, mine = true)),
            optimisticToggle(listOf(ReactionTally("👍", 3, mine = false)), "👍"),
        )
    }

    @Test
    fun `leaving a shared reaction decrements it and unmarks mine`() {
        assertEquals(
            listOf(ReactionTally("👍", 2, mine = false)),
            optimisticToggle(listOf(ReactionTally("👍", 3, mine = true)), "👍"),
        )
    }

    @Test
    fun `leaving my only reaction removes the emoji entirely, matching the wire`() {
        // The server omits an unreacted emoji rather than sending count 0, so the
        // optimistic list has to omit it too — otherwise the confirmed render and
        // the guessed render of the same state would differ.
        assertEquals(
            emptyList<ReactionTally>(),
            optimisticToggle(listOf(ReactionTally("🔥", 1, mine = true)), "🔥"),
        )
    }

    @Test
    fun `a toggle leaves the other emojis untouched and keeps the order`() {
        val before = listOf(
            ReactionTally("👍", 2, mine = false),
            ReactionTally("🤔", 1, mine = true),
        )

        val after = optimisticToggle(before, "❤️")

        assertEquals(listOf("👍", "❤️", "🤔"), after.map { it.emoji })
        assertEquals(ReactionTally("👍", 2, mine = false), after[0])
        assertEquals(ReactionTally("🤔", 1, mine = true), after[2])
    }

    @Test
    fun `toggling twice returns to the starting state`() {
        val start = listOf(ReactionTally("👍", 3, mine = false), ReactionTally("😂", 1, mine = true))

        val roundTrip = optimisticToggle(optimisticToggle(start, "👍"), "👍")

        assertEquals(start, roundTrip)
    }

    // ── validateCommentBody: trim first, then measure ────────────────────────

    @Test
    fun `a normal comment is valid and carries the trimmed body`() {
        assertEquals(CommentDraft.Valid("Hello there"), validateCommentBody("  Hello there \n"))
    }

    @Test
    fun `empty and whitespace-only bodies cannot be sent`() {
        assertEquals(CommentDraft.Empty, validateCommentBody(""))
        assertEquals(CommentDraft.Empty, validateCommentBody("   "))
        assertEquals(CommentDraft.Empty, validateCommentBody("\n\t  \n"))
    }

    @Test
    fun `exactly the limit is accepted`() {
        val body = "x".repeat(COMMENT_BODY_MAX)

        assertEquals(CommentDraft.Valid(body), validateCommentBody(body))
    }

    @Test
    fun `one over the limit is rejected`() {
        val body = "x".repeat(COMMENT_BODY_MAX + 1)

        assertEquals(CommentDraft.TooLong(COMMENT_BODY_MAX + 1), validateCommentBody(body))
    }

    @Test
    fun `padding does not count towards the limit, because the server trims first`() {
        // 2000 real characters wrapped in whitespace is legal on the server, so it
        // must be legal here — a client that measured the raw string would block it.
        val padded = "  " + "x".repeat(COMMENT_BODY_MAX) + "\n\n"

        assertEquals(CommentDraft.Valid("x".repeat(COMMENT_BODY_MAX)), validateCommentBody(padded))
    }

    @Test
    fun `the limit matches the contract`() {
        assertEquals(2000, COMMENT_BODY_MAX)
        assertEquals(listOf("👍", "❤️", "🎉", "🤔", "😂", "🔥"), REACTION_EMOJIS)
    }

    // ── Failure classification ──────────────────────────────────────────────

    @Test
    fun `429 is rate limiting, by status or by code`() {
        assertEquals(
            SocialWriteFailure.RateLimited,
            classifySocialWriteFailure(BtApiError(429, CODE_RATE_LIMITED, "Too many requests")),
        )
        // A proxy can strip the envelope; a bare 429 still means the same thing.
        assertEquals(
            SocialWriteFailure.RateLimited,
            classifySocialWriteFailure(BtApiError(429, "UNKNOWN", "Request failed (HTTP 429).")),
        )
        // And a code without the status (belt and braces).
        assertEquals(
            SocialWriteFailure.RateLimited,
            classifySocialWriteFailure(BtApiError(400, CODE_RATE_LIMITED, "x")),
        )
    }

    @Test
    fun `everything else is generic`() {
        assertEquals(
            SocialWriteFailure.Generic,
            classifySocialWriteFailure(BtApiError(500, "INTERNAL", "boom")),
        )
        assertEquals(
            SocialWriteFailure.Generic,
            classifySocialWriteFailure(BtApiError(0, BtApiError.Codes.NETWORK, "No connection.")),
        )
    }

    // ── Timestamps ──────────────────────────────────────────────────────────

    @Test
    fun `an ISO timestamp becomes epoch millis`() {
        assertEquals(1_785_837_600_000L, commentTimeMillis("2026-08-04T10:00:00.000Z"))
    }

    @Test
    fun `an unparseable timestamp is null rather than a crash`() {
        assertNull(commentTimeMillis(""))
        assertNull(commentTimeMillis("yesterday"))
    }
}
