package at.bettertrack.app.sync

import kotlinx.serialization.Serializable

/**
 * Core models of the outbound sync queue (spec §7.2/§7.3). Everything in this
 * file is pure Kotlin — no Android/Room dependencies — so the queue state
 * machine is unit-testable with plain JUnit.
 */

/** The §7.2 offline-writable ledger-event set. */
enum class OpType(val wire: String) {
    TX_BUY("tx_buy"),
    TX_SELL("tx_sell"),
    CASH_DEPOSIT("cash_deposit"),
    CASH_WITHDRAW("cash_withdraw"),
    /** Atomic source-to-source transfer (real endpoint since Step 9). */
    CASH_TRANSFER("cash_transfer"),
    CUSTOM_ASSET_VALUE_POINT("custom_asset_value_point"),
    ;

    companion object {
        fun fromWire(wire: String): OpType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Queue lifecycle states (§7.3). */
enum class OpStatus(val wire: String) {
    /** Waiting its FIFO turn (or backing off after a retryable failure). */
    PENDING("pending"),
    /**
     * A send attempt started and its outcome is UNKNOWN (crash mid-drain, lost
     * response, 5xx). Never re-sent blindly — the next drain first reconciles
     * against the server to prove whether it landed (exactly-once).
     */
    IN_FLIGHT("in_flight"),
    /** Server rejected it (business 4xx) — keeps the server's reason, never blocks the queue. */
    NEEDS_ATTENTION("needs_attention"),
    /** Proven landed; kept briefly for the debug/pending UI, then pruned. */
    DONE("done"),
    ;

    companion object {
        fun fromWire(wire: String): OpStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** A queued operation (pure mirror of the Room row). */
data class SyncOp(
    /** Monotonic enqueue sequence — the FIFO order. */
    val id: Long,
    /**
     * Client-generated UUID minted at enqueue and persisted for life (§7.3).
     * This IS the server `Idempotency-Key` (platform #432, live on ALL portfolio
     * mutations): every send carries it, so a replayed retry of a landed op
     * returns a byte-identical 2xx and a replay of a never-landed op executes
     * exactly once. It is the SOLE exactly-once mechanism — the legacy
     * `[bt:<uuid>]` note marker is retired. Regenerated only if the server ever
     * rejects it as non-UUID ([ExecResult.InvalidKey]).
     *
     * TTL caveat (#9): the server stores key→response for ≥48 h per user, then
     * the replay guarantee lapses. Reconcile replays an ambiguous op only while
     * it is younger than [SyncEngine.REPLAY_SAFE_WINDOW_MS] (a conservative 40 h
     * measured by [firstAttemptAtMs]); past that it parks NEEDS_ATTENTION rather
     * than blind-replay a possibly-already-applied op past its dedupe window.
     */
    val clientId: String,
    val type: OpType,
    val portfolioId: String?,
    val payloadJson: String,
    val status: OpStatus,
    val attemptCount: Int,
    val nextAttemptAtMs: Long,
    val serverError: String?,
    val serverResultJson: String?,
    val accountKey: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /**
     * Wall-clock ms the op began its CURRENT in-flight streak (0 = never sent /
     * not currently in-flight). Set when a non-in-flight op transitions to
     * IN_FLIGHT, preserved across ambiguous replays, and implicitly reset when
     * the op leaves IN_FLIGHT (a provably-not-applied 408/429/auth, a queue
     * edit, or a Retry). Bounds the replay-reconcile window (see [clientId]).
     */
    val firstAttemptAtMs: Long = 0L,
)

// ── Op payloads (kotlinx-serialized into SyncOp.payloadJson) ─────────────────

/** Buy/sell ledger event (§6.2). Field names mirror the API request contract. */
@Serializable
data class TxOpPayload(
    val assetId: String,
    /** "buy" | "sell" — must agree with the op's [OpType]. */
    val side: String,
    val quantity: Double,
    val price: Double,
    val fee: Double = 0.0,
    /** ISO timestamp chosen at enqueue time. */
    val executedAt: String,
    val note: String? = null,
    val payFromCash: Boolean? = null,
    val addProceedsToCash: Boolean? = null,
    /**
     * Backdated pay-from-cash settlement (platform #378). On a `payFromCash` BUY
     * whose cash was short AS OF the (backdated) `executedAt`, the server keeps the
     * stock trade on its past date but dates the linked cash-withdrawal leg TODAY —
     * so a buy affordable now is no longer falsely rejected. Ignored server-side
     * when the cash already sufficed at the buy date; harmless on non-backdated /
     * sell ops. Sent for every pay-from-cash buy.
     */
    val settleCashAsOfToday: Boolean? = null,
    /**
     * Uncovered (over-)sell (platform PR #429, Step 19). A SELL whose quantity
     * exceeds the held amount (incl. zero holding) is rejected `400 OVERSELL`
     * UNLESS this flag is `true`; then the position closes at exactly 0 (no
     * shorts) and full proceeds go to cash. Sent only when the user ticked the
     * "sell anyway" acknowledgment. Persisted in the queued mutation so an
     * offline uncovered sell stays accepted when it finally drains.
     */
    val allowUncovered: Boolean? = null,
    /**
     * Optional cost basis for the uncovered part (native per-unit). `null`/omitted
     * ⇒ the server uses the sale price as basis (0 % realized on the uncovered
     * part); set it for an accurate realized figure. Ignored unless
     * [allowUncovered] is true.
     */
    val uncoveredEntryPrice: Double? = null,
    // Display-only snapshot of the asset identity (Step 8 pending rows render
    // instantly from the queue, §7.4). NEVER sent to the API — the executor
    // maps payload → request field-by-field.
    val assetSymbol: String? = null,
    val assetName: String? = null,
    /** Native currency code (e.g. "USD") for pending-row / edit price labels. */
    val assetCurrency: String? = null,
)

/** Cash deposit / withdrawal (§6.3). */
@Serializable
data class CashOpPayload(
    val amountEur: Double,
    val executedAt: String? = null,
    val note: String? = null,
    /** Target source; only the synthetic main source exists until §6.3 ships. */
    val sourceId: String? = null,
)

/** Cash transfer between sources — queued shape ready; platform endpoint missing (§6.3 gap). */
@Serializable
data class CashTransferOpPayload(
    val fromSourceId: String,
    val toSourceId: String,
    val amountEur: Double,
    val executedAt: String? = null,
    val note: String? = null,
)

/** Custom-asset value point (§6.4): merged into the point set by date (idempotent). */
@Serializable
data class ValuePointOpPayload(
    val customAssetId: String,
    /** Calendar date `yyyy-MM-dd`. */
    val date: String,
    val value: Double,
)

// ── Drain outcomes ───────────────────────────────────────────────────────────

/** Result of one queue-drain pass. */
sealed interface DrainResult {
    /** Nothing to drain (empty queue / no session / session rejected). */
    data object Idle : DrainResult

    /** Every open op resolved; [completed] ops proven landed this pass. */
    data class Drained(val completed: Int) : DrainResult

    /** Head op is backing off — retry no earlier than [atMs]. */
    data class RetryAt(val atMs: Long) : DrainResult

    /** Network unreachable — wait for connectivity to return. */
    data object Offline : DrainResult
}

/** Outcome of executing one op against the API. */
sealed interface ExecResult {
    /** 2xx — provably applied. */
    data class Success(val serverResultJson: String?) : ExecResult

    /** Business 4xx — provably NOT applied; the server's human-readable reason. */
    data class Rejected(val message: String) : ExecResult

    /** 408/429 — provably not applied, worth retrying with backoff. */
    data class RetryableNotApplied(val message: String) : ExecResult

    /**
     * Outcome unknown: [reachable] = true for a 5xx (server reached, effect
     * unclear), false for a transport failure. The op stays in-flight and the
     * next drain reconciles before any resend.
     */
    data class Ambiguous(val reachable: Boolean) : ExecResult

    /** 401 after the token machinery gave up — drain stops, op stays pending (§7.3). */
    data object AuthFailure : ExecResult

    /** The platform has no endpoint for this op yet — parked as needs-attention. */
    data class Unsupported(val message: String) : ExecResult

    /**
     * 400 `IDEMPOTENCY_KEY_INVALID` (platform #432): the server rejected the
     * `Idempotency-Key` as a non-UUID. Should be impossible — the queue always
     * mints canonical [java.util.UUID]s — so it means a corrupted key. Per #9
     * the engine mints a FRESH key once, persists it, and retries; a repeat is
     * a permanent failure surfaced through needs-attention. A 4xx released the
     * key server-side, so regenerating is safe (no double-apply).
     */
    data object InvalidKey : ExecResult
}

// ── Backoff ─────────────────────────────────────────────────────────────────

/**
 * Exponential backoff (§7.3): 10s · 20s · 40s · … capped at 30 minutes.
 * Deliberately jitter-free — a single-user client has no thundering herd.
 */
fun backoffDelayMs(attemptCount: Int): Long {
    if (attemptCount <= 0) return 0L
    val exponent = (attemptCount - 1).coerceAtMost(12)
    return (BACKOFF_BASE_MS shl exponent).coerceAtMost(BACKOFF_CAP_MS)
}

const val BACKOFF_BASE_MS = 10_000L
const val BACKOFF_CAP_MS = 30L * 60_000L
