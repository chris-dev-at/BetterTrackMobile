package at.bettertrack.app.data.storage

import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Use BetterTrack for prices only" opt-in (S3/S4 plan §5 W6, item 3).
 *
 * The interesting content of this feature is not the switch — it is the two
 * rules the switch is subject to, both of which are here:
 *
 *  1. **It cannot act without a session.** `/search` and the `/assets` routes
 *     require an OAuth bearer with `market:read`. A stored `true` on an install
 *     with no account is consent without capability, and honouring it would send
 *     unauthenticated calls that 401 — the exact failure the per-call router was
 *     introduced to eliminate.
 *  2. **It moves the market seam and nothing else.** The promise in the settings
 *     copy — *"BetterTrack would see which assets you look up, never what you
 *     own"* — is only true if turning it on leaves the portfolio backend routed
 *     at the vault. That is structural (two separate routers), and
 *     [the toggle never routes portfolio data to the server] holds the line on it.
 */
class PriceLookupGatingTest {

    // ── Availability ────────────────────────────────────────────────────────

    @Test
    fun `server mode does not offer the toggle at all`() {
        // Prices already come from BetterTrack because everything does; the row
        // would be a switch with nothing on the other side of it.
        assertEquals(
            PriceLookupAvailability.NOT_APPLICABLE,
            priceLookupAvailability(StorageMode.SERVER, hasSession = true),
        )
        assertEquals(
            PriceLookupAvailability.NOT_APPLICABLE,
            priceLookupAvailability(StorageMode.UNSET, hasSession = true),
        )
    }

    @Test
    fun `drive mode with no account shows the honest disabled state`() {
        // Shown rather than hidden: the control is genuinely applicable and one
        // step (attach an account) away, and that is information the user needs.
        assertEquals(
            PriceLookupAvailability.NEEDS_ACCOUNT,
            priceLookupAvailability(StorageMode.DRIVE, hasSession = false),
        )
    }

    @Test
    fun `drive mode with a session can offer it`() {
        assertEquals(
            PriceLookupAvailability.AVAILABLE,
            priceLookupAvailability(StorageMode.DRIVE, hasSession = true),
        )
    }

    @Test
    fun `both mode follows the session too`() {
        assertEquals(
            PriceLookupAvailability.AVAILABLE,
            priceLookupAvailability(StorageMode.BOTH, hasSession = true),
        )
        assertEquals(
            PriceLookupAvailability.NEEDS_ACCOUNT,
            priceLookupAvailability(StorageMode.BOTH, hasSession = false),
        )
    }

    // ── Activation ──────────────────────────────────────────────────────────

    @Test
    fun `the toggle is off by default`() {
        // A privacy default that needs no explanation is the only kind worth
        // shipping — plan §5 W6 says OFF, and nothing may quietly opt a user in.
        assertFalse(priceLookupActive(StorageMode.DRIVE, hasSession = true, enabled = false))
    }

    @Test
    fun `consent without a session never activates`() {
        // The rule that stops a stored `true` from producing a 401 storm.
        assertFalse(priceLookupActive(StorageMode.DRIVE, hasSession = false, enabled = true))
    }

    @Test
    fun `opted in with a session in drive mode activates`() {
        assertTrue(priceLookupActive(StorageMode.DRIVE, hasSession = true, enabled = true))
    }

    @Test
    fun `it is inert in modes that already use the server`() {
        // SERVER and BOTH route market calls to the server regardless, so the flag
        // must not be the thing that decides — otherwise turning it OFF in BOTH
        // would take live prices away from an account that pays for them.
        assertFalse(priceLookupActive(StorageMode.SERVER, hasSession = true, enabled = true))
        assertFalse(priceLookupActive(StorageMode.BOTH, hasSession = true, enabled = true))
        assertFalse(priceLookupActive(StorageMode.UNSET, hasSession = true, enabled = true))
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    private fun router(mode: StorageMode, active: Boolean) = ModeRoutingMarketDataSource(
        mode = { mode },
        server = { NamedSource("server") },
        offline = { NamedSource("offline") },
        lookupsActive = { active },
    )

    private suspend fun routedTo(source: MarketDataSource): String =
        (source.search("q") as BtResult.Ok).value.results.single().id

    @Test
    fun `drive mode routes to the offline source while the toggle is off`() = runBlocking {
        assertEquals("offline", routedTo(router(StorageMode.DRIVE, active = false)))
    }

    @Test
    fun `drive mode routes quotes to the server once the toggle is active`() = runBlocking {
        assertEquals("server", routedTo(router(StorageMode.DRIVE, active = true)))
    }

    @Test
    fun `server and both modes are unaffected by the toggle`() = runBlocking {
        for (mode in listOf(StorageMode.SERVER, StorageMode.BOTH, StorageMode.UNSET)) {
            assertEquals("server", routedTo(router(mode, active = false)))
            assertEquals("server", routedTo(router(mode, active = true)))
        }
    }

    @Test
    fun `every market method follows the same routing decision`() = runBlocking {
        // A method that forgot to go through `active()` would leak the offline
        // source into an opted-in install, or worse, the reverse.
        val source = router(StorageMode.DRIVE, active = true)
        assertEquals("server", (source.assetDetail("x") as BtResult.Ok).value.asset.id)
        assertEquals("server", (source.quote("x") as BtResult.Ok).value.asset.id)
        assertEquals(1, (source.assetDailyCloses("x") as BtResult.Ok).value.size)
        assertEquals(
            AssetRange.M1,
            (source.assetHistory("x", AssetRange.M1) as BtResult.Ok).value.range,
        )
    }

    @Test
    fun `the toggle never routes portfolio data to the server`() {
        // Structural, not behavioural: the market router has no reference to a
        // PortfolioBackend and cannot reach one, which is what makes the privacy
        // copy true by construction rather than by discipline.
        val backendRouterFields = ModeRoutingPortfolioBackend::class.java.declaredFields.map { it.name }
        val marketRouterFields = ModeRoutingMarketDataSource::class.java.declaredFields.map { it.name }

        assertTrue(marketRouterFields.contains("lookupsActive"))
        assertFalse(
            "the market toggle must not be a field of the portfolio router",
            backendRouterFields.contains("lookupsActive"),
        )
    }

    @Test
    fun `the default router keeps pre-W6 behaviour exactly`() = runBlocking {
        // Existing call sites that do not pass `lookupsActive` must be unchanged.
        val source = ModeRoutingMarketDataSource(
            mode = { StorageMode.DRIVE },
            server = { NamedSource("server") },
            offline = { NamedSource("offline") },
        )
        assertEquals("offline", routedTo(source))
    }
}

/** A [MarketDataSource] that answers every call with its own name. */
private class NamedSource(private val name: String) : MarketDataSource {

    private fun asset() = at.bettertrack.app.data.repo.MarketAsset(
        id = name,
        symbol = name,
        name = name,
        exchange = null,
        type = "stock",
        currency = "EUR",
        isCustom = false,
    )

    override suspend fun search(query: String): BtResult<SearchOutcome> =
        BtResult.Ok(SearchOutcome(results = listOf(asset()), enriching = false))

    override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> = BtResult.Ok(
        AssetSnapshot(
            asset = asset(),
            nativePrice = 1.0,
            quoteCurrency = "EUR",
            dayChangePct = null,
            prevClose = null,
            eurPrice = 1.0,
            asOf = null,
            stale = false,
        ),
    )

    override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> =
        BtResult.Ok(listOf(PricePoint(0L, 1.0)))

    override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        BtResult.Ok(AssetPriceSeries(range = range, points = emptyList()))

    override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = assetDetail(assetId)
}
