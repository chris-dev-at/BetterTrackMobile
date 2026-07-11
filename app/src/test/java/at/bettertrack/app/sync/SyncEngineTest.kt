package at.bettertrack.app.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit tests of the queue state machine (spec §7.3): enqueue →
 * in-flight → done/needs-attention, FIFO ordering, backoff, ambiguous-outcome
 * reconciliation (crash mid-drain must never double-submit), session gating.
 */
class SyncEngineTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeOpStore : OpStore {
        val ops = mutableListOf<SyncOp>()
        private var nextId = 1L

        override suspend fun append(
            clientId: String,
            type: OpType,
            portfolioId: String?,
            payloadJson: String,
            accountKey: String,
            nowMs: Long,
        ): SyncOp {
            val op = SyncOp(
                id = nextId++,
                clientId = clientId,
                type = type,
                portfolioId = portfolioId,
                payloadJson = payloadJson,
                status = OpStatus.PENDING,
                attemptCount = 0,
                nextAttemptAtMs = 0L,
                serverError = null,
                serverResultJson = null,
                accountKey = accountKey,
                createdAtMs = nowMs,
                updatedAtMs = nowMs,
            )
            ops += op
            return op
        }

        override suspend fun firstOpen(): SyncOp? =
            ops.filter { it.status == OpStatus.PENDING || it.status == OpStatus.IN_FLIGHT }
                .minByOrNull { it.id }

        override suspend fun getById(id: Long): SyncOp? = ops.firstOrNull { it.id == id }

        private fun update(id: Long, mutate: (SyncOp) -> SyncOp) {
            val i = ops.indexOfFirst { it.id == id }
            if (i >= 0) ops[i] = mutate(ops[i])
        }

        override suspend fun markInFlight(id: Long, nowMs: Long) =
            update(id) { it.copy(status = OpStatus.IN_FLIGHT, updatedAtMs = nowMs) }

        override suspend fun markInFlightAttempt(
            id: Long,
            attemptCount: Int,
            nextAttemptAtMs: Long,
            nowMs: Long,
        ) = update(id) {
            it.copy(
                status = OpStatus.IN_FLIGHT,
                attemptCount = attemptCount,
                nextAttemptAtMs = nextAttemptAtMs,
                updatedAtMs = nowMs,
            )
        }

        override suspend fun markDone(id: Long, serverResultJson: String?, nowMs: Long) =
            update(id) {
                it.copy(
                    status = OpStatus.DONE,
                    serverResultJson = serverResultJson,
                    serverError = null,
                    updatedAtMs = nowMs,
                )
            }

        override suspend fun markNeedsAttention(id: Long, error: String, nowMs: Long) =
            update(id) {
                it.copy(status = OpStatus.NEEDS_ATTENTION, serverError = error, updatedAtMs = nowMs)
            }

        override suspend fun markPending(
            id: Long,
            attemptCount: Int,
            nextAttemptAtMs: Long,
            nowMs: Long,
        ) = update(id) {
            it.copy(
                status = OpStatus.PENDING,
                attemptCount = attemptCount,
                nextAttemptAtMs = nextAttemptAtMs,
                serverError = null,
                updatedAtMs = nowMs,
            )
        }

        override suspend fun markEdited(id: Long, payloadJson: String, nowMs: Long) =
            update(id) {
                it.copy(
                    payloadJson = payloadJson,
                    status = OpStatus.PENDING,
                    attemptCount = 0,
                    nextAttemptAtMs = 0L,
                    serverError = null,
                    serverResultJson = null,
                    updatedAtMs = nowMs,
                )
            }

        override suspend fun resetBackoffGates() {
            ops.indices.forEach { i ->
                val op = ops[i]
                if (op.status == OpStatus.PENDING || op.status == OpStatus.IN_FLIGHT) {
                    ops[i] = op.copy(nextAttemptAtMs = 0L)
                }
            }
        }

        var regenCount = 0
        override suspend fun regenerateClientId(id: Long, nowMs: Long): SyncOp? {
            // Deterministic fresh key so tests can assert the swap happened.
            update(id) { it.copy(clientId = "regen-${++regenCount}-${it.clientId}", updatedAtMs = nowMs) }
            return ops.firstOrNull { it.id == id }
        }

