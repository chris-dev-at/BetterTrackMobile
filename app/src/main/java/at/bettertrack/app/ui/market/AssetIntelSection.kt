package at.bettertrack.app.ui.market

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.DividendEventDto
import at.bettertrack.app.data.api.dto.DividendsResponse
import at.bettertrack.app.data.api.dto.EarningsEventDto
import at.bettertrack.app.data.api.dto.EarningsResponse
import at.bettertrack.app.data.api.dto.NewsHeadlineDto
import at.bettertrack.app.data.api.dto.NewsResponse
import at.bettertrack.app.data.api.dto.SplitEventDto
import at.bettertrack.app.data.api.dto.SplitsResponse
import at.bettertrack.app.data.repo.AssetIntel
import at.bettertrack.app.data.repo.MarketIntelRepository
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCustomTab
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V5 S2c-2 — the **market-intel block on the asset page**: dividends, earnings,
 * headlines and splits for one asset, rendered under the price/chart content.
 *
 * The whole feature turns on one distinction, enforced by
 * [at.bettertrack.app.data.repo.IntelSection]:
 *
 *  - **Absent** — the server can't serve that block (flag off, the asset's
 *    provider has no such capability, upstream threw). It renders as NOTHING:
 *    no heading, no empty state, no error. Anything else would be the app
 *    inventing an answer it wasn't given.
 *  - **Empty** — the server answered and the list is genuinely empty ("this
 *    asset pays no dividends"). That is an ANSWER, and gets a calm empty line.
 *  - **Error** — the request itself failed (offline, 401, paranoid 403). That is
 *    a compact inline failure WITH retry; showing it as "no data" would let an
 *    aeroplane-mode phone claim a dividend aristocrat pays nothing.
 *
 * When every one of the four blocks is absent ([AssetIntel.allOff] — a custom
 * asset, or intel switched off server-wide) the section occupies ZERO height, so
 * the asset page looks exactly as it did before this feature existed.
 *
 * Everything with a currency renders through the app's money helpers
 * ([formatMoney]) so discreet mode masks it automatically. EPS figures do NOT —
 * see [IntelEarningsBlock] for why that is the honest choice, not an oversight.
 */

// ═══════════════════════ Pure display logic (unit-tested) ═══════════════════

/** How many past payouts / reports / headlines a *section* shows before stopping. */
private const val INTEL_DIVIDEND_HISTORY_CAP = 4
private const val INTEL_EARNINGS_RECENT_CAP = 3
private const val INTEL_NEWS_CAP = 5

/**
 * `forwardYield` on the wire is a **FRACTION** (`0.0152` == 1.52 %), while
 * [formatPercent] takes percent units. Multiplying here — once, in a named
 * function with a test pinning it — is the whole defence against the obvious bug
 * of rendering 1.52 % as "0,02 %".
 */
fun intelYieldPercent(fraction: Double?): Double? =
    fraction?.takeIf { it.isFinite() }?.times(100.0)

/**
 * Whether an amount may be shown at all.
 *
 * A number without a currency cannot be LABELLED, and labelling a provider's
 * `0.24` with the app's default € when it was really $0.24 is a lie the user has
 * no way to catch. The platform's own web client hides the amount in that case;
 * so does this app. Applies to dividend events and to the portfolio-wide
 * dividend calendar alike.
 */
fun intelAmountRenderable(amount: Double?, currency: String?): Boolean =
    amount != null && amount.isFinite() && !currency.isNullOrBlank()

/**
 * Parse one of this surface's timestamps. Every field here is a FULL ISO-8601
 * datetime, and [MarketRepository.parseIsoToMs] already handles that (plus the
 * offset and bare-date forms) — a second parser in the UI layer would only be a
 * second thing to get wrong.
 */
fun intelTimeMs(iso: String?): Long? =
    iso?.takeIf { it.isNotBlank() }?.let { MarketRepository.parseIsoToMs(it) }

/**
 * A wire timestamp as a localized **calendar date**.
 *
 * Rendered in UTC on purpose. An ex-date, a pay date and an earnings date are
 * calendar days that the server emits as UTC midnight; re-zoning them to the
 * device would slide a payout onto the previous day for every user west of
 * Greenwich. Instants that really are instants (a headline's `publishedAt`) go
 * through [intelAgeOf] instead, which is zone-independent by construction.
 */
fun intelDate(iso: String?, locale: Locale): String? =
    intelTimeMs(iso)?.let {
        Instant.ofEpochMilli(it)
            .atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }

/** The day a dividend event is filed under: its ex-date, else its pay date. */
fun intelDividendEventTime(event: DividendEventDto): Long? =
    intelTimeMs(event.exDate) ?: intelTimeMs(event.payDate)

/**
 * The single upcoming payout worth leading with — the soonest one. Undated
 * announcements sort last rather than being dropped: "announced, date to come"
 * is still news.
 */
fun intelNextDividend(response: DividendsResponse): DividendEventDto? =
    response.upcoming.minByOrNull { intelDividendEventTime(it) ?: Long.MAX_VALUE }

/** Most-recent-first slice of the payout history (the wire is ascending). */
fun intelRecentDividends(
    response: DividendsResponse,
    cap: Int = INTEL_DIVIDEND_HISTORY_CAP,
): List<DividendEventDto> = response.history.takeLast(cap).reversed()

/** Most-recent-first slice of past reports (the wire is ascending). */
fun intelRecentEarnings(
    response: EarningsResponse,
    cap: Int = INTEL_EARNINGS_RECENT_CAP,
): List<EarningsEventDto> = response.recent.takeLast(cap).reversed()

/** Splits newest first; an undated split keeps its place at the end. */
fun intelSplitRows(response: SplitsResponse): List<SplitEventDto> =
    response.history.sortedByDescending { intelTimeMs(it.date) ?: Long.MIN_VALUE }

/** True when the dividends block has literally nothing to say (but COULD have). */
fun intelDividendsEmpty(response: DividendsResponse): Boolean =
    response.history.isEmpty() &&
        response.upcoming.isEmpty() &&
        response.forwardYield == null &&
        response.trailingAmount == null

/** True when the earnings block has nothing to say (but COULD have). */
fun intelEarningsEmpty(response: EarningsResponse): Boolean =
    response.next == null && response.recent.isEmpty()

/**
 * Beat (+1) / miss (-1) / in line (0), or `null` when either side is missing —
 * which is the common case, since `epsActual` stays null until the company
 * actually reports. Colouring a row green off an estimate alone would be a
 * verdict on a result nobody has seen yet.
 *
 * Half a cent of EPS is provider rounding, not a surprise, so it reads as
 * "in line".
 */
fun intelEarningsSurprise(estimate: Double?, actual: Double?): Int? {
    if (estimate == null || actual == null) return null
    if (!estimate.isFinite() || !actual.isFinite()) return null
    val delta = actual - estimate
    return when {
        delta > 0.005 -> 1
        delta < -0.005 -> -1
        else -> 0
    }
}

/** A headline's age, bucketed for display. */
sealed interface IntelAge {
    data object Now : IntelAge
    data class Minutes(val value: Int) : IntelAge
    data class Hours(val value: Int) : IntelAge
    data class Days(val value: Int) : IntelAge
    data class Weeks(val value: Int) : IntelAge
}

/**
 * Bucket a headline's age. A `publishedAt` in the FUTURE (provider clock skew,
 * or a scheduled embargo lift) clamps to "just now" rather than rendering a
 * negative age.
 */
fun intelAgeOf(publishedMs: Long, nowMs: Long): IntelAge {
    val minutes = (nowMs - publishedMs).coerceAtLeast(0L) / 60_000L
    return when {
        minutes < 1 -> IntelAge.Now
        minutes < 60 -> IntelAge.Minutes(minutes.toInt())
        minutes < 60 * 24 -> IntelAge.Hours((minutes / 60).toInt())
        minutes < 60 * 24 * 7 -> IntelAge.Days((minutes / (60 * 24)).toInt())
        else -> IntelAge.Weeks((minutes / (60 * 24 * 7)).toInt())
    }
}

/** "publisher · 3 h ago", either half optional, never a stray separator. */
@Composable
fun intelHeadlineByline(headline: NewsHeadlineDto, nowMs: Long): String? {
    val age = intelTimeMs(headline.publishedAt)?.let { intelAgeLabel(intelAgeOf(it, nowMs)) }
    val publisher = headline.publisher?.takeIf { it.isNotBlank() }
    return listOfNotNull(publisher, age).joinToString(" · ").takeIf { it.isNotEmpty() }
}

@Composable
private fun intelAgeLabel(age: IntelAge): String = when (age) {
    IntelAge.Now -> stringResource(R.string.bt_intel_age_now)
    is IntelAge.Minutes -> stringResource(R.string.bt_intel_age_minutes, age.value)
    is IntelAge.Hours -> stringResource(R.string.bt_intel_age_hours, age.value)
    is IntelAge.Days -> stringResource(R.string.bt_intel_age_days, age.value)
    is IntelAge.Weeks -> stringResource(R.string.bt_intel_age_weeks, age.value)
}

// ═════════════════════════════ Opening a headline ═══════════════════════════

/**
 * Open a provider headline in a Chrome Custom Tab.
 *
 * The builder itself now lives in [BtCustomTab] — this feature was the third
 * place to grow the same brand-coloured, fail-soft Custom Tabs code, which is
 * one place too many for the app's chrome to be able to drift.
 */
internal fun openIntelArticle(context: Context, url: String) {
    BtCustomTab.open(context, url)
}

// ═════════════════════════════════ ViewModel ════════════════════════════════

/** The section's three outcomes — absent-ness lives INSIDE [AssetIntel]. */
sealed interface AssetIntelUiState {
    data object Loading : AssetIntelUiState
    data class Ready(val intel: AssetIntel) : AssetIntelUiState

    /** The capability probe itself failed — nothing here can be claimed. */
    data class Failed(val message: BtMessage) : AssetIntelUiState
}

class AssetIntelViewModel(
    private val repo: MarketIntelRepository,
    private val assetId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<AssetIntelUiState>(AssetIntelUiState.Loading)
    val state: StateFlow<AssetIntelUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = AssetIntelUiState.Loading
            _state.value = when (val r = repo.assetIntel(assetId)) {
                is BtResult.Ok -> AssetIntelUiState.Ready(r.value)
                is BtResult.Err -> AssetIntelUiState.Failed(r.error.asMessage())
            }
        }
    }
}

