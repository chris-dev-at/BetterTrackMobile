package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.auth.FirstRunGate
import at.bettertrack.app.data.auth.firstRunGate
import at.bettertrack.app.data.storage.RootGate
import at.bettertrack.app.data.storage.hasServerAccount
import at.bettertrack.app.data.storage.rootGate
import at.bettertrack.app.data.auth.SignOutReason
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.AppLockScreen
import at.bettertrack.app.ui.auth.LoginScreen
import at.bettertrack.app.ui.firstrun.FirstRunWizard
import at.bettertrack.app.ui.auth.PasswordChangeRequiredScreen
import at.bettertrack.app.data.prefs.ServerOrigins
import at.bettertrack.app.data.prefs.originLabel
import at.bettertrack.app.ui.settings.ServerScreen
import at.bettertrack.app.ui.storage.StorageSetupWizard
import at.bettertrack.app.ui.storage.VaultUnlockGate
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.ui.update.UpdateNotifierHost

/**
 * The root gate stack.
 *
 * V5 W5 inserts a **storage gate above the auth gate** (S3/S4 plan §4.1), which
 * is the only correct order: "do you have a BetterTrack session?" is a question
 * that only makes sense once "does this install use BetterTrack at all?" has been
 * answered. A Drive-only user has no session and never will, and the old gate
 * would have parked them on a login screen forever.
 *
 * ```
 * StorageMode.UNSET          → StorageSetupWizard
 * StorageMode.SERVER | BOTH  → the existing AuthState branch, unchanged
 * StorageMode.DRIVE          → VaultUnlockGate → BtApp()
 * ```
 *
 * **Ordering guarantee.** UNSET now *means* something — it renders the wizard —
 * so an existing install must be grandfathered to SERVER before this gate ever
 * evaluates it (plan §4.3). The grandfathering pass ends in an IO probe of the
 * Room owner key, so the gate waits on `storageModeStore.resolved` and shows the
 * same neutral backstop `AuthState.Unknown` uses. Without that wait an
 * upgrade-in-place would flash the first-run wizard at a user who has been using
 * the app for months.
 */
@Composable
fun BtRoot(
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val modeResolved by AppGraph.storageModeStore.resolved.collectAsStateWithLifecycle()
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()

    when (rootGate(modeResolved, AppGraph.gatedStorageMode(storedMode))) {
        // No-flash backstop while grandfathering resolves — see [rootGate].
        RootGate.WAITING -> Box(Modifier.fillMaxSize().background(BtTheme.colors.bg))

        RootGate.WIZARD -> StorageSetupWizard(onStartLogin = onStartLogin, onOpenUrl = onOpenUrl)

        RootGate.VAULT_UNLOCK -> VaultUnlockGate { BtApp() }

        // SERVER and BOTH keep today's behaviour byte for byte.
        RootGate.AUTH -> AuthGate(
            onStartLogin = onStartLogin,
            onOpenUrl = onOpenUrl,
            // Drive-autonomous installs have no BetterTrack account, so first-run
            // setup does not apply to them and the wizard must never be reachable
            // from there. Passed as a fact rather than re-derived inside the auth
            // gate so the ordering — storage mode decided BEFORE the account
            // question — stays visible at the one place that owns it.
            hasServerAccount = AppGraph.gatedStorageMode(storedMode).hasServerAccount,
        )
    }

    // Dev update notifier (Step V) — an app-level overlay dialog, shown over any
    // auth state when CI has published a newer debug build. Play builds (Task B1)
    // compile the self-update path out entirely, so the host is not rendered.
    if (BuildConfig.SELF_UPDATE_ENABLED) {
        UpdateNotifierHost()
    }
}

/**
 * The auth-gated root (spec §4), exactly as it has always been: logged out ⇒ the
 * login screen ONLY; a forced password change ⇒ the finish-on-the-web wall;
 * logged in ⇒ the app lock, then the 4-tab shell.
 */
