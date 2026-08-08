package at.bettertrack.app.ui.shell

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Box(
        modifier.pullToRefresh(
            state = state,
            isRefreshing = isRefreshing,
            enabled = btPullOwner(armed, isRefreshing) == BtPullOwner.REFRESH,
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
