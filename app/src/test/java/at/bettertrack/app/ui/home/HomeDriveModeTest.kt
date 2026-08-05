package at.bettertrack.app.ui.home

import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.db.PortfolioTotals
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.priceCoverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home in DRIVE-only mode, end to end through the pure composition functions.
 *
 * ## Why this is a unit test and not a screenshot
 *
 * Every claim below is about what is ABSENT, and absence is exactly what a
 * screenshot proves worst: a reviewer looking at a Drive Home cannot tell an
 * intentionally missing "Needs you" block from one that failed to load, and
 * neither can a diff of two PNGs. It is also the mode nobody on the team runs by
 * default, so the states that regress silently are precisely these. Deciding the
 * whole composition in `HomeLogic.kt` is what lets this be asserted at all — the
 * composable renders what these functions return, so pinning them pins the screen.
 *
 * ## What a Drive-only install actually is
 *
 * No BetterTrack account: no alert engine, no friends, no chat, no inbox. Those
 * are not features it is missing, they are features that cannot exist for it
 * (S3/S4 §4.5, "absent, not greyed"). What it *does* have is a vault of
 * transactions and whatever prices the user typed — so the one thing Home can
 * usefully ask of this user is the prices they have not typed yet.
 */
class HomeDriveModeTest {

    private val drive = StorageMode.DRIVE

    // ── The actionable block ────────────────────────────────────────────────

    @Test
    fun `the whole actionable block is absent in drive mode`() {
        // Even with every count non-zero — which cannot happen on a real Drive
        // install, but is exactly what a stale flow or a mis-wired repository
        // would produce, and this is the gate that catches it.
        val rows = homeActionRows(
            mode = drive,
            triggeredAlerts = 7,
            friendRequests = 4,
            unreadMessages = 3,
            unreadNotifications = 9,
            newestNotificationTitle = "should never be rendered",
        )
        assertTrue("a Drive install has no server surface to be waiting on it", rows.isEmpty())
    }

    @Test
    fun `the same counts in server mode produce the full block`() {
        // The mirror of the test above: proof the emptiness is the MODE gate and
        // not a broken function that returns nothing for everyone.
        val rows = homeActionRows(
            mode = StorageMode.SERVER,
            triggeredAlerts = 7,
            friendRequests = 4,
            unreadMessages = 3,
            unreadNotifications = 9,
        )
        assertEquals(4, rows.size)
    }

    // ── Movers ──────────────────────────────────────────────────────────────

    @Test
    fun `movers are absent in drive mode without any mode check`() {
        // Manual prices (W6) give a holding a market value but never a previous
        // close, so `dayChangePct` is null on every row and the movers filter
        // empties the section by itself. Verified rather than hard-coded, exactly
        // as the spec asks — if the vault projector ever starts deriving a day
        // change, this test failing is the correct outcome.
        val manuallyPriced = listOf(
            holding("AAPL", marketValue = 2_450.0, dayChangePct = null),
            holding("MSFT", marketValue = 1_800.0, dayChangePct = null),
        )
        assertTrue(homeMovers(manuallyPriced).isEmpty())
    }

    // ── The row a Drive Home DOES have ──────────────────────────────────────

    @Test
    fun `unpriced holdings surface as the drive user's actionable row`() {
        val holdings = listOf(
            holding("ZZZZ", marketValue = null),
            holding("AAAA", marketValue = null),
            holding("MMMM", marketValue = null),
            holding("PRICED", marketValue = 500.0),
        )
        val state = homeUnpriced(drive, holdings)

        assertNotNull("this is the single most valuable row a Drive Home has", state)
        assertEquals(3, state!!.total)
        assertEquals(
            "the preview is sorted so the same names keep the same order across emissions",
            listOf("AAAA", "MMMM", "ZZZZ"),
            state.preview.map { it.assetSymbol },
        )
        assertFalse("three of three is not 'more'", state.hasMore)
    }

