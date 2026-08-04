package at.bettertrack.app.data.storage

import at.bettertrack.app.data.db.SERVER_SCOPED_TABLES
import at.bettertrack.app.data.db.VAULT_SCOPED_TABLES
import at.bettertrack.app.data.db.tablesToClear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logout wipe rule (S3/S4 plan §4.4, row 2).
 *
 * Today `logout()` destroys every local table. Once a Drive vault can exist that
 * would delete data the user still owns and the server never had — so the wipe
 * became mode-scoped. The tests below pin **both** halves: the new rule for the
 * modes W5 unlocks, and the guarantee that the modes reachable TODAY still take
 * the identical full-wipe path.
 */
class LogoutWipeRuleTest {

    // ── The rule ────────────────────────────────────────────────────────────

    @Test
    fun `logging out of a server-only install still wipes everything`() {
        assertEquals(WipeScope.EVERYTHING, logoutWipeScope(StorageMode.SERVER))
    }

    @Test
    fun `unset wipes everything too — it behaves as server`() {
        assertEquals(WipeScope.EVERYTHING, logoutWipeScope(StorageMode.UNSET))
    }

    @Test
    fun `a mode holding a vault never lets logout destroy it`() {
        assertEquals(WipeScope.SERVER_ONLY, logoutWipeScope(StorageMode.BOTH))
        assertEquals(WipeScope.SERVER_ONLY, logoutWipeScope(StorageMode.DRIVE))
    }

    @Test
    fun `every mode has a defined wipe scope`() {
        for (mode in StorageMode.entries) assertNotNull(logoutWipeScope(mode))
    }

    // ── W1 behaviour equivalence ────────────────────────────────────────────

    @Test
    fun `every mode reachable today takes the historic clearAllTables path`() {
        // Only SERVER and UNSET can be stored until the W5 wizard ships, and
        // `null` is precisely "call BtDatabase.clearAllTables()" — the same call
        // AuthRepository.logout has always made. This is the byte-identical
        // behaviour claim of W1, expressed as a test.
        for (mode in listOf(StorageMode.UNSET, StorageMode.SERVER)) {
            assertNull(tablesToClear(logoutWipeScope(mode)))
        }
    }

    @Test
    fun `the real vault tables are the ones a scoped wipe spares`() {
        // W1 asserted this list was EMPTY, deliberately, so that W4 adding the
        // tables would fail here and force the classification to be made rather
        // than defaulted. W5 makes it: these three, and nothing else.
        assertEquals(
            listOf("vault_entities", "vault_meta", "price_cache"),
            VAULT_SCOPED_TABLES,
        )
    }

    @Test
    fun `no table is classified as both server-scoped and vault-scoped`() {
        // The two lists drive opposite decisions on a logout. A name in both
        // would mean the same table is simultaneously the account's to destroy
        // and the user's to keep — and the subtraction in `tablesToClear` would
        // silently resolve it one way forever.
        val overlap = SERVER_SCOPED_TABLES.intersect(VAULT_SCOPED_TABLES.toSet())
        assertTrue("classified twice: $overlap", overlap.isEmpty())
    }

    @Test
    fun `logging out of BOTH keeps every vault table`() {
        // The claim W4 could not make and W5 can: a BOTH install that logs out
        // still owns its vault afterwards.
        val cleared = tablesToClear(logoutWipeScope(StorageMode.BOTH))!!
        for (table in VAULT_SCOPED_TABLES) {
            assertTrue("$table must survive a BOTH logout", table !in cleared)
        }
    }

    @Test
    fun `a server-only logout still destroys everything including the vault tables`() {
        // The other half of the guarantee, and the reason the scope exists at
        // all: in SERVER mode there is no vault to protect, and leaving stray
        // vault rows behind for the NEXT account would be a data leak between
        // two people sharing a phone.
        assertNull(tablesToClear(logoutWipeScope(StorageMode.SERVER)))
    }

    // ── The scoped wipe W4 extends ──────────────────────────────────────────

    @Test
    fun `a scoped wipe clears the server tables and spares the vault ones`() {
        val cleared = tablesToClear(WipeScope.SERVER_ONLY)!!
        assertTrue(cleared.containsAll(SERVER_SCOPED_TABLES - VAULT_SCOPED_TABLES.toSet()))
        for (vaultTable in VAULT_SCOPED_TABLES) {
            assertTrue("$vaultTable must survive logout", vaultTable !in cleared)
        }
    }

    @Test
    fun `the scoped wipe still takes the outbound queue and the account caches`() {
        // Scoping the wipe must not accidentally leave one account's queued
        // mutations to drain under the next account's session.
        val cleared = tablesToClear(WipeScope.SERVER_ONLY)!!
        assertTrue("sync_ops" in cleared)
        assertTrue("meta" in cleared)
        assertTrue("portfolios" in cleared)
        assertTrue("transactions" in cleared)
        assertTrue("cash_movements" in cleared)
    }

    @Test
    fun `the server table list has no duplicates`() {
        assertEquals(SERVER_SCOPED_TABLES.size, SERVER_SCOPED_TABLES.toSet().size)
    }
}
