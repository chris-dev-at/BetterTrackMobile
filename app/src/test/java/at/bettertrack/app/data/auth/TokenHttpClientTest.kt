package at.bettertrack.app.data.auth

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp must never re-send a refresh on its own.
 *
 * The hazard, proven on a plain client first (so the fix is not asserted against
 * a scenario that could never fail): a refresh written into a REUSED pooled
 * connection, read by the server, answered with a dropped connection — OkHttp's
 * `RetryAndFollowUpInterceptor` silently sends the same body again on a fresh
 * connection. Against the platform that second POST presents a token the first
 * one just consumed, and the app only ever sees its `400 INVALID_GRANT`.
 */
class TokenHttpClientTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun refreshPost() = Request.Builder()
        .url(server.url("/api/v1/oauth/token"))
        .post("""{"grant_type":"refresh_token","refresh_token":"R1"}""".toRequestBody("application/json".toMediaType()))
        .build()

    private fun warmUp(client: OkHttpClient) {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/warm")).build()).execute().use { it.body?.string() }
    }

    private fun dropThenRefuse() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"INVALID_GRANT"}}"""))
    }

    @Test
    fun `the hazard - a plain pooled client re-sends a refresh the server already read`() {
        val plain = OkHttpClient()
        warmUp(plain)
        dropThenRefuse()

        val code = plain.newCall(refreshPost()).execute().use { it.code }

        assertEquals("warm-up + the refresh + OkHttp's silent replay", 3, server.requestCount)
        assertEquals("and the caller only sees the replay's refusal", 400, code)
    }

    @Test
    fun `the production token client sends a refresh exactly once and reports the lost answer`() {
        val client = tokenHttpClient()
        warmUp(client)
        dropThenRefuse()

        val failed = runCatching { client.newCall(refreshPost()).execute().close() }.exceptionOrNull()

        assertTrue("an after-send failure reaches TokenManager: $failed", failed is IOException)
        assertTrue("and is classified as possibly delivered", requestMayHaveBeenDelivered(failed!!))
        assertEquals("warm-up + one refresh, nothing more", 2, server.requestCount)
    }

    /** The one-shot body alone closes the hole, even with connection pooling left on. */
    @Test
    fun `one-shot bodies alone stop the re-send on a pooled connection`() {
        val pooled = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .addInterceptor(OneShotRequestBodies)
            .build()
        warmUp(pooled)
        dropThenRefuse()

        val failed = runCatching { pooled.newCall(refreshPost()).execute().close() }.exceptionOrNull()

        assertTrue(failed is IOException)
        assertEquals(2, server.requestCount)
    }

    /** OkHttp's own `503 Retry-After: 0` follow-up would re-send the body too. */
    @Test
    fun `a 503 asking for an immediate retry is not re-sent by the token client`() {
        val plain = OkHttpClient()
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{}"))
        plain.newCall(refreshPost()).execute().close()
        assertEquals("the hazard: a plain client follows it up", 2, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        val code = tokenHttpClient().newCall(refreshPost()).execute().use { it.code }
        assertEquals(503, code)
        assertEquals("one more request, not two", 3, server.requestCount)
    }

    @Test
    fun `a lost answer is noticed inside the server's grace`() {
        val client = tokenHttpClient()
        assertEquals(TOKEN_CALL_TIMEOUT_SECONDS * 1000, client.callTimeoutMillis.toLong())
        // Noticed by the call timeout, replayed after at most 3 s, and the replay
        // itself bounded the same way: all inside the 60 s rotation grace.
        assertTrue(2 * TOKEN_CALL_TIMEOUT_SECONDS * 1000 + 3_000 < SERVER_ROTATION_GRACE_MS)
    }
}
