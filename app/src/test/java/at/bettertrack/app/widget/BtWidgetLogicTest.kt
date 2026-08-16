package at.bettertrack.app.widget

import at.bettertrack.app.data.api.dto.CashBudgetListResponse
import at.bettertrack.app.data.api.dto.CashBudgetProgressDto
import at.bettertrack.app.data.api.dto.CashSummaryResponse
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.WatchlistEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.format.BT_EM_DASH
import at.bettertrack.app.ui.format.btMaskedMoney
import at.bettertrack.app.ui.home.HomeHeroState
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.PriceCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The widgets' pure half: what the home screen is allowed to say, and what it
 * must refuse to say.
 *
 * Every case here is a claim about honesty rather than about layout — a widget
 * is the one surface the user does not open on purpose, so a figure on it is
 * trusted without being interrogated. The two that matter most are discreet mode
 * (a masked amount must stay masked on a launcher even while the app is
 * revealing it) and the absent-value cases (an em dash, never a zero).
 */
class BtWidgetLogicTest {

    private val de = Locale.GERMANY

    private fun item(assetId: String, symbol: String = assetId, currency: String = "USD") =
        WatchlistItemEntity(
            id = "wi-$assetId",
            watchlistId = "workboard",
            assetId = assetId,
            sortOrder = 0,
            note = null,
            assetSymbol = symbol,
            assetName = "$symbol Inc.",
            assetExchange = "NASDAQ",
            assetCurrency = currency,
            assetType = "stock",
        )

    private fun holding(assetId: String, price: Double?, dayPct: Double?, currency: String = "USD") =
        HoldingEntity(
            portfolioId = "p1",
            assetId = assetId,
            assetSymbol = assetId,
            assetName = "$assetId Inc.",
            assetExchange = "NASDAQ",
            assetCurrency = currency,
            assetType = "stock",
            assetIsCustom = false,
            quantity = 1.0,
            avgCost = 1.0,
            realizedPnl = 0.0,
            price = price,
            marketValueEur = 100.0,
            costBasisEur = 90.0,
            unrealizedPnlEur = 10.0,
            unrealizedPnlPct = 11.1,
            dayChangeEur = 1.0,
            dayChangePct = dayPct,
        )

    /** A holding with controllable stat aggregates, for [btWidgetStats]. */
    private fun stat(
        assetId: String,
        costBasisEur: Double?,
        unrealizedPnlEur: Double?,
    ) = HoldingEntity(
        portfolioId = "p1",
        assetId = assetId,
        assetSymbol = assetId,
        assetName = "$assetId Inc.",
        assetExchange = "NASDAQ",
        assetCurrency = "USD",
        assetType = "stock",
        assetIsCustom = false,
        quantity = 1.0,
        avgCost = 1.0,
        realizedPnl = 0.0,
        price = 1.0,
        marketValueEur = 100.0,
        costBasisEur = costBasisEur,
        unrealizedPnlEur = unrealizedPnlEur,
        unrealizedPnlPct = null,
        dayChangeEur = 1.0,
        dayChangePct = 1.0,
    )

    private fun ready(
        eur: Double?,
        dayEur: Double = 12.5,
        dayPct: Double? = 1.25,
        showDayChange: Boolean = true,
        covered: Int = 2,
        active: Int = 2,
    ): HomeHeroState.Ready {
        val coverage = PriceCoverage(priced = 3, unpriced = 0)
        return HomeHeroState.Ready(
            netWorth = if (eur == null) {
                NetWorthState.Unpriceable(coverage)
            } else {
                NetWorthState.Value(eur, coverage)
            },
            dayChangeEur = dayEur,
            dayChangePct = dayPct,
            showDayChange = showDayChange,
            covered = covered,
            active = active,
        )
    }

    // ── The hero, flattened ───────────────────────────────────────────────────

