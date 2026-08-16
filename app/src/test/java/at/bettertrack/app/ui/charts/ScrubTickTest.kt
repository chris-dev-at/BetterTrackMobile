package at.bettertrack.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub's haptic cadence (owner order 2026-08-10: *"every point you at gives
 * another haptic feedback so it feels cool like on the samsung watches with the
 * fake bezel spin"*).
 *
 * The whole risk in that feature is the difference between a row of detents and
 * a continuous buzz, and that difference is entirely in [nextScrubTick]. It takes
 * the clock as a parameter precisely so the cadence can be pinned here with a
 * fake one instead of being judged by holding a phone.
 */
class ScrubTickTest {

    @Test
    fun `the first touch of a drag always ticks`() {
        val tick = nextScrubTick(prev = null, index = 7, x = 120f, nowMs = 1_000)
        assertNotNull(tick)
        assertEquals(7, tick!!.index)
        assertEquals(1_000L, tick.atMs)
    }

    @Test
    fun `staying on the same point does not tick again`() {
        val first = nextScrubTick(null, index = 3, x = 50f, nowMs = 0)!!
        assertNull(nextScrubTick(first, index = 3, x = 58f, nowMs = 500))
    }

    @Test
    fun `crossing to the next point ticks once it is allowed to`() {
        val first = nextScrubTick(null, index = 3, x = 50f, nowMs = 0)!!
        val second = nextScrubTick(first, index = 4, x = 70f, nowMs = SCRUB_TICK_MIN_INTERVAL_MS)
        assertNotNull(second)
        assertEquals(4, second!!.index)
    }

    @Test
    fun `a dense series ticks like detents, not like a buzz`() {
        // 400 points under a 400px canvas: one point per pixel. A fast drag
        // crosses one per millisecond; the motor must not be asked 400 times.
        var state = nextScrubTick(null, index = 0, x = 0f, nowMs = 0)
        var ticks = 1
        for (ms in 1..400L) {
            val next = nextScrubTick(state, index = ms.toInt(), x = ms.toFloat(), nowMs = ms)
            if (next != null) {
                state = next
                ticks++
            }
        }
        // 400ms of drag at the floor: one tick per interval, never one per point.
        // At the owner's faster 30ms cadence (2026-08-17) that is 14, not 400.
        assertEquals(400 / SCRUB_TICK_MIN_INTERVAL_MS + 1, ticks.toLong())
        assertEquals(14, ticks)
    }

    @Test
    fun `the cadence stays inside the owner's fast-but-not-a-buzz band`() {
        // Pinned as a NUMBER as well as a formula, because every assertion above
        // is written against the constant and would follow it silently to any
        // value at all — including back to a cadence he has already rejected as
        // too slow, or down to one that asks the motor for a new attack faster
        // than it can produce one.
        assertTrue(
            "scrub cadence out of band: ${SCRUB_TICK_MIN_INTERVAL_MS}ms",
            SCRUB_TICK_MIN_INTERVAL_MS in 20L..35L,
        )
    }

    @Test
    fun `a suppressed crossing does not move the clock reference`() {
        // Otherwise the throttle would measure from the last CROSSING rather than
        // the last tick, and a stream of suppressed crossings would hold it off
        // forever.
        val first = nextScrubTick(null, index = 0, x = 0f, nowMs = 0)!!
        val suppressed = nextScrubTick(first, index = 1, x = 10f, nowMs = 10)
        assertNull(suppressed)
        val allowed = nextScrubTick(first, index = 2, x = 20f, nowMs = SCRUB_TICK_MIN_INTERVAL_MS)
        assertNotNull(allowed)
    }

    @Test
    fun `a finger wobbling over a cell boundary stays silent`() {
        // Same index flip-flop, plenty of time, but almost no travel: a real
        // bezel does not click when your hand is still, so neither does this.
        var state = nextScrubTick(null, index = 5, x = 100f, nowMs = 0)!!
        var ticks = 0
        var now = 0L
        repeat(20) { i ->
            now += 100
            val next = nextScrubTick(state, index = if (i % 2 == 0) 6 else 5, x = 100f + (i % 2), now)
            if (next != null) {
                state = next
                ticks++
            }
        }
        assertEquals(0, ticks)
    }

    @Test
    fun `travelling far enough with time to spare ticks every point`() {
        var state = nextScrubTick(null, index = 0, x = 0f, nowMs = 0)!!
        var ticks = 1
        repeat(5) { i ->
            val next = nextScrubTick(
                state,
                index = i + 1,
                x = (i + 1) * 40f,
                nowMs = (i + 1) * (SCRUB_TICK_MIN_INTERVAL_MS + 5),
            )
            if (next != null) {
                state = next
                ticks++
            }
        }
        assertEquals(6, ticks)
    }
}
