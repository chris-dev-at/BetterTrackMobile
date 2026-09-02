package at.bettertrack.app.ui.auth

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.i18n.AppLanguage
import at.bettertrack.app.data.i18n.LocaleManager
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.prefs.originLabel
import at.bettertrack.app.data.prefs.themeModeFromName
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtChoiceSheet
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtPickerOption
import at.bettertrack.app.ui.shell.BtSheet
import at.bettertrack.app.ui.shell.BtSheetHostState
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Settings, before there is an account (owner order 2026-08-08).
 *
 * Owner verbatim: *"on the login page there should be a small setting thing up
 * top corner so you move the change server and other settings you need to do
 * before login there. still keep the display of the server on the bottom but
 * only visual text no button."*
 *
 * ## What is on it, and why nothing else is
 *
 * The rule is *"settings you need to do before login"*, read strictly: a row
 * belongs here only if it genuinely works with no session. Three do.
 *
 *  - **Server** — the reason the order exists. You have to be able to point the
 *    app at a backend BEFORE signing in to one, and the in-app Settings screen
 *    is behind the very login you cannot complete yet. It opens the same
 *    [at.bettertrack.app.ui.settings.ServerScreen] the bottom line used to, with
 *    the same capabilities, and is gated on the same flavor switch
 *    ([ServerOrigins.settingEnabled]) — a Play install has fixed endpoints, so
 *    the row would be a lie there.
 *  - **Theme** — device-scoped (`devicePrefs`), synchronous, no network. It
 *    already survives logout; there was never a reason it needed a login.
 *  - **Language** — `LocaleManager` persists to a private prefs file and
 *    recreates the activity, which is entirely device-local. The one part of
 *    Settings→Language that touches an account is its best-effort mirror to
 *    `PATCH /settings/account`, and that is deliberately NOT done here: there is
 *    no account to mirror to yet. The choice reaches the server the first time
 *    the user changes it while signed in.
 *
 * Everything else in Settings — security, notifications, taxes, discreet mode,
 * the whole `/control/…` family — needs a session to mean anything, so it stays
 * where it is rather than appearing here as a row that opens a 401.
 *
 * ## Why it drives [BtSheet] directly instead of `btSheet<>`
 *
 * The login screen is rendered OUTSIDE the NavHost (see
 * [at.bettertrack.app.ui.shell.BtRoot]) — logged out there is no graph to
 * register a route in, and adding one would put a pre-auth destination in the
 * shell's graph purely to open a sheet. So this composes the same sheet
 * component every subpage uses, with a local [BtSheetHostState] whose `pop` is
 * this surface's own dismissal. The user gets the identical grabber, scrim,
 * pull-down and predictive-back behaviour as everywhere else in the app.
 *
 * @param diagnostics whether the sign-out history is showing INSIDE this sheet.
 *   Hoisted (see [PreLoginNav]) rather than remembered here, so that back at
 *   this level pops exactly one thing — the history — instead of the whole
 *   sheet, which is what it did until the owner's 2026-09-01 pass (#4).
 * @param onOpenServer opens the Server screen — the host swaps it in for the
 *   login screen, exactly as the retired bottom-line button did. Null where the
 *   flavor has no server setting.
 */
@Composable
internal fun PreLoginSettingsSheet(
    diagnostics: Boolean,
    onOpenDiagnostics: () -> Unit,
    onCloseDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    onOpenServer: (() -> Unit)? = null,
) {
    val onDismissState = rememberUpdatedState(onDismiss)
    val onOpenServerState = rememberUpdatedState(onOpenServer)

    // Opening the Server screen REPLACES the login screen underneath this sheet,
    // and this sheet is composed by that login screen. Handing over on the tap
    // would therefore delete the sheet mid-air, with no exit animation and the
    // scrim vanishing on the same frame. So the tap only arms the hand-off; the
    // sheet plays its ordinary dismissal and the swap happens on the far side of
    // it, in `pop`.
    var handOff by remember { mutableStateOf(false) }
    val host = remember {
        BtSheetHostState(
            pop = {
                // The hand-off REPLACES the dismissal rather than following it.
                // Both would mean "close the sheet, then open the Server screen",
                // and closing the sheet is a POP — it would take the Settings
                // level off the stack that the Server screen is about to be
                // pushed onto, and back from the Server screen would then land
                // on the bare login screen. Which is exactly what it did.
                if (handOff) {
                    handOff = false
                    onOpenServerState.value?.invoke()
                } else {
                    onDismissState.value()
                }
            },
        )
    }

    BtSheet(host) {
        PreLoginSettingsContent(
            diagnostics = diagnostics,
            onOpenDiagnostics = onOpenDiagnostics,
            onCloseDiagnostics = onCloseDiagnostics,
            onClose = { host.dismissTop() },
            onOpenServer = if (ServerOrigins.settingEnabled && onOpenServer != null) {
                {
                    handOff = true
                    host.dismissTop()
                }
            } else {
                null
            },
        )
    }
}

