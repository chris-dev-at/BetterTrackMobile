package at.bettertrack.app.ui.workboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Workbench "Needs you" selection rule (R-arc R2 §3).
 *
 * The block is the screen's whole claim to leading with what is actionable, so
 * the two ways it can lie are what these tests pin: showing the wrong things
 * when the cap bites, and implying it is showing everything when it is not.
 */
class WorkbenchLeadLogicTest {

    @Test
    fun `nothing waiting means an empty plan`() {
        val plan = needsYouPlan(emptyList<String>(), emptyList<String>(), max = 3)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.hidden)
    }

    @Test
    fun `alerts take the slots before ideas`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1", "a2", "a3"),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        // A fired alert is about money moving now; an unwritten thesis has been
        // waiting for weeks. If the cap forces a choice it is not a close call.
        assertEquals(listOf("a1", "a2", "a3"), plan.alerts)
        assertEquals(emptyList<String>(), plan.ideas)
    }

    @Test
    fun `ideas fill only what the alerts left`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1"),
            unfinishedIdeas = listOf("i1", "i2", "i3"),
            max = 3,
        )
        assertEquals(listOf("a1"), plan.alerts)
        assertEquals(listOf("i1", "i2"), plan.ideas)
    }

    @Test
    fun `hidden counts what was dropped from BOTH lists`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1", "a2", "a3", "a4"),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        // 6 actionable, 3 shown — the block must own up to the other 3, not just
        // to the ideas it never got to.
        assertEquals(3, plan.hidden)
    }

    @Test
    fun `nothing is hidden when everything fits`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1"),
            unfinishedIdeas = listOf("i1"),
            max = 3,
        )
        assertEquals(0, plan.hidden)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `ideas alone still lead when no alert fired`() {
        val plan = needsYouPlan(
            triggeredAlerts = emptyList<String>(),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        assertEquals(emptyList<String>(), plan.alerts)
        assertEquals(listOf("i1", "i2"), plan.ideas)
        assertEquals(0, plan.hidden)
    }

    @Test
    fun `a zero cap hides everything rather than rendering a headless block`() {
        val plan = needsYouPlan(listOf("a1"), listOf("i1"), max = 0)
        assertTrue(plan.isEmpty)
        assertEquals(2, plan.hidden)
    }
}
