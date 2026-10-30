package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.db.LocalAccountData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Sign-out revokes THIS install's token family (PR-B #2085): first with the
 * stored refresh token over `POST /oauth/revoke` on the bare one-shot token
 * client, then — only when that failed without a verdict — with bearer
 * `POST /auth/logout`. Never the grant list or `DELETE /settings/oauth-grants`,
 * which is grant-wide since PR-B and would sign out every device.
 */
class LogoutRevocationTest {

    private lateinit var server: MockWebServer
    private lateinit var btApi: BtApi
    private lateinit var tokenApi: TokenApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        btApi = retrofit(okhttp3.OkHttpClient()).create(BtApi::class.java)
        // The production token client: one-shot bodies, no pool, no bearer.
        tokenApi = retrofit(tokenHttpClient()).create(TokenApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun retrofit(client: okhttp3.OkHttpClient) = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()

    private fun status(code: Int, body: String = "") = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val ok = """{"ok":true}"""

    private val held = testTokens(access = "bto_access", refresh = "btr_refresh")

    private suspend fun revoke(tokens: AuthTokens? = held, stepTimeoutMs: Long = 2_000L) = revokeInstallOnSignOut(
        held = tokens,
        revoke = { token, hint ->
            tokenApi.revoke(at.bettertrack.app.data.api.dto.TokenRevokeRequest(token, hint, TEST_CLIENT))
        },
        bearerLogout = { btApi.logout() },
        stepTimeoutMs = stepTimeoutMs,
    )

    private fun recorded(): List<RecordedRequest> =
        (0 until server.requestCount).map { server.takeRequest(1, TimeUnit.SECONDS)!! }

    private fun List<RecordedRequest>.lines() = map { "${it.method} ${it.path}" }

    @Test
    fun `a revoke with the stored refresh token is the whole sign-out`() = runBlocking {
        server.enqueue(status(200, ok).setHeader("Cache-Control", "no-store"))

        assertEquals(LogoutRevocation.REVOKED, revoke())
        val requests = recorded()
        assertEquals(listOf("POST /oauth/revoke"), requests.lines())
        val body = Json.parseToJsonElement(requests[0].body.readUtf8()).jsonObject
        // The strict schema: exactly these keys, no secret for a public client.
        assertEquals(setOf("token", "token_type_hint", "client_id"), body.keys)
        assertEquals("btr_refresh", body["token"]!!.jsonPrimitive.content)
        assertEquals("refresh_token", body["token_type_hint"]!!.jsonPrimitive.content)
        assertEquals(TEST_CLIENT, body["client_id"]!!.jsonPrimitive.content)
        assertNull("unauthenticated endpoint: no bearer", requests[0].getHeader("Authorization"))
    }

    @Test
    fun `an expired access token does not matter - the refresh token names the family`() = runBlocking {
        server.enqueue(status(200, ok))
        val expired = testTokens(access = "bto_dead", refresh = "btr_live", expiresAt = 0L)

        assertEquals(LogoutRevocation.REVOKED, revoke(expired))
        assertEquals(listOf("POST /oauth/revoke"), recorded().lines())
    }

    @Test
    fun `a 5xx falls back to the bearer logout, never to the grant list`() = runBlocking {
        server.enqueue(status(503, """{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"x"}}"""))
        server.enqueue(status(200, ok))

        assertEquals(LogoutRevocation.LOGGED_OUT, revoke())
        assertEquals(listOf("POST /oauth/revoke", "POST /auth/logout"), recorded().lines())
    }

    /** A 400 is the endpoint's verdict on the request (INVALID_CLIENT): no fallback. */
    @Test
    fun `a 400 is a verdict, not a network failure`() = runBlocking {
        server.enqueue(status(400, """{"error":{"code":"INVALID_CLIENT","message":"x"}}"""))

        assertEquals(LogoutRevocation.REFUSED, revoke())
        assertEquals(listOf("POST /oauth/revoke"), recorded().lines())
    }

    /**
     * The revoke rides the one-shot token client: a connection dropped after
     * the server read the body is NOT silently re-sent by OkHttp — it surfaces
     * as a transport failure, and the sign-out takes the bearer path instead.
     */
    @Test
    fun `a dropped revoke is sent once, then the bearer path answers 401 - already gone`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(status(401, """{"error":{"code":"API_KEY_INVALID","message":"x"}}"""))

        assertEquals(LogoutRevocation.ALREADY_GONE, revoke())
        assertEquals(listOf("POST /oauth/revoke", "POST /auth/logout"), recorded().lines())
    }

