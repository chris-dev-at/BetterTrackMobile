package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
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
 * E9 is live on prod (c4f4ae2): `POST /api/v1/auth/fresh-start-notice/acknowledge`
 * accepts this app's bearer with `account:security` — no body, no CSRF header —
 * and answers the fresh `/auth/me`; without the scope it is `403
 * INSUFFICIENT_SCOPE`. The 200 must rebuild the session from the server's body,
 * and the 403 must come back as an error the sheet renders (never a silent
 * local dismissal, which would hide an unspent server flag).
 */
class FreshStartAckWireTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private object NoLocalData : LocalAccountData {
        override suspend fun onSessionEstablished(userId: String) = Unit
        override suspend fun wipeAll() = Unit
        override suspend fun wipeForLogout() = Unit
    }

    private fun me(pending: Boolean) = """
        {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
         "baseCurrency":"EUR","privacyMode":"normal",
         "lastLoginAt":"2026-08-29T11:17:59.871Z",
         "createdAt":"2026-07-02T20:56:10.002Z","paranoidFreshStartPending":$pending}
    """.trimIndent()

    private fun repo(): AuthRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        val store = FakeStore(initial = null)
        return AuthRepository(
            tokenManager = testManager(FakeTokenApi { rotatedResponse("a", "r") }, store),
            btApi = api,
            store = store,
            json = testJson,
            webOrigin = "https://web.example.test",
            clientId = TEST_CLIENT,
            scope = CoroutineScope(Dispatchers.Unconfined),
            localAccountData = NoLocalData,
        )
    }

    @Test
    fun `a 200 spends the notice and the session is rebuilt from the server's body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me(pending = false)))
        val repo = repo()

        val result = repo.acknowledgeFreshStartNotice()

        assertTrue("got $result", result is BtResult.Ok)
        assertEquals(false, (result as BtResult.Ok).value.paranoidFreshStartPending)
        assertEquals(AuthState.LoggedIn(result.value), repo.authState.value)
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request")
        assertEquals("POST", request.method)
        assertEquals("/auth/fresh-start-notice/acknowledge", request.path)
        assertEquals("no body", 0L, request.bodySize)
        assertEquals("no CSRF header on the bearer route", null, request.getHeader("X-CSRF-Token"))
    }

    @Test
    fun `a 403 INSUFFICIENT_SCOPE is surfaced, not silently dismissed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"INSUFFICIENT_SCOPE","message":"Missing scope account:security."}}"""),
        )
        val repo = repo()
        val before = repo.authState.value

        val result = repo.acknowledgeFreshStartNotice()

        assertTrue("got $result", result is BtResult.Err)
        val error = (result as BtResult.Err).error
        assertEquals(403, error.httpStatus)
        assertEquals("INSUFFICIENT_SCOPE", error.code)
        assertEquals("nothing local spent the notice", before, repo.authState.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the surface is reachable now that the route accepts the bearer`() {
        assertTrue(FreshStartNoticeFlags.enabled)
        assertTrue(freshStartNoticeDue(signedIn = true, pending = true, shownThisSession = false))
        assertFalse(freshStartNoticeDue(signedIn = true, pending = false, shownThisSession = false))
    }
}
