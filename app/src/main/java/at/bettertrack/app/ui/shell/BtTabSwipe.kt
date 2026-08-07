package at.bettertrack.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.components.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * What a finished horizontal drag over a top-level tab means.
 *
 * [Forward] is "the finger travelled LEFT", which reveals the tab to the RIGHT —
 * the pager idiom, and the owner's wording: *"portfolio swipe left goes to assets
 * and reverse"*.
 */
internal enum class TabSwipe { None, Forward, Back }

/** How far the finger must travel before a release commits the hop. */
private val SWIPE_DISTANCE: Dp = 64.dp

/** A flick this fast commits even when it never travelled [SWIPE_DISTANCE]. */
private val SWIPE_VELOCITY: Dp = 400.dp

/**
 * The cap on how far the outgoing page follows the finger.
 *
 * The page moves because a gesture that changes what you are looking at should
 * show that it is being recognised BEFORE you let go — that is the whole of the
 * "pager feel" the owner asked for. It is capped and damped rather than 1:1
 * because the neighbouring tab is not composed yet (see [btTabSwipe]), so a
 * full-width follow would drag a blank page into view.
 */
private val SWIPE_FOLLOW_MAX: Dp = 40.dp

/**
 * Whether a completed drag commits a tab hop, and in which direction. Pure, so
 * every branch is pinned by unit tests rather than by a device gesture.
 *
 * Distance OR velocity is enough — but a fling only counts when it is still
 * travelling the way the drag went, so that a swipe you drag left and then flick
 * back to the right lands where you left it rather than where you started.
 */
internal fun tabSwipeOutcome(
    totalDx: Float,
    velocityX: Float,
    distanceThresholdPx: Float,
    velocityThresholdPx: Float,
): TabSwipe {
    if (totalDx == 0f) return TabSwipe.None
    val forward = totalDx < 0f
    val farEnough = abs(totalDx) >= distanceThresholdPx
    val flung = abs(velocityX) >= velocityThresholdPx && (velocityX < 0f) == forward
    if (!farEnough && !flung) return TabSwipe.None
    return if (forward) TabSwipe.Forward else TabSwipe.Back
}

/**
 * Damped follow: asymptotic, so the page slides freely at first and firms up as
 * it approaches [maxPx]. `tanh` gives that for free and never overshoots the cap.
 */
internal fun swipeFollowOffset(totalDx: Float, maxPx: Float): Float =
    if (maxPx <= 0f) 0f else maxPx * tanh(totalDx / maxPx)

/**
 * How far the bottom bar's selection indicator leads a drag, **in item steps**.
 *
 * This is the whole of B2 §6.3's adaptation to a gesture layer, and it is one
 * division on purpose. §6.3 was written for a `HorizontalPager`, whose
 * `currentPageOffsetFraction` hands you the answer; there is no pager here (see
 * [btTabSwipe]), so the fraction has to be derived from the only thing that
 * actually moved — the page.
 *
 * The rule: **the indicator travels the same fraction of one item step that the
 * page travelled of one page width.** [pageOffsetPx] is capped and damped at
 * `SWIPE_FOLLOW_MAX`, so on a 411dp-wide screen a fully committed drag leads by
 * 40/411 ≈ 0.097 of a step — a nudge, matching the page's own nudge.
 *
 * That proportion is the point, and it is why this is not simply `±1f`. Letting
 * the pill run a whole step while the page moves a tenth of one would put the
 * bar somewhere the content never went: a bar that lies about the gesture, which
 * is worse than a bar that does not move at all. When a real pager lands, the
 * shell starts returning true intermediate values from `selectionFraction` and
 * this helper stops being needed.
 *
 * The sign inverts because content and indicator travel opposite ways — dragging
 * the finger LEFT moves the page left and reveals the tab to the RIGHT, so the
 * indicator must go right. That is the pager idiom, and getting it backwards is
 * the single most likely way to break this.
 *
 * @param barWidthPx the bar's measured width; `0` (not yet laid out) disables
 *   the lead rather than dividing by zero.
 */
internal fun tabIndicatorLead(pageOffsetPx: Float, barWidthPx: Float): Float =
    if (barWidthPx <= 0f) 0f else -pageOffsetPx / barWidthPx

/**
 * The tab drag, hoisted so that **the page and the bottom bar move together**.
 *
 * The displacement used to be a private `Animatable` inside [btTabSwipe], which
 * was right while the page was the only thing that moved. §6.3 asks the bar's
 * selection indicator to travel with the gesture rather than snap after it, and
 * a bar that reads a different number than the page is exactly the "bar snaps
 * while content slides" failure the spec set out to prevent. One `Animatable`,
 * two readers.
 *
 * [pageOffsetPx] is a **snapshot state read**: consume it inside a draw or
 * layout lambda (as `BtBottomBar` does) and a drag frame costs a redraw, not a
 * recomposition of the bar.
 */
