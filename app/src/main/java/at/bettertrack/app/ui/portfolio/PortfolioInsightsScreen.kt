package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.charts.BtDonutChart
import at.bettertrack.app.ui.charts.DonutSegment
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.market.rememberAssetTypeLabeller
import at.bettertrack.app.ui.theme.BtColors
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The portfolio's **Insights** subpage (owner UI batch 2026-08-16).
 *
 * Born as the new home of the allocation section, which the owner moved OFF the
 * overview — the overview now reads value → curve → positions, and proportion
 * analysis gets a page of its own behind the "More insights" row. A page rather
 * than a sheet because it is built to GROW: future insight modules (performance
 * attribution, income projections, whatever earns its way in) land as further
 * sections of this Column without renegotiating the overview's layout each time.
 *
 * Portfolio-SCOPED by id, like the settings page — an ambient-selection insights
 * page that silently retargeted itself would be wrong in the same way.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PortfolioInsightsScreen(
    portfolioId: String,
    onBack: () -> Unit,
) {
    val vm: PortfolioInsightsViewModel = viewModel(key = "portfolio-insights-$portfolioId") {
        PortfolioInsightsViewModel(AppGraph.portfolioRepository, portfolioId)
    }
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val name by vm.portfolioName.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    val cashEur by vm.cashEur.collectAsStateWithLifecycle()
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()

    Scaffold(
        containerColor = bt.bg,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_insights_title),
                subtitle = name ?: "",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (holdings.isEmpty() && cashEur <= 0.0) {
                // Nothing to divide: an empty portfolio has no proportions. The
                // calm inline form — this is an answer, not a failure.
                BtInlineEmpty(text = stringResource(R.string.bt_overview_no_holdings_title))
            } else {
                AllocationSummary(holdings = holdings, cashEur = cashEur, locale = locale)
            }
        }
    }
}

/**
 * State for [PortfolioInsightsScreen]: the cached rows this page's sections
 * read, plus one quiet on-open detail refresh so the proportions are current.
 * Every number rendered is server-computed (§7.1); the allocation section only
 * maps them to shares.
 */
