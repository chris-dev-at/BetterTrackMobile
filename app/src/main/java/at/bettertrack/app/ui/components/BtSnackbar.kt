package at.bettertrack.app.ui.components

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * One transient-feedback idiom for the whole app (S6 P1-9).
 *
 * The app used to answer "we did the thing" three different ways: a system
 * [android.widget.Toast] (styled by the OS, ignores the dark/gold theme, cannot
 * carry an action, and on Android 13+ is silently dropped when notifications are
 * denied), a per-screen `SnackbarHost` (two screens had their own), and inline
 * text that lingered. Three idioms means the user learns none of them.
 *
 * Now there is one: a [SnackbarHost] hoisted in `AppShell`, reached through
 * [LocalBtSnackbar]. Inline text is reserved for form-field validation, where
 * the message must sit next to the field it is about and stay put while the user
 * fixes it.
 */
@Immutable
data class BtSnackbarMessage(
    /**
     * The message body — a resource, never a raw server string (see P0-4).
     * Null exactly when [pluralRes] is set.
     */
    @StringRes val res: Int? = null,
    /**
     * Counted body, resolved with `getQuantityString`. A snackbar that reports
     * "how many" must decline properly: German picks a different form at one
     * than at zero-or-many, and concatenating a number onto a fixed noun gets
     * that wrong every time. Null exactly when [res] is set.
     */
    @PluralsRes val pluralRes: Int? = null,
    /** The count [pluralRes] declines on, and its first format argument. */
    val quantity: Int = 0,
    /**
     * Positional format arguments. A list rather than a single value because the
     * app's most informative confirmations are two-part — "«Tech» shared with 3
     * friends", "«Tech» is now shared with Family" — and a one-argument payload
     * would have forced those to either drop the fact the user just decided or
     * stay on a system Toast. Neither is acceptable for the one feedback idiom.
     */
    val formatArgs: List<Any> = emptyList(),
    /**
     * Dim second half, appended after an em dash. Only ever the server's own
     * words for a code this build has no copy for.
     */
    val diagnostic: String? = null,
    /**
     * The action's label. Defaults to "Try again" because failures are the
     * common case, but it is a parameter so a confirmation can offer "Undo" —
     * hardcoding the label would have silently deleted every non-retry
     * affordance in the app the moment it moved onto this host.
     */
    @StringRes val actionLabel: Int = R.string.bt_action_retry,
    /**
     * What the action does. When null the snackbar has no action at all, which
     * is the difference between "that failed" and "that failed, here is the way
     * out". Supply it wherever there genuinely is a way out.
     */
    val onAction: (() -> Unit)? = null,
    /** Long duration for failures (they need reading), short for confirmations. */
    val duration: SnackbarDuration = SnackbarDuration.Short,
) {
    init {
        // Enforced here rather than left to the call site: a message with
        // neither body would render blank, and one with both would silently
        // drop whichever the resolver checked second.
        require((res == null) != (pluralRes == null)) {
            "BtSnackbarMessage needs exactly one of res / pluralRes"
        }
    }
}

/** Controller handed down through [LocalBtSnackbar]; screens only ever `show`. */
@Immutable
class BtSnackbarController internal constructor(
    private val emit: (BtSnackbarMessage) -> Unit,
) {
    fun show(message: BtSnackbarMessage) = emit(message)

    /** Confirmation: short, no action. */
    fun show(@StringRes res: Int, vararg formatArgs: Any) =
        emit(BtSnackbarMessage(res = res, formatArgs = formatArgs.toList()))

    /**
     * Counted confirmation ("3 movements tagged"). Callers pass the raw count
     * and let the resource decline it — never build the sentence themselves.
     * [quantity] is the plural's first format argument; [formatArgs] follow it.
     */
    fun showQuantity(@PluralsRes pluralRes: Int, quantity: Int, vararg formatArgs: Any) =
        emit(
            BtSnackbarMessage(
                pluralRes = pluralRes,
                quantity = quantity,
                formatArgs = formatArgs.toList(),
            ),
        )

    /**
     * Confirmation that can be taken back ("Archived" · Undo). Kept distinct
     * from [showError] so the label is never wrong: an undoable success and a
     * failed action want opposite words.
     */
    fun showUndoable(@StringRes res: Int, @StringRes undoLabel: Int, onUndo: () -> Unit) =
        emit(BtSnackbarMessage(res = res, actionLabel = undoLabel, onAction = onUndo))

    /**
     * Failure: the app-owned sentence for the error, a Retry action when the
     * caller can offer one, and the long duration because failures need reading.
     */
    fun showError(message: BtMessage, onRetry: (() -> Unit)? = null) = emit(
        BtSnackbarMessage(
            res = message.res,
            formatArgs = listOfNotNull(message.formatArg),
            diagnostic = message.diagnostic,
            actionLabel = R.string.bt_action_retry,
            onAction = onRetry,
            duration = SnackbarDuration.Long,
        ),
    )
}

