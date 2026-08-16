package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero delta line's honesty rule.
 *
 * The line pairs a EUR figure with a percent. For 1D those are one quantity —
 * the server computes `dayChangeEur` and `dayChangePct` together, off the same
 * holdings' prior close — so writing them as `+12,30 € (+0,8 %)` is true.
 *
 * For every other window they are NOT. The EUR comes from the net-worth series
 * (a deposit moves it by its full amount) and the percent is the platform's
 * time-weighted return (a deposit moves it by nothing). Bracketing the second
 * inside the first claims a relationship that does not exist, and on a real
 * portfolio it showed as `+3 004 € (+0,85 %)`.
 *
 * This pins the rule so nobody "tidies" the punctuation back.
 */
class DeltaLineBasisTest {

    @Test
    fun `only the day window may bracket its percent`() {
        assertTrue(samePairBasis(HistoryRange.D1))
    }

    @Test
    fun `every longer window states its two numbers separately`() {
        listOf(
            HistoryRange.W1,
            HistoryRange.M1,
            HistoryRange.M6,
            HistoryRange.Y1,
            HistoryRange.MAX,
        ).forEach { range ->
            assertFalse(
                "$range pairs a net-worth delta with a time-weighted return — they cannot be bracketed",
                samePairBasis(range),
            )
        }
    }

    // ── The EUR half is a difference of two server points, nothing more ──────

    @Test
    fun `the range delta is last minus first`() {
        val points = listOf(
            HistoryPoint(1_000L, 17_526.61),
            HistoryPoint(2_000L, 19_000.00),
            HistoryPoint(3_000L, 20_530.68),
        )
        assertEquals(20_530.68 - 17_526.61, rangeDeltaEur(points)!!, 1e-9)
    }

    @Test
    fun `a series that cannot carry a difference stays silent rather than claiming zero`() {
        assertNull(rangeDeltaEur(emptyList()))
        assertNull(rangeDeltaEur(listOf(HistoryPoint(1_000L, 100.0))))
    }

    @Test
    fun `a fallen portfolio reports a negative delta`() {
        val points = listOf(HistoryPoint(1L, 500.0), HistoryPoint(2L, 420.0))
        assertEquals(-80.0, rangeDeltaEur(points)!!, 1e-9)
    }
}