class PortfolioInsightsViewModel(
    repo: PortfolioRepository,
    portfolioId: String,
) : ViewModel() {

    val portfolioName: StateFlow<String?> = repo.portfolios
        .map { all -> all.firstOrNull { it.id == portfolioId }?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Server cash roll-up — allocation's one non-holding slice. */
    val cashEur: StateFlow<Double> = repo.portfolios
        .map { all -> all.firstOrNull { it.id == portfolioId }?.totals?.cashEur ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    /** Same display filter as the overview: sold-out positions have no share. */
    val holdings: StateFlow<List<HoldingEntity>> = repo.holdings(portfolioId)
        .map { visibleHoldings(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Fail-soft freshness: offline still renders the cache under the global
        // as-of banner, exactly like the overview it grew out of.
        viewModelScope.launch { repo.refreshPortfolioDetail(portfolioId) }
    }
}

// ── The allocation section (moved verbatim from the overview, 2026-08-16) ────

/**
 * The allocation section (decision O-5, relocated by the owner's 2026-08-16
 * batch from the overview to this page).
 *
 * ## The bar and the donut swapped defaults when the section moved
 *
 * O-5 led with a slim stacked bar and three names and kept the donut behind
 * "See all". That was a decision about SCARCITY: on the overview this section
 * had to state a proportion in 10dp of height without pushing the holdings list
 * off the screen, and a bar does that where a donut cannot.
 *
 * The scarcity left with the section. On a page whose whole subject is
 * allocation, the donut is simply the better shape for "how is this divided", so
 * the page opens on it, on the full legend, and on the by-asset / by-category
 * switch — a question you ask while *studying* allocation, which is now the only
 * reason to be on this screen. Nothing was deleted: the bar is still what the
 * collapsed state draws, and a thirty-position portfolio can still fold the
 * legend back to its top three. Only the default changed.
 *
 * Exactly ONE graphic shows at a time in either state — a bar above a donut is
 * two pictures of one number.
 */
@Composable
internal fun AllocationSummary(holdings: List<HoldingEntity>, cashEur: Double, locale: Locale) {
    val bt = BtTheme.colors
    var byCategory by rememberSaveable { mutableStateOf(false) }
    // ── Open, not collapsed (2026-08-16, on first render of this page) ───────
    //
    // The collapse was correct when this section was a BLOCK on the overview:
    // there, three names and a 10dp bar were all the room allocation could claim
    // without pushing the holdings list off the screen, and the donut was
    // rightly one tap behind "See all".
    //
    // On a page whose entire subject is allocation, that same default renders a
    // bar, three lines, and two thirds of a phone screen of nothing — the empty
    // void the owner names as a defect in its own right. Worse, it hides the
    // donut, which is the picture this section was moved here to be able to
    // show. So the page opens on the full statement and the disclosure keeps its
    // other direction: a portfolio with thirty positions can still fold the
    // legend back down to the top three.
    var expanded by rememberSaveable { mutableStateOf(true) }

    val otherLabel = stringResource(R.string.bt_overview_alloc_other)
    val cashLabel = stringResource(R.string.bt_overview_alloc_cash)
    // R3 §3: the category names are resolved HERE, like the two labels above,
    // because `allocationSegments` is a pure function and `assetTypeLabel` is a
    // composable. `assetTypeLabel` is the app's real, localized mapping for
    // exactly these server type strings.
    val categoryLabels = rememberAssetTypeLabeller()
    val palette = BtTheme.colors
    val segments = remember(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels, palette) {
        allocationSegments(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels, palette)
    }
    val total = segments.sumOf { it.value }
    if (segments.isEmpty() || total <= 0.0) return

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bt_overview_allocation_section),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(10.dp))

        // By-asset/by-category is an exclusive choice, and this app says
        // exclusive choice with [BtSegmented] (owner order 2026-08-10). It
        // governs the bar and the legend the reader is looking at right now,
        // so it is never hidden behind "see all".
        BtSegmented(
            options = ALLOCATION_GROUPINGS,
            selected = if (byCategory) AllocationGrouping.CATEGORY else AllocationGrouping.ASSET,
            label = { stringResource(allocationGroupingLabel(it)) },
            onSelect = { byCategory = it == AllocationGrouping.CATEGORY },
            modifier = Modifier.fillMaxWidth(),
            equalWidths = true,
        )
        Spacer(Modifier.height(14.dp))
        // ONE graphic at a time. Collapsed, the slim stacked bar is the compact
        // statement; expanded, it opens into the donut. Both at once is two
        // pictures of one number.
        if (expanded) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BtDonutChart(segments = segments, modifier = Modifier.size(ALLOCATION_DONUT_SIZE))
            }
        } else {
            AllocationBar(segments = segments, total = total)
        }
        Spacer(Modifier.height(14.dp))

        // One row per slice, in the SAME shape whether three of them or all of
        // them are showing — expanding grows a list instead of swapping layouts.
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            val shown = if (expanded) segments else segments.take(ALLOCATION_SUMMARY_LEGEND)
            shown.forEach { segment ->
                AllocationLegendRow(segment = segment, total = total, locale = locale)
            }
        }

        if (segments.size > ALLOCATION_SUMMARY_LEGEND) {
            Spacer(Modifier.height(4.dp))
            AllocationExpandRow(
                expanded = expanded,
                hidden = segments.size - ALLOCATION_SUMMARY_LEGEND,
                onToggle = { expanded = !expanded },
            )
        }
    }
}

/** How many slices the collapsed allocation summary names. */
private const val ALLOCATION_SUMMARY_LEGEND = 3

/** The expanded donut. Centred and given room, rather than squeezed beside text. */
private val ALLOCATION_DONUT_SIZE = 168.dp

/** The two ways the allocation section can group a portfolio. */
private enum class AllocationGrouping { ASSET, CATEGORY }

