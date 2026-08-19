package at.bettertrack.app.ui.insights

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.format.btFormatMoneyExport
import at.bettertrack.app.ui.market.rememberAssetTypeLabeller
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `Bericht erstellen` — choose a frame, check the insights, review, export.
 *
 * ## Why the frame sits above the checklist
 *
 * Period and portfolios apply to the whole report, and a card that cannot be
 * rendered in the chosen frame is unchecked **out loud** the moment the frame
 * changes. Putting the frame below the list would let a user curate ten sections
 * and then silently lose one. The study is explicit: a frame change "never
 * exports an empty page silently".
 *
 * ## Why the page count is promised before it is produced
 *
 * `reportPageCount` is the single source of the estimate in the footer, the
 * count on the review screen, and the `Seite x von y` in the finished PDF's
 * footer. One function, three readers — so the number the user agreed to is the
 * number they get.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InsightsReportScreen(
    portfolioId: String,
    preselect: String?,
    onBack: () -> Unit,
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
    val context = LocalContext.current
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

    val today = remember { LocalDate.now() }
    val frameWindow = remember(framePeriod, today) { insightWindow(framePeriod, today) }
    val isCalendarYear = remember(frameWindow) { windowIsCalendarYear(frameWindow) }
    val frame = remember(framePeriod, scopeIds, isCalendarYear) {
        BtReportFrame(framePeriod, scopeIds, isCalendarYear)
    }

    var selected by remember(preselect) {
        mutableStateOf(
            preselect
                ?.let { name -> BtInsight.entries.firstOrNull { it.name == name } }
                ?.let { listOf(it) }
                ?: reportRecommendedSelection(BtReportFrame(BtInsightPeriod.ONE_YEAR, emptySet(), false)),
        )
    }
    var deselectedCount by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(ReportStep.Choose) }
    var phase by remember { mutableStateOf<ReportPhase>(ReportPhase.Idle) }
    var periodSheet by remember { mutableStateOf(false) }
    var scopeSheet by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf<BtInsight?>(null) }
    // Progress lives OUTSIDE `phase`: the render effect is keyed on `phase`, so
    // writing a Rendering(page) into it would cancel the very coroutine doing
    // the rendering. Observed on device 2026-08-18 — the PDF was written but the
    // sheet stayed on "Seite 0 von 8 wird erstellt" forever.
    var renderedPage by remember { mutableIntStateOf(0) }
    var renderTotal by remember { mutableIntStateOf(0) }

    // A frame change unchecks what it cannot render, and says how many.
    LaunchedEffect(frame) {
        val change = reportReconcileSelection(selected, frame)
        if (change.removedCount > 0) {
            selected = change.selected
            deselectedCount = change.removedCount
        }
    }

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
    val resolvedSource = remember(source, assetTypeLabel, cashLabel, dash) {
        source.copy(
            assetTypeLabel = assetTypeLabel,
            cashLabel = cashLabel,
            unnamedLabel = dash,
            monthLabel = monthLabel,
        )
    }

    val ordered = remember(selected, page) { reportOrderSelection(selected, page) }
    val sections = remember(ordered) { reportSections(ordered) }
    val pageCount = reportPageCount(ordered.size)

    val scopeLabel = when {
        scopeIds.isEmpty() || scopeIds.size == allPortfolios.size ->
            stringResource(R.string.bt_insight_scope_all)
        else -> scoped.joinToString(", ") { it.name }
    }

    Scaffold(
        containerColor = bt.bg,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BtCollapsingHeader(
                title = stringResource(
                    if (step == ReportStep.Choose) {
                        R.string.bt_insight_report_create
                    } else {
                        R.string.bt_insight_report_review
                    },
                ),
                subtitle = insightFormatRange(frameWindow.fromEpochDay, frameWindow.toEpochDay, locale),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (step == ReportStep.Review) step = ReportStep.Choose else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                action = {
                    if (step == ReportStep.Choose) {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                selected = if (selected.size == BT_INSIGHTS_RANKED.count {
                                        insightAcceptsCalendarYear(it, isCalendarYear)
                                    }
                                ) {
                                    emptyList()
                                } else {
                                    BT_INSIGHTS_RANKED.filter {
                                        insightAcceptsCalendarYear(it, isCalendarYear)
                                    }
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(
                                    if (selected.isEmpty()) {
                                        R.string.bt_insight_report_select_all
                                    } else {
                                        R.string.bt_insight_report_clear
                                    },
                                ),
                                color = bt.goldEmphasis,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            ReportFooter(
                step = step,
                selectedCount = ordered.size,
                pageCount = pageCount,
                estimateBytes = reportEstimateBytes(ordered.size),
                phase = phase,
                locale = locale,
                onReview = { step = ReportStep.Review },
                onCreate = { phase = ReportPhase.Preparing },
                onShare = { file -> sharePdf(context, file) },
                onDone = onBack,
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
            when (step) {
                ReportStep.Choose -> {
                    ReportFrameCard(
                        periodLabel = insightFormatRange(
                            frameWindow.fromEpochDay,
                            frameWindow.toEpochDay,
                            locale,
                        ),
                        scopeLabel = scopeLabel,
                        dataAsOf = DateTimeFormatter
                            .ofLocalizedDateTime(FormatStyle.SHORT)
                            .withLocale(locale)
                            .format(Instant.ofEpochMilli(dataAsOfMs).atZone(ZoneId.systemDefault())),
                        onPeriod = { periodSheet = true },
                        onScope = { scopeSheet = true },
                    )

                    if (deselectedCount > 0) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.bt_insight_deselected,
                                deselectedCount,
                                deselectedCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.goldEmphasis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BtShapes.cardSmall)
                                .background(bt.goldWash)
                                .padding(10.dp),
                        )
                    }

                    Text(
                        text = stringResource(R.string.bt_insight_report_choose),
                        style = MaterialTheme.typography.titleMedium,
                        color = bt.textPrimary,
                    )

                    if (ordered.isEmpty()) {
                        ReportEmptySelection(
                            onRecommended = { selected = reportRecommendedSelection(frame) },
                        )
                    }

                    BtInsightGroup.entries.forEach { group ->
                        val rows = BT_INSIGHTS_RANKED.filter { it.spec.group == group }
                        if (rows.isEmpty()) return@forEach
                        Text(
                            text = stringResource(insightGroupRes(group)),
                            style = MaterialTheme.typography.labelMedium,
                            color = bt.textMuted,
                        )
                        rows.forEach { insight ->
                            val available = insightAcceptsCalendarYear(insight, isCalendarYear)
                            ReportChecklistRow(
                                insight = insight,
                                checked = insight in selected,
                                available = available,
                                styleLine = reportStyleLine(
                                    insight = insight,
                                    config = cards[insight] ?: BtInsightConfig.PRISTINE,
                                    family = insight.spec.family?.let { familyConfigs[it.name] }
                                        ?: BtVizConfig(),
                                ),
                                onToggle = {
                                    selected = if (insight in selected) {
                                        selected - insight
                                    } else {
                                        selected + insight
                                    }
                                    deselectedCount = 0
                                },
                                onCustomize = { configuring = insight },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.bt_insight_report_today_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BtShapes.cardSmall)
                            .background(bt.surfaceQuiet)
                            .padding(10.dp),
                    )
                }

                ReportStep.Review -> ReportReview(
                    sections = sections,
                    pageCount = pageCount,
                    rendering = phase is ReportPhase.Preparing,
                    renderedPage = renderedPage,
                    renderTotal = renderTotal,
                    locale = locale,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
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

    configuring?.let { insight ->
        // `Anpassen` opens the SAME configurator the card uses, against the
        // report frame. It edits the card's saved settings — the study is
        // explicit that the builder must not create a second style preference.
        val snapshot = remember(insight, resolvedSource, frameWindow) {
            buildInsightSnapshot(
                insight = insight,
                config = insightForReport(
                    insight,
                    cards[insight] ?: BtInsightConfig.PRISTINE,
                    framePeriod,
                    scopeIds,
                ),
                source = resolvedSource,
                window = frameWindow,
            )
        }
        InsightConfigSheet(
            insight = insight,
            config = cards[insight] ?: BtInsightConfig.PRISTINE,
            family = insight.spec.family?.let { familyConfigs[it.name] } ?: BtVizConfig(),
            snapshot = snapshot,
            portfolioNames = allPortfolios.associate { it.id to it.name },
            onApply = { vm.setCardConfig(insight, it) },
            onDismiss = { configuring = null },
        )
    }

    // ── Rendering ───────────────────────────────────────────────────────────
    val palette = remember { insightsPdfPalette() }
    val resources = context.resources
    val brandName = stringResource(R.string.app_name)
    LaunchedEffect(phase) {
        if (phase !is ReportPhase.Preparing) return@LaunchedEffect
        renderedPage = 0
        val doc = withContext(Dispatchers.Default) {
            buildInsightsReportDoc(
                sections = sections,
                cards = cards,
                familyConfigs = familyConfigs,
                source = resolvedSource,
                framePeriod = framePeriod,
                scopeIds = scopeIds,
                frameWindow = frameWindow,
                scopeLabel = scopeLabel,
                palette = palette,
                resources = resources,
                locale = locale,
                brand = brandName,
                createdAtMs = System.currentTimeMillis(),
                dataAsOfMs = dataAsOfMs,
            )
        }
        renderTotal = doc.totalPages
        phase = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, INSIGHT_EXPORT_DIR).apply { mkdirs() }
                val target = File(
                    dir,
                    insightReportFileName(
                        scope = scopeLabel,
                        fromIso = insightIsoDate(frameWindow.fromEpochDay),
                        toIso = insightIsoDate(frameWindow.toEpochDay),
                        joiner = resources.getString(R.string.bt_insight_range_joiner),
                    ),
                )
                writeInsightsPdf(target, doc) { current, _ -> renderedPage = current }
                ReportPhase.Ready(target, target.length(), doc.totalPages) as ReportPhase
            }.getOrElse { error ->
                // Storage-full is the one failure worth distinguishing: it is
                // the only one the user can act on, and a partial file must not
                // be left behind claiming to be a report.
                val message = error.message.orEmpty()
                if (message.contains("ENOSPC") || message.contains("space", ignoreCase = true)) {
                    ReportPhase.Failed(R.string.bt_insight_no_space_title)
                } else {
                    ReportPhase.Failed(R.string.bt_insight_report_failed)
                }
            }
        }
    }
}

private enum class ReportStep { Choose, Review }

private sealed interface ReportPhase {
    data object Idle : ReportPhase
    data object Preparing : ReportPhase
    data class Ready(val file: File, val bytes: Long, val pages: Int) : ReportPhase
    data class Failed(val reason: Int) : ReportPhase
}

@Composable
private fun ReportFrameCard(
    periodLabel: String,
    scopeLabel: String,
    dataAsOf: String,
    onPeriod: () -> Unit,
    onScope: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            FrameRow(stringResource(R.string.bt_insight_period), periodLabel, onPeriod)
            FrameRow(stringResource(R.string.bt_insight_scope), scopeLabel, onScope)
            FrameRow(stringResource(R.string.bt_insight_pdf_data_as_of), dataAsOf, null)
            Text(
                text = stringResource(R.string.bt_insight_report_frame_hint),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun FrameRow(label: String, value: String, onClick: (() -> Unit)?) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textMuted,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = bt.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = bt.textFaint,
            )
        }
    }
}

