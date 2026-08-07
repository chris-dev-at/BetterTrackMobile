package at.bettertrack.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.tabNeighbour
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

/**
 * How far the finger must travel before a release commits the hop, **as a
 * fraction of one page**.
 *
 * This is the launcher's rule — the owner's reference is the phone home screen
 * ("yk like on smartphone homepage", 2026-08-07): let go and you land on
 * whichever page is now mostly under you. Half a page is exactly "mostly", and
 * because the pages track the finger 1:1 (see [swipeFollowOffset]) the user can
 * see which side they are on before they release. A flick still commits early —
 * see [SWIPE_VELOCITY] — which is how the gesture stays quick despite the
 * threshold being half a screen rather than the 64dp Batch 1 used.
 *
 * 64dp was the right threshold when the page only nudged 40dp and the neighbour
 * was not drawn: with nothing to look at, a short decisive drag was all the user
 * could offer. Now that the neighbour is on screen and under the finger, a
 * threshold that small would commit while the incoming page was still a sliver,
 * which reads as the phone deciding for you.
 */
private const val SWIPE_COMMIT_FRACTION = 0.5f

/**
 * The commit threshold for the frame before the page area has been measured
 * (and under reduced motion, where nothing tracks the finger and there is no
 * "mostly under you" to read). Batch 1's constant, kept for exactly that gap.
 */
private val SWIPE_DISTANCE: Dp = 64.dp

/** A flick this fast commits even when it never travelled [SWIPE_COMMIT_FRACTION] of a page. */
private val SWIPE_VELOCITY: Dp = 400.dp

/**
 * The cap on how far the page follows the finger **at the ends of the bar**.
 *
 * There is no wrap-around: swipe left on People and there is no page to reveal.
 * The page still moves, damped and capped, because a gesture that does nothing
 * should still say it was heard — and because the damping itself is the message
 * ("this is the end"), which a dead screen would not give. Everywhere else the
 * follow is 1:1 and this constant is not consulted.
 */
private val SWIPE_EDGE_MAX: Dp = 40.dp

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
 * The distance a release must have covered to commit: half a page once the page
 * has been measured, [SWIPE_DISTANCE] before that.
 */
internal fun swipeCommitDistancePx(pageWidthPx: Float, fallbackPx: Float): Float =
    if (pageWidthPx > 0f) pageWidthPx * SWIPE_COMMIT_FRACTION else fallbackPx

/**
 * How far the outgoing page has moved, for a drag that has travelled [totalDx].
 *
 * **1:1 when there is a page to reveal.** Batch 1 damped this with `tanh` and
 * capped it at 40dp, and said why: the neighbouring tab was not composed, so a
 * full-width follow would have dragged a blank page into view. That is no longer
 * true — [BtTabPeekLayers] puts the real neighbour under the finger — and with a
 * real page there, damping would be a lie about where the release will land.
 * The launcher moves its pages exactly as far as the finger; so does this.
 *
 * The clamp is the seam: one page of travel is the whole hop, and going further
 * would pull a third page's worth of nothing in behind the second.
 *
 * **Damped at the ends of the bar**, where [hasNeighbour] is false — the
 * overscroll hint, unchanged from Batch 1. `tanh` is asymptotic, so the page
 * slides freely at first and firms up as it approaches [edgeMaxPx], and it never
 * overshoots the cap.
 *
 * Both zero (reduced motion) means the page does not move at all: the hop still
 * happens on release, it simply does not animate under the finger (§3.7).
 */
internal fun swipeFollowOffset(
    totalDx: Float,
    pageWidthPx: Float,
    edgeMaxPx: Float,
    hasNeighbour: Boolean,
): Float = when {
    hasNeighbour && pageWidthPx > 0f -> totalDx.coerceIn(-pageWidthPx, pageWidthPx)
    edgeMaxPx > 0f -> edgeMaxPx * tanh(totalDx / edgeMaxPx)
    else -> 0f
}

