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
    /**
     * Hard block (§6.2, case 1): the cash-coupled write can't be funded EVEN
     * TODAY — it would drive the CURRENT Main balance below zero. This is the
     * only cash state that keeps [canSubmit] false.
     */
    val insufficientCash: Boolean = false,
    /**
     * Soft, NON-blocking warning (§6.2, case 2 / platform #378): a pay-from-cash
     * BUY that the Main wallet covers TODAY but did NOT cover as of its own
     * (backdated) date. The buy is allowed — the server settles the cash leg as
     * of today (`settleCashAsOfToday`) while the stock trade keeps its backdated
     * date. Never blocks submission; it only tells the user what will happen.
     */
    val backdatedCashWarning: Boolean = false,
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
 * plus the three-way cash outcome for a pay-from-cash write (platform #378):
 *
 * 1. can't afford it EVEN TODAY (overdraws the CURRENT Main balance) →
 *    [TxFormValidation.insufficientCash] HARD block.
 * 2. a BUY affordable today but short AS OF its own backdated date →
 *    [TxFormValidation.backdatedCashWarning] soft, non-blocking warning (the
 *    server dates the cash leg today, the stock trade stays backdated).
 * 3. covered as of the date → no cash flag at all.
 *
 * Overselling is only warned about — final validation is the server's.
 *
 * [currentCashEur] is the Main wallet balance NOW (the affordability gate);
 * [asOfCashEur] is the Main cash spendable AS OF the buy's date (the running
 * minimum from that instant on — see [availableMainCashAsOf]). Either may be
 * null (unknown / cold cache); an unknown current balance never hard-blocks.
 */
fun validateTxForm(
    assetSelected: Boolean,
    quantity: Double?,
    price: Double?,
    fee: Double?,
    isBuy: Boolean,
    cashCoupled: Boolean,
    currentCashEur: Double?,
    asOfCashEur: Double?,
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
    val cashActive = fieldsOk && cashCoupled
    // Resulting cash if the movement settled at "now" (the CURRENT-balance basis).
    // The cash leg of a backdated buy settles today (#378), so this — not the
    // as-of balance — is the true affordability gate.
    val afterCurrent = if (cashActive && currentCashEur != null) {
        cashAfterPreview(currentCashEur, isBuy, quantity!!, price!!, fee ?: 0.0)
    } else {
        null
    }
    // Case 1 — can't afford it even today: hard block.
    val insufficient = afterCurrent != null && afterCurrent < 0.0
    // Case 2 — a BUY fine today but short as of its backdated date: soft warning.
    val afterAsOf = if (cashActive && isBuy && asOfCashEur != null) {
        cashAfterPreview(asOfCashEur, true, quantity!!, price!!, fee ?: 0.0)
    } else {
        null
    }
    val backdatedShort = afterCurrent != null && afterCurrent >= 0.0 &&
        afterAsOf != null && afterAsOf < 0.0
    val oversell = fieldsOk && !isBuy && heldQuantity != null && quantity!! > heldQuantity

    return TxFormValidation(
        quantityError = quantityError,
        priceError = priceError,
        feeError = feeError,
        assetMissing = !assetSelected,
        insufficientCash = insufficient,
        backdatedCashWarning = backdatedShort,
        oversellWarning = oversell,
    )
}

// ── Uncovered (over-)sell (platform PR #429, Step 19) ────────────────────────

/** Quantities below this are treated as equal (float noise on fractional shares). */
const val UNCOVERED_QTY_EPS = 1e-9

/**
 * State of a possible uncovered sell: a SELL whose quantity exceeds the held
 * amount (a null held count means the asset isn't held ⇒ zero, matching the
 * platform's "incl. zero holding"). Buys and covered sells are never active.
 */
data class UncoveredSell(
    /** True ⇒ this write needs the explicit "sell anyway" acknowledgment. */
    val active: Boolean,
    /** Shares sold beyond the held amount (0 when not active). */
    val uncoveredQuantity: Double,
    /** Held amount used for the calc (null held → 0). */
    val heldQuantity: Double,
)

fun uncoveredSellState(
    isBuy: Boolean,
    quantity: Double?,
    heldQuantity: Double?,
): UncoveredSell {
    val held = (heldQuantity ?: 0.0).coerceAtLeast(0.0)
    if (isBuy || quantity == null || quantity <= 0.0) return UncoveredSell(false, 0.0, held)
    val over = quantity - held
    return if (over > UNCOVERED_QTY_EPS) UncoveredSell(true, over, held) else UncoveredSell(false, 0.0, held)
}

