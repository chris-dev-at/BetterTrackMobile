package at.bettertrack.app.ui.storage

import at.bettertrack.app.data.storage.StorageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's interlocks.
 *
 * Every `canAdvance` gate is a promise made on behalf of a user who is about to
 * create data only they can ever decrypt. These tests exist so those promises are
 * kept by the build rather than by whoever last tapped through the flow.
 */
class StorageWizardStateTest {

    private fun driveAt(step: WizardStep) = WizardState(choice = WizardChoice.DRIVE, step = step)

    // ── Topology ────────────────────────────────────────────────────────────

    @Test
    fun `the server branch is the choice and the existing login, nothing more`() {
        assertEquals(
            listOf(WizardStep.CHOOSE, WizardStep.SERVER_LOGIN),
            wizardPath(WizardChoice.SERVER),
        )
    }

    @Test
    fun `the drive branch runs the full friction ladder in the plan's order`() {
        assertEquals(
            listOf(
                WizardStep.CHOOSE,
                WizardStep.GOOGLE_CONNECT,
                WizardStep.PASSPHRASE,
                WizardStep.RECOVERY_KIT,
                WizardStep.ACKNOWLEDGE,
                WizardStep.FIRST_PORTFOLIO,
                WizardStep.WORKING,
                WizardStep.DONE,
            ),
            wizardPath(WizardChoice.DRIVE),
        )
    }

    @Test
    fun `both is the server login followed by the identical drive tail`() {
        val both = wizardPath(WizardChoice.BOTH)
        assertEquals(WizardStep.SERVER_LOGIN, both[1])
        assertEquals(wizardPath(WizardChoice.DRIVE).drop(1), both.drop(2))
    }

    @Test
    fun `the recovery kit always comes after the passphrase that produces the key`() {
        // The kit IS the vault key: there is nothing to export until the key
        // exists, so this ordering is a correctness constraint, not a preference.
        for (choice in WizardChoice.entries) {
            val path = wizardPath(choice)
            if (WizardStep.RECOVERY_KIT !in path) continue
            assertTrue(path.indexOf(WizardStep.PASSPHRASE) < path.indexOf(WizardStep.RECOVERY_KIT))
        }
    }

    @Test
    fun `the blocking acknowledgment always comes before anything is created`() {
        for (choice in WizardChoice.entries) {
            val path = wizardPath(choice)
            if (WizardStep.ACKNOWLEDGE !in path) continue
            assertTrue(path.indexOf(WizardStep.ACKNOWLEDGE) < path.indexOf(WizardStep.WORKING))
        }
    }

    // ── Interlocks ──────────────────────────────────────────────────────────

    @Test
    fun `you cannot leave the first screen without choosing`() {
        assertFalse(canAdvance(WizardState()))
        assertTrue(canAdvance(WizardState(choice = WizardChoice.DRIVE)))
    }

    @Test
    fun `the login step is never advanced by a button`() {
        // Only a real session moves it — otherwise the wizard would let someone
        // walk past the login into a BOTH flow with no account.
        assertFalse(canAdvance(WizardState(choice = WizardChoice.BOTH, step = WizardStep.SERVER_LOGIN)))
    }

    @Test
    fun `the google step needs either a connection or a deliberate opt-out`() {
        val step = driveAt(WizardStep.GOOGLE_CONNECT)
        assertFalse(canAdvance(step))
        assertTrue(canAdvance(step.copy(googleConnected = true)))
        assertTrue(canAdvance(step.copy(continueWithoutGoogle = true)))
    }

    @Test
    fun `a mismatched confirmation blocks the passphrase step`() {
        val step = driveAt(WizardStep.PASSPHRASE)
        assertFalse(canAdvance(step.copy(passphrase = "correct horse battery", confirm = "correct horse")))
        assertTrue(canAdvance(step.copy(passphrase = "correct horse battery", confirm = "correct horse battery")))
    }

    @Test
    fun `a too-short passphrase blocks the step even when confirmed`() {
        val short = "a".repeat(MIN_PASSPHRASE_LENGTH - 1)
        assertFalse(canAdvance(driveAt(WizardStep.PASSPHRASE).copy(passphrase = short, confirm = short)))
    }

    @Test
    fun `the recovery-kit tick is mandatory`() {
        val step = driveAt(WizardStep.RECOVERY_KIT)
        assertFalse(canAdvance(step))
        assertTrue(canAdvance(step.copy(kitSaved = true)))
    }

    @Test
    fun `the unrecoverability acknowledgment is mandatory`() {
        val step = driveAt(WizardStep.ACKNOWLEDGE)
        assertFalse(canAdvance(step))
        assertTrue(canAdvance(step.copy(acknowledged = true)))
    }

