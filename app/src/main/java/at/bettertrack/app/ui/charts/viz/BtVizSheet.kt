package at.bettertrack.app.ui.charts.viz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The `Darstellung` control surface: the trigger row, the picker sheet with
 * **live previews rendered from the caller's real data**, and the companion
 * knobs (`Beschriftung`, `Umfang`, `Cash`).
 *
 * ## Why the previews use real data rather than a sample
 *
 * The whole question a user is answering here is "which of these shows *my*
 * portfolio best". A stock six-slice demo answers a different question and
 * flatters every form equally — it is precisely the long tail, the dominant
 * position, the near-equal pair that decide whether a treemap or a ranked list
 * is the right pick. Rendering the actual series in each row costs one extra
 * layout pass per option and makes the choice honest.
 *
 * ## The incompatible-choice rule
 *
 * Forms that cannot survive the current canvas are **absent from the list**, not
 * greyed out at the bottom of it (study, §"Configurable Darstellung model"). The
 * one case where an unsupported form is still visible is when it was saved
 * earlier and the canvas has since changed: then it appears with the
 * `Bei dieser Größe nicht verfügbar` explanation and the surface keeps drawing
 * the automatic fallback until another choice is made.
 */

/** The localized name of a form. `AUTO` reads as the recommendation it is. */
@Composable
fun vizFormLabel(form: BtVizForm): String = stringResource(
    when (form) {
        BtVizForm.AUTO -> R.string.bt_viz_auto
        BtVizForm.TREEMAP -> R.string.bt_viz_form_treemap
        BtVizForm.MOSAIC -> R.string.bt_viz_form_mosaic
        BtVizForm.STACKED_BAR -> R.string.bt_viz_form_stacked_bar
        BtVizForm.RANKED_BARS -> R.string.bt_viz_form_ranked_bars
        BtVizForm.RING -> R.string.bt_viz_form_ring
        BtVizForm.WAFFLE -> R.string.bt_viz_form_waffle
        BtVizForm.DOT_PLOT -> R.string.bt_viz_form_dot_plot
        BtVizForm.BUBBLES -> R.string.bt_viz_form_bubbles
        BtVizForm.DONUT -> R.string.bt_viz_form_donut
    },
)

/**
 * Prepare a raw series for drawing: stable colours, the cash rule, then the
 * responsive bucket with the right noun and plural.
 *
 * The bucket label distinguishes two genuinely different meanings, which the
 * study insists on keeping apart: `Andere` is a catch-all the *data* contains,
 * `Weitere` is one *this view* created because the canvas ran out of room.
 * Merging them would quietly tell a user that a real "Other" category grew.
 */
@Composable
fun rememberVizItems(
    raw: List<VizDatum>,
    form: BtVizForm,
    canvas: BtVizCanvas,
    config: BtVizConfig,
    categories: Boolean,
): List<VizDatum> {
    val limit = vizEffectiveLimit(config, form, canvas)

    // Reduce first with a placeholder label: only the reduction knows how many
    // rows it hid, and only composition can resolve a plural for that number.
    val reduced = remember(raw, limit, config.showCash) {
        val cashFiltered = if (config.showCash) raw else raw.filter { it.role != VizRole.Cash }
        val coloured = withStableColorIndices(cashFiltered)
        var realOtherPresent = false
        val items = reduceToTopN(coloured, limit) { _, present ->
            realOtherPresent = present
            ""
        }
        items to realOtherPresent
    }

    val items = reduced.first
    val bucket = items.lastOrNull()?.takeIf { it.key == VIZ_BUCKET_KEY } ?: return items
    val label = pluralStringResource(
        id = when {
            categories && reduced.second -> R.plurals.bt_viz_bucket_remaining_categories
            categories -> R.plurals.bt_viz_bucket_other_categories
            reduced.second -> R.plurals.bt_viz_bucket_remaining_positions
            else -> R.plurals.bt_viz_bucket_other_positions
        },
        count = bucket.hiddenCount,
        bucket.hiddenCount,
    )
    return items.dropLast(1) + bucket.copy(label = label)
}

/**
 * The row that opens the picker. Compact by design — it is a setting on a chart
 * card, not a section of its own, and the owner's standing rule is that nothing
 * gets pixels it has not earned.
 */
