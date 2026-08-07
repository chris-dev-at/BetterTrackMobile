package at.bettertrack.app.ui.prices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import at.bettertrack.app.R
import at.bettertrack.app.data.storage.ManualPriceError
import at.bettertrack.app.data.storage.ManualPricePoint
import at.bettertrack.app.data.storage.ManualPriceValidation
import at.bettertrack.app.data.storage.validateManualPrice
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.formatMoney
import at.bettertrack.app.ui.components.BtDateField
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtDatePickerDialog
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.theme.BtTheme
import java.time.LocalDate
import java.util.Locale

/**
 * Manual price entry for any asset (S3/S4 plan §5 W6, item 1).
 *
 * Deliberately shaped after `CustomAssetDetailScreen.UpdateValueSheet` — a user
 * who has recorded a custom asset's value should recognise this immediately,
 * because it is the same act. Three differences, each earning its place:
 *
 *  1. **The currency is shown, not assumed.** The custom-asset sheet hardcodes
 *     `€`. Here the currency is the one the engine will value this asset in, and
 *     it is editable so the field is honest about being a real parameter rather
 *     than decoration — with a designed refusal when it is one the device has no
 *     rate for ([ManualPriceError.NO_RATE]).
 *  2. **The entered points are listed, with delete.** Prices accumulate into a
 *     series that drives the history curve, so the user needs to see and correct
 *     what they have said. Re-entering a date replaces it, so "edit" is just
 *     entering it again — the same rule custom assets already follow.
 *  3. **Validation is real.** The custom-asset sheet accepts anything `>= 0`.
 *     A price of zero is refused here, because on the money path "worth nothing"
 *     and "not known" must not collapse into the same number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualPriceSheet(
    assetSymbol: String,
    assetId: String,
    valuationCurrency: String,
    points: List<ManualPricePoint>,
    busy: Boolean,
    locale: Locale,
    onSubmit: (date: LocalDate, value: String, currency: String) -> Unit,
    onDelete: (dateIso: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var valueText by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(valuationCurrency) }
    var date by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf(LocalDate.now()) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val validation = validateManualPrice(
        assetId = assetId,
        rawValue = valueText,
        date = date,
        today = LocalDate.now(),
        currency = currency,
        valuationCurrency = valuationCurrency,
    )
    // EMPTY is "not typed yet", not "wrong" — the form stays quiet until the user
    // has actually said something that could be wrong.
    val error = (validation as? ManualPriceValidation.Invalid)
        ?.error
        ?.takeIf { it != ManualPriceError.EMPTY }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_price_sheet_title, assetSymbol),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                text = stringResource(R.string.bt_price_sheet_body),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = sanitizeDecimalInput(it, maxDecimals = 4) },
                    label = { Text(stringResource(R.string.bt_price_field_value)) },
                    singleLine = true,
                    isError = error != null,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
                    modifier = Modifier.weight(1.6f),
                )
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.take(3).uppercase() },
                    label = { Text(stringResource(R.string.bt_price_field_currency)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }

            BtDateField(
                date = date,
                label = stringResource(R.string.bt_price_field_date),
                enabled = !busy,
                locale = locale,
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(
                    text = manualPriceErrorText(it, currency, valuationCurrency),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            BtPrimaryButton(
                text = stringResource(R.string.bt_price_save),
                onClick = { onSubmit(date, valueText, currency) },
                enabled = validation is ManualPriceValidation.Valid && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.bt_price_history_title),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
            if (points.isEmpty()) {
                BtInlineEmpty(stringResource(R.string.bt_price_history_empty))
            } else {
                // Newest first: the price that is currently valuing the holding is
                // the one the user most likely came here to check or correct.
                points.sortedByDescending { it.dateIso }.forEach { point ->
                    ManualPriceRow(
                        point = point,
                        locale = locale,
                        enabled = !busy,
                        onDelete = { confirmDelete = point.dateIso },
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        BtDatePickerDialog(
            initial = date,
            onPick = {
                date = it
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    confirmDelete?.let { dateIso ->
        val point = points.firstOrNull { it.dateIso == dateIso }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = bt.surfaceHigh,
            title = { Text(stringResource(R.string.bt_price_delete_title), color = bt.textPrimary) },
            text = {
                Text(
                    text = stringResource(
                        R.string.bt_price_delete_body,
                        point?.let { formatMoney(it.close, it.currency, locale) }.orEmpty(),
                        formatPriceDate(dateIso, locale) ?: dateIso,
                    ),
                    color = bt.textMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(dateIso)
                }) {
                    Text(stringResource(R.string.bt_price_delete_confirm), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textMuted)
                }
            },
        )
    }
}

@Composable
private fun ManualPriceRow(
    point: ManualPricePoint,
    locale: Locale,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatPriceDate(point.dateIso, locale) ?: point.dateIso,
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatMoney(point.close, point.currency, locale),
            style = BtTheme.type.moneySmall,
            color = bt.textPrimary,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.bt_price_delete_confirm),
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** One designed sentence per refusal — never a generic "invalid input". */
@Composable
private fun manualPriceErrorText(
    error: ManualPriceError,
    entered: String,
    valuation: String,
): String = when (error) {
    ManualPriceError.EMPTY, ManualPriceError.NOT_A_NUMBER ->
        stringResource(R.string.bt_price_err_number)

    ManualPriceError.NOT_POSITIVE -> stringResource(R.string.bt_price_err_positive)
    ManualPriceError.TOO_LARGE -> stringResource(R.string.bt_price_err_large)
    ManualPriceError.FUTURE_DATE -> stringResource(R.string.bt_price_err_future)
    ManualPriceError.BAD_CURRENCY -> stringResource(R.string.bt_price_err_currency)
    ManualPriceError.NO_RATE -> stringResource(R.string.bt_price_err_rate, entered, valuation)
}

/** Saver so the picked date survives config change / process death. */
private val LocalDateSaver = Saver<LocalDate, String>(
    save = { it.toString() },
    restore = { LocalDate.parse(it) },
)
