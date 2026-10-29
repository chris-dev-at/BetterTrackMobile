package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.MoneyWeightedPct
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.format.BT_EM_DASH
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.util.Locale

/**
 * The window's return summary (#1669; platform ruling 2026-09-03, owner §16
 * 2026-09-14, board tick 2026-09-15):
 *
 * ```
 * Zeitgewichtet -32,73 % · Geldgewichtet +9,50 %
 * Zeitgewichtet ist die Rendite deiner Bestände, … (MAX only)
 * ```
 *
 * Both figures are the SERVER's, read verbatim and printed at 2 dp through the
 * app's one percent formatter (§7.1 — nothing is computed here):
 *
 *  · time-weighted = the last point of the served `performance` curve (the same
 *    figure the delta line brackets, and the curve the chart draws in % mode);
 *  · money-weighted = `moneyWeightedPct`, the server's Modified Dietz return of
 *    the same window.
 *
 * Its own quiet line UNDER the delta line rather than a rewrite of it: the delta
 * line's shape is an owner ruling (device QA 2026-09-01 #14) and keeps its one-line
 * rhythm and its verdict colour; this line labels the two return definitions and
 * stays neutral (secondary ink, tabular figures, no red/green) — it explains, it
 * does not judge.
 *
 * The MAX explainer exists because MAX is anchored at 0 % before day one and is
 * NOT re-based: early losses on a tiny starting balance can leave the time-weighted
 * figure negative on a visibly rising curve, which the money-weighted figure —
 * the investor's own cash flows — does not share. Copy matches the web's
 * `portfolio.overview.chart.summaryMaxExplainer` in both languages.
 */
internal data class ReturnSummary(
    val timeWeightedPct: Double,
    /** Never [MoneyWeightedPct.NotComputed] — that state has no summary at all. */
    val moneyWeighted: MoneyWeightedPct,
    val explainMax: Boolean,
)

/**
 * Whether the window gets a summary line, and with what.
 *
 *  · absent `moneyWeightedPct` (older server / row cached before the field) →
 *    `null`: render NOTHING, exactly the pre-#1669 screen;
 *  · explicit `null` → a summary whose money-weighted figure is a dash;
 *  · a number → both figures.
 *
 * No time-weighted point (an empty `performance` series) also means no line — the
 * web's `ReturnSummaryLine` returns nothing in that case too, and a line with a
 * single labelled half would not be the comparison it exists to make.
 *
 * The MAX decision reads the range the HISTORY was served for, not the picker:
 * the explainer belongs to the figures on screen.
 */
internal fun returnSummaryOf(history: PortfolioHistory?): ReturnSummary? {
    if (history == null) return null
    if (history.moneyWeighted is MoneyWeightedPct.NotComputed) return null
    val twr = history.rangePerformancePct ?: return null
    return ReturnSummary(
        timeWeightedPct = twr,
        moneyWeighted = history.moneyWeighted,
        explainMax = history.range == HistoryRange.MAX,
    )
}

/** The money-weighted figure as displayed: a dash for no capital, never 0. */
internal fun moneyWeightedDisplay(value: MoneyWeightedPct, locale: Locale): String = when (value) {
    is MoneyWeightedPct.Value -> formatPercent(value.pct, locale)
    MoneyWeightedPct.NoCapital -> BT_EM_DASH
    // Unreachable through [returnSummaryOf]; rendered as nothing rather than a dash
    // should a caller ever ask, because "not computed" must never read as "no capital".
    MoneyWeightedPct.NotComputed -> ""
}

/**
 * The line as one string, from the two already-labelled halves — the single
 * definition of its shape (`<time-weighted> · <money-weighted>`), unit-tested.
 */
internal fun btReturnSummaryText(timeWeighted: String, moneyWeighted: String): String =
    "$timeWeighted · $moneyWeighted"

/**
 * The labelled summary line (+ the MAX explainer); composes nothing when there is
 * no summary.
 *
 * @param showMaxExplainer `false` where a surface states the explainer ONCE for
 *   several lines (the account sheet's small multiples) — see [ReturnSummaryExplainer].
 */
@Composable
internal fun ReturnSummaryLine(
    history: PortfolioHistory?,
    modifier: Modifier = Modifier,
    showMaxExplainer: Boolean = true,
    style: TextStyle = BtTheme.type.numberCaption,
) {
    val summary = remember(history) { returnSummaryOf(history) } ?: return
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    Column(modifier) {
        Text(
            text = btReturnSummaryText(
                timeWeighted = stringResource(
                    R.string.bt_return_summary_time_weighted,
                    formatPercent(summary.timeWeightedPct, locale),
                ),
                moneyWeighted = stringResource(
                    R.string.bt_return_summary_money_weighted,
                    moneyWeightedDisplay(summary.moneyWeighted, locale),
                ),
            ),
            style = style,
            color = bt.textSecondary,
        )
        if (showMaxExplainer && summary.explainMax) {
            ReturnSummaryExplainer(Modifier.padding(top = 2.dp))
        }
    }
}

/** The one-line MAX explainer on its own, for a surface that states it once. */
@Composable
internal fun ReturnSummaryExplainer(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.bt_return_summary_max_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = modifier,
    )
}
