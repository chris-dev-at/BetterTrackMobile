package at.bettertrack.app.data.storage

import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.data.repo.SearchOutcome

/**
 * ⚠️ **SCAFFOLD ONLY — OWNER DECISION PENDING. NOT WIRED, NOT SHIPPED.** ⚠️
 *
 * The third [MarketDataSource] implementation the plan anticipates (§1.3): one
 * that fetches quotes from a market-data provider **directly**, with no
 * BetterTrack account involved — the only shape that gives a Drive-autonomous
 * install live prices while keeping the "no account" promise whole.
 *
 * It exists as structure and nothing else, so that if and when the owner decides,
 * the change is a small diff in known places rather than an architecture task.
 *
 * ## Why it is off
 *
 * Plan §6 risk 6, verbatim — *"Provider ToS for direct market data — the biggest
 * non-engineering risk. Yahoo-direct is a platform-documented non-goal; Play
 * distribution raises ToS/Data-Safety exposure. **Do not ship a direct-provider
 * adapter by default.** Owner decision needed: licensed provider or owner-run
 * price proxy?"*
 *
 * This is not an engineering trade-off that a builder may resolve. Shipping
 * scraped quotes in a Play-distributed app exposes the owner to a provider's
 * terms and to Google's Data-Safety declarations, and the app cannot make that
 * call on his behalf. Until he answers, [BuildConfig.DIRECT_PROVIDER_PRICES] is
 * `false` in **every** build type and flavour and nothing constructs this class.
 *
 * ## What deciding looks like
 *
 * The owner's answer is one of two, and each is a contained diff:
 *
 *  - **Licensed provider** (e.g. a paid quote API with an app-embeddable key):
 *    implement [fetchQuote]/[fetchSearch]/[fetchHistory] against it, add the key
 *    to the build config the same way `OAUTH_CLIENT_ID` is supplied, flip the
 *    flag, and add this source to [ModeRoutingMarketDataSource]'s `offline`
 *    branch behind a user-facing opt-in mirroring [PriceLookupStore].
 *  - **Owner-run price proxy** (a small service the owner operates, quotes only,
 *    no account): identical shape, different base URL, and the privacy copy
 *    becomes stronger rather than weaker — the proxy sees lookups, never
 *    holdings, and it is his own server.
 *
 * Either way the pieces this file would need — the interface, the routing seam,
 * the opt-in preference, the honest-state UI — are all already built and tested
 * by W6. This is the last mile, deliberately left unwalked.
 *
 * ## Behaviour if constructed anyway
 *
 * Inert and loud. The constructor refuses outright when the flag is off, so a
 * mis-wire fails at graph-construction time in a debug build rather than
 * silently returning empty quotes into the money path. Every method that could
 * be reached returns a designed error rather than a fabricated price — the same
 * rule [NoLivePricesMarketDataSource] follows, for the same reason: an invented
 * number on a portfolio screen is worse than an absent one.
 */
class DirectProviderMarketDataSource : MarketDataSource {

    init {
        check(BuildConfig.DIRECT_PROVIDER_PRICES) { DISABLED_MESSAGE }
    }

    override suspend fun search(query: String): BtResult<SearchOutcome> = notImplemented()

    override suspend fun assetDetail(assetId: String): BtResult<AssetSnapshot> = notImplemented()

    override suspend fun assetDailyCloses(assetId: String): BtResult<List<PricePoint>> = notImplemented()

    override suspend fun assetHistory(assetId: String, range: AssetRange): BtResult<AssetPriceSeries> =
        notImplemented()

    override suspend fun quote(assetId: String): BtResult<AssetSnapshot> = notImplemented()

    /**
     * The single provider call every method above would route through once a
     * provider is chosen. Left unimplemented on purpose: writing a body would
     * mean picking the provider, which is precisely the decision that is pending.
     */
    private fun <T> notImplemented(): BtResult<T> = BtResult.Err(
        BtApiError(httpStatus = 0, code = ERROR_CODE),
    )

    companion object {
        const val ERROR_CODE: String = "DIRECT_PROVIDER_DISABLED"

        const val DISABLED_MESSAGE: String =
            "Direct market-data providers are not enabled in this build (S3/S4 plan §6 risk 6 — " +
                "owner decision pending: licensed provider or owner-run price proxy)."

        /**
         * Whether the flag is on. Read this rather than [BuildConfig] directly so
         * the one place that decides is greppable — and so the test that asserts
         * the flag is off everywhere has something stable to hold.
         */
        val enabled: Boolean get() = BuildConfig.DIRECT_PROVIDER_PRICES
    }
}
