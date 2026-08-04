package at.bettertrack.app.vault

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The four binding merge rules (plan §2.6 / `docs/paranoid-design.md` §4).
 *
 * Ported from the platform's own `merge.test.ts` (vendored at
 * `tools/domain-vectors/vendor/web-vault/merge.test.ts`) and organised so each
 * test names the rule it defends — a failure here should say *which guarantee*
 * broke, not merely that a merge returned something unexpected.
 *
 * > **R1** Per entity `id`: higher `rev` → later `editedAt` → lexicographically
 * >        higher `editedBy`. Total determinism.
 * > **R2** Tombstone vs concurrent edit ⇒ **the edit wins**.
 * > **R3** Merged `vaultVersion = max(parents) + 1`, recorded in `mergeLog`,
 * >        capped at 20.
 * > **R4** Whole-blob fallback: highest readable wins, corrupt retained — i.e.
 * >        malformed material fails closed instead of being dropped.
 *
 * Everything is asserted in **both parent orders**. A merge rule that is not
 * order-invariant is not a merge rule: two devices would converge on different
 * documents and CAS-fight forever.
 */
class VaultMergeTest {

    private val entityA = "018f0000-0000-7000-8000-000000000001"
    private val entityB = "018f0000-0000-7000-8000-000000000002"
    private val deviceA = "018f0000-0000-7000-8000-00000000000a"
    private val deviceB = "018f0000-0000-7000-8000-00000000000b"
    private val mergeDevice = "018f0000-0000-7000-8000-00000000000c"
    private val mergedAt = "2026-07-25T12:00:00.000Z"

    /** `entity()` (merge.test.ts:25-34). */
    private fun entity(
        id: String = entityA,
        rev: Int = 1,
        editedAt: String = "2026-07-25T10:00:00Z",
        editedBy: String = deviceA,
        deletedAt: String? = null,
        data: JsonObject = JsonObject(mapOf("amount" to JsonPrimitive(1))),
    ) = VaultEntity(id, rev, editedAt, editedBy, deletedAt, data)

    /** `document()` (merge.test.ts:37-47) — note `mirrorProvenance` defaults to `[]`. */
    private fun document(
        entities: List<VaultEntity>,
        mergeLog: List<VaultMergeRecord> = emptyList(),
        mirrorProvenance: List<VaultMirrorProvenance> = emptyList(),
    ) = VaultDocument.v1(
        entities = linkedMapOf("transaction" to entities),
        mergeLog = mergeLog,
        mirrorProvenance = mirrorProvenance,
    )

    /** `merge()` (merge.test.ts:49-63). */
    private fun merge(
        left: VaultDocument,
        right: VaultDocument,
        leftVersion: Int = 4,
        rightVersion: Int = 4,
        deviceId: String = mergeDevice,
        at: String = mergedAt,
        forceDivergent: Boolean = false,
    ) = mergeVaultDocuments(
        MergeVaultDocumentsInput(left, leftVersion, right, rightVersion, forceDivergent, deviceId, at)
    )

    private fun transactionsOf(result: MergedVaultDocument): List<VaultEntity> =
        result.document.entities["transaction"].orEmpty()

    private fun assertSameDocument(what: String, expected: VaultDocument, actual: VaultDocument) =
        assertEquals(what, jsJsonStringify(expected.toJson()), jsJsonStringify(actual.toJson()))

    private fun assertSameDocument(expected: VaultDocument, actual: VaultDocument) =
        assertSameDocument("documents must serialize identically", expected, actual)

