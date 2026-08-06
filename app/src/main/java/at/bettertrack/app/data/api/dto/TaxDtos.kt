package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the tax surface (V3-P4 / V5-P4, `packages/contracts/src/portfolio.ts`
 * §"Taxes" + §"tax-years"): the user-level default (`/settings/taxes`), the
 * per-portfolio override (`/portfolios/:id/settings/tax`) and the per-year
 * reports (`/portfolios/:id/reports/tax-years[/…]`).
 *
 * ## The one convention that carries real meaning here: OMITTED ≠ NULL
 *
 * `taxSettingsResponseSchema` is `.strict()` and deliberately **omits** its
 * mode-dependent members rather than nulling them — `custom` appears exactly in
 * `custom` mode, `manualDefault*` only when configured. `country` is the lone
 * exception: it is `.nullable()`, i.e. always present and null outside
 * `country_specific`.
 *
 * That distinction is not cosmetic. The PATCH body is validated by a
 * `superRefine` that rejects a `country` sent in a non-country mode and a
 * `custom` sent in a non-custom mode — so a client that helpfully serialized
 * `"custom": null` would be rejected by a schema that only tolerates the key's
 * ABSENCE. Every request DTO here is therefore nullable-with-null-default and
 * relies on the app's `explicitNulls = false` Json to drop unset keys entirely.
 * See [at.bettertrack.app.domain.TaxSettingsDraft.toRequest], which is where
 * that mode/field consistency is enforced client-side before we ever hit the
 * wire.
 *
 * Response DTOs keep the house rule (every field defaulted, so a missing key
 * degrades instead of throwing) with `ignoreUnknownKeys = true` on top.
 */

// ── GET /settings/taxes · PATCH /settings/taxes ──────────────────────────────

/**
 * The custom rule-built engine's parameter set (V5-P4c). Present exactly in
 * `custom` mode. Defaults mirror the contract's AT-equivalent expression
 * (`{27.5, offset, refund, reset, no carry, moving-average}`) so a malformed
 * payload degrades to something coherent rather than to zeros.
 */
@Serializable
data class CustomTaxParamsDto(
    val ratePct: Double = 0.0,
    val lossOffset: Boolean = true,
    val refund: Boolean = true,
    val yearReset: Boolean = true,
    val carryForward: Boolean = false,
    /** `moving-average` | `fifo`. */
    val costBasis: String = "moving-average",
)

/**
 * `GET /settings/taxes` + `PATCH /settings/taxes` response, and the shape reused
 * for all three layers of the per-portfolio cascade.
 *
 * `country` is nullable-always; `custom` / `manualDefault*` are omitted-when-absent
 * (see the file KDoc).
 */
@Serializable
data class TaxSettingsDto(
    /** `none` | `manual_per_trade` | `country_specific` | `custom`. */
    val mode: String = "none",
    /** Set exactly when [mode] is `country_specific`; `AT` | `DE` | `FI`. */
    val country: String? = null,
    val custom: CustomTaxParamsDto? = null,
    val manualDefaultAmountEur: Double? = null,
    val manualDefaultRatePct: Double? = null,
)

/**
 * `PATCH /settings/taxes` body. Mode-dependent fields are unrepresentable
 * inconsistently server-side; the client mirrors those rules in
 * [at.bettertrack.app.domain.TaxSettingsDraft] so the user is never allowed to
 * compose a request the server will reject.
 */
@Serializable
data class UpdateTaxSettingsRequest(
    val mode: String,
    val country: String? = null,
    val custom: CustomTaxParamsDto? = null,
    val manualDefaultAmountEur: Double? = null,
    val manualDefaultRatePct: Double? = null,
)

// ── GET/PUT/DELETE /portfolios/:id/settings/tax ──────────────────────────────

/**
 * One portfolio's tax treatment resolved through the scoping cascade (#636):
 * `effective = override ?? userDefault ?? system('none')`.
 *
 * [source] is the field the UI actually renders a state from — `portfolio` means
 * "overridden", `user`/`system` mean "inheriting". It is reported rather than
 * inferred from `override != null` on purpose: the server owns the cascade, and
 * a client that re-derives it would drift the moment a layer is added.
 */
@Serializable
data class PortfolioTaxSettingsResponse(
    val effective: TaxSettingsDto = TaxSettingsDto(),
    val override: TaxSettingsDto? = null,
    val userDefault: TaxSettingsDto = TaxSettingsDto(),
    /** `portfolio` | `user` | `system`. */
    val source: String = "system",
)

// ── GET /portfolios/:id/reports/tax-years ────────────────────────────────────

