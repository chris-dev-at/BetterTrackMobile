package at.bettertrack.app.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PieChart
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.ChatMessage
import at.bettertrack.app.data.repo.ChatRepository
import at.bettertrack.app.data.repo.ShareChip
import at.bettertrack.app.data.repo.ShareChipKind
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatThreadViewModel(
    private val chat: ChatRepository,
    private val convId: String?,
    private val friendUserId: String?,
    val friendUsername: String,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private var conversationId: String? = convId

    init {
        viewModelScope.launch {
            if (conversationId == null && friendUserId != null) {
                conversationId = (chat.conversationWith(friendUserId, friendUsername) as? BtResult.Ok)?.value
            }
            conversationId?.let { id ->
                chat.markRead(id)
                chat.messages(id).collect { msgs ->
                    _messages.value = msgs
                    chat.markRead(id) // keep the open thread read as new ones arrive
                }
            }
        }
    }

    fun send(body: String) {
        val id = conversationId ?: return
        viewModelScope.launch { chat.send(id, body, null) }
    }

    fun sendChip(chip: ShareChip) {
        val id = conversationId ?: return
        viewModelScope.launch { chat.send(id, null, chip) }
    }

    fun attachables(): List<ShareChip> = chat.attachables()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(
    conversationId: String?,
    friendUserId: String?,
    friendUsername: String,
    onBack: () -> Unit,
) {
    val vm: ChatThreadViewModel = viewModel {
        ChatThreadViewModel(AppGraph.chatRepository, conversationId, friendUserId, friendUsername)
    }
    val bt = BtTheme.colors
    val context = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BtAvatar(name = friendUsername, size = 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("@$friendUsername", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
            MessageInputBar(
                value = input,
                onValueChange = { input = it },
                onSend = { if (input.isNotBlank()) { vm.send(input); input = "" } },
                onAttach = { showAttach = true },
            )
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.id }) { m -> MessageBubble(m, onOpenChip = {
                Toast.makeText(context, "Opens the shared item when chat goes live.", Toast.LENGTH_SHORT).show()
            }) }
        }
    }

    if (showAttach) {
        AttachSheet(
            items = vm.attachables(),
            onPick = { chip -> showAttach = false; vm.sendChip(chip) },
            onDismiss = { showAttach = false },
        )
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onOpenChip: () -> Unit) {
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
        Column {
            androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = bt.border)
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
    return ownerName?.let { "$k · $it" } ?: k
}
