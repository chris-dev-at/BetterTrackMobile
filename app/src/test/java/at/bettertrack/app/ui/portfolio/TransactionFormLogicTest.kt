package at.bettertrack.app.ui.portfolio

import at.bettertrack.app.data.db.SyncOpEntity
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.TxOpPayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Step-8 pure-logic tests (spec §6.2/§7.4): decimal input parsing, the
 * cash-after preview math, the sticky cash-coupling default, form validation
 * (hard cash block vs soft oversell warning), synced-note marker preservation
 * and the pending-row decoding/filtering.
 */
class TransactionFormLogicTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ── Decimal input ────────────────────────────────────────────────────────

    @Test
    fun `parse accepts comma or dot decimals and grouping`() {
        assertEquals(1234.56, parseLocalizedDecimal("1.234,56")!!, 1e-9) // de-AT
        assertEquals(1234.56, parseLocalizedDecimal("1,234.56")!!, 1e-9) // en
        assertEquals(0.5, parseLocalizedDecimal("0,5")!!, 1e-9)
        assertEquals(0.5, parseLocalizedDecimal("0.5")!!, 1e-9)
        assertEquals(7.0, parseLocalizedDecimal("7")!!, 1e-9)
        assertNull(parseLocalizedDecimal(""))
        assertNull(parseLocalizedDecimal("abc"))
        assertNull(parseLocalizedDecimal("1.2.3,4,5"))
    }

    @Test
    fun `sanitize keeps digits and a single separator`() {
        assertEquals("12,34", sanitizeDecimalInput("12,34"))
        assertEquals("12,34", sanitizeDecimalInput("1a2,3.4")) // second sep dropped
        assertEquals("1234", sanitizeDecimalInput("12€34"))
        assertEquals("0,12345678", sanitizeDecimalInput("0,123456789")) // 8-decimal cap
    }

    // ── Cash-after preview (§6.2) ────────────────────────────────────────────

    @Test
    fun `buy consumes gross plus fee and sell adds gross minus fee`() {
        // Buy 2 × €100 + €5 fee from €1000 → €795.
        assertEquals(795.0, cashAfterPreview(1000.0, true, 2.0, 100.0, 5.0), 1e-9)
        // Sell 2 × €100 − €5 fee into €1000 → €1195.
        assertEquals(1195.0, cashAfterPreview(1000.0, false, 2.0, 100.0, 5.0), 1e-9)
    }

    @Test
    fun `order total mirrors the cash delta magnitude`() {
        assertEquals(205.0, orderTotal(true, 2.0, 100.0, 5.0), 1e-9)
        assertEquals(195.0, orderTotal(false, 2.0, 100.0, 5.0), 1e-9)
    }

    // ── Sticky default (§6.2) ────────────────────────────────────────────────

    @Test
    fun `sticky local value wins over the server default`() {
        assertTrue(resolveCashCouplingDefault(localSticky = true, serverDefault = false))
        assertFalse(resolveCashCouplingDefault(localSticky = false, serverDefault = true))
        assertTrue(resolveCashCouplingDefault(localSticky = null, serverDefault = true))
        assertFalse(resolveCashCouplingDefault(localSticky = null, serverDefault = false))
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    fun `insufficient cash on a coupled buy HARD-blocks submission`() {
        val v = validateTxForm(
            assetSelected = true,
            quantity = 10.0,
            price = 100.0,
            fee = 0.0,
            isBuy = true,
            cashCoupled = true,
            currentCashEur = 500.0, // needs 1000 — short today AND back then
            asOfCashEur = 500.0,
            heldQuantity = null,
        )
        assertTrue(v.insufficientCash)
        assertFalse(v.canSubmit)

        // Toggle off ⇒ no cash involvement ⇒ submits.
        val off = v.copy(insufficientCash = false)
        assertTrue(off.canSubmit)
    }

    @Test
    fun `coupled sell that would overdraw via fee also blocks`() {
        // Sell proceeds 1×10 − fee 50 = −40 against balance 20 → after −20.
        val v = validateTxForm(
            assetSelected = true,
            quantity = 1.0,
            price = 10.0,
            fee = 50.0,
            isBuy = false,
            cashCoupled = true,
            currentCashEur = 20.0,
            asOfCashEur = 20.0,
            heldQuantity = 5.0,
        )
        assertTrue(v.insufficientCash)
        assertFalse(v.canSubmit)
    }

    @Test
    fun `oversell only warns - the server stays the final validator`() {
        val v = validateTxForm(
            assetSelected = true,
            quantity = 10.0,
            price = 10.0,
            fee = 0.0,
            isBuy = false,
            cashCoupled = false,
            currentCashEur = 0.0,
            asOfCashEur = 0.0,
            heldQuantity = 3.0,
        )
        assertTrue(v.oversellWarning)
        assertTrue(v.canSubmit) // soft warning, not a block
    }

    @Test
    fun `missing fields block submission`() {
        val v = validateTxForm(
            assetSelected = false,
            quantity = null,
            price = null,
            fee = null,
            isBuy = true,
            cashCoupled = false,
            currentCashEur = null,
            asOfCashEur = null,
            heldQuantity = null,
        )
        assertEquals(TxFieldError.MISSING, v.quantityError)
        assertEquals(TxFieldError.MISSING, v.priceError)
        assertTrue(v.assetMissing)
        assertFalse(v.canSubmit)
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    @Test
    fun `today keeps the current instant and past days stamp midday local`() {
        val zone = ZoneId.of("Europe/Vienna")
        val now = Instant.parse("2026-07-08T09:30:00Z")
        val today = now.atZone(zone).toLocalDate()

        assertEquals(now.toString(), executedAtIso(today, zone, now))

        val past = LocalDate.of(2026, 7, 1)
        assertEquals(
            past.atTime(12, 0).atZone(zone).toInstant().toString(),
            executedAtIso(past, zone, now),
        )
    }

    // ── Synced-note marker preservation ─────────────────────────────────────

    @Test
    fun `editing a synced note keeps its invisible bt marker`() {
        val original = "my note [bt:c963cc59-1111-2222-3333-444455556666]"
        assertEquals(
            "changed [bt:c963cc59-1111-2222-3333-444455556666]",
            mergeNotePreservingMarker("changed", original),
        )
        // Clearing the note still keeps the marker (reconcile/cleanup key).
        assertEquals(
            "[bt:c963cc59-1111-2222-3333-444455556666]",
            mergeNotePreservingMarker("", original),
        )
        // No marker on the original ⇒ plain note / null.
        assertEquals("plain", mergeNotePreservingMarker("plain", "old"))
        assertNull(mergeNotePreservingMarker("", "old"))
    }

    // ── Pending rows (§7.4) ──────────────────────────────────────────────────

    private fun op(
        id: Long,
        type: OpType = OpType.TX_BUY,
        status: OpStatus = OpStatus.PENDING,
        portfolioId: String = "p1",
        payload: TxOpPayload = TxOpPayload(
            assetId = "asset-1",
            side = if (type == OpType.TX_BUY) "buy" else "sell",
            quantity = 2.0,
            price = 50.0,
            executedAt = "2026-07-08T10:00:00Z",
            assetSymbol = "AMD",
            assetName = "Advanced Micro Devices",
        ),
        error: String? = null,
    ) = SyncOpEntity(
        id = id,
        clientId = "client-$id",
        opType = type.wire,
        portfolioId = portfolioId,
        payloadJson = json.encodeToString(TxOpPayload.serializer(), payload),
        status = status.wire,
        attemptCount = 0,
        nextAttemptAtMs = 0L,
        serverError = error,
        serverResultJson = null,
        accountKey = "u1",
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )

    @Test
    fun `decode maps statuses and skips other portfolios, non-tx and done ops`() {
        val ops = listOf(
            op(1, status = OpStatus.PENDING),
            op(2, status = OpStatus.IN_FLIGHT),
            op(3, status = OpStatus.NEEDS_ATTENTION, error = "Sell exceeds holding."),
            op(4, status = OpStatus.DONE),
            op(5, portfolioId = "OTHER"),
            SyncOpEntity(
                id = 6, clientId = "c6", opType = OpType.CASH_DEPOSIT.wire, portfolioId = "p1",
                payloadJson = "{}", status = OpStatus.PENDING.wire, attemptCount = 0,
                nextAttemptAtMs = 0, serverError = null, serverResultJson = null,
                accountKey = "u1", createdAtMs = 1, updatedAtMs = 1,
            ),
        )

        val rows = decodePendingTxRows(ops, json, portfolioId = "p1")

        assertEquals(listOf(3L, 2L, 1L), rows.map { it.opId }) // newest first, no done/other/cash
        assertEquals(PendingUiStatus.NEEDS_ATTENTION, rows[0].status)
        assertEquals("Sell exceeds holding.", rows[0].serverError)
        assertEquals(PendingUiStatus.SYNCING, rows[1].status)
        assertEquals(PendingUiStatus.PENDING, rows[2].status)
        assertEquals("AMD", rows[2].assetSymbol)
        assertEquals(100.0, transactionNotional(rows[2].quantity, rows[2].price), 1e-9)
    }

    @Test
    fun `pending rows honor the ledger display filters`() {
        val rows = decodePendingTxRows(
            listOf(op(1, OpType.TX_BUY), op(2, OpType.TX_SELL)),
            json,
            portfolioId = "p1",
        )

        assertEquals(listOf(2L), filterPendingTxRows(rows, TxSideFilter.SELL, null).map { it.opId })
        assertEquals(listOf(2L, 1L), filterPendingTxRows(rows, TxSideFilter.ALL, "asset-1").map { it.opId })
        assertTrue(filterPendingTxRows(rows, TxSideFilter.ALL, "nope").isEmpty())
    }

    // ── Cold-cache validation guard (§7.3, owner hotfix) ─────────────────────

    @Test
    fun `unknown cash balance never hard-blocks a cash-coupled buy`() {
        // Cold cache: cachedCashEur is null (UNKNOWN, not zero) → never a block.
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true, currentCashEur = null, asOfCashEur = null, heldQuantity = null,
        )
        assertFalse(v.insufficientCash)
        assertTrue(v.canSubmit)
    }

    @Test
    fun `known insufficient cash still hard-blocks`() {
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true, currentCashEur = 500.0, asOfCashEur = 500.0, heldQuantity = null,
        )
        assertTrue(v.insufficientCash)
        assertFalse(v.canSubmit)
    }

    // ── Three-way cash split: block today vs warn backdated vs clean (#378) ───
    // A pay-from-cash buy the Main wallet covers TODAY but not AS OF its backdated
    // date is now ALLOWED with an amber advisory (not blocked): the server settles
    // the cash leg today (settleCashAsOfToday) while the stock trade keeps its date.

    @Test
    fun `case 1 - short even today HARD-blocks and never warns`() {
        // Needs 1000; only 500 now (and 0 back then) → unaffordable today at all.
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true,
            currentCashEur = 500.0, asOfCashEur = 0.0, heldQuantity = null,
        )
        assertTrue(v.insufficientCash)
        assertFalse(v.backdatedCashWarning) // a hard block is never also a soft warning
        assertFalse(v.canSubmit)
    }

    @Test
    fun `case 2 - affordable today but short back then WARNS and still submits`() {
        // Needs 1000; 5000 now covers it, but the wallet held 0 as of the buy date.
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true,
            currentCashEur = 5000.0, asOfCashEur = 0.0, heldQuantity = null,
        )
        assertFalse(v.insufficientCash)
        assertTrue(v.backdatedCashWarning)
        assertTrue(v.canSubmit) // non-blocking — the owner's fix
    }

    @Test
    fun `case 3 - covered as of the date is clean, no cash flags`() {
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true,
            currentCashEur = 5000.0, asOfCashEur = 5000.0, heldQuantity = null,
        )
        assertFalse(v.insufficientCash)
        assertFalse(v.backdatedCashWarning)
        assertTrue(v.canSubmit)
    }

    @Test
    fun `the backdated warning is buy-only - a coupled sell never warns`() {
        // A sell adds proceeds; there is no as-of shortfall concept for it.
        val v = validateTxForm(
            assetSelected = true, quantity = 1.0, price = 100.0, fee = 0.0,
            isBuy = false, cashCoupled = true,
            currentCashEur = 5000.0, asOfCashEur = 0.0, heldQuantity = 10.0,
        )
        assertFalse(v.backdatedCashWarning)
        assertFalse(v.insufficientCash)
        assertTrue(v.canSubmit)
    }

    @Test
    fun `an unknown as-of balance never warns but still submits (server settles)`() {
        // Cold / unreconcilable ledger ⇒ as-of unknown ⇒ no client warning, yet it
        // is affordable today so it still submits (the flag lets the server settle
        // the cash leg as of today).
        val v = validateTxForm(
            assetSelected = true, quantity = 10.0, price = 100.0, fee = 0.0,
            isBuy = true, cashCoupled = true,
            currentCashEur = 5000.0, asOfCashEur = null, heldQuantity = null,
        )
        assertFalse(v.backdatedCashWarning)
        assertFalse(v.insufficientCash)
        assertTrue(v.canSubmit)
    }

    // ── Max-quantity math + input formatting (§6.2, owner request) ───────────

    @Test
    fun `max affordable subtracts fee and floors at zero`() {
        assertEquals(9.9, maxAffordableQuantity(1000.0, 100.0, 10.0), 1e-9)
        assertEquals(0.0, maxAffordableQuantity(50.0, 100.0, 60.0), 1e-9) // fee exceeds cash
        assertEquals(0.0, maxAffordableQuantity(1000.0, 0.0, 0.0), 1e-9) // non-positive price
    }

    // ── Main-source cash for pay-from-cash (§6.2, owner fix) ─────────────────

    private fun source(id: String, isMain: Boolean, balance: Double, archived: String? = null) =
        at.bettertrack.app.data.db.CashSourceEntity(
            id = id,
            portfolioId = "p1",
            name = id,
            kind = "cash",
            isMain = isMain,
            balanceEur = balance,
            archivedAt = archived,
        )

    @Test
    fun `main-source balance picks the main wallet, not the summed total`() {
        val sources = listOf(
            source("main", isMain = true, balance = 300.0),
            source("broker", isMain = false, balance = 700.0),
            source("savings", isMain = false, balance = 1000.0),
        )
        // The default wallet holds €300 even though total cash is €2000.
        assertEquals(300.0, mainCashSourceBalanceEur(sources)!!, 1e-9)
    }

    @Test
    fun `main-source balance is null when no main source is cached yet`() {
        assertNull(mainCashSourceBalanceEur(emptyList()))
        assertNull(mainCashSourceBalanceEur(listOf(source("broker", isMain = false, balance = 500.0))))
    }

    @Test
    fun `max buy sizes against the main wallet so the server never rejects insufficient`() {
        val sources = listOf(
            source("main", isMain = true, balance = 300.0),
            source("broker", isMain = false, balance = 700.0),
        )
        val main = mainCashSourceBalanceEur(sources)!!
        // Buying at €100 from the €300 Main wallet ⇒ 3 shares (not 10 from the
        // €1000 total, which the server would decline).
        assertEquals(3.0, maxAffordableQuantity(main, price = 100.0, fee = 0.0), 1e-9)
    }

    // ── Point-in-time (as-of) cash for a BACKDATED pay-from-cash buy ──────────
    // The server keeps cash non-negative at EVERY instant, so a backdated buy is
    // sized against the balance ON ITS DATE, not today's (§6.2 owner fix — the
    // false "Insufficient cash balance." on a €360 buy backdated ~15 months to a
    // day the Main source still held €0).

    private fun ms(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test
    fun `as-of available is zero before the source was ever funded`() {
        // Reproduces the owner's stuck op: Main first funded 2026-07-05; the failed
        // buy was dated 2025-04-14.
        val moves = listOf(
            ms("2026-07-05T00:00:00Z") to 6042.0,
            ms("2026-07-05T00:00:00Z") to -213.44,
            ms("2026-07-06T00:00:00Z") to -5828.55,
            ms("2026-07-06T00:00:00Z") to 5430.0,
        )
        val current = 5430.01 // 6042 − 213.44 − 5828.55 + 5430
        val backdated = ms("2025-04-14T10:00:00Z")
        val today = ms("2026-07-09T12:00:00Z")
        // Backdated to before ANY movement ⇒ nothing was available yet.
        assertEquals(0.0, availableMainCashAsOf(moves, current, backdated)!!, 1e-6)
        // So a €360 buy is correctly unaffordable AS OF that date …
        assertTrue(cashAfterPreview(availableMainCashAsOf(moves, current, backdated)!!, true, 2.0, 180.0, 0.0) < 0.0)
        // … yet perfectly affordable TODAY (dated at/after the newest movement).
        assertEquals(current, availableMainCashAsOf(moves, current, today)!!, 1e-6)
        assertFalse(cashAfterPreview(availableMainCashAsOf(moves, current, today)!!, true, 2.0, 180.0, 0.0) < 0.0)
    }

    @Test
    fun `as-of available is the running MINIMUM from the buy date onward`() {
        // Balance dips to €0.01 mid-history even though it recovers later.
        val moves = listOf(
            ms("2026-07-05T00:00:00Z") to 6000.0,
            ms("2026-07-06T00:00:00Z") to -5999.99, // trough: 0.01
            ms("2026-07-07T00:00:00Z") to 4000.0, // recovers: 4000.01
        )
        val current = 4000.01
        // A buy dated on/after the €6000 deposit still only clears the €0.01 trough.
        assertEquals(0.01, availableMainCashAsOf(moves, current, ms("2026-07-05T12:00:00Z"))!!, 1e-6)
        // A buy dated AFTER the trough sees the recovered balance.
        assertEquals(4000.01, availableMainCashAsOf(moves, current, ms("2026-07-07T12:00:00Z"))!!, 1e-6)
    }

    @Test
    fun `same-instant credits settle before debits so no phantom negative`() {
        // A funding deposit and a buy share ONE timestamp, buy listed FIRST. Credits
        // settle first, so a date before them still sees €0 held — not the buy's
        // −€213,44 (the exact artifact the owner would otherwise have seen on screen).
        val t = ms("2026-07-05T00:00:00Z")
        val moves = listOf(t to -213.44, t to 6042.0)
        assertEquals(0.0, availableMainCashAsOf(moves, 5828.56, ms("2025-04-14T00:00:00Z"))!!, 1e-6)
    }

    @Test
    fun `as-of returns null when the cached ledger cannot be reconciled`() {
        // Movements don't sum to the live balance (partial / unsynced ledger) → the
        // reconstruction is untrusted, so the caller falls back to the current
        // balance and never HARD-blocks on bad data.
        val moves = listOf(ms("2026-07-05T00:00:00Z") to 100.0)
        assertNull(availableMainCashAsOf(moves, currentBalanceEur = 5000.0, executedAtMs = ms("2026-07-06T00:00:00Z")))
        // An empty ledger consistent with a zero balance reconciles fine.
        assertEquals(0.0, availableMainCashAsOf(emptyList(), 0.0, ms("2026-07-06T00:00:00Z"))!!, 1e-9)
    }

    @Test
    fun `executedAtMsFor mirrors executedAtIso - today is now, a past day is midday`() {
        val zone = ZoneId.of("Europe/Vienna")
        val now = Instant.parse("2026-07-09T08:30:00Z")
        assertEquals(now.toEpochMilli(), executedAtMsFor(LocalDate.of(2026, 7, 9), zone, now))
        val past = LocalDate.of(2025, 4, 14)
        val expectedPast = past.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expectedPast, executedAtMsFor(past, zone, now))
    }

    @Test
    fun `format decimal for input trims zeros and honours locale separator`() {
        assertEquals("5", formatDecimalForInput(5.0, java.util.Locale.US))
        assertEquals("100", formatDecimalForInput(100.0, java.util.Locale.US))
        assertEquals("0.0333", formatDecimalForInput(0.0333, java.util.Locale.US))
        assertEquals("0,0333", formatDecimalForInput(0.0333, java.util.Locale.GERMANY))
    }

    // ── Date→price link lookup (§6.2) ────────────────────────────────────────

    @Test
    fun `close on or before returns exact, prior, or earliest`() {
        val series = listOf(
            LocalDate.of(2026, 6, 29) to 100.0,
            LocalDate.of(2026, 6, 30) to 101.0,
            LocalDate.of(2026, 7, 2) to 103.0,
        )
        assertEquals(101.0, closeOnOrBefore(series, LocalDate.of(2026, 6, 30))!!, 1e-9) // exact
        assertEquals(101.0, closeOnOrBefore(series, LocalDate.of(2026, 7, 1))!!, 1e-9) // prior trading day
        assertEquals(100.0, closeOnOrBefore(series, LocalDate.of(2026, 6, 1))!!, 1e-9) // before all → earliest
        assertNull(closeOnOrBefore(emptyList(), LocalDate.of(2026, 7, 1)))
    }

    // ── Reverse link: price→date lookup (§6.2, mirrors web `dateForPrice`) ────

    // An ascending series with a weekend gap: Fri 2026-06-05 → Mon 2026-06-08,
    // identical to the web app's priceDateLink.test.ts fixture so both platforms
    // resolve a typed price to the same day.
    private val reverseSeries = listOf(
        LocalDate.of(2026, 6, 1) to 100.0,
        LocalDate.of(2026, 6, 2) to 110.0,
        LocalDate.of(2026, 6, 3) to 90.0,
        LocalDate.of(2026, 6, 4) to 95.0,
        LocalDate.of(2026, 6, 5) to 105.0, // Friday
        LocalDate.of(2026, 6, 8) to 108.0, // Monday
    )

    @Test
    fun `price to date returns the exact close on the most recent matching day`() {
        assertEquals(LocalDate.of(2026, 6, 8), mostRecentDateAtPrice(reverseSeries, 108.0))
    }

    @Test
    fun `a price crossed between two closes lands on the later day`() {
        // 100 lies in the (95 → 105) 06-04→06-05 move; the crossing day is 06-05.
        assertEquals(LocalDate.of(2026, 6, 5), mostRecentDateAtPrice(reverseSeries, 100.0))
    }

    @Test
    fun `an exact historical close is attributed to its own day, not the later boundary`() {
        // 105 is Friday 06-05's close AND the lower bound of the (105 → 108) move;
        // the exact day wins over the boundary of the later segment.
        assertEquals(LocalDate.of(2026, 6, 5), mostRecentDateAtPrice(reverseSeries, 105.0))
    }

    @Test
    fun `price to date picks the MOST RECENT crossing when a price occurs more than once`() {
        // 92 lies in (90,95) on 06-04 and also in (110,90) on 06-03 — newest wins.
        assertEquals(LocalDate.of(2026, 6, 4), mostRecentDateAtPrice(reverseSeries, 92.0))
    }

    @Test
    fun `a price never reached in history returns null`() {
        assertNull(mostRecentDateAtPrice(reverseSeries, 5.0))
        assertNull(mostRecentDateAtPrice(reverseSeries, 500.0))
    }

    @Test
    fun `single-point series matches only its exact close`() {
        val one = listOf(LocalDate.of(2026, 6, 1) to 100.0)
        assertEquals(LocalDate.of(2026, 6, 1), mostRecentDateAtPrice(one, 100.0))
        assertNull(mostRecentDateAtPrice(one, 99.0))
    }

    @Test
    fun `price to date rejects a non-finite price and an empty series`() {
        assertNull(mostRecentDateAtPrice(reverseSeries, Double.NaN))
        assertNull(mostRecentDateAtPrice(emptyList(), 100.0))
    }

    // ── Uncovered (over-)sell gating (PR #429, Step 19) ──────────────────────

    @Test
    fun `a buy is never an uncovered sell`() {
        assertFalse(uncoveredSellState(isBuy = true, quantity = 999.0, heldQuantity = 1.0).active)
    }

    @Test
    fun `selling within the held amount is not uncovered`() {
        val s = uncoveredSellState(isBuy = false, quantity = 3.0, heldQuantity = 5.0)
        assertFalse(s.active)
        assertEquals(0.0, s.uncoveredQuantity, 1e-9)
    }

    @Test
    fun `selling exactly the held amount is covered (not uncovered)`() {
        assertFalse(uncoveredSellState(isBuy = false, quantity = 5.0, heldQuantity = 5.0).active)
    }

    @Test
    fun `selling more than held is uncovered by the excess`() {
        val s = uncoveredSellState(isBuy = false, quantity = 8.0, heldQuantity = 5.0)
        assertTrue(s.active)
        assertEquals(3.0, s.uncoveredQuantity, 1e-9)
        assertEquals(5.0, s.heldQuantity, 1e-9)
    }

    @Test
    fun `selling a not-held asset (null held) is fully uncovered`() {
        val s = uncoveredSellState(isBuy = false, quantity = 2.0, heldQuantity = null)
        assertTrue(s.active)
        assertEquals(2.0, s.uncoveredQuantity, 1e-9)
        assertEquals(0.0, s.heldQuantity, 1e-9)
    }

    @Test
    fun `a null or non-positive quantity is never uncovered`() {
        assertFalse(uncoveredSellState(isBuy = false, quantity = null, heldQuantity = 0.0).active)
        assertFalse(uncoveredSellState(isBuy = false, quantity = 0.0, heldQuantity = 0.0).active)
    }

    @Test
    fun `a tiny fractional overage within epsilon is treated as covered`() {
        // Float noise on fractional shares must not spuriously demand the ack.
        val s = uncoveredSellState(isBuy = false, quantity = 5.0 + 1e-12, heldQuantity = 5.0)
        assertFalse(s.active)
    }

    @Test
    fun `uncovered submit is gated on the acknowledgment`() {
        val uncovered = uncoveredSellState(isBuy = false, quantity = 8.0, heldQuantity = 5.0)
        assertFalse(uncoveredSellSubmitOk(uncovered, acknowledged = false))
        assertTrue(uncoveredSellSubmitOk(uncovered, acknowledged = true))
    }

    @Test
    fun `a covered sell submits regardless of the acknowledgment flag`() {
        val covered = uncoveredSellState(isBuy = false, quantity = 3.0, heldQuantity = 5.0)
        assertTrue(uncoveredSellSubmitOk(covered, acknowledged = false))
    }

    @Test
    fun `optional buy-in price parses positive, else null`() {
        assertEquals(150.0, parseUncoveredEntryPrice("150"))
        assertEquals(80.5, parseUncoveredEntryPrice("80,5")) // comma-tolerant
        assertNull(parseUncoveredEntryPrice(""))
        assertNull(parseUncoveredEntryPrice("0"))
        assertNull(parseUncoveredEntryPrice("-3"))
        assertNull(parseUncoveredEntryPrice("abc"))
    }
}
