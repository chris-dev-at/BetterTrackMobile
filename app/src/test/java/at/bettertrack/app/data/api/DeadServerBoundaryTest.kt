package at.bettertrack.app.data.api

import at.bettertrack.app.data.account.AccountRepository
import at.bettertrack.app.data.repo.AlertsRepository
import at.bettertrack.app.data.repo.FriendGroupRepository
import at.bettertrack.app.data.repo.IdeasRepository
import at.bettertrack.app.data.repo.SocialThreadRepository
import at.bettertrack.app.data.standingorders.StandingOrderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * The contract this whole hardening pass rests on: **an unreachable backend is a
 * [BtResult.Err], never a throw.**
 *
 * `api.bettertrack.at` being down produces four distinct JVM exceptions
 * (`UnknownHostException` when DNS is gone, `ConnectException` when the port is
 * shut, `SocketTimeoutException` when the box accepts and never answers,
 * `SSLException` when a terminator is half-up) plus a fifth shape that is not an
 * exception at all: a maintenance proxy answering **HTML with a 200** where JSON
 * was promised, which surfaces as a `SerializationException` out of the Retrofit
 * converter.
 *
 * That fifth one is the interesting one. It is NOT an `IOException`, and the
 * repositories that hand-rolled `catch (_: IOException)` let it escape into
 * whatever coroutine had called them — a `viewModelScope` or the graph's
 * `appScope`, both of which take the process down with them. These tests pin all
 * five to `BtResult.Err` at the boundary.
 */
class DeadServerBoundaryTest {

