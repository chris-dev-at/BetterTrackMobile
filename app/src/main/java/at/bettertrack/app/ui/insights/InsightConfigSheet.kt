package at.bettertrack.app.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizConfig
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizLabels
import at.bettertrack.app.ui.charts.viz.BtVizScope
import at.bettertrack.app.ui.charts.viz.vizFormsFor
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * `Insight konfigurieren` — one card's bottom sheet.
 *
 * ## Staged, not live
 *
 * Every control edits a local draft and the preview redraws immediately, but
 * nothing is saved until `Übernehmen`. That is the opposite of the shipped
 * `BtVizSheet`, which applies each choice as it is made, and the difference is
 * deliberate: this sheet can change a card's *period and scope*, so a
 * half-finished exploration would otherwise fire a network fetch per tap and
 * leave the page on whatever the user happened to touch last.
 *
 * ## What the sheet may show
 *
 * Only the knobs [BtInsightSpec] lists for this insight. A control that makes no
 * semantic sense is absent, not disabled — the tax card has no period picker at
 * all, and the daily-movers card has no comparison. Rendering a greyed control
 * would advertise a capability the product does not have.
 *
 * ## Precedence, made visible
 *
 * The `Darstellung` row reads `Standard verwenden` while the card inherits, and
 * the family it inherits from is named underneath. Choosing a shape here writes
 * a CARD override only; the app-wide family default is never touched, so the
 * cash screen's spending chart keeps its own shape. `Zurücksetzen` clears the
 * override rather than pinning the family's current value, which is what lets
 * the card follow future changes to the family again.
 */
