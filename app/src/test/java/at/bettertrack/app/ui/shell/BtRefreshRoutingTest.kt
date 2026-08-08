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
        assertEquals(1100L, BT_REFRESH_DISARM_MS)
        assertEquals(500L, BT_SHEET_HINT_MS)
        // The hint has to be gone well before the gesture re-arms, or it would
        // still be on screen saying something that had stopped being true.
        assertTrue(BT_SHEET_HINT_MS < BT_REFRESH_DISARM_MS)
        // Raised on 2026-08-09. The window is measured from the trigger, which
        // fires while the FIRST pull is still finishing, so a good part of it is
        // always spent on a finger that has not let go yet. It has to be long
        // enough that the hand can reset and start a second pull inside it.
        assertTrue("the window must leave room to start a second pull", BT_REFRESH_DISARM_MS >= 1000L)
    }

    // ── The latch: a gesture ends the way it began ──────────────────────────

    @Test
    fun `an unlatched pull tracks the live routing`() {
        // With no finger down there is nothing to protect, so the latch is out of
        // the way entirely and the live rule decides.
        assertEquals(BtPullOwner.REFRESH, btPullOwnerLatched(null, armed = true, isRefreshing = false))
        assertEquals(BtPullOwner.SHEET, btPullOwnerLatched(null, armed = false, isRefreshing = false))
        assertEquals(BtPullOwner.SHEET, btPullOwnerLatched(null, armed = true, isRefreshing = true))
    }

    @Test
    fun `latching changes nothing on the frame it happens`() {
        // The safety property the whole design rests on: because the value being
        // frozen is the live one, freezing it can never move the routing. If this
        // failed, the down event itself would be able to flip the gesture.
        listOf(true, false).forEach { armed ->
            listOf(true, false).forEach { refreshing ->
                val live = btPullOwner(armed, refreshing)
                assertEquals(
                    "armed=$armed refreshing=$refreshing",
                    live,
                    btPullOwnerLatched(live, armed, refreshing),
                )
            }
        }
    }

    @Test
    fun `a dismissal that began while disarmed stays a dismissal when the timer expires`() {
        // The owner's bug, as a sequence. Finger down inside the window; the
        // window ends under the finger; the pull must not become a refresh.
        val held = btPullOwner(armed = false, isRefreshing = false)
        assertEquals(BtPullOwner.SHEET, held)
        assertEquals(
            "the in-flight pull must not convert",
            BtPullOwner.SHEET,
            btPullOwnerLatched(held, armed = true, isRefreshing = false),
        )
        // ...and only once the finger lifts does refresh get the gesture back.
        assertEquals(
            BtPullOwner.REFRESH,
            btPullOwnerLatched(null, armed = true, isRefreshing = false),
        )
    }

    @Test
    fun `a refresh pull that began after expiry stays a refresh`() {
        // The mirror. A refresh starting mid-gesture must not yank the pull away
        // from the refresh that its own first frame asked for.
        val held = btPullOwner(armed = true, isRefreshing = false)
        assertEquals(BtPullOwner.REFRESH, held)
        assertEquals(
            BtPullOwner.REFRESH,
            btPullOwnerLatched(held, armed = false, isRefreshing = true),
        )
    }

    @Test
    fun `the timer expiring mid-gesture cannot reach the gesture, on the real clock`() {
        runTest {
            var armed = true
            launch { btRefreshDisarmWindow { armed = it } }
            advanceTimeBy(1)
            assertFalse(armed)

            // The finger goes down late in the window — the exact case the owner
            // hit, and the one the device pass reproduces by hand.
            advanceTimeBy(BT_REFRESH_DISARM_MS - 50)
            val held = btPullOwner(armed, isRefreshing = false)
            assertEquals(BtPullOwner.SHEET, held)

            // It expires while the finger is still down and still dragging.
            advanceUntilIdle()
            assertTrue("the window really did expire", armed)
            assertEquals(
                "the drag in progress must still belong to the sheet",
                BtPullOwner.SHEET,
                btPullOwnerLatched(held, armed, isRefreshing = false),
            )
        }
    }
}
