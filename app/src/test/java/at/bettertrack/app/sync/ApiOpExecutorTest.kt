package at.bettertrack.app.sync

import at.bettertrack.app.data.api.BtApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
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

/**
 * Wire-level tests of the queue op → API mapping (platform #432, PLATFORM_ASKS
 * #9): the offline queue must attach `Idempotency-Key: <op.clientId>` to every
 * portfolio-mutation send, on exactly the right endpoints, and classify the
 * server's idempotency error codes correctly. Uses a real MockWebServer so the
 * assertions are on the ACTUAL bytes Retrofit puts on the wire.
 */
class ApiOpExecutorTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BtApi
    private lateinit var executor: ApiOpExecutor

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
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BtApi::class.java)
        executor = ApiOpExecutor(api, json) // idempotencyEnabled defaults true
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Header attached on exactly the right endpoints ───────────────────────

    @Test
    fun `createTransaction carries the Idempotency-Key equal to the op clientId`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))

        val result = executor.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/portfolios/p1/transactions"))
        assertEquals(KEY, req.getHeader("Idempotency-Key"))
    }

    @Test
    fun `cashDeposit carries the Idempotency-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody(CASH_BODY))

        val result = executor.execute(op(OpType.CASH_DEPOSIT, CASH_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/cash/deposit"))
        assertEquals(KEY, req.getHeader("Idempotency-Key"))
    }

    @Test
    fun `cashWithdraw carries the Idempotency-Key`() = runBlocking {
        server.enqueue(MockResponse().setBody(CASH_BODY))

        val result = executor.execute(op(OpType.CASH_WITHDRAW, CASH_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/cash/withdraw"))
        assertEquals(KEY, req.getHeader("Idempotency-Key"))
    }

    @Test
    fun `cashTransfer carries the Idempotency-Key`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"outgoing":{"id":"o1","kind":"transfer_out","amountEur":10.0,"executedAt":"$TS","createdAt":"$TS"}}""",
            ),
        )

        val result = executor.execute(op(OpType.CASH_TRANSFER, TRANSFER_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/cash/transfer"))
        assertEquals(KEY, req.getHeader("Idempotency-Key"))
    }

    @Test
    fun `value-point PUT carries the key while its preceding read GET does not`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"points":[]}"""))                                 // read
        server.enqueue(MockResponse().setBody("""{"points":[{"date":"2026-07-11","value":100.0}]}""")) // write

        val result = executor.execute(op(OpType.CUSTOM_ASSET_VALUE_POINT, VALUE_POINT_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertNull("the read must not carry a key", get.getHeader("Idempotency-Key"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals(KEY, put.getHeader("Idempotency-Key"))
    }

    @Test
    fun `re-executing the same op sends the identical key (replay-safe retries)`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))
        val o = op(OpType.TX_BUY, TX_PAYLOAD)

        executor.execute(o)
        executor.execute(o) // a retry / replay of the SAME op

        assertEquals(KEY, server.takeRequest().getHeader("Idempotency-Key"))
        assertEquals(KEY, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `disabled kill-switch sends no Idempotency-Key header`() = runBlocking {
        val disabled = ApiOpExecutor(api, json, idempotencyEnabled = false)
        server.enqueue(MockResponse().setBody("""{"transactions":[]}"""))

        val result = disabled.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Success)
        assertNull("flag off ⇒ no header", server.takeRequest().getHeader("Idempotency-Key"))
    }

    // ── Server idempotency error-code classification ─────────────────────────

    @Test
    fun `409 IDEMPOTENCY_IN_PROGRESS is a retryable outcome`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(envelope("IDEMPOTENCY_IN_PROGRESS", "still processing")))

        val result = executor.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertTrue("was $result", result is ExecResult.RetryableNotApplied)
        // The key still went out — a replay after the first send settles it.
        assertEquals(KEY, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `409 IDEMPOTENCY_KEY_MISMATCH is a permanent rejection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody(envelope("IDEMPOTENCY_KEY_MISMATCH", "mismatch")))

        val result = executor.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Rejected)
    }

    @Test
    fun `400 IDEMPOTENCY_KEY_INVALID asks the engine to regenerate the key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(envelope("IDEMPOTENCY_KEY_INVALID", "not a uuid")))

        val result = executor.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertEquals(ExecResult.InvalidKey, result)
    }

    @Test
    fun `a generic 4xx stays a normal business rejection`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(envelope("VALIDATION_ERROR", "Insufficient cash balance.")))

        val result = executor.execute(op(OpType.TX_BUY, TX_PAYLOAD))

        assertTrue("was $result", result is ExecResult.Rejected)
        assertEquals("Insufficient cash balance.", (result as ExecResult.Rejected).message)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun op(type: OpType, payloadJson: String) = SyncOp(
        id = 1L,
        clientId = KEY,
        type = type,
        portfolioId = "p1",
        payloadJson = payloadJson,
        status = OpStatus.IN_FLIGHT,
        attemptCount = 0,
        nextAttemptAtMs = 0L,
        serverError = null,
        serverResultJson = null,
        accountKey = "user-1",
        createdAtMs = 0L,
        updatedAtMs = 0L,
    )

    private fun envelope(code: String, message: String) =
        """{"error":{"code":"$code","message":"$message"}}"""

    private companion object {
        const val KEY = "11111111-2222-3333-4444-555555555555"
        const val TS = "2026-07-11T00:00:00Z"
        const val TX_PAYLOAD =
            """{"assetId":"a1","side":"buy","quantity":1.0,"price":2.0,"executedAt":"$TS"}"""
        const val CASH_PAYLOAD = """{"amountEur":10.0}"""
        const val TRANSFER_PAYLOAD = """{"fromSourceId":"s1","toSourceId":"s2","amountEur":10.0}"""
        const val VALUE_POINT_PAYLOAD =
            """{"customAssetId":"ca1","date":"2026-07-11","value":100.0}"""
        const val CASH_BODY =
            """{"movement":{"id":"m1","kind":"deposit","amountEur":10.0,"executedAt":"$TS","createdAt":"$TS"},"balanceEur":10.0}"""
    }
}
