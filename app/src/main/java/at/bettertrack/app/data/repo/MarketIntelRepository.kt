package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.DividendCalendarResponse
import at.bettertrack.app.data.api.dto.DividendProjectionResponse
import at.bettertrack.app.data.api.dto.DividendsResponse
import at.bettertrack.app.data.api.dto.EarningsCalendarResponse
import at.bettertrack.app.data.api.dto.EarningsResponse
import at.bettertrack.app.data.api.dto.FundamentalsResponse
import at.bettertrack.app.data.api.dto.MarketIntelStatusResponse
import at.bettertrack.app.data.api.dto.NewsDigestResponse
import at.bettertrack.app.data.api.dto.NewsResponse
import at.bettertrack.app.data.api.dto.SplitsResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * Market intel (V5, `market:read`) — dividends, earnings, news and splits for
 * one asset, plus the four portfolio-wide roll-ups.
 *
 * ### The one rule this repository exists to enforce
 * **Unavailable is not an error, and an error is not "unavailable".**
 *
 * The platform answers 200 with an `available:false` body whenever the feature
 * flag is off, the asset's provider can't serve that capability (every custom
 * asset), or the upstream threw. So a *transport* failure — no network, 401, a
 * paranoid-mode 403 — is a genuinely different outcome, and collapsing the two
 * would make an offline phone claim your holdings pay no dividends. Hence
 * [IntelSection]: `Off` hides the block entirely, `Data` renders it (possibly
 * empty), and a `BtResult.Err` still travels as an error the caller can retry.
 *
 * Nothing here is cached. Intel is a decoration on an online-only surface, and
 * the server already caches it (10 min for news, 6-12 h for the rest); a second
 * cache in Room would only add a way to show a stale headline.
 */

/**
 * A per-asset intel block's outcome.
 *
 * [Off] carries no payload on purpose — there is nothing truthful to render.
 */
sealed interface IntelSection<out T> {
    /** The server can't serve this block (flag off / provider can't / upstream failed). */
    data object Off : IntelSection<Nothing>

    /** The server answered; the payload may still legitimately be empty. */
    data class Data<T>(val value: T) : IntelSection<T>

    val payload: T? get() = (this as? Data<T>)?.value
}

/** Everything the asset-page intel section renders, fetched in one round. */
data class AssetIntel(
    val dividends: IntelSection<DividendsResponse>,
    val earnings: IntelSection<EarningsResponse>,
    val news: IntelSection<NewsResponse>,
    val splits: IntelSection<SplitsResponse>,
) {
    /**
     * True when every block is off — the caller renders NOTHING at all rather
     * than a heading over four absences.
     */
    val allOff: Boolean
        get() = dividends is IntelSection.Off &&
            earnings is IntelSection.Off &&
            news is IntelSection.Off &&
            splits is IntelSection.Off
}

