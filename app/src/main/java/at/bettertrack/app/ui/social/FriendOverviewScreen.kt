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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.Idea
import at.bettertrack.app.data.repo.IdeasRepository
import at.bettertrack.app.data.repo.SharedConglomerateSummary
import at.bettertrack.app.data.repo.SharedIdeaSummary
import at.bettertrack.app.data.repo.SharedPortfolioSummary
import at.bettertrack.app.data.repo.SharedWatchlistSummary
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtScrollFill
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FriendOverviewUi(
    val loading: Boolean = true,
    val error: BtMessage? = null,
    /**
     * The shared read failed for lack of a connection. Distinct from [error]
     * because a network drop is not a fault the user should read as "this
     * failed" — and, before this existed, it was not read as anything at all:
     * `load()` filters network failures out of [error], so an offline load fell
     * through to the EMPTY state and told the user "@name shares nothing with
     * you" on the strength of a request that never left the phone.
     */
    val offline: Boolean = false,
    val since: String? = null,
    /**
     * The friend's curated-avatar id. It is NOT a nav argument — the route
     * carries a username and nothing else — so it is read off the friend row in
     * `friends()`, which this screen already fetches for [since]. Null until
     * that load lands (and for a friend who picked no icon), at which point the
     * avatar falls back to the deterministic name-derived one.
     */
    val profileIcon: String? = null,
    val stillFriend: Boolean = true,
    val portfolios: List<SharedPortfolioSummary> = emptyList(),
    val conglomerates: List<SharedConglomerateSummary> = emptyList(),
    val watchlists: List<SharedWatchlistSummary> = emptyList(),
    /** V5: the friend's shared ideas — pointers; the full state needs a clone. */
    val ideas: List<SharedIdeaSummary> = emptyList(),
    /** subjectId → activity-alert enabled (optimistic overlay over the summaries). */
    val activity: Map<String, Boolean> = emptyMap(),
    /** Idea ids with a clone in flight — the row's action goes busy, not the screen. */
    val cloning: Set<String> = emptySet(),
    /** Set once a clone lands; the screen navigates to the caller's own copy and clears it. */
    val clonedIdea: Idea? = null,
    val removing: Boolean = false,
    val removed: Boolean = false,
) {
    val sharesNothing: Boolean
        get() = portfolios.isEmpty() && conglomerates.isEmpty() && watchlists.isEmpty() && ideas.isEmpty()
}

