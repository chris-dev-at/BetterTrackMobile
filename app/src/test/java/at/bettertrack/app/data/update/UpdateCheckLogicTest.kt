package at.bettertrack.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step-V pure-logic tests (dev update notifier): version comparison, the
 * cold-start / debounced check cadence, and the dialog gate (ignore + snooze
 * suppression) plus the always-on badge rule.
 *
 * These rules were all green while the popup was invisible on v0.107 — the bug
 * was in the ORCHESTRATION that calls them, which is why `UpdateCheckerTest`
 * now exists alongside this file. What changed HERE is the cadence constant
 * (six hours → a 15-minute anti-hammer debounce) and the name of the dialog's
 * quiet-flag: it no longer means "already reminded in this process", it means
 * "silenced right now", for any of the reasons the checker folds together.
 */
class UpdateCheckLogicTest {

    private val debounce = UpdateCheckLogic.FOREGROUND_DEBOUNCE_MS

    @Test
    fun `isNewer strictly compares versionCode`() {
        assertTrue(UpdateCheckLogic.isNewer(1, 2))
        assertFalse(UpdateCheckLogic.isNewer(2, 2))
        assertFalse(UpdateCheckLogic.isNewer(3, 2))
    }

    @Test
    fun `cold start always checks and warm start respects the debounce`() {
        val now = 10_000_000L
        assertTrue(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now, coldStart = true, debounceMs = debounce))
        assertFalse(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now, coldStart = false, debounceMs = debounce))
        assertTrue(
            UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now - debounce, coldStart = false, debounceMs = debounce),
        )
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now - (debounce - 1), coldStart = false, debounceMs = debounce),
        )
    }

    @Test
    fun `the foreground debounce is fifteen minutes, not six hours`() {
        // The owner bug: reopening the app inside the old six-hour window did
        // nothing at all. 15 minutes caps the automatic path at 4 requests/hour
        // against a 60/hour unauthenticated budget — polite, and short enough
        // that "I opened the app" and "the app looked" are the same event.
        assertEquals(15L * 60 * 1000, UpdateCheckLogic.FOREGROUND_DEBOUNCE_MS)
        val now = 10_000_000L
        val sixHours = 6L * 60 * 60 * 1000
        // A warm reopen 20 minutes after the last attempt now checks; under the
        // old constant it stayed silent for another five and a half hours.
        assertTrue(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = now - 20L * 60 * 1000, coldStart = false))
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(
                true, now, lastCheckMs = now - 20L * 60 * 1000, coldStart = false, debounceMs = sixHours,
            ),
        )
    }

    @Test
    fun `disabled auto-check never checks, even on cold start`() {
        val now = 10_000_000L
        // The toggle is the hard gate: OFF suppresses both cold start and an
        // otherwise-due warm check.
        assertFalse(UpdateCheckLogic.shouldCheckNow(false, now, lastCheckMs = 0L, coldStart = true, debounceMs = debounce))
        assertFalse(
            UpdateCheckLogic.shouldCheckNow(false, now, lastCheckMs = now - debounce, coldStart = false, debounceMs = debounce),
        )
        // Sanity: same inputs but enabled → does check.
        assertTrue(UpdateCheckLogic.shouldCheckNow(true, now, lastCheckMs = 0L, coldStart = true, debounceMs = debounce))
    }

    @Test
    fun `dialog shows for a newer build and respects ignore + snooze`() {
        // Newer, not ignored, not snoozed → show.
        assertTrue(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, snoozed = false))
        // Same version ignored → never show.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = false))
        // A DIFFERENT (even newer) version is not covered by the old ignore.
        assertTrue(UpdateCheckLogic.shouldShowDialog(1, 6, ignoredVersionCode = 5, snoozed = false))
        // Snoozed → suppressed until the snooze runs out.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, snoozed = true))
        // Not actually newer → never show.
        assertFalse(UpdateCheckLogic.shouldShowDialog(5, 5, ignoredVersionCode = 0, snoozed = false))
    }

    @Test
    fun `an un-snoozed newer build shows every time it is asked`() {
        // The spec, as a pure rule: nothing about this decision is once-per-
        // process, once-per-version or once-per-anything. Same inputs, same
        // answer, however many app-opens ask the question.
        repeat(5) {
            assertTrue(UpdateCheckLogic.shouldShowDialog(107, 108, ignoredVersionCode = 0, snoozed = false))
        }
    }

    @Test
    fun `a manual check re-offers a version the user ignored or snoozed`() {
        // Ignored forever by the automatic path…
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = false))
        // …but the user just tapped "Check for updates", so it is offered again.
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = false, manual = true),
        )
        // Same for a "remind me later" snooze, and for both suppressions at once.
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, snoozed = true, manual = true),
        )
        assertTrue(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = true, manual = true),
        )
    }

    @Test
    fun `a manual check still refuses to invent a newer build`() {
        // "Manual" overrides the SUPPRESSIONS, never the version comparison: with
        // nothing newer there is no dialog to show, and the About screen answers
        // the tap with its up-to-date line instead.
        assertFalse(UpdateCheckLogic.shouldShowDialog(5, 5, ignoredVersionCode = 0, snoozed = false, manual = true))
        assertFalse(UpdateCheckLogic.shouldShowDialog(6, 5, ignoredVersionCode = 0, snoozed = false, manual = true))
    }

    @Test
    fun `manual defaults to false so automatic callers keep both suppressions`() {
        // Regression guard for the added parameter: the ignore/snooze rules must
        // survive at every existing call site that does not name `manual`.
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = false))
        assertFalse(UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 0, snoozed = true))
        assertFalse(
            UpdateCheckLogic.shouldShowDialog(1, 5, ignoredVersionCode = 5, snoozed = false, manual = false),
        )
    }

    @Test
    fun `badge shows whenever a newer build exists regardless of ignore`() {
        assertTrue(UpdateCheckLogic.shouldShowBadge(1, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(5, 5))
        assertFalse(UpdateCheckLogic.shouldShowBadge(6, 5))
    }

    @Test
    fun `the badge and the dialog agree that a newer build exists`() {
        // They may disagree about whether to SPEAK (ignore/snooze silence the
        // dialog, never the badge) but never about the fact. A badge without a
        // reachable offer is what "update available, no popup" looked like.
        assertEquals(
            UpdateCheckLogic.shouldShowBadge(107, 108),
            UpdateCheckLogic.shouldShowDialog(107, 108, ignoredVersionCode = 0, snoozed = false),
        )
        assertEquals(
            UpdateCheckLogic.shouldShowBadge(108, 108),
            UpdateCheckLogic.shouldShowDialog(108, 108, ignoredVersionCode = 0, snoozed = false),
        )
    }
}
