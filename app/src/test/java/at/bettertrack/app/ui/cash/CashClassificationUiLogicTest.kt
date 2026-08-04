package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.api.dto.CASH_RULE_PATTERN_MAX
import at.bettertrack.app.data.api.dto.CASH_TAG_NAME_MAX
import at.bettertrack.app.data.api.dto.CashRuleDto
import at.bettertrack.app.data.api.dto.CashRuleMatchTypes
import at.bettertrack.app.data.db.CashTagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5 S2c pure-logic tests for the two classification management screens
 * (manage tags / auto-tag rules): catalog sectioning, name and draft
 * validation, priority sanitation, and the two list edits that must never
 * disturb the server's evaluation order.
 */
class CashClassificationUiLogicTest {

    private fun tag(id: String, name: String, system: Boolean = false) =
        CashTagEntity(id = id, name = name, color = "#22c55e", system = system, systemKey = if (system) name else null)

    private fun rule(
        id: String,
        pattern: String = "REWE",
        priority: Int = 0,
        enabled: Boolean = true,
    ) = CashRuleDto(
        id = id,
        tagIds = listOf("t1"),
        matchType = CashRuleMatchTypes.CONTAINS,
        pattern = pattern,
        priority = priority,
        enabled = enabled,
    )

    // ── Tags ────────────────────────────────────────────────────────────────

    @Test
    fun `split keeps user tags and system tags apart in DAO order`() {
        val tags = listOf(
            tag("u1", "Groceries"),
            tag("u2", "Rent"),
            tag("s1", "Fees", system = true),
            tag("s2", "Tax", system = true),
        )
        val (user, system) = splitCashTags(tags)
        assertEquals(listOf("u1", "u2"), user.map { it.id })
        assertEquals(listOf("s1", "s2"), system.map { it.id })
    }

    @Test
    fun `split answers empty halves rather than nulls`() {
        val (user, system) = splitCashTags(emptyList())
        assertTrue(user.isEmpty())
        assertTrue(system.isEmpty())
    }

    @Test
    fun `tag name is trimmed and capped the way the server caps it`() {
        assertEquals("Groceries", normalizeCashTagName("  Groceries  "))
        assertEquals(CASH_TAG_NAME_MAX, normalizeCashTagName("x".repeat(CASH_TAG_NAME_MAX + 40)).length)
    }

    @Test
    fun `whitespace-only tag name is not submittable`() {
        assertFalse(isCashTagNameValid(""))
        assertFalse(isCashTagNameValid("   "))
        assertTrue(isCashTagNameValid(" Rent "))
    }

    // ── Rule drafts ─────────────────────────────────────────────────────────

    @Test
    fun `rule draft needs both a pattern and at least one tag`() {
        assertFalse(isCashRuleDraftValid(CashRuleDraft()))
        assertFalse(isCashRuleDraftValid(CashRuleDraft(pattern = "REWE")))
        assertFalse(isCashRuleDraftValid(CashRuleDraft(tagIds = listOf("t1"))))
        assertFalse(isCashRuleDraftValid(CashRuleDraft(pattern = "   ", tagIds = listOf("t1"))))
        assertTrue(isCashRuleDraftValid(CashRuleDraft(pattern = "REWE", tagIds = listOf("t1"))))
    }

    @Test
    fun `pattern cap matches the platform cap`() {
        // The field truncates; this asserts the constant the UI truncates against.
        assertEquals(200, CASH_RULE_PATTERN_MAX)
    }

    @Test
    fun `priority input keeps digits only and clamps to the server ceiling`() {
        assertEquals("", sanitizePriorityInput(""))
        assertEquals("", sanitizePriorityInput("abc"))
        assertEquals("0", sanitizePriorityInput("0"))
        assertEquals("42", sanitizePriorityInput("4a2"))
        assertEquals("7", sanitizePriorityInput("007"))
        assertEquals(CASH_RULE_PRIORITY_MAX.toString(), sanitizePriorityInput("99999"))
        assertEquals(CASH_RULE_PRIORITY_MAX.toString(), sanitizePriorityInput("10001"))
        assertEquals("10000", sanitizePriorityInput("10000"))
    }

    @Test
    fun `blank priority means run first`() {
        assertEquals(0, parsePriorityInput(""))
        assertEquals(0, parsePriorityInput("   "))
        assertEquals(12, parsePriorityInput("12"))
        assertEquals(CASH_RULE_PRIORITY_MAX, parsePriorityInput("500000"))
    }

    @Test
    fun `only the four wire match types are editable as chips`() {
        CashRuleMatchTypes.ALL.forEach { assertTrue(isKnownMatchType(it)) }
        assertFalse(isKnownMatchType("fuzzy"))
        assertFalse(isKnownMatchType(""))
    }

    // ── Rule list edits ─────────────────────────────────────────────────────

    @Test
    fun `replacing a rule keeps every position - order is the server's answer`() {
        val rules = listOf(rule("a", priority = 0), rule("b", priority = 5), rule("c", priority = 9))
        val updated = replaceCashRule(rules, rule("b", pattern = "BILLA", priority = 5))
        assertEquals(listOf("a", "b", "c"), updated.map { it.id })
        assertEquals("BILLA", updated[1].pattern)
    }

    @Test
    fun `replacing an unknown id changes nothing`() {
        val rules = listOf(rule("a"), rule("b"))
        assertEquals(rules, replaceCashRule(rules, rule("zzz")))
    }

    @Test
    fun `enabled flip touches one rule and never reorders`() {
        val rules = listOf(rule("a"), rule("b"), rule("c"))
        val flipped = toggleCashRuleEnabled(rules, "b", enabled = false)
        assertEquals(listOf("a", "b", "c"), flipped.map { it.id })
        assertTrue(flipped[0].enabled)
        assertFalse(flipped[1].enabled)
        assertTrue(flipped[2].enabled)
    }

    @Test
    fun `enabled flip is its own inverse - the revert path restores the row`() {
        val rules = listOf(rule("a", enabled = true))
        val off = toggleCashRuleEnabled(rules, "a", enabled = false)
        val back = toggleCashRuleEnabled(off, "a", enabled = true)
        assertEquals(rules, back)
    }

    @Test
    fun `tag picker toggles selection and preserves pick order`() {
        var selected = emptyList<String>()
        selected = toggleRuleTag(selected, "t1")
        selected = toggleRuleTag(selected, "t2")
        assertEquals(listOf("t1", "t2"), selected)
        selected = toggleRuleTag(selected, "t1")
        assertEquals(listOf("t2"), selected)
        selected = toggleRuleTag(selected, "t1")
        assertEquals(listOf("t2", "t1"), selected)
    }
}
