package at.bettertrack.app.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P0-2: the debug "Clean up test data" button archives a portfolio on the
 * owner's LIVE production account. It used to arm on "something is selected"
 * over a silent first-live-portfolio default. This pins the rule that replaced
 * it — explicit selection + exact name + typed confirmation.
 */
class CleanupArmingTest {

    private val confirm = TEST_PORTFOLIO_NAME

    @Test
    fun `arms only for the exactly named throwaway with a typed confirmation`() {
        assertTrue(cleanupArmed(TEST_PORTFOLIO_NAME, confirm))
    }

    @Test
    fun `nothing selected never arms`() {
        assertFalse(cleanupArmed(null, confirm))
    }

    @Test
    fun `a real portfolio never arms, however it is confirmed`() {
        assertFalse(cleanupArmed("Main", confirm))
        assertFalse(cleanupArmed("Main", "Main"))
        assertFalse(cleanupArmed("", confirm))
    }

    @Test
    fun `near-miss names are not the throwaway`() {
        assertFalse(cleanupArmed("ZZ App Test 2", confirm))
        assertFalse(cleanupArmed("zz app test", confirm))
        assertFalse(cleanupArmed(" ZZ App Test", confirm))
        assertFalse(cleanupArmed("ZZ App Testing", confirm))
    }

    @Test
    fun `the confirmation must match exactly, whitespace aside`() {
        assertTrue(cleanupArmed(TEST_PORTFOLIO_NAME, "  $TEST_PORTFOLIO_NAME  "))
        assertFalse(cleanupArmed(TEST_PORTFOLIO_NAME, ""))
        assertFalse(cleanupArmed(TEST_PORTFOLIO_NAME, "zz app test"))
        assertFalse(cleanupArmed(TEST_PORTFOLIO_NAME, "ZZ App"))
        assertFalse(cleanupArmed(TEST_PORTFOLIO_NAME, "delete"))
    }
}
