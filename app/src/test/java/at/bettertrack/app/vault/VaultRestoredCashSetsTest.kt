package at.bettertrack.app.vault

import at.bettertrack.app.data.api.dto.CASH_TAGS_PER_ITEM_MAX
import at.bettertrack.app.data.api.dto.CASH_TAGS_PER_USER_MAX
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `refineRestoredCashSets` — the three suites of `packages/contracts/src/
 * vault.test.ts` @ef73d282 lines 464-809 (vendored verbatim as
 * `tools/domain-vectors/vendor/web-vault/contracts-vault.test.ts`), mirrored
 * one `it` to one `@Test`, fixtures built by the same formulas.
 *
 * Mapping: `vaultStrictDocumentV1Schema.safeParse(doc).success` is
 * `refineRestoredCashSets(entities).isEmpty()`; `error.issues[0].code/path/
 * params.code` are [VaultStrictIssue.code]/[VaultStrictIssue.path]/
 * [VaultStrictIssue.paramsCode]; `parsed.data.entities` toHaveLength(n) — the
 * tombstones are CARRIED — is the input list, which the refinement never
 * rewrites (asserted on the list it was handed). The row schemas the strict
 * parse also applies are not ported (VaultContracts.kt), so each `safeParse`
 * here exercises the refinement alone — which is what these suites test.
 *
 * Red first: with the refinement stubbed to `emptyList()` every refusal below
 * failed (9 of the 15 mirrored cases + the walk-order case), the acceptances
 * passed.
 */
class VaultRestoredCashSetsTest {

    private val uuidA = "018f0000-0000-7000-8000-00000000000a"

    private fun entity(id: String, deletedAt: String?, data: Map<String, JsonPrimitive>) = VaultEntity(
        id = id,
        rev = 1,
        editedAt = "2026-01-01T00:00:00.000Z",
        editedBy = uuidA,
        deletedAt = deletedAt,
        data = JsonObject(data),
    )

    private fun ok(entities: List<VaultStrictEntity>): Boolean = refineRestoredCashSets(entities).isEmpty()

    // ── describe('a restored rule’s tag fan-out (#1954)') ─────────────────────

    private val ruleA = "018f0000-0000-7000-8000-0000000000e1"
    private val ruleB = "018f0000-0000-7000-8000-0000000000e2"

    private fun fanOutTagId(index: Int) = "018f0000-0000-7000-8000-1${index.toString(16).padStart(11, '0')}"

    private fun fanOutLink(ruleId: String, index: Int, deletedAt: String? = null) = VaultStrictEntity(
        "cashRuleTag",
        entity(
            id = "018f0000-0000-7000-8000-${index.toString(16).padStart(12, '0')}",
            deletedAt = deletedAt,
            data = linkedMapOf(
                "ruleId" to JsonPrimitive(ruleId),
                "tagId" to JsonPrimitive(fanOutTagId(index)),
                "createdAt" to JsonPrimitive("2026-01-01T00:00:00.000Z"),
            ),
        ),
    )

    private fun linksFor(ruleId: String, count: Int, offset: Int = 0) =
        List(count) { i -> fanOutLink(ruleId, offset + i) }

    private fun linkTombstonesFor(ruleId: String, count: Int, offset: Int = 0) =
        List(count) { i -> fanOutLink(ruleId, offset + i, "2026-02-01T00:00:00.000Z") }

