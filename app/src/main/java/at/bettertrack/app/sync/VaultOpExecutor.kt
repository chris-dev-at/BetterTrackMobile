package at.bettertrack.app.sync

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.domain.CashLedgerError
import at.bettertrack.app.domain.CashTransferInput
import at.bettertrack.app.domain.DomainException
import at.bettertrack.app.domain.InsufficientCashError
import at.bettertrack.app.domain.OversellError
import at.bettertrack.app.domain.SourcedCashMovement
import at.bettertrack.app.domain.Transaction
import at.bettertrack.app.domain.TransactionSide
import at.bettertrack.app.domain.floorCents
import at.bettertrack.app.domain.pairedTransferMovements
import at.bettertrack.app.domain.projectCashLedger
import at.bettertrack.app.domain.reducePosition
import at.bettertrack.app.domain.spendableAsOf
import at.bettertrack.app.vault.VaultEntityGraph
import at.bettertrack.app.vault.VaultKinds
import at.bettertrack.app.vault.VaultMutationContext
import at.bettertrack.app.vault.VaultPayloads
import at.bettertrack.app.vault.VaultStore
import at.bettertrack.app.vault.decimal
import at.bettertrack.app.vault.text
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * The Drive-mode write path — the vault arm of [ModeRoutingOpExecutor],
 * replacing `UnavailableVaultOpExecutor` for [at.bettertrack.app.data.storage.BackendTag.VAULT]
 * ops (S3/S4 plan §1.2 "Semantic note", §5 W4).
 *
 * ## What changes and what deliberately does not
 *
 * In Drive mode the outbound queue **stops being a network queue and becomes a
 * local-apply journal**. This executor applies the op to the vault entity graph
 * and returns [ExecResult.Success] synchronously — there is no request, so there
 * is no ambiguity, no idempotency key to replay and no backoff. Pushing the
 * result to Drive is a separate, coalesced, failable step
 * (`VaultSyncCoordinator`), and a failed push never turns into a failed write.
 *
 * The queue is kept anyway, and that is the point: it is the same durable FIFO,
 * with the same states, the same needs-attention surface
 * (`ui/sync/PendingSyncScreen.kt`) and the same "pending row renders instantly"
 * behaviour (§7.4). No new UI ships to support Drive-mode writes.
 *
 * ## Domain refusals are Rejected, never crashes
 *
 * The ported engine is the calculator here (`CLAUDE.md`, plan §3.5), and it
 * refuses two things by design: selling more than is held ([OversellError]) and
 * spending cash that is not there ([InsufficientCashError]). Both carry a
 * message with the offending quantities, and both map to [ExecResult.Rejected] —
 * *provably not applied* — so the existing needs-attention UI shows the engine's
 * own sentence exactly where it would have shown the server's. A refusal must
 * never propagate as an exception: it would kill the drain and block every op
 * behind it.
 *
 * The refusal is also proven **before anything is persisted**: [VaultStore.mutate]
 * hands the block a copy of the graph, so a throw leaves the vault and its
 * `vaultVersion` untouched.
 */
