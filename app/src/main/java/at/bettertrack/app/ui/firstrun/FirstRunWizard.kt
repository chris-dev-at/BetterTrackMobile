package at.bettertrack.app.ui.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.auth.AuthState
import at.bettertrack.app.data.auth.SessionUser
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.applock.AppLockSetupScreen
import at.bettertrack.app.ui.components.BtSnackbarHost
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtSnackbarState
import at.bettertrack.app.ui.paranoid.ParanoidGate
import at.bettertrack.app.ui.settings.AccountPinScreen
import at.bettertrack.app.ui.settings.ActiveSessionsScreen
import at.bettertrack.app.ui.settings.LanguageScreen
import at.bettertrack.app.ui.settings.PasskeysScreen
import at.bettertrack.app.ui.settings.PublicProfileScreen
import at.bettertrack.app.ui.settings.SecurityScreen
import at.bettertrack.app.ui.settings.TrustedDevicesScreen
import at.bettertrack.app.ui.settings.TwoFactorScreen
import at.bettertrack.app.ui.storage.WizardScaffold
import at.bettertrack.app.ui.tax.TaxSettingsScreen
import at.bettertrack.app.ui.tax.TaxYearDetailScreen
import at.bettertrack.app.ui.tax.TaxYearsScreen
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.launch

/**
 * The native account first-run wizard (§6.12) — the surface the app never had.
 *
 * ## Why it exists
 *
 * The web's `FirstRunGate` exempts `/oauth/authorize` from its redirect to
 * `/welcome`, on purpose: bouncing a brand-new user into setup in the middle of
 * an authorization-code flow would break the integration rather than delay it.
 * The consequence is that an account created through THIS app's Custom-Tab login
 * is never shown the web wizard at all — it lands in the app with
 * `firstRunCompletedAt === null` and, until now, nothing on the phone could
 * either run the setup or complete it. This screen is that missing half.
 *
 * ## What it is, structurally
 *
 * A **sequencing shell over the app's existing editors**. It owns the frame — the
 * question, the progress rail, Continue / Back / "Do this later" — and each step
 * owns only its summary and its hand-off. Tapping the hand-off pushes the real
 * screen (`SecurityScreen`, `LanguageScreen`, `TaxSettingsScreen`,
 * `PublicProfileScreen`, …) onto a small local back stack and shows it
 * full-screen; nothing about those screens is duplicated here.
 *
 * The local stack exists because the wizard renders ABOVE the tab shell (see
 * `BtRoot`), so the app's `NavHost` and sheet graph are not composed while it is
 * on screen. [FirstRunEditor] enumerates the whole reachable sub-tree — including
 * Security's five sub-screens and Tax's two — so that no hosted screen has a row
 * that silently does nothing.
 *
 * ## It is an offer, never a trap
 *
 * Every exit works: system back pops an editor, then walks back a step, then
 * leaves; "Do this later" leaves from any non-terminal step; and when the final
 * completion call fails, the failure state offers to leave as well. Leaving
 * records a **local, account-scoped dismissal** ([at.bettertrack.app.data.prefs.FirstRunStore])
 * and deliberately does NOT write the server flag — the account is still pending,
 * which is what keeps Settings' "Finish setup" row visible so the user can come
 * back. Only the terminal step's action calls `POST /auth/first-run/complete`.
 *
 * (That is a deliberate divergence from the web, whose "Do this later" also
 * stamps the server. The web has no equivalent of the Settings escape row, so for
 * it, dismissing and finishing genuinely are the same act; here they are not.)
 */
