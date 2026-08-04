package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Read-path tests for ideas: the flat, tolerant DTOs decoding into the sealed
 * domain model, through a real MockWebServer.
 *
 * The read model is deliberately flat (a `conglomerate` source decodes with
 * `positions == null` and vice versa), so the branch selection lives in the
 * repository's mapping — which is exactly what these assert, both source
 * branches and all four benchmark cases.
 */
class IdeaDecodeTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: IdeasRepository

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = IdeasRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Fixtures (plain concatenation: no nested raw strings) ────────────────

    private fun idea(state: String, thesis: String = "null"): String =
        "{\"id\":\"id-1\",\"name\":\"Quality compounders\",\"thesis\":" + thesis +
            ",\"state\":" + state +
            ",\"createdAt\":\"2026-07-01T10:00:00.000Z\"" +
            ",\"updatedAt\":\"2026-08-01T10:00:00.000Z\"}"

    private fun listBody(state: String, thesis: String = "null"): String =
        "{\"ideas\":[" + idea(state, thesis) + "]}"

    private fun singleBody(state: String, thesis: String = "null"): String =
        "{\"idea\":" + idea(state, thesis) + "}"

    private fun adhocState(benchmark: String = "null"): String =
        "{\"source\":{\"kind\":\"adhoc\",\"positions\":[" +
            "{\"assetId\":\"as-1\",\"weight\":2.0},{\"assetId\":\"as-2\",\"weight\":1.0}]}," +
            "\"range\":\"5Y\",\"benchmark\":" + benchmark +
            ",\"mode\":\"redistribute\",\"rebalance\":\"quarterly\"}"

    private fun conglomerateState(benchmark: String = "null"): String =
        "{\"source\":{\"kind\":\"conglomerate\",\"conglomerateId\":\"cg-1\"}," +
            "\"range\":\"1Y\",\"benchmark\":" + benchmark +
            ",\"mode\":\"clip\",\"rebalance\":\"none\"}"

    private fun firstIdea(): Idea =
        (runBlocking { repo.ideas() } as BtResult.Ok).value.single()

    // ── source branches ──────────────────────────────────────────────────────

    @Test
    fun `an adhoc idea decodes into positions with their relative weights`() {
        server.enqueue(MockResponse().setBody(listBody(adhocState())))

        val idea = firstIdea()
        assertEquals("Quality compounders", idea.name)
        assertNull(idea.thesis)
        assertEquals("5Y", idea.state.range)
        assertEquals("redistribute", idea.state.mode)
        assertEquals("quarterly", idea.state.rebalance)

        val source = idea.state.source as IdeaSource.Adhoc
        assertEquals(listOf("as-1", "as-2"), source.positions.map { it.assetId })
        // Weights stay RELATIVE — the app never normalises them itself.
        assertEquals(2.0, source.positions[0].weight, 1e-9)
        assertEquals(1.0, source.positions[1].weight, 1e-9)
        // The chip list the detail screen resolves.
        assertEquals(listOf("as-1", "as-2"), idea.assetIds)
        assertEquals("/ideas", server.takeRequest().path)
    }

    @Test
    fun `a conglomerate idea decodes with no positions and an empty assetIds`() = runBlocking {
        server.enqueue(MockResponse().setBody(singleBody(conglomerateState(), "\"Wide moats.\"")))

        val idea = (repo.idea("id-1") as BtResult.Ok).value
        assertEquals("Wide moats.", idea.thesis)
        assertEquals("cg-1", (idea.state.source as IdeaSource.Conglomerate).conglomerateId)
        // Nothing to resolve to asset chips — the screen shows the conglomerate.
        assertTrue(idea.assetIds.isEmpty())
        assertEquals("/ideas/id-1", server.takeRequest().path)
    }

    @Test
    fun `an unmodelled source kind degrades to an empty adhoc rather than crashing`() {
        val state = "{\"source\":{\"kind\":\"portfolio\"},\"range\":\"MAX\"," +
            "\"benchmark\":null,\"mode\":\"clip\",\"rebalance\":\"none\"}"
        server.enqueue(MockResponse().setBody(listBody(state)))

        val source = firstIdea().state.source
        assertTrue(source is IdeaSource.Adhoc)
        assertTrue((source as IdeaSource.Adhoc).positions.isEmpty())
    }

    // ── all four benchmark cases ─────────────────────────────────────────────

    @Test
    fun `a null benchmark stays null`() {
        server.enqueue(MockResponse().setBody(listBody(adhocState("null"))))

        assertNull(firstIdea().state.benchmark)
    }

    @Test
    fun `an omitted benchmark key also reads as no benchmark`() {
        val state = "{\"source\":{\"kind\":\"adhoc\",\"positions\":" +
            "[{\"assetId\":\"as-1\",\"weight\":1.0}]}," +
            "\"range\":\"3Y\",\"mode\":\"cash\",\"rebalance\":\"monthly\"}"
        server.enqueue(MockResponse().setBody(listBody(state)))

        val idea = firstIdea()
        assertNull(idea.state.benchmark)
        assertEquals("3Y", idea.state.range)
        assertEquals("cash", idea.state.mode)
        assertEquals("monthly", idea.state.rebalance)
    }

    @Test
    fun `a preset benchmark decodes into the preset branch`() {
        server.enqueue(MockResponse().setBody(listBody(adhocState("{\"preset\":\"^GSPC\"}"))))

        val benchmark = firstIdea().state.benchmark
        assertEquals("^GSPC", (benchmark as IdeaBenchmark.Preset).symbol)
    }

    @Test
    fun `an asset benchmark decodes into the asset branch`() {
        server.enqueue(MockResponse().setBody(listBody(adhocState("{\"assetId\":\"as-bench\"}"))))

        val benchmark = firstIdea().state.benchmark
        assertEquals("as-bench", (benchmark as IdeaBenchmark.Asset).assetId)
    }

    @Test
    fun `a conglomerate benchmark decodes into the conglomerate branch`() {
        server.enqueue(
            MockResponse().setBody(listBody(adhocState("{\"conglomerateId\":\"cg-bench\"}"))),
        )

        val benchmark = firstIdea().state.benchmark
        assertEquals("cg-bench", (benchmark as IdeaBenchmark.Conglomerate).conglomerateId)
    }

    @Test
    fun `an all-null benchmark object reads as no benchmark, not a half-built one`() {
        val bench = "{\"preset\":null,\"assetId\":null,\"conglomerateId\":null}"
        server.enqueue(MockResponse().setBody(listBody(adhocState(bench))))

        assertNull(firstIdea().state.benchmark)
    }

    // ── write round-trips + refusals ─────────────────────────────────────────

    @Test
    fun `create posts the hand-built body and unwraps the 201 idea envelope`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(singleBody(conglomerateState())),
        )

        val r = repo.create(
            name = "Quality compounders",
            thesis = null,
            state = IdeaState(
                source = IdeaSource.Conglomerate("cg-1"),
                range = "1Y",
                benchmark = null,
                mode = "clip",
                rebalance = "none",
            ),
        )

        assertEquals("id-1", (r as BtResult.Ok).value.id)
        val request = server.takeRequest()
        assertEquals("/ideas", request.path)
        val body = request.body.readUtf8()
        // The two properties the strict schema cares about, on the actual bytes.
        assertTrue(body, body.contains("\"benchmark\":null"))
        assertTrue(body, !body.contains("\"positions\""))
    }

    @Test
    fun `update sends only the changed fields`() = runBlocking {
        server.enqueue(MockResponse().setBody(singleBody(adhocState())))

        repo.update("id-1", name = "Renamed", clearThesis = true)

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/ideas/id-1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"name\":\"Renamed\""))
        assertTrue(body, body.contains("\"thesis\":null"))
        assertTrue(body, !body.contains("\"state\""))
    }

    @Test
    fun `delete accepts a bare 204 with no body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        assertTrue(repo.delete("id-1") is BtResult.Ok)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/ideas/id-1", request.path)
    }

    @Test
    fun `a 404 on a single idea is reported as such - it is owner-only`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Idea not found\"}}"),
        )

        val r = repo.idea("someone-elses")
        assertEquals(404, (r as BtResult.Err).error.httpStatus)
    }

    @Test
    fun `a bad conglomerate reference keeps its own code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                "{\"error\":{\"code\":\"IDEA_CONGLOMERATE_NOT_FOUND\"," +
                    "\"message\":\"No such conglomerate\"}}",
            ),
        )

        val r = repo.create(
            name = "Idea",
            thesis = null,
            state = IdeaState(
                source = IdeaSource.Conglomerate("nope"),
                range = "MAX",
                benchmark = null,
                mode = "clip",
                rebalance = "none",
            ),
        )

        assertEquals(
            IdeasRepository.CODE_CONGLOMERATE_NOT_FOUND,
            (r as BtResult.Err).error.code,
        )
    }
}
