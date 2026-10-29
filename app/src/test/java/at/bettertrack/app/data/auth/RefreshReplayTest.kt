package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.TokenApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * The lost-answer replay, pinned (coordinator scope change 2026-10-29).
 *
 * A refresh whose answer is lost may have rotated server-side; the token on disk
 * may be spent. The app replays the SAME token promptly (1-3 s, jittered,
 * single-flight), persists the fact that it is at risk BEFORE every send, and
 * replays a record a dead process left behind before anything else uses the
 * session. Against the platform:
 *  - today (zero grace): the replay is answered `400 INVALID_GRANT` and the
 *    session ends — the SERVER's verdict, ledgered as the network story;
 *  - PR-A (60 s grace) / PR-C (identical unused pair re-served up to 24 h): the
 *    replay is answered `200` with the SAME pair — accepted, session kept.
 */
class RefreshReplayTest {

    // ── (a) a lost answer is replayed at once, and the same pair is accepted ───

    @Test
    fun `a lost answer is replayed within seconds and the identical pair is accepted`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val time = VirtualTime()
        val journal = MemoryJournal()
        val api = FakeTokenApi { call ->
            if (call == 1) throw SocketTimeoutException("sent, no answer")
            // PR-A / PR-C: the server hands back the pair it issued the first time.
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store, time, journal = journal)

        assertEquals("A2", tm.refreshOn401("A1"))

