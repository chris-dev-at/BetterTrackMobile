package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.data.db.PvVaultDocCandidateRow
import at.bettertrack.app.data.db.PvVaultDocCursorRow
import at.bettertrack.app.data.db.PvVaultDocRow
import at.bettertrack.app.data.db.PvVaultRow
import at.bettertrack.app.data.db.PvVaultSyncDao
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The local doc set on Room** — the three interface methods, plus the two
 * rules the interface cannot state.
 *
 * The DAO is faked rather than instantiated: Room needs an instrumented device
 * and this project's entire database suite is deliberately device-free (see
 * `BtDatabaseMigrationTest`). What is NOT faked is anything that could be wrong
 * — the crypto is the real envelope codec, so "the row on disk is ciphertext"
 * and "what comes back out is what went in" are properties of the shipped
 * encrypt/decrypt pair rather than of a stub.
 */
class PvRoomVaultLocalStoreTest {

    // ── A DAO that behaves like the tables ──────────────────────────────────

    private class FakeDao : PvVaultSyncDao {
        val vaults = LinkedHashMap<String, PvVaultRow>()
        val docs = LinkedHashMap<Pair<String, String>, PvVaultDocRow>()
        val candidates = LinkedHashMap<List<String>, PvVaultDocCandidateRow>()
        val cursors = LinkedHashMap<List<String>, PvVaultDocCursorRow>()

        override suspend fun vault(vaultId: String) = vaults[vaultId]
        override suspend fun vaults() = vaults.values.sortedBy { it.name.lowercase() }
        override suspend fun putVault(row: PvVaultRow) { vaults[row.id] = row }
        override suspend fun deleteVault(vaultId: String) { vaults.remove(vaultId) }
        override suspend fun clearVaults() = vaults.clear()

        override suspend fun docs(vaultId: String) =
            docs.values.filter { it.vaultId == vaultId }.sortedBy { it.docId }

        override suspend fun doc(vaultId: String, docId: String) = docs[vaultId to docId]
        override suspend fun putDoc(row: PvVaultDocRow) { docs[row.vaultId to row.docId] = row }
        override suspend fun forgetVaultDocs(vaultId: String) {
            docs.keys.filter { it.first == vaultId }.toList().forEach { docs.remove(it) }
        }

        override suspend fun clearDocs() = docs.clear()

        override suspend fun putCandidate(row: PvVaultDocCandidateRow) {
            candidates[listOf(row.vaultId, row.docId, row.medium, row.reason)] = row
        }

        override suspend fun candidates(vaultId: String) =
            candidates.values.filter { it.vaultId == vaultId }.sortedByDescending { it.keptAtMs }

        override suspend fun candidateCount(vaultId: String) =
            candidates.values.count { it.vaultId == vaultId }

        override suspend fun forgetVaultCandidates(vaultId: String) {
            candidates.keys.filter { it[0] == vaultId }.toList().forEach { candidates.remove(it) }
        }

        override suspend fun clearCandidates() = candidates.clear()

        override suspend fun cursor(vaultId: String, medium: String, docId: String) =
            cursors[listOf(vaultId, docId, medium)]

        override suspend fun cursors(vaultId: String, medium: String) =
            cursors.values.filter { it.vaultId == vaultId && it.medium == medium }

        override suspend fun putCursor(row: PvVaultDocCursorRow) {
            cursors[listOf(row.vaultId, row.docId, row.medium)] = row
        }

        override suspend fun forgetCursor(vaultId: String, medium: String, docId: String) {
            cursors.remove(listOf(vaultId, docId, medium))
        }

        override suspend fun forgetVault(vaultId: String) {
            cursors.keys.filter { it[0] == vaultId }.toList().forEach { cursors.remove(it) }
        }

        override suspend fun clearCursors() = cursors.clear()
    }

    /** No isolation to simulate — the fake is a map, so the block is the transaction. */
    private object DirectTransactions : PvDocTransactions {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private val dao = FakeDao()
    private val keys = FakeKeys()

