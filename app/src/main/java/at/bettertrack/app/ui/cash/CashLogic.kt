package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.sync.CashOpPayload
import at.bettertrack.app.sync.CashTransferOpPayload
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import at.bettertrack.app.ui.portfolio.pendingUiStatus
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure logic behind the Step-9 cash screen (§6.3): balance-after previews,
 * entry validation, and queued-cash-op decoding for pending rows. Everything
 * here is unit-tested; previews use CACHED balances (§7.3 best-effort — the
 * server stays the final validator).
 */

// ── Previews ────────────────────────────────────────────────────────────────

/** Balance of one source after a deposit(+) / withdrawal(−). */
fun balanceAfterEntry(balanceEur: Double, amountEur: Double, deposit: Boolean): Double =
    if (deposit) balanceEur + amountEur else balanceEur - amountEur

/** Both sides of a transfer preview. */
data class TransferPreview(val fromAfterEur: Double, val toAfterEur: Double)

fun transferPreview(fromBalanceEur: Double, toBalanceEur: Double, amountEur: Double): TransferPreview =
    TransferPreview(fromBalanceEur - amountEur, toBalanceEur + amountEur)

// ── Validation ──────────────────────────────────────────────────────────────

/** Validation of a deposit/withdraw entry (§6.3 — no silent negatives). */
data class CashEntryValidation(
    val amountMissing: Boolean,
    val amountNotPositive: Boolean,
    /** Withdrawal would overdraw the CACHED source balance — hard block. */
    val insufficient: Boolean,
) {
    val canSubmit: Boolean get() = !amountMissing && !amountNotPositive && !insufficient
}

fun validateCashEntry(
    amount: Double?,
    deposit: Boolean,
    sourceBalanceEur: Double?,
): CashEntryValidation {
    val missing = amount == null
    val notPositive = amount != null && amount <= 0.0
    val insufficient = !deposit && amount != null && amount > 0.0 &&
        sourceBalanceEur != null && sourceBalanceEur - amount < 0.0
    return CashEntryValidation(missing, notPositive, insufficient)
}

/** Validation of a transfer (§6.3): distinct sources, positive, no overdraw. */
data class TransferValidation(
    val amountMissing: Boolean,
    val amountNotPositive: Boolean,
    val sameSource: Boolean,
    val missingSource: Boolean,
    val insufficient: Boolean,
) {
    val canSubmit: Boolean
        get() = !amountMissing && !amountNotPositive && !sameSource && !missingSource && !insufficient
}

fun validateTransfer(
    amount: Double?,
    fromId: String?,
    toId: String?,
    fromBalanceEur: Double?,
): TransferValidation {
    val missing = amount == null
    val notPositive = amount != null && amount <= 0.0
    val missingSource = fromId == null || toId == null
    val same = fromId != null && fromId == toId
    val insufficient = amount != null && amount > 0.0 && fromBalanceEur != null &&
        fromBalanceEur - amount < 0.0
    return TransferValidation(missing, notPositive, same, missingSource, insufficient)
}

/** Active (non-archived) sources, Main first — the pickers' universe. */
fun activeSources(sources: List<CashSourceEntity>): List<CashSourceEntity> =
    sources.filter { it.archivedAt == null }.sortedByDescending { it.isMain }

// ── Which wallet an entry sheet opens on ────────────────────────────────────

/**
 * The source a deposit / withdrawal / transfer sheet should open on.
 *
 * One precedence chain, three callers (owner defect 2026-08-17: *the in-app
 * Bezahlt / Erhalten buttons ignored the selected source while the Cash Wallet
 * widget honoured it*). Highest wins:
 *
 *  1. [prefillSourceId] — a QUEUED op being edited. Editing must never move
 *     money between wallets, so the op's own source outranks everything.
 *  2. [requestedSourceId] — what the caller asked for: the widget's configured
 *     wallet, or the Cash screen's current scope (`sourceFilter`). Honoured
 *     only if a source with that id is present in [sources]; the callers pass
 *     the ACTIVE list, so an archived or deleted wallet — a stale widget
 *     config, or a source archived while the screen was scoped to it — falls
 *     through instead of seeding an unselectable id.
 *  3. The main wallet — the "alle Quellen" default.
 *
 * Null only when [sources] has no main wallet and nothing else matched.
 */
fun cashEntrySourcePreselection(
    prefillSourceId: String?,
    requestedSourceId: String?,
    sources: List<CashSourceEntity>,
): String? =
    prefillSourceId
        ?: requestedSourceId?.takeIf { id -> sources.any { it.id == id } }
        ?: sources.firstOrNull { it.isMain }?.id

