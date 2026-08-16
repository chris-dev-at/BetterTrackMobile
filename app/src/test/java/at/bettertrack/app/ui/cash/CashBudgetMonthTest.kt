package at.bettertrack.app.ui.cash

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The budget month's future clamp (owner order 2026-08-16: *"the budget month
 * selector must NOT allow navigating into future months"*). The stepper's
 * disabled next-arrow is the visible half; [clampedBudgetMonth] is the model
 * guarantee this file pins.
 */
class CashBudgetMonthTest {

    private val now = YearMonth.of(2026, 8)

    @Test
    fun `stepping forward from the current month stays on the current month`() {
        assertEquals(now, clampedBudgetMonth(now.plusMonths(1), now))
    }

    @Test
    fun `a far-future candidate lands on the current month, not one step back`() {
        assertEquals(now, clampedBudgetMonth(YearMonth.of(2030, 1), now))
    }

    @Test
    fun `stepping forward from a past month is allowed`() {
        assertEquals(
            YearMonth.of(2026, 7),
            clampedBudgetMonth(YearMonth.of(2026, 7), now),
        )
    }

    @Test
    fun `the current month itself passes through`() {
        assertEquals(now, clampedBudgetMonth(now, now))
    }

    @Test
    fun `history stays unlimited`() {
        assertEquals(
            YearMonth.of(2019, 1),
            clampedBudgetMonth(YearMonth.of(2019, 1), now),
        )
    }

    @Test
    fun `a december to january year boundary clamps correctly`() {
        val dec = YearMonth.of(2026, 12)
        assertEquals(dec, clampedBudgetMonth(YearMonth.of(2027, 1), dec))
    }
}
