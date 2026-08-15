package at.bettertrack.app.widget

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
}
