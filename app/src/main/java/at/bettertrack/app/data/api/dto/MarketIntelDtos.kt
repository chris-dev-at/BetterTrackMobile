package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Market intel wire DTOs (V5 drop, `market:read`) — platform
 * `packages/contracts/src/marketIntel.ts`, routes
 * `apps/api/src/http/routes/marketIntelRoutes.ts` mounted on `/api/v1/assets`.
 *
 * Three things about this surface drive every decision in these types:
 *
 *  1. **Nothing here ever fails loudly.** Every handler answers **200** with a
 *     contract-valid body — a disabled feature flag, a provider that lacks the
 *     capability (every custom asset), and an upstream timeout all return the
 *     same "unavailable" shape. The router says so in as many words: *"an asset
 *     page never 5xxs on intel"*. So the app's job is to read the flag, not to
 *     handle errors.
 *  2. **Two different flag names.** `GET /assets/{id}/intel` reports
 *     [MarketIntelStatusResponse.enabled] + per-capability booleans; every other
 *     endpoint reports its own `available`. They are not interchangeable, and
 *     both must be honoured — the capability probe can say `news: true` while
 *     the news call still comes back `available: false` because the provider
 *     threw on that particular request.
 *  3. **`available: true` with an empty list is normal**, and is NOT the same as
 *     unavailable: "this asset pays no dividends" is an answer; "we can't tell
 *     you about dividends" is the absence of one. The UI shows an empty state
 *     for the first and renders nothing at all for the second.
 *
 * Every timestamp on this surface is a FULL ISO-8601 UTC datetime
 * (`2026-08-04T00:00:00.000Z`), never a bare `YYYY-MM-DD` — the contract uses
 * `z.string().datetime()` throughout and the provider mapper emits
 * `Date.toISOString()`. Defaults are supplied on every field so a pre-v5 or
 * partially-populated body decodes rather than throwing.
 */

// ── GET /assets/{id}/intel — the capability probe ───────────────────────────

@Serializable
data class MarketIntelCapabilitiesDto(
    val dividends: Boolean = false,
    val earnings: Boolean = false,
    val news: Boolean = false,
    val splits: Boolean = false,
)

/**
 * `GET /assets/{id}/intel`.
 *
 * [enabled] is the server-wide `MARKET_INTEL_ENABLED` gate; [capabilities] is
 * derived per asset from whether its price provider implements each method —
 * so a custom asset (manual provider) reports all four false while the flag is
 * still on.
 */
@Serializable
data class MarketIntelStatusResponse(
    val enabled: Boolean = false,
    val capabilities: MarketIntelCapabilitiesDto = MarketIntelCapabilitiesDto(),
)

// ── GET /assets/{id}/intel/dividends ────────────────────────────────────────

@Serializable
data class DividendEventDto(
    /** ISO datetime; the day the share trades without the dividend. */
    val exDate: String? = null,
    /** ISO datetime; the day the cash actually lands. */
    val payDate: String? = null,
    /** Per-share amount in [currency], already converted out of minor units (GBp→GBP). */
    val amount: Double? = null,
    val currency: String? = null,
)

@Serializable
data class DividendsResponse(
    val available: Boolean = false,
    val currency: String? = null,
    /** Past payouts, ascending by ex-date. */
    val history: List<DividendEventDto> = emptyList(),
    /** Announced future payouts. */
    val upcoming: List<DividendEventDto> = emptyList(),
    /**
     * A **FRACTION**, not a percentage: `0.015` is 1.5 %. Rendering it straight
     * into a percent formatter without the ×100 is the obvious bug here.
     */
    val forwardYield: Double? = null,
    /** Trailing-12-month dividend per share. */
    val trailingAmount: Double? = null,
)

// ── GET /assets/{id}/intel/earnings ─────────────────────────────────────────

@Serializable
data class EarningsEventDto(
    /** ISO datetime of the report. */
    val date: String? = null,
    /** Signed — a loss estimate is negative. */
    val epsEstimate: Double? = null,
    /** Signed; null until the company actually reports. */
    val epsActual: Double? = null,
    /** True while the date itself is only the provider's estimate. */
    val estimated: Boolean = false,
)

@Serializable
data class EarningsResponse(
    val available: Boolean = false,
    val next: EarningsEventDto? = null,
    /** Past reports, ascending by date. */
    val recent: List<EarningsEventDto> = emptyList(),
)