/**
 * The sheet's content — a plain screen, in the app's short-screen bar idiom
 * (title + back arrow, no collapsing header), because three rows never scroll.
 * The sheet above it has already consumed the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreLoginSettingsContent(
    diagnostics: Boolean,
    onOpenDiagnostics: () -> Unit,
    onCloseDiagnostics: () -> Unit,
    onClose: () -> Unit,
    onOpenServer: (() -> Unit)?,
) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val activity = context as? Activity
    var picker by remember { mutableStateOf<PreLoginPicker?>(null) }
    val themeMode by AppGraph.devicePrefs.themeMode.collectAsStateWithLifecycle()

    // The diagnostics history REPLACES this sheet's content rather than opening
    // a second sheet on top of it: the login screen is outside the NavHost, so
    // there is no route to push, and stacking sheets to show a list would put two
    // grabbers and two scrims on screen for one linear drill-down.
    if (diagnostics) {
        // ...which is exactly why back needs saying out loud here. The sheet
        // layer's own PredictiveBackHandler would take a press and dismiss the
        // WHOLE sheet — two levels for one back. This composable is composed
        // inside that layer, so its handler is registered after the layer's and
        // wins the dispatcher (owner report #4).
        BackHandler(onBack = onCloseDiagnostics)
        AuthDiagnosticsScreen(onClose = onCloseDiagnostics)
        return
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.bt_dest_settings),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
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
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_prelogin_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            BtGroup {
                if (onOpenServer != null) {
                    BtGroupRow(
                        icon = Icons.Outlined.Dns,
                        title = stringResource(R.string.bt_dest_server),
                        subtitle = serverRowSubtitle(),
                        onClick = onOpenServer,
                    )
                }
                BtGroupRow(
                    icon = Icons.Outlined.Contrast,
                    title = stringResource(R.string.bt_settings_theme),
                    subtitle = stringResource(R.string.bt_settings_theme_sub),
                    onClick = { picker = PreLoginPicker.Theme },
                    trailing = { PreLoginValue(stringResource(themeModeLabelRes(themeMode))) },
                )
                BtGroupRow(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(R.string.bt_dest_settings_language),
                    subtitle = stringResource(languageLabelRes(LocaleManager.current(context))),
                    onClick = { picker = PreLoginPicker.Language },
                )
                // Its own labelled row, not folded into anything: someone who has
                // just been thrown back to this screen needs to find the answer
                // without knowing the word for what happened to them.
                BtGroupRow(
                    icon = Icons.Outlined.MonitorHeart,
                    title = stringResource(R.string.bt_prelogin_diagnostics),
                    subtitle = stringResource(R.string.bt_prelogin_diagnostics_sub),
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }

    when (picker) {
        null -> Unit

        // The same picker Settings→Theme opens, down to the label on its close
        // button: it deliberately does NOT dismiss on pick, because the app is
        // repainting behind it and that makes the list its own live preview.
        PreLoginPicker.Theme -> BtChoiceSheet(
            title = stringResource(R.string.bt_settings_theme),
            options = BtThemeMode.entries.map {
                BtPickerOption(value = it.name, label = stringResource(themeModeLabelRes(it)))
            },
            selected = themeMode.name,
            // Device-local and synchronous: nothing to wait for, nothing to fail.
            busy = false,
            message = null,
            onPick = { AppGraph.devicePrefs.setThemeMode(themeModeFromName(it)) },
            closeLabel = stringResource(R.string.bt_action_done),
            onDismiss = { picker = null },
        )

        // Picking recreates the activity (that is how the per-app locale is
        // applied), so this sheet, the pre-login sheet under it and the login
        // screen under that are all rebuilt in the new language. Nothing is lost:
        // logged out there is no state to lose, and inside the first-run wizard
        // the step lives in a ViewModel, which a recreate retains.
        PreLoginPicker.Language -> BtChoiceSheet(
            title = stringResource(R.string.bt_dest_settings_language),
            options = AppLanguage.entries.map {
                BtPickerOption(value = it.name, label = stringResource(languageLabelRes(it)))
            },
            selected = LocaleManager.current(context).name,
            busy = false,
            message = null,
            onPick = { name ->
                val language = AppLanguage.entries.firstOrNull { it.name == name }
                if (activity != null && language != null) {
                    LocaleManager.applyAndRecreate(activity, language)
                }
            },
            closeLabel = stringResource(R.string.bt_action_done),
            onDismiss = { picker = null },
        )
    }
}

/** Which pre-login picker is open. */
private enum class PreLoginPicker { Theme, Language }

/**
 * A row's current value in the trailing slot, with the chevron kept beside it —
 * the same shape Settings uses, so the two screens read as one family. Private
 * here rather than shared because it is four lines and `SettingsScreen`'s copy
 * is private for the same reason.
 */
@Composable
private fun PreLoginValue(value: String) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
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
 * The Server row's subtitle: the host actually in use when it is NOT the
 * official one, and the plain description otherwise — the same rule Settings
 * follows, and it matters more here. Standing at a login screen, "which backend
 * am I about to hand this password to" is the question the row exists to answer.
 */
@Composable
private fun serverRowSubtitle(): String =
    if (ServerOrigins.isOverridden) {
        originLabel(ServerOrigins.apiOrigin)
    } else {
        stringResource(R.string.bt_settings_server_sub)
    }

/** Theme choice → its label. Exhaustive, so the enum cannot grow a silent case. */
private fun themeModeLabelRes(mode: BtThemeMode): Int = when (mode) {
    BtThemeMode.System -> R.string.bt_settings_theme_system
    BtThemeMode.Light -> R.string.bt_settings_theme_light
    BtThemeMode.Dark -> R.string.bt_settings_theme_dark
}

/** Language choice → its label. Same list, same copy as Settings→Language. */
private fun languageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.System -> R.string.bt_lang_system
    AppLanguage.English -> R.string.bt_lang_english
    AppLanguage.German -> R.string.bt_lang_german
}
