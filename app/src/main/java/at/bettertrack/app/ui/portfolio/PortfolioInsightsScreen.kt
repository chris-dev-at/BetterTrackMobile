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
import androidx.compose.ui.res.pluralStringResource
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
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizChart
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizDarstellungRow
import at.bettertrack.app.ui.charts.viz.BtVizFamily
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizFormat
import at.bettertrack.app.ui.charts.viz.BtVizSelectedDetail
import at.bettertrack.app.ui.charts.viz.BtVizSheet
import at.bettertrack.app.ui.charts.viz.VizDatum
import at.bettertrack.app.ui.charts.viz.VizRole
import at.bettertrack.app.ui.charts.viz.rememberVizItems
import at.bettertrack.app.ui.charts.viz.vizConfigDecode
import at.bettertrack.app.ui.charts.viz.vizConfigEncode
import at.bettertrack.app.ui.charts.viz.vizFill
import at.bettertrack.app.ui.charts.viz.vizFormHasOwnRows
import at.bettertrack.app.ui.charts.viz.vizResolveForm
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.market.rememberAssetTypeLabeller
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
                Spacer(Modifier.height(10.dp))
                MoversSummary(holdings = holdings, locale = locale)
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
 * batch from the overview to this page; re-shaped by the round-5 chart study,
 * owner ask 2026-08-17 — *"für die ganzen charts … suche alternative
 * möglichkeiten die sachen anzuzeigen"*).
 *
 * ## The donut stopped being the answer to both questions
 *
 * This section used to draw one shape — a donut — for two genuinely different
 * questions. The study measured that and it does not hold up: for six asset
 * classes a donut is merely unexciting, but for a nineteen-position portfolio
 * it is *wrong*, because fourteen of nineteen marks end up anonymous slivers
 * and the legend that rescues them grows larger than the chart it explains.
 *
 * So the shape now follows the question. `Automatisch` draws a **treemap** for
 * asset classes, where concentration is the answer, and **ranked bars** for
 * positions, where the answer is a name and a number on one line. The donut is
 * still here — a user who prefers it keeps it — it has simply lost the job of
 * being the default for a question it answers badly.
 *
 * ## What is a display choice and what is not
 *
 * Everything this section does is presentation. Values arrive server-computed;
 * the forms rearrange space. The single number this file derives is the
 * denominator for printed shares, and it is derived from exactly the slices on
 * screen — which is why hiding cash *says* it changed the basis rather than
 * quietly re-scaling every percentage.
 */
