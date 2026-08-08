package at.bettertrack.app.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Pull-to-refresh inside a sheet, with the two gestures explicitly routed.
 *
 * ## The routing, and what it replaces
 *
 * A sheet's pull-to-dismiss and its content's pull-to-refresh are the same
 * finger asking for two different things, and the shipped build decided between
 * them by accident: Material3 consumes nothing while `isRefreshing` is true, so
 * whether the second pull refreshed again or dismissed the sheet depended on
 * whether the first refresh had finished — and a 320 ms *minimum visible* floor
 * on the indicator was quietly what made that window exist at all. The owner
 * rejected both halves of that (2026-08-08).
 *
 * So the floor is gone (see [btRefreshAttempt]: the indicator now shows the
 * attempt's natural duration, nothing more) and the routing is stated outright:
 *
 *  1. Pull one triggers the refresh. Work starts on the spot.
 *  2. For [BT_REFRESH_DISARM_MS] the refresh gesture is **disarmed** — the
 *     modifier consumes nothing, so a second downward pull reaches the content's
 *     scroll and then the sheet, exactly as if there were no refresh here at all.
 *  3. A chip says so, for [BT_SHEET_HINT_MS], drawn by the sheet above.
 *
 * `enabled` gates only the nested-scroll callbacks — it does not touch the
 * indicator — so the spinner is unaffected by the disarm window.
 *
 * ## Why the decision is latched (owner, 2026-08-09)
 *
 * Step 2 used to be recomputed on every recomposition, which meant the window
 * could expire *underneath a finger that was already dragging*. `enabled` flipped
 * false to true mid-gesture, the refresh modifier started consuming a scroll that
 * had been travelling to the sheet, and the pull froze halfway — the owner's "it
 * cancels or gets stuck". The window is longer now, which makes the boundary
 * rarer but not impossible, so the boundary itself is fixed rather than hidden:
 * [btPullOwnerLatched] freezes the routing at the pointer-down and holds it until
 * the finger leaves. A gesture always ends the way it began.
 */
@Composable
fun BtSheetRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val bt = BtTheme.colors
    val hint = LocalBtSheetRefreshHint.current
    val state = rememberPullToRefreshState()

    var trigger by remember { mutableIntStateOf(0) }
    var armed by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        btRefreshDisarmWindow { armed = it }
    }

    // The latch. Non-null for exactly the length of one gesture; see
    // [btPullOwnerLatched] for why the decision has to be frozen at the down.
    var held by remember { mutableStateOf<BtPullOwner?>(null) }
    val liveNow by rememberUpdatedState(btPullOwner(armed, isRefreshing))

    Box(
        modifier
            // Observes the pointer on the Initial pass and consumes nothing, so
            // it is invisible to the content's own gestures and to the refresh
            // modifier below it. All it does is bracket the gesture.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    held = liveNow
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        // A cancelled gesture must release the latch too, or the
                        // next pull would inherit a decision made for a finger
                        // that is no longer on the screen.
                        held = null
                    }
                }
            }
            .pullToRefresh(
                state = state,
                isRefreshing = isRefreshing,
                enabled = btPullOwnerLatched(held, armed, isRefreshing) == BtPullOwner.REFRESH,
                onRefresh = {
                    trigger++
                    hint.ping()
                    onRefresh()
                },
            ),
    ) {
        content()
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = bt.surface,
            color = bt.goldInk,
        )
    }
}
