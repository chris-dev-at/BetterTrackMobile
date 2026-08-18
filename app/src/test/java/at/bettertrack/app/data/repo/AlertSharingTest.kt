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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `GET/PUT /alerts/sharing` — whether followers can see this account's alerts.
 *
 * The guard that matters: the acknowledgement must ride along on every call that
 * turns sharing ON, because the server re-checks it each time rather than only
 * on the off→on transition. A client that sent it once and then omitted it on a
 * later re-enable would get a `400` that looks like a bug in the toggle.
 *
 * The mirror guard matters just as much: turning sharing OFF must NOT send an
 * acknowledgement. Withdrawing access is not something a user should be made to
 * confirm, and a stray `acknowledgeFollowers: true` on a disabling call would
 * read, to anyone auditing the wire, as consent to the opposite of what happened.
 */
class AlertSharingTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: AlertsRepository

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
        repo = AlertsRepository(api, json)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `sharing reads the flag off its own route`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"visibleToFollowers":true}"""))

        val r = repo.sharing() as BtResult.Ok

        assertTrue(r.value)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/alerts/sharing"))
    }

    @Test
    fun `an account that never shared reads as off`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))

        assertFalse((repo.sharing() as BtResult.Ok).value)
    }

    @Test
    fun `enabling sends the acknowledgement`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"visibleToFollowers":true}"""))

        val r = repo.setSharing(true) as BtResult.Ok

        assertTrue(r.value)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue("path was ${req.path}", req.path!!.endsWith("/alerts/sharing"))
        val body = req.body.readUtf8()
        assertTrue("body was $body", body.contains("\"visibleToFollowers\":true"))
        assertTrue("body was $body", body.contains("\"acknowledgeFollowers\":true"))
    }

    @Test
    fun `disabling sends no acknowledgement at all`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"visibleToFollowers":false}"""))

        val r = repo.setSharing(false) as BtResult.Ok

        assertFalse(r.value)
        val body = server.takeRequest().body.readUtf8()
        assertTrue("body was $body", body.contains("\"visibleToFollowers\":false"))
        assertFalse("body was $body", body.contains("acknowledgeFollowers"))
    }

    @Test
    fun `the server's refusal to enable without an ack keeps its code`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"ALERT_SHARING_ACK_REQUIRED","message":"…must acknowledge…"}}""",
            ),
        )

        val err = (repo.setSharing(true) as BtResult.Err).error
        assertEquals("ALERT_SHARING_ACK_REQUIRED", err.code)
    }

    @Test
    fun `the response is trusted over the request when the two disagree`() = runBlocking {
        // Defensive: if the server ever answers "still off" to an enable, the
        // toggle must land where the SERVER says, not where we asked.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"visibleToFollowers":false}"""))

        assertFalse((repo.setSharing(true) as BtResult.Ok).value)
    }
}
