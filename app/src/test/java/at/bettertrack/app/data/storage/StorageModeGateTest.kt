package at.bettertrack.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The storage-mode rules that decide behaviour app-wide (S3/S4 plan §1.4/§4.3).
 *
 * The load-bearing claim of W1 is **"no behaviour change"**, and these are the
 * assertions that hold it: while the wizard does not exist, every branch must
 * resolve exactly as the pre-W1 app did, and an install that already has data
 * must never be treated as a fresh one.
 */
class StorageModeGateTest {

    // ── UNSET behaves as SERVER, everywhere but the stored value ─────────────

    @Test
    fun `unset resolves to server for every behavioural rule`() {
        assertEquals(StorageMode.SERVER, StorageMode.UNSET.effective)
        assertTrue(StorageMode.UNSET.writesToServer)
        assertFalse(StorageMode.UNSET.isDriveOnly)
        assertFalse(StorageMode.UNSET.holdsVault)
        assertEquals(BackendTag.SERVER, StorageMode.UNSET.backendTag())
    }

    @Test
    fun `unset is still distinguishable as a stored value`() {
        // W5's wizard needs "never asked" ≠ "chose the server"; only [effective]
        // collapses them.
        assertEquals(StorageMode.UNSET, StorageMode.fromWire("unset"))
        assertEquals(StorageMode.SERVER, StorageMode.fromWire("server"))
        assertEquals(null, StorageMode.fromWire("something-else"))
        assertEquals(null, StorageMode.fromWire(null))
    }

    @Test
    fun `server and both write to the server, drive does not`() {
        assertTrue(StorageMode.SERVER.writesToServer)
        assertTrue(StorageMode.BOTH.writesToServer)
        assertFalse(StorageMode.DRIVE.writesToServer)
    }

    // ── The sync-engine session gate (plan §1.2) ────────────────────────────

    /** Exactly the lambda AppGraph installs as `SyncEngine.hasSession`. */
    private fun hasSession(mode: StorageMode, hasTokens: Boolean): Boolean =
        mode.isDriveOnly || hasTokens

    @Test
    fun `the session gate is unchanged for every server-attached mode`() {
        for (mode in listOf(StorageMode.UNSET, StorageMode.SERVER, StorageMode.BOTH)) {
            assertTrue("$mode logged in", hasSession(mode, hasTokens = true))
            assertFalse("$mode logged out", hasSession(mode, hasTokens = false))
        }
    }

    @Test
    fun `a drive-only install drains without a bearer`() {
        // Otherwise the queue would no-op forever: there is no account to
        // acquire tokens from.
        assertTrue(hasSession(StorageMode.DRIVE, hasTokens = false))
    }

    // ── Enqueue tagging (plan §1.2) ─────────────────────────────────────────

    @Test
    fun `ops are tagged for the backend that will apply them`() {
        assertEquals(BackendTag.SERVER, StorageMode.SERVER.backendTag())
        assertEquals(BackendTag.SERVER, StorageMode.BOTH.backendTag())
        assertEquals(BackendTag.VAULT, StorageMode.DRIVE.backendTag())
    }

    @Test
    fun `an unknown or missing stored tag reads as server`() {
        // Pre-v7 rows carry no tag at all, and they were all server ops.
        assertEquals(BackendTag.SERVER, BackendTag.fromWire(null))
        assertEquals(BackendTag.SERVER, BackendTag.fromWire(""))
        assertEquals(BackendTag.SERVER, BackendTag.fromWire("something-from-the-future"))
        assertEquals(BackendTag.VAULT, BackendTag.fromWire("vault"))
    }

    // ── Grandfathering (plan §4.3) ──────────────────────────────────────────

    @Test
    fun `an install that has ever held a session resolves to server`() {
        val signals = listOf(
            "everSignedIn" to Triple(true, false, false),
            "live tokens" to Triple(false, true, false),
            "cached user" to Triple(false, false, true),
        )
        for ((label, s) in signals) {
            assertEquals(
                label,
                StorageMode.SERVER,
                resolveGrandfatheredMode(
                    stored = StorageMode.UNSET,
                    everSignedIn = s.first,
                    hasTokens = s.second,
                    hasCachedUser = s.third,
                    hasDbOwner = false,
                ),
            )
        }
    }

    @Test
    fun `a logged-out install whose Room caches survived still resolves to server`() {
        // The exact case the §4.3 rule exists for: session expiry keeps the DB
        // (and its owner key) but drops tokens and the cached user. Without the
        // owner-key signal this user would meet the W5 wizard on upgrade.
        assertEquals(
            StorageMode.SERVER,
            resolveGrandfatheredMode(
                stored = StorageMode.UNSET,
                everSignedIn = false,
                hasTokens = false,
                hasCachedUser = false,
                hasDbOwner = true,
            ),
        )
    }

    @Test
    fun `a genuinely clean install stays unset`() {
        assertEquals(
            StorageMode.UNSET,
            resolveGrandfatheredMode(
                stored = StorageMode.UNSET,
                everSignedIn = false,
                hasTokens = false,
                hasCachedUser = false,
                hasDbOwner = false,
            ),
        )
    }

    @Test
    fun `grandfathering never overwrites a mode that was already decided`() {
        for (stored in listOf(StorageMode.SERVER, StorageMode.DRIVE, StorageMode.BOTH)) {
            assertEquals(
                stored,
                resolveGrandfatheredMode(
                    stored = stored,
                    everSignedIn = true,
                    hasTokens = true,
                    hasCachedUser = true,
                    hasDbOwner = true,
                ),
            )
        }
    }

    @Test
    fun `resolution is idempotent — re-running it changes nothing`() {
        val once = resolveGrandfatheredMode(StorageMode.UNSET, false, true, false, false)
        val twice = resolveGrandfatheredMode(once, false, true, false, false)
        assertEquals(once, twice)
        assertEquals(StorageMode.SERVER, twice)
    }
}
