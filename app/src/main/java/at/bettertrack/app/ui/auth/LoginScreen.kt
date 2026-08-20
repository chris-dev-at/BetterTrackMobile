package at.bettertrack.app.ui.auth

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.data.auth.LoginError
import at.bettertrack.app.data.auth.LoginPhase

/**
 * The Android host for [BtLoginScreen].
 *
 * The screen itself moved into `:shared/commonMain` in the web port, Phase W1;
 * what stayed behind is exactly the part that is Android — `R.string` lookups,
 * an `R.drawable` painter, Material's gear, and [PreLoginSettingsSheet] (which
 * reads `SharedPreferences` and switches server origins). This function keeps
 * the ORIGINAL signature, so both call sites — `BtRoot` and the storage setup
 * wizard — are untouched and the rendered screen is unchanged.
 *
 * When W2 lands compose-resources the strings and the painter go away and this
 * file shrinks to the sheet; when W6 gives the sheet a multiplatform home, it
 * goes away entirely.
 */
@Composable
fun LoginScreen(
    phase: LoginPhase,
    onLogin: () -> Unit,
    onNeedAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPressWordmark: () -> Unit = {},
    onUseWithoutAccount: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    serverHost: String? = null,
    onOpenServer: (() -> Unit)? = null,
) {
    BtLoginScreen(
        phase = phase,
        strings = androidLoginStrings(),
        brandGlyph = painterResource(R.drawable.splash_bt_glyph),
        settingsIcon = Icons.Outlined.Settings,
        onLogin = onLogin,
        onNeedAccount = onNeedAccount,
        onForgotPassword = onForgotPassword,
        modifier = modifier,
        onLongPressWordmark = onLongPressWordmark,
        onUseWithoutAccount = onUseWithoutAccount,
        onBack = onBack,
        // Formatted here rather than in the shared screen: the format string is
        // a resource, the host is not, and `stringResource(id, arg)` is the same
        // one-line call it always was — it has simply moved one frame outwards.
        serverLine = serverHost?.let { stringResource(R.string.bt_login_server_label, it) },
        settingsSheet = { onDismiss ->
            PreLoginSettingsSheet(
                onDismiss = onDismiss,
                onOpenServer = onOpenServer,
            )
        },
    )
}

/**
 * The screen's copy, resolved from `res/values{,-de}` exactly as the screen used
 * to resolve it inline.
 */
@Composable
private fun androidLoginStrings(): LoginStrings = LoginStrings(
    edition = stringResource(R.string.bt_edition_app),
    tagline = stringResource(R.string.bt_login_tagline),
    loginButton = stringResource(R.string.bt_login_button),
    needAccount = stringResource(R.string.bt_login_need_account),
    forgotPassword = stringResource(R.string.bt_login_forgot_password),
    useWithoutAccount = stringResource(R.string.bt_login_use_without_account),
    back = stringResource(R.string.bt_action_back),
    settingsLabel = stringResource(R.string.bt_dest_settings),
    errorMessage = loginErrorMessages(),
)

/**
 * All seven login failure messages, resolved up front.
 *
 * Reading them eagerly is what turns the lookup into a pure function the shared
 * screen can call: `stringResource` is a composable and cannot be invoked from
 * inside the `when`, which is exactly what the old private `messageFor(error)`
 * did. Seven short strings cost less than carrying a resource id across the
 * module boundary would.
 */
@Composable
private fun loginErrorMessages(): (LoginError) -> String {
    val generic = stringResource(R.string.bt_login_error_generic)
    val network = stringResource(R.string.bt_login_error_network)
    val state = stringResource(R.string.bt_login_error_state)
    val exchange = stringResource(R.string.bt_login_error_exchange)
    val disabled = stringResource(R.string.bt_login_error_disabled)
    val admin = stringResource(R.string.bt_login_error_admin)
    val denied = stringResource(R.string.bt_login_error_denied)
    return { error ->
        when (error) {
            LoginError.GENERIC -> generic
            LoginError.NETWORK -> network
            LoginError.STATE_MISMATCH -> state
            LoginError.EXCHANGE_FAILED -> exchange
            LoginError.ACCOUNT_DISABLED -> disabled
            LoginError.ADMIN_NOT_ALLOWED -> admin
            LoginError.SERVER_DENIED -> denied
        }
    }
}
