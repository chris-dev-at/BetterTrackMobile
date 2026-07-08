package at.bettertrack.app.data.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the app lock (spec §5): the progressive-backoff
 * schedule, the 4–6-digit PIN rule, and the AFK threshold mapping. These are the
 * security-relevant policies, so they're proven without a device.
 */
class AppLockLogicTest {

    // ── Progressive backoff (§5) ──────────────────────────────────────────────
    @Test
    fun `first four misses impose no lockout`() {
        (0..4).forEach { assertEquals("failures=$it", 0L, lockoutMillisFor(it)) }
    }

    @Test
    fun `fifth miss starts a 30s lockout`() {
        assertEquals(30_000L, lockoutMillisFor(5))
    }

    @Test
    fun `lockout escalates then caps at five minutes`() {
        assertEquals(60_000L, lockoutMillisFor(6))
        assertEquals(120_000L, lockoutMillisFor(7))
        assertEquals(300_000L, lockoutMillisFor(8))
        // Capped — further misses do not grow beyond 5 minutes.
        assertEquals(300_000L, lockoutMillisFor(9))
        assertEquals(300_000L, lockoutMillisFor(42))
    }

    @Test
    fun `backoff is monotonically non-decreasing`() {
        var prev = 0L
        for (n in 0..20) {
            val cur = lockoutMillisFor(n)
            assertTrue("n=$n went backwards", cur >= prev)
            prev = cur
        }
    }

    // ── PIN format (§5: 4–6 digits) ───────────────────────────────────────────
    @Test
    fun `valid pins are four to six digits`() {
        assertTrue(isValidPinFormat("0000"))
        assertTrue(isValidPinFormat("12345"))
        assertTrue(isValidPinFormat("987654"))
    }

    @Test
    fun `pins outside four to six digits are rejected`() {
        assertFalse(isValidPinFormat(""))
        assertFalse(isValidPinFormat("123"))
        assertFalse(isValidPinFormat("1234567"))
    }

    @Test
    fun `non-digit pins are rejected`() {
        assertFalse(isValidPinFormat("12a4"))
        assertFalse(isValidPinFormat("12 4"))
        assertFalse(isValidPinFormat("１２３４")) // full-width digits are not ASCII 0-9
    }

    // ── AFK threshold mapping ─────────────────────────────────────────────────
    @Test
    fun `default threshold is one minute`() {
        assertEquals(AfkThreshold.OneMinute, AfkThreshold.Default)
        assertEquals(60_000L, AfkThreshold.OneMinute.millis)
    }

    @Test
    fun `immediately is a zero budget`() {
        assertEquals(0, AfkThreshold.Immediately.minutes)
        assertEquals(0L, AfkThreshold.Immediately.millis)
    }

    @Test
    fun `fromMinutes maps known values and falls back to default`() {
        assertEquals(AfkThreshold.Immediately, AfkThreshold.fromMinutes(0))
        assertEquals(AfkThreshold.OneMinute, AfkThreshold.fromMinutes(1))
        assertEquals(AfkThreshold.FiveMinutes, AfkThreshold.fromMinutes(5))
        assertEquals(AfkThreshold.FifteenMinutes, AfkThreshold.fromMinutes(15))
        // Anything unrecognised (e.g. a value from a newer build) → the default.
        assertEquals(AfkThreshold.Default, AfkThreshold.fromMinutes(3))
        assertEquals(AfkThreshold.Default, AfkThreshold.fromMinutes(-7))
    }

    @Test
    fun `fifteen minute threshold has the right budget`() {
        assertEquals(900_000L, AfkThreshold.FifteenMinutes.millis)
    }
}
