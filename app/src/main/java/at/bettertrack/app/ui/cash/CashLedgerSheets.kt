package at.bettertrack.app.ui.cash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtDateField
import at.bettertrack.app.ui.components.BtDatePickerDialog
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import java.time.LocalDate
import java.util.Locale

/**
 * The ledger's three filter sheets (v2, owner: *"the current filters are too
 * basic"*), built to the commissioned study `DESIGN_NOTES_LEDGER.md`.
 *
 * Bottom sheets and nothing else — anchored menus are banned app-wide and the
 * ban is enforced by `AnchoredMenuDisciplineTest`. Beyond obeying the rule,
 * these are the right surface for the job: each one is a *staged* edit with a
 * thumb-zone commit button that previews its own result count, which a chip row
 * cannot do at all.
 *
 * ## Staging
 *
 * Every sheet edits a local copy and commits on the primary button. Swiping
 * down or pressing Back discards. That is the study's model and it is the one
 * that makes multi-select bearable: ticking four tags with live application
 * would re-filter the list under the sheet four times, and the result count on
 * the button would be chasing the user's finger.
 */

// ═══════════════════════════ 1. The date sheet ══════════════════════════════

/**
 * `Zeitraum`: the four rolling presets, plus an explicit custom start/end.
 *
 * ## Why the custom mode uses the app's date field, not an inline calendar
 *
 * The study draws a one-month range calendar inside the sheet. This app already
 * owns a dated-input pattern — [BtDateField] opening [BtDatePickerDialog] — that
 * every other dated form in the product uses, and it already implements the
 * hard parts the study calls out: a hard max so the future cannot be picked, a
 * localized medium date in the field, and 48dp targets throughout. Building a
 * second calendar here would give the ledger a date picker that behaves
 * differently from the transaction form's, which is precisely the kind of split
 * this codebase spends its component layer preventing. The *semantics* the
 * study specifies are kept exactly: two explicit endpoints, inclusive, Apply
 * disabled until both are valid, and the end field refuses anything before the
 * start.
 *
 * @param latestBooked the newest booking date the ledger holds, so a future-
 *   dated standing-order row stays selectable. See [cashMaxSelectableDate].
 */
