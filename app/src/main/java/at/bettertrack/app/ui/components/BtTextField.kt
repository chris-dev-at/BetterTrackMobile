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
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme

/** Brand colors for an [OutlinedTextField] (gold focus, red error) — the app-wide field look. */
@Composable
fun btFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    disabledBorderColor = BtTheme.colors.border,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    disabledLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    disabledTextColor = BtTheme.colors.textMuted,
    cursorColor = BtTheme.colors.gold,
)

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