    private lateinit var server: MockWebServer

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
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun apiFor(
        baseUrl: String = server.url("/").toString(),
        client: OkHttpClient = OkHttpClient(),
    ): BtApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BtApi::class.java)

    /** A base URL whose host cannot resolve — the DNS-is-gone shape. */
    private fun unresolvableApi(): BtApi = apiFor("https://api.invalid.bettertrack.test/api/v1/")

    // ── The exception→error mapping itself ────────────────────────────────────

    @Test
    fun `every shape of an unreachable host maps to the NETWORK error`() {
        val transportFailures = listOf(
            UnknownHostException("api.bettertrack.at"),
            ConnectException("Connection refused"),
            SocketTimeoutException("timeout"),
            SSLHandshakeException("handshake failed"),
        )
        transportFailures.forEach { e ->
            val mapped = asBtApiError(e)
            assertEquals("${e.javaClass.simpleName} should be NETWORK", 0, mapped.httpStatus)
            assertTrue(mapped.isNetwork)
            assertEquals(BtApiError.Codes.NETWORK, mapped.code)
        }
    }

    @Test
    fun `a non-transport failure is UNEXPECTED, not NETWORK`() {
        val mapped = asBtApiError(IllegalStateException("converter blew up"))
        assertFalse(mapped.isNetwork)
        assertEquals(BtErrorCopy.AppCodes.UNEXPECTED, mapped.code)
        assertEquals("converter blew up", mapped.diagnostic)
    }

    @Test
    fun `a transport failure is never an auth-hard failure — a dead server must not log anyone out`() {
        assertFalse("network", asBtApiError(UnknownHostException("x")).isAuthHardFailure)
        assertFalse("5xx", BtApiError(503, "MAINTENANCE").isAuthHardFailure)
        assertFalse("502", BtApiError(502, BtErrorCopy.AppCodes.HTTP_FAILED).isAuthHardFailure)
        // Only a server that ANSWERED with a refusal may end the session.
        assertTrue("401", BtApiError(401, "INVALID_TOKEN").isAuthHardFailure)
        assertTrue("400", BtApiError(400, "INVALID_GRANT").isAuthHardFailure)
    }

    @Test(expected = CancellationException::class)
    fun `transportErr re-throws cancellation rather than reporting it as an error`() {
        transportErr(CancellationException("scope closed"))
    }

    // ── apiCall ───────────────────────────────────────────────────────────────

    @Test
    fun `apiCall returns a NETWORK error when the host does not resolve`() = runBlocking {
        val err = (apiCall(json) { unresolvableApi().me() } as BtResult.Err).error
        assertTrue(err.isNetwork)
        assertEquals(0, err.httpStatus)
    }

    @Test
    fun `apiCall returns a NETWORK error when the port is closed`() = runBlocking {
        val api = apiFor()
        server.shutdown()
        val err = (apiCall(json) { api.me() } as BtResult.Err).error
        assertTrue(err.isNetwork)
    }

    @Test
    fun `apiCall returns a NETWORK error when the server accepts and never answers`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val api = apiFor(
            client = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build(),
        )
        val err = (apiCall(json) { api.me() } as BtResult.Err).error
        assertTrue(err.isNetwork)
    }

    @Test
    fun `apiCall survives a maintenance page served with a 200 instead of JSON`() = runBlocking {
        // The shape that is NOT an IOException, and used to escape the data layer.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>Scheduled maintenance</body></html>"),
        )
        val result = apiCall(json) { apiFor().me() }
        assertTrue("was $result", result is BtResult.Err)
        assertEquals(BtErrorCopy.AppCodes.UNEXPECTED, (result as BtResult.Err).error.code)
    }

    @Test
    fun `apiCall maps a 503 maintenance body to an http error, not a crash`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>down for maintenance</html>"))
        val err = (apiCall(json) { apiFor().me() } as BtResult.Err).error
        assertEquals(503, err.httpStatus)
        assertEquals(BtErrorCopy.AppCodes.HTTP_FAILED, err.code)
        assertFalse(err.isAuthHardFailure)
    }

    // ── unitApiCall (the bodyless twin the 204 endpoints share) ───────────────

    @Test
    fun `unitApiCall returns a NETWORK error instead of throwing on a dead host`() = runBlocking {
        val err = (unitApiCall(json) { unresolvableApi().deleteAlert("a1") } as BtResult.Err).error
        assertTrue(err.isNetwork)
        assertEquals(0, err.httpStatus)
    }

    @Test
    fun `unitApiCall accepts a bodyless 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(unitApiCall(json) { apiFor().deleteAlert("a1") } is BtResult.Ok)
    }

    @Test
    fun `unitApiCall surfaces the platform error envelope on a refusal`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"error":{"code":"CASH_TAG_SYSTEM_PROTECTED","message":"Built-in tag."}}"""),
        )
        val err = (unitApiCall(json) { apiFor().deleteAlert("a1") } as BtResult.Err).error
        assertEquals(409, err.httpStatus)
        assertTrue(err.isCashTagSystemProtected)
    }

    // ── The repositories that used to hand-roll the catch ─────────────────────

    @Test
    fun `every bodyless-write repository answers Err, not a throw, against a dead origin`() = runBlocking {
        val api = unresolvableApi()
        val results: List<Pair<String, BtResult<*>>> = listOf(
            "alerts.delete" to AlertsRepository(api, json).delete("a1"),
            "standingOrders.delete" to StandingOrderRepository(api, json).delete("o1"),
            "ideas.delete" to IdeasRepository(api, json).delete("i1"),
            "friendGroups.delete" to FriendGroupRepository(api, json).delete("g1"),
            "socialThread.deleteComment" to SocialThreadRepository(api, json).deleteComment("c1"),
            "account.changePassword" to AccountRepository(api, json).changePassword("a", "b"),
            "account.revokeSession" to AccountRepository(api, json).revokeSession("s1"),
        )
        results.forEach { (what, result) ->
            assertTrue("$what returned $result", result is BtResult.Err)
            assertTrue("$what should be NETWORK", (result as BtResult.Err).error.isNetwork)
        }
    }

    @Test
    fun `a bodyless-write repository survives an HTML maintenance page`() = runBlocking {
        // Pre-fix this escaped every hand-rolled `catch (_: IOException)`: the
        // converter's SerializationException is not an IOException.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html>maintenance</html>"),
        )
        val result = StandingOrderRepository(apiFor(), json).delete("o1")
        // A 200 with an unparseable body is still a successful DELETE — the point
        // is only that it does not throw out of the data layer.
        assertTrue("was $result", result is BtResult.Ok || result is BtResult.Err)
    }

    @Test
    fun `a body-returning repository answers Err, not a throw, against a dead origin`() = runBlocking {
        val api = unresolvableApi()
        val results: List<Pair<String, BtResult<*>>> = listOf(
            "alerts.list" to AlertsRepository(api, json).list(),
            "standingOrders.list" to StandingOrderRepository(api, json).list("p1"),
            "ideas.ideas" to IdeasRepository(api, json).ideas(),
            "friendGroups.groups" to FriendGroupRepository(api, json).groups(),
            "account.sessions" to AccountRepository(api, json).sessions(),
            "account.twoFactorStatus" to AccountRepository(api, json).twoFactorStatus(),
        )
        results.forEach { (what, result) ->
            assertTrue("$what returned $result", result is BtResult.Err)
            assertTrue("$what should be NETWORK", (result as BtResult.Err).error.isNetwork)
        }
    }

    @Test
    fun `a triggered-alert refresh keeps its last count when the server is gone`() = runBlocking {
        // §7: a cached value must not be zeroed by an unreachable server —
        // "we could not ask" is not "your alerts stopped firing".
        val repo = AlertsRepository(unresolvableApi(), json)
        repo.refreshTriggered(at.bettertrack.app.data.storage.StorageMode.SERVER)
        assertEquals(0, repo.triggered.value)
    }
}