// ═══════════════════════════════════ UI ═════════════════════════════════════

/**
 * The intel block for one asset. Drop it below the existing asset-page content;
 * it owns its own ViewModel and loads once per [assetId].
 */
@Composable
fun AssetIntelSection(assetId: String, modifier: Modifier = Modifier) {
    val vm: AssetIntelViewModel = viewModel(key = "asset-intel-$assetId") {
        AssetIntelViewModel(AppGraph.marketIntelRepository, assetId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = rememberBtLocale()

    when (val s = state) {
        AssetIntelUiState.Loading -> IntelSectionSkeleton(modifier)

        is AssetIntelUiState.Failed -> IntelInlineError(
            message = s.message,
            onRetry = { vm.load() },
            modifier = modifier,
        )

        // Nothing the server can serve ⇒ nothing on screen. Not a heading over
        // four absences, not an empty state: zero height.
        is AssetIntelUiState.Ready -> if (!s.intel.allOff) {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.intel.dividends.payload?.let { IntelDividendsBlock(it, locale) }
                s.intel.earnings.payload?.let { IntelEarningsBlock(it, locale) }
                s.intel.news.payload?.let { IntelNewsBlock(it) }
                // Splits are the one block that hides on empty as well as on
                // absent: virtually every asset has never split, and a permanent
                // "No splits recorded" line on every asset page is noise, not an
                // answer anyone asked for.
                s.intel.splits.payload?.takeIf { it.history.isNotEmpty() }
                    ?.let { IntelSplitsBlock(it, locale) }
            }
        }
    }
}

// ── Dividends ───────────────────────────────────────────────────────────────

@Composable
private fun IntelDividendsBlock(response: DividendsResponse, locale: Locale) {
    val bt = BtTheme.colors
    IntelCard(stringResource(R.string.bt_intel_section_dividends)) {
        if (intelDividendsEmpty(response)) {
            IntelEmptyLine(stringResource(R.string.bt_intel_dividends_empty))
            return@IntelCard
        }

        val yieldPct = intelYieldPercent(response.forwardYield)
        val trailingCurrency = response.currency
        val trailingShown = intelAmountRenderable(response.trailingAmount, trailingCurrency)
        if (yieldPct != null || trailingShown) {
            Row(Modifier.fillMaxWidth()) {
                IntelStat(
                    label = stringResource(R.string.bt_intel_forward_yield),
                    value = yieldPct?.let { formatPercent(it, locale, showSign = false) }
                        ?: stringResource(R.string.bt_value_dash),
                    modifier = Modifier.weight(1f),
                )
                IntelStat(
                    label = stringResource(R.string.bt_intel_trailing_amount),
                    value = if (trailingShown) {
                        formatMoney(response.trailingAmount!!, trailingCurrency!!, locale)
                    } else {
                        stringResource(R.string.bt_value_dash)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        intelNextDividend(response)?.let { next ->
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.bt_intel_next_payout),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            IntelDividendRow(next, response.currency, locale, emphasis = true)
        }

        val history = intelRecentDividends(response)
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.bt_intel_dividend_history),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            history.forEach { IntelDividendRow(it, response.currency, locale, emphasis = false) }
        }
    }
}

/**
 * One payout: the dates on the left, the per-share amount on the right.
 *
 * The event's own currency wins over the response-level one; when neither exists
 * the amount is DROPPED rather than labelled with a guess (see
 * [intelAmountRenderable]).
 */
@Composable
private fun IntelDividendRow(
    event: DividendEventDto,
    fallbackCurrency: String?,
    locale: Locale,
    emphasis: Boolean,
) {
    val bt = BtTheme.colors
    val currency = event.currency ?: fallbackCurrency
    val dates = listOfNotNull(
        intelDate(event.exDate, locale)?.let { stringResource(R.string.bt_intel_ex_date, it) },
        intelDate(event.payDate, locale)?.let { stringResource(R.string.bt_intel_pay_date, it) },
    ).joinToString(" · ")

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = dates.ifEmpty { stringResource(R.string.bt_intel_date_unknown) },
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasis) bt.textPrimary else bt.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (intelAmountRenderable(event.amount, currency)) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatMoney(event.amount!!, currency!!, locale),
                style = BtTheme.type.moneySmall,
                fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
                color = bt.textPrimary,
            )
        }
    }
}