/**
 * The German year-end block (V5-P4, §20 EStG) — present exactly when the year
 * contains DE-taxed rows, which is why it is nullable here and why the UI must
 * render the section conditionally rather than showing zeros.
 *
 * The allowance (Sparer-Pauschbetrag) does NOT carry: an unused remainder is
 * lost at year end. Both loss pots (Aktien / Sonstige) DO carry and are stored
 * positive. `kapestEur` / `soliEur` are the report's derived split of the year's
 * target — settlements post combined.
 */
@Serializable
data class TaxYearDeSummaryDto(
    val allowanceUsedEur: Double = 0.0,
    val allowanceRemainingEur: Double = 0.0,
    val aktienPotInEur: Double = 0.0,
    val aktienPotOutEur: Double = 0.0,
    val sonstigePotInEur: Double = 0.0,
    val sonstigePotOutEur: Double = 0.0,
    val kapestEur: Double = 0.0,
    val soliEur: Double = 0.0,
)

/**
 * One Europe/Vienna calendar year. `realizedPnlEur` / `dividendsGrossEur` are
 * financial facts across ALL rows regardless of tax mode; the tax figures are
 * the current movement-level truth (corrections included), with
 * `taxNetEur = taxWithheldEur − taxRefundedEur`.
 *
 * [locked] marks a closed year (any Vienna year before the current one): it
 * keeps its recording-time settlements and is never re-derived by a settings
 * change. Open years omit the key and re-derive live on every read — which is
 * exactly the distinction the report screen has to state, because it is the
 * difference between "this number is final" and "this number will move if you
 * change the mode".
 */
@Serializable
data class TaxYearSummaryDto(
    val year: Int = 0,
    val realizedPnlEur: Double = 0.0,
    val dividendsGrossEur: Double = 0.0,
    val taxWithheldEur: Double = 0.0,
    val taxRefundedEur: Double = 0.0,
    val taxNetEur: Double = 0.0,
    val de: TaxYearDeSummaryDto? = null,
    val locked: Boolean = false,
)

/** `GET /portfolios/:id/reports/tax-years` — newest year first. */
@Serializable
data class TaxYearListResponse(
    val years: List<TaxYearSummaryDto> = emptyList(),
)

// ── GET /portfolios/:id/reports/tax-years/:year ──────────────────────────────

/**
 * One sell in the year drill-down: the EUR realization against the current
 * moving-average basis, next to the tax facts FROZEN on the row at recording
 * time (`taxMode` null = a pre-engine row).
 *
 * `taxCountry` / `taxParams` are the frozen snapshot the row was taxed under.
 * Anything reconstructing a row's tax basis must read these and never the
 * portfolio's current settings — a mode switch is forward-only and never
 * rewrites history (§16).
 */
@Serializable
data class TaxYearSellDto(
    val transactionId: String = "",
    val executedAt: String = "",
    val quantity: Double = 0.0,
    val proceedsEur: Double = 0.0,
    val costBasisEur: Double = 0.0,
    val realizedPnlEur: Double = 0.0,
    val taxMode: String? = null,
    val taxAmountEur: Double? = null,
    val taxCountry: String? = null,
    val taxParams: CustomTaxParamsDto? = null,
)

/** One dividend in the year drill-down. */
@Serializable
data class TaxYearDividendDto(
    val dividendId: String = "",
    val executedAt: String = "",
    val grossAmountEur: Double = 0.0,
    val taxMode: String = "none",
    val taxAmountEur: Double? = null,
    val taxCountry: String? = null,
    val taxParams: CustomTaxParamsDto? = null,
)

/**
 * Per-position drill-down. `taxEur` is the tax recorded on THIS asset's rows —
 * year-level corrections are portfolio-wide and surface only in the summary, so
 * the positions will not always add up to `summary.taxNetEur` and the UI must
 * not present them as a reconciliation.
 */
@Serializable
data class TaxYearPositionDto(
    // The one undefaulted field in this file: [AssetDto] is the app's shared
    // asset identity and carries required members of its own, so a synthetic
    // default here would invent an asset with an empty id rather than fail
    // loudly on a payload that is genuinely malformed.
    val asset: AssetDto,
    val realizedPnlEur: Double = 0.0,
    val dividendsGrossEur: Double = 0.0,
    val taxEur: Double = 0.0,
    val sells: List<TaxYearSellDto> = emptyList(),
    val dividends: List<TaxYearDividendDto> = emptyList(),
)

/** `GET /portfolios/:id/reports/tax-years/:year`. */
@Serializable
data class TaxYearReportResponse(
    val year: Int = 0,
    val summary: TaxYearSummaryDto = TaxYearSummaryDto(),
    val positions: List<TaxYearPositionDto> = emptyList(),
)
