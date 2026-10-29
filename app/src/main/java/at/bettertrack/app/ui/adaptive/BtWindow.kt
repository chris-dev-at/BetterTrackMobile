package at.bettertrack.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How wide the app's WINDOW is, in Material's three width classes (T1 large-screen
 * foundation, 2026-10-29).
 *
 * The window, not the device: a tablet in split screen is Compact, a phone in
 * landscape is Medium or Expanded. That is the property layout has to follow,
 * which is why the phone/tablet question the orientation default asks lives
 * somewhere else entirely ([at.bettertrack.app.data.prefs.BtDeviceClass]).
 *
 * - [Compact] — under 600dp. Every phone in portrait. The app as it always was:
 *   bottom bar, full-width pages, full-width page sheets.
 * - [Medium] — 600 to 839dp. A tablet in portrait (the owner's Tab S8 is ~753dp),
 *   a large phone in landscape. Navigation rail, capped content column.
 * - [Expanded] — 840dp and up. A tablet in landscape. Same shell as Medium with a
 *   wider column.
 */
enum class BtWidthClass { Compact, Medium, Expanded }

/** The lower bound of [BtWidthClass.Medium], in dp — Material's own breakpoint. */
const val BT_MEDIUM_MIN_WIDTH_DP: Int = 600

/** The lower bound of [BtWidthClass.Expanded], in dp — Material's own breakpoint. */
const val BT_EXPANDED_MIN_WIDTH_DP: Int = 840

/**
 * The width class for a window [widthDp] wide.
 *
 * Pure, so the breakpoints are pinned by a JVM test rather than by a tablet. Fed
 * from `WindowSizeClass.minWidthDp`, which is already a bucket's lower bound
 * (0 / 600 / 840); a raw window width maps through the same three comparisons.
 */
fun btWidthClassFor(widthDp: Int): BtWidthClass = when {
    widthDp < BT_MEDIUM_MIN_WIDTH_DP -> BtWidthClass.Compact
    widthDp < BT_EXPANDED_MIN_WIDTH_DP -> BtWidthClass.Medium
    else -> BtWidthClass.Expanded
}

/**
 * The widest a tab page's content column may grow, or `null` for "the whole
 * width" (Compact — unchanged from the phone app).
 *
 * The cap is what keeps a row's label and its value within one reading span: on
 * the owner's tablet the phone layout put ~900px of nothing between them. 680dp
 * in Medium and 760dp in Expanded hold a two-column row at the line length the
 * phone layout was designed around; the gutters absorb the rest.
 */
fun btContentMaxWidth(widthClass: BtWidthClass): Dp? = when (widthClass) {
    BtWidthClass.Compact -> null
    BtWidthClass.Medium -> 680.dp
    BtWidthClass.Expanded -> 760.dp
}

/**
 * The widest a full-height page sheet may be, or `null` for "the whole width".
 *
 * 640dp is Material's own `BottomSheetDefaults.SheetMaxWidth`, which every modal
 * bottom sheet in the app already wears — so a page sheet and a bottom sheet
 * opened from it share one edge instead of two.
 */
fun btSheetMaxWidth(widthClass: BtWidthClass): Dp? = when (widthClass) {
    BtWidthClass.Compact -> null
    BtWidthClass.Medium, BtWidthClass.Expanded -> 640.dp
}

/**
 * Everything the shell and its pages derive from the window's width — the ONE
 * source of truth for large-screen layout. Nothing in the app reads
 * `screenWidthDp` to make a layout decision; it reads this.
 */
@Immutable
data class BtWindow(val widthClass: BtWidthClass) {

    /** Medium and Expanded navigate from a left rail; Compact keeps the bottom bar. */
    val usesRail: Boolean get() = widthClass != BtWidthClass.Compact

    /** See [btContentMaxWidth]. */
    val contentMaxWidth: Dp? get() = btContentMaxWidth(widthClass)

    /** See [btSheetMaxWidth]. */
    val sheetMaxWidth: Dp? get() = btSheetMaxWidth(widthClass)

    companion object {
        /** The phone app — also what anything composed outside [ProvideBtWindow] sees. */
        val Compact: BtWindow = BtWindow(BtWidthClass.Compact)
    }
}

/**
 * The current [BtWindow]. Provided once, at the composition root, by
 * [ProvideBtWindow].
 *
 * Static rather than dynamic: the value changes only when the window crosses a
 * breakpoint (a rotation, a split-screen resize), and when it does the whole
 * layout below it is supposed to change — there is nothing to gain from
 * fine-grained invalidation. Defaulting to [BtWindow.Compact] means a preview, a
 * widget-configuration activity or anything else composed without the provider
 * renders exactly the phone layout it always did.
 */
val LocalBtWindow = staticCompositionLocalOf { BtWindow.Compact }

/**
 * Measure the window and provide [LocalBtWindow] to [content].
 *
 * `currentWindowAdaptiveInfo()` recomposes on every change of the window's
 * size (a freeform resize writes it every frame); the [remember] keyed on the
 * width CLASS means the provided value — and so the subtree under a static local
 * — only changes when a breakpoint is actually crossed.
 */
@Composable
fun ProvideBtWindow(content: @Composable () -> Unit) {
    val widthClass = btWidthClassFor(currentWindowAdaptiveInfo().windowSizeClass.minWidthDp)
    val window = remember(widthClass) { BtWindow(widthClass) }
    CompositionLocalProvider(LocalBtWindow provides window, content = content)
}

/**
 * A page's content column: full width in Compact, centred and capped at
 * [maxWidth] in Medium and Expanded, with the gutters on either side left to the
 * page's own background.
 *
 * Applied by the SHELL around every tab page — never inside a screen — so no
 * screen's rows change: a full-width `LazyColumn` inside it is simply laid out
 * against a narrower box.
 *
 * The node structure is the same in every width class on purpose. A Compact
 * branch that skipped the wrapper would put the page at a different position in
 * the composition the moment the window crossed 600dp, and Compose would treat
 * it as a NEW page — scroll positions, expanded sections and every `remember`
 * inside it gone on a split-screen resize. With one shape, the cap is just a
 * constraint that changes.
 *
 * `propagateMinConstraints` keeps what a pager page has always been given: exact
 * constraints, so a page root that does not say `fillMaxSize` still fills.
 */
@Composable
fun BtContentColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp? = LocalBtWindow.current.contentMaxWidth,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxHeight()
                .widthIn(max = maxWidth ?: Dp.Unspecified)
                .fillMaxWidth(),
            propagateMinConstraints = true,
        ) {
            content()
        }
    }
}
