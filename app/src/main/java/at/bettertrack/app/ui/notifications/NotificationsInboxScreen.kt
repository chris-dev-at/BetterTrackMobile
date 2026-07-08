package at.bettertrack.app.ui.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.AppNotification
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.NotifKind
import at.bettertrack.app.data.notifications.resolveDeepLink
import at.bettertrack.app.data.push.BtMessagingService
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtUnreadDot
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

private enum class InboxPhase { Loading, Loaded, Error }

/**
 * In-app notification inbox (Step 16, §6.11): the bell's destination. Unread
 * badge + mark-read (single via tap, all via the top-bar action), deep-link
 * tap-through to the relevant screen, and empty/error/skeleton states. Reads are
 * live when the notifications scope is granted, else a debug sample inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsInboxScreen(
    onBack: () -> Unit,
    onDeepLink: (NotifDeepLink) -> Unit,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val repo = AppGraph.notificationRepository
    val scope = rememberCoroutineScope()

    val notifications by repo.items.collectAsStateWithLifecycle()
    val unread by repo.unreadCount.collectAsStateWithLifecycle()

    var phase by remember { mutableStateOf(InboxPhase.Loading) }

    LaunchedEffect(Unit) {
        val r = repo.refresh()
        phase = if (r is at.bettertrack.app.data.api.BtResult.Err && notifications.isEmpty()) {
            InboxPhase.Error
        } else {
            InboxPhase.Loaded
        }
    }

    // Android 13+ POST_NOTIFICATIONS — asked IN CONTEXT here (never cold-launch).
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var permissionGranted by remember {
        mutableStateOf(
            !needsPermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    var simulateIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_notifications), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                actions = {
                    if (unread > 0) {
                        IconButton(onClick = { scope.launch { repo.markAllRead() } }) {
                            Icon(
                                Icons.Outlined.DoneAll,
                                contentDescription = stringResource(R.string.bt_notif_mark_all_read),
                                tint = bt.gold,
                            )
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        IconButton(onClick = {
                            BtMessagingService.debugSimulate(context, simulateIndex)
                            simulateIndex++
                        }) {
                            Icon(Icons.Outlined.Science, contentDescription = "Simulate a notification", tint = bt.textMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (phase) {
                InboxPhase.Loading -> InboxSkeleton()
                InboxPhase.Error -> BtErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    onRetry = {
                        phase = InboxPhase.Loading
                        scope.launch {
                            val r = repo.refresh()
                            phase = if (r is at.bettertrack.app.data.api.BtResult.Err) InboxPhase.Error else InboxPhase.Loaded
                        }
                    },
                )
                InboxPhase.Loaded -> {
                    if (notifications.isEmpty()) {
                        BtEmptyState(
                            modifier = Modifier.align(Alignment.Center),
                            icon = Icons.Outlined.NotificationsNone,
                            title = stringResource(R.string.bt_notif_empty_title),
                            message = stringResource(R.string.bt_notif_empty_message),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (needsPermission && !permissionGranted) {
                                item {
                                    EnablePushPrompt(onEnable = {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    })
                                }
                            }
                            items(notifications, key = { it.id }) { n ->
                                NotificationRow(
                                    notification = n,
                                    onClick = {
                                        scope.launch { repo.markRead(listOf(n.id)) }
                                        resolveDeepLink(n.type, n.payload)?.let(onDeepLink)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnablePushPrompt(onEnable: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        onClick = onEnable,
        color = bt.gold.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, bt.gold.copy(alpha = 0.35f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = bt.gold, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bt_notif_enable_push_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(stringResource(R.string.bt_notif_enable_push_message), style = MaterialTheme.typography.bodySmall, color = bt.textSecondary)
            }
            Text(stringResource(R.string.bt_notif_enable_push_action), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = bt.gold)
        }
    }
}

@Composable
private fun NotificationRow(notification: AppNotification, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val unreadTint = if (notification.isUnread) bt.gold.copy(alpha = 0.06f) else bt.surface
    Surface(
        onClick = onClick,
        color = unreadTint,
        border = BorderStroke(1.dp, if (notification.isUnread) bt.gold.copy(alpha = 0.22f) else bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            // Icon-in-badge (matches the empty/error state language).
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(bt.gold.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(notifIcon(notification.kind), contentDescription = null, tint = bt.gold, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (notification.isUnread) FontWeight.SemiBold else FontWeight.Medium,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(inboxRelativeTime(notification.createdAtMs), style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
                    if (notification.isUnread) {
                        Spacer(Modifier.width(8.dp))
                        BtUnreadDot()
                    }
                }
                Spacer(Modifier.size(3.dp))
                Text(notification.body, style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
            }
        }
    }
}

@Composable
private fun InboxSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(5) {
            BtSkeleton(modifier = Modifier.fillMaxWidth().height(74.dp), shape = BtShapes.card)
        }
    }
}

private fun notifIcon(kind: NotifKind): ImageVector = when (kind) {
    NotifKind.FriendRequest -> Icons.Outlined.PersonAdd
    NotifKind.FriendAccepted -> Icons.Outlined.People
    NotifKind.PortfolioShared -> Icons.Outlined.Share
    NotifKind.AlertTriggered -> Icons.Outlined.NotificationsActive
    NotifKind.ChatMessage -> Icons.AutoMirrored.Outlined.Chat
    NotifKind.AccountInvite -> Icons.Outlined.MailOutline
    NotifKind.AccountTempPassword -> Icons.Outlined.Key
    NotifKind.System -> Icons.Outlined.Info
}

private fun inboxRelativeTime(ms: Long): String {
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
