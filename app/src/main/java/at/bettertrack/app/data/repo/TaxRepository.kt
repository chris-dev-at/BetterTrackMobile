package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.api.transportErr
import at.bettertrack.app.data.api.dto.CustomTaxParamsDto
import at.bettertrack.app.data.api.dto.PortfolioTaxSettingsResponse
import at.bettertrack.app.data.api.dto.TaxSettingsDto
import at.bettertrack.app.data.api.dto.TaxYearDeSummaryDto
import at.bettertrack.app.data.api.dto.TaxYearDividendDto
import at.bettertrack.app.data.api.dto.TaxYearPositionDto
import at.bettertrack.app.data.api.dto.TaxYearSellDto
import at.bettertrack.app.data.api.dto.TaxYearSummaryDto
import at.bettertrack.app.data.api.dto.UpdateTaxSettingsRequest
import at.bettertrack.app.domain.CustomTaxParams
import at.bettertrack.app.domain.SettingSource
import at.bettertrack.app.domain.TaxSettingsDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The tax surface (V3-P4 / V5-P4): the user-level default, one portfolio's
 * override, and the per-year reports including the CSV export.
 *
 * ## Three tiers, one shape
 *
 * `TaxSettingsDto` is the payload of all three layers of the per-portfolio
 * cascade, so the domain type [TaxSettings] is likewise reused. What differs is
 * only which layer you are looking at, and that is what [PortfolioTaxSettings]
 * carries — including the server's own [SettingSource], which the app reports
 * rather than infers. The cascade belongs to the server; a client that
 * re-derived "am I overridden?" from `override != null` would drift the moment
 * another layer is introduced.
 *
 * ## Scope trap worth knowing about
 *
 * The USER-level routes (`/settings/taxes`) are scoped `social:read`/`social:write`
 * — they sit under the `/settings` prefix — while the per-portfolio and report
 * routes are `portfolio:*`. A token holding only the portfolio pair 403s
 * `INSUFFICIENT_SCOPE` on the user default while succeeding everywhere else.
 * The app requests both pairs, so this is a note for whoever reads a confusing
 * 403 later, not a live defect.
 */

/** One tax configuration, as it appears at any layer of the cascade. */
data class TaxSettings(
    /** `none` | `manual_per_trade` | `country_specific` | `custom`. */
    val mode: String,
    /** Non-null exactly in `country_specific` mode. */
    val country: String?,
    /** Present exactly in `custom` mode. */
    val custom: CustomTaxParams?,
    val manualDefaultAmountEur: Double?,
    val manualDefaultRatePct: Double?,
) {
    /** True when this app build has no UI for the mode the server reports. */
    val isKnownMode: Boolean
        get() = mode in at.bettertrack.app.domain.TAX_MODES
}

/**
 * One portfolio's tax treatment, resolved.
 *
 * [source] is the rendering input: `Portfolio` means this portfolio pins its own
 * value; `User` / `System` mean it inherits and will follow a change to the user
 * default. [override] is what a "reset to default" action removes.
 */
data class PortfolioTaxSettings(
    val effective: TaxSettings,
    val override: TaxSettings?,
    val userDefault: TaxSettings,
    val source: SettingSource,
) {
    val isOverridden: Boolean get() = source == SettingSource.PORTFOLIO
}

/** The German year-end block — present exactly on DE-taxed years. */
data class DeTaxYearSummary(
    val allowanceUsedEur: Double,
    val allowanceRemainingEur: Double,
    val aktienPotInEur: Double,
    val aktienPotOutEur: Double,
    val sonstigePotInEur: Double,
    val sonstigePotOutEur: Double,
    val kapestEur: Double,
    val soliEur: Double,
)

/**
 * One Europe/Vienna calendar year.
 *
 * [locked] is the field that changes what the screen may promise. A closed year
 * keeps its recording-time settlements forever; an open year re-derives live on
 * every read under the portfolio's CURRENT settings, so its numbers genuinely
 * will move if the user edits the mode. Saying so is the difference between a
 * report the user trusts and one they quietly stop believing.
 */
data class TaxYearSummary(
    val year: Int,
    val realizedPnlEur: Double,
    val dividendsGrossEur: Double,
    val taxWithheldEur: Double,
    val taxRefundedEur: Double,
    val taxNetEur: Double,
    val de: DeTaxYearSummary?,
    val locked: Boolean,
)

/** One sell in a year's drill-down, with its FROZEN tax facts. */
data class TaxYearSell(
    val transactionId: String,
    val executedAt: String,
    val quantity: Double,
    val proceedsEur: Double,
    val costBasisEur: Double,
    val realizedPnlEur: Double,
    /** Null on a pre-engine row. */
    val taxMode: String?,
    val taxAmountEur: Double?,
    val taxCountry: String?,
    val taxParams: CustomTaxParams?,
)