/**
 * Whether an uncovered sell may be submitted: only once the user ticked the
 * acknowledgment. Every non-uncovered write is unaffected (returns true).
 */
fun uncoveredSellSubmitOk(uncovered: UncoveredSell, acknowledged: Boolean): Boolean =
    !uncovered.active || acknowledged

/**
 * Parse the OPTIONAL buy-in price for the uncovered part: a positive number in
 * the asset's native currency, else null (the server then bases it on the sale
 * price). Blank / non-positive input yields null, never an error.
 */
fun parseUncoveredEntryPrice(raw: String): Double? =
    parseLocalizedDecimal(raw)?.takeIf { it > 0.0 && it.isFinite() }

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
 * The balance the pay-from-cash coupling actually draws on: the MAIN/default
 * cash source, NOT the sum of every source (§6.2 owner fix). A buy paid "from
 * cash" deducts from the default wallet server-side, so Max-buy and the
 * cash-after preview must size against that wallet alone — sizing against the
 * summed total over-estimates and the server then rejects "insufficient".
 * Null when no main source is cached yet (balance treated as unknown, not zero).
 */
fun mainCashSourceBalanceEur(sources: List<at.bettertrack.app.data.db.CashSourceEntity>): Double? =
    sources.firstOrNull { it.isMain }?.balanceEur

/** Tolerance (one cent) for reconciling the cached Main ledger to the live balance. */
const val CASH_LEDGER_RECONCILE_EPS = 0.01

/**
 * The Main cash a PAY-FROM-CASH buy dated [executedAtMs] may actually draw on.
 *
 * The platform keeps a cash source non-negative at EVERY instant of its ledger,
 * so a buy backdated into the past can only spend the LOWEST the Main running
 * balance ever dips from the buy's own date onward — NOT the (usually higher)
 * balance today. Inserting a spend of C at time e shifts the whole balance curve
 * at/after e down by C, so the ledger stays valid exactly when
 * `C ≤ min(runningBalance(t) : t ≥ e)`. This returns that minimum: the cash
 * "available as of" [executedAtMs].
 *
 * Without it the client sizes a backdated buy against TODAY's balance while the
 * server sizes it against the balance ON THE TRANSACTION'S DATE — the exact
 * mismatch behind the owner's false "Insufficient cash balance." (a €360 buy
 * backdated to a day the Main source still held €0 looked affordable on the
 * client, the server rejected it).
 *
 * [movements] are the MAIN source's movements as (executedAtMs, signedEur) in ANY
 * order (deposits / sell-proceeds +, buys / withdrawals / transfers −). Their
 * signed sum must reconcile to [currentBalanceEur]; when it does NOT (a partial
 * or not-yet-synced ledger) this returns null, so the caller falls back to the
 * current balance and never HARD-blocks on an untrustworthy reconstruction.
 *
 * A buy dated at/after the newest movement simply yields the current balance, so
 * the everyday "record a buy now" path is unchanged.
 */
