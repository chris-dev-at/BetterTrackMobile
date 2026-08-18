package at.bettertrack.app.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * The MOVERS preset of the row family ([BtRowFamilyContent]): the holdings,
 * biggest absolute day move first, with the "n von m Positionen bewegt ·
 * Depot +x %" footer. Reconfigurable to any source/sort/direction the family
 * supports (the winners|losers SPLIT lives under the direction knob); this
 * class only carries the defaults and the picker identity. Config-optional.
 */
class BtMoversWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val config: BtWidgetRowsConfig,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            id = id,
            load = {
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = BtWidgetRepository.load(context),
                    colors = btGlanceColors(btWidgetThemeMode(context)),
                    config = btWidgetConfigOrNull("movers") {
                        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                        if (state[BT_WIDGET_PREF_ROWS_SOURCE] == null) {
                            btWidgetClaimPinnedRows(
                                context, id, BtWidgetPinKind.MOVERS, BT_WIDGET_ROWS_MOVERS_DEFAULTS,
                            ) ?: btWidgetRowsConfig(state, BT_WIDGET_ROWS_MOVERS_DEFAULTS)
                        } else {
                            btWidgetRowsConfig(state, BT_WIDGET_ROWS_MOVERS_DEFAULTS)
                        }
                    } ?: BT_WIDGET_ROWS_MOVERS_DEFAULTS,
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                // The ROWS carry the deep links (each opens its own asset); the
                // card behind them has no single subject, so it just opens the
                // app — see btWidgetLaunchIntent.
                action = actionStartActivity(btWidgetLaunchIntent(context)),
            ) {
                BtRowFamilyContent(context, data.local, data.snapshot, data.config, data.colors)
            }
        }
    }

    private companion object {
        /** ~2x1 (round 2b) — the two-row winner/loser edge. */
        val STRIP = DpSize(110.dp, 40.dp)

        /** ~2x2 — the ranked list. */
        val SQUARE = DpSize(140.dp, 90.dp)

        /** ~4x2 — price column; SPLIT direction becomes two columns. */
        val WIDE = DpSize(250.dp, 90.dp)

        /** ~4x3+ — more rows, name sublines. */
        val TALL = DpSize(250.dp, 160.dp)
    }
}
