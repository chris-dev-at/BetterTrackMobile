package at.bettertrack.app.ui.market

import at.bettertrack.app.data.api.dto.EarningsEventDto
import at.bettertrack.app.data.api.dto.EarningsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The earnings graphic's arithmetic (owner order 2026-08-10: *"more info for
 * earnings … nice graphics"*).
 *
 * The chart draws EPS estimate against EPS actual because that is the entirety of
 * what `GET /assets/{id}/intel/earnings` serves — there is no revenue and no
 * statement data anywhere in the platform, so there is nothing else it could
 * honestly draw. What IS worth pinning is which periods make it onto the axis and
 * how the y-window is chosen, since both are places where a chart can quietly
 * start lying about a company's record.
 */
class EarningsChartTest {

    private val day = 86_400_000L

    /** Bars are built off a fake clock: the parser itself has its own tests. */
    private fun time(iso: String?): Long? = iso?.toLongOrNull()

    private fun event(t: Long, estimate: Double?, actual: Double?) =
        EarningsEventDto(date = t.toString(), epsEstimate = estimate, epsActual = actual)

    private fun bars(response: EarningsResponse, cap: Int = EARNINGS_CHART_CAP) =
        earningsChartBars(response, cap, ::time)

    @Test
    fun `reports come out oldest first with the next one last`() {
        val result = bars(
            EarningsResponse(
                available = true,
                recent = listOf(event(3 * day, 1.0, 1.2), event(day, 0.8, 0.7)),
                next = event(10 * day, 1.4, null),
            ),
        )
        assertEquals(listOf(day, 3 * day, 10 * day), result.map { it.timeMs })
        assertTrue(result.last().upcoming)
        assertFalse(result.first().upcoming)
    }

    @Test
    fun `an undated report has no place on a time axis`() {
        val result = bars(EarningsResponse(recent = listOf(EarningsEventDto(date = null, epsEstimate = 1.0))))
        assertEquals(0, result.size)
    }

    @Test
    fun `a period with neither number is dropped rather than drawn empty`() {
        val result = bars(
            EarningsResponse(
                recent = listOf(event(day, null, null), event(2 * day, 1.0, 1.0)),
            ),
        )
        assertEquals(listOf(2 * day), result.map { it.timeMs })
    }

    @Test
    fun `a non-finite provider number is treated as absent`() {
        val result = bars(
            EarningsResponse(recent = listOf(event(day, Double.NaN, 1.1))),
        )
        assertEquals(1, result.size)
        assertEquals(null, result.single().estimate)
        assertEquals(1.1, result.single().actual!!, 0.0001)
    }

    @Test
    fun `the next report is not drawn twice when history already has it`() {
        val result = bars(
            EarningsResponse(
                recent = listOf(event(5 * day, 1.0, 1.1)),
                next = event(5 * day, 1.0, null),
            ),
        )
        assertEquals(1, result.size)
        assertFalse(result.single().upcoming)
    }

    @Test
    fun `the cap keeps the most recent periods, not the oldest`() {
        val response = EarningsResponse(
            recent = (1..9).map { event(it * day, it.toDouble(), it.toDouble()) },
        )
        val result = bars(response, cap = 4)
        assertEquals(listOf(6 * day, 7 * day, 8 * day, 9 * day), result.map { it.timeMs })
    }

    @Test
    fun `the y window always contains zero so bar lengths are comparable`() {
        val positive = earningsChartScale(
            listOf(EarningsBar(0, 2.0, 2.4, false), EarningsBar(day, 3.0, 3.1, false)),
        )
        assertEquals(0.0, positive.start, 0.0001)
        assertTrue(positive.endInclusive > 3.1)

        val negative = earningsChartScale(listOf(EarningsBar(0, -1.0, -1.4, false)))
        assertTrue(negative.start < -1.4)
        assertEquals(0.0, negative.endInclusive, 0.0001)
    }

    @Test
    fun `a loss-making quarter keeps its sign in the window`() {
        val scale = earningsChartScale(
            listOf(EarningsBar(0, 1.0, -0.5, false), EarningsBar(day, 1.0, 1.2, false)),
        )
        assertTrue(scale.start < -0.5)
        assertTrue(scale.endInclusive > 1.2)
    }

    @Test
    fun `an all-zero series still has a drawable window`() {
        val scale = earningsChartScale(listOf(EarningsBar(0, 0.0, 0.0, false)))
        assertTrue(scale.start < scale.endInclusive)
    }

    @Test
    fun `one period is a number, not a trend`() {
        assertFalse(earningsChartWorthDrawing(emptyList()))
        assertFalse(earningsChartWorthDrawing(listOf(EarningsBar(0, 1.0, 1.1, false))))
        assertTrue(
            earningsChartWorthDrawing(
                listOf(EarningsBar(0, 1.0, 1.1, false), EarningsBar(day, 1.0, null, true)),
            ),
        )
    }
}
