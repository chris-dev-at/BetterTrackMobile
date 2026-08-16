package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import at.bettertrack.app.R
import java.util.Locale

/**
 * The portfolio-stats widget: the Overview's key figures, compressed.
 *
 * ## What each breakpoint drops, and why
 *
 * Resizable via [SizeMode.Responsive] with three sizes, and the layout removes
 * parts in the order they stop being worth their space:
 *
 *  * **compact** (2x2) — net worth, then today's move and total P&L as PERCENTS
 *    only. A percent survives truncation where "+1.234," does not, and at 2x2 it
 *    is the figure that fits.
 *  * **wide** (4x2) — the same two lines gain their EUR amounts.
 *  * **tall** (4x3) — invested and the holdings count join, because they are
 *    context rather than headline and only earn a row once there is height to
 *    spare.
 *
 * Net worth and the day change come from [BtWidgetRepository]'s `homeNetWorth`
 * call — the same figure the net-worth widget and `HomeScreen` show. Tapping opens
 * the Overview ([BT_WIDGET_TARGET_OVERVIEW]).
 */
class BtPortfolioStatsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val local = btWidgetContext(context)
        val snapshot = BtWidgetRepository.load(context)
        val colors = btGlanceColors(btWidgetThemeMode())
        provideContent {
            BtWidgetCard(
                colors = colors,
                action = actionStartActivity(btWidgetIntent(context, BT_WIDGET_TARGET_OVERVIEW)),
            ) {
                Content(local, snapshot, colors)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        context: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        BtWidgetLabel(context.getString(R.string.bt_widget_portfolio_stats_title), colors)
        Spacer(GlanceModifier.height(6.dp))

        when {
            snapshot.session == BtWidgetSession.SIGNED_OUT ->
                BtWidgetMessage(
                    context.getString(R.string.bt_widget_signed_out),
                    colors,
                    emphasis = true,
                )

            snapshot.session == BtWidgetSession.LOADING || snapshot.netWorthSyncing ->
                BtWidgetMessage(context.getString(R.string.bt_widget_syncing), colors)

            snapshot.noPortfolios ->
                BtWidgetMessage(context.getString(R.string.bt_widget_no_portfolios), colors)

            else -> Figures(context, snapshot, colors)
        }
    }

    @Composable
    private fun ColumnScope.Figures(
        context: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val net = snapshot.netWorth
        val stats = snapshot.stats
        val locale = btWidgetLocale(context)
        val size = LocalSize.current
        val showAmounts = size.width >= WIDE.width
        val showExtra = size.height >= TALL.height

        Text(
            text = btWidgetMoney(net?.eur, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale),
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = if (showAmounts) 24.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))

        // Today — the same figure the net-worth widget draws, omitted when the
        // hero says the day change is not showable (nothing priced).
        if (net != null && (net.dayChangeEur != null || net.dayChangePct != null)) {
            StatRow(
                label = context.getString(R.string.bt_widget_stat_today),
                value = signedValue(
                    amount = net.dayChangeEur.takeIf { showAmounts },
                    pct = net.dayChangePct,
                    discreet = snapshot.discreet,
                    locale = locale,
                ),
                valueColor = colors.tone(btWidgetTone(net.dayChangeEur ?: net.dayChangePct)),
                colors = colors,
            )
        }

        // Total unrealized P&L.
        if (stats != null && (stats.unrealizedPnlEur != null || stats.unrealizedPnlPct != null)) {
            StatRow(
                label = context.getString(R.string.bt_widget_stat_pnl),
                value = signedValue(
                    amount = stats.unrealizedPnlEur.takeIf { showAmounts },
                    pct = stats.unrealizedPnlPct,
                    discreet = snapshot.discreet,
                    locale = locale,
                ),
                valueColor = colors.tone(btWidgetTone(stats.unrealizedPnlEur ?: stats.unrealizedPnlPct)),
                colors = colors,
            )
        }

        if (showExtra && stats != null) {
            StatRow(
                label = context.getString(R.string.bt_widget_stat_invested),
                value = btWidgetMoney(stats.investedEur, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale),
                valueColor = colors.textSecondary,
                colors = colors,
            )
            StatRow(
                label = context.getString(R.string.bt_widget_stat_holdings),
                // A count is not an amount — never masked, never an em dash.
                value = stats.holdingsCount.toString(),
                valueColor = colors.textSecondary,
                colors = colors,
            )
        }

        if (net?.partial == true) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.bt_widget_partial, net.covered, net.active),
                style = TextStyle(color = colors.textMuted, fontSize = 10.sp),
                maxLines = 1,
            )
        }
        if (snapshot.netWorthStale && snapshot.netWorthAsOfMs != null) {
            Spacer(GlanceModifier.height(2.dp))
            BtWidgetAsOf(context, snapshot.netWorthAsOfMs, colors, locale)
        }
    }

    @Composable
    private fun StatRow(
        label: String,
        value: String,
        valueColor: ColorProvider,
        colors: BtGlanceColors,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TextStyle(color = colors.textMuted, fontSize = 12.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = value,
                style = TextStyle(
                    color = valueColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }

    private companion object {
        /** ~2x2 — net worth + two percents. */
        val COMPACT = DpSize(140.dp, 100.dp)

        /** ~4x2 — the percents gain their EUR amounts. */
        val WIDE = DpSize(250.dp, 100.dp)

        /** ~4x3 — invested + holdings count join. */
        val TALL = DpSize(250.dp, 160.dp)
    }
}

/**
 * A signed stat's value string: an optional EUR amount and its percent, joined.
 * The amount is discreet-masked (it is absolute money); the percent stays live,
 * matching the app's rule everywhere else.
 */
private fun signedValue(
    amount: Double?,
    pct: Double?,
    discreet: Boolean,
    locale: Locale,
): String {
    val pctStr = btWidgetPercent(pct, locale)
    if (amount == null) return pctStr
    val amountStr = btWidgetMoney(
        value = amount,
        currency = BT_WIDGET_QUOTE_CURRENCY,
        discreet = discreet,
        locale = locale,
        showSign = true,
    )
    return "$amountStr  $pctStr"
}
