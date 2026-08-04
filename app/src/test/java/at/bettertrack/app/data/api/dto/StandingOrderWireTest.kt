package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the v5 standing-order payloads.
 *
 * The load-bearing fact here is the ENVELOPE ASYMMETRY: the list is
 * `{"orders":[…]}` while every single-order response is the bare object. Getting
 * that backwards is a silent parse failure on the create path only, which is
 * exactly the kind of thing that survives a happy-path smoke test.
 */
class StandingOrderWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val monthlyBuy = """
        {"id":"11111111-1111-1111-1111-111111111111",
         "portfolioId":"22222222-2222-2222-2222-222222222222","kind":"buy-asset",
         "assetId":"33333333-3333-3333-3333-333333333333","assetSymbol":"VWCE",
         "assetName":"Vanguard FTSE All-World","amount":1.5,"currency":"EUR",
         "label":"Sparplan","cadence":"monthly","anchorDay":1,"startDate":"2026-01-01",
         "endDate":null,"status":"active","lastRunAt":"2026-08-01T04:00:00.000Z",
         "lastPeriodKey":"2026-08-01","nextRunDate":"2026-09-01",
         "createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-08-01T04:00:00.000Z"}
    """.trimIndent()

    @Test
    fun `the LIST is enveloped under orders`() {
        val wire = """{"orders":[$monthlyBuy]}"""
        val r = json.decodeFromString(StandingOrderListResponse.serializer(), wire)

        val o = r.orders.single()
        assertEquals(StandingOrderKinds.BUY_ASSET, o.kind)
        assertEquals("VWCE", o.assetSymbol)
        assertEquals(1, o.anchorDay)
        assertEquals("2026-09-01", o.nextRunDate)
        assertNull(o.endDate)
    }

    @Test
    fun `a SINGLE order is the BARE object with no order wrapper`() {
        // Verified against the platform monorepo: standingOrderService returns
        // toDto(...) directly for create/get/patch/pause/resume, and the OpenAPI
        // doc declares `response: R.StandingOrder` (not a wrapper schema).
        val o = json.decodeFromString(StandingOrderDto.serializer(), monthlyBuy)
        assertEquals("11111111-1111-1111-1111-111111111111", o.id)
        assertEquals("Sparplan", o.label)
    }

    @Test
    fun `a daily cash-deduct order has no asset and no anchor day`() {
        val wire = """
            {"id":"a","portfolioId":"p","kind":"cash-deduct","assetId":null,"assetSymbol":null,
             "assetName":null,"amount":20.0,"currency":"EUR","label":"Netflix","cadence":"daily",
             "anchorDay":null,"startDate":"2026-08-01","endDate":"2026-12-31","status":"paused",
             "lastRunAt":null,"lastPeriodKey":null,"nextRunDate":null,
             "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"}
        """.trimIndent()
        val o = json.decodeFromString(StandingOrderDto.serializer(), wire)

        assertNull(o.assetId)
        assertNull(o.anchorDay)
        assertEquals(StandingOrderStatuses.PAUSED, o.status)
        // Paused ⇒ no next run, and the UI must render that rather than guessing.
        assertNull(o.nextRunDate)
        assertEquals("2026-12-31", o.endDate)
    }

    @Test
    fun `an order with keys omitted reads as neutral instead of crashing`() {
        val o = json.decodeFromString(StandingOrderDto.serializer(), """{"id":"a"}""")
        assertEquals("", o.kind)
        assertEquals(0.0, o.amount, 0.0001)
        assertEquals(StandingOrderStatuses.ACTIVE, o.status)
        assertTrue(json.decodeFromString(StandingOrderListResponse.serializer(), "{}").orders.isEmpty())
    }

    @Test
    fun `a buy create body carries the asset and never a currency`() {
        val body = json.encodeToString(
            CreateStandingOrderRequest.serializer(),
            CreateStandingOrderRequest(
                portfolioId = "p",
                kind = StandingOrderKinds.BUY_ASSET,
                assetId = "a",
                amount = 1.5,
                label = "Sparplan",
                cadence = StandingOrderCadences.MONTHLY,
                anchorDay = 1,
                startDate = "2026-01-01",
            ),
        )
        assertTrue(body.contains(""""assetId":"a""""))
        assertTrue(body.contains(""""anchorDay":1"""))
        // currency is server-derived and has no place in the request at all.
        assertFalse(body.contains("currency"))
        // endDate was never set — the .strict() schema must not see a null for it.
        assertFalse(body.contains("endDate"))
    }

    @Test
    fun `a daily cash create body omits assetId and anchorDay entirely`() {
        // Not merely null: the server REJECTS those keys for this shape rather
        // than ignoring them, so an emitted "assetId":null would be a 400.
        val body = json.encodeToString(
            CreateStandingOrderRequest.serializer(),
            CreateStandingOrderRequest(
                portfolioId = "p",
                kind = StandingOrderKinds.CASH_DEDUCT,
                amount = 20.0,
                cadence = StandingOrderCadences.DAILY,
            ),
        )
        assertEquals(
            """{"portfolioId":"p","kind":"cash-deduct","amount":20.0,"cadence":"daily"}""",
            body,
        )
    }
}