    @Test
    fun `past the preview limit the row counts instead of listing`() {
        val holdings = (1..7).map { holding("S%02d".format(it), marketValue = null) }
        val state = homeUnpriced(drive, holdings)!!
        assertEquals(7, state.total)
        assertEquals(3, state.preview.size)
        assertTrue(state.hasMore)
    }

    @Test
    fun `with every holding priced there is no row`() {
        val state = homeUnpriced(drive, listOf(holding("AAPL", marketValue = 1.0)))
        assertNull("a task with nothing in it is not a task", state)
    }

    @Test
    fun `server mode never offers the row, however many prices are missing`() {
        // In SERVER/BOTH a missing quote is a transient server gap the user
        // cannot fix by typing, so offering them the task would be a lie about
        // whose problem it is. Same predicate the manual-price sheet uses.
        val unpriced = (1..5).map { holding("S$it", marketValue = null) }
        assertNull(homeUnpriced(StorageMode.SERVER, unpriced))
        assertNull(homeUnpriced(StorageMode.BOTH, unpriced))
        assertNull(homeUnpriced(StorageMode.UNSET, unpriced))
    }

    // ── The hero, on a Drive vault ──────────────────────────────────────────

    @Test
    fun `a drive hero with cash and no prices shows the cash and no day change`() {
        // The W6 shape that matters most on Home: the cash is real and must be
        // rendered, the day change is a sum of zeroes and must not be, and the
        // unpriced count has to survive all the way to the renderer.
        val holdings = listOf(holding("A", marketValue = null), holding("B", marketValue = null))
        val ready = homeNetWorth(
            active = listOf(vaultPortfolio(total = 4_000.0, cash = 4_000.0)),
            coverage = priceCoverage(holdings),
        ) as HomeHeroState.Ready

        val worth = ready.netWorth as NetWorthState.Value
        assertEquals(4_000.0, worth.eur, 1e-9)
        assertEquals(2, worth.coverage.unpriced)
        assertFalse("'+0,00 € · today' would read as flat when it means unknown", ready.showDayChange)
    }

    @Test
    fun `a drive vault with nothing priced and no cash renders no figure at all`() {
        val holdings = listOf(holding("A", marketValue = null))
        val ready = homeNetWorth(
            active = listOf(vaultPortfolio(total = 0.0, cash = 0.0)),
            coverage = priceCoverage(holdings),
        ) as HomeHeroState.Ready

        assertTrue(
            "every candidate number here is a 0 that means 'not known'",
            ready.netWorth is NetWorthState.Unpriceable,
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun holding(
        symbol: String,
        marketValue: Double?,
        dayChangePct: Double? = null,
    ) = HoldingEntity(
        portfolioId = "vault",
        assetId = "asset-$symbol",
        assetSymbol = symbol,
        assetName = "$symbol Holding",
        assetExchange = null,
        assetCurrency = "EUR",
        assetType = "stock",
        assetIsCustom = false,
        quantity = 3.0,
        avgCost = 100.0,
        realizedPnl = 0.0,
        price = marketValue,
        marketValueEur = marketValue,
        costBasisEur = 300.0,
        unrealizedPnlEur = null,
        unrealizedPnlPct = null,
        dayChangeEur = null,
        dayChangePct = dayChangePct,
    )

    /** A vault-projected portfolio: totals are present, prices may not be. */
    private fun vaultPortfolio(total: Double, cash: Double) = PortfolioEntity(
        id = "vault",
        name = "Vault",
        visibility = "private",
        sortOrder = 0,
        isDefault = true,
        defaultPayFromCash = false,
        archivedAt = null,
        baseCurrency = "EUR",
        totals = PortfolioTotals(
            marketValueEur = total - cash,
            investedEur = 0.0,
            unrealizedPnlEur = 0.0,
            unrealizedPnlPct = null,
            dayChangeEur = 0.0,
            dayChangePct = null,
            cashEur = cash,
            totalValueEur = total,
        ),
        detailSyncedAtMs = 1L,
    )
}
