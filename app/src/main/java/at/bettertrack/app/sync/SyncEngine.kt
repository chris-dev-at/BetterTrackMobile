package at.bettertrack.app.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Executes one op against the API and answers reconcile lookups. */
interface OpExecutor {
    suspend fun execute(op: SyncOp): ExecResult

    /**
     * Ask the server whether [op] (whose last send was ambiguous) actually
     * landed — via the `[bt:<clientId>]` note marker or, for value points, the
     * (date, value) pair. Must be side-effect free.
     */
    suspend fun lookup(op: SyncOp): LookupResult
}

/** Refetch-and-reconcile hook (§7.3): server truth replaces local projections. */
interface PostSyncRefresher {
    /** Called after a drain pass that completed ops for these portfolios. */
    suspend fun afterDrain(portfolioIds: Set<String>)
}

/**
 * The outbound-queue state machine (spec §7.3). Pure Kotlin — persistence,
 * network and clock are injected — so every transition is unit-testable.
 *
 * Drain contract:
 *  - strict FIFO, one op at a time; a backing-off head BLOCKS the queue
 *    (ledger events must apply in order), but needs-attention ops never do;
 *  - an op is marked in-flight BEFORE its send; any ambiguous outcome (crash,
 *    lost response, 5xx) leaves it in-flight, and the next drain RECONCILES it
 *    against the server before any resend — this is what makes a crash
 *    mid-drain unable to double-submit;
 *  - business rejections (4xx) become needs-attention with the server's
 *    message and the drain moves on;
 *  - auth failures stop the drain with ops left pending: an expired session
 *    never loses queued entries — the user re-authenticates and drain resumes.
 */