    private fun store(now: Long = 1_000L) = RoomPvVaultLocalStore(
        dao = dao,
        transactions = DirectTransactions,
        keys = keys,
        deviceId = { DEVICE_A },
        now = { now },
        nowIso = { "2026-09-02T10:00:00.000Z" },
        newWriteId = { countedUuid("cc", dao.docs.size + 1) },
    )

    private fun configureVault(
        vaultId: String = VAULT_ID,
        headerDocId: String = HEADER_DOC_ID,
        commonDocId: String = COMMON_DOC_ID,
    ) = dao.vaults.put(
        vaultId,
        PvVaultRow(
            id = vaultId,
            name = "Household",
            headerDocId = headerDocId,
            commonDocId = commonDocId,
            media = "server",
            driveConnectionId = null,
            keyFingerprint = "SGn1pC05gjstkyjs",
            retirementProofPublicKey = "cHVibGljLWtleQ",
            createdAt = "2026-08-01T08:00:00.000Z",
            updatedAt = "2026-08-01T08:00:00.000Z",
        ),
    )

    private fun seedCursor(docId: String) = dao.cursors.put(
        listOf(VAULT_ID, docId, PvMedium.SERVER.wire),
        PvVaultDocCursorRow(VAULT_ID, docId, PvMedium.SERVER.wire, "\"1\"", 1, "w1", 0L),
    )

    // ── snapshot ────────────────────────────────────────────────────────────

    @Test
    fun `a vault this device has no configuration for is null, not an empty vault`() = runTest {
        assertNull(store().snapshot(VAULT_ID))
    }

    @Test
    fun `a configuration row without its two doc ids is not an address to guess at`() = runTest {
        configureVault(headerDocId = "", commonDocId = "")
        assertNull(
            "an empty header address must never be treated as a doc id",
            store().snapshot(VAULT_ID),
        )
    }