fun availableMainCashAsOf(
    movements: List<Pair<Long, Double>>,
    currentBalanceEur: Double,
    executedAtMs: Long,
): Double? {
    val total = movements.sumOf { it.second }
    if (kotlin.math.abs(total - currentBalanceEur) > CASH_LEDGER_RECONCILE_EPS) return null
    // Time ascending; at the SAME instant settle credits before debits (a same-day
    // funding deposit is available to that day's buy) so day-granular backdated
    // entries sharing one timestamp don't reconstruct a phantom intra-instant
    // negative the server never actually had.
    val sorted = movements.sortedWith(compareBy({ it.first }, { -it.second }))
    // Floor: everything strictly BEFORE the buy's instant (a buy can't lean on a
    // same-instant-or-later deposit to fund itself).
    var running = 0.0
    for ((t, amt) in sorted) if (t < executedAtMs) running += amt
    var minFromE = running
    // Then the running balance dips from the buy's date to the end — the buy must
    // clear the lowest of them all.
    for ((t, amt) in sorted) {
        if (t >= executedAtMs) {
            running += amt
            if (running < minFromE) minFromE = running
        }
    }
    return minFromE
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
 * The MOST RECENT day an ASCENDING (date, close) series was at [price] — the
 * reverse of [closeOnOrBefore], mirroring the web app's `dateForPrice` (#226) so
 * the two platforms pick the same day. With closes-only history (no intraday
 * OHLC), a price is "at" a day when EITHER that day closed exactly at it, OR the
 * close-to-close move CROSSED through it — i.e. [price] lies strictly between the
 * previous close and that day's close, in which case the crossing is attributed
 * to the later day. Scans newest-first and returns the first hit's day.
 *
 * The strict interior (`> lo && < hi`) is deliberate: a boundary value equals one
 * of the two closes and so belongs to THAT day's own exact-close check, keeping an
 * exact historical close on the day it closed at rather than the later day it was
 * merely a starting point for.
 *
 * Returns null when the series never reached [price] in the available history (or
 * [price] is non-finite), so the caller leaves the date unchanged and says so — it
 * never guesses a date.
 */
fun mostRecentDateAtPrice(closes: List<Pair<LocalDate, Double>>, price: Double): LocalDate? {
    if (!price.isFinite()) return null
    for (i in closes.indices.reversed()) {
        val (curDate, curClose) = closes[i]
        // Exact close wins for its own day (also covers a single-point series,
        // which has no pair to cross).
        if (curClose == price) return curDate
        if (i > 0) {
            val prevClose = closes[i - 1].second
            val lo = minOf(prevClose, curClose)
            val hi = maxOf(prevClose, curClose)
            if (price > lo && price < hi) return curDate
        }
    }
    return null
}

/**
 * Format a decimal for an input field (quantity or price): full precision
 * (round-DOWN, no scientific notation), trailing zeros trimmed, locale decimal
 * separator so it reads correctly in de-AT (the field parser tolerates '.'/',').
 */
fun formatDecimalForInput(value: Double, locale: Locale, maxDecimals: Int = 8): String {
    // BigDecimal(value.toString()) keeps the double's SHORTEST decimal form —
    // BigDecimal(value) would expose the binary expansion and a stored 231.49
    // could truncate to 231.489999 (the float artifact the owner directive bans).
    val bd = java.math.BigDecimal(value.toString())
        .setScale(maxDecimals, java.math.RoundingMode.DOWN)
        .stripTrailingZeros()
    return localizedPlain(bd, locale)
}

/**
 * Format a MONEY value the app writes into an input field from market data
 * (e.g. the price autofilled by the date→price link). Owner directive
 * 2026-07-12: such prefills carry cents, TRUNCATED — 231.499320001 autofills
 * as 231.49 (explicitly cut, never rounded half-up). String-faithful cut, so
 * a stored 231.49 stays 231.49. Sub-cent unit prices are the exception (a
 * cents-cut would erase them to 0.00): they keep up to 6 significant decimals,
 * truncated, per the display directive's tiny-price rule. Applies ONLY to
 * programmatic market-data prefills — stored-value edit prefills and anything
 * the user types stay exact.
 */
fun truncateMoneyForPrefill(value: Double, locale: Locale): String {
    val exact = java.math.BigDecimal(value.toString())
    val cut = if (exact.abs() < java.math.BigDecimal("0.01") && exact.signum() != 0) {
        // Keep 6 significant decimals: digits-after-leading-zeros = scale-precision.
        val sigScale = exact.scale() - exact.precision() + 6
        exact.setScale(minOf(exact.scale(), sigScale), java.math.RoundingMode.DOWN)
            .stripTrailingZeros()
    } else {
        exact.setScale(2, java.math.RoundingMode.DOWN)
    }
    return localizedPlain(cut, locale)
}

/** Plain (non-scientific) rendering with the locale's decimal separator. */
private fun localizedPlain(bd: java.math.BigDecimal, locale: Locale): String {
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

/**
 * The `executedAt` instant in epoch-ms a picked [date] will be stamped with —
 * mirrors [executedAtIso] exactly (today/future → now; a past day → midday local)
 * so the point-in-time cash check ([availableMainCashAsOf]) aligns with what is
 * actually submitted to the server.
 */
fun executedAtMsFor(date: LocalDate, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): Long {
    val today = now.atZone(zone).toLocalDate()
    return if (date >= today) now.toEpochMilli()
    else date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
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
