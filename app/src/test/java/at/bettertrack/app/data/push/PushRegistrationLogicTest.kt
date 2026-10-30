package at.bettertrack.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure gating for FCM device-token registration ([registrationAction]): the
 * decision the [PushTokenManager] executes on every obtained/rotated token and on
 * login. Bearer-gated and defer-until-login — no Android deps.
 */
class PushRegistrationLogicTest {

    @Test fun `logged in with a token registers now`() {
        assertEquals(
            RegistrationAction.Register,
            registrationAction(hasToken = true, loggedIn = true),
        )
    }

    @Test fun `token obtained while logged out is deferred to next login`() {
        assertEquals(
            RegistrationAction.DeferUntilLogin,
            registrationAction(hasToken = true, loggedIn = false),
        )
    }

    @Test fun `no token means nothing to do`() {
        assertEquals(
            RegistrationAction.Skip,
            registrationAction(hasToken = false, loggedIn = true),
        )
    }
}
