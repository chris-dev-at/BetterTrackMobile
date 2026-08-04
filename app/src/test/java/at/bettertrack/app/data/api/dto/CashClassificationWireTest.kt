package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the v5 cash-classification payloads, using the app's real
 * Json configuration. The response fixtures are the exact shapes the dev backend
 * returns; the request assertions are on the ACTUAL serialized string, because
 * "the PATCH body is sparse" is a claim about bytes, not about Kotlin nulls.
 */
class CashClassificationWireTest {

    // Matches the app's production Json config (see di/AppGraph).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ── Tags ────────────────────────────────────────────────────────────────

    @Test
    fun `the tag list parses user and system tags together`() {
        val wire = """
            {"tags":[
              {"id":"11111111-1111-1111-1111-111111111111","name":"Groceries","color":"#22c55e",
               "system":false,"systemKey":null,"createdAt":"2026-08-01T10:00:00.000Z",
               "updatedAt":"2026-08-01T10:00:00.000Z"},
              {"id":"22222222-2222-2222-2222-222222222222","name":"Fees","color":"#f97316",
               "system":true,"systemKey":"fees","createdAt":"2026-07-30T00:00:00.000Z",
               "updatedAt":"2026-07-30T00:00:00.000Z"}
            ]}
        """.trimIndent()
        val r = json.decodeFromString(CashTagListResponse.serializer(), wire)

        assertEquals(2, r.tags.size)
        assertFalse(r.tags[0].system)
        assertNull(r.tags[0].systemKey)
        assertTrue(r.tags[1].system)
        assertEquals(CashSystemTagKeys.FEES, r.tags[1].systemKey)
        assertEquals("#f97316", r.tags[1].color)
    }

    @Test
    fun `a tag mutation response unwraps the tag envelope`() {
        val wire = """
            {"tag":{"id":"33333333-3333-3333-3333-333333333333","name":"Netflix","color":"#8b5cf6",
             "system":false,"systemKey":null,"createdAt":"2026-08-04T09:00:00.000Z",
             "updatedAt":"2026-08-04T09:00:00.000Z"}}
        """.trimIndent()
        assertEquals("Netflix", json.decodeFromString(CashTagResponse.serializer(), wire).tag.name)
    }

    @Test
    fun `a future system key parses as a plain string instead of crashing`() {
        // systemKey is deliberately not an enum: the platform may seed a tenth.
        val wire = """
            {"id":"a","name":"Rebate","color":"#000000","system":true,"systemKey":"cashback",
             "createdAt":"","updatedAt":""}
        """.trimIndent()
        assertEquals("cashback", json.decodeFromString(CashTagDto.serializer(), wire).systemKey)
    }

    @Test
    fun `create tag omits an unset colour entirely`() {
        assertEquals(
            """{"name":"Groceries"}""",
            json.encodeToString(CreateCashTagRequest.serializer(), CreateCashTagRequest("Groceries")),
        )
    }

    @Test
    fun `a colour-only re-tint PATCH sends ONLY the colour`() {
        // Sending {"name":null} would both wipe nothing and fail the .strict()
        // schema — the sparseness is the whole point.
        val body = json.encodeToString(
            UpdateCashTagRequest.serializer(),
            UpdateCashTagRequest(color = "#ff0000"),
        )
        assertEquals("""{"color":"#ff0000"}""", body)
        assertFalse(body.contains("name"))
    }

    @Test
    fun `a rename-only PATCH sends ONLY the name`() {
        assertEquals(
            """{"name":"Food"}""",
            json.encodeToString(UpdateCashTagRequest.serializer(), UpdateCashTagRequest(name = "Food")),
        )
    }

    // ── Movement tags ───────────────────────────────────────────────────────

