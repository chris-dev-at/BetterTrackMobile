package at.bettertrack.app.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AudienceState
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.FriendRequest
import at.bettertrack.app.data.repo.MyShared
import at.bettertrack.app.data.repo.MySharedItem
import at.bettertrack.app.data.repo.PersonShares
import at.bettertrack.app.data.repo.ShareAudience
import at.bettertrack.app.data.repo.ShareableKind
import at.bettertrack.app.data.repo.SharedWithMe
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.data.repo.groupByPerson
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCountBadge
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SocialSection { Friends, SharedWithMe, MyShares }

data class SocialUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val online: Boolean = true,
    val error: String? = null,
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val sharedWithMe: SharedWithMe? = null,
    val myShared: MyShared? = null,
    /** The item whose audience sheet is open (null = closed). */
    val sharingItem: MySharedItem? = null,
    /** The item's live audience (friendIds + link state); null while loading. */
    val sharingState: AudienceState? = null,
    val sharingBusy: Boolean = false,
    /** A freshly-minted public link to reveal once (null = no dialog). */
    val publicLinkToShow: String? = null,
)

class SocialViewModel(
    private val repo: SocialRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
    private val _state = MutableStateFlow(SocialUiState())
    val state: StateFlow<SocialUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init { load(initial = true) }

    fun load(initial: Boolean = false) {
        viewModelScope.launch {
            if (!isOnline.value) {
                _state.value = _state.value.copy(loading = false, refreshing = false, online = false)
                return@launch
            }
            _state.value = _state.value.copy(
                loading = initial && _state.value.friends.isEmpty(),
                online = true,
                error = null,
            )
            val friendsR = repo.friends()
            val requestsR = repo.requests()
            val sharedR = repo.sharedWithMe()
            val mineR = repo.myShared()

            val err = listOf(friendsR, requestsR, sharedR, mineR)
                .filterIsInstance<BtResult.Err>()
                .firstOrNull { !it.error.isNetwork }
            _state.value = _state.value.copy(
                loading = false,
                refreshing = false,
                online = true,
                error = if (_state.value.friends.isEmpty() && err != null) err.error.userMessage else null,
                friends = (friendsR as? BtResult.Ok)?.value ?: _state.value.friends,
                incoming = (requestsR as? BtResult.Ok)?.value?.incoming ?: _state.value.incoming,
                outgoing = (requestsR as? BtResult.Ok)?.value?.outgoing ?: _state.value.outgoing,
                sharedWithMe = (sharedR as? BtResult.Ok)?.value ?: _state.value.sharedWithMe,
                myShared = (mineR as? BtResult.Ok)?.value ?: _state.value.myShared,
            )
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        load()
    }

    fun sendRequest(identifier: String) = write {
        val r = repo.sendRequest(identifier)
        // No enumeration: identical message whether or not the target exists.
        if (r is BtResult.Ok) "Friend request sent to @${identifier.substringBefore('@')}" else (r as BtResult.Err).error.userMessage
    }

    fun decline(req: FriendRequest) = write { toastFor(repo.declineRequest(req.id), "Request declined") }
    fun cancel(req: FriendRequest) = write { toastFor(repo.cancelRequest(req.id), "Request cancelled") }
    fun accept(req: FriendRequest) = write { toastFor(repo.acceptRequest(req.id), "You're now friends with @${req.username}") }

    fun openSharing(item: MySharedItem) {
        _state.value = _state.value.copy(sharingItem = item, sharingState = null)
        viewModelScope.launch {
            val r = repo.getAudience(item.kind, item.id)
            val resolved = (r as? BtResult.Ok)?.value
                ?: AudienceState(item.kind, item.id, item.audience, emptySet(), linkActive = false, linkCreatedAt = null)
            // Only apply if the sheet is still open for the same item.
            if (_state.value.sharingItem?.id == item.id) {
                _state.value = _state.value.copy(sharingState = resolved)
            }
        }
    }

    fun closeSharing() { _state.value = _state.value.copy(sharingItem = null, sharingState = null) }
    fun dismissLink() { _state.value = _state.value.copy(publicLinkToShow = null) }

    fun applyAudience(item: MySharedItem, audience: ShareAudience, friendIds: Set<String>, acknowledge: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sharingBusy = true)
            val r = repo.setAudience(item.kind, item.id, audience, friendIds, acknowledge)
            when (r) {
                is BtResult.Ok -> {
                    _state.value = _state.value.copy(
                        sharingBusy = false,
                        sharingItem = null,
                        sharingState = null,
                        publicLinkToShow = r.value.publicUrl,
                    )
                    if (r.value.publicUrl == null) {
                        _toast.value = when (audience) {
                            ShareAudience.Private -> "“${item.name}” is now private"
                            ShareAudience.AllFriends -> "“${item.name}” shared with all friends"
                            ShareAudience.SpecificFriends -> "“${item.name}” shared with ${friendIds.size} friend${if (friendIds.size == 1) "" else "s"}"
                            ShareAudience.PublicLink -> "Public link is active"
                        }
                    }
                    load()
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(sharingBusy = false)
                    _toast.value = r.error.userMessage
                }
            }
        }
    }

    fun consumeToast() { _toast.value = null }

    private fun write(block: suspend () -> String) {
        viewModelScope.launch {
            val msg = block()
            _toast.value = msg
            load()
        }
    }

    private fun toastFor(r: BtResult<Unit>, ok: String): String =
        if (r is BtResult.Ok) ok else (r as BtResult.Err).error.userMessage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    onOpenFriend: (userId: String, username: String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenChatWith: (friendUserId: String, username: String) -> Unit,
) {
    val vm: SocialViewModel = viewModel {
        SocialViewModel(AppGraph.socialRepository, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val chatUnread by AppGraph.chatRepository.totalUnread.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var section by remember { mutableStateOf(SocialSection.Friends) }
    var showAdd by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
    androidx.compose.runtime.LaunchedEffect(toast) {
        toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() }
    }

    val refreshState = rememberPullToRefreshState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MessagesHeader(unread = chatUnread, onOpenChats = onOpenChats)
            SegmentedTabs(
                selected = section,
                onSelect = { section = it },
                sharedCount = ui.sharedWithMe?.count ?: 0,
                requestCount = ui.incoming.size,
            )
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                state = refreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = refreshState,
                        isRefreshing = ui.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = bt.surface,
                        color = bt.gold,
                    )
                },
            ) {
                when {
                    ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = bt.gold)
                    }
                    !ui.online && ui.friends.isEmpty() && ui.sharedWithMe == null -> BtEmptyState(
                        icon = Icons.Outlined.People,
                        title = "You're offline",
                        message = "Friends and shares need a connection. Reconnect to see them.",
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                    )
                    ui.error != null && ui.friends.isEmpty() -> BtErrorState(
                        message = ui.error,
                        onRetry = { vm.load(initial = true) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> when (section) {
                        SocialSection.Friends -> FriendsSection(ui, vm, onAdd = { showAdd = true }, onOpenFriend = onOpenFriend, onChatWith = onOpenChatWith)
                        SocialSection.SharedWithMe -> SharedWithMeSection(ui.sharedWithMe, onOpenPerson = onOpenFriend)
                        SocialSection.MyShares -> MySharesSection(ui.myShared, onShare = { vm.openSharing(it) })
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddFriendDialog(
            onDismiss = { showAdd = false },
            onSend = { id -> showAdd = false; vm.sendRequest(id) },
        )
    }

    val item = ui.sharingItem
    val audienceState = ui.sharingState
    if (item != null && audienceState != null) {
        AudiencePickerSheet(
            itemName = item.name,
            kind = item.kind,
            currentAudience = audienceState.audience,
            friends = ui.friends,
            initialFriendIds = audienceState.friendIds,
            linkActive = audienceState.linkActive,
            busy = ui.sharingBusy,
            onApply = { audience, friendIds, ack -> vm.applyAudience(item, audience, friendIds, ack) },
            onDismiss = { vm.closeSharing() },
        )
    }

    ui.publicLinkToShow?.let { url ->
        PublicLinkDialog(
            url = url,
            onCopy = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("BetterTrack link", url))
                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, "Share link"))
            },
            onDismiss = { vm.dismissLink() },
        )
    }
}