// ── GET /assets/{id}/intel/news ─────────────────────────────────────────────

@Serializable
data class NewsHeadlineDto(
    /** Provider uuid, falling back to the url — stable enough for a list key. */
    val id: String = "",
    val title: String = "",
    val publisher: String? = null,
    /** Guaranteed http(s) by the server mapper. */
    val url: String = "",
    val publishedAt: String? = null,
)

@Serializable
data class NewsResponse(
    val available: Boolean = false,
    val headlines: List<NewsHeadlineDto> = emptyList(),
)

// ── GET /assets/{id}/intel/splits ───────────────────────────────────────────

@Serializable
data class SplitEventDto(
    val date: String? = null,
    val numerator: Double = 0.0,
    val denominator: Double = 0.0,
    /** Pre-rendered by the server, e.g. `"4:1"` — display this, don't re-derive. */
    val ratio: String = "",
)

@Serializable
data class SplitsResponse(
    val available: Boolean = false,
    val history: List<SplitEventDto> = emptyList(),
    /** Always empty with today's provider; kept because the contract has it. */
    val upcoming: List<SplitEventDto> = emptyList(),
)

// ── GET /assets/intel/earnings-calendar ─────────────────────────────────────

@Serializable
data class EarningsCalendarEntryDto(
    val assetId: String = "",
    val symbol: String = "",
    val name: String = "",
    /** ISO datetime — non-nullable here; undated entries are dropped server-side. */
    val date: String = "",
    val epsEstimate: Double? = null,
    val estimated: Boolean = false,
    /** [held] and [watched] are independent — an asset can be both. */
    val held: Boolean = false,
    val watched: Boolean = false,
)

@Serializable
data class EarningsCalendarResponse(
    val available: Boolean = false,
    /** Ascending by date. */
    val entries: List<EarningsCalendarEntryDto> = emptyList(),
)

// ── GET /assets/portfolio/dividend-calendar ─────────────────────────────────

@Serializable
data class DividendCalendarEntryDto(
    val assetId: String = "",
    val symbol: String = "",
    val name: String = "",
    /** `holding` | `watchlist` — held wins when an asset is both. */
    val source: String = "",
    val exDate: String? = null,
    val payDate: String? = null,
    val amount: Double? = null,
    /**
     * Null means the provider did not say which currency the amount is in. The
     * web deliberately hides the amount in that case rather than relabelling
     * `$0.24` as `€0.24`, and the app does the same.
     */
    val currency: String? = null,
)

@Serializable
data class DividendCalendarResponse(
    val available: Boolean = false,
    /** Ascending by the earlier of ex/pay date; only today onwards. */
    val entries: List<DividendCalendarEntryDto> = emptyList(),
)

// ── GET /assets/portfolio/dividend-projection ───────────────────────────────

@Serializable
data class ProjectedDividendHoldingDto(
    val assetId: String = "",
    val symbol: String = "",
    val name: String = "",
    val quantity: Double = 0.0,
    val annualPerShare: Double = 0.0,
    /** The DIVIDEND's currency — not EUR, unlike [annualIncomeEur]. */
    val currency: String = "",
    val annualIncomeEur: Double = 0.0,
)

/**
 * `GET /assets/portfolio/dividend-projection`.
 *
 * All-or-nothing by design: if ANY holding's FX conversion fails the whole
 * response comes back unavailable, so a smaller total is never presented as
 * complete income. The app therefore never has to reason about partial totals.
 */
@Serializable
data class DividendProjectionResponse(
    val available: Boolean = false,
    /** Always `"EUR"`. */
    val currency: String = "EUR",
    val monthlyTotalEur: Double = 0.0,
    val yearlyTotalEur: Double = 0.0,
    /** Descending by annual income. */
    val holdings: List<ProjectedDividendHoldingDto> = emptyList(),
)

// ── GET /assets/portfolio/news-digest ───────────────────────────────────────

@Serializable
data class NewsDigestGroupDto(
    val assetId: String = "",
    val symbol: String = "",
    val name: String = "",
    val held: Boolean = false,
    val watched: Boolean = false,
    /** Newest first; groups with no headlines are omitted server-side. */
    val headlines: List<NewsHeadlineDto> = emptyList(),
)

@Serializable
data class NewsDigestResponse(
    val available: Boolean = false,
    /** Ordered by newest headline first. */
    val groups: List<NewsDigestGroupDto> = emptyList(),
)
