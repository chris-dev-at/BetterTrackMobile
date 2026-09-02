package at.bettertrack.app.vault.pv.sync

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.vault.pv.envelope.PvHeaderPortfolio
import at.bettertrack.app.vault.pv.envelope.PvPortfolioDoc
import at.bettertrack.app.vault.pv.store.PvDocEtag
import at.bettertrack.app.vault.pv.store.PvDocPrecondition
import at.bettertrack.app.vault.pv.store.PvDocReadOutcome
import at.bettertrack.app.vault.pv.store.PvDocRef
import at.bettertrack.app.vault.pv.store.PvDocWriteOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-vault sync engine, against a medium that enforces real CAS.
 *
 * Every test here is one sentence from `paranoid-design.md` §6 or from the round
 * brief, and the ones worth naming are the four that are easy to get wrong and
 * expensive to get wrong:
 *
 * - **a lost response that had committed is not a conflict.** Merging against
 *   your own bytes is harmless once and a pointless generation every time the
 *   network flaps; the remote envelope's cleartext `writeId` is what tells this
 *   device's own write apart from another device's.
 * - **an absent remote never wipes local.** Local holds the doc, so local is
 *   authoritative and the copy is re-created.
 * - **a foreign or unopenable envelope is kept, never adopted.**
 * - **two vaults never wait for each other.** A vault whose medium hangs must
 *   not hold up a vault whose medium is fine — which is the entire reason this
 *   round exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PvVaultSyncEngineTest {

    private val local = FakeLocalStore()
    private val keys = FakeKeys()
    private val cursors = InMemoryPvDocCursorStore()
    private val medium = FakeMedium()
    private var writeIds = 0
    private var clock = 1_000L

    /**
     * [scope] defaults to `backgroundScope` so the follow-up push a merge
     * requests cannot interfere with a test that is asserting on one pass —
     * `advanceUntilIdle()` drains FOREGROUND work only, so background requests
     * stay parked. The two tests that are *about* scheduling therefore pass the
     * test scope itself, which is the half `advanceUntilIdle()` does run.
     */
    private fun TestScope.engine(
        media: suspend (String) -> List<PvDocMedium> = { listOf(medium) },
        debounceMs: Long = 0L,
        scope: CoroutineScope = backgroundScope,
    ) = PvVaultSyncEngine(
        scope = scope,
        local = local,
        keys = keys,
        media = media,
        cursors = cursors,
        deviceId = { DEVICE_A },
        now = { clock },
        nowIso = { "2026-09-02T12:00:00.000Z" },
        newWriteId = { countedUuid("cc", ++writeIds) },
        debounceMs = debounceMs,
    )

    private fun transactions(vararg ids: Int) =
        ids.map { entity(countedUuid("51", it), rev = it, payload = it) }

    // ── First contact ───────────────────────────────────────────────────────

    @Test
    fun `a first push creates every doc and records a cursor for each`() = runTest {
        local.seed(REF_HEADER, headerDoc(), docVersion = 1)
        local.seed(REF_COMMON, commonDoc(), docVersion = 1)
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)

        val state = engine().pushNow(VAULT_ID)

        assertEquals(
            listOf(HEADER_DOC_ID, COMMON_DOC_ID, PORTFOLIO_A),
            medium.committed,
        )
        listOf(HEADER_DOC_ID, COMMON_DOC_ID, PORTFOLIO_A).forEach { docId ->
            val cursor = cursors.cursor(VAULT_ID, PvMedium.SERVER, docId)
            assertEquals("cursor for $docId", 1, cursor?.docVersion)
            assertEquals(medium.etagOf(docId), cursor?.etag)
        }
        assertEquals(PvVaultSyncStatus.Idle, state.status)
        assertEquals(emptySet<String>(), state.pendingDocIds)
        assertEquals(clock, state.lastSyncedAtMs)
    }

    @Test
    fun `a portfolio edit re-encrypts exactly one doc`() = runTest {
        local.seed(REF_HEADER, headerDoc(), docVersion = 1)
        local.seed(REF_COMMON, commonDoc(), docVersion = 1)
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        val engine = engine()
        engine.pushNow(VAULT_ID)
        medium.writeAttempts.clear()
        medium.committed.clear()

        local.edit(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1, 2)))
        engine.pushNow(VAULT_ID)

        // The header and the common doc are untouched by a portfolio edit, so
        // they are not encrypted, not sent, and not even looked at.
        assertEquals(listOf(PORTFOLIO_A), medium.writeAttempts)
        assertEquals(listOf(PORTFOLIO_A), medium.committed)
        assertEquals(2, medium.versionOf(PORTFOLIO_A))
    }

    @Test
    fun `a doc every medium already holds costs nothing`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        val engine = engine()
        engine.pushNow(VAULT_ID)
        medium.writeAttempts.clear()

        val state = engine.pushNow(VAULT_ID)

        assertEquals(emptyList<String>(), medium.writeAttempts)
        assertEquals(PvVaultSyncStatus.Idle, state.status)
    }

    @Test
    fun `a locked vault is saved locally and writes nothing`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        keys.locked = true

        val state = engine().pushNow(VAULT_ID)

        assertEquals(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.LOCKED), state.status)
        assertEquals(emptyList<String>(), medium.writeAttempts)
    }

    // ── Conflict ────────────────────────────────────────────────────────────

    @Test
    fun `a stale precondition pulls, merges and pushes the successor`() = runTest {
        // Another device wrote v2 while this one was offline.
        val theirs = portfolioDoc(transactions = transactions(9))
        medium.seed(REF_PORTFOLIO_A, seal(theirs, PORTFOLIO_A, docVersion = 2))
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 2)
        cursors.put(
            PvDocCursor(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, PvDocEtag("\"stale\""), 1, "old", 0),
        )

        val state = engine().pushNow(VAULT_ID)

        // max(2, 2) + 1 — §6 rule 3.
        assertEquals(3, medium.versionOf(PORTFOLIO_A))
        val merged = local.doc(REF_PORTFOLIO_A)!!
        assertEquals(3, merged.docVersion)
        assertEquals(
            "both parents' rows survive the merge",
            2,
            (merged.document as PvPortfolioDoc).entities.getValue("transaction").size,
        )
        assertEquals(1, local.adoptions.size)
        assertEquals(PvVaultSyncStatus.Idle, state.status)
    }

    @Test
    fun `a lost response that had committed is recognised as landed, never merged`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        medium.swallowNextResponse = true
        // ONE engine across both passes on purpose: the memory that recognises
        // this device's own key is process-local, and the test would be checking
        // a different property if each pass got a fresh one.
        val engine = engine()

        val stalled = engine.pushNow(VAULT_ID)
        assertEquals(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE), stalled.status)
        assertNull(
            "no cursor may be recorded for a write we never saw acknowledged",
            cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A),
        )
        assertEquals(listOf(PORTFOLIO_A), medium.committed)

        // The retry meets a 412 that looks exactly like another device's edit.
        val settled = engine.pushNow(VAULT_ID)

        assertEquals("the doc must not be written a second time", listOf(PORTFOLIO_A), medium.committed)
        assertEquals("nothing may be merged against this device's own bytes", 0, local.adoptions.size)
        assertEquals(1, cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A)?.docVersion)
        assertEquals(PvVaultSyncStatus.Idle, settled.status)
    }

    @Test
    fun `a replace against a doc that is gone re-creates it at the local version`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 4)
        cursors.put(
            PvDocCursor(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, PvDocEtag("\"7\""), 3, "old", 0),
        )

        val state = engine().pushNow(VAULT_ID)

        assertEquals("the replace and then the create", 2, medium.writeAttempts.size)
        assertEquals(listOf(PORTFOLIO_A), medium.committed)
        assertEquals("re-created at the LOCAL version, never wound back", 4, medium.versionOf(PORTFOLIO_A))
        assertEquals(PvVaultSyncStatus.Idle, state.status)
    }

    @Test
    fun `bounded conflict retries stop rather than loop`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 2)
        medium.seed(REF_PORTFOLIO_A, seal(portfolioDoc(transactions = transactions(9)), PORTFOLIO_A, 2))
        cursors.put(
            PvDocCursor(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, PvDocEtag("\"stale\""), 1, "old", 0),
        )
        // A device that wins every race: the validator is stale again the moment
        // it is read.
        medium.refuseWrites = { PvDocWriteOutcome.PreconditionStale(PvDocEtag("\"moved\"")) }

        val state = engine().pushNow(VAULT_ID)

        val failure = (state.status as PvVaultSyncStatus.Error).failure
        assertEquals(PvSyncFailureReason.CONFLICT_UNRESOLVED, failure.reason)
        assertTrue("retries must be bounded", medium.writeAttempts.size <= 5)
        assertEquals(setOf(PORTFOLIO_A), state.pendingDocIds)
    }

    // ── Pull ────────────────────────────────────────────────────────────────

    @Test
    fun `a pull adopts a doc this device has never held and advances the cursor`() = runTest {
        local.seed(REF_HEADER, headerDoc(portfolios = listOf(PvHeaderPortfolio(PORTFOLIO_A, "Main"))), 1)
        medium.seed(REF_PORTFOLIO_A, seal(portfolioDoc(transactions = transactions(4)), PORTFOLIO_A, 4))

        engine().pullNow(VAULT_ID)

        val adopted = local.doc(REF_PORTFOLIO_A)
        assertNotNull("the roster named it, so the pull had to reach it", adopted)
        assertEquals(4, adopted!!.docVersion)
        assertEquals(4, cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A)?.docVersion)
    }

    @Test
    fun `a header adopted in this pass makes its new portfolio reachable in the same pass`() = runTest {
        // This device knows nothing but the two singletons; the header on the
        // medium is what names the portfolio another endpoint moved in.
        local.seed(REF_HEADER, headerDoc(portfolios = emptyList()), docVersion = 1)
        medium.seed(
            REF_HEADER,
            seal(headerDoc(portfolios = listOf(PvHeaderPortfolio(PORTFOLIO_B, "Pension"))), HEADER_DOC_ID, 2),
        )
        medium.seed(REF_PORTFOLIO_B, seal(portfolioDoc(PORTFOLIO_B, transactions(3)), PORTFOLIO_B, 1))

        engine().pullNow(VAULT_ID)

        assertNotNull("the pass that learns the roster must also fetch what it names",
            local.doc(REF_PORTFOLIO_B))
    }

    @Test
    fun `a validator the local state still stands behind makes a read a no-op`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        val engine = engine()
        engine.pushNow(VAULT_ID)

        engine.pullNow(VAULT_ID)

        assertTrue("the cursor's claim held, so the read was conditional", medium.notModified >= 1)
        assertEquals("a 304 adopts nothing", 0, local.adoptions.size)
    }

    @Test
    fun `an absent remote never wipes local`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 3)
        cursors.put(
            PvDocCursor(VAULT_ID, PORTFOLIO_A, PvMedium.SERVER, PvDocEtag("\"3\""), 3, "old", 0),
        )

        engine().pullNow(VAULT_ID)

        val held = local.doc(REF_PORTFOLIO_A)
        assertEquals("local is authoritative when the remote holds nothing", 3, held?.docVersion)
        assertEquals(1, (held!!.document as PvPortfolioDoc).entities.getValue("transaction").size)
        assertNull(
            "the validator named bytes that no longer exist, so it must go",
            cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A),
        )
    }

    @Test
    fun `an envelope addressed to another vault is kept as a candidate, never adopted`() = runTest {
        val mine = portfolioDoc(transactions = transactions(1))
        local.seed(REF_PORTFOLIO_A, mine, docVersion = 1)
        val foreign = seal(portfolioDoc(transactions = transactions(8)), PORTFOLIO_A, 5, vaultId = OTHER_VAULT_ID)
        medium.readOverride = { ref ->
            if (ref.docId == PORTFOLIO_A) {
                PvDocReadOutcome.Loaded(ref, foreign, PvDocEtag("\"5\""), headerOf(foreign))
            } else {
                null
            }
        }

        val state = engine().pullNow(VAULT_ID)

        val candidate = local.candidates.single()
        assertEquals(PORTFOLIO_A, candidate.docId)
        assertTrue("${candidate.reason}", candidate.reason.contains("belongs to vault $OTHER_VAULT_ID"))
        assertTrue("the bytes are kept for the restore picker", candidate.envelope!!.isNotEmpty())
        assertEquals(0, local.adoptions.size)
        assertEquals(1, local.doc(REF_PORTFOLIO_A)!!.docVersion)
        assertEquals(
            PvSyncFailureReason.CANDIDATE_KEPT,
            (state.status as PvVaultSyncStatus.Error).failure.reason,
        )
    }

    // ── Coalescing and per-vault isolation ──────────────────────────────────

    @Test
    fun `ten edits coalesce into one push of the latest state`() = runTest {
        val engine = engine(debounceMs = PvVaultSyncEngine.DEBOUNCE_MS, scope = this)

        repeat(10) { round ->
            local.edit(
                REF_PORTFOLIO_A,
                portfolioDoc(transactions = transactions(*(1..round + 1).toList().toIntArray())),
            )
            engine.requestPush(VAULT_ID)
        }
        advanceUntilIdle()

        assertEquals("a burst of edits is ONE push", listOf(PORTFOLIO_A), medium.writeAttempts)
        assertEquals("and it carries the tenth state", 10, medium.versionOf(PORTFOLIO_A))
    }

    @Test
    fun `a vault whose medium hangs does not delay another vault`() = runTest {
        val stuck = HangingMedium(VAULT_ID)
        val healthy = FakeMedium(OTHER_VAULT_ID)
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        local.seed(REF_PORTFOLIO_B, portfolioDoc(PORTFOLIO_B, transactions(2)), docVersion = 1, vaultId = OTHER_VAULT_ID)

        val engine = engine(
            media = { vaultId -> listOf(if (vaultId == VAULT_ID) stuck else healthy) },
            debounceMs = PvVaultSyncEngine.DEBOUNCE_MS,
            scope = this,
        )
        engine.requestPush(VAULT_ID)
        engine.requestPush(OTHER_VAULT_ID)
        advanceUntilIdle()

        assertEquals("vault B must not wait behind vault A", listOf(PORTFOLIO_B), healthy.committed)
        assertEquals(PvVaultSyncStatus.Idle, engine.states.value.getValue(OTHER_VAULT_ID).status)
        assertTrue("vault A is still in flight", stuck.entered)
        assertNull("and it has recorded nothing", cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A))

        // Let vault A finish so the test leaves no coroutine behind; its own
        // outcome is a plain "saved on this device", which is the point — a
        // stuck medium is a state, never a stalled app.
        stuck.release()
        advanceUntilIdle()
        assertEquals(
            PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE),
            engine.states.value.getValue(VAULT_ID).status,
        )
    }

    @Test
    fun `a failing medium leaves the doc pending and the state honest`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        medium.refuseWrites = {
            PvDocWriteOutcome.Transport(BtApiError(0, BtApiError.Codes.NETWORK, "No route to host."))
        }

        val state = engine().pushNow(VAULT_ID)

        assertEquals(PvVaultSyncStatus.SavedLocally(PvSavedLocallyReason.OFFLINE), state.status)
        assertEquals(setOf(PORTFOLIO_A), state.pendingDocIds)
        assertTrue(state.hasUnpushedChanges)
        assertNull(state.lastSyncedAtMs)
    }

    @Test
    fun `forgetting a vault drops its cursors and its state row`() = runTest {
        local.seed(REF_PORTFOLIO_A, portfolioDoc(transactions = transactions(1)), docVersion = 1)
        val engine = engine()
        engine.pushNow(VAULT_ID)
        assertNotNull(cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A))

        engine.forget(VAULT_ID)

        assertNull(
            "a validator must never outlive the state it claims",
            cursors.cursor(VAULT_ID, PvMedium.SERVER, PORTFOLIO_A),
        )
        assertNull(engine.states.value[VAULT_ID])
    }

    /**
     * A medium that does not answer until told to — the "is A blocking B?" probe.
     *
     * It parks on a deferred rather than looping, so the scheduler sees no
     * runnable task for vault A while vault B's pass runs; [release] then lets A
     * finish, so the test leaves nothing behind.
     */
    private class HangingMedium(override val vaultId: String) : PvDocMedium {
        override val medium: PvMedium get() = PvMedium.SERVER
        private val gate = CompletableDeferred<Unit>()
        var entered = false
            private set

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun read(ref: PvDocRef, ifNoneMatch: PvDocEtag?): PvDocReadOutcome {
            entered = true
            gate.await()
            return PvDocReadOutcome.Absent
        }

        override suspend fun write(
            ref: PvDocRef,
            precondition: PvDocPrecondition,
            envelope: ByteArray,
        ): PvDocWriteOutcome {
            entered = true
            gate.await()
            return PvDocWriteOutcome.Transport(BtApiError(0, BtApiError.Codes.NETWORK, "No route to host."))
        }
    }
}
