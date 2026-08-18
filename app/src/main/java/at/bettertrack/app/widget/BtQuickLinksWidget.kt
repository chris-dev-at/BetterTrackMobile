package at.bettertrack.app.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.state.getAppWidgetState
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
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import at.bettertrack.app.R

/**
 * Quick Links (the round-3 Codex study, family 01): a launcher inside the
 * launcher — ordered deep links as icon-only, app-icon-shaped tiles.
 *
 * ## What the owner asked for, and what changed
 *
 * The predecessor, Quick actions, was three fixed TEXT tiles. His order was
 * direct: *quick links to everything, as pure icon buttons that look like app
 * icons, no text labels, and the set has to be configurable.* All three parts
 * are here — the catalog in [BtQuickLink], the per-instance ordered list in
 * [BtQuickLinksConfig], and a grid that renders nothing but gold glyphs on
 * rounded squares.
 *
 * **This widget IS the old one.** `BtQuickActionsWidgetReceiver` keeps its class
 * name and its manifest entry on purpose: the framework addresses a placed
 * instance by ComponentName, so renaming the receiver would turn every
 * already-placed Quick-actions widget into a dead launcher placeholder the user
 * has to hunt down and delete. Only the Glance implementation behind it
 * changed, so an existing placement simply becomes an icon grid on its next
 * frame — with the defaults, because it never had a config to lose.
 *
 * ## Renditions (each composed, never scaled)
 *
 * The study's four are 3 icons at 2x1, 6 at 4x1, a 2x2 grid of 4, and a 4x2
 * grid of 8. Rather than a size-class table, the grid is derived from a minimum
 * tile PITCH ([btQuickLinksPerRow]) — this launcher hands out 3-column
 * placements the four named renditions do not describe, and those must get four
 * or five icons instead of falling back to the 2x1's three.
 *
 * ## Captions
 *
 * The study demonstrates an optional tiny caption under each tile, once, and
 * says `Off` by default. It ships as the per-instance `Beschriftung` toggle,
 * default off — icon-only is the design, captions are the escape hatch.
 *
 * ## Accessibility
 *
 * Every tile is icon-only, so the content description is the ONLY place its
 * destination is stated ([btQuickLinkDescription]). A monogram tile speaks its
 * portfolio's full name, never the letter.
 */
class BtQuickLinksWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    /** Everything the card needs, resolved OFF the path to the first frame. */
    private class Loaded(
        val local: Context,
        val snapshot: BtWidgetSnapshot,
        val colors: BtGlanceColors,
        val config: BtQuickLinksConfig,
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
                    // A failed read degrades to the DEFAULT set rather than to
                    // an empty card: this widget's unconfigured reading is a
                    // complete widget, which is the whole reason it can be
                    // configuration_optional.
                    config = btWidgetConfigOrNull("quick links") {
                        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
                        // No stored list yet ⇒ a just-pinned instance may have
                        // one waiting from the in-app builder. See BtWidgetPinning.
                        if (prefs[BT_WIDGET_PREF_LINKS].isNullOrEmpty()) {
                            btWidgetClaimPinnedQuickLinks(context, id) ?: btQuickLinksConfig(prefs)
                        } else {
                            btQuickLinksConfig(prefs)
                        }
                    } ?: BtQuickLinksConfig(BT_QUICK_LINKS_DEFAULT),
                )
            },
        ) { data ->
            BtWidgetCard(
                colors = data.colors,
                // The tiles are the actions; this only catches the GAPS between
                // them. A gap tap is a near-miss, so it must not navigate at
                // all — it just opens the app where the user left it, which is
                // the least surprising thing a missed tap can do (owner ruling
                // 2026-08-18; see btWidgetLaunchIntent).
                action = actionStartActivity(btWidgetLaunchIntent(context)),
                padding = CARD_PADDING,
            ) {
                Content(context, data.local, data.snapshot, data.config, data.colors)
            }
        }
    }

    @Composable
    private fun ColumnScope.Content(
        context: Context,
        local: Context,
        snapshot: BtWidgetSnapshot,
        config: BtQuickLinksConfig,
        colors: BtGlanceColors,
    ) {
        // The only non-action state. Nothing here renders account data, so
        // there is no syncing, empty or offline reading to have — a shortcut
        // grid works exactly as well with a cold cache.
        if (snapshot.session == BtWidgetSession.SIGNED_OUT) {
            BtWidgetMessage(local.getString(R.string.bt_widget_signed_out), colors, emphasis = true)
            return
        }

        val size = LocalSize.current
        val fs = btWidgetFontScale(local)
        val contentW = size.width.value - 2 * CARD_PADDING.value
        val contentH = size.height.value - 2 * CARD_PADDING.value

        // Two rows once the card is a real 2-cell tall square; one row on any
        // strip. ROW3+ keeps two rows rather than growing to three: eight tiles
        // is the configured maximum, and 4x2 is the study's densest grid.
        val rows = if (btWidgetRowClass(size.height.value) >= BtWidgetSizeClass.ROW2) 2 else 1
        val perRow = btQuickLinksPerRow(contentW, rows)
        val grid = btQuickLinksRows(config.actions, perRow, rows)
        if (grid.isEmpty()) {
            BtWidgetMessage(local.getString(R.string.bt_ql_empty), colors)
            return
        }

        // The SAME gap the capacity rule budgeted with — if the two disagreed,
        // the last tile of every full row would be clipped.
        val gap = btQuickLinkGap(rows)
        val captionDp = if (config.captions) btWidgetTextDp(9f, fs) + 3f else 0f
        // What one tile may claim vertically, after the row gaps and captions
        // are paid for…
        val heightBudget = (contentH - (grid.size - 1) * gap) / grid.size - captionDp
        // …and what it gets across, which is fixed by the weighted cell.
        val cellWidth = contentW / perRow - gap
        // The tile takes the SMALLER, so it stays SQUARE.
        //
        // This is not a nicety. Height alone gave a 2x1 tile 53dp across and
        // 76dp down (device QA 2026-08-17) — a vertical pill, and the owner's
        // whole brief was "icon buttons that look like app icons". App icons
        // are square, so a tile that is not square has already missed the
        // point no matter how good the glyph inside it is.
        val tileDp = minOf(heightBudget, cellWidth).coerceIn(TILE_MIN, TILE_MAX)

        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            grid.forEachIndexed { index, rowActions ->
                if (index > 0) Spacer(GlanceModifier.height(gap.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowActions.forEach { action ->
                        Cell(context, local, action, config.captions, tileDp, colors)
                    }
                    // A short last row keeps the CELL WIDTH of the full rows
                    // above it, so three tiles under four stay on the same
                    // column grid instead of stretching to fill the width. The
                    // filler is one empty weighted Box, well inside the ten
                    // children a RemoteViews container will keep.
                    val missing = perRow - rowActions.size
                    if (missing > 0 && grid.size > 1) {
                        Box(modifier = GlanceModifier.defaultWeight()) {}
                    }
                }
            }
        }
    }

    /**
     * One launcher tile: a rounded square on the neutral chip fill carrying a
     * gold glyph, or a portfolio's monogram in the same gold.
     *
     * The click sits on the TILE, not on a wrapper, so the hit area is exactly
     * the thing the user aimed at. The cell's own horizontal padding is the
     * grid gap — spacers between weighted children would work too, but they are
     * extra children in a container that silently drops the eleventh.
     */
    @Composable
    private fun RowScope.Cell(
        context: Context,
        local: Context,
        action: BtQuickLinkAction,
        captions: Boolean,
        tileDp: Float,
        colors: BtGlanceColors,
    ) {
        val label = local.getString(action.link.label)
        val description = btQuickLinkDescription(action, label)
        val base = GlanceModifier
            // A SQUARE, centred in its weighted cell — not fillMaxWidth, which
            // stretched the tile to the cell and produced the pill shape the
            // device pass rejected. The cell keeps the even grid; the tile
            // keeps the launcher-icon proportion.
            .size(tileDp.dp)
            .background(colors.chip)
            .clickable(
                actionStartActivity(
                    btWidgetIntent(
                        context,
                        action.link.target,
                        // The tile's AIM travels with the tap (owner
                        // 2026-08-18). Both are blank-tolerant, so an
                        // untargeted tile is byte-identical to the old
                        // parameterless intent. The source also makes the
                        // PendingIntent distinct, which is what lets three
                        // Cash tiles be three different taps rather than
                        // collapsing onto whichever was registered first.
                        portfolioId = action.portfolioId.takeIf { it.isNotBlank() },
                        sourceId = action.sourceId.takeIf { it.isNotBlank() },
                    ),
                ),
            )
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = (BT_QUICK_LINK_GAP_STRIP / 2f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The study's launcher-icon squircle, in proportion to the
                    // tile so a big grid does not read as a small one blown up.
                    base.cornerRadius((tileDp * CORNER_RATIO).coerceIn(8f, 18f).dp)
                } else {
                    base
                },
                contentAlignment = Alignment.Center,
            ) {
                // An AIMED tile paints its target's initial instead of the
                // catalog pictogram — three Cash tiles have to be three
                // different marks with captions off, which is the default.
                val monogram = btQuickLinkTileMonogram(action)
                if (monogram != null) {
                    Text(
                        text = monogram,
                        style = TextStyle(
                            color = colors.gold,
                            fontSize = (tileDp * MONOGRAM_RATIO).coerceIn(14f, 26f).sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                    )
                } else {
                    Image(
                        provider = ImageProvider(action.link.icon),
                        contentDescription = description,
                        // The glyphs ship white and are tinted here, so ONE
                        // drawable serves day gold, night gold and both forced
                        // themes — see the drawables' own header.
                        colorFilter = ColorFilter.tint(colors.gold),
                        modifier = GlanceModifier.size((tileDp * GLYPH_RATIO).coerceIn(16f, 30f).dp),
                    )
                }
            }
            if (captions) {
                Spacer(GlanceModifier.height(3.dp))
                Text(
                    // The TARGET when the tile has one: the caption's job is to
                    // say what differs, and "Cash" three times says nothing.
                    text = btQuickLinkCaption(action, label),
                    style = TextStyle(
                        color = colors.textMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }

    private companion object {
        /**
         * Tighter than the family's 12dp: this card is a grid of targets and
         * every dp of inset is a dp the tiles do not get. The study uses the
         * same reduced inset on its quick-link canvases.
         */
        val CARD_PADDING = 10.dp

        /** 44dp + the cell's own inset clears the 48dp minimum tap target. */
        const val TILE_MIN = 44f
        const val TILE_MAX = 76f

        /** The study's glyph-to-tile and monogram-to-tile proportions. */
        const val GLYPH_RATIO = 0.38f
        const val MONOGRAM_RATIO = 0.34f
        const val CORNER_RATIO = 0.23f
    }
}
