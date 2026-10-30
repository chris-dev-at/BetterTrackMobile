package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MB-02: what `/auth/me` may do to a session that is seconds old.
 *
 * `completeLogin` judged `/auth/me` with `isAuthHardFailure` (400 || 401) — the
 * REFRESH call's rule, where a 400 is the token endpoint's `INVALID_GRANT`
 * verdict. On an identity READ a 400 is body-parser / proxy / validation noise
 * that says nothing about the token, yet it wiped the session the exchange had
 * just minted: logged out seconds after a successful login. Only a 401 — the
 * fresh token itself refused — may end it; anything else proceeds with the
 * unknown-identity placeholder the transient branch already used (Settings
 * refetches `/auth/me` later).
 */
class LoginIdentityGateTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private class RecordingAccountData : LocalAccountData {
        val established = CopyOnWriteArrayList<String>()
        @Volatile var wipedAll = false
        override suspend fun onSessionEstablished(userId: String) {
            established += userId
        }
        override suspend fun wipeAll() {
            wipedAll = true
        }
        override suspend fun wipeForLogout() = Unit
    }

    private class Outcome(val repo: AuthRepository, val store: FakeStore, val data: RecordingAccountData)

    /** A completed token exchange followed by `/auth/me` answering [status] with [body]. */
    private fun loginWithMe(status: Int, body: String): Outcome = runBlocking {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        val store = FakeStore(initial = null)
        val tokens = testManager(FakeTokenApi { rotatedResponse("fresh-access", "fresh-refresh") }, store)
        val data = RecordingAccountData()
        val repo = AuthRepository(
            tokenManager = tokens,
            btApi = api,
            store = store,
            json = testJson,
            webOrigin = "https://web.example.test",
            clientId = TEST_CLIENT,
            scope = CoroutineScope(Dispatchers.Unconfined),
            localAccountData = data,
        )
        assertEquals("starts signed out", AuthState.LoggedOut, repo.authState.value)
        // The exchange the deep link performs, then the gate under test.
        assertTrue(tokens.exchange("code", "verifier") is at.bettertrack.app.data.api.BtResult.Ok)
        repo.completeLogin()
        assertEquals("/auth/me was asked exactly once", 1, server.requestCount)
        Outcome(repo, store, data)
    }

    private fun assertKeptWithUnknownIdentity(o: Outcome, what: String) {
        assertFalse("$what wiped the fresh session", o.store.wiped)
        assertNotNull("$what dropped the fresh tokens", o.store.tokens)
        assertEquals("fresh-access", o.store.tokens!!.accessToken)
        assertEquals(AuthState.LoggedIn(SessionUser.unknown()), o.repo.authState.value)
        assertEquals(LoginPhase.Idle, o.repo.loginPhase.value)
        // The same placeholder the transient branch always used: the owner gate
        // keeps local data for an unknown identity.
        assertEquals(listOf(SessionUser.unknown().id), o.data.established)
        assertFalse(o.data.wipedAll)
    }

    @Test
    fun `a 400 from auth me right after login keeps the session with an unknown identity`() {
        val o = loginWithMe(400, """{"error":{"code":"VALIDATION_ERROR","message":"Malformed request."}}""")
        assertKeptWithUnknownIdentity(o, "a 400")
    }

    @Test
    fun `a 503 from auth me keeps the session too`() {
        val o = loginWithMe(503, """{"error":{"code":"MAINTENANCE","message":"down"}}""")
        assertKeptWithUnknownIdentity(o, "a 503")
    }

    @Test
    fun `a 401 - the fresh token itself refused - still ends the session`() {
        // Negative space: narrowing the wipe must not remove it.
        val o = loginWithMe(401, """{"error":{"code":"INVALID_TOKEN","message":"no"}}""")
        assertTrue("a 401 left the session standing", o.store.wiped)
        assertNull(o.store.tokens)
        assertEquals(AuthState.LoggedOut, o.repo.authState.value)
        assertEquals(LoginPhase.Failed(LoginError.GENERIC), o.repo.loginPhase.value)
    }

    @Test
    fun `only a 401 rejects the identity, while the refresh rule keeps its 400`() {
        val e = { status: Int -> at.bettertrack.app.data.api.BtApiError(status, "X") }
        assertTrue(e(401).isIdentityRejected)
        for (status in listOf(0, 400, 403, 404, 429, 500, 502, 503)) {
            assertFalse("$status must not end a fresh session", e(status).isIdentityRejected)
        }
        // The refresh call's verdict is a different question and stays as it was:
        // its 400 IS the token endpoint saying the grant is dead.
        assertTrue(e(400).isAuthHardFailure)
        assertTrue(e(401).isAuthHardFailure)
    }
}
