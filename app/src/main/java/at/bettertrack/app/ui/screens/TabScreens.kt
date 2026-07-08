package at.bettertrack.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Placeholder tab screens with the pull-to-refresh scaffold (spec §6.13 —
 * pull-to-refresh on every list/overview screen). Refresh is a visual no-op
 * until real data lands. Portfolio became real in Step 6 (ui/portfolio/);
 * TODO(step 11/13/14): replace the remaining tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshableTabScreen(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            // TODO(step 5+): trigger a real repository refresh.
            scope.launch {
                refreshing = true
                delay(800)
                refreshing = false
            }
        },
        state = state,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = bt.surface,
                color = bt.gold,
            )
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Box(
                    modifier = Modifier.fillParentMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    BtEmptyState(icon = icon, title = title, message = message, action = action)
                }
            }
        }
    }
}

@Composable
fun AssetsTabScreen(onOpenCustomAssets: () -> Unit = {}) {
    // Interim entry to custom-asset management (§6.4) until search + watchlists
    // fill this tab (Steps 11–12).
    RefreshableTabScreen(
        icon = Icons.AutoMirrored.Outlined.ShowChart,
        title = stringResource(R.string.bt_tab_assets_empty_title),
        message = stringResource(R.string.bt_tab_assets_empty_message),
        action = {
            BtSecondaryButton(
                text = stringResource(R.string.bt_custom_manage),
                onClick = onOpenCustomAssets,
            )
        },
    )
}

@Composable
fun SocialTabScreen() {
    RefreshableTabScreen(
        icon = Icons.Outlined.People,
        title = stringResource(R.string.bt_tab_social_empty_title),
        message = stringResource(R.string.bt_tab_social_empty_message),
    )
}

@Composable
fun WorkboardTabScreen() {
    RefreshableTabScreen(
        icon = Icons.Outlined.Dashboard,
        title = stringResource(R.string.bt_tab_workboard_empty_title),
        message = stringResource(R.string.bt_tab_workboard_empty_message),
    )
}
