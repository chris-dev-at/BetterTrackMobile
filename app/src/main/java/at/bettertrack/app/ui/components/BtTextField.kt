package at.bettertrack.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme

/**
 * Brand colors for an [OutlinedTextField] (gold focus, red error) — the app-wide
 * field look.
 *
 * The focus RING and the caret are graphical marks, so they carry the brand
 * `gold` in both modes (owner order 2026-08-07). The floating LABEL is a word
 * and keeps `goldInk`, which is the same yellow one step down the brand ray.
 */
@Composable
fun btFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    disabledBorderColor = BtTheme.colors.border,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.goldInk,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    disabledLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    disabledTextColor = BtTheme.colors.textMuted,
    cursorColor = BtTheme.colors.gold,
)

/**
 * How long a DESCRIPTION may be, in characters — the contract's cap on the wire
 * field `note`.
 *
 * Enforced at every entry point (the transaction form and all three cash sheets,
 * which now share [BtDescriptionField]) and SHOWN next to the field, because a
 * limit the app silently swallows keystrokes against is indistinguishable from a
 * broken keyboard. This is the only place the app CAN state it: the server
 * states it as a 422, which arrives after the user has already lost the words.
 *
 * The four call sites clamped at 900 before — a margin with no recorded reason.
 * The real ceiling is the honest number to enforce and to print.
 */
const val BT_DESCRIPTION_MAX_CHARS: Int = 1000

/**
 * The app's one free-text field: a transaction's or cash movement's DESCRIPTION.
 *
 * **Owner 2026-08-17: *"mache notiz wichtiger für transaktionen und nenne es
 * nicht notiz sondern etwas wichtigeres wie beschreibung"*.** It was a "Notiz"
 * in a two-line box at the bottom of the form — the shape and the position an
 * app gives to something it does not expect you to use. It is a Beschreibung
 * now, three lines, in the middle of the flow, with the cap on show.
 *
 * One component for all four call sites so the label, the height, the clamp and
 * the counter cannot drift apart between the trade form and the cash sheets —
 * they are the same field on the same record type, and the owner's word for the
 * whole batch was *einheitlich*.
 *
 * Still OPTIONAL: the label says so, nothing validates it, and an empty value
 * submits as null. The counter appears only once there is something to count —
 * an empty field showing "0/1000" is a scold, not a help.
 *
 * [colors] is a parameter because the sheets and the full-screen form carry
 * different field palettes (a sheet sits on `surfaceHigh`).
 */
@Composable
fun BtDescriptionField(
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: TextFieldColors = btFieldColors(),
) {
    val bt = BtTheme.colors
    val full = value.length >= BT_DESCRIPTION_MAX_CHARS
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.take(BT_DESCRIPTION_MAX_CHARS)) },
        label = { Text(stringResource(R.string.bt_txform_description)) },
        enabled = enabled,
        minLines = 3,
        maxLines = 5,
        supportingText = if (value.isEmpty()) {
            null
        } else {
            {
                Text(
                    text = stringResource(
                        R.string.bt_txform_description_counter,
                        value.length,
                        BT_DESCRIPTION_MAX_CHARS,
                    ),
                    // Red at the ceiling: the field stops accepting characters
                    // there, and the reader deserves to know that is deliberate.
                    color = if (full) bt.loss else bt.textMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        colors = colors,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A brand-styled single-line text field (Settings forms). When [isPassword] the
 * content is masked with an inline show/hide eye toggle. Keeps a 48dp+ target and
 * the gold-focus / red-error field look shared across the app.
 */
@Composable
fun BtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    isError: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingText: String? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    val bt = BtTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        visualTransformation = when {
            !isPassword -> VisualTransformation.None
            revealed -> VisualTransformation.None
            else -> PasswordVisualTransformation()
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (isPassword && !revealed) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
        ),
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.bt_action_hide_password else R.string.bt_action_show_password,
                        ),
                        tint = bt.textMuted,
                    )
                }
            }
        } else {
            null
        },
        supportingText = supportingText?.let { { Text(it, color = if (isError) bt.loss else bt.textMuted) } },
        colors = btFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}