class SyncEngine(
    private val store: OpStore,
    private val executor: OpExecutor,
    private val refresher: PostSyncRefresher,
    /** Session gate — drains are a no-op when logged out. */
    private val hasSession: () -> Boolean,
    /** Owner key stamped on enqueued ops (defense-in-depth; DB is single-owner). */
    private val ownerKey: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Hard cap on a single online attempt — the send AND its reconcile lookup.
     * OkHttp's connect (20 s) / read (30 s) timeouts and lack of a call timeout
     * let a server that accepts the socket but never replies hold an attempt far
     * beyond this; the cap guarantees a queued op can't sit in-flight forever.
     * Injected (default [ATTEMPT_TIMEOUT_MS]) so tests drive it under virtual time.
     */
    private val attemptTimeoutMs: Long = ATTEMPT_TIMEOUT_MS,
) {
    private val drainMutex = Mutex()

    /** Append a ledger event to the durable queue (client UUID minted here). */
    suspend fun enqueue(type: OpType, portfolioId: String?, payloadJson: String): SyncOp =
        store.append(
            clientId = UUID.randomUUID().toString(),
            type = type,
            portfolioId = portfolioId,
            payloadJson = payloadJson,
            accountKey = ownerKey(),
            nowMs = now(),
        )

    /** Needs-attention → pending again (fresh attempt counter). */
    suspend fun retryOp(id: Long) {
        val op = store.getById(id) ?: return
        if (op.status == OpStatus.NEEDS_ATTENTION) {
            store.markPending(id, attemptCount = 0, nextAttemptAtMs = 0L, nowMs = now())
        }
    }

    /**
     * Edit a queued op in place (§7.2: editing a not-yet-synced item just
     * mutates the local queue; §7.3 edit-and-retry). The op keeps its client
     * UUID — a needs-attention retry RESUBMITS THE SAME identity — and goes
     * back to pending with a fresh attempt counter.
     *
     * Refused (returns false) for IN_FLIGHT ops: their last send has an
     * UNKNOWN outcome, and rewriting the payload before reconcile could
     * double-apply (old content landed + edited content resent). DONE ops are
     * server truth and equally immutable here.
     */
    suspend fun updateOp(id: Long, payloadJson: String): Boolean {
        val op = store.getById(id) ?: return false
        if (op.status != OpStatus.PENDING && op.status != OpStatus.NEEDS_ATTENTION) return false
        store.markEdited(id, payloadJson, now())
        return true
    }

    /** Drop a queued op entirely (documented §7.4 affordance). */
    suspend fun discardOp(id: Long) {
        val op = store.getById(id) ?: return
        if (op.status != OpStatus.DONE) store.delete(id)
    }

    /**
     * One FIFO drain pass. [manual] (the user's "drain now") clears backoff
     * gates first so everything is immediately eligible. Single-flighted.
     */
    suspend fun drain(manual: Boolean = false): DrainResult = drainMutex.withLock {
        if (!hasSession()) return@withLock DrainResult.Idle

        if (manual) store.resetBackoffGates()

        var completed = 0
        val affected = mutableSetOf<String>()

        val result: DrainResult = run loop@{
            while (true) {
                val op = store.firstOpen() ?: break

                // Backoff gate — the head blocks the whole queue (FIFO ordering).
                if (op.nextAttemptAtMs > now()) return@loop DrainResult.RetryAt(op.nextAttemptAtMs)

                if (op.status == OpStatus.IN_FLIGHT) {
                    // Ambiguous from a crash / lost response: reconcile, never resend
                    // blindly. Bounded by the same cap so a hung lookup (server accepts
                    // the socket, never replies) can't wedge the drain either.
                    when (val looked = withTimeoutOrNull(attemptTimeoutMs) { executor.lookup(op) }) {
                        is LookupResult.Found -> {
                            store.markDone(op.id, looked.serverResultJson, now())
                            op.portfolioId?.let(affected::add)
                            completed++
                        }

                        LookupResult.NotFound ->
                            // Provably absent — safe to resend right away.
                            store.markPending(op.id, op.attemptCount, 0L, now())

                        // Unreachable OR a timed-out reconcile: behave like offline —
                        // wait for a healthy link; the op stays in-flight for a later pass.
                        LookupResult.Unreachable, null -> return@loop DrainResult.Offline
                    }
                    continue
                }

                // PENDING head — attempt the send (carries the op's persisted
                // idempotency key; a replay of a landed op returns a 2xx). Bounded by
                // the hard cap: a server that never replies must not stall the queue.
                store.markInFlight(op.id, now())
                var exec = withTimeoutOrNull(attemptTimeoutMs) { executor.execute(op) }
                if (exec is ExecResult.InvalidKey) {
                    // Should never happen — keys are canonical UUIDs. #9: the server
                    // rejected the key as non-UUID. A non-2xx releases the key, so
                    // mint a fresh one, persist it, and retry ONCE; a repeat is a
                    // permanent failure handled by the InvalidKey branch below.
                    val regenerated = store.regenerateClientId(op.id, now())
                    if (regenerated != null) {
                        exec = withTimeoutOrNull(attemptTimeoutMs) { executor.execute(regenerated) }
                    }
                }
                when (val e = exec) {
                    null -> {
                        // The attempt blew the hard cap while online: cancellation
                        // aborted the Retrofit call. The op is still IN_FLIGHT so a
                        // single bounded reconcile can't double-submit — if it actually
                        // landed, complete it; otherwise park it as timed-out with the
                        // Retry / Remove affordances (Retry re-sends the SAME
                        // idempotency key — safe).
                        when (val looked = withTimeoutOrNull(attemptTimeoutMs) { executor.lookup(op) }) {
                            is LookupResult.Found -> {
                                store.markDone(op.id, looked.serverResultJson, now())
                                op.portfolioId?.let(affected::add)
                                completed++
                            }
                            // NotFound / Unreachable / lookup timeout — surface "timed out".
                            else -> store.markNeedsAttention(op.id, MSG_ATTEMPT_TIMED_OUT, now())
                        }
                    }

                    is ExecResult.Success -> {
                        store.markDone(op.id, e.serverResultJson, now())
                        op.portfolioId?.let(affected::add)
                        completed++
                    }

                    is ExecResult.Rejected -> store.markNeedsAttention(op.id, e.message, now())

                    is ExecResult.Unsupported -> store.markNeedsAttention(op.id, e.message, now())

                    // Still invalid after one regeneration — unrecoverable; surface it.
                    ExecResult.InvalidKey -> store.markNeedsAttention(op.id, MSG_KEY_INVALID, now())

                    is ExecResult.RetryableNotApplied -> {
                        val attempts = op.attemptCount + 1
                        val at = now() + backoffDelayMs(attempts)
                        store.markPending(op.id, attempts, at, now())
                        return@loop DrainResult.RetryAt(at)
                    }

                    is ExecResult.Ambiguous -> {
                        val attempts = op.attemptCount + 1
                        return@loop if (e.reachable) {
                            // Server reached but outcome unclear (5xx): back off,
                            // stay in-flight so the next pass reconciles first.
                            val at = now() + backoffDelayMs(attempts)
                            store.markInFlightAttempt(op.id, attempts, at, now())
                            DrainResult.RetryAt(at)
                        } else {
                            // Transport failure: wait for connectivity; no gate so
                            // the reconnect drain reconciles immediately.
                            store.markInFlightAttempt(op.id, attempts, 0L, now())
                            DrainResult.Offline
                        }
                    }

                    ExecResult.AuthFailure -> {
                        // Request was refused before processing — revert to pending;
                        // the queue survives re-login of the same account (§7.3).
                        store.markPending(op.id, op.attemptCount, op.nextAttemptAtMs, now())
                        return@loop DrainResult.Idle
                    }
                }
            }
            if (completed > 0) DrainResult.Drained(completed) else DrainResult.Idle
        }

        // Refetch-and-reconcile: server truth replaces local projections (§7.3).
        if (affected.isNotEmpty()) refresher.afterDrain(affected)
        store.pruneDone(KEEP_DONE_ROWS)
        result
    }

    companion object {
        /** Done rows kept for the debug/pending screens before pruning. */
        const val KEEP_DONE_ROWS = 25

        /** Shown when the server rejects the op's idempotency key twice (#432). */
        const val MSG_KEY_INVALID =
            "This change couldn't be submitted (its sync key was rejected). Discard it and re-create it."

        /**
         * Hard per-attempt cap (send AND reconcile lookup): an attempt only tries
         * for 10 s while online; a server that accepts the connection but never
         * replies is aborted so the op can't sit in-flight forever.
         */
        const val ATTEMPT_TIMEOUT_MS = 10_000L

        /**
         * Client-generated park message (same raw-`serverError` pattern as
         * [MSG_KEY_INVALID]; the pending-sync UIs render `serverError` verbatim,
         * with no sentinel→resource mapping) for an op whose online attempt blew
         * [ATTEMPT_TIMEOUT_MS] and whose reconcile couldn't prove it landed.
         */
        const val MSG_ATTEMPT_TIMED_OUT =
            "This change timed out (the server didn't respond within 10 seconds). Retry, or remove it."
    }
}