@Stable
internal class BtTabSwipeState {
    /** Signed damped displacement of the current page, in px. Negative = page moved left. */
    internal val offset = Animatable(0f)

    /** What the page is drawn at right now. */
    val pageOffsetPx: Float get() = offset.value
}

@Composable
internal fun rememberBtTabSwipeState(): BtTabSwipeState = remember { BtTabSwipeState() }

/**
 * Horizontal swipe between the four top-level tabs (owner ask, 2026-08-07).
 *
 * ## Why this is a gesture layer and not a `HorizontalPager`
 *
 * A pager hosting the four tab composables would have to take those four routes
 * OUT of the [BtNavHost] graph, and with them the thing that makes tab switching
 * feel right today: `popUpTo(start){saveState}` + `restoreState`, which remembers
 * **each tab's pushed screens**. Open a holding, hop to Markets, come back — the
 * holding is still there. A pager remembers page scroll state but knows nothing
 * about a back stack, so that behaviour would have to be rebuilt by hand (or
 * lost) across 43 typed destinations, and the deep-link rule that switches to a
 * link's owning tab BEFORE pushing its detail would need a second implementation.
 *
 * A gesture layer buys the same user-visible outcome — drag, page turns, bar
 * follows — while [switchToTab] stays the single way a tab is ever entered, so
 * saved state, deep-link owning-tab semantics and bottom-bar selection sync are
 * not re-implemented at all. The honest cost: the neighbouring tab is not
 * composed until the hop commits, so the drag shows a damped nudge of the
 * outgoing page rather than the incoming one tracking the finger 1:1. The page
 * turn itself is the lateral slide in [BtNavMotion].
 *
 * ## Not fighting the charts
 *
 * Nothing here runs in the Initial pass, so children see every pointer first: the
 * hero chart's scrub (`detectHorizontalDragGestures` + `change.consume()`), any
 * `horizontalScroll` chip row and the LazyColumn's vertical scroll all consume
 * their drags, and a consumed change makes this layer's slop detection abort
 * before it starts. Scrubbing therefore wins on the chart area, which is the
 * behaviour the owner asked to preserve.
 *
 * @param state the shared displacement — see [BtTabSwipeState]. The bottom bar
 *   reads the same object, which is what keeps the indicator and the page from
 *   telling the user two different stories about one gesture.
 * @param enabled top-level pages only — never on a pushed screen.
 * @param onSwipe returns `true` when the hop actually happened; `false` (an end
 *   of the bar) springs the page back instead of leaving it displaced.
 */
@Composable
internal fun Modifier.btTabSwipe(
    state: BtTabSwipeState,
    enabled: Boolean,
    onSwipe: (forward: Boolean) -> Boolean,
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val offset = state.offset
    val onSwipeState = rememberUpdatedState(onSwipe)
    val distancePx = with(density) { SWIPE_DISTANCE.toPx() }
    val velocityPx = with(density) { SWIPE_VELOCITY.toPx() }
    // Reduced motion keeps the gesture and drops the follow — the hop still
    // happens, it simply does not animate under the finger (§3.7).
    val followMaxPx = if (reducedMotion) 0f else with(density) { SWIPE_FOLLOW_MAX.toPx() }

    this
        // `layout` rather than `offset`: the page is DISPLACED for feedback, and
        // its measured size must not change while it is, or the tab underneath
        // would relayout on every frame of the drag.
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(IntOffset(offset.value.roundToInt(), 0))
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var total = 0f
            val tracker = VelocityTracker()
            val settle: suspend () -> Unit = {
                offset.animateTo(
                    0f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                )
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    total = 0f
                    tracker.resetTracking()
                },
                onDragCancel = {
                    total = 0f
                    scope.launch { settle() }
                },
                onDragEnd = {
                    val velocity = tracker.calculateVelocity().x
                    val outcome = tabSwipeOutcome(total, velocity, distancePx, velocityPx)
                    total = 0f
                    val hopped = when (outcome) {
                        TabSwipe.None -> false
                        TabSwipe.Forward -> onSwipeState.value(true)
                        TabSwipe.Back -> onSwipeState.value(false)
                    }
                    scope.launch {
                        // A committed hop hands the animation to the nav slide, so
                        // the displacement is dropped instantly; anything else
                        // springs back so the page never rests off-centre.
                        if (hopped) offset.snapTo(0f) else settle()
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    total += dragAmount
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offset.snapTo(swipeFollowOffset(total, followMaxPx)) }
                },
            )
        }
}