        assertEquals("the SAME token, twice", listOf("R1", "R1"), api.presented)
        assertEquals("one in-call pause before the replay", 1, time.sleeps.size)
        val wait = time.sleeps.single()
        assertTrue("replayed inside the server's 60 s grace with room to spare: $wait", wait in 1_000L..30_000L)
        assertEquals("R2", store.tokens?.refreshToken)
        assertNull("success clears the record", store.pendingRefresh)
        assertFalse(store.wiped)
        assertFalse(tm.sessionDegraded.value)
        // The journal tells the whole story, newest first.
        assertEquals(listOf("ROTATED", "ANSWER_LOST"), journal.rows.map { it.verdict })
        assertEquals(listOf("REPLAY", "REACTIVE"), journal.rows.map { it.caller })
        assertEquals(listOf(2, 1), journal.rows.map { it.attempt })
        assertEquals(wait, journal.rows.last().waitMs)
    }

    @Test
    fun `the replay pause is jittered and never more than three seconds the first time`() {
        val low = refreshBackoffMs(RefreshBackoffKind.REPLAY, failures = 1, random = 0.0)
        val high = refreshBackoffMs(RefreshBackoffKind.REPLAY, failures = 1, random = 0.999999)
        assertEquals(1_000L, low)
        assertTrue(high in 2_999L..3_000L)
    }

    /**
     * The same story on the wire, through the PRODUCTION token client: the server
     * reads the refresh and drops the connection (it rotated; the phone never
     * hears). Exactly two requests reach it — the original and the app's one
     * deliberate replay. Before the one-shot client, OkHttp could add a third,
     * silent one of its own.
     */
    @Test
    fun `on the wire, a dropped answer costs exactly one deliberate replay`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(pairResponse("A2", "R2"))
            val store = FakeStore(testTokens("A1", "R1"))
            val tm = testManager(tokenApiOn(server), store)

            runBlocking { assertEquals("A2", tm.refreshOn401("A1")) }

            assertEquals(2, server.requestCount)
            repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("\"refresh_token\":\"R1\"")) }
            assertEquals("R2", store.tokens?.refreshToken)
            assertNull(store.pendingRefresh)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a replay that loses its answer again waits on the replay schedule, then tries again`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val time = VirtualTime()
        val api = FakeTokenApi { call ->
            if (call <= 2) throw SocketTimeoutException("sent, no answer")
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store, time)

        assertNull(tm.refreshOn401("A1"))
        assertEquals(2, api.calls.get())
        val record = store.pendingRefresh
        assertNotNull("still at risk, and on disk", record)
        assertEquals(2, record!!.attempts)
        assertTrue(tm.sessionDegraded.value)
        val firstSent = record.firstSentAtMs

        // Inside the replay window nobody but a person presents the token again.
        assertNull(tm.refreshOn401("A1"))
        tm.proactiveRefreshIfNeeded()
        assertEquals("held by the schedule", 2, api.calls.get())

        time.now += REPLAY_CAP_MS
        tm.proactiveRefreshIfNeeded()
        assertEquals("the pending replay runs ahead of the request", 3, api.calls.get())
        assertEquals("R2", store.tokens?.refreshToken)
        assertNull(store.pendingRefresh)
        assertTrue(firstSent < time.now)
    }

    @Test
    fun `a person may replay at once`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { call ->
            if (call <= 2) throw SocketTimeoutException("sent, no answer")
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertTrue("the banner / developer tap is not held by the replay schedule", tm.retryRefreshNow())
        assertEquals(3, api.calls.get())
    }

    // ── (b) process death mid-refresh ─────────────────────────────────────────

    @Test
    fun `a process that dies mid-refresh leaves a record, and the next start replays it first`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val dying = testManager(
            FakeTokenApi {
                assertNotNull("write-ahead: on disk before the request leaves", store.pendingRefresh)
                throw SimulatedProcessDeath()
            },
            store,
        )
        try {
            dying.refreshOn401("A1")
            fail("the process was supposed to die")
        } catch (_: SimulatedProcessDeath) {
        }
        assertEquals(refreshTokenFingerprint("R1"), store.pendingRefresh?.tokenFingerprint)
        assertEquals("R1", store.tokens?.refreshToken)

        // A new process: a new TokenManager over the same storage.
        val api = FakeTokenApi { rotatedResponse("A2", "R2") }
        val reborn = testManager(api, store)
        assertTrue(reborn.resumePendingRefresh())

        assertEquals(listOf("R1"), api.presented)
        assertEquals("R2", store.tokens?.refreshToken)
        assertNull(store.pendingRefresh)
        assertFalse("nothing to replay twice", reborn.resumePendingRefresh())
        assertEquals(1, api.calls.get())
    }

    @Test
    fun `the first API call of a new process replays the record before it goes out`() = runBlocking {
        // The access token is far from expiry: only the record makes this refresh happen.
        val store = FakeStore(testTokens("A1", "R1"))
        store.pendingRefresh = PendingRefresh(refreshTokenFingerprint("R1"), 10L, 10L, attempts = 1)
        val api = FakeTokenApi { rotatedResponse("A2", "R2") }
        val tm = testManager(api, store)

        tm.proactiveRefreshIfNeeded()

        assertEquals(listOf("R1"), api.presented)
        assertEquals("A2", tm.currentAccessToken())
        assertNull(store.pendingRefresh)
    }

    @Test
    fun `a record for a token that is no longer on disk is dropped, not replayed`() = runBlocking {
        val store = FakeStore(testTokens("A2", "R2"))
        store.pendingRefresh = PendingRefresh(refreshTokenFingerprint("R1"), 10L, 10L, attempts = 1)
        val api = FakeTokenApi { rotatedResponse("A3", "R3") }
        val tm = testManager(api, store)

        assertFalse(tm.resumePendingRefresh())
        tm.proactiveRefreshIfNeeded()

        assertEquals("nothing sent", 0, api.calls.get())
        assertNull(store.pendingRefresh)
    }

    /** Today's server: the replay after a process death meets the spent token. The server decides, and it is ledgered as such. */
    @Test
    fun `a refused replay after a process death is the network story`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        store.pendingRefresh = PendingRefresh(refreshTokenFingerprint("R1"), 10L, 10L, attempts = 1)
        val tm = testManager(FakeTokenApi { refusedResponse(400, "INVALID_GRANT") }, store)
        val seen = mutableListOf<SessionInvalidation>()
        val collector = launch(Dispatchers.Default) { tm.sessionInvalidated.collect { seen += it } }

        assertFalse(tm.resumePendingRefresh())
        repeat(200) { if (seen.isEmpty()) delay(5) }
        collector.cancel()

        assertTrue(store.wiped)
        assertNull(store.pendingRefresh)
        assertEquals(SignOutReason.REFRESH_REJECTED_AFTER_LOST_RESPONSE, seen.single().reason)
    }

    @Test
    fun `a caller that goes away mid-refresh cannot abort the commit`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val sent = CompletableDeferred<Unit>()
        val api = FakeTokenApi {
            sent.complete(Unit)
            delay(100)
            rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store)

        val job = launch(Dispatchers.Default) { tm.refreshOn401("A1") }
        sent.await()
        job.cancel()
        job.join()
        repeat(100) { if (store.tokens?.refreshToken != "R2") delay(10) }

        assertEquals("the answer the server gave was still committed", "R2", store.tokens?.refreshToken)
        assertNull(store.pendingRefresh)
    }

    // ── (c) 429 / 5xx / transport are never definitive ───────────────────────

    @Test
    fun `a 429 holds every caller for its Retry-After, then lets the refresh through`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val time = VirtualTime()
        val api = FakeTokenApi { call ->
            if (call == 1) refusedResponse(429, "RATE_LIMITED", retryAfter = "120") else rotatedResponse("A2", "R2")
        }
        val tm = testManager(api, store, time)

        assertNull(tm.refreshOn401("A1"))
        val backoff = tm.status.value.backoff
        assertEquals(RefreshBackoffKind.RATE_LIMITED, backoff?.kind)
        assertTrue("never shorter than Retry-After", backoff!!.durationMs >= 120_000L)
        assertFalse(store.wiped)

        assertNull("a 401 does not prove the limiter cooled down", tm.refreshOn401("A1"))
        assertFalse("a tap inside the cooldown only escalates it", tm.retryRefreshNow())
        assertEquals(1, api.calls.get())

        time.now += backoff.durationMs
        assertEquals("A2", tm.refreshOn401("A1"))
        assertNull(tm.status.value.backoff)
        assertFalse(tm.sessionDegraded.value)
    }

    @Test
    fun `a 429 during a pending replay keeps the token at risk`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        store.pendingRefresh = PendingRefresh(refreshTokenFingerprint("R1"), 10L, 10L, attempts = 1)
        val tm = testManager(FakeTokenApi { refusedResponse(429, "RATE_LIMITED", retryAfter = "5") }, store)

        assertFalse(tm.resumePendingRefresh())
        assertEquals("still the first send's record", 10L, store.pendingRefresh?.firstSentAtMs)
        assertFalse(store.wiped)
    }

    @Test
    fun `nothing short of the two definitive codes ever wipes`() = runBlocking {
        val answers = listOf(
            { refusedResponse(500, "INTERNAL") },
            { refusedResponse(502, "BAD_GATEWAY") },
            { refusedResponse(520, "HTTP_FAILED") },
            { refusedResponse(522, "HTTP_FAILED") },
            { refusedResponse(429, "RATE_LIMITED") },
            { refusedResponse(400, "VALIDATION_ERROR") },
            { refusedResponse(400, "INVALID_CLIENT") },
            { refusedResponse(401, "API_KEY_INVALID") },
            { refusedResponse(403, "FORBIDDEN") },
            { throw ConnectException("refused") },
            { throw SocketTimeoutException("silence") },
        )
        answers.forEachIndexed { i, answer ->
            val store = FakeStore(testTokens("A1", "R1"))
            val tm = testManager(FakeTokenApi { answer() }, store)
            assertNull("case $i", tm.refreshOn401("A1"))
            assertFalse("case $i must keep the session", store.wiped)
            assertEquals("case $i", "R1", store.tokens?.refreshToken)
            assertTrue("case $i surfaces as reconnecting", tm.sessionDegraded.value)
        }
    }

    @Test
    fun `an unreachable server holds the proactive path but not a 401`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1", expiresAt = 0L))
        val api = FakeTokenApi { call -> if (call == 1) throw ConnectException("offline") else rotatedResponse("A2", "R2") }
        val tm = testManager(api, store)

        tm.proactiveRefreshIfNeeded()
        tm.proactiveRefreshIfNeeded()
        assertEquals("one connect timeout, not one per request", 1, api.calls.get())
        assertNull(store.pendingRefresh)
        assertEquals("A2", tm.refreshOn401("A1"))
    }

    // ── (d) the record is cleared on the definitive outcomes ──────────────────

    @Test
    fun `the record is cleared by INVALID_SCOPE after a replay`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { call ->
            if (call == 1) throw SocketTimeoutException("sent, no answer")
            refusedResponse(400, "INVALID_SCOPE")
        }
        val tm = testManager(api, store)

        assertNull(tm.refreshOn401("A1"))
        assertTrue(store.wiped)
        assertNull(store.pendingRefresh)
    }

    @Test
    fun `a fresh login starts without the old session's renewal state`() = runBlocking {
        val store = FakeStore(testTokens("A1", "R1"))
        val api = FakeTokenApi { call ->
            if (call == 1) refusedResponse(429, "RATE_LIMITED", retryAfter = "600") else rotatedResponse("B1", "S1")
        }
        val tm = testManager(api, store)
        tm.refreshOn401("A1")
        assertNotNull("precondition: a long limiter pause is in force", tm.status.value.backoff)
        store.pendingRefresh = PendingRefresh(refreshTokenFingerprint("R1"), 10L, 10L, 1)

        tm.exchange("code", "verifier")

        assertNull(store.pendingRefresh)
        assertNull("the new session may refresh at once", tm.status.value.backoff)
        assertEquals("S1", store.tokens?.refreshToken)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun pairResponse(access: String, refresh: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"access_token":"$access","token_type":"Bearer","expires_in":3600,""" +
                """"refresh_token":"$refresh","scope":"portfolio:read"}""",
        )

    private fun tokenApiOn(server: MockWebServer): TokenApi = Retrofit.Builder()
        .baseUrl(server.url("/api/v1/"))
        .client(tokenHttpClient())
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TokenApi::class.java)
}
