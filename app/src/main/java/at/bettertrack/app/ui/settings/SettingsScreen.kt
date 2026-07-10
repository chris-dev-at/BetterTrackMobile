package at.bettertrack.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.auth.SessionUser
import at.bettertrack.app.data.i18n.AppLanguage
import at.bettertrack.app.data.i18n.LocaleManager
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.update.UpdateAvailableRow

/**
 * Settings & account management (spec §6.12). Sections: **Account** (username /
 * email display, change password, delete account), **Preferences** (security,
 * notifications, language), **About** (version, About screen, what's new), plus a
 * hidden **Developer** menu revealed by multi-tapping the version row (debug only),
 * and Log out. Each destructive/secondary surface is its own screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSecurity: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenChangePassword: () -> Unit = {},
    onOpenLanguage: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDeleteAccount: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSyncDebug: () -> Unit = {},
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val auth = AppGraph.authRepository
    val authState by auth.authState.collectAsStateWithLifecycle()
    val user: SessionUser? = when (val s = authState) {
        is AuthState.LoggedIn -> s.user
        is AuthState.PasswordChangeRequired -> s.user
        else -> null
    }

    LaunchedEffect(Unit) { auth.refreshUser() }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Hidden developer menu: multi-tap the version row (debug builds only).
    var versionTaps by remember { mutableIntStateOf(0) }
    var devUnlocked by remember { mutableStateOf(false) }
    val versionInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bt_dest_settings), style = MaterialTheme.typography.titleLarge) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val update by AppGraph.updateChecker.available.collectAsStateWithLifecycle()
            update?.let { UpdateAvailableRow(it) }

            // ── ACCOUNT ──────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.bt_settings_account_section))
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AccountRow(stringResource(R.string.bt_settings_username), user?.username?.ifBlank { "—" } ?: "—")
                    AccountRow(stringResource(R.string.bt_settings_email), user?.email?.ifBlank { "—" } ?: "—")
                }
            }
            SettingsNavRow(
                icon = Icons.Outlined.Key,
                title = stringResource(R.string.bt_dest_change_password),
                subtitle = stringResource(R.string.bt_settings_change_password_sub),
                onClick = onOpenChangePassword,
            )

            Spacer(Modifier.height(4.dp))

            // ── PREFERENCES ──────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.bt_settings_preferences_section))
            SettingsNavRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.bt_dest_settings_security),
                subtitle = stringResource(R.string.bt_settings_security_sub),
                onClick = onOpenSecurity,
            )
            SettingsNavRow(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.bt_settings_notifications_row),
                subtitle = stringResource(R.string.bt_settings_notifications_sub),
                onClick = onOpenNotifications,
            )
            SettingsNavRow(
                icon = Icons.Outlined.Translate,
                title = stringResource(R.string.bt_dest_settings_language),
                subtitle = currentLanguageLabel(),
                onClick = onOpenLanguage,
            )

            Spacer(Modifier.height(4.dp))

            // ── ABOUT ────────────────────────────────────────────────────────
            SectionLabel(stringResource(R.string.bt_settings_about_section))
            // Version row — multi-tap (debug) reveals the Developer section.
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (BuildConfig.DEBUG) {
                                Modifier.clickable(interactionSource = versionInteraction, indication = null) {
                                    versionTaps++
                                    if (versionTaps >= DEV_TAP_THRESHOLD && !devUnlocked) {
                                        devUnlocked = true
                                        Toast.makeText(context, context.getString(R.string.bt_settings_dev_unlocked), Toast.LENGTH_SHORT).show()
                                    } else if (versionTaps in DEV_TAP_HINT_AT until DEV_TAP_THRESHOLD) {
                                        val left = DEV_TAP_THRESHOLD - versionTaps
                                        Toast.makeText(context, context.getString(R.string.bt_settings_dev_hint, left), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.bt_settings_version), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                    Text(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                }
            }
            SettingsNavRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.bt_dest_settings_about),
                subtitle = stringResource(R.string.bt_settings_about_sub),
                onClick = onOpenAbout,
            )

            // ── DEVELOPER (hidden; debug + multi-tap) ────────────────────────
            if (BuildConfig.DEBUG && devUnlocked) {
                Spacer(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.bt_settings_developer_section))
                SettingsNavRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.bt_settings_dev_gallery),
                    subtitle = stringResource(R.string.bt_settings_dev_gallery_sub),
                    onClick = onOpenGallery,
                )
                SettingsNavRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.bt_settings_dev_sync),
                    subtitle = stringResource(R.string.bt_settings_dev_sync_sub),
                    onClick = onOpenSyncDebug,
                )
            }

            Spacer(Modifier.height(8.dp))

            BtSecondaryButton(
                text = stringResource(R.string.bt_action_logout),
                onClick = { showLogoutConfirm = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            // ── DANGER ZONE ──────────────────────────────────────────────────
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.bt_settings_danger_section))
            Surface(
                onClick = onOpenDeleteAccount,
                color = bt.surface,
                border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.35f)),
                shape = BtShapes.card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = bt.loss, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bt_dest_delete_account), style = MaterialTheme.typography.titleSmall, color = bt.loss)
                        Text(stringResource(R.string.bt_settings_delete_sub), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = bt.gold) },
            title = { Text(stringResource(R.string.bt_settings_logout_confirm_title)) },
            text = { Text(stringResource(R.string.bt_settings_logout_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    auth.requestLogout()
                }) { Text(stringResource(R.string.bt_action_logout), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

private const val DEV_TAP_THRESHOLD = 7
private const val DEV_TAP_HINT_AT = 4

@Composable
private fun currentLanguageLabel(): String = when (LocaleManager.current(LocalContext.current)) {
    AppLanguage.System -> stringResource(R.string.bt_lang_system)
    AppLanguage.English -> stringResource(R.string.bt_lang_english)
    AppLanguage.German -> stringResource(R.string.bt_lang_german)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = BtTheme.colors.textMuted)
}

@Composable
private fun SettingsNavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Surface(onClick = onClick, color = bt.surface, border = BorderStroke(1.dp, bt.border), shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = bt.textMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, value: String, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
