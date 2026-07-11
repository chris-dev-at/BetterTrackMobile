package at.bettertrack.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure gating for FCM device-token registration ([registrationAction]): the
 * decision the [PushTokenManager] executes on every obtained/rotated token and on
 * login. Bearer-gated, defer-until-login, and a stub kill-switch — no Android deps.
 */
class PushRegistrationLogicTest {

    @Test fun `logged in with a token registers now`() {
        assertEquals(
            RegistrationAction.Register,
            registrationAction(hasToken = true, loggedIn = true, stubbed = false),
        )
    }

    @Test fun `token obtained while logged out is deferred to next login`() {
        assertEquals(
            RegistrationAction.DeferUntilLogin,
            registrationAction(hasToken = true, loggedIn = false, stubbed = false),
        )
    }

    @Test fun `no token means nothing to do`() {
        assertEquals(
            RegistrationAction.Skip,
            registrationAction(hasToken = false, loggedIn = true, stubbed = false),
        )
    }

    @Test fun `the stub kill-switch wins over everything`() {
        assertEquals(
            RegistrationAction.Skip,
            registrationAction(hasToken = true, loggedIn = true, stubbed = true),
        )
    }
}
