package at.bettertrack.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "remind me later" snooze, read the way About renders it.
 *
 * The predicate is one line, and that is exactly why it is worth pinning: it has
 * to answer the boundary the SAME way the dialog gate does
 * (`nowMs() < prefs.remindAfterMs`, `UpdateChecker.runCheck`). If the two ever
 * disagree by one millisecond the app either claims a snooze that no longer
 * silences anything, or silences a prompt while insisting nothing is paused —
 * and both read as the notifier being broken.
 */
class UpdateSnoozeTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `a future deadline is the snooze`() {
        val deadline = now + UpdateChecker.REMIND_SNOOZE_MS
        assertEquals(deadline, updateSnoozeDeadlineMs(deadline, now))
    }

    @Test
    fun `never snoozed reads as no snooze`() {
        // 0L is UpdatePrefs' stored default — "remindAfterMs was never written".
        assertNull(updateSnoozeDeadlineMs(0L, now))
    }

    @Test
    fun `an expired deadline is no longer a snooze`() {
        assertNull(updateSnoozeDeadlineMs(now - 1, now))
        assertNull(updateSnoozeDeadlineMs(now - UpdateChecker.REMIND_SNOOZE_MS, now))
    }

    @Test
    fun `the boundary matches the dialog gate exactly`() {
        // The gate suppresses while `now < remindAfter`, so the instant the two
        // are equal the snooze is OVER. Same instant, same answer, both places.
        assertNull(updateSnoozeDeadlineMs(now, now))
        assertEquals(now + 1, updateSnoozeDeadlineMs(now + 1, now))
    }

    @Test
    fun `a fresh remind-later lands one full window in the future`() {
        // Mirrors UpdateChecker.remindLater's arithmetic without touching prefs.
        val stored = now + UpdateChecker.REMIND_SNOOZE_MS
        assertEquals(stored, updateSnoozeDeadlineMs(stored, now))
        // …and stops being a snooze the moment the window has elapsed.
        assertNull(updateSnoozeDeadlineMs(stored, stored))
        assertEquals(24L * 60 * 60 * 1000, UpdateChecker.REMIND_SNOOZE_MS)
    }
}
