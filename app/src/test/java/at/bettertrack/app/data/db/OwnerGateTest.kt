package at.bettertrack.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The account-key wipe gate (spec §7.3): local data is keyed to the logged-in
 * account — it survives token refresh / session expiry / re-login of the same
 * user and is wiped ONLY on a detected account switch (or explicit logout,
 * which calls wipeAll directly). Identity can be unknown at login because the
 * platform has no bearer-readable identity endpoint yet.
 */
class OwnerGateTest {

    @Test
    fun `fresh database with known identity adopts the user id`() {
        val action = resolveOwnerAction(storedOwner = null, sessionUserId = "user-42")
        assertEquals(OwnerAction.Adopt("user-42"), action)
    }

    @Test
    fun `fresh database with unknown identity adopts a local key`() {
        val action = resolveOwnerAction(storedOwner = null, sessionUserId = "")
        assertTrue(action is OwnerAction.Adopt)
        assertTrue((action as OwnerAction.Adopt).ownerKey.startsWith(LOCAL_KEY_PREFIX))
    }

    @Test
    fun `same user keeps data`() {
        val action = resolveOwnerAction(storedOwner = "user-42", sessionUserId = "user-42")
        assertEquals(OwnerAction.Keep("user-42"), action)
    }

    @Test
    fun `unknown identity never wipes — expired sessions must not lose the queue`() {
        val action = resolveOwnerAction(storedOwner = "user-42", sessionUserId = "")
        assertEquals(OwnerAction.Keep("user-42"), action)
    }

    @Test
    fun `late identity resolution upgrades a local key without wiping`() {
        val action = resolveOwnerAction(
            storedOwner = LOCAL_KEY_PREFIX + "abc",
            sessionUserId = "user-42",
        )
        assertEquals(OwnerAction.Adopt("user-42"), action)
    }

    @Test
    fun `a different user id wipes local data`() {
        val action = resolveOwnerAction(storedOwner = "user-42", sessionUserId = "user-99")
        assertEquals(OwnerAction.Wipe("user-99"), action)
    }
}