@Composable
internal fun AllocationSummary(holdings: List<HoldingEntity>, cashEur: Double, locale: Locale) {
    val bt = BtTheme.colors
    var byCategory by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    // By class vs by position is not a filter, it is a different data family —
    // and the study is explicit that a form preference belongs to a family. So
    // the two groupings remember their shapes independently: choosing a treemap
    // for asset classes must not turn the long tail of positions into one.
    val family = if (byCategory) BtVizFamily.ALLOCATION_CLASS else BtVizFamily.ALLOCATION_POSITION
    val canvas = BtVizCanvas.APP_FULL

    val configs by AppGraph.vizPrefs.configs.collectAsStateWithLifecycle()
    val config = remember(configs, family) { vizConfigDecode(configs[family.name]) }
    val resolved = vizResolveForm(config, family, canvas)
    val saveConfig: (BtVizConfig) -> Unit = { next ->
        AppGraph.vizPrefs.setConfig(family.name, vizConfigEncode(next))
    }

    val cashLabel = stringResource(R.string.bt_overview_alloc_cash)
    // R3 §3: the category names are resolved HERE because `allocationData` is a
    // pure function and `assetTypeLabel` is a composable. `assetTypeLabel` is the
    // app's real, localized mapping for exactly these server type strings.
    val categoryLabels = rememberAssetTypeLabeller()
    val raw = remember(holdings, cashEur, byCategory, cashLabel, categoryLabels) {
        allocationData(holdings, cashEur, byCategory, cashLabel, categoryLabels)
    }
    val items = rememberVizItems(
        raw = raw,
        form = resolved,
        canvas = canvas,
        config = config,
        categories = byCategory,
    )
    // The denominator is the slices actually drawn — see the KDoc above.
    val total = items.sumOf { it.value }
    if (raw.isEmpty()) return

    val format = rememberAllocationFormat(locale, total)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_overview_allocation_section),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!config.showCash) {
                // The scope label is not decoration: every percentage below is a
                // fraction of a total that no longer contains cash, and a reader
                // who missed the toggle would otherwise read a changed basis as
                // changed data.
                Text(
                    text = stringResource(R.string.bt_viz_cash_excluded),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // By-asset/by-category is an exclusive choice, and this app says
        // exclusive choice with [BtSegmented] (owner order 2026-08-10).
        BtSegmented(
            options = ALLOCATION_GROUPINGS,
            selected = if (byCategory) AllocationGrouping.CATEGORY else AllocationGrouping.ASSET,
            label = { stringResource(allocationGroupingLabel(it)) },
            onSelect = {
                byCategory = it == AllocationGrouping.CATEGORY
                selectedKey = null
            },
            modifier = Modifier.fillMaxWidth(),
            equalWidths = true,
        )

        BtVizDarstellungRow(
            config = config,
            resolved = resolved,
            onClick = { sheetOpen = true },
        )
        Spacer(Modifier.height(6.dp))

        BtVizChart(
            items = items,
            form = resolved,
            canvas = canvas,
            format = format,
            emptyText = stringResource(R.string.bt_viz_empty_allocation),
            labels = config.labels,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it },
        )

        val selected = items.firstOrNull { it.key == selectedKey }
        if (selected != null) {
            Spacer(Modifier.height(10.dp))
            BtVizSelectedDetail(
                label = selected.label,
                value = "${format.amount(selected.value)} · " +
                    format.share(if (total > 0.0) selected.value / total else 0.0),
                onClear = { selectedKey = null },
            )
        }

        // Ranked bars already print name and amount on one line; adding the
        // legend under them would state every value twice.
        if (!vizFormHasOwnRows(resolved)) {
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items.forEach { datum ->
                    AllocationLegendRow(
                        datum = datum,
                        total = total,
                        locale = locale,
                        selected = datum.key == selectedKey,
                        onClick = {
                            selectedKey = if (datum.key == selectedKey) null else datum.key
                        },
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        BtVizSheet(
            family = family,
            canvas = canvas,
            config = config,
            rawItems = raw,
            format = format,
            signed = false,
            categories = byCategory,
            emptyText = stringResource(R.string.bt_viz_empty_allocation),
            onConfig = saveConfig,
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Money and share formatting for this card, in the app's de-AT convention and discreet-aware. */
@Composable
private fun rememberAllocationFormat(locale: Locale, total: Double): BtVizFormat {
    val dash = stringResource(R.string.bt_value_dash)
    val masking = BtDiscreetMode.masking
    return remember(locale, total, dash, masking) {
        BtVizFormat(
            amount = { value -> formatEur(value, locale) },
            share = { fraction -> if (total > 0.0) formatWeight(fraction * 100.0, locale) else dash },
        )
    }
}

/** The two ways the allocation section can group a portfolio. */
private enum class AllocationGrouping { ASSET, CATEGORY }

private val ALLOCATION_GROUPINGS = listOf(AllocationGrouping.ASSET, AllocationGrouping.CATEGORY)

private fun allocationGroupingLabel(grouping: AllocationGrouping): Int = when (grouping) {
    AllocationGrouping.ASSET -> R.string.bt_overview_alloc_by_asset
    AllocationGrouping.CATEGORY -> R.string.bt_overview_alloc_by_category
}

/**
 * One legend line: swatch, weight, name — the "attached row" every part-to-whole
 * form in the study relies on for exact values.
 *
 * **The number leads.** The figures sit in a fixed leading column, aligned to
 * the digit — they are the thing the eye scans a ranked list for, and nothing
 * sits at the right edge for a FAB to cover (S6 P1-7's lesson, kept).
 *
 * The row is now also a selection target. That is not a convenience: several of
 * the new forms have marks too small to tap accurately (a 2 % treemap tile, one
 * waffle cell), and the study requires every mark to be reachable in the same
 * visible order TalkBack uses. The row is that order.
 */
@Composable
private fun AllocationLegendRow(
    datum: VizDatum,
    total: Double,
    locale: Locale,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BtShapes.cardSmall)
            .then(if (selected) Modifier.background(bt.goldWash) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(vizFill(datum, signed = false), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text = weightPct(datum.value, total)?.let { formatWeight(it, locale) }
                ?: stringResource(R.string.bt_value_dash),
            style = BtTheme.type.numberCaption,
            color = bt.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(ALLOCATION_WEIGHT_COLUMN),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = datum.label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatEur(datum.value, locale),
            style = BtTheme.type.numberCaption,
            color = bt.textMuted,
            maxLines = 1,
        )
    }
}

/**
 * The width the weights column reserves. Wide enough for "100,0 %" at the
 * largest font scale the app supports without the names starting at a different
 * x on one row than on the next.
 */
private val ALLOCATION_WEIGHT_COLUMN = 58.dp

// ── Display mapping (proportions of server values only) ───────────────────────

/**
 * Build the chart series from server values.
 *
 * Two things this deliberately does NOT do any more, both of which the old
 * `allocationSegments` did:
 *
 *  - **It does not cap the list at the palette length.** Folding everything past
 *    slot ten into "Other" was a *colour* constraint masquerading as a data
 *    decision. Reduction is now a canvas-and-form question answered by
 *    [rememberVizItems], which knows how many marks the chosen shape can label;
 *    a ranked list on the full card can honestly show all nineteen.
 *  - **It does not assign colours.** Colour follows a stable rank
 *    ([withStableColorIndices]) so a holding keeps its hue across refreshes
 *    instead of changing identity when two neighbours swap places.
 *
 * Cash keeps its own role rather than being one more category, which is what
 * lets it stay out of every `Andere` bucket and keep its fixed neutral.
 */
private fun allocationData(
    holdings: List<HoldingEntity>,
    cashEur: Double,
    byCategory: Boolean,
    cashLabel: String,
    categoryLabel: (String) -> String,
): List<VizDatum> {
    val parts: List<VizDatum> = if (byCategory) {
        holdings
            .groupBy { it.assetType }
            .map { (type, rows) ->
                VizDatum(
                    key = "type:$type",
                    label = categoryLabel(type),
                    value = rows.sumOf { it.marketValueEur ?: 0.0 },
                    hiddenCount = 1,
                )
            }
    } else {
        holdings.map {
            VizDatum(
                key = "sym:${it.assetSymbol}",
                label = it.assetSymbol,
                value = it.marketValueEur ?: 0.0,
                hiddenCount = 1,
            )
        }
    }
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }

    if (cashEur <= 0.0) return parts
    return parts + VizDatum(
        key = "cash",
        label = cashLabel,
        value = cashEur,
        role = VizRole.Cash,
        hiddenCount = 1,
    )
}

// ── Winners & losers (round-5 study, set D) ──────────────────────────────────

/**
 * `Gewinner & Verlierer · Heute` — the second insight module, and the study's
 * one **signed** data family.
 *
 * ## Why this lives here and not on Home
 *
 * Home already shows today's movers, as a horizontal strip of cards. That strip
 * is deliberately shaped: movers are the second thing on that screen and were
 * given a row precisely so they could not cost a screen-third of height. A
 * row-per-holding dot plot is the opposite trade, so putting it on Home would
 * quietly undo a decision that was made on purpose.
 *
 * The insights page was built to grow additional modules, and a page whose whole
 * job is analysis is where a reader has both the room and the intent for
 * "how did each position contribute today, on one shared axis". The two
 * surfaces answer different questions and both get to keep their shape.
 *
 * ## Why part-to-whole forms are not offered
 *
 * A share of a whole cannot express a direction. Split a day into a treemap and
 * a −143 € loss becomes an *area*, indistinguishable from a gain of the same
 * size — so the picker for this family contains exactly two forms, both signed.
 */
@Composable
private fun MoversSummary(holdings: List<HoldingEntity>, locale: Locale) {
    val bt = BtTheme.colors
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    val family = BtVizFamily.MOVERS
    val canvas = BtVizCanvas.APP_FULL
    val configs by AppGraph.vizPrefs.configs.collectAsStateWithLifecycle()
    val config = remember(configs) { vizConfigDecode(configs[family.name]) }
    val resolved = vizResolveForm(config, family, canvas)

    val raw = remember(holdings) { moversData(holdings) }
    if (raw.isEmpty()) return
    val items = rememberVizItems(
        raw = raw,
        form = resolved,
        canvas = canvas,
        config = config,
        categories = false,
    )
    val format = remember(locale) {
        // Signed throughout: the sign is the message, and it is printed rather
        // than left to colour alone.
        BtVizFormat(
            amount = { value -> formatEur(value, locale, showSign = true) },
            share = { fraction -> formatWeight(fraction * 100.0, locale) },
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_viz_movers_section),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.bt_viz_today),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
        }
        Text(
            text = stringResource(R.string.bt_viz_movers_title),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )

        BtVizDarstellungRow(
            config = config,
            resolved = resolved,
            onClick = { sheetOpen = true },
        )
        Spacer(Modifier.height(6.dp))

        BtVizChart(
            items = items,
            form = resolved,
            canvas = canvas,
            format = format,
            emptyText = stringResource(R.string.bt_viz_empty_movers),
            labels = config.labels,
            signed = true,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it },
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Two counts, two nouns, so two plural resources joined by a
                // separator string — a single "%d winners · %d losers" cannot
                // inflect either noun, and the house guard rightly refuses it.
                text = stringResource(
                    R.string.bt_viz_movers_counts,
                    pluralStringResource(
                        R.plurals.bt_viz_movers_winners,
                        raw.count { it.value > 0.0 },
                        raw.count { it.value > 0.0 },
                    ),
                    pluralStringResource(
                        R.plurals.bt_viz_movers_losers,
                        raw.count { it.value < 0.0 },
                        raw.count { it.value < 0.0 },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (resolved == BtVizForm.DOT_PLOT) {
                // Name the axis. A diverging plot whose zero line is not
                // announced invites the reader to treat the left edge as zero.
                Text(
                    text = stringResource(R.string.bt_viz_zero_axis),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }

        val selected = items.firstOrNull { it.key == selectedKey }
        if (selected != null) {
            Spacer(Modifier.height(10.dp))
            BtVizSelectedDetail(
                label = selected.label,
                value = format.amount(selected.value),
                onClear = { selectedKey = null },
            )
        }
    }

    if (sheetOpen) {
        BtVizSheet(
            family = family,
            canvas = canvas,
            config = config,
            rawItems = raw,
            format = format,
            signed = true,
            categories = false,
            emptyText = stringResource(R.string.bt_viz_empty_movers),
            onConfig = { AppGraph.vizPrefs.setConfig(family.name, vizConfigEncode(it)) },
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * Today's signed contributions, largest gain first.
 *
 * `dayChangeEur` is **server-computed** — this does not derive a euro move from
 * a percentage and a market value, which would be the app calculating money.
 * Rows that moved exactly nothing are dropped: a dot sitting on the axis adds a
 * line without adding an answer.
 */
private fun moversData(holdings: List<HoldingEntity>): List<VizDatum> = holdings
    .filter { (it.dayChangeEur ?: 0.0) != 0.0 }
    .groupBy { it.assetSymbol }
    .map { (symbol, rows) ->
        VizDatum(
            key = "mv:$symbol",
            label = symbol,
            // One asset held in several portfolios contributed the sum of its
            // rows to today; showing them separately would double-count the day.
            value = rows.sumOf { it.dayChangeEur ?: 0.0 },
            hiddenCount = 1,
        )
    }
    .sortedByDescending { it.value }
