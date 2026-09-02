package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.TokenApi
import at.bettertrack.app.data.api.dto.OAuthGrant
import at.bettertrack.app.data.api.dto.TokenExchangeRequest
import at.bettertrack.app.data.api.dto.TokenRefreshRequest
import at.bettertrack.app.data.api.dto.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The forced-sign-out rail, pinned.
 *
 * Written for one reported fault: the owner was being returned to the login
 * screen repeatedly, days apart, with no explanation available anywhere. The
 * chain that produced it is asserted here end to end, along with the four rules
 * that now hold whatever the network does.
 *
 * ## The chain, as proven against the platform source
 *
 * `POST /oauth/token` rotates: the presented refresh token is stamped
 * `consumed_at` inside the same transaction that issues the successor
 * (`oauthRepository.ts:646-700`), with **no grace window anywhere in the
 * codebase**. Presenting a consumed token is treated as a compromise and
 * **revokes the whole grant** (`oauthService.ts:919-930`, RFC 6819 §5.2.2.3) —
 * and because the platform keeps at most one grant per (client, user)
 * (`oauthService.ts:869-884`), that is every install the account has.
 *
 * So a refresh whose ANSWER is lost in flight leaves the phone holding a token
 * the server has already spent. The old client then re-presented it within
 * milliseconds: the refresh mutex released, the next queued 401 saw an unchanged
 * access token, and fired the same token straight back. One flaky moment, one
 * dead grant, one login screen — with `logcat` long since rotated by the time
 * anyone looked.
 */
class ForcedSignOutTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── the pure rules ────────────────────────────────────────────────────────

    /**
     * `_source`: platform repo @ origin/main, `apps/api/src/services/oauth/oauthService.ts`
     * — every "your refresh token is dead" answer is `400 INVALID_GRANT`
     * (:912-915, :916-918, :919-930, :931-933, :946-954). Nothing else on that
     * endpoint is a statement about the session.
     */
    @Test
    fun `only 400 INVALID_GRANT may end a session`() {
        assertTrue(refreshRefusalIsDefinitive(400, "INVALID_GRANT"))
        assertTrue("case is not part of the contract", refreshRefusalIsDefinitive(400, "invalid_grant"))

        // The request was wrong, not the session. The body schema is `.strict()`,
        // so one stray field or a form-encoded body lands here.
        assertFalse("validation", refreshRefusalIsDefinitive(400, "VALIDATION_ERROR"))
        // A wrong/rotated client_id is a build problem.
        assertFalse("client", refreshRefusalIsDefinitive(400, "INVALID_CLIENT"))
        // The token endpoint is unauthenticated: a 401 can only come from the
        // bearer middleware above /api/v1 rejecting a header that should never
        // have been attached (bearerAuth.ts:371-413, API_KEY_INVALID).
        assertFalse("bearer leaked onto the refresh", refreshRefusalIsDefinitive(401, "API_KEY_INVALID"))
        // The general limiter is keyed by IP: 60 requests / 10 s, shared with
        // everyone behind the same NAT.
        assertFalse("rate limit", refreshRefusalIsDefinitive(429, "RATE_LIMITED"))
        assertFalse("origin down", refreshRefusalIsDefinitive(520, "HTTP_FAILED"))
        assertFalse("server error", refreshRefusalIsDefinitive(500, "INTERNAL"))
        assertFalse("transport", refreshRefusalIsDefinitive(0, "NETWORK_ERROR"))
        assertFalse("unparseable body", refreshRefusalIsDefinitive(400, "HTTP_FAILED"))
    }

    @Test
    fun `a lost answer means the request may have been delivered`() {
        assertFalse("DNS never resolved", requestMayHaveBeenDelivered(UnknownHostException()))
        assertFalse("port refused", requestMayHaveBeenDelivered(ConnectException()))
        assertTrue("sent, then silence", requestMayHaveBeenDelivered(SocketTimeoutException()))
    }

    @Test
    fun `an at-risk token is offered again only after the cool-off`() {
        val cool = 600_000L
        assertTrue(atRiskReplaySuppressed("R1", "R1", lastAttemptMs = 1_000L, nowMs = 1_001L, coolOffMs = cool))
        assertTrue(atRiskReplaySuppressed("R1", "R1", 1_000L, 1_000L + cool - 1, cool))
        assertFalse("window over", atRiskReplaySuppressed("R1", "R1", 1_000L, 1_000L + cool, cool))
        assertFalse("a different token", atRiskReplaySuppressed("R1", "R2", 1_000L, 1_001L, cool))
        assertFalse("nothing at risk", atRiskReplaySuppressed(null, "R1", 1_000L, 1_001L, cool))
        assertFalse("clock went backwards", atRiskReplaySuppressed("R1", "R1", 1_000L, 500L, cool))
    }

    @Test
    fun `the ledger keeps the newest twenty`() {
        var rows = emptyList<SignOutEvent>()
        repeat(25) { i -> rows = appendCapped(rows, event(at = i.toLong())) }
        assertEquals(LEDGER_CAP, rows.size)
        assertEquals("newest first", 24L, rows.first().at)
        assertEquals("oldest kept", 5L, rows.last().at)
    }

    @Test
    fun `a ledger row carries no secrets`() {
        val row = SignOutEvent(
            at = 1_700_000_000_000L,
            reason = SignOutReason.REFRESH_REJECTED.name,
            httpStatus = 400,
            errorCode = "INVALID_GRANT",
            trigger = SignOutTrigger.REFRESH.name,
            caller = "TokenManager.doRefresh",
        )
        val encoded = json.encodeToString(SignOutEvent.serializer(), row)
        assertEquals(
            """{"at":1700000000000,"reason":"REFRESH_REJECTED","httpStatus":400,""" +
                """"errorCode":"INVALID_GRANT","trigger":"REFRESH","caller":"TokenManager.doRefresh"}""",
            encoded,
        )
    }

    // ── which grant a logout may revoke ───────────────────────────────────────

    @Test
    fun `revocation follows the server's own marker`() {
        val mine = grant(id = "a", current = true)
        val theirs = grant(id = "b", current = false)
        assertSame(mine, grantToRevoke(listOf(theirs, mine), CLIENT))
    }

    @Test
    fun `the server saying none-of-these-is-yours is believed`() {
        // Two installs of this app, and the caller is neither (a cookie caller,
        // or a bearer whose grant is not listed). Guessing here is what used to
        // revoke the OTHER device.
        val grants = listOf(grant("a", current = false), grant("b", current = false))
        assertNull(grantToRevoke(grants, CLIENT))
    }

    @Test
    fun `without a marker only an unambiguous single grant is revoked`() {
        assertEquals("a", grantToRevoke(listOf(grant("a", current = null)), CLIENT)?.id)
        assertNull(
            "two of ours and no way to tell them apart",
            grantToRevoke(listOf(grant("a", current = null), grant("b", current = null)), CLIENT),
        )
        assertNull(
            "someone else's app",
            grantToRevoke(listOf(grant("a", current = null, clientId = "other")), CLIENT),
        )
    }

    // ── rule 1: transient never signs out ─────────────────────────────────────

    @Test
    fun `a 5xx keeps the session`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val api = FakeTokenApi { refused(503, "INTERNAL") }
        val tm = manager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("not wiped", store.wiped)
        assertEquals("R1", store.tokens?.refreshToken)
        assertTrue("surfaces as reconnecting", tm.sessionDegraded.value)
    }

    @Test
    fun `a 400 VALIDATION_ERROR keeps the session`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val tm = manager(FakeTokenApi { refused(400, "VALIDATION_ERROR") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse(store.wiped)
        assertEquals("R1", store.tokens?.refreshToken)
    }

    @Test
    fun `a 401 from the token endpoint keeps the session`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val tm = manager(FakeTokenApi { refused(401, "API_KEY_INVALID") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("a bearer artefact is not a dead token", store.wiped)
    }

    @Test
    fun `a rate limit keeps the session`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val tm = manager(FakeTokenApi { refused(429, "RATE_LIMITED") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse(store.wiped)
    }

    @Test
    fun `unreadable storage never wipes, even on a definitive refusal`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val api = FakeTokenApi {
            // The Keystore goes unreadable while the call is in flight.
            store.unavailable = "KeyStoreException"
            refused(400, "INVALID_GRANT")
        }
        val tm = manager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("the bytes may be perfectly good", store.wiped)
    }

    // ── rule 2: one refresh at a time, persisted before anyone proceeds ───────

    @Test
    fun `eight concurrent callers cause exactly one refresh`() = runBlocking(Dispatchers.Default) {
        val store = FakeStore(tokens("A1", "R1"))
        val api = FakeTokenApi {
            delay(40) // hold the mutex long enough for the others to queue
            rotated("A2", "R2")
        }
        val tm = manager(api, store)

        val results = (1..8).map { async { tm.refreshOn401("A1") } }.awaitAll()

        assertEquals("exactly one rotation", 1, api.calls.get())
        assertTrue("everyone gets the fresh token", results.all { it == "A2" })
        assertEquals("R2", store.tokens?.refreshToken)
    }

    @Test
    fun `the rotated pair is on disk before the caller returns`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val tm = manager(FakeTokenApi { rotated("A2", "R2") }, store)

        assertEquals("A2", tm.refreshOn401("A1"))
        assertEquals("one synchronous write", 1, store.saves)
        assertEquals("A2", store.tokens?.accessToken)
        assertEquals("R2", store.tokens?.refreshToken)
    }

    // ── rule 3: the reuse race ────────────────────────────────────────────────

    @Test
    fun `a refusal for a superseded token keeps the rotated session`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val api = FakeTokenApi {
            // Somebody else rotated between our read and the server's answer, so
            // the refusal we are holding is about a token nobody uses any more.
            store.tokens = tokens("A2", "R2")
            refused(400, "INVALID_GRANT")
        }
        val tm = manager(api, store)

        assertEquals("A2", tm.refreshOn401("A1"))
        assertFalse("nothing was revoked that matters", store.wiped)
    }

    @Test
    fun `a lost answer is not replayed, and the next caller does not fire again`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val api = FakeTokenApi { throw SocketTimeoutException("sent, no answer") }
        val tm = manager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertEquals(1, api.calls.get())
        assertFalse(store.wiped)

        // This is the exact moment the old client killed the grant: the mutex is
        // free, the access token is unchanged, and the next queued 401 arrives.
        assertNull(tm.refreshOn401("A1"))
        assertEquals("the spent token is NOT offered again", 1, api.calls.get())
        assertTrue(tm.sessionDegraded.value)
    }

    @Test
    fun `a connect failure is not a lost answer and may be retried at once`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        var first = true
        val api = FakeTokenApi {
            if (first) {
                first = false
                throw ConnectException("never left the phone")
            }
            rotated("A2", "R2")
        }
        val tm = manager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertEquals("A2", tm.refreshOn401("A1"))
        assertEquals(2, api.calls.get())
    }

    @Test
    fun `the quarantined token is offered once the cool-off expires`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        var clock = 1_000_000L
        var first = true
        val api = FakeTokenApi {
            if (first) {
                first = false
                throw SocketTimeoutException("sent, no answer")
            }
            rotated("A2", "R2")
        }
        val tm = manager(api, store) { clock }

        assertNull(tm.refreshOn401("A1"))
        clock += 9 * 60_000L
        assertNull("still inside the cool-off", tm.refreshOn401("A1"))
        assertEquals(1, api.calls.get())

        clock += 2 * 60_000L
        assertEquals("A2", tm.refreshOn401("A1"))
        assertEquals(2, api.calls.get())
    }

    // ── rule 4: the ledger says which story it was ────────────────────────────

    @Test
    fun `a definitive refusal ends the session and names itself`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        val tm = manager(FakeTokenApi { refused(400, "INVALID_GRANT") }, store)
        val seen = mutableListOf<SessionInvalidation>()
        val collector = async(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertNull(tm.refreshOn401("A1"))
        waitFor { seen.isNotEmpty() }
        collector.cancel()

        assertTrue("local credentials gone", store.wiped)
        assertEquals(1, seen.size)
        assertEquals(SignOutReason.REFRESH_REJECTED, seen.single().reason)
        assertEquals(400, seen.single().httpStatus)
        assertEquals("INVALID_GRANT", seen.single().errorCode)
        assertEquals("TokenManager.doRefresh", seen.single().caller)
    }

    @Test
    fun `a refusal after a lost answer is recorded as the network story`() = runBlocking {
        val store = FakeStore(tokens("A1", "R1"))
        var clock = 1_000_000L
        var first = true
        val api = FakeTokenApi {
            if (first) {
                first = false
                throw SocketTimeoutException("sent, no answer")
            }
            refused(400, "INVALID_GRANT")
        }
        val tm = manager(api, store) { clock }
        val seen = mutableListOf<SessionInvalidation>()
        val collector = async(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertNull(tm.refreshOn401("A1"))
        clock += 11 * 60_000L
        assertNull(tm.refreshOn401("A1"))
        waitFor { seen.isNotEmpty() }
        collector.cancel()

        assertEquals(
            "the difference between 'revoked' and 'a flaky network cost you your session'",
            SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE,
            seen.single().reason,
        )
    }

    // ── the 401 that must never happen ────────────────────────────────────────

    /**
     * The bearer middleware is mounted on the whole `/api/v1` subtree ABOVE the
     * public OAuth router and has no exemption for `/oauth/token`
     * (`apps/api/src/app.ts:144` vs `:174`). An `Authorization` header on a
     * refresh therefore never reaches the OAuth service at all — it comes back
     * `401 API_KEY_INVALID` with a perfectly healthy refresh token. The app's
     * refresh rides a bare client for exactly this reason; this pins the wire.
     */
    @Test
    fun `the refresh goes out with no Authorization header`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"access_token":"A2","token_type":"Bearer","expires_in":3600,""" +
                            """"refresh_token":"R2","scope":"portfolio:read"}""",
                    ),
            )
            val api = Retrofit.Builder()
                .baseUrl(server.url("/api/v1/"))
                .client(OkHttpClient.Builder().build())
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TokenApi::class.java)

            val store = FakeStore(tokens("A1", "R1"))
            val tm = TokenManager(api, store, json, CLIENT, REDIRECT)
            runBlocking { assertEquals("A2", tm.refreshOn401("A1")) }

            val recorded = server.takeRequest()
            assertNull("a bearer here is a 401 API_KEY_INVALID", recorded.getHeader("Authorization"))
            assertEquals("/api/v1/oauth/token", recorded.path)
            // `.strict()` server-side: exactly these three fields, JSON, nothing else.
            assertEquals(
                """{"grant_type":"refresh_token","refresh_token":"R1","client_id":"$CLIENT"}""",
                recorded.body.readUtf8(),
            )
        } finally {
            server.shutdown()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun manager(
        api: TokenApi,
        store: SessionStore,
        clock: () -> Long = { 1_000_000L },
    ) = TokenManager(api, store, json, CLIENT, REDIRECT, clock)

    private fun tokens(access: String, refresh: String) = AuthTokens(
        accessToken = access,
        refreshToken = refresh,
        scope = "portfolio:read",
        // Far future: these tests drive the REACTIVE path on purpose.
        expiresAtEpochMs = Long.MAX_VALUE / 2,
    )

    private fun rotated(access: String, refresh: String): Response<TokenResponse> =
        Response.success(
            TokenResponse(
                accessToken = access,
                tokenType = "Bearer",
                expiresIn = 3600,
                refreshToken = refresh,
                scope = "portfolio:read",
            ),
        )

    private fun refused(status: Int, code: String): Response<TokenResponse> =
        Response.error(
            status,
            """{"error":{"code":"$code","message":"refused"}}"""
                .toResponseBody("application/json".toMediaType()),
        )

    private fun event(at: Long) = SignOutEvent(
        at = at,
        reason = SignOutReason.USER_LOGOUT.name,
        trigger = SignOutTrigger.USER.name,
        caller = "test",
    )

    private fun grant(id: String, current: Boolean?, clientId: String = CLIENT) = OAuthGrant(
        id = id,
        clientId = clientId,
        appName = "BetterTrackMobile",
        current = current,
    )

    /** Poll a `tryEmit`ed flow without wiring a dispatcher into the production code. */
    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(5)
        }
        assertTrue("condition never became true", condition())
    }

    private class FakeStore(initial: AuthTokens? = null) : SessionStore {
        var tokens: AuthTokens? = initial
        var unavailable: String? = null
        var wiped = false
        var saves = 0

        override fun readTokens(): TokenRead {
            unavailable?.let { return TokenRead.Unavailable(it) }
            return tokens?.let { TokenRead.Present(it) } ?: TokenRead.None
        }

        override fun saveTokens(tokens: AuthTokens): Boolean {
            saves++
            this.tokens = tokens
            return true
        }

        override fun clearTokens() {
            tokens = null
        }

        override fun savePending(codeVerifier: String, state: String) = Unit
        override fun loadPending(): Pair<String, String>? = null
        override fun clearPending() = Unit
        override fun loadUser(): SessionUser? = null
        override fun saveUser(user: SessionUser) = Unit

        override fun wipeAll() {
            wiped = true
            tokens = null
        }

        override fun reopen(): Boolean = unavailable == null
    }

    private class FakeTokenApi(
        private val answer: suspend () -> Response<TokenResponse>,
    ) : TokenApi {
        val calls = AtomicInteger(0)

        override suspend fun exchange(body: TokenExchangeRequest): Response<TokenResponse> {
            calls.incrementAndGet()
            return answer()
        }

        override suspend fun refresh(body: TokenRefreshRequest): Response<TokenResponse> {
            calls.incrementAndGet()
            return answer()
        }
    }

    private companion object {
        const val CLIENT = "btc_IbT1mzw_7kBiPHPkGfaE0Q"
        const val REDIRECT = "bettertrack://oauth/callback"
    }
}
