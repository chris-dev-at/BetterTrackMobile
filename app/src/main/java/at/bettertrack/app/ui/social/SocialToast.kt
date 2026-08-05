package at.bettertrack.app.ui.social

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.resolveWithDiagnostic

/**
 * A toast payload the Social ViewModels can emit without holding a Context.
 *
 * The in-app language switch wraps each Activity's resources per-locale
 * (see [at.bettertrack.app.data.i18n.LocaleManager]); the Application context is
 * NOT re-wrapped, so resolving strings from a ViewModel would ignore that choice.
 * Emitting a resource reference and resolving it in the UI (against the
 * locale-wrapped activity resources) keeps VM-sourced toasts correctly localized.
 */
sealed interface SocialToast {
    /**
     * A write that failed.
     *
     * It carries a [BtMessage] — the app's OWN sentence for the server's error
     * code — where it used to carry the server's English prose (S6 P0-4). The
     * server's words survive only as the dim diagnostic half of that message,
     * and only for a code this build has no copy for.
     *
     * [onRetry] is the way out: when the failed call can simply be made again,
     * the ViewModel hands over the lambda that re-issues it and the snackbar
     * grows a "Try again" action. It lives on the payload rather than at the
     * render site because only the ViewModel still knows WHICH call failed.
     */
    data class Failure(val message: BtMessage, val onRetry: (() -> Unit)? = null) : SocialToast

    /** A plain string resource with optional positional format args. */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : SocialToast

    /** A quantity string; [count] both selects the plural and is a format arg (via [args]). */
    data class Quantity(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : SocialToast
}

/** Resolve against the current (locale-wrapped) composition resources. */
@Composable
fun SocialToast.resolve(): String = when (this) {
    is SocialToast.Failure -> message.resolveWithDiagnostic()
    is SocialToast.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
    is SocialToast.Quantity -> pluralStringResource(id, count, *args.toTypedArray())
}

/**
 * The single place a [SocialToast] becomes feedback (S6 P1-9).
 *
 * Everything goes through the app-level snackbar hoisted in `AppShell`: one
 * idiom, themed like the rest of the app, and — unlike a system toast — able to
 * carry an action. Failures get the long duration (a failure has to be read) and
 * a "Try again" whenever the ViewModel handed one over; confirmations are short
 * and actionless.
 *
 * There is deliberately no escape hatch. The two-part confirmations ("«Tech»
 * shared with 3 friends") briefly kept a system Toast because the snackbar
 * payload carried a single argument and no plural; it now carries an argument
 * LIST and a plurals id, so every social toast fits and `android.widget.Toast`
 * is gone from this package entirely.
 */
@Composable
fun SocialToastEffect(toast: SocialToast?, onConsumed: () -> Unit) {
    val snackbar = LocalBtSnackbar.current

    LaunchedEffect(toast) {
        when (val pending = toast) {
            null -> return@LaunchedEffect
            is SocialToast.Failure -> snackbar.showError(pending.message, pending.onRetry)
            is SocialToast.Res -> snackbar.show(pending.id, *pending.args.toTypedArray())
            is SocialToast.Quantity ->
                snackbar.showQuantity(pending.id, pending.count, *pending.args.toTypedArray())
        }
        onConsumed()
    }
}
