package at.bettertrack.app.ui.cash

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
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
