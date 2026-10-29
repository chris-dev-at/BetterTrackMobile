package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A basket that contains a basket (platform #655, V5-P6).
 *
 * The live spec's `positions[]` is a `oneOf` on `kind`: an asset row
 * (`assetId` + `asset`) or a NESTED row (`childId` + `child`). The app modelled
 * only the first, with both fields required, so a single child basket made the
 * whole response undecodable — `GET`, every write that answers with the detail,
 * and the friend-shared view alike. These fixtures are the spec's two shapes,
 * verbatim, side by side.
 */
class NestedConglomerateWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val mixedPositions = """
        [
          {"kind":"asset","assetId":"a1","weightPct":60,"sortOrder":0,
           "asset":{"symbol":"VWCE.DE","name":"Vanguard FTSE All-World","currency":"EUR","type":"etf"}},
          {"kind":"conglomerate","childId":"c-child","weightPct":40,"sortOrder":1,
           "child":{"id":"c-child","name":"Tech tilt","status":"active","positionCount":3}}
        ]
    """.trimIndent()

    private fun ok(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun detailBody(positions: String = mixedPositions) = """
        {"id":"c-parent","name":"Core + tilt","description":null,"status":"active",
         "visibility":"private","positionCount":2,
         "createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-01T10:00:00Z",
         "positions":$positions}
    """.trimIndent()

    @Test
    fun `GET detail decodes an asset row and a nested row`() = runBlocking {
        server.enqueue(ok(detailBody()))
        val d = (ConglomerateRepository(api, json).detail("c-parent") as BtResult.Ok).value

        assertEquals(2, d.positions.size)
        val asset = d.positions[0]
        assertFalse(asset.isNested)
        assertEquals("a1", asset.assetId)
        assertEquals("VWCE.DE", asset.symbol)
        assertEquals(60.0, asset.weightPct, 1e-9)

        val child = d.positions[1]
        assertTrue(child.isNested)
        assertEquals("c-child", child.childId)
        assertEquals("c-child", child.refId)
        assertEquals("Tech tilt", child.name)
        assertEquals(3, child.childPositionCount)
        assertEquals(40.0, child.weightPct, 1e-9)
        // A nested row has no asset identity to leak into a backtest/asset link.
        assertEquals("", child.assetId)
    }

    /** A pre-nesting server sends no `kind`: every such row is an asset row. */
    @Test
    fun `a row without kind is an asset row`() = runBlocking {
        server.enqueue(
            ok(
                detailBody(
                    """[{"assetId":"a1","weightPct":100,"sortOrder":0,
                        "asset":{"symbol":"AAPL","name":"Apple","currency":"USD","type":"stock"}}]""",
                ),
            ),
        )
        val d = (ConglomerateRepository(api, json).detail("c-parent") as BtResult.Ok).value
        assertEquals(listOf("a1"), d.positions.map { it.assetId })
        assertFalse(d.positions.single().isNested)
    }

    /** A builder save of a nested basket keeps the child — as `childId`, never `assetId`. */
    @Test
    fun `PUT positions sends a nested row as childId and decodes the answer`() = runBlocking {
        server.enqueue(ok(detailBody()))
        val r = ConglomerateRepository(api, json).replacePositions(
            "c-parent",
            listOf(PositionWeight("a1", 60.0), PositionWeight("c-child", 40.0, nested = true)),
        )
        assertTrue(r is BtResult.Ok)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/conglomerates/c-parent/positions", req.path)
        val rows = json.parseToJsonElement(req.body.readUtf8()).jsonObject["positions"]!!.jsonArray
        val asset = rows[0].jsonObject
        val nested = rows[1].jsonObject
        assertEquals(setOf("assetId", "weightPct"), asset.keys)
        assertEquals("a1", asset["assetId"]!!.jsonPrimitive.content)
        // Exactly the `.strict()` child shape: no stray null assetId alongside.
        assertEquals(setOf("childId", "weightPct"), nested.keys)
        assertEquals("c-child", nested["childId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resolved weights are the backtest input for a nested basket`() = runBlocking {
        server.enqueue(
            ok(
                """{"conglomerateId":"c-parent","nested":true,"unresolvedPct":0,
                    "positions":[
                      {"assetId":"a1","weightPct":60,"asset":{"symbol":"VWCE.DE","name":"V","currency":"EUR","type":"etf"}},
                      {"assetId":"a2","weightPct":40,"asset":{"symbol":"NVDA","name":"N","currency":"USD","type":"stock"}}
                    ]}""",
            ),
        )
        val r = ConglomerateRepository(api, json).resolvedWeights("c-parent")
        assertEquals(BtResult.Ok(listOf("a1" to 60.0, "a2" to 40.0)), r)
        assertEquals("/conglomerates/c-parent/resolved", server.takeRequest().path)
    }

    @Test
    fun `the friend-shared view decodes a nested row`() = runBlocking {
        server.enqueue(
            ok(
                """{"conglomerateId":"c-parent","name":"Core + tilt","description":null,"status":"active",
                    "owner":{"id":"u1","username":"alice"},
                    "positions":$mixedPositions}""",
            ),
        )
        val repo = DefaultSocialRepository(api, json, webOrigin = "https://bettertrack.at")
        val d = (repo.sharedConglomerate("c-parent") as BtResult.Ok).value

        assertEquals(2, d.positions.size)
        assertEquals("VWCE.DE", d.positions[0].asset!!.symbol)
        assertNull(d.positions[0].child)
        assertTrue(d.positions[1].isNested)
        assertEquals("Tech tilt", d.positions[1].child!!.name)
        assertNull(d.positions[1].asset)
        // Distinct, stable list keys across the two kinds.
        assertEquals(listOf("asset:a1", "child:c-child"), d.positions.map { it.rowKey })
    }
}
