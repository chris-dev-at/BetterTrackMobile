package at.bettertrack.app.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.FundamentalsRatiosDto
import at.bettertrack.app.data.api.dto.FundamentalsResponse
import at.bettertrack.app.data.repo.MarketIntelRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatCompactMoney
import at.bettertrack.app.ui.components.formatCompactNumber
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The **fundamentals block on the asset page** (platform arc f, board #76 item 1)
 * — annual or quarterly statement figures for one asset, plus the snapshot
 * valuation ratios.
 *
 * This is the section the owner asked for on 2026-08-10 (*"more info for
 * earnings. like yearly quarterly reports and nice graphics"*). It does not
 * replace the earnings block next to it: that one charts **EPS estimate against
 * actual per report**, which is a question about guidance, and this one charts
 * **revenue against net income per fiscal period**, which is a question about the
 * business. Both survive a provider that can only serve one of them, which is why
 * they are separate cards rather than two tabs of one.
 *
 * ## The three states, and the one that matters most
 *
 * Fundamentals is deliberately **not** part of the `GET /assets/{id}/intel`
 * capability probe — the contract leaves it out, so the only way to learn whether
 * an asset has fundamentals is to ask for them. That makes the response's own
 * `available` flag the whole gate:
 *
 *  - **`available: false`** — a Drive-only or custom asset, a provider without
 *    the capability, a gate switched off, or an upstream error. The section
 *    renders as NOTHING: zero height, no heading, no empty state. The EPS
 *    earnings chart beside it stays exactly as it was, which is the fallback
 *    layer for this whole feature — an asset that cannot serve statements still
 *    gets the graphic it could always serve.
 *  - **`available: true` with periods** — the card.
 *  - **a transport failure** — an inline error WITH retry, NOT a hidden block.
 *    Hiding it would let an aeroplane-mode phone quietly claim Apple files no
 *    accounts, which is the same lie [AssetIntelSection] refuses to tell.
 *
 * ## Nulls
 *
 * Every figure on this wire is nullable and a gap is `null`, never a zero. So no
 * row here ever prints a fabricated number and no row ever prints "null": a stat
 * with no value is DROPPED from the grid entirely rather than rendered as a
 * dash-filled cell, and a period with neither revenue nor net income never
 * becomes a bar. Today's provider supplies no per-period `eps` and no
 * `reportDate` at all, so those two are absent for every asset — which is exactly
 * the case this rule is built for.
 */

// ═══════════════════════ Pure display logic (unit-tested) ═══════════════════

/** The statement granularity toggle. [wire] is the query value the contract takes. */
enum class FundamentalsPeriodType(val wire: String) {
    ANNUAL("annual"),
    QUARTERLY("quarterly"),
}

/** Both options, in toggle order. */
val FUNDAMENTALS_PERIOD_TYPES: List<FundamentalsPeriodType> = FundamentalsPeriodType.entries.toList()

/**
 * `profitMargin` and `returnOnEquity` arrive as **FRACTIONS** (`0.25` ≈ 25 %)
 * while [formatPercent] takes percent units. Converting here — once, in a named
 * function with a test pinning it — is the whole defence against rendering a
 * 25 % margin as "0,25 %". Exactly the job [intelYieldPercent] does for the
 * dividend yield.
 *
 * `debtToEquity` deliberately does NOT go through this: it is already in percent
 * units on the wire. See [FundamentalsRatiosDto].
 */
fun fundamentalsFractionPercent(fraction: Double?): Double? =
    fraction?.takeIf { it.isFinite() }?.times(100.0)

/** One cell of the ratio grid: a label and an already-formatted value. */
data class FundamentalsStat(val labelRes: Int, val value: String)

/**
 * Whether the annual/quarterly toggle is worth showing.
 *
 * The rule exists because a provider with ratios but NO statements in either
 * granularity — every crypto asset, where market cap exists and an income
 * statement does not — was rendering a switch whose only possible effect was to
 * swap one empty view for another.
 *
 * [loading] keeps the control on screen across a re-fetch so it does not
 * disappear under the finger that just pressed it, and the [period] clause keeps
 * it on screen once the user has moved off the default, so a granularity that
 * legitimately came back empty can still be navigated out of.
 */
fun fundamentalsShowPeriodToggle(
    hasChart: Boolean,
    period: FundamentalsPeriodType,
    loading: Boolean,
): Boolean = hasChart || loading || period != FundamentalsPeriodType.ANNUAL

/** True when the response answered but carries nothing renderable. */
fun fundamentalsEmpty(response: FundamentalsResponse): Boolean =
    response.periods.isEmpty() && !fundamentalsHasAnyRatio(response.ratios)

/** Whether any ratio at all survived the provider. */
fun fundamentalsHasAnyRatio(ratios: FundamentalsRatiosDto): Boolean =
    listOf(
        ratios.marketCap,
        ratios.trailingPe,
        ratios.forwardPe,
        ratios.priceToBook,
        ratios.profitMargin,
        ratios.returnOnEquity,
        ratios.debtToEquity,
        ratios.trailingEps,
        ratios.forwardEps,
    ).any { it != null && it.isFinite() }

/**
 * The ratio highlights, in reading order, with **every absent one dropped**.
 *
 * Six of the nine ratios on the wire, chosen because together they answer "what
 * is it worth, does it make money, and what does it owe" without turning a card
 * into a spreadsheet — and because six fills two rows of three exactly, so a
 * fully-populated asset never renders a lonely orphan cell.
 *
 * Trailing EPS earns its place next to the P/E rather than beside the EPS chart
 * in the earnings block: P/E *is* price over trailing EPS, so the two belong in
 * one eyeline, and putting it here needs no second request. Forward P/E,
 * price-to-book and forward EPS are omitted as the estimate-flavoured or
 * second-order versions of ratios already shown.
 *
 * Dropping rather than dashing is the point: a grid of six em dashes tells the
 * reader nothing except that the app tried, and the "absent rows collapse" rule
 * the owner asked for is enforced right here.
 */
fun fundamentalsRatioStats(
    ratios: FundamentalsRatiosDto,
    currency: String?,
    locale: Locale,
): List<FundamentalsStat> {
    fun money(value: Double?): String? = value?.takeIf { it.isFinite() }?.let {
        // No currency from the provider means the figure cannot be LABELLED, and
        // stamping the app's default € on a US market cap is the mislabelling
        // `intelAmountRenderable` exists to prevent. The bare magnitude is still
        // true, so it is shown without a symbol rather than dropped.
        if (currency.isNullOrBlank()) {
            formatCompactNumber(it, locale)
        } else {
            formatCompactMoney(it, currency, locale)
        }
    }

    fun ratio(value: Double?): String? =
        value?.takeIf { it.isFinite() }?.let { formatBareDecimal(it, locale) }

    fun fractionPct(value: Double?): String? =
        fundamentalsFractionPercent(value)?.let { formatPercent(it, locale, showSign = false) }

    return listOfNotNull(
        money(ratios.marketCap)?.let { FundamentalsStat(R.string.bt_fundamentals_market_cap, it) },
        ratio(ratios.trailingPe)?.let { FundamentalsStat(R.string.bt_fundamentals_pe, it) },
        fractionPct(ratios.profitMargin)
            ?.let { FundamentalsStat(R.string.bt_fundamentals_profit_margin, it) },
        fractionPct(ratios.returnOnEquity)
            ?.let { FundamentalsStat(R.string.bt_fundamentals_roe, it) },
        // NOT rescaled — already percent units on the wire, and the label carries
        // the unit so "145 %" cannot be misread as a 145× ratio.
        ratios.debtToEquity?.takeIf { it.isFinite() }
            ?.let { FundamentalsStat(R.string.bt_fundamentals_debt_equity, formatPercent(it, locale, showSign = false)) },
        // Bare, like every EPS figure in the app: the wire carries no currency for
        // it, and stamping one on would be the mislabelling the dividend rule
        // exists to prevent.
        ratio(ratios.trailingEps)
            ?.let { FundamentalsStat(R.string.bt_fundamentals_trailing_eps, it) },
    )
}

// ═════════════════════════════════ ViewModel ════════════════════════════════

/** What the card is doing right now. */
sealed interface FundamentalsPhase {
    data object Loading : FundamentalsPhase
    data class Ready(val response: FundamentalsResponse) : FundamentalsPhase

    /** The server answered `available: false` — render nothing at all. */
    data object Unavailable : FundamentalsPhase

    /** The request itself failed — retryable, never silently hidden. */
    data class Failed(val message: BtMessage) : FundamentalsPhase
}

data class FundamentalsUiState(
    val period: FundamentalsPeriodType,
    val phase: FundamentalsPhase,
    /**
     * True once the card has rendered real data at least once. It keeps a
     * granularity FLIP from collapsing the whole section to a skeleton and back —
     * the toggle the user just pressed would jump out from under their finger.
     */
    val everReady: Boolean = false,
)

class FundamentalsViewModel(
    private val repo: MarketIntelRepository,
    private val assetId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(
        FundamentalsUiState(FundamentalsPeriodType.ANNUAL, FundamentalsPhase.Loading),
    )
    val state: StateFlow<FundamentalsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun select(period: FundamentalsPeriodType) {
        if (_state.value.period == period) return
        _state.value = _state.value.copy(period = period, phase = FundamentalsPhase.Loading)
        load()
    }

    fun load() {
        val period = _state.value.period
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = FundamentalsPhase.Loading)
            val phase = when (
                val r = repo.fundamentals(assetId, period.wire, FUNDAMENTALS_CHART_CAP)
            ) {
                is BtResult.Ok ->
                    if (r.value.available) {
                        FundamentalsPhase.Ready(r.value)
                    } else {
                        FundamentalsPhase.Unavailable
                    }

                is BtResult.Err -> FundamentalsPhase.Failed(r.error.asMessage())
            }
            // A late response for a granularity the user has already toggled away
            // from would repaint the card with the wrong series under the right
            // label. Dropping it is cheaper than a request id.
            if (_state.value.period != period) return@launch
            _state.value = _state.value.copy(
                phase = phase,
                everReady = _state.value.everReady || phase is FundamentalsPhase.Ready,
            )
        }
    }
}

