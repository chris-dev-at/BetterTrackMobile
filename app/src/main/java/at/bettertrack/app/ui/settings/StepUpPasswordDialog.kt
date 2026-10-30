package at.bettertrack.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.account.StepUpPromptReason
import at.bettertrack.app.data.account.StepUpPrompter
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.paranoid.openBtWebApp
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.CompletableDeferred

/** Where the web app manages two-factor settings (the Control Center's sign-in panel). */
internal const val TWO_FACTOR_WEB_PATH: String = "/control/sign-in"

/**
 * Compose side of [StepUpPrompter] (platform #2101): one pending request at a
 * time, answered by [submit] or [cancel]. The password is handed straight to the
 * waiting flow and is not kept here.
 */
@Stable
internal class StepUpPromptController : StepUpPrompter {

    internal class Pending(
        val reason: StepUpPromptReason,
        val answer: CompletableDeferred<String?>,
    ) {
        /** True between Confirm and the server's answer: the prompt is locked. */
        var submitting by mutableStateOf(false)
    }

    var pending by mutableStateOf<Pending?>(null)
        private set

    override suspend fun requestPassword(reason: StepUpPromptReason): String? {
        val request = Pending(reason, CompletableDeferred())
        pending = request
        return request.answer.await()
    }

    fun submit(password: String) {
        val p = pending ?: return
        if (p.submitting || password.isEmpty()) return
        p.submitting = true
        p.answer.complete(password)
    }

    fun cancel() {
        val p = pending ?: return
        if (p.submitting) return
        pending = null
        p.answer.complete(null)
    }

    override fun finished() {
        pending = null
    }
}

/** The masked password prompt; shown while [controller] has a pending request. */
@Composable
internal fun StepUpPasswordDialog(controller: StepUpPromptController) {
    val request = controller.pending ?: return
    val bt = BtTheme.colors
    val context = LocalContext.current
    // Keyed by the request: every prompt (including the one after a wrong
    // password) starts empty, and the typed value leaves with the dialog.
    var password by remember(request) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { controller.cancel() },
        containerColor = bt.surfaceHigh,
        titleContentColor = bt.textPrimary,
        textContentColor = bt.textSecondary,
        title = { Text(stringResource(R.string.bt_2fa_stepup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (request.reason == StepUpPromptReason.PASSWORD_REQUIRED) R.string.bt_2fa_stepup_password_required
                        else R.string.bt_2fa_stepup_message,
                    ),
                )
                BtTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.bt_2fa_stepup_password),
                    isPassword = true,
                    isError = request.reason == StepUpPromptReason.WRONG_PASSWORD,
                    enabled = !request.submitting,
                    imeAction = ImeAction.Done,
                )
                if (request.reason == StepUpPromptReason.WRONG_PASSWORD) {
                    Text(
                        stringResource(R.string.bt_2fa_stepup_wrong),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.loss,
                    )
                }
                Text(
                    stringResource(R.string.bt_2fa_stepup_no_password_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                TextButton(
                    enabled = !request.submitting,
                    onClick = {
                        controller.cancel()
                        openBtWebApp(context, TWO_FACTOR_WEB_PATH)
                    },
                ) { Text(stringResource(R.string.bt_2fa_stepup_web_link), color = bt.goldInk) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !request.submitting,
                onClick = { controller.submit(password) },
            ) { Text(stringResource(R.string.bt_2fa_stepup_confirm), color = bt.goldInk) }
        },
        dismissButton = {
            TextButton(enabled = !request.submitting, onClick = { controller.cancel() }) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}
