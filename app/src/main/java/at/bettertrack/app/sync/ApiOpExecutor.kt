package at.bettertrack.app.sync

import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.dto.CashEntryRequest
import at.bettertrack.app.data.api.dto.CashTransferRequest
import at.bettertrack.app.data.api.dto.CreateTransactionRequest
import at.bettertrack.app.data.api.dto.PutValuePointsRequest
import at.bettertrack.app.data.api.dto.ValuePointDto
import at.bettertrack.app.data.api.parseApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import retrofit2.Response
import java.io.IOException

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
            OpType.CASH_DEPOSIT -> executeCash(op, deposit = true)
            OpType.CASH_WITHDRAW -> executeCash(op, deposit = false)
            OpType.CASH_TRANSFER -> executeTransfer(op)
            OpType.CUSTOM_ASSET_VALUE_POINT -> executeValuePoint(op)
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

    // ── Cash (deposit / withdraw) ────────────────────────────────────────────

    private suspend fun executeCash(op: SyncOp, deposit: Boolean): ExecResult {
        val payload = decode(CashOpPayload.serializer(), op) ?: return malformed(op)
        val body = CashEntryRequest(
            amountEur = payload.amountEur,
            sourceId = payload.sourceId,
            executedAt = payload.executedAt,
            note = payload.note,
        )
        val portfolioId = op.portfolioId ?: return malformed(op)
        return runMutation(op, {
            if (deposit) {
                api.cashDeposit(portfolioId, body, idempotencyKey = op.clientId)
            } else {
                api.cashWithdraw(portfolioId, body, idempotencyKey = op.clientId)
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
                resp.code() in 400..499 -> return ExecResult.Rejected(
                    parseApiError(json, resp.code(), resp.errorBody()).userMessage,
                )
                else -> return ExecResult.Ambiguous(reachable = true)
            }
        } catch (_: IOException) {
            return ExecResult.Ambiguous(reachable = false)
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
        val resp = call() ?: return ExecResult.Rejected(MSG_MALFORMED)
        when {
            // 2xx — provably applied. A replay (same key) returns a byte-identical
            // 2xx, so this success path needs no special-casing (#9).
            resp.isSuccessful -> {
                val body = resp.body()
                ExecResult.Success(body?.let(onSuccess))
            }
            resp.code() == 401 -> ExecResult.AuthFailure
            resp.code() == 408 || resp.code() == 429 -> ExecResult.RetryableNotApplied(
                "The server asked us to retry (HTTP ${resp.code()}).",
            )
            resp.code() in 400..499 ->
                classifyClientError(parseApiError(json, resp.code(), resp.errorBody()), op)
            // 5xx — the server was reached; the effect is unknown.
            else -> ExecResult.Ambiguous(reachable = true)
        }
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
            ExecResult.RetryableNotApplied("A previous attempt of this change is still being processed.")
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
            ExecResult.Rejected(err.userMessage)
        }
        else -> ExecResult.Rejected(err.userMessage)
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
        return ExecResult.Rejected(MSG_MALFORMED)
    }

    private fun resultJson(key: String, ids: List<String>): String =
        buildJsonObject { putJsonArray(key) { ids.forEach { add(it) } } }.toString()

    companion object {
        private const val TAG = "BtOpExecutor"
        private const val MSG_MALFORMED = "This queued entry is malformed and can't be submitted."
    }
}