@Composable
fun BtVizDarstellungRow(
    config: BtVizConfig,
    resolved: BtVizForm,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val autoLabel = stringResource(R.string.bt_viz_auto_resolves, vizFormLabel(resolved))
    val value = if (config.form == BtVizForm.AUTO) autoLabel else vizFormLabel(config.form)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BtShapes.card)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.bt_viz_title),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = bt.goldInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The picker sheet.
 *
 * @param items the already-coloured, already-bucketed series — the same one the
 *   card is drawing right now, so a preview is a promise rather than a mock-up.
 * @param onConfig applied immediately. The sheet has no Save button because the
 *   house picker family has none: the chart behind the sheet updates as you
 *   choose, which is a better preview than any thumbnail.
 */
@Composable
fun BtVizSheet(
    family: BtVizFamily,
    canvas: BtVizCanvas,
    config: BtVizConfig,
    rawItems: List<VizDatum>,
    format: BtVizFormat,
    signed: Boolean,
    categories: Boolean,
    emptyText: String,
    onConfig: (BtVizConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val offered = remember(family, canvas) { vizFormsFor(family, canvas) }
    val savedUnsupported = config.form != BtVizForm.AUTO &&
        !vizFormSupported(config.form, family, canvas)

    BtPickerSheet(
        title = stringResource(R.string.bt_viz_choose),
        onDismiss = onDismiss,
        footer = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = stringResource(R.string.bt_viz_reset),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.goldInk,
                    modifier = Modifier
                        .clip(BtShapes.card)
                        .clickable { onConfig(BtVizConfig()) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        },
    ) {
        if (savedUnsupported) {
            // The saved choice survives; it simply cannot be honoured here.
            // Saying so beats silently swapping the shape under the user.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = BtShapes.card,
                color = bt.goldWash,
                border = BorderStroke(1.dp, bt.goldEdge),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "${vizFormLabel(config.form)} · ${stringResource(R.string.bt_viz_unavailable)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.bt_viz_unavailable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }

        VizFormOption(
            form = BtVizForm.AUTO,
            resolved = vizAutoForm(family, canvas),
            selected = config.form == BtVizForm.AUTO,
            canvas = canvas,
            config = config,
            rawItems = rawItems,
            format = format,
            signed = signed,
            categories = categories,
            emptyText = emptyText,
            onClick = { onConfig(config.copy(form = BtVizForm.AUTO)) },
        )
        offered.forEach { form ->
            VizFormOption(
                form = form,
                resolved = form,
                selected = config.form == form,
                canvas = canvas,
                config = config,
                rawItems = rawItems,
                format = format,
                signed = signed,
                categories = categories,
                emptyText = emptyText,
                onClick = { onConfig(config.copy(form = form)) },
            )
        }

        Spacer(Modifier.height(4.dp))
        VizKnobs(
            family = family,
            canvas = canvas,
            config = config,
            resolved = vizResolveForm(config, family, canvas),
            onConfig = onConfig,
        )
    }
}

/** One option row: a live thumbnail of this form drawn from the caller's data, plus its name. */
@Composable
private fun ColumnScope.VizFormOption(
    form: BtVizForm,
    resolved: BtVizForm,
    selected: Boolean,
    canvas: BtVizCanvas,
    config: BtVizConfig,
    rawItems: List<VizDatum>,
    format: BtVizFormat,
    signed: Boolean,
    categories: Boolean,
    emptyText: String,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    // The thumbnail always previews the COMPACT rendition: it is a 96dp-wide
    // box, and pretending it is the full card would preview a layout the user
    // is not about to get.
    val items = rememberVizItems(
        raw = rawItems,
        form = resolved,
        canvas = BtVizCanvas.APP_COMPACT,
        config = config,
        categories = categories,
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = BtShapes.card,
        color = if (selected) bt.goldWash else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, bt.goldEdge) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = VIZ_THUMB_WIDTH, height = VIZ_THUMB_HEIGHT)
                    .clip(BtShapes.cardSmall)
                    .background(bt.surfaceLow)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BtVizChart(
                    items = items,
                    form = resolved,
                    canvas = BtVizCanvas.APP_COMPACT,
                    format = format,
                    emptyText = "",
                    signed = signed,
                    labels = config.labels,
                    thumbnail = true,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = vizFormLabel(form),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                )
                if (form == BtVizForm.AUTO) {
                    Text(
                        text = vizFormLabel(resolved),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
    }
}

private val VIZ_THUMB_WIDTH = 96.dp
private val VIZ_THUMB_HEIGHT = 52.dp

/** `Beschriftung`, `Umfang` and the cash switch. Only the knobs this form and canvas can honour. */
@Composable
private fun VizKnobs(
    family: BtVizFamily,
    canvas: BtVizCanvas,
    config: BtVizConfig,
    resolved: BtVizForm,
    onConfig: (BtVizConfig) -> Unit,
) {
    val bt = BtTheme.colors
    val scopes = remember(resolved, canvas) { vizScopesFor(resolved, canvas) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // `Beschriftung` is meaningless where the form prints one fixed thing
        // (a dot plot always prints the signed amount), so it is not offered.
        if (!vizFormHasOwnRows(resolved) || resolved == BtVizForm.RANKED_BARS) {
            VizKnobBlock(stringResource(R.string.bt_viz_labels)) {
                BtSegmented(
                    options = BtVizLabels.entries.toList(),
                    selected = config.labels,
                    label = {
                        stringResource(
                            when (it) {
                                BtVizLabels.AUTO -> R.string.bt_viz_auto_short
                                BtVizLabels.SHARES -> R.string.bt_viz_labels_shares
                                BtVizLabels.AMOUNTS -> R.string.bt_viz_labels_amounts
                            },
                        )
                    },
                    onSelect = { onConfig(config.copy(labels = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    equalWidths = true,
                )
            }
        }

        VizKnobBlock(stringResource(R.string.bt_viz_scope)) {
            BtSegmented(
                options = scopes,
                selected = if (config.scope in scopes) config.scope else BtVizScope.AUTO,
                label = {
                    when (it) {
                        // "Automatisch" wraps to two lines once `Alle` makes this
                        // a five-segment control; a wrapped segment label is the
                        // kind of half-clipped text the owner reads as a defect.
                        BtVizScope.AUTO -> stringResource(R.string.bt_viz_auto_tiny)
                        BtVizScope.ALL -> stringResource(R.string.bt_viz_scope_all)
                        else -> stringResource(R.string.bt_viz_scope_top, it.limit)
                    }
                },
                onSelect = { onConfig(config.copy(scope = it)) },
                modifier = Modifier.fillMaxWidth(),
                equalWidths = true,
            )
        }

        if (vizHasCashControl(family)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bt_viz_cash),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                    )
                    Text(
                        // Say what hiding cash DOES. It is not a filter on a
                        // list, it changes the denominator every printed share
                        // is a fraction of, and a user who does not know that
                        // will read the new percentages as a data change.
                        text = stringResource(
                            if (config.showCash) R.string.bt_viz_cash_show else R.string.bt_viz_cash_excluded,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = config.showCash,
                    onCheckedChange = { onConfig(config.copy(showCash = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = bt.onGold,
                        checkedTrackColor = bt.gold,
                        checkedBorderColor = bt.gold,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedTrackColor = bt.surface,
                        uncheckedBorderColor = bt.borderStrong,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VizKnobBlock(label: String, content: @Composable () -> Unit) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
        content()
    }
}

/**
 * The persistent detail line for the selected mark.
 *
 * Persistent and screen-reader reachable by design — the study forbids a
 * default reading that depends on a tooltip, so the exact value of whatever is
 * selected has to live in real text on the card rather than in a hover.
 */
@Composable
fun BtVizSelectedDetail(
    label: String,
    value: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = BtShapes.card,
        color = bt.goldWash,
        border = BorderStroke(1.dp, bt.goldEdge),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = value, style = BtTheme.type.numberCaption, color = bt.textPrimary, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.bt_viz_clear_selection),
                style = MaterialTheme.typography.labelMedium,
                color = bt.goldInk,
                modifier = Modifier
                    .clip(BtShapes.card)
                    .clickable(onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}
