package at.bettertrack.app.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 P0-5: a failed refresh must be visible. These pin the state mapping the
 * Transactions and Watchlist view models drive the inline notice from.
 */
class RefreshNoticeStateTest {

    @Test
    fun `a fresh screen shows nothing`() {
        assertFalse(RefreshNoticeState().visible(isOnline = true))
        assertFalse(RefreshNoticeState().visible(isOnline = false))
    }

    @Test
    fun `a failed refresh while online shows the notice`() {
        val s = RefreshNoticeState().onFailure()
        assertTrue(s.visible(isOnline = true))
    }

    @Test
    fun `offline suppresses it because the offline banner already says it`() {
        val s = RefreshNoticeState().onFailure()
        assertFalse(s.visible(isOnline = false))
    }

    @Test
    fun `a successful refresh clears it`() {
        val s = RefreshNoticeState().onFailure().onSuccess()
        assertFalse(s.visible(isOnline = true))
    }

    @Test
    fun `dismissing hides it without pretending the data is fresh`() {
        val s = RefreshNoticeState().onFailure().onDismiss()
        assertFalse(s.visible(isOnline = true))
        assertTrue("the failure itself is still recorded", s.failed)
    }

    @Test
    fun `a new failure re-arms a dismissed notice`() {
        // The user dismissed a message about an EARLIER attempt; the next failed
        // pull-to-refresh is new information and must speak up again.
        val s = RefreshNoticeState().onFailure().onDismiss().onFailure()
        assertTrue(s.visible(isOnline = true))
    }

    @Test
    fun `success after a dismissal resets both flags`() {
        val s = RefreshNoticeState().onFailure().onDismiss().onSuccess()
        assertFalse(s.failed)
        assertFalse(s.dismissed)
        assertFalse(s.visible(isOnline = true))
    }
}
