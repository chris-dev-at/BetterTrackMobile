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
 * The tab the NEXT gesture starts from — which is not always the tab the nav
 * graph is showing.
 *
 * ## Why the nav graph is the last word, not the first (owner report 2026-08-07)
 *
 * *"If you swipe too fast left or right — imagine you want to go 3 pages left
 * fast — it sometimes bugs and doesn't go a page forward."*
 *
 * A hop is DECIDED the moment a drag is released past the threshold, but the nav
 * graph only learns about it after the page turn has animated and a back-stack
 * `StateFlow` has propagated. A second flick arriving inside that window asked
 * `tabNeighbour` where to go and got the answer for the tab the user had already
 * left — so three fast flicks from Portfolio all resolved to Markets and the
 * chain collapsed to a single page. Measured on device before the fix: three
 * flicks at a 117ms cadence advanced exactly one page, 10 trials out of 10.
 *
 * So a gesture starts from the newest DECISION, and only falls back to the nav
 * graph when there is no decision in flight. That is what lets rapid flings
 * chain: each one steps off where the previous one landed, not where the screen
 * happens to have caught up to.
 *
 * A TAP is a decision by the same argument. Since the optimistic tap latch
 * landed (see [tapLatchHolds]) a bar tap navigates and lights its tab in the
 * same frame, while the nav graph still trails by ~50ms; a swipe that starts
 * inside that window must step off the tab the user just TAPPED, not the one
 * they tapped away from. It sits below the two swipe decisions and above the
 * nav graph, which is exactly its age: newer than the coordinate, older than
 * anything a finger is doing right now.
 *
 * @param pendingCommit a hop whose page turn is still animating
 *   ([BtTabSwipeState.pendingCommit]) — the newest decision there is.
 * @param handoff a hop already handed to the NavHost, which has not yet drawn it
 *   ([BtTabSwipeState.handoff]).
 * @param tapCommit a bar tap the nav graph has not caught up to yet.
 * @param navCurrent the tab the nav graph reports, which trails all three.
 */
internal fun swipeOriginTab(
    pendingCommit: BtTab?,
    handoff: BtTab?,
    tapCommit: BtTab?,
    navCurrent: BtTab?,
): BtTab? = pendingCommit ?: handoff ?: tapCommit ?: navCurrent

/**
 * Whether an optimistic TAP latch is still believable.
 *
 * ## Why the tap needed a latch of its own (owner report 2026-08-08)
 *
 * *"Tapping a bottom-nav tab lags before selection."* Measured on device at
 * 120Hz before this fix: touch-UP to the label turning gold ran 44–59ms, median
 * 48ms — and the recording emitted NO frame in between, so the tap had no
 * visual acknowledgment at all until the whole selection flipped at once. The
 * cause is structural, not slow code: the bar's selection was derived purely
 * from the nav graph, so a tap had to complete `navigate()`, propagate a
 * back-stack `StateFlow` AND compose the destination before the pill could
 * move. The heavier the destination, the later the tab lit — the selection was
 * paying for the content.
 *
 * So a tap now writes its target here and the bar believes it immediately, and
 * the content follows on its own schedule. This is the same trick [handoff]
 * plays for a committed swipe, but deliberately NOT the same field: [handoff]
 * also pins the peek layer over the swap and tells `BtNavHost` to suppress its
 * transitions, and a tap has no frozen peek layer to show — reusing it would
 * uncover a second page that was never prepared.
 *
 * The latch may not outlive its own truth, so it holds only while the nav graph
 * is still where the tap LEFT it:
 *
 *  - nav reports the target → it agreed, the latch has done its job and goes;
 *  - nav reports [origin] → still in flight, hold;
 *  - nav reports some THIRD tab → something else drove navigation (a deep link,
 *    a back press), the tap's opinion is stale, drop it at once rather than
 *    lighting a tab nobody is on;
 *  - nav reports null → a pushed detail screen, where the bar is not drawn at
 *    all; hold, because the tab underneath is still the right answer for when
 *    it comes back.
 *
 * @param target the tab the tap asked for.
 * @param origin the tab the nav graph was reporting when the tap happened.
 * @param navCurrent the tab the nav graph reports now.
 */
internal fun tapLatchHolds(target: BtTab, origin: BtTab?, navCurrent: BtTab?): Boolean = when {
    navCurrent == target -> false
    navCurrent == null -> true
    else -> navCurrent == origin
}

