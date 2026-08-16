package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R
import java.util.Locale

/**
 * The ROW FAMILY (the Codex study's family 06): "one row system covers movers,
 * holdings, and a manual watchlist — size determines row count; source and
 * sort determine the story."
 *
 * One implementation, two launcher presets: [BtWatchlistWidget] (watchlist ·
 * manual order) and [BtMoversWidget] (holdings · biggest movement). Both are
 * the same content with different [BtWidgetRowsConfig] DEFAULTS, and both
 * reconfigure to any combination of source (watchlist / holdings), sort
 * (movement / value / manual) and direction (all / winners / losers / split).
 * Keeping two picker entries is a deliberate call: "Watchlist" and "Movers"
 * are the two stories users look for by name, and a single entry would bury
 * one of them behind configuration.
 *
 * The study's per-row sparklines (4x4) are ABSENT by honest necessity — the
 * device caches one captured quote per asset, not per-asset price series
 * (reported as a data-gap deviation). The 4x4 instead earns more rows plus the
 * name sublines.
 *
 * VALUE sort on a watchlist source falls back to MANUAL: a watch-only row has
 * no position value to rank by.
 */

@Composable
internal fun ColumnScope.BtRowFamilyContent(
    context: Context,
    local: Context,
    snapshot: BtWidgetSnapshot,
    config: BtWidgetRowsConfig,
    colors: BtGlanceColors,
) {
    when {
        snapshot.session == BtWidgetSession.SIGNED_OUT ->
            BtWidgetMessage(
                local.getString(R.string.bt_widget_signed_out),
                colors,
                emphasis = true,
            )

        snapshot.session == BtWidgetSession.LOADING ->
            BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

        // The 2x1 edge (round 2b): the strongest winner and loser, two fixed
        // rows — the tightest honest rendition of "what moved". ROW1 routes
        // here too: one launcher row (92–120dp) holds two edge rows honestly,
        // but a header + one list row + footer would be a fragment (device QA
        // 2026-08-16; new placements are steered to 2 rows by minResizeHeight).
        btWidgetRowClass(LocalSize.current.height.value) <= BtWidgetSizeClass.ROW1 ->
            EdgeStrip(context, local, snapshot, config, colors)

        else -> Rows(context, local, snapshot, config, colors)
    }
}

