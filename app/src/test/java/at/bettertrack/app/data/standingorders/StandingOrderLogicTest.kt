package at.bettertrack.app.data.standingorders

import at.bettertrack.app.data.api.dto.CreateStandingOrderRequest
import at.bettertrack.app.data.api.dto.StandingOrderCadences
import at.bettertrack.app.data.api.dto.StandingOrderKinds
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client-side mirror of the server's standing-order shape rules, and the
 * tri-state PATCH builder.
 *
 * These rules are "iff" rules on both sides — an `assetId` on a cash order or an
 * `anchorDay` on a daily one is REJECTED, not ignored — so a form that only
 * checks the required direction still ships guaranteed 400s.
 */
class StandingOrderLogicTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private fun draft(
        kind: StandingOrderKind = StandingOrderKind.CashDeduct,
        cadence: StandingOrderCadence = StandingOrderCadence.Daily,
        amount: Double? = 20.0,
        assetId: String? = null,
        label: String? = null,
        anchorDay: Int? = null,
        startDate: String? = null,
        endDate: String? = null,
    ) = StandingOrderDraft(
        portfolioId = "22222222-2222-2222-2222-222222222222",
        kind = kind,
        cadence = cadence,
        amount = amount,
        assetId = assetId,
        label = label,
        anchorDay = anchorDay,
        startDate = startDate,
        endDate = endDate,
    )

    // ── The happy shapes ────────────────────────────────────────────────────

    @Test
    fun `a daily cash deduct is valid`() {
        assertTrue(validateStandingOrder(draft()).isValid)
    }

    @Test
    fun `a monthly buy with an asset and an anchor day is valid`() {
        val v = validateStandingOrder(
            draft(
                kind = StandingOrderKind.BuyAsset,
                cadence = StandingOrderCadence.Monthly,
                amount = 1.5,
                assetId = "33333333-3333-3333-3333-333333333333",
                anchorDay = 1,
            ),
        )
        assertTrue(v.problems.toString(), v.isValid)
    }

    // ── assetId iff buy-asset ───────────────────────────────────────────────

    @Test
    fun `a buy-asset order without an asset is AssetRequired`() {
        val v = validateStandingOrder(draft(kind = StandingOrderKind.BuyAsset))
        assertEquals(StandingOrderProblem.AssetRequired, v[StandingOrderField.AssetId])
        assertFalse(v.isValid)
    }

    @Test
    fun `a blank asset id counts as no asset`() {
        val v = validateStandingOrder(draft(kind = StandingOrderKind.BuyAsset, assetId = "   "))
        assertEquals(StandingOrderProblem.AssetRequired, v[StandingOrderField.AssetId])
    }

    @Test
    fun `a cash order carrying an asset is AssetNotAllowed`() {
        val v = validateStandingOrder(draft(kind = StandingOrderKind.CashAdd, assetId = "a"))
        assertEquals(StandingOrderProblem.AssetNotAllowed, v[StandingOrderField.AssetId])
    }

    // ── anchorDay iff monthly ───────────────────────────────────────────────

    @Test
    fun `a monthly order without an anchor day is AnchorDayRequired`() {
        val v = validateStandingOrder(draft(cadence = StandingOrderCadence.Monthly))
        assertEquals(StandingOrderProblem.AnchorDayRequired, v[StandingOrderField.AnchorDay])
    }

    @Test
    fun `a daily order carrying an anchor day is AnchorDayNotAllowed`() {
        val v = validateStandingOrder(draft(cadence = StandingOrderCadence.Daily, anchorDay = 15))
        assertEquals(StandingOrderProblem.AnchorDayNotAllowed, v[StandingOrderField.AnchorDay])
    }

    @Test
    fun `an anchor day outside 1 to 31 is out of range`() {
        assertEquals(
            StandingOrderProblem.AnchorDayOutOfRange,
            validateStandingOrder(
                draft(cadence = StandingOrderCadence.Monthly, anchorDay = 0),
            )[StandingOrderField.AnchorDay],
        )
        assertEquals(
            StandingOrderProblem.AnchorDayOutOfRange,
            validateStandingOrder(
                draft(cadence = StandingOrderCadence.Monthly, anchorDay = 32),
            )[StandingOrderField.AnchorDay],
        )
        // 31 is legal — the server clamps it to month-end in shorter months.
        assertTrue(
            validateStandingOrder(
                draft(cadence = StandingOrderCadence.Monthly, anchorDay = 31),
            ).isValid,
        )
    }

    // ── endDate >= startDate ────────────────────────────────────────────────

    @Test
    fun `an end date before the start date is refused`() {
        val v = validateStandingOrder(draft(startDate = "2026-08-10", endDate = "2026-08-09"))
        assertEquals(StandingOrderProblem.EndDateBeforeStart, v[StandingOrderField.EndDate])
    }

    @Test
    fun `an end date EQUAL to the start date is allowed`() {
        assertTrue(validateStandingOrder(draft(startDate = "2026-08-10", endDate = "2026-08-10")).isValid)
    }

    @Test
    fun `an end date after the start date is allowed, including across a year`() {
        assertTrue(validateStandingOrder(draft(startDate = "2026-12-31", endDate = "2027-01-01")).isValid)
    }

    @Test
    fun `a malformed date is reported on its own field`() {
        val v = validateStandingOrder(draft(startDate = "01-08-2026", endDate = "nope"))
        assertEquals(StandingOrderProblem.StartDateMalformed, v[StandingOrderField.StartDate])
        assertEquals(StandingOrderProblem.EndDateMalformed, v[StandingOrderField.EndDate])
    }

    @Test
    fun `an end date alone cannot be ordered locally - the server owns today`() {
        // startDate omitted ⇒ the server substitutes ITS calendar day and
        // re-checks; the app has no timezone-correct "today" to compare against,
        // so it must not invent one.
        assertTrue(validateStandingOrder(draft(endDate = "2020-01-01")).isValid)
    }

    // ── Amount + label ──────────────────────────────────────────────────────

    @Test
    fun `a missing, zero or negative amount is not positive`() {
        for (a in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "amount=$a",
                StandingOrderProblem.AmountNotPositive,
                validateStandingOrder(draft(amount = a))[StandingOrderField.Amount],
            )
        }
    }

    @Test
    fun `an amount past the ledger ceiling is too large`() {
        assertEquals(
            StandingOrderProblem.AmountTooLarge,
            validateStandingOrder(draft(amount = 1_000_000_001.0))[StandingOrderField.Amount],
        )
        assertTrue(validateStandingOrder(draft(amount = 1_000_000_000.0)).isValid)
    }

    @Test
    fun `a blank label is absent, not an error`() {
        assertTrue(validateStandingOrder(draft(label = "   ")).isValid)
    }

    @Test
    fun `a label past 120 characters is refused`() {
        assertTrue(validateStandingOrder(draft(label = "x".repeat(120))).isValid)
        assertEquals(
            StandingOrderProblem.LabelTooLong,
            validateStandingOrder(draft(label = "x".repeat(121)))[StandingOrderField.Label],
        )
    }

    @Test
    fun `several broken fields are all reported at once`() {
        val v = validateStandingOrder(
            draft(kind = StandingOrderKind.BuyAsset, amount = -1.0, anchorDay = 5),
        )
        assertEquals(3, v.problems.size)
        assertEquals(StandingOrderProblem.AmountNotPositive, v[StandingOrderField.Amount])
        assertEquals(StandingOrderProblem.AssetRequired, v[StandingOrderField.AssetId])
        assertEquals(StandingOrderProblem.AnchorDayNotAllowed, v[StandingOrderField.AnchorDay])
    }

    // ── Draft → request normalisation ───────────────────────────────────────

    @Test
    fun `toCreateRequest strips an asset id a cash kind must not send`() {
        val body = draft(kind = StandingOrderKind.CashAdd, assetId = "a").toCreateRequest()
        assertNull(body.assetId)
        assertEquals(StandingOrderKinds.CASH_ADD, body.kind)
    }

    @Test
    fun `toCreateRequest strips an anchor day a daily cadence must not send`() {
        assertNull(draft(cadence = StandingOrderCadence.Daily, anchorDay = 9).toCreateRequest().anchorDay)
    }

    @Test
    fun `toCreateRequest trims the label and drops a blank one`() {
        assertEquals("Netflix", draft(label = "  Netflix  ").toCreateRequest().label)
        assertNull(draft(label = "   ").toCreateRequest().label)
    }

    @Test
    fun `a normalised cash draft serializes to exactly the keys the server accepts`() {
        val body = json.encodeToString(
            CreateStandingOrderRequest.serializer(),
            draft(kind = StandingOrderKind.CashDeduct, assetId = "leftover", label = "Netflix")
                .toCreateRequest(),
        )
        assertEquals(
            """{"portfolioId":"22222222-2222-2222-2222-222222222222","kind":"cash-deduct",""" +
                """"amount":20.0,"label":"Netflix","cadence":"daily"}""",
            body,
        )
    }

    @Test
    fun `a monthly buy draft keeps its asset and anchor day`() {
        val body = draft(
            kind = StandingOrderKind.BuyAsset,
            cadence = StandingOrderCadence.Monthly,
            amount = 1.5,
            assetId = "a",
            anchorDay = 1,
            startDate = "2026-01-01",
        ).toCreateRequest()
        assertEquals("a", body.assetId)
        assertEquals(1, body.anchorDay)
        assertEquals(StandingOrderCadences.MONTHLY, body.cadence)
        assertEquals("2026-01-01", body.startDate)
    }

    // ── PATCH body ──────────────────────────────────────────────────────────

    @Test
    fun `an unchanged patch is null so no empty body is ever sent`() {
        // The server's .strict() schema 400s {} — the caller must skip instead.
        assertNull(buildStandingOrderPatch())
        assertNull(buildStandingOrderPatch(label = "   "))
    }

    @Test
    fun `an amount-only patch carries ONLY the amount`() {
        assertEquals("""{"amount":42.0}""", buildStandingOrderPatch(amount = 42.0).toString())
    }

    @Test
    fun `clearing the label sends an explicit null, which is a different fact from omitting it`() {
        assertEquals("""{"label":null}""", buildStandingOrderPatch(clearLabel = true).toString())
        // Setting it sends the trimmed value; omitting it sends nothing at all.
        assertEquals("""{"label":"Rent"}""", buildStandingOrderPatch(label = "  Rent ").toString())
        assertFalse(buildStandingOrderPatch(amount = 1.0).toString().contains("label"))
    }

    @Test
    fun `clearing the end date sends an explicit null`() {
        assertEquals("""{"endDate":null}""", buildStandingOrderPatch(clearEndDate = true).toString())
        assertEquals(
            """{"endDate":"2027-01-01"}""",
            buildStandingOrderPatch(endDate = "2027-01-01").toString(),
        )
    }

    @Test
    fun `an explicit clear beats a value passed alongside it`() {
        assertEquals(
            """{"label":null}""",
            buildStandingOrderPatch(label = "ignored", clearLabel = true).toString(),
        )
    }

    @Test
    fun `all three editable fields fit in one patch`() {
        assertEquals(
            """{"amount":9.0,"label":"Rent","endDate":null}""",
            buildStandingOrderPatch(
                amount = 9.0,
                label = "Rent",
                endDate = null,
                clearEndDate = true,
            ).toString(),
        )
    }

    // ── Wire vocabulary ─────────────────────────────────────────────────────

    @Test
    fun `wire vocabularies round trip and an unknown value never crashes`() {
        assertEquals(StandingOrderKind.BuyAsset, StandingOrderKind.fromWire("buy-asset"))
        assertNull(StandingOrderKind.fromWire("sell-asset"))
        assertEquals(StandingOrderCadence.Monthly, StandingOrderCadence.fromWire("monthly"))
        assertNull(StandingOrderCadence.fromWire("weekly"))
        assertEquals(StandingOrderStatus.Paused, StandingOrderStatus.fromWire("paused"))
        // An unknown status degrades to Active rather than hiding the order.
        assertEquals(StandingOrderStatus.Active, StandingOrderStatus.fromWire("hibernating"))
    }
}
