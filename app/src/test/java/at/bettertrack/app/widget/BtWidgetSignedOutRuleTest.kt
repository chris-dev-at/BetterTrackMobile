package at.bettertrack.app.widget

import at.bettertrack.app.data.auth.AuthTokens
import at.bettertrack.app.data.auth.TokenRead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sign in" on a home-screen widget means signed out FOR REAL — storage holds no
 * session because a definitive verdict removed it. Never a refresh that is
 * failing, backing off or replaying (the pair stays on disk), never a Keystore
 * that will not open right now.
 */
class BtWidgetSignedOutRuleTest {

    private val present = TokenRead.Present(AuthTokens("a", "r", "s", Long.MAX_VALUE))

    @Test
    fun `only an empty store with no vault shows the sign-in card`() {
        assertTrue(btWidgetShowsSignedOut(TokenRead.None, holdsVault = false))
    }

    @Test
    fun `a session whose refresh is struggling keeps its figures`() {
        assertFalse(btWidgetShowsSignedOut(present, holdsVault = false))
    }

    @Test
    fun `an unreadable Keystore is not a sign-out`() {
        // This used to be "login needed": hasTokens() is false for it.
        assertFalse(btWidgetShowsSignedOut(TokenRead.Unavailable("KeyStoreException"), holdsVault = false))
    }

    @Test
    fun `a local vault is a session of its own`() {
        assertFalse(btWidgetShowsSignedOut(TokenRead.None, holdsVault = true))
    }
}
