package at.bettertrack.app.data.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure mapping coverage for the "use my BetterTrack PIN" network seam (spec §5):
 * how a `/auth/pin/verify` and `/auth/pin/status` HTTP status becomes an app
 * outcome, plus the availability gate (offer only when the account has a web PIN).
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

    // ── /auth/pin/status access classification ────────────────────────────────
    @Test
    fun `pin status maps 200 to ok and 403 to forbidden`() {
        assertEquals(PinGateAccess.Ok, pinGateAccessFor(200))
        assertEquals(PinGateAccess.Forbidden, pinGateAccessFor(403))
    }

    @Test
    fun `pin status maps transport failure to offline and the rest to error`() {
        assertEquals(PinGateAccess.Offline, pinGateAccessFor(0))
        assertEquals(PinGateAccess.Error, pinGateAccessFor(401))
        assertEquals(PinGateAccess.Error, pinGateAccessFor(500))
    }

    // ── Availability gate: offer only when the account has a web PIN ───────────
    @Test
    fun `offer the option only when a web pin is set`() {
        assertTrue(shouldOfferBetterTrackPin(AccountPinStatus.Known(pinSet = true)))
        assertFalse(shouldOfferBetterTrackPin(AccountPinStatus.Known(pinSet = false)))
    }

    @Test
    fun `never offer when the status could not be confirmed`() {
        assertFalse(shouldOfferBetterTrackPin(AccountPinStatus.Forbidden))
        assertFalse(shouldOfferBetterTrackPin(AccountPinStatus.Offline))
        assertFalse(shouldOfferBetterTrackPin(AccountPinStatus.Error(500, "X")))
    }

    // ── Feature gate ──────────────────────────────────────────────────────────
    @Test
    fun `bettertrack pin option is live now the platform grants bearer access`() {
        // Platform #361 (2026-07-08) gave the mobile OAuth bearer access to
        // GET /auth/pin/status + POST /auth/pin/verify, so the option is offered
        // (gated on the account actually having a web PIN via pinSet).
        assertTrue(AppLockFeatures.betterTrackPinLock)
    }
}