@Composable
fun InsightConfigSheet(
    insight: BtInsight,
    config: BtInsightConfig,
    family: BtVizConfig,
    snapshot: BtInsightSnapshot,
    portfolioNames: Map<String, String>,
    /**
     * The period the card is ACTUALLY rendered with right now — its own override
     * when it has one, else the page frame's.
     *
     * Passed in rather than guessed. Device QA 2026-09-01 #17: this sheet used to
     * fall back to `insightPeriodKinds(insight).first()` for a card with no
     * override, so it announced `Zeitraum: 1 Monat` while the page chip said
     * `1 Jahr` and the live preview two rows above rendered
     * `02.09.2025 – 02.09.2026`. The sheet simply had no way to know the frame.
     */
    effectivePeriod: BtInsightPeriod,
    onApply: (BtInsightConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val spec = insight.spec
    var draft by remember(config) { mutableStateOf(config) }
    var compactPreview by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<ConfigPicker?>(null) }

    val previewCanvas = if (compactPreview) BtVizCanvas.APP_COMPACT else BtVizCanvas.APP_FULL
    val resolved = insightResolvedForm(insight, draft, family, previewCanvas)
    val unavailable = insightFormUnavailable(insight, draft, previewCanvas)

    BtPickerSheet(
        title = stringResource(R.string.bt_insight_configure),
        subtitle = stringResource(insightNameRes(insight)),
        onDismiss = onDismiss,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_insight_reset),
                    onClick = { draft = insightResetToFamily(draft) },
                    modifier = Modifier.weight(1f),
                    enabled = insightHasFormOverride(draft),
                )
                BtPrimaryButton(
                    text = stringResource(R.string.bt_insight_apply),
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1.4f),
                )
            }
        },
    ) {
        // ── Live preview, real data ─────────────────────────────────────────
        Text(
            text = stringResource(R.string.bt_insight_live_preview),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        InsightCard(
            snapshot = snapshot,
            config = draft,
            family = family,
            compact = compactPreview,
            onConfigure = {},
            onShare = {},
            modifier = Modifier.heightIn(max = 420.dp),
        )

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.bt_insight_preview_size),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(6.dp))
        BtSegmented(
            options = listOf(true, false),
            selected = compactPreview,
            label = {
                stringResource(if (it) R.string.bt_insight_compact else R.string.bt_insight_full)
            },
            onSelect = { compactPreview = it },
            modifier = Modifier.fillMaxWidth(),
            equalWidths = true,
        )

        Spacer(Modifier.height(6.dp))

        // ── Period — absent entirely for a stichtag or a session ────────────
        val periodKinds = insightPeriodKinds(insight)
        if (periodKinds.isNotEmpty()) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_period),
                value = stringResource(
                    insightPeriodRes(draft.period?.kind ?: effectivePeriod.kind),
                ),
                onClick = { picker = ConfigPicker.Period },
            )
        }

        // ── Zeitspanne — the movements card's subject, not a Darstellung ────
        // Sits where the period row would be, because for this card it IS the
        // period question. Its hint states what the chosen span can print, so
        // the reader learns that 1 Woche is a percentage BEFORE they pick it
        // rather than by noticing the euro sign vanished.
        if (spec.moveRanges.isNotEmpty()) {
            val range = draft.moveRange ?: BT_INSIGHT_MOVE_RANGE_DEFAULT
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_move_label),
                hint = insightMoveNoteRes(range)?.let { stringResource(it) },
                value = stringResource(insightMoveRangeRes(range)),
                onClick = { picker = ConfigPicker.MoveRange },
            )
        }

        // ── Scope ───────────────────────────────────────────────────────────
        ConfigValueRow(
            label = stringResource(R.string.bt_insight_scope),
            hint = stringResource(R.string.bt_insight_scope_hint),
            value = draft.portfolioIds
                ?.mapNotNull { portfolioNames[it] }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: stringResource(R.string.bt_insight_scope_all),
            onClick = { picker = ConfigPicker.Scope },
        )

        // ── Darstellung — only where a shape is a real choice ───────────────
        if (spec.forms.isNotEmpty()) {
            ConfigValueRow(
                label = stringResource(R.string.bt_viz_title),
                hint = spec.family?.let {
                    stringResource(R.string.bt_insight_darstellung_family, vizFamilyLabel(it))
                },
                value = draft.form?.let { stringResource(insightFormRes(it)) }
                    ?: stringResource(R.string.bt_insight_use_family_default),
                onClick = { picker = ConfigPicker.Form },
            )
            // The resolver's answer, stated rather than left to be discovered.
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_resolved),
                hint = stringResource(R.string.bt_insight_resolved_hint),
                value = if (unavailable) {
                    stringResource(R.string.bt_insight_unavailable_at_size)
                } else {
                    stringResource(insightFormRes(resolved))
                },
                emphasis = unavailable,
                onClick = null,
            )
        }

        if (spec.seriesChoice) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_series),
                value = stringResource(insightSeriesRes(draft.series ?: BtInsightSeries.BOTH)),
                onClick = { picker = ConfigPicker.Series },
            )
        }

        if (spec.labels) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_labels),
                hint = stringResource(R.string.bt_insight_labels_hint),
                value = stringResource(labelsRes(draft.labels ?: family.labels)),
                onClick = { picker = ConfigPicker.Labels },
            )
        }

        if (spec.topN.isNotEmpty()) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_topn),
                value = scopeLabel(draft.topN ?: family.scope),
                onClick = { picker = ConfigPicker.TopN },
            )
        }

        if (spec.sorts.isNotEmpty()) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_sort),
                value = stringResource(insightSortRes(draft.sort ?: spec.sorts.first())),
                onClick = { picker = ConfigPicker.Sort },
            )
        }

        if (spec.groupings.size > 1) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_grouping),
                value = stringResource(
                    insightGroupingRes(draft.grouping ?: spec.groupings.first()),
                ),
                onClick = { picker = ConfigPicker.Grouping },
            )
        }

        if (spec.compare != BtInsightCompare.NONE) {
            ConfigSwitchRow(
                label = stringResource(R.string.bt_insight_compare),
                hint = stringResource(insightCompareRes(spec.compare)),
                checked = draft.compare,
                onCheckedChange = { draft = draft.copy(compare = it) },
            )
        }

        if (spec.cashToggle) {
            ConfigSwitchRow(
                label = stringResource(
                    if (insight == BtInsight.LIQUID_FUNDS) {
                        R.string.bt_insight_include_broker_cash
                    } else {
                        R.string.bt_insight_show_cash
                    },
                ),
                hint = stringResource(R.string.bt_insight_show_cash_hint),
                checked = draft.showCash ?: family.showCash,
                onCheckedChange = { draft = draft.copy(showCash = it) },
            )
        }

        if (spec.budgetsToggle) {
            ConfigSwitchRow(
                label = stringResource(R.string.bt_insight_show_budgets),
                checked = draft.showBudgets,
                onCheckedChange = { draft = draft.copy(showBudgets = it) },
            )
        }

        if (spec.feesToggle) {
            ConfigSwitchRow(
                label = stringResource(R.string.bt_insight_show_fees),
                checked = draft.showFees,
                onCheckedChange = { draft = draft.copy(showFees = it) },
            )
        }

        if (spec.transfersToggle) {
            ConfigSwitchRow(
                label = stringResource(R.string.bt_insight_include_transfers),
                checked = draft.includeTransfers,
                onCheckedChange = { draft = draft.copy(includeTransfers = it) },
            )
        }

        if (spec.focus && snapshot.datums.isNotEmpty()) {
            ConfigValueRow(
                label = stringResource(R.string.bt_insight_focus),
                hint = stringResource(R.string.bt_insight_focus_hint),
                value = draft.focusKey
                    ?.let { key -> snapshot.datums.firstOrNull { it.key == key }?.label }
                    ?: stringResource(R.string.bt_insight_focus_none),
                onClick = { picker = ConfigPicker.Focus },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.bt_insight_preview_frozen),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .clip(BtShapes.cardSmall)
                .background(bt.goldWash)
                .padding(10.dp),
        )
    }

    // ── Nested pickers. Menu-like choices open sheets, never dropdowns ──────
    when (val open = picker) {
        null -> Unit
        ConfigPicker.Period -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_period),
            options = insightPeriodKinds(insight),
            label = { stringResource(insightPeriodRes(it)) },
            // The EFFECTIVE kind, so the open picker check-marks what is in force
            // rather than nothing at all (device QA 2026-09-01 #17).
            selected = draft.period?.kind ?: effectivePeriod.kind,
            onSelect = { kind ->
                draft = draft.copy(
                    period = BtInsightPeriod(
                        kind = kind,
                        year = draft.period?.year ?: 0,
                        fromEpochDay = draft.period?.fromEpochDay ?: 0L,
                        toEpochDay = draft.period?.toEpochDay ?: 0L,
                    ),
                )
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.MoveRange -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_move_label),
            options = spec.moveRanges,
            label = { stringResource(insightMoveRangeRes(it)) },
            selected = draft.moveRange ?: BT_INSIGHT_MOVE_RANGE_DEFAULT,
            onSelect = {
                draft = draft.copy(moveRange = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Scope -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_scope),
            options = listOf<String?>(null) + portfolioNames.keys.toList(),
            label = { id ->
                id?.let { portfolioNames[it] ?: it }
                    ?: stringResource(R.string.bt_insight_scope_all)
            },
            selected = draft.portfolioIds?.singleOrNull(),
            onSelect = { id ->
                draft = draft.copy(portfolioIds = id?.let { setOf(it) })
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Form -> {
            // Only forms that survive the PREVIEW canvas are offered, so the
            // picker cannot produce an illegible card. The list already carries
            // the family default as its first entry.
            val canvas = previewCanvas
            val allowed = spec.family
                ?.let { family -> spec.forms.filter { it == BtVizForm.AUTO || it in vizFormsFor(family, canvas) } }
                ?: spec.forms
            InsightOptionSheet(
                title = stringResource(R.string.bt_viz_title),
                options = listOf<BtVizForm?>(null) + allowed,
                label = { form ->
                    form?.let { stringResource(insightFormRes(it)) }
                        ?: stringResource(R.string.bt_insight_use_family_default)
                },
                selected = draft.form,
                onSelect = {
                    draft = draft.copy(form = it)
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }

        ConfigPicker.Labels -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_labels),
            options = BtVizLabels.entries.toList(),
            label = { stringResource(labelsRes(it)) },
            selected = draft.labels,
            onSelect = {
                draft = draft.copy(labels = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.TopN -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_topn),
            options = spec.topN,
            label = { scopeLabel(it) },
            selected = draft.topN,
            onSelect = {
                draft = draft.copy(topN = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Sort -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_sort),
            options = spec.sorts,
            label = { stringResource(insightSortRes(it)) },
            selected = draft.sort,
            onSelect = {
                draft = draft.copy(sort = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Grouping -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_grouping),
            options = spec.groupings,
            label = { stringResource(insightGroupingRes(it)) },
            selected = draft.grouping,
            onSelect = {
                draft = draft.copy(grouping = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Series -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_series),
            options = BtInsightSeries.entries.toList(),
            label = { stringResource(insightSeriesRes(it)) },
            selected = draft.series,
            onSelect = {
                draft = draft.copy(series = it)
                picker = null
            },
            onDismiss = { picker = null },
        )

        ConfigPicker.Focus -> InsightOptionSheet(
            title = stringResource(R.string.bt_insight_focus),
            options = listOf<String?>(null) + snapshot.datums.map { it.key },
            label = { key ->
                key?.let { k -> snapshot.datums.firstOrNull { it.key == k }?.label ?: k }
                    ?: stringResource(R.string.bt_insight_focus_none)
            },
            selected = draft.focusKey,
            onSelect = {
                draft = draft.copy(focusKey = it)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

private enum class ConfigPicker {
    Period, MoveRange, Scope, Form, Labels, TopN, Sort, Grouping, Series, Focus,
}

/**
 * A nested value picker.
 *
 * A bottom sheet rather than an anchored menu, because the owner banned anchored
 * dropdowns app-wide and `AnchoredMenuDisciplineTest` enforces it. A sheet also
 * gives every option a full 48 dp target, which a dense menu never does.
 */
@Composable
fun <T> InsightOptionSheet(
    title: String,
    options: List<T>,
    label: @Composable (T) -> String,
    selected: T?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    BtPickerSheet(title = title, onDismiss = onDismiss) {
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(BtShapes.cardSmall)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) bt.goldEmphasis else bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = bt.goldEmphasis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigValueRow(
    label: String,
    value: String,
    hint: String? = null,
    emphasis: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(BtShapes.cardSmall)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = bt.textPrimary)
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasis) bt.textMuted else bt.goldEmphasis,
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
private fun ConfigSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = bt.textPrimary)
            if (!hint.isNullOrBlank()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = bt.onGold,
                checkedTrackColor = bt.gold,
                uncheckedThumbColor = bt.textMuted,
                uncheckedTrackColor = bt.surfaceQuiet,
            ),
        )
    }
}

/**
 * The VALUE a labels row prints.
 *
 * `AUTO` reads "Automatisch", not "Beschriftung": the row's own name is already
 * in the left column, and echoing it on the right says nothing. (Caught on
 * device, 2026-08-18 — the row read "Beschriftung  Beschriftung".)
 */
@Composable
private fun labelsRes(labels: BtVizLabels): Int = when (labels) {
    BtVizLabels.AUTO -> R.string.bt_viz_auto
    BtVizLabels.SHARES -> R.string.bt_insight_labels_shares
    BtVizLabels.AMOUNTS -> R.string.bt_insight_labels_amounts
}

@Composable
private fun scopeLabel(scope: BtVizScope): String = when (scope) {
    BtVizScope.AUTO -> stringResource(R.string.bt_viz_auto)
    BtVizScope.ALL -> stringResource(R.string.bt_viz_scope_all)
    else -> stringResource(R.string.bt_viz_scope_top, scope.limit)
}

@Composable
private fun vizFamilyLabel(family: at.bettertrack.app.ui.charts.viz.BtVizFamily): String =
    when (family) {
        at.bettertrack.app.ui.charts.viz.BtVizFamily.ALLOCATION_CLASS ->
            stringResource(R.string.bt_insight_name_asset_classes)
        at.bettertrack.app.ui.charts.viz.BtVizFamily.ALLOCATION_POSITION ->
            stringResource(R.string.bt_insight_name_concentration)
        at.bettertrack.app.ui.charts.viz.BtVizFamily.SPENDING ->
            stringResource(R.string.bt_insight_name_spending)
        at.bettertrack.app.ui.charts.viz.BtVizFamily.MOVERS ->
            stringResource(R.string.bt_insight_name_movers)
    }
