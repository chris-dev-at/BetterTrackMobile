package at.bettertrack.app.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.theme.BtTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shared no-future date input (§6.2 / §6.4). A read-only field that opens the
 * BetterTrack-styled [BtDatePickerDialog] on press — reused by the transaction
 * form and the custom-asset value-point sheet so both share one dated-input
 * pattern (localized medium date, gold accent, calendar affordance).
 */
@Composable
fun BtDateField(
    date: LocalDate,
    label: String,
    enabled: Boolean,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release && enabled) onClick()
        }
    }
    OutlinedTextField(
        value = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            Icon(
                Icons.Outlined.CalendarToday,
                contentDescription = stringResource(R.string.bt_txform_date_cd),
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
        },
        interactionSource = interaction,
        modifier = modifier,
        colors = btDateFieldColors(),
    )
}

/**
 * No-future date picker (selectable up to end of today, UTC-keyed) styled to the
 * brand. Shared by every dated form so the "can't pick the future" rule and OK/
 * Cancel affordances live in exactly one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val zone = remember { ZoneId.systemDefault() }
    val todayEndUtc = remember {
        LocalDate.now(zone).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() - 1
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayEndUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate())
                },
            ) { Text(stringResource(R.string.bt_txform_date_ok), color = bt.gold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@Composable
private fun btDateFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    disabledBorderColor = BtTheme.colors.border,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    disabledLabelColor = BtTheme.colors.textMuted,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    disabledTextColor = BtTheme.colors.textMuted,
    cursorColor = BtTheme.colors.gold,
)
