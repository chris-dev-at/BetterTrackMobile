package at.bettertrack.app.data.standingorders

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.StandingOrderStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Wire-level tests of the standing-order repository.
 *
 * The point of exercising these through a real MockWebServer is the envelope
 * asymmetry — list wrapped, singles bare. A DTO that guessed a `{"order":…}`
 * wrapper would parse the list fine and fail only on create, pause and resume.
 */
class StandingOrderRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var repo: StandingOrderRepository

    // Matches the app's production Json config (see di/AppGraph).
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
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        repo = StandingOrderRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `list unwraps the orders envelope and can narrow to one portfolio`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"orders":[$ORDER]}"""))

        val r = repo.list("p1")

        assertEquals("o1", (r as BtResult.Ok).value.single().id)
        assertEquals("/standing-orders?portfolioId=p1", server.takeRequest().path)
    }

    @Test
    fun `list without a portfolio drops the query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"orders":[]}"""))

        repo.list()

        assertEquals("/standing-orders", server.takeRequest().path)
    }

    @Test
    fun `create parses the BARE 201 body, not an order envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(ORDER))

        val r = repo.create(
            StandingOrderDraft(
                portfolioId = "p1",
                kind = StandingOrderKind.CashDeduct,
                cadence = StandingOrderCadence.Monthly,
                amount = 20.0,
                label = "Netflix",
                anchorDay = 15,
            ),
        )

        assertTrue("was $r", r is BtResult.Ok)
        assertEquals("o1", (r as BtResult.Ok).value.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/standing-orders", req.path)
        assertEquals(
            """{"portfolioId":"p1","kind":"cash-deduct","amount":20.0,"label":"Netflix",""" +
                """"cadence":"monthly","anchorDay":15}""",
            req.body.readUtf8(),
        )
        // The platform mounts no idempotency middleware on this router.
        assertEquals(null, req.getHeader("Idempotency-Key"))
    }

    @Test
    fun `create strips the keys the server would reject for this shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(ORDER))

        repo.create(
            StandingOrderDraft(
                portfolioId = "p1",
                kind = StandingOrderKind.CashAdd,
                cadence = StandingOrderCadence.Daily,
                amount = 1500.0,
                // Both of these are REJECTED (not ignored) for this kind/cadence.
                assetId = "leftover-from-a-kind-switch",
                anchorDay = 9,
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("assetId"))
        assertFalse(body.contains("anchorDay"))
    }

    @Test
    fun `get parses the bare single-order body`() = runBlocking {
        server.enqueue(MockResponse().setBody(ORDER))

        val r = repo.get("o1")

        assertEquals("Netflix", (r as BtResult.Ok).value.label)
        assertEquals("/standing-orders/o1", server.takeRequest().path)
    }

    @Test
    fun `update PATCHes only the changed field`() = runBlocking {
        server.enqueue(MockResponse().setBody(ORDER))

        val r = repo.update("o1", amount = 25.0)

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/standing-orders/o1", req.path)
        assertEquals("""{"amount":25.0}""", req.body.readUtf8())
    }

    @Test
    fun `clearing the label sends an explicit null`() = runBlocking {
        server.enqueue(MockResponse().setBody(ORDER))

        repo.update("o1", clearLabel = true)

        assertEquals("""{"label":null}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `an update that changes nothing re-reads instead of sending an empty body`() = runBlocking {
        // {} fails the .strict() schema, so the repository must not send one.
        server.enqueue(MockResponse().setBody(ORDER))

        val r = repo.update("o1")

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/standing-orders/o1", req.path)
    }

    @Test
    fun `pause and resume both parse the bare returned order`() = runBlocking {
        server.enqueue(MockResponse().setBody(ORDER.replace(""""status":"active"""", """"status":"paused"""")))
        server.enqueue(MockResponse().setBody(ORDER))

        val paused = repo.pause("o1")
        assertEquals(StandingOrderStatuses.PAUSED, (paused as BtResult.Ok).value.status)
        assertEquals("/standing-orders/o1/pause", server.takeRequest().path)

        val resumed = repo.resume("o1")
        assertEquals(StandingOrderStatuses.ACTIVE, (resumed as BtResult.Ok).value.status)
        assertEquals("/standing-orders/o1/resume", server.takeRequest().path)
    }

    @Test
    fun `delete succeeds on a bodyless 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val r = repo.delete("o1")

        assertTrue("was $r", r is BtResult.Ok)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/standing-orders/o1", req.path)
    }

    @Test
    fun `a server-side end-before-start refusal surfaces its code and message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"STANDING_ORDER_END_BEFORE_START",
                   "message":"endDate must be on or after startDate."}}""",
            ),
        )

        val err = (repo.update("o1", endDate = "2020-01-01") as BtResult.Err).error
        assertEquals(400, err.httpStatus)
        assertEquals("STANDING_ORDER_END_BEFORE_START", err.code)
        assertEquals("endDate must be on or after startDate.", err.userMessage)
    }

    @Test
    fun `a dropped connection is a NETWORK error on a 204 endpoint too`() = runBlocking {
        server.shutdown()

        val err = (repo.delete("o1") as BtResult.Err).error
        assertEquals(0, err.httpStatus)
        assertTrue(err.isNetwork)
    }

    private companion object {
        /** A real bare single-order body — note there is NO {"order":…} wrapper. */
        const val ORDER = """
            {"id":"o1","portfolioId":"p1","kind":"cash-deduct","assetId":null,"assetSymbol":null,
             "assetName":null,"amount":20.0,"currency":"EUR","label":"Netflix","cadence":"monthly",
             "anchorDay":15,"startDate":"2026-08-01","endDate":null,"status":"active",
             "lastRunAt":null,"lastPeriodKey":null,"nextRunDate":"2026-09-15",
             "createdAt":"2026-08-01T00:00:00.000Z","updatedAt":"2026-08-01T00:00:00.000Z"}
        """
    }
}