class FriendOverviewViewModel(
    private val repo: SocialRepository,
    /**
     * Cloning is an IDEAS write, not a social one: `POST /ideas/{id}/clone`
     * lands the copy in the caller's own list. It is reached through the ideas
     * repository for that reason — the social repository owns the pointer, the
     * ideas repository owns the thing the pointer resolves to.
     */
    private val ideas: IdeasRepository,
    private val friendUserId: String,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
    private val _state = MutableStateFlow(FriendOverviewUi())
    val state: StateFlow<FriendOverviewUi> = _state.asStateFlow()

    private val _toast = MutableStateFlow<SocialToast?>(null)
    val toast: StateFlow<SocialToast?> = _toast.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            // Only a FIRST load shows the skeleton; a reload with rows already on
            // screen refreshes them in place. The condition asks whether ANY kind
            // is present, not just portfolios — a friend who shares only ideas
            // would otherwise be thrown back to the skeleton on every refresh.
            _state.value = _state.value.copy(loading = _state.value.sharesNothing, error = null)
            val friendsR = repo.friends()
            val sharedR = repo.sharedWithMe()
            val friendRow = (friendsR as? BtResult.Ok)?.value?.firstOrNull { it.userId == friendUserId }
            val since = friendRow?.since
            val icon = friendRow?.profileIcon
            val stillFriend = (friendsR as? BtResult.Ok)?.value?.any { it.userId == friendUserId } ?: true
            val shared = (sharedR as? BtResult.Ok)?.value
            val err = listOf(friendsR, sharedR).filterIsInstance<BtResult.Err>().firstOrNull { !it.error.isNetwork }
            if (shared == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = err?.error?.asMessage(),
                    // No catalogued error and no data ⇒ the request never made it
                    // out. Say "you're offline", not "they share nothing".
                    offline = err == null,
                    // The two reads fail independently: the friend row can be in
                    // hand while the shares read is not, and the header avatar is
                    // rendered on this branch too.
                    profileIcon = icon ?: _state.value.profileIcon,
                )
                return@launch
            }
            val ps = shared.portfolios.filter { it.ownerId == friendUserId }
            val cs = shared.conglomerates.filter { it.ownerId == friendUserId }
            val ws = shared.watchlists.filter { it.ownerId == friendUserId }
            val ids = shared.ideas.filter { it.ownerId == friendUserId }
            val activity = buildMap {
                ps.forEach { put(it.portfolioId, it.activityAlertsEnabled) }
                cs.forEach { put(it.conglomerateId, it.activityAlertsEnabled) }
                ws.forEach { put(it.watchlistId, it.activityAlertsEnabled) }
                ids.forEach { put(it.ideaId, it.activityAlertsEnabled) }
            }
            _state.value = _state.value.copy(
                loading = false,
                error = null,
                offline = false,
                since = since,
                profileIcon = icon ?: _state.value.profileIcon,
                stillFriend = stillFriend,
                portfolios = ps,
                conglomerates = cs,
                watchlists = ws,
                ideas = ids,
                activity = activity,
            )
        }
    }

    /**
     * Copy a friend's shared idea into my own list.
     *
     * This is the ONLY way a non-owner ever reads an idea's full state — the
     * summary on `/social/shared` carries a name and a `hasThesis` flag and
     * nothing else, and `GET /ideas/{id}` is owner-only. So this is not a
     * convenience shortcut next to an "open" affordance; it IS the open.
     *
     * **404 is not a bug here.** The server answers 404 — deliberately, never
     * 403 — to a viewer the audience no longer admits, so that a revoked share
     * leaks nothing about whether the idea still exists. Rendering that as the
     * generic failure would blame the app for the owner's decision, so it gets
     * its own sentence and a silent [load] that drops the stale row from the
     * list. There is no "try again" on that branch: trying again is precisely
     * what will not help.
     */
    fun cloneIdea(idea: SharedIdeaSummary) {
        if (idea.ideaId in _state.value.cloning) return
        viewModelScope.launch {
            _state.value = _state.value.copy(cloning = _state.value.cloning + idea.ideaId)
            val r = ideas.clone(idea.ideaId)
            _state.value = _state.value.copy(cloning = _state.value.cloning - idea.ideaId)
            when (r) {
                is BtResult.Ok -> {
                    _toast.value = SocialToast.Res(R.string.bt_shared_idea_copied_toast, listOf(r.value.name))
                    _state.value = _state.value.copy(clonedIdea = r.value)
                }
                is BtResult.Err -> if (r.error.httpStatus == 404) {
                    _toast.value = SocialToast.Failure(BtMessage(R.string.bt_shared_idea_revoked))
                    load()
                } else {
                    _toast.value = SocialToast.Failure(r.error.asMessage(), onRetry = { cloneIdea(idea) })
                }
            }
        }
    }

    /** The screen has navigated to the copy; forget it so a re-entry doesn't re-navigate. */
    fun consumeClone() { _state.value = _state.value.copy(clonedIdea = null) }

    fun toggleActivity(kind: ShareableKind, subjectId: String) {
        val current = _state.value.activity[subjectId] ?: false
        val next = !current
        // Optimistic.
        _state.value = _state.value.copy(activity = _state.value.activity + (subjectId to next))
        viewModelScope.launch {
            when (val r = repo.setActivityAlert(kind, subjectId, next)) {
                is BtResult.Ok -> _toast.value = SocialToast.Res(if (next) R.string.bt_social_toast_alerts_on else R.string.bt_social_toast_alerts_off)
                is BtResult.Err -> {
                    _state.value = _state.value.copy(activity = _state.value.activity + (subjectId to current))
                    // The switch has already sprung back, so "Try again" re-issues
                    // the same intent the user expressed by flipping it.
                    _toast.value = SocialToast.Failure(r.error.asMessage(), onRetry = { toggleActivity(kind, subjectId) })
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
                    // The confirmation dialog is already behind us and the screen
                    // is still open, so re-issuing is the whole retry.
                    _toast.value = SocialToast.Failure(r.error.asMessage(), onRetry = { removeFriend() })
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
    /** Where a freshly cloned idea lands — the caller's OWN copy, on the ideas surface. */
    onOpenIdea: (String) -> Unit,
) {
    val vm: FriendOverviewViewModel = viewModel(key = "friend-$friendUserId") {
        FriendOverviewViewModel(
            AppGraph.socialRepository,
            AppGraph.ideasRepository,
            friendUserId,
            AppGraph.connectivityMonitor,
        )
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(ui.removed) { if (ui.removed) onBack() }
    SocialToastEffect(toast) { vm.consumeToast() }
    // The confirmation is emitted BEFORE this runs (the toast effect above is
    // declared first, so its coroutine is queued first) and the snackbar host
    // lives in AppShell — so the "«name» is now in your ideas" line survives the
    // navigation that immediately follows it.
    val cloned = ui.clonedIdea
    androidx.compose.runtime.LaunchedEffect(cloned) {
        if (cloned != null) {
            vm.consumeClone()
            onOpenIdea(cloned.id)
        }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text("@$username", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
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
            // A skeleton in the SHAPE of the screen (avatar, chat row, share
            // rows), not a spinner: the layout is known before the data is, so
            // the wait can be spent showing where things will land.
            BtScrollFill(Modifier.padding(pad)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))
                    BtSkeleton(Modifier.size(72.dp), shape = CircleShape)
                    Spacer(Modifier.height(2.dp))
                    BtSkeleton(Modifier.width(140.dp).height(20.dp))
                    Spacer(Modifier.height(6.dp))
                    BtSkeleton(Modifier.fillMaxWidth().height(52.dp), shape = BtShapes.card)
                    repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(88.dp), shape = BtShapes.card) }
                }
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
                    BtAvatar(name = username, iconId = ui.profileIcon, size = 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("@$username", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary)
                    ui.since?.let {
                        Text(stringResource(R.string.bt_social_friends_since, it.take(10)), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
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
                        Text(stringResource(R.string.bt_social_go_to_chat), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Shares-with-you.
            item {
                Text(
                    stringResource(R.string.bt_social_shares_with_you),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            // R3 §2: the view model has computed `error` since this screen was
            // written and nothing ever rendered it, so a failed `sharedWithMe()`
            // fell straight through to "@name shares nothing with you" — a claim
            // about the FRIEND made on the strength of a request that did not
            // complete. The failure replaces the empty *in this section only*:
            // the profile header and the go-to-chat row above it did not fail and
            // stay usable, which a full-screen error state would have taken away.
            val sharesError = ui.error
            if (sharesError != null && ui.sharesNothing) {
                item {
                    BtErrorState(
                        message = sharesError,
                        onRetry = { vm.load() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            } else if (ui.offline && ui.sharesNothing) {
                // Same reasoning one step further: a dropped connection is not a
                // fault and not an emptiness. It is the one failure the user can
                // usually fix, so it says so and keeps the retry.
                item {
                    BtOfflineState(
                        message = stringResource(R.string.bt_social_fo_offline_body),
                        onRetry = { vm.load() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            } else if (ui.sharesNothing) {
                item {
                    BtEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = stringResource(R.string.bt_social_fo_empty_title),
                        message = stringResource(R.string.bt_social_fo_empty_body, username),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
            items(ui.portfolios, key = { "p-" + it.portfolioId }) { p ->
                SharedItemRow(
                    icon = Icons.Outlined.PieChart,
                    title = p.name,
                    subtitle = stringResource(R.string.bt_social_kind_portfolio),
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
                    subtitle = pluralStringResource(R.plurals.bt_social_positions, c.positionCount, c.positionCount),
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
                    subtitle = pluralStringResource(R.plurals.bt_social_assets, w.itemCount, w.itemCount),
                    alertsOn = ui.activity[w.watchlistId] ?: false,
                    onToggleAlerts = { vm.toggleActivity(ShareableKind.Watchlist, w.watchlistId) },
                    onOpen = { onOpenSharedWatchlist(w.watchlistId, w.ownerName) },
                    trailing = null,
                )
            }
            // V5: shared IDEAS. Deliberately the last group and deliberately not
            // a `SharedItemRow`: the other three resolve to a read-only detail
            // screen, and this one has none to resolve to. Giving it the same
            // chevron would promise a view that does not exist.
            items(ui.ideas, key = { "i-" + it.ideaId }) { idea ->
                SharedIdeaRow(
                    idea = idea,
                    alertsOn = ui.activity[idea.ideaId] ?: false,
                    cloning = idea.ideaId in ui.cloning,
                    onToggleAlerts = { vm.toggleActivity(ShareableKind.Idea, idea.ideaId) },
                    onClone = { vm.cloneIdea(idea) },
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
                        Text(stringResource(R.string.bt_social_remove_friend), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.loss)
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
            title = { Text(stringResource(R.string.bt_social_remove_confirm_title, username)) },
            text = { Text(stringResource(R.string.bt_social_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.removeFriend() }) { Text(stringResource(R.string.bt_social_remove), color = bt.loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) } },
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
            ActivityAlertRow(alertsOn = alertsOn, onToggle = onToggleAlerts)
        }
    }
}

/**
 * A friend's shared idea.
 *
 * Two things make it a different row from [SharedItemRow] rather than another
 * call of it:
 *
 *  1. **It does not open.** There is no read-only shared-idea screen, because
 *     the platform ships no route that would feed one — `GET /ideas/{id}` is
 *     owner-only. So the card is not clickable and carries no chevron; the
 *     explicit copy button is the whole affordance, and the line above it says
 *     plainly what copying does. An "Open" that silently made a private copy in
 *     the user's own list would be the dishonest version of this row.
 *  2. **`hasThesis` is the only thing known about the rationale.** The thesis
 *     text is deliberately not inlined in the summary, so the subtitle reports
 *     the presence of a thesis and stops there rather than implying the words
 *     are one tap away.
 *
 * The activity-alert switch is shared with the sibling rows — an idea is a
 * per-viewer subscribable subject exactly like the other three
 * (`PUT /social/shared/activity/idea/{id}` → 200, verified live).
 */
@Composable
private fun SharedIdeaRow(
    idea: SharedIdeaSummary,
    alertsOn: Boolean,
    cloning: Boolean,
    onToggleAlerts: () -> Unit,
    onClone: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(idea.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight_SemiBold(), color = bt.textPrimary, maxLines = 1)
                    Text(
                        stringResource(
                            if (idea.hasThesis) R.string.bt_shared_idea_has_thesis else R.string.bt_shared_idea_no_thesis,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.bt_shared_idea_copy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            BtSecondaryButton(
                text = stringResource(
                    if (cloning) R.string.bt_shared_idea_copying else R.string.bt_shared_idea_copy,
                ),
                onClick = onClone,
                enabled = !cloning,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            ActivityAlertRow(alertsOn = alertsOn, onToggle = onToggleAlerts)
        }
    }
}

/**
 * The per-item activity-alert opt-in (V3-P6). One implementation, because the
 * switch means the same thing on all four share kinds and two copies of it would
 * be two chances to drift.
 */
@Composable
private fun ActivityAlertRow(alertsOn: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = if (alertsOn) bt.goldEmphasis else bt.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.bt_social_alert_activity),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = alertsOn,
            onCheckedChange = { onToggle() },
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

// Small helper to avoid importing FontWeight everywhere in this file.
private fun FontWeight_SemiBold() = androidx.compose.ui.text.font.FontWeight.SemiBold
