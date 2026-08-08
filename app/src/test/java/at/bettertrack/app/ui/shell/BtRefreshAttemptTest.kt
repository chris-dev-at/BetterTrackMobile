package at.bettertrack.app.ui.shell

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.asMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The pull-to-refresh failure state machine, on a virtual clock.
 *
 * ## Why this file exists at all
 *
 * The owner reported the five refresh sheets as *"in offline mode it still
 * doesn't work"*, and the reason turned out not to be the refresh — it was the
 * **indicator**, and through it the sheet's dismiss gesture. Material3's
 * `PullToRefreshModifierNode.consumeAvailableOffset` returns zero while
 * `isRefreshing` is true, so the pull passes straight up to [BtSheet]'s
 * connection: an indicator that is up when it should not be turns every
 * subsequent pull into a sheet dismissal, and an indicator that never appears
 * makes the gesture read as having done nothing at all.
 *
 * So the three guarantees below are gesture guarantees, not data guarantees, and
 * they are the ones worth pinning without a device: a floor so the attempt is
 * visible, a ceiling so it cannot hang, and a `finally` so nothing can strand it.
 *
 * The clock is virtual throughout — [runTest] advances it — so a 25-second
 * timeout costs the suite nothing.
 */
class BtRefreshAttemptTest {

    // ── No floor: the indicator shows the attempt, and only the attempt ───────

    @Test
    fun `the indicator is up for exactly as long as the work takes`() = runTest {
        // The 320 ms minimum-visible floor is retired (owner, 2026-08-08). It made
        // a fast refresh look slower than it was, and it was quietly what decided
        // how long a second pull belonged to the sheet — a routing question, now
        // answered explicitly and on its own clock (see [BtRefreshRoutingTest]).
        val refreshing = MutableStateFlow(false)
        val work = 40L
        val run = async {
            btRefreshAttempt(refreshing) {
                delay(work)
                "landed"
            }
        }
        advanceTimeBy(work - 1)
        assertTrue("the indicator is up while the work runs", refreshing.value)
        advanceUntilIdle()
        assertEquals("landed", run.await())
        assertFalse(refreshing.value)
        assertEquals("no time beyond the attempt's own", work, testScheduler.currentTime)
    }

    @Test
    fun `a refresh that fails in no time costs no time at all`() = runTest {
        // The offline fail-fast case. The flag still rises and falls — a collector
        // may conflate the pair, which is exactly why the pull's feedback is no
        // longer this flag's job — but nothing is held open to fake an attempt.
        val refreshing = MutableStateFlow(false)
        val run = async { btRefreshAttempt(refreshing) { "instant failure" } }
        advanceUntilIdle()
        assertEquals("instant failure", run.await())
        assertFalse(refreshing.value)
        assertEquals("an instant failure must be instant", 0L, testScheduler.currentTime)
    }

    // ── The ceiling: a wedged request cannot hold the gesture ────────────────

    @Test
    fun `an attempt that never answers is cut off at the ceiling`() = runTest {
        // The "fails slow" case, and the one that mattered most: the authed
        // OkHttp client sets no callTimeout, so against a host that accepts
        // nothing and refuses nothing a refresh could hold the indicator for the
        // better part of a minute — with a pull-to-dismiss armed the whole time.
        val refreshing = MutableStateFlow(false)
        val run = async { btRefreshAttempt(refreshing) { awaitCancellation() } }

        advanceTimeBy(BT_REFRESH_TIMEOUT_MS - 1)
        assertTrue("still trying, indicator still up", refreshing.value)

        advanceUntilIdle()
        assertNull("the ceiling reports itself as null", run.await())
        assertFalse("and the indicator retires with it", refreshing.value)
        assertEquals(BT_REFRESH_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `an attempt that answers just inside the ceiling keeps its value`() = runTest {
        val refreshing = MutableStateFlow(false)
        val run = async {
            btRefreshAttempt(refreshing) {
                delay(BT_REFRESH_TIMEOUT_MS - 1)
                "just made it"
            }
        }
        advanceUntilIdle()
        assertEquals("just made it", run.await())
        assertFalse(refreshing.value)
    }

    // ── The finally: nothing may strand the indicator ────────────────────────

    @Test
    fun `a throwing attempt still retires the indicator`() = runTest {
        // The five ViewModels used to set the flag on a straight line with no
        // `finally`. A repository that threw instead of returning an Err left the
        // indicator up forever — and a stuck indicator is a sheet that dismisses
        // itself on the next pull.
        val refreshing = MutableStateFlow(false)
        val thrown = runCatching {
            btRefreshAttempt(refreshing) { throw IOException("connection reset") }
        }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertFalse("the flag must not survive the throw", refreshing.value)
    }

    @Test
    fun `a cancelled attempt still retires the indicator`() = runTest {
        // A ViewModel whose scope is cleared mid-refresh — the user left the
        // screen. The sheet may be re-entered a moment later, and it must not
        // come back with an indicator nobody can retire.
        val refreshing = MutableStateFlow(false)
        val run = launch { btRefreshAttempt(refreshing) { awaitCancellation() } }
        advanceTimeBy(321)
        assertTrue(refreshing.value)

        run.cancel(CancellationException("scope cleared"))
        advanceUntilIdle()
        assertFalse("the flag must not survive cancellation", refreshing.value)
    }

    // ── What the user is told ────────────────────────────────────────────────

    @Test
    fun `a timed-out refresh reports itself as a network failure`() {
        // Deliberately the same copy a refused connection gets. A request the app
        // gave up waiting for and a request the network refused are one event to
        // the person holding the phone; a second string for the distinction would
        // only ask them to care about it.
        val timedOut = btRefreshTimedOutMessage()
        val refused = BtApiError(httpStatus = 0, code = BtApiError.Codes.NETWORK).asMessage()
        assertEquals(refused.res, timedOut.res)
    }

    @Test
    fun `the ceiling is above any healthy refresh and below the offline worst case`() {
        // The numbers this was chosen against, pinned so a later edit to either
        // constant has to answer for the relationship rather than just the value:
        // one doomed connect is 20s and the authed client has no call timeout, so
        // two sequential doomed calls run past this — while a healthy read is
        // under two seconds and is nowhere near it.
        assertTrue("must outlast any healthy refresh", BT_REFRESH_TIMEOUT_MS > 5_000L)
        assertTrue("must cut off the offline worst case", BT_REFRESH_TIMEOUT_MS < 40_000L)
    }
}
