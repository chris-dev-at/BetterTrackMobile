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

// ── GET /assets/{id}/intel/fundamentals ─────────────────────────────────────
//
// The FIFTH intel capability (platform arc f, mobile board #76) — and the one
// that is deliberately NOT in [MarketIntelCapabilitiesDto]. The contract says so
// in as many words: a provider that cannot serve fundamentals just makes the
// endpoint answer `available: false`, exactly like a gate-off or an upstream
// error. So this read is NOT gated on the capability probe — it is issued
// unconditionally and its own `available` flag is the only gate. Probing first
// would ask a question the probe cannot answer.
//
// Every statement figure is a plain JSON number in the company's own reporting
// [FundamentalsResponse.currency] — never converted to the portfolio base, the
// same convention as earnings EPS. Real revenues (hundreds of billions) sit far
// inside `Number.MAX_SAFE_INTEGER`, so a JSON number loses no precision and
// `Double` holds them exactly.

/**
 * One reporting period's statement line items.
 *
 * [fiscalPeriod] is `"FY"` for an annual row and `"Q1".."Q4"` for a quarterly
 * one; it and [fiscalYear] are derived server-side from [endDate], so both are
 * calendar-based approximations for issuers whose fiscal year is offset.
 *
 * **Every figure is nullable and a gap is `null`, never a fabricated 0** — which
 * is the whole reason this DTO cannot use `0.0` defaults the way the calendar
 * DTOs above do. A company that reported no operating cash flow and a company
 * whose provider simply didn't carry the line are different facts, and only
 * `null` can tell them apart. The UI collapses absent rows rather than printing
 * a zero it was never given.
 *
 * [eps] and [reportDate] are `null` on today's provider (Yahoo's statement
 * modules supply neither); they are carried for shape-completeness and forward
 * compatibility. Trailing/forward EPS live in [FundamentalsRatiosDto], where
 * they ARE authoritative.
 */
@Serializable
data class FundamentalsPeriodDto(
    /** `"FY"` for an annual row, `"Q1".."Q4"` for a quarterly one. */
    val fiscalPeriod: String = "",
    val fiscalYear: Int? = null,
    /** ISO datetime — the period-end date the provider reports. */
    val endDate: String? = null,
    /** ISO datetime the results were announced. NULL on today's provider. */
    val reportDate: String? = null,
    val revenue: Double? = null,
    val netIncome: Double? = null,
    /** NULL on today's provider — see [FundamentalsRatiosDto.trailingEps]. */
    val eps: Double? = null,
    val grossProfit: Double? = null,
    val operatingIncome: Double? = null,
    val totalAssets: Double? = null,
    val totalLiabilities: Double? = null,
    val totalEquity: Double? = null,
    val operatingCashFlow: Double? = null,
    val freeCashFlow: Double? = null,
)

/**
 * Snapshot valuation / profitability ratios **as of the read**, not per-period.
 *
 * Three different unit conventions live in this one object, and mixing them up
 * is the obvious bug here:
 *
 *  - [profitMargin] and [returnOnEquity] are **FRACTIONS** (`0.25` ≈ 25 %) — the
 *    contract names them as such. They need ×100 before a percent formatter,
 *    exactly like [DividendsResponse.forwardYield].
 *  - [debtToEquity] is **NOT** a fraction. The contract pointedly does not list
 *    it with the two above, and today's provider reports Yahoo's convention:
 *    debt as a PERCENTAGE of equity (`145` = debt is 145 % of equity). The app
 *    therefore renders it through the percent formatter with NO rescaling — the
 *    unit is asserted in the label, not by arithmetic on a number whose scale
 *    the contract declines to pin. Rendering the bare `145` next to a
 *    "Debt/equity" label would read as 145×, which is the catastrophic misread.
 *  - Everything else ([trailingPe], [priceToBook], [marketCap], the EPS pair) is
 *    a plain number in its natural unit; [marketCap] alone is money, in
 *    [FundamentalsResponse.currency].
 *
 * Every field is nullable — the provider fills what it can.
 */
@Serializable
data class FundamentalsRatiosDto(
    /** Money, in [FundamentalsResponse.currency]. */
    val marketCap: Double? = null,
    val trailingPe: Double? = null,
    val forwardPe: Double? = null,
    val priceToBook: Double? = null,
    /** A FRACTION: `0.25` ≈ 25 %. */
    val profitMargin: Double? = null,
    /** A FRACTION: `0.25` ≈ 25 %. */
    val returnOnEquity: Double? = null,
    /** Already in PERCENT units on today's provider (`145` = 145 % of equity). */
    val debtToEquity: Double? = null,
    val trailingEps: Double? = null,
    val forwardEps: Double? = null,
)

/**
 * `GET /assets/{id}/intel/fundamentals?period=annual|quarterly&limit=1..12`.
 *
 * [available] `false` — with empty [periods] and an all-null [ratios] — whenever
 * the gate is off, the asset's provider lacks the capability (every custom /
 * Drive-only asset), or the upstream errored. Never a 5xx, exactly like the
 * sibling intel families.
 *
 * [period] echoes the granularity actually served, so the client need not
 * re-derive it. `limit` is CLAMPED server-side to `1..12` rather than rejected,
 * so over-asking costs a round-trip, not an error.
 */
@Serializable
data class FundamentalsResponse(
    val available: Boolean = false,
    /** The company's own reporting currency — every figure is denominated in it. */
    val currency: String? = null,
    /** `"annual"` | `"quarterly"` — the granularity actually served. */
    val period: String = "annual",
    /** Most-recent-FIRST. */
    val periods: List<FundamentalsPeriodDto> = emptyList(),
    val ratios: FundamentalsRatiosDto = FundamentalsRatiosDto(),
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
