package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The cash ledger's filter model and the roll-up it produces.
 *
 * Owner ask 2026-08-16: *"add filters for not just source but tags as well and
 * timespan between all and a specific one, and then have some all-around stats
 * for the selected stuff — like total and total plus and total minus"*.
 * Owner verdict 2026-08-17 on what shipped: **too basic**. This is v2, built to
 * the commissioned design study (`DESIGN_NOTES_LEDGER.md`): multi-select
 * sources and tags, an explicit custom date range beside the rolling presets,
 * faceted option counts, and a stats block that says more than three sums.
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

// ══════════════════════════ 1. The date facet ═══════════════════════════════

/** The window a ledger selection covers. */
enum class CashLedgerWindow {
    /** Everything the ledger holds — the default, and the reset target. */
    ALL,
    DAYS_30,
    DAYS_90,
    YEAR_1,

    /**
     * Two dates the user picked (v2).
     *
     * A separate member rather than "a preset whose dates happen to be set",
     * because the two behave differently over time: a preset is a *rolling*
     * window that must re-resolve every time the screen is opened, and a custom
     * range is a fixed pair of days that must NOT drift. Collapsing them would
     * make one of those two wrong.
     */
    CUSTOM,
    ;
}

/** The windows in display order — presets first, custom last (its own row). */
val CASH_LEDGER_WINDOWS: List<CashLedgerWindow> = listOf(
    CashLedgerWindow.ALL,
    CashLedgerWindow.DAYS_30,
    CashLedgerWindow.DAYS_90,
    CashLedgerWindow.YEAR_1,
)

/**
 * A resolved, inclusive day range — what every window becomes before it filters
 * anything.
 *
 * The design study is explicit that even a preset resolves to dates in the UI
 * ("after application, even a preset becomes a resolved date token rather than
 * staying an opaque `30 Tage` label"), so resolution is not a rendering detail
 * that could live in the composable: the token, the filter, the stats scope and
 * the export's provenance columns must all be the same two days.
 */
data class CashDateRange(val start: LocalDate, val end: LocalDate) {
    /** Inclusive length in days — 1 for a same-day range, never 0. */
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
}

/**
 * The earliest instant a window admits, or null for [CashLedgerWindow.ALL].
 *
 * Counted back in whole DAYS from the start of today rather than from "now
 * minus N×24h": a movement booked this morning must not fall out of "last 30
 * days" because the user opened the screen in the afternoon, and a ledger row's
 * meaningful unit is the day it was booked on.
 *
 * Kept for the preset arithmetic that [resolveCashRange] and its tests lean on.
 */
