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
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.notifications.AppNotification
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.notifications.NotifKind
import at.bettertrack.app.data.notifications.NotifView
import at.bettertrack.app.data.notifications.NotificationFlags
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

/** The two irreversible bulk actions, each behind a destructive confirm dialog. */
private enum class BulkConfirm { DeleteArchived, DeleteAll }

/**
 * In-app notification inbox (Step 16, §6.11; archive/delete on Notifications-v3
 * #437): the bell's destination. Unread badge + mark-read, deep-link tap-through,
 * and — behind [NotificationFlags.archiveDeleteEnabled] — an Active|Archived|All
 * filter, per-item archive/unarchive/delete via a row overflow menu (archive shows
 * an Undo snackbar), and bulk archive-all-read / delete-all-archived / delete-all
 * (the two deletes behind destructive confirm dialogs). When the flag is off the
 * screen is the pre-#437 flat inbox (mark-read only).
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
    val actionsEnabled = NotificationFlags.archiveDeleteEnabled

    val notifications by repo.items.collectAsStateWithLifecycle()
    val unread by repo.unreadCount.collectAsStateWithLifecycle()

    var selectedView by remember { mutableStateOf(NotifView.Active) }
    var phase by remember { mutableStateOf(InboxPhase.Loading) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog + menu state.
    var deleteTarget by remember { mutableStateOf<AppNotification?>(null) }
    var bulkConfirm by remember { mutableStateOf<BulkConfirm?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }

    // (Re)load whenever the selected view changes; also runs on first composition.
    LaunchedEffect(selectedView) {
        phase = InboxPhase.Loading
        val r = repo.refresh(selectedView)
        phase = if (r is BtResult.Err && notifications.isEmpty()) InboxPhase.Error else InboxPhase.Loaded
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

    // Snackbar copy resolved in composition (can't call stringResource in a lambda).
    val archivedMsg = stringResource(R.string.bt_notif_snack_archived)
    val archivedReadMsg = stringResource(R.string.bt_notif_snack_archived_read)
    val undoLabel = stringResource(R.string.bt_notif_undo)

    fun onArchive(n: AppNotification) {
        scope.launch {
            when (val r = repo.archive(n.id)) {
                is BtResult.Ok -> {
                    val res = snackbarHostState.showSnackbar(
                        message = archivedMsg,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (res == SnackbarResult.ActionPerformed) repo.unarchive(n.id, restore = n)
                }
                is BtResult.Err -> snackbarHostState.showSnackbar(r.error.userMessage)
            }
        }
    }

    fun onUnarchive(n: AppNotification) {
        scope.launch {
            val r = repo.unarchive(n.id)
            if (r is BtResult.Err) snackbarHostState.showSnackbar(r.error.userMessage)
        }
    }

    // Counts for the bulk menu + destructive dialogs (from the current view's list).
    val archivedCount = notifications.count { it.isArchived }
    val readActiveCount = notifications.count { !it.isUnread && !it.isArchived }
    val totalCount = notifications.size

    Scaffold(
        containerColor = bt.bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    if (actionsEnabled) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.bt_notif_more_actions),
                                    tint = bt.textSecondary,
                                )
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bt_notif_bulk_archive_read)) },
                                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                                    enabled = readActiveCount > 0,
                                    onClick = {
                                        overflowOpen = false
                                        scope.launch {
                                            val r = repo.archiveAllRead()
                                            snackbarHostState.showSnackbar(if (r is BtResult.Err) r.error.userMessage else archivedReadMsg)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bt_notif_bulk_delete_archived), color = bt.loss) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = bt.loss) },
                                    enabled = archivedCount > 0,
                                    onClick = { overflowOpen = false; bulkConfirm = BulkConfirm.DeleteArchived },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bt_notif_bulk_delete_all), color = bt.loss) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = bt.loss) },
                                    enabled = totalCount > 0,
                                    onClick = { overflowOpen = false; bulkConfirm = BulkConfirm.DeleteAll },
                                )
                            }
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
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (actionsEnabled) {
                InboxFilter(selected = selectedView, onSelect = { selectedView = it })
            }
            Box(Modifier.fillMaxSize()) {
                when (phase) {
                    InboxPhase.Loading -> InboxSkeleton()
                    InboxPhase.Error -> BtErrorState(
                        modifier = Modifier.align(Alignment.Center),
                        onRetry = {
                            phase = InboxPhase.Loading
                            scope.launch {
                                val r = repo.refresh(selectedView)
                                phase = if (r is BtResult.Err) InboxPhase.Error else InboxPhase.Loaded
                            }
                        },
                    )
                    InboxPhase.Loaded -> {
                        if (notifications.isEmpty()) {
                            EmptyForView(selectedView, actionsEnabled, Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
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
                                        showActions = actionsEnabled,
                                        onClick = {
                                            scope.launch { repo.markRead(listOf(n.id)) }
                                            resolveDeepLink(n.type, n.payload)?.let(onDeepLink)
                                        },
                                        onArchive = { onArchive(n) },
                                        onUnarchive = { onUnarchive(n) },
                                        onDelete = { deleteTarget = n },
                                        onMarkRead = { scope.launch { repo.markRead(listOf(n.id)) } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-item delete confirm (hard server delete → small destructive dialog).
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_notif_delete_title)) },
            text = { Text(stringResource(R.string.bt_notif_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val r = repo.delete(target.id)
                        if (r is BtResult.Err) snackbarHostState.showSnackbar(r.error.userMessage)
                    }
                }) { Text(stringResource(R.string.bt_notif_action_delete), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
            },
        )
    }

    // Bulk destructive confirm dialogs (clear reds + counts).
    bulkConfirm?.let { which ->
        val count = if (which == BulkConfirm.DeleteArchived) archivedCount else totalCount
        AlertDialog(
            onDismissRequest = { bulkConfirm = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = {
                Text(
                    stringResource(
                        if (which == BulkConfirm.DeleteArchived) R.string.bt_notif_delete_archived_title
                        else R.string.bt_notif_delete_all_title,
                    ),
                )
            },
            text = {
                Text(
                    pluralStringResource(
                        if (which == BulkConfirm.DeleteArchived) R.plurals.bt_notif_delete_archived_body
                        else R.plurals.bt_notif_delete_all_body,
                        count, count,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = which
                    bulkConfirm = null
                    scope.launch {
                        val r = when (chosen) {
                            BulkConfirm.DeleteArchived -> repo.deleteAllArchived()
                            BulkConfirm.DeleteAll -> repo.deleteAll()
                        }
                        if (r is BtResult.Err) snackbarHostState.showSnackbar(r.error.userMessage)
                    }
                }) { Text(stringResource(R.string.bt_notif_action_delete), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { bulkConfirm = null }) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
            },
        )
    }
}

// ── Filter (Active | Archived | All) ─────────────────────────────────────────

@Composable
private fun InboxFilter(selected: NotifView, onSelect: (NotifView) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterSegment(stringResource(R.string.bt_notif_filter_active), selected == NotifView.Active, Modifier.weight(1f)) { onSelect(NotifView.Active) }
        FilterSegment(stringResource(R.string.bt_notif_filter_archived), selected == NotifView.Archived, Modifier.weight(1f)) { onSelect(NotifView.Archived) }
        FilterSegment(stringResource(R.string.bt_notif_filter_all), selected == NotifView.All, Modifier.weight(1f)) { onSelect(NotifView.All) }
    }
}

@Composable
private fun FilterSegment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
        }
    }
}

@Composable
private fun EmptyForView(view: NotifView, actionsEnabled: Boolean, modifier: Modifier) {
    val archived = actionsEnabled && view == NotifView.Archived
    BtEmptyState(
        modifier = modifier,
        icon = if (archived) Icons.Outlined.Archive else Icons.Outlined.NotificationsNone,
        title = stringResource(if (archived) R.string.bt_notif_empty_archived_title else R.string.bt_notif_empty_title),
        message = stringResource(if (archived) R.string.bt_notif_empty_archived_message else R.string.bt_notif_empty_message),
    )
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
private fun NotificationRow(
    notification: AppNotification,
    showActions: Boolean,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val bt = BtTheme.colors
    val unreadTint = if (notification.isUnread) bt.gold.copy(alpha = 0.06f) else bt.surface
    Surface(
        onClick = onClick,
        color = unreadTint,
        border = BorderStroke(1.dp, if (notification.isUnread) bt.gold.copy(alpha = 0.22f) else bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
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
                        notification.title.ifBlank { stringResource(notifKindTitleRes(notification.kind)) },
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
                Text(
                    notification.body.ifBlank { stringResource(notifKindBodyRes(notification.kind)) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
                if (showActions && notification.isArchived) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.bt_notif_archived_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                }
            }
            if (showActions) {
                RowOverflow(
                    notification = notification,
                    onArchive = onArchive,
                    onUnarchive = onUnarchive,
                    onDelete = onDelete,
                    onMarkRead = onMarkRead,
                )
            }
        }
    }
}

@Composable
private fun RowOverflow(
    notification: AppNotification,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onMarkRead: () -> Unit,
) {
    val bt = BtTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.bt_notif_more_actions), tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (notification.isUnread && !notification.isArchived) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_notif_action_mark_read)) },
                    leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                    onClick = { open = false; onMarkRead() },
                )
            }
            if (notification.isArchived) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_notif_action_unarchive)) },
                    leadingIcon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
                    onClick = { open = false; onUnarchive() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_notif_action_archive)) },
                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    onClick = { open = false; onArchive() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bt_notif_action_delete), color = bt.loss) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = bt.loss) },
                onClick = { open = false; onDelete() },
            )
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
    // Task C: distinct icons matching the app's iconography for each entity.
    NotifKind.FriendActivity -> Icons.AutoMirrored.Outlined.TrendingUp
    NotifKind.WatchlistShared -> Icons.AutoMirrored.Outlined.ShowChart
    NotifKind.ConglomerateShared -> Icons.Outlined.Dashboard
    NotifKind.System -> Icons.Outlined.Info
}

/** Localized fallback title used when the server row has a blank title. */
private fun notifKindTitleRes(kind: NotifKind): Int = when (kind) {
    NotifKind.FriendRequest -> R.string.bt_notif_type_friend_request
    NotifKind.FriendAccepted -> R.string.bt_notif_type_friend_accepted
    NotifKind.PortfolioShared -> R.string.bt_notif_type_portfolio_shared
    NotifKind.AlertTriggered -> R.string.bt_notif_type_alert
    NotifKind.ChatMessage -> R.string.bt_notif_type_chat
    NotifKind.AccountInvite -> R.string.bt_notif_type_account_invite
    NotifKind.AccountTempPassword -> R.string.bt_notif_type_security
    NotifKind.FriendActivity -> R.string.bt_notif_type_friend_activity
    NotifKind.WatchlistShared -> R.string.bt_notif_type_watchlist_shared
    NotifKind.ConglomerateShared -> R.string.bt_notif_type_conglomerate_shared
    NotifKind.System -> R.string.bt_notif_type_system
}

/** Localized fallback body used when the server row has a blank body. */
private fun notifKindBodyRes(kind: NotifKind): Int = when (kind) {
    NotifKind.FriendRequest -> R.string.bt_notif_type_friend_request_sub
    NotifKind.FriendAccepted -> R.string.bt_notif_type_friend_accepted_sub
    NotifKind.PortfolioShared -> R.string.bt_notif_type_portfolio_shared_sub
    NotifKind.AlertTriggered -> R.string.bt_notif_type_alert_sub
    NotifKind.ChatMessage -> R.string.bt_notif_type_chat_sub
    NotifKind.AccountInvite -> R.string.bt_notif_type_account_invite_sub
    NotifKind.AccountTempPassword -> R.string.bt_notif_type_security_sub
    NotifKind.FriendActivity -> R.string.bt_notif_type_friend_activity_sub
    NotifKind.WatchlistShared -> R.string.bt_notif_type_watchlist_shared_sub
    NotifKind.ConglomerateShared -> R.string.bt_notif_type_conglomerate_shared_sub
    NotifKind.System -> R.string.bt_notif_type_system_sub
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
