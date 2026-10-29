package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException

/** The debug-only fault injector does exactly what its label says, once, and only to refreshes. */
class AuthFaultInterceptorTest {

    private lateinit var server: MockWebServer
    private var armed = AuthFault.NONE
    private var kills = 0
    private var requestsAtKill = -1

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun api(): TokenApi = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .client(
            tokenHttpClient(
                outer = listOf(
                    AuthFaultInterceptor(
                        armed = { armed },
                        disarm = { armed = AuthFault.NONE },
                        killProcess = {
                            kills++
                            requestsAtKill = server.requestCount
                        },
                    ),
                ),
            ),
        )
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TokenApi::class.java)

    private fun pair() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"access_token":"A2","token_type":"Bearer","expires_in":3600,"refresh_token":"R2","scope":"x"}""")

    private val refresh = TokenRefreshRequest(refreshToken = "R1", clientId = TEST_CLIENT)

    @Test
    fun `lost answer - the server receives it, the app never hears back, and it fires once`() = runBlocking {
        armed = AuthFault.LOST_ANSWER
        server.enqueue(pair())
        server.enqueue(pair())
        val api = api()

        val failed = runCatching { api.refresh(refresh) }.exceptionOrNull()
        assertTrue(failed is SocketTimeoutException)
        assertEquals("the server DID get it (and would rotate)", 1, server.requestCount)
        assertEquals(AuthFault.NONE, armed)
        assertEquals("the next refresh is untouched", 200, api.refresh(refresh).code())
    }

    @Test
    fun `rate limit - a synthetic 429 with Retry-After, nothing sent`() = runBlocking {
        armed = AuthFault.RATE_LIMITED
        val resp = api().refresh(refresh)
        assertEquals(429, resp.code())
        assertEquals("20", resp.headers()["Retry-After"])
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `transport - a connect failure, nothing sent`() = runBlocking {
        armed = AuthFault.TRANSPORT
        val failed = runCatching { api().refresh(refresh) }.exceptionOrNull()
        assertTrue(failed is ConnectException)
        assertTrue("classified as never delivered", !requestMayHaveBeenDelivered(failed!!))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `kill after send - disarmed first, killed after the server got it`() = runBlocking {
        armed = AuthFault.KILL_AFTER_SEND
        server.enqueue(pair())
        runCatching { api().refresh(refresh) }
        assertEquals(1, kills)
        assertEquals(1, requestsAtKill)
        assertEquals("a relaunched process must not die again", AuthFault.NONE, armed)
    }

    @Test
    fun `the code exchange is never faulted`() = runBlocking {
        armed = AuthFault.TRANSPORT
        server.enqueue(pair())
        val resp = api().exchange(TokenExchangeRequest(code = "c", redirectUri = TEST_REDIRECT, clientId = TEST_CLIENT, codeVerifier = "v"))
        assertEquals(200, resp.code())
        assertEquals("still armed for the next refresh", AuthFault.TRANSPORT, armed)
    }

    @Test
    fun `an unknown stored name reads as no fault`() {
        assertEquals(AuthFault.NONE, authFaultFromName(null))
        assertEquals(AuthFault.NONE, authFaultFromName("SOMETHING_ELSE"))
        assertEquals(AuthFault.LOST_ANSWER, authFaultFromName("LOST_ANSWER"))
    }
}
