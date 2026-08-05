package at.bettertrack.app.ui.cash

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.sync.CashOpPayload
import at.bettertrack.app.sync.CashTransferOpPayload
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Step-9 pure-logic tests (§6.3): previews, validation, pending decode. */
class CashLogicTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `entry preview adds deposits and subtracts withdrawals`() {
        assertEquals(150.0, balanceAfterEntry(100.0, 50.0, deposit = true), 1e-9)
        assertEquals(50.0, balanceAfterEntry(100.0, 50.0, deposit = false), 1e-9)
    }

    @Test
    fun `transfer preview moves the amount between both sides`() {
        val p = transferPreview(fromBalanceEur = 500.0, toBalanceEur = 100.0, amountEur = 200.0)
        assertEquals(300.0, p.fromAfterEur, 1e-9)
        assertEquals(300.0, p.toAfterEur, 1e-9)
    }

    @Test
    fun `withdrawal over the cached balance hard-blocks`() {
        val v = validateCashEntry(amount = 150.0, deposit = false, sourceBalanceEur = 100.0)
        assertTrue(v.insufficient)
        assertFalse(v.canSubmit)
        // Deposits never block on balance.
        assertTrue(validateCashEntry(150.0, deposit = true, sourceBalanceEur = 0.0).canSubmit)
        // Exact drain to zero is allowed (never SILENTLY negative, zero is fine).
        assertTrue(validateCashEntry(100.0, deposit = false, sourceBalanceEur = 100.0).canSubmit)
    }

    @Test
    fun `transfer validation blocks same source and overdraw`() {
        assertTrue(validateTransfer(50.0, "a", "b", 100.0).canSubmit)
        assertTrue(validateTransfer(50.0, "a", "a", 100.0).sameSource)
        assertFalse(validateTransfer(50.0, "a", "a", 100.0).canSubmit)
        assertTrue(validateTransfer(150.0, "a", "b", 100.0).insufficient)
        assertFalse(validateTransfer(150.0, "a", "b", 100.0).canSubmit)
        assertTrue(validateTransfer(null, "a", "b", 100.0).amountMissing)
        assertTrue(validateTransfer(50.0, null, "b", 100.0).missingSource)
    }

    @Test
    fun `active sources filter archived and put Main first`() {
        fun src(id: String, main: Boolean, archived: String?) = CashSourceEntity(
            id = id, portfolioId = "p", name = id, kind = "bank",
            isMain = main, balanceEur = 0.0, archivedAt = archived,
        )
        val ordered = activeSources(
            listOf(src("bank", false, null), src("main", true, null), src("old", false, "2026-01-01")),
        )
        assertEquals(listOf("main", "bank"), ordered.map { it.id })
    }

    private fun op(id: Long, type: OpType, payload: String, status: OpStatus = OpStatus.PENDING) =
        SyncOpEntity(
            id = id, clientId = "c$id", opType = type.wire, portfolioId = "p1",
            payloadJson = payload, status = status.wire, attemptCount = 0, nextAttemptAtMs = 0,
            serverError = null, serverResultJson = null, accountKey = "u", createdAtMs = 1, updatedAtMs = 1,
        )

    @Test
    fun `pending cash decode maps deposits withdrawals and transfers`() {
        val dep = op(
            1, OpType.CASH_DEPOSIT,
            json.encodeToString(CashOpPayload.serializer(), CashOpPayload(50.0, sourceId = "s1")),
        )
        val tr = op(
            2, OpType.CASH_TRANSFER,
            json.encodeToString(
                CashTransferOpPayload.serializer(),
                CashTransferOpPayload("s1", "s2", 200.0),
            ),
            status = OpStatus.NEEDS_ATTENTION,
        )
        val txOp = op(3, OpType.TX_BUY, "{}") // not a cash op — skipped

        val rows = decodePendingCashRows(listOf(dep, tr, txOp), json, "p1")

        assertEquals(listOf(2L, 1L), rows.map { it.opId })
        assertEquals(OpType.CASH_TRANSFER, rows[0].type)
        assertEquals("s1", rows[0].sourceId)
        assertEquals("s2", rows[0].toSourceId)
        assertEquals(PendingUiStatus.NEEDS_ATTENTION, rows[0].status)
        assertEquals(50.0, rows[1].amountEur, 1e-9)
        assertEquals("s1", rows[1].sourceId)
    }

    // ── Backdated cash date (board #36) ──────────────────────────────────────

    @Test
    fun `cash executedAt omits today and future, backdates a past day at local midday`() {
        val zone = ZoneId.of("Europe/Vienna")
        val now = LocalDate.of(2026, 7, 15).atTime(9, 30).atZone(zone).toInstant()

        // Today → omitted (server stamps now; byte-identical to an undated entry).
        assertNull(cashExecutedAtOrNull(LocalDate.of(2026, 7, 15), zone, now))
        // A future day the no-future picker shouldn't allow → also omitted (defensive).
        assertNull(cashExecutedAtOrNull(LocalDate.of(2026, 7, 20), zone, now))

        // A past day → midday LOCAL of exactly that calendar day (no day slip).
        val iso = cashExecutedAtOrNull(LocalDate.of(2026, 7, 1), zone, now)
        assertTrue(iso != null)
        val stamped = Instant.parse(iso!!).atZone(zone)
        assertEquals(LocalDate.of(2026, 7, 1), stamped.toLocalDate())
        assertEquals(12, stamped.hour)
    }

    @Test
    fun `pending cash decode carries a backdated executedAt and null for a today entry`() {
        val iso = "2026-07-01T10:00:00Z"
        val backdated = op(
            1, OpType.CASH_DEPOSIT,
            json.encodeToString(
                CashOpPayload.serializer(),
                CashOpPayload(50.0, executedAt = iso, sourceId = "s1"),
            ),
        )
        // A today entry / an old pre-feature payload: no executedAt key at all.
        val today = op(
            2, OpType.CASH_DEPOSIT,
            json.encodeToString(CashOpPayload.serializer(), CashOpPayload(50.0, sourceId = "s1")),
        )

        val rows = decodePendingCashRows(listOf(backdated, today), json, "p1").associateBy { it.opId }

        assertEquals(iso, rows.getValue(1L).executedAt)
        assertNull(rows.getValue(2L).executedAt)
    }

    // ── Ledger state (failed-first-fetch vs genuinely empty) ────────────────
    //
    // The decision itself belongs to the shared `resolveListSurface`; what is
    // Cash-specific — and what has burned this screen before — is the flag fed
    // into its `firstLoadPending` slot. It must stay true while the first read
    // is genuinely unanswered, and it must be IMPOSSIBLE to leave true forever.

    @Test
    fun `the ledger waits while the first read is unanswered`() {
        assertTrue(cashLedgerPending(loaded = false, hasPortfolio = true, sourcesSeen = true))
    }

    @Test
    fun `the ledger stops waiting once the first read has answered`() {
        assertFalse(cashLedgerPending(loaded = true, hasPortfolio = true, sourcesSeen = true))
        // A later refresh does not reopen the question — `loaded` is one-way, so
        // a background refresh can never blank real rows back to placeholders.
        assertFalse(cashLedgerPending(loaded = true, hasPortfolio = false, sourcesSeen = true))
    }

    @Test
    fun `with no portfolio at all the wait ends instead of shimmering forever`() {
        // Nothing will ever be requested, so nothing would ever set `loaded`.
        // This is the trap R3 fixed one section up, and the reason the flag is a
        // function rather than a bare `!loaded`.
        assertFalse(cashLedgerPending(loaded = false, hasPortfolio = false, sourcesSeen = true))
    }

    @Test
    fun `before the local reads have run the ledger still waits`() {
        // The very first frame: the portfolio has not resolved yet, so "no
        // portfolio" is not yet an answer and must not read as "no movements".
        assertTrue(cashLedgerPending(loaded = false, hasPortfolio = false, sourcesSeen = false))
    }

    // ── The three analytics blocks now carry their error ─────────────────────

    @Test
    fun `summary, trends and budgets failures all carry the server's message`() {
        val a = BtMessage(R.string.bt_err_unknown, diagnostic = "rate_limited")
        assertEquals(a, CashSummaryUi.Failed(a).message)
        assertEquals(a, CashTrendsUi.Failed(a).message)
        assertEquals(a, BudgetsUi.Failed(a).message)
        // Two different refusals are two different states — as payload-less
        // objects they compared equal and the UI could not tell them apart.
        assertTrue(CashSummaryUi.Failed(a) != CashSummaryUi.Failed(BtMessage(R.string.bt_err_network_error)))
    }
}
