package at.bettertrack.app.ui.chat

import at.bettertrack.app.data.repo.chatMessagePreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the chat surface (Step 15, §6.10): conversation-row preview
 * formatting and the relative-time stamp. Both are stub-agnostic and Context-free.
 */
class ChatLogicTest {

    // ── Conversation-row preview ─────────────────────────────────────────────

    @Test
    fun preview_incoming_text_is_plain() {
        assertEquals("Nice, up this week", chatMessagePreview(fromMe = false, body = "Nice, up this week", chipLabel = null))
    }

    @Test
    fun preview_outgoing_text_is_you_prefixed() {
        assertEquals("You: Anytime", chatMessagePreview(fromMe = true, body = "Anytime", chipLabel = null))
    }

    @Test
    fun preview_incoming_shared_chip() {
        assertEquals("📎 Shared Apple", chatMessagePreview(fromMe = false, body = null, chipLabel = "Apple"))
    }

    @Test
    fun preview_outgoing_shared_chip_is_you_prefixed() {
        assertEquals("You: 📎 Shared My Main", chatMessagePreview(fromMe = true, body = null, chipLabel = "My Main"))
    }

    @Test
    fun preview_chip_takes_precedence_over_body() {
        assertEquals("📎 Shared Tech basket", chatMessagePreview(fromMe = false, body = "look at this", chipLabel = "Tech basket"))
    }

    @Test
    fun preview_null_body_and_no_chip_is_empty() {
        assertEquals("", chatMessagePreview(fromMe = false, body = null, chipLabel = null))
    }

    // ── Relative time ────────────────────────────────────────────────────────

    private fun ago(minutes: Long): Long = System.currentTimeMillis() - minutes * 60_000L

    @Test
    fun time_just_now() {
        assertEquals("now", relativeTime(System.currentTimeMillis()))
    }

    @Test
    fun time_minutes() {
        assertEquals("5m", relativeTime(ago(5)))
    }

    @Test
    fun time_hours() {
        assertEquals("3h", relativeTime(ago(3 * 60)))
    }

    @Test
    fun time_days() {
        assertEquals("2d", relativeTime(ago(2 * 24 * 60)))
    }

    @Test
    fun time_weeks() {
        // A little over two weeks lands in the weeks bucket.
        assertEquals("2w", relativeTime(ago(2 * 7 * 24 * 60 + 60)))
    }
}