private val ALLOCATION_GROUPINGS = listOf(AllocationGrouping.ASSET, AllocationGrouping.CATEGORY)

private fun allocationGroupingLabel(grouping: AllocationGrouping): Int = when (grouping) {
    AllocationGrouping.ASSET -> R.string.bt_overview_alloc_by_asset
    AllocationGrouping.CATEGORY -> R.string.bt_overview_alloc_by_category
}

/**
 * One legend line: swatch, weight, name.
 *
 * **The number leads.** The figures sit in a fixed leading column, aligned to
 * the digit — they are the thing the eye scans a ranked list for, and nothing
 * sits at the right edge for a FAB to cover (S6 P1-7's lesson, kept).
 */
@Composable
private fun AllocationLegendRow(segment: DonutSegment, total: Double, locale: Locale) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(segment.color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text = weightPct(segment.value, total)?.let { formatWeight(it, locale) }
                ?: stringResource(R.string.bt_value_dash),
            style = BtTheme.type.numberCaption,
            color = bt.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(ALLOCATION_WEIGHT_COLUMN),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = segment.label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The width the weights column reserves. Wide enough for "100,0 %" at the
 * largest font scale the app supports without the names starting at a different
 * x on one row than on the next.
 */
private val ALLOCATION_WEIGHT_COLUMN = 58.dp

/**
 * The expand/collapse control — a disclosure row that says HOW MANY more there
 * are, which is the one thing a reader wants before deciding to tap.
 */
@Composable
private fun AllocationExpandRow(expanded: Boolean, hidden: Int, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BtShapes.card)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.bt_overview_alloc_less)
            } else {
                stringResource(R.string.bt_overview_alloc_see_all_count, hidden)
            },
            style = MaterialTheme.typography.labelLarge,
            color = bt.goldInk,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = bt.goldInk,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The slim stacked bar. Weighted rather than measured: the slices are
 * proportions of a known total, so `weight` keeps them exact at any width. The
 * 2dp gaps keep adjacent slices of similar colour readable as two things.
 */
@Composable
private fun AllocationBar(segments: List<DonutSegment>, total: Double) {
    val cd = stringResource(R.string.bt_overview_alloc_bar_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .semantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            val share = (segment.value / total).toFloat()
            if (share > 0f) {
                Box(
                    Modifier
                        .weight(share)
                        .fillMaxHeight()
                        .background(segment.color, BtShapes.pill),
                )
            }
        }
    }
}

// ── Display mapping (proportions of server values only) ────────────────────

/**
 * Build the donut segments from server values: top slices by weight in fixed
 * palette-slot order, tail folded into "Other", cash always its own quiet
 * slice. Percentages are proportions of the server-provided EUR values — the
 * same display mapping the reference web app renders.
 */
private fun allocationSegments(
    holdings: List<HoldingEntity>,
    cashEur: Double,
    byCategory: Boolean,
    otherLabel: String,
    cashLabel: String,
    categoryLabel: (String) -> String,
    palette: BtColors,
): List<DonutSegment> {
    data class Part(val label: String, val value: Double)

    val parts: List<Part> = if (byCategory) {
        holdings
            .groupBy { it.assetType }
            .map { (type, rows) -> Part(categoryLabel(type), rows.sumOf { it.marketValueEur ?: 0.0 }) }
    } else {
        holdings.map { Part(it.assetSymbol, it.marketValueEur ?: 0.0) }
    }
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }

    val maxSlots = palette.chartSeries.size
    val top = parts.take(maxSlots)
    val rest = parts.drop(maxSlots).sumOf { it.value }

    return buildList {
        top.forEachIndexed { i, part ->
            add(DonutSegment(part.label, part.value, palette.chartSeries[i]))
        }
        if (rest > 0.0) add(DonutSegment(otherLabel, rest, palette.chartRest))
        if (cashEur > 0.0) add(DonutSegment(cashLabel, cashEur, palette.chartCash))
    }
}
