package at.bettertrack.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.AppLockScreen
import at.bettertrack.app.ui.auth.LoginScreen
import at.bettertrack.app.ui.auth.PasswordChangeRequiredScreen
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.ui.update.UpdateNotifierHost

/**
 * The auth-gated root (spec §4): logged out ⇒ the login screen ONLY; a forced
 * password change ⇒ the finish-on-the-web wall; logged in ⇒ the 4-tab shell.
 * The session (and thus which of these shows) survives process death because it
 * is resolved from the Keystore-backed store at startup.
 *
 * [onStartLogin] launches the Custom Tab, [onOpenUrl] opens a web page in the
 * browser — both need an Activity, so they're passed down from [MainActivity].
 */
@Composable
fun BtRoot(
    onStartLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val auth = AppGraph.authRepository
    val state by auth.authState.collectAsStateWithLifecycle()

    when (val s = state) {
        AuthState.Unknown ->
            // Startup is resolved synchronously; this is just a no-flash backstop.
            Box(Modifier.fillMaxSize().background(BtTheme.colors.bg))

        AuthState.LoggedOut -> {
            val phase by auth.loginPhase.collectAsStateWithLifecycle()
            LoginScreen(
                phase = phase,
                onLogin = onStartLogin,
                onNeedAccount = { onOpenUrl(auth.needAccountUrl()) },
                onForgotPassword = { onOpenUrl(auth.forgotPasswordUrl()) },
            )
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
                        auth.requestLogout()
                    },
                )
            } else {
                BtApp()
            }
        }
    }

    // Dev update notifier (Step V) — an app-level overlay dialog, shown over any
    // auth state when CI has published a newer debug build. Play builds (Task B1)
    // compile the self-update path out entirely, so the host is not rendered.
    if (BuildConfig.SELF_UPDATE_ENABLED) {
        UpdateNotifierHost()
    }
}
