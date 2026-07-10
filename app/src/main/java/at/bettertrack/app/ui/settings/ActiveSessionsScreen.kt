package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.account.AccountSession
import at.bettertrack.app.data.account.SessionMapper
import at.bettertrack.app.data.account.SessionRecency
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * Settings → Security → Active sessions (spec §6.12). Lists the account's web /
 * other-device logins (`GET /auth/sessions`) and revokes one (`DELETE /auth/
 * sessions/{id}`) or all others (`POST /auth/sessions/revoke-others`). The app's
 * OWN principal is an OAuth token, not a web session, so it never appears here and
 * no row is marked "current" — a footnote explains that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveSessionsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val repo = AppGraph.accountRepository
    val scope = rememberCoroutineScope()
    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var sessions by remember { mutableStateOf<List<AccountSession>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var revokeTarget by remember { mutableStateOf<AccountSession?>(null) }
    var showRevokeAll by remember { mutableStateOf(false) }

    suspend fun reload() {
        when (val r = repo.sessions()) {
            is BtResult.Ok -> { sessions = r.value; error = null }
            is BtResult.Err -> error = r.error.userMessage
        }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_active_sessions), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg, titleContentColor = bt.textPrimary, navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bt_sessions_intro), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = bt.gold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
                error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = bt.loss)
                sessions.isEmpty() -> Text(stringResource(R.string.bt_sessions_none), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                else -> {
                    sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            enabled = online && !busy,
                            onRevoke = { revokeTarget = session },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.bt_sessions_app_note), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)

                    if (sessions.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            onClick = { showRevokeAll = true },
                            enabled = online && !busy,
                            color = bt.surface,
                            border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.4f)),
                            shape = BtShapes.card,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.bt_sessions_revoke_all),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.loss,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_sessions_revoke_title)) },
            text = { Text(stringResource(R.string.bt_sessions_revoke_message, target.deviceLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    revokeTarget = null
                    busy = true
                    scope.launch {
                        when (val r = repo.revokeSession(target.id)) {
                            is BtResult.Ok -> reload()
                            is BtResult.Err -> error = r.error.userMessage
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.bt_sessions_revoke), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
            },
        )
    }

    if (showRevokeAll) {
        AlertDialog(
            onDismissRequest = { showRevokeAll = false },
            containerColor = bt.surface,
            titleContentColor = bt.loss,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_sessions_revoke_all_title)) },
            text = { Text(stringResource(R.string.bt_sessions_revoke_all_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRevokeAll = false
                    busy = true
                    scope.launch {
                        when (val r = repo.revokeOtherSessions()) {
                            is BtResult.Ok -> reload()
                            is BtResult.Err -> error = r.error.userMessage
                        }
                        busy = false
                    }
                }) { Text(stringResource(R.string.bt_sessions_revoke_all_confirm), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAll = false }) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
            },
        )
    }
}

@Composable
private fun SessionRow(session: AccountSession, enabled: Boolean, onRevoke: () -> Unit) {
    val bt = BtTheme.colors
    val isDesktop = session.deviceLabel.contains("Windows", true) ||
        session.deviceLabel.contains("Mac", true) ||
        session.deviceLabel.contains("Linux", true)
    Surface(color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isDesktop) Icons.Outlined.Computer else Icons.Outlined.Smartphone,
                contentDescription = null,
                tint = bt.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session.deviceLabel, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary, fontWeight = FontWeight.Medium)
                    if (session.current) {
                        Spacer(Modifier.width(8.dp))
                        BtBadge(text = stringResource(R.string.bt_sessions_current))
                    }
                }
                Text(lastActiveLabel(session.lastSeenAtMs), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            if (!session.current) {
                TextButton(onClick = onRevoke, enabled = enabled) {
                    Text(stringResource(R.string.bt_sessions_revoke), color = if (enabled) bt.loss else bt.textMuted)
                }
            }
        }
    }
}

@Composable
private fun lastActiveLabel(lastSeenMs: Long?): String {
    return when (val r = SessionMapper.recency(lastSeenMs, System.currentTimeMillis())) {
        is SessionRecency.JustNow -> stringResource(R.string.bt_sessions_just_now)
        is SessionRecency.MinutesAgo -> stringResource(R.string.bt_sessions_minutes, r.minutes)
        is SessionRecency.HoursAgo -> stringResource(R.string.bt_sessions_hours, r.hours)
        is SessionRecency.DaysAgo -> stringResource(R.string.bt_sessions_days, r.days)
        is SessionRecency.OnDate -> stringResource(
            R.string.bt_sessions_on_date,
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(r.epochMs)),
        )
        is SessionRecency.Unknown -> stringResource(R.string.bt_sessions_unknown)
    }
}