        override suspend fun delete(id: Long) {
            ops.removeAll { it.id == id }
        }

        override suspend fun pruneDone(keep: Int) {
            val done = ops.filter { it.status == OpStatus.DONE }.sortedByDescending { it.id }
            done.drop(keep).forEach { d -> ops.removeAll { it.id == d.id } }
        }

        override suspend fun countOpen(): Int =
            ops.count { it.status == OpStatus.PENDING || it.status == OpStatus.IN_FLIGHT }

        fun byClient(clientId: String): SyncOp? = ops.firstOrNull { it.clientId == clientId }
    }

    /** Scripted executor: outcomes per clientId (repeatable last entry). */
    private class FakeExecutor : OpExecutor {
        val executed = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        var execScript: (SyncOp) -> ExecResult = { ExecResult.Success(null) }
        var lookupScript: (SyncOp) -> LookupResult = { LookupResult.NotFound }

        override suspend fun execute(op: SyncOp): ExecResult {
            executed += op.clientId
            return execScript(op)
        }

        override suspend fun lookup(op: SyncOp): LookupResult {
            lookedUp += op.clientId
            return lookupScript(op)
        }
    }

    private class FakeRefresher : PostSyncRefresher {
        val calls = mutableListOf<Set<String>>()
        override suspend fun afterDrain(portfolioIds: Set<String>) {
            calls += portfolioIds
        }
    }

    private class Harness(
        val store: FakeOpStore = FakeOpStore(),
        val executor: FakeExecutor = FakeExecutor(),
        val refresher: FakeRefresher = FakeRefresher(),
        var loggedIn: Boolean = true,
        var nowMs: Long = 1_000_000L,
    ) {
        val engine = SyncEngine(
            store = store,
            executor = executor,
            refresher = refresher,
            hasSession = { loggedIn },
            ownerKey = { "user-1" },
            now = { nowMs },
        )

        fun enqueueTx(portfolio: String = "p1"): SyncOp = runBlocking {
            engine.enqueue(OpType.TX_BUY, portfolio, """{"assetId":"a"}""")
        }
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `enqueue then drain marks done exactly once and refetches portfolio`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()

        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
        assertEquals(listOf(op.clientId), h.executor.executed) // exactly one send
        assertEquals(listOf(setOf("p1")), h.refresher.calls)   // refetch-and-reconcile

        // A second drain is a no-op: nothing re-executes.
        assertEquals(DrainResult.Idle, h.engine.drain())
        assertEquals(1, h.executor.executed.size)
    }

    @Test
    fun `drain is FIFO one op at a time`() = runBlocking {
        val h = Harness()
        val first = h.enqueueTx("p1")
        val second = h.enqueueTx("p2")

        h.engine.drain()

        assertEquals(listOf(first.clientId, second.clientId), h.executor.executed)
    }

    @Test
    fun `no session means idle drain and nothing executed`() = runBlocking {
        val h = Harness(loggedIn = false)
        h.enqueueTx()

        assertEquals(DrainResult.Idle, h.engine.drain())
        assertTrue(h.executor.executed.isEmpty())
        assertEquals(OpStatus.PENDING, h.store.ops.single().status)
    }

    // ── Rejections (needs-attention) ─────────────────────────────────────────

    @Test
    fun `server rejection lands in needs-attention with message and never blocks the queue`() =
        runBlocking {
            val h = Harness()
            val bad = h.enqueueTx("p1")
            val good = h.enqueueTx("p2")
            h.executor.execScript = { op ->
                if (op.clientId == bad.clientId) {
                    ExecResult.Rejected("Insufficient cash balance.")
                } else {
                    ExecResult.Success(null)
                }
            }

            val result = h.engine.drain()

            assertEquals(DrainResult.Drained(1), result)
            val rejected = h.store.byClient(bad.clientId)!!
            assertEquals(OpStatus.NEEDS_ATTENTION, rejected.status)
            assertEquals("Insufficient cash balance.", rejected.serverError)
            // The op behind it still drained.
            assertEquals(OpStatus.DONE, h.store.byClient(good.clientId)!!.status)
        }

    @Test
    fun `unsupported op parks as needs-attention and queue continues`() = runBlocking {
        val h = Harness()
        runBlocking { h.engine.enqueue(OpType.CASH_TRANSFER, "p1", "{}") }
        val good = h.enqueueTx("p1")
        h.executor.execScript = { op ->
            if (op.type == OpType.CASH_TRANSFER) {
                ExecResult.Unsupported("Not supported yet.")
            } else {
                ExecResult.Success(null)
            }
        }

        h.engine.drain()

        assertEquals(
            OpStatus.NEEDS_ATTENTION,
            h.store.ops.first { it.type == OpType.CASH_TRANSFER }.status,
        )
        assertEquals(OpStatus.DONE, h.store.byClient(good.clientId)!!.status)
    }

    @Test
    fun `retry moves needs-attention back to pending and discard deletes`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Rejected("nope") }
        h.engine.drain()
        assertEquals(OpStatus.NEEDS_ATTENTION, h.store.byClient(op.clientId)!!.status)

        h.engine.retryOp(op.id)
        val retried = h.store.byClient(op.clientId)!!
        assertEquals(OpStatus.PENDING, retried.status)
        assertEquals(0, retried.attemptCount)
        assertNull(retried.serverError)

        h.engine.discardOp(op.id)
        assertNull(h.store.byClient(op.clientId))
    }

    // ── Backoff ─────────────────────────────────────────────────────────────

    @Test
    fun `retryable failure backs off exponentially and blocks the fifo head`() = runBlocking {
        val h = Harness()
        val head = h.enqueueTx("p1")
        h.enqueueTx("p2")
        h.executor.execScript = { ExecResult.RetryableNotApplied("HTTP 429") }

        val r1 = h.engine.drain()
        val afterFirst = h.store.byClient(head.clientId)!!
        assertEquals(OpStatus.PENDING, afterFirst.status)
        assertEquals(1, afterFirst.attemptCount)
        assertEquals(DrainResult.RetryAt(h.nowMs + backoffDelayMs(1)), r1)
        // FIFO: the second op must NOT have been attempted.
        assertEquals(1, h.executor.executed.size)

        // Still gated → drain refuses to hit the network.
        val r2 = h.engine.drain()
        assertEquals(DrainResult.RetryAt(afterFirst.nextAttemptAtMs), r2)
        assertEquals(1, h.executor.executed.size)

        // Clock passes the gate → retried, attempt count grows, delay doubles.
        h.nowMs = afterFirst.nextAttemptAtMs + 1
        h.engine.drain()
        val afterSecond = h.store.byClient(head.clientId)!!
        assertEquals(2, afterSecond.attemptCount)
        assertEquals(h.nowMs + backoffDelayMs(2), afterSecond.nextAttemptAtMs)
    }

    @Test
    fun `manual drain resets backoff gates and retries immediately`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.RetryableNotApplied("HTTP 429") }
        h.engine.drain()
        assertTrue(h.store.byClient(op.clientId)!!.nextAttemptAtMs > h.nowMs)

        h.executor.execScript = { ExecResult.Success(null) }
        val result = h.engine.drain(manual = true)

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
    }

    @Test
    fun `backoff delay doubles from 10s and caps at 30min`() {
        assertEquals(0L, backoffDelayMs(0))
        assertEquals(10_000L, backoffDelayMs(1))
        assertEquals(20_000L, backoffDelayMs(2))
        assertEquals(40_000L, backoffDelayMs(3))
        assertEquals(BACKOFF_CAP_MS, backoffDelayMs(12))
        assertEquals(BACKOFF_CAP_MS, backoffDelayMs(100))
    }

    // ── Ambiguous outcomes & crash-mid-drain (exactly-once) ─────────────────

    @Test
    fun `transport failure leaves op in flight and reports offline`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }

        val result = h.engine.drain()

        assertEquals(DrainResult.Offline, result)
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)
    }

    @Test
    fun `ambiguous send that actually landed completes via reconcile without resend`() =
        runBlocking {
            val h = Harness()
            val op = h.enqueueTx()
            h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }
            h.engine.drain() // send attempt #1 — outcome unknown

            // Connectivity back; the server HAS the op (marker found).
            h.executor.lookupScript = { LookupResult.Found("""{"transactionIds":["s1"]}""") }
            val result = h.engine.drain()

            assertEquals(DrainResult.Drained(1), result)
            val done = h.store.byClient(op.clientId)!!
            assertEquals(OpStatus.DONE, done.status)
            assertNotNull(done.serverResultJson)
            // CRITICAL: only the ONE original send — no double-submit.
            assertEquals(1, h.executor.executed.size)
            assertEquals(1, h.executor.lookedUp.size)
        }

    @Test
    fun `ambiguous send that did not land is resent exactly once after reconcile`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }
        h.engine.drain()

        h.executor.lookupScript = { LookupResult.NotFound }
        h.executor.execScript = { ExecResult.Success(null) }
        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
        assertEquals(2, h.executor.executed.size) // original attempt + one resend
    }

    @Test
    fun `op stuck in flight from a crash is reconciled by a fresh engine`() = runBlocking {
        val store = FakeOpStore()
        val executor = FakeExecutor()
        // Simulate a crash mid-drain: the row was persisted as IN_FLIGHT and the
        // process died before any outcome arrived.
        val crashed = store.append("c1", OpType.TX_BUY, "p1", "{}", "user-1", 1L)
        store.markInFlight(crashed.id, 2L)

        // App restarts with a brand-new engine.
        val h = Harness(store = store, executor = executor)
        executor.lookupScript = { LookupResult.Found(null) }
        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, store.byClient("c1")!!.status)
        assertTrue(executor.executed.isEmpty()) // reconciled, never re-sent
    }

    @Test
    fun `reconcile is impossible while unreachable so nothing is resent`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }
        h.engine.drain()

        h.executor.lookupScript = { LookupResult.Unreachable }
        val result = h.engine.drain()

        assertEquals(DrainResult.Offline, result)
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)
        assertEquals(1, h.executor.executed.size) // still only the original send
    }

    @Test
    fun `server 5xx backs off in flight and reconciles on the next pass`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Ambiguous(reachable = true) }

        val r1 = h.engine.drain()
        val inFlight = h.store.byClient(op.clientId)!!
        assertEquals(OpStatus.IN_FLIGHT, inFlight.status)
        assertEquals(1, inFlight.attemptCount)
        assertEquals(DrainResult.RetryAt(h.nowMs + backoffDelayMs(1)), r1)

        // After the gate: lookup says it never landed → pending → resent OK.
        h.nowMs = inFlight.nextAttemptAtMs + 1
        h.executor.lookupScript = { LookupResult.NotFound }
        h.executor.execScript = { ExecResult.Success(null) }
        assertEquals(DrainResult.Drained(1), h.engine.drain())
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
    }

    // ── Auth ────────────────────────────────────────────────────────────────

    @Test
    fun `auth failure stops the drain with the op back in pending`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.AuthFailure }

        val result = h.engine.drain()

        assertEquals(DrainResult.Idle, result)
        // §7.3: an expired session never loses queued entries.
        assertEquals(OpStatus.PENDING, h.store.byClient(op.clientId)!!.status)
    }

    // ── Queue edits (Step 8, §7.2/§7.3 edit-and-retry) ──────────────────────

    @Test
    fun `updateOp on a pending op swaps payload in place and keeps the client id`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()

        val ok = h.engine.updateOp(op.id, """{"assetId":"a","quantity":2.0}""")

        assertTrue(ok)
        val edited = h.store.byClient(op.clientId)!!
        assertEquals(op.clientId, edited.clientId)            // same identity
        assertEquals("""{"assetId":"a","quantity":2.0}""", edited.payloadJson)
        assertEquals(OpStatus.PENDING, edited.status)
        assertEquals(0, edited.attemptCount)
        assertEquals(1, h.store.ops.size)                     // in place, no new op
    }

    @Test
    fun `edit-and-retry resubmits the SAME client uuid after a rejection`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.Rejected("Sell exceeds current holding.") }
        h.engine.drain()
        assertEquals(OpStatus.NEEDS_ATTENTION, h.store.byClient(op.clientId)!!.status)

        // The user fixes the amount in the form → updateOp → drain again.
        val ok = h.engine.updateOp(op.id, """{"assetId":"a","quantity":1.0}""")
        assertTrue(ok)
        h.executor.execScript = { ExecResult.Success(null) }
        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
        // Both sends carried the SAME client UUID (idempotency identity).
        assertEquals(listOf(op.clientId, op.clientId), h.executor.executed)
    }

    @Test
    fun `updateOp refuses in-flight and done ops`() = runBlocking {
        val h = Harness()

        // IN_FLIGHT (ambiguous outcome): editing could double-apply.
        val ambiguous = h.enqueueTx()
        h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }
        h.engine.drain()
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(ambiguous.clientId)!!.status)
        assertEquals(false, h.engine.updateOp(ambiguous.id, """{"x":1}"""))
        assertEquals("""{"assetId":"a"}""", h.store.byClient(ambiguous.clientId)!!.payloadJson)

        // DONE: server truth, immutable through the queue.
        val done = h.enqueueTx()
        h.executor.lookupScript = { LookupResult.Found(null) } // resolve the in-flight head
        h.executor.execScript = { ExecResult.Success(null) }
        h.engine.drain()
        assertEquals(OpStatus.DONE, h.store.byClient(done.clientId)!!.status)
        assertEquals(false, h.engine.updateOp(done.id, """{"x":1}"""))
    }

    @Test
    fun `discard removes a needs-attention op without touching the rest`() = runBlocking {
        val h = Harness()
        val bad = h.enqueueTx("p1")
        val good = h.enqueueTx("p2")
        h.executor.execScript = { op ->
            if (op.clientId == bad.clientId) ExecResult.Rejected("nope") else ExecResult.Success(null)
        }
        h.engine.drain()

        h.engine.discardOp(bad.id)

        assertNull(h.store.byClient(bad.clientId))
        assertEquals(OpStatus.DONE, h.store.byClient(good.clientId)!!.status)
    }

    // ── Idempotency key handling (platform #432, PLATFORM_ASKS #9) ──────────

    @Test
    fun `key is minted at enqueue as a uuid and persisted with the op`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()

        // A canonical RFC-4122 UUID (36 chars, 5 hyphen-separated groups).
        assertTrue(
            "clientId should be a UUID but was '${op.clientId}'",
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                .matches(op.clientId),
        )
        // Persisted verbatim on the stored op — this IS the Idempotency-Key.
        assertEquals(op.clientId, h.store.getById(op.id)!!.clientId)
    }

    @Test
    fun `idempotency key is identical across a retryable-failure retry`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        var call = 0
        h.executor.execScript = {
            if (call++ == 0) ExecResult.RetryableNotApplied("busy") else ExecResult.Success(null)
        }

        h.engine.drain() // attempt 1 → backoff
        h.nowMs = h.store.getById(op.id)!!.nextAttemptAtMs + 1
        h.engine.drain() // attempt 2 → success

        assertEquals(OpStatus.DONE, h.store.getById(op.id)!!.status)
        // Both sends carried the SAME persisted key so the server dedupes them.
        assertEquals(listOf(op.clientId, op.clientId), h.executor.executed)
    }

    @Test
    fun `invalid idempotency key is regenerated once and the retry succeeds`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        val originalKey = op.clientId
        var call = 0
        h.executor.execScript = {
            if (call++ == 0) ExecResult.InvalidKey else ExecResult.Success(null)
        }

        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        val done = h.store.getById(op.id)!!
        assertEquals(OpStatus.DONE, done.status)
        assertTrue("key should have been regenerated", done.clientId != originalKey)
        assertEquals(1, h.store.regenCount)          // exactly one regeneration
        assertEquals(2, h.executor.executed.size)    // rejected original + retry
    }

    @Test
    fun `invalid idempotency key twice lands in needs-attention`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.executor.execScript = { ExecResult.InvalidKey }

        val result = h.engine.drain()

        assertEquals(DrainResult.Idle, result)       // nothing completed
        assertEquals(OpStatus.NEEDS_ATTENTION, h.store.getById(op.id)!!.status)
        assertEquals(1, h.store.regenCount)          // regenerated ONCE, not looped
        assertEquals(2, h.executor.executed.size)    // original + single retry
    }
}
