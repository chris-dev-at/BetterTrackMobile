package at.bettertrack.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import at.bettertrack.app.data.api.BtResult
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
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        chat.connectRealtime()
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _error.value = (chat.refreshConversations() as? BtResult.Err)?.error?.userMessage
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
    var showPicker by remember { mutableStateOf(false) }

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
            FloatingActionButton(
                onClick = { showPicker = true },
                containerColor = bt.gold,
                contentColor = bt.onGold,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            ) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.bt_chat_new_message_cd)) }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                // A failed refresh with nothing cached is an ERROR, not an empty inbox.
                error != null && conversations.isEmpty() -> BtErrorState(
                    message = error,
                    onRetry = vm::refresh,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                conversations.isEmpty() -> BtEmptyState(
                    icon = Icons.AutoMirrored.Outlined.Chat,
                    title = stringResource(R.string.bt_chat_empty_title),
                    message = stringResource(R.string.bt_chat_empty_body),
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                else -> LazyColumn(
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
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BtAvatar(name = c.friendUsername, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "@${c.friendUsername}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = bt.textPrimary,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.bt_chat_new_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
            Text(stringResource(R.string.bt_chat_new_subtitle), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
            Spacer(Modifier.size(12.dp))
            if (friends.isEmpty()) {
                Text(stringResource(R.string.bt_chat_new_no_friends), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(friends, key = { it.userId }) { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BtAvatar(name = f.username, size = 38.dp)
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
