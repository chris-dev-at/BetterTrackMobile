package at.bettertrack.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Ignore-below-this jitter, in pixels of a single scroll delta. Without it a
 * fling's tail (and a fingertip's natural wobble) flickers the FAB in and out.
 */
private const val FAB_SCROLL_THRESHOLD_PX = 4f

/**
 * How long the list has to be still before a shrunken FAB grows back.
 *
 * Long enough to survive the gap between two drag gestures in one flick-flick
 * scroll (which would otherwise pump the FAB), short enough that stopping to
 * read never leaves you looking at the small one.
 */
private const val FAB_IDLE_EXPAND_MS = 550L

/**
 * The FAB-visibility rule as a pure function so it can be unit-tested without a
 * device (S6 P1-7).
 *
 * [deltaY] is the raw pre-scroll delta a [NestedScrollConnection] reports:
 * NEGATIVE while the finger drags upward, i.e. while the user scrolls DOWN into
 * the content — that is when the FAB gets out of the way. Dragging back down
 * (positive delta) brings it straight back. Anything inside the dead band leaves
 * the current state alone.
 */
fun nextFabVisible(
    current: Boolean,
    deltaY: Float,
    threshold: Float = FAB_SCROLL_THRESHOLD_PX,
): Boolean = when {
    deltaY < -threshold -> false
    deltaY > threshold -> true
    else -> current
}

/**
 * **The empty-state rule** (Fable design review, 2026-08-04 — binding, app-wide).
 *
 * A screen gets ONE create affordance at a time. When the list is empty, the
 * empty state carries the call to action and the FAB stands down; once there is
 * content, the FAB is the single entry point and the empty state is gone. Two
 * buttons that do the same thing, six inches apart, is the app failing to have
 * an opinion — and on an empty screen the FAB is the weaker of the two, because
 * it is a floating icon next to a paragraph that just explained what to do.
 *
 * [resolved] is "the screen knows what it has" — first load finished, state is
 * Loaded rather than Loading. An unresolved screen shows no FAB either: popping
 * one in over a skeleton only to pull it away when the list turns out to be
 * empty is the flicker this rule exists to prevent.
 *
 * Pure, and tested, for the reason `nextFabVisible` is: this is a rule, not a
 * layout, and a rule that can only be checked by looking at a phone is a rule
 * that quietly rots.
 */
fun fabVisibleForList(resolved: Boolean, empty: Boolean): Boolean = resolved && !empty

/**
 * The same rule over a resolved [BtListSurface]. Only CONTENT keeps the FAB:
 * every other surface either has nothing to add to yet (SKELETON), carries its
 * own single action (EMPTY's CTA, ERROR/OFFLINE's retry), or both.
 */
fun fabVisibleForList(surface: BtListSurface): Boolean = surface == BtListSurface.CONTENT

/**
 * Scroll-aware FAB visibility: hold this next to a scrollable, hang
 * [BtFabVisibility.nestedScroll] on that scrollable (or any ancestor of it), and
 * wrap the FAB in [BtFabVisibility.Content].
 *
 * The FAB is restored whenever the list settles back at the top, so a short list
 * that never scrolls can never end up with a hidden FAB.
 */
class BtFabVisibility internal constructor() {
    var visible: Boolean by mutableStateOf(true)
        internal set

    /**
     * When the last scroll delta arrived. Only [ShrinkingContent] reads it, to
     * decide when the scroll has actually STOPPED — see its "at rest" note.
     */
    internal var lastScrollAtMs: Long = 0L
        private set

