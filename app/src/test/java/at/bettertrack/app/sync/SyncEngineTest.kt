package at.bettertrack.app.sync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit tests of the queue state machine (spec §7.3): enqueue →
 * in-flight → done/needs-attention, FIFO ordering, backoff, ambiguous-outcome
 * RECONCILE-BY-REPLAY (crash mid-drain must never double-submit), the replay
 * safety window, session gating.
 */
class SyncEngineTest {

    private val HOUR_MS = 60L * 60 * 1000

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
                firstAttemptAtMs = 0L,
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

        override suspend fun markInFlight(id: Long, nowMs: Long) = update(id) {
            // Stamp the streak start on ENTRY: a fresh (non-in-flight) op or an
            // unstamped one starts its clock now; an op already mid-streak keeps it.
            val streakStart =
                if (it.status != OpStatus.IN_FLIGHT || it.firstAttemptAtMs == 0L) nowMs
                else it.firstAttemptAtMs
            it.copy(
                status = OpStatus.IN_FLIGHT,
                firstAttemptAtMs = streakStart,
                serverError = null,
                updatedAtMs = nowMs,
            )
        }

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

        /** Force an op into an IN_FLIGHT streak that started [ageMs] ago (test seed). */
        fun seedInFlightSince(clientId: String, streakStartMs: Long) {
            val i = ops.indexOfFirst { it.clientId == clientId }
            if (i >= 0) ops[i] = ops[i].copy(status = OpStatus.IN_FLIGHT, firstAttemptAtMs = streakStartMs)
        }
    }

    /**
     * Scripted executor that ALSO models the platform's idempotent server: a
     * successful application records the op's key in [serverApplied], and a later
     * `execute` of an already-applied key returns the stored 2xx (a replay) — so
     * `serverApplied.size` proves exactly-once regardless of how often the client
     * re-sends. [execScript] drives the outcome of a FIRST (fresh) application.
     */
    private class FakeExecutor : OpExecutor {
        val executed = mutableListOf<String>()        // every client send/replay
        val serverApplied = mutableListOf<String>()   // keys the "server" applied
        var execScript: (SyncOp) -> ExecResult = { ExecResult.Success(null) }

        /**
         * The send reaches the server (which APPLIES the op) but the client loses
         * the response, seeing this ambiguous/failure outcome. The key is recorded
         * as applied, so the reconcile replay returns the stored 2xx.
         */
        var landsSilentlyFor: (SyncOp) -> ExecResult? = { null }

        /** Simulate a server that accepts the socket but never replies. */
        var execHangsFor: (SyncOp) -> Boolean = { false }

        override suspend fun execute(op: SyncOp): ExecResult {
            executed += op.clientId
            if (execHangsFor(op)) awaitCancellation()
            if (op.clientId in serverApplied) return ExecResult.Success(REPLAY_RESULT)
            landsSilentlyFor(op)?.let {
                serverApplied += op.clientId
                return it
            }
            val r = execScript(op)
            if (r is ExecResult.Success) serverApplied += op.clientId
            return r
        }

        companion object {
            const val REPLAY_RESULT = """{"replayed":true}"""
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

    // ── Ambiguous outcomes & crash-mid-drain (reconcile-by-replay) ──────────

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
    fun `an ambiguous send that landed silently is completed by a replay exactly once`() =
        runBlocking {
            val h = Harness()
            val op = h.enqueueTx()
            // Attempt #1 reaches the server (applies) but the client sees offline.
            h.executor.landsSilentlyFor = { ExecResult.Ambiguous(reachable = false) }
            h.engine.drain()
            assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)

            // Connectivity back; the reconcile REPLAY returns the stored 2xx.
            val result = h.engine.drain()

            assertEquals(DrainResult.Drained(1), result)
            val done = h.store.byClient(op.clientId)!!
            assertEquals(OpStatus.DONE, done.status)
            assertNotNull(done.serverResultJson)
            // The replay carried the SAME idempotency key; the server applied ONCE.
            assertEquals(listOf(op.clientId, op.clientId), h.executor.executed)
            assertEquals(1, h.executor.serverApplied.size)
        }

    @Test
    fun `an ambiguous send that never reached the server is applied by the replay`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        var call = 0
        h.executor.execScript = {
            if (call++ == 0) ExecResult.Ambiguous(reachable = false) // never reached the server
            else ExecResult.Success(null)                            // replay applies it
        }
        h.engine.drain()
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)

        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
        assertEquals(2, h.executor.executed.size)        // original attempt + one replay
        assertEquals(1, h.executor.serverApplied.size)   // applied exactly once (on the replay)
    }

    @Test
    fun `an op left in-flight by a crash is reconciled by replay in a fresh engine`() = runBlocking {
        val store = FakeOpStore()
        val executor = FakeExecutor()
        // Crash mid-drain: the row persisted IN_FLIGHT (streak stamped) and the
        // send had actually landed server-side before the process died.
        val crashed = store.append("c1", OpType.TX_BUY, "p1", "{}", "user-1", 1L)
        store.markInFlight(crashed.id, 2L)
        executor.serverApplied += "c1"

        // App restarts with a brand-new engine.
        val h = Harness(store = store, executor = executor)
        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, store.byClient("c1")!!.status)
        assertEquals(1, executor.executed.size)          // one replay
        assertEquals(1, executor.serverApplied.size)     // still applied exactly once
    }

    @Test
    fun `an in-flight replay that stays offline keeps the op in-flight without applying`() =
        runBlocking {
            val h = Harness()
            val op = h.enqueueTx()
            h.executor.execScript = { ExecResult.Ambiguous(reachable = false) }
            h.engine.drain()
            val result = h.engine.drain()

            assertEquals(DrainResult.Offline, result)
            assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)
            assertEquals(2, h.executor.executed.size)        // each pass attempts a replay
            assertTrue(h.executor.serverApplied.isEmpty())   // nothing applied — exactly-once holds
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

        // After the gate: the replay succeeds → done.
        h.nowMs = inFlight.nextAttemptAtMs + 1
        h.executor.execScript = { ExecResult.Success(null) }
        assertEquals(DrainResult.Drained(1), h.engine.drain())
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
    }

    // ── Replay safety window (server dedupe TTL) ────────────────────────────

    @Test
    fun `an in-flight op past the replay window parks as needs-attention`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        // In-flight since 41 h ago — beyond REPLAY_SAFE_WINDOW_MS (40 h).
        h.store.seedInFlightSince(op.clientId, h.nowMs - 41 * HOUR_MS)

        val result = h.engine.drain()

        assertEquals(DrainResult.Idle, result)             // nothing completed
        val parked = h.store.byClient(op.clientId)!!
        assertEquals(OpStatus.NEEDS_ATTENTION, parked.status)
        assertEquals(SyncEngine.MSG_REPLAY_WINDOW_EXPIRED, parked.serverError)
        assertTrue(h.executor.executed.isEmpty())          // never blind-replayed
    }

    @Test
    fun `an in-flight op within the replay window is replayed and completes`() = runBlocking {
        val h = Harness()
        val op = h.enqueueTx()
        h.store.seedInFlightSince(op.clientId, h.nowMs - 39 * HOUR_MS)  // still inside 40 h
        h.executor.execScript = { ExecResult.Success("""{"transactionIds":["s1"]}""") }

        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
        assertEquals(listOf(op.clientId), h.executor.executed) // exactly one replay
    }

    @Test
    fun `a replay-window-parked op steps aside for a later op`() = runBlocking {
        val h = Harness()
        val stale = h.enqueueTx("p1")
        val later = h.enqueueTx("p2")
        h.store.seedInFlightSince(stale.clientId, h.nowMs - 41 * HOUR_MS)

        val result = h.engine.drain()

        assertEquals(DrainResult.Drained(1), result)
        assertEquals(OpStatus.NEEDS_ATTENTION, h.store.byClient(stale.clientId)!!.status)
        assertEquals(OpStatus.DONE, h.store.byClient(later.clientId)!!.status)
    }

    @Test
    fun `retry of a replay-window-parked op sends it back to pending with the same key`() =
        runBlocking {
            val h = Harness()
            val op = h.enqueueTx()
            h.store.seedInFlightSince(op.clientId, h.nowMs - 41 * HOUR_MS)
            h.engine.drain()
            assertEquals(OpStatus.NEEDS_ATTENTION, h.store.byClient(op.clientId)!!.status)

            h.engine.retryOp(op.id)

            val retried = h.store.byClient(op.clientId)!!
            assertEquals(OpStatus.PENDING, retried.status)   // Retry re-sends the SAME key
            assertEquals(op.clientId, retried.clientId)
            assertEquals(0, retried.attemptCount)
            assertNull(retried.serverError)
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

        // Resolve the in-flight head via a successful replay.
        h.executor.execScript = { ExecResult.Success(null) }
        h.engine.drain()
        assertEquals(OpStatus.DONE, h.store.byClient(ambiguous.clientId)!!.status)

        // DONE: server truth, immutable through the queue.
        val done = h.enqueueTx()
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

    // ── 10 s hard attempt timeout (server accepts the socket, never replies) ──

    @Test
    fun `a timed-out send stays in-flight and backs off rather than parking`() = runTest {
        val h = Harness()
        val op = h.engine.enqueue(OpType.TX_BUY, "p1", """{"assetId":"a"}""")
        h.executor.execHangsFor = { true }

        val result = h.engine.drain()

        assertTrue("expected RetryAt, was $result", result is DrainResult.RetryAt)
        val after = h.store.byClient(op.clientId)!!
        assertEquals(OpStatus.IN_FLIGHT, after.status)   // NOT parked as needs-attention
        assertNull(after.serverError)
        assertEquals(1, after.attemptCount)
    }

    @Test
    fun `a hung send times out then a later replay completes it`() = runTest {
        val h = Harness()
        val op = h.engine.enqueue(OpType.TX_BUY, "p1", """{"assetId":"a"}""")
        var firstAttempt = true
        h.executor.execHangsFor = { firstAttempt }
        h.executor.execScript = { ExecResult.Success("""{"transactionIds":["s1"]}""") }

        val r1 = h.engine.drain()                    // times out → in-flight + backoff
        firstAttempt = false
        assertTrue(r1 is DrainResult.RetryAt)
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(op.clientId)!!.status)

        h.nowMs = h.store.byClient(op.clientId)!!.nextAttemptAtMs + 1
        val r2 = h.engine.drain()                    // replay succeeds
        assertEquals(DrainResult.Drained(1), r2)
        assertEquals(OpStatus.DONE, h.store.byClient(op.clientId)!!.status)
    }

    @Test
    fun `a hung head blocks the queue then both drain once it replays`() = runTest {
        val h = Harness()
        val head = h.engine.enqueue(OpType.TX_BUY, "p1", """{"assetId":"a"}""")
        val later = h.engine.enqueue(OpType.TX_BUY, "p2", """{"assetId":"b"}""")
        var headHangs = true
        h.executor.execHangsFor = { headHangs && it.clientId == head.clientId }
        h.executor.execScript = { ExecResult.Success(null) }

        val r1 = h.engine.drain()                    // head times out, blocks FIFO
        assertTrue(r1 is DrainResult.RetryAt)
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(head.clientId)!!.status)
        assertEquals(OpStatus.PENDING, h.store.byClient(later.clientId)!!.status)  // blocked
        assertEquals(1, h.executor.executed.size)    // only the head was attempted

        headHangs = false
        h.nowMs = h.store.byClient(head.clientId)!!.nextAttemptAtMs + 1
        val r2 = h.engine.drain()                    // head replays, then later drains
        assertEquals(DrainResult.Drained(2), r2)
        assertEquals(OpStatus.DONE, h.store.byClient(head.clientId)!!.status)
        assertEquals(OpStatus.DONE, h.store.byClient(later.clientId)!!.status)
    }

    @Test
    fun `a hung replay of a crash-ambiguous op parks as timed out instead of wedging`() = runTest {
        val store = FakeOpStore()
        val executor = FakeExecutor()
        val crashed = store.append("c1", OpType.TX_BUY, "p1", "{}", "user-1", 1L)
        store.markInFlight(crashed.id, 2L)           // ambiguous from a crash
        val h = Harness(store = store, executor = executor)
        executor.execHangsFor = { true }             // the reconcile replay hangs too

        val result = h.engine.drain()

        // IN_FLIGHT pickup + hard-cap hit = second strike → park, don't hold the head.
        assertEquals(DrainResult.Idle, result)
        val parked = store.byClient("c1")!!
        assertEquals(OpStatus.NEEDS_ATTENTION, parked.status)
        assertEquals(SyncEngine.MSG_ATTEMPT_TIMED_OUT, parked.serverError)
        assertEquals(1, executor.executed.size)      // the one (timed-out) replay
        assertTrue(executor.serverApplied.isEmpty()) // nothing applied
    }

    @Test
    fun `two consecutive timeouts park the op as timed out and the queue flows`() = runTest {
        val h = Harness()
        val head = h.engine.enqueue(OpType.TX_BUY, "p1", """{"assetId":"a"}""")
        val later = h.engine.enqueue(OpType.TX_BUY, "p2", """{"assetId":"b"}""")
        h.executor.execHangsFor = { it.clientId == head.clientId }  // head hangs forever
        h.executor.execScript = { ExecResult.Success(null) }

        val r1 = h.engine.drain()                    // fresh attempt times out → one free replay
        assertTrue("expected RetryAt, was $r1", r1 is DrainResult.RetryAt)
        assertEquals(OpStatus.IN_FLIGHT, h.store.byClient(head.clientId)!!.status)
        assertEquals(OpStatus.PENDING, h.store.byClient(later.clientId)!!.status)

        h.nowMs = h.store.byClient(head.clientId)!!.nextAttemptAtMs + 1
        val r2 = h.engine.drain()                    // replay times out → park; successor drains
        assertEquals(DrainResult.Drained(1), r2)
        val parked = h.store.byClient(head.clientId)!!
        assertEquals(OpStatus.NEEDS_ATTENTION, parked.status)
        assertEquals(SyncEngine.MSG_ATTEMPT_TIMED_OUT, parked.serverError)
        assertEquals(OpStatus.DONE, h.store.byClient(later.clientId)!!.status)
    }

    @Test
    fun `retry of a timeout-parked op replays the same key and can complete`() = runTest {
        val h = Harness()
        val op = h.engine.enqueue(OpType.TX_BUY, "p1", """{"assetId":"a"}""")
        var hangs = true
        h.executor.execHangsFor = { hangs }
        h.executor.execScript = { ExecResult.Success("""{"transactionIds":["s1"]}""") }

        h.engine.drain()                             // strike one → in-flight backoff
        h.nowMs = h.store.byClient(op.clientId)!!.nextAttemptAtMs + 1
        h.engine.drain()                             // strike two → parked
        assertEquals(OpStatus.NEEDS_ATTENTION, h.store.byClient(op.clientId)!!.status)

        hangs = false                                // server recovers
        h.engine.retryOp(h.store.byClient(op.clientId)!!.id)
        val r = h.engine.drain()
        assertEquals(DrainResult.Drained(1), r)
        val done = h.store.byClient(op.clientId)!!
        assertEquals(OpStatus.DONE, done.status)
        assertEquals(op.clientId, done.clientId)     // SAME idempotency key end to end
    }
}
