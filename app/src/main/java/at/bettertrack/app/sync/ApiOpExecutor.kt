package at.bettertrack.app.sync

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.MIRROR_SEAM_CONFLICT_CODES
import at.bettertrack.app.data.api.dto.CashEntryRequest
import at.bettertrack.app.data.api.dto.CashTransferRequest
import at.bettertrack.app.data.api.dto.CreateTransactionRequest
import at.bettertrack.app.data.api.dto.PutValuePointsRequest
import at.bettertrack.app.data.api.dto.ValuePointDto
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.ui.cash.CashKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import retrofit2.Response

/**
 * The op → API mapping layer (§7.2 ledger-event set → module endpoints).
 *
 * Exactly-once via the server idempotency key (platform #432, live on ALL
 * portfolio mutations): every send of a queued mutation carries
 * `Idempotency-Key: <op.clientId>` (a UUID minted + persisted at enqueue), so a
 * replayed retry runs exactly once and returns a byte-identical 2xx. This is the
 * SOLE exactly-once mechanism — [SyncEngine] reconciles an ambiguous op by
 * simply re-executing it here (a landed op replays its stored 2xx, a never-landed
 * op executes once).
 *
 * The legacy ` [bt:<uuid>]` note marker — an interim landing-proof used before
 * the platform accepted an idempotency key — is retired: sends no longer touch
 * the user's note, and there is no reconcile lookup. Display code still strips
 * the marker from legacy rows that carry one (see `PortfolioFormat.displayNote`).
 * Value-point writes were always idempotent (a full-replace PUT of the merged
 * point set) and, like every other op, carry the key.
 */
