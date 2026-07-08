package at.bettertrack.app.ui.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.NotifChannel
import at.bettertrack.app.data.notifications.NotifKind
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Notification settings matrix (Step 16, §6.11): per-type × per-channel (in-app /
 * email / push / mute) preferences + the system permission status. In-app + email
 * mirror the web and sync to `PATCH /settings/notifications` when the scope is
 * granted; Push + Mute are saved on-device (no server push channel yet). Muting a
 * type suppresses it locally — proven on device.
 *
 * Design note: the type × channel matrix is rendered as per-type cards with
 * channel toggle-chips (not a dense checkbox grid) so every target stays a 48dp
 * tap on a phone while reading as one coherent grid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val store = AppGraph.notificationSettingsStore
    val repo = AppGraph.notificationRepository
    val scope = rememberCoroutineScope()

    val matrix by store.matrix.collectAsStateWithLifecycle()

    // Best-effort: pull the server matrix on open so in-app/email reflect the web.
    androidx.compose.runtime.LaunchedEffect(Unit) { repo.loadServerSettings() }

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

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_settings_notifications), style = MaterialTheme.typography.titleLarge) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // System permission status (Android 13+) with an in-context enable.
            PermissionStatusCard(
                granted = permissionGranted,
                needsPermission = needsPermission,
                onEnable = {
                    if (permissionGranted) {
                        // Already granted → deep-link to the app's channel settings.
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    } else {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )

            Text(
                stringResource(R.string.bt_notif_matrix_section).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )

            store.configurableKinds.forEach { kind ->
                TypePrefCard(
                    kind = kind,
                    prefs = matrix.prefs(kind),
                    onToggleChannel = { channel, on ->
                        store.setChannel(kind, channel, on)
                        scope.launch { repo.pushServerSettings() }
                    },
                    onToggleMute = { muted -> store.setMuted(kind, muted) },
                )
            }

            Text(
                stringResource(R.string.bt_notif_matrix_footer),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PermissionStatusCard(granted: Boolean, needsPermission: Boolean, onEnable: () -> Unit) {
    val bt = BtTheme.colors
    val on = granted || !needsPermission
    Surface(
        onClick = onEnable,
        color = if (on) bt.surface else bt.gold.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, if (on) bt.border else bt.gold.copy(alpha = 0.35f)),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (on) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                contentDescription = null,
                tint = if (on) bt.gain else bt.gold,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (on) R.string.bt_notif_perm_on_title else R.string.bt_notif_perm_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.textPrimary,
                )
                Text(
                    stringResource(if (on) R.string.bt_notif_perm_on_message else R.string.bt_notif_perm_off_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
            if (!on) {
                Text(stringResource(R.string.bt_notif_enable_push_action), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = bt.gold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypePrefCard(
    kind: NotifKind,
    prefs: at.bettertrack.app.data.notifications.TypePrefs,
    onToggleChannel: (NotifChannel, Boolean) -> Unit,
    onToggleMute: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(notifKindTitle(kind), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                    Text(notifKindSubtitle(kind), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                }
                Text(
                    stringResource(R.string.bt_notif_mute),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (prefs.muted) bt.gold else bt.textMuted,
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = prefs.muted,
                    onCheckedChange = onToggleMute,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = bt.onGold,
                        checkedTrackColor = bt.gold,
                        checkedBorderColor = bt.gold,
                        uncheckedThumbColor = bt.textMuted,
                        uncheckedTrackColor = bt.surface,
                        uncheckedBorderColor = bt.borderStrong,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelChip(R.string.bt_notif_channel_inapp, prefs.inApp, prefs.muted) { onToggleChannel(NotifChannel.InApp, it) }
                ChannelChip(R.string.bt_notif_channel_email, prefs.email, prefs.muted) { onToggleChannel(NotifChannel.Email, it) }
                ChannelChip(R.string.bt_notif_channel_push, prefs.push, prefs.muted) { onToggleChannel(NotifChannel.Push, it) }
            }
        }
    }
}

@Composable
private fun ChannelChip(labelRes: Int, selected: Boolean, muted: Boolean, onToggle: (Boolean) -> Unit) {
    BtChip(
        text = stringResource(labelRes),
        selected = selected && !muted,
        enabled = !muted,
        onClick = { onToggle(!selected) },
    )
}

@Composable
private fun notifKindTitle(kind: NotifKind): String = stringResource(
    when (kind) {
        NotifKind.FriendRequest -> R.string.bt_notif_type_friend_request
        NotifKind.FriendAccepted -> R.string.bt_notif_type_friend_accepted
        NotifKind.PortfolioShared -> R.string.bt_notif_type_portfolio_shared
        NotifKind.AlertTriggered -> R.string.bt_notif_type_alert
        NotifKind.ChatMessage -> R.string.bt_notif_type_chat
        NotifKind.AccountInvite -> R.string.bt_notif_type_account_invite
        NotifKind.AccountTempPassword -> R.string.bt_notif_type_security
        NotifKind.System -> R.string.bt_notif_type_system
    },
)

@Composable
private fun notifKindSubtitle(kind: NotifKind): String = stringResource(
    when (kind) {
        NotifKind.FriendRequest -> R.string.bt_notif_type_friend_request_sub
        NotifKind.FriendAccepted -> R.string.bt_notif_type_friend_accepted_sub
        NotifKind.PortfolioShared -> R.string.bt_notif_type_portfolio_shared_sub
        NotifKind.AlertTriggered -> R.string.bt_notif_type_alert_sub
        NotifKind.ChatMessage -> R.string.bt_notif_type_chat_sub
        NotifKind.AccountInvite -> R.string.bt_notif_type_account_invite_sub
        NotifKind.AccountTempPassword -> R.string.bt_notif_type_security_sub
        NotifKind.System -> R.string.bt_notif_type_system_sub
    },
)
