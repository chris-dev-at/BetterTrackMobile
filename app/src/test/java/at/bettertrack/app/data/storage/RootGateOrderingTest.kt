package at.bettertrack.app.data.storage

import at.bettertrack.app.data.auth.FirstRunGate
import at.bettertrack.app.data.auth.FirstRunState
import at.bettertrack.app.data.auth.firstRunGate
import at.bettertrack.app.data.prefs.gatedStorageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one W5 regression that would be silent, catastrophic and shipped:
 * **an existing user being shown the first-run wizard.**
 *
 * W1 built the §4.3 grandfathering rule but nothing consumed `UNSET`, so its
 * timing did not matter. W5's gate consumes it, which means the rule is now
 * racing a screen. These tests pin both halves of the fix — the rule's *content*
 * (any session signal ⇒ SERVER) and its *ordering* (the gate cannot decide before
 * the rule has run).
 */
class RootGateOrderingTest {

    // ── Ordering ────────────────────────────────────────────────────────────

    @Test
    fun `nothing is decided before grandfathering has run`() {
        for (mode in StorageMode.entries) {
            assertEquals(
                "an unresolved $mode must not pick a screen",
                RootGate.WAITING,
                rootGate(resolved = false, gatedMode = mode),
            )
        }
    }

    @Test
    fun `an unresolved install never reaches the wizard`() {
        // The whole point: the window in which a months-old install still reads
        // UNSET must not be a window in which the wizard can appear.
        assertNotEquals(RootGate.WIZARD, rootGate(resolved = false, gatedMode = StorageMode.UNSET))
    }

    @Test
    fun `an install with ANY session signal can never be routed to the wizard`() {
        // The composition of the two rules, over every combination of signals.
        for (mask in 1 until 16) {
            val resolvedMode = resolveGrandfatheredMode(
                stored = StorageMode.UNSET,
                everSignedIn = mask and 1 != 0,
                hasTokens = mask and 2 != 0,
                hasCachedUser = mask and 4 != 0,
                hasDbOwner = mask and 8 != 0,
            )
            assertEquals("signals=$mask", StorageMode.SERVER, resolvedMode)
            assertEquals(
                "signals=$mask",
                RootGate.AUTH,
                rootGate(resolved = true, gatedMode = gatedStorageMode(resolvedMode, driveEnabled = false)),
            )
        }
    }