fun cashWindowStartMs(window: CashLedgerWindow, nowMs: Long, zone: ZoneId): Long? {
    val days = cashWindowDays(window) ?: return null
    val startOfToday = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return startOfToday.minusDays(days - 1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** How many days back a rolling preset reaches, inclusive of today. */
fun cashWindowDays(window: CashLedgerWindow): Long? = when (window) {
    CashLedgerWindow.ALL, CashLedgerWindow.CUSTOM -> null
    CashLedgerWindow.DAYS_30 -> 30L
    CashLedgerWindow.DAYS_90 -> 90L
    CashLedgerWindow.YEAR_1 -> 365L
}

/**
 * Is this pair of days a usable range?
 *
 * Two rules, and the second one is the interesting one:
 *
 *  - both dates must exist and the end may not precede the start;
 *  - the end may not be in the future **unless the ledger actually holds a
 *    movement out there**. Cash movements are booked, not predicted, so an end
 *    date past today normally selects nothing and is a slip. But standing
 *    orders and imports do occasionally land a row with a future booking date,
 *    and refusing to select a row the user can see in the list would be the app
 *    calling its own data impossible. [latest] is that escape hatch: pass the
 *    newest booked date the ledger holds.
 */
fun cashRangeValid(
    start: LocalDate?,
    end: LocalDate?,
    today: LocalDate,
    latest: LocalDate? = null,
): Boolean {
    if (start == null || end == null) return false
    if (end.isBefore(start)) return false
    return !end.isAfter(cashMaxSelectableDate(today, latest))
}

/** The latest day a picker may offer — today, or the newest booked row if later. */
fun cashMaxSelectableDate(today: LocalDate, latest: LocalDate?): LocalDate =
    if (latest != null && latest.isAfter(today)) latest else today

// ═════════════════════════ 2. The selection ═════════════════════════════════

/**
 * One ledger selection: which sources, which tags, which window.
 *
 * A value class rather than five loose parameters so "the current selection" is
 * one thing that can be passed to the list, to the roll-up, to the export and
 * to the reset affordance — and so [isActive] cannot disagree with what the
 * filters actually do.
 *
 * @param sourceIds empty = every source. Multiple sources combine with **OR**
 *   (v2; v1 allowed exactly one). "Show me the current account and the travel
 *   cash" is a question a bookkeeping ledger must be able to answer, and the
 *   single-select chip row could not.
 * @param tagIds empty = every tag AND untagged rows. A row matches when it
 *   carries **any** of the selected tags (OR, not AND): tags are labels, and a
 *   user picking "Groceries" and "Fuel" is asking to see both, not the rows that
 *   are somehow both at once.
 * @param customStart / [customEnd] only meaningful under [CashLedgerWindow.CUSTOM].
 *   They are kept even when the window is a preset so switching back to Custom
 *   restores what the user had picked rather than emptying the fields.
 */
data class CashLedgerSelection(
    val sourceIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(),
    val window: CashLedgerWindow = CashLedgerWindow.ALL,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
) {
    /** True when this selection narrows anything — drives the reset affordance. */
    val isActive: Boolean
        get() = dateActive || sourceIds.isNotEmpty() || tagIds.isNotEmpty()

    /** True when the DATE facet alone narrows anything. */
    val dateActive: Boolean
        get() = when (window) {
            CashLedgerWindow.ALL -> false
            CashLedgerWindow.CUSTOM -> customStart != null && customEnd != null
            else -> true
        }

    /**
     * How many FACETS are active — never how many values are selected.
     *
     * The summary line says "3 filters active" and the study is explicit that
     * this counts facets: a user who ticked six tags has narrowed by one thing,
     * not by six, and telling them otherwise makes the ledger feel unmanageable.
     */
    val facetCount: Int
        get() = (if (dateActive) 1 else 0) +
            (if (sourceIds.isNotEmpty()) 1 else 0) +
            (if (tagIds.isNotEmpty()) 1 else 0)
}

/**
 * The two days a selection's date facet resolves to, or null when it does not
 * restrict anything (ALL, or a half-finished custom range).
 *
 * Presets are rolling windows ENDING TODAY: "30 Tage" is today plus the
 * preceding 29 booking dates, which is the same arithmetic [cashWindowStartMs]
 * has always done.
 */
fun resolveCashRange(selection: CashLedgerSelection, today: LocalDate): CashDateRange? =
    when (selection.window) {
        CashLedgerWindow.ALL -> null
        CashLedgerWindow.CUSTOM -> {
            val s = selection.customStart
            val e = selection.customEnd
            if (s == null || e == null || e.isBefore(s)) null else CashDateRange(s, e)
        }

        else -> {
            val days = cashWindowDays(selection.window)!!
            CashDateRange(today.minusDays(days - 1), today)
        }
    }

/**
 * The half-open millisecond interval a day range covers: `[start 00:00, day
 * after end 00:00)`.
 *
 * Half-open on purpose, and computed from the day AFTER the end rather than
 * from "end 23:59:59.999". The study calls this out and it is a real bug class:
 * on a day that gains an hour, a naive end-of-day is not the last instant of
 * that day, and a movement booked in the repeated hour would fall out of a
 * range that visibly contains its date.
 */
fun cashRangeMillis(range: CashDateRange, zone: ZoneId): LongRange {
    val from = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
    val until = range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return from until until
}

/**
 * A facet that has every available value ticked is not a filter.
 *
 * The study: *"selecting every available value in a facet normalizes to no
 * filter, avoiding a misleading 'active' state that changes nothing."* Applied
 * on commit, so the sheet itself still shows the user's ticks while they work.
 */
fun normalizeCashFacet(selected: Set<String>, available: Set<String>): Set<String> {
    if (selected.isEmpty() || available.isEmpty()) return selected
    val kept = selected intersect available
    return if (kept.size >= available.size) emptySet() else kept
}

/**
 * The selection as one flat string, so `rememberSaveable` can carry it across a
 * rotation and a process death.
 *
 * One encoded value rather than five pieces of saved state, because the pieces
 * are only meaningful together: a restore that brought back the custom dates
 * but lost the window would silently widen the ledger, which is exactly the bug
 * the v1 screen's comment about rotation was worried about. Unit separator
 * (U+001F) between fields and record separator (U+001E) inside a set — two
 * characters a ULID, a preset name and an ISO date all provably cannot contain.
 */
fun encodeCashSelection(selection: CashLedgerSelection): String = listOf(
    selection.window.name,
    selection.customStart?.toString().orEmpty(),
    selection.customEnd?.toString().orEmpty(),
    selection.sourceIds.sorted().joinToString(RECORD_SEP),
    selection.tagIds.sorted().joinToString(RECORD_SEP),
).joinToString(UNIT_SEP)

/** Inverse of [encodeCashSelection]; anything unparseable restores the default. */
fun decodeCashSelection(raw: String): CashLedgerSelection {
    val parts = raw.split(UNIT_SEP)
    if (parts.size != 5) return CashLedgerSelection()
    val window = CashLedgerWindow.entries.firstOrNull { it.name == parts[0] } ?: CashLedgerWindow.ALL
    return CashLedgerSelection(
        sourceIds = parts[3].split(RECORD_SEP).filter { it.isNotEmpty() }.toSet(),
        tagIds = parts[4].split(RECORD_SEP).filter { it.isNotEmpty() }.toSet(),
        window = window,
        customStart = parseLocalDateOrNull(parts[1]),
        customEnd = parseLocalDateOrNull(parts[2]),
    )
}

private fun parseLocalDateOrNull(raw: String): LocalDate? =
    if (raw.isEmpty()) null else try { LocalDate.parse(raw) } catch (_: Exception) { null }

private const val UNIT_SEP = "\u001F"
private const val RECORD_SEP = "\u001E"

// ══════════════════════════ 3. Filtering ════════════════════════════════════

/**
 * The movements a selection admits, in the order they arrived (newest first,
 * as the DAO delivers them).
 *
 * The three facets AND together — each one narrows what the previous left — so
 * "Savings account", "Groceries" and "last 30 days" answers the question the
 * user actually asked. WITHIN a facet the match is OR; see
 * [CashLedgerSelection].
 */
fun filterCashMovements(
    movements: List<CashMovementEntity>,
    selection: CashLedgerSelection,
    nowMs: Long,
    zone: ZoneId,
): List<CashMovementEntity> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val span = resolveCashRange(selection, today)?.let { cashRangeMillis(it, zone) }
    return movements.filter { m -> matchesCashSelection(m, selection, span) }
}

/**
 * One row against one selection, with the date facet already resolved to
 * milliseconds.
 *
 * Split out of [filterCashMovements] because the faceted option counts need to
 * apply the selection **minus one facet** to every row, and re-resolving the
 * range once per facet per row would turn an O(n) pass into an O(n) pass with a
 * time-zone calculation in its inner loop.
 */
private fun matchesCashSelection(
    m: CashMovementEntity,
    selection: CashLedgerSelection,
    span: LongRange?,
): Boolean {
    if (selection.sourceIds.isNotEmpty() && m.sourceId !in selection.sourceIds) return false
    if (span != null && m.executedAtMs !in span) return false
    if (selection.tagIds.isNotEmpty()) {
        val rowTags = decodeTagIds(m.tagIds)
        if (rowTags.none { it in selection.tagIds }) return false
    }
    return true
}

/**
 * How many movements each SOURCE would contribute, given every other committed
 * facet.
 *
 * The study's rule, and it is the one that makes a filter sheet trustworthy:
 * *"option counts are calculated against the other committed facets while
 * excluding the facet currently being edited"* — so the number beside
 * "Alltagskonto" answers "how many rows would match the current date and tags
 * if this source were included", not "how many rows are showing right now"
 * (which for an unticked option is always zero, i.e. useless).
 */
fun cashSourceCounts(
    movements: List<CashMovementEntity>,
    selection: CashLedgerSelection,
    nowMs: Long,
    zone: ZoneId,
): Map<String, Int> {
    val base = selection.copy(sourceIds = emptySet())
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val span = resolveCashRange(base, today)?.let { cashRangeMillis(it, zone) }
    val out = HashMap<String, Int>()
    movements.forEach { m ->
        if (matchesCashSelection(m, base, span)) {
            out[m.sourceId] = (out[m.sourceId] ?: 0) + 1
        }
    }
    return out
}

/**
 * How many movements each TAG would contribute, given every other committed
 * facet. See [cashSourceCounts] for the rule.
 *
 * A movement with two tags counts once under each — that is what an OR filter
 * will actually do with it, so it is what the option counts must promise. The
 * untagged bucket is keyed [CASH_UNTAGGED_KEY].
 */
fun cashTagCounts(
    movements: List<CashMovementEntity>,
    selection: CashLedgerSelection,
    nowMs: Long,
    zone: ZoneId,
): Map<String, Int> {
    val base = selection.copy(tagIds = emptySet())
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val span = resolveCashRange(base, today)?.let { cashRangeMillis(it, zone) }
    val out = HashMap<String, Int>()
    movements.forEach { m ->
        if (!matchesCashSelection(m, base, span)) return@forEach
        val tags = decodeTagIds(m.tagIds)
        if (tags.isEmpty()) {
            out[CASH_UNTAGGED_KEY] = (out[CASH_UNTAGGED_KEY] ?: 0) + 1
        } else {
            tags.forEach { t -> out[t] = (out[t] ?: 0) + 1 }
        }
    }
    return out
}

/**
 * The synthetic key for "carries no tag at all".
 *
 * An empty string, because a real tag id is a server ULID and can never be one
 * — so the bucket cannot collide with a tag, and it survives being put in the
 * same `Set<String>` the tag facet already uses.
 */
const val CASH_UNTAGGED_KEY = ""

// ═══════════════════════════ 4. The roll-up ═════════════════════════════════

/**
 * One tag's share of the selection, by COUNT.
 *
 * By count and not by euro, and that is a deliberate refusal rather than a
 * shortcut. This model lets a movement carry up to 20 equal-status tags
 * (`CASH_TAGS_PER_ITEM_MAX`), and the server's own `/cash/summary` documents
 * that a two-tag movement contributes its FULL magnitude to both tag rows —
 * which is why the summary block already warns that its rows over-sum. A
 * euro-denominated "spend by tag" would inherit that double-count and read as
 * an allocation the data cannot support. The design study reaches the same
 * conclusion and names the fallback: *"if no primary tag exists, show movement
 * counts by tag instead of inventing an allocation rule."*
 *
 * @param tagId null = the untagged bucket.
 */
data class CashTagCount(val tagId: String?, val count: Int)

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
 *   It counts the LIST, so it includes the paired transfer legs below.
 * @param bookedCount the movements the money figures are computed over:
 *   [count] minus [transferCount]. It is the denominator of [avgAbsEur].
 * @param avgAbsEur mean magnitude of a booked movement — the sum of ABSOLUTE
 *   amounts over [bookedCount], so a +900 and a −900 average to 900 and not to
 *   zero. Zero when there is nothing booked.
 * @param transferEur the value moved by internal transfers whose BOTH legs are
 *   in the selection, counted once per pair.
 * @param transferCount how many legs that is (two per complete pair).
 * @param largest the booked movement with the biggest magnitude, or null.
 * @param outByTag outgoing movements grouped by tag, heaviest first. See
 *   [CashTagCount] for why it is a count and not a sum.
 */
data class CashLedgerStats(
    val inflowEur: Double,
    val outflowEur: Double,
    val netEur: Double,
    val count: Int,
    val bookedCount: Int = 0,
    val avgAbsEur: Double = 0.0,
    val transferEur: Double = 0.0,
    val transferCount: Int = 0,
    val largest: CashMovementEntity? = null,
    val outByTag: List<CashTagCount> = emptyList(),
) {
    companion object {
        val EMPTY = CashLedgerStats(0.0, 0.0, 0.0, 0)
    }
}

/**
 * Roll up a selection.
 *
 * ## Sign comes from the amount, never from the kind
 *
 * The kinds are an open set that has grown twice already (`fee`, `dividend`,
 * the transfer pair), and a roll-up that classified by kind would silently drop
 * every kind added after it was written. The stored `amountEur` is signed by
 * the server for exactly this purpose, so it is the thing to trust. Exact zeros
 * land in neither column — they are counted, and they move nothing.
 *
 * ## Why complete transfer pairs are pulled out
 *
 * Moving 500 € from the current account to the travel cash books TWO rows:
 * −500 on one source and +500 on the other. When both are in the selection,
 * leaving them in makes the block report 500 in and 500 out for money that
 * never entered or left the user's cash — Netto stays honest, but Zufluss and
 * Abfluss are both inflated by a round trip. So a pair whose two legs share a
 * `transferId` and are BOTH present is reported separately as `Umbuchungen`.
 *
 * A HALF pair — one leg selected, its partner filtered out by source — stays in
 * the ordinary columns, because from the perspective of the selected source
 * that money genuinely did leave.
 */
fun cashLedgerStats(movements: List<CashMovementEntity>): CashLedgerStats {
    // Complete pairs first: a transferId seen on two or more selected rows.
    val legsByTransfer = HashMap<String, Int>()
    movements.forEach { m ->
        val tid = m.transferId ?: return@forEach
        legsByTransfer[tid] = (legsByTransfer[tid] ?: 0) + 1
    }
    val pairedIds = legsByTransfer.filterValues { it >= 2 }.keys

    var inflow = 0.0
    var outflow = 0.0
    var absSum = 0.0
    var booked = 0
    var transferEur = 0.0
    var transferLegs = 0
    var largest: CashMovementEntity? = null
    val outCounts = HashMap<String?, Int>()

    movements.forEach { m ->
        val amount = if (m.amountEur.isFinite()) m.amountEur else 0.0
        if (m.transferId != null && m.transferId in pairedIds) {
            transferLegs++
            // Once per PAIR, not once per leg: the outgoing leg is the one that
            // names the amount moved.
            if (amount < 0.0) transferEur += -amount
            return@forEach
        }
        booked++
        absSum += abs(amount)
        if (amount > 0.0) inflow += amount else if (amount < 0.0) outflow += -amount
        if (largest == null || abs(amount) > abs(largest.amountEur)) largest = m
        if (amount < 0.0) {
            val tags = decodeTagIds(m.tagIds)
            if (tags.isEmpty()) {
                outCounts[null] = (outCounts[null] ?: 0) + 1
            } else {
                tags.forEach { t -> outCounts[t] = (outCounts[t] ?: 0) + 1 }
            }
        }
    }

    return CashLedgerStats(
        inflowEur = inflow,
        outflowEur = outflow,
        netEur = inflow - outflow,
        count = movements.size,
        bookedCount = booked,
        avgAbsEur = if (booked > 0) absSum / booked else 0.0,
        transferEur = transferEur,
        transferCount = transferLegs,
        // A selection of nothing but zero-amount rows has a "largest" of zero,
        // which is true and useless; null keeps the fact out of the UI.
        largest = largest?.takeIf { abs(it.amountEur) > 0.0 },
        outByTag = outCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String?, Int>> { it.value }.thenBy { it.key ?: "￿" })
            .map { CashTagCount(it.key, it.value) },
    )
}

/**
 * The outgoing-by-tag rows a compact breakdown should draw: the [top] heaviest,
 * then everything else folded into one remainder row keyed `null`… except that
 * `null` already means *untagged*, so the remainder is expressed by the caller.
 *
 * Returns the head and the remainder count separately for exactly that reason —
 * two different "other"s in one list would be indistinguishable.
 */
fun cashTagSplitHead(rows: List<CashTagCount>, top: Int = 3): Pair<List<CashTagCount>, Int> {
    if (rows.size <= top) return rows to 0
    val head = rows.take(top)
    val rest = rows.drop(top).sumOf { it.count }
    return head to rest
}
