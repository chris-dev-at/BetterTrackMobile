package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import at.bettertrack.app.R

/**
 * Quick actions (owner order, device review 2026-08-16): one-tap shortcuts
 * into the app's entry forms — a new trade, a new cash entry, and the market
 * search. Three, deliberately: a shortcut board that scrolls is a menu, and a
 * menu belongs in the app.
 *
 * Round-1 visual language: no header, compact tiles on the neutral chip fill,
 * each a gold glyph beside its label. Every tile is one honest deep link
 * ([BT_WIDGET_TARGET_ADD_TRANSACTION] / [BT_WIDGET_TARGET_ADD_CASH] /
 * [BT_WIDGET_TARGET_SEARCH]) through the same `NotifDeepLink` landing
 * discipline as every other widget tap. No data renders here, so the only
 * non-action state is signed-out (the standard CTA).
 *
 * 2x1 carries the two ADD actions side by side; 2x2 and wider stack all three
 * as full-width tiles.
 */
class BtQuickActionsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        btProvideContent(
            context = context,
            load = {
                Loaded(
                    local = btWidgetContext(context),
                    snapshot = BtWidgetRepository.load(context),
                    colors = btGlanceColors(btWidgetThemeMode()),
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                action = actionStartActivity(btWidgetIntent(context, BT_WIDGET_TARGET_OVERVIEW)),
                padding = 10.dp,
            ) {
                Content(context, data.local, data.snapshot, data.colors)
            }
        }
    }

    private data class Action(
        val glyph: String,
        val label: String,
        val target: String,
        /** The 4x1 strip-cell label — three tiles share one row there. */
        val shortLabel: String = label,
    )

    @Composable
    private fun ColumnScope.Content(
        context: Context,
        local: Context,
        snapshot: BtWidgetSnapshot,
        colors: BtGlanceColors,
    ) {
        if (snapshot.session == BtWidgetSession.SIGNED_OUT) {
            BtWidgetMessage(
                local.getString(R.string.bt_widget_signed_out),
                colors,
                emphasis = true,
            )
            return
        }
        val actions = listOf(
            Action(
                "+",
                local.getString(R.string.bt_widget_action_trade),
                BT_WIDGET_TARGET_ADD_TRANSACTION,
                shortLabel = local.getString(R.string.bt_widget_action_trade_short),
            ),
            Action("€", local.getString(R.string.bt_widget_action_cash), BT_WIDGET_TARGET_ADD_CASH),
            Action("↗", local.getString(R.string.bt_widget_action_market), BT_WIDGET_TARGET_SEARCH),
        )
        val oneRow = btWidgetRowClass(LocalSize.current.height.value) <= BtWidgetSizeClass.ROW1
        if (oneRow && btWidgetIsWide(LocalSize.current.width.value)) {
            // The 4-cell strip: all three tiles share the row, on their SHORT
            // labels — "Neue Transaktion" in a third of 373dp read "Neue
            // Transakt…" (device QA 2026-08-16), and a truncated verb is worse
            // than a shorter one.
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { i, action ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    TileCell(context, action, colors)
                }
            }
        } else if (oneRow) {
            // The 2-cell strip: two side-by-side cells left ~56dp per label —
            // both ADD actions truncated (same QA pass). Stacked full-width,
            // both labels fit whole; the third action is the app itself.
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.take(2).forEachIndexed { i, action ->
                    if (i > 0) Spacer(GlanceModifier.height(6.dp))
                    Tile(context, action, colors)
                }
            }
        } else {
            Column(
                modifier = GlanceModifier.fillMaxSize().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { i, action ->
                    if (i > 0) Spacer(GlanceModifier.height(6.dp))
                    Tile(context, action, colors)
                }
            }
        }
    }

    /** A full-width tile: gold glyph, label, the whole surface tappable. */
    @Composable
    private fun Tile(context: Context, action: Action, colors: BtGlanceColors) {
        val base = GlanceModifier
            .fillMaxWidth()
            .background(colors.chip)
            .clickable(actionStartActivity(btWidgetIntent(context, action.target)))
            .padding(horizontal = 10.dp, vertical = 9.dp)
        Row(
            modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.cornerRadius(10.dp)
            } else {
                base
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = action.glyph,
                style = TextStyle(
                    color = colors.gold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = action.label,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }

    /** The strip's half-width cell — same tile, sharing the row. */
    @Composable
    private fun RowScope.TileCell(context: Context, action: Action, colors: BtGlanceColors) {
        val base = GlanceModifier
            .defaultWeight()
            .background(colors.chip)
            .clickable(actionStartActivity(btWidgetIntent(context, action.target)))
            .padding(horizontal = 10.dp, vertical = 9.dp)
        Row(
            modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                base.cornerRadius(10.dp)
            } else {
                base
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = action.glyph,
                style = TextStyle(
                    color = colors.gold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.width(7.dp))
            Text(
                text = action.shortLabel,
                style = TextStyle(
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }

}
