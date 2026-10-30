package at.bettertrack.app.data.standingorders

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.widget.RecordingWidgetRefreshTrigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.lang.reflect.Modifier

/** Every standing-order write tells the cash widgets after a 2xx; reads and refusals do not. */
class StandingOrderWidgetRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var widgets: RecordingWidgetRefreshTrigger
    private lateinit var repo: StandingOrderRepository

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
        widgets = RecordingWidgetRefreshTrigger()
        repo = StandingOrderRepository(api, json, widgets)
    }

    @After
    fun tearDown() = server.shutdown()

    private val draft = StandingOrderDraft(
        portfolioId = "p1",
        kind = StandingOrderKind.CashDeduct,
        cadence = StandingOrderCadence.Monthly,
        amount = 20.0,
        label = "Netflix",
        anchorDay = 15,
        startDate = "2026-11-01",
    )

    private val writes: Map<String, Pair<MockResponse, suspend StandingOrderRepository.() -> BtResult<*>>> = mapOf(
        "create" to (MockResponse().setResponseCode(201).setBody(ORDER) to { create(draft) }),
        "update" to (MockResponse().setBody(ORDER) to { update("o1", amount = 25.0) }),
        "pause" to (MockResponse().setBody(ORDER) to { pause("o1") }),
        "resume" to (MockResponse().setBody(ORDER) to { resume("o1") }),
        "delete" to (MockResponse().setResponseCode(204) to { delete("o1") }),
    )

    @Test
    fun `every order write tells the cash widgets once, naming the order's portfolio when known`() = runBlocking {
        assertEquals(WRITES, writes.keys)
        writes.forEach { (name, w) ->
            widgets.clear()
            server.enqueue(w.first)

            val r = repo.(w.second)()

            assertTrue("$name should succeed, was $r", r is BtResult.Ok)
            assertEquals("$name → one cash refresh", 1, widgets.cash.size)
            val expected = if (name == "delete") null else "p1"
            assertEquals(name, expected, widgets.cash.single().portfolioId)
        }
    }

    @Test
    fun `a refused order write and every read tell nobody`() = runBlocking {
        writes.forEach { (name, w) ->
            widgets.clear()
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"NOPE","message":"no"}}"""))
            val r = repo.(w.second)()
            assertTrue("$name should fail, was $r", r is BtResult.Err)
            assertTrue("$name refused → no refresh", widgets.calls.isEmpty())
        }
        server.enqueue(MockResponse().setBody("""{"orders":[$ORDER]}"""))
        server.enqueue(MockResponse().setBody(ORDER))
        repo.list("p1")
        repo.get("o1")
        assertTrue(widgets.calls.isEmpty())
    }

    @Test
    fun `every public method is sorted - a new write fails here until it is wired`() {
        val declared = StandingOrderRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .map { it.name }
            .toSet()
        assertEquals(WRITES + setOf("list", "get"), declared)
    }

    private companion object {
        val WRITES = setOf("create", "update", "pause", "resume", "delete")

        const val ORDER = """
            {"id":"o1","portfolioId":"p1","kind":"cash-deduct","assetId":null,"assetSymbol":null,
             "assetName":null,"amount":20.0,"currency":"EUR","label":"Netflix","cadence":"monthly",
             "anchorDay":15,"startDate":"2026-08-01","endDate":null,"status":"active",
             "lastRunAt":null,"lastPeriodKey":null,"nextRunDate":"2026-09-15",
             "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"}
        """
    }
}