    @Test
    fun `a blank portfolio name blocks creation`() {
        val step = driveAt(WizardStep.FIRST_PORTFOLIO)
        assertFalse(canAdvance(step.copy(portfolioName = "   ")))
        assertTrue(canAdvance(step.copy(portfolioName = "Retirement")))
    }

    @Test
    fun `nothing advances out of WORKING or DONE`() {
        assertFalse(canAdvance(driveAt(WizardStep.WORKING)))
        assertFalse(canAdvance(driveAt(WizardStep.DONE)))
    }

    @Test
    fun `advance is a no-op whenever an interlock refuses`() {
        val blocked = driveAt(WizardStep.ACKNOWLEDGE)
        assertEquals(blocked, advance(blocked))
    }

    @Test
    fun `a full drive run reaches WORKING and no further by itself`() {
        var state = WizardState(choice = WizardChoice.DRIVE)
        state = advance(state)
        assertEquals(WizardStep.GOOGLE_CONNECT, state.step)
        state = advance(state.copy(continueWithoutGoogle = true))
        assertEquals(WizardStep.PASSPHRASE, state.step)
        state = advance(state.copy(passphrase = "seven blue lanterns", confirm = "seven blue lanterns"))
        assertEquals(WizardStep.RECOVERY_KIT, state.step)
        state = advance(state.copy(kitSaved = true))
        assertEquals(WizardStep.ACKNOWLEDGE, state.step)
        state = advance(state.copy(acknowledged = true))
        assertEquals(WizardStep.FIRST_PORTFOLIO, state.step)
        state = advance(state.copy(portfolioName = "Main"))
        assertEquals(WizardStep.WORKING, state.step)
        // DONE is reached by the verified round trip, never by the state machine.
        assertEquals(WizardStep.WORKING, advance(state).step)
    }

    // ── Going back ──────────────────────────────────────────────────────────

    @Test
    fun `back from the first screen closes the wizard`() {
        assertNull(previousStep(WizardState()))
    }

    @Test
    fun `back from the second step returns to an unchosen first screen`() {
        val back = previousStep(driveAt(WizardStep.GOOGLE_CONNECT))
        assertNotNull(back)
        assertEquals(WizardStep.CHOOSE, back!!.step)
        // The choice is cleared so the cards do not show a stale selection.
        assertNull(back.choice)
    }

    @Test
    fun `back walks the branch in reverse`() {
        assertEquals(WizardStep.RECOVERY_KIT, previousStep(driveAt(WizardStep.ACKNOWLEDGE))!!.step)
        assertEquals(WizardStep.PASSPHRASE, previousStep(driveAt(WizardStep.RECOVERY_KIT))!!.step)
    }

    @Test
    fun `back is refused once key material is being committed`() {
        // Abandoning the flow mid-creation is exactly how a half-written vault
        // happens, so WORKING and DONE simply do not go back.
        val working = driveAt(WizardStep.WORKING)
        assertEquals(working, previousStep(working))
        val done = driveAt(WizardStep.DONE)
        assertEquals(done, previousStep(done))
    }

    @Test
    fun `back clears a stale failure so the next attempt starts clean`() {
        val failed = driveAt(WizardStep.ACKNOWLEDGE).copy(failure = WizardFailure.ROUND_TRIP_FAILED)
        assertNull(previousStep(failed)!!.failure)
    }

    // ── Login hand-off ──────────────────────────────────────────────────────

    @Test
    fun `a server-only user is finished the moment there is a session`() {
        val state = WizardState(choice = WizardChoice.SERVER, step = WizardStep.SERVER_LOGIN)
        assertEquals(WizardStep.DONE, afterServerLogin(state).step)
    }

    @Test
    fun `a both user has only just begun when the session arrives`() {
        val state = WizardState(choice = WizardChoice.BOTH, step = WizardStep.SERVER_LOGIN)
        assertEquals(WizardStep.GOOGLE_CONNECT, afterServerLogin(state).step)
    }

    // ── Mode mapping ────────────────────────────────────────────────────────

    @Test
    fun `each choice maps to its mode`() {
        assertEquals(StorageMode.SERVER, modeFor(WizardChoice.SERVER))
        assertEquals(StorageMode.DRIVE, modeFor(WizardChoice.DRIVE))
        assertEquals(StorageMode.BOTH, modeFor(WizardChoice.BOTH))
    }

