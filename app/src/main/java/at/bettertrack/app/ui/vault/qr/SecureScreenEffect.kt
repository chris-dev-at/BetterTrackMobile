package at.bettertrack.app.ui.vault.qr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Holds `FLAG_SECURE` on the window for as long as the calling composable is in
 * the composition — the platform secure-screen flag §13 requires on **both** the
 * show and the scan screen.
 *
 * What the flag buys: the window is excluded from screenshots and screen
 * recordings, it is blacked out on non-secure external displays, and it does not
 * appear in the recents thumbnail. On a screen that renders a vault's master
 * secret as a machine-readable image, and on the screen that receives it, that
 * is not a nicety — a screenshot of the show screen IS the vault.
 *
 * ## Composing with the app's own FLAG_SECURE use
 *
 * `MainActivity.applyRecentsMasking` already manages this flag: on API 33+ the
 * app lock suppresses the recents snapshot instead (`setRecentsScreenshotEnabled`,
 * which leaves FLAG_SECURE alone), but **below 33 it sets FLAG_SECURE itself**
 * whenever the local app lock is on. Two independent owners of one window flag is
 * the classic way to end up clearing protection someone else still needs, so this
 * effect never clears blindly: it records whether the flag was already set when
 * it arrived and restores exactly that state on dispose. Turning the effect on
 * over an already-secured window is a no-op in both directions.
 *
 * The narrow residual case — the user toggles the app lock *while* one of these
 * screens is open, below API 33 — resolves on the next `applyRecentsMasking`
 * call, which runs from the activity's own state and wins. Nothing is left
 * unprotected while a secret is on screen, which is the property that matters.
 *
 * A no-op when there is no host Activity (Compose previews, tooling).
 */
@Composable
fun SecureScreenEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findHostActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val alreadySecure =
                (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            if (!alreadySecure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (!alreadySecure) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

/**
 * Walk the `ContextWrapper` chain a Compose context sits on to reach its
 * Activity. `LocalContext.current` is frequently a themed wrapper rather than
 * the Activity itself, so a bare `as? Activity` silently misses — and a missed
 * Activity here would mean a screen that thinks it asked for FLAG_SECURE and did
 * not, or a permission state that reads "blocked" because it could not ask.
 */
internal tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}
