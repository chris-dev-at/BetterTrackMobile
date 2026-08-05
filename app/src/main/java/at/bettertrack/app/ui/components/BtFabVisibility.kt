package at.bettertrack.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Ignore-below-this jitter, in pixels of a single scroll delta. Without it a
 * fling's tail (and a fingertip's natural wobble) flickers the FAB in and out.
 */
private const val FAB_SCROLL_THRESHOLD_PX = 4f

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

    val nestedScroll: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            visible = nextFabVisible(visible, available.y)
            return Offset.Zero
        }
    }

    /** Re-show the FAB (called when the scrollable is back at the very top). */
    fun show() {
        visible = true
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
