package at.bettertrack.app.vault

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Severed-fork MIRRORCHAIN provenance carriage — ported from the platform's
 * `mirrorProvenance.test.ts` (vendored under
 * `tools/domain-vectors/vendor/web-vault/`).
 *
 * The app never authors one of these, but every merge and every write carries
 * them, and the §7.1 rule is that **a merge must never be the step that loses an
 * identity map**. These tests pin the two ways that could go wrong: dropping an
 * identity that is still needed, and keeping a stale one the server would reject
 * (which would block the account from ever leaving paranoid mode).
 *
 * **Skipped, with reason:** the two `captureForkProvenanceIntoVault` cases
 * ("captures into the vault through one mutation and no-ops when unchanged",
 * "drops a captured entry whose local row is already deleted"). They drive a
 * `VaultSyncEngine` fake, and the sync engine is W4 — the pure logic they
 * compose is covered below.
 */
class MirrorProvenanceTest {

    private val chain = "018f0000-0000-7000-8000-0000000000c1"
    private val otherChain = "018f0000-0000-7000-8000-0000000000c2"
    private val portfolio = "018f0000-0000-7000-8000-0000000000c3"
    private val txMirror = "018f0000-0000-7000-8000-0000000000c4"
    private val txLocal = "018f0000-0000-7000-8000-0000000000c5"
    private val sourceMirror = "018f0000-0000-7000-8000-0000000000c6"
    private val sourceLocal = "018f0000-0000-7000-8000-0000000000c7"
    private val deletedLocal = "018f0000-0000-7000-8000-0000000000c8"
    private val device = "018f0000-0000-7000-8000-0000000000c9"
    private val membership = "018f0000-0000-7000-8000-0000000000ca"
    private val rejoinedMembership = "018f0000-0000-7000-8000-0000000000cb"
    private val rejoinedPortfolio = "018f0000-0000-7000-8000-0000000000cc"
    private val rejoinedLocal = "018f0000-0000-7000-8000-0000000000cd"

    private val transactionEntry = VaultMirrorProvenance(
        chainId = chain, membershipId = membership, kind = "transaction",
        mirrorId = txMirror, portfolioId = portfolio, localId = txLocal,
    )
    private val sourceEntry = VaultMirrorProvenance(
        chainId = chain, membershipId = membership, kind = "cash_source",
        mirrorId = sourceMirror, portfolioId = portfolio, localId = sourceLocal,
    )

    /** The same chain and logical entity, kept by a SECOND membership's copy. */
    private val rejoinedEntry = VaultMirrorProvenance(
        chainId = chain, membershipId = rejoinedMembership, kind = "transaction",
        mirrorId = txMirror, portfolioId = rejoinedPortfolio, localId = rejoinedLocal,
    )

    private fun entity(id: String, deletedAt: String? = null) = VaultEntity(
        id = id, rev = 1, editedAt = "2026-07-24T10:00:00.000Z", editedBy = device,
        deletedAt = deletedAt, data = JsonObject(emptyMap()),
    )

    private fun document(mirrorProvenance: List<VaultMirrorProvenance>? = emptyList()) = VaultDocument.v1(
        entities = linkedMapOf(
            "transaction" to listOf(
                entity(txLocal),
                entity(rejoinedLocal),
                entity(deletedLocal, "2026-07-25T10:00:00.000Z"),
            ),
            "cashSource" to listOf(entity(sourceLocal)),
        ),
        mirrorProvenance = mirrorProvenance,
    )

