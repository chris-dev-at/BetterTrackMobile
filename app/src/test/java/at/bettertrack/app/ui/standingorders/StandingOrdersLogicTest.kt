package at.bettertrack.app.ui.standingorders

import at.bettertrack.app.data.api.dto.StandingOrderCadences
import at.bettertrack.app.data.api.dto.StandingOrderDto
import at.bettertrack.app.data.api.dto.StandingOrderKinds
import at.bettertrack.app.data.api.dto.StandingOrderStatuses
import at.bettertrack.app.data.standingorders.buildStandingOrderPatch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The screen's pure half: list ordering, repaint-from-response, the edit diff,
 * and the two date renderers. Everything here runs without a device because the
 * failure modes it covers (a paused row staying at the top, an empty PATCH being
 * sent, a malformed server day blanking a row) are logic bugs, not pixels.
 */
class StandingOrdersLogicTest {

    private fun order(
        id: String,
        status: String = StandingOrderStatuses.ACTIVE,
        nextRunDate: String? = null,
        amount: Double = 100.0,
        label: String? = null,
        endDate: String? = null,
        kind: String = StandingOrderKinds.CASH_ADD,
    ) = StandingOrderDto(
        id = id,
        portfolioId = "p1",
        kind = kind,
        amount = amount,
        label = label,
        cadence = StandingOrderCadences.MONTHLY,
        anchorDay = 5,
        startDate = "2026-01-05",
        endDate = endDate,
        status = status,
        nextRunDate = nextRunDate,
    )

    // ── Ordering ────────────────────────────────────────────────────────────

    @Test
    fun `active orders sort above paused ones`() {
        val sorted = sortStandingOrders(
            listOf(
                order("paused", status = StandingOrderStatuses.PAUSED, nextRunDate = null),
                order("active", nextRunDate = "2026-09-05"),
            ),
        )
        assertEquals(listOf("active", "paused"), sorted.map { it.id })
    }

    @Test
    fun `the soonest next run comes first`() {
        val sorted = sortStandingOrders(
            listOf(
                order("later", nextRunDate = "2026-12-05"),
                order("sooner", nextRunDate = "2026-09-05"),
            ),
        )
        assertEquals(listOf("sooner", "later"), sorted.map { it.id })
    }

    @Test
    fun `an active order with no next run sinks below the scheduled ones`() {
        // Past its end date but still "active" — it will never fire again, so it
        // must not sit above an order that fires tomorrow.
        val sorted = sortStandingOrders(
            listOf(
                order("spent", nextRunDate = null),
                order("scheduled", nextRunDate = "2026-09-05"),
            ),
        )
        assertEquals(listOf("scheduled", "spent"), sorted.map { it.id })
    }

    @Test
    fun `an unknown status reads as active rather than vanishing to the bottom`() {
        val sorted = sortStandingOrders(
            listOf(
                order("paused", status = StandingOrderStatuses.PAUSED, nextRunDate = "2026-01-01"),
                order("future-status", status = "hibernating", nextRunDate = "2026-09-05"),
            ),
        )
        assertEquals(listOf("future-status", "paused"), sorted.map { it.id })
    }

    // ── Repaint from the response body ──────────────────────────────────────

    @Test
    fun `a paused order is replaced in place and sinks`() {
        val before = sortStandingOrders(
            listOf(order("a", nextRunDate = "2026-09-05"), order("b", nextRunDate = "2026-09-06")),
        )
        val paused = order("a", status = StandingOrderStatuses.PAUSED, nextRunDate = null)
        val after = applyUpdatedOrder(before, paused)
        assertEquals(2, after.size)
        assertEquals(listOf("b", "a"), after.map { it.id })
        assertEquals(StandingOrderStatuses.PAUSED, after.last().status)
    }

    @Test
    fun `a freshly created order is inserted rather than dropped`() {
        val after = applyUpdatedOrder(
            listOf(order("existing", nextRunDate = "2026-09-06")),
            order("new", nextRunDate = "2026-09-05"),
        )
        assertEquals(listOf("new", "existing"), after.map { it.id })
    }

    // ── Edit diff ───────────────────────────────────────────────────────────

