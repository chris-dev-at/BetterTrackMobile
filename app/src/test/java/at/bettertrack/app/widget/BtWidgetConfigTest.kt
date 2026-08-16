package at.bettertrack.app.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.WatchlistItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The configurable widgets' pure half: the Preferences codecs a widget's stored
 * choice round-trips through, the pickers' option lists, and the asset row's
 * source precedence. All JVM — the same discipline as the rest of the package:
 * what a widget CLAIMS is tested, what it draws is geometry.
 */
class BtWidgetConfigTest {

    private fun holding(assetId: String, price: Double? = 100.0, dayPct: Double? = 1.0) =
        HoldingEntity(
            portfolioId = "p1",
            assetId = assetId,
            assetSymbol = assetId,
            assetName = "$assetId Inc.",
            assetExchange = null,
            assetCurrency = "USD",
            assetType = "stock",
            assetIsCustom = false,
            quantity = 1.0,
            avgCost = 1.0,
            realizedPnl = 0.0,
            price = price,
            marketValueEur = 100.0,
            costBasisEur = null,
            unrealizedPnlEur = null,
            unrealizedPnlPct = null,
            dayChangeEur = null,
            dayChangePct = dayPct,
        )

    private fun item(assetId: String, symbol: String = assetId) = WatchlistItemEntity(
        id = "wi-$assetId",
        watchlistId = "board",
        assetId = assetId,
        sortOrder = 0,
        note = null,
        assetSymbol = symbol,
        assetName = "$symbol Corp.",
        assetExchange = null,
        assetCurrency = "GBP",
        assetType = "stock",
    )

    private fun portfolio(id: String, sortOrder: Int, archivedAt: String? = null) =
        PortfolioEntity(
            id = id,
            name = "PF $id",
            visibility = "private",
            sortOrder = sortOrder,
            isDefault = false,
            defaultPayFromCash = false,
            archivedAt = archivedAt,
            baseCurrency = null,
            totals = null,
            detailSyncedAtMs = null,
        )

    // ── Codecs ────────────────────────────────────────────────────────────────

    @Test
    fun `an asset choice round-trips through the widget's preferences`() {
        val config = BtWidgetAssetConfig("as-1", "AAPL", "Apple Inc.", "USD")
        val prefs = mutablePreferencesOf()
        btWidgetPutAssetConfig(prefs, config)
        assertEquals(config, btWidgetAssetConfig(prefs))
    }

    @Test
    fun `a portfolio choice round-trips through the widget's preferences`() {
        val config = BtWidgetPortfolioConfig("pf-1", "Depot")
        val prefs = mutablePreferencesOf()
        btWidgetPutPortfolioConfig(prefs, config)
        assertEquals(config, btWidgetPortfolioConfig(prefs))
    }

    @Test
    fun `empty preferences decode to unconfigured, not to a blank widget`() {
        assertNull(btWidgetAssetConfig(mutablePreferencesOf()))
        assertNull(btWidgetPortfolioConfig(mutablePreferencesOf()))
    }

    @Test
    fun `a blank stored id reads as unconfigured`() {
        val prefs = mutablePreferencesOf()
        btWidgetPutAssetConfig(prefs, BtWidgetAssetConfig("  ", "X", "X", "EUR"))
        assertNull("a whitespace id must not configure a widget", btWidgetAssetConfig(prefs))
    }

    @Test
    fun `a missing currency falls back to EUR rather than an empty unit`() {
        val prefs = mutablePreferencesOf()
        prefs[BT_WIDGET_PREF_ASSET_ID] = "as-1"
        assertEquals(BT_WIDGET_QUOTE_CURRENCY, btWidgetAssetConfig(prefs)!!.currency)
    }

    // ── Picker option lists ───────────────────────────────────────────────────

    @Test
    fun `asset choices unite held and watched, held identity winning, sorted by symbol`() {
        val choices = btWidgetAssetChoices(
            holdings = listOf(holding("ZZ"), holding("AA")),
            watchlistItems = listOf(item("AA", symbol = "AA"), item("MM")),
        )
        assertEquals(listOf("AA", "MM", "ZZ"), choices.map { it.symbol })
        // AA is held AND watched: the held row's native currency wins, because
        // that is the unit an offline fallback price will arrive in.
        assertEquals("USD", choices.first { it.assetId == "AA" }.currency)
        assertEquals("GBP", choices.first { it.assetId == "MM" }.currency)
    }