    val nestedScroll: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            lastScrollAtMs = SystemClock.uptimeMillis()
            visible = nextFabVisible(visible, available.y)
            return Offset.Zero
        }
    }

    /** Re-show the FAB (called when the scrollable is back at the very top). */
    fun show() {
        visible = true
    }

    /**
     * The FAB as something that SHRINKS while scrolling instead of leaving.
     *
     * **Coordinator ruling on the owner's 2026-08-10 "should it be like that??"**
     * The hiding behaviour was itself a fix — the buy/sell FAB sat exactly over
     * the allocation legend's value column (S6 P1-7) — but it solved an overlap
     * by removing an action, and a primary action that disappears when you look
     * away from the top of the page is a control the user has to go hunting for.
     *
     * Shrinking keeps both properties: at rest the FAB is the full 56dp target it
     * always was, and while the finger is pulling content up it collapses to a
     * 40dp mini that clears the legend's numbers and is still perfectly tappable.
     * Nothing is ever unreachable, and nothing is ever covered.
     *
     * The size is animated rather than scaled with `graphicsLayer`, so the touch
     * target really is the size it looks — a scaled-down FAB keeps its original
     * 56dp hit rect and would go on eating taps meant for the row underneath.
     *
     * [fab] receives the size to draw at; the icon inside should size off it too.
     */
    @Composable
    fun ShrinkingContent(modifier: Modifier = Modifier, fab: @Composable (Dp) -> Unit) {
        val reducedMotion = rememberReducedMotion()

        // "…and expands at rest / scroll-up". The shared state machine only
        // knows about DIRECTION, which would leave the FAB mini for as long as
        // the reader sat still half-way down the page. Compact is a statement
        // about *motion*, so it ends when the motion does: once no delta has
        // arrived for [FAB_IDLE_EXPAND_MS], the FAB grows back on its own.
        //
        // A poll rather than a per-frame effect key: one coroutine per hide,
        // sleeping exactly as long as the remaining idle time, instead of a
        // cancel-and-relaunch on every frame of a fling.
        LaunchedEffect(visible) {
            while (!visible) {
                val idleFor = SystemClock.uptimeMillis() - lastScrollAtMs
                if (idleFor >= FAB_IDLE_EXPAND_MS) {
                    visible = true
                } else {
                    delay(FAB_IDLE_EXPAND_MS - idleFor)
                }
            }
        }

        val size by animateDpAsState(
            targetValue = if (visible) BT_FAB_SIZE else BT_FAB_MINI_SIZE,
            animationSpec = tween(if (reducedMotion) 0 else 180),
            label = "fabSize",
        )
        Box(modifier) { fab(size) }
    }

    /** The FAB itself, animated in/out with the shell's quiet motion language. */
    @Composable
    fun Content(modifier: Modifier = Modifier, fab: @Composable () -> Unit) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(tween(160)) + fadeIn(tween(160)),
            exit = scaleOut(tween(160), targetScale = 0.8f) + fadeOut(tween(160)),
            modifier = modifier,
        ) { fab() }
    }
}

@Composable
fun rememberBtFabVisibility(): BtFabVisibility = remember { BtFabVisibility() }

/** The resting FAB — Material's standard size, and the app's tap-target floor. */
val BT_FAB_SIZE: Dp = 56.dp

/**
 * The scrolling FAB. Material's own mini-FAB size, which is also exactly the
 * smallest thing this app is willing to ask a thumb to hit.
 */
val BT_FAB_MINI_SIZE: Dp = 40.dp

/**
 * The glyph inside the FAB at [size] — 24dp at rest, 20dp mini.
 *
 * Linear in the container so the icon keeps its optical weight instead of
 * rattling around inside a box that shrank without it. Pure, and pinned by
 * `BtFabVisibilityTest` for the same reason [nextFabVisible] is.
 */
fun btFabIconSize(size: Dp): Dp {
    val t = ((size - BT_FAB_MINI_SIZE) / (BT_FAB_SIZE - BT_FAB_MINI_SIZE)).coerceIn(0f, 1f)
    return BT_FAB_MINI_ICON + (BT_FAB_ICON - BT_FAB_MINI_ICON) * t
}

private val BT_FAB_ICON: Dp = 24.dp
private val BT_FAB_MINI_ICON: Dp = 20.dp
