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
 * The watchlist widget: the assets the user watches, with the last price and day
 * move this device actually has.
 *
 * ## What it can and cannot promise
 *
 * Prices here are a CACHE, refreshed by [BtWidgetRefreshWorker] through the app's
 * existing single-asset quote read — the platform has no batch quote endpoint and
 * a widget is not the place to invent one. So a row shows the last figure that
 * landed, and the card says "as of …" once that figure is old enough to matter.
 * A row that has never been quoted and is not also a held position renders an em
 * dash: the app's own symbol for "no value", not a zero.
 *
 * Tapping a row opens that asset's market page; tapping the header opens the app.
 */
class BtWatchlistWidget : GlanceAppWidget() {

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
        BtWidgetLabel(local.getString(R.string.bt_widget_watchlist_title), colors)
        Spacer(GlanceModifier.height(6.dp))

        when {
            snapshot.session == BtWidgetSession.SIGNED_OUT ->
                BtWidgetMessage(
                    local.getString(R.string.bt_widget_signed_out),
                    colors,
                    emphasis = true,
                )

            snapshot.session == BtWidgetSession.LOADING ->
                BtWidgetMessage(local.getString(R.string.bt_widget_syncing), colors)

            snapshot.rows.isEmpty() ->
                BtWidgetMessage(local.getString(R.string.bt_widget_watchlist_empty), colors)

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
            items(items = snapshot.rows, itemId = { it.assetId.hashCode().toLong() }) { row ->
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
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
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    // The price is the first thing to go when the card narrows:
                    // the day move is why a watchlist is on a home screen, and a
                    // squeezed two-column row is worth more than a clipped three.
                    if (wide) {
                        Text(
                            text = btWidgetMoney(
                                value = row.price,
                                currency = row.currency,
                                discreet = snapshot.discreet,
                                locale = locale,
                            ),
                            style = TextStyle(color = colors.textSecondary, fontSize = 12.sp),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.width(10.dp))
                    }
                    Text(
                        text = btWidgetPercent(row.dayChangePct, locale),
                        style = TextStyle(
                            color = colors.tone(btWidgetTone(row.dayChangePct)),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }

        if (snapshot.quotesStale && snapshot.quotesAsOfMs != null) {
            Spacer(GlanceModifier.height(2.dp))
            BtWidgetAsOf(local, snapshot.quotesAsOfMs, colors, locale)
        }
    }

    private companion object {
        /** ~2x2 — symbol + day move. */
        val NARROW = DpSize(140.dp, 110.dp)

        /** ~4x2 and up — symbol + price + day move. */
        val WIDE = DpSize(250.dp, 110.dp)
    }
}