    @Test
    fun `portfolio choices are the active portfolios in switcher order`() {
        val choices = btWidgetPortfolioChoices(
            listOf(
                portfolio("b", sortOrder = 2),
                portfolio("archived", sortOrder = 0, archivedAt = "2026-01-01T00:00:00Z"),
                portfolio("a", sortOrder = 1),
            ),
        )
        assertEquals(listOf("a", "b"), choices.map { it.id })
    }

    // ── The configured asset's row ────────────────────────────────────────────

    @Test
    fun `a cached quote wins and is EUR, exactly like a watchlist row`() {
        val row = btWidgetAssetRow(
            config = BtWidgetAssetConfig("as-1", "AAPL", "Apple Inc.", "USD"),
            quotes = mapOf("as-1" to BtWidgetQuote(eurPrice = 180.0, dayChangePct = 1.5)),
            holdings = listOf(holding("as-1", price = 195.0, dayPct = 9.9)),
        )
        assertEquals(180.0, row.price)
        assertEquals("EUR", row.currency)
        assertEquals(1.5, row.dayChangePct)
        assertEquals("AAPL", row.symbol)
    }

    @Test
    fun `with no quote a held position's NATIVE price is the fallback, unconverted`() {
        val row = btWidgetAssetRow(
            config = BtWidgetAssetConfig("as-1", "AAPL", "Apple Inc.", "EUR"),
            quotes = emptyMap(),
            holdings = listOf(holding("as-1", price = 195.0, dayPct = -0.4)),
        )
        assertEquals(195.0, row.price)
        assertEquals("the holding's native unit, not the config's", "USD", row.currency)
        assertEquals(-0.4, row.dayChangePct)
    }

    @Test
    fun `with neither source the row keeps its identity and shows no price`() {
        val row = btWidgetAssetRow(
            config = BtWidgetAssetConfig("as-1", "AAPL", "Apple Inc.", "USD"),
            quotes = emptyMap(),
            holdings = emptyList(),
        )
        assertNull(row.price)
        assertNull(row.dayChangePct)
        assertEquals("AAPL", row.symbol)
        assertEquals("Apple Inc.", row.name)
    }

    // ── Budget config (the owner's "Food €300 as ring, bar or number") ───────

    @Test
    fun `a budget choice round-trips through the widget's preferences`() {
        val config = BtWidgetBudgetConfig("b1", "Food", BtWidgetBudgetStyle.BAR)
        val prefs = mutablePreferencesOf()
        btWidgetPutBudgetConfig(prefs, config)
        assertEquals(config, btWidgetBudgetConfig(prefs))
    }

    @Test
    fun `no budget config means the all-budgets list, and clearing returns to it`() {
        val prefs = mutablePreferencesOf()
        assertNull(btWidgetBudgetConfig(prefs))
        btWidgetPutBudgetConfig(prefs, BtWidgetBudgetConfig("b1", "Food", BtWidgetBudgetStyle.RING))
        btWidgetClearBudgetConfig(prefs)
        assertNull("cleared config must read as the list mode again", btWidgetBudgetConfig(prefs))
    }

    @Test
    fun `an unknown stored style degrades to the ring, never crashes the launcher`() {
        assertEquals(BtWidgetBudgetStyle.RING, btWidgetBudgetStyle("HOLOGRAM"))
        assertEquals(BtWidgetBudgetStyle.RING, btWidgetBudgetStyle(null))
        assertEquals(BtWidgetBudgetStyle.AMOUNT, btWidgetBudgetStyle("AMOUNT"))
    }

