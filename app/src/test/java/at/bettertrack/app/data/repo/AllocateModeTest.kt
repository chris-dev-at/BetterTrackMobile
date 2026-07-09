package at.bettertrack.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget-calculator buying mode (item 3) must mirror the web app's
 * `AllocateRequest` exactly: the wire values, the whole default, and the rule
 * that `step` travels only in fractional mode and `atLeastOneShare` only in
 * whole mode (verified against the live openapi `AllocateRequest` schema).
 */
class AllocateModeTest {

    @Test
    fun `wire values match the contract enum`() {
        assertEquals("whole", AllocateMode.WHOLE.wire)
        assertEquals("fractional", AllocateMode.FRACTIONAL.wire)
    }

    @Test
    fun `default mode is whole (matches web calculator default)`() {
        assertEquals(AllocateMode.WHOLE, AllocateMode.DEFAULT)
    }

    @Test
    fun `whole mode sends atLeastOneShare and never a step`() {
        val req = buildAllocateRequest(
            budgetEur = 1000.0,
            mode = AllocateMode.WHOLE,
            atLeastOneShare = true,
            step = 0.0001,
        )
        assertEquals("whole", req.mode)
        assertEquals(1000.0, req.budgetEur, 0.0)
        assertTrue(req.atLeastOneShare)
        assertNull("step is ignored in whole mode", req.step)
    }

    @Test
    fun `whole mode without the toggle sends atLeastOneShare false`() {
        val req = buildAllocateRequest(1000.0, AllocateMode.WHOLE, atLeastOneShare = false, step = null)
        assertFalse(req.atLeastOneShare)
        assertNull(req.step)
    }

    @Test
    fun `fractional mode forwards the step and drops atLeastOneShare`() {
        val req = buildAllocateRequest(
            budgetEur = 500.0,
            mode = AllocateMode.FRACTIONAL,
            atLeastOneShare = true, // must be dropped in fractional mode
            step = 0.0001,
        )
        assertEquals("fractional", req.mode)
        assertEquals(0.0001, req.step!!, 0.0)
        assertFalse("atLeastOneShare is ignored in fractional mode", req.atLeastOneShare)
    }

    @Test
    fun `fractional mode with no step omits it (server default)`() {
        val req = buildAllocateRequest(500.0, AllocateMode.FRACTIONAL, atLeastOneShare = false, step = null)
        assertEquals("fractional", req.mode)
        assertNull(req.step)
    }
}
