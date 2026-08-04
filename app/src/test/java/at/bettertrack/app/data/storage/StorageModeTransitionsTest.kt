package at.bettertrack.app.data.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §1.4 transition table, and the switcher that executes it.
 *
 * Two properties matter more than any individual row:
 *
 *  1. **You can never end up with no medium.** The transition set is what
 *     enforces plan §5 rule 3, and it does so structurally — the illegal moves
 *     simply do not exist as values — rather than by a guard someone could
 *     forget to call.
 *  2. **A mode is written only after the medium change happened.** A recorded
 *     mode is the app's promise about where the user's data is; recording it
 *     optimistically is how a user ends up trusting a backup that is not there.
 */
class StorageModeTransitionsTest {

    private val everything = TransitionCapabilities(
        googleConnected = true,
        serverSignedIn = true,
        vaultUnlocked = true,
        attachReplayAvailable = true,
    )

    // ── What is offered ─────────────────────────────────────────────────────

    @Test
    fun `a server install may only add the Drive medium`() {
        assertEquals(listOf(StorageTransition.SERVER_TO_BOTH), availableTransitions(StorageMode.SERVER))
    }

    @Test
    fun `an unset install is offered the same as server`() {
        // UNSET is answered by the wizard, not by a transition — but if anything
        // ever asks, it must not be handed a move that assumes data exists.
        assertEquals(availableTransitions(StorageMode.SERVER), availableTransitions(StorageMode.UNSET))
    }

    @Test
    fun `a drive install may only add an account`() {
        assertEquals(listOf(StorageTransition.DRIVE_TO_BOTH), availableTransitions(StorageMode.DRIVE))
    }

    @Test
    fun `a both install may drop either medium`() {
        assertEquals(
            listOf(StorageTransition.BOTH_TO_DRIVE, StorageTransition.BOTH_TO_SERVER),
            availableTransitions(StorageMode.BOTH),
        )
    }

    @Test
    fun `no transition ever removes the last medium`() {
        // The structural guarantee: every removal starts from BOTH, and every
        // destination is a mode that still stores the data somewhere.
        for (transition in StorageTransition.entries) {
            assertTrue(
                "${transition.name} would strand the data",
                transition.to == StorageMode.BOTH || transition.from == StorageMode.BOTH,
            )
            assertNotNull(transition.to)
        }
    }

    @Test
    fun `single-medium modes are never both the source and the target`() {
        for (transition in StorageTransition.entries) {
            assertTrue(transition.from != transition.to)
        }
    }

    // ── Prerequisites ───────────────────────────────────────────────────────

    @Test
    fun `adding Drive to a server account needs a Google connection`() {
        val outcome = evaluateTransition(
            StorageTransition.SERVER_TO_BOTH,
            everything.copy(googleConnected = false),
        )
        assertEquals(
            TransitionOutcome.Blocked(StorageTransition.SERVER_TO_BOTH, TransitionBlocker.NEEDS_GOOGLE),
            outcome,
        )
    }

    @Test
    fun `a signed-out user is told to sign in before being told about Google`() {
        // Order matters: sending someone to connect Google for an account they
        // are not signed in to is a dead end.
        val outcome = evaluateTransition(
            StorageTransition.SERVER_TO_BOTH,
            everything.copy(serverSignedIn = false, googleConnected = false),
        )
        assertEquals(
            TransitionOutcome.Blocked(StorageTransition.SERVER_TO_BOTH, TransitionBlocker.NEEDS_SERVER_ACCOUNT),
            outcome,
        )
    }

    @Test
    fun `adding a server account to a Drive vault is blocked until the replay exists`() {
        val outcome = evaluateTransition(
            StorageTransition.DRIVE_TO_BOTH,
            everything.copy(attachReplayAvailable = false),
        )
        assertEquals(
            TransitionOutcome.Blocked(
                StorageTransition.DRIVE_TO_BOTH,
                TransitionBlocker.ATTACH_REPLAY_UNAVAILABLE,
            ),
            outcome,
        )
    }