/** One dividend in a year's drill-down. */
data class TaxYearDividend(
    val dividendId: String,
    val executedAt: String,
    val grossAmountEur: Double,
    val taxMode: String,
    val taxAmountEur: Double?,
    val taxCountry: String?,
    val taxParams: CustomTaxParams?,
)

/**
 * Per-position drill-down. [taxEur] counts only tax recorded on THIS asset's
 * rows — year-level corrections are portfolio-wide and appear only in the
 * summary, so positions will not always sum to the year's net and the UI must
 * not present them as a reconciliation.
 */
data class TaxYearPosition(
    val assetId: String,
    val symbol: String,
    val name: String,
    val realizedPnlEur: Double,
    val dividendsGrossEur: Double,
    val taxEur: Double,
    val sells: List<TaxYearSell>,
    val dividends: List<TaxYearDividend>,
)

data class TaxYearReport(
    val year: Int,
    val summary: TaxYearSummary,
    val positions: List<TaxYearPosition>,
)

class TaxRepository(
    private val api: BtApi,
    private val json: Json,
) {

    // ── User-level default ───────────────────────────────────────────────────

    suspend fun userTaxSettings(): BtResult<TaxSettings> =
        when (val r = apiCall(json) { api.taxSettings() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    suspend fun updateUserTaxSettings(draft: TaxSettingsDraft): BtResult<TaxSettings> =
        when (val r = apiCall(json) { api.updateTaxSettings(draft.toRequest()) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    // ── Per-portfolio override ───────────────────────────────────────────────

    suspend fun portfolioTaxSettings(portfolioId: String): BtResult<PortfolioTaxSettings> =
        when (val r = apiCall(json) { api.portfolioTaxSettings(portfolioId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    /**
     * Pin an override. The server reconciles open years immediately — correction
     * cash movements are posted as part of this call — so the caller must treat a
     * success as "the portfolio's cash and tax rows may both have changed" and
     * refresh accordingly, not merely as "a setting was saved".
     */
    suspend fun putPortfolioTaxSettings(
        portfolioId: String,
        draft: TaxSettingsDraft,
    ): BtResult<PortfolioTaxSettings> =
        when (val r = apiCall(json) { api.putPortfolioTaxSettings(portfolioId, draft.toRequest()) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    /**
     * Drop the override and inherit again. Returns the re-resolved cascade (the
     * route answers 200 with the new view, not 204), so the caller can show what
     * the portfolio fell back TO without a second round trip.
     */
    suspend fun clearPortfolioTaxSettings(portfolioId: String): BtResult<PortfolioTaxSettings> =
        when (val r = apiCall(json) { api.deletePortfolioTaxSettings(portfolioId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.toDomain())
            is BtResult.Err -> r
        }

    // ── Reports ──────────────────────────────────────────────────────────────

    suspend fun taxYears(portfolioId: String): BtResult<List<TaxYearSummary>> =
        when (val r = apiCall(json) { api.taxYears(portfolioId) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.years.map { it.toDomain() })
            is BtResult.Err -> r
        }

    suspend fun taxYearReport(portfolioId: String, year: Int): BtResult<TaxYearReport> =
        when (val r = apiCall(json) { api.taxYearReport(portfolioId, year) }) {
            is BtResult.Ok -> BtResult.Ok(
                TaxYearReport(
                    year = r.value.year,
                    summary = r.value.summary.toDomain(),
                    positions = r.value.positions.map { it.toDomain() },
                ),
            )

            is BtResult.Err -> r
        }

    /**
     * Download one year's CSV into [targetDir] and return the file.
     *
     * Written to disk rather than returned as a String because its only
     * destination is a share sheet, which needs a content URI backed by a real
     * file. Streaming straight from the response body to the file also keeps the
     * whole report off the heap.
     *
     * The body is copied on [Dispatchers.IO] and the stream is always closed —
     * an un-consumed OkHttp body leaks the connection, and this is the one call
     * in the app whose body is not parsed by the serializer's `use` block.
     */
    suspend fun downloadTaxYearCsv(
        portfolioId: String,
        year: Int,
        locale: String,
        targetDir: File,
    ): BtResult<File> = withContext(Dispatchers.IO) {
        try {
            val response = api.taxYearCsv(portfolioId, year, locale)
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                return@withContext BtResult.Err(
                    parseApiError(json, response.code(), response.errorBody()),
                )
            }
            if (!targetDir.exists()) targetDir.mkdirs()
            // Matches the server's own Content-Disposition filename, so the file
            // the user shares is named the same as the one the web app downloads.
            val file = File(targetDir, "tax-report-$year.csv")
            body.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            BtResult.Ok(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            transportErr(e)
        }
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun TaxSettingsDto.toDomain() = TaxSettings(
        mode = mode,
        country = country,
        custom = custom?.toDomain(),
        manualDefaultAmountEur = manualDefaultAmountEur,
        manualDefaultRatePct = manualDefaultRatePct,
    )

    private fun CustomTaxParamsDto.toDomain() = CustomTaxParams(
        ratePct = ratePct,
        lossOffset = lossOffset,
        refund = refund,
        yearReset = yearReset,
        carryForward = carryForward,
        costBasis = costBasis,
    )

    private fun PortfolioTaxSettingsResponse.toDomain() = PortfolioTaxSettings(
        effective = effective.toDomain(),
        override = override?.toDomain(),
        userDefault = userDefault.toDomain(),
        // Unknown layer → SYSTEM, the least-claiming answer: it renders as
        // "inheriting the built-in default", which is wrong-but-harmless, where
        // guessing PORTFOLIO would tell the user they had pinned something they
        // never pinned and offer them a reset that does nothing.
        source = SettingSource.entries.firstOrNull { it.wire == source } ?: SettingSource.SYSTEM,
    )

    private fun TaxYearSummaryDto.toDomain() = TaxYearSummary(
        year = year,
        realizedPnlEur = realizedPnlEur,
        dividendsGrossEur = dividendsGrossEur,
        taxWithheldEur = taxWithheldEur,
        taxRefundedEur = taxRefundedEur,
        taxNetEur = taxNetEur,
        de = de?.toDomain(),
        locked = locked,
    )

    private fun TaxYearDeSummaryDto.toDomain() = DeTaxYearSummary(
        allowanceUsedEur = allowanceUsedEur,
        allowanceRemainingEur = allowanceRemainingEur,
        aktienPotInEur = aktienPotInEur,
        aktienPotOutEur = aktienPotOutEur,
        sonstigePotInEur = sonstigePotInEur,
        sonstigePotOutEur = sonstigePotOutEur,
        kapestEur = kapestEur,
        soliEur = soliEur,
    )

    private fun TaxYearPositionDto.toDomain() = TaxYearPosition(
        assetId = asset.id,
        symbol = asset.symbol,
        name = asset.name,
        realizedPnlEur = realizedPnlEur,
        dividendsGrossEur = dividendsGrossEur,
        taxEur = taxEur,
        sells = sells.map { it.toDomain() },
        dividends = dividends.map { it.toDomain() },
    )

    private fun TaxYearSellDto.toDomain() = TaxYearSell(
        transactionId = transactionId,
        executedAt = executedAt,
        quantity = quantity,
        proceedsEur = proceedsEur,
        costBasisEur = costBasisEur,
        realizedPnlEur = realizedPnlEur,
        taxMode = taxMode,
        taxAmountEur = taxAmountEur,
        taxCountry = taxCountry,
        taxParams = taxParams?.toDomain(),
    )

    private fun TaxYearDividendDto.toDomain() = TaxYearDividend(
        dividendId = dividendId,
        executedAt = executedAt,
        grossAmountEur = grossAmountEur,
        taxMode = taxMode,
        taxAmountEur = taxAmountEur,
        taxCountry = taxCountry,
        taxParams = taxParams?.toDomain(),
    )
}

/**
 * Serialize a draft to the one field set its mode allows.
 *
 * This is the single place the server's six `superRefine` rules are honoured on
 * the way out: every branch emits its own fields and leaves the rest null, and
 * the app's `explicitNulls = false` Json drops them from the body entirely — so
 * a `country` can never accompany a non-country mode, and `custom` can never
 * accompany a non-custom one. Sending `"custom": null` would fail a schema that
 * only tolerates the key's ABSENCE, which is why this cannot be a simple
 * field-by-field copy.
 */
internal fun TaxSettingsDraft.toRequest(): UpdateTaxSettingsRequest = when (this) {
    is TaxSettingsDraft.None -> UpdateTaxSettingsRequest(mode = mode)

    is TaxSettingsDraft.CountrySpecific -> UpdateTaxSettingsRequest(
        mode = mode,
        country = country,
    )

    is TaxSettingsDraft.Custom -> UpdateTaxSettingsRequest(
        mode = mode,
        custom = CustomTaxParamsDto(
            ratePct = params.ratePct,
            lossOffset = params.lossOffset,
            refund = params.refund,
            yearReset = params.yearReset,
            carryForward = params.carryForward,
            costBasis = params.costBasis,
        ),
    )

    is TaxSettingsDraft.Manual -> UpdateTaxSettingsRequest(
        mode = mode,
        manualDefaultAmountEur = defaultAmountEur,
        manualDefaultRatePct = defaultRatePct,
    )
}