    @Test
    fun `a configured budget resolves by id first and falls back by tag name`() {
        val rows = listOf(
            BtWidgetBudget(id = "b1", tagName = "Food", spent = 120.0, amount = 300.0),
            BtWidgetBudget(id = "b2", tagName = "Fun", spent = 10.0, amount = 50.0),
        )
        val byId = btWidgetResolveBudget(
            BtWidgetBudgetConfig("b2", "Renamed", BtWidgetBudgetStyle.RING),
            rows,
        )
        assertEquals("b2", byId?.id)
        // The budget was deleted and recreated for the same tag: the id is new,
        // the tag survives, the widget stays alive.
        val byTag = btWidgetResolveBudget(
            BtWidgetBudgetConfig("gone", "Food", BtWidgetBudgetStyle.RING),
            rows,
        )
        assertEquals("b1", byTag?.id)
        assertNull(
            btWidgetResolveBudget(
                BtWidgetBudgetConfig("gone", "Nothing", BtWidgetBudgetStyle.RING),
                rows,
            ),
        )
    }

    @Test
    fun `clearing the portfolio config returns the widget to follow mode`() {
        val prefs = mutablePreferencesOf()
        btWidgetPutPortfolioConfig(prefs, BtWidgetPortfolioConfig("pf-1", "Depot"))
        btWidgetClearPortfolioConfig(prefs)
        assertNull(btWidgetPortfolioConfig(prefs))
    }

    // ── The builder's pin hand-off codecs ────────────────────────────────────

    @Test
    fun `pin payloads round-trip all three config shapes`() {
        val asset = BtWidgetAssetConfig("as-1", "BAYN.DE", "Bayer AG", "EUR")
        assertEquals(asset, btWidgetPinAsset(btWidgetPinPayload(asset)))

        val portfolio = BtWidgetPortfolioConfig("pf-1", "Depot")
        assertEquals(portfolio, btWidgetPinPortfolio(btWidgetPinPayload(portfolio)))

        val budget = BtWidgetBudgetConfig("b1", "Food", BtWidgetBudgetStyle.AMOUNT)
        assertEquals(budget, btWidgetPinBudget(btWidgetPinPayload(budget)))
    }

    @Test
    fun `a corrupt pin payload decodes to nothing, not to a half-config`() {
        assertNull(btWidgetPinAsset(emptyMap()))
        assertNull(btWidgetPinPortfolio(mapOf("name" to "Depot")))
        assertNull(btWidgetPinBudget(mapOf("style" to "RING")))
    }

    // ── Round-2 knobs ────────────────────────────────────────────────────────

    @Test
    fun `the asset config snapshots the exchange and the sparkline knob`() {
        val config = BtWidgetAssetConfig(
            "as-1", "BAYN.DE", "Bayer AG", "EUR",
            exchange = "XETRA", sparkline = false,
        )
        val prefs = mutablePreferencesOf()
        btWidgetPutAssetConfig(prefs, config)
        assertEquals("XETRA", btWidgetAssetConfig(prefs)!!.exchange)
        assertEquals(false, btWidgetAssetConfig(prefs)!!.sparkline)
        assertEquals(config, btWidgetPinAsset(btWidgetPinPayload(config)))
    }

    @Test
    fun `the budget emphasis round-trips and defaults to remaining`() {
        val config = BtWidgetBudgetConfig(
            "b1", "Food", BtWidgetBudgetStyle.AMOUNT, BtWidgetBudgetEmphasis.SPENT,
        )
        val prefs = mutablePreferencesOf()
        btWidgetPutBudgetConfig(prefs, config)
        assertEquals(config, btWidgetBudgetConfig(prefs))
        assertEquals(BtWidgetBudgetEmphasis.REMAINING, btWidgetBudgetEmphasis("JUNK"))
    }

    @Test
    fun `the pulse config is never null and empty state means the whole account`() {
        val empty = btWidgetPulseConfig(mutablePreferencesOf())
        assertNull(empty.portfolioId)
        assertEquals(BtWidgetDeltaStyle.BOTH, empty.style)
        assertEquals(true, empty.sparkline)

        val pinned = BtWidgetPulseConfig(
            "pf-1", "Depot", BtWidgetDeltaStyle.PERCENT, sparkline = false,
        )
        val prefs = mutablePreferencesOf()
        btWidgetPutPulseConfig(prefs, pinned)
        assertEquals(pinned, btWidgetPulseConfig(prefs))
        assertEquals(pinned, btWidgetPinPulse(btWidgetPinPayload(pinned)))
        // Re-saving the account scope clears the pinned portfolio.
        btWidgetPutPulseConfig(prefs, BtWidgetPulseConfig())
        assertNull(btWidgetPulseConfig(prefs).portfolioId)
    }

