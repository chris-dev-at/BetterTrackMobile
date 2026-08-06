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
import androidx.compose.material.icons.outlined.Dns
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
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.prefs.originLabel
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.update.UpdateAvailableRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Anchor
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Rocket
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.WorkspacePremium
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AccountSettingsResponse
import at.bettertrack.app.data.api.dto.BT_BASE_CURRENCIES
import at.bettertrack.app.data.api.dto.BT_PROFILE_ICONS
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.data.api.dto.UpdateAccountSettingsRequest
import at.bettertrack.app.data.api.dto.UpdateProfileSettingsRequest
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSkeleton

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
    onOpenTaxSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDeleteAccount: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    onOpenDataHome: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSyncDebug: () -> Unit = {},
    onOpenServer: () -> Unit = {},
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

    // ── Account defaults + profile ───────────────────────────────────────────
    //
    // Display currency, the new-portfolio visibility default and the profile icon
    // are the last three account fields the app never exposed. They go through
    // [AccountRepository], which grew `accountSettings` / `updateAccountSettings` /
    // `socialProfile` / `updateProfileIcon` for them, rather than reaching for the
    // API client directly: `AppGraph.btApi` is private on purpose, and the icon
    // write in particular cannot be expressed by a typed DTO at all (see
    // `updateProfileIcon` — clearing needs an explicit JSON null that the app's
    // `explicitNulls = false` Json would otherwise drop).
    val settingsScope = rememberCoroutineScope()
    var accountPrefs by remember { mutableStateOf<AccountSettingsResponse?>(null) }
    var accountPrefsError by remember { mutableStateOf<BtMessage?>(null) }
    var savingAccountPrefs by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf<ProfileSettingsResponse?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileMessage by remember { mutableStateOf<BtMessage?>(null) }
    var savingProfile by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<SettingsPicker?>(null) }

    /**
     * The profile read is a PRECONDITION, not a nicety: `PUT /social/profile`
     * requires `isPublic`, so sending the icon without first knowing the current
     * value would quietly make a public profile private. Its failure is therefore
     * surfaced (and retryable) inside the picker instead of being swallowed.
     */
    fun loadProfile() {
        if (!hasAccount || profileLoading) return
        profileLoading = true
        profileMessage = null
        settingsScope.launch {
            when (val r = AppGraph.accountRepository.socialProfile()) {
                is BtResult.Ok -> profile = r.value
                is BtResult.Err -> profileMessage = r.error.asMessage()
            }
            profileLoading = false
        }
    }

    fun saveAccountPrefs(update: UpdateAccountSettingsRequest) {
        if (savingAccountPrefs) return
        savingAccountPrefs = true
        accountPrefsError = null
        settingsScope.launch {
            // The PATCH schema is strict and additive: only the field this dialog
            // changed travels (`explicitNulls = false` drops the rest), so picking
            // a currency can never rewrite the language or the discreet flag.
            when (val r = AppGraph.accountRepository.updateAccountSettings(update)) {
                is BtResult.Ok -> {
                    accountPrefs = r.value
                    picker = null
                }

                is BtResult.Err -> accountPrefsError = r.error.asMessage()
            }
            savingAccountPrefs = false
        }
    }

    fun saveProfileIcon(iconId: String?) {
        val current = profile ?: return
        if (savingProfile) return
        savingProfile = true
        profileMessage = null
        settingsScope.launch {
            // The repository composes the PUT by hand: `isPublic` and `bio` are
            // echoed from [current] because a PUT that omits them would make a
            // public profile private, and `profileIcon` is written as an explicit
            // JSON null when clearing — which a typed DTO cannot express under
            // `explicitNulls = false`. See AccountRepository.updateProfileIcon.
            when (val r = AppGraph.accountRepository.updateProfileIcon(current, iconId)) {
                is BtResult.Ok -> {
                    profile = r.value
                    // Still verified against the response rather than assumed. The
                    // clear path now genuinely works, but the server remains the
                    // authority on what it stored, and a silent divergence is
                    // exactly the class of bug this check was written to catch.
                    if (r.value.profileIcon == iconId) {
                        picker = null
                        snackbar.show(R.string.bt_settings_profile_saved)
                    } else {
                        profileMessage = BtMessage.generic
                    }
                }

                is BtResult.Err -> profileMessage = r.error.asMessage()
            }
            savingProfile = false
        }
    }

    LaunchedEffect(hasAccount) {
        if (!hasAccount) return@LaunchedEffect
        when (val r = AppGraph.accountRepository.accountSettings()) {
            is BtResult.Ok -> accountPrefs = r.value
            // Silent on purpose: this read is the app's idea, not the user's. The
            // rows fall back to their em dash and the picker still opens.
            is BtResult.Err -> Unit
        }
        loadProfile()
    }

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
                // Which backend this install talks to. A `github`-flavor row —
                // a Play install has fixed official endpoints, so the setting
                // would be a lie there. This and the login screen's affordance
                // are the two (and only two) paths to the Server screen.
                if (BuildConfig.SERVER_SETTING_ENABLED) {
                    BtGroupRow(
                        icon = Icons.Outlined.Dns,
                        title = stringResource(R.string.bt_dest_server),
                        subtitle = serverRowSubtitle(),
                        onClick = onOpenServer,
                    )
                }
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
                // Taxes — the ACCOUNT-level default, i.e. what a newly created
                // portfolio inherits. Any single portfolio can override it from
                // its own settings page, which is where the effective/inherited
                // distinction is actually rendered; this row deliberately does not
                // try to summarise a value, because the account default is the one
                // number on this screen that may apply to none of your portfolios.
                if (hasAccount && storageMode.shows(BtSurface.TAX_MODES)) {
                    BtGroupRow(
                        icon = Icons.Outlined.Percent,
                        title = stringResource(R.string.bt_tax_settings_row),
                        subtitle = stringResource(R.string.bt_tax_settings_row_sub),
                        onClick = onOpenTaxSettings,
                    )
                }
                // Both round-trip through `/settings/account`, so they belong to
                // the modes that HAVE an account — same rule as discreet mode.
                if (hasAccount) {
                    BtGroupRow(
                        icon = Icons.Outlined.Payments,
                        title = stringResource(R.string.bt_settings_base_currency),
                        subtitle = stringResource(R.string.bt_settings_base_currency_sub),
                        onClick = { picker = SettingsPicker.Currency },
                        trailing = { SettingsValue(accountPrefs?.baseCurrency) },
                    )
                    BtGroupRow(
                        icon = Icons.Outlined.Visibility,
                        title = stringResource(R.string.bt_settings_default_visibility),
                        subtitle = stringResource(R.string.bt_settings_default_visibility_sub),
                        onClick = { picker = SettingsPicker.Visibility },
                        trailing = {
                            val wire = accountPrefs?.defaultPortfolioVisibility
                            val labelRes = wire?.let { visibilityLabelRes(it) }
                            SettingsValue(labelRes?.let { stringResource(it) })
                        },
                    )
                }
                val orientationLocked by AppGraph.devicePrefs.orientationLocked.collectAsStateWithLifecycle()
                SettingsToggleRow(
                    icon = Icons.Outlined.ScreenRotation,
                    title = stringResource(R.string.bt_settings_orientation_lock),
                    subtitle = stringResource(R.string.bt_settings_orientation_lock_sub),
                    checked = orientationLocked,
                    onCheckedChange = { AppGraph.devicePrefs.setOrientationLocked(it) },
                )
            }

            accountPrefsError?.let {
                BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp))
            }

            // ── PROFILE ──────────────────────────────────────────────────────
            // What friends and group members see next to the username. There is
            // exactly one field the platform lets a client set — the icon — so
            // this is a one-row section rather than a screen of its own.
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_profile_section))
                BtGroup {
                    BtGroupRow(
                        // The row previews the choice: the leading glyph IS the
                        // current icon, gold when one is set.
                        icon = profileIconVector(profile?.profileIcon),
                        iconTint = if (profile?.profileIcon != null) bt.gold else null,
                        title = stringResource(R.string.bt_settings_profile_icon),
                        subtitle = stringResource(R.string.bt_settings_profile_icon_sub),
                        onClick = {
                            if (profile == null) loadProfile()
                            picker = SettingsPicker.ProfileIcon
                        },
                    )
                }
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
                BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp))
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

    when (picker) {
        null -> Unit

        SettingsPicker.Currency -> SettingsChoiceDialog(
            title = stringResource(R.string.bt_settings_base_currency),
            // The codes are the labels: a currency code is not copy, and the
            // platform's list is closed (`BASE_CURRENCIES`), so this is a picker
            // rather than a text field.
            options = BT_BASE_CURRENCIES.map { it to it },
            selected = accountPrefs?.baseCurrency,
            busy = savingAccountPrefs,
            message = accountPrefsError,
            onPick = { saveAccountPrefs(UpdateAccountSettingsRequest(baseCurrency = it)) },
            onDismiss = {
                picker = null
                accountPrefsError = null
            },
        )

        SettingsPicker.Visibility -> SettingsChoiceDialog(
            title = stringResource(R.string.bt_settings_default_visibility),
            options = listOf(
                VISIBILITY_PRIVATE to stringResource(R.string.bt_settings_visibility_private),
                VISIBILITY_FRIENDS to stringResource(R.string.bt_settings_visibility_friends),
            ),
            selected = accountPrefs?.defaultPortfolioVisibility,
            busy = savingAccountPrefs,
            message = accountPrefsError,
            onPick = { saveAccountPrefs(UpdateAccountSettingsRequest(defaultPortfolioVisibility = it)) },
            onDismiss = {
                picker = null
                accountPrefsError = null
            },
        )

        SettingsPicker.ProfileIcon -> ProfileIconDialog(
            current = profile?.profileIcon,
            ready = profile != null,
            loading = profileLoading,
            busy = savingProfile,
            message = profileMessage,
            onRetry = { loadProfile() },
            onPick = { saveProfileIcon(it) },
            onDismiss = {
                picker = null
                profileMessage = null
            },
        )
    }
}

