package at.bettertrack.app.ui.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.SharedConglomerateSummary
import at.bettertrack.app.data.repo.SharedPortfolioSummary
import at.bettertrack.app.data.repo.SharedWatchlistSummary
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FriendOverviewUi(
    val loading: Boolean = true,
    val error: String? = null,
    val since: String? = null,
    val stillFriend: Boolean = true,
    val portfolios: List<SharedPortfolioSummary> = emptyList(),
    val conglomerates: List<SharedConglomerateSummary> = emptyList(),
    val watchlists: List<SharedWatchlistSummary> = emptyList(),
    /** subjectId → activity-alert enabled (optimistic overlay over the summaries). */
    val activity: Map<String, Boolean> = emptyMap(),
    val removing: Boolean = false,
    val removed: Boolean = false,
) {
    val sharesNothing: Boolean get() = portfolios.isEmpty() && conglomerates.isEmpty() && watchlists.isEmpty()
}

class FriendOverviewViewModel(
    private val repo: SocialRepository,
    private val friendUserId: String,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
    private val _state = MutableStateFlow(FriendOverviewUi())
    val state: StateFlow<FriendOverviewUi> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.portfolios.isEmpty(), error = null)
            val friendsR = repo.friends()
            val sharedR = repo.sharedWithMe()
            val since = (friendsR as? BtResult.Ok)?.value?.firstOrNull { it.userId == friendUserId }?.since
            val stillFriend = (friendsR as? BtResult.Ok)?.value?.any { it.userId == friendUserId } ?: true
            val shared = (sharedR as? BtResult.Ok)?.value
            val err = listOf(friendsR, sharedR).filterIsInstance<BtResult.Err>().firstOrNull { !it.error.isNetwork }
            if (shared == null) {
                _state.value = _state.value.copy(loading = false, error = err?.error?.userMessage)
                return@launch
            }
            val ps = shared.portfolios.filter { it.ownerId == friendUserId }
            val cs = shared.conglomerates.filter { it.ownerId == friendUserId }
            val ws = shared.watchlists.filter { it.ownerId == friendUserId }
            val activity = buildMap {
                ps.forEach { put(it.portfolioId, it.activityAlertsEnabled) }
                cs.forEach { put(it.conglomerateId, it.activityAlertsEnabled) }
                ws.forEach { put(it.watchlistId, it.activityAlertsEnabled) }
            }
            _state.value = _state.value.copy(
                loading = false,
                error = null,
                since = since,
                stillFriend = stillFriend,
                portfolios = ps,
                conglomerates = cs,
                watchlists = ws,
                activity = activity,
            )
        }
    }

    fun toggleActivity(kind: ShareableKind, subjectId: String) {
        val current = _state.value.activity[subjectId] ?: false
        val next = !current
        // Optimistic.
        _state.value = _state.value.copy(activity = _state.value.activity + (subjectId to next))
        viewModelScope.launch {
            when (val r = repo.setActivityAlert(kind, subjectId, next)) {
                is BtResult.Ok -> _toast.value = if (next) "You'll be alerted about activity here" else "Alerts off for this item"
                is BtResult.Err -> {
                    _state.value = _state.value.copy(activity = _state.value.activity + (subjectId to current))
                    _toast.value = r.error.userMessage
                }
            }
        }
    }

    fun removeFriend() {
        viewModelScope.launch {
            _state.value = _state.value.copy(removing = true)
            when (val r = repo.unfriend(friendUserId)) {
                is BtResult.Ok -> _state.value = _state.value.copy(removing = false, removed = true)
                is BtResult.Err -> {
                    _state.value = _state.value.copy(removing = false)
                    _toast.value = r.error.userMessage
                }
            }
        }
    }

    fun consumeToast() { _toast.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendOverviewScreen(
    friendUserId: String,
    username: String,
    onBack: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    onOpenSharedPortfolio: (String) -> Unit,
    onOpenSharedWatchlist: (watchlistId: String, ownerName: String) -> Unit,
    onOpenSharedConglomerate: (String) -> Unit,
) {
    val vm: FriendOverviewViewModel = viewModel(key = "friend-$friendUserId") {
        FriendOverviewViewModel(AppGraph.socialRepository, friendUserId, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(ui.removed) { if (ui.removed) onBack() }
    androidx.compose.runtime.LaunchedEffect(toast) {
        toast?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show(); vm.consumeToast() }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text("@$username", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { pad ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = bt.gold)
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Profile header.
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BtAvatar(name = username, size = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("@$username", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary)
                    ui.since?.let {
                        Text("Friends since ${it.take(10)}", style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                    }
                }
            }

            // Go to chat.
            item {
                Surface(
                    onClick = { onOpenChat(friendUserId, username) },
                    color = bt.surface,
                    border = BorderStroke(1.dp, bt.border),
                    shape = BtShapes.card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = bt.gold, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Go to chat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Shares-with-you.
            item {
                Text(
                    "SHARES WITH YOU",
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            if (ui.sharesNothing) {
                item {
                    BtEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = "Nothing shared with you",
                        message = "@$username hasn't shared any portfolios, watchlists or conglomerates with you yet.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
            items(ui.portfolios, key = { "p-" + it.portfolioId }) { p ->
                SharedItemRow(
                    icon = Icons.Outlined.PieChart,
                    title = p.name,
                    subtitle = "Portfolio",
                    alertsOn = ui.activity[p.portfolioId] ?: false,
                    onToggleAlerts = { vm.toggleActivity(ShareableKind.Portfolio, p.portfolioId) },
                    onOpen = { onOpenSharedPortfolio(p.portfolioId) },
                    trailing = { MoneyText(value = p.totalValueEur, style = MaterialTheme.typography.titleSmall) },
                )
            }
            items(ui.conglomerates, key = { "c-" + it.conglomerateId }) { c ->
                SharedItemRow(
                    icon = Icons.Outlined.Dashboard,
                    title = c.name,
                    subtitle = if (c.positionCount == 1) "1 position" else "${c.positionCount} positions",
                    alertsOn = ui.activity[c.conglomerateId] ?: false,
                    onToggleAlerts = { vm.toggleActivity(ShareableKind.Conglomerate, c.conglomerateId) },
                    onOpen = { onOpenSharedConglomerate(c.conglomerateId) },
                    trailing = null,
                )
            }
            items(ui.watchlists, key = { "w-" + it.watchlistId }) { w ->
                SharedItemRow(
                    icon = Icons.AutoMirrored.Outlined.ShowChart,
                    title = w.name,
                    subtitle = if (w.itemCount == 1) "1 asset" else "${w.itemCount} assets",
                    alertsOn = ui.activity[w.watchlistId] ?: false,
                    onToggleAlerts = { vm.toggleActivity(ShareableKind.Watchlist, w.watchlistId) },
                    onOpen = { onOpenSharedWatchlist(w.watchlistId, w.ownerName) },
                    trailing = null,
                )
            }

            // Remove friend (moved off the row, into the overview).
            item {
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { confirmRemove = true },
                    color = bt.surface,
                    border = BorderStroke(1.dp, bt.border),
                    shape = BtShapes.card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PersonRemove, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Remove friend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.loss)
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text("Remove @$username?") },
            text = { Text("You'll both stop seeing anything shared between you. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.removeFriend() }) { Text("Remove", color = bt.loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel", color = bt.textSecondary) } },
        )
    }
}

@Composable
private fun SharedItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    alertsOn: Boolean,
    onToggleAlerts: () -> Unit,
    onOpen: () -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary, maxLines = 1)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                }
                trailing?.invoke()
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = if (alertsOn) bt.goldEmphasis else bt.textMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Alert me about activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (alertsOn) {
                    BtBadge(text = "Coming soon", kind = BtBadgeKind.Neutral)
                    Spacer(Modifier.width(8.dp))
                }
                Switch(
                    checked = alertsOn,
                    onCheckedChange = { onToggleAlerts() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = bt.onGold,
                        checkedTrackColor = bt.gold,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedTrackColor = bt.surface,
                        uncheckedBorderColor = bt.border,
                    ),
                )
            }
        }
    }
}

// Small helper to avoid importing FontWeight everywhere in this file.
private fun FontWeight_SemiBold() = androidx.compose.ui.text.font.FontWeight.SemiBold