    @Test
    fun `a ready hero carries its figure and its day change through`() {
        val net = btWidgetNetWorth(ready(eur = 1234.56))
        assertEquals(1234.56, net?.eur)
        assertEquals(12.5, net?.dayChangeEur)
        assertEquals(1.25, net?.dayChangePct)
        assertFalse(net!!.partial)
    }

    @Test
    fun `loading and no-portfolios carry no figure at all`() {
        assertNull(btWidgetNetWorth(HomeHeroState.Loading))
        assertNull(btWidgetNetWorth(HomeHeroState.NoPortfolios))
    }

    @Test
    fun `an unpriceable net worth is absent, not zero`() {
        // NetWorthState.Unpriceable means nothing could be priced. Rendering that
        // as 0 would tell the user they own nothing.
        assertNull(btWidgetNetWorth(ready(eur = null))?.eur)
    }

    @Test
    fun `a day change the hero will not show is dropped rather than shown as zero`() {
        val net = btWidgetNetWorth(ready(eur = 100.0, dayEur = 0.0, dayPct = null, showDayChange = false))
        assertNull("no prices means no day change, not a +0,00", net?.dayChangeEur)
        assertNull(net?.dayChangePct)
    }

    @Test
    fun `partial coverage is reported when an active portfolio has never synced`() {
        assertTrue(btWidgetNetWorth(ready(eur = 100.0, covered = 1, active = 3))!!.partial)
    }

    // ── Discreet mode ─────────────────────────────────────────────────────────

    @Test
    fun `discreet mode masks the amount with the app's own mask`() {
        val masked = btWidgetMoney(1234.56, "EUR", discreet = true, locale = de)
        assertEquals(btMaskedMoney("EUR", de), masked)
        assertFalse("a masked amount must not leak digits", masked.any { it.isDigit() })
    }

    @Test
    fun `an unmasked widget amount formats exactly like the app's`() {
        // The property that matters is not a specific glyph sequence, it is that
        // the widget and the screen cannot disagree.
        assertEquals(
            formatMoney(1234.56, "EUR", de),
            btWidgetMoney(1234.56, "EUR", discreet = false, locale = de),
        )
        assertEquals(
            formatMoney(12.5, "EUR", de, showSign = true),
            btWidgetMoney(12.5, "EUR", discreet = false, locale = de, showSign = true),
        )
    }

    @Test
    fun `a missing amount is an em dash in both modes`() {
        assertEquals(BT_EM_DASH, btWidgetMoney(null, "EUR", discreet = false, locale = de))
        // Discreet still masks: "no value" is itself information about the account.
        assertEquals(btMaskedMoney("EUR", de), btWidgetMoney(null, "EUR", discreet = true, locale = de))
    }

    @Test
    fun `percentages stay live under discreet mode`() {
        // Matches the app: discreet hides absolute amounts and deliberately keeps
        // relative figures, which is what makes it usable in public.
        val pct = btWidgetPercent(1.25, de)
        assertTrue("a percentage must survive discreet mode", pct.any { it.isDigit() })
        assertEquals(BT_EM_DASH, btWidgetPercent(null, de))
    }

    // ── Tone ──────────────────────────────────────────────────────────────────

    @Test
    fun `zero and unknown are flat, not gains`() {
        assertEquals(BtWidgetTone.FLAT, btWidgetTone(0.0))
        assertEquals(BtWidgetTone.FLAT, btWidgetTone(null))
        assertEquals(BtWidgetTone.UP, btWidgetTone(0.01))
        assertEquals(BtWidgetTone.DOWN, btWidgetTone(-0.01))
    }

    // ── Watchlist rows ────────────────────────────────────────────────────────

    @Test
    fun `a cached quote wins and is reported as EUR`() {
        val rows = btWidgetRows(
            items = listOf(item("AAPL")),
            quotes = mapOf("AAPL" to BtWidgetQuote(eurPrice = 180.0, dayChangePct = 1.5)),
            holdings = listOf(holding("AAPL", price = 195.0, dayPct = 9.9)),
        )
        assertEquals(180.0, rows.single().price)
        assertEquals("EUR", rows.single().currency)
        assertEquals(1.5, rows.single().dayChangePct)
    }