// ═══════════════════════════════════ UI ═════════════════════════════════════

/**
 * The fundamentals block for one asset. Owns its own ViewModel and loads once per
 * [assetId]; renders zero height when the provider cannot serve statements.
 */
@Composable
fun FundamentalsSection(assetId: String, modifier: Modifier = Modifier) {
    val vm: FundamentalsViewModel = viewModel(key = "fundamentals-$assetId") {
        FundamentalsViewModel(AppGraph.marketIntelRepository, assetId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = rememberBtLocale()

    when (val phase = state.phase) {
        // Nothing this provider can serve ⇒ nothing on screen. The EPS earnings
        // chart beside it is the fallback layer and is untouched by this.
        FundamentalsPhase.Unavailable -> Unit

        is FundamentalsPhase.Failed -> IntelInlineError(
            message = phase.message,
            onRetry = { vm.load() },
            modifier = modifier,
        )

        FundamentalsPhase.Loading -> if (state.everReady) {
            FundamentalsCard(state.period, null, locale, vm::select, modifier)
        } else {
            IntelSectionSkeleton(modifier)
        }

        is FundamentalsPhase.Ready ->
            FundamentalsCard(state.period, phase.response, locale, vm::select, modifier)
    }
}

/**
 * The card. A null [response] means the granularity is being re-fetched — the
 * frame and the toggle stay put and only the content below them is a skeleton.
 */
@Composable
private fun FundamentalsCard(
    period: FundamentalsPeriodType,
    response: FundamentalsResponse?,
    locale: Locale,
    onSelect: (FundamentalsPeriodType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bars = remember(response) { response?.let { fundamentalsChartBars(it) } ?: emptyList() }
    val hasChart = fundamentalsChartWorthDrawing(bars)

    IntelCard(stringResource(R.string.bt_fundamentals_section), modifier) {
        // The toggle appears only when there is something to toggle BETWEEN —
        // see [fundamentalsShowPeriodToggle].
        if (fundamentalsShowPeriodToggle(hasChart, period, loading = response == null)) {
            BtSegmented(
                options = FUNDAMENTALS_PERIOD_TYPES,
                selected = period,
                label = { stringResource(fundamentalsPeriodTypeLabel(it)) },
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
                equalWidths = true,
            )
            Spacer(Modifier.height(14.dp))
        }

        if (response == null) {
            BtSkeleton(Modifier.fillMaxWidth().height(FUNDAMENTALS_CHART_HEIGHT))
            return@IntelCard
        }
        if (fundamentalsEmpty(response)) {
            IntelEmptyLine(stringResource(R.string.bt_fundamentals_empty))
            return@IntelCard
        }

        if (hasChart) {
            FundamentalsSeriesBlock(bars, response.currency, locale)
        }

        val stats = remember(response, locale) {
            fundamentalsRatioStats(response.ratios, response.currency, locale)
        }
        if (stats.isNotEmpty()) {
            if (hasChart) Spacer(Modifier.height(16.dp))
            FundamentalsStatGrid(stats)
        }
    }
}

/** The readout, the chart and its key — the three pieces that move together. */
@Composable
private fun FundamentalsSeriesBlock(
    bars: List<FundamentalsBar>,
    currency: String?,
    locale: Locale,
) {
    // The newest period is the one someone opening the page came to read, so the
    // readout starts there rather than on an arbitrary end of the axis. Keyed on
    // `bars`, so flipping the granularity resets the selection to that series'
    // newest period instead of stranding it on an index that meant something
    // else — and so a scrub survives every recomposition that isn't a data change.
    var selected by remember(bars) { mutableIntStateOf(bars.lastIndex) }
    val safeIndex = selected.coerceIn(0, (bars.size - 1).coerceAtLeast(0))
    bars.getOrNull(safeIndex)?.let { FundamentalsReadout(it, currency, locale) }
    Spacer(Modifier.height(10.dp))
    FundamentalsChart(
        bars = bars,
        locale = locale,
        selectedIndex = safeIndex,
        onSelect = { selected = it },
        modifier = Modifier.fillMaxWidth().height(FUNDAMENTALS_CHART_HEIGHT),
    )
    Spacer(Modifier.height(8.dp))
    FundamentalsChartLegend()
}

/**
 * The selected period's exact figures — the precision the abbreviated axis gives
 * up. Net income wears the gain/loss colour because a loss is the one fact on
 * this card that a reader must not have to parse a minus sign to notice.
 */
@Composable
private fun FundamentalsReadout(bar: FundamentalsBar, currency: String?, locale: Locale) {
    val bt = BtTheme.colors
    val quarterly = bar.fiscalPeriod.startsWith("Q")
    val year = bar.fiscalYear?.toString()
    val title = when {
        year == null -> bar.fiscalPeriod
        quarterly -> stringResource(R.string.bt_fundamentals_period_quarter, bar.fiscalPeriod, year)
        else -> stringResource(R.string.bt_fundamentals_period_annual, year)
    }

    fun figure(value: Double?): String? = value?.takeIf { it.isFinite() }?.let {
        if (currency.isNullOrBlank()) {
            formatCompactNumber(it, locale)
        } else {
            formatCompactMoney(it, currency, locale)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            IntelStat(
                label = stringResource(R.string.bt_fundamentals_revenue),
                value = figure(bar.revenue) ?: stringResource(R.string.bt_value_dash),
                modifier = Modifier.weight(1f),
            )
            IntelStat(
                label = stringResource(R.string.bt_fundamentals_net_income),
                value = figure(bar.netIncome) ?: stringResource(R.string.bt_value_dash),
                valueColor = bar.netIncome?.takeIf { it < 0.0 }?.let { bt.loss },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The ratio highlights, three to a row.
 *
 * The last row is padded with empty weights rather than stretched, so a grid of
 * four stats does not render two double-width cells that look like a different
 * kind of value from the three above them.
 */
@Composable
private fun FundamentalsStatGrid(stats: List<FundamentalsStat>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stats.chunked(FUNDAMENTALS_STATS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { stat ->
                    IntelStat(
                        label = stringResource(stat.labelRes),
                        value = stat.value,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(FUNDAMENTALS_STATS_PER_ROW - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private const val FUNDAMENTALS_STATS_PER_ROW = 3

/** The toggle's two labels. */
internal fun fundamentalsPeriodTypeLabel(type: FundamentalsPeriodType): Int = when (type) {
    FundamentalsPeriodType.ANNUAL -> R.string.bt_fundamentals_annual
    FundamentalsPeriodType.QUARTERLY -> R.string.bt_fundamentals_quarterly
}
