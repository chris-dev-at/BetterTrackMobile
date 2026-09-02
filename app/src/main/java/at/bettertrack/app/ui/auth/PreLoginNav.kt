package at.bettertrack.app.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The back stack of the surfaces that exist BEFORE there is a session.
 *
 * ## Why this exists at all
 *
 * Logged out there is no `NavHost` — the login screen is rendered outside the
 * shell's graph (see [at.bettertrack.app.ui.shell.BtRoot]) — so the pre-login
 * surfaces were wired as three independent booleans in three different
 * composables: `showServer` in the auth gate, `showSettings` inside
 * [LoginScreen], `diagnostics` inside [PreLoginSettingsSheet]. Nothing owned the
 * relationship between them, and the owner's 2026-09-01 device pass found both
 * halves of what that costs (report #4):
 *
 *  - **System back on the Server screen exited the app.** No handler existed for
 *    a boolean, so back fell through to the activity and finished the task. The
 *    process survived, the boolean survived with it, and relaunching landed
 *    straight back on the Server screen — a route that had never been popped.
 *  - **The in-app ← from the Server screen skipped the settings sheet.**
 *    `showServer = false` re-composed [LoginScreen] from scratch, and
 *    `showSettings` was `remember`ed inside it, so the sheet the user had opened
 *    the Server screen FROM was gone. Back went two levels in one press.
 *
 * A stack fixes both at once, because both are the same missing fact: *what is
 * underneath this?* The stack is the answer, [preLoginBack] pops exactly one of
 * it, and the rule is one pure function rather than three booleans that have to
 * be kept in agreement by hand.
 *
 * ## Not saved across process death, on purpose
 *
 * Plain `remember`, not `rememberSaveable`. Logged out there is nothing to lose:
 * every step here is either a device preference already persisted elsewhere or a
 * read-only list, and the only two events that rebuild the activity — a language
 * pick and process death — should both land on the login screen rather than
 * restoring a settings sheet nobody asked for. Losing position pre-login can
 * only ever show LESS than the user had; it can never show the wrong thing.
 */
internal enum class PreLoginStep {
    /** The login screen itself. The floor: back here belongs to the system. */
    Login,

    /** [PreLoginSettingsSheet], over the login screen. */
    Settings,

    /** [AuthDiagnosticsScreen], inside that sheet. */
    Diagnostics,

    /**
     * [at.bettertrack.app.ui.settings.ServerScreen], which REPLACES the login
     * screen rather than covering it — so the sheet is not composed while this
     * is on top, and re-appears when it is popped.
     */
    Server,
}

/** The stack every pre-login surface starts from. */
internal val PRE_LOGIN_FLOOR: List<PreLoginStep> = listOf(PreLoginStep.Login)

/**
 * Push [step], unless it is already on top (a double tap is one open).
 *
 * [PreLoginStep.Login] is not a step you push — it is the floor — so opening it
 * means going back to it.
 */
internal fun preLoginOpen(
    stack: List<PreLoginStep>,
    step: PreLoginStep,
): List<PreLoginStep> = when {
    step == PreLoginStep.Login -> PRE_LOGIN_FLOOR
    stack.lastOrNull() == step -> stack
    else -> stack + step
}

/**
 * Pop **exactly one** level, or `null` at the floor.
 *
 * `null` is the whole contract with the caller: at the login screen back is not
 * the app's to answer, and a handler that swallowed it there would replace
 * "exits the app" with "does nothing", which is the same defect wearing the
 * other shoe.
 */
internal fun preLoginBack(stack: List<PreLoginStep>): List<PreLoginStep>? =
    if (stack.size <= 1) null else stack.dropLast(1)

/**
 * The sheet layer dismissed itself — a pull-down, a scrim tap, or back at the
 * sheet's own level.
 *
 * Everything the sheet was holding goes with it, which is one level from
 * [PreLoginStep.Settings] and two from [PreLoginStep.Diagnostics]. That is not
 * an exception to "one back is one level": a pull-down is *close this sheet*,
 * not *back*, and it means the same thing everywhere else in the app.
 */
internal fun preLoginSheetDismissed(stack: List<PreLoginStep>): List<PreLoginStep> =
    stack.filterNot { it == PreLoginStep.Settings || it == PreLoginStep.Diagnostics }
        .ifEmpty { PRE_LOGIN_FLOOR }

/** Whether [PreLoginSettingsSheet] is on screen at this step. */
internal fun preLoginSheetOpen(step: PreLoginStep): Boolean =
    step == PreLoginStep.Settings || step == PreLoginStep.Diagnostics

/**
 * The stack, as a holder the pre-login composables share.
 *
 * Held by whoever owns the swap between the login screen and the Server screen
 * (the auth gate), because that swap is the one boundary a `remember` inside
 * [LoginScreen] cannot survive.
 */
@Stable
internal class PreLoginNav(initial: List<PreLoginStep> = PRE_LOGIN_FLOOR) {
    var stack: List<PreLoginStep> by mutableStateOf(initial)
        private set

    /** The surface on top. */
    val current: PreLoginStep get() = stack.last()

    fun open(step: PreLoginStep) {
        stack = preLoginOpen(stack, step)
    }

    /** @return true when a level was popped; false when the floor owns back. */
    fun back(): Boolean {
        stack = preLoginBack(stack) ?: return false
        return true
    }

    /** The sheet layer took itself off screen. */
    fun sheetDismissed() {
        stack = preLoginSheetDismissed(stack)
    }
}

@Composable
internal fun rememberPreLoginNav(): PreLoginNav = remember { PreLoginNav() }