    @Test
    fun `a held asset with no quote falls back to its NATIVE price, uncoverted`() {
        // The fallback must not convert: there is no rate here, and inventing one
        // is exactly the money arithmetic the widget refuses to do.
        val rows = btWidgetRows(
            items = listOf(item("MSFT", currency = "USD")),
            quotes = emptyMap(),
            holdings = listOf(holding("MSFT", price = 410.0, dayPct = -0.44)),
        )
        assertEquals(410.0, rows.single().price)
        assertEquals("USD", rows.single().currency)
        assertEquals(-0.44, rows.single().dayChangePct)
    }

    @Test
    fun `an unquoted, unheld asset still renders as a row with no price`() {
        val row = btWidgetRows(listOf(item("TSLA")), emptyMap(), emptyList()).single()
        assertNull(row.price)
        assertNull(row.dayChangePct)
        assertEquals("TSLA", row.symbol)
    }

    @Test
    fun `rows are capped so a background refresh cannot fan out without bound`() {
        val many = (1..40).map { item("A$it") }
        assertEquals(BT_WIDGET_ROW_LIMIT, btWidgetRows(many, emptyMap(), emptyList()).size)
    }

    // ── Board selection ───────────────────────────────────────────────────────

    @Test
    fun `the default board wins, else the first`() {
        val a = WatchlistEntity(id = "a", name = "A", isDefault = false, sortOrder = 0)
        val b = WatchlistEntity(id = "b", name = "B", isDefault = true, sortOrder = 1)
        assertEquals("b", btWidgetBoard(listOf(a, b))?.id)
        assertEquals("a", btWidgetBoard(listOf(a))?.id)
        assertNull(btWidgetBoard(emptyList()))
    }

    // ── Quote cache merge ─────────────────────────────────────────────────────

    @Test
    fun `a failed asset keeps its previous quote instead of blanking`() {
        val previous = BtWidgetQuoteCache(
            cachedAtMs = 1_000L,
            quotes = mapOf("A" to BtWidgetQuote(1.0, 1.0), "B" to BtWidgetQuote(2.0, 2.0)),
        )
        val merged = btWidgetMergeQuotes(
            previous = previous,
            fetched = mapOf("A" to BtWidgetQuote(1.5, 1.5)),
            keep = setOf("A", "B"),
            nowMs = 2_000L,
        )
        assertEquals(1.5, merged.quotes["A"]?.eurPrice)
        assertEquals("B kept its last-known figure", 2.0, merged.quotes["B"]?.eurPrice)
        assertEquals(2_000L, merged.cachedAtMs)
    }

    @Test
    fun `an asset removed from the board is dropped from the cache`() {
        val merged = btWidgetMergeQuotes(
            previous = BtWidgetQuoteCache(1_000L, mapOf("A" to BtWidgetQuote(1.0, 1.0))),
            fetched = emptyMap(),
            keep = emptySet(),
            nowMs = 2_000L,
        )
        assertTrue(merged.quotes.isEmpty())
    }

    @Test
    fun `a pass that fetched nothing does not advance the as-of clock`() {
        // Otherwise "as of" would keep refreshing while the figures never changed,
        // which is the one thing the note exists to rule out.
        val merged = btWidgetMergeQuotes(
            previous = BtWidgetQuoteCache(1_000L, mapOf("A" to BtWidgetQuote(1.0, 1.0))),
            fetched = emptyMap(),
            keep = setOf("A"),
            nowMs = 9_000L,
        )
        assertEquals(1_000L, merged.cachedAtMs)
    }

    // ── Staleness ─────────────────────────────────────────────────────────────