    @Test
    fun `only a genuinely clean install reaches the wizard`() {
        val resolvedMode = resolveGrandfatheredMode(
            stored = StorageMode.UNSET,
            everSignedIn = false,
            hasTokens = false,
            hasCachedUser = false,
            hasDbOwner = false,
        )
        assertEquals(StorageMode.UNSET, resolvedMode)
        assertEquals(RootGate.WIZARD, rootGate(resolved = true, gatedMode = resolvedMode))
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    @Test
    fun `server and both take the unchanged auth branch`() {
        assertEquals(RootGate.AUTH, rootGate(resolved = true, gatedMode = StorageMode.SERVER))
        assertEquals(RootGate.AUTH, rootGate(resolved = true, gatedMode = StorageMode.BOTH))
    }

    @Test
    fun `drive goes to the vault gate and never to the login screen`() {
        // A Drive-only user has no account and never will; parking them on a
        // login screen would be a dead end with no exit.
        assertEquals(RootGate.VAULT_UNLOCK, rootGate(resolved = true, gatedMode = StorageMode.DRIVE))
    }

    @Test
    fun `every mode has a gate`() {
        for (mode in StorageMode.entries) {
            assertTrue(rootGate(resolved = true, gatedMode = mode) != RootGate.WAITING)
        }
    }

    // ── Interaction with the debug Drive gate ───────────────────────────────

    @Test
    fun `a release build can never be routed to the vault gate`() {
        // `DriveModeGate` maps a stored DRIVE/BOTH down to SERVER when the Drive
        // medium is disabled, so the gate below cannot select the vault branch —
        // "flag off ⇒ release build unchanged" holds at the root too.
        for (stored in listOf(StorageMode.DRIVE, StorageMode.BOTH)) {
            val gated = gatedStorageMode(stored, driveEnabled = false)
            assertEquals(RootGate.AUTH, rootGate(resolved = true, gatedMode = gated))
        }
    }

    @Test
    fun `the debug gate leaves UNSET alone so the wizard stays reachable`() {
        // Collapsing UNSET here would destroy the "never asked" vs "chose the
        // server" distinction the wizard is built on.
        assertEquals(StorageMode.UNSET, gatedStorageMode(StorageMode.UNSET, driveEnabled = false))
        assertEquals(RootGate.WIZARD, rootGate(resolved = true, gatedMode = StorageMode.UNSET))
    }

    // ── The ACCOUNT first-run gate, stacked under this one ──────────────────
    //
    // Two different wizards now sit in the root stack and they must never be
    // confused: [RootGate.WIZARD] is the STORAGE setup ("where should your data
    // live?"), which runs before there is a session at all; [FirstRunGate.WIZARD]
    // is the ACCOUNT setup (§6.12), which runs below the auth gate and the app
    // lock, for a signed-in account the server reports as never set up.
    //
    // The regression these guard is the same shape as the one above — an
    // established user shown a first-run screen — with a different trigger: not a
    // mode that has not resolved yet, but a signal the server never sent.

    @Test
    fun `an unknown first-run signal behaves exactly like a completed one`() {
        // The dangerous case. A pre-0074 server, or a session cached by a build
        // that did not know the field, reads UNKNOWN — and UNKNOWN must be as
        // inert as DONE, for every account, dismissed or not.
        for (dismissed in listOf(true, false)) {
            assertEquals(
                FirstRunGate.APP,
                firstRunGate(hasServerAccount = true, state = FirstRunState.UNKNOWN, dismissedForAccount = dismissed),
            )
            assertEquals(
                firstRunGate(hasServerAccount = true, state = FirstRunState.DONE, dismissedForAccount = dismissed),
                firstRunGate(hasServerAccount = true, state = FirstRunState.UNKNOWN, dismissedForAccount = dismissed),
            )
        }
    }

    @Test
    fun `only the two server modes can ever reach the account wizard`() {
        // Composition with the gate above: whatever `rootGate` selects, the
        // account wizard is reachable only where the install actually HAS a
        // BetterTrack account. A Drive-only user has none and never will.
        for (mode in StorageMode.entries) {
            val gate = firstRunGate(
                hasServerAccount = mode.hasServerAccount,
                state = FirstRunState.PENDING,
                dismissedForAccount = false,
            )
            val expected = if (mode.hasServerAccount) FirstRunGate.WIZARD else FirstRunGate.APP
            assertEquals("mode=$mode", expected, gate)
        }
        assertNotEquals(
            FirstRunGate.WIZARD,
            firstRunGate(
                hasServerAccount = StorageMode.DRIVE.hasServerAccount,
                state = FirstRunState.PENDING,
                dismissedForAccount = false,
            ),
        )
    }

    @Test
    fun `the two wizards can never both be selected`() {
        // `BtRoot` composes them in sequence, so the account wizard is only ever
        // evaluated on the AUTH branch. The property that makes that safe rather
        // than merely true today: every mode the STORAGE wizard claims is a mode
        // for which the account gate answers APP, so nothing can ever want both.
        for (mode in StorageMode.entries) {
            if (rootGate(resolved = true, gatedMode = mode) != RootGate.WIZARD) continue
            // UNSET means "we have not asked where the data lives yet" — there is
            // no account question to have an answer to.
            assertEquals(
                "mode=$mode selects the storage wizard, so the account wizard must be inert",
                FirstRunGate.APP,
                firstRunGate(
                    hasServerAccount = false,
                    state = FirstRunState.PENDING,
                    dismissedForAccount = false,
                ),
            )
        }
    }
}
