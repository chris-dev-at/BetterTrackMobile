package at.bettertrack.app.data.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure mapping coverage for the "use my BetterTrack PIN" network seam (spec §5):
 * how a `/auth/pin/verify` and `/auth/me` HTTP status becomes an app outcome.
 * These are the load-bearing rules that decide whether the lock activates, so
 * they're proven without a network — [AccountPinService] just attaches the call.
 */
class AccountPinMappingTest {

    // ── /auth/pin/verify status → outcome ─────────────────────────────────────
    @Test
    fun `200 is a correct pin`() {
        assertEquals(BtPinVerifyOutcome.Correct, pinVerifyOutcomeFor(200))
    }

    @Test
    fun `401 is a wrong pin`() {
        assertEquals(BtPinVerifyOutcome.WrongPin, pinVerifyOutcomeFor(401))
    }

    @Test
    fun `400 means the account has no web pin`() {
        assertEquals(BtPinVerifyOutcome.NoPinSet, pinVerifyOutcomeFor(400))
    }

    @Test
    fun `403 means the bearer is forbidden on the endpoint`() {
        // The on-device truth today: the mobile OAuth bearer is session-only here.
        assertEquals(BtPinVerifyOutcome.Forbidden, pinVerifyOutcomeFor(403))
    }

    @Test
    fun `zero is the transport-failure sentinel`() {
        assertEquals(BtPinVerifyOutcome.Offline, pinVerifyOutcomeFor(0))
    }

    @Test
    fun `anything else is a generic error`() {
        assertEquals(BtPinVerifyOutcome.Error, pinVerifyOutcomeFor(500))
        assertEquals(BtPinVerifyOutcome.Error, pinVerifyOutcomeFor(404))
        assertEquals(BtPinVerifyOutcome.Error, pinVerifyOutcomeFor(-1))
    }

    // ── /auth/me access classification ────────────────────────────────────────
    @Test
    fun `me maps 200 to ok and 403 to forbidden`() {
        assertEquals(MeAccess.Ok, meAccessFor(200))
        assertEquals(MeAccess.Forbidden, meAccessFor(403))
    }

    @Test
    fun `me maps transport failure to offline and the rest to error`() {
        assertEquals(MeAccess.Offline, meAccessFor(0))
        assertEquals(MeAccess.Error, meAccessFor(401))
        assertEquals(MeAccess.Error, meAccessFor(500))
    }

    // ── Feature gate (tripwire) ───────────────────────────────────────────────
    @Test
    fun `bettertrack pin option stays gated off until the API is fixed`() {
        // On-device probing (2026-07-08) shows the mobile bearer gets 403
        // API_KEY_FORBIDDEN on /auth/pin/verify, so the option is device-PIN-only.
        // When the platform grants bearer access, flip AppLockFeatures.
        // betterTrackPinLock AND update this test — after re-verifying on-device.
        assertFalse(AppLockFeatures.betterTrackPinLock)
    }
}