    @Test
    fun `staleness needs a timestamp and a real gap`() {
        val now = 1_800_000_000_000L // a real wall clock; the window is 3h wide
        assertFalse("never synced is not stale, it is empty", btWidgetStale(null, now))
        // 0 is the "no timestamp recorded" sentinel the quote cache starts at, and
        // it must read the same as null rather than as "stale since 1970".
        assertFalse(btWidgetStale(0L, now))
        assertFalse(btWidgetStale(now - 1000L, now))
        assertTrue(btWidgetStale(now - BT_WIDGET_STALE_AFTER_MS - 1L, now))
    }

    @Test
    fun `the stale window survives more than one missed refresh`() {
        // A single skipped cycle (Doze, no network) is ordinary and must not brand
        // the figure as stale.
        val cycleMs = BtWidgetScheduler.REFRESH_INTERVAL_MINUTES * 60_000L
        assertTrue(
            "the stale window must exceed two refresh cycles",
            BT_WIDGET_STALE_AFTER_MS > 2 * cycleMs,
        )
    }

    // ── The signed-out snapshot ───────────────────────────────────────────────

    @Test
    fun `a signed-out snapshot carries no data at all`() {
        // Not "hidden" — absent. A widget is visible on a lock screen, so the
        // signed-out state must be incapable of leaking a cached figure.
        val s = BtWidgetSnapshot.signedOut(1L)
        assertEquals(BtWidgetSession.SIGNED_OUT, s.session)
        assertNull(s.netWorth)
        assertTrue(s.rows.isEmpty())
        assertNull(s.netWorthAsOfMs)
        assertNull(s.quotesAsOfMs)
        assertFalse(s.discreet)
    }

    @Test
    fun `a signed-in account with portfolios but no totals reads as syncing`() {
        val s = BtWidgetSnapshot(
            session = BtWidgetSession.READY,
            discreet = false,
            netWorth = null,
            noPortfolios = false,
            netWorthAsOfMs = null,
            rows = emptyList(),
            quotesAsOfMs = null,
            nowMs = 1L,
        )
        assertTrue(s.netWorthSyncing)
        assertFalse(s.copy(noPortfolios = true).netWorthSyncing)
    }

    // ── Portfolio stats ───────────────────────────────────────────────────────

    @Test
    fun `stats sum the holdings' EUR aggregates and derive the percent from the sums`() {
        val stats = btWidgetStats(
            listOf(
                stat("A", costBasisEur = 100.0, unrealizedPnlEur = 20.0),
                stat("B", costBasisEur = 300.0, unrealizedPnlEur = -30.0),
            ),
        )
        assertEquals(400.0, stats.investedEur)
        assertEquals(-10.0, stats.unrealizedPnlEur)
        assertEquals(-10.0 / 400.0 * 100.0, stats.unrealizedPnlPct!!, 1e-9)
        assertEquals(2, stats.holdingsCount)
    }

    @Test
    fun `stats over an empty book are absent, not zero`() {
        // A zero would tell the user they broke exactly even; "not known" is honest.
        val stats = btWidgetStats(emptyList())
        assertNull(stats.investedEur)
        assertNull(stats.unrealizedPnlEur)
        assertNull(stats.unrealizedPnlPct)
        assertEquals(0, stats.holdingsCount)
    }

    @Test
    fun `stats skip the holdings whose detail has not synced`() {
        val stats = btWidgetStats(
            listOf(
                stat("A", costBasisEur = 100.0, unrealizedPnlEur = 25.0),
                stat("B", costBasisEur = null, unrealizedPnlEur = null),
            ),
        )
        assertEquals(100.0, stats.investedEur)
        assertEquals(25.0, stats.unrealizedPnlEur)
        // The count is positions held, present figures or not.
        assertEquals(2, stats.holdingsCount)
    }

    @Test
    fun `the P&L percent is guarded against a zero cost basis`() {
        val stats = btWidgetStats(listOf(stat("A", costBasisEur = 0.0, unrealizedPnlEur = 5.0)))
        assertNull("no base to express the P&L against", stats.unrealizedPnlPct)
        assertEquals(5.0, stats.unrealizedPnlEur)
    }

    // ── Top movers ──────────────────────────────────────────────────────────────