    private inline fun expectDocumentInvalid(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: expected VaultCryptoError[document-invalid]")
        } catch (e: VaultCryptoError) {
            assertEquals(what, VaultCryptoErrorCode.DOCUMENT_INVALID, e.code)
        }
    }

    @Test
    fun ordersAndDeDuplicatesOneCaptureDeterministically() {
        val normalized = normalizeForkProvenance(listOf(sourceEntry, transactionEntry, sourceEntry))
        assertEquals(listOf(sourceEntry, transactionEntry), normalized)
        assertEquals(
            "input order must not matter",
            normalized,
            normalizeForkProvenance(listOf(transactionEntry, sourceEntry)),
        )
    }

    @Test
    fun failsClosedOnTwoLocalRowsClaimingOneLogicalIdentity() {
        expectDocumentInvalid("same logical key, different local row") {
            normalizeForkProvenance(listOf(transactionEntry, transactionEntry.copy(localId = deletedLocal)))
        }
        expectDocumentInvalid("same local row, different logical identity") {
            normalizeForkProvenance(listOf(transactionEntry, transactionEntry.copy(mirrorId = sourceMirror)))
        }
    }

    @Test
    fun rejectsAMalformedEntryRatherThanCarryingItIntoTheVault() {
        // `cashMovement` is the VAULT ENTITY kind; the provenance `kind` is the
        // MIRROR ROW kind (`cash_movement`). Accepting the wrong vocabulary here
        // would silently point the prune step at a table that never matches.
        expectDocumentInvalid("entity-kind spelling in a mirror-row-kind field") {
            normalizeForkProvenance(listOf(transactionEntry.copy(kind = "cashMovement")))
        }
        expectDocumentInvalid("non-uuid id") {
            normalizeForkProvenance(listOf(transactionEntry.copy(localId = "not-a-uuid")))
        }
    }

    @Test
    fun mergesTwoReplicasByUnionInEitherDirection() {
        assertEquals(
            mergeForkProvenance(listOf(sourceEntry), listOf(transactionEntry)),
            mergeForkProvenance(listOf(transactionEntry), listOf(sourceEntry)),
        )
        assertEquals(2, mergeForkProvenance(listOf(transactionEntry), listOf(sourceEntry)).size)
        assertEquals(
            listOf(transactionEntry),
            mergeForkProvenance(listOf(transactionEntry), listOf(transactionEntry)),
        )
        assertEquals(emptyList<VaultMirrorProvenance>(), mergeForkProvenance(null, null))
    }

    @Test
    fun onlyDominatesWhenItAlreadyContainsTheOtherReplicaCapture() {
        assertTrue(forkProvenanceDominates(listOf(transactionEntry, sourceEntry), listOf(transactionEntry)))
        assertFalse(forkProvenanceDominates(listOf(transactionEntry), listOf(transactionEntry, sourceEntry)))
        assertFalse(
            "a same-keyed entry with different content does not dominate",
            forkProvenanceDominates(listOf(transactionEntry), listOf(transactionEntry.copy(chainId = otherChain))),
        )
    }

    @Test
    fun prunesAnEntryWhoseLocalRowWasDeletedOrNeverExisted() {
        val stale = transactionEntry.copy(mirrorId = sourceMirror, localId = deletedLocal)
        val missing = transactionEntry.copy(mirrorId = otherChain, localId = otherChain)
        assertEquals(
            listOf(transactionEntry),
            pruneForkProvenance(listOf(transactionEntry, stale, missing), document().entities),
        )
    }

    @Test
    fun foldsACaptureIntoTheDocumentIdempotently() {
        val once = withForkProvenance(document(), listOf(transactionEntry, sourceEntry))
        val twice = withForkProvenance(once, listOf(transactionEntry, sourceEntry))
        assertEquals(listOf(sourceEntry, transactionEntry), once.mirrorProvenance)
        assertEquals(once.mirrorProvenance, twice.mirrorProvenance)
        assertEquals(
            "folding must not disturb the entity graph",
            jsJsonStringify(once.toJson()),
            jsJsonStringify(twice.toJson()),
        )
    }

    /**
     * Re-joining a chain is a normal flow: the second membership gets its own
     * copy, so one chain can hold two retained forks that both kept the same
     * logical entity. Keying by chain alone would call that a fatal duplicate —
     * which is exactly why [MirrorProvenance] keys by membership.
     */
    @Test
    fun keepsTwoRetainedForksOfOneChainApartByMembership() {
        assertEquals(2, normalizeForkProvenance(listOf(transactionEntry, rejoinedEntry)).size)
        assertFalse(forkProvenanceDominates(listOf(transactionEntry), listOf(rejoinedEntry)))
        expectDocumentInvalid("...but one local row still cannot serve two memberships") {
            normalizeForkProvenance(listOf(transactionEntry, rejoinedEntry.copy(localId = txLocal)))
        }
    }

    @Test
    fun neverInventsTheKeyOnADocumentThatHasNone() {
        val withoutKey = document(mirrorProvenance = null)
        assertNull(carriedForkProvenance(withoutKey))
        assertEquals(
            "withForkProvenance must return the document untouched",
            jsJsonStringify(withoutKey.toJson()),
            jsJsonStringify(withForkProvenance(withoutKey, emptyList()).toJson()),
        )
        assertEquals(listOf(transactionEntry), carriedForkProvenance(document(listOf(transactionEntry))))
    }
}