// ── Earnings ────────────────────────────────────────────────────────────────

/**
 * Next report + the last few, with estimate against actual.
 *
 * EPS renders as a bare number, NOT through the money helpers, because the wire
 * carries no currency for it. Labelling a US company's `2.15` with a € (the
 * app's default) would be exactly the mislabelling the dividend-calendar rule
 * exists to prevent — and an EPS figure is public company data, so leaving it
 * unmasked in discreet mode reveals nothing about the user's portfolio.
 */
@Composable
private fun IntelEarningsBlock(response: EarningsResponse, locale: Locale) {
    val bt = BtTheme.colors
    IntelCard(stringResource(R.string.bt_intel_section_earnings)) {
        if (intelEarningsEmpty(response)) {
            IntelEmptyLine(stringResource(R.string.bt_intel_earnings_empty))
            return@IntelCard
        }

        response.next?.let { next ->
            Text(
                text = stringResource(R.string.bt_intel_next_report),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = intelDate(next.date, locale)
                        ?: stringResource(R.string.bt_intel_date_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textPrimary,
                )
                if (next.estimated) {
                    Spacer(Modifier.width(8.dp))
                    // The provider is guessing the DATE — say so, rather than
                    // presenting a guess as a diary entry.
                    BtBadge(
                        text = stringResource(R.string.bt_intel_date_estimated),
                        kind = BtBadgeKind.Neutral,
                    )
                }
            }
            next.epsEstimate?.takeIf { it.isFinite() }?.let { eps ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.bt_intel_eps_estimate_value,
                        formatEps(eps, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
        }

        // The graphic (owner order 2026-08-10). It leads the history rows rather
        // than following them: the shape of six quarters of beats and misses is
        // the answer most readers want, and the rows are the detail they drop to
        // afterwards. Drawn only when there are at least two periods to compare —
        // a single bar is not a trend, it is a number with a rectangle around it.
        val bars = remember(response) { earningsChartBars(response) }
        if (earningsChartWorthDrawing(bars)) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.bt_intel_eps_history),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            EarningsChart(
                bars = bars,
                locale = locale,
                modifier = Modifier.fillMaxWidth().height(EARNINGS_CHART_HEIGHT),
            )
            Spacer(Modifier.height(8.dp))
            EarningsChartLegend()
        }

        val recent = intelRecentEarnings(response)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.bt_intel_recent_reports),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            recent.forEach { IntelEarningsRow(it, locale) }
        }
    }
}

