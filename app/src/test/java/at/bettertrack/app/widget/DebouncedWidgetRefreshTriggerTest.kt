package at.bettertrack.app.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing of the cash-widget trigger, on virtual time: the debounce, the
 * max-wait cap, the foreground fast path and its conflation, and the
 * never-throws contract. `now` is the test scheduler's clock, so `delay` and
 * the cap measure the same time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedWidgetRefreshTriggerTest {

    private class Rig(
        scope: TestScope,
        var foreground: Boolean = true,
        var placed: Boolean = true,
        var enqueueThrows: Boolean = false,
        var repaintGate: CompletableDeferred<Unit>? = null,
    ) {
        val passes = mutableListOf<Pair<Long, Set<String>>>()
        var repaints = 0

        val trigger = DebouncedWidgetRefreshTrigger(
            scope = scope.backgroundScope,
            now = { scope.testScheduler.currentTime },
            isForeground = { foreground },
            cashWidgetsPlaced = { placed },
            repaintCash = {
                repaints++
                repaintGate?.await()
            },
            enqueueCashPass = { ids ->
                if (enqueueThrows) error("WorkManager not initialised")
                passes += scope.testScheduler.currentTime to ids
            },
        )
    }

    @Test
    fun `a burst of edits enqueues ONE network pass, 3 s after the last, with every portfolio it touched`() =
        runTest {
            val rig = Rig(this)
            rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
            advanceTimeBy(1_000)
            rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, "p2")
            advanceTimeBy(1_000)
            rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, null)
            advanceTimeBy(2_999)
            runCurrent()
            assertTrue("nothing before the quiet period ends, was ${rig.passes}", rig.passes.isEmpty())

            advanceTimeBy(2)
            runCurrent()
            assertEquals(listOf(5_000L to setOf("p1", "p2")), rig.passes)

            // Quiet afterwards: no second pass for the same burst.
            advanceTimeBy(60_000)
            assertEquals(1, rig.passes.size)
        }

    @Test
    fun `a continuous stream cannot postpone the pass past the 10 s cap`() = runTest {
        val rig = Rig(this)
        // One write every 2 s for 25 s — the 3 s quiet period never happens.
        repeat(13) {
            rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, "p1")
            advanceTimeBy(2_000)
        }
        runCurrent()
        assertTrue("first pass by the cap, was ${rig.passes}", rig.passes.first().first <= 10_000L)
        assertTrue("the stream keeps producing passes, was ${rig.passes}", rig.passes.size >= 2)
    }

    @Test
    fun `a local write in the foreground repaints from Room at once, before the network pass`() = runTest {
        val rig = Rig(this, foreground = true)
        rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
        runCurrent()

        assertEquals(1, rig.repaints)
        assertTrue(rig.passes.isEmpty())
        advanceTimeBy(3_001)
        assertEquals(1, rig.passes.size)
    }

    @Test
    fun `in the background there is no fast path, only the network pass`() = runTest {
        val rig = Rig(this, foreground = false)
        rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
        advanceTimeBy(3_001)

        assertEquals(0, rig.repaints)
        assertEquals(listOf(3_000L to setOf("p1")), rig.passes)
    }

    @Test
    fun `a server signal never takes the fast path - Room does not have that change yet`() = runTest {
        val rig = Rig(this, foreground = true)
        rig.trigger.cashChanged(BtWidgetRefreshReason.PUSH, "p9")
        rig.trigger.cashChanged(BtWidgetRefreshReason.REALTIME, null)
        advanceTimeBy(3_001)

        assertEquals(0, rig.repaints)
        assertEquals(listOf(3_000L to setOf("p9")), rig.passes)
    }

    @Test
    fun `fast-path repaints are conflated - one running, at most one owed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(this, repaintGate = gate)
        rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, "p1")
        runCurrent()
        assertEquals(1, rig.repaints) // the first is now suspended on the gate

        repeat(5) { rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, "p1") }
        runCurrent()
        assertEquals(1, rig.repaints)

        gate.complete(Unit)
        runCurrent()
        assertEquals("five requests during one repaint cost exactly one more", 2, rig.repaints)
    }

    @Test
    fun `no cash widget placed - nothing is repainted or scheduled`() = runTest {
        val rig = Rig(this, placed = false)
        rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
        advanceTimeBy(30_000)

        assertEquals(0, rig.repaints)
        assertTrue(rig.passes.isEmpty())
    }

    @Test
    fun `investmentsChanged keeps the 45-minute cadence - it schedules nothing`() = runTest {
        val rig = Rig(this)
        rig.trigger.investmentsChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
        advanceTimeBy(30_000)

        assertEquals(0, rig.repaints)
        assertTrue(rig.passes.isEmpty())
    }

    @Test
    fun `the trigger never throws - not on a broken placed check, not on a failing enqueue`() = runTest {
        val broken = DebouncedWidgetRefreshTrigger(
            scope = backgroundScope,
            now = { testScheduler.currentTime },
            isForeground = { true },
            cashWidgetsPlaced = { error("AppWidgetManager unavailable") },
            repaintCash = {},
            enqueueCashPass = {},
        )
        broken.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1") // must not throw

        val rig = Rig(this, enqueueThrows = true)
        rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p1")
        advanceTimeBy(3_001) // the failing enqueue is swallowed inside the pass
        // ...and the trigger still works for the next burst.
        rig.enqueueThrows = false
        rig.trigger.cashChanged(BtWidgetRefreshReason.QUEUE_DRAIN, "p2")
        advanceTimeBy(3_001)
        assertEquals(setOf("p2"), rig.passes.single().second)
    }

    @Test
    fun `a write with no portfolio still schedules the pass, for the governing portfolio`() = runTest {
        val rig = Rig(this)
        rig.trigger.cashChanged(BtWidgetRefreshReason.REPOSITORY_WRITE, null)
        advanceTimeBy(3_001)

        assertEquals(listOf(3_000L to emptySet<String>()), rig.passes)
    }

    @Test
    fun `the cash kinds are wallet, budget, monthly flow and net worth - never a quote widget`() {
        assertEquals(
            setOf(BtWidgetKind.CASH_WALLET, BtWidgetKind.BUDGET, BtWidgetKind.SPENDING, BtWidgetKind.NET_WORTH),
            BT_WIDGET_CASH_KINDS,
        )
    }
}