/**
 * Which side a live drag is revealing — `true` = the tab to the RIGHT — or
 * `null` when nothing is in flight.
 *
 * This is what keeps the peek layer OFF at rest. The neighbour is drawn only
 * while this is non-null, so an app sitting still composes and records exactly
 * one page, which is the perf gate this feature had to clear.
 */
internal fun swipePeekSide(totalDx: Float): Boolean? = when {
    totalDx < 0f -> true
    totalDx > 0f -> false
    else -> null
}

/**
 * Which tab the peek layer is showing, or `null` for "draw no second page".
 *
 * Pure, and the single place the answer is decided:
 *  - a committed hop pins the peek to its target until the NavHost has caught up
 *    (see [BtTabSwipeState.handoff]) — that pin is what removes the swap flash;
 *  - otherwise a live drag reveals its neighbour;
 *  - at rest, and at the ends of the bar where [tabNeighbour] has nothing to
 *    give, there is no second page.
 */
internal fun swipePeekTab(
    handoff: BtTab?,
    peekSide: Boolean?,
    current: BtTab?,
    visible: List<BtTab>,
): BtTab? {
    if (handoff != null) return handoff
    val forward = peekSide ?: return null
    val here = current ?: return null
    return tabNeighbour(here, forward, visible)
}

/**
 * Where the INCOMING page sits, given where the outgoing one is.
 *
 * Exactly one page away, on the side being revealed: hard-adjacent, edge to
 * edge, no gap and no parallax — the owner's reference is the phone home screen,
 * where the two pages are simply one strip that moves. Because both layers read
 * the same [BtTabSwipeState.pageOffsetPx], they cannot drift apart.
 *
 * [handedOff] pins the incoming page at rest: the drag is over, the pixels are
 * final, and the NavHost is being told to swap underneath. Letting the peek
 * follow the offset through that moment would slide it back off screen and
 * uncover the tab we are leaving — the flash this whole handoff exists to avoid.
 */
internal fun peekPageOffsetPx(
    pageOffsetPx: Float,
    pageWidthPx: Float,
    forward: Boolean,
    handedOff: Boolean,
): Float = when {
    handedOff -> 0f
    forward -> pageOffsetPx + pageWidthPx
    else -> pageOffsetPx - pageWidthPx
}

/**
 * How far the bottom bar's selection indicator leads a drag, **in item steps**.
 *
 * The rule is unchanged from Batch 1 — **the indicator travels the same fraction
 * of one item step that the page travelled of one page width** — but it has
 * stopped being an approximation. Batch 1 capped the page at 40dp, so a fully
 * committed drag led by 40/411 ≈ 0.097 of a step: honest about the nudge, but a
 * tenth of the truth. The pages now travel 1:1 ([swipeFollowOffset]), so a drag
 * that has moved the page half a screen moves the pill half a step, and a
 * completed hop lands it exactly one step along — which is where the nav graph
 * then holds it. §6.3's `currentPageOffsetFraction` by another road.
 *
 * [barWidthPx] stands in for the page width, which is sound because the bar and
 * the page are both the full width of the window. That was incidental when the
 * result was a nudge; it is load-bearing now, and it is why this takes the bar's
 * MEASURED width rather than a constant.
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
 * The tab drag, hoisted so that **both pages and the bottom bar move together**.
 *
 * One `Animatable`, three readers: the outgoing page, the incoming page and the
 * bar's indicator. A bar that reads a different number than the page is exactly
 * the "bar snaps while content slides" failure §6.3 set out to prevent, and two
 * pages that read different numbers would tear at the seam.
 *
 * [pageOffsetPx] is a **snapshot state read**: consume it inside a draw or
 * layout lambda (as both page layers and `BtBottomBar` do) and a drag frame
 * costs a redraw, not a recomposition.
 *
 * [peekSide] and [handoff] are the only fields read in COMPOSITION, and both
 * change at most a couple of times per gesture — which is what lets the second
 * page exist at all without making a drag frame recompose the shell.
 */
@Stable
internal class BtTabSwipeState {
    /** Signed displacement of the current page, in px. Negative = page moved left. */
    internal val offset = Animatable(0f)

    /** What the outgoing page is drawn at right now. */
    val pageOffsetPx: Float get() = offset.value