// ── Segmented control ────────────────────────────────────────────────────────

@Composable
private fun SegmentedTabs(
    selected: SocialSection,
    onSelect: (SocialSection) -> Unit,
    sharedCount: Int,
    requestCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Segment("Friends", requestCount, selected == SocialSection.Friends, Modifier.weight(1f)) { onSelect(SocialSection.Friends) }
        Segment("Shared", sharedCount, selected == SocialSection.SharedWithMe, Modifier.weight(1f)) { onSelect(SocialSection.SharedWithMe) }
        Segment("My shares", 0, selected == SocialSection.MyShares, Modifier.weight(1f)) { onSelect(SocialSection.MyShares) }
    }
}

@Composable
private fun Segment(label: String, badge: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = if (selected) bt.gold.copy(alpha = 0.14f) else bt.surface,
        contentColor = if (selected) bt.goldEmphasis else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.gold.copy(alpha = 0.45f) else bt.border),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            if (badge > 0) {
                Spacer(Modifier.width(6.dp))
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = BtShapes.pill, color = bt.gold) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = bt.onGold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesHeader(unread: Int, onOpenChats: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onOpenChats,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Messages", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary, modifier = Modifier.weight(1f))
            if (unread > 0) {
                BtCountBadge(count = unread)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

// ── Friends section ──────────────────────────────────────────────────────────

@Composable
private fun FriendsSection(
    ui: SocialUiState,
    vm: SocialViewModel,
    onAdd: () -> Unit,
    onOpenFriend: (String, String) -> Unit,
    onChatWith: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            BtPrimaryButton(
                text = "Add a friend",
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }

        if (ui.incoming.isNotEmpty()) {
            item { SectionHeader("Requests to you", ui.incoming.size) }
            items(ui.incoming, key = { "in-" + it.id }) { req ->
                RequestRow(req, incoming = true, onAccept = { vm.accept(req) }, onDecline = { vm.decline(req) }, onCancel = {})
            }
        }
        if (ui.outgoing.isNotEmpty()) {
            item { SectionHeader("Sent requests", ui.outgoing.size) }
            items(ui.outgoing, key = { "out-" + it.id }) { req ->
                RequestRow(req, incoming = false, onAccept = {}, onDecline = {}, onCancel = { vm.cancel(req) })
            }
        }

        item { SectionHeader("Friends", ui.friends.size) }
        if (ui.friends.isEmpty() && ui.incoming.isEmpty() && ui.outgoing.isEmpty()) {
            item {
                BtEmptyState(
                    icon = Icons.Outlined.Group,
                    title = "No friends yet",
                    message = "Add friends by username or email to share portfolios and see what they share with you.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        } else {
            items(ui.friends, key = { "f-" + it.userId }) { f ->
                FriendRow(f, onOpen = { onOpenFriend(f.userId, f.username) }, onChat = { onChatWith(f.userId, f.username) })
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
        Spacer(Modifier.width(8.dp))
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = bt.textMuted)
    }
}

/** A friend row: whole card opens the overview; the chat quick-action stays here. */
@Composable
private fun FriendRow(f: Friend, onOpen: () -> Unit, onChat: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = f.username, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${f.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text("Friends since ${f.since.take(10)}", style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            IconButton(onClick = onChat) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = "Message @${f.username}", tint = bt.textSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RequestRow(
    req: FriendRequest,
    incoming: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = req.username, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${req.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(
                    if (incoming) "Wants to be your friend" else "Waiting to accept",
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            if (incoming) {
                IconButton(onClick = onDecline) {
                    Icon(Icons.Outlined.Close, contentDescription = "Decline", tint = bt.textSecondary)
                }
                Spacer(Modifier.width(2.dp))
                Surface(onClick = onAccept, shape = BtShapes.pill, color = bt.gold, contentColor = bt.onGold) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Accept", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                BtBadge(text = "Pending", kind = BtBadgeKind.Gold)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text("Cancel", color = bt.textSecondary) }
            }
        }
    }
}

// ── Shared-with-me section (grouped by PERSON) ───────────────────────────────

@Composable
private fun SharedWithMeSection(shared: SharedWithMe?, onOpenPerson: (String, String) -> Unit) {
    if (shared == null || shared.isEmpty) {
        BtEmptyState(
            icon = Icons.Outlined.People,
            title = "Nothing shared with you yet",
            message = "When a friend shares a portfolio, watchlist or conglomerate with you, they'll show up here — grouped by friend.",
            modifier = Modifier.fillMaxSize().padding(24.dp),
        )
        return
    }
    val people = remember(shared) { shared.groupByPerson() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionHeader("People sharing with you", people.size) }
        items(people, key = { "person-" + it.ownerId }) { p ->
            PersonRow(p, onClick = { onOpenPerson(p.ownerId, p.ownerName) })
        }
    }
}

@Composable
private fun PersonRow(p: PersonShares, onClick: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = p.ownerName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${p.ownerName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(sharesSummary(p), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.portfolios.isNotEmpty()) MiniCount(Icons.Outlined.PieChart, p.portfolios.size)
                if (p.conglomerates.isNotEmpty()) MiniCount(Icons.Outlined.Dashboard, p.conglomerates.size)
                if (p.watchlists.isNotEmpty()) MiniCount(Icons.AutoMirrored.Outlined.ShowChart, p.watchlists.size)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MiniCount(icon: ImageVector, count: Int) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Icon(icon, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = bt.textSecondary)
    }
}

private fun sharesSummary(p: PersonShares): String {
    val parts = buildList {
        if (p.portfolios.isNotEmpty()) add("${p.portfolios.size} portfolio${plural(p.portfolios.size)}")
        if (p.conglomerates.isNotEmpty()) add("${p.conglomerates.size} conglomerate${plural(p.conglomerates.size)}")
        if (p.watchlists.isNotEmpty()) add("${p.watchlists.size} watchlist${plural(p.watchlists.size)}")
    }
    return parts.joinToString(" · ")
}

private fun plural(n: Int): String = if (n == 1) "" else "s"

// ── My-shares section ────────────────────────────────────────────────────────

@Composable
private fun MySharesSection(mine: MyShared?, onShare: (MySharedItem) -> Unit) {
    val bt = BtTheme.colors
    if (mine == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = bt.gold) }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                if (mine.sharedCount == 0) "You're not sharing anything yet" else "You're sharing ${mine.sharedCount} of ${mine.items.size} items",
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        items(mine.items, key = { it.kind.name + "-" + it.id }) { item -> MySharedRow(item, onShare = { onShare(item) }) }
        item {
            Text(
                "Tap any item to change who can see it, or revoke access.",
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MySharedRow(item: MySharedItem, onShare: () -> Unit) {
    val bt = BtTheme.colors
    val chrome = audienceChrome(item.audience, item.friendCount)
    val typeIcon = when (item.kind) {
        ShareableKind.Portfolio -> Icons.Outlined.PieChart
        ShareableKind.Watchlist -> Icons.AutoMirrored.Outlined.ShowChart
        ShareableKind.Conglomerate -> Icons.Outlined.Dashboard
    }
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onShare) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(typeIcon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary, maxLines = 1)
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(chrome.icon, contentDescription = null, tint = if (chrome.kind == BtBadgeKind.Gold) bt.goldEmphasis else bt.textMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                BtBadge(text = chrome.label, kind = chrome.kind)
            }
        }
    }
}

private data class AudienceChrome(val icon: ImageVector, val label: String, val kind: BtBadgeKind)

private fun audienceChrome(a: ShareAudience, friendCount: Int): AudienceChrome = when (a) {
    ShareAudience.Private -> AudienceChrome(Icons.Outlined.Lock, "Private", BtBadgeKind.Neutral)
    ShareAudience.SpecificFriends -> AudienceChrome(Icons.Outlined.People, if (friendCount > 0) "$friendCount friend${plural(friendCount)}" else "Some friends", BtBadgeKind.Gold)
    ShareAudience.AllFriends -> AudienceChrome(Icons.Outlined.Group, "All friends", BtBadgeKind.Gold)
    ShareAudience.PublicLink -> AudienceChrome(Icons.Outlined.Link, "Public", BtBadgeKind.Gold)
}

// ── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
private fun PublicLinkDialog(url: String, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        icon = { Icon(Icons.Outlined.Link, contentDescription = null, tint = bt.gold) },
        title = { Text("Public link created") },
        text = {
            Column {
                Text(
                    "Anyone with this link can view it. It's shown only once — copy it now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = BtShapes.card,
                    color = bt.bg,
                    border = BorderStroke(1.dp, bt.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy() }) { Text("Copy", color = bt.gold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onShare() }) { Text("Share", color = bt.textSecondary) }
                TextButton(onClick = onDismiss) { Text("Done", color = bt.textSecondary) }
            }
        },
    )
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    val bt = BtTheme.colors
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = bt.gold) },
        title = { Text("Add a friend") },
        text = {
            Column {
                Text("Enter their username or email. They'll get a request to accept.", style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("username or email", color = bt.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSend(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Send request", color = if (text.isNotBlank()) bt.gold else bt.textMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = bt.textSecondary) } },
    )
}
