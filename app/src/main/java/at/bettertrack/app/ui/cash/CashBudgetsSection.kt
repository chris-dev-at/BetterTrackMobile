package at.bettertrack.app.ui.cash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.CashBudgetProgressDto
import at.bettertrack.app.ui.components.BtActionSheet
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtSheetAction
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * The **Budgets** block on the cash screen (V5 S2c).
 *
 * A budget is a monthly spend target on one tag inside one portfolio, and the
 * only question a person actually has is "how much of it is left". So the row is
 * a bar, not a table: tag name, a gold fill that turns red the moment `exceeded`
 * flips, and the remaining/over figure. `spent`, `remaining` and `exceeded` all
 * come from the server — nothing here re-derives money, and `exceeded` is read
 * from the wire rather than computed as `spent > amount`, because that exact
 * comparison is what claims the platform's budget-fire row and sends the
 * `budget.exceeded` push. Two implementations of one threshold would eventually
 * disagree and the app would contradict its own notification.
 *
 * Amounts render through [formatEur], so discreet mode masks them for free.
 */

/** Wire month format for the budget/summary endpoints. */
private val WireMonth = DateTimeFormatter.ofPattern("yyyy-MM")

/** Human month for the header ("August 2026" / "August 2026"). */
private fun displayMonth(month: YearMonth, locale: Locale): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))

/** `YYYY-MM` for the wire. */
fun wireMonth(month: YearMonth): String = month.format(WireMonth)

/**
 * The budgets month, kept out of the future (owner order 2026-08-16: *"the
 * budget month selector must NOT allow navigating into future months"*). A
 * budget is an evaluation of booked movements; a future month has none, so a
 * step past [now] lands ON [now] rather than in an empty tomorrow. Backwards
 * stays unlimited — history is real. Pure, so the rule is a unit test.
 */
fun clampedBudgetMonth(candidate: YearMonth, now: YearMonth): YearMonth =
    if (candidate.isAfter(now)) now else candidate

/**
 * Fraction of the target consumed, clamped to `0f..1f` for the bar's width.
 *
 * Clamped because an exceeded budget would otherwise draw a bar wider than its
 * track; the *fact* of exceeding is carried by colour and by the "over" figure,
 * not by overflow geometry. A zero or negative target cannot be divided by, and
 * is treated as fully consumed when anything was spent at all.
 *
 * Pure + top-level so the arithmetic is unit-testable without Compose.
 */
fun budgetFraction(spent: Double, amount: Double): Float {
    if (!spent.isFinite() || !amount.isFinite()) return 0f
    if (amount <= 0.0) return if (spent > 0.0) 1f else 0f
    val raw = spent / amount
    if (raw.isNaN()) return 0f
    return min(1.0, maxOf(0.0, raw)).toFloat()
}

/**
 * One budget row: tag dot + name, the recurring badge, a progress bar, and the
 * remaining-or-over figure.
 */
@Composable
fun CashBudgetRow(
    budget: CashBudgetProgressDto,
    locale: Locale,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    // Over budget is the one state that must be unmissable, so it takes the
    // app's loss colour; everything else stays on the gold accent.
    val fill = if (budget.exceeded) bt.loss else bt.gold
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(parseTagColor(budget.tagColor, bt.tagFallback)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = budget.tagName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (budget.recurring) {
                Text(
                    text = stringResource(R.string.bt_budgets_recurring_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (onEdit != null || onDelete != null) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_budgets_actions_cd),
                        tint = bt.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (menuOpen) {
            val editLabel = stringResource(R.string.bt_budgets_edit_title)
            val deleteLabel = stringResource(R.string.bt_budgets_delete_action)
            BtActionSheet(
                title = budget.tagName,
                actions = buildList {
                    onEdit?.let { edit -> add(BtSheetAction(label = editLabel, onClick = edit)) }
                    onDelete?.let { del ->
                        add(BtSheetAction(label = deleteLabel, destructive = true, onClick = del))
                    }
                },
                onDismiss = { menuOpen = false },
            )
        }

        Spacer(Modifier.height(6.dp))
        BudgetBar(fraction = budgetFraction(budget.spent, budget.amount), fill = fill)
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.bt_budgets_progress,
                    formatEur(budget.spent, locale),
                    formatEur(budget.amount, locale),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (budget.exceeded) {
                    stringResource(R.string.bt_budgets_exceeded, formatEur(abs(budget.remaining), locale))
                } else {
                    stringResource(R.string.bt_budgets_remaining, formatEur(budget.remaining, locale))
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (budget.exceeded) bt.loss else bt.textSecondary,
            )
        }
    }
}

/**
 * The bar itself. Hand-drawn rather than `LinearProgressIndicator` so the track
 * and fill take the app's own surface/accent colours and rounded pill geometry
 * without fighting Material's defaults.
 *
 * ## The zero mark (device QA 2026-09-01, defect #12)
 *
 * A 0 % bar used to draw nothing at all inside the track, which made it
 * indistinguishable from a bar whose data had not arrived. It now draws a short
 * dimmed stub at the left end — visibly the fill's own colour, visibly not a
 * measurable length. That says "this bar is loaded and sits at zero" without
 * claiming any spend: [BUDGET_BAR_ZERO_MARK] is a fixed 8dp, not a fraction, so
 * it can never be read off the track as an amount.
 */
@Composable
private fun BudgetBar(fraction: Float, fill: Color, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(BtShapes.pill)
            .background(bt.border),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(BtShapes.pill)
                    .background(fill),
            )
        } else {
            Box(
                Modifier
                    .width(BUDGET_BAR_ZERO_MARK)
                    .height(6.dp)
                    .clip(BtShapes.pill)
                    .background(fill.copy(alpha = BUDGET_BAR_ZERO_ALPHA)),
            )
        }
    }
}