/**
 * No-op default. A composable that shows feedback outside the shell (previews,
 * the gallery, a screen hosted in a test) should not crash — it simply has
 * nowhere to show it.
 */
val LocalBtSnackbar = compositionLocalOf { BtSnackbarController {} }

/**
 * Wires a [BtSnackbarController] to a [SnackbarHostState]. Call once, in the
 * shell; pass [host] to the `Scaffold`'s `snackbarHost` and provide [controller]
 * over the content.
 */
class BtSnackbarState internal constructor(
    val hostState: SnackbarHostState,
    val controller: BtSnackbarController,
)

// The snackbar body is resolved when the message is SHOWN, inside a coroutine —
// not during composition — so `stringResource` is unavailable here by
// construction. The context is a remember key, so a locale change rebuilds the
// controller and the next message resolves in the new language. This is the only
// place in the app that legitimately needs the captured-context form.
@Suppress("LocalContextGetResourceValueCall")
@Composable
fun rememberBtSnackbarState(): BtSnackbarState {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(hostState, scope, context) {
        val controller = BtSnackbarController { message ->
            scope.launch {
                val body = buildString {
                    append(
                        when {
                            // The count is the plural's first argument by
                            // convention; anything else follows it.
                            message.pluralRes != null -> context.resources.getQuantityString(
                                message.pluralRes,
                                message.quantity,
                                *(listOf<Any>(message.quantity) + message.formatArgs).toTypedArray(),
                            )
                            message.formatArgs.isNotEmpty() ->
                                context.getString(message.res!!, *message.formatArgs.toTypedArray())
                            else -> context.getString(message.res!!)
                        },
                    )
                    // The diagnostic is appended rather than given its own line:
                    // a snackbar is one gulp of text, and a second line would
                    // read as a second, competing message.
                    message.diagnostic?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
                }
                // Replace whatever is showing: the newest outcome is the one the
                // user just caused, so it should never queue behind a stale one.
                hostState.currentSnackbarData?.dismiss()
                val result = hostState.showSnackbar(
                    message = body,
                    actionLabel = message.onAction?.let { context.getString(message.actionLabel) },
                    withDismissAction = false,
                    duration = message.duration,
                )
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
        BtSnackbarState(hostState, controller)
    }
}

/**
 * The app's snackbar: the card surface, the app's own corner radius, and gold —
 * the single accent — on the action. A system [android.widget.Toast] could match
 * none of that, which is half the reason it had to go.
 */
@Composable
fun BtSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.medium,
            // §2 A1: an overlay floats above the content, so it takes the sheet/
            // dialog level rather than the card level it used to share. In dark
            // that is a real ΔL* 3.4 lift off the cards it covers; in light the
            // two levels are both white and the shadow does the separating.
            containerColor = bt.surfaceHigh,
            contentColor = bt.textPrimary,
            actionContentColor = bt.goldInk,
            dismissActionContentColor = bt.textSecondary,
        )
    }
}

/**
 * Fire-and-forget bridge for screens that already model feedback as a nullable
 * state field: show it when it appears, then hand the consume callback back.
 * Keeps the migration off `Toast` to one composable per screen.
 */
@Composable
fun BtSnackbarEffect(message: BtSnackbarMessage?, onConsumed: () -> Unit) {
    val snackbar = LocalBtSnackbar.current
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.show(message)
            onConsumed()
        }
    }
}

/** Same bridge, for the common "one string resource, no action" confirmation. */
@Composable
fun BtSnackbarEffect(@StringRes res: Int?, onConsumed: () -> Unit) {
    val snackbar = LocalBtSnackbar.current
    LaunchedEffect(res) {
        if (res != null) {
            snackbar.show(res)
            onConsumed()
        }
    }
}

/**
 * Bridge for the failure case: show the app-owned copy for [error] with a Retry
 * action, then hand the consume callback back.
 */
@Composable
fun BtSnackbarErrorEffect(
    error: BtMessage?,
    onRetry: (() -> Unit)? = null,
    onConsumed: () -> Unit,
) {
    val snackbar = LocalBtSnackbar.current
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showError(error, onRetry)
            onConsumed()
        }
    }
}
