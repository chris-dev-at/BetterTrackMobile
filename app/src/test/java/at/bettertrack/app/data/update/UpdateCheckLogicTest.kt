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
        assertTrue(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now, coldStart = true, intervalMs = interval))
        assertFalse(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now, coldStart = false, intervalMs = interval))
        assertTrue(
            UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now - interval, coldStart = false, intervalMs = interval),
        )
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now - (interval - 1), coldStart = false, intervalMs = interval),
        )
    }

    @Test
    fun `disabled auto-check never checks, even on cold start`() {
        val now = 10_000_000L
        // The toggle is the hard gate: OFF suppresses both cold start and an
        // otherwise-due warm check.
        assertFalse(UpdateCheckLogic.shouldCheckNow(false, now, lastCheckMs = 0L, coldStart = true, intervalMs = interval))
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(false, now, lastCheckMs = now - interval, coldStart = false, intervalMs = interval),
        )
        // Sanity: same inputs but enabled → does check.
        assertTrue(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = 0L, coldStart = true, intervalMs = interval))
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
    fun `a manual check re-offers a version the user ignored or snoozed`() {
        // Ignored forever by the automatic path…
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = false))
        // …but the user just tapped "Check for updates", so it is offered again.
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = false, manual = true),
        )
        // Same for a "remind me later" snooze, and for both suppressions at once.
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, remindedThisSession = true, manual = true),
        )
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = true, manual = true),
        )
    }

    @Test
    fun `a manual check still refuses to invent a newer build`() {
        // "Manual" overrides the SUPPRESSIONS, never the version comparison: with
        // nothing newer there is no dialog to show, and the About screen answers
        // the tap with its up-to-date line instead.
        assertFalse(UpdateCheckLogic.shouldShowDialog(5, 5, ignoredVersionCode = 0, remindedThisSession = false, manual = true))
        assertFalse(UpdateCheckLogic.shouldShowDialog(6, 5, ignoredVersionCode = 0, remindedThisSession = false, manual = true))
    }

    @Test
    fun `manual defaults to false so automatic callers keep both suppressions`() {
        // Regression guard for the added parameter: the ignore/remind rules must
        // survive at every existing call site that does not name `manual`.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = false))
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, remindedThisSession = true))
        assertFalse(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, remindedThisSession = false, manual = false),
        )
    }

    @Test
    fun `badge shows whenever a newer build exists regardless of ignore`() {
        assertTrue(UpdateCheckLogic.shouldShowBadge(1, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(5, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(6, 5))
    }
}
