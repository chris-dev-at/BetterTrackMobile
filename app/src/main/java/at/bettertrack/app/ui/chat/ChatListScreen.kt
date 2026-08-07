package at.bettertrack.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.Conversation
import at.bettertrack.app.data.repo.Friend
import at.bettertrack.app.data.repo.SocialRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtListSurface
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.fabVisibleForList
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.resolveListSurface
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatListViewModel(
    private val chat: ChatRepository,
    private val social: SocialRepository,
) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = chat.conversations
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends

    /** Last refresh failure (user message) — an errored empty list is NOT "no messages yet". */
    private val _error = MutableStateFlow<BtMessage?>(null)
    val error: StateFlow<BtMessage?> = _error

    /**
     * True until the first conversation refresh has answered, either way.
     *
     * The screen already told the truth about a *failed* first fetch (that is
     * what [error] is for) but had no third state, so in the window before the
     * first response lands an empty list fell straight through to "no messages
     * yet" — the app telling a user with a full inbox that they have none.
     *
     * The flag is one-way on purpose: it goes false when the first refresh
     * settles and never goes back. Later refreshes happen over conversations
     * that are already on screen, and replacing real content with a skeleton
     * because a background poll is in flight would be a worse lie than the one
     * this fixes.
     */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    init {
        chat.connectRealtime()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _error.value = (chat.refreshConversations() as? BtResult.Err)?.error?.asMessage()
            _loading.value = false
        }
        viewModelScope.launch {
            (social.friends() as? BtResult.Ok)?.let { _friends.value = it.value }
        }
    }

    override fun onCleared() {
        chat.disconnectRealtime()
        super.onCleared()
    }
}

/**
 * ## R2 bar decision: compact bar kept here too. (Spec §2.4)
 *
 * The thread screen's exclusion is forced by its composer; this one is a
 * judgement call, and it comes down to what a collapsing header would be for.
 * A large title earns its space by making the screen's subject unmissable on
 * arrival and then getting out of the way — which requires content long enough
 * to scroll. A conversation list is a handful of rows for almost every user, and
 * it carries a compose FAB the list already insets for. So the header would
 * expand to 112dp, have nothing to collapse against, and permanently cost a
 * fifth of the screen to restate a word ("Chats") that the row the user just
 * tapped already said.
 *
 * Consistency argues the other way, and it is a real cost — this and the thread
 * are the only two pushed screens in the app that scroll and do not collapse.
 * They are also the only two the user reaches as a PAIR, so the inconsistency is
 * at least internally coherent: chat looks like chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onBack: () -> Unit,
    onOpenConversation: (conversationId: String, username: String) -> Unit,
    onStartWithFriend: (friendUserId: String, username: String) -> Unit,
) {
    val vm: ChatListViewModel = viewModel {
        ChatListViewModel(AppGraph.chatRepository, AppGraph.socialRepository)
    }
    val bt = BtTheme.colors
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val friends by vm.friends.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    // Held in a plain local so the null check narrows the type for the
    // BtErrorState call — a `by` delegate never smart-casts.
    val failure = error
    // The shared resolver rather than this screen's own `when`: the "failed first
    // fetch reads as an empty inbox" bug is the one every list screen wrote
    // independently, so the decision lives in one tested place
    // (BtListSurfaceTest) and this screen only renders it. Hoisted above the
    // Scaffold because the FAB obeys it too — see [fabVisibleForList].
    val surface = resolveListSurface(
        hasContent = conversations.isNotEmpty(),
        firstLoadPending = loading,
        failed = failure != null,
    )

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_chat_title), style = MaterialTheme.typography.titleLarge) },
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
        floatingActionButton = {
            // The app-wide empty-state rule: with no conversations the empty
            // state carries the single "New message" CTA and this stands down.
            if (fabVisibleForList(surface)) {
                FloatingActionButton(
                    onClick = { showPicker = true },
                    containerColor = bt.gold,
                    contentColor = bt.onGold,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                ) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.bt_chat_new_message_cd)) }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (surface) {
                BtListSurface.SKELETON -> ChatListSkeleton()
                // OFFLINE cannot occur here: this VM tracks no connectivity flag,
                // so the resolver is called with the default isOnline = true. It
                // shares the ERROR branch rather than being dropped, so adding a
                // connectivity flag later cannot silently lose the case.
                BtListSurface.ERROR, BtListSurface.OFFLINE -> BtErrorState(
                    message = failure ?: BtMessage(R.string.bt_error_generic_message),
                    onRetry = vm::refresh,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                // The FAB is gone on this surface, so the CTA lives here.
                BtListSurface.EMPTY -> BtEmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = stringResource(R.string.bt_chat_empty_title),
                    message = stringResource(R.string.bt_chat_empty_body),
                    action = {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_chat_new_title),
                            onClick = { showPicker = true },
                        )
                    },
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                BtListSurface.CONTENT -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = { it.id }) { c ->
                        ConversationRow(c, onClick = { onOpenConversation(c.id, c.friendUsername) })
                    }
                }
            }
        }
    }

    if (showPicker) {
        FriendPickerSheet(
            friends = friends,
            onPick = { f -> showPicker = false; onStartWithFriend(f.userId, f.username) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    val bt = BtTheme.colors
    // A blank username means the other participant deleted their account (#362).
    // It gets a translated LABEL, not a handle: no "@", no primary-text weight —
    // "@deleted" read like a username you could look up, and in German it read
    // like an English one.
    val deleted = c.friendUsername.isBlank()
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = c.friendUsername, iconId = c.friendProfileIcon, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (deleted) stringResource(R.string.bt_chat_deleted_user) else "@${c.friendUsername}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (deleted) bt.textSecondary else bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(relativeTime(c.lastAtMs), style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                }
                Spacer(Modifier.size(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.lastPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.unread > 0) bt.textSecondary else bt.textMuted,
                        fontWeight = if (c.unread > 0) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (c.unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = BtShapes.pill, color = bt.gold) {
                            Text(
                                c.unread.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = bt.onGold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendPickerSheet(friends: List<Friend>, onPick: (Friend) -> Unit, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = bt.surface) {
        // A ModalBottomSheet ships no content insets — the 24dp is a content
        // margin, not nav-bar clearance, so the last friend row would sit behind
        // a 3-button nav bar without navigationBarsPadding().
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.bt_chat_new_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
            Text(stringResource(R.string.bt_chat_new_subtitle), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
            Spacer(Modifier.size(12.dp))
            if (friends.isEmpty()) {
                BtInlineEmpty(
                    text = stringResource(R.string.bt_chat_new_no_friends),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(friends, key = { it.userId }) { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BtAvatar(name = f.username, iconId = f.profileIcon, size = 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("@${f.username}", style = MaterialTheme.typography.bodyLarge, color = bt.textPrimary, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { onPick(f) }) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(R.string.bt_chat_open_cd), tint = bt.gold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Placeholder rows for the window before the first conversation refresh answers.
 *
 * Five blocks at the conversation row's own height, so the list does not jump
 * when the real rows replace them. `BtSkeleton` skips its shimmer under reduced
 * motion by itself (§3.7) — nothing here has to remember that.
 */
@Composable
private fun ChatListSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(5) {
            BtSkeleton(modifier = Modifier.fillMaxWidth().height(68.dp), shape = BtShapes.card)
        }
    }
}

internal fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val min = diff / 60_000
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        min < 60 * 24 * 7 -> "${min / (60 * 24)}d"
        else -> "${min / (60 * 24 * 7)}w"
    }
}
