package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.auth.SessionUser
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.update.UpdateAvailableRow

/**
 * Minimal Step-4 Settings surface: it shows the signed-in account (username +
 * email from `/auth/me`) and provides Log out (server-side revocation + full
 * local wipe). TODO(step 18): this screen grows into full Settings & account
 * management (change password, delete account, security, notifications, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val auth = AppGraph.authRepository
    val authState by auth.authState.collectAsStateWithLifecycle()
    val user: SessionUser? = when (val s = authState) {
        is AuthState.LoggedIn -> s.user
        is AuthState.PasswordChangeRequired -> s.user
        else -> null
    }

    // Freshen username/email whenever Settings opens.
    LaunchedEffect(Unit) { auth.refreshUser() }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_dest_settings),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Dev update notifier badge (Step V) — only when a newer build exists.
            val update by AppGraph.updateChecker.available.collectAsStateWithLifecycle()
            update?.let { UpdateAvailableRow(it) }

            Text(
                text = stringResource(R.string.bt_settings_account_section).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AccountRow(
                        label = stringResource(R.string.bt_settings_username),
                        value = user?.username?.ifBlank { "—" } ?: "—",
                    )
                    AccountRow(
                        label = stringResource(R.string.bt_settings_email),
                        value = user?.email?.ifBlank { "—" } ?: "—",
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            BtSecondaryButton(
                text = stringResource(R.string.bt_action_logout),
                onClick = { showLogoutConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            )
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            icon = {
                Icon(
                    Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = null,
                    tint = bt.gold,
                )
            },
            title = { Text(stringResource(R.string.bt_settings_logout_confirm_title)) },
            text = { Text(stringResource(R.string.bt_settings_logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    auth.requestLogout()
                }) {
                    Text(stringResource(R.string.bt_action_logout), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun AccountRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
