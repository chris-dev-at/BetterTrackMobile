package at.bettertrack.app.data.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IdeaWire] request-body tests — the exact KEY SETS, asserted literally.
 *
 * The ideas write schemas are `.strict()` discriminated unions, so this is not
 * pedantry: an extra key is a 400, a missing `benchmark` key is a 400, and a
 * `"positions": null` on the conglomerate branch is a 400 too. Every one of those
 * is invisible in a normal serializer test and would only ever be discovered as
 * a failed save on someone's phone.
 */
class IdeaWireTest {

    /** The app's production Json — note `explicitNulls = false`. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val adhoc = IdeaSource.Adhoc(
        listOf(IdeaPosition("a1", 1.0), IdeaPosition("a2", 2.5)),
    )

    private fun state(
        source: IdeaSource = adhoc,
        benchmark: IdeaBenchmark? = null,
    ) = IdeaState(
        source = source,
        range = "5Y",
        benchmark = benchmark,
        mode = "clip",
        rebalance = "quarterly",
    )

    // ── source: exactly its own branch's keys ────────────────────────────────

    @Test
    fun `a conglomerate source emits kind and conglomerateId and NOTHING else`() {
        val body = IdeaWire.source(IdeaSource.Conglomerate("cg-1"))

        assertEquals(setOf("kind", "conglomerateId"), body.keys)
        assertEquals("conglomerate", body["kind"]!!.jsonPrimitive.content)
        assertEquals("cg-1", body["conglomerateId"]!!.jsonPrimitive.content)
        // Not even as an explicit null: the strict branch rejects the key itself.
        assertFalse("positions" in body)
    }

    @Test
    fun `an adhoc source emits kind and positions and NOTHING else`() {
        val body = IdeaWire.source(adhoc)

        assertEquals(setOf("kind", "positions"), body.keys)
        assertEquals("adhoc", body["kind"]!!.jsonPrimitive.content)
        assertFalse("conglomerateId" in body)

        val positions = body["positions"] as JsonArray
        assertEquals(2, positions.size)
        assertEquals(setOf("assetId", "weight"), positions[0].jsonObject.keys)
        assertEquals("a1", positions[0].jsonObject["assetId"]!!.jsonPrimitive.content)
        assertEquals(1.0, positions[0].jsonObject["weight"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(2.5, positions[1].jsonObject["weight"]!!.jsonPrimitive.content.toDouble(), 1e-9)
    }

    @Test
    fun `an empty adhoc source still emits the positions key, never omits it`() {
        val body = IdeaWire.source(IdeaSource.Adhoc(emptyList()))

        assertEquals(setOf("kind", "positions"), body.keys)
        assertEquals(0, (body["positions"] as JsonArray).size)
    }

    // ── benchmark: exactly one key, or an explicit null ──────────────────────

    @Test
    fun `each benchmark branch names exactly one field`() {
        assertEquals(
            setOf("preset"),
            (IdeaWire.benchmark(IdeaBenchmark.Preset("^GSPC")) as JsonObject).keys,
        )
        assertEquals(
            setOf("assetId"),
            (IdeaWire.benchmark(IdeaBenchmark.Asset("as-1")) as JsonObject).keys,
        )
        assertEquals(
            setOf("conglomerateId"),
            (IdeaWire.benchmark(IdeaBenchmark.Conglomerate("cg-1")) as JsonObject).keys,
        )
    }

    @Test
    fun `no benchmark is JsonNull, not an omission and not an empty object`() {
        assertEquals(JsonNull, IdeaWire.benchmark(null))
    }

    // ── state: the required-present-but-nullable benchmark ───────────────────

    @Test
    fun `state always carries all five keys with benchmark present`() {
        val body = IdeaWire.state(state())

        assertEquals(
            setOf("source", "range", "benchmark", "mode", "rebalance"),
            body.keys,
        )
        assertEquals("5Y", body["range"]!!.jsonPrimitive.content)
        assertEquals("clip", body["mode"]!!.jsonPrimitive.content)
        assertEquals("quarterly", body["rebalance"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["benchmark"])
    }

    @Test
    fun `the explicit benchmark null SURVIVES the app's explicitNulls-false Json`() {
        // This is the whole reason the body is hand-built rather than serialized
        // from a DTO: the app's global Json can never emit "present and null", and
        // the server requires exactly that. If this line ever prints
        // {"...","mode":...} without "benchmark", every save with no comparison
        // line 400s.
        val encoded = json.encodeToString(JsonObject.serializer(), IdeaWire.state(state()))

        assertTrue(encoded, encoded.contains("\"benchmark\":null"))
    }

    // ── createBody ───────────────────────────────────────────────────────────

    @Test
    fun `createBody omits thesis entirely when it is null or blank`() {
        assertEquals(setOf("name", "state"), IdeaWire.createBody("Idea", null, state()).keys)
        assertEquals(setOf("name", "state"), IdeaWire.createBody("Idea", "", state()).keys)
        assertEquals(setOf("name", "state"), IdeaWire.createBody("Idea", "   ", state()).keys)
    }

    @Test
    fun `createBody trims name and thesis and keeps both when written`() {
        val body = IdeaWire.createBody("  Quality compounders  ", "  Wide moats.  ", state())

        assertEquals(setOf("name", "thesis", "state"), body.keys)
        assertEquals("Quality compounders", body["name"]!!.jsonPrimitive.content)
        assertEquals("Wide moats.", body["thesis"]!!.jsonPrimitive.content)
    }

    @Test
    fun `createBody nests the source branch untouched`() {
        val body = IdeaWire.createBody("Idea", null, state(IdeaSource.Conglomerate("cg-9")))
        val source = body["state"]!!.jsonObject["source"]!!.jsonObject

        assertEquals(setOf("kind", "conglomerateId"), source.keys)
    }

    // ── updateBody: the three meanings of `thesis` ───────────────────────────

    @Test
    fun `an untouched thesis is absent from the patch`() {
        val body = IdeaWire.updateBody(name = "New title")

        assertEquals(setOf("name"), body.keys)
        assertFalse("thesis" in body)
    }

    @Test
    fun `a written thesis is sent as a trimmed string`() {
        val body = IdeaWire.updateBody(thesis = "  Rewritten.  ")

        assertEquals(setOf("thesis"), body.keys)
        assertEquals("Rewritten.", body["thesis"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearThesis sends an explicit null, which is what an emptied field means`() {
        val body = IdeaWire.updateBody(clearThesis = true)

        assertEquals(setOf("thesis"), body.keys)
        assertEquals(JsonNull, body["thesis"])
        assertTrue(
            json.encodeToString(JsonObject.serializer(), body).contains("\"thesis\":null"),
        )
    }

    @Test
    fun `clearThesis wins over a supplied thesis so the two can never both be sent`() {
        val body = IdeaWire.updateBody(thesis = "ignored", clearThesis = true)

        assertEquals(setOf("thesis"), body.keys)
        assertEquals(JsonNull, body["thesis"])
    }

    @Test
    fun `updateBody with nothing to change is empty rather than half-filled`() {
        // The caller has to catch this before sending — an empty PATCH is a 400 —
        // but the builder must not invent a field to avoid it.
        assertTrue(IdeaWire.updateBody().isEmpty())
    }

    @Test
    fun `updateBody can carry a whole replacement state alongside a rename`() {
        val body = IdeaWire.updateBody(
            name = "Renamed",
            state = state(benchmark = IdeaBenchmark.Preset("URTH")),
        )

        assertEquals(setOf("name", "state"), body.keys)
        val benchmark = body["state"]!!.jsonObject["benchmark"]!!.jsonObject
        assertEquals(setOf("preset"), benchmark.keys)
        assertEquals("URTH", benchmark["preset"]!!.jsonPrimitive.content)
    }
}