    @Test
    fun `the allocation config round-trips and tolerates junk`() {
        val config = BtWidgetAllocationConfig(
            BtWidgetAllocationGroup.CURRENCY,
            includeCash = false,
            center = BtWidgetAllocationCenter.TOP,
        )
        val prefs = mutablePreferencesOf()
        btWidgetPutAllocationConfig(prefs, config)
        assertEquals(config, btWidgetAllocationConfig(prefs))
        assertEquals(config, btWidgetPinAllocation(btWidgetPinPayload(config)))
        assertEquals(BtWidgetAllocationGroup.CLASS, btWidgetAllocationGroup("JUNK"))
        assertEquals(BtWidgetAllocationCenter.TOTAL, btWidgetAllocationCenter(null))
    }

    @Test
    fun `the rows config round-trips and each preset keeps its own defaults`() {
        val config = BtWidgetRowsConfig(
            BtWidgetRowSource.HOLDINGS,
            BtWidgetRowSort.VALUE,
            BtWidgetRowDirection.SPLIT,
        )
        val prefs = mutablePreferencesOf()
        btWidgetPutRowsConfig(prefs, config)
        assertEquals(config, btWidgetRowsConfig(prefs, BT_WIDGET_ROWS_WATCHLIST_DEFAULTS))
        assertEquals(config, btWidgetPinRows(btWidgetPinPayload(config), BT_WIDGET_ROWS_MOVERS_DEFAULTS))
        // Empty state → each preset's own defaults, not one shared guess.
        assertEquals(
            BT_WIDGET_ROWS_WATCHLIST_DEFAULTS,
            btWidgetRowsConfig(mutablePreferencesOf(), BT_WIDGET_ROWS_WATCHLIST_DEFAULTS),
        )
        assertEquals(
            BT_WIDGET_ROWS_MOVERS_DEFAULTS,
            btWidgetRowsConfig(mutablePreferencesOf(), BT_WIDGET_ROWS_MOVERS_DEFAULTS),
        )
    }

    @Test
    fun `the flow mode and perf range parse their wire forms and tolerate junk`() {
        assertEquals(BtWidgetFlowMode.BARS, btWidgetFlowMode("BARS"))
        assertEquals(BtWidgetFlowMode.DONUT, btWidgetFlowMode(null))
        assertEquals(BtWidgetFlowMode.DONUT, btWidgetFlowMode("JUNK"))
        assertEquals(
            at.bettertrack.app.data.repo.HistoryRange.M6,
            btWidgetPerfRange("6M"),
        )
        assertEquals(
            at.bettertrack.app.data.repo.HistoryRange.M1,
            btWidgetPerfRange("garbage"),
        )
        // Only the four server-backed ranges are chips — no 1W, no 1D.
        assertEquals(4, BT_WIDGET_PERF_RANGES.size)
        assertEquals(
            listOf("1M", "6M", "1Y", "MAX"),
            BT_WIDGET_PERF_RANGES.map { it.wire },
        )
    }

    @Test
    fun `a pin stash is claimable only within its TTL window`() {
        val at = 1_000_000L
        assertEquals(true, btWidgetPinFresh(at, at + 1_000L))
        assertEquals(true, btWidgetPinFresh(at, at + BT_WIDGET_PIN_TTL_MS))
        // Expired: a cancelled pin dialog must not configure next week's widget.
        assertEquals(false, btWidgetPinFresh(at, at + BT_WIDGET_PIN_TTL_MS + 1L))
        // Nonsense clocks are stale, not fresh: never-written and future stamps.
        assertEquals(false, btWidgetPinFresh(0L, at))
        assertEquals(false, btWidgetPinFresh(at + 5_000L, at))
    }
}