    @Test
    fun `a locked vault snapshots empty rather than absent, so the chip can say LOCKED`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 3))
        keys.locked = true

        val snapshot = store().snapshot(VAULT_ID)
        assertNotNull("the device HAS this vault; it just cannot open it", snapshot)
        assertEquals(emptyList<PvLocalDoc>(), snapshot!!.docs)
        assertEquals(HEADER_DOC_ID, snapshot.directory.headerDocId)
    }

    @Test
    fun `what is adopted is what comes back, at the same version`() = runTest {
        configureVault()
        val document = portfolioDoc(transactions = listOf(entity("018f0000-0000-7000-8000-000000000101", rev = 4)))
        store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, document, 7))

        val snapshot = store().snapshot(VAULT_ID)!!
        assertEquals(1, snapshot.docs.size)
        val held = snapshot.doc(PORTFOLIO_A)!!
        assertEquals(7, held.docVersion)
        assertEquals(document, held.document)
        assertEquals(REF_PORTFOLIO_A, held.ref)
    }

    @Test
    fun `the row on disk is the envelope, not the document`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_HEADER, headerDoc(name = "Kitchen table"), 2))

        val row = dao.docs.getValue(VAULT_ID to HEADER_DOC_ID)
        val envelope = row.envelope!!
        assertEquals("the cache is ciphertext (§6), so the version rides in the row too", 2, row.docVersion)
        assertEquals(PvVaultContract.DOC_FORMAT_VERSION, row.formatVersion)
        assertEquals(envelope.size, row.sizeBytes)
        assertEquals(PvVaultContract.KIND_HEADER, row.docKind)
        assertNull("only a portfolio doc names a portfolio", row.portfolioId)
        assertTrue(
            "a locally cached doc must not be readable without K_c",
            "Kitchen table" !in String(envelope, Charsets.ISO_8859_1),
        )
        assertEquals(
            "the envelope's own header must name the same version the column does",
            2,
            headerOf(envelope).docVersion,
        )
    }

    @Test
    fun `a portfolio doc records its portfolio id`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        assertEquals(PORTFOLIO_A, dao.docs.getValue(VAULT_ID to PORTFOLIO_A).portfolioId)
    }

    // ── the version contract ────────────────────────────────────────────────

    @Test
    fun `adopt never bumps a version - the caller already defended that number`() = runTest {
        configureVault()
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 5))
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 5))
        assertEquals(5, dao.docs.getValue(VAULT_ID to PORTFOLIO_A).docVersion)
        // A merge that adopted an OLDER remote version verbatim must land as that
        // version, not as "whatever is bigger".
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 2))
        assertEquals(2, dao.docs.getValue(VAULT_ID to PORTFOLIO_A).docVersion)
    }

    @Test
    fun `commit is the only place a version is minted, and it mints exactly one`() = runTest {
        configureVault()
        val store = store()
        assertEquals(1, store.commit(VAULT_ID, REF_COMMON, commonDoc()).docVersion)
        assertEquals(2, store.commit(VAULT_ID, REF_COMMON, commonDoc()).docVersion)
        // From what STORAGE holds, not from what a caller remembers: an adopt at
        // 9 makes the next commit 10.
        store.adopt(VAULT_ID, PvLocalDoc(REF_COMMON, commonDoc(), 9))
        assertEquals(10, store.commit(VAULT_ID, REF_COMMON, commonDoc()).docVersion)
    }

    @Test
    fun `a vault that locked mid-pass refuses loudly instead of dropping the merge`() = runTest {
        configureVault()
        keys.locked = true
        val thrown = try {
            store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 4))
            null
        } catch (cause: VaultCryptoError) {
            cause
        }
        assertNotNull("a silent drop here would cost the merge; the engine relies on the throw", thrown)
        assertTrue("nothing may be written half-sealed", dao.docs.isEmpty())
    }

    @Test
    fun `a cached row that no longer opens is skipped, never deleted`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        val row = dao.docs.getValue(VAULT_ID to PORTFOLIO_A)
        // Flip a ciphertext byte: the GCM tag refuses it from here on.
        val damaged = row.envelope!!.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        dao.docs[VAULT_ID to PORTFOLIO_A] = row.copy(envelope = damaged)

        assertEquals(emptyList<PvLocalDoc>(), store().snapshot(VAULT_ID)!!.docs)
        assertTrue("those bytes may be the only copy of that version", dao.docs.isNotEmpty())
    }

    // ── candidates (§6/§16) ─────────────────────────────────────────────────

    @Test
    fun `a kept candidate carries the kind and version its cleartext header names`() = runTest {
        configureVault()
        val envelope = seal(portfolioDoc(), PORTFOLIO_A, docVersion = 12)
        store().keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, envelope, "It would not open.", 900L),
        )

        val kept = store().candidates(VAULT_ID).single()
        assertEquals(PORTFOLIO_A, kept.docId)
        assertEquals(PvMedium.SERVER, kept.medium)
        assertEquals(PvVaultContract.KIND_PORTFOLIO, kept.docKind)
        assertEquals(12, kept.docVersion)
        assertEquals(PvVaultContract.DOC_FORMAT_VERSION, kept.formatVersion)
        assertEquals("It would not open.", kept.reason)
        assertTrue(kept.hasEnvelope)
    }

    @Test
    fun `a refusal with no bytes is still kept - somebody has to be told`() = runTest {
        store().keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "The store refused it.", 900L),
        )
        val kept = store().candidates(VAULT_ID).single()
        assertEquals(false, kept.hasEnvelope)
        assertNull(kept.docKind)
        assertNull(kept.docVersion)
    }

    @Test
    fun `the same refusal at the same address is one candidate, not one per pass`() = runTest {
        val store = store()
        repeat(5) { pass ->
            store.keepCandidate(
                PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "Corrupt.", 100L + pass),
            )
        }
        assertEquals(1, store.candidateCount(VAULT_ID))
        assertEquals("the newest keep wins", 104L, store.candidates(VAULT_ID).single().keptAtMs)

        // A DIFFERENT refusal is a different candidate and must survive.
        store.keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "Foreign vault.", 200L),
        )
        assertEquals(2, store.candidateCount(VAULT_ID))
    }

    @Test
    fun `keeping a candidate never touches the local doc at that address`() = runTest {
        configureVault()
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 3))
        store.keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "Corrupt.", 100L),
        )
        assertEquals(3, store.snapshot(VAULT_ID)!!.doc(PORTFOLIO_A)!!.docVersion)
    }

    // ── the cursor rule, enforced by the store ──────────────────────────────

    @Test
    fun `discarding a vault discards its cursors in the same breath`() = runTest {
        configureVault()
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        store.keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "Corrupt.", 100L),
        )
        seedCursor(PORTFOLIO_A)
        assertNotNull(dao.cursor(VAULT_ID, PvMedium.SERVER.wire, PORTFOLIO_A))

        store.forgetVault(VAULT_ID)

        assertNull(
            "a validator that outlives its state tells a medium to skip sending data we no longer have",
            dao.cursor(VAULT_ID, PvMedium.SERVER.wire, PORTFOLIO_A),
        )
        assertTrue(dao.docs.isEmpty())
        assertTrue(dao.candidates.isEmpty())
        assertTrue("the configuration mirror goes too", dao.vaults.isEmpty())
    }

    @Test
    fun `one vault's discard leaves another vault's state alone`() = runTest {
        configureVault()
        configureVault(vaultId = OTHER_VAULT_ID)
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        dao.docs[OTHER_VAULT_ID to PORTFOLIO_B] = dao.docs.getValue(VAULT_ID to PORTFOLIO_A)
            .copy(vaultId = OTHER_VAULT_ID, docId = PORTFOLIO_B)
        dao.cursors[listOf(OTHER_VAULT_ID, PORTFOLIO_B, PvMedium.SERVER.wire)] =
            PvVaultDocCursorRow(OTHER_VAULT_ID, PORTFOLIO_B, PvMedium.SERVER.wire, "\"9\"", 9, "w9", 0L)

        store.forgetVault(VAULT_ID)

        assertNotNull(dao.doc(OTHER_VAULT_ID, PORTFOLIO_B))
        assertNotNull(dao.cursor(OTHER_VAULT_ID, PvMedium.SERVER.wire, PORTFOLIO_B))
    }

    @Test
    fun `account teardown leaves no validator behind either`() = runTest {
        configureVault()
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        store.keepCandidate(
            PvRejectedCandidate(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, null, "Corrupt.", 100L),
        )
        seedCursor(PORTFOLIO_A)

        store.clear()

        assertTrue(dao.docs.isEmpty())
        assertTrue(dao.candidates.isEmpty())
        assertTrue(dao.cursors.isEmpty())
        assertTrue(dao.vaults.isEmpty())
    }

    // ── the key facts a locked device still needs ───────────────────────────

    @Test
    fun `the header facts come out of any held envelope's cleartext header`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_HEADER, headerDoc(), 1))

        val facts = RoomPvVaultHeaderFacts(dao).facts(VAULT_ID)
        assertNotNull("the slots ride in cleartext precisely so this works", facts)
        assertEquals(KEY_ID, facts!!.keyId)
        assertEquals(ACCOUNT_BINDING, facts.accountBinding)
        assertEquals(keySlots().map { it.keyId }, facts.keySlots.map { it.keyId })
    }

    @Test
    fun `header facts fall back to any other doc when the header is not held`() = runTest {
        configureVault()
        store().adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        assertEquals(KEY_ID, RoomPvVaultHeaderFacts(dao).facts(VAULT_ID)!!.keyId)
    }

    @Test
    fun `a device holding nothing has no facts and therefore no key`() = runTest {
        configureVault()
        assertNull(RoomPvVaultHeaderFacts(dao).facts(VAULT_ID))
    }

    @Test
    fun `an unreadable envelope is skipped while another still answers`() = runTest {
        configureVault()
        val store = store()
        store.adopt(VAULT_ID, PvLocalDoc(REF_HEADER, headerDoc(), 1))
        store.adopt(VAULT_ID, PvLocalDoc(REF_PORTFOLIO_A, portfolioDoc(), 1))
        dao.docs[VAULT_ID to HEADER_DOC_ID] =
            dao.docs.getValue(VAULT_ID to HEADER_DOC_ID).copy(envelope = ByteArray(4))

        assertEquals(KEY_ID, RoomPvVaultHeaderFacts(dao).facts(VAULT_ID)!!.keyId)
    }
}
