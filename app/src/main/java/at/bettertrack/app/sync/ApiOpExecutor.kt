package at.bettertrack.app.sync

import android.util.Log
import at.bettertrack.app.data.api.BtApi
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
 * Exactly-once WITHOUT a server idempotency key (§7.3 platform-prereq — the
 * API has no such field yet, `additionalProperties: false` throughout): the
 * op's client UUID is embedded into the request's REAL `note` field as a
 * ` [bt:<uuid>]` suffix. If a send ends ambiguously (crash, lost response,
 * 5xx), [lookup] can then PROVE whether the op landed by finding the marker in
 * the ledger — no blind resends, no heuristics. Value points don't need a
 * marker: the only write is a full-replace PUT of the merged point set, which
 * is idempotent by construction. Once the platform ships a real idempotency
 * key, the marker goes away (TODO(platform), noted in TODO.md).
 */
class ApiOpExecutor(
    private val api: BtApi,
    private val json: Json,
) : OpExecutor {

    override suspend fun execute(op: SyncOp): ExecResult = when (op.type) {
        OpType.TX_BUY, OpType.TX_SELL -> executeTransaction(op)
        OpType.CASH_DEPOSIT -> executeCash(op, deposit = true)
        OpType.CASH_WITHDRAW -> executeCash(op, deposit = false)
        OpType.CASH_TRANSFER -> executeTransfer(op)
        OpType.CUSTOM_ASSET_VALUE_POINT -> executeValuePoint(op)
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
        return runMutation({
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
                ),
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
        return runMutation({
            if (deposit) api.cashDeposit(portfolioId, body) else api.cashWithdraw(portfolioId, body)
        }) { resp ->
            resultJson("movementIds", listOf(resp.movement.id))
        }
    }

    /** Step 9: atomic transfer between two sources (the marker rides the note). */
    private suspend fun executeTransfer(op: SyncOp): ExecResult {
        val payload = decode(CashTransferOpPayload.serializer(), op) ?: return malformed(op)
        val portfolioId = op.portfolioId ?: return malformed(op)
        return runMutation({
            api.cashTransfer(
                portfolioId,
                CashTransferRequest(
                    fromSourceId = payload.fromSourceId,
                    toSourceId = payload.toSourceId,
                    amountEur = payload.amountEur,
                    executedAt = payload.executedAt,
                    note = markedNote(payload.note, op.clientId),
                ),
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
        return runMutation({
            api.putValuePoints(payload.customAssetId, PutValuePointsRequest(merged.sortedBy { it.date }))
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
        call: suspend () -> Response<T>?,
        onSuccess: (T) -> String?,
    ): ExecResult = try {
        val resp = call() ?: return ExecResult.Rejected(MSG_MALFORMED)
        when {
            resp.isSuccessful -> {
                val body = resp.body()
                ExecResult.Success(body?.let(onSuccess))
            }
            resp.code() == 401 -> ExecResult.AuthFailure
            resp.code() == 408 || resp.code() == 429 -> ExecResult.RetryableNotApplied(
                "The server asked us to retry (HTTP ${resp.code()}).",
            )
            resp.code() in 400..499 -> ExecResult.Rejected(
                parseApiError(json, resp.code(), resp.errorBody()).userMessage,
            )
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
