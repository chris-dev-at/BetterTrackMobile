package at.bettertrack.app.ui.insights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.market.rememberAssetTypeLabeller
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The **Insights Studio**: a configurable feed of insight cards, a per-card
 * configurator, and two ways out — a composed A4 report and a single shared
 * image.
 *
 * Grew out of the portfolio's insights subpage (owner UI batch 2026-08-16),
 * which shipped with two hard-coded sections. The owner's follow-up was
 * explicit: *"give me more insights and ways to export a report … i can setup
 * each insight how i want and then export the insights i want into a nice
 * looking pdf or share a graphic quick as picture"*. So the page keeps its route
 * and its portfolio seed, and gains a catalog, a layout, and export.
 *
 * ## Why the scope lives at the top and not in every card
 *
 * Twelve cards each carrying their own portfolio picker would ask the same
 * question twelve times. The page owns one frame — portfolios and period — and
 * a card may override it where that is meaningful. The frame is also what the
 * report freezes, which is why it is a page-level control rather than a setting.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InsightsStudioScreen(
    portfolioId: String,
    onBack: () -> Unit,
    onOpenReport: (String?) -> Unit,
) {
    val vm: InsightsStudioViewModel = viewModel(key = "insights-studio-$portfolioId") {
        InsightsStudioViewModel(
            repo = AppGraph.portfolioRepository,
            cashRepo = AppGraph.cashClassificationRepository,
            taxRepo = AppGraph.taxRepository,
            marketRepo = AppGraph.marketRepository,
            prefs = AppGraph.insightsPrefs,
            vizPrefs = AppGraph.vizPrefs,
            seedPortfolioId = portfolioId,
        )
    }
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()

    val page by vm.page.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val familyConfigs by vm.familyConfigs.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val scoped by vm.scopedPortfolios.collectAsStateWithLifecycle()
    val allPortfolios by vm.allPortfolios.collectAsStateWithLifecycle()
    val scopeIds by vm.scopeIds.collectAsStateWithLifecycle()
    val framePeriod by vm.framePeriod.collectAsStateWithLifecycle()
    val dataAsOfMs by vm.dataAsOfMs.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf(false) }
    var catalogOpen by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf<BtInsight?>(null) }
    var sharing by remember { mutableStateOf<BtInsight?>(null) }
    var scopeSheet by remember { mutableStateOf(false) }
    var periodSheet by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    // The one place the twelve builders are called. Resolved with the localized
    // asset-class names, which only a composable can supply.
    val assetTypeLabel = rememberAssetTypeLabeller()
    val cashLabel = stringResource(R.string.bt_overview_alloc_cash)
    val dash = stringResource(R.string.bt_value_dash)
    // `2026-08` -> `Aug.` in the reader's language.
    val monthLabel: (String) -> String = remember(locale) {
        { key ->
            runCatching {
                java.time.YearMonth.parse(key).month
                    .getDisplayName(java.time.format.TextStyle.SHORT, locale)
            }.getOrDefault(key)
        }
    }
    val today = remember { LocalDate.now() }
    val resolvedSource = remember(source, assetTypeLabel, cashLabel, dash) {
        source.copy(
            assetTypeLabel = assetTypeLabel,
            cashLabel = cashLabel,
            unnamedLabel = dash,
            monthLabel = monthLabel,
        )
    }
    val snapshots = remember(resolvedSource, cards, page, framePeriod, today) {
        page.visible.associateWith { insight ->
            buildInsightSnapshot(
                insight = insight,
                config = cards[insight] ?: BtInsightConfig.PRISTINE,
                source = resolvedSource,
                window = vm.windowFor(insight, today),
            )
        }
    }

    val scopeLabel = when {
        scopeIds.isEmpty() || scopeIds.size == allPortfolios.size ->
            stringResource(R.string.bt_insight_scope_all)
        scopeIds.size == 1 -> scoped.firstOrNull()?.name.orEmpty()
        else -> scoped.joinToString(", ") { it.name }
    }

    Scaffold(
        containerColor = bt.bg,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_insight_studio_title),
                subtitle = scopeLabel,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = if (editing) ({ editing = false }) else onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                // ONE top-bar action, per the house directive the tab bar
                // enforces: creating a report is the page's export verb, and
                // `Insights anpassen` is a page-editing mode that belongs with
                // the other page-editing affordance at the foot of the list.
                action = {
                    IconButton(onClick = { onOpenReport(null) }) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = stringResource(R.string.bt_insight_report_create),
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
            FrameChips(
                scopeLabel = scopeLabel,
                periodLabel = stringResource(insightPeriodRes(framePeriod.kind)),
                dataAsOf = stringResource(
                    R.string.bt_insight_data_as_of,
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                        .withLocale(locale)
                        .format(Instant.ofEpochMilli(dataAsOfMs).atZone(ZoneId.systemDefault())),
                ),
                onScope = { scopeSheet = true },
                onPeriod = { periodSheet = true },
            )

            if (editing) {
                InsightsEditList(
                    page = page,
                    onMove = vm::moveInsight,
                    onHide = vm::hideInsight,
                    onShow = vm::showInsight,
                    onAdd = { catalogOpen = true },
                    onRestore = { confirmRestore = true },
                )
            } else {
                page.visible.forEach { insight ->
                    val snapshot = snapshots[insight] ?: return@forEach
                    InsightCard(
                        snapshot = snapshot,
                        config = cards[insight] ?: BtInsightConfig.PRISTINE,
                        family = insight.spec.family
                            ?.let { familyConfigs[it.name] }
                            ?: at.bettertrack.app.ui.charts.viz.BtVizConfig(),
                        compact = false,
                        onConfigure = { configuring = insight },
                        onShare = { sharing = insight },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtSecondaryButton(
                        text = stringResource(R.string.bt_insight_add),
                        onClick = { catalogOpen = true },
                        modifier = Modifier.weight(1f),
                    )
                    BtSecondaryButton(
                        text = stringResource(R.string.bt_insight_customize),
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Sheets ──────────────────────────────────────────────────────────────

    if (catalogOpen) {
        InsightsCatalogSheet(
            page = page,
            onAdd = {
                vm.showInsight(it)
                catalogOpen = false
            },
            onDismiss = { catalogOpen = false },
        )
    }

    configuring?.let { insight ->
        val snapshot = snapshots[insight]
        if (snapshot == null) {
            configuring = null
        } else {
            InsightConfigSheet(
                insight = insight,
                config = cards[insight] ?: BtInsightConfig.PRISTINE,
                family = insight.spec.family
                    ?.let { familyConfigs[it.name] }
                    ?: at.bettertrack.app.ui.charts.viz.BtVizConfig(),
                snapshot = snapshot,
                portfolioNames = allPortfolios.associate { it.id to it.name },
                onApply = { vm.setCardConfig(insight, it) },
                onDismiss = { configuring = null },
            )
        }
    }

    sharing?.let { insight ->
        val snapshot = snapshots[insight]
        if (snapshot == null) {
            sharing = null
        } else {
            InsightShareSheet(
                insight = insight,
                snapshot = snapshot,
                config = cards[insight] ?: BtInsightConfig.PRISTINE,
                family = insight.spec.family
                    ?.let { familyConfigs[it.name] }
                    ?: at.bettertrack.app.ui.charts.viz.BtVizConfig(),
                scopeLabel = scopeLabel,
                onExportPdf = {
                    sharing = null
                    onOpenReport(insight.name)
                },
                onDismiss = { sharing = null },
            )
        }
    }

    if (scopeSheet) {
        InsightOptionSheet(
            title = stringResource(R.string.bt_insight_scope),
            options = listOf<String?>(null) + allPortfolios.map { it.id },
            label = { id ->
                id?.let { key -> allPortfolios.firstOrNull { it.id == key }?.name ?: key }
                    ?: stringResource(R.string.bt_insight_scope_all)
            },
            selected = scopeIds.singleOrNull(),
            onSelect = { id ->
                vm.setScope(if (id == null) emptySet() else setOf(id))
                scopeSheet = false
            },
            onDismiss = { scopeSheet = false },
        )
    }

    if (periodSheet) {
        InsightOptionSheet(
            title = stringResource(R.string.bt_insight_period),
            options = listOf(
                BtInsightPeriodKind.ONE_MONTH,
                BtInsightPeriodKind.SIX_MONTHS,
                BtInsightPeriodKind.ONE_YEAR,
                BtInsightPeriodKind.MAX,
                BtInsightPeriodKind.CALENDAR_YEAR,
            ),
            label = { stringResource(insightPeriodRes(it)) },
            selected = framePeriod.kind,
            onSelect = { kind ->
                vm.setFramePeriod(
                    BtInsightPeriod(
                        kind = kind,
                        year = if (kind == BtInsightPeriodKind.CALENDAR_YEAR) today.year else 0,
                    ),
                )
                periodSheet = false
            },
            onDismiss = { periodSheet = false },
        )
    }

    if (confirmRestore) {
        BtPickerSheet(
            title = stringResource(R.string.bt_insight_restore_confirm_title),
            onDismiss = { confirmRestore = false },
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtSecondaryButton(
                        text = stringResource(R.string.bt_insight_report_cancel),
                        onClick = { confirmRestore = false },
                        modifier = Modifier.weight(1f),
                    )
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_insight_restore),
                        onClick = {
                            vm.restoreDefaultPage()
                            confirmRestore = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            },
        ) {
            Text(
                text = stringResource(R.string.bt_insight_restore_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
        }
    }
}

/** The page frame, as three chips. Both configurable ones open bottom sheets. */
@Composable
private fun FrameChips(
    scopeLabel: String,
    periodLabel: String,
    dataAsOf: String,
    onScope: () -> Unit,
    onPeriod: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtChip(text = scopeLabel, selected = true, onClick = onScope)
        BtChip(text = periodLabel, onClick = onPeriod)
        // Not a control: the data timestamp is a fact about what is on screen,
        // and making it tappable would imply it could be changed.
        Text(
            text = dataAsOf,
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
    }
}

/**
 * `Insights anpassen` — the full-page edit mode.
 *
 * Reorder uses discrete up/down buttons rather than only a drag handle. That is
 * not a fallback: TalkBack needs discrete actions, and a 48 dp button is also
 * the more reliable target on a list this short. The switch hides a card; it
 * never deletes its configuration, which is why the hidden section can restore
 * one instantly.
 */
@Composable
private fun InsightsEditList(
    page: BtInsightsPage,
    onMove: (BtInsight, Int) -> Unit,
    onHide: (BtInsight) -> Unit,
    onShow: (BtInsight) -> Unit,
    onAdd: () -> Unit,
    onRestore: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.bt_insight_reorder),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        page.visible.forEachIndexed { index, insight ->
            val name = stringResource(insightNameRes(insight))
            BtCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(insightGroupRes(insight.spec.group)),
                            style = MaterialTheme.typography.labelSmall,
                            color = bt.textMuted,
                        )
                    }
                    IconButton(
                        onClick = { onMove(insight, -1) },
                        enabled = index > 0,
                    ) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = stringResource(R.string.bt_insight_move_up, name),
                            tint = if (index > 0) bt.textSecondary else bt.textFaint,
                        )
                    }
                    IconButton(
                        onClick = { onMove(insight, 1) },
                        enabled = index < page.visible.lastIndex,
                    ) {
                        Icon(
                            Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(R.string.bt_insight_move_down, name),
                            tint = if (index < page.visible.lastIndex) bt.textSecondary else bt.textFaint,
                        )
                    }
                    Switch(
                        checked = true,
                        onCheckedChange = { onHide(insight) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = bt.onGold,
                            checkedTrackColor = bt.gold,
                            uncheckedThumbColor = bt.textMuted,
                            uncheckedTrackColor = bt.surfaceQuiet,
                        ),
                    )
                }
            }
        }

        val hidden = page.hidden
        if (hidden.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.bt_insight_hidden_section),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
            hidden.forEach { insight ->
                BtCard(quiet = true, onClick = { onShow(insight) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(insightNameRes(insight)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = bt.textSecondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.bt_insight_show),
                            style = MaterialTheme.typography.labelMedium,
                            color = bt.goldEmphasis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        BtSecondaryButton(
            text = stringResource(R.string.bt_insight_add),
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
        )
        BtSecondaryButton(
            text = stringResource(R.string.bt_insight_restore_default),
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * `Insight-Katalog` — the ranked catalog, grouped, with the default five marked.
 *
 * Rank order, not alphabetical: the study ranked these by what a reader can act
 * on, and sorting them by name would bury `Portfolioentwicklung` under
 * `Anlageklassen` for no reason a user benefits from.
 */
@Composable
fun InsightsCatalogSheet(
    page: BtInsightsPage,
    onAdd: (BtInsight) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    BtPickerSheet(
        title = stringResource(R.string.bt_insight_catalog_title),
        subtitle = androidx.compose.ui.res.pluralStringResource(
            R.plurals.bt_insight_catalog_count,
            BT_INSIGHTS_RANKED.size,
            BT_INSIGHTS_RANKED.size,
        ),
        onDismiss = onDismiss,
    ) {
        BtInsightGroup.entries.forEach { group ->
            val rows = BT_INSIGHTS_RANKED.filter { it.spec.group == group }
            if (rows.isEmpty()) return@forEach
            Text(
                text = stringResource(insightGroupRes(group)),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            rows.forEach { insight ->
                val onPage = insight in page.visible
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = !onPage) { onAdd(insight) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(insightNameRes(insight)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (onPage) bt.textMuted else bt.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            BtBadge(
                                text = stringResource(
                                    if (insight.spec.defaultOn) {
                                        R.string.bt_insight_badge_default
                                    } else {
                                        R.string.bt_insight_badge_optional
                                    },
                                ),
                                kind = if (insight.spec.defaultOn) BtBadgeKind.Gold else BtBadgeKind.Neutral,
                            )
                        }
                        Text(
                            text = stringResource(insightQuestionRes(insight)),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (onPage) {
                        Text(
                            text = stringResource(R.string.bt_insight_already_on_page),
                            style = MaterialTheme.typography.labelSmall,
                            color = bt.textFaint,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.bt_insight_add),
                            tint = bt.goldEmphasis,
                        )
                    }
                }
            }
        }
    }
}
