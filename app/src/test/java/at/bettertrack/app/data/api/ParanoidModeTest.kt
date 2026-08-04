package at.bettertrack.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5 S2a(a) — the global PARANOID_MODE watcher.
 *
 * Two things must hold: the classifier only fires on the real envelope (an
 * unrelated 403 must never black out the portfolio surfaces), and the
 * interceptor stays observe-only — the response body is still fully readable by
 * the normal per-call error handling downstream.
 */
class ParanoidModeTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    // ── Pure classifier ─────────────────────────────────────────────────────

    @Test
    fun `the paranoid envelope is recognised`() {
        assertTrue(
            isParanoidModeBody(
                json,
                """{"error":{"code":"PARANOID_MODE","message":"This account is in paranoid mode."}}""",
            ),
        )
    }

    @Test
    fun `another 403 code is not paranoid mode`() {
        assertFalse(
            isParanoidModeBody(
                json,
                """{"error":{"code":"INSUFFICIENT_SCOPE","message":"missing scope"}}""",
            ),
        )
        assertFalse(
            isParanoidModeBody(
                json,
                """{"error":{"code":"API_KEY_FORBIDDEN","message":"nope"}}""",
            ),
        )
    }

    @Test
    fun `a non-envelope or empty body is never paranoid mode`() {
        assertFalse(isParanoidModeBody(json, null))
        assertFalse(isParanoidModeBody(json, ""))
        assertFalse(isParanoidModeBody(json, "   "))
        assertFalse(isParanoidModeBody(json, "<html>gateway error</html>"))
        assertFalse(isParanoidModeBody(json, """{"PARANOID_MODE":true}"""))
        // The string appearing somewhere else must not be enough.
        assertFalse(isParanoidModeBody(json, """{"error":{"code":"X","message":"PARANOID_MODE"}}"""))
    }

    // ── Interceptor behaviour ───────────────────────────────────────────────

    private fun clientCounting(detected: MutableList<Unit>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ParanoidModeInterceptor(json) { detected.add(Unit) })
            .build()

    @Test
    fun `a paranoid 403 flips the state exactly once per response`() {
        val hits = mutableListOf<Unit>()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"PARANOID_MODE","message":"nope"}}"""),
        )
        clientCounting(hits).newCall(Request.Builder().url(server.url("/api/v1/portfolios")).build())
            .execute().close()
        assertEquals(1, hits.size)
    }

    @Test
    fun `the body is left intact for the normal error mapping downstream`() {
        val hits = mutableListOf<Unit>()
        val body = """{"error":{"code":"PARANOID_MODE","message":"This account is in paranoid mode."}}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
        val resp = clientCounting(hits)
            .newCall(Request.Builder().url(server.url("/api/v1/portfolios")).build())
            .execute()
        // peekBody must NOT have consumed the stream.
        assertEquals(body, resp.body!!.string())
        resp.close()
        assertEquals(1, hits.size)
    }

    @Test
    fun `an unrelated 403 does not flip the state`() {
        val hits = mutableListOf<Unit>()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":"INSUFFICIENT_SCOPE","message":"missing scope"}}"""),
        )
        clientCounting(hits).newCall(Request.Builder().url(server.url("/api/v1/alerts")).build())
            .execute().close()
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a success response is never inspected`() {
        val hits = mutableListOf<Unit>()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"ok":true}"""),
        )
        clientCounting(hits).newCall(Request.Builder().url(server.url("/api/v1/portfolios")).build())
            .execute().close()
        assertTrue(hits.isEmpty())
    }

    // ── App-level state ─────────────────────────────────────────────────────

    @Test
    fun `state is sticky until explicitly cleared on account wipe`() {
        ParanoidModeState.clear()
        assertFalse(ParanoidModeState.active.value)
        ParanoidModeState.markActive()
        assertTrue(ParanoidModeState.active.value)
        ParanoidModeState.markActive() // idempotent
        assertTrue(ParanoidModeState.active.value)
        ParanoidModeState.clear()
        assertFalse(ParanoidModeState.active.value)
    }

    // ── Error mapping ───────────────────────────────────────────────────────

    @Test
    fun `parseApiError exposes the paranoid code`() {
        val err = parseApiError(
            json,
            403,
            """{"error":{"code":"PARANOID_MODE","message":"nope"}}"""
                .toResponseBody(),
        )
        assertTrue(err.isParanoidMode)
        assertTrue(err.isForbidden)
    }

    private fun String.toResponseBody() =
        okhttp3.ResponseBody.create("application/json".toMediaType(), this)
}