/**
 * What a new drag does to a page that is still moving.
 *
 * ## Gestures are accepted at any moment (owner report 2026-08-07)
 *
 * Standard pager behaviour, and the second half of the fast-swipe bug: a finger
 * arriving mid-animation must TAKE OVER, never be swallowed and never be made to
 * wait. Two cases, and they differ in where the new drag's zero is:
 *
 *  - **A hop is already decided** ([pendingCommit] non-null — the page turn is
 *    running). The hop is not up for renegotiation: it is delivered right now,
 *    and the new drag starts from a clean zero **rebased on the tab it lands
 *    on**. Carrying the old displacement across would put the finger most of a
 *    page along a hop it never made.
 *
 *  - **Nothing is decided** (a spring-back is running). The new drag continues
 *    from wherever the page currently IS, so it neither jumps nor loses the
 *    ground the last drag covered. This is also what makes the release honest:
 *    [swipeCommitDistancePx] asks "is this page mostly under you", and it can
 *    only answer that if the seed is the page's real position.
 *
 * Returning the seed rather than mutating anything keeps the rule testable
 * without a device — which matters, because it is the rule the bug was in.
 *
 * @param liveOffsetPx where the page is drawn at the instant the drag starts.
 * @return [SwipeTakeover.deliver] = a hop to hand over immediately, or null;
 *   [SwipeTakeover.startTotalPx] = the new drag's starting displacement.
 */
internal fun swipeTakeover(pendingCommit: BtTab?, liveOffsetPx: Float): SwipeTakeover =
    if (pendingCommit != null) {
        SwipeTakeover(deliver = pendingCommit, startTotalPx = 0f)
    } else {
        SwipeTakeover(deliver = null, startTotalPx = liveOffsetPx)
    }

/** The answer [swipeTakeover] gives. */
internal data class SwipeTakeover(val deliver: BtTab?, val startTotalPx: Float)

/**
 * Whether the peek layer must OUTLIVE the handoff pin that is being released.
 *
 * The pin drops two frames after the NavHost draws the committed tab. Before the
 * fast-swipe fix that instant was always the quiet end of a gesture, so tearing
 * the second page down with it was free. Now a hop is handed over the moment the
 * NEXT finger lands, so the pin can drop while the swipe stack is still busy —
 * and in both of those cases the neighbour is still being looked at:
 *
 *  - [dragging]: a finger is down and the page is under it. Blanking the
 *    neighbour would punch a hole in the page being dragged for the one frame
 *    before the next drag event rewrites the side.
 *  - [hopInFlight]: a released flick's page turn is still animating. The
 *    incoming page IS the peek; dropping it mid-turn would slide the outgoing
 *    page off over nothing.
 */
internal fun peekSurvivesHandoffEnd(dragging: Boolean, hopInFlight: Boolean): Boolean =
    dragging || hopInFlight

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
 * Which tab the bottom bar should show as selected, as a 0f/1f fraction.
 *
 * ## The arbiter, and the latch (owner report 2026-08-07)
 *
 * *"You swipe, it shows the swap correctly on the bottom, then it jumps back for
 * a brief second where you used to be, then goes back to where it should be."*
 *
 * Normally the NAV GRAPH owns the settled selection — [isCurrentDestination] is
 * the whole answer. But a committed swipe writes [committed], calls
 * `switchToTab` and snaps the page offset to zero in one breath, while the nav
 * graph reports its new destination through a back-stack `StateFlow` that lands a
 * frame or more later. In that window `isCurrentDestination` is still true for
 * the tab the user just LEFT, and the bar used to believe it: the pill, the icon
 * tint and the label tint all snapped backwards and then sprang forwards again.
 * Measured on device at 120Hz before the fix: five visible regressions across ten
 * rapid commits, each parked on the old tab for 91–142ms.
 *
 * So while a hop is in flight the bar believes the COMMIT. This cannot drift from
 * the nav graph, because the shell only clears [committed] once `currentTab` has
 * actually become that tab — the latch can be wrong for exactly as long as the
 * nav graph takes to agree, and not one frame longer.
 *
 * Since 2026-08-08 a TAP writes this too, for the same reason and with the same
 * discipline — see [tapLatchHolds], which is what stops the tap's opinion
 * outliving the nav graph's disagreement. The two sources never fight: a swipe
 * handoff outranks a tap latch at the call site, and either way this function
 * only ever sees the one answer the shell has already settled on.
 *
 * @param committed the tab the shell has committed to ahead of the nav graph —
 *   a swipe's [BtTabSwipeState.handoff] or an optimistic tap latch — or null
 *   when nothing is in flight and the coordinate is the whole truth.
 * @param isCurrentDestination whether the nav graph currently reports [tab].
 */
