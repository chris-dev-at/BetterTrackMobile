package at.bettertrack.app.ui.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's first-run steps must be the web's first-run steps.
 *
 * Two clients now render the same wizard, and the only thing that makes
 * "step 3 of 7" mean one thing rather than two is that both read the same
 * ordered list of ids. The web's is
 * `apps/web/src/user/firstrun/stepMeta.ts` → `FIRST_RUN_STEP_META`; this pins
 * the app's against a literal copy of it, so a reorder or rename on either side
 * has to be a deliberate, visible edit here rather than a silent divergence
 * nobody notices until a support conversation goes wrong.
 */
class FirstRunStepRegistryTest {

    /** Literal transcription of `FIRST_RUN_STEP_META`'s ids, in order. */
    private val webOrder = listOf(
        "profile",
        "verifyEmail",
        "security",
        "preferences",
        "tax",
        "publicProfile",
        "done",
    )

    @Test
    fun `the ids and their order match the web exactly`() {
        assertEquals(webOrder, FIRST_RUN_STEPS.map { it.id.wireId })
    }

    @Test
    fun `there are seven steps and no duplicates`() {
        assertEquals(7, FIRST_RUN_STEPS.size)
        assertEquals(FIRST_RUN_STEPS.size, FIRST_RUN_STEPS.map { it.id }.toSet().size)
    }

    @Test
    fun `every declared step id is in the registry`() {
        // Adding an id to the enum without a row would leave a step the frame can
        // never show — and, worse, a `when` branch that looks handled.
        assertEquals(FirstRunStepId.entries.toSet(), FIRST_RUN_STEPS.map { it.id }.toSet())
    }

    @Test
    fun `only the last step is terminal`() {
        // The terminal flag drives two things: no "Do this later", and the action
        // that calls POST /auth/first-run/complete. A second terminal step would
        // mean completing setup from the middle of it.
        assertEquals(listOf(FirstRunStepId.DONE), FIRST_RUN_STEPS.filter { it.terminal }.map { it.id })
        assertTrue(FIRST_RUN_STEPS.last().terminal)
    }

    @Test
    fun `every step carries a label and a question`() {
        FIRST_RUN_STEPS.forEach { step ->
            assertTrue("${step.id} has no label", step.label != 0)
            assertTrue("${step.id} has no title", step.title != 0)
        }
    }

    @Test
    fun `the summary lists every step except the terminal one`() {
        // What the Done step renders. Six rows, in the same order.
        val summarised = FIRST_RUN_STEPS.filterNot { it.terminal }.map { it.id.wireId }
        assertEquals(webOrder.dropLast(1), summarised)
        assertFalse(summarised.contains("done"))
    }
}