@Composable
private fun AuthGate(
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    hasServerAccount: Boolean,
) {
    val auth = AppGraph.authRepository
    val state by auth.authState.collectAsStateWithLifecycle()

    when (state) {
        AuthState.Unknown ->
            // Startup is resolved synchronously; this is just a no-flash backstop.
            Box(Modifier.fillMaxSize().background(BtTheme.colors.bg))

        AuthState.LoggedOut -> {
            val phase by auth.loginPhase.collectAsStateWithLifecycle()
            // The Server screen has to be reachable while LOGGED OUT — you point
            // the app at a backend BEFORE you can sign in to one, and Settings
            // lives behind the login.
            //
            // Since the 2026-08-08 owner order the way in is the login screen's
            // corner gear → the pre-login settings sheet → its Server row, which
            // calls `onOpenServer` below; the bottom "Server: <host>" line is
            // display text and no longer a control. The debug wordmark long-press
            // stays as the old shortcut. The login screen is rendered outside the
            // NavHost, so this is a plain state swap rather than a route — and
            // that is also why the sheet drives `BtSheet` directly instead of
            // being a `btSheet<>` registration.
            var showServer by remember { mutableStateOf(false) }
            val serverEnabled = ServerOrigins.settingEnabled
            if (serverEnabled && showServer) {
                ServerScreen(onBack = { showServer = false })
            } else {
                LoginScreen(
                    phase = phase,
                    onLogin = onStartLogin,
                    onNeedAccount = { onOpenUrl(auth.needAccountUrl()) },
                    onForgotPassword = { onOpenUrl(auth.forgotPasswordUrl()) },
                    onLongPressWordmark = { if (BuildConfig.DEBUG && serverEnabled) showServer = true },
                    serverHost = if (serverEnabled) originLabel(ServerOrigins.webOrigin) else null,
                    onOpenServer = if (serverEnabled) ({ showServer = true }) else null,
                )
            }
        }

        is AuthState.PasswordChangeRequired ->
            PasswordChangeRequiredScreen(
                onOpenWeb = { onOpenUrl(auth.webHomeUrl()) },
                onLogout = { auth.requestLogout() },
            )

        is AuthState.LoggedIn -> {
            // Step 17 (§5): the app lock gates the logged-in UI. When enabled and
            // currently locked (cold start / AFK return) the lock screen shows
            // instead of any data. "Forgot PIN" wipes the lock + logs out so the
            // user can sign in again and set a new PIN.
            val lockConfig by AppGraph.appLockController.config.collectAsStateWithLifecycle()
            val locked by AppGraph.appLockController.locked.collectAsStateWithLifecycle()
            if (lockConfig.enabled && lockConfig.hasPin && locked) {
                AppLockScreen(
                    onForgotPin = {
                        AppGraph.appLockController.disableLock()
                        auth.requestLogout(SignOutReason.APP_LOCK_FORGOT_PIN)
                    },
                )
            } else {
                // The first-run gate (§6.12) — BELOW the storage gate, below the
                // auth gate and below the app lock, in that order and for the same
                // reason each of those sits where it does: "has this account been
                // set up?" only means anything once "is there an account, and may
                // this person see it?" has been answered.
                //
                // The no-flash discipline is [firstRunGate]'s third clause rather
                // than a `resolved` flag: the signal's own UNKNOWN value already
                // means "the server has not told us", and an upgrade-in-place or a
                // pre-0074 server reads UNKNOWN — so there is no window in which a
                // months-old install can be routed here, not even for one frame.
                val dismissedAccount by AppGraph.firstRunStore.dismissedAccount
                    .collectAsStateWithLifecycle()
                val user = (state as AuthState.LoggedIn).user
                val gate = firstRunGate(
                    hasServerAccount = hasServerAccount,
                    state = user.firstRun,
                    dismissedForAccount = user.id.isNotBlank() && dismissedAccount == user.id,
                )
                when (gate) {
                    FirstRunGate.WIZARD -> FirstRunWizard()
                    FirstRunGate.APP -> {
                        BtApp()
                        // PARANOID E9 (§17 step 3): the one-time fresh-start
                        // notice. It sits HERE — the last gate, beside the app
                        // itself — for the same ordering reason every gate above
                        // it does: "here is what happened to your account" is
                        // only worth saying to someone who is signed in, past the
                        // app lock and actually looking at their app. It is not
                        // a gate of its own; the shell is fully usable behind it,
                        // and the notice is a sheet over it. No-op while
                        // `FreshStartNoticeFlags.enabled` is false.
                        at.bettertrack.app.ui.paranoid.FreshStartNoticeHost(user = user)
                    }
                }
            }
        }
    }
}
