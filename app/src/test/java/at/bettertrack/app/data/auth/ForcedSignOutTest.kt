package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.TokenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
 * the server has already spent. On today's server nothing the phone does after
 * that can save the session — any presentation of the spent token is the
 * refusal — which is why the platform is adding a 60 s grace (PR-A) and an
 * identical-pair re-serve (PR-C). What the app owes in return, pinned here and
 * in `RefreshReplayTest`: never end a session on anything but that refusal,
 * replay the SAME token promptly (inside the grace) and single-flight, and keep
 * the at-risk record on disk so a process death cannot turn the replay into a
 * fresh, unexplained presentation hours later.
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
     * (:1069, :1072, :1084, :1087, :1127), plus `400 INVALID_SCOPE` since
     * platform #2018 (:1108-1111 — the zero-scope refresh retires the grant).
     * Nothing else on that endpoint is a statement about the session.
     */
    @Test
    fun `only 400 INVALID_GRANT or INVALID_SCOPE may end a session`() {
        assertTrue(refreshRefusalIsDefinitive(400, "INVALID_GRANT"))
        assertTrue("case is not part of the contract", refreshRefusalIsDefinitive(400, "invalid_grant"))
        assertTrue("zero-scope refresh: the grant is retired (#2018)", refreshRefusalIsDefinitive(400, "INVALID_SCOPE"))
        assertTrue(refreshRefusalIsDefinitive(400, "invalid_scope"))
        assertEquals(setOf("INVALID_GRANT", "INVALID_SCOPE"), DEFINITIVE_REFRESH_REFUSAL_CODES)
        // Status still matters: only the token endpoint's 400 carries the verdict.
        assertFalse(refreshRefusalIsDefinitive(403, "INVALID_SCOPE"))

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
        assertFalse("no route", requestMayHaveBeenDelivered(java.net.NoRouteToHostException()))
        assertFalse("TLS handshake precedes the request", requestMayHaveBeenDelivered(javax.net.ssl.SSLHandshakeException("x")))
        assertTrue("sent, then silence", requestMayHaveBeenDelivered(SocketTimeoutException()))
        assertTrue("reset mid-answer", requestMayHaveBeenDelivered(java.net.SocketException("Connection reset")))
        assertTrue("a 200 we could not read", requestMayHaveBeenDelivered(kotlinx.serialization.SerializationException("x")))
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

    // ── rule 1: transient never signs out ─────────────────────────────────────

    @Test
    fun `a 5xx keeps the session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { refusedResponse(503, "INTERNAL") }
        val tm = testManager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("not wiped", store.wiped)
        assertEquals("R1", store.tokens?.refreshToken)
        assertTrue("surfaces as reconnecting", tm.sessionDegraded.value)
        // A 5xx may come from behind the rotation, so it is replayed like a lost
        // answer — once, in-call — and the record of the at-risk token stays.
        assertEquals(listOf("R1", "R1"), api.presented)
        assertEquals(2, store.pendingRefresh?.attempts)
    }

    @Test
    fun `a 400 VALIDATION_ERROR keeps the session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(400, "VALIDATION_ERROR") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse(store.wiped)
        assertEquals("R1", store.tokens?.refreshToken)
        assertNull("the server answered before rotating: nothing at risk", store.pendingRefresh)
    }

    @Test
    fun `a 401 from the token endpoint keeps the session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(401, "API_KEY_INVALID") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("a bearer artefact is not a dead token", store.wiped)
    }

    @Test
    fun `a rate limit keeps the session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(429, "RATE_LIMITED", retryAfter = "20") }, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse(store.wiped)
        assertNull("the limiter answered before the handler: nothing consumed", store.pendingRefresh)
    }

    @Test
    fun `unreadable storage never wipes, even on a definitive refusal`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi {
            // The Keystore goes unreadable while the call is in flight.
            store.unavailable = "KeyStoreException"
            refusedResponse(400, "INVALID_GRANT")
        }
        val tm = testManager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertFalse("the bytes may be perfectly good", store.wiped)
    }

    // ── rule 2: one refresh at a time, persisted before anyone proceeds ───────

    @Test
    fun `eight concurrent callers cause exactly one refresh`() = runBlocking(Dispatchers.Default) {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi {
            delay(40) // hold the mutex long enough for the others to queue
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store)

        val results = (1..8).map { async { tm.refreshOn401("A1") } }.awaitAll()

        assertEquals("exactly one rotation", 1, api.calls.get())
        assertTrue("everyone gets the fresh token", results.all { it == "A2" })
        assertEquals("R2", store.tokens?.refreshToken)
    }

    @Test
    fun `the rotated pair is on disk before the caller returns`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { rotatedResponse("A2", "R2") }, store)

        assertEquals("A2", tm.refreshOn401("A1"))
        assertEquals("one synchronous write", 1, store.saves)
        assertEquals("A2", store.tokens?.accessToken)
        assertEquals("R2", store.tokens?.refreshToken)
        assertNull("success clears the write-ahead record", store.pendingRefresh)
    }

    @Test
    fun `the write-ahead record is on disk before the request leaves`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        var recordAtSend: PendingRefresh? = null
        val tm = testManager(
            FakeTokenApi {
                recordAtSend = store.pendingRefresh
                rotatedResponse("A2", "R2")
            },
            store,
        )

        tm.refreshOn401("A1")
        assertEquals(refreshTokenFingerprint("R1"), recordAtSend?.tokenFingerprint)
        assertEquals(1, recordAtSend?.attempts)
    }

    @Test
    fun `a rotated pair that will not persist keeps the replay record`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1")).apply { failSaves = 2 }
        val tm = testManager(FakeTokenApi { rotatedResponse("A2", "R2") }, store)

        assertEquals("the caller still gets the live token", "A2", tm.refreshOn401("A1"))
        assertEquals("one retry of the commit", 2, store.saves)
        assertEquals(
            "the disk still holds the spent token, so its next use must be a replay",
            refreshTokenFingerprint("R1"),
            store.pendingRefresh?.tokenFingerprint,
        )
    }

    // ── rule 3: the reuse race ────────────────────────────────────────────────

    @Test
    fun `a refusal for a superseded token keeps the rotated session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi {
            // Somebody else rotated between our read and the server's answer, so
            // the refusal we are holding is about a token nobody uses any more.
            store.tokens = testTokens("A2", "R2")
            refusedResponse(400, "INVALID_GRANT")
        }
        val tm = testManager(api, store)

        assertEquals("A2", tm.refreshOn401("A1"))
        assertFalse("nothing was revoked that matters", store.wiped)
    }

    @Test
    fun `a connect failure is not a lost answer and a 401 may retry at once`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { call ->
            if (call == 1) throw ConnectException("never left the phone")
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertNull("the bytes never left: nothing at risk", store.pendingRefresh)
        assertEquals("a 401 is proof of reachability", "A2", tm.refreshOn401("A1"))
        assertEquals(2, api.calls.get())
    }

    // ── rule 4: the ledger says which story it was ────────────────────────────

    @Test
    fun `a definitive refusal ends the session and names itself`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(400, "INVALID_GRANT") }, store)
        val seen = mutableListOf<SessionInvalidation>()
        val collector = async(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertNull(tm.refreshOn401("A1"))
        waitFor { seen.isNotEmpty() }
        collector.cancel()

        assertTrue("local credentials gone", store.wiped)
        assertNull("the write-ahead record goes with them", store.pendingRefresh)
        assertEquals(1, seen.size)
        assertEquals(SignOutReason.REFRESH_REJECTED, seen.single().reason)
        assertEquals(400, seen.single().httpStatus)
        assertEquals("INVALID_GRANT", seen.single().errorCode)
        assertEquals("TokenManager.doRefresh", seen.single().caller)
    }

    /**
     * Platform #2018: a refresh whose effective scopes clamp to empty answers
     * `400 INVALID_SCOPE` and retires the grant in the same breath. Keeping the
     * session would replay a refresh that can never succeed again.
     */
    @Test
    fun `a zero-scope refusal ends the session and names itself`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(400, "INVALID_SCOPE") }, store)
        val seen = mutableListOf<SessionInvalidation>()
        val collector = async(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertNull(tm.refreshOn401("A1"))
        waitFor { seen.isNotEmpty() }
        collector.cancel()

        assertTrue("local credentials gone", store.wiped)
        assertNull(store.pendingRefresh)
        assertEquals(SignOutReason.REFRESH_REJECTED, seen.single().reason)
        assertEquals(400, seen.single().httpStatus)
        assertEquals("INVALID_SCOPE", seen.single().errorCode)
    }

    /** INVALID_CLIENT is a build problem (wrong/rotated client_id), not a session verdict. */
    @Test
    fun `an INVALID_CLIENT refusal keeps the session`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val tm = testManager(FakeTokenApi { refusedResponse(400, "INVALID_CLIENT") }, store)
        tm.refreshOn401("A1")
        assertFalse("credentials kept", store.wiped)
    }

    /**
     * Today's zero-grace server: the replay of a consumed token is answered
     * `400 INVALID_GRANT`. The sign-out is the SERVER's verdict, and the ledger
     * says it followed a lost answer.
     */
    @Test
    fun `a refusal of the replay after a lost answer is recorded as the network story`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { call ->
            if (call == 1) throw SocketTimeoutException("sent, no answer")
            refusedResponse(400, "INVALID_GRANT")
        }
        val tm = testManager(api, store)
        val seen = mutableListOf<SessionInvalidation>()
        val collector = async(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertNull(tm.refreshOn401("A1"))
        waitFor { seen.isNotEmpty() }
        collector.cancel()

        assertEquals("the same token, replayed once", listOf("R1", "R1"), api.presented)
        assertEquals(
            "the difference between 'revoked' and 'a flaky network cost you your session'",
            SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE,
            seen.single().reason,
        )
        assertEquals("INVALID_GRANT", seen.single().errorCode)
        assertNull("cleared on the definitive outcome", store.pendingRefresh)
    }

    // ── the 401 that must never happen ────────────────────────────────────────

    /**
     * The bearer middleware is mounted on the whole `/api/v1` subtree ABOVE the
     * public OAuth router and has no exemption for `/oauth/token`
     * (`apps/api/src/app.ts:144` vs `:174`). An `Authorization` header on a
     * refresh therefore never reaches the OAuth service at all — it comes back
     * `401 API_KEY_INVALID` with a perfectly healthy refresh token. The app's
     * refresh rides a bare client for exactly this reason; this pins the wire,
     * on the PRODUCTION token client.
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
                .client(tokenHttpClient())
                .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TokenApi::class.java)

            val store = FakeStore(testTokens("A1", "R1"))
            val tm = TokenManager(api, store, testJson, TEST_CLIENT, TEST_REDIRECT)
            runBlocking { assertEquals("A2", tm.refreshOn401("A1")) }

            val recorded = server.takeRequest()
            assertNull("a bearer here is a 401 API_KEY_INVALID", recorded.getHeader("Authorization"))
            assertEquals("/api/v1/oauth/token", recorded.path)
            // `.strict()` server-side: exactly these three fields, JSON, nothing else.
            assertEquals(
                """{"grant_type":"refresh_token","refresh_token":"R1","client_id":"$TEST_CLIENT"}""",
                recorded.body.readUtf8(),
            )
        } finally {
            server.shutdown()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun event(at: Long) = SignOutEvent(
        at = at,
        reason = SignOutReason.USER_LOGOUT.name,
        trigger = SignOutTrigger.USER.name,
        caller = "test",
    )

    /** Poll a `tryEmit`ed flow without wiring a dispatcher into the production code. */
    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            delay(5)
        }
        assertTrue("condition never became true", condition())
    }
}
