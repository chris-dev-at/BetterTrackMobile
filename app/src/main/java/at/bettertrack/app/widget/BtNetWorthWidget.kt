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
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import at.bettertrack.app.R

/**
 * The net-worth widget: the Overview's own figure, on the home screen.
 *
 * Resizable via [SizeMode.Responsive]. The two breakpoints are not two designs —
 * they are the same design with the parts that stop fitting removed, in the order
 * they stop being worth their space: at 4x2 the day change reads as an amount AND
 * a percent, at 2x2 the percent alone carries it, because a percentage survives
 * truncation and "+1.234," does not.
 *
 * Tapping opens the app on the Overview ([BT_WIDGET_TARGET_OVERVIEW]).
 */
class BtNetWorthWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM))

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
        BtWidgetLabel(context.getString(R.string.bt_widget_net_worth_title), colors)
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
    private fun Figures(context: Context, snapshot: BtWidgetSnapshot, colors: BtGlanceColors) {
        val net = snapshot.netWorth ?: return
        val locale = btWidgetLocale(context)
        val compact = LocalSize.current.width < MEDIUM.width

        Text(
            text = btWidgetMoney(net.eur, BT_WIDGET_QUOTE_CURRENCY, snapshot.discreet, locale),
            style = TextStyle(
                color = colors.textPrimary,
                fontSize = if (compact) 20.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )

        // Omitted entirely when the hero says the day change is not showable —
        // see [btWidgetNetWorth]. A missing line is honest; a "+0,00 €" is not.
        if (net.dayChangeEur != null || net.dayChangePct != null) {
            Spacer(GlanceModifier.height(4.dp))
            val tone = colors.tone(btWidgetTone(net.dayChangeEur ?: net.dayChangePct))
            val style = TextStyle(color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row {
                if (!compact) {
                    Text(
                        text = btWidgetMoney(
                            value = net.dayChangeEur,
                            currency = BT_WIDGET_QUOTE_CURRENCY,
                            discreet = snapshot.discreet,
                            locale = locale,
                            showSign = true,
                        ),
                        style = style,
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
                Text(text = btWidgetPercent(net.dayChangePct, locale), style = style, maxLines = 1)
            }
        }

        // Two honesty markers, and neither is decoration: `partial` means some
        // active portfolio has never synced its totals, so the figure above is a
        // sum of fewer things than the user owns.
        if (net.partial) {
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

    private companion object {
        /** ~2x2. */
        val COMPACT = DpSize(140.dp, 100.dp)

        /** ~4x2. */
        val MEDIUM = DpSize(250.dp, 100.dp)
    }
}