    @Test
    fun `an untouched edit form produces no changes at all`() {
        // An empty PATCH is a 400 — the save button has to be able to stay off.
        val original = order("a", amount = 250.0, label = "Salary", endDate = "2027-01-01")
        val intent = standingOrderEditIntent(original, 250.0, "Salary", "2027-01-01")
        assertFalse(intent.hasChanges)
        assertNull(intent.toPatch())
    }

    @Test
    fun `only the changed amount travels`() {
        val original = order("a", amount = 250.0, label = "Salary")
        val intent = standingOrderEditIntent(original, 300.0, "Salary", null)
        assertTrue(intent.hasChanges)
        val patch = intent.toPatch()
        assertEquals(setOf("amount"), patch?.keys)
        assertEquals(JsonPrimitive(300.0), patch?.get("amount"))
    }

    @Test
    fun `emptying the label sends an explicit json null`() {
        val original = order("a", label = "Netflix")
        val patch = standingOrderEditIntent(original, 100.0, "   ", null).toPatch()
        assertEquals(setOf("label"), patch?.keys)
        assertEquals(JsonNull, patch?.get("label"))
    }

    @Test
    fun `a label that was already absent and stays absent is not a change`() {
        val original = order("a", label = null)
        val intent = standingOrderEditIntent(original, 100.0, "", null)
        assertFalse(intent.clearLabel)
        assertFalse(intent.hasChanges)
    }

    @Test
    fun `clearing the end date sends an explicit json null`() {
        val original = order("a", endDate = "2027-01-01")
        val patch = standingOrderEditIntent(original, 100.0, "", null).toPatch()
        assertEquals(setOf("endDate"), patch?.keys)
        assertEquals(JsonNull, patch?.get("endDate"))
    }

    @Test
    fun `adding an end date to an open-ended order is a change`() {
        val original = order("a", endDate = null)
        val patch = standingOrderEditIntent(original, 100.0, "", "2027-01-01").toPatch()
        assertEquals(setOf("endDate"), patch?.keys)
        assertEquals(JsonPrimitive("2027-01-01"), patch?.get("endDate"))
    }

    @Test
    fun `an unparseable amount field never blanks the amount server-side`() {
        // parseLocalizedDecimal returns null for junk; a null must mean "leave it",
        // never "set it to zero".
        val original = order("a", amount = 250.0)
        val intent = standingOrderEditIntent(original, null, "", null)
        assertNull(intent.amount)
        assertFalse(intent.hasChanges)
    }

    // ── Date rendering ──────────────────────────────────────────────────────

    @Test
    fun `a schedule day renders as a readable date`() {
        assertEquals("5 Jun 2026", formatIsoDay("2026-06-05", Locale.ENGLISH))
    }

    @Test
    fun `a null or blank day renders as nothing at all`() {
        assertNull(formatIsoDay(null, Locale.ENGLISH))
        assertNull(formatIsoDay("  ", Locale.ENGLISH))
        assertNull(formatIsoInstantDay(null, Locale.ENGLISH))
    }

    @Test
    fun `a day this app cannot parse is shown verbatim rather than swallowed`() {
        assertEquals("someday", formatIsoDay("someday", Locale.ENGLISH))
        assertEquals("nope", formatIsoInstantDay("nope", Locale.ENGLISH))
    }

    @Test
    fun `lastRunAt is an instant, not a day, and still renders`() {
        val zulu = formatIsoInstantDay("2026-06-05T10:15:30Z", Locale.ENGLISH)
        assertTrue(zulu!!.contains("2026"))
        // An offset instant must not fall through to the verbatim escape hatch.
        val offset = formatIsoInstantDay("2026-06-05T12:15:30+02:00", Locale.ENGLISH)
        assertTrue(offset!!.contains("2026"))
        assertFalse(offset.contains("T"))
    }

    // ── Prefill round-trip ──────────────────────────────────────────────────

    @Test
    fun `a numeric prefill carries no grouping or trailing zeros`() {
        assertEquals("1500", plainAmountText(1500.0))
        assertEquals("12.5", plainAmountText(12.5))
        assertEquals("0.00000001", plainAmountText(0.00000001))
    }

    private fun StandingOrderEditIntent.toPatch() = buildStandingOrderPatch(
        amount = amount,
        label = label,
        clearLabel = clearLabel,
        endDate = endDate,
        clearEndDate = clearEndDate,
    )
}