    private inline fun expectDocumentInvalid(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: expected VaultCryptoError[document-invalid], nothing was thrown")
        } catch (e: VaultCryptoError) {
            assertEquals("$what: error code", VaultCryptoErrorCode.DOCUMENT_INVALID, e.code)
        }
    }

    // =======================================================================
    // The determinism matrix — merge.test.ts:65-198
    // =======================================================================

    /** One row of `mergeMatrix` (merge.test.ts:65-197). */
    private data class MatrixCase(
        val name: String,
        val left: VaultEntity,
        val right: VaultEntity,
        val winner: VaultEntity,
        val rule: String,
    )

    private fun matrix(): List<MatrixCase> = listOf(
        MatrixCase(
            "higher revision",
            entity(rev = 2, data = amount(2)),
            entity(rev = 1, editedAt = "2026-07-25T11:00:00Z", editedBy = deviceB, data = amount(3)),
            entity(rev = 2, data = amount(2)),
            "R1 rev beats a later editedAt and a higher editedBy",
        ),
        MatrixCase(
            "later tombstone among tombstones",
            entity(rev = 2, editedAt = "2026-07-25T10:00:00Z", deletedAt = "2026-07-25T10:00:00Z", data = empty()),
            entity(rev = 2, editedAt = "2026-07-25T10:01:00Z", deletedAt = "2026-07-25T10:01:00Z", data = empty()),
            entity(rev = 2, editedAt = "2026-07-25T10:01:00Z", deletedAt = "2026-07-25T10:01:00Z", data = empty()),
            "R1 two tombstones still fall through to editedAt",
        ),
        MatrixCase(
            "live edit against a concurrent tombstone",
            entity(
                rev = 2, editedAt = "2026-07-25T11:00:00Z", editedBy = deviceB,
                deletedAt = "2026-07-25T11:00:00Z", data = empty(),
            ),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00Z", editedBy = deviceA, data = amount(2)),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00Z", editedBy = deviceA, data = amount(2)),
            "R2 the EDIT wins even though the tombstone is an hour later",
        ),
        MatrixCase(
            "re-deletion after resurrection",
            entity(rev = 2, editedAt = "2026-07-25T10:00:00Z", editedBy = deviceB, data = amount(2)),
            entity(
                rev = 3, editedAt = "2026-07-25T11:00:00Z", editedBy = deviceA,
                deletedAt = "2026-07-25T11:00:00Z", data = empty(),
            ),
            entity(
                rev = 3, editedAt = "2026-07-25T11:00:00Z", editedBy = deviceA,
                deletedAt = "2026-07-25T11:00:00Z", data = empty(),
            ),
            "R2 is scoped to EQUAL rev — a higher-rev tombstone still wins",
        ),
        MatrixCase(
            "later normalized sub-second instant",
            entity(rev = 2, editedAt = "2026-07-25T10:00:00Z", data = amount(1)),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00.001Z", data = amount(2)),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00.001Z", data = amount(2)),
            "R1 sub-second precision orders correctly",
        ),
        MatrixCase(
            "later instant against a seconds-omitted instant",
            entity(rev = 2, editedAt = "2026-07-25T10:00Z", data = amount(1)),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00.001Z", data = amount(2)),
            entity(rev = 2, editedAt = "2026-07-25T10:00:00.001Z", data = amount(2)),
            "R1 a seconds-omitted instant normalizes rather than string-compares",
        ),
        MatrixCase(
            "lexicographically higher device at the same instant",
            entity(rev = 2, editedBy = deviceA, data = amount(1)),
            entity(rev = 2, editedBy = deviceB, data = amount(2)),
            entity(rev = 2, editedBy = deviceB, data = amount(2)),
            "R1 editedBy is the last deterministic tie-break before content",
        ),
        MatrixCase(
            "canonical whole-entity tie-break for differing payloads",
            entity(rev = 2, data = amount(1)),
            entity(rev = 2, data = amount(2)),
            entity(rev = 2, data = amount(2)),
            "R1 identical metadata still resolves deterministically",
        ),
        MatrixCase(
            "canonical whole-entity tie-break for signed zero",
            entity(rev = 2, data = JsonObject(mapOf("amount" to JsonPrimitive(-0.0)))),
            entity(rev = 2, data = JsonObject(mapOf("amount" to JsonPrimitive(0.0)))),
            entity(rev = 2, data = JsonObject(mapOf("amount" to JsonPrimitive(0.0)))),
            "R1 canonical JSON distinguishes -0 from 0 so even this is decidable",
        ),
    )

    private fun amount(value: Int) = JsonObject(mapOf("amount" to JsonPrimitive(value)))
    private fun empty() = JsonObject(emptyMap())

    /**
     * **R1 + R2** — the deterministic winner, and the same winner whichever
     * parent is presented first.
     */
    @Test
    fun selectsTheExpectedWholeEntityInBothParentOrders() {
        for (case in matrix()) {
            val forward = merge(document(listOf(case.left)), document(listOf(case.right)))
            val backward = merge(document(listOf(case.right)), document(listOf(case.left)))

            assertEquals("${case.name} [${case.rule}] forward", listOf(case.winner), transactionsOf(forward))
            assertEquals("${case.name} [${case.rule}] backward", listOf(case.winner), transactionsOf(backward))
            assertSameDocument(
                "${case.name}: argument order must not change the merged document",
                forward.document,
                backward.document,
            )
            assertEquals("${case.name}: vaultVersion", 5, forward.vaultVersion)
            assertTrue("${case.name}: divergent", forward.divergent)
            assertEquals(
                "${case.name}: exactly one appended merge record",
                listOf(VaultMergeRecord(mergedAt, listOf(4), 5, mergeDevice)),
                forward.document.mergeLog,
            )

            // chooseVaultEntity directly, both orders — the rule under the merge.
            assertEquals("${case.name}: chooseVaultEntity forward", case.winner, chooseVaultEntity(case.left, case.right))
            assertEquals("${case.name}: chooseVaultEntity backward", case.winner, chooseVaultEntity(case.right, case.left))
        }
    }

    /**
     * Re-merging a merged document against either original parent must be a
     * **no-op** — `divergent = false`, same version, same bytes. Without this a
     * pair of devices would mint a new generation on every single reconcile and
     * never settle.
     */
    @Test
    fun isIdempotentAgainstEitherOriginalParentInBothOrders() {
        for (case in matrix()) {
            val leftParent = document(listOf(case.left))
            val rightParent = document(listOf(case.right))
            val merged = merge(leftParent, rightParent)

            for ((label, parent) in listOf("left" to leftParent, "right" to rightParent)) {
                val resultFirst = mergeVaultDocuments(
                    MergeVaultDocumentsInput(
                        merged.document, merged.vaultVersion, parent, 4,
                        deviceId = mergeDevice, mergedAt = "2026-07-25T13:00:00.000Z",
                    )
                )
                val parentFirst = mergeVaultDocuments(
                    MergeVaultDocumentsInput(
                        parent, 4, merged.document, merged.vaultVersion,
                        deviceId = mergeDevice, mergedAt = "2026-07-25T13:00:00.000Z",
                    )
                )
                assertFalse("${case.name}/$label: must not diverge again", resultFirst.divergent)
                assertEquals("${case.name}/$label: version held", merged.vaultVersion, resultFirst.vaultVersion)
                assertSameDocument("${case.name}/$label: document held", merged.document, resultFirst.document)
                assertFalse("${case.name}/$label reversed: must not diverge", parentFirst.divergent)
                assertSameDocument("${case.name}/$label reversed", resultFirst.document, parentFirst.document)
            }
        }
    }

    /** **R2**, stated on its own so a regression names the rule it broke. */
    @Test
    fun r2_aTombstoneLosesToAConcurrentEdit() {
        val tombstone = entity(
            rev = 2, editedAt = "2026-07-25T23:00:00Z", editedBy = deviceB,
            deletedAt = "2026-07-25T23:00:00Z", data = empty(),
        )
        val edit = entity(rev = 2, editedAt = "2026-07-25T01:00:00Z", editedBy = deviceA, data = amount(42))

        // The tombstone is 22 hours later AND has the higher editedBy; it still loses.
        assertEquals("the edit must win", edit, chooseVaultEntity(tombstone, edit))
        assertEquals("...in either order", edit, chooseVaultEntity(edit, tombstone))
        assertEquals(listOf(edit), transactionsOf(merge(document(listOf(tombstone)), document(listOf(edit)))))
        assertEquals(listOf(edit), transactionsOf(merge(document(listOf(edit)), document(listOf(tombstone)))))

        // But rule 2 is scoped to EQUAL rev: a genuine later delete still applies.
        val laterDelete = entity(rev = 3, editedAt = "2026-07-25T02:00:00Z", deletedAt = "2026-07-25T02:00:00Z", data = empty())
        assertEquals("a higher-rev delete still wins", laterDelete, chooseVaultEntity(edit, laterDelete))
    }

    /** **R1** — a sub-second instant must normalize, not compare `Z` against `.`. */
    @Test
    fun r1_recognizesTheSubSecondInstantAsLater() {
        val wholeSecond = entity(rev = 2, editedAt = "2026-07-25T10:00:00Z")
        val millisecondLater = entity(rev = 2, editedAt = "2026-07-25T10:00:00.001Z", data = amount(2))
        assertEquals(millisecondLater, chooseVaultEntity(wholeSecond, millisecondLater))
        assertEquals(millisecondLater, chooseVaultEntity(millisecondLater, wholeSecond))
        // Trailing zeros are not significance: `.1` and `.10` are the same instant,
        // so the comparison falls through to the next tie-break instead of picking
        // the longer string.
        val tenth = entity(rev = 2, editedAt = "2026-07-25T10:00:00.1Z", editedBy = deviceA, data = amount(1))
        val tenthPadded = entity(rev = 2, editedAt = "2026-07-25T10:00:00.10Z", editedBy = deviceB, data = amount(1))
        assertEquals("equal instants fall through to editedBy", tenthPadded, chooseVaultEntity(tenth, tenthPadded))
    }

    // =======================================================================
    // R3 — version arithmetic and the bounded merge log
    // =======================================================================

    @Test
    fun r3_mergedVersionIsMaxOfParentsPlusOne() {
        val result = merge(
            document(listOf(entity(id = entityA))),
            document(listOf(entity(id = entityB))),
            leftVersion = 40, rightVersion = 60,
        )
        assertEquals("max(40, 60) + 1", 61, result.vaultVersion)
        assertTrue(result.divergent)
        assertEquals(
            "parents deduped and sorted NUMERICALLY (a string sort would give [40, 60] here but [10, 9] elsewhere)",
            listOf(40, 60),
            result.document.mergeLog.last().parents,
        )
        assertEquals(61, result.document.mergeLog.last().into)

        // Equal parents collapse to a single-element `parents`.
        val equal = merge(document(listOf(entity(id = entityA))), document(listOf(entity(id = entityB))))
        assertEquals(listOf(4), equal.document.mergeLog.last().parents)

        // Numeric, not lexicographic: 9 must sort before 10.
        val numeric = merge(
            document(listOf(entity(id = entityA))),
            document(listOf(entity(id = entityB))),
            leftVersion = 10, rightVersion = 9,
        )
        assertEquals(listOf(9, 10), numeric.document.mergeLog.last().parents)
        assertEquals(11, numeric.vaultVersion)
    }

    /** merge.test.ts:265-291 — the log stays bounded while appending exactly one record. */
    @Test
    fun r3_keepsTheLogBoundedWhileAppendingExactlyOneRecord() {
        fun history(offset: Int): List<VaultMergeRecord> = (0 until VAULT_MERGE_LOG_LIMIT).map { index ->
            val version = offset + index + 1
            VaultMergeRecord(
                mergedAt = "2026-07-24T00:00:%02dZ".format(version),
                parents = listOf(version),
                into = version + 1,
                deviceId = if (index % 2 == 0) deviceA else deviceB,
            )
        }
        val left = document(listOf(entity(id = entityA)), history(0))
        val right = document(listOf(entity(id = entityB)), history(10))

        val result = merge(left, right, leftVersion = 40, rightVersion = 60)
        val reversed = merge(right, left, leftVersion = 60, rightVersion = 40)
        val appended = VaultMergeRecord(mergedAt, listOf(40, 60), 61, mergeDevice)

        assertEquals(61, result.vaultVersion)
        assertTrue(result.divergent)
        assertSameDocument("bounded log is order-invariant", result.document, reversed.document)
        assertEquals("capped at the limit", VAULT_MERGE_LOG_LIMIT, result.document.mergeLog.size)
        assertEquals("the appended record is last", appended, result.document.mergeLog.last())
        assertEquals(
            "exactly one record for this generation",
            listOf(appended),
            result.document.mergeLog.filter { it.into == 61 },
        )
        // The 30 distinct inherited records are pruned to the NEWEST 19.
        val inherited = result.document.mergeLog.dropLast(1)
        assertEquals(VAULT_MERGE_LOG_LIMIT - 1, inherited.size)
        assertEquals(
            "the newest inherited records survive",
            inherited.sortedBy { it.mergedAt },
            inherited,
        )
    }

    /** merge.test.ts:293-318 — seconds-omitted history sorts by instant, not by string. */
    @Test
    fun r3_sortsSecondsOmittedHistoryAndMergeInstants() {
        val existing = VaultMergeRecord("2026-07-25T11:00Z", listOf(2), 3, deviceA)
        val first = merge(
            document(listOf(entity(id = entityA)), listOf(existing)),
            document(listOf(entity(id = entityB))),
            at = "2026-07-25T12:00Z",
        )
        assertEquals(
            listOf("2026-07-25T11:00Z", "2026-07-25T12:00Z"),
            first.document.mergeLog.map { it.mergedAt },
        )

        val second = merge(
            first.document,
            document(listOf(entity(rev = 2))),
            leftVersion = first.vaultVersion,
            rightVersion = first.vaultVersion,
            at = "2026-07-25T13:00Z",
        )
        assertEquals(
            listOf("2026-07-25T11:00Z", "2026-07-25T12:00Z", "2026-07-25T13:00Z"),
            second.document.mergeLog.map { it.mergedAt },
        )
    }

    // =======================================================================
    // R4 — dominance, linear successors, and failing closed
    // =======================================================================

    /**
     * **R4** — the highest readable version wins *without* minting a new
     * generation, provided it genuinely dominates.
     */
    @Test
    fun r4_aStrictlyNewerDominatingDocumentIsALinearSuccessor() {
        val older = document(listOf(entity(rev = 1)))
        val newer = document(listOf(entity(rev = 2, data = amount(9))))

        val result = merge(newer, older, leftVersion = 9, rightVersion = 4)
        assertFalse("a dominating successor needs no new generation", result.divergent)
        assertEquals(9, result.vaultVersion)
        assertSameDocument("the newer document is taken verbatim", newer, result.document)

        // Same in the other argument order.
        val reversed = merge(older, newer, leftVersion = 4, rightVersion = 9)
        assertFalse(reversed.divergent)
        assertSameDocument(newer, reversed.document)

        assertTrue(documentDominates(newer, older))
        assertFalse(documentDominates(older, newer))

        // A newer version that does NOT dominate must still diverge: it is a fork,
        // not a successor, and taking it verbatim would drop the other edit.
        val forked = document(listOf(entity(id = entityB, rev = 1)))
        val diverged = merge(forked, older, leftVersion = 9, rightVersion = 4)
        assertTrue("a non-dominating newer document is a fork", diverged.divergent)
        assertEquals(10, diverged.vaultVersion)
        assertEquals(2, transactionsOf(diverged).size)

        // ...and `forceDivergent` overrides the short-circuit outright, because a
        // known locally pending write is a fork even when it dominates.
        val forced = merge(newer, older, leftVersion = 9, rightVersion = 4, forceDivergent = true)
        assertTrue(forced.divergent)
        assertEquals(10, forced.vaultVersion)
    }

    @Test
    fun r4_equalVersionByteEquivalentDocumentsNeedNoNewGeneration() {
        val one = document(listOf(entity(rev = 3)))
        val same = document(listOf(entity(rev = 3)))
        val result = merge(one, same)
        assertFalse("byte-equivalent parents are not a fork", result.divergent)
        assertEquals(4, result.vaultVersion)
        assertSameDocument(one, result.document)
    }

    /**
     * **R4** — malformed material fails closed with a typed error, in every
     * argument order. "Corrupt retained, never silently discarded" starts with
     * refusing to merge it into oblivion.
     */
    @Test
    fun r4_failsClosedOnAnUnparseableEditedAt() {
        for (bad in listOf(
            "2026-07-25T10:00:00.not-a-fractionZ",
            "2026-02-30T10:00:00Z",   // a real-looking date that does not exist
            "2026-07-25T25:00:00Z",   // rolls over a day in JS; must not be accepted
            "2026-07-25T10:00:00",    // no zone
            "2026-07-25T10:00:00+02:00", // offsets are not accepted, only Z
        )) {
            val malformed = entity(editedAt = bad)
            expectDocumentInvalid("chooseVaultEntity($bad) forward") { chooseVaultEntity(malformed, entity()) }
            expectDocumentInvalid("chooseVaultEntity($bad) backward") { chooseVaultEntity(entity(), malformed) }
            expectDocumentInvalid("merge($bad)") {
                merge(document(listOf(malformed)), document(listOf(entity(rev = 2))))
            }
        }
    }

    /**
     * Both candidates are validated **before** a winner is chosen, so a malformed
     * timestamp on the loser still fails the merge instead of being waved through
     * because `rev` already decided.
     */
    @Test
    fun r4_validatesBothCandidatesEvenWhenRevAlreadyDecides() {
        val decidedWinner = entity(rev = 9)
        val malformedLoser = entity(rev = 1, editedAt = "2026-07-25T10:00:00.not-a-fractionZ")
        expectDocumentInvalid("the loser's timestamp still fails closed") {
            chooseVaultEntity(decidedWinner, malformedLoser)
        }
    }

    @Test
    fun r4_refusesCandidatesWithDifferentIds() {
        expectDocumentInvalid("different entity ids") {
            chooseVaultEntity(entity(id = entityA), entity(id = entityB))
        }
    }

    /**
     * merge.test.ts:443-467 — unsafe versions and successor overflow.
     *
     * §3.3 note: the TypeScript guards `Number.MAX_SAFE_INTEGER`, which a Kotlin
     * `Int` cannot even represent. The equivalent boundary here is
     * `VaultContract.VERSION_MAX` (2_147_483_647, the contract's own
     * `vaultVersionSchema` maximum): merging at the ceiling overflows the
     * successor, and the same `envelope-invalid` fires. Sub-1 versions are
     * rejected on the way in.
     */
    @Test
    fun r4_failsClosedOnUnsafeVersionsAndSuccessorOverflow() {
        val left = document(listOf(entity(id = entityA)))
        val right = document(listOf(entity(id = entityB)))

        fun expectEnvelopeInvalid(what: String, block: () -> Unit) {
            try {
                block()
                fail("$what: expected VaultCryptoError[envelope-invalid]")
            } catch (e: VaultCryptoError) {
                assertEquals(what, VaultCryptoErrorCode.ENVELOPE_INVALID, e.code)
            }
        }
        expectEnvelopeInvalid("version 0") { merge(left, right, leftVersion = 0) }
        expectEnvelopeInvalid("negative version") { merge(left, right, rightVersion = -1) }
        expectEnvelopeInvalid("successor overflows the contract maximum") {
            merge(left, right, leftVersion = VaultContract.VERSION_MAX, rightVersion = 4)
        }
    }

    // =======================================================================
    // Entity-set union and ordering
    // =======================================================================

    @Test
    fun unionsEntitiesAcrossKindsAndOrdersThemById() {
        val left = VaultDocument.v1(
            entities = linkedMapOf(
                "transaction" to listOf(entity(id = entityB)),
                "cashSource" to listOf(entity(id = entityA)),
            ),
            mirrorProvenance = emptyList(),
        )
        val right = VaultDocument.v1(
            entities = linkedMapOf("transaction" to listOf(entity(id = entityA))),
            mirrorProvenance = emptyList(),
        )
        val merged = merge(left, right)
        assertEquals(
            "entity kinds are emitted in sorted order",
            listOf("cashSource", "transaction"),
            merged.document.entities.keys.toList(),
        )
        assertEquals(
            "entities within a kind are ordered by id",
            listOf(entityA, entityB),
            merged.document.entities.getValue("transaction").map { it.id },
        )
        assertSameDocument(
            "kind ordering is order-invariant",
            merged.document,
            merge(right, left).document,
        )
    }

    // =======================================================================
    // Severed-fork provenance across the CAS merge — merge.test.ts:469-538
    // =======================================================================

    private val chain = "018f0000-0000-7000-8000-0000000000d1"
    private val provLeft = VaultMirrorProvenance(
        chainId = chain,
        membershipId = "018f0000-0000-7000-8000-0000000000d4",
        kind = "transaction",
        mirrorId = "018f0000-0000-7000-8000-0000000000d2",
        portfolioId = "018f0000-0000-7000-8000-0000000000d3",
        localId = entityA,
    )
    private val provRight = provLeft.copy(mirrorId = "018f0000-0000-7000-8000-0000000000d5", localId = entityB)

    @Test
    fun unionsBothReplicasInsteadOfDroppingEitherIdentityMap() {
        val both = listOf(entity(id = entityA), entity(id = entityB))
        val merged = merge(document(both, emptyList(), listOf(provLeft)), document(both, emptyList(), listOf(provRight)))
        assertTrue(merged.divergent)
        assertEquals(listOf(provLeft, provRight), merged.document.mirrorProvenance)
        assertSameDocument(
            "the union is order-invariant",
            merged.document,
            merge(document(both, emptyList(), listOf(provRight)), document(both, emptyList(), listOf(provLeft))).document,
        )
    }

    @Test
    fun dropsAnIdentityWhoseRowTheMergeDeleted() {
        val deleted = entity(id = entityB, rev = 2, deletedAt = "2026-07-25T11:00:00Z")
        val merged = merge(
            document(listOf(entity(id = entityA), entity(id = entityB)), emptyList(), listOf(provLeft, provRight)),
            document(listOf(entity(id = entityA), deleted), emptyList(), listOf(provLeft, provRight)),
        )
        assertEquals(listOf(provLeft), merged.document.mirrorProvenance)
    }

    @Test
    fun stillAcceptsALinearSuccessorWhoseExtraIdentityNamesADeletedRow() {
        val deleted = entity(id = entityB, rev = 2, deletedAt = "2026-07-25T11:00:00Z")
        val successor = merge(
            document(listOf(entity(id = entityA), deleted), emptyList(), listOf(provLeft)),
            document(listOf(entity(id = entityA), deleted), emptyList(), listOf(provLeft, provRight)),
            leftVersion = 9, rightVersion = 4,
        )
        assertFalse("a stale entry must not force a divergent merge forever", successor.divergent)
    }

    @Test
    fun refusesToCallANewerDocumentALinearSuccessorWhileItLacksAnIdentity() {
        val newer = merge(
            document(listOf(entity(id = entityA)), emptyList(), emptyList()),
            document(listOf(entity(id = entityA)), emptyList(), listOf(provLeft)),
            leftVersion = 9, rightVersion = 4,
        )
        assertTrue("taking it verbatim would lose the fork's proof", newer.divergent)
        assertEquals(listOf(provLeft), newer.document.mirrorProvenance)
        assertEquals(10, newer.vaultVersion)
    }

    @Test
    fun failsClosedOnContradictoryForkProvenance() {
        // Two local rows claiming one logical identity.
        val conflicting = provLeft.copy(localId = entityB)
        expectDocumentInvalid("two local rows, one logical identity") {
            normalizeForkProvenance(listOf(provLeft, conflicting))
        }
        // One local row claiming two logical identities.
        val secondIdentity = provLeft.copy(mirrorId = "018f0000-0000-7000-8000-0000000000d9")
        expectDocumentInvalid("one local row, two logical identities") {
            normalizeForkProvenance(listOf(provLeft, secondIdentity))
        }
    }

    /**
     * An absent `mirrorProvenance` must survive [carriedForkProvenance] as absent
     * — it is the distinction the published envelope bytes depend on.
     */
    @Test
    fun absentForkProvenanceStaysAbsent() {
        val withoutKey = VaultDocument.v1(
            entities = linkedMapOf("transaction" to listOf(entity())),
            mirrorProvenance = null,
        )
        assertEquals(null, carriedForkProvenance(withoutKey))
        assertFalse(jsJsonStringify(withoutKey.toJson()).contains("mirrorProvenance"))
        assertSameDocument("withForkProvenance must not invent the key", withoutKey, withForkProvenance(withoutKey, emptyList()))

        val withEmptyKey = VaultDocument.v1(
            entities = linkedMapOf("transaction" to listOf(entity())),
            mirrorProvenance = emptyList(),
        )
        assertNotNull(carriedForkProvenance(withEmptyKey))
        assertTrue(jsJsonStringify(withEmptyKey.toJson()).contains("mirrorProvenance"))
    }
}
