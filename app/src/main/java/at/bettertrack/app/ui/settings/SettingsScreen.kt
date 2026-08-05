package at.bettertrack.app.ui.settings

import kotlinx.coroutines.launch
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.storage.labelRes
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.update.UpdateAvailableRow

/**
 * Settings & account management (spec §6.12). Sections: **Account** (username /
 * email display, change password, delete account), **Preferences** (security,
 * notifications, language), **About** (version, About screen, what's new), plus a
 * hidden **Developer** menu revealed by multi-tapping the version row (debug only),
 * and Log out. Each destructive/secondary surface is its own screen.
 *
 * ## R2 visual pass — what changed, and what deliberately did not
 *
 * **No IA changes.** Every row leads where it led before, in the same section,
 * in the same order. The mandate's §5 webapp-parity items (digest cadence and
 * quiet hours, discreet mode) were already here; R2's job was to make them sit
 * naturally, not to move them.
 *
 * **The wall of boxes is gone.** Every row used to be its own bordered
 * `Surface` — eleven identical rounded rectangles stacked vertically, so the
 * border was the loudest thing on the screen and nothing indicated which rows
 * belonged together. Rows are now [BtGroupRow]s inside one [BtGroup] per
 * section: a single tonal block, no border, no dividers, the grouping carried by
 * the tonal step alone (mandate §4). The section labels stay, but they now label
 * something that visually *is* one thing.
 *
 * **The bar collapses.** Settings scrolls, so it gets the same large title every
 * other R2 screen has.
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
    onOpenDataHome: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSyncDebug: () -> Unit = {},
    onOpenDevBackend: () -> Unit = {},
) {
    val bt = BtTheme.colors
    val snackbar = LocalBtSnackbar.current
    val auth = AppGraph.authRepository
    val authState by auth.authState.collectAsStateWithLifecycle()
    // V5 W5: Settings adapts to where the data lives (S3/S4 plan §4.5). A
    // Drive-only install has no BetterTrack account, so the account rows, the
    // server-backed notification settings and "Log out" are ABSENT — the vault
    // section on "Where your data lives" is what replaces them. SERVER and BOTH
    // render exactly as before.
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    val storageMode = AppGraph.gatedStorageMode(storedMode)
    val hasAccount = storageMode.shows(BtSurface.ACCOUNT_SETTINGS)
    val hasNotifications = storageMode.shows(BtSurface.ALERTS_NOTIFICATIONS)
    val user: SessionUser? = when (val s = authState) {
        is AuthState.LoggedIn -> s.user
        is AuthState.PasswordChangeRequired -> s.user
        else -> null
    }

    LaunchedEffect(Unit) { auth.refreshUser() }

    // Reconcile discreet mode with the account: the device cache decides what is
    // rendered (so masking survives a cold or offline start), the server decides
    // what the user actually chose — e.g. after flipping it on the web.
    LaunchedEffect(Unit) {
        val r = AppGraph.accountRepository.discreetMode()
        if (r is BtResult.Ok) AppGraph.discreetModeStore.set(r.value)
    }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Hidden developer menu: multi-tap the version row (debug builds only).
    var versionTaps by remember { mutableIntStateOf(0) }
    var devUnlocked by remember { mutableStateOf(false) }
    val versionInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_dest_settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The "Update available" row is part of the self-update surface — shown
            // only in github builds (Task B1); Play builds compile it out.
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                val update by AppGraph.updateChecker.available.collectAsStateWithLifecycle()
                update?.let { UpdateAvailableRow(it) }
            }

            // ── ACCOUNT ──────────────────────────────────────────────────────
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_account_section))
                BtGroup {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AccountRow(stringResource(R.string.bt_settings_username), user?.username?.ifBlank { "—" } ?: "—")
                        AccountRow(stringResource(R.string.bt_settings_email), user?.email?.ifBlank { "—" } ?: "—")
                    }
                    BtGroupRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.bt_dest_change_password),
                        subtitle = stringResource(R.string.bt_settings_change_password_sub),
                        onClick = onOpenChangePassword,
                    )
                }
            }

            // ── PREFERENCES ──────────────────────────────────────────────────
            BtSectionHeader(stringResource(R.string.bt_settings_preferences_section))
            BtGroup {
                BtGroupRow(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.bt_dest_settings_security),
                    subtitle = stringResource(R.string.bt_settings_security_sub),
                    onClick = onOpenSecurity,
                )
                BtGroupRow(
                    icon = Icons.Outlined.Storage,
                    title = stringResource(R.string.bt_storage_settings_row),
                    subtitle = stringResource(storageMode.labelRes()),
                    onClick = onOpenDataHome,
                )
                if (hasNotifications) {
                    BtGroupRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(R.string.bt_settings_notifications_row),
                        subtitle = stringResource(R.string.bt_settings_notifications_sub),
                        onClick = onOpenNotifications,
                    )
                }
                BtGroupRow(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(R.string.bt_dest_settings_language),
                    subtitle = currentLanguageLabel(),
                    onClick = onOpenLanguage,
                )
                val orientationLocked by AppGraph.devicePrefs.orientationLocked.collectAsStateWithLifecycle()
                SettingsToggleRow(
                    icon = Icons.Outlined.ScreenRotation,
                    title = stringResource(R.string.bt_settings_orientation_lock),
                    subtitle = stringResource(R.string.bt_settings_orientation_lock_sub),
                    checked = orientationLocked,
                    onCheckedChange = { AppGraph.devicePrefs.setOrientationLocked(it) },
                )
            }

            // ── PRIVACY ──────────────────────────────────────────────────────
            // Discreet mode round-trips through the account, so it belongs to the
            // modes that have one.
            if (hasAccount) {
            BtSectionHeader(stringResource(R.string.bt_settings_privacy_section))
            val discreet by AppGraph.discreetModeStore.enabled.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var discreetError by remember { mutableStateOf<BtMessage?>(null) }
            BtGroup {
            SettingsToggleRow(
                icon = Icons.Outlined.VisibilityOff,
                title = stringResource(R.string.bt_settings_discreet),
                subtitle = stringResource(R.string.bt_settings_discreet_sub),
                checked = discreet,
                onCheckedChange = { wanted ->
                    // Flip locally FIRST: the whole point is that amounts vanish
                    // the instant the user asks, not a round-trip later. If the
                    // server refuses, roll back and say so.
                    AppGraph.discreetModeStore.set(wanted)
                    discreetError = null
                    scope.launch {
                        val r = AppGraph.accountRepository.updateDiscreetMode(wanted)
                        if (r is BtResult.Err) {
                            AppGraph.discreetModeStore.set(!wanted)
                            discreetError = r.error.asMessage()
                        }
                    }
                },
            )
            }
            discreetError?.let {
                Text(
                    text = it.resolveWithDiagnostic(),
                    style = MaterialTheme.typography.bodySmall,
                    color = BtTheme.colors.loss,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            }

            // ── ABOUT ────────────────────────────────────────────────────────
            BtSectionHeader(stringResource(R.string.bt_settings_about_section))
            BtGroup {
                // Version row — multi-tap (debug) reveals the Developer section.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (BuildConfig.DEBUG) {
                                Modifier.clickable(interactionSource = versionInteraction, indication = null) {
                                    versionTaps++
                                    if (versionTaps >= DEV_TAP_THRESHOLD && !devUnlocked) {
                                        devUnlocked = true
                                        snackbar.show(R.string.bt_settings_dev_unlocked)
                                    } else if (versionTaps in DEV_TAP_HINT_AT until DEV_TAP_THRESHOLD) {
                                        val left = DEV_TAP_THRESHOLD - versionTaps
                                        snackbar.show(R.string.bt_settings_dev_hint, left.toString())
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
                BtGroupRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.bt_dest_settings_about),
                    subtitle = stringResource(R.string.bt_settings_about_sub),
                    onClick = onOpenAbout,
                )
            }

            // ── DEVELOPER (hidden; debug + multi-tap) ────────────────────────
            if (BuildConfig.DEBUG && devUnlocked) {
                BtSectionHeader(stringResource(R.string.bt_settings_developer_section))
                BtGroup {
                    BtGroupRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.bt_settings_dev_gallery),
                        subtitle = stringResource(R.string.bt_settings_dev_gallery_sub),
                        onClick = onOpenGallery,
                    )
                    BtGroupRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.bt_settings_dev_sync),
                        subtitle = stringResource(R.string.bt_settings_dev_sync_sub),
                        onClick = onOpenSyncDebug,
                    )
                    // V5 S1: point the installed debug build at any backend (dev stack).
                    BtGroupRow(
                        icon = Icons.Outlined.Code,
                        title = stringResource(R.string.bt_settings_dev_backend),
                        subtitle = stringResource(R.string.bt_settings_dev_backend_sub),
                        onClick = onOpenDevBackend,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // "Log out" is meaningless without an account (plan §4.4 row 1);
            // Drive mode offers lock / disconnect / delete-everything on
            // "Where your data lives" instead.
            if (hasAccount) {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_logout),
                    onClick = { showLogoutConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }

            // ── DANGER ZONE ──────────────────────────────────────────────────
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_danger_section))
                // The ONE place on this screen that keeps a border. Every other
                // group dropped its outline for a tonal step, which is exactly
                // what makes a single red-edged block read as "this one is not
                // like the others" — the emphasis is bought by the absence
                // elsewhere rather than by shouting louder.
                Surface(
                    color = bt.surface,
                    border = BorderStroke(1.dp, bt.loss.copy(alpha = 0.35f)),
                    shape = BtShapes.group,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BtGroupRow(
                        icon = Icons.Outlined.DeleteForever,
                        iconTint = bt.loss,
                        title = stringResource(R.string.bt_dest_delete_account),
                        titleColor = bt.loss,
                        subtitle = stringResource(R.string.bt_settings_delete_sub),
                        onClick = onOpenDeleteAccount,
                    )
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

// R2: `SectionLabel` and `SettingsNavRow` are gone — `BtSectionHeader` and
// `BtGroupRow` do both jobs for the whole app, which is what stops the settings
// subscreens each growing a slightly different row.

/**
 * A toggle row inside a [BtGroup]. Kept local rather than pushed into
 * `BtGroupRow`'s `trailing` slot because the whole ROW has to be the toggle's
 * tap target — a switch is a 32dp target at the far edge of a 360dp screen, and
 * making the label work is the difference between a settings list you can use
 * one-handed and one you have to aim at.
 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = bt.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = bt.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
}

@Composable
private fun AccountRow(label: String, value: String, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
