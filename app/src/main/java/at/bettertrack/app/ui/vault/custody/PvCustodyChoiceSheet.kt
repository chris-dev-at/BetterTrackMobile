package at.bettertrack.app.ui.vault.custody

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.custody.PV_DEVICE_PASSWORD_MIN_LENGTH
import at.bettertrack.app.vault.pv.custody.PvCustodyMode

/**
 * The custody-choice step's state.
 *
 * The password pair only exists on the wrapped branch and only when the
 * endpoint has no device password yet — a second vault stored on a phone that
 * already has one simply joins it (§12: one password per endpoint, not per
 * vault), so asking again would imply a second secret that does not exist.
 */
@Immutable
data class PvCustodyChoiceState(
    val selected: PvCustodyMode = PvCustodyMode.WRAPPED,
    val needsNewPassword: Boolean = true,
    val password: String = "",
    val repeated: String = "",
    val working: Boolean = false,
) {
    internal val tooShort: Boolean
        get() = password.isNotEmpty() && password.length < PV_DEVICE_PASSWORD_MIN_LENGTH

    internal val mismatched: Boolean
        get() = repeated.isNotEmpty() && repeated != password

    internal val canSave: Boolean
        get() = when {
            working -> false
            selected == PvCustodyMode.PLAIN -> true
            !needsNewPassword -> true
            else -> password.length >= PV_DEVICE_PASSWORD_MIN_LENGTH && repeated == password
        }
}

/**
 * "How should this phrase be stored?" — the §12 custody choice.
 *
 * ## Plain custody exists, and it is built
 *
 * §2's owner ruling names it directly: *"there is also the risky option of just
 * storing the 12 word seedphrase plain on the end device which you will be
 * warned is way less secure but also possible if you just want it to be
 * encrypted and unreadable for bettertrack."* An earlier mobile review declined
 * to build it; §21 overrules that. This sheet therefore offers both rungs, with
 * wrapped as the default.
 *
 * ## The warning is a sentence, not a banner
 *
 * §21 Q4 fixed the tone for the whole arc — facts stated calmly, "no alarm
 * banners, no bloat". So the plain option carries one sentence saying what it
 * means (anyone who gets into this phone can read the phrase) and what it does
 * not change (BetterTrack still cannot read the vault), inline in the option
 * row where the choice is actually made. No red field, no interstitial, no
 * second confirmation: the user is choosing between two clearly-described
 * things, which is what the friction is for.
 *
 * A standalone step by construction — it takes its state and its callbacks and
 * knows nothing about the creation ceremony that will later host it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvCustodyChoiceSheet(
    state: PvCustodyChoiceState,
    onSelect: (PvCustodyMode) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onSave: () -> Unit,
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
        PvCustodyChoiceContent(
            state = state,
            onSelect = onSelect,
            onPasswordChange = onPasswordChange,
            onRepeatChange = onRepeatChange,
            onSave = onSave,
        )
    }
}

/** The step's body, free of the sheet — see [PvCustodyChoiceSheet]. */
@Composable
internal fun PvCustodyChoiceContent(
    state: PvCustodyChoiceState,
    onSelect: (PvCustodyMode) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRepeatChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        Text(
            text = stringResource(R.string.bt_pv_custody_choice_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = bt.textPrimary,
        )
        Spacer(Modifier.height(14.dp))

        CustodyOption(
            title = stringResource(R.string.bt_pv_custody_choice_wrapped),
            body = stringResource(R.string.bt_pv_custody_choice_wrapped_sub),
            selected = state.selected == PvCustodyMode.WRAPPED,
            enabled = !state.working,
            onClick = { onSelect(PvCustodyMode.WRAPPED) },
        )
        Spacer(Modifier.height(10.dp))
        CustodyOption(
            title = stringResource(R.string.bt_pv_custody_choice_plain),
            body = stringResource(R.string.bt_pv_custody_choice_plain_sub),
            selected = state.selected == PvCustodyMode.PLAIN,
            enabled = !state.working,
            onClick = { onSelect(PvCustodyMode.PLAIN) },
        )

        if (state.selected == PvCustodyMode.WRAPPED && state.needsNewPassword) {
            Spacer(Modifier.height(16.dp))
            BtTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = stringResource(R.string.bt_pv_custody_choice_new_password),
                isPassword = true,
                isError = state.tooShort,
                enabled = !state.working,
                imeAction = ImeAction.Next,
                supportingText = if (state.tooShort) {
                    stringResource(R.string.bt_pv_custody_choice_too_short)
                } else {
                    null
                },
            )
            Spacer(Modifier.height(10.dp))
            BtTextField(
                value = state.repeated,
                onValueChange = onRepeatChange,
                label = stringResource(R.string.bt_pv_custody_choice_repeat_password),
                isPassword = true,
                isError = state.mismatched,
                enabled = !state.working,
                imeAction = ImeAction.Done,
                supportingText = if (state.mismatched) {
                    stringResource(R.string.bt_pv_custody_choice_mismatch)
                } else {
                    null
                },
            )
        }

        Spacer(Modifier.height(18.dp))
        BtPrimaryButton(
            text = stringResource(R.string.bt_pv_custody_choice_save),
            onClick = onSave,
            enabled = state.canSave,
            loading = state.working,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
    }
}

/**
 * One rung of the choice.
 *
 * Selection is a full-width card rather than a radio list because the body text
 * IS the choice — the two options differ by a sentence, not by a label, and a
 * radio row would push that sentence into a supporting-text afterthought.
 */
@Composable
private fun CustodyOption(
    title: String,
    body: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = BtShapes.card,
        color = if (selected) bt.goldWash else bt.surface,
        contentColor = bt.textPrimary,
        border = BorderStroke(1.dp, if (selected) bt.goldEdge else bt.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) bt.goldInk else bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                    )
                }
            }
        }
    }
}
