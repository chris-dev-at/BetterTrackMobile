package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import java.time.Instant
import java.time.ZoneId

/**
 * The cash ledger's filter model and the roll-up it produces (owner ask
 * 2026-08-16: *"add filters for not just source but tags as well and timespan
 * between all and a specific one, and then have some all-around stats for the
 * selected stuff — like total and total plus and total minus"*).
 *
 * Everything here is pure Kotlin over rows the ledger already has. That is
 * deliberate and it is also the ONLY honest option: the platform's cash
 * aggregates (`/cash/summary`, `/cash/trends`) are keyed on a MONTH, not on an
 * arbitrary source × tags × window selection, so there is no server total to ask
 * for. Summing the rows on screen is not "the app calculating money" in the
 * §7.1 sense — every addend is a server-recorded movement amount and the result
 * describes exactly the list underneath it, which is why the UI labels it *for
 * this selection* rather than presenting it as a portfolio figure.
 */

/** The window a ledger selection covers. */
enum class CashLedgerWindow {
    /** Everything the ledger holds — the default, and the reset target. */
    ALL,
    DAYS_30,
    DAYS_90,
    YEAR_1,
    ;
}

/** The windows in display order (widest choice last, "All" first). */
val CASH_LEDGER_WINDOWS: List<CashLedgerWindow> = listOf(
    CashLedgerWindow.ALL,
    CashLedgerWindow.DAYS_30,
    CashLedgerWindow.DAYS_90,
    CashLedgerWindow.YEAR_1,
)

/**
 * The earliest instant a window admits, or null for [CashLedgerWindow.ALL].
 *
 * Counted back in whole DAYS from the start of today rather than from "now
 * minus N×24h": a movement booked this morning must not fall out of "last 30
 * days" because the user opened the screen in the afternoon, and a ledger row's
 * meaningful unit is the day it was booked on.
 */
fun cashWindowStartMs(window: CashLedgerWindow, nowMs: Long, zone: ZoneId): Long? {
    val days = when (window) {
        CashLedgerWindow.ALL -> return null
        CashLedgerWindow.DAYS_30 -> 30L
        CashLedgerWindow.DAYS_90 -> 90L
        CashLedgerWindow.YEAR_1 -> 365L
    }
    val startOfToday = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return startOfToday.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * One ledger selection: which source, which tags, which window.
 *
 * A value class rather than three loose parameters so "the current selection" is
 * one thing that can be passed to the list, to the roll-up and to the reset
 * affordance — and so [isActive] cannot disagree with what the filters actually
 * do.
 *
 * @param sourceId null = every source.
 * @param tagIds empty = every tag AND untagged rows. A row matches when it
 *   carries **any** of the selected tags (OR, not AND): tags are labels, and a
 *   user picking "Groceries" and "Fuel" is asking to see both, not the rows that
 *   are somehow both at once.
 */
data class CashLedgerSelection(
    val sourceId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val window: CashLedgerWindow = CashLedgerWindow.ALL,
) {
    /** True when this selection narrows anything — drives the reset affordance. */
    val isActive: Boolean
        get() = sourceId != null || tagIds.isNotEmpty() || window != CashLedgerWindow.ALL
}

/**
 * The movements a selection admits, in the order they arrived (newest first,
 * as the DAO delivers them).
 *
 * The three filters AND together — each one narrows what the previous left — so
 * "Savings account", "Groceries" and "last 30 days" answers the question the
 * user actually asked. Within the tag filter alone the match is OR; see
 * [CashLedgerSelection.tagIds].
 */
fun filterCashMovements(
    movements: List<CashMovementEntity>,
    selection: CashLedgerSelection,
    nowMs: Long,
    zone: ZoneId,
): List<CashMovementEntity> {
    val from = cashWindowStartMs(selection.window, nowMs, zone)
    return movements.filter { m ->
        if (selection.sourceId != null && m.sourceId != selection.sourceId) return@filter false
        if (from != null && m.executedAtMs < from) return@filter false
        if (selection.tagIds.isNotEmpty()) {
            val rowTags = decodeTagIds(m.tagIds)
            if (rowTags.none { it in selection.tagIds }) return@filter false
        }
        true
    }
}

/**
 * What a selection adds up to.
 *
 * @param inflowEur the positive side, as a POSITIVE number.
 * @param outflowEur the negative side, also as a positive number — it is
 *   rendered under a "out" label with its own red ink, and a figure that reads
 *   `−60,00 €` beneath a heading that already says *out* states the sign twice.
 * @param netEur inflow minus outflow, signed. This is the one number that keeps
 *   its sign, because "net" is exactly the question of which way it went.
 * @param count how many movements are in the selection — the honest denominator
 *   for everything above, and the thing that makes an empty selection legible.
 */
data class CashLedgerStats(
    val inflowEur: Double,
    val outflowEur: Double,
    val netEur: Double,
    val count: Int,
) {
    companion object {
        val EMPTY = CashLedgerStats(0.0, 0.0, 0.0, 0)
    }
}

/**
 * Roll up a selection.
 *
 * Sign is read from the AMOUNT, not from the movement's kind. The kinds are an
 * open set that has grown twice already (`fee`, `dividend`, the transfer pair),
 * and a roll-up that classified by kind would silently drop every kind added
 * after it was written. The stored `amountEur` is signed by the server for
 * exactly this purpose, so it is the thing to trust.
 *
 * Exact zeros land in neither column — they are counted, and they move nothing.
 */
fun cashLedgerStats(movements: List<CashMovementEntity>): CashLedgerStats {
    var inflow = 0.0
    var outflow = 0.0
    movements.forEach { m ->
        val amount = m.amountEur
        if (amount > 0.0) inflow += amount else if (amount < 0.0) outflow += -amount
    }
    return CashLedgerStats(
        inflowEur = inflow,
        outflowEur = outflow,
        netEur = inflow - outflow,
        count = movements.size,
    )
}
