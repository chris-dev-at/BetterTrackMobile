package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.AlertsListResponse
import at.bettertrack.app.data.api.dto.CreateAlertRequest
import at.bettertrack.app.data.api.dto.UpdateAlertRequest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alert wire-mapping tests (owner ask 2026-07-10). Uses the app's EXACT Json
 * config (ignoreUnknownKeys + encodeDefaults + explicitNulls=false, and NO
 * coerceInputValues) so behaviour here matches what actually happens on device —
 * in particular the null `refPrice` the server sends for the four non-ref kinds.
 */
class AlertMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun asset(symbol: String = "AAPL") =
        """{"id":"as-$symbol","symbol":"$symbol","name":"$symbol Inc","currency":"USD","type":"stock"}"""

    // ── Regression: null refPrice must NOT crash (contract refPrice: number|null) ─

    @Test
    fun `a non-ref alert decodes with a null refPrice and maps refPrice to zero`() {
        val dto = json.decodeFromString(
            AlertsListResponse.serializer(),
            """{"items":[{"id":"a1","kind":"price_above","threshold":150.0,"refPrice":null,""" +
                """"repeat":false,"status":"active","lastTriggeredAt":null,"asset":${asset()}}]}""",
        )
        val alert = dto.items.single().toDomainOrNull()!!
        assertEquals(AlertKind.PriceAbove, alert.kind)
        assertEquals(150.0, alert.threshold, 1e-9)
        assertEquals(0.0, alert.refPrice, 1e-9) // null coalesced; never read for this kind
        assertEquals(AlertStatus.Active, alert.status)
        assertNull(alert.lastTriggeredAt)
        assertFalse(alert.repeat)
    }

    @Test
    fun `an older payload omitting refPrice entirely also decodes`() {
        val dto = json.decodeFromString(
            AlertsListResponse.serializer(),
            """{"items":[{"id":"a1","kind":"pct_day_up","threshold":3.0,""" +
                """"repeat":false,"status":"active","asset":${asset("TSLA")}}]}""",
        )
        val alert = dto.items.single().toDomainOrNull()!!
        assertEquals(AlertKind.PctDayUp, alert.kind)
        assertEquals(0.0, alert.refPrice, 1e-9)
    }

    // ── From-ref kind carries its captured reference price + trigger metadata ──

    @Test
    fun `a from-ref alert preserves refPrice, repeat, triggered status and timestamp`() {
        val dto = json.decodeFromString(
            AlertsListResponse.serializer(),
            """{"items":[{"id":"a2","kind":"pct_up_from_ref","threshold":5.0,"refPrice":120.0,""" +
                """"repeat":true,"status":"triggered","lastTriggeredAt":"2026-07-10T10:00:00Z",""" +
                """"asset":${asset("MSFT")}}]}""",
        )
        val alert = dto.items.single().toDomainOrNull()!!
        assertEquals(AlertKind.PctUpFromRef, alert.kind)
        assertEquals(120.0, alert.refPrice, 1e-9)
        assertTrue(alert.repeat)
        assertEquals(AlertStatus.Triggered, alert.status)
        assertEquals("2026-07-10T10:00:00Z", alert.lastTriggeredAt)
        assertEquals("MSFT", alert.asset.symbol)
        assertEquals("USD", alert.asset.currency)
    }

    // ── Forward-compatible: an unknown kind is skipped, never a crash ──────────

    @Test
    fun `an unknown kind maps to null and is dropped from the list`() {
        val dto = json.decodeFromString(
            AlertsListResponse.serializer(),
            """{"items":[
                {"id":"a1","kind":"price_below","threshold":90.0,"refPrice":null,"repeat":false,"status":"active","asset":${asset()}},
                {"id":"a2","kind":"pct_sideways_wobble","threshold":1.0,"refPrice":null,"repeat":false,"status":"active","asset":${asset("X")}}
            ]}""",
        )
        assertNull(dto.items[1].toDomainOrNull()) // unknown kind → null
        val kept = dto.items.mapNotNull { it.toDomainOrNull() }
        assertEquals(1, kept.size)
        assertEquals(AlertKind.PriceBelow, kept.single().kind)
    }

    // ── Status + kind wire round-trips ─────────────────────────────────────────

    @Test
    fun `status fromWire maps the three states and defaults unknown to active`() {
        assertEquals(AlertStatus.Active, AlertStatus.fromWire("active"))
        assertEquals(AlertStatus.Triggered, AlertStatus.fromWire("triggered"))
        assertEquals(AlertStatus.Disabled, AlertStatus.fromWire("disabled"))
        assertEquals(AlertStatus.Active, AlertStatus.fromWire("something_new"))
    }

    @Test
    fun `every kind round-trips through its wire string`() {
        for (k in AlertKind.entries) {
            assertEquals(k, AlertKind.fromWire(k.wire))
        }
        assertNull(AlertKind.fromWire("nope"))
    }

    // ── Request encoding: create sends all fields, PATCH omits nulls (delta) ───

    @Test
    fun `create request encodes assetId, kind, threshold and repeat`() {
        val out = json.encodeToString(
            CreateAlertRequest.serializer(),
            CreateAlertRequest(assetId = "as-1", kind = "price_above", threshold = 150.0, repeat = true),
        )
        assertTrue(out, out.contains("\"assetId\":\"as-1\""))
        assertTrue(out, out.contains("\"kind\":\"price_above\""))
        assertTrue(out, out.contains("\"threshold\":150.0"))
        assertTrue(out, out.contains("\"repeat\":true"))
    }

    @Test
    fun `patch omits a null field and sends only what changed`() {
        assertEquals(
            """{"threshold":200.0}""",
            json.encodeToString(UpdateAlertRequest.serializer(), UpdateAlertRequest(threshold = 200.0, repeat = null)),
        )
        assertEquals(
            """{"repeat":false}""",
            json.encodeToString(UpdateAlertRequest.serializer(), UpdateAlertRequest(threshold = null, repeat = false)),
        )
    }
}