class MarketIntelRepository(
    private val api: BtApi,
    private val json: Json,
) {

    /**
     * Fetch the whole per-asset intel section.
     *
     * The capability probe runs first and its four booleans decide which of the
     * four reads are even attempted — that is the difference between one request
     * and five for a custom asset that supports none of them. The reads that DO
     * run go in parallel: they are independent, and serialising four provider
     * round-trips would be plainly visible on the asset page.
     *
     * A failed probe is fatal for the section (we cannot honestly claim any of
     * it), but a failed individual read is not: it degrades that one block to
     * [IntelSection.Off], exactly as the server's own error handling does.
     */
    suspend fun assetIntel(assetId: String): BtResult<AssetIntel> {
        val status: MarketIntelStatusResponse = when (val r = apiCall(json) { api.assetIntel(assetId) }) {
            is BtResult.Ok -> r.value
            is BtResult.Err -> return r
        }
        if (!status.enabled) {
            return BtResult.Ok(
                AssetIntel(IntelSection.Off, IntelSection.Off, IntelSection.Off, IntelSection.Off),
            )
        }
        val caps = status.capabilities
        return coroutineScope {
            val dividends = async {
                if (caps.dividends) section(apiCall(json) { api.assetDividends(assetId) }) { it.available } else IntelSection.Off
            }
            val earnings = async {
                if (caps.earnings) section(apiCall(json) { api.assetEarnings(assetId) }) { it.available } else IntelSection.Off
            }
            val news = async {
                if (caps.news) section(apiCall(json) { api.assetNews(assetId) }) { it.available } else IntelSection.Off
            }
            val splits = async {
                if (caps.splits) section(apiCall(json) { api.assetSplits(assetId) }) { it.available } else IntelSection.Off
            }
            BtResult.Ok(
                AssetIntel(
                    dividends = dividends.await(),
                    earnings = earnings.await(),
                    news = news.await(),
                    splits = splits.await(),
                ),
            )
        }
    }

    /**
     * Statement figures + snapshot ratios for one asset (platform arc f).
     *
     * Returns the RAW result rather than an [IntelSection] on purpose, and is
     * NOT folded into [assetIntel]'s parallel round. Two reasons, both structural:
     *
     *  1. Fundamentals is not in the capability probe — the contract leaves it
     *     out deliberately — so there is no boolean to gate it on. The probe
     *     cannot tell the caller anything about this block.
     *  2. It is the only intel read with a QUERY: flipping the annual/quarterly
     *     toggle re-fetches, and a block that re-fetches on user input cannot
     *     ride a one-shot bundle computed when the page opened. (The server
     *     caches both granularities behind one provider call, so the second
     *     round-trip is a cache read, not a second upstream hit.)
     *
     * The `available:false` → hide-the-block mapping therefore happens in the
     * ViewModel, where the retryable transport error stays distinguishable from
     * the honest "this provider has no fundamentals" — the same three-outcome
     * split [IntelSection] enforces for the other four families.
     */
    suspend fun fundamentals(
        assetId: String,
        period: String,
        limit: Int,
    ): BtResult<FundamentalsResponse> =
        apiCall(json) { api.assetFundamentals(assetId, period, limit) }

    // ── Portfolio-wide roll-ups ─────────────────────────────────────────────
    //
    // These keep their BtResult so the dedicated screen can tell "couldn't
    // reach the server" (retry) from "the server has nothing for you" (empty
    // state) from "this isn't available" (absent block). Three outcomes, three
    // renderings — which is the whole reason they aren't flattened here.

    suspend fun earningsCalendar(): BtResult<EarningsCalendarResponse> =
        apiCall(json) { api.earningsCalendar() }

    suspend fun dividendCalendar(): BtResult<DividendCalendarResponse> =
        apiCall(json) { api.dividendCalendar() }

    suspend fun dividendProjection(): BtResult<DividendProjectionResponse> =
        apiCall(json) { api.dividendProjection() }

    suspend fun newsDigest(): BtResult<NewsDigestResponse> =
        apiCall(json) { api.newsDigest() }

    // A note on PARANOID MODE, because the obvious "improvement" here is a
    // regression: three of these four roll-ups read the caller's HOLDINGS, so a
    // paranoid account gets `403 PARANOID_MODE` from them, and it is tempting to
    // translate that into an `available:false` body so the blocks just vanish.
    //
    // They must NOT vanish. `available:false` renders as an absent block with no
    // explanation, and a paranoid user would simply find their dividend calendar
    // missing. The error path instead carries the SERVER'S OWN sentence about
    // paranoid mode to the screen, so the user is told why. A futile Retry button
    // is the smaller flaw; silence is the bigger one. Pinned by
    // `MarketIntelRepositoryTest`.

    /**
     * Map one read into a section: an error, or a body whose own `available`
     * flag says no, both become [IntelSection.Off].
     *
     * The capability probe saying `true` is NOT a promise — the provider can
     * still throw on the actual call and come back `available:false` — so the
     * per-response flag is re-checked here rather than trusted from the probe.
     */
    private inline fun <T : Any> section(
        result: BtResult<T>,
        available: (T) -> Boolean,
    ): IntelSection<T> = when (result) {
        is BtResult.Ok -> if (available(result.value)) IntelSection.Data(result.value) else IntelSection.Off
        is BtResult.Err -> IntelSection.Off
    }
}
