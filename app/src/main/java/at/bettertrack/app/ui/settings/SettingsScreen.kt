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
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Webhook
import androidx.compose.material.icons.outlined.Widgets
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
import androidx.compose.ui.res.pluralStringResource
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
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.prefs.themeModeFromName
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.prefs.originLabel
import at.bettertrack.app.ui.components.BtChoiceSheet
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtPickerOption
import at.bettertrack.app.ui.components.BtPickerRow
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtWebLinkRow
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.update.UpdateAvailableRow
import at.bettertrack.app.ui.util.rememberBtLocale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Percent
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.AccountSettingsResponse
import at.bettertrack.app.data.api.dto.BT_BASE_CURRENCIES
import at.bettertrack.app.data.api.dto.BT_PROFILE_ICONS
import at.bettertrack.app.data.api.dto.ProfileSettingsResponse
import at.bettertrack.app.data.api.dto.UpdateAccountSettingsRequest
import at.bettertrack.app.data.api.dto.UpdateProfileSettingsRequest
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtSkeleton
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import at.bettertrack.app.ui.components.BtAvatar
import at.bettertrack.app.ui.components.profileIconLabelRes
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Settings & account management (spec §6.12).
 *
 * ## The 2026-08-08 regroup — the web control center's taxonomy
 *
 * The owner's order was to group the settings more like the web control center.
 * That control center (`apps/web/src/user/control/ControlCenterOverlay.tsx`)
 * groups by **what the user is doing**, not by which file exists, and every
 * group answers one question:
 *
 * | Web group           | Its question                          | Here |
 * |---------------------|---------------------------------------|------|
 * | Account             | who am I, and how does it render?     | **Account** (merged with Profile) |
 * | —                   | (device-scoped half of the above)     | **Appearance** |
 * | Security            | how do I prove it's me? where am I in?| **Sign-in & security** |
 * | Preferences         | how does the app behave for me?       | **New portfolio defaults**, **Preferences**, **Privacy** |
 * | Connections & API   | what is plugged into my account?      | **Connections & API** |
 * | Danger zone         | the one irreversible action           | **Danger zone** |
 *
 * Three adaptations, each deliberate:
 *
 * 1. **Account and Profile are ONE section**, on the owner's explicit order.
 *    The web keeps two panels; the app merges them because the avatar and the
 *    username are the same question and were three sections apart.
 * 2. **Appearance is split out of Account** rather than folded into it as the
 *    web does. Theme, language and rotation are DEVICE-scoped: they survive
 *    logout and must show in a Drive-only install that has no account at all,
 *    which an account-gated group cannot do.
 * 3. **New portfolio defaults is its own group**, owner-named, mirroring the
 *    web's DefaultsPanel — the user-level layer of the settings cascade.
 *
 * App-only surfaces the web has no panel for are placed by the same question
 * rule rather than parked in a leftovers bin: Server and "Where your data
 * lives" answer *how does the app behave for me* (Preferences); About, the
 * version row and the hidden Developer menu are chrome and stay at the bottom;
 * app lock lives inside the Security screen, and quiet hours inside
 * Notifications, per the prior ruling.
 *
 * Nothing lost capability in the move — this was regrouping and renaming, not a
 * capability change, and every row still leads where it led.
 *
 * ## R2 visual pass — what changed, and what deliberately did not
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
    onOpenWidgets: () -> Unit = {},
    onOpenTaxSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    /** The feedback composer. Only ever reached when `FeedbackFlags.enabled`. */
    onOpenFeedback: () -> Unit = {},
    onOpenDeleteAccount: () -> Unit = {},
    // `onOpenChangelog` was removed 2026-08-09: it went vestigial in a1e7882 when
    // the About block left Settings for `AboutScreen`, which took the "What's new"
    // row with it and wires its own `onOpenChangelog` (AppShell → AboutScreen).
    // Settings kept the parameter and a live lambda for thirteen months and never
    // invoked either. The doorway exists; only the plumbing to it was dead.
    onOpenDataHome: () -> Unit = {},
    onOpenGallery: () -> Unit = {},
    onOpenSyncDebug: () -> Unit = {},
    onOpenServer: () -> Unit = {},
    onOpenConnections: () -> Unit = {},
    onOpenAuthorizedApps: () -> Unit = {},
    onOpenPublicProfile: () -> Unit = {},
    onOpenDataExport: () -> Unit = {},
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

    // Alerts-visible-to-followers (#455). Null until the read lands, which is
    // what keeps the switch disabled rather than showing a confident "off" for a
    // flag nobody has asked the server about yet.
    var alertSharing by remember { mutableStateOf<Boolean?>(null) }
    var alertSharingBusy by remember { mutableStateOf(false) }
    var alertSharingError by remember { mutableStateOf<BtMessage?>(null) }
    var alertSharingConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(hasAccount, hasNotifications) {
        // Drive-only installs have no alert engine at all, so there is nothing to
        // share and nothing to ask about.
        if (!hasAccount || !hasNotifications) return@LaunchedEffect
        alertSharing = (AppGraph.alertsRepository.sharing() as? BtResult.Ok)?.value
    }
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

            // ── ACCOUNT — identity and profile, MERGED ───────────────────────
            //
            // Owner order 2026-08-08: Account and Profile become ONE surface.
            // They were two sections asking the same question from opposite ends
            // — "who am I to the server" and "who am I to everyone else" — with
            // the avatar stranded three sections below the username it belongs
            // to. Identity leads (artwork, name, facts), then the things you can
            // actually change about it, then the two hand-offs.
            //
            // The web control center keeps Account and Profile as two panels.
            // This is the one deliberate divergence from its taxonomy, and it is
            // the owner's call, not a drift.
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_account_section))
                BtGroup {
                    AccountIdentity(
                        username = user?.username.orEmpty(),
                        email = user?.email?.ifBlank { null },
                        iconId = profile?.profileIcon,
                        // Web parity: the account page names the day the account
                        // was opened. Absent on a pre-v5 server (`createdAt` is
                        // null) and on a session cached before this build, and the
                        // line simply does not render then — an em dash would read
                        // as "we lost it", not "your server does not say".
                        memberSince = formatMemberSince(user?.memberSince, rememberBtLocale()),
                    )
                    BtGroupRow(
                        title = stringResource(R.string.bt_settings_profile_icon),
                        subtitle = stringResource(R.string.bt_settings_profile_icon_sub),
                        onClick = {
                            if (profile == null) loadProfile()
                            picker = SettingsPicker.ProfileIcon
                        },
                        // The row previews the choice with the REAL avatar, in the
                        // trailing "current value" position — the artwork itself,
                        // never a Material stand-in for it.
                        trailing = {
                            BtAvatar(
                                name = profile?.username.orEmpty(),
                                iconId = profile?.profileIcon,
                                size = 32.dp,
                            )
                        },
                    )
                    // Round-trips through `/settings/account`, so it belongs to
                    // the modes that HAVE an account. The web files it under its
                    // Account panel too — that panel's question is "who am I, and
                    // how does the app render for me", and a display currency is
                    // the second half of it.
                    BtGroupRow(
                        icon = Icons.Outlined.Payments,
                        title = stringResource(R.string.bt_settings_base_currency),
                        subtitle = stringResource(R.string.bt_settings_base_currency_sub),
                        onClick = { picker = SettingsPicker.Currency },
                        trailing = { SettingsValue(accountPrefs?.baseCurrency) },
                    )
                    // The public opt-in and the bio are NATIVE now (owner
                    // doctrine: anything the server stores must be editable on
                    // the phone too, at least as granularly as on the web). The
                    // app already read both fields and echoed them back on every
                    // icon change; it simply never let the user change them.
                    BtGroupRow(
                        icon = Icons.Outlined.Person,
                        title = stringResource(R.string.bt_profile_dest),
                        subtitle = profileVisibilitySubtitle(profile),
                        onClick = onOpenPublicProfile,
                    )
                    // The account-wide export is native too. The 2026-08-08 ruling
                    // that deferred it assumed a mail-a-link job with several
                    // formats; the real contract is a re-auth'd request, a poll
                    // and a one-time download of a single zip, which is a flow the
                    // phone can own honestly.
                    BtGroupRow(
                        icon = Icons.Outlined.Download,
                        title = stringResource(R.string.bt_export_dest),
                        subtitle = stringResource(R.string.bt_export_row_sub),
                        onClick = onOpenDataExport,
                    )
                }
                accountPrefsError?.let {
                    BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            // ── APPEARANCE ───────────────────────────────────────────────────
            // Device-scoped, not account-scoped: these belong to the phone you
            // are holding, survive logout, and are shown in EVERY storage mode —
            // a Drive-only install has no account but still has eyes and a
            // language. That ungated-ness is why they are their own group rather
            // than folded into Account, where the web's own taxonomy files the
            // interface preferences: folding them there would hide them from the
            // installs that have no account at all.
            //
            // ## Two things this section deliberately does NOT contain
            //
            // **Interface scale — ANDROID-SYSTEM-EXEMPT** (parity audit
            // 2026-08-08). The web ships a text/interface-scale setting because a
            // browser tab has no other owner for it. Android does: Settings →
            // Display → Font size and Display size already scale every `sp` and
            // every density-aware dimension in this app, system-wide, with a
            // preview and per-user accessibility defaults. An in-app duplicate
            // would compose with the system's value rather than replace it, so
            // the two controls would multiply and neither would be the truth.
            // This is a ruling, not an oversight: do not "restore" it.
            //
            // (One thing it deliberately does NOT contain — the interface-scale
            // ruling above still stands. True black is no longer on that list:)
            //
            // **True black — REINSTATED by the owner, 2026-08-17:** *"also the
            // oled dark mode dissapeared."* It had been removed the day after it
            // shipped, for web parity, and `DevicePrefs` additionally destroyed
            // the stored key on read so nobody was left trapped in a black app
            // with no control to undo it.
            //
            // That parity argument is overruled and the reasoning is worth
            // keeping: the web has no OLED setting because a browser tab has no
            // panel. This app runs on one specific piece of glass in the owner's
            // hand, where pixel-off is a real, measurable property — so parity
            // with a surface that cannot have the feature is the wrong test. The
            // row is back below, the pref persists again, and the healing is
            // gone (`DevicePrefs.setTrueBlack`).
            BtSectionHeader(stringResource(R.string.bt_settings_appearance_section))
            val themeMode by AppGraph.devicePrefs.themeMode.collectAsStateWithLifecycle()
            BtGroup {
                BtGroupRow(
                    icon = Icons.Outlined.Contrast,
                    title = stringResource(R.string.bt_settings_theme),
                    subtitle = stringResource(R.string.bt_settings_theme_sub),
                    onClick = { picker = SettingsPicker.Theme },
                    trailing = { SettingsValue(stringResource(themeModeLabelRes(themeMode))) },
                )
                // Reinstated 2026-08-17 (see the ruling above). Directly under
                // Theme because it is a sub-setting OF the theme, not a peer of
                // it — and greyed, with its own reason, whenever the app is
                // rendering light, where it would change nothing.
                val trueBlack by AppGraph.devicePrefs.trueBlack.collectAsStateWithLifecycle()
                val darkActive = !BtTheme.colors.isLight
                SettingsToggleRow(
                    icon = Icons.Outlined.DarkMode,
                    title = stringResource(R.string.bt_settings_true_black),
                    subtitle = stringResource(
                        if (darkActive) {
                            R.string.bt_settings_true_black_sub
                        } else {
                            R.string.bt_settings_true_black_light_hint
                        },
                    ),
                    checked = trueBlack,
                    onCheckedChange = { AppGraph.devicePrefs.setTrueBlack(it) },
                    enabled = darkActive,
                )
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
                // The in-app widget builder (widget redesign 2026-08-16). Filed
                // under Appearance because that is where "how BetterTrack looks
                // outside its own window" naturally reads from.
                BtGroupRow(
                    icon = Icons.Outlined.Widgets,
                    title = stringResource(R.string.bt_dest_widgets),
                    subtitle = stringResource(R.string.bt_settings_widgets_sub),
                    onClick = onOpenWidgets,
                )
            }

            // ── SIGN-IN & SECURITY ───────────────────────────────────────────
            // The web's Security group asks "how do I prove it's me, and where am
            // I signed in?" — its Sign-in and Sessions panels. The app answers
            // both behind one Security screen (2FA, passkeys, PIN, app lock and
            // the session list all live there), so this group is that screen plus
            // the password change that used to sit under Account.
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_signin_section))
                BtGroup {
                    BtGroupRow(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.bt_dest_settings_security),
                        subtitle = stringResource(R.string.bt_settings_security_sub),
                        onClick = onOpenSecurity,
                    )
                    BtGroupRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.bt_dest_change_password),
                        subtitle = stringResource(R.string.bt_settings_change_password_sub),
                        onClick = onOpenChangePassword,
                    )
                }
            }

            // ── NEW PORTFOLIO DEFAULTS ───────────────────────────────────────
            // Owner-named group (2026-08-08), mirroring the web's DefaultsPanel:
            // the user-level DEFAULT layer of the settings cascade
            // (effective = portfolio override ?? user default ?? system default).
            // The intro line states that inheritance, because the one thing that
            // confuses people here is whether editing a default rewrites the
            // portfolios that already exist. It does not.
            //
            // Currently one scopeable default (tax treatment), exactly like the
            // web — the group exists so the next one drops in as a sibling row.
            //
            // **Default visibility is deliberately absent** (parity audit
            // 2026-08-08, web test #377): the web forbids setting a new-portfolio
            // visibility default, so mirroring the control here would offer a
            // promise the platform does not keep. Sharing is chosen per item, on
            // the item, through the audience sheet. Do not resurrect it.
            if (hasAccount && storageMode.shows(BtSurface.TAX_MODES)) {
                BtSectionHeader(stringResource(R.string.bt_settings_defaults_section))
                Text(
                    text = stringResource(R.string.bt_settings_defaults_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                BtGroup {
                    // This row deliberately does not try to summarise a value:
                    // the account default is the one number on this screen that
                    // may apply to none of your portfolios.
                    BtGroupRow(
                        icon = Icons.Outlined.Percent,
                        title = stringResource(R.string.bt_tax_settings_row),
                        subtitle = stringResource(R.string.bt_tax_settings_row_sub),
                        onClick = onOpenTaxSettings,
                    )
                }
            }

            // ── PREFERENCES ──────────────────────────────────────────────────
            // The web's Preferences group asks "how does the app behave for me?".
            // Notifications is its panel verbatim (quiet hours stays inside it,
            // per the prior ruling). "Where your data lives" and "Server" are
            // app-only and land here because they are exactly that question for a
            // native client: which backend, and which copy of the data.
            BtSectionHeader(stringResource(R.string.bt_settings_preferences_section))
            BtGroup {
                if (hasNotifications) {
                    BtGroupRow(
                        icon = Icons.Outlined.Notifications,
                        title = stringResource(R.string.bt_settings_notifications_row),
                        subtitle = stringResource(R.string.bt_settings_notifications_sub),
                        onClick = onOpenNotifications,
                    )
                }
                BtGroupRow(
                    icon = Icons.Outlined.Storage,
                    title = stringResource(R.string.bt_storage_settings_row),
                    subtitle = stringResource(storageMode.labelRes()),
                    onClick = onOpenDataHome,
                )
                // Which backend this install talks to. A `github`-flavor row — a
                // Play install has fixed official endpoints, so the setting would
                // be a lie there. This and the login screen's gear are the two
                // (and only two) paths to the Server screen.
                if (BuildConfig.SERVER_SETTING_ENABLED) {
                    BtGroupRow(
                        icon = Icons.Outlined.Dns,
                        title = stringResource(R.string.bt_dest_server),
                        subtitle = serverRowSubtitle(),
                        onClick = onOpenServer,
                    )
                }
            }

            // ── PRIVACY ──────────────────────────────────────────────────────
            // The web's Privacy panel. Discreet mode round-trips through the
            // account, so it belongs to the modes that have one.
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

            // Alerts visible to followers (#455). A privacy question about the
            // account, so it lives beside discreet mode rather than on the
            // Workboard where the alerts themselves are managed: this decides who
            // may see them, not what they are.
            //
            // Unlike discreet mode this one is NOT optimistic. Turning it on
            // exposes every asset the user watches to anyone who follows them, so
            // the switch waits for the confirmation and then for the server —
            // flipping first and rolling back would show "shared" for a moment on
            // a decision that had not been made.
            SettingsToggleRow(
                icon = Icons.Outlined.Campaign,
                title = stringResource(R.string.bt_alert_sharing_title),
                subtitle = stringResource(
                    if (alertSharing == true) R.string.bt_alert_sharing_on
                    else R.string.bt_alert_sharing_off,
                ),
                checked = alertSharing == true,
                enabled = alertSharing != null && !alertSharingBusy,
                onCheckedChange = { wanted ->
                    alertSharingError = null
                    if (wanted) {
                        // The §16 rung. Asking is the UI's job; stating the
                        // acknowledgement on the wire is the repository's.
                        alertSharingConfirm = true
                    } else {
                        alertSharingBusy = true
                        scope.launch {
                            when (val r = AppGraph.alertsRepository.setSharing(false)) {
                                is BtResult.Ok -> {
                                    alertSharing = r.value
                                    snackbar.show(R.string.bt_alert_sharing_disabled)
                                }

                                is BtResult.Err -> alertSharingError = r.error.asMessage()
                            }
                            alertSharingBusy = false
                        }
                    }
                },
            )
            }
            discreetError?.let {
                BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp))
            }
            alertSharingError?.let {
                BtFormError(it, modifier = Modifier.padding(horizontal = 4.dp))
            }
            }

            // ── CONNECTIONS & API ────────────────────────────────────────────
            // The web's Integrations group — "what is plugged into my account?".
            //
            // Owner order 2026-08-08: **Connections and Authorized apps are
            // handled INSIDE the app and do not redirect.** Both are now real
            // screens at web capability parity. The remaining three stay
            // hand-offs: API keys, OAuth apps and webhooks each show a secret
            // exactly once at creation time, which is a job for a full keyboard
            // and a page you can copy out of, not a phone row — and the owner
            // named only the two.
            if (hasAccount) {
                BtSectionHeader(stringResource(R.string.bt_settings_integrations_section))
                Text(
                    text = stringResource(R.string.bt_settings_integrations_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                BtGroup {
                    BtGroupRow(
                        icon = Icons.Outlined.Link,
                        title = stringResource(R.string.bt_settings_connections_row),
                        subtitle = stringResource(R.string.bt_settings_connections_sub),
                        onClick = onOpenConnections,
                    )
                    BtGroupRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = stringResource(R.string.bt_settings_authorized_apps_row),
                        subtitle = stringResource(R.string.bt_settings_authorized_apps_sub),
                        onClick = onOpenAuthorizedApps,
                    )
                    BtWebLinkRow(
                        icon = Icons.Outlined.Key,
                        title = stringResource(R.string.bt_settings_web_api_keys),
                        path = "/control/api",
                    )
                    BtWebLinkRow(
                        icon = Icons.Outlined.Apps,
                        title = stringResource(R.string.bt_settings_web_oauth_apps),
                        path = "/control/oauth-apps",
                    )
                    BtWebLinkRow(
                        icon = Icons.Outlined.Webhook,
                        title = stringResource(R.string.bt_settings_web_webhooks),
                        path = "/control/webhooks",
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
                // The feedback composer's primary entry point. Held behind the
                // capability flag — the route exists, but a row that opens a form
                // whose POST can only 403 is worse than no row. See
                // `FeedbackFlags.enabled` for the two-step unlock.
                if (at.bettertrack.app.data.repo.FeedbackFlags.enabled) {
                    BtGroupRow(
                        icon = Icons.Outlined.Feedback,
                        title = stringResource(R.string.bt_dest_feedback),
                        subtitle = stringResource(R.string.bt_settings_feedback_sub),
                        onClick = onOpenFeedback,
                    )
                }
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
                    border = BorderStroke(1.dp, bt.edge(bt.loss, 0.35f)),
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

    // The §16 rung for exposing alerts to followers. A dialog rather than an
    // inline checkbox because the web uses one, and because the confirming BUTTON
    // carrying the words "I understand" is the acknowledgement — there is no
    // second control to forget to tick.
    if (alertSharingConfirm) {
        val sharingScope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { if (!alertSharingBusy) alertSharingConfirm = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_alert_sharing_confirm_title)) },
            text = { Text(stringResource(R.string.bt_alert_sharing_confirm_warning)) },
            confirmButton = {
                TextButton(
                    enabled = !alertSharingBusy,
                    onClick = {
                        alertSharingBusy = true
                        sharingScope.launch {
                            when (val r = AppGraph.alertsRepository.setSharing(true)) {
                                is BtResult.Ok -> {
                                    alertSharing = r.value
                                    snackbar.show(R.string.bt_alert_sharing_enabled)
                                }

                                is BtResult.Err -> alertSharingError = r.error.asMessage()
                            }
                            alertSharingBusy = false
                            alertSharingConfirm = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.bt_alert_sharing_confirm_enable), color = bt.goldInk)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !alertSharingBusy,
                    onClick = { alertSharingConfirm = false },
                ) { Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary) }
            },
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = bt.goldInk) },
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

        SettingsPicker.Currency -> BtChoiceSheet(
            title = stringResource(R.string.bt_settings_base_currency),
            subtitle = stringResource(R.string.bt_settings_base_currency_sub),
            // The codes are the labels: a currency code is not copy, and the
            // platform's list is closed (`BASE_CURRENCIES`), so this is a picker
            // rather than a text field.
            options = BT_BASE_CURRENCIES.map { BtPickerOption(value = it, label = it) },
            selected = accountPrefs?.baseCurrency,
            busy = savingAccountPrefs,
            message = accountPrefsError,
            closeLabel = stringResource(R.string.bt_action_cancel),
            onPick = { saveAccountPrefs(UpdateAccountSettingsRequest(baseCurrency = it)) },
            onDismiss = {
                picker = null
                accountPrefsError = null
            },
        )

        SettingsPicker.Theme -> {
            // Collected, not read off `.value`: the sheet does not dismiss on
            // pick (it is its own preview), so the tick has to MOVE when the
            // choice changes underneath it.
            val mode by AppGraph.devicePrefs.themeMode.collectAsStateWithLifecycle()
            BtChoiceSheet(
                title = stringResource(R.string.bt_settings_theme),
                options = BtThemeMode.entries.map {
                    BtPickerOption(value = it.name, label = stringResource(themeModeLabelRes(it)))
                },
                selected = mode.name,
                // Device-local and synchronous: there is nothing to wait for and
                // nothing that can fail, so the two round-trip slots stay empty.
                busy = false,
                message = null,
                // Deliberately does NOT dismiss. The whole app is repainting
                // behind this sheet, which makes the picker its own preview —
                // the one place in Settings where staying open is more useful
                // than closing, and a sheet shows more of the repainting app
                // than the centre dialog it replaced ever did.
                onPick = { AppGraph.devicePrefs.setThemeMode(themeModeFromName(it)) },
                closeLabel = stringResource(R.string.bt_action_done),
                onDismiss = { picker = null },
            )
        }

        SettingsPicker.ProfileIcon -> ProfileIconSheet(
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

/**
 * Which of the settings pickers is open.
 *
 * `Visibility` is gone with its row (parity audit 2026-08-08, web test #377) —
 * see the PREFERENCES section for why the app does not offer a new-portfolio
 * visibility default at all.
 */
private enum class SettingsPicker { Currency, ProfileIcon, Theme }

/**
 * Theme choice → its label. Exhaustive over [BtThemeMode] on purpose: the
 * enum stays three-valued (true black is a boolean UNDER Dark, not a fourth
 * mode) precisely so this `when` cannot grow a silent `else`.
 */
private fun themeModeLabelRes(mode: BtThemeMode): Int = when (mode) {
    BtThemeMode.System -> R.string.bt_settings_theme_system
    BtThemeMode.Light -> R.string.bt_settings_theme_light
    BtThemeMode.Dark -> R.string.bt_settings_theme_dark
}

// The `defaultPortfolioVisibility` wire constants and their label map went with
// the row (parity audit 2026-08-08). `AccountSettingsResponse` still CARRIES the
// field — it is the platform's wire shape and not the app's to trim — the app
// simply never reads or writes it.

/**
 * `/auth/me`'s `createdAt` as a "Member since" day, or **null** when there is
 * nothing honest to render.
 *
 * Null covers all three ways this can be absent, and the row is omitted for each
 * of them: a pre-v5 server that never sends the key, a session cached by a build
 * that did not carry it, and a timestamp this app cannot parse. An em dash would
 * claim the value was lost; saying nothing claims nothing.
 *
 * Rendered in the DEVICE's zone rather than UTC. This is a true instant (unlike
 * an ex-date or a pay date, which are calendar days the server pins to UTC
 * midnight and which `intelDate` therefore keeps in UTC), and the honest answer
 * to "when did I join" is the wall clock the user was looking at when they did.
 *
 * Pure, so the parsing and the fallbacks are unit-tested without a device.
 */
internal fun formatMemberSince(
    iso: String?,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val raw = iso?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val instant = runCatching { Instant.parse(raw) }
        .recoverCatching { OffsetDateTime.parse(raw).toInstant() }
        .getOrNull() ?: return null
    return runCatching {
        instant.atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }.getOrNull()
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

// R4: `SettingsChoiceDialog` is gone. Its job — a single-select list where
// picking is the confirm, with a busy state and an error that keeps the surface
// open — is now `BtChoiceSheet` in `ui/components/BtPickerSheet.kt`, shared with
// every other picker in the app. See that file's KDoc for what a centre dialog
// was getting wrong.

/**
 * The 16 curated profile icons, plus "no icon".
 *
 * The grid renders the web's own avatar artwork (`ui/components/BtProfileIcon.kt`),
 * 4×4 in the contract's own order, which is part of the contract: ids are
 * appended, never inserted, so a user's icon cannot silently become a different
 * one.
 *
 * [ready] is the `isPublic` precondition, not a spinner: until the current
 * profile is in hand the grid is not drawn at all, because a PUT without it
 * would flip a public profile private.
 *
 * ## Why it is a sheet with a grid rather than a picker of its own
 *
 * It keeps the grid — sixteen pieces of artwork are a thing you look AT, and a
 * list of sixteen 56dp rows would be a scroll instead of a glance. What it no
 * longer keeps is its own chrome: the title, the scroll cap, the error slot, the
 * close button and the refusal to dismiss mid-write all come from
 * [BtPickerSheet], so this picker and the currency/theme pickers are visibly one
 * family. The "no icon" entry is a [BtPickerRow] for exactly that reason — it is
 * a choice in a list, not a cell in the grid, and it now looks like every other
 * choice in the app.
 */
@Composable
private fun ProfileIconSheet(
    current: String?,
    ready: Boolean,
    loading: Boolean,
    busy: Boolean,
    message: BtMessage?,
    onRetry: () -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberBtHaptics()
    BtPickerSheet(
        title = stringResource(R.string.bt_settings_profile_icon_title),
        onDismiss = onDismiss,
        busy = busy,
        // While the profile read is still failing the error IS the content (a
        // retryable [BtInlineError] below), so it must not also be repeated as
        // the sheet's own footer line.
        message = if (ready) message else null,
        closeLabel = stringResource(R.string.bt_action_cancel),
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
                BtPickerRow(
                    label = stringResource(R.string.bt_settings_profile_icon_none),
                    selected = current == null,
                    onClick = if (busy || current == null) {
                        null
                    } else {
                        {
                            haptics.confirm()
                            onPick(null)
                        }
                    },
                    leading = {
                        Icon(
                            imageVector = Icons.Outlined.AccountCircle,
                            contentDescription = null,
                            tint = if (current == null) BtTheme.colors.goldEmphasis else BtTheme.colors.textSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                )
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
                                onClick = {
                                    haptics.confirm()
                                    onPick(id)
                                },
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
    }
}

private const val PROFILE_ICON_COLUMNS = 4

/**
 * 52 → 56dp. The cell now holds real artwork rather than a 24dp glyph, and the
 * selection ring is drawn around it instead of recolouring it, so it needs the
 * extra 4dp to keep the same optical breathing room.
 */
private val PROFILE_ICON_CELL = 56.dp

@Composable
private fun ProfileIconCell(
    id: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val isSelected = selected
    // Closes the TalkBack gap the old grid documented and left open: every cell
    // announced nothing at all, because the ids are wire tokens with no copy.
    val label = profileIconLabelRes(id)?.let { stringResource(it) }
    Box(
        modifier = Modifier
            .size(PROFILE_ICON_CELL)
            .background(if (isSelected) bt.goldWash else Color.Transparent, CircleShape)
            .then(if (isSelected) Modifier.border(2.dp, bt.goldEdge, CircleShape) else Modifier)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics {
                role = Role.RadioButton
                this.selected = isSelected
                if (label != null) contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        // The artwork is multicolour and carries its own tile, so selection is a
        // ring + backing rather than a tint swap — tinting it would destroy the
        // very thing the user is choosing between.
        BtAvatar(name = id, iconId = id, size = PROFILE_ICON_ART)
    }
}

/** The artwork inside the 56dp cell — the ring needs the remaining 8dp. */
private val PROFILE_ICON_ART = 44.dp

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
/**
 * The public-profile row's subtitle: what the profile currently IS, not what the
 * row does.
 *
 * Null (the profile read has not landed, or failed) falls back to the neutral
 * private-state wording rather than claiming a state we have not confirmed.
 */
@Composable
private fun profileVisibilitySubtitle(profile: ProfileSettingsResponse?): String = when {
    profile == null -> stringResource(R.string.bt_settings_profile_icon_sub)
    !profile.isPublic -> stringResource(R.string.bt_profile_row_sub_off)
    else -> pluralStringResource(
        R.plurals.bt_profile_row_sub_on,
        profile.publicItemCount,
        profile.publicItemCount,
    )
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /**
     * False when the toggle exists but cannot bite right now — the True-black
     * row while the app is rendering light. Shown greyed WITH a subtitle that
     * says why, rather than hidden: a control that vanishes teaches the reader
     * nothing, and "where did my OLED setting go" is the exact bug this row is
     * back to fix.
     */
    enabled: Boolean = true,
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
            .clickable(enabled = enabled) { commit(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) bt.textSecondary else bt.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) bt.textPrimary else bt.textMuted,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = commit,
            enabled = enabled,
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

/**
 * The merged Account section's head (owner order 2026-08-08): the profile
 * artwork and the username as one identity block, then the account facts under
 * it.
 *
 * This is the whole point of merging Account and Profile. The avatar used to
 * live three sections below the username it belongs to, so the two halves of
 * "who am I" never appeared on screen together; here the picture and the name
 * are one object, and everything below is a fact about it.
 *
 * [memberSince] is already formatted (or null) — the row is OMITTED rather than
 * em-dashed when the server does not say, because an em dash would read as "we
 * lost it" instead of "your server does not carry it".
 */
@Composable
private fun AccountIdentity(
    username: String,
    email: String?,
    iconId: String?,
    memberSince: String?,
) {
    val bt = BtTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtAvatar(name = username, iconId = iconId, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                text = username.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
        }
        AccountRow(stringResource(R.string.bt_settings_email), email ?: "—")
        memberSince?.let {
            AccountRow(stringResource(R.string.bt_settings_member_since), it)
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