    @Test
    fun `no choice ever produces UNSET`() {
        // UNSET means "never asked". A completed wizard that recorded it would
        // loop the user back into the wizard forever.
        for (choice in WizardChoice.entries) {
            assertTrue(modeFor(choice) != StorageMode.UNSET)
        }
    }
}

/** The local passphrase check (plan §2.7). */
class PassphraseStrengthTest {

    @Test
    fun `anything under the minimum is refused outright`() {
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength(""))
        assertEquals(PassphraseStrength.TOO_SHORT, passphraseStrength("a".repeat(MIN_PASSPHRASE_LENGTH - 1)))
    }

    @Test
    fun `length alone does not make a passphrase strong`() {
        // "aaaaaaaaaaaaaaaaaaaa" is twenty characters and one guess.
        assertEquals(PassphraseStrength.WEAK, passphraseStrength("a".repeat(20)))
        assertEquals(PassphraseStrength.WEAK, passphraseStrength("ababababababababab"))
    }

    @Test
    fun `a few unrelated words score well`() {
        assertEquals(PassphraseStrength.STRONG, passphraseStrength("seven blue lanterns drift"))
    }

    @Test
    fun `a mixed short-ish secret lands in the middle`() {
        assertEquals(PassphraseStrength.FAIR, passphraseStrength("Trout9dial"))
    }

    @Test
    fun `strength is a pure function of the text`() {
        val value = "Trout9dial harbour"
        assertEquals(passphraseStrength(value), passphraseStrength(value))
    }

    @Test
    fun `acceptance requires a match and a non-trivial length`() {
        assertTrue(passphrasePairAccepted("seven blue lanterns", "seven blue lanterns"))
        assertFalse(passphrasePairAccepted("seven blue lanterns", "seven blue lantern"))
        assertFalse(passphrasePairAccepted("short", "short"))
    }

    @Test
    fun `a weak but long passphrase is accepted with a warning rather than blocked`() {
        // Deliberate: the app warns, it does not decide for the user on a secret
        // only they can ever hold. The warning is rendered from the WEAK bucket.
        val weak = "a".repeat(MIN_PASSPHRASE_LENGTH + 2)
        assertEquals(PassphraseStrength.WEAK, passphraseStrength(weak))
        assertTrue(passphrasePairAccepted(weak, weak))
    }
}

/**
 * The "Both" branch's refusal to claim a medium it does not have.
 *
 * This is the one place the wizard could quietly lie: BOTH is a promise about two
 * storage media, and the Google client that provides the second one does not
 * exist yet (plan §6.8). Letting the flow through would persist a mode whose
 * whole description — "an encrypted backup also goes to your Drive" — is false
 * from the instant it is written.
 */
class WizardBothBranchTest {

    private fun both(step: WizardStep) = WizardState(choice = WizardChoice.BOTH, step = step)

    @Test
    fun `both cannot continue past Google on a device-local opt-out`() {
        val blocked = both(WizardStep.GOOGLE_CONNECT).copy(continueWithoutGoogle = true)
        assertFalse(canAdvance(blocked))
        assertEquals(blocked, advance(blocked))
    }

    @Test
    fun `both continues normally once Google is genuinely connected`() {
        val connected = both(WizardStep.GOOGLE_CONNECT).copy(googleConnected = true)
        assertTrue(canAdvance(connected))
        assertEquals(WizardStep.PASSPHRASE, advance(connected).step)
    }

    @Test
    fun `drive-only is NOT blocked by the same missing connection`() {
        // A device-local vault is a complete vault: LocalDataHome works fully and
        // syncs to Drive whenever a connection appears. The asymmetry is the whole
        // point — one claim is true without Google and the other is not.
        val drive = WizardState(choice = WizardChoice.DRIVE, step = WizardStep.GOOGLE_CONNECT)
            .copy(continueWithoutGoogle = true)
        assertTrue(canAdvance(drive))
        assertEquals(WizardStep.PASSPHRASE, advance(drive).step)
    }

    @Test
    fun `a blocked both run can settle for the account it already has`() {
        // The user is signed in by this point, so SERVER is genuinely finished.
        val settled = both(WizardStep.GOOGLE_CONNECT)
            .copy(choice = WizardChoice.SERVER, step = WizardStep.DONE)
        assertEquals(StorageMode.SERVER, modeFor(settled.choice!!))
    }

    @Test
    fun `a blocked both run can switch to the drive branch instead`() {
        val switched = both(WizardStep.GOOGLE_CONNECT)
            .copy(choice = WizardChoice.DRIVE, continueWithoutGoogle = true)
        assertTrue(canAdvance(switched))
        assertEquals(StorageMode.DRIVE, modeFor(switched.choice!!))
    }
}
