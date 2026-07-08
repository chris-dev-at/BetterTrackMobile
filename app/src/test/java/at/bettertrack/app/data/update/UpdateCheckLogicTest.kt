package at.bettertrack.app.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step-V pure-logic tests (dev update notifier): version comparison, the
 * cold-start / 6h check cadence, and the one-per-version dialog gate
 * (ignore + remind-later suppression) plus the always-on badge rule.
 */
class UpdateCheckLogicTest {

    private val interval = UpdateCheckLogic.CHECK_INTERVAL_MS

    @Test
    fun `isNewer strictly compares versionCode`() {
        assertTrue(UpdateCheckLogic.isNewer(1, 2))
        assertFalse(UpdateCheckLogic.isNewer(2, 2))
        assertFalse(UpdateCheckLogic.isNewer(3, 2))
    }

    @Test
    fun `cold start always checks and warm start respects the interval`() {
        val now = 10_000_000L
        assertTrue(UpdateCheckLogic.shouldCheckNow(now, lastCheckMs = now, coldStart = true, intervalMs = interval))
        assertFalse(UpdateCheckLogic.shouldCheckNow(now, lastCheckMs = now, coldStart = false, intervalMs = interval))
        assertTrue(
            UpdateCheckLogic.shouldCheckNow(now, lastCheckMs = now - interval, coldStart = false, intervalMs = interval),
        )
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(now, lastCheckMs = now - (interval - 1), coldStart = false, intervalMs = interval),
        )
    }

    @Test
    fun `dialog shows once per new version and respects ignore + remind`() {
        // Newer, not ignored, not snoozed → show.
        assertTrue(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, remindedThisSession = false))
        // Same version ignored → never show.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = false))
        // A DIFFERENT (even newer) version is not covered by the old ignore.
        assertTrue(UpdateCheckLogic.shouldShowDialog(1, 6, ignoredVersionCode = 5, remindedThisSession = false))
        // Reminded this session → suppressed until next cold start.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, remindedThisSession = true))
        // Not actually newer → never show.
        assertFalse(UpdateCheckLogic.shouldShowDialog(5, 5, ignoredVersionCode = 0, remindedThisSession = false))
    }

    @Test
    fun `badge shows whenever a newer build exists regardless of ignore`() {
        assertTrue(UpdateCheckLogic.shouldShowBadge(1, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(5, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(6, 5))
    }
}