    /**
     * The measured width of the page area. Drives three things that must agree:
     * the 1:1 follow's clamp, the commit threshold, and the seam the incoming
     * page sits at.
     */
    var pageWidthPx: Float by mutableFloatStateOf(0f)

    /** Which side the live drag is revealing; `null` when nothing is in flight. */
    var peekSide: Boolean? by mutableStateOf(null)

    /**
     * The tab a committed hop is handing to the NavHost, from the moment the
     * page turn has finished animating until the NavHost is showing it.
     *
     * While this is set the visuals are already final, so the nav swap must not
     * animate — see `BtNavHost`, which returns `None` for all four transitions
     * while a handoff is in flight.
     */
    var handoff: BtTab? by mutableStateOf(null)
}

@Composable
internal fun rememberBtTabSwipeState(): BtTabSwipeState = remember { BtTabSwipeState() }

/**
 * Translate one page layer by the shared displacement.
 *
 * `layout` rather than `offset`: the page is DISPLACED, and its measured size
 * must not change while it is, or the whole page would relayout on every frame
 * of the drag. The state read happens in the PLACEMENT lambda, so a drag frame
 * costs a re-placement and a redraw — never a recomposition, never a re-measure.
 *
 * `placeRelative` (not `place`) keeps the two layers mirrored together under
 * RTL, which the app declares support for.
 */
internal fun Modifier.btTabPageOffset(offsetPx: () -> Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(IntOffset(offsetPx().roundToInt(), 0))
        }
    }

/**
 * Horizontal swipe between the four top-level tabs (owner ask, 2026-08-07;
 * "connected" paging per the 2026-08-07 clarification).
 *
 * ## Why this is a gesture layer and not a `HorizontalPager`
 *
 * A pager hosting the four tab composables would have to take those four routes
 * OUT of the [BtNavHost] graph, and with them the thing that makes tab switching
 * feel right: `popUpTo(start){saveState}` + `restoreState`, which remembers
 * **each tab's pushed screens**. Open a holding, hop to Markets, come back — the
 * holding is still there. A pager remembers page scroll but knows nothing about
 * a back stack, so that behaviour would have to be rebuilt by hand (or lost)
 * across 43 typed destinations, and the deep-link rule that switches to a link's
 * owning tab BEFORE pushing its detail would need a second implementation.
 *
 * ## How the neighbour is on screen without being composed twice
 *
 * Batch 1's honest cost was that the neighbour was not drawn at all, so the drag
 * showed a damped nudge of the outgoing page. The fix is NOT to compose the
 * neighbouring tab a second time in an overlay: outside the NavHost the
 * `ViewModelStoreOwner` is the Activity, so a second copy would build a second
 * ViewModel that never clears, re-run every `LaunchedEffect(Unit) { load() }`,
 * and — because segment selections and list scroll live in those ViewModels and
 * in per-instance `remember` — show a page in a *different state* than the tab
 * it claims to preview.
 *
 * Instead the neighbour is drawn from the **pixels it last drew**: every visible
 * tab records itself into a per-tab `GraphicsLayer` ([BtTabPeekLayers]), and the
 * peek replays that layer. One composition, one ViewModel, no extra requests,
 * and the peek is state-exact by construction — it is not a reconstruction of
 * the tab's state, it *is* the tab's last frame, scroll position, selected
 * segment and all. A tab never visited this process has no frame to replay and
 * peeks as the app's loading skeleton.
 *
 * ## Not fighting the charts
 *
 * Nothing here runs in the Initial pass, so children see every pointer first:
 * the hero chart's scrub (`detectHorizontalDragGestures` + `change.consume()`),
 * any `horizontalScroll` chip row and the LazyColumn's vertical scroll all
 * consume their drags, and a consumed change makes this layer's slop detection
 * abort before it starts. Scrubbing therefore wins on the chart area, which is
 * the behaviour the owner asked to preserve.
 *
 * @param state the shared displacement — see [BtTabSwipeState].
 * @param enabled top-level pages only — never on a pushed screen.
 * @param neighbourOf the tab a hop in this direction would land on, or `null` at
 *   the ends of the bar. Consulted DURING the drag as well as on release,
 *   because it is what decides between a 1:1 follow and the damped edge hint.
 * @param onCommit switch to this tab. Called only after the page turn has
 *   finished animating, so the NavHost swaps under a picture that is already
 *   correct.
 */
