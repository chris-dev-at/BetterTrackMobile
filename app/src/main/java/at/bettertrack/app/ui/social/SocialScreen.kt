package at.bettertrack.app.ui.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.AudienceState
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.FriendGroup
import at.bettertrack.app.data.repo.FriendGroupRepository
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
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtHeaderWordmark
import at.bettertrack.app.ui.components.BtSettingsGear
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtNeedsYouGroup
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtPinnedHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtFabVisibility
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.fabVisibleForList
import at.bettertrack.app.ui.mirrorchain.MirrorInvitesCard
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
    val error: BtMessage? = null,
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequest> = emptyList(),
    val outgoing: List<FriendRequest> = emptyList(),
    val sharedWithMe: SharedWithMe? = null,
    val myShared: MyShared? = null,
    /** V5: the caller's friend groups — the audience sheet's Group rung reads these. */
    val groups: List<FriendGroup> = emptyList(),
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
    private val groupRepo: FriendGroupRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline
    private val _state = MutableStateFlow(SocialUiState())
    val state: StateFlow<SocialUiState> = _state.asStateFlow()

    private val _toast = MutableStateFlow<SocialToast?>(null)
    val toast: StateFlow<SocialToast?> = _toast.asStateFlow()

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
            // Groups feed the audience sheet's Group rung. They are deliberately
            // NOT part of the error fold below: an empty group list is a normal,
            // fully usable state, so a failure here must never blank the tab.
            val groupsR = groupRepo.groups()

            val err = listOf(friendsR, requestsR, sharedR, mineR)
                .filterIsInstance<BtResult.Err>()
                .firstOrNull { !it.error.isNetwork }
            _state.value = _state.value.copy(
                loading = false,
                refreshing = false,
                online = true,
                error = if (_state.value.friends.isEmpty() && err != null) err.error.asMessage() else null,
                friends = (friendsR as? BtResult.Ok)?.value ?: _state.value.friends,
                incoming = (requestsR as? BtResult.Ok)?.value?.incoming ?: _state.value.incoming,
                outgoing = (requestsR as? BtResult.Ok)?.value?.outgoing ?: _state.value.outgoing,
                sharedWithMe = (sharedR as? BtResult.Ok)?.value ?: _state.value.sharedWithMe,
                myShared = (mineR as? BtResult.Ok)?.value ?: _state.value.myShared,
                groups = (groupsR as? BtResult.Ok)?.value ?: _state.value.groups,
            )
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        load()
    }

    fun sendRequest(identifier: String): Unit = write {
        val r = repo.sendRequest(identifier)
        // No enumeration: identical message whether or not the target exists.
        if (r is BtResult.Ok) SocialToast.Res(R.string.bt_social_toast_request_sent, listOf(identifier.substringBefore('@')))
        else SocialToast.Failure((r as BtResult.Err).error.asMessage(), onRetry = { sendRequest(identifier) })
    }

    fun decline(req: FriendRequest): Unit = write { toastFor(repo.declineRequest(req.id), SocialToast.Res(R.string.bt_social_toast_request_declined)) { decline(req) } }
    fun cancel(req: FriendRequest): Unit = write { toastFor(repo.cancelRequest(req.id), SocialToast.Res(R.string.bt_social_toast_request_cancelled)) { cancel(req) } }
    fun accept(req: FriendRequest): Unit = write { toastFor(repo.acceptRequest(req.id), SocialToast.Res(R.string.bt_social_toast_now_friends, listOf(req.username))) { accept(req) } }

    fun openSharing(item: MySharedItem) {
        _state.value = _state.value.copy(sharingItem = item, sharingState = null)
        viewModelScope.launch {
            val r = repo.getAudience(item.kind, item.id)
            val resolved = (r as? BtResult.Ok)?.value
                ?: AudienceState(
                    kind = item.kind,
                    subjectId = item.id,
                    audience = item.audience,
                    friendIds = emptySet(),
                    groupId = null,
                    linkActive = false,
                    linkCreatedAt = null,
                )
            // Only apply if the sheet is still open for the same item.
            if (_state.value.sharingItem?.id == item.id) {
                _state.value = _state.value.copy(sharingState = resolved)
            }
        }
    }

    fun closeSharing() { _state.value = _state.value.copy(sharingItem = null, sharingState = null) }
    fun dismissLink() { _state.value = _state.value.copy(publicLinkToShow = null) }

    fun applyAudience(
        item: MySharedItem,
        audience: ShareAudience,
        friendIds: Set<String>,
        groupId: String?,
        acknowledge: Boolean,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(sharingBusy = true)
            // The repository sends each optional field only on the rung that owns
            // it, so passing groupId unconditionally here is safe.
            val r = repo.setAudience(item.kind, item.id, audience, friendIds, acknowledge, groupId)
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
                            ShareAudience.Private -> SocialToast.Res(R.string.bt_social_toast_now_private, listOf(item.name))
                            ShareAudience.AllFriends -> SocialToast.Res(R.string.bt_social_toast_shared_all, listOf(item.name))
                            ShareAudience.SpecificFriends -> SocialToast.Quantity(R.plurals.bt_social_toast_shared_specific, friendIds.size, listOf(item.name, friendIds.size))
                            // Name the group, not the mechanism — "shared with
                            // Family" is what the user just decided. The unnamed
                            // fallback only fires if the group vanished mid-apply.
                            ShareAudience.Group ->
                                _state.value.groups.firstOrNull { it.id == groupId }?.name
                                    ?.let { SocialToast.Res(R.string.bt_groups_toast_shared, listOf(item.name, it)) }
                                    ?: SocialToast.Res(R.string.bt_groups_toast_shared_generic, listOf(item.name))
                            ShareAudience.PublicLink -> SocialToast.Res(R.string.bt_social_toast_public_active)
                        }
                    }
                    load()
                }
                is BtResult.Err -> {
                    _state.value = _state.value.copy(sharingBusy = false)
                    // The sheet is gone but every argument survives here, so
                    // "Try again" can re-issue exactly the audience the user
                    // just chose instead of making them re-pick it.
                    _toast.value = SocialToast.Failure(
                        r.error.asMessage(),
                        onRetry = { applyAudience(item, audience, friendIds, groupId, acknowledge) },
                    )
                }
            }
        }
    }

    fun consumeToast() { _toast.value = null }

    private fun write(block: suspend () -> SocialToast) {
        viewModelScope.launch {
            _toast.value = block()
            load()
        }
    }

    private fun toastFor(r: BtResult<Unit>, ok: SocialToast, retry: () -> Unit): SocialToast =
        if (r is BtResult.Ok) ok else SocialToast.Failure((r as BtResult.Err).error.asMessage(), retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialScreen(
    onOpenFriend: (userId: String, username: String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenChatWith: (friendUserId: String, username: String) -> Unit,
    onOpenGroups: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: SocialViewModel = viewModel {
        SocialViewModel(AppGraph.socialRepository, AppGraph.friendGroupRepository, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val ui by vm.state.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val chatRepo: ChatRepository = AppGraph.chatRepository
    val chatUnread by chatRepo.totalUnread.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalBtSnackbar.current
    var section by remember { mutableStateOf(SocialSection.Friends) }
    var showAdd by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
    // Keep the Messages unread badge live while the Social tab is foregrounded:
    // ref-counted realtime (socket + foreground poll). connectRealtime() starts the
    // poll loop, whose first tick refetches the conversation list immediately, so the
    // badge updates on entry without a separate refresh call.
    androidx.compose.runtime.DisposableEffect(Unit) {
        chatRepo.connectRealtime()
        onDispose { chatRepo.disconnectRealtime() }
    }
    SocialToastEffect(toast) { vm.consumeToast() }

    val refreshState = rememberPullToRefreshState()
    // Pinned brand strip, like every top-level tab (owner order 2026-08-07). Still
    // a real behaviour rather than null: it is what keeps the tonal lift as the
    // friends list travels under the bar. See BtCollapsingHeader's `pinned`
    // branch.
    val scrollBehavior = rememberBtPinnedHeaderBehavior()
    val fabVisibility = rememberBtFabVisibility()
    val friendsListState = rememberLazyListState()
    // Back at the very top = nothing to get out of the way of. Without this the
    // FAB stays hidden across a segment switch: `fabVisibility` is remembered at
    // the screen level (so it survives the switch) while the list resets to the
    // top (so no upward delta is ever produced to bring the FAB back).
    androidx.compose.runtime.LaunchedEffect(friendsListState) {
        androidx.compose.runtime.snapshotFlow {
            friendsListState.firstVisibleItemIndex == 0 &&
                friendsListState.firstVisibleItemScrollOffset == 0
        }.collect { atTop -> if (atTop) fabVisibility.show() }
    }
    val addFriendCd = stringResource(R.string.bt_social_add_friend)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                // Ordering kept from Portfolio (see its comment). Both of these
                // connections observe and consume nothing now that the header is
                // pinned, so today the order decides nothing — it stays because
                // the day this bar goes back to collapsing, an inner FAB
                // connection would silently start seeing only the delta the
                // header had left over, and a FAB that stops hiding is a bug
                // nobody would trace back to a line ordering.
                .nestedScroll(fabVisibility.nestedScroll)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            BtCollapsingHeader(
                // No text title: the bottom bar's selected label already says
                // "People" a few dp below, and the segments directly under the
                // bar name this tab's contents more usefully than the tab's own
                // word does. See the `title` KDoc.
                title = null,
                scrollBehavior = scrollBehavior,
                pinned = true,
                navigationIcon = { BtHeaderWordmark() },
                // R1 put Messages in the shell bar as People's ONE action; R2
                // gives People its own header, so the action moves with it —
                // same affordance, same place on screen. The unread COUNT stays
                // off it (mandate §1: badges left the top bar); it is carried by
                // the People tab dot and by the Needs-you row below.
                action = {
                    IconButton(onClick = onOpenChats) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Chat,
                            contentDescription = stringResource(R.string.bt_top_messages),
                            tint = bt.textSecondary,
                        )
                    }
                },
                // The ⋮ that used to sit here carried exactly one entry, "Friend
                // groups", and that entry already had an in-content home: the
                // doorways group at the foot of the friends list, where it sits
                // beside Messages with an icon, a subtitle and a chevron. A menu
                // whose whole contents are visible on the screen behind it is not
                // a shortcut, it is a second name for the same door — and it was
                // one of the ⋮s the owner meant by "every page shouldn't have the
                // same 3 dots leading to 1000 different results". Dissolved; the
                // slot it vacated is the gear's, app-wide.
                settings = { BtSettingsGear(onOpenSettings) },
            )
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
                // Pulled out of the `when` so it smart-casts: the error is a
                // BtMessage now, and BtErrorState takes it non-null.
                val loadError = ui.error
                when {
                    ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = bt.gold)
                    }
                    !ui.online && ui.friends.isEmpty() && ui.sharedWithMe == null -> BtOfflineState(
                        title = stringResource(R.string.bt_social_offline_title),
                        message = stringResource(R.string.bt_social_offline_body),
                        onRetry = { vm.load(initial = true) },
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                    )
                    loadError != null && ui.friends.isEmpty() -> BtErrorState(
                        message = loadError,
                        onRetry = { vm.load(initial = true) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> when (section) {
                        SocialSection.Friends -> FriendsSection(
                            ui = ui,
                            vm = vm,
                            listState = friendsListState,
                            chatUnread = chatUnread,
                            onOpenFriend = onOpenFriend,
                            onChatWith = onOpenChatWith,
                            onOpenChats = onOpenChats,
                            onOpenGroups = onOpenGroups,
                            onAddFriend = { showAdd = true },
                        )
                        SocialSection.SharedWithMe -> SharedWithMeSection(ui.sharedWithMe, onOpenPerson = onOpenFriend)
                        SocialSection.MyShares -> MySharesSection(ui.myShared, onShare = { vm.openSharing(it) })
                    }
                }
            }
        }

        // The tab's ONE creation entry (mandate §1). It used to be a full-width
        // gold button at the top of the friends list, which put "add someone new"
        // above "answer the people already asking" — the exact inversion §3 is
        // about. As a FAB it is always reachable, gets out of the way on scroll,
        // and stops competing with the requests for the lead.
        //
        // …and it stands down entirely on an empty friends list, where the empty
        // state carries the single "Add a friend" CTA ([fabVisibleForList]).
        // `empty` is the same three-way test the empty state itself uses: an
        // outgoing request you sent is a friends list that is doing something.
        val friendsFabVisible = fabVisibleForList(
            resolved = !ui.loading,
            empty = ui.friends.isEmpty() && ui.incoming.isEmpty() && ui.outgoing.isEmpty(),
        )
        if (section == SocialSection.Friends && friendsFabVisible) {
            fabVisibility.Content(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            ) {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = bt.gold,
                    contentColor = bt.onGold,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.semantics { contentDescription = addFriendCd },
                ) { Icon(Icons.Outlined.PersonAdd, contentDescription = null) }
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
            groups = ui.groups,
            initialGroupId = audienceState.groupId,
            linkActive = audienceState.linkActive,
            busy = ui.sharingBusy,
            onApply = { audience, friendIds, groupId, ack ->
                vm.applyAudience(item, audience, friendIds, groupId, ack)
            },
            // Leaving for Groups closes the sheet; the user comes back to a fresh
            // one with the group they just made already in the list.
            onOpenGroups = { vm.closeSharing(); onOpenGroups() },
            onDismiss = { vm.closeSharing() },
        )
    }

    ui.publicLinkToShow?.let { url ->
        // Read in composition, not inside the lambdas: a `context.getString` in a
        // click handler is resolved against a context Compose is not observing,
        // so it can hand back the previous language after an in-app switch
        // (S6 P2-18, LocalContextGetResourceValueCall).
        val clipLabel = stringResource(R.string.bt_social_link_clip_label)
        val chooserTitle = stringResource(R.string.bt_social_link_chooser_title)
        PublicLinkDialog(
            url = url,
            onCopy = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText(clipLabel, url))
                snackbar.show(R.string.bt_social_link_copied_toast)
            },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(send, chooserTitle))
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
        Segment(stringResource(R.string.bt_social_tab_friends), requestCount, selected == SocialSection.Friends, Modifier.weight(1f)) { onSelect(SocialSection.Friends) }
        Segment(stringResource(R.string.bt_social_tab_shared), sharedCount, selected == SocialSection.SharedWithMe, Modifier.weight(1f)) { onSelect(SocialSection.SharedWithMe) }
        Segment(stringResource(R.string.bt_social_tab_my_shares), 0, selected == SocialSection.MyShares, Modifier.weight(1f)) { onSelect(SocialSection.MyShares) }
    }
}

