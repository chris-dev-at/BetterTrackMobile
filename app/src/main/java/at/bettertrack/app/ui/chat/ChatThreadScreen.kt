package at.bettertrack.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.dto.CHAT_MESSAGE_MAX
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.ShareChip
import at.bettertrack.app.data.repo.ShareChipKind
import at.bettertrack.app.data.repo.ThreadAvailability
import at.bettertrack.app.data.repo.ThreadState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class ChatThreadViewModel(
    private val chat: ChatRepository,
    convId: String?,
    private val friendUserId: String?,
    val friendUsername: String,
) : ViewModel() {

    private var conversationId: String? = convId

    private val _state = MutableStateFlow(ThreadState(loading = true, friendUsername = friendUsername))
    val state: StateFlow<ThreadState> = _state

    private val _attachables = MutableStateFlow<List<ShareChip>>(emptyList())
    val attachables: StateFlow<List<ShareChip>> = _attachables

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private var loadJob: Job? = null

    init {
        chat.connectRealtime()
        startLoad()
        viewModelScope.launch {
            (chat.attachables() as? BtResult.Ok)?.let { _attachables.value = it.value }
        }
    }

    private fun startLoad() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val id = resolveConversationId() ?: return@launch
            conversationId = id
            chat.setActiveConversation(id)
            launch { chat.loadThread(id) }
            chat.markRead(id)
            var lastSeen: String? = null
            chat.thread(id).collect { st ->
                _state.value = st.copy(friendUsername = st.friendUsername.ifEmpty { friendUsername })
                val newest = st.messages.lastOrNull()
                if (newest != null && newest.id != lastSeen) {
                    val firstBatch = lastSeen == null
                    lastSeen = newest.id
                    // A new incoming message while the thread is open stays "read".
                    if (!firstBatch && !newest.fromMe) chat.markRead(id)
                }
            }
        }
    }

    /** Re-run the resolve+load after a failure (drives the full-screen error state's Retry). */
    fun retry() {
        _state.value = _state.value.copy(loading = true, error = null)
        startLoad()
    }

    private suspend fun resolveConversationId(): String? {
        conversationId?.let { return it }
        val fid = friendUserId ?: run {
            _state.value = _state.value.copy(loading = false, availability = ThreadAvailability.NotAvailable)
            return null
        }
        return when (val r = chat.openConversationWith(fid, friendUsername)) {
            is BtResult.Ok -> r.value
            is BtResult.Err -> {
                _state.value = _state.value.copy(
                    loading = false,
                    availability = if (r.error.httpStatus == 404) ThreadAvailability.NotAvailable else ThreadAvailability.Available,
                    error = if (r.error.httpStatus == 404) null else r.error.userMessage,
                )
                null
            }
        }
    }

    fun send(text: String) {
        val id = conversationId ?: return
        val body = text.trim().take(CHAT_MESSAGE_MAX)
        if (body.isBlank()) return
        viewModelScope.launch {
            (chat.send(id, body, null) as? BtResult.Err)?.let { _toast.value = it.error.userMessage }
        }
    }

    fun sendChip(chip: ShareChip) {
        val id = conversationId ?: return
        viewModelScope.launch {
            (chat.send(id, null, chip) as? BtResult.Err)?.let { _toast.value = it.error.userMessage }
        }
    }

    fun loadOlder() {
        val id = conversationId ?: return
        viewModelScope.launch { chat.loadOlder(id) }
    }

    fun consumeToast() { _toast.value = null }

    override fun onCleared() {
        chat.setActiveConversation(null)
        chat.disconnectRealtime()
        super.onCleared()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    conversationId: String?,
    friendUserId: String?,
    friendUsername: String,
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    onOpenSharedPortfolio: (portfolioId: String) -> Unit,
    onOpenSharedWatchlist: (watchlistId: String, ownerName: String) -> Unit,
    onOpenSharedConglomerate: (conglomerateId: String) -> Unit,
) {
    val vm: ChatThreadViewModel = viewModel {
        ChatThreadViewModel(AppGraph.chatRepository, conversationId, friendUserId, friendUsername)
    }
    val bt = BtTheme.colors
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val attachables by vm.attachables.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val headerName = state.friendUsername.ifEmpty { friendUsername }

    LaunchedEffect(toast) {
        toast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    // Auto-scroll to the newest message only when a NEW message lands at the bottom
    // (not when older history is prepended by pagination).
    var lastNewestId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        val newestId = state.messages.lastOrNull()?.id
        if (newestId != null && newestId != lastNewestId) {
            lastNewestId = newestId
            if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Page older history when the user scrolls near the top.
    val current = rememberUpdatedState(state)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx ->
                val s = current.value
                if (idx <= 2 && s.hasMore && !s.loadingOlder && s.messages.isNotEmpty()) vm.loadOlder()
            }
    }

    val openChip: (ShareChip) -> Unit = { chip ->
        when (chip.kind) {
            ShareChipKind.Asset -> onOpenAsset(chip.refId)
            ShareChipKind.Portfolio -> onOpenSharedPortfolio(chip.refId)
            ShareChipKind.Watchlist -> onOpenSharedWatchlist(chip.refId, chip.ownerName ?: headerName)
            ShareChipKind.Conglomerate -> onOpenSharedConglomerate(chip.refId)
        }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtAvatar(name = headerName, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("@$headerName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                },
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
        bottomBar = {
            when {
                // While the full-screen load error is up, a live composer would invite
                // sends into a thread we couldn't even load — hide it until Retry works.
                state.error != null && state.messages.isEmpty() -> Unit
                state.availability == ThreadAvailability.Available -> MessageInputBar(
                    value = input,
                    onValueChange = { input = it.take(CHAT_MESSAGE_MAX) },
                    onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
                    onAttach = { showAttach = true },
                )
                state.availability == ThreadAvailability.ReadOnly -> ClosedThreadNotice(
                    "You can't send messages in this chat anymore.",
                )
                else -> Unit
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                state.loading && state.messages.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = bt.gold) }

                state.availability == ThreadAvailability.NotAvailable -> BtEmptyState(
                    icon = Icons.Outlined.Lock,
                    title = "Conversation not available",
                    message = "This chat isn't available. You may no longer be able to message this person.",
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )

                // A failed load with nothing cached is an ERROR, not an empty thread —
                // never render "Say hi" over a thread that may well have history.
                state.error != null && state.messages.isEmpty() -> BtErrorState(
                    message = state.error,
                    onRetry = vm::retry,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )

                state.messages.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    BtAvatar(name = headerName, size = 64.dp)
                    Spacer(Modifier.size(16.dp))
                    Text(
                        "Say hi to @$headerName",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Start the conversation — you can share an asset or portfolio right here with the paperclip.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textMuted,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.loadingOlder) {
                        item(key = "older-spinner") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = bt.gold, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    items(state.messages, key = { it.id }) { m -> MessageBubble(m, onOpenChip = { openChip(m.chip!!) }) }
                }
            }
        }
    }

    if (showAttach) {
        AttachSheet(
            items = attachables,
            onPick = { chip -> showAttach = false; vm.sendChip(chip) },
            onDismiss = { showAttach = false },
        )
    }
}