@Composable
private fun ReportChecklistRow(
    insight: BtInsight,
    checked: Boolean,
    available: Boolean,
    styleLine: String,
    onToggle: () -> Unit,
    onCustomize: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(BtShapes.cardSmall)
            .clickable(enabled = available, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked && available,
            onCheckedChange = { if (available) onToggle() },
            enabled = available,
            colors = CheckboxDefaults.colors(
                checkedColor = bt.gold,
                checkmarkColor = bt.onGold,
                uncheckedColor = bt.border,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(insightNameRes(insight)),
                style = MaterialTheme.typography.bodyLarge,
                color = if (available) bt.textPrimary else bt.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (available) {
                    styleLine
                } else {
                    stringResource(R.string.bt_insight_report_calendar_only)
                },
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (checked && available) {
            androidx.compose.material3.TextButton(onClick = onCustomize) {
                Text(
                    text = stringResource(R.string.bt_insight_report_customize),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.goldEmphasis,
                )
            }
        }
        Text(
            text = stringResource(R.string.bt_insight_report_one_page),
            style = MaterialTheme.typography.labelSmall,
            color = bt.textFaint,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

@Composable
private fun ReportEmptySelection(onRecommended: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(quiet = true) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.bt_insight_report_none_title),
                style = MaterialTheme.typography.bodyLarge,
                color = bt.textPrimary,
            )
            Text(
                text = stringResource(R.string.bt_insight_report_none_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(10.dp))
            BtSecondaryButton(
                text = stringResource(R.string.bt_insight_report_select_recommended),
                onClick = onRecommended,
            )
        }
    }
}

@Composable
private fun ReportReview(
    sections: List<BtReportSection>,
    pageCount: Int,
    rendering: Boolean,
    renderedPage: Int,
    renderTotal: Int,
    locale: Locale,
) {
    val bt = BtTheme.colors
    BtCard {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = pluralStringResource(R.plurals.bt_insight_pages, pageCount, pageCount),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                text = stringResource(R.string.bt_insight_report_contains),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bt_insight_report_real_amounts),
                style = MaterialTheme.typography.bodySmall,
                color = bt.goldEmphasis,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.bt_insight_report_order),
        style = MaterialTheme.typography.labelMedium,
        color = bt.textMuted,
    )
    sections.forEach { section ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(insightNameRes(section.insight)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.bt_insight_report_page, section.page),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
            }
            Text(
                text = String.format(locale, "%02d", section.number),
                style = BtTheme.type.numberCaption,
                color = bt.textFaint,
            )
        }
    }
    if (rendering) {
        Spacer(Modifier.height(10.dp))
        Text(
            // Determinate once the total is known; until then the sheet says it
            // is freezing the data rather than inventing a page number.
            text = if (renderTotal > 0) {
                stringResource(
                    R.string.bt_insight_report_creating_page,
                    renderedPage.coerceAtLeast(1),
                    renderTotal,
                )
            } else {
                stringResource(R.string.bt_insight_report_freezing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        LinearProgressIndicator(
            progress = { if (renderTotal == 0) 0f else renderedPage.toFloat() / renderTotal },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReportFooter(
    step: ReportStep,
    selectedCount: Int,
    pageCount: Int,
    estimateBytes: Long,
    phase: ReportPhase,
    locale: Locale,
    onReview: () -> Unit,
    onCreate: () -> Unit,
    onShare: (File) -> Unit,
    onDone: () -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(bt.bg)
            // Without this the primary action sits UNDER a 3-button nav bar and
            // the system swallows the tap — observed on the owner's device,
            // 2026-08-18: `Bericht prüfen` was unreachable.
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        when (val current = phase) {
            is ReportPhase.Ready -> {
                Text(
                    text = stringResource(R.string.bt_insight_report_ready_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.bt_insight_pages,
                        current.pages,
                        current.pages,
                    ) + " · PDF · " + insightFormatBytes(current.bytes, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PdfSaveButton(current.file, Modifier.weight(1f))
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_insight_share),
                        onClick = { onShare(current.file) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_insight_done),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is ReportPhase.Failed -> {
                Text(
                    text = stringResource(current.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.loss,
                )
                Spacer(Modifier.height(8.dp))
                BtPrimaryButton(
                    text = stringResource(R.string.bt_insight_try_again),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_insight_selected,
                            selectedCount,
                            selectedCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        // "Voraussichtlich" until the render is done: the study
                        // forbids promising an exact size before one exists.
                        text = pluralStringResource(
                            R.plurals.bt_insight_pages_estimate,
                            pageCount,
                            pageCount,
                        ) + " · " + insightFormatBytes(estimateBytes, locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.height(8.dp))
                BtPrimaryButton(
                    text = stringResource(
                        if (step == ReportStep.Choose) {
                            R.string.bt_insight_report_review
                        } else {
                            R.string.bt_insight_report_make_pdf
                        },
                    ),
                    onClick = if (step == ReportStep.Choose) onReview else onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedCount > 0 && phase !is ReportPhase.Preparing,
                    loading = phase is ReportPhase.Preparing,
                )
            }
        }
    }
}

@Composable
private fun PdfSaveButton(file: File, modifier: Modifier) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME),
    ) { uri: Uri? -> if (uri != null) copyToDocument(context, file, uri) }
    BtSecondaryButton(
        text = stringResource(R.string.bt_insight_save_to_files),
        onClick = { launcher.launch(file.name) },
        modifier = modifier,
    )
}

private fun sharePdf(context: Context, file: File): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = PDF_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, file.name).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
    true
}.getOrDefault(false)

/** The one-line summary of a card's saved style, shown on its checklist row. */
@Composable
private fun reportStyleLine(
    insight: BtInsight,
    config: BtInsightConfig,
    family: BtVizConfig,
): String {
    val resolved = insightResolvedForm(insight, config, family, BtVizCanvas.APP_FULL)
    val parts = buildList {
        if (resolved != BtVizForm.AUTO) add(stringResource(insightFormRes(resolved)))
        config.topN?.let { add(stringResource(R.string.bt_viz_scope_top, it.limit)) }
        if (config.compare) add(stringResource(insightCompareRes(insight.spec.compare)))
    }
    return parts.joinToString(" · ")
}