@Composable
private fun Segment(label: String, badge: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.pill,
        color = if (selected) bt.goldWash else bt.surface,
        contentColor = if (selected) bt.goldEmphasis else bt.textSecondary,
        border = BorderStroke(1.dp, if (selected) bt.edge(bt.gold, 0.45f) else bt.border),
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

// ── Friends section ──────────────────────────────────────────────────────────

/**
 * The Friends segment, re-ranked for R2 (§3: *"requests + unread first, then
 * friends, then shared items"*).
 *
 * ## What the order was, and why it was wrong
 *
 * Before: mirror invites → a full-width gold **Add friend** button → the Groups
 * doorway → incoming requests → sent requests → friends. So the first two things
 * on the People tab were a creation action and a navigation doorway, and the
 * people actually waiting on an answer were the fourth block down — below the
 * fold on a 360×800 screen once the segments and the messages card were counted.
 *
 * After: everything genuinely waiting on the user is one **Needs you** block at
 * the top (mirror invites, unread messages, incoming friend requests), then the
 * friends themselves, then the quiet doorways (sent requests, Groups, Messages).
 * Add-friend became the tab's FAB.
 *
 * ## Why the block is a list item and not a fixed header
 *
 * Workbench's identical block is pinned above its segments because its content
 * is bounded (three rows and a count). This one is not — every incoming request
 * carries its own accept/decline decision and all of them must be reachable, so
 * capping it would strand requests behind a "+N more" that goes nowhere. Living
 * inside the list means it can be exactly as long as it needs to be and still
 * scrolls away once dealt with.
 */
@Composable
private fun FriendsSection(
    ui: SocialUiState,
    vm: SocialViewModel,
    listState: LazyListState,
    chatUnread: Int,
    onOpenFriend: (String, String) -> Unit,
    onChatWith: (String, String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenGroups: () -> Unit,
    onAddFriend: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // V5 S2c: a group-portfolio (mirrorchain) invitation is the one thing on
        // this tab that is genuinely WAITING on the user, so it leads. The card
        // renders at zero height when there is nothing to answer, so it costs
        // this list nothing the rest of the time.
        item { MirrorInvitesCard(modifier = Modifier.fillMaxWidth()) }

        if (chatUnread > 0 || ui.incoming.isNotEmpty()) {
            item(key = "needs-you") {
                BtNeedsYouGroup(title = stringResource(R.string.bt_needs_you)) {
                    if (chatUnread > 0) {
                        BtGroupRow(
                            title = stringResource(R.string.bt_social_messages),
                            subtitle = pluralStringResource(
                                R.plurals.bt_social_unread_messages,
                                chatUnread,
                                chatUnread,
                            ),
                            icon = Icons.AutoMirrored.Outlined.Chat,
                            iconTint = BtTheme.colors.goldEmphasis,
                            onClick = onOpenChats,
                            trailing = { BtCountBadge(count = chatUnread) },
                        )
                    }
                    ui.incoming.forEach { req ->
                        RequestDecisionRow(
                            req = req,
                            onAccept = { vm.accept(req) },
                            onDecline = { vm.decline(req) },
                        )
                    }
                }
            }
        }

        item { BtSectionHeader(stringResource(R.string.bt_social_tab_friends), count = ui.friends.size) }
        if (ui.friends.isEmpty() && ui.incoming.isEmpty() && ui.outgoing.isEmpty()) {
            item {
                // The FAB stands down on exactly this condition, so this button
                // is the tab's only way to add someone.
                BtEmptyState(
                    icon = Icons.Outlined.Group,
                    title = stringResource(R.string.bt_social_no_friends_title),
                    message = stringResource(R.string.bt_social_no_friends_body),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_social_add_friend),
                            onClick = onAddFriend,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
            }
        } else {
            items(ui.friends, key = { "f-" + it.userId }) { f ->
                FriendRow(f, onOpen = { onOpenFriend(f.userId, f.username) }, onChat = { onChatWith(f.userId, f.username) })
            }
        }

        // Sent requests are STATUS, not a decision — nobody is waiting on the
        // user here, they are waiting on someone else — so they sit below the
        // friends rather than above them.
        if (ui.outgoing.isNotEmpty()) {
            item { BtSectionHeader(stringResource(R.string.bt_social_sent_requests), count = ui.outgoing.size) }
            items(ui.outgoing, key = { "out-" + it.id }) { req ->
                SentRequestRow(req, onCancel = { vm.cancel(req) })
            }
        }

        // The quiet doorways, grouped as one object rather than two more cards
        // in a list of cards. Messages is here as well as in the header action:
        // Fable's addendum rule is that a bar affordance may never be the ONLY
        // path to a surface, and a group whose siblings are all people-related
        // is where someone looking for their conversations would actually look.
        item(key = "doorways") {
            BtGroup(modifier = Modifier.padding(top = 8.dp)) {
                BtGroupRow(
                    title = stringResource(R.string.bt_groups_entry_title),
                    subtitle = stringResource(R.string.bt_groups_entry_sub),
                    icon = Icons.Outlined.Groups,
                    onClick = onOpenGroups,
                    trailing = if (ui.groups.isNotEmpty()) {
                        { BtBadge(text = ui.groups.size.toString(), kind = BtBadgeKind.Neutral) }
                    } else {
                        null
                    },
                )
                BtGroupRow(
                    title = stringResource(R.string.bt_social_messages),
                    subtitle = stringResource(R.string.bt_social_messages_sub),
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    onClick = onOpenChats,
                )
            }
        }
    }
}

/**
 * An incoming friend request inside the Needs-you block: the same decision the
 * old `RequestRow` offered, without the card chrome the group already provides.
 */
@Composable
private fun RequestDecisionRow(req: FriendRequest, onAccept: () -> Unit, onDecline: () -> Unit) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BtAvatar(name = req.username, iconId = req.profileIcon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("@${req.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
            Text(
                stringResource(R.string.bt_social_request_wants),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
        IconButton(onClick = onDecline) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bt_social_decline_cd), tint = bt.textSecondary)
        }
        Surface(onClick = onAccept, shape = BtShapes.pill, color = bt.gold, contentColor = bt.onGold) {
            Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.bt_social_accept), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// `PeopleOverflow` is deleted (nav restoration 2026-08-06). Its single entry,
// "Friend groups", is reached from the doorways group at the foot of the friends
// list — see the `settings =` comment on this screen's header.

// R2: `GroupsEntryRow` and this file's private `SectionHeader` are gone. Groups
// is now a row in the doorways group at the foot of the friends list, and the
// header is `BtSectionHeader` — one implementation for the whole app instead of
// three private copies that had drifted apart on casing and spacing.

/**
 * A friend row. TWO targets, both spoken (S6 P1-16):
 *  · the row itself opens the friend's overview;
 *  · the speech bubble opens the chat.
 *
 * The trailing chevron is gone. It was a third target sitting inside the row's
 * own tap area, doing exactly what the row already did — so it taught the user
 * that this row has three separate things to hit when it has two, and it pushed
 * the one real action (the bubble) off the row's optical end.
 */
@Composable
private fun FriendRow(f: Friend, onOpen: () -> Unit, onChat: () -> Unit) {
    val bt = BtTheme.colors
    val openCd = stringResource(R.string.bt_social_open_friend_cd, f.username)
    BtCard(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = openCd },
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = f.username, iconId = f.profileIcon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${f.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(stringResource(R.string.bt_social_friends_since, f.since.take(10)), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            IconButton(onClick = onChat) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.bt_social_message_friend_cd, f.username), tint = bt.textSecondary)
            }
        }
    }
}

