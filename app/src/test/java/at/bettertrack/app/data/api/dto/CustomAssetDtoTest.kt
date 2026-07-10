package at.bettertrack.app.data.api.dto

import at.bettertrack.app.ui.customassets.CUSTOM_ASSET_CATEGORIES
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Custom-asset value-smoothing (V3-P2) + list-adapter (#387) wire mapping. Uses
 * the app's EXACT Json config (encodeDefaults + explicitNulls=false) so the
 * PATCH-omission behavior tested here matches what actually goes on the wire.
 */
class CustomAssetDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── Create: smoothing always sent (server default is false) ─────────────────

    @Test
    fun `create encodes smoothing true`() {
        val out = json.encodeToString(
            CreateCustomAssetRequest.serializer(),
            CreateCustomAssetRequest(name = "House", category = "other", smoothing = true),
        )
        assertTrue(out, out.contains("\"smoothing\":true"))
    }

    @Test
    fun `create encodes smoothing false explicitly`() {
        val out = json.encodeToString(
            CreateCustomAssetRequest.serializer(),
            CreateCustomAssetRequest(name = "House", category = "other", smoothing = false),
        )
        assertTrue(out, out.contains("\"smoothing\":false"))
    }

    // ── Update: delta PATCH — null smoothing is OMITTED, a set value is sent ─────

    @Test
    fun `update with null smoothing omits the field`() {
        val out = json.encodeToString(
            UpdateCustomAssetRequest.serializer(),
            UpdateCustomAssetRequest(name = "New name", category = null, smoothing = null),
        )
        assertFalse(out, out.contains("smoothing"))
        assertFalse(out, out.contains("category"))
        assertTrue(out, out.contains("\"name\":\"New name\""))
    }

    @Test
    fun `update toggling smoothing sends it`() {
        val on = json.encodeToString(
            UpdateCustomAssetRequest.serializer(),
            UpdateCustomAssetRequest(smoothing = true),
        )
        assertEquals("""{"smoothing":true}""", on)
        val off = json.encodeToString(
            UpdateCustomAssetRequest.serializer(),
            UpdateCustomAssetRequest(smoothing = false),
        )
        assertEquals("""{"smoothing":false}""", off)
    }

    // ── Response decode ─────────────────────────────────────────────────────────

    @Test
    fun `custom asset dto decodes smoothing, defaults false when absent`() {
        val withFlag = json.decodeFromString(
            CustomAssetDto.serializer(),
            """{"id":"a1","symbol":"X","name":"X","category":"stock","currency":"EUR","smoothing":true}""",
        )
        assertTrue(withFlag.smoothing)
        // An older/cached payload without the field decodes as false (default).
        val legacy = json.decodeFromString(
            CustomAssetDto.serializer(),
            """{"id":"a1","symbol":"X","name":"X","category":"stock","currency":"EUR"}""",
        )
        assertFalse(legacy.smoothing)
    }

    // ── #387 list adapter: zero-holding item + latest value ─────────────────────

    @Test
    fun `list decodes a zero-holding asset with null latest value`() {
        val resp = json.decodeFromString(
            CustomAssetListResponse.serializer(),
            """{"assets":[
                {"id":"a1","symbol":"HOUSE","name":"House","category":"other","currency":"EUR","smoothing":true,"latestValue":{"date":"2026-07-01","value":410000.0}},
                {"id":"a2","symbol":"EMPTY","name":"No holding yet","category":"stock","currency":"EUR","smoothing":false,"latestValue":null}
            ]}""",
        )
        assertEquals(2, resp.assets.size)
        val house = resp.assets[0]
        assertTrue(house.smoothing)
        assertEquals(410000.0, house.latestValue!!.value, 0.0)
        val empty = resp.assets[1]
        assertNull(empty.latestValue)
        assertFalse(empty.smoothing)
    }

    // ── V3-P2 catalog taxonomy is what the create/edit chips offer ──────────────

    @Test
    fun `category taxonomy is the V3-P2 catalog set`() {
        assertEquals(
            listOf("stock", "etf", "crypto", "commodity", "cash_like", "other"),
            CUSTOM_ASSET_CATEGORIES,
        )
    }
}
