package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.ui.vault.custody.pvFormatCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §12 lockout ladder as pure arithmetic.
 *
 * *"Failures escalate a client-side delay (5 wrong → 30 s, doubling, capped at
 * 5 min) — there is no server lockout because the server is not involved."*
 *
 * Two things are worth a test rather than a read-through. The **doubling** is
 * easy to write as a hand-rolled table that quietly stops doubling (the app
 * lock's own `lockoutMillisFor` jumps 120 s → 300 s and skips the 240 s rung),
 * and the **cap** must clamp rather than overflow: a schedule that shifts a
 * `Long` far enough wraps to a negative delay, which reads as "no lockout" at
 * exactly the attempt count where the lockout matters most.
 */
class PvLockoutScheduleTest {

    /** The schedule, spelled out. Every row is a claim §12 makes. */
    private val table = listOf(
        0 to 0L,
        1 to 0L,
        2 to 0L,
        3 to 0L,
        4 to 0L,
        5 to 30_000L,
        6 to 60_000L,
        7 to 120_000L,
        8 to 240_000L,
        9 to 300_000L,
        10 to 300_000L,
        25 to 300_000L,
    )

    @Test
    fun `the ladder matches the spec row for row`() {
        table.forEach { (failures, expected) ->
            assertEquals("after $failures wrong attempts", expected, pvLockoutMillisFor(failures))
        }
    }

    @Test
    fun `the first four attempts are free and the fifth is not`() {
        assertEquals(0L, pvLockoutMillisFor(PV_LOCKOUT_FREE_ATTEMPTS - 1))
        assertEquals(PV_LOCKOUT_BASE_MS, pvLockoutMillisFor(PV_LOCKOUT_FREE_ATTEMPTS))
    }

    @Test
    fun `each rung doubles until the cap and never exceeds it`() {
        var previous = pvLockoutMillisFor(PV_LOCKOUT_FREE_ATTEMPTS)
        (PV_LOCKOUT_FREE_ATTEMPTS + 1..40).forEach { failures ->
            val current = pvLockoutMillisFor(failures)
            assertTrue("rung $failures went backwards", current >= previous)
            assertTrue("rung $failures blew the cap", current <= PV_LOCKOUT_CAP_MS)
            if (previous < PV_LOCKOUT_CAP_MS) {
                assertEquals(
                    "rung $failures stopped doubling before the cap",
                    minOf(previous * 2, PV_LOCKOUT_CAP_MS),
                    current,
                )
            }
            previous = current
        }
    }

    @Test
    fun `an absurd failure count still yields a sane delay`() {
        // The overflow guard. Without the clamped shift, 30 s ≪ 64 wraps to 0
        // and the ladder silently disappears.
        listOf(64, 1_000, Int.MAX_VALUE).forEach { failures ->
            assertEquals("failures=$failures", PV_LOCKOUT_CAP_MS, pvLockoutMillisFor(failures))
        }
    }

    @Test
    fun `a negative count is treated as no failures, not as a negative delay`() {
        assertEquals(0L, pvLockoutMillisFor(-1))
        assertEquals(0L, pvLockoutMillisFor(Int.MIN_VALUE))
    }

    @Test
    fun `the countdown label rounds up so it can reach zero`() {
        // The real formatter the unlock sheet renders, not a copy of it. A
        // truncating one would show "0:00" for the last 999 ms, which reads as a
        // stuck prompt; rounded up, the label says 0:00 only once the wait is
        // actually over.
        assertEquals("0:30", pvFormatCountdown(30_000L))
        assertEquals("0:30", pvFormatCountdown(29_001L))
        assertEquals("0:29", pvFormatCountdown(29_000L))
        assertEquals("0:01", pvFormatCountdown(1L))
        assertEquals("0:00", pvFormatCountdown(0L))
        assertEquals("5:00", pvFormatCountdown(PV_LOCKOUT_CAP_MS))
        assertEquals("1:00", pvFormatCountdown(60_000L))
        assertEquals("0:00", pvFormatCountdown(-5L))
    }
}