/**
 * A request the user SENT. R2 split this from the incoming case (now
 * [RequestDecisionRow]): one carries a decision and leads the screen, the other
 * is status and sits below the friends, and a single composable with an
 * `incoming` flag was making one row pretend to be both.
 */
@Composable
private fun SentRequestRow(req: FriendRequest, onCancel: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = req.username, iconId = req.profileIcon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${req.username}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(
                    stringResource(R.string.bt_social_request_waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            BtBadge(text = stringResource(R.string.bt_social_pending), kind = BtBadgeKind.Neutral)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
        }
    }
}

// ── Shared-with-me section (grouped by PERSON) ───────────────────────────────

@Composable
private fun SharedWithMeSection(shared: SharedWithMe?, onOpenPerson: (String, String) -> Unit) {
    if (shared == null || shared.isEmpty) {
        BtEmptyState(
            icon = Icons.Outlined.People,
            title = stringResource(R.string.bt_social_swm_empty_title),
            message = stringResource(R.string.bt_social_swm_empty_body),
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
        item { BtSectionHeader(stringResource(R.string.bt_social_people_sharing), count = people.size) }
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
            BtAvatar(name = p.ownerName, iconId = p.ownerIcon, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("@${p.ownerName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(sharesSummary(p), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.portfolios.isNotEmpty()) MiniCount(Icons.Outlined.PieChart, p.portfolios.size)
                if (p.conglomerates.isNotEmpty()) MiniCount(Icons.Outlined.Dashboard, p.conglomerates.size)
                if (p.watchlists.isNotEmpty()) MiniCount(Icons.AutoMirrored.Outlined.ShowChart, p.watchlists.size)
                // V5: a friend's shared ideas. Same glyph the my-shares row and
                // the Workboard Ideas segment use, so the mark means one thing.
                if (p.ideas.isNotEmpty()) MiniCount(Icons.Outlined.Lightbulb, p.ideas.size)
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

@Composable
private fun sharesSummary(p: PersonShares): String {
    val parts = buildList {
        if (p.portfolios.isNotEmpty()) add(pluralStringResource(R.plurals.bt_social_count_portfolios, p.portfolios.size, p.portfolios.size))
        if (p.conglomerates.isNotEmpty()) add(pluralStringResource(R.plurals.bt_social_count_conglomerates, p.conglomerates.size, p.conglomerates.size))
        if (p.watchlists.isNotEmpty()) add(pluralStringResource(R.plurals.bt_social_count_watchlists, p.watchlists.size, p.watchlists.size))
        if (p.ideas.isNotEmpty()) add(pluralStringResource(R.plurals.bt_social_count_ideas, p.ideas.size, p.ideas.size))
    }
    return parts.joinToString(" · ")
}

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
                // "X of Y items" — the noun belongs to Y, so the TOTAL picks the
                // form; the shared count is only the numerator.
                if (mine.sharedCount == 0) {
                    stringResource(R.string.bt_social_not_sharing)
                } else {
                    pluralStringResource(
                        R.plurals.bt_social_sharing_count,
                        mine.items.size,
                        mine.sharedCount,
                        mine.items.size,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        items(mine.items, key = { it.kind.name + "-" + it.id }) { item -> MySharedRow(item, onShare = { onShare(item) }) }
        item {
            Text(
                stringResource(R.string.bt_social_my_shares_hint),
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
        // A saved workboard analysis: a written thesis with a backtest behind it.
        ShareableKind.Idea -> Icons.Outlined.Lightbulb
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
                val subtitle = when (item.kind) {
                    ShareableKind.Portfolio -> stringResource(R.string.bt_social_kind_portfolio)
                    ShareableKind.Conglomerate -> pluralStringResource(R.plurals.bt_social_positions, item.count, item.count)
                    ShareableKind.Watchlist -> pluralStringResource(R.plurals.bt_social_assets, item.count, item.count)
                    // An idea has no countable rows — it is one thesis and one
                    // backtest — so it names what it is instead of counting.
                    ShareableKind.Idea -> stringResource(R.string.bt_share_idea_subtitle)
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
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

@Composable
private fun audienceChrome(a: ShareAudience, friendCount: Int): AudienceChrome = when (a) {
    ShareAudience.Private -> AudienceChrome(Icons.Outlined.Lock, stringResource(R.string.bt_social_audience_private), BtBadgeKind.Neutral)
    ShareAudience.SpecificFriends -> AudienceChrome(
        Icons.Outlined.People,
        if (friendCount > 0) pluralStringResource(R.plurals.bt_social_audience_friend_count, friendCount, friendCount) else stringResource(R.string.bt_social_audience_some_friends),
        BtBadgeKind.Gold,
    )
    // The row has no group id (the my-shared list carries only the audience), so
    // the badge names the rung; the sheet is where the group itself is named.
    ShareAudience.Group -> AudienceChrome(Icons.Outlined.Groups, stringResource(R.string.bt_groups_audience_badge), BtBadgeKind.Gold)
    ShareAudience.AllFriends -> AudienceChrome(Icons.Outlined.Group, stringResource(R.string.bt_social_audience_all_friends), BtBadgeKind.Gold)
    ShareAudience.PublicLink -> AudienceChrome(Icons.Outlined.Link, stringResource(R.string.bt_social_audience_public), BtBadgeKind.Gold)
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
        title = { Text(stringResource(R.string.bt_social_link_created_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.bt_social_link_created_body),
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
            TextButton(onClick = { onCopy() }) { Text(stringResource(R.string.bt_social_link_copy), color = bt.gold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onShare() }) { Text(stringResource(R.string.bt_social_link_share), color = bt.textSecondary) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_action_done), color = bt.textSecondary) }
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
        title = { Text(stringResource(R.string.bt_social_add_friend)) },
        text = {
            Column {
                Text(stringResource(R.string.bt_social_add_friend_body), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bt_social_add_friend_placeholder), color = bt.textMuted) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSend(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.bt_social_send_request), color = if (text.isNotBlank()) bt.gold else bt.textMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) } },
    )
}