/** The fixed-width stub a 0 % bar draws — a state, never a measurement. */
private val BUDGET_BAR_ZERO_MARK = 8.dp

/** Dimmed, so the zero mark cannot be mistaken for a real (tiny) fill. */
private const val BUDGET_BAR_ZERO_ALPHA = 0.35f

/**
 * The month stepper above the budget list. Kept deliberately plain — two arrows
 * and the month — because it also drives the summary block beneath it, and a
 * heavier control would read as a filter over the whole screen rather than over
 * these two sections.
 */
@Composable
fun CashMonthStepper(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False at the current month (owner order 2026-08-16): the future has no
     * booked movements to budget against, so the arrow visibly stands down
     * instead of stepping into an empty month. [clampedBudgetMonth] is the
     * model-side guarantee behind the disabled control.
     */
    nextEnabled: Boolean = true,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.bt_budgets_prev_month_cd),
                tint = bt.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = displayMonth(month, locale),
            style = MaterialTheme.typography.labelLarge,
            color = bt.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(onClick = onNext, enabled = nextEnabled, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.bt_budgets_next_month_cd),
                tint = if (nextEnabled) bt.textSecondary else bt.border,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The overview's BRIEF budget line (owner order 2026-08-16: *"one compact
 * used-up bar per budget, nothing more"*): tag dot + name + ONE figure on the
 * same line, then the bar. Still no recurring badge, no menu, no second figures
 * row — all of that lives on the budgets subpage this block links to.
 *
 * ## Why the figure came back (device QA 2026-09-01, defect #12)
 *
 * The row shipped as name + bar, and on the owner's phone it read as "Essen"
 * beside an entirely featureless grey rail: no numbers, no fill, nothing. The
 * bar was arithmetically right — `0,00 € von 400,00 €` is 0 %, and 0 % draws no
 * fill — but a control that renders identically for *nothing spent* and
 * *nothing loaded* is not communicating, and the standing design rule is that a
 * configuration must never produce an illegible result.
 *
 * Two changes, neither of which reopens the crowded block he rejected:
 *
 *  · **One compact figure**, `0,00 € / 400,00 €`, right-aligned on the line the
 *    tag name already occupies — no new row, and it is the same pair the
 *    subpage leads with.
 *  · **An explicit zero mark** in the bar ([BudgetBar]), so an empty fill reads
 *    as a live bar sitting at zero rather than as an unpainted one.
 */
@Composable
fun CashBudgetBriefRow(
    budget: CashBudgetProgressDto,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val fill = if (budget.exceeded) bt.loss else bt.gold
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(parseTagColor(budget.tagColor, bt.tagFallback)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = budget.tagName,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.bt_budgets_brief_progress,
                    formatEur(budget.spent, locale),
                    formatEur(budget.amount, locale),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (budget.exceeded) bt.loss else bt.textMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(5.dp))
        BudgetBar(fraction = budgetFraction(budget.spent, budget.amount), fill = fill)
    }
}

/**
 * Loading placeholder that matches the real row's geometry (no layout jump).
 *
 * [brief] picks WHICH real row: the overview's [CashBudgetBriefRow] is a name
 * and a bar, the subpage's [CashBudgetRow] adds the remaining-or-over figure
 * underneath. A skeleton is only worth drawing if the content lands in the shape
 * it promised — a three-line placeholder in front of a two-line row would make
 * the overview jump on every load, which is the exact defect skeletons exist to
 * prevent.
 */
@Composable
fun CashBudgetSkeletonRow(modifier: Modifier = Modifier, brief: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BtSkeleton(Modifier.width(120.dp).height(14.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(6.dp))
        if (!brief) BtSkeleton(Modifier.width(160.dp).height(11.dp))
    }
}

/**
 * Empty-state body for the budgets block.
 *
 * This was a hand-rolled glyph + title + message row — a third, smaller copy of
 * the `BtEmptyState` scaffold, drawn inside a section that must not claim the
 * whole page is empty. It is now the design system's compact empty: ONE muted
 * line stating the answer, with the block's own "New budget" button directly
 * beneath it carrying the next step. The glyph is deliberately gone — a muted
 * pie chart beside the words "no budgets" decorated a sentence that already
 * said everything, and an empty section is an ANSWER, not a state that needs a
 * mark to be recognised.
 */
@Composable
fun CashBudgetsEmpty(modifier: Modifier = Modifier) {
    BtInlineEmpty(
        text = stringResource(R.string.bt_budgets_empty_title),
        modifier = modifier,
        // The sentence that says what a budget IS. A section is empty most often
        // because the feature has never been used, so this is the line that has
        // to survive the conversion, not the first casualty of it.
        message = stringResource(R.string.bt_budgets_empty_message),
    )
}