/** Which of the three account pickers is open. */
private enum class SettingsPicker { Currency, Visibility, ProfileIcon }

/** `defaultPortfolioVisibility` wire values — the platform's closed pair. */
private const val VISIBILITY_PRIVATE = "private"
private const val VISIBILITY_FRIENDS = "friends"

/**
 * Wire visibility → label, or **null** for a value this build does not know.
 *
 * Deliberately not defaulting to "Private": the row would then confidently name
 * a setting the user does not have. An em dash says "not something I can name",
 * which is the truth, and the picker still opens.
 */
private fun visibilityLabelRes(wire: String): Int? = when (wire) {
    VISIBILITY_PRIVATE -> R.string.bt_settings_visibility_private
    VISIBILITY_FRIENDS -> R.string.bt_settings_visibility_friends
    else -> null
}

/**
 * A settings row's current value, sitting where the chevron would be — with the
 * chevron kept beside it, because the row still navigates somewhere.
 *
 * The em dash for an unknown value is the same placeholder the account rows
 * above already use, so a value that has not arrived yet reads as "not loaded"
 * rather than as an empty setting.
 */
@Composable
private fun SettingsValue(value: String?) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The shared single-select dialog behind the currency and visibility rows.
 *
 * Picking IS the confirmation — the same immediate-apply model the language
 * screen uses — so there is no second "Save" to press. The dialog stays open on
 * failure with the error under the list, because the choice the user made is
 * still on screen and still the thing they want.
 */
