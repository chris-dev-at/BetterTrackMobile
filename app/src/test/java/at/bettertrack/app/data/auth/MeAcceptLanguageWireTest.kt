package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.LocalAccountData
import at.bettertrack.app.data.i18n.AppLanguage
import at.bettertrack.app.data.i18n.acceptLanguageFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale

/**
 * Platform ruling (lands with their PR #2094): `GET /auth/me` localises
 * `pendingAcknowledgements[].title` from the request's `Accept-Language`. The
 * app sends the in-app language, else the device language, on BOTH of its
 * `/auth/me` reads (the post-login identity read and the refresh).
 */
class MeAcceptLanguageWireTest {

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

    private val me = """
        {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
         "baseCurrency":"EUR","privacyMode":"normal",
         "lastLoginAt":"2026-08-29T11:17:59.871Z","createdAt":"2026-07-02T20:56:10.002Z"}
    """.trimIndent()

    private fun repo(language: String?): Pair<AuthRepository, TokenManager> {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        val store = FakeStore(initial = null)
        val tokens = testManager(FakeTokenApi { rotatedResponse("fresh-access", "fresh-refresh") }, store)
        return AuthRepository(
            tokenManager = tokens,
            btApi = api,
            store = store,
            json = testJson,
            webOrigin = "https://web.example.test",
            clientId = TEST_CLIENT,
            scope = CoroutineScope(Dispatchers.Unconfined),
            localAccountData = NoLocalData,
            acceptLanguage = { language },
        ) to tokens
    }

    @Test
    fun `the refresh read of auth me carries the language`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me))
        val (repo, tokens) = repo("de")
        assertTrue(tokens.exchange("code", "verifier") is BtResult.Ok) // refresh needs a session
        assertTrue(repo.refreshUserNow() is BtResult.Ok)
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no /auth/me request")
        assertEquals("/auth/me", request.path)
        assertEquals("de", request.getHeader("Accept-Language"))
    }

    @Test
    fun `the post-login identity read carries it too`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me))
        val (repo, tokens) = repo("de-AT")
        assertTrue(tokens.exchange("code", "verifier") is BtResult.Ok)
        repo.completeLogin()
        assertEquals("de-AT", (server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request")).getHeader("Accept-Language"))
    }

    @Test
    fun `no language means no header rather than an empty one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(me))
        val (repo, tokens) = repo(null)
        assertTrue(tokens.exchange("code", "verifier") is BtResult.Ok)
        assertTrue(repo.refreshUserNow() is BtResult.Ok)
        assertNull((server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no request")).getHeader("Accept-Language"))
    }

    @Test
    fun `in-app language wins, else the device language, else nothing`() {
        assertEquals("de", acceptLanguageFor(AppLanguage.German, Locale.US))
        assertEquals("en", acceptLanguageFor(AppLanguage.English, Locale.GERMANY))
        assertEquals("de-AT", acceptLanguageFor(AppLanguage.System, Locale.forLanguageTag("de-AT")))
        assertNull(acceptLanguageFor(AppLanguage.System, Locale.ROOT))
    }
}