    @Test
    fun `attaching an account needs the vault open — its entities have to be read`() {
        val outcome = evaluateTransition(
            StorageTransition.DRIVE_TO_BOTH,
            everything.copy(vaultUnlocked = false),
        )
        assertEquals(
            TransitionOutcome.Blocked(StorageTransition.DRIVE_TO_BOTH, TransitionBlocker.NEEDS_VAULT_UNLOCK),
            outcome,
        )
    }

    @Test
    fun `promotion to Drive-only does NOT require Google`() {
        // A promoted vault that cannot reach Drive yet is just a local vault with
        // a pending push — the designed state the whole medium is built around.
        val outcome = evaluateTransition(
            StorageTransition.BOTH_TO_DRIVE,
            everything.copy(googleConnected = false),
        )
        assertEquals(TransitionOutcome.Allowed(StorageTransition.BOTH_TO_DRIVE), outcome)
    }

    @Test
    fun `promotion requires an unlocked vault`() {
        val outcome = evaluateTransition(
            StorageTransition.BOTH_TO_DRIVE,
            everything.copy(vaultUnlocked = false),
        )
        assertEquals(
            TransitionOutcome.Blocked(StorageTransition.BOTH_TO_DRIVE, TransitionBlocker.NEEDS_VAULT_UNLOCK),
            outcome,
        )
    }

    @Test
    fun `dropping the Drive medium needs nothing at all`() {
        // The server already holds everything, and the remote delete is best
        // effort by design — so a locked vault or a dead Google token must not
        // trap the user in a mode they want to leave.
        assertEquals(
            TransitionOutcome.Allowed(StorageTransition.BOTH_TO_SERVER),
            evaluateTransition(StorageTransition.BOTH_TO_SERVER, TransitionCapabilities()),
        )
    }

    @Test
    fun `every transition has an outcome for every capability combination`() {
        for (transition in StorageTransition.entries) {
            for (mask in 0 until 16) {
                val capabilities = TransitionCapabilities(
                    googleConnected = mask and 1 != 0,
                    serverSignedIn = mask and 2 != 0,
                    vaultUnlocked = mask and 4 != 0,
                    attachReplayAvailable = mask and 8 != 0,
                )
                assertNotNull("$transition/$mask", evaluateTransition(transition, capabilities))
            }
        }
    }

    // ── Logout semantics ────────────────────────────────────────────────────

    @Test
    fun `logging out of BOTH demotes to Drive rather than destroying the vault`() {
        assertEquals(StorageMode.DRIVE, modeAfterLogout(StorageMode.BOTH))
    }

    @Test
    fun `logging out of a server install changes nothing about the mode`() {
        assertEquals(StorageMode.SERVER, modeAfterLogout(StorageMode.SERVER))
        assertEquals(StorageMode.UNSET, modeAfterLogout(StorageMode.UNSET))
    }

    @Test
    fun `the word log out only means something where there is an account`() {
        assertTrue(StorageMode.SERVER.hasServerAccount)
        assertTrue(StorageMode.BOTH.hasServerAccount)
        assertFalse(StorageMode.DRIVE.hasServerAccount)
        assertTrue(StorageMode.UNSET.hasServerAccount)
    }

    // ── The switcher ────────────────────────────────────────────────────────

    private class Recorder {
        var mode: StorageMode? = null
        var vaultWiped = false
        var keyForgotten = false
        var loggedOut = false
        var remoteDeleteAttempts = 0
    }

    private fun switcher(
        recorder: Recorder,
        capabilities: TransitionCapabilities = TransitionCapabilities(vaultUnlocked = true),
        remoteDeleteSucceeds: Boolean = true,
        remoteDeleteThrows: Boolean = false,
    ) = StorageModeSwitcher(
        setMode = { recorder.mode = it },
        deleteRemoteVault = {
            recorder.remoteDeleteAttempts++
            if (remoteDeleteThrows) throw IllegalStateException("drive exploded")
            remoteDeleteSucceeds
        },
        forgetVaultKey = { recorder.keyForgotten = true },
        wipeVaultTables = { recorder.vaultWiped = true },
        logoutServer = { recorder.loggedOut = true },
        capabilities = { capabilities },
    )

