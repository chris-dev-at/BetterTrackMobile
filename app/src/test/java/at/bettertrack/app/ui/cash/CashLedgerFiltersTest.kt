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
 * The ledger's selection model (owner ask 2026-08-16: source × tags × timespan,
 * plus a roll-up of whatever is selected).
 *
 * Worth testing rather than eyeballing, for two reasons. The filters COMBINE, so
 * the interesting cases are the intersections — and an intersection bug shows up
 * as "some of my movements are missing", which looks like a sync problem rather
 * than a filter problem. And the roll-up is money the user will reconcile against
 * their bank: an off-by-one on a window boundary or a mis-signed sum is a wrong
 * number presented with total confidence.
 */
class CashLedgerFiltersTest {

    private val zone: ZoneId = ZoneId.of("Europe/Vienna")

    /** 2026-08-16 12:00 local — the batch's "today". */
    private val nowMs = LocalDate.of(2026, 8, 16)
        .atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dayMs(date: LocalDate, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun movement(
        id: String,
        amountEur: Double,
        sourceId: String = "main",
        tagIds: String = "",
        date: LocalDate = LocalDate.of(2026, 8, 16),
    ) = CashMovementEntity(
        id = id,
        portfolioId = "p1",
        sourceId = sourceId,
        kind = if (amountEur >= 0) "deposit" else "withdrawal",
        amountEur = amountEur,
        transactionId = null,
        transferId = null,
        counterpartSourceId = null,
        executedAt = date.toString(),
        executedAtMs = dayMs(date),
        note = null,
        createdAt = date.toString(),
        tagIds = tagIds,
    )

    // ── Windows ─────────────────────────────────────────────────────────────

    @Test
    fun `the all window has no lower bound`() {
        assertNull(cashWindowStartMs(CashLedgerWindow.ALL, nowMs, zone))
    }

    @Test
    fun `a window is counted in whole days from the start of today`() {
        // 30 days INCLUDING today ⇒ the boundary is 2026-07-18T00:00 local.
        val start = cashWindowStartMs(CashLedgerWindow.DAYS_30, nowMs, zone)!!
        assertEquals(
            LocalDate.of(2026, 7, 18).atStartOfDay(zone).toInstant().toEpochMilli(),
            start,
        )
    }

    @Test
    fun `a movement booked earlier today survives an afternoon visit`() {
        // The bug this pins: counting back "now minus 30x24h" drops this
        // morning's row the moment the user opens the screen after lunch.
        val thisMorning = movement("m1", 10.0, date = LocalDate.of(2026, 8, 16))
        val kept = filterCashMovements(
            listOf(thisMorning),
            CashLedgerSelection(window = CashLedgerWindow.DAYS_30),
            nowMs,
            zone,
        )
        assertEquals(listOf("m1"), kept.map { it.id })
    }

    @Test
    fun `a movement just outside the window is excluded and one just inside is kept`() {
        val inside = movement("in", 5.0, date = LocalDate.of(2026, 7, 18))
        val outside = movement("out", 5.0, date = LocalDate.of(2026, 7, 17))
        val kept = filterCashMovements(
            listOf(inside, outside),
            CashLedgerSelection(window = CashLedgerWindow.DAYS_30),
            nowMs,
            zone,
        )
        assertEquals(listOf("in"), kept.map { it.id })
    }

    // ── Source and tags ─────────────────────────────────────────────────────

    @Test
    fun `the source filter keeps only that wallet`() {
        val rows = listOf(
            movement("a", 5.0, sourceId = "main"),
            movement("b", 5.0, sourceId = "savings"),
        )
        val kept = filterCashMovements(rows, CashLedgerSelection(sourceIds = setOf("savings")), nowMs, zone)
        assertEquals(listOf("b"), kept.map { it.id })
    }

    @Test
    fun `tags match ANY of the selected ones, not all of them`() {
        val rows = listOf(
            movement("food", 5.0, tagIds = "t-food"),
            movement("fuel", 5.0, tagIds = "t-fuel"),
            movement("rent", 5.0, tagIds = "t-rent"),
        )
        val kept = filterCashMovements(
            rows,
            CashLedgerSelection(tagIds = setOf("t-food", "t-fuel")),
            nowMs,
            zone,
        )
        assertEquals(listOf("food", "fuel"), kept.map { it.id })
    }

    @Test
    fun `a row carrying one of several tags matches`() {
        val rows = listOf(movement("multi", 5.0, tagIds = "t-food,t-rent"))
        val kept = filterCashMovements(rows, CashLedgerSelection(tagIds = setOf("t-rent")), nowMs, zone)
        assertEquals(listOf("multi"), kept.map { it.id })
    }

    @Test
    fun `no tag filter admits untagged rows`() {
        val rows = listOf(movement("bare", 5.0, tagIds = ""))
        assertEquals(1, filterCashMovements(rows, CashLedgerSelection(), nowMs, zone).size)
    }

    @Test
    fun `the three filters AND together`() {
        val target = movement(
            "target", -20.0,
            sourceId = "savings", tagIds = "t-food", date = LocalDate.of(2026, 8, 1),
        )
        val wrongSource = movement(
            "wrong-source", -20.0,
            sourceId = "main", tagIds = "t-food", date = LocalDate.of(2026, 8, 1),
        )
        val wrongTag = movement(
            "wrong-tag", -20.0,
            sourceId = "savings", tagIds = "t-fuel", date = LocalDate.of(2026, 8, 1),
        )
        val tooOld = movement(
            "too-old", -20.0,
            sourceId = "savings", tagIds = "t-food", date = LocalDate.of(2025, 1, 1),
        )
        val kept = filterCashMovements(
            listOf(target, wrongSource, wrongTag, tooOld),
            CashLedgerSelection(
                sourceIds = setOf("savings"),
                tagIds = setOf("t-food"),
                window = CashLedgerWindow.DAYS_30,
            ),
            nowMs,
            zone,
        )
        assertEquals(listOf("target"), kept.map { it.id })
    }

    // ── Is anything narrowed ────────────────────────────────────────────────

    @Test
    fun `an untouched selection is not active`() {
        assertFalse(CashLedgerSelection().isActive)
    }

    @Test
    fun `any one filter makes the selection active`() {
        assertTrue(CashLedgerSelection(sourceIds = setOf("main")).isActive)
        assertTrue(CashLedgerSelection(tagIds = setOf("t")).isActive)
        assertTrue(CashLedgerSelection(window = CashLedgerWindow.DAYS_90).isActive)
    }

    // ── The roll-up ─────────────────────────────────────────────────────────

    @Test
    fun `in and out are positive magnitudes and net keeps its sign`() {
        val stats = cashLedgerStats(
            listOf(
                movement("a", 2900.0),
                movement("b", -60.0),
                movement("c", -40.0),
            ),
        )
        assertEquals(2900.0, stats.inflowEur, 1e-9)
        assertEquals(100.0, stats.outflowEur, 1e-9)
        assertEquals(2800.0, stats.netEur, 1e-9)
        assertEquals(3, stats.count)
    }

    @Test
    fun `a net loss is negative`() {
        val stats = cashLedgerStats(listOf(movement("a", 10.0), movement("b", -25.0)))
        assertEquals(-15.0, stats.netEur, 1e-9)
        assertEquals(10.0, stats.inflowEur, 1e-9)
        assertEquals(25.0, stats.outflowEur, 1e-9)
    }

    @Test
    fun `an empty selection sums to nothing rather than crashing`() {
        val stats = cashLedgerStats(emptyList())
        assertEquals(0.0, stats.netEur, 1e-9)
        assertEquals(0, stats.count)
    }

    @Test
    fun `sign comes from the amount, not from the kind`() {
        // The kinds are an open set (fee, dividend, the transfer pair, whatever
        // v6 adds). A roll-up that classified by kind would drop every kind
        // added after it was written; the server signs the amount for this.
        val odd = movement("odd", -12.5).copy(kind = "some_future_kind")
        val stats = cashLedgerStats(listOf(odd))
        assertEquals(12.5, stats.outflowEur, 1e-9)
        assertEquals(0.0, stats.inflowEur, 1e-9)
    }

    @Test
    fun `an exact zero is counted but moves neither column`() {
        val stats = cashLedgerStats(listOf(movement("z", 0.0)))
        assertEquals(0.0, stats.inflowEur, 1e-9)
        assertEquals(0.0, stats.outflowEur, 1e-9)
        assertEquals(1, stats.count)
    }
}
