package at.bettertrack.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Pure sheet-gesture rules (owner corrections 2026-08-08, retuned 2026-08-09 and
// 2026-10-29). No Compose state here beyond the refresh-hint signal, so every
// rule is JVM-testable.

/**
 * The vertical drag model, in one place. Every number here is a finger distance
 * or a speed; [sheetDragModel] resolves them against one measured sheet.
 *
 * ## The geometry (2026-10-29)
 *
 * The one-level point stays a FRACTION of the sheet, because that is the point
 * the owner tuned on both the phone and the tablet and asked to keep. Everything
 * above it is DP: the band that buys "close everything" is the same thumb distance
 * on a 882dp phone sheet and on the 1173dp tablet sheet. A fraction-of-height band
 * made the tall tablet window charge a third more finger for the same decision.
 *
 * ```
 *  finger  0 ── backOne ──(+RESIST_ONSET)── onset ──(resisted)── closeAll ──>
 *  release      SETTLE  |  BACK_ONE (depth >= 2)                  |  CLOSE_ALL
 *  depth 1      SETTLE  |  SETTLE unless a CLOSE_FLING flick      |  closes
 * ```
 */
internal object BtSheetDragTuning {

    /**
     * The one-level trigger, and the dead zone under it, as a fraction of the
     * sheet's height. Below it a swipe-down means nothing whatever its velocity
     * (owner 2026-08-08: overscroll jiggles must never navigate, 0.12); past it a
     * nested release goes back ONE page. Owner 2026-10-29: keep it where it is, it
     * may fire "just very very little" earlier, so 0.12 -> 0.115 (4%: phone
     * 106 -> 102dp, tablet 141 -> 135dp).
     */
    const val BACK_ONE_FRACTION = 0.115f

    /**
     * The full-close threshold, as finger distance PAST the one-level point.
     * Owner 2026-10-29: "the harder pull needs far too much effort", cut it to
     * roughly half. It was 0.43 sheet-heights from the top (phone 379dp, tablet
     * 504dp); one-level + 96dp is phone 198dp (52%) and tablet 231dp (46%). The
     * 96dp is also the safety band: a back-one release can land anywhere in it,
     * and no back-one drag that stops short of it can close the stack.
     */
    val CLOSE_ALL_BAND: Dp = 96.dp

    /**
     * Where the resistance begins, as finger distance past the one-level point.
     * Owner 2026-08-09 put it "1/4 below the top" (0.25); the 2026-10-29
     * half-distance close-all lands before a quarter, so the onset now rides
     * inside the band: 40dp of free travel to release a back-one in, then 56dp of
     * resisted stretch that ends exactly at the close-all point.
     */
    val RESIST_ONSET: Dp = 40.dp

    /**
     * How much of the finger's movement the sheet keeps inside the resisted
     * stretch. 1f would be no resistance; 0f a wall. Owner 2026-08-09 ("make the
     * resistance less, because closing literally requires to swipe the entire
     * screen"): 0.40 -> 0.50. Owner 2026-10-29 keeps the STIFFNESS (it is the
     * felt step between the two stages) and halves the effort by shortening the
     * stretch: the resistance now costs 28dp of finger, was 0.09 sheet-heights
     * (phone 79dp, tablet 106dp).
     */
    const val RESISTANCE = 0.50f

    /**
     * A flick this fast back UP cancels from anywhere, and a rightward back-swipe
     * flick this fast commits (shipped 2026-08-08 model; the back-swipe shape is
     * binding and unchanged). Depth >= 2 has NO downward velocity rule: distance
     * alone decides back-one vs close-all, so the detent haptic and the gold pill
     * are an honest promise and a fast back-one flick can never close the stack.
     */
    val FLING_VELOCITY: Dp = 420.dp

    /**
     * A depth-1 sheet closes on a downward flick this fast, once it is past the
     * dead zone. Depth 1's close IS the close-everything pull, so the owner's
     * 2026-10-29 halving applies to it too: 420 -> 210dp/s.
     */
    val CLOSE_FLING_VELOCITY: Dp = 210.dp

    /** What an unmeasured sheet (first frame, height 0) is resolved against. */
    val UNMEASURED_SHEET: Dp = 880.dp

    /** No threshold may ask for more finger than this share of the sheet. */
    const val PULL_CEILING = 0.90f
}

/**
 * The drag model resolved for ONE sheet: [BtSheetDragTuning] in fractions of that
 * sheet's height (the unit `travel` lives in) and speeds in px/s.
 *
 * @property backOne one-level trigger and dead zone, sheet travel (= finger).
 * @property resistOnset where the resistance begins, sheet travel (= finger).
 * @property closeAll the close-all boundary in SHEET travel, depth >= 2.
 * @property closeAllPull the same boundary in FINGER travel; depth 1's close.
 */
@Immutable
internal data class SheetDragModel(
    val backOne: Float,
    val resistOnset: Float,
    val closeAll: Float,
    val closeAllPull: Float,
    val resistance: Float,
    val flingPx: Float,
    val closeFlingPx: Float,
)

/** Resolve [BtSheetDragTuning] for a sheet [heightPx] tall at [pxPerDp]. */
internal fun sheetDragModel(heightPx: Float, pxPerDp: Float): SheetDragModel {
    val t = BtSheetDragTuning
    val d = if (pxPerDp > 0f) pxPerDp else 1f
    val h = if (heightPx > 0f) heightPx else t.UNMEASURED_SHEET.value * d
    val closeAllPull = (t.BACK_ONE_FRACTION + t.CLOSE_ALL_BAND.value * d / h)
        .coerceAtMost(t.PULL_CEILING)
    val onset = (t.BACK_ONE_FRACTION + t.RESIST_ONSET.value * d / h).coerceAtMost(closeAllPull)
    return SheetDragModel(
        backOne = t.BACK_ONE_FRACTION,
        resistOnset = onset,
        closeAll = onset + (closeAllPull - onset) * t.RESISTANCE,
        closeAllPull = closeAllPull,
        resistance = t.RESISTANCE,
        flingPx = t.FLING_VELOCITY.value * d,
        closeFlingPx = t.CLOSE_FLING_VELOCITY.value * d,
    )
}

/** Which band of the swipe-down the sheet is sitting in, for the grabber. */
internal enum class SheetStage { IDLE, BACK, CLOSE_ALL }

/**
 * What letting go of a swipe-down means.
 *
 * Owner's model, as retuned 2026-10-29: a little pull goes back ONE page; push
 * through the resistance just after it and the ENTIRE stack closes, with about
 * half the finger the 2026-08-09 model asked for. See [BtSheetDragTuning].
 */
internal enum class SheetRelease {
    /** Inside the dead zone, or a flick back up — nothing happened. */
    SETTLE,

    /** Back one page; at depth 1 there is nothing below, so this closes. */
    BACK_ONE,

    /** Past the resistance: the whole sheet stack goes. */
    CLOSE_ALL,
}
/**
 * What letting go here does.
 *
 * The dead zone is checked FIRST and beats velocity: an overscroll jiggle at the
 * top of a list arrives as a few pixels of travel with a real fling velocity
 * behind it, and it must never navigate.
 */
internal fun sheetRelease(
    travel: Float,
    velocityY: Float,
    stacked: Boolean,
    model: SheetDragModel,
): SheetRelease {
    if (travel < model.backOne) return SheetRelease.SETTLE
    // A flick back UP is a change of mind, from anywhere.
    if (velocityY <= -model.flingPx) return SheetRelease.SETTLE
    if (stacked) {
        return if (travel >= model.closeAll) SheetRelease.CLOSE_ALL else SheetRelease.BACK_ONE
    }
    // Depth 1: closing IS closing everything, so it takes the same finger as the
    // nested close-all (depth 1 is linear: travel == finger), or a flick.
    if (travel >= model.closeAllPull || velocityY >= model.closeFlingPx) {
        return SheetRelease.BACK_ONE
    }
    return SheetRelease.SETTLE
}

/** [SheetStage] for a travel value — the visual half of [sheetRelease]. */
internal fun sheetStageOf(travel: Float, stacked: Boolean, model: SheetDragModel): SheetStage = when {
    travel < model.backOne -> SheetStage.IDLE
    stacked && travel >= model.closeAll -> SheetStage.CLOSE_ALL
    stacked -> SheetStage.BACK
    travel >= model.closeAllPull -> SheetStage.BACK
    else -> SheetStage.IDLE
}
/**
 * Whether moving from [previous] to [next] just crossed INTO the close-all zone.
 *
 * The trigger for the detent haptic the owner asked for (2026-08-09): "make a
 * vibration haptic once you go past it". Once, on the way in — so it is a
 * *crossing* and not a *position*. Holding the finger still past the notch fires
 * nothing more; retreating below the boundary and coming back fires again,
 * because that is a second crossing and the user did it on purpose.
 *
 * Depth 1 has no second stage, so it has nothing to announce.
 */
internal fun sheetNotchCrossed(
    previous: Float,
    next: Float,
    stacked: Boolean,
    model: SheetDragModel,
): Boolean = stacked && previous < model.closeAll && next >= model.closeAll

/**
 * Where a drag of [dy] px puts the sheet, as a fraction of its own height.
 *
 * Depth 1 tracks the finger exactly — there is no two-stage decision to feel, so
 * a detent there would be resistance that means nothing. At depth >= 2 the
 * resisted stretch stiffens the drag between [SheetDragModel.resistOnset] and
 * [SheetDragModel.closeAll]; the mapping carries the FINGER's accumulated
 * distance ([sheetPullFor]) rather than the sheet's position, so dragging back up
 * unwinds the same resistance.
 */
internal fun sheetDragTravel(
    current: Float,
    dy: Float,
    heightPx: Float,
    stacked: Boolean,
    model: SheetDragModel,
): Float {
    if (heightPx <= 0f) return current
    if (!stacked) return (current + dy / heightPx).coerceIn(0f, 1f)
    val pulled = sheetPullFor(current, model) + dy / heightPx
    return sheetTravelFor(pulled.coerceAtLeast(0f), model)
}

/** Finger distance (as a fraction of sheet height) -> travel. */
internal fun sheetTravelFor(pull: Float, model: SheetDragModel): Float = when {
    pull <= model.resistOnset -> pull
    pull <= model.closeAllPull -> model.resistOnset + (pull - model.resistOnset) * model.resistance
    else -> model.closeAll + (pull - model.closeAllPull)
}.coerceIn(0f, 1f)

/** The exact inverse of [sheetTravelFor]. */
internal fun sheetPullFor(travel: Float, model: SheetDragModel): Float = when {
    travel <= model.resistOnset -> travel
    travel <= model.closeAll -> model.resistOnset + (travel - model.resistOnset) / model.resistance
    else -> model.closeAllPull + (travel - model.closeAll)
}.coerceAtLeast(0f)
/**
 * What the depth axis must be DRAWN at on the frame a depth change lands — or
 * null when nothing is pending and the live value is the truth.
 *
 * ## The flicker this removes (owner, 2026-08-09)
 *
 * "There is still a light flicker when going back from a sub sub page to a sub
 * page." Caught in the draw phase on the test phone, logging every plane's
 * translation as it was painted:
 *
 * ```
 * 10:57:35.069  planes=2 fromTop=0 key=93f6…  slide=1.0  tx=1080.0   leaving page, gone right
 * 10:57:35.069  planes=2 fromTop=1 key=fdfa…  slide=1.0  tx=0.0      parent, exactly at rest
 * 10:57:35.101  planes=1 fromTop=0 key=fdfa…  slide=1.0  tx=1080.0   <-- SAME page, off-screen
 * 10:57:35.135  planes=1 fromTop=0 key=fdfa…  slide=0.0  tx=0.0      back where it was
 * ```
 *
 * For 34ms — four frames of a 120Hz panel — the only page left was painted a full
 * width to the right of where it had been painted the frame before, leaving the
 * sheet showing nothing but its own background. That is the flicker, and it was
 * never a rendering glitch: it is arithmetic.
 *
 * The two planes are positioned by two different expressions, `slide * w` for the
 * top one and `-(1 - slide) * w` for the parent, and those agree only at
 * `slide == 0`. A pop moves the surviving page from the second expression to the
 * first while `slide` is still 1, so it moves by a whole width. The reset that
 * fixes it lives in `LaunchedEffect(depth, topKey)`, and a `LaunchedEffect` body
 * is dispatched *after* the composition that observed the smaller stack — so the
 * frame in between draws with the old value. Nothing was wrong with the reset
 * except that it could not possibly be early enough.
 *
 * So the frame is told the answer instead of waiting for it. A depth change is
 * visible in composition (`depth` against the effect's `lastDepth`) one whole
 * frame before the effect runs, and what the reset is *going* to write is fully
 * determined by the direction of that change: a pop is heading for 0, a push is
 * heading for 1 before it animates back to 0, and a sheet arriving from nothing
 * is heading for 0. Returning it here makes the pop's own frame draw the settled
 * geometry, and the effect's snap a moment later becomes a no-op on identical
 * pixels — which is what the two-plane design always claimed it was.
 */
internal fun sheetPendingSlide(depth: Int, lastDepth: Int): Float? = when {
    depth == lastDepth -> null
    // Opening, or gone entirely: the top page is the only one that matters and it
    // belongs at rest.
    depth == 0 || lastDepth == 0 -> 0f
    // A push starts off to the right and slides in.
    depth > lastDepth -> 1f
    // A pop has already slid out; the parent is the top page now, at rest.
    else -> 0f
}

// ── Who owns a system back press ────────────────────────────────────────────

/** What a system-back press means to the sheet layer at this instant. */
internal enum class SheetBack {
    /** Nothing is stacked. Back belongs to the shell, and then to the system. */
    NOT_OURS,

    /**
     * A dismissal is already travelling. The press is CONSUMED and dropped: one
     * back is one level, and the level this one would take is already leaving.
     */
    SWALLOW,

    /** Preview the travel under the finger, and pop ONE page when it commits. */
    TAKE,
}

/**
 * Which of the three a back press is right now.
 *
 * ## The defect this exists to prevent (owner device pass 2026-09-01, #5)
 *
 * *"One Back collapses two stacked sheet levels: Portfolio → Bargeld →
 * Transaktionen, a single Back returns to the portfolio detail, skipping
 * Bargeld."*
 *
 * The sheet layer's back handler used to be **disabled** for the whole of a
 * dismissal (`enabled = depth > 0 && !leaving`), which is a quarter of a second
 * in which the layer is not claiming back at all. The `NavController`'s own
 * `OnBackPressedCallback` sits directly underneath it in the dispatcher and is
 * enabled the entire time, so any second back event arriving inside that window
 * — a bouncy edge swipe, an impatient second press — reached the graph directly
 * and popped the NEXT entry with no animation whatsoever. Two levels gone, the
 * second of them instantly, which is indistinguishable from one back having
 * eaten both.
 *
 * So the layer now claims back for as long as anything is stacked, and decides
 * what to DO with it here. Swallowing is the deliberate half: a press that
 * arrives while a page is mid-flight is answered by the page that is already
 * leaving, not by the one underneath it.
 */
internal fun sheetBackAction(depth: Int, leaving: Boolean): SheetBack = when {
    depth <= 0 -> SheetBack.NOT_OURS
    leaving -> SheetBack.SWALLOW
    else -> SheetBack.TAKE
}

/**
 * Whether a dismissal that began on page [startedOn] may still pop, given
 * whichever page is on top by the time its travel has finished.
 *
 * The other half of "one back is one level", from the far side of the
 * animation. `popBackStack()` pops *whatever is on top*, so a dismissal that
 * takes 260ms to play out is a 260ms promise to delete a page by position
 * rather than by name. If anything else pops in the meantime the promise now
 * points at an innocent page. Addressing it makes the late pop a no-op instead.
 */
internal fun sheetPopIsAddressed(startedOn: String?, topNow: String?): Boolean =
    startedOn != null && startedOn == topNow

/**
 * Whether a rightward swipe on a depth->=2 sheet commits to going back.
 *
 * Distance OR velocity, and a flick back to the LEFT cancels — the same shape as
 * the vertical rule, because it is the same decision on the other axis.
 */
internal fun sheetBackSwipeCommits(
    progress: Float,
    velocityX: Float,
    velocityThresholdPx: Float,
): Boolean {
    if (progress < SHEET_BACK_SWIPE_DEAD_ZONE) return false
    if (velocityX <= -velocityThresholdPx) return false
    return progress >= SHEET_BACK_SWIPE_FRACTION || velocityX >= velocityThresholdPx
}
// ── Tunables ────────────────────────────────────────────────────────────────

// The vertical drag model lives in [BtSheetDragTuning], at the top of this file.

/** How far across the screen a rightward back-swipe must travel to commit. */
internal const val SHEET_BACK_SWIPE_FRACTION = 0.30f

/** The horizontal counterpart of [BtSheetDragTuning.BACK_ONE_FRACTION]. */
internal const val SHEET_BACK_SWIPE_DEAD_ZONE = 0.04f

/**
 * How far the parent plane sits to the left while covered, as a fraction of the
 * sheet's width. 1f = pager-exact (the two planes move together, 1:1).
 */
internal const val SHEET_DEPTH_PARALLAX = 1f

/** How much of the sheet a completed predictive-back preview shows. */
internal const val SHEET_BACK_PREVIEW = 0.42f

/**
 * How long the pull-to-refresh gesture stays disarmed after a trigger, so the
 * second downward pull scrolls or dismisses the sheet instead of refreshing
 * again. Independent of how long the refresh itself takes.
 *
 * Raised from 600ms (owner, 2026-08-09: "increase the timer of the drag down
 * possible for refreshing sites"). 600ms was measured from the trigger, which is
 * the instant the finger is still finishing the FIRST pull — by the time the hand
 * has reset and started the second one, most of the window had already been spent
 * on the tail of the first gesture. A second pull that is meant to dismiss now
 * has a full second of room to begin in.
 */
const val BT_REFRESH_DISARM_MS: Long = 1100L

/** How long the "pull again" hint chip stays up. */
const val BT_SHEET_HINT_MS: Long = 500L

/**
 * The app's motion rhythm, in ms.
 *
 * Inherited from R3, where it was the graph's transition budget. The graph has
 * no transitions left — every sheet motion is a drag the sheet layer owns — but
 * the *rhythm* is the thing worth keeping, because it is what makes forty
 * surfaces feel like one app. Whatever else animates on its own (the collapsing
 * header's settle) matches it.
 */
const val BT_MOTION_RHYTHM_MS: Int = 300

// ── Which gesture owns a downward pull ──────────────────────────────────────

/** Who a downward pull belongs to on a refresh screen inside a sheet. */
internal enum class BtPullOwner { REFRESH, SHEET }

/**
 * The routing, stated rather than implied.
 *
 * Refresh only owns the pull when it is armed AND not already running. Both
 * exclusions matter: [armed] is the deliberate [BT_REFRESH_DISARM_MS] window
 * after a trigger, and `isRefreshing` is the case Material3 already declines,
 * which this makes explicit instead of leaving to a library's internals.
 */
internal fun btPullOwner(armed: Boolean, isRefreshing: Boolean): BtPullOwner =
    if (armed && !isRefreshing) BtPullOwner.REFRESH else BtPullOwner.SHEET

/**
 * The same routing, but **decided once per gesture and then held**.
 *
 * The bug this fixes (owner, 2026-08-09: "once the timer is up and the user is
 * mid gesture he can still pull down and it doesn't cancel or gets stuck"): the
 * routing was recomputed continuously, so [BT_REFRESH_DISARM_MS] expiring while a
 * finger was already dragging flipped `pullToRefresh`'s `enabled` underneath that
 * finger. The in-flight pull — which had started life as a sheet dismissal —
 * suddenly found the refresh modifier consuming its scroll, and it stalled.
 *
 * So the decision is latched at the moment the finger goes down. [held] is that
 * latch: non-null for exactly as long as one gesture lasts. Nothing about the
 * world changing mid-gesture can reach the gesture already in progress, in either
 * direction — a pull that began while disarmed stays a dismissal even after the
 * timer expires, and one that began after expiry stays a refresh even if a
 * refresh starts running under it.
 *
 * Latching at gesture start is safe precisely because the un-latched value is
 * live and correct at that instant: freezing it changes nothing on the frame it
 * happens, only on the frames after.
 */
internal fun btPullOwnerLatched(
    held: BtPullOwner?,
    armed: Boolean,
    isRefreshing: Boolean,
): BtPullOwner = held ?: btPullOwner(armed, isRefreshing)

/**
 * The disarm window itself: the refresh gesture is handed to the sheet for
 * [windowMs] from the trigger, and taken back in a `finally` so a cancelled
 * screen can never strand the gesture in the wrong place.
 */
internal suspend fun btRefreshDisarmWindow(
    windowMs: Long = BT_REFRESH_DISARM_MS,
    setArmed: (Boolean) -> Unit,
) {
    setArmed(false)
    try {
        delay(windowMs)
    } finally {
        setArmed(true)
    }
}

// ── The refresh hint signal ─────────────────────────────────────────────────

/**
 * The channel between a screen's pull-to-refresh and the sheet chrome above it.
 *
 * The chip is drawn by the sheet, not by the screen: it is the SHEET that the
 * second pull acts on, and a chip inside the scrolling content would also shift
 * the content — the exact thing the owner rejected elsewhere in this gesture.
 */
@Stable
class BtSheetRefreshHint {
    var token by mutableIntStateOf(0)
        private set

    /** A pull-to-refresh just triggered. */
    fun ping() {
        token++
    }
}

/** No-op default: outside a sheet (the four tabs) there is nothing to hint at. */
val LocalBtSheetRefreshHint = staticCompositionLocalOf { BtSheetRefreshHint() }