@Composable
private fun IntelEarningsRow(event: EarningsEventDto, locale: Locale) {
    val bt = BtTheme.colors
    val surprise = intelEarningsSurprise(event.epsEstimate, event.epsActual)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = intelDate(event.date, locale) ?: stringResource(R.string.bt_intel_date_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val estimate = event.epsEstimate?.takeIf { it.isFinite() }?.let { formatEps(it, locale) }
        val actual = event.epsActual?.takeIf { it.isFinite() }?.let { formatEps(it, locale) }
        if (estimate != null || actual != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    R.string.bt_intel_eps_pair,
                    estimate ?: stringResource(R.string.bt_value_dash),
                    actual ?: stringResource(R.string.bt_value_dash),
                ),
                style = BtTheme.type.numberCaption,
                color = bt.textPrimary,
            )
        }
        // Only a report with BOTH numbers earns a verdict.
        if (surprise != null && surprise != 0) {
            Spacer(Modifier.width(8.dp))
            BtBadge(
                text = stringResource(
                    if (surprise > 0) R.string.bt_intel_beat else R.string.bt_intel_miss,
                ),
                kind = if (surprise > 0) BtBadgeKind.Gain else BtBadgeKind.Loss,
            )
        }
    }
}

// ── News ────────────────────────────────────────────────────────────────────