@Composable
private fun SettingsChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    busy: Boolean,
    message: BtMessage?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { (wire, label) ->
                    val isSelected = wire == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (busy || isSelected) {
                                    Modifier
                                } else {
                                    Modifier.clickable { onPick(wire) }
                                },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) bt.gold else bt.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = bt.gold,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    BtFormError(it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

/**
 * The 16 curated profile icons, plus "no icon".
 *
 * The platform ships **no artwork** for these ids — no bundled asset, no URL —
 * so each one is drawn as a Material glyph chosen by [profileIconVector]. The
 * grid is 4×4 in the contract's own order, which is part of the contract: ids
 * are appended, never inserted, so a user's icon cannot silently become a
 * different one.
 *
 * [ready] is the `isPublic` precondition, not a spinner: until the current
 * profile is in hand the grid is not drawn at all, because a PUT without it
 * would flip a public profile private.
 */
@Composable
private fun ProfileIconDialog(
    current: String?,
    ready: Boolean,
    loading: Boolean,
    busy: Boolean,
    message: BtMessage?,
    onRetry: () -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_settings_profile_icon_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    loading -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(4) { BtSkeleton(Modifier.fillMaxWidth().height(48.dp)) }
                    }

                    !ready -> BtInlineError(
                        message = message ?: BtMessage.generic,
                        onRetry = onRetry,
                    )

                    else -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (busy) Modifier else Modifier.clickable { onPick(null) })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = if (current == null) bt.gold else bt.textSecondary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.bt_settings_profile_icon_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current == null) bt.gold else bt.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (current == null) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = bt.gold,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        BT_PROFILE_ICONS.chunked(PROFILE_ICON_COLUMNS).forEach { rowIds ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                rowIds.forEach { id ->
                                    ProfileIconCell(
                                        id = id,
                                        selected = id == current,
                                        enabled = !busy,
                                        onClick = { onPick(id) },
                                    )
                                }
                                // Keeps a short last row left-aligned with the
                                // ones above instead of spreading across the width.
                                repeat(PROFILE_ICON_COLUMNS - rowIds.size) {
                                    Spacer(Modifier.size(PROFILE_ICON_CELL))
                                }
                            }
                        }
                    }
                }
                if (ready) {
                    message?.let {
                        Spacer(Modifier.height(10.dp))
                        BtFormError(it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

private const val PROFILE_ICON_COLUMNS = 4
private val PROFILE_ICON_CELL = 52.dp

@Composable
private fun ProfileIconCell(
    id: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Box(
        modifier = Modifier
            .size(PROFILE_ICON_CELL)
            .background(if (selected) bt.goldSurface else bt.bg, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = profileIconVector(id),
            // No description: the ids are wire tokens, not translated copy, and
            // this build has no string for them. The dialog's own title names the
            // task. KNOWN GAP — the picker needs one label string per id (or a
            // generic one) to be properly readable by TalkBack.
            contentDescription = null,
            tint = if (selected) bt.gold else bt.textSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Profile-icon id → Material glyph.
 *
 * The platform ships the ids only; picking the artwork is the client's job, and
 * this app deliberately does not invent 16 custom assets for a 24dp glyph. Two
 * ids share a glyph on purpose (fox and panda are both `Pets` — there is no
 * fox), which is a smaller lie than drawing something that is not the animal.
 *
 * **The `else` branch is load-bearing.** `PROFILE_ICON_IDS` is append-only on
 * the platform, so an id this build has never heard of WILL arrive — from the
 * user's own account after they pick one on the web, if nothing else. It renders
 * as a neutral avatar glyph rather than nothing, and the picker simply won't
 * offer it until the app ships a mapping.
 */
private fun profileIconVector(id: String?): ImageVector = when (id) {
    "astronaut" -> Icons.Outlined.Rocket
    "fox", "panda" -> Icons.Outlined.Pets
    "robot" -> Icons.Outlined.SmartToy
    "star" -> Icons.Outlined.Star
    "wave" -> Icons.Outlined.Waves
    "mountain" -> Icons.Outlined.Terrain
    "leaf" -> Icons.Outlined.Eco
    "flame" -> Icons.Outlined.LocalFireDepartment
    "bolt" -> Icons.Outlined.Bolt
    "moon" -> Icons.Outlined.DarkMode
    "planet" -> Icons.Outlined.Public
    "ghost" -> Icons.Outlined.Face
    "crown" -> Icons.Outlined.WorkspacePremium
    "compass" -> Icons.Outlined.Explore
    "anchor" -> Icons.Outlined.Anchor
    else -> Icons.Outlined.AccountCircle
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
    // R3 §4: a toggle is the one control whose result can sit under the thumb
    // that flipped it, so the direction is worth carrying in the haptic. Stated
    // once here and applied to both affordances below, because the row and the
    // switch are the same act and must not feel different.
    val haptics = rememberBtHaptics()
    val commit: (Boolean) -> Unit = { on -> haptics.toggle(on); onCheckedChange(on) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { commit(!checked) }
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
            onCheckedChange = commit,
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

/**
 * The Server row's subtitle: the host actually in use when it is NOT the
 * official one, and the plain description otherwise. A custom server is the
 * kind of state that must be legible without opening the screen — it changes
 * what every number in the app means.
 */
@Composable
private fun serverRowSubtitle(): String =
    if (ServerOrigins.isOverridden) {
        originLabel(ServerOrigins.apiOrigin)
    } else {
        stringResource(R.string.bt_settings_server_sub)
    }

@Composable
private fun AccountRow(label: String, value: String, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary)
    }
}
