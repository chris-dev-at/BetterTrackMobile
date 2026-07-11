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
import kotlin.math.abs

/**
 * The op → API mapping layer (§7.2 ledger-event set → module endpoints).
 *
 * Exactly-once via the server idempotency key (platform #432, LIVE — see
 * PLATFORM_ASKS #9): every send of a queued mutation carries
 * `Idempotency-Key: <op.clientId>` (a UUID minted + persisted at enqueue), so a
 * replayed retry runs exactly once and returns a byte-identical 2xx. This is the
 * PRIMARY exactly-once guarantee now.
 *
 * The legacy ` [bt:<uuid>]` note marker is RETAINED as defense-in-depth: after
 * an ambiguous send (crash, lost response, 5xx) [lookup] can still PROVE whether
 * the op landed by finding the marker, so the drain marks it done without a
 * resend. And should the marker lookup ever miss a landed op, the eventual
 * resend now REPLAYS (same key) instead of duplicating — strictly safer than the
 * marker alone. Value points never needed a marker (their write is a full-replace
 * PUT of the merged point set, idempotent by construction) but still carry the
 * key. Dropping the marker entirely is a separable cleanup (it would rework the
 * reconcile lookup path); out of scope for adopting the key.
 */
class ApiOpExecutor(
    private val api: BtApi,
    private val json: Json,
    /** Kill-switch for the #432 header — see [SyncFeatureFlags.IDEMPOTENCY_KEYS_ENABLED]. */
    private val idempotencyEnabled: Boolean = true,
) : OpExecutor {

    /** The Idempotency-Key to send for [op], or null when the feature is off. */
    private fun idempotencyKeyFor(op: SyncOp): String? =
        if (idempotencyEnabled) op.clientId else null

    override suspend fun execute(op: SyncOp): ExecResult {
        // Presence-only diagnostics — the idempotency-key UUID is not a secret.
        Log.d(
            TAG,
            "execute ${op.type.wire} op#${op.id} Idempotency-Key=" +
                if (idempotencyEnabled) op.clientId else "(disabled)",
        )
        return when (op.type) {
            OpType.TX_BUY, OpType.TX_SELL -> executeTransaction(op)
            OpType.CASH_DEPOSIT -> executeCash(op, deposit = true)
            OpType.CASH_WITHDRAW -> executeCash(op, deposit = false)
            OpType.CASH_TRANSFER -> executeTransfer(op)
            OpType.CUSTOM_ASSET_VALUE_POINT -> executeValuePoint(op)
        }
    }

    override suspend fun lookup(op: SyncOp): LookupResult = when (op.type) {
        OpType.TX_BUY, OpType.TX_SELL -> lookupTransaction(op)
        OpType.CASH_DEPOSIT, OpType.CASH_WITHDRAW, OpType.CASH_TRANSFER -> lookupCashMovement(op)
        OpType.CUSTOM_ASSET_VALUE_POINT -> lookupValuePoint(op)
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
                    note = markedNote(payload.note, op.clientId),
                    payFromCash = payload.payFromCash,
                    addProceedsToCash = payload.addProceedsToCash,
                    settleCashAsOfToday = payload.settleCashAsOfToday,
                    allowUncovered = payload.allowUncovered,
                    uncoveredEntryPrice = payload.uncoveredEntryPrice,
                ),
                idempotencyKey = idempotencyKeyFor(op),
            )
        }) { body ->
            resultJson("transactionIds", body.transactions.map { it.id })
        }
    }

    private suspend fun lookupTransaction(op: SyncOp): LookupResult {
        val portfolioId = op.portfolioId ?: return LookupResult.NotFound
        val marker = marker(op.clientId)
        return try {
            // Walk up to 3 newest pages (600 rows) — the op was sent recently,
            // so its marker sits at the top of the ledger if it landed.
            var cursor: String? = null
            repeat(3) {
                val resp = api.transactions(portfolioId, cursor = cursor, limit = 200)
                if (!resp.isSuccessful) return LookupResult.Unreachable
                val body = resp.body() ?: return LookupResult.Unreachable
                val hit = body.items.firstOrNull { it.note?.contains(marker) == true }
                if (hit != null) {
                    return LookupResult.Found(resultJson("transactionIds", listOf(hit.id)))
                }
                cursor = body.nextCursor ?: return LookupResult.NotFound
            }
            LookupResult.NotFound
        } catch (_: IOException) {
            LookupResult.Unreachable
        } catch (e: Exception) {
            Log.w(TAG, "Transaction lookup failed: ${e.message}")
            LookupResult.Unreachable
        }
    }

    // ── Cash (deposit / withdraw) ────────────────────────────────────────────

    private suspend fun executeCash(op: SyncOp, deposit: Boolean): ExecResult {
        val payload = decode(CashOpPayload.serializer(), op) ?: return malformed(op)
        val body = CashEntryRequest(
            amountEur = payload.amountEur,
            sourceId = payload.sourceId,
            executedAt = payload.executedAt,
            note = markedNote(payload.note, op.clientId),
        )
        val portfolioId = op.portfolioId ?: return malformed(op)
        return runMutation(op, {
            if (deposit) {
                api.cashDeposit(portfolioId, body, idempotencyKey = idempotencyKeyFor(op))
            } else {
                api.cashWithdraw(portfolioId, body, idempotencyKey = idempotencyKeyFor(op))
            }
        }) { resp ->
            resultJson("movementIds", listOf(resp.movement.id))
        }
    }

    /** Step 9: atomic transfer between two sources (the marker rides the note). */
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
                    note = markedNote(payload.note, op.clientId),
                ),
                idempotencyKey = idempotencyKeyFor(op),
            )
        }) { resp ->
            resultJson("movementIds", listOfNotNull(resp.outgoing.id, resp.incoming?.id))
        }
    }

    private suspend fun lookupCashMovement(op: SyncOp): LookupResult {
        val portfolioId = op.portfolioId ?: return LookupResult.NotFound
        val marker = marker(op.clientId)
        return try {
            val resp = api.cash(portfolioId)
            if (!resp.isSuccessful) return LookupResult.Unreachable
            val hit = resp.body()?.movements?.firstOrNull { it.note?.contains(marker) == true }
            if (hit != null) {
                LookupResult.Found(resultJson("movementIds", listOf(hit.id)))
            } else {
                LookupResult.NotFound
            }
        } catch (_: IOException) {
            LookupResult.Unreachable
        } catch (e: Exception) {
            Log.w(TAG, "Cash lookup failed: ${e.message}")
            LookupResult.Unreachable
        }
    }

    // ── Custom-asset value points ────────────────────────────────────────────

    /**
     * The API's only write is a full-replace PUT, so "add a value point" is
     * read-merge-write keyed by date — replaying it converges on the same set,
     * making this op idempotent without any marker.
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
                idempotencyKey = idempotencyKeyFor(op),
            )
        }) { null }
    }

    private suspend fun lookupValuePoint(op: SyncOp): LookupResult {
        val payload = decode(ValuePointOpPayload.serializer(), op) ?: return LookupResult.NotFound
        return try {
            val resp = api.valuePoints(payload.customAssetId)
            if (!resp.isSuccessful) return LookupResult.Unreachable
            val hit = resp.body()?.points?.any {
                it.date == payload.date && abs(it.value - payload.value) < 1e-9
            } == true
            if (hit) LookupResult.Found(null) else LookupResult.NotFound
        } catch (_: IOException) {
            LookupResult.Unreachable
        } catch (e: Exception) {
            Log.w(TAG, "Value-point lookup failed: ${e.message}")
            LookupResult.Unreachable
        }
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
        // ambiguous so reconcile proves it instead of resubmitting.
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

        /** The reconcile marker carried in the API's real `note` field. */
        fun marker(clientId: String): String = "[bt:$clientId]"

        /**
         * Append the marker to the user's note (clamped to the API's 1000-char
         * limit). Interim mechanism until the platform accepts an idempotency
         * key on portfolio mutations (§7.3 platform-prereq).
         */
        fun markedNote(userNote: String?, clientId: String): String {
            val m = marker(clientId)
            val trimmed = userNote?.trim().orEmpty()
            return if (trimmed.isEmpty()) m else "${trimmed.take(1000 - m.length - 1)} $m"
        }
    }
}