@Composable
private fun IntelNewsBlock(response: NewsResponse) {
    IntelCard(stringResource(R.string.bt_intel_section_news)) {
        if (response.headlines.isEmpty()) {
            IntelEmptyLine(stringResource(R.string.bt_intel_news_empty))
            return@IntelCard
        }
        val nowMs = System.currentTimeMillis()
        response.headlines.take(INTEL_NEWS_CAP).forEach { IntelHeadlineRow(it, nowMs) }
    }
}

/** One headline; the whole row opens the article in a Custom Tab. */
@Composable
internal fun IntelHeadlineRow(headline: NewsHeadlineDto, nowMs: Long) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val openLabel = stringResource(R.string.bt_intel_open_article)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = openLabel) { openIntelArticle(context, headline.url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = headline.title,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            intelHeadlineByline(headline, nowMs)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            // The row's own title is its label; the glyph is decoration, and the
            // tap action is already named by onClickLabel.
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── Splits ──────────────────────────────────────────────────────────────────

@Composable
private fun IntelSplitsBlock(response: SplitsResponse, locale: Locale) {
    val bt = BtTheme.colors
    IntelCard(stringResource(R.string.bt_intel_section_splits)) {
        intelSplitRows(response).forEach { split ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = intelDate(split.date, locale)
                        ?: stringResource(R.string.bt_intel_date_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // The server pre-renders "4:1"; re-deriving it from
                    // numerator/denominator would only be a way to disagree
                    // with the web client.
                    text = split.ratio,
                    style = BtTheme.type.numberCaption,
                    color = bt.textPrimary,
                )
            }
        }
    }
}

// ── Shared pieces ───────────────────────────────────────────────────────────

/** A titled intel card — the section's one repeated container. */
@Composable
internal fun IntelCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * A label over a value — the small stat pair used across the intel surfaces.
 *
 * [valueColor] is null for the overwhelming majority of stats, which are neutral
 * facts. It exists for the one case that is not: a NEGATIVE figure (a loss-making
 * year's net income) that a reader must be able to see without parsing a minus
 * sign. Defaulting to null rather than to a colour keeps every existing call site
 * neutral by construction.
 */
@Composable
internal fun IntelStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val bt = BtTheme.colors
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: bt.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * "There is nothing here" — an ANSWER, styled calmly, never as a failure.
 *
 * This row's styling is now the design system's
 * [at.bettertrack.app.ui.components.BtInlineEmpty]: it turned out that this
 * private helper had been the app's de-facto compact empty all along, copied by
 * hand into other screens because the DS shipped a compact *error* row
 * ([at.bettertrack.app.ui.components.BtInlineError]) and no compact *empty* to
 * pair with it. The name stays because the intel blocks read better for it —
 * same move `IntelInlineError` made below.
 */
@Composable
internal fun IntelEmptyLine(text: String, modifier: Modifier = Modifier) =
    BtInlineEmpty(text = text, modifier = modifier)

/**
 * A failed intel read, inline and with retry.
 *
 * Deliberately compact rather than a full [at.bettertrack.app.ui.components.BtErrorState]:
 * intel is a SECONDARY read on a page whose primary content (the price, the
 * chart) is already on screen, so claiming the surface would say the *page*
 * failed. The retry is not optional though; without it the only cure for a
 * dropped request is to leave the page and come back.
 *
 * (This used to cite `CashAnalyticsError` as the precedent for that reasoning.
 * That composable was a third hand-rolled copy of the same row and has since
 * been deleted in favour of the shared
 * [at.bettertrack.app.ui.components.BtInlineError] this one now delegates to,
 * so the citation would have pointed at nothing.)
 *
 * One line of copy is all this row has, so the diagnostic (present only for a
 * code this build has no copy for) rides along after an em dash rather than
 * claiming a second line the compact layout does not have.
 */
@Composable
internal fun IntelInlineError(message: BtMessage, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    BtInlineError(message = message, onRetry = onRetry, modifier = modifier)

/**
 * One card-shaped placeholder while the capability probe runs.
 *
 * Deliberately ONE block, not four: for a custom asset the answer is "nothing at
 * all", and four skeleton cards collapsing to zero height would be a far bigger
 * jump than one.
 */
@Composable
internal fun IntelSectionSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BtSkeleton(Modifier.fillMaxWidth(0.35f).height(14.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(72.dp))
    }
}
