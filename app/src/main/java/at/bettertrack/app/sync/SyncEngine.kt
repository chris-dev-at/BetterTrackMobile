package at.bettertrack.app.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Executes one op against the API. There is no separate reconcile lookup:
 * every send carries the op's persisted `Idempotency-Key`, so re-executing an
 * ambiguous op IS the reconcile — a landed op replays its stored 2xx, a
 * never-landed op runs exactly once (platform #432, live on all mutations).
 */
interface OpExecutor {
    suspend fun execute(op: SyncOp): ExecResult
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
 *    lost response, 5xx, 10 s timeout) leaves it in-flight, and the next drain
 *    RECONCILES it by re-sending the SAME idempotency key — a replay of a
 *    landed op returns its stored 2xx, a replay of a never-landed op runs once,
 *    so a crash mid-drain can never double-submit;
 *  - the replay guarantee lives only inside the server's dedupe TTL, so an
 *    ambiguous op whose in-flight streak is older than [REPLAY_SAFE_WINDOW_MS]
 *    is parked NEEDS_ATTENTION instead of blind-replayed;
 *  - a hung server can't wedge the queue: a timed-out fresh attempt earns ONE
 *    automatic replay; when the replay times out too, the op parks
 *    NEEDS_ATTENTION ("timed out", Retry/Remove) and steps aside — worst case
 *    ~10 s + backoff + ~10 s before successors flow again (owner directive
 *    2026-07-15);
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

                if (op.status == OpStatus.IN_FLIGHT &&
                    op.firstAttemptAtMs != 0L &&
                    now() - op.firstAttemptAtMs > REPLAY_SAFE_WINDOW_MS
                ) {
                    // Ambiguous op past the server's dedupe TTL: a blind replay could
                    // double-apply an op that already landed, so step aside and let the
                    // user decide (needs-attention never blocks the queue). Their Retry
                    // re-sends the SAME key — a documented, chosen residual risk.
                    store.markNeedsAttention(op.id, MSG_REPLAY_WINDOW_EXPIRED, now())
                    continue
                }

                // Mark (or re-mark) in-flight BEFORE the send: a PENDING head stamps the
                // streak's firstAttemptAtMs and becomes crash-reconcilable; an IN_FLIGHT
                // head (crash / lost response / 5xx / timeout) keeps its streak start and
                // RECONCILES by replaying the same idempotency key — a landed op returns
                // its stored 2xx, a never-landed op runs exactly once. One bounded attempt:
                // a server that never replies is cancelled at the hard cap (returns null).
                val wasReplay = op.status == OpStatus.IN_FLIGHT
                store.markInFlight(op.id, now())
                when (val e = attempt(op)) {
                    null -> {
                        // Timed out — outcome unknown. A fresh (PENDING-picked) op gets ONE
                        // free automatic replay: stay in-flight and back off, the next pass
                        // re-sends the same key. But when the timed-out attempt WAS already
                        // a replay (two consecutive hard-cap hits on the same streak), the
                        // server is wedged for this op — park it with Retry/Remove (owner
                        // directive 2026-07-15: ~10 s then "timed out", never a stuck
                        // queue) and move on; needs-attention steps aside, so successors
                        // flow. Retry re-sends the SAME key — exactly-once-safe inside
                        // [REPLAY_SAFE_WINDOW_MS].
                        if (wasReplay) {
                            store.markNeedsAttention(op.id, MSG_ATTEMPT_TIMED_OUT, now())
                            continue
                        }
                        val attempts = op.attemptCount + 1
                        val at = now() + backoffDelayMs(attempts)
                        store.markInFlightAttempt(op.id, attempts, at, now())
                        return@loop DrainResult.RetryAt(at)
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
                        // Provably NOT applied (408/429, or IDEMPOTENCY_IN_PROGRESS —
                        // a prior same-key send is still processing): back off as
                        // PENDING. The previous attempt left no effect, so the streak
                        // clock restarts when it next goes in-flight (a longer, honest
                        // ambiguity window than counting from the discarded attempt).
                        val attempts = op.attemptCount + 1
                        val at = now() + backoffDelayMs(attempts)
                        store.markPending(op.id, attempts, at, now())
                        return@loop DrainResult.RetryAt(at)
                    }

                    is ExecResult.Ambiguous -> {
                        val attempts = op.attemptCount + 1
                        return@loop if (e.reachable) {
                            // Server reached but outcome unclear (5xx): back off,
                            // stay in-flight so the next pass replays first.
                            val at = now() + backoffDelayMs(attempts)
                            store.markInFlightAttempt(op.id, attempts, at, now())
                            DrainResult.RetryAt(at)
                        } else {
                            // Transport failure: wait for connectivity; no gate so
                            // the reconnect drain replays immediately.
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

    /**
     * One bounded send of [op]; the reconcile replay of an in-flight op uses this
     * same path (send == reconcile now that the idempotency key makes a resend
     * exactly-once). Returns null when the hard cap [attemptTimeoutMs] fires (a
     * server that accepts the socket but never replies). A single key
     * regeneration handles the should-never-happen non-UUID rejection (#9): a
     * non-2xx released the key server-side, so minting a fresh one and retrying
     * once cannot double-apply; a repeat surfaces as [ExecResult.InvalidKey].
     */
    private suspend fun attempt(op: SyncOp): ExecResult? {
        var exec = withTimeoutOrNull(attemptTimeoutMs) { executor.execute(op) }
        if (exec is ExecResult.InvalidKey) {
            val regenerated = store.regenerateClientId(op.id, now())
            if (regenerated != null) {
                exec = withTimeoutOrNull(attemptTimeoutMs) { executor.execute(regenerated) }
            }
        }
        return exec
    }

    companion object {
        /** Done rows kept for the debug/pending screens before pruning. */
        const val KEEP_DONE_ROWS = 25

        /** Shown when the server rejects the op's idempotency key twice (#432). */
        const val MSG_KEY_INVALID =
            "This change couldn't be submitted (its sync key was rejected). Discard it and re-create it."

        /**
         * Hard per-attempt cap (each send / replay): an attempt only tries for
         * 10 s while online; a server that accepts the connection but never
         * replies is aborted so the op can't sit in-flight forever.
         */
        const val ATTEMPT_TIMEOUT_MS = 10_000L

        /**
         * Conservative replay-reconcile window. The server stores key→response for
         * ≥48 h per user; past that a resend of an ambiguous op is no longer a
         * guaranteed replay and could double-apply. An in-flight op whose streak
         * ([SyncOp.firstAttemptAtMs]) is older than this parks instead of
         * replaying. 40 h leaves generous headroom under the 48 h floor.
         */
        const val REPLAY_SAFE_WINDOW_MS = 40L * 60 * 60 * 1000

        /**
         * Client-generated park message (same raw-`serverError` pattern as
         * [MSG_KEY_INVALID]; the pending-sync UIs render `serverError` verbatim,
         * with no sentinel→resource mapping) for an ambiguous op the drain can no
         * longer safely replay because its in-flight streak crossed
         * [REPLAY_SAFE_WINDOW_MS]. The user's Retry re-sends the SAME key.
         */
        const val MSG_REPLAY_WINDOW_EXPIRED =
            "Couldn't confirm whether this change reached the server. " +
                "Check your transactions before retrying."

        /**
         * Park message when an op's attempt AND its automatic replay both blew
         * [ATTEMPT_TIMEOUT_MS] (the server accepts the socket but never answers
         * this request). Same raw-`serverError` pattern as [MSG_KEY_INVALID].
         * Retry re-sends the SAME idempotency key — safe inside
         * [REPLAY_SAFE_WINDOW_MS].
         */
        const val MSG_ATTEMPT_TIMED_OUT =
            "This change timed out (the server didn't respond). Retry, or remove it."
    }
}
