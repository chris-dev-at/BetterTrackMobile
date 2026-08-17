package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.db.CashMovementEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger filters **v2** (owner 2026-08-17: *"the current filters are too
 * basic"*) — the parts v1 did not have: a custom date range with validation,
 * multi-select facets that normalize, faceted option counts, the deeper
 * roll-up, and the selection codec that carries all of it across a rotation.
 *
 * These are worth tests rather than eyeballs for the same reason v1's were: a
 * filter bug reads to the user as missing data, and the roll-up is money they
 * will reconcile against a bank statement.
 */
class CashLedgerFiltersV2Test {

    private val zone: ZoneId = ZoneId.of("Europe/Vienna")
    private val today: LocalDate = LocalDate.of(2026, 8, 16)
    private val nowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun movement(
        id: String,
        amountEur: Double,
        sourceId: String = "main",
        tagIds: String = "",
        date: LocalDate = today,
        transferId: String? = null,
        note: String? = null,
    ) = CashMovementEntity(
        id = id,
        portfolioId = "p1",
        sourceId = sourceId,
        kind = if (amountEur >= 0) "deposit" else "withdrawal",
        amountEur = amountEur,
        transactionId = null,
        transferId = transferId,
        counterpartSourceId = null,
        executedAt = date.toString(),
        executedAtMs = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
        note = note,
        createdAt = date.toString(),
        tagIds = tagIds,
    )

    // ══════════════════════ Range resolution + validation ═══════════════════

    @Test
    fun `all resolves to no range at all`() {
        assertNull(resolveCashRange(CashLedgerSelection(), today))
    }

    @Test
    fun `a preset resolves to concrete dates ending today`() {
        val range = resolveCashRange(CashLedgerSelection(window = CashLedgerWindow.DAYS_30), today)!!
        // "30 days" is today plus the 29 before it — inclusive, not 30 days ago.
        assertEquals(LocalDate.of(2026, 7, 18), range.start)
        assertEquals(today, range.end)
        assertEquals(30L, range.days)
    }

    @Test
    fun `the preset resolution matches the millisecond arithmetic it replaced`() {
        // v1's `cashWindowStartMs` is still the reference; the two must not drift.
        val viaMs = cashWindowStartMs(CashLedgerWindow.DAYS_90, nowMs, zone)!!
        val viaRange = resolveCashRange(CashLedgerSelection(window = CashLedgerWindow.DAYS_90), today)!!
        assertEquals(viaMs, viaRange.start.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    @Test
    fun `a half-finished custom range does not filter anything yet`() {
        val onlyStart = CashLedgerSelection(
            window = CashLedgerWindow.CUSTOM,
            customStart = LocalDate.of(2026, 6, 1),
        )
        assertNull(resolveCashRange(onlyStart, today))
        assertFalse(onlyStart.dateActive)
        assertFalse(onlyStart.isActive)
    }

    @Test
    fun `an inverted custom range resolves to nothing rather than to an empty list`() {
        val inverted = CashLedgerSelection(
            window = CashLedgerWindow.CUSTOM,
            customStart = LocalDate.of(2026, 8, 1),
            customEnd = LocalDate.of(2026, 6, 1),
        )
        assertNull(resolveCashRange(inverted, today))
    }

    @Test
    fun `a same-day custom range is one day, not zero`() {
        val range = resolveCashRange(
            CashLedgerSelection(
                window = CashLedgerWindow.CUSTOM,
                customStart = today,
                customEnd = today,
            ),
            today,
        )!!
        assertEquals(1L, range.days)
    }

    @Test
    fun `validation refuses an incomplete or inverted pair`() {
        assertFalse(cashRangeValid(null, today, today))
        assertFalse(cashRangeValid(today, null, today))
        assertFalse(cashRangeValid(today, today.minusDays(1), today))
        assertTrue(cashRangeValid(today.minusDays(1), today, today))
        assertTrue(cashRangeValid(today, today, today))
    }

    @Test
    fun `validation refuses a future end unless the ledger actually reaches there`() {
        val tomorrow = today.plusDays(1)
        assertFalse(cashRangeValid(today, tomorrow, today))
        // A standing order booked into next week makes next week selectable —
        // refusing it would be the app calling its own row impossible.
        assertTrue(cashRangeValid(today, tomorrow, today, latest = today.plusDays(7)))
        assertEquals(today, cashMaxSelectableDate(today, latest = today.minusDays(3)))
        assertEquals(today.plusDays(7), cashMaxSelectableDate(today, latest = today.plusDays(7)))
    }

    @Test
    fun `a range is half-open on the day after the end`() {
        val span = cashRangeMillis(CashDateRange(today, today), zone)
        val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDay = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(startOfDay in span)
        assertTrue(nextDay - 1 in span)
        assertFalse(nextDay in span)
    }

    @Test
    fun `a custom range filters inclusively at both ends`() {
        val rows = listOf(
            movement("before", 1.0, date = LocalDate.of(2026, 5, 31)),
            movement("start", 1.0, date = LocalDate.of(2026, 6, 1)),
            movement("middle", 1.0, date = LocalDate.of(2026, 7, 4)),
            movement("end", 1.0, date = LocalDate.of(2026, 8, 16)),
            movement("after", 1.0, date = LocalDate.of(2026, 8, 17)),
        )
        val kept = filterCashMovements(
            rows,
            CashLedgerSelection(
                window = CashLedgerWindow.CUSTOM,
                customStart = LocalDate.of(2026, 6, 1),
                customEnd = LocalDate.of(2026, 8, 16),
            ),
            nowMs,
            zone,
        )
        assertEquals(listOf("start", "middle", "end"), kept.map { it.id })
    }

    // ══════════════════════════ Multi-select facets ═════════════════════════

    @Test
    fun `several sources combine with OR`() {
        val rows = listOf(
            movement("a", 1.0, sourceId = "main"),
            movement("b", 1.0, sourceId = "cash"),
            movement("c", 1.0, sourceId = "travel"),
        )
        val kept = filterCashMovements(
            rows,
            CashLedgerSelection(sourceIds = setOf("main", "travel")),
            nowMs,
            zone,
        )
        assertEquals(listOf("a", "c"), kept.map { it.id })
    }

    @Test
    fun `a facet with everything ticked normalizes to no filter`() {
        val available = setOf("main", "cash")
        assertEquals(emptySet<String>(), normalizeCashFacet(setOf("main", "cash"), available))
        assertEquals(setOf("main"), normalizeCashFacet(setOf("main"), available))
        // A stale id for a source that no longer exists is dropped rather than
        // left to narrow the list to nothing forever.
        assertEquals(setOf("main"), normalizeCashFacet(setOf("main", "gone"), available))
        assertEquals(emptySet<String>(), normalizeCashFacet(emptySet(), available))
    }

    @Test
    fun `facet counts answer what WOULD match, ignoring the facet being edited`() {
        val rows = listOf(
            movement("a", 1.0, sourceId = "main", date = today),
            movement("b", 1.0, sourceId = "cash", date = today),
            movement("c", 1.0, sourceId = "cash", date = LocalDate.of(2025, 1, 1)),
        )
        // Already narrowed to "main" — the count beside "cash" must still say 1
        // (the row inside the date window), not 0.
        val selection = CashLedgerSelection(
            sourceIds = setOf("main"),
            window = CashLedgerWindow.DAYS_30,
        )
        val counts = cashSourceCounts(rows, selection, nowMs, zone)
        assertEquals(1, counts["main"])
        assertEquals(1, counts["cash"])
    }

    @Test
    fun `tag counts count a multi-tag row under each of its tags`() {
        val rows = listOf(
            movement("a", -1.0, tagIds = "t-food,t-fuel"),
            movement("b", -1.0, tagIds = "t-food"),
            movement("c", -1.0, tagIds = ""),
        )
        val counts = cashTagCounts(rows, CashLedgerSelection(), nowMs, zone)
        assertEquals(2, counts["t-food"])
        assertEquals(1, counts["t-fuel"])
        assertEquals(1, counts[CASH_UNTAGGED_KEY])
    }

    @Test
    fun `the facet count counts facets, never selected values`() {
        val selection = CashLedgerSelection(
            sourceIds = setOf("a", "b", "c"),
            tagIds = setOf("t1", "t2"),
            window = CashLedgerWindow.DAYS_30,
        )
        assertEquals(3, selection.facetCount)
        assertEquals(0, CashLedgerSelection().facetCount)
        assertEquals(1, CashLedgerSelection(tagIds = setOf("t1")).facetCount)
    }

    // ══════════════════════════ The roll-up ═════════════════════════════════

    @Test
    fun `average is over magnitudes, so opposite movements do not cancel`() {
        val stats = cashLedgerStats(listOf(movement("a", 900.0), movement("b", -900.0)))
        assertEquals(900.0, stats.avgAbsEur, 0.0001)
        assertEquals(0.0, stats.netEur, 0.0001)
        assertEquals(2, stats.bookedCount)
    }

    @Test
    fun `largest is the biggest magnitude in either direction`() {
        val stats = cashLedgerStats(
            listOf(movement("small", 20.0), movement("big", -1240.0), movement("mid", 300.0)),
        )
        assertEquals("big", stats.largest?.id)
    }

    @Test
    fun `a selection of nothing but zeros has no largest movement`() {
        val stats = cashLedgerStats(listOf(movement("z", 0.0)))
        assertNull(stats.largest)
        assertEquals(0.0, stats.avgAbsEur, 0.0001)
        assertEquals(1, stats.count)
    }

    @Test
    fun `a complete transfer pair leaves in and out alone and reports itself`() {
        // 500 moved between the user's own sources is not 500 in AND 500 out.
        val stats = cashLedgerStats(
            listOf(
                movement("out", -500.0, sourceId = "main", transferId = "tr1"),
                movement("in", 500.0, sourceId = "cash", transferId = "tr1"),
                movement("rent", -1200.0),
            ),
        )
        assertEquals(0.0, stats.inflowEur, 0.0001)
        assertEquals(1200.0, stats.outflowEur, 0.0001)
        assertEquals(-1200.0, stats.netEur, 0.0001)
        assertEquals(500.0, stats.transferEur, 0.0001)
        assertEquals(2, stats.transferCount)
        // The list still shows all three rows; only the money figures exclude two.
        assertEquals(3, stats.count)
        assertEquals(1, stats.bookedCount)
    }

    @Test
    fun `a half transfer pair stays an ordinary outflow`() {
        // Filtered to one source, the partner leg is not in view — and from that
        // source's point of view the money genuinely did leave.
        val stats = cashLedgerStats(listOf(movement("out", -500.0, transferId = "tr1")))
        assertEquals(500.0, stats.outflowEur, 0.0001)
        assertEquals(0.0, stats.transferEur, 0.0001)
        assertEquals(0, stats.transferCount)
    }

    @Test
    fun `outgoing rows are grouped by tag, heaviest first, untagged included`() {
        val stats = cashLedgerStats(
            listOf(
                movement("a", -10.0, tagIds = "food"),
                movement("b", -10.0, tagIds = "food"),
                movement("c", -10.0, tagIds = "rent"),
                movement("d", -10.0, tagIds = ""),
                movement("income", 500.0, tagIds = "salary"),
            ),
        )
        assertEquals(listOf("food", "rent", null), stats.outByTag.map { it.tagId })
        assertEquals(listOf(2, 1, 1), stats.outByTag.map { it.count })
        // An INCOMING row's tag never appears in an outflow breakdown.
        assertFalse(stats.outByTag.any { it.tagId == "salary" })
    }

    @Test
    fun `the compact breakdown keeps a head and folds the rest into one number`() {
        val rows = listOf(
            CashTagCount("a", 9),
            CashTagCount("b", 5),
            CashTagCount("c", 4),
            CashTagCount("d", 3),
            CashTagCount("e", 1),
        )
        val (head, rest) = cashTagSplitHead(rows, top = 3)
        assertEquals(listOf("a", "b", "c"), head.map { it.tagId })
        assertEquals(4, rest)
        // Nothing to fold when everything already fits.
        assertEquals(0, cashTagSplitHead(rows.take(2), top = 3).second)
    }

    // ═══════════════════════ The saveable selection ═════════════════════════

    @Test
    fun `a selection survives a round trip through its encoded form`() {
        val original = CashLedgerSelection(
            sourceIds = setOf("s2", "s1"),
            tagIds = setOf("t1"),
            window = CashLedgerWindow.CUSTOM,
            customStart = LocalDate.of(2026, 6, 1),
            customEnd = LocalDate.of(2026, 8, 16),
        )
        assertEquals(original, decodeCashSelection(encodeCashSelection(original)))
    }

    @Test
    fun `the default selection round-trips too`() {
        assertEquals(
            CashLedgerSelection(),
            decodeCashSelection(encodeCashSelection(CashLedgerSelection())),
        )
    }

    @Test
    fun `garbage decodes to the default rather than to a half-restored filter`() {
        // The failure mode this guards is the dangerous one: a partial restore
        // that keeps the dates but loses the window silently widens the ledger.
        assertEquals(CashLedgerSelection(), decodeCashSelection(""))
        assertEquals(CashLedgerSelection(), decodeCashSelection("nonsense"))
        val unknownWindow = decodeCashSelection(
            encodeCashSelection(CashLedgerSelection(window = CashLedgerWindow.DAYS_90))
                .replace("DAYS_90", "DAYS_45"),
        )
        assertEquals(CashLedgerWindow.ALL, unknownWindow.window)
    }
}