@Composable
fun FirstRunWizard() {
    val bt = BtTheme.colors
    val snackbar = rememberBtSnackbarState()
    val auth = AppGraph.authRepository
    val authState by auth.authState.collectAsStateWithLifecycle()
    val user: SessionUser? = when (val s = authState) {
        is AuthState.LoggedIn -> s.user
        is AuthState.PasswordChangeRequired -> s.user
        else -> null
    }

    val steps = FIRST_RUN_STEPS
    var index by remember { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(steps.indices)]

    /** What each step reported when Continue was pressed — the Done summary. */
    val recorded = remember { mutableStateMapOf<FirstRunStepId, FirstRunStepStatus>() }

    /**
     * The CURRENT step's live report. Reset per step (`remember(index)`) so a
     * status never leaks forward: a step that never reports counts as walked
     * past, which is the honest default.
     */
    var reported by remember(index) { mutableStateOf(FirstRunStepStatus.SKIPPED) }
    var stepBusy by remember(index) { mutableStateOf(false) }

    /** The local editor stack — see [FirstRunEditor]. */
    val editors = remember { mutableStateListOf<FirstRunEditor>() }

    /**
     * Bumped whenever an editor closes. Steps key their reads on it, so returning
     * from `SecurityScreen` re-asks the server what is now switched on instead of
     * showing the answer from before the visit.
     */
    var revision by remember { mutableIntStateOf(0) }

    var completing by remember { mutableStateOf(false) }
    var completeError by remember { mutableStateOf<BtMessage?>(null) }
    var completeSignedOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Leave without claiming anything was finished. */
    fun leave() = AppGraph.firstRunStore.dismiss(user?.id)

    /**
     * Finish: tell the server, and let the gate do the navigating. On success the
     * session user comes back DONE and `BtRoot` stops selecting this screen —
     * there is no second source of truth to keep in step.
     */
    fun finish() {
        if (completing) return
        completing = true
        completeError = null
        completeSignedOut = false
        scope.launch {
            when (val r = auth.completeFirstRun()) {
                is BtResult.Ok -> Unit // the gate swaps this screen out
                is BtResult.Err -> {
                    completeSignedOut = r.error.isAuthHardFailure
                    completeError = r.error.asMessage()
                }
            }
            completing = false
        }
    }

    fun popEditor() {
        if (editors.isNotEmpty()) {
            editors.removeAt(editors.lastIndex)
            revision++
        }
    }

    BackHandler {
        when {
            editors.isNotEmpty() -> popEditor()
            index > 0 -> index -= 1
            else -> leave()
        }
    }

    CompositionLocalProvider(LocalBtSnackbar provides snackbar.controller) {
        Box(Modifier.fillMaxSize().background(bt.bg)) {
            val top = editors.lastOrNull()
            if (top != null) {
                FirstRunEditorHost(
                    editor = top,
                    onPop = ::popEditor,
                    onPush = { editors.add(it) },
                )
            } else {
                WizardScaffold(
                    stepIndex = index,
                    stepCount = steps.size,
                    title = stringResource(step.title),
                    subtitle = stringResource(
                        R.string.bt_firstrun_step_of,
                        index + 1,
                        steps.size,
                        stringResource(step.label),
                    ),
                    onBack = if (index > 0) ({ index -= 1 }) else null,
                    primaryText = stringResource(
                        if (step.terminal) R.string.bt_firstrun_finish else R.string.bt_firstrun_continue,
                    ),
                    primaryEnabled = !stepBusy && !completing,
                    primaryLoading = completing,
                    onPrimary = {
                        recorded[step.id] = reported
                        if (step.terminal) {
                            finish()
                        } else {
                            index = (index + 1).coerceAtMost(steps.lastIndex)
                        }
                    },
                    secondary = {
                        // Always available on a non-terminal step, and on the
                        // terminal one only once finishing has actually failed —
                        // the way out is never hidden, but it is not offered as a
                        // competing choice next to "Finish" either.
                        if (!step.terminal || completeError != null) {
                            TextButton(onClick = ::leave, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.bt_firstrun_later),
                                    color = bt.textSecondary,
                                )
                            }
                        }
                    },
                ) {
                    FirstRunStepBody(
                        step = step,
                        user = user,
                        revision = revision,
                        completing = completing,
                        completeError = completeError,
                        completeSignedOut = completeSignedOut,
                        recorded = recorded,
                        onReport = { status, busy ->
                            reported = status
                            stepBusy = busy
                        },
                        onOpenEditor = { editors.add(it) },
                        onRetryFinish = ::finish,
                    )
                }
            }

            BtSnackbarHost(
                hostState = snackbar.hostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}

/**
 * Renders one entry of the local editor stack.
 *
 * Each branch composes exactly what `AppShell`'s sheet graph composes for the
 * matching route, with `onBack` wired to [onPop] and every onward hand-off wired
 * to [onPush]. Keeping the two lists identical is what makes "the wizard reuses
 * the app's editors" true rather than approximately true.
 */
@Composable
private fun FirstRunEditorHost(
    editor: FirstRunEditor,
    onPop: () -> Unit,
    onPush: (FirstRunEditor) -> Unit,
) {
    when (editor) {
        FirstRunEditor.Security -> SecurityScreen(
            onBack = onPop,
            onSetupPin = { onPush(FirstRunEditor.AppLockSetup(change = false)) },
            onChangePin = { onPush(FirstRunEditor.AppLockSetup(change = true)) },
            onOpenTwoFactor = { onPush(FirstRunEditor.TwoFactor) },
            onOpenSessions = { onPush(FirstRunEditor.Sessions) },
            onOpenAccountPin = { onPush(FirstRunEditor.AccountPin) },
            onOpenPasskeys = { onPush(FirstRunEditor.Passkeys) },
            onOpenTrustedDevices = { onPush(FirstRunEditor.TrustedDevices) },
        )

        is FirstRunEditor.AppLockSetup ->
            AppLockSetupScreen(change = editor.change, onDone = onPop, onBack = onPop)

        FirstRunEditor.AccountPin -> AccountPinScreen(onBack = onPop)
        FirstRunEditor.TwoFactor -> TwoFactorScreen(onBack = onPop)
        FirstRunEditor.Sessions -> ActiveSessionsScreen(onBack = onPop)
        FirstRunEditor.Passkeys -> PasskeysScreen(onBack = onPop)
        FirstRunEditor.TrustedDevices -> TrustedDevicesScreen(onBack = onPop)
        FirstRunEditor.Language -> LanguageScreen(onBack = onPop)
        FirstRunEditor.PublicProfile -> PublicProfileScreen(onBack = onPop)

        // The tax sub-tree keeps its paranoid gate: a client-encrypted account has
        // no server-side tax figures to show, and the wizard is not the place to
        // discover that through a wall of 403s.
        FirstRunEditor.Tax -> ParanoidGate(onBack = onPop) {
            TaxSettingsScreen(
                onBack = onPop,
                onOpenTaxReports = { portfolioId -> onPush(FirstRunEditor.TaxYears(portfolioId)) },
            )
        }

        is FirstRunEditor.TaxYears -> ParanoidGate(onBack = onPop) {
            TaxYearsScreen(
                portfolioId = editor.portfolioId,
                onBack = onPop,
                onOpenYear = { year -> onPush(FirstRunEditor.TaxYear(editor.portfolioId, year)) },
            )
        }

        is FirstRunEditor.TaxYear -> ParanoidGate(onBack = onPop) {
            TaxYearDetailScreen(
                portfolioId = editor.portfolioId,
                year = editor.year,
                onBack = onPop,
            )
        }
    }
}
