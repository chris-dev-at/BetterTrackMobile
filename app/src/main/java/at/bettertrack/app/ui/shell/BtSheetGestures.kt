package at.bettertrack.app.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Pure sheet-gesture rules (owner corrections 2026-08-08). No Compose state here
// beyond the refresh-hint signal, so every rule is JVM-testable.

/** Which band of the swipe-down the sheet is sitting in, for the grabber. */
internal enum class SheetStage { IDLE, BACK, CLOSE_ALL }

/**
 * What letting go of a swipe-down means.
 *
 * Owner's corrected model (2026-08-08): the travel meets its first resistance
 * just before three quarters of the sheet. Release at or before it = back ONE
 * page; drag through it past three quarters = the ENTIRE stack closes.
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
    velocityThresholdPx: Float,
    stacked: Boolean,
): SheetRelease {
    if (travel < SHEET_DEAD_ZONE) return SheetRelease.SETTLE
    // A flick back UP is a change of mind, from anywhere.
    if (velocityY <= -velocityThresholdPx) return SheetRelease.SETTLE
    if (stacked) {
        return if (travel >= SHEET_NOTCH_END) SheetRelease.CLOSE_ALL else SheetRelease.BACK_ONE
    }
    // Depth 1: unchanged full pull-down-to-close, distance OR flick.
    if (travel >= SHEET_CLOSE_FRACTION || velocityY >= velocityThresholdPx) {
        return SheetRelease.BACK_ONE
    }
    return SheetRelease.SETTLE
}

/** [SheetStage] for a travel value — the visual half of [sheetRelease]. */
internal fun sheetStageOf(travel: Float, stacked: Boolean): SheetStage = when {
    travel < SHEET_DEAD_ZONE -> SheetStage.IDLE
    stacked && travel >= SHEET_NOTCH_END -> SheetStage.CLOSE_ALL
    stacked -> SheetStage.BACK
    travel >= SHEET_CLOSE_FRACTION -> SheetStage.BACK
    else -> SheetStage.IDLE
}
/**
 * Where a drag of [dy] px puts the sheet, as a fraction of its own height.
 *
 * Depth 1 tracks the finger exactly — there is no two-stage decision to feel, so
 * a detent there would be resistance that means nothing. At depth >= 2 the notch
 * band stiffens the drag between [SHEET_NOTCH_START] and [SHEET_NOTCH_END]; the
 * mapping carries the FINGER's accumulated distance ([sheetPullFor]) rather than
 * the sheet's position, so dragging back up unwinds the same resistance.
 */
internal fun sheetDragTravel(current: Float, dy: Float, heightPx: Float, stacked: Boolean): Float {
    if (heightPx <= 0f) return current
    if (!stacked) return (current + dy / heightPx).coerceIn(0f, 1f)
    val pulled = sheetPullFor(current) + dy / heightPx
    return sheetTravelFor(pulled.coerceAtLeast(0f))
}

/** Finger distance (as a fraction of sheet height) -> travel. */
internal fun sheetTravelFor(pull: Float): Float {
    val notchPull = (SHEET_NOTCH_END - SHEET_NOTCH_START) / SHEET_NOTCH_RESISTANCE
    return when {
        pull <= SHEET_NOTCH_START -> pull
        pull <= SHEET_NOTCH_START + notchPull ->
            SHEET_NOTCH_START + (pull - SHEET_NOTCH_START) * SHEET_NOTCH_RESISTANCE
        else -> SHEET_NOTCH_END + (pull - SHEET_NOTCH_START - notchPull)
    }.coerceIn(0f, 1f)
}

/** The exact inverse of [sheetTravelFor]. */
internal fun sheetPullFor(travel: Float): Float {
    val notchPull = (SHEET_NOTCH_END - SHEET_NOTCH_START) / SHEET_NOTCH_RESISTANCE
    return when {
        travel <= SHEET_NOTCH_START -> travel
        travel <= SHEET_NOTCH_END ->
            SHEET_NOTCH_START + (travel - SHEET_NOTCH_START) / SHEET_NOTCH_RESISTANCE
        else -> SHEET_NOTCH_START + notchPull + (travel - SHEET_NOTCH_END)
    }.coerceAtLeast(0f)
}
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

/**
 * The dead zone. Below this the swipe-down means nothing at all, whatever its
 * velocity — the fix for overscroll jiggles navigating (owner, 2026-08-08).
 */
internal const val SHEET_DEAD_ZONE = 0.12f

/** Where the resistance begins, just before three quarters. Depth >= 2 only. */
internal const val SHEET_NOTCH_START = 0.72f

/** Past here — beyond three quarters — releasing closes the whole stack. */
internal const val SHEET_NOTCH_END = 0.78f

/**
 * How much of the finger's movement the sheet keeps while crossing the notch.
 * 1f would be no notch; 0f a wall. Crossing costs
 * `(END - START) / RESISTANCE` of sheet height in finger travel, so this is a
 * budget as much as a feeling — see the reachability guard in BtSheetDragTest.
 *
 * Tuned on the device (2026-08-08). The sheet is 2228px tall on the test phone,
 * so the whole journey to close-all is a single thumb stroke of `pull x 2228`:
 * at 0.65/0.80 that came to 1899px on a 2316px screen, which only fits if the
 * drag starts on the grabber itself. 0.72/0.78 costs 1790px for the same shape —
 * still a deliberate, near-full-screen pull, but with 110px of slack in where it
 * may begin. A 28% stiffening is felt without being fought.
 */
internal const val SHEET_NOTCH_RESISTANCE = 0.72f

/** Depth-1 pull-down-to-close commit point. Unchanged from the shipped model. */
internal const val SHEET_CLOSE_FRACTION = 0.50f

/** How far across the screen a rightward back-swipe must travel to commit. */
internal const val SHEET_BACK_SWIPE_FRACTION = 0.30f

/** The horizontal counterpart of [SHEET_DEAD_ZONE]. */
internal const val SHEET_BACK_SWIPE_DEAD_ZONE = 0.04f

/** A flick this fast commits whatever distance it covered. */
internal val SHEET_DISMISS_VELOCITY: Dp = 420.dp

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
 */
const val BT_REFRESH_DISARM_MS: Long = 600L

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
