package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
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

/**
 * The top-movers widget: the day's biggest moves across every holding.
 *
 * The ranking is [BtWidgetRepository]'s `btWidgetMovers`, which delegates to the
 * SAME `homeMovers` Home's strip uses — biggest absolute move first, one row per
 * asset, holdings with no known move dropped. A row shows the symbol and its day
 * percent (gain/loss coloured); the EUR move joins once the card is wide enough.
 *
 * When nothing has a known day move — a fresh sync, or a Drive install with manual
 * prices and therefore no previous close — the list is empty and the card says so,
 * rather than drawing a row of em dashes. Tapping a row opens that asset's page.
 */
class BtTopMoversWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(NARROW, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val local = btWidgetContext(context)
        val snapshot = BtWidgetRepository.load(context)
        val colors = btGlanceColors(btWidgetThemeMode())
        provideContent {
            BtWidgetCard(
                colors = colors,
                action = actionStartActivity(btWidgetIntent(context, BT_WIDGET_TARGET_OVERVIEW)),
            ) {
                Content(context, local, snapshot, colors)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        context: Context,
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        BtWidgetLabel(local.getString(R.string.bt_widget_top_movers_title), colors)
        Spacer(GlanceModifier.height(6.dp))

        when {
            snapshot.session == BtWidgetSession.SIGNED_OUT ->
                BtWidgetMessage(
                    local.getString(R.string.bt_widget_signed_out),
                    colors,
                    emphasis = true,
                )

            snapshot.session == BtWidgetSession.LOADING || snapshot.netWorthSyncing ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            snapshot.noPortfolios ->
                BtWidgetMessage(local.getString(R.string.bt_widget_no_portfolios), colors)

            snapshot.movers.isEmpty() ->
                BtWidgetMessage(local.getString(R.string.bt_widget_movers_empty), colors)

            else -> Rows(context, local, snapshot, colors)
        }
    }

    @Composable
    private fun ColumnScope.Rows(
        context: Context,
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        val locale = btWidgetLocale(local)
        val wide = LocalSize.current.width >= WIDE.width

        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            items(items = snapshot.movers, itemId = { it.assetId.hashCode().toLong() }) { mover ->
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clickable(
                            actionStartActivity(
                                btWidgetIntent(context, BT_WIDGET_TARGET_ASSET, mover.assetId),
                            ),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mover.symbol,
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    // The EUR move is the first thing to go when the card narrows:
                    // the percent is why a mover is a mover, and it must not be
                    // squeezed off by the amount.
                    if (wide) {
                        Text(
                            text = btWidgetMoney(
                                value = mover.dayChangeEur,
                                currency = BT_WIDGET_QUOTE_CURRENCY,
                                discreet = snapshot.discreet,
                                locale = locale,
                                showSign = true,
                            ),
                            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.width(10.dp))
                    }
                    Text(
                        text = btWidgetPercent(mover.dayChangePct, locale),
                        style = TextStyle(
                            color = colors.tone(btWidgetTone(mover.dayChangePct)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        // Movers ride the portfolio sync, so the same "as of" the net-worth widget
        // uses applies once that sync has gone stale.
        if (snapshot.netWorthStale && snapshot.netWorthAsOfMs != null) {
            Spacer(GlanceModifier.height(2.dp))
            BtWidgetAsOf(local, snapshot.netWorthAsOfMs, colors, locale)
        }
    }

    private companion object {
        /** ~2x2 — symbol + day percent. */
        val NARROW = DpSize(140.dp, 110.dp)

        /** ~4x2 and up — symbol + EUR move + day percent. */
        val WIDE = DpSize(250.dp, 110.dp)
    }
}