@Composable
private fun ClosedThreadNotice(text: String) {
    val bt = BtTheme.colors
    Surface(color = bt.bg) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = bt.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
        }
    }
}

@Composable
private fun MessageBubble(m: at.bettertrack.app.data.repo.ChatMessage, onOpenChip: () -> Unit) {
    val bt = BtTheme.colors
    val bubbleShape = if (m.fromMe) {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 3.dp)
    } else {
        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 3.dp, bottomEnd = 12.dp)
    }
    val container = if (m.fromMe) bt.gold.copy(alpha = 0.14f) else bt.surface
    val border = if (m.fromMe) bt.gold.copy(alpha = 0.40f) else bt.border
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = bubbleShape,
            color = container,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.widthIn(max = 288.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                m.chip?.let { chip ->
                    ShareChipView(chip, onClick = onOpenChip)
                    if (!m.body.isNullOrBlank()) Spacer(Modifier.size(6.dp))
                }
                if (!m.body.isNullOrBlank()) {
                    Text(m.body, style = MaterialTheme.typography.bodyLarge, color = bt.textPrimary)
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    relativeTime(m.sentAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun ShareChipView(chip: ShareChip, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val icon = chip.kind.icon()
    if (chip.viewable) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = bt.bg,
            border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = bt.goldEmphasis, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(chip.symbol ?: chip.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary, maxLines = 1)
                    Text(chip.subtitle(), style = MaterialTheme.typography.labelSmall, color = bt.textMuted, maxLines = 1)
                }
                Text("View", style = MaterialTheme.typography.labelMedium, color = bt.goldEmphasis, fontWeight = FontWeight.SemiBold)
            }
        }
    } else {
        // "Not shared with you" — never leaks data.
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = bt.bg,
            border = BorderStroke(1.dp, bt.border),
            modifier = Modifier.widthIn(min = 200.dp),
        ) {
            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(chip.kindLabel(), style = MaterialTheme.typography.titleSmall, color = bt.textSecondary, maxLines = 1)
                    Text("Not shared with you", style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                }
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(color = bt.bg) {
        // Pad the composer content above the keyboard AND the system navigation bar
        // (edge-to-edge draws behind both) — union takes the larger of the two, so
        // the input + send button are never hidden under a 3-button nav bar.
        // This is the SINGLE source of bottom-inset compensation: the manifest
        // declares windowSoftInputMode="adjustResize" so the window dispatches the
        // IME inset (edge-to-edge = no pan/resize) rather than panning up — without
        // that, the pan would stack with this padding and float the bar a whole
        // keyboard-height above the keyboard. Keep the two in lockstep.
        Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = bt.border)
            // Show a character counter only as the 4000-char limit approaches.
            if (value.length > CHAT_MESSAGE_MAX - 400) {
                Text(
                    "${value.length} / $CHAT_MESSAGE_MAX",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (value.length >= CHAT_MESSAGE_MAX) bt.loss else bt.textMuted,
                    modifier = Modifier.align(Alignment.End).padding(end = 16.dp, top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Share an item", tint = bt.textSecondary)
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message", color = bt.textMuted) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = bt.surface,
                        unfocusedContainerColor = bt.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedTextColor = bt.textPrimary,
                        unfocusedTextColor = bt.textPrimary,
                        cursorColor = bt.gold,
                    ),
                    shape = RoundedCornerShape(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                val canSend = value.isNotBlank()
                Surface(
                    onClick = onSend,
                    enabled = canSend,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (canSend) bt.gold else bt.border,
                    contentColor = if (canSend) bt.onGold else bt.textMuted,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(items: List<ShareChip>, onPick: (ShareChip) -> Unit, onDismiss: () -> Unit) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = bt.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Share in chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
            Text("Sending never widens access — friends only see it if it's already shared with them.", style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            Spacer(Modifier.size(12.dp))
            if (items.isEmpty()) {
                Text(
                    "You don't have any portfolios, watchlists or baskets to share yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textMuted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                items.forEach { chip ->
                    Surface(
                        onClick = { onPick(chip) },
                        shape = at.bettertrack.app.ui.theme.BtShapes.card,
                        color = bt.bg,
                        border = BorderStroke(1.dp, bt.border),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(chip.kind.icon(), contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chip.symbol ?: chip.label, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                                Text(chip.subtitle(), style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ShareChipKind.icon(): ImageVector = when (this) {
    ShareChipKind.Asset -> Icons.AutoMirrored.Outlined.ShowChart
    ShareChipKind.Portfolio -> Icons.Outlined.PieChart
    ShareChipKind.Watchlist -> Icons.AutoMirrored.Outlined.ShowChart
    ShareChipKind.Conglomerate -> Icons.Outlined.Dashboard
}

private fun ShareChip.kindLabel(): String = when (kind) {
    ShareChipKind.Asset -> "Asset"
    ShareChipKind.Portfolio -> "Portfolio"
    ShareChipKind.Watchlist -> "Watchlist"
    ShareChipKind.Conglomerate -> "Conglomerate"
}

private fun ShareChip.subtitle(): String {
    val k = kindLabel()
    return ownerName?.takeIf { it.isNotBlank() }?.let { "$k · $it" } ?: k
}
