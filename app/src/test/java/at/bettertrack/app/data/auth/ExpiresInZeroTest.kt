package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.AuthInterceptor
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.TokenAuthenticator
import at.bettertrack.app.data.api.dto.TokenResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PR-C: a late re-serve answers `expires_in: 0` — "refresh at once". The token
 * rail must refresh BEFORE the next authorized request goes out (one refresh,
 * then the request with the new bearer), not send a dead token and pay a 401
 * round trip. And if the server ever answered twice in a row with an
 * already-expired pair, the proactive gate must not rotate once per request.
 *
 * Driven through the real [AuthInterceptor] + [TokenAuthenticator] on an OkHttp
 * client against a server that 401s every bearer it did not just issue.
 */
class ExpiresInZeroTest {

    private lateinit var server: MockWebServer
    private val bearers = CopyOnWriteArrayList<String>()

    /** The bearers the API accepts; anything else is a 401, like an expired token. */
    @Volatile
    private var accepted: Set<String> = emptySet()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ").orEmpty()
                bearers += bearer
                return if (bearer in accepted) {
                    MockResponse().setResponseCode(200).setBody("[]")
                } else {
                    MockResponse().setResponseCode(401)
                        .setBody("""{"error":{"code":"API_KEY_INVALID","message":"expired"}}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun pair(access: String, refresh: String, expiresIn: Long): Response<TokenResponse> =
        Response.success(TokenResponse(access, "Bearer", expiresIn, refresh, "portfolio:read"))

    private fun client(tm: TokenManager) = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tm))
        .authenticator(TokenAuthenticator(tm))
        .build()

    private fun OkHttpClient.get(): Int =
        newCall(Request.Builder().url(server.url("/portfolios")).build()).execute().use { it.code }

    @Test
    fun `a code exchange with expires_in 0 refreshes before the first request`() = runBlocking {
        val api = FakeTokenApi { call ->
            when (call) {
                1 -> pair("A0", "R0", expiresIn = 0)
                2 -> pair("A1", "R1", expiresIn = 3600)
                else -> error("unexpected token call $call")
            }
        }
        val tm = testManager(api, FakeStore())
        accepted = setOf("A1")

        assertTrue(tm.exchange("code", "v".repeat(43)) is BtResult.Ok)
        assertEquals(200, client(tm).get())

        assertEquals("exchange + exactly one refresh", 2, api.calls.get())
        assertEquals(listOf("R0"), api.presented)
        assertEquals("the request went out once, with the fresh bearer — no 401 round trip", listOf("A1"), bearers)
    }

    @Test
    fun `a refresh answered with expires_in 0 refreshes again before the next request`() = runBlocking {
        val api = FakeTokenApi { call ->
            when (call) {
                1 -> pair("A1", "R1", expiresIn = 0) // the late re-serve
                2 -> pair("A2", "R2", expiresIn = 3600)
                else -> error("unexpected token call $call")
            }
        }
        val store = FakeStore(testTokens(access = "A0", refresh = "R0"))
        val tm = testManager(api, store)
        assertTrue(tm.retryRefreshNow())
        accepted = setOf("A2")

        assertEquals(200, client(tm).get())

        assertEquals(listOf("R0", "R1"), api.presented)
        assertEquals(listOf("A2"), bearers)
        assertEquals("A2", store.tokens?.accessToken)
    }

    @Test
    fun `a second expired-on-arrival pair in a row does not rotate once per request`() = runBlocking {
        val api = FakeTokenApi { call ->
            when (call) {
                1 -> pair("A0", "R0", expiresIn = 0)
                2 -> pair("A1", "R1", expiresIn = 0) // the server does it again
                3 -> pair("A2", "R2", expiresIn = 3600)
                else -> error("unexpected token call $call")
            }
        }
        val time = VirtualTime()
        val tm = testManager(api, FakeStore(), time)
        accepted = setOf("A1", "A2")
        val http = client(tm)

        assertTrue(tm.exchange("code", "v".repeat(43)) is BtResult.Ok)
        repeat(3) { assertEquals(200, http.get()) }

        assertEquals("one refresh for three requests, not three", 2, api.calls.get())
        assertEquals(listOf("A1", "A1", "A1"), bearers)

        // The hold is bounded: a minute later the gate refreshes ahead again.
        time.now += EXPIRING_ARRIVAL_HOLD_MS + 1
        assertEquals(200, http.get())
        assertEquals(3, api.calls.get())
        assertEquals(listOf("R0", "R1"), api.presented)
        assertEquals("A2", bearers.last())
    }

    @Test
    fun `the backstop rule`() {
        assertFalse("one expired arrival refreshes at once", expiringArrivalHolds(1, lastArrivalAtMs = 0, nowMs = 1))
        assertTrue(expiringArrivalHolds(2, lastArrivalAtMs = 0, nowMs = 1))
        assertFalse("bounded in time", expiringArrivalHolds(2, lastArrivalAtMs = 0, nowMs = EXPIRING_ARRIVAL_HOLD_MS))
        assertFalse(expiringArrivalHolds(0, lastArrivalAtMs = 0, nowMs = 1))
    }
}
