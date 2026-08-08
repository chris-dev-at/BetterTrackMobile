package at.bettertrack.app.ui.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a downward pull goes on the five refresh sheets (owner, 2026-08-08).
 *
 * The shipped build left this to Material3's internals — it consumes nothing
 * while `isRefreshing` is true — with a 320 ms minimum-visible floor on the
 * indicator quietly deciding how long that lasted. Both are gone. The window is
 * now explicit, fixed, and measured from the TRIGGER rather than from whenever
 * the network happens to answer.
 *
 * The clock is virtual, so a 600 ms window costs the suite nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BtRefreshRoutingTest {

    // ── The routing rule ────────────────────────────────────────────────────

    @Test
    fun `an armed, idle screen gives the pull to refresh`() {
        assertEquals(BtPullOwner.REFRESH, btPullOwner(armed = true, isRefreshing = false))
    }

    @Test
    fun `a disarmed screen gives the pull to the sheet`() {
        // This is the whole ask: pull one refreshes, pull two dismisses.
        assertEquals(BtPullOwner.SHEET, btPullOwner(armed = false, isRefreshing = false))
    }

    @Test
    fun `a refresh already running gives the pull to the sheet`() {
        assertEquals(BtPullOwner.SHEET, btPullOwner(armed = true, isRefreshing = true))
        assertEquals(BtPullOwner.SHEET, btPullOwner(armed = false, isRefreshing = true))
    }

    // ── The window ──────────────────────────────────────────────────────────

    @Test
    fun `the gesture is handed over instantly and taken back at the window`() {
        runTest {
            var armed = true
            val run = launch { btRefreshDisarmWindow { armed = it } }

            advanceTimeBy(1)
            assertFalse("the second pull must belong to the sheet at once", armed)

            advanceTimeBy(BT_REFRESH_DISARM_MS - 2)
            assertFalse("...for the whole window", armed)

            advanceUntilIdle()
            assertTrue("...and refresh gets it back afterwards", armed)
            run.join()
        }
    }

    @Test
    fun `the window is measured from the trigger, not from the refresh finishing`() {
        // The point of dropping the floor: a refresh that answers in 3 ms must not
        // re-arm the gesture 3 ms later and turn the user's second pull into a
        // second refresh. The window does not consult the refresh at all.
        runTest {
            var armed = true
            launch { btRefreshDisarmWindow { armed = it } }
            advanceTimeBy(3)
            // ... a refresh completes here, in single-digit ms, offline.
            assertFalse(armed)
            advanceTimeBy(BT_REFRESH_DISARM_MS - 4)
            assertFalse(armed)
            advanceUntilIdle()
            assertTrue(armed)
        }
    }

    @Test
    fun `a cancelled screen cannot strand the gesture in the wrong place`() {
        runTest {
            var armed = true
            val run = async { btRefreshDisarmWindow { armed = it } }
            advanceTimeBy(50)
            assertFalse(armed)
            run.cancel(CancellationException("screen left"))
            advanceUntilIdle()
            assertTrue("the finally must hand the gesture back", armed)
        }
    }

    @Test
    fun `the tunables are the numbers the owner asked for`() {
        assertEquals(600L, BT_REFRESH_DISARM_MS)
        assertEquals(500L, BT_SHEET_HINT_MS)
        // The hint has to be gone well before the gesture re-arms, or it would
        // still be on screen saying something that had stopped being true.
        assertTrue(BT_SHEET_HINT_MS < BT_REFRESH_DISARM_MS)
    }
}