internal fun tabSelectionFraction(
    committed: BtTab?,
    tab: BtTab,
    isCurrentDestination: Boolean,
): Float = when {
    committed != null -> if (tab == committed) 1f else 0f
    isCurrentDestination -> 1f
    else -> 0f
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

    /**
     * A hop a released drag has DECIDED but whose page turn is still animating,
     * so it has not been handed to the NavHost yet.
     *
     * This exists because the decision and the animation used to be the same
     * coroutine, and `Animatable` cancels the coroutine that owns it whenever
     * something else takes the handle. The next drag's very first `snapTo`
     * therefore killed the commit that was queued behind the page turn: the
     * pixels finished moving, `switchToTab` was never called, and the swipe
     * vanished. Holding the decision out here — set the instant the finger
     * lifts, delivered by whoever gets there first, the animation or the next
     * gesture — is what makes a hop survive being interrupted.
     *
     * Deliberately NOT snapshot state: it is written and read only from the
     * gesture callbacks and the `neighbourOf` lambda they call, all on the main
     * thread, and making it observable would recompose the shell twice per
     * gesture for something no composable draws.
     */
    var pendingCommit: BtTab? = null

    /**
     * Whether a finger is currently down and dragging. Read when a handoff pin
     * is released — see [peekSurvivesHandoffEnd]. Plain, for the same reason as
     * [pendingCommit].
     */
    var dragging: Boolean = false
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
            // Hand a decided hop to the NavHost, pin the picture, and put the
            // page back at rest for whatever comes next.
            //
            // Idempotent by way of [BtTabSwipeState.pendingCommit], because two
            // callers race for it: the page-turn animation that finishes
            // normally, and the next gesture that interrupts it. Whoever arrives
            // first delivers; the other one finds the slot empty and does
            // nothing. Everything the hop actually MEANS happens synchronously
            // here — only the offset reset is launched, and only because
            // `snapTo` suspends.
            val deliver: (BtTab) -> Unit = { target ->
                if (state.pendingCommit == target) {
                    state.pendingCommit = null
                    // 1. Pin the picture. The bar reads this too, and it is what
                    //    keeps the selection from regressing to the tab we left.
                    state.handoff = target
                    // 2. Tell the NavHost, under a picture that is already final.
                    onCommitState.value(target)
                    // 3. Put the (still-old, still-covered) NavHost back at rest
                    //    so the new destination composes where it belongs. The
                    //    shell drops the pin once the NavHost is showing target.
                    scope.launch { offset.snapTo(0f) }
                }
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    state.dragging = true
                    // A new gesture is never queued behind a moving page: it
                    // either completes the hop already decided and starts fresh
                    // on the tab that hop lands on, or it picks the page up
                    // exactly where the spring left it. See [swipeTakeover].
                    val takeover = swipeTakeover(state.pendingCommit, offset.value)
                    takeover.deliver?.let(deliver)
                    total = takeover.startTotalPx
                    tracker.resetTracking()
                },
                onDragCancel = {
                    state.dragging = false
                    total = 0f
                    scope.launch { settle() }
                },
                onDragEnd = {
                    state.dragging = false
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
                    val followWidth = if (reducedMotion) 0f else pageWidth
                    // The decision is recorded HERE, synchronously, before a
                    // single suspending line runs — that is the whole fix. The
                    // page turn below may be interrupted and its coroutine
                    // cancelled at any moment; the hop it was going to deliver
                    // survives in `pendingCommit` and the next gesture delivers
                    // it. Nothing that can be cancelled is load-bearing.
                    if (target != null && followWidth > 0f) state.pendingCommit = target
                    scope.launch {
                        if (forward == null || target == null) {
                            // Nothing to commit to, including the ends of the
                            // bar: spring back so the page never rests off-centre.
                            settle()
                            return@launch
                        }
                        if (followWidth > 0f) {
                            // Finish the page turn. The incoming page rides the
                            // same offset, so it arrives at rest exactly as the
                            // outgoing one leaves — then hand the hop over. If a
                            // new finger lands first it delivers this instead,
                            // and `deliver` makes the loser a no-op.
                            offset.animateTo(
                                if (forward) -followWidth else followWidth,
                                pageSpring,
                                initialVelocity = velocity,
                            )
                            deliver(target)
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
                    //
                    // Written even while a handoff pin is up, which it did not
                    // used to be. The pin decides what the peek DRAWS all by
                    // itself ([swipePeekTab] answers `handoff` first), so this
                    // changes nothing on screen while the pin holds — but the
                    // pin can drop mid-drag during a fast chain, and when it
                    // does the peek falls straight through to a side the live
                    // drag has kept current instead of to a stale one.
                    if (side != state.peekSide) state.peekSide = side
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