    @Test
    fun `promotion records DRIVE and signs out of the server`() = runTest {
        val recorder = Recorder()
        val result = switcher(recorder).apply(StorageTransition.BOTH_TO_DRIVE)

        assertEquals(SwitchResult.Applied(StorageMode.DRIVE), result)
        assertEquals(StorageMode.DRIVE, recorder.mode)
        assertTrue(recorder.loggedOut)
        // Promotion moves nothing and deletes nothing — the entity graph is
        // already the vault; only authority changed.
        assertFalse(recorder.vaultWiped)
        assertFalse(recorder.keyForgotten)
        assertEquals(0, recorder.remoteDeleteAttempts)
    }

    @Test
    fun `dropping the Drive medium removes the remote file, the key and the rows`() = runTest {
        val recorder = Recorder()
        val result = switcher(recorder).apply(StorageTransition.BOTH_TO_SERVER)

        assertEquals(SwitchResult.Applied(StorageMode.SERVER), result)
        assertEquals(StorageMode.SERVER, recorder.mode)
        assertEquals(1, recorder.remoteDeleteAttempts)
        assertTrue(recorder.vaultWiped)
        assertTrue(recorder.keyForgotten)
        // Removing a backup is not logging out of the account you are keeping.
        assertFalse(recorder.loggedOut)
    }

    @Test
    fun `a failed remote delete still completes locally but SAYS SO`() = runTest {
        val recorder = Recorder()
        val result = switcher(recorder, remoteDeleteSucceeds = false)
            .apply(StorageTransition.BOTH_TO_SERVER)

        // Plan §5 rule 2: those are the user's own bytes in the user's own Drive.
        // Reporting a clean removal that did not happen is the one outcome this
        // whole class exists to prevent.
        assertEquals(
            SwitchResult.Partial(StorageMode.SERVER, SwitchWarning.REMOTE_VAULT_NOT_DELETED),
            result,
        )
        assertEquals(StorageMode.SERVER, recorder.mode)
        assertTrue(recorder.vaultWiped)
    }

    @Test
    fun `a throwing remote delete is a warning, never a crash`() = runTest {
        val recorder = Recorder()
        val result = switcher(recorder, remoteDeleteThrows = true)
            .apply(StorageTransition.BOTH_TO_SERVER)

        assertEquals(
            SwitchResult.Partial(StorageMode.SERVER, SwitchWarning.REMOTE_VAULT_NOT_DELETED),
            result,
        )
    }

    @Test
    fun `a blocked transition changes absolutely nothing`() = runTest {
        val recorder = Recorder()
        val result = switcher(recorder, capabilities = TransitionCapabilities(vaultUnlocked = false))
            .apply(StorageTransition.BOTH_TO_DRIVE)

        assertEquals(SwitchResult.Blocked(TransitionBlocker.NEEDS_VAULT_UNLOCK), result)
        assertEquals(null, recorder.mode)
        assertFalse(recorder.loggedOut)
        assertFalse(recorder.vaultWiped)
        assertFalse(recorder.keyForgotten)
    }

    @Test
    fun `the additive transitions refuse to be executed as a single call`() = runTest {
        val recorder = Recorder()
        val result = switcher(
            recorder,
            capabilities = TransitionCapabilities(
                googleConnected = true,
                serverSignedIn = true,
                vaultUnlocked = true,
                attachReplayAvailable = true,
            ),
        ).apply(StorageTransition.SERVER_TO_BOTH)

        // Adding a medium ends in a VERIFIED round trip performed by the flow;
        // a switcher that recorded BOTH here would be promising a backup that
        // has not been written yet.
        assertEquals(SwitchResult.NeedsFlow(StorageTransition.SERVER_TO_BOTH), result)
        assertEquals(null, recorder.mode)
    }
}
