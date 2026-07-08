package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.TxOpPayload
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure logic behind the Step-8 transaction form and the pending-row display
 * (§6.2/§7.4). No Android/Compose imports — everything here is unit-tested.
 */

// ── Numeric input ────────────────────────────────────────────────────────────

/**
 * Parse user-typed decimal input, tolerant of locale separators: both `,` and
 * `.` accepted as THE decimal separator (whichever occurs last), the other
 * treated as grouping and stripped. Returns null for empty/garbage input.
 */
fun parseLocalizedDecimal(raw: String): Double? {
    val s = raw.trim().replace(" ", "").replace(" ", "")
    if (s.isEmpty()) return null
    val lastComma = s.lastIndexOf(',')
    val lastDot = s.lastIndexOf('.')
    val normalized = when {
        lastComma < 0 && lastDot < 0 -> s
        lastComma > lastDot -> s.replace(".", "").replace(',', '.')
        else -> s.replace(",", "")
    }
    // Reject stray separators like "1.2.3" after normalization.
    if (normalized.count { it == '.' } > 1) return null
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** Keep only digits + at most one decimal separator while the user types. */
fun sanitizeDecimalInput(raw: String, maxDecimals: Int = 8): String {
    val sb = StringBuilder()
    var sepSeen = false
    var decimals = 0
    for (c in raw) {
        when {
            c.isDigit() -> {
                if (sepSeen && decimals >= maxDecimals) continue
                if (sepSeen) decimals++
                sb.append(c)
            }

            (c == ',' || c == '.') && !sepSeen -> {
                sepSeen = true
                sb.append(c)
            }
        }
    }
    return sb.toString()
}

// ── Cash-after preview (§6.2) ────────────────────────────────────────────────

/**
 * The live cash-after preview, computed against the CACHED balance (§7.3 —
 * offline validation is best-effort; the server is the final validator).
 * A buy paid from cash consumes quantity×price + fee; a sell adds
 * quantity×price − fee. Display-only proportion of server numbers — the server
 * recomputes authoritative balances on sync.
 */
fun cashAfterPreview(
    balanceEur: Double,
    isBuy: Boolean,
    quantity: Double,
    price: Double,
    fee: Double,
): Double {
    val gross = quantity * price
    return if (isBuy) balanceEur - (gross + fee) else balanceEur + (gross - fee)
}

/** Total order value shown under the inputs: qty × price + fee for buys, − fee for sells. */
fun orderTotal(isBuy: Boolean, quantity: Double, price: Double, fee: Double): Double =
    if (isBuy) quantity * price + fee else quantity * price - fee

// ── Sticky toggle default (§6.2) ────────────────────────────────────────────

/**
 * Cash-coupling toggle default: the locally-sticky per-portfolio value when
 * the user ever flipped it, else the portfolio's server-side default.
 */
fun resolveCashCouplingDefault(localSticky: Boolean?, serverDefault: Boolean): Boolean =
    localSticky ?: serverDefault

// ── Validation ───────────────────────────────────────────────────────────────

/** Field-level outcome of validating the form for submission. */
data class TxFormValidation(
    val quantityError: TxFieldError? = null,
    val priceError: TxFieldError? = null,
    val feeError: TxFieldError? = null,
    val assetMissing: Boolean = false,
    /** Hard block (§6.2): cash-coupled write would drive cash below zero. */
    val insufficientCash: Boolean = false,
    /** Soft warning only — the server re-validates oversell (§7.3). */
    val oversellWarning: Boolean = false,
) {
    val canSubmit: Boolean
        get() = quantityError == null && priceError == null && feeError == null &&
            !assetMissing && !insufficientCash
}

enum class TxFieldError { MISSING, NOT_POSITIVE, NEGATIVE }

/**
 * Validate the form (§6.2): quantity > 0, price ≥ 0, fee ≥ 0, asset chosen;
 * cash coupling must never go silently negative against the cached balance
 * (hard block with a clear inline error); overselling is only warned about —
 * final validation is the server's.
 */
fun validateTxForm(
    assetSelected: Boolean,
    quantity: Double?,
    price: Double?,
    fee: Double?,
    isBuy: Boolean,
    cashCoupled: Boolean,
    cachedCashEur: Double?,
    heldQuantity: Double?,
): TxFormValidation {
    val quantityError = when {
        quantity == null -> TxFieldError.MISSING
        quantity <= 0.0 -> TxFieldError.NOT_POSITIVE
        else -> null
    }
    val priceError = when {
        price == null -> TxFieldError.MISSING
        price < 0.0 -> TxFieldError.NEGATIVE
        else -> null
    }
    val feeError = if (fee != null && fee < 0.0) TxFieldError.NEGATIVE else null

    val fieldsOk = quantityError == null && priceError == null && feeError == null && assetSelected
    val insufficient = fieldsOk && cashCoupled && cachedCashEur != null &&
        cashAfterPreview(cachedCashEur, isBuy, quantity!!, price!!, fee ?: 0.0) < 0.0
    val oversell = fieldsOk && !isBuy && heldQuantity != null && quantity!! > heldQuantity

    return TxFormValidation(
        quantityError = quantityError,
        priceError = priceError,
        feeError = feeError,
        assetMissing = !assetSelected,
        insufficientCash = insufficient,
        oversellWarning = oversell,
    )
}

// ── Max-quantity + date→price helpers (§6.2, owner requests) ─────────────────

/**
 * Max quantity affordable from a KNOWN cash balance buying at [price], after
 * subtracting [fee]. Zero when the price is non-positive. Rounds are the
 * caller's job (fill uses round-DOWN so the buy never exceeds cash).
 */
fun maxAffordableQuantity(cashEur: Double, price: Double, fee: Double): Double {
    if (price <= 0.0) return 0.0
    return ((cashEur - fee) / price).coerceAtLeast(0.0)
}

/**
 * The close on / just-before [target] from an ASCENDING (date, close) series:
 * the exact day if present, else the most recent prior trading day, else the
 * earliest close (target precedes all data). Null only for an empty series.
 */
fun closeOnOrBefore(closes: List<Pair<LocalDate, Double>>, target: LocalDate): Double? {
    if (closes.isEmpty()) return null
    var result: Double? = null
    for ((d, c) in closes) {
        if (d <= target) result = c else break
    }
    return result ?: closes.first().second
}

/**
 * Format a decimal for an input field (quantity or price): full precision
 * (round-DOWN, no scientific notation), trailing zeros trimmed, locale decimal
 * separator so it reads correctly in de-AT (the field parser tolerates '.'/',').
 */
fun formatDecimalForInput(value: Double, locale: Locale, maxDecimals: Int = 8): String {
    val bd = java.math.BigDecimal(value)
        .setScale(maxDecimals, java.math.RoundingMode.DOWN)
        .stripTrailingZeros()
    val plain = if (bd.scale() < 0) bd.setScale(0).toPlainString() else bd.toPlainString()
    val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return if (sep != '.') plain.replace('.', sep) else plain
}

// ── Dates ────────────────────────────────────────────────────────────────────

/**
 * The op's `executedAt` ISO instant for a picked calendar date: today keeps
 * the actual current time; a past day is stamped mid-day local time (the
 * ledger displays dates day-granular). Future dates are the picker's job to
 * prevent.
 */
fun executedAtIso(date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val today = now.atZone(zone).toLocalDate()
    return if (date >= today) {
        now.toString()
    } else {
        date.atTime(12, 0).atZone(zone).toInstant().toString()
    }
}

// ── Note marker preservation (synced edits) ─────────────────────────────────

private val MARKER_REGEX = Regex("""\[bt:[0-9a-fA-F-]{8,}]""")

/**
 * When EDITING a synced transaction, the original note may carry the
 * ` [bt:<uuid>]` reconcile marker from its offline birth. The user edits only
 * the visible part ([displayNote]); this re-attaches the original marker so
 * it stays intact and invisible (the cleanup/reconcile tooling keys on it).
 */
fun mergeNotePreservingMarker(userNote: String?, originalNote: String?): String? {
    val marker = originalNote?.let { MARKER_REGEX.find(it)?.value }
    val visible = userNote?.trim().orEmpty()
    return when {
        marker == null -> visible.takeIf { it.isNotEmpty() }
        visible.isEmpty() -> marker
        else -> "${visible.take(1000 - marker.length - 1)} $marker"
    }
}

// ── Pending-op display models (§7.4) ─────────────────────────────────────────

/** UI status of a queued op (display grouping of [OpStatus]). */
enum class PendingUiStatus { PENDING, SYNCING, NEEDS_ATTENTION, DONE }

fun pendingUiStatus(status: OpStatus): PendingUiStatus = when (status) {
    OpStatus.PENDING -> PendingUiStatus.PENDING
    OpStatus.IN_FLIGHT -> PendingUiStatus.SYNCING
    OpStatus.NEEDS_ATTENTION -> PendingUiStatus.NEEDS_ATTENTION
    OpStatus.DONE -> PendingUiStatus.DONE
}

/** A queued buy/sell decoded for display alongside the ledger (§7.1/§7.4). */
data class PendingTxRow(
    val opId: Long,
    val clientId: String,
    val portfolioId: String?,
    val isBuy: Boolean,
    val assetId: String,
    val assetSymbol: String,
    val assetName: String?,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    val executedAtMs: Long,
    val note: String?,
    val cashCoupled: Boolean,
    val status: PendingUiStatus,
    val serverError: String?,
    val createdAtMs: Long,
    val payload: TxOpPayload,
)

/**
 * Decode the queue's OPEN buy/sell ops into display rows (newest enqueued
 * first). Non-transaction ops and undecodable payloads are skipped here —
 * the Pending-sync screen still lists them generically.
 */
fun decodePendingTxRows(
    ops: List<SyncOpEntity>,
    json: Json,
    portfolioId: String? = null,
    includeDone: Boolean = false,
): List<PendingTxRow> = ops
    .asSequence()
    .filter { it.opType == OpType.TX_BUY.wire || it.opType == OpType.TX_SELL.wire }
    .filter { portfolioId == null || it.portfolioId == portfolioId }
    .filter { includeDone || it.status != OpStatus.DONE.wire }
    .mapNotNull { op -> decodePendingTxRow(op, json) }
    .sortedByDescending { it.opId }
    .toList()

fun decodePendingTxRow(op: SyncOpEntity, json: Json): PendingTxRow? {
    val payload = try {
        json.decodeFromString(TxOpPayload.serializer(), op.payloadJson)
    } catch (_: Exception) {
        return null
    }
    val status = OpStatus.fromWire(op.status) ?: return null
    val isBuy = op.opType == OpType.TX_BUY.wire
    return PendingTxRow(
        opId = op.id,
        clientId = op.clientId,
        portfolioId = op.portfolioId,
        isBuy = isBuy,
        assetId = payload.assetId,
        assetSymbol = payload.assetSymbol ?: payload.assetId.take(8),
        assetName = payload.assetName,
        quantity = payload.quantity,
        price = payload.price,
        fee = payload.fee,
        executedAtMs = parseInstantMsOrZero(payload.executedAt),
        note = payload.note,
        cashCoupled = (payload.payFromCash == true) || (payload.addProceedsToCash == true),
        status = pendingUiStatus(status),
        serverError = op.serverError,
        createdAtMs = op.createdAtMs,
        payload = payload,
    )
}

private fun parseInstantMsOrZero(iso: String): Long = try {
    Instant.parse(iso).toEpochMilli()
} catch (_: Exception) {
    0L
}

/** Apply the §6.2 display filters to pending rows (same rules as the ledger). */
fun filterPendingTxRows(
    rows: List<PendingTxRow>,
    side: TxSideFilter,
    assetId: String?,
): List<PendingTxRow> = rows.filter { row ->
    val sideOk = when (side) {
        TxSideFilter.ALL -> true
        TxSideFilter.BUY -> row.isBuy
        TxSideFilter.SELL -> !row.isBuy
    }
    sideOk && (assetId == null || row.assetId == assetId)
}
