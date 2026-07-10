package at.bettertrack.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.theme.BtShapes
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
fun AssetsTabScreen(
    onOpenSearch: () -> Unit = {},
    onOpenCustomAssets: () -> Unit = {},
    onOpenAsset: (String) -> Unit = {},
    onAddToWatchlist: () -> Unit = {},
) {
    // Step 12 (§6.6): the Assets tab is search + watchlists. Custom-asset
    // management (§6.4) stays reachable via the link.
    val bt = BtTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        SearchBarButton(onClick = onOpenSearch)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_watchlist_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onOpenCustomAssets) {
                Text(stringResource(R.string.bt_custom_manage), color = bt.textSecondary)
            }
        }
        at.bettertrack.app.ui.watchlist.WatchlistPanel(
            onOpenAsset = onOpenAsset,
            onAddAsset = { onAddToWatchlist() },
            modifier = Modifier.weight(1f),
        )
    }
}

/** A tappable search "field" (looks like an input, opens the search screen). */
@Composable
private fun SearchBarButton(onClick: () -> Unit) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().btPressScale(interaction, pressedScale = 0.985f),
        shape = BtShapes.control,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.bt_assets_search_bar),
                style = MaterialTheme.typography.bodyLarge,
                color = bt.textMuted,
            )
        }
    }
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
fun WorkboardTabScreen(
    onOpenConglomerate: (String) -> Unit,
    onCreateConglomerate: () -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    // Step-13 conglomerates + the new price-alerts manager, behind the Workboard
    // segmented host (owner ask 2026-07-10).
    at.bettertrack.app.ui.workboard.WorkboardScreen(
        onOpenConglomerate = onOpenConglomerate,
        onCreateConglomerate = onCreateConglomerate,
        onOpenAsset = onOpenAsset,
    )
}