class VaultOpExecutor(
    private val store: VaultStore,
    private val json: Json,
    /**
     * Native → EUR conversion for the cash legs of a trade. `null` means "no rate
     * available" — the honest answer in Drive-only mode with no live prices
     * (plan §1.3), and a [ExecResult.Rejected] rather than a guessed number.
     */
    private val toEur: suspend (amount: Double, currency: String, date: String) -> Double?,
    /** Notified after every applied op so projections re-derive and a push is scheduled. */
    private val onApplied: suspend (vaultVersion: Int) -> Unit = {},
) : OpExecutor {

    override suspend fun execute(op: SyncOp): ExecResult {
        val result = try {
            store.mutate { graph, context -> apply(op, graph, context) }
        } catch (refusal: VaultOpRefusal) {
            return ExecResult.Rejected(refusal.code, refusal.arg)
        } catch (refusal: OversellError) {
            // The engine's own invariants map onto the SAME codes the server uses
            // for the same refusal, so a Drive-mode park and a server-mode park
            // read identically to the user (S6 P1-13).
            return ExecResult.Rejected(BtApiError.Codes.OVERSELL, refusal.message)
        } catch (refusal: InsufficientCashError) {
            return ExecResult.Rejected(BtApiError.Codes.INSUFFICIENT_CASH, refusal.message)
        } catch (refusal: CashLedgerError) {
            return ExecResult.Rejected(
                BtApiError.Codes.CASH_LEDGER_WOULD_GO_NEGATIVE,
                refusal.message,
            )
        } catch (refusal: DomainException) {
            // Any other engine-level invariant (a non-finite quantity, an
            // unparseable timestamp). Well-formed-input errors are the user's to
            // fix, so they park as needs-attention rather than retrying forever.
            return ExecResult.Rejected(
                BtErrorCopy.AppCodes.OP_MALFORMED_VAULT,
                refusal.message,
            )
        }

        onApplied(result.vaultVersion)
        return ExecResult.Success(result.value.resultJson)
    }

    private suspend fun apply(op: SyncOp, graph: VaultEntityGraph, context: VaultMutationContext): Applied =
        when (op.type) {
            OpType.TX_BUY, OpType.TX_SELL -> applyTransaction(op, graph, context)
            OpType.CASH_DEPOSIT -> applyCash(op, graph, context, "deposit")
            OpType.CASH_WITHDRAW -> applyCash(op, graph, context, "withdrawal")
            OpType.CASH_FEE -> applyCash(op, graph, context, "fee")
            OpType.CASH_TRANSFER -> applyTransfer(op, graph, context)
            OpType.CUSTOM_ASSET_VALUE_POINT -> applyValuePoint(op, graph, context)
        }

    // ── Transactions ────────────────────────────────────────────────────────

    private suspend fun applyTransaction(
        op: SyncOp,
        graph: VaultEntityGraph,
        context: VaultMutationContext,
    ): Applied {
        val payload = decode(TxOpPayload.serializer(), op) ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val portfolioId = op.portfolioId ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val expectedSide = if (op.type == OpType.TX_BUY) "buy" else "sell"
        if (payload.side != expectedSide) refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)

        // The asset identity may not be in the vault yet — an offline buy of an
        // asset this device met through search. The queue's display-only snapshot
        // (`assetSymbol`/`assetName`/`assetCurrency`, §7.4) is exactly the
        // material needed to mint the row, which is what those fields are for.
        val currency = ensureAsset(graph, context, payload)

        val existing = graph.live(VaultKinds.TRANSACTION)
            .filter { it.text("portfolioId") == portfolioId && it.text("assetId") == payload.assetId }
            .map { it.toDomainTransaction() }
        val incoming = Transaction(
            assetId = payload.assetId,
            side = if (payload.side == "buy") TransactionSide.BUY else TransactionSide.SELL,
            quantity = payload.quantity,
            price = payload.price,
            fee = payload.fee,
            executedAt = payload.executedAt,
            allowUncovered = payload.allowUncovered,
            uncoveredEntryPrice = payload.uncoveredEntryPrice,
        )
        // Throws OversellError — caught in `execute` and mapped to Rejected.
        reducePosition(existing + incoming)

        val gross = payload.quantity * payload.price
        val cashLeg = when {
            payload.side == "buy" && payload.payFromCash == true -> gross + payload.fee
            payload.side == "sell" && payload.addProceedsToCash == true -> gross - payload.fee
            else -> null
        }

        var movementId: String? = null
        if (cashLeg != null) {
            val sourceId = mainCashSourceId(graph, portfolioId)
                ?: refuse(BtErrorCopy.AppCodes.OP_NO_CASH_SOURCE)
            val nativeEur = toEur(cashLeg, currency, payload.executedAt.take(10))
                ?: refuse(BtErrorCopy.AppCodes.OP_NO_RATE, currency)
            val amount = floorCents(nativeEur)

            val sourceMovements = sourceMovements(graph, sourceId)
            val occurredAt = if (payload.side == "buy") {
                settlementInstant(payload, sourceMovements, amount, context.now)
            } else {
                payload.executedAt
            }
            val kind = if (payload.side == "buy") "buy" else "sell_proceeds"
            val signed = if (payload.side == "buy") -amount else amount
            // Throws InsufficientCashError at the offending point — including for
            // a BACKDATED buy that the balance covered today but not then.
            projectCashLedger(sourceMovements + SourcedCashMovement(kind, signed, occurredAt, sourceId))

            movementId = context.newId()
            graph.create(
                kind = VaultKinds.CASH_MOVEMENT,
                id = movementId,
                data = VaultPayloads.cashMovement(
                    portfolioId = portfolioId,
                    sourceId = sourceId,
                    kind = kind,
                    amountEur = signed,
                    executedAt = occurredAt,
                    createdAt = context.now,
                    transactionId = null,
                ),
                editedAt = context.now,
                editedBy = context.deviceId,
            )
        }

        val transactionId = context.newId()
        graph.create(
            kind = VaultKinds.TRANSACTION,
            id = transactionId,
            data = VaultPayloads.transaction(
                portfolioId = portfolioId,
                assetId = payload.assetId,
                side = payload.side,
                quantity = payload.quantity,
                price = payload.price,
                fee = payload.fee,
                executedAt = payload.executedAt,
                note = payload.note,
                allowUncovered = payload.allowUncovered == true,
                uncoveredEntryPrice = payload.uncoveredEntryPrice,
            ),
            editedAt = context.now,
            editedBy = context.deviceId,
        )
        // Link the cash leg back now that the transaction has an id.
        if (movementId != null) {
            graph.edit(VaultKinds.CASH_MOVEMENT, movementId, context.now, context.deviceId) { data ->
                JsonObject(LinkedHashMap(data).apply { put("transactionId", JsonPrimitive(transactionId)) })
            }
        }
        return Applied(resultJson("transactionIds", listOf(transactionId)))
    }

    /**
     * Backdated pay-from-cash settlement — the client-side mirror of platform
     * #378 (`TxOpPayload.settleCashAsOfToday`).
     *
     * The trade keeps its real (past) date, but when the cash was short **as of
     * then** and suffices **now**, the withdrawal leg is dated today. Without
     * this a user entering last month's purchases in any order gets refusals that
     * say nothing about anything they can fix.
     */
    private fun settlementInstant(
        payload: TxOpPayload,
        sourceMovements: List<SourcedCashMovement>,
        amount: Double,
        now: String,
    ): String {
        if (payload.settleCashAsOfToday != true) return payload.executedAt
        val availableThen = spendableAsOf(sourceMovements, payload.executedAt)
        if (availableThen >= amount) return payload.executedAt
        return if (spendableAsOf(sourceMovements, now) >= amount) now else payload.executedAt
    }

    // ── Cash ────────────────────────────────────────────────────────────────

    private fun applyCash(
        op: SyncOp,
        graph: VaultEntityGraph,
        context: VaultMutationContext,
        kind: String,
    ): Applied {
        val payload = decode(CashOpPayload.serializer(), op) ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val portfolioId = op.portfolioId ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val sourceId = payload.sourceId?.takeIf { graph.find(VaultKinds.CASH_SOURCE, it) != null }
            ?: mainCashSourceId(graph, portfolioId)
            ?: refuse(BtErrorCopy.AppCodes.OP_NO_CASH_SOURCE)

        // Cash exists only in cents (CASH_DECIMALS = 2) — quantize once, here, so
        // the stored payload and every replay of it agree to the cent.
        val magnitude = floorCents(kotlin.math.abs(payload.amountEur))
        if (magnitude == 0.0) refuse(BtErrorCopy.AppCodes.OP_ZERO_AMOUNT)
        val signed = if (kind == "deposit") magnitude else -magnitude
        val occurredAt = payload.executedAt ?: context.now

        projectCashLedger(
            sourceMovements(graph, sourceId) + SourcedCashMovement(kind, signed, occurredAt, sourceId)
        )

        val movementId = context.newId()
        graph.create(
            kind = VaultKinds.CASH_MOVEMENT,
            id = movementId,
            data = VaultPayloads.cashMovement(
                portfolioId = portfolioId,
                sourceId = sourceId,
                kind = kind,
                amountEur = signed,
                executedAt = occurredAt,
                createdAt = context.now,
                note = payload.note,
            ),
            editedAt = context.now,
            editedBy = context.deviceId,
        )
        return Applied(resultJson("movementIds", listOf(movementId)))
    }

    /** Atomic double-entry transfer — both legs or neither (plan §2.4 semantics). */
    private fun applyTransfer(
        op: SyncOp,
        graph: VaultEntityGraph,
        context: VaultMutationContext,
    ): Applied {
        val payload = decode(CashTransferOpPayload.serializer(), op) ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val portfolioId = op.portfolioId ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val occurredAt = payload.executedAt ?: context.now

        // Throws CashLedgerError on a same-source / non-positive / sub-cent move.
        val legs = pairedTransferMovements(
            CashTransferInput(
                fromSourceId = payload.fromSourceId,
                toSourceId = payload.toSourceId,
                amountEur = payload.amountEur,
                occurredAt = occurredAt,
            )
        )
        projectCashLedger(sourceMovements(graph, payload.fromSourceId) + legs.outgoing)

        val transferId = context.newId()
        val outgoingId = context.newId()
        val incomingId = context.newId()
        graph.create(
            kind = VaultKinds.CASH_MOVEMENT,
            id = outgoingId,
            data = VaultPayloads.cashMovement(
                portfolioId = portfolioId,
                sourceId = payload.fromSourceId,
                kind = legs.outgoing.kind,
                amountEur = legs.outgoing.amountEur,
                executedAt = occurredAt,
                createdAt = context.now,
                note = payload.note,
                transferId = transferId,
                counterpartSourceId = payload.toSourceId,
            ),
            editedAt = context.now,
            editedBy = context.deviceId,
        )
        graph.create(
            kind = VaultKinds.CASH_MOVEMENT,
            id = incomingId,
            data = VaultPayloads.cashMovement(
                portfolioId = portfolioId,
                sourceId = payload.toSourceId,
                kind = legs.incoming.kind,
                amountEur = legs.incoming.amountEur,
                executedAt = occurredAt,
                createdAt = context.now,
                note = payload.note,
                transferId = transferId,
                counterpartSourceId = payload.fromSourceId,
            ),
            editedAt = context.now,
            editedBy = context.deviceId,
        )
        return Applied(resultJson("movementIds", listOf(outgoingId, incomingId)))
    }

    // ── Custom-asset value points ───────────────────────────────────────────

    /**
     * Merged by **date**, exactly like the server's full-replace PUT: re-applying
     * the same point converges instead of stacking duplicates, which is what
     * makes this op safe to replay after a merge.
     */
    private fun applyValuePoint(
        op: SyncOp,
        graph: VaultEntityGraph,
        context: VaultMutationContext,
    ): Applied {
        val payload = decode(ValuePointOpPayload.serializer(), op) ?: refuse(BtErrorCopy.AppCodes.OP_MALFORMED_VAULT)
        val existing = graph.live(VaultKinds.CUSTOM_ASSET_VALUE).firstOrNull {
            it.text("assetId") == payload.customAssetId && it.text("date") == payload.date
        }
        val data = VaultPayloads.customAssetValue(payload.customAssetId, payload.date, payload.value)
        val id = if (existing != null) {
            graph.edit(VaultKinds.CUSTOM_ASSET_VALUE, existing.id, context.now, context.deviceId) { data }
            existing.id
        } else {
            context.newId().also {
                graph.create(VaultKinds.CUSTOM_ASSET_VALUE, it, data, context.now, context.deviceId)
            }
        }
        return Applied(resultJson("valuePointIds", listOf(id)))
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    private fun ensureAsset(
        graph: VaultEntityGraph,
        context: VaultMutationContext,
        payload: TxOpPayload,
    ): String {
        graph.find(VaultKinds.CUSTOM_ASSET, payload.assetId)?.let { return it.text("currency") ?: "EUR" }
        val currency = payload.assetCurrency ?: "EUR"
        graph.create(
            kind = VaultKinds.CUSTOM_ASSET,
            id = payload.assetId,
            data = VaultPayloads.customAsset(
                ownerId = null,
                type = "stock",
                symbol = payload.assetSymbol ?: payload.assetId,
                name = payload.assetName ?: payload.assetSymbol ?: payload.assetId,
                currency = currency,
            ),
            editedAt = context.now,
            editedBy = context.deviceId,
        )
        return currency
    }

    private fun mainCashSourceId(graph: VaultEntityGraph, portfolioId: String): String? {
        val sources = graph.live(VaultKinds.CASH_SOURCE).filter { it.text("portfolioId") == portfolioId }
        return (sources.firstOrNull { it.text("isMain") == "true" } ?: sources.firstOrNull())?.id
    }

    private fun sourceMovements(graph: VaultEntityGraph, sourceId: String): List<SourcedCashMovement> =
        graph.live(VaultKinds.CASH_MOVEMENT)
            .filter { it.text("sourceId") == sourceId }
            .map {
                SourcedCashMovement(
                    kind = it.text("kind").orEmpty(),
                    amountEur = it.decimal("amountEur") ?: 0.0,
                    occurredAt = it.text("executedAt").orEmpty(),
                    sourceId = sourceId,
                )
            }

    private fun <T> decode(serializer: KSerializer<T>, op: SyncOp): T? = try {
        json.decodeFromString(serializer, op.payloadJson)
    } catch (_: Exception) {
        null
    }

    private fun resultJson(key: String, ids: List<String>): String =
        buildJsonObject { putJsonArray(key) { ids.forEach { add(it) } } }.toString()

    /** What an applied op produced — the ids the server path would have returned. */
    private data class Applied(val resultJson: String)

    /**
     * A refusal decided by this executor rather than by the engine (a missing
     * cash source, an unconvertible currency, a malformed payload).
     *
     * Thrown rather than returned so it takes the **same** path as an
     * [OversellError] or [InsufficientCashError]: [VaultStore.mutate] hands the
     * block a copy of the graph, so a throw leaves both the entities and
     * `vaultVersion` untouched. A returned refusal would have left the CAS token
     * advanced for an op that changed nothing — a phantom "unsynced change" the
     * chip would then show the user.
     */
    private class VaultOpRefusal(
        val code: String,
        /** Format argument for the codes that take one; null otherwise. */
        val arg: String? = null,
    ) : RuntimeException(code)

    private fun refuse(code: String, arg: String? = null): Nothing = throw VaultOpRefusal(code, arg)
}

private fun at.bettertrack.app.vault.VaultEntity.toDomainTransaction(): Transaction = Transaction(
    assetId = text("assetId").orEmpty(),
    side = if (text("side") == "buy") TransactionSide.BUY else TransactionSide.SELL,
    quantity = decimal("quantity") ?: 0.0,
    price = decimal("price") ?: 0.0,
    fee = decimal("fee") ?: 0.0,
    executedAt = text("executedAt").orEmpty(),
    allowUncovered = text("allowUncovered")?.toBooleanStrictOrNull(),
    uncoveredEntryPrice = decimal("uncoveredEntryPrice"),
)