    @Test
    fun `each server call is bounded, so a hung network cannot hold the sign-out`() = runBlocking {
        server.enqueue(status(200, ok).setHeadersDelay(5, TimeUnit.SECONDS))
        server.enqueue(status(200, ok).setHeadersDelay(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals(LogoutRevocation.FAILED, revoke(stepTimeoutMs = 300L))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("two bounded steps, not two 5 s waits: $tookMs ms", tookMs < 2_500)
    }

    @Test
    fun `nothing on the phone means nothing to send`() = runBlocking {
        assertEquals(LogoutRevocation.NOTHING_HELD, revoke(tokens = null))
        assertEquals(0, server.requestCount)
    }

    // ── through AuthRepository.logout ─────────────────────────────────────────

    private object NoLocalData : LocalAccountData {
        override suspend fun onSessionEstablished(userId: String) = Unit
        override suspend fun wipeAll() = Unit
        override suspend fun wipeForLogout() = Unit
    }

    private fun repo(store: FakeStore, tokens: FakeTokenApi) = AuthRepository(
        tokenManager = testManager(tokens, store),
        btApi = btApi,
        store = store,
        json = testJson,
        webOrigin = "https://web.example.test",
        clientId = TEST_CLIENT,
        scope = CoroutineScope(Dispatchers.Unconfined),
        localAccountData = NoLocalData,
    )

    /**
     * A refresh of the token on disk is pending (its answer was lost, the
     * server may hold a successor the phone never saw). The token presented is
     * still the one on disk: the server resolves a revocable token in any state
     * — consumed included — and revokes its family, successor and all.
     */
    @Test
    fun `logout presents the on-disk refresh token even while a replay of it is pending`() = runBlocking {
        val store = FakeStore(testTokens(access = "bto_A1", refresh = "btr_R1"))
        store.pendingRefresh = PendingRefresh(
            tokenFingerprint = refreshTokenFingerprint("btr_R1"),
            firstSentAtMs = 1L,
            lastSentAtMs = 1L,
            attempts = 1,
        )
        val tokens = FakeTokenApi { error("no refresh during a sign-out") }

        repo(store, tokens).logout()

        assertEquals(listOf("btr_R1"), tokens.revoked.map { it.token })
        assertEquals("refresh_token", tokens.revoked.single().tokenTypeHint)
        assertEquals(TEST_CLIENT, tokens.revoked.single().clientId)
        assertEquals("no bearer call, no grant list, no DELETE", 0, server.requestCount)
        assertTrue("the local wipe always runs", store.wiped)
        assertNull(store.tokens)
    }

    @Test
    fun `the local wipe runs whatever both paths say`() = runBlocking {
        val store = FakeStore(testTokens(access = "bto_A1", refresh = "btr_R1"))
        val tokens = FakeTokenApi { error("no refresh during a sign-out") }
        tokens.onRevoke = { throw java.io.IOException("offline") }
        server.enqueue(status(503, """{"error":{"code":"UPSTREAM_UNAVAILABLE","message":"x"}}"""))

        repo(store, tokens).logout()

        assertEquals(1, tokens.revoked.size)
        val lines = recorded().lines()
        assertEquals(listOf("POST /auth/logout"), lines)
        assertFalse(lines.any { "oauth-grants" in it })
        assertTrue(store.wiped)
    }

    // ── login copy for the token endpoint's request-level refusals ───────────

    @Test
    fun `a code exchange refused for scope or client gets its own sentence`() {
        assertEquals(LoginError.SCOPE_REFUSED, loginErrorForExchange(BtApiError(400, "INVALID_SCOPE")))
        assertEquals(LoginError.CLIENT_REJECTED, loginErrorForExchange(BtApiError(400, "INVALID_CLIENT")))
        assertEquals(LoginError.EXCHANGE_FAILED, loginErrorForExchange(BtApiError(400, "INVALID_GRANT")))
        assertEquals(LoginError.NETWORK, loginErrorForExchange(BtApiError(0, "NETWORK_ERROR")))
    }
}
