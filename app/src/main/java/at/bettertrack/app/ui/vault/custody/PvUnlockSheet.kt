package at.bettertrack.app.ui.vault.custody

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags

/**
 * Everything the §12 unlock sheet renders, in one value.
 *
 * A data class rather than composable-local state because every branch of it is
 * a state the design owes an answer for — working, wrong password, counting
 * down, confirming a reset — and a screenshot of each has to be producible
 * without reaching an actual lockout on a real phone.
 */
@Immutable
data class PvUnlockSheetState(
    val password: String = "",
    val working: Boolean = false,
    val wrongPassword: Boolean = false,
    /** > 0 while the §12 ladder has an open window; drives the live countdown. */
    val lockoutRemainingMillis: Long = 0L,
    /** The "Forgot the password?" destination, shown in place of the form. */
    val confirmingReset: Boolean = false,
)

/**
 * The device-password prompt for paranoid vaults (§12).
 *
 * ## Why a bottom sheet and not a screen
 *
 * The v1 vault gate is a full screen because it gates the whole app: no key, no
 * content, nothing else to look at. This prompt is narrower — it opens ONE
 * endpoint's stored phrases, from wherever the user was — and the app's
 * established language for "a transient thing that needs an answer" is a bottom
 * sheet (owner order 2026-08-16). Same chrome as `BtActionSheet`: `surfaceHigh`
 * container, drag handle, nav-bar insets. The `ime` inset joins the union here,
 * unlike in the action sheet, because this one hosts a text field.
 *
 * ## The lockout is shown, never hidden
 *
 * While the ladder has an open window the field and the button go quiet and the
 * sheet says how long is left, counting down. A prompt that silently rejects
 * during the wait teaches the user that the password is wrong, which is the one
 * thing it is not.
 *
 * ## Forgetting the password is not losing anything
 *
 * "Forgot the password?" leads to a keystore reset, and the confirm copy is one
 * sentence saying exactly that: the phrases stored on this phone go, no vault
 * data does, the words come back by typing or by the §13 QR. §16's contrast is
 * the whole point — a forgotten *device password* loses nothing; only a lost
 * *phrase* is unrecoverable.
 *
 * Flag-gated at the entry point; [PvUnlockSheetContent] is the pure body, so a
 * gallery or preview can render every state without flipping the program flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvUnlockSheet(
    state: PvUnlockSheetState,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onResetConfirmed: () -> Unit,
    onResetDismissed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!ParanoidVaultsFlags.enabled) return
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        PvUnlockSheetContent(
            state = state,
            onPasswordChange = onPasswordChange,
            onSubmit = onSubmit,
            onForgotPassword = onForgotPassword,
            onResetConfirmed = onResetConfirmed,
            onResetDismissed = onResetDismissed,
        )
    }
}

/** The sheet's body, free of the sheet — see [PvUnlockSheet]. */
@Composable
internal fun PvUnlockSheetContent(
    state: PvUnlockSheetState,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
    onResetConfirmed: () -> Unit,
    onResetDismissed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
            // A ModalBottomSheet ships no content insets. `ime` is in the union
            // because this sheet has a field: without it the keyboard covers the
            // very control the sheet exists for.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        if (state.confirmingReset) {
            ResetConfirm(onResetConfirmed = onResetConfirmed, onResetDismissed = onResetDismissed)
        } else {
            UnlockForm(
                state = state,
                onPasswordChange = onPasswordChange,
                onSubmit = onSubmit,
                onForgotPassword = onForgotPassword,
            )
        }
    }
}

@Composable
private fun UnlockForm(
    state: PvUnlockSheetState,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val bt = BtTheme.colors
    val lockedOut = state.lockoutRemainingMillis > 0L

    Text(
        text = stringResource(R.string.bt_pv_custody_unlock_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = bt.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.bt_pv_custody_unlock_body),
        style = MaterialTheme.typography.bodyMedium,
        color = bt.textSecondary,
    )
    Spacer(Modifier.height(18.dp))

    BtTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.bt_pv_custody_password_label),
        isPassword = true,
        isError = state.wrongPassword || lockedOut,
        enabled = !state.working && !lockedOut,
        imeAction = ImeAction.Done,
    )

    // A reserved status line, so the button never jumps when the error or the
    // countdown appears — the same trick `VaultUnlockGate` uses.
    Box(Modifier.height(34.dp).padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
        when {
            lockedOut -> Text(
                text = stringResource(
                    R.string.bt_pv_custody_lockout,
                    pvFormatCountdown(state.lockoutRemainingMillis),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.loss,
            )

            state.wrongPassword -> Text(
                text = stringResource(R.string.bt_pv_custody_unlock_wrong),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.loss,
            )
        }
    }

    BtPrimaryButton(
        text = if (state.working) {
            stringResource(R.string.bt_pv_custody_unlock_working)
        } else {
            stringResource(R.string.bt_pv_custody_unlock_action)
        },
        onClick = onSubmit,
        enabled = state.password.isNotEmpty() && !state.working && !lockedOut,
        loading = state.working,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    )
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onForgotPassword, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.bt_pv_custody_forgot),
            style = MaterialTheme.typography.labelLarge,
            color = bt.textSecondary,
        )
    }
}

@Composable
private fun ResetConfirm(onResetConfirmed: () -> Unit, onResetDismissed: () -> Unit) {
    val bt = BtTheme.colors
    Text(
        text = stringResource(R.string.bt_pv_custody_reset_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = bt.textPrimary,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        // One sentence, calm and factual (§21 Q4 tone): what goes, what does
        // not, and how the phrase comes back.
        text = stringResource(R.string.bt_pv_custody_reset_body),
        style = MaterialTheme.typography.bodyMedium,
        color = bt.textSecondary,
    )
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        BtSecondaryButton(
            text = stringResource(R.string.bt_action_cancel),
            onClick = onResetDismissed,
            modifier = Modifier.weight(1f).height(50.dp),
        )
        BtPrimaryButton(
            text = stringResource(R.string.bt_pv_custody_reset_confirm),
            onClick = onResetConfirmed,
            modifier = Modifier.weight(1.4f).height(50.dp),
        )
    }
}

/** mm:ss for a remaining-millis countdown (29 000 → "0:29"), rounded up so it hits 0 exactly. */
internal fun pvFormatCountdown(millis: Long): String {
    val totalSeconds = ((millis + 999) / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