/**
 * The transfer sheet's TO side, given the resolved [fromId]. A queued op's own
 * target wins; otherwise the first source that is not the FROM side, so the
 * sheet never opens on the same-source state its own validation rejects.
 */
fun cashTransferToPreselection(
    prefillToSourceId: String?,
    fromId: String?,
    sources: List<CashSourceEntity>,
): String? =
    prefillToSourceId
        ?: sources.firstOrNull { it.id != fromId }?.id

// ── Pending queued cash ops (§7.4) ──────────────────────────────────────────

/** A queued cash op decoded for display alongside the movement stream. */
data class PendingCashRow(
    val opId: Long,
    val clientId: String,
    val type: OpType,
    /** Deposit/withdraw target, or transfer FROM. */
    val sourceId: String?,
    /** Transfer TO. */
    val toSourceId: String?,
    val amountEur: Double,
    val note: String?,
    val status: PendingUiStatus,
    /**
     * Both halves of the park reason, so the row can resolve it through
     * `rememberParkReason` (S6 P0-4): [errorCode] drives the translated sentence,
     * [serverError] is the diagnostic detail — or the pre-DB-v10 English prose
     * when there is no code to resolve.
     */
    val errorCode: String?,
    val serverError: String?,
    val createdAtMs: Long,
    /**
     * The queued movement's `executedAt` ISO instant, or null when it carries no
     * explicit date (a "today" deposit/withdraw — the field is omitted so the
     * server stamps it "now"). Used to restore the date picker when editing a
     * backdated queued entry in place.
     */
    val executedAt: String? = null,
)

/** Decode the OPEN queued cash ops of one portfolio, newest first. */
fun decodePendingCashRows(
    ops: List<SyncOpEntity>,
    json: Json,
    portfolioId: String,
): List<PendingCashRow> = ops
    .asSequence()
    .filter { it.portfolioId == portfolioId }
    .filter { it.status != OpStatus.DONE.wire }
    .mapNotNull { op -> decodePendingCashRow(op, json) }
    .sortedByDescending { it.opId }
    .toList()

fun decodePendingCashRow(op: SyncOpEntity, json: Json): PendingCashRow? {
    val type = OpType.fromWire(op.opType) ?: return null
    val status = OpStatus.fromWire(op.status)?.let(::pendingUiStatus) ?: return null
    return when (type) {
        OpType.CASH_DEPOSIT, OpType.CASH_WITHDRAW, OpType.CASH_FEE -> {
            val p = try {
                json.decodeFromString(CashOpPayload.serializer(), op.payloadJson)
            } catch (_: Exception) {
                return null
            }
            PendingCashRow(
                opId = op.id,
                clientId = op.clientId,
                type = type,
                sourceId = p.sourceId,
                toSourceId = null,
                amountEur = p.amountEur,
                note = p.note,
                status = status,
                errorCode = op.errorCode,
                serverError = op.serverError,
                createdAtMs = op.createdAtMs,
                executedAt = p.executedAt,
            )
        }

        OpType.CASH_TRANSFER -> {
            val p = try {
                json.decodeFromString(CashTransferOpPayload.serializer(), op.payloadJson)
            } catch (_: Exception) {
                return null
            }
            PendingCashRow(
                opId = op.id,
                clientId = op.clientId,
                type = type,
                sourceId = p.fromSourceId,
                toSourceId = p.toSourceId,
                amountEur = p.amountEur,
                note = p.note,
                status = status,
                errorCode = op.errorCode,
                serverError = op.serverError,
                createdAtMs = op.createdAtMs,
                executedAt = p.executedAt,
            )
        }

        else -> null
    }
}

/** ISO instant for a cash op enqueued now. */
fun cashExecutedAtNow(now: Instant = Instant.now()): String = now.toString()

/**
 * The `executedAt` ISO instant a dated deposit/withdrawal should carry for a
 * picked calendar [date], or `null` to OMIT the field entirely.
 *
 * - Today (or, defensively, a future day the no-future picker shouldn't allow)
 *   → `null`: the request omits `executedAt` (Json `explicitNulls=false`) and the
 *   server stamps the movement "now" — byte-identical on the wire to a plain
 *   undated deposit, so the common one-tap case is unchanged.
 * - A PAST day → **midday local** of that day, exactly mirroring the transaction
 *   form's [at.bettertrack.app.ui.portfolio.executedAtIso] past-date branch, so a
 *   backdated movement lands on the intended calendar day in every timezone
 *   (never slips across a day boundary via a UTC-midnight timestamp).
 */
fun cashExecutedAtOrNull(
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): String? {
    val today = now.atZone(zone).toLocalDate()
    return if (date >= today) null
    else date.atTime(12, 0).atZone(zone).toInstant().toString()
}