class ApiOpExecutor(
    private val api: BtApi,
    private val json: Json,
) : OpExecutor {

    override suspend fun execute(op: SyncOp): ExecResult {
        // Presence-only diagnostics — the idempotency-key UUID is not a secret.
        Log.d(TAG, "execute ${op.type.wire} op#${op.id} Idempotency-Key=${op.clientId}")
        return when (op.type) {
            OpType.TX_BUY, OpType.TX_SELL -> executeTransaction(op)
            OpType.CASH_DEPOSIT -> executeCash(op, CashKind.DEPOSIT)
            OpType.CASH_WITHDRAW -> executeCash(op, CashKind.WITHDRAWAL)
            OpType.CASH_FEE -> executeCash(op, CashKind.FEE)
            OpType.CASH_TRANSFER -> executeTransfer(op)
            OpType.CUSTOM_ASSET_VALUE_POINT -> executeValuePoint(op)
            OpType.TX_REBOOK -> executeRebook(op)
        }
    }

    // ── Transactions (buy / sell) ────────────────────────────────────────────

    private suspend fun executeTransaction(op: SyncOp): ExecResult {
        val payload = decode(TxOpPayload.serializer(), op) ?: return malformed(op)
        val expectedSide = if (op.type == OpType.TX_BUY) "buy" else "sell"
        if (payload.side != expectedSide) return malformed(op)
        return runMutation(op, {
            api.createTransaction(
                op.portfolioId ?: return@runMutation null,
                CreateTransactionRequest(
                    assetId = payload.assetId,
                    side = payload.side,
                    quantity = payload.quantity,
                    price = payload.price,
                    fee = payload.fee,
                    executedAt = payload.executedAt,
                    note = payload.note,
                    payFromCash = payload.payFromCash,
                    addProceedsToCash = payload.addProceedsToCash,
                    settleCashAsOfToday = payload.settleCashAsOfToday,
                    allowUncovered = payload.allowUncovered,
                    uncoveredEntryPrice = payload.uncoveredEntryPrice,
                ),
                idempotencyKey = op.clientId,
            )
        }) { body ->
            resultJson("transactionIds", body.transactions.map { it.id })
        }
    }

    // ── Re-book: the cash-linked edit (owner order 2026-08-16) ───────────────

    /**
     * Replace a cash-linked transaction: DELETE the old row, CREATE the edited
     * one with its wallet coupling restored.
     *
     * ## Order, and what happens if it is interrupted
     *
     * Delete first, create second — never the other way round. The reverse order
     * would leave the portfolio holding BOTH trades if the run stopped in the
     * middle, i.e. it would double the user's position and their cash movement.
     * This order can only leave the trade MISSING, which is recoverable: the op
     * is still on the queue with the whole replacement payload in it, and the
     * next drain re-runs from the top.
     *
     * Re-running from the top is safe because both legs carry derived, stable
     * idempotency keys ([rebookLegKey]). A resumed op replays the delete (the
     * server returns its stored 2xx rather than a 404 for the row that is
     * already gone) and then either replays or performs the create. The one
     * window the keys cannot cover is a resume after the server's 48h
     * idempotency TTL has lapsed — past that a replayed delete really would 404.
     * That is precisely the case the queue's own `REPLAY_SAFE_WINDOW_MS` already
     * refuses to blind-replay, so the op parks for the user instead, which is
     * the honest outcome.
     *
     * ## Why the delete's own failures are not swallowed
     *
     * `CASH_LEDGER_WOULD_GO_NEGATIVE` (removing a sale would overdraw the
     * wallet) and `TAX_YEAR_LOCKED` are real refusals of the whole edit, and the
     * user has to see them. They come back through the normal 4xx classification
     * and park the op with the server's reason — at which point NOTHING has been
     * destroyed, because the delete is the first leg and it did not happen.
     */
    private suspend fun executeRebook(op: SyncOp): ExecResult {
        val payload = decode(TxRebookOpPayload.serializer(), op) ?: return malformed(op)
        val portfolioId = op.portfolioId ?: return malformed(op)
        val tx = payload.replacement

        val deleted = runMutation(op, {
            api.deleteTransaction(
                portfolioId,
                payload.txId,
                idempotencyKey = rebookLegKey(op.clientId, REBOOK_LEG_DELETE),
            )
        }) { null }
        // Anything other than a proven delete stops here, with the op intact.
        // Success is the ONLY state in which the original row is known to be
        // gone, and therefore the only state in which creating its replacement
        // cannot duplicate it.
        if (deleted !is ExecResult.Success) return deleted

        return runMutation(op, {
            api.createTransaction(
                portfolioId,
                CreateTransactionRequest(
                    assetId = tx.assetId,
                    side = tx.side,
                    quantity = tx.quantity,
                    price = tx.price,
                    fee = tx.fee,
                    executedAt = tx.executedAt,
                    note = tx.note,
                    payFromCash = tx.payFromCash,
                    addProceedsToCash = tx.addProceedsToCash,
                    // The whole reason this field was added to the contract DTO:
                    // without it the re-created leg silently lands on Main.
                    cashSourceId = tx.cashSourceId,
                    settleCashAsOfToday = tx.settleCashAsOfToday,
                    allowUncovered = tx.allowUncovered,
                    uncoveredEntryPrice = tx.uncoveredEntryPrice,
                ),
                idempotencyKey = rebookLegKey(op.clientId, REBOOK_LEG_CREATE),
            )
        }) { body ->
            resultJson("transactionIds", body.transactions.map { it.id })
        }
    }

    // ── Cash (deposit / withdraw / fee) ──────────────────────────────────────

    private suspend fun executeCash(op: SyncOp, kind: CashKind): ExecResult {
        val payload = decode(CashOpPayload.serializer(), op) ?: return malformed(op)
        val body = CashEntryRequest(
            amountEur = payload.amountEur,
            sourceId = payload.sourceId,
            executedAt = payload.executedAt,
            note = payload.note,
        )
        val portfolioId = op.portfolioId ?: return malformed(op)
        return runMutation(op, {
            when (kind) {
                CashKind.DEPOSIT -> api.cashDeposit(portfolioId, body, idempotencyKey = op.clientId)
                CashKind.FEE -> api.cashFee(portfolioId, body, idempotencyKey = op.clientId)
                else -> api.cashWithdraw(portfolioId, body, idempotencyKey = op.clientId)
            }
        }) { resp ->
            resultJson("movementIds", listOf(resp.movement.id))
        }
    }

    /** Step 9: atomic transfer between two sources. */
    private suspend fun executeTransfer(op: SyncOp): ExecResult {
        val payload = decode(CashTransferOpPayload.serializer(), op) ?: return malformed(op)
        val portfolioId = op.portfolioId ?: return malformed(op)
        return runMutation(op, {
            api.cashTransfer(
                portfolioId,
                CashTransferRequest(
                    fromSourceId = payload.fromSourceId,
                    toSourceId = payload.toSourceId,
                    amountEur = payload.amountEur,
                    executedAt = payload.executedAt,
                    note = payload.note,
                ),
                idempotencyKey = op.clientId,
            )
        }) { resp ->
            resultJson("movementIds", listOfNotNull(resp.outgoing.id, resp.incoming?.id))
        }
    }

    // ── Custom-asset value points ────────────────────────────────────────────

    /**
     * The API's only write is a full-replace PUT, so "add a value point" is
     * read-merge-write keyed by date — replaying it converges on the same set,
     * making this op idempotent by construction (and it carries the key too).
     */
    private suspend fun executeValuePoint(op: SyncOp): ExecResult {
        val payload = decode(ValuePointOpPayload.serializer(), op) ?: return malformed(op)
        // Read the current set.
        val current = try {
            val resp = api.valuePoints(payload.customAssetId)
            when {
                resp.isSuccessful -> resp.body()?.points ?: emptyList()
                resp.code() == 401 -> return ExecResult.AuthFailure
                resp.code() in 400..499 -> {
                    val err = parseApiError(json, resp.code(), resp.errorBody())
                    return ExecResult.Rejected(err.code, err.diagnostic)
                }
                else -> return ExecResult.Ambiguous(reachable = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return ExecResult.Ambiguous(reachable = false)
        } catch (e: Exception) {
            // The server answered, we just could not read it (a maintenance page
            // where JSON was promised). It was reached ⇒ the effect is unknown.
            Log.w(TAG, "Value-point read ended ambiguously: ${e.message}")
            return ExecResult.Ambiguous(reachable = true)
        }
        // Merge by date and write the full set back.
        val merged = current.filter { it.date != payload.date } +
            ValuePointDto(payload.date, payload.value)
        return runMutation(op, {
            api.putValuePoints(
                payload.customAssetId,
                PutValuePointsRequest(merged.sortedBy { it.date }),
                idempotencyKey = op.clientId,
            )
        }) { null }
    }

    // ── Shared plumbing ──────────────────────────────────────────────────────

    /**
     * Run one mutating call and classify the outcome for the state machine.
     * The call lambda may return null to signal a malformed op (missing ids).
     */
    private suspend fun <T : Any> runMutation(
        op: SyncOp,
        call: suspend () -> Response<T>?,
        onSuccess: (T) -> String?,
    ): ExecResult = try {
        val resp = call() ?: return ExecResult.Rejected(BtErrorCopy.AppCodes.OP_MALFORMED_SUBMIT)
        when {
            // 2xx — provably applied. A replay (same key) returns a byte-identical
            // 2xx, so this success path needs no special-casing (#9).
            resp.isSuccessful -> {
                val body = resp.body()
                ExecResult.Success(body?.let(onSuccess))
            }
            resp.code() == 401 -> ExecResult.AuthFailure
            // RetryableNotApplied never parks, so its payload is never shown to
            // anyone — the op goes back to PENDING with the error column cleared.
            // A bare code is all the log needs.
            resp.code() == 408 || resp.code() == 429 ->
                ExecResult.RetryableNotApplied("HTTP ${resp.code()}")
            resp.code() in 400..499 ->
                classifyClientError(parseApiError(json, resp.code(), resp.errorBody()), op)
            // V5 mirror seam: 503 MIRROR_SYNC_STALLED is a KNOWN transient — the
            // write was not applied because the mirror is catching up. Retry on
            // the normal backoff (same idempotency key) instead of treating it as
            // an unknown-effect 5xx, and never park it as needs-attention.
            resp.code() == 503 -> {
                val err = parseApiError(json, resp.code(), resp.errorBody())
                if (err.isMirrorSyncStalled) {
                    Log.d(TAG, "Mirror sync stalled for op#${op.id} — retrying with backoff")
                    ExecResult.RetryableNotApplied(err.code)
                } else {
                    ExecResult.Ambiguous(reachable = true)
                }
            }
            // Other 5xx — the server was reached; the effect is unknown.
            else -> ExecResult.Ambiguous(reachable = true)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        // Transport failure — may or may not have reached the server.
        ExecResult.Ambiguous(reachable = false)
    } catch (e: Exception) {
        // E.g. a 2xx whose body failed to parse: it DID land — treat as
        // ambiguous so a replay proves it instead of resubmitting.
        Log.w(TAG, "Mutation ended ambiguously: ${e.message}")
        ExecResult.Ambiguous(reachable = true)
    }

    /**
     * Map a 4xx business error into a queue outcome, special-casing the server's
     * idempotency-key codes (platform #432, PLATFORM_ASKS #9). Diagnostics are
     * presence-only (code + op id + non-secret key UUID; never the response body).
     */
    private fun classifyClientError(err: BtApiError, op: SyncOp): ExecResult = when (err.code) {
        // A same-key mutation is still processing server-side — transient, so
        // retry the SAME key on the normal backoff path (first send wins, this
        // one settles into the replay).
        BtApiError.Codes.IDEMPOTENCY_IN_PROGRESS -> {
            Log.d(TAG, "Idempotency in-progress for op#${op.id} (key=${op.clientId}) — will retry")
            ExecResult.RetryableNotApplied(err.code)
        }
        // Non-UUID key — regenerate once + retry (handled by the engine).
        BtApiError.Codes.IDEMPOTENCY_KEY_INVALID -> {
            Log.w(TAG, "Idempotency key rejected as INVALID for op#${op.id} (key=${op.clientId}) — regenerating once")
            ExecResult.InvalidKey
        }
        // Same key, different body: impossible by construction (key + body are
        // persisted together, so replays are byte-identical). Treat as a
        // permanent op failure surfaced through needs-attention.
        BtApiError.Codes.IDEMPOTENCY_KEY_MISMATCH -> {
            Log.w(TAG, "Idempotency key MISMATCH for op#${op.id} (key=${op.clientId}, HTTP ${err.httpStatus}) — parking as needs-attention")
            ExecResult.Rejected(err.code, err.diagnostic)
        }
        // V5 mirror seam (409): the row moved, vanished, or is derived-and-not-
        // editable. All three are permanent for THIS attempt, so park with the
        // app-authored one-liner and let the user retry/remove from the
        // pending-sync screen (same shape as MSG_ATTEMPT_TIMED_OUT).
        in MIRROR_SEAM_CONFLICT_CODES -> {
            Log.w(TAG, "Mirror-seam refusal ${err.code} for op#${op.id} (HTTP ${err.httpStatus}) — parking as needs-attention")
            ExecResult.Rejected(err.code, err.diagnostic)
        }
        else -> ExecResult.Rejected(err.code, err.diagnostic)
    }

    private fun <T> decode(
        serializer: kotlinx.serialization.KSerializer<T>,
        op: SyncOp,
    ): T? = try {
        json.decodeFromString(serializer, op.payloadJson)
    } catch (e: Exception) {
        Log.w(TAG, "Malformed payload for op ${op.clientId}: ${e.message}")
        null
    }

    private fun malformed(op: SyncOp): ExecResult {
        Log.w(TAG, "Rejecting malformed op ${op.clientId} (${op.type.wire})")
        return ExecResult.Rejected(BtErrorCopy.AppCodes.OP_MALFORMED_SUBMIT)
    }

    private fun resultJson(key: String, ids: List<String>): String =
        buildJsonObject { putJsonArray(key) { ids.forEach { add(it) } } }.toString()

    companion object {
        private const val TAG = "BtOpExecutor"
    }
}