    @Test
    fun `movers rank by absolute day move and drop the ones with no known move`() {
        val movers = btWidgetMovers(
            listOf(
                holding("A", price = 1.0, dayPct = 2.0),
                holding("B", price = 1.0, dayPct = -9.0),
                holding("C", price = 1.0, dayPct = null),
            ),
        )
        assertEquals(listOf("B", "A"), movers.map { it.symbol })
        assertEquals(-9.0, movers.first().dayChangePct, 0.0)
    }

    @Test
    fun `a mover carries its EUR move for the wide layout`() {
        val movers = btWidgetMovers(listOf(holding("A", price = 1.0, dayPct = 3.0)))
        assertEquals(1.0, movers.single().dayChangeEur)
        assertEquals("A", movers.single().assetId)
    }

    @Test
    fun `movers are capped to the widget limit`() {
        val many = (1..20).map { holding("A$it", price = 1.0, dayPct = it.toDouble()) }
        assertEquals(BT_WIDGET_MOVERS_LIMIT, btWidgetMovers(many).size)
    }

    // ── Budget progress ───────────────────────────────────────────────────────

    @Test
    fun `the budget bar fills to the spend but clamps, while the percent does not`() {
        assertEquals(0.5f, btWidgetBudgetFraction(50.0, 100.0))
        assertEquals(0f, btWidgetBudgetFraction(0.0, 100.0))
        // Over budget: the bar can only fill to full, the label tells the truth.
        assertEquals(1f, btWidgetBudgetFraction(130.0, 100.0))
        assertEquals(130.0, btWidgetBudgetPercent(130.0, 100.0))
        assertEquals(50.0, btWidgetBudgetPercent(50.0, 100.0))
    }

    @Test
    fun `budget math is safe against a non-positive limit`() {
        assertEquals(0f, btWidgetBudgetFraction(10.0, 0.0))
        assertNull(btWidgetBudgetPercent(10.0, 0.0))
    }

    // ── Budget cache build ──────────────────────────────────────────────────────

    @Test
    fun `the budget cache flattens the server rows and stays available`() {
        val cache = btWidgetBudgetCache(
            portfolioId = "pf-1",
            budgets = CashBudgetListResponse(
                period = "2026-08",
                budgets = listOf(
                    CashBudgetProgressDto(
                        id = "b1",
                        tagName = "Food",
                        amount = 200.0,
                        spent = 250.0,
                        exceeded = true,
                        currency = "EUR",
                    ),
                ),
            ),
            summary = CashSummaryResponse(net = -42.0),
            nowMs = 5_000L,
        )
        assertTrue(cache.available)
        assertEquals("pf-1", cache.portfolioId)
        assertEquals("2026-08", cache.period)
        assertEquals(-42.0, cache.netEur)
        assertEquals(5_000L, cache.cachedAtMs)
        val b = cache.budgets.single()
        assertEquals("Food", b.tagName)
        assertEquals(250.0, b.spent, 0.0)
        assertEquals(200.0, b.amount, 0.0)
        assertTrue(b.exceeded)
    }

    @Test
    fun `the budget cache tolerates a missing summary`() {
        // The summary is a header-only companion read; losing it must not cost the
        // bars, so the cache still renders with a null net.
        val cache = btWidgetBudgetCache(
            portfolioId = "pf-1",
            budgets = CashBudgetListResponse(period = "2026-08"),
            summary = null,
            nowMs = 1L,
        )
        assertNull(cache.netEur)
        assertTrue(cache.available)
        assertTrue(cache.budgets.isEmpty())
    }

    @Test
    fun `the unavailable budget cache is empty and marked so`() {
        // The Drive-mode / cash-403 state: distinct from an empty server board.
        assertFalse(BtWidgetBudgetCache.UNAVAILABLE.available)
        assertTrue(BtWidgetBudgetCache.UNAVAILABLE.budgets.isEmpty())
        assertTrue(BtWidgetBudgetCache.EMPTY.available)
    }
}