@Composable
fun CashDateRangeSheet(
    window: CashLedgerWindow,
    customStart: LocalDate?,
    customEnd: LocalDate?,
    today: LocalDate,
    latestBooked: LocalDate?,
    locale: Locale,
    resultCount: (CashLedgerWindow, LocalDate?, LocalDate?) -> Int,
    onApply: (CashLedgerWindow, LocalDate?, LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var stagedWindow by remember { mutableStateOf(window) }
    var stagedStart by remember { mutableStateOf(customStart) }
    var stagedEnd by remember { mutableStateOf(customEnd) }
    var picking by remember { mutableStateOf<DateEndpoint?>(null) }

    val maxDate = cashMaxSelectableDate(today, latestBooked)
    val custom = stagedWindow == CashLedgerWindow.CUSTOM
    val valid = !custom || cashRangeValid(stagedStart, stagedEnd, today, latestBooked)
    val count = if (valid) resultCount(stagedWindow, stagedStart, stagedEnd) else 0

    BtPickerSheet(
        title = stringResource(R.string.bt_ledger_facet_date),
        subtitle = stringResource(R.string.bt_ledger_date_inclusive),
        onDismiss = onDismiss,
        footer = {
            BtPrimaryButton(
                text = pluralStringResource(R.plurals.bt_ledger_show_movements, count, count),
                onClick = { onApply(stagedWindow, stagedStart, stagedEnd) },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        },
    ) {
        FacetSheetHeaderAction(
            label = stringResource(R.string.bt_ledger_facet_reset),
            onClick = {
                stagedWindow = CashLedgerWindow.ALL
                stagedStart = null
                stagedEnd = null
            },
        )
        Text(
            text = stringResource(R.string.bt_ledger_date_quick),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
        )
        BtSegmented(
            options = CASH_LEDGER_WINDOWS,
            selected = stagedWindow,
            label = { stringResource(cashLedgerWindowLabel(it)) },
            onSelect = { stagedWindow = it },
            equalWidths = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // The fifth choice on its own row: five labels do not fit across 412dp,
        // and the study is explicit that it still belongs to the same exclusive
        // group rather than becoming a mode switch beside the presets.
        BtSegmented(
            options = listOf(CashLedgerWindow.CUSTOM),
            selected = stagedWindow,
            label = { stringResource(R.string.bt_ledger_window_custom) },
            onSelect = { stagedWindow = it },
            equalWidths = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (custom) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BtDateField(
                    date = stagedStart ?: today,
                    label = stringResource(R.string.bt_ledger_date_start),
                    enabled = true,
                    locale = locale,
                    onClick = { picking = DateEndpoint.START },
                    modifier = Modifier.weight(1f),
                )
                BtDateField(
                    date = stagedEnd ?: today,
                    label = stringResource(R.string.bt_ledger_date_end),
                    enabled = true,
                    locale = locale,
                    onClick = { picking = DateEndpoint.END },
                    modifier = Modifier.weight(1f),
                )
            }
            if (stagedStart == null || stagedEnd == null) {
                Text(
                    text = stringResource(R.string.bt_ledger_date_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            } else if (!valid) {
                Text(
                    text = stringResource(R.string.bt_ledger_date_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            } else {
                val range = CashDateRange(stagedStart!!, stagedEnd!!)
                Text(
                    text = pluralStringResource(
                        R.plurals.bt_ledger_date_days,
                        range.days.toInt(),
                        range.days.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }

    }

    picking?.let { endpoint ->
        BtDatePickerDialog(
            initial = when (endpoint) {
                DateEndpoint.START -> stagedStart ?: today
                DateEndpoint.END -> stagedEnd ?: today
            },
            maxDate = maxDate,
            // The end can never precede the start, so the calendar simply does
            // not offer those days — a refusal the user sees before they make
            // the mistake beats an error message afterwards.
            minDate = if (endpoint == DateEndpoint.END) stagedStart else null,
            onPick = { picked ->
                when (endpoint) {
                    DateEndpoint.START -> {
                        stagedStart = picked
                        // A start dragged past the end takes the end with it,
                        // rather than leaving an impossible pair on screen.
                        if (stagedEnd != null && stagedEnd!!.isBefore(picked)) stagedEnd = picked
                    }

                    DateEndpoint.END -> stagedEnd = picked
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** Which half of the range the calendar is currently editing. */
private enum class DateEndpoint { START, END }

// ══════════════════════ 2. The multi-select facet sheet ═════════════════════

/** One option in a facet sheet: a stable key, a name, and its contextual count. */
data class CashFacetOption(
    val key: String,
    val label: String,
    val count: Int,
)

/**
 * `Quellen` / `Tags`: 56dp check rows with a contextual count on the right.
 *
 * ## The count on the right is not the count of what is showing
 *
 * It is computed against the other committed facets while EXCLUDING this one
 * (see [cashSourceCounts]) — "how many rows would match if this option were
 * included". The alternative reads zero for every unticked option, which is
 * both true and completely useless. The count is drawn in muted ink and never
 * in emerald or red: it is a quantity of rows, not a direction of money.
 *
 * A selected option with a zero count stays visible and stays selected. The
 * study is firm about this and it is right — silently un-ticking a user's
 * choice because it currently matches nothing is the app rewriting their
 * question.
 *
 * @param searchLabel search appears only at [SEARCH_THRESHOLD] options or more.
 *   Below that a permanent text field is one more thing on screen than the list
 *   it would filter.
 */
@Composable
fun CashFacetSheet(
    title: String,
    options: List<CashFacetOption>,
    selected: Set<String>,
    searchLabel: String,
    emptySearchLabel: String,
    resultCount: (Set<String>) -> Int,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var staged by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val searchable = options.size >= SEARCH_THRESHOLD
    val visible = remember(options, query) {
        if (query.isBlank()) options else options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }
    val count = resultCount(staged)

    BtPickerSheet(
        title = title,
        subtitle = stringResource(R.string.bt_ledger_facet_counts_hint),
        onDismiss = onDismiss,
        searchQuery = if (searchable) query else null,
        searchLabel = if (searchable) searchLabel else null,
        onSearchQueryChange = if (searchable) ({ query = it }) else null,
        footer = {
            BtPrimaryButton(
                text = pluralStringResource(R.plurals.bt_ledger_show_movements, count, count),
                onClick = { onApply(staged) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        },
    ) {
        FacetSheetHeaderAction(
            label = stringResource(R.string.bt_ledger_facet_clear),
            onClick = { staged = emptySet() },
        )
        if (visible.isEmpty()) {
            Text(
                text = emptySearchLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        visible.forEach { option ->
            CashCheckRow(
                label = option.label,
                count = option.count,
                checked = option.key in staged,
                onToggle = {
                    staged = if (option.key in staged) staged - option.key else staged + option.key
                },
            )
        }
    }
}

/**
 * One 56dp check row. The WHOLE row toggles — a 20dp checkbox is a target only
 * a stylus enjoys — and the state is carried by three signals (wash, edge,
 * tick) for the same reason `BtPickerRow` carries three.
 */
@Composable
private fun CashCheckRow(
    label: String,
    count: Int,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val bt = BtTheme.colors
    val cd = stringResource(
        if (checked) R.string.bt_ledger_facet_selected else R.string.bt_ledger_facet_unselected,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = BtShapes.card,
        color = if (checked) bt.goldWash else Color.Transparent,
        contentColor = bt.textPrimary,
        border = if (checked) BorderStroke(1.dp, bt.goldEdge) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = cd,
                        tint = bt.goldEmphasis,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = bt.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                // Never gain/loss ink: this is a row count, not money.
                color = bt.textMuted,
            )
        }
    }
}

/** The sheet-local reset, right-aligned under the title block. */
@Composable
private fun FacetSheetHeaderAction(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = BtTheme.colors.goldInk,
            modifier = Modifier
                .clip(BtShapes.pill)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Below this many options a search field costs more than it saves. */
private const val SEARCH_THRESHOLD = 9