    @Test
    fun `fan-out - accepts a rule landing EXACTLY on the cap`() {
        val entities = linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX)
        assertTrue(ok(entities))
        assertEquals(CASH_TAGS_PER_ITEM_MAX, entities.size)
    }

    @Test
    fun `fan-out - refuses the WHOLE document one link past the cap, never a bounded prefix`() {
        val issues = refineRestoredCashSets(linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX + 1))
        assertTrue("refused", issues.isNotEmpty())
        assertEquals("too_big", issues[0].code)
        assertEquals(listOf("entities"), issues[0].path)
    }

    @Test
    fun `fan-out - counts PER RULE, so two capped rules in one document are legal`() {
        assertTrue(ok(linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX) + linksFor(ruleB, CASH_TAGS_PER_ITEM_MAX, 1_000)))
        // …and one rule past the cap still condemns the document even when its
        // sibling is fine — the offender is found wherever it sits.
        assertEquals(
            false,
            ok(linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX) + linksFor(ruleB, CASH_TAGS_PER_ITEM_MAX + 1, 1_000)),
        )
    }

    @Test
    fun `fan-out - refuses the 16 MB case the bound exists for`() {
        assertEquals(false, ok(linksFor(ruleA, 5_000)))
    }

    @Test
    fun `fan-out - counts LIVE links only, a tombstone is not a tag the rule carries`() {
        val entities = linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX) + linkTombstonesFor(ruleA, 40, 2_000)
        assertTrue(ok(entities))
        // Carried, not dropped: §4's merge rules key off them.
        assertEquals(CASH_TAGS_PER_ITEM_MAX + 40, entities.size)
        // …and the live count is still what decides.
        assertEquals(
            false,
            ok(linksFor(ruleA, CASH_TAGS_PER_ITEM_MAX + 1) + linkTombstonesFor(ruleA, 40, 2_000)),
        )
    }

    @Test
    fun `fan-out - leaves a document with no rule links alone`() {
        assertTrue(ok(emptyList()))
    }

    // ── describe('a restored account’s TAG SET (#1963)') ──────────────────────

    private fun tag(index: Int, deletedAt: String? = null) = VaultStrictEntity(
        "cashTag",
        entity(
            id = "018f0000-0000-7000-8000-2${index.toString(16).padStart(11, '0')}",
            deletedAt = deletedAt,
            data = linkedMapOf(
                "userId" to JsonPrimitive(uuidA),
                "name" to JsonPrimitive("tag-$index"),
                "color" to JsonPrimitive("#64748b"),
                "system" to JsonPrimitive(false),
                "systemKey" to JsonNull,
                "createdAt" to JsonPrimitive("2026-01-01T00:00:00.000Z"),
                "updatedAt" to JsonPrimitive("2026-01-01T00:00:00.000Z"),
            ),
        ),
    )

    private fun tags(count: Int, offset: Int = 0, deletedAt: String? = null) =
        List(count) { i -> tag(offset + i, deletedAt) }

    @Test
    fun `tag set - accepts a document landing EXACTLY on the cap`() {
        val entities = tags(CASH_TAGS_PER_USER_MAX)
        assertTrue(ok(entities))
        assertEquals(CASH_TAGS_PER_USER_MAX, entities.size)
    }

    @Test
    fun `tag set - refuses the WHOLE document one tag past the cap, never a bounded prefix`() {
        val issues = refineRestoredCashSets(tags(CASH_TAGS_PER_USER_MAX + 1))
        assertTrue("refused", issues.isNotEmpty())
        assertEquals("too_big", issues[0].code)
        assertEquals(listOf("entities"), issues[0].path)
        // First offender wins: one issue, not one per offending row.
        assertEquals(1, issues.size)
    }

    @Test
    fun `tag set - refuses the 16 MB case the bound exists for`() {
        assertEquals(false, ok(tags(20_000)))
    }

    @Test
    fun `tag set - counts LIVE tags only, a tombstone is not a tag the account holds`() {
        val entities = tags(CASH_TAGS_PER_USER_MAX) + tags(500, 100_000, "2026-02-01T00:00:00.000Z")
        assertTrue(ok(entities))
        assertEquals(CASH_TAGS_PER_USER_MAX + 500, entities.size)
        assertEquals(
            false,
            ok(tags(CASH_TAGS_PER_USER_MAX + 1) + tags(500, 100_000, "2026-02-01T00:00:00.000Z")),
        )
    }

    @Test
    fun `tag set - leaves a document with no tags alone`() {
        assertTrue(ok(emptyList()))
    }

    // ── describe('a restored rule→tag link is UNIQUE per pair (#1963)') ───────

    private val rule = "018f0000-0000-7000-8000-0000000000e1"
    private val otherRule = "018f0000-0000-7000-8000-0000000000e2"
    private val tagOne = "018f0000-0000-7000-8000-0000000000f1"
    private val otherTag = "018f0000-0000-7000-8000-0000000000f2"

    private fun pairLink(index: Int, ruleId: String, tagId: String, deletedAt: String? = null) = VaultStrictEntity(
        "cashRuleTag",
        entity(
            id = "018f0000-0000-7000-8000-3${index.toString(16).padStart(11, '0')}",
            deletedAt = deletedAt,
            data = linkedMapOf(
                "ruleId" to JsonPrimitive(ruleId),
                "tagId" to JsonPrimitive(tagId),
                "createdAt" to JsonPrimitive("2026-01-01T00:00:00.000Z"),
            ),
        ),
    )

    @Test
    fun `pair - refuses the same (rule, tag) pair twice, instead of leaving it to the unique index`() {
        val issues = refineRestoredCashSets(listOf(pairLink(1, rule, tagOne), pairLink(2, rule, tagOne)))
        assertTrue("refused", issues.isNotEmpty())
        assertEquals("custom", issues[0].code)
        assertEquals("CASH_RULE_TAG_DUPLICATE", issues[0].paramsCode)
        assertEquals(listOf("entities"), issues[0].path)
        assertEquals(1, issues.size)
    }

    @Test
    fun `pair - is a PAIR, not an id - the same tag on two rules and two tags on one rule are legal`() {
        assertTrue(ok(listOf(pairLink(1, rule, tagOne), pairLink(2, otherRule, tagOne), pairLink(3, rule, otherTag))))
    }

    @Test
    fun `pair - lets a TOMBSTONED link sit beside the live one, unlink then relink is not a duplicate`() {
        assertTrue(ok(listOf(pairLink(1, rule, tagOne, "2026-02-01T00:00:00.000Z"), pairLink(2, rule, tagOne))))
        // Two tombstones of the same pair are equally harmless — neither is written.
        assertTrue(
            ok(
                listOf(
                    pairLink(1, rule, tagOne, "2026-02-01T00:00:00.000Z"),
                    pairLink(2, rule, tagOne, "2026-03-01T00:00:00.000Z"),
                )
            )
        )
    }

    @Test
    fun `pair - names the DUPLICATE, not the cap, when a rule is over-tagged BY duplicates`() {
        val distinct = List(CASH_TAGS_PER_ITEM_MAX) { i ->
            pairLink(i, rule, "018f0000-0000-7000-8000-4${i.toString(16).padStart(11, '0')}")
        }
        val firstTagId = (distinct[0].entity.data["tagId"] as JsonPrimitive).content
        val issues = refineRestoredCashSets(distinct + pairLink(900, rule, firstTagId))
        assertTrue("refused", issues.isNotEmpty())
        assertEquals("custom", issues[0].code)
        assertEquals("CASH_RULE_TAG_DUPLICATE", issues[0].paramsCode)
    }

    // ── The walk: `toStrictRestoreDocument`'s flat order (paranoidDisable.ts:54-71) ─

    /**
     * Not an upstream `it`: the Kotlin document keeps rows per kind, so the flat
     * order the refinement walks has to be rebuilt — VAULT_ENTITY_KINDS order,
     * then row order, whatever order the map's keys arrived in. `cashTag`
     * precedes `cashRuleTag` there, so with BOTH a tag-count overflow and a
     * duplicate link the first offender is the tag count.
     */
    @Test
    fun `walk - rebuilds the flat order by entity kind and the first offender follows it`() {
        val links = listOf(pairLink(1, rule, tagOne), pairLink(2, rule, tagOne))
        val document = VaultDocument.v1(
            entities = linkedMapOf(
                // deliberately NOT in VAULT_ENTITY_KINDS order
                "cashRuleTag" to links.map { it.entity },
                "cashTag" to tags(CASH_TAGS_PER_USER_MAX + 1).map { it.entity },
            )
        )
        val flat = strictRestoreEntityOrder(document)
        assertEquals(
            List(CASH_TAGS_PER_USER_MAX + 1) { "cashTag" } + listOf("cashRuleTag", "cashRuleTag"),
            flat.map { it.kind },
        )
        val issues = refineRestoredCashSets(flat)
        assertEquals(1, issues.size)
        assertEquals("too_big", issues[0].code)
        assertEquals(CASH_TAGS_PER_USER_MAX, issues[0].maximum)
    }

    /** `if (row.data.ownerId == null) continue;` — absent and null alike. */
    @Test
    fun `walk - drops ownerless customAsset rows like toStrictRestoreDocument`() {
        fun asset(id: String, owner: JsonPrimitive?) = entity(
            id = id,
            deletedAt = null,
            data = if (owner == null) emptyMap() else mapOf("ownerId" to owner),
        )
        val owned = asset("018f0000-0000-7000-8000-0000000000a1", JsonPrimitive(uuidA))
        val document = VaultDocument.v1(
            entities = linkedMapOf(
                "customAsset" to listOf(
                    asset("018f0000-0000-7000-8000-0000000000a0", null),
                    owned,
                    entity("018f0000-0000-7000-8000-0000000000a2", null, mapOf("ownerId" to JsonNull)),
                )
            )
        )
        assertEquals(listOf(owned), strictRestoreEntityOrder(document).map { it.entity })
    }

    /** A LIVE link whose ids are not strings never reaches the refinement upstream. */
    @Test
    fun `a live link without string ids is document-invalid`() {
        val bad = VaultStrictEntity(
            "cashRuleTag",
            entity("018f0000-0000-7000-8000-0000000000b1", null, mapOf("ruleId" to JsonPrimitive(7))),
        )
        try {
            refineRestoredCashSets(listOf(bad))
            fail("expected document-invalid")
        } catch (e: VaultCryptoError) {
            assertEquals(VaultCryptoErrorCode.DOCUMENT_INVALID, e.code)
        }
    }

    /** packages/contracts/src/cash.ts:101 and :126 @ef73d282. */
    @Test
    fun `the caps are the contract constants at the pin`() {
        assertEquals(20, CASH_TAGS_PER_ITEM_MAX)
        assertEquals(1000, CASH_TAGS_PER_USER_MAX)
    }
}