@Composable
internal fun Modifier.btTabSwipe(
    state: BtTabSwipeState,
    enabled: Boolean,
    neighbourOf: (forward: Boolean) -> BtTab?,
    onCommit: (BtTab) -> Unit,
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val offset = state.offset
    val onCommitState = rememberUpdatedState(onCommit)
    val neighbourState = rememberUpdatedState(neighbourOf)
    val fallbackDistancePx = with(density) { SWIPE_DISTANCE.toPx() }
    val velocityPx = with(density) { SWIPE_VELOCITY.toPx() }
    // Reduced motion keeps the gesture and drops the follow — the hop still
    // happens, it simply does not animate under the finger (§3.7). Both widths
    // go to zero, which is the one input that makes swipeFollowOffset return 0.
    val edgeMaxPx = if (reducedMotion) 0f else with(density) { SWIPE_EDGE_MAX.toPx() }

    this
        .onSizeChanged { state.pageWidthPx = it.width.toFloat() }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            var total = 0f
            val tracker = VelocityTracker()
            // Crisp, not bouncy: a home screen page arrives and stops. Batch 1
            // used MediumBouncy, which reads fine on a 40dp nudge and reads like
            // a rubber band on a full-width page.
            val pageSpring = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            val settle: suspend () -> Unit = {
                offset.animateTo(0f, pageSpring)
                // Only now: the neighbour stays drawn while it slides back out.
                state.peekSide = null
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
                    val pageWidth = state.pageWidthPx
                    val commitPx = swipeCommitDistancePx(
                        if (reducedMotion) 0f else pageWidth,
                        fallbackDistancePx,
                    )
                    val outcome = tabSwipeOutcome(total, velocity, commitPx, velocityPx)
                    total = 0f
                    val forward = when (outcome) {
                        TabSwipe.None -> null
                        TabSwipe.Forward -> true
                        TabSwipe.Back -> false
                    }
                    val target = forward?.let { neighbourState.value(it) }
                    scope.launch {
                        if (forward == null || target == null) {
                            // Nothing to commit to, including the ends of the
                            // bar: spring back so the page never rests off-centre.
                            settle()
                            return@launch
                        }
                        val followWidth = if (reducedMotion) 0f else pageWidth
                        if (followWidth > 0f) {
                            // 1. Finish the page turn. The incoming page rides
                            //    the same offset, so it arrives at rest exactly
                            //    as the outgoing one leaves.
                            offset.animateTo(
                                if (forward) -followWidth else followWidth,
                                pageSpring,
                                initialVelocity = velocity,
                            )
                            // 2. Pin the picture and tell the NavHost. The pin
                            //    is what lets step 3 happen invisibly.
                            state.handoff = target
                            onCommitState.value(target)
                            // 3. Put the (still-old, still-covered) NavHost back
                            //    at rest so the new destination composes where it
                            //    belongs. The shell drops the pin once the
                            //    NavHost is actually showing `target`.
                            offset.snapTo(0f)
                        } else {
                            // Reduced motion: nothing moved, so there is nothing
                            // to hand over and no peek to keep on screen.
                            onCommitState.value(target)
                            state.peekSide = null
                            offset.snapTo(0f)
                        }
                    }
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    total += dragAmount
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val side = swipePeekSide(total)
                    // A composition-visible write, but only when the direction
                    // actually flips — once per gesture in the normal case.
                    if (side != state.peekSide && state.handoff == null) state.peekSide = side
                    val follow = swipeFollowOffset(
                        totalDx = total,
                        pageWidthPx = if (reducedMotion) 0f else state.pageWidthPx,
                        edgeMaxPx = edgeMaxPx,
                        hasNeighbour = side != null && neighbourState.value(side) != null,
                    )
                    scope.launch { offset.snapTo(follow) }
                },
            )
        }
}
