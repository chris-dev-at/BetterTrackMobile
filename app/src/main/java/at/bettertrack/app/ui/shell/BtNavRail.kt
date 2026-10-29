package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.components.BtTabBadgeDot
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The navigation rail — the SAME four tabs as [BtBottomBar], presented down the
 * left edge on Medium and Expanded windows (T1 large-screen foundation,
 * 2026-10-29).
 *
 * ## One tab model, two presentations
 *
 * The rail takes exactly the arguments the bar takes, from exactly the same
 * values in [BtApp]: the visible-tab list (so the storage-mode gating is the
 * bar's gating), the tap-latched selected index, the pager, the pinned index,
 * the badge predicate and the one `onSelect` that already routes a re-tap of
 * Portfolio to the switcher. Nothing about navigation is decided here; the rail
 * only draws. That is what keeps deep links, badges and the pager honest on both
 * shells — there is no second tab model for them to disagree with.
 *
 * ## The same selection vocabulary
 *
 * - **One pill, drawn once, travelling.** The items' own indicators are switched
 *   off and a single 48×32 pill ([NAV_INDICATOR_WIDTH] × [NAV_INDICATOR_HEIGHT])
 *   is painted in this composable's `drawBehind`, reading the pager's continuous
 *   position in the DRAW phase. On the bar it travels sideways between icon
 *   centres; here it travels down, between the same measured centres, through
 *   the same [tabPillX] interpolation — a swipe of the pages moves it frame for
 *   frame, a tap pins it at its destination.
 * - **Same fills.** `goldWashStrong` for the pill, with the `goldEdge` ring in
 *   light only; `goldInk` / `textMuted` ink with the selected label carrying
 *   SemiBold weight rather than colour alone — every reason [BtBottomBar] gives
 *   for these applies unchanged.
 * - **Same badge.** [BtTabBadgeDot] at the glyph's top-right, ring on the pill
 *   for the selected tab.
 *
 * ## Container and edge
 *
 * The rail's tone is the bar's `navBar` token, painted under the pill (the M3
 * container is transparent so the pill layer can sit between the two). Its
 * trailing edge is `groupBorder` — the token the bar's top divider uses: nothing
 * in dark, where the tone step separates it, and a real hairline in light.
 *
 * ## Insets
 *
 * `WideNavigationRailDefaults.windowInsets` — system bars and the display cutout
 * on the top, bottom and start edges — so the first item clears the status bar,
 * the last clears the Samsung taskbar, and a landscape phone's side navigation
 * bar never covers a label. The tone still runs edge to edge behind all three.
 * The shell consumes the same start inset for the content beside the rail, so
 * nothing to the right pays it twice.
 *
 * Collapsed `WideNavigationRail` (96dp, labels under icons) rather than the
 * classic `NavigationRail`: it is the expressive rail in this BOM's material3
 * 1.4.0, it is stable API, and its 64dp item height gives every tab a target
 * well past 48dp.
 */
@Composable
internal fun BtNavRail(
    tabs: List<TabSpec>,
    /** Which tab wears the ink and the selected weight, right now. */
    selectedIndex: Int,
    /** The pager, read in the draw phase so a drag frame never recomposes the rail. */
    pager: PagerState,
    /** Hold the pill here instead of interpolating — a tap in flight. */
    pinnedIndex: Int?,
    hasBadge: (TabSpec) -> Boolean,
    onSelect: (TabSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val density = LocalDensity.current

    // Root coordinates for the same reason as the bar's: onGloballyPositioned
    // fires children-first, so the subtraction happens at draw time, when both
    // the rail's origin and every icon's centre are known.
    var railOriginX by remember { mutableFloatStateOf(Float.NaN) }
    var railOriginY by remember { mutableFloatStateOf(Float.NaN) }
    val iconCentres = remember { mutableStateMapOf<Int, Offset>() }

    val indicatorW = with(density) { NAV_INDICATOR_WIDTH.toPx() }
    val indicatorH = with(density) { NAV_INDICATOR_HEIGHT.toPx() }
    val ringPx = with(density) { 1.dp.toPx() }

    // One column, so any laid-out icon answers where the pill sits horizontally.
    val centreX: Float? = tabs.indices.firstNotNullOfOrNull { iconCentres[it]?.x }

    Row(modifier.fillMaxHeight()) {
        WideNavigationRail(
            modifier = Modifier
                .onGloballyPositioned {
                    val p = it.positionInRoot()
                    railOriginX = p.x
                    railOriginY = p.y
                }
                .drawBehind {
                    // The container, painted here so the single pill layer can
                    // sit between it and the items. Full node rect: the tone runs
                    // behind the status bar and the taskbar.
                    drawRect(bt.navBar)

                    val x0 = centreX ?: return@drawBehind
                    if (railOriginY.isNaN()) return@drawBehind

                    // The one state read that makes the pill travel, in the draw
                    // lambda — a drag frame redraws this node and recomposes
                    // nothing. See BtBottomBar for the full argument.
                    val pos = tabPagerPosition(
                        pager.currentPage,
                        pager.currentPageOffsetFraction,
                        tabs.size,
                    )
                    val y0 = pinnedIndex?.let { iconCentres[it]?.y }
                        ?: tabPillX(pos, tabs.size) { iconCentres[it]?.y }
                        ?: return@drawBehind
                    // Clamped to the outermost centres, over `tabs.indices` so a
                    // storage-mode change that shortens the rail cannot clamp
                    // against two stale entries.
                    val laid = tabs.indices.mapNotNull { iconCentres[it]?.y }
                    val minY = laid.minOrNull() ?: return@drawBehind
                    val maxY = laid.maxOrNull() ?: return@drawBehind
                    val x = x0 - railOriginX
                    val y = y0.coerceIn(minY, maxY) - railOriginY

                    val topLeft = Offset(x - indicatorW / 2f, y - indicatorH / 2f)
                    val size = Size(indicatorW, indicatorH)
                    val radius = CornerRadius(indicatorH / 2f)
                    drawRoundRect(bt.goldWashStrong, topLeft, size, radius)
                    if (bt.isLight) {
                        drawRoundRect(bt.goldEdge, topLeft, size, radius, style = Stroke(ringPx))
                    }
                },
            // Painted in drawBehind above; M3 must not lay its own opaque
            // container over the indicator layer.
            colors = WideNavigationRailDefaults.colors(containerColor = Color.Transparent),
        ) {
            tabs.forEachIndexed { index, tab ->
                // 0f/1f in composition, exactly like the bar: the pill carries the
                // travel in the draw phase; the ink and the weight flip at the
                // pager's own `currentPage`.
                val selected = index == selectedIndex
                val ink = lerp(bt.textMuted, bt.goldInk, if (selected) 1f else 0f)
                WideNavigationRailItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    railExpanded = false,
                    icon = {
                        Box(
                            Modifier.onGloballyPositioned {
                                val p = it.positionInRoot()
                                iconCentres[index] = Offset(
                                    p.x + it.size.width / 2f,
                                    p.y + it.size.height / 2f,
                                )
                            },
                        ) {
                            Icon(tab.icon, contentDescription = null)
                            // The bar's placement, verbatim — it is measured
                            // against the 48×32 pill, which is the same pill here.
                            BtTabBadgeDot(
                                show = hasBadge(tab),
                                onIndicator = selected,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = 0.dp),
                            )
                        }
                    },
                    label = {
                        Text(
                            text = stringResource(tab.labelRes),
                            style = BtTheme.type.labelNav,
                            fontWeight = if (selected) FontWeight.SemiBold else null,
                        )
                    },
                    colors = WideNavigationRailItemDefaults.colors(
                        // Both states carry the same ink, so M3 cannot re-introduce
                        // the per-item cross-fade the single pill exists to remove.
                        selectedIconColor = ink,
                        selectedTextColor = ink,
                        selectedIndicatorColor = Color.Transparent,
                        unselectedIconColor = ink,
                        unselectedTextColor = ink,
                    ),
                )
            }
        }
        // The trailing edge: the bar's divider token, turned on its side.
        VerticalDivider(thickness = 1.dp, color = bt.groupBorder)
    }
}