    @Test
    fun `the movement-tags response carries the whole replaced set`() {
        val wire = """
            {"movementId":"44444444-4444-4444-4444-444444444444","tags":[
              {"id":"11111111-1111-1111-1111-111111111111","name":"Groceries","color":"#22c55e",
               "system":false,"systemKey":null,"createdAt":"","updatedAt":""}
            ]}
        """.trimIndent()
        val r = json.decodeFromString(CashMovementTagsResponse.serializer(), wire)

        assertEquals("44444444-4444-4444-4444-444444444444", r.movementId)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), r.tags.map { it.id })
    }

    @Test
    fun `clearing a movement's tags serializes an explicit empty array`() {
        // [] is meaningful here (it CLEARS); it must never be dropped.
        assertEquals(
            """{"tagIds":[]}""",
            json.encodeToString(
                SetCashMovementTagsRequest.serializer(),
                SetCashMovementTagsRequest(emptyList()),
            ),
        )
    }

    // ── Budgets ─────────────────────────────────────────────────────────────

    @Test
    fun `the budget list parses progress rows`() {
        val wire = """
            {"period":"2026-08","budgets":[
              {"id":"55555555-5555-5555-5555-555555555555",
               "portfolioId":"66666666-6666-6666-6666-666666666666",
               "tagId":"11111111-1111-1111-1111-111111111111","tagName":"Groceries",
               "tagColor":"#22c55e","amount":400.0,"currency":"EUR","period":"2026-08",
               "recurring":true,"spent":432.19,"remaining":-32.19,"exceeded":true}
            ]}
        """.trimIndent()
        val r = json.decodeFromString(CashBudgetListResponse.serializer(), wire)

        assertEquals("2026-08", r.period)
        val b = r.budgets.single()
        assertTrue(b.recurring)
        assertTrue(b.exceeded)
        assertEquals(432.19, b.spent, 0.0001)
        // remaining goes NEGATIVE once over budget — the app must not clamp it.
        assertEquals(-32.19, b.remaining, 0.0001)
    }

    @Test
    fun `a recurring budget parses with a null period`() {
        val wire = """
            {"budget":{"id":"a","portfolioId":"p","tagId":"t","period":null,"amount":250.0,
             "currency":"EUR","createdAt":"2026-08-01T00:00:00.000Z",
             "updatedAt":"2026-08-01T00:00:00.000Z"}}
        """.trimIndent()
        val b = json.decodeFromString(CashBudgetResponse.serializer(), wire).budget

        assertNull(b.period) // null = the recurring monthly target
        assertEquals(250.0, b.amount, 0.0001)
    }

    @Test
    fun `creating a recurring budget omits the period key`() {
        val body = json.encodeToString(
            CreateCashBudgetRequest.serializer(),
            CreateCashBudgetRequest(portfolioId = "p", tagId = "t", amount = 250.0),
        )
        assertFalse(body.contains("period"))
        assertTrue(body.contains(""""currency":"EUR""""))
    }

    @Test
    fun `an amount-only budget PATCH omits the currency`() {
        assertEquals(
            """{"amount":300.0}""",
            json.encodeToString(
                UpdateCashBudgetRequest.serializer(),
                UpdateCashBudgetRequest(amount = 300.0),
            ),
        )
    }

    // ── Rules ───────────────────────────────────────────────────────────────

    @Test
    fun `the rule list parses in evaluation order with multi-tag sets`() {
        val wire = """
            {"rules":[
              {"id":"77777777-7777-7777-7777-777777777777",
               "tagIds":["11111111-1111-1111-1111-111111111111",
                         "22222222-2222-2222-2222-222222222222"],
               "matchType":"contains","pattern":"REWE","priority":0,"enabled":true,
               "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"},
              {"id":"88888888-8888-8888-8888-888888888888","tagIds":["33333333-3333-3333-3333-333333333333"],
               "matchType":"regex","pattern":"^NETFLIX.*","priority":10,"enabled":false,
               "createdAt":"2026-08-02T00:00:00.000Z","updatedAt":"2026-08-02T00:00:00.000Z"}
            ]}
        """.trimIndent()
        val r = json.decodeFromString(CashRuleListResponse.serializer(), wire)

        assertEquals(2, r.rules.size)
        assertEquals(2, r.rules[0].tagIds.size)
        assertEquals(CashRuleMatchTypes.CONTAINS, r.rules[0].matchType)
        assertEquals(CashRuleMatchTypes.REGEX, r.rules[1].matchType)
        assertFalse(r.rules[1].enabled)
        // Server order IS evaluation order — ascending priority, first match wins.
        assertTrue(r.rules[0].priority < r.rules[1].priority)
    }

    @Test
    fun `an enable-toggle rule PATCH sends ONLY enabled`() {
        val body = json.encodeToString(
            UpdateCashRuleRequest.serializer(),
            UpdateCashRuleRequest(enabled = false),
        )
        assertEquals("""{"enabled":false}""", body)
        assertFalse(body.contains("tagIds"))
        assertFalse(body.contains("pattern"))
        assertFalse(body.contains("priority"))
    }

    @Test
    fun `a tagIds-replacing rule PATCH sends the whole set and nothing else`() {
        assertEquals(
            """{"tagIds":["a","b"]}""",
            json.encodeToString(
                UpdateCashRuleRequest.serializer(),
                UpdateCashRuleRequest(tagIds = listOf("a", "b")),
            ),
        )
    }

    @Test
    fun `apply reports a movement count, and a second run honestly reports zero`() {
        assertEquals(
            23,
            json.decodeFromString(CashRuleApplyResponse.serializer(), """{"movementsTagged":23}""")
                .movementsTagged,
        )
        assertEquals(
            0,
            json.decodeFromString(CashRuleApplyResponse.serializer(), """{"movementsTagged":0}""")
                .movementsTagged,
        )
    }

    @Test
    fun `preview of an empty note is a legal empty answer`() {
        assertEquals(
            """{"note":""}""",
            json.encodeToString(CashRulePreviewRequest.serializer(), CashRulePreviewRequest("")),
        )
        assertTrue(
            json.decodeFromString(CashRulePreviewResponse.serializer(), """{"tagIds":[]}""")
                .tagIds.isEmpty(),
        )
    }

    // ── Summary + trends ────────────────────────────────────────────────────

    @Test
    fun `the summary parses the untagged bucket as a null tagId with null name and colour`() {
        val wire = """
            {"portfolioId":"66666666-6666-6666-6666-666666666666","month":"2026-08",
             "totalInflow":3200.0,"totalOutflow":1875.5,"net":1324.5,"tags":[
              {"tagId":"11111111-1111-1111-1111-111111111111","name":"Groceries","color":"#22c55e",
               "system":false,"outflow":432.19,"inflow":0.0,"movements":11},
              {"tagId":null,"name":null,"color":null,"system":false,"outflow":120.0,
               "inflow":0.0,"movements":3}
            ]}
        """.trimIndent()
        val s = json.decodeFromString(CashSummaryResponse.serializer(), wire)

        assertEquals(1324.5, s.net, 0.0001)
        assertEquals(s.totalInflow - s.totalOutflow, s.net, 0.0001)

        val untagged = s.tags.single { it.tagId == null }
        assertNull(untagged.name)
        assertNull(untagged.color)
        assertEquals(3, untagged.movements)
        assertNotNull(s.tags.first().name)
    }

    @Test
    fun `per-tag rows do NOT sum to the totals when a movement carries two tags`() {
        // The contract's headline semantic: a two-tag movement contributes FULLY
        // to both rows, so summing the breakdown overstates the outflow. Totals
        // are authoritative; this test exists so nobody "fixes" the DTO by
        // deriving them.
        val wire = """
            {"portfolioId":"p","month":"2026-08","totalInflow":0.0,"totalOutflow":100.0,
             "net":-100.0,"tags":[
              {"tagId":"a","name":"Food","color":"#111111","system":false,"outflow":100.0,
               "inflow":0.0,"movements":1},
              {"tagId":"b","name":"Groceries","color":"#222222","system":false,"outflow":100.0,
               "inflow":0.0,"movements":1}
            ]}
        """.trimIndent()
        val s = json.decodeFromString(CashSummaryResponse.serializer(), wire)

        assertEquals(200.0, s.tags.sumOf { it.outflow }, 0.0001)
        assertEquals(100.0, s.totalOutflow, 0.0001)
    }

    @Test
    fun `trends parse oldest-to-newest with gap months as zeros`() {
        val wire = """
            {"portfolioId":"66666666-6666-6666-6666-666666666666","points":[
              {"month":"2026-06","inflow":3200.0,"outflow":1500.0},
              {"month":"2026-07","inflow":0.0,"outflow":0.0},
              {"month":"2026-08","inflow":3200.0,"outflow":1875.5}
            ]}
        """.trimIndent()
        val t = json.decodeFromString(CashTrendResponse.serializer(), wire)

        assertEquals(listOf("2026-06", "2026-07", "2026-08"), t.points.map { it.month })
        assertEquals(0.0, t.points[1].inflow, 0.0001)
        assertEquals(0.0, t.points[1].outflow, 0.0001)
    }

    // ── Forward/backward tolerance ──────────────────────────────────────────

    @Test
    fun `a server that omits optional keys reads as neutral instead of crashing`() {
        // Every non-id field defaults, so a pre-v5 / partially-rolled-out server
        // degrades rather than throwing MissingFieldException mid-render.
        assertEquals("", json.decodeFromString(CashTagDto.serializer(), """{"id":"a"}""").name)
        assertTrue(json.decodeFromString(CashTagListResponse.serializer(), "{}").tags.isEmpty())
        assertTrue(json.decodeFromString(CashRuleListResponse.serializer(), "{}").rules.isEmpty())
        assertEquals(
            0.0,
            json.decodeFromString(CashSummaryResponse.serializer(), "{}").net,
            0.0001,
        )
        assertTrue(json.decodeFromString(CashTrendResponse.serializer(), "{}").points.isEmpty())
    }

    @Test
    fun `an unknown future key is ignored, not fatal`() {
        val wire = """{"id":"a","name":"X","color":"#000000","system":false,"icon":"🍏"}"""
        assertEquals("X", json.decodeFromString(CashTagDto.serializer(), wire).name)
    }
}