@Composable
private fun ColumnScope.EdgeStrip(
    context: Context,
    local: Context,
    snapshot: BtWidgetSnapshot,
    config: BtWidgetRowsConfig,
    colors: BtGlanceColors,
) {
    val locale = btWidgetLocale(local)
    val all = when (config.source) {
        BtWidgetRowSource.WATCHLIST -> snapshot.rows
        BtWidgetRowSource.HOLDINGS -> btWidgetHoldingRows(snapshot.holdings)
    }
    val ranked = btWidgetSortRows(all, BtWidgetRowSort.MOVEMENT)
    val best = btWidgetFilterRows(ranked, BtWidgetRowDirection.WINNERS).firstOrNull()
    val worst = btWidgetFilterRows(ranked, BtWidgetRowDirection.LOSERS).firstOrNull()
    if (best == null && worst == null) {
        BtWidgetMessage(local.getString(R.string.bt_widget_movers_empty), colors)
        return
    }
    Column(
        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOfNotNull(best, worst).forEach { row ->
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clickable(
                        actionStartActivity(
                            btWidgetIntent(context, BT_WIDGET_TARGET_ASSET, row.assetId),
                        ),
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.symbol,
                    style = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    text = btWidgetPercent(row.dayChangePct, locale),
                    style = TextStyle(
                        color = colors.tone(btWidgetTone(row.dayChangePct)),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Rows(
    context: Context,
    local: Context,
    snapshot: BtWidgetSnapshot,
    config: BtWidgetRowsConfig,
    colors: BtGlanceColors,
) {
    val locale = btWidgetLocale(local)
    val size = LocalSize.current
    val wide = btWidgetIsWide(size.width.value)

    val all = when (config.source) {
        BtWidgetRowSource.WATCHLIST -> snapshot.rows
        BtWidgetRowSource.HOLDINGS -> btWidgetHoldingRows(snapshot.holdings)
    }
    if (all.isEmpty()) {
        BtWidgetMessage(
            local.getString(
                when (config.source) {
                    BtWidgetRowSource.WATCHLIST -> R.string.bt_widget_watchlist_empty
                    BtWidgetRowSource.HOLDINGS -> R.string.bt_widget_movers_empty
                },
            ),
            colors,
        )
        return
    }

    val effectiveSort =
        if (config.source == BtWidgetRowSource.WATCHLIST && config.sort == BtWidgetRowSort.VALUE) {
            BtWidgetRowSort.MANUAL
        } else {
            config.sort
        }
    val sorted = btWidgetSortRows(all, effectiveSort)
    val counts = btWidgetRowCounts(all)
    val split = config.direction == BtWidgetRowDirection.SPLIT && wide

    // The study's subject line: not a type header — it names THIS instance's
    // configuration ("Depot · stärkste Bewegung"), with the fixed day window
    // as the corner chip. That is what tells two row cards apart on one screen.
    BtSubjectRow(
        subject = local.getString(
            R.string.bt_widget_rows_subject,
            local.getString(
                when (config.source) {
                    BtWidgetRowSource.WATCHLIST -> R.string.bt_widget_watchlist_title
                    BtWidgetRowSource.HOLDINGS -> R.string.bt_widget_config_source_holdings
                },
            ),
            local.getString(
                when (effectiveSort) {
                    BtWidgetRowSort.MOVEMENT -> R.string.bt_widget_config_sort_movement
                    BtWidgetRowSort.VALUE -> R.string.bt_widget_config_sort_value
                    BtWidgetRowSort.MANUAL -> R.string.bt_widget_config_sort_manual
                },
            ),
        ),
        colors = colors,
    ) {
        BtContextChip(local.getString(R.string.bt_widget_range_1d), colors)
    }
    Spacer(GlanceModifier.height(3.dp))

    // The counts header — the mockup 4x4's "4↑ · 4↓" lead. The split reading
    // skips it: its GEWINNER/VERLIERER column headers already carry the story.
    val tall = btWidgetRowClass(size.height.value) >= BtWidgetSizeClass.ROW3
    val countsHeader = tall && !split
    if (countsHeader) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${counts.up} ↑",
                style = TextStyle(color = colors.gain, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Text(
                text = " · ",
                style = TextStyle(color = colors.textMuted, fontSize = 14.sp),
                maxLines = 1,
            )
            Text(
                text = "${counts.down} ↓",
                style = TextStyle(color = colors.loss, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(3.dp))
    }

    // Rows-that-fit, exact-fill (device QA 2026-08-16): the card computes how
    // many full rows its height holds, then divides the area so the LAST row
    // ends where the list does — no clipped half-row, no dead band under the
    // list (the mockup's rows fill their card edge to edge). Hairline dividers
    // between rows are the mockup's own row separation.
    val fs = btWidgetFontScale(local)
    val footerDp = if (config.source == BtWidgetRowSource.HOLDINGS) {
        3f + btWidgetTextDp(10f, fs)
    } else {
        0f
    }
    val headerDp = 18f + 3f + (if (countsHeader) btWidgetTextDp(14f, fs) + 3f else 0f) +
        (if (split) btWidgetTextDp(9f, fs) + 3f else 0f) // the sides' own headers
    val availableDp = size.height.value - 2 * BT_WIDGET_PADDING.value - headerDp - footerDp
    val rowUnit = BT_ROW_HEIGHT_DP * fs + 1f
    val fit = ((availableDp + 1f) / rowUnit).toInt().coerceAtLeast(1)

    if (split) {
        val perSide = minOf(BT_WIDGET_WINLOSE_PER_SIDE, fit)
        val rowH = btWidgetRowFillHeight(availableDp, perSide)
        Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            SplitSide(
                context, local, snapshot,
                btWidgetFilterRows(sorted, BtWidgetRowDirection.WINNERS),
                perSide, rowH, gain = true, colors, locale,
            )
            Spacer(GlanceModifier.width(14.dp))
            SplitSide(
                context, local, snapshot,
                btWidgetFilterRows(sorted, BtWidgetRowDirection.LOSERS),
                perSide, rowH, gain = false, colors, locale,
            )
        }
    } else {
        val shown = btWidgetFilterRows(sorted, config.direction)
        if (shown.isEmpty()) {
            BtWidgetMessage(local.getString(R.string.bt_widget_movers_empty), colors)
        } else {
            val count = minOf(shown.size, fit)
            val rowH = btWidgetRowFillHeight(availableDp, count)
            Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                shown.take(count).forEachIndexed { i, row ->
                    if (i > 0) BtWidgetDivider(colors)
                    // Price + move stacked right at EVERY size — the mock's
                    // hierarchy; Exact sizing means even 2x2 affords it.
                    QuoteRow(context, snapshot, row, rowH, colors, locale, wide = true)
                }
            }
        }
    }

    // The movers footer: how much of the book moved, and the day at account
    // scale — counts plus the hero's own day percent, no new math. Short by
    // design ("11/12 bewegt"), so it can never truncate.
    if (config.source == BtWidgetRowSource.HOLDINGS) {
        val dayPct = snapshot.netWorth?.dayChangePct
        if (counts.total > 0) {
            Spacer(GlanceModifier.height(3.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = local.getString(
                        R.string.bt_widget_rows_moved_short,
                        counts.moved,
                        counts.total,
                    ),
                    style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (dayPct != null) {
                    Text(
                        text = local.getString(
                            R.string.bt_widget_rows_day,
                            btWidgetPercent(dayPct, locale),
                        ),
                        style = TextStyle(
                            color = colors.tone(btWidgetTone(dayPct)),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    } else if (snapshot.quotesStale && snapshot.quotesAsOfMs != null) {
        Spacer(GlanceModifier.height(2.dp))
        BtWidgetAsOf(local, snapshot.quotesAsOfMs, colors, locale)
    }
}

/** One two-line row's height budget, for the rows-that-fit computation. */
internal const val BT_ROW_HEIGHT_DP = 34f

/**
 * One winners-or-losers column of the split reading, led by the mockup's own
 * column header — "↗ Gewinner" in the gain hue, "↘ Verlierer" in the loss hue.
 */
@Composable
private fun RowScope.SplitSide(
    context: Context,
    local: Context,
    snapshot: BtWidgetSnapshot,
    rows: List<BtWidgetRow>,
    perSide: Int,
    rowH: Float,
    gain: Boolean,
    colors: BtGlanceColors,
    locale: Locale,
) {
    Column(modifier = GlanceModifier.defaultWeight()) {
        Text(
            text = local.getString(
                if (gain) R.string.bt_widget_rows_side_winners else R.string.bt_widget_rows_side_losers,
            ).uppercase(Locale.ROOT),
            style = TextStyle(
                color = if (gain) colors.gain else colors.loss,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(3.dp))
        rows.take(perSide).forEachIndexed { i, row ->
            if (i > 0) BtWidgetDivider(colors)
            QuoteRow(context, snapshot, row, rowH, colors, locale, wide = true)
        }
    }
}

/**
 * One row, the study's hierarchy: symbol over its muted name on the left,
 * price over the signed coloured move on the right. The height is the exact
 * per-row share [btWidgetRowFillHeight] computed, so the list fills its card.
 */
@Composable
private fun QuoteRow(
    context: Context,
    snapshot: BtWidgetSnapshot,
    row: BtWidgetRow,
    rowH: Float,
    colors: BtGlanceColors,
    locale: Locale,
    wide: Boolean,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(rowH.dp)
            .clickable(
                actionStartActivity(
                    btWidgetIntent(context, BT_WIDGET_TARGET_ASSET, row.assetId),
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.symbol,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            if (row.name.isNotEmpty()) {
                Text(
                    text = row.name,
                    style = TextStyle(color = colors.textMuted, fontSize = 9.sp),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (wide) {
                Text(
                    text = btWidgetMoney(
                        value = row.price,
                        currency = row.currency,
                        discreet = snapshot.discreet,
                        locale = locale,
                    ),
                    style = TextStyle(
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                )
            }
            Text(
                text = btWidgetPercent(row.dayChangePct, locale),
                style = TextStyle(
                    color = colors.tone(btWidgetTone(row.dayChangePct)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }
}
