package at.bettertrack.app.data.storage

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
}
