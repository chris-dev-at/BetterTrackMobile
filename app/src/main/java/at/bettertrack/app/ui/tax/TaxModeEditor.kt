package at.bettertrack.app.ui.tax

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.repo.TaxSettings
import at.bettertrack.app.domain.AT_AS_CUSTOM_PARAMS
import at.bettertrack.app.domain.COST_BASIS_STRATEGIES
import at.bettertrack.app.domain.SUPPORTED_TAX_COUNTRIES
import at.bettertrack.app.domain.SettingSource
import at.bettertrack.app.domain.TaxDraftProblem
import at.bettertrack.app.domain.TaxSettingsDraft
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtGroup
import at.bettertrack.app.ui.components.BtGroupRow
import at.bettertrack.app.ui.components.BtSectionHeader
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.components.rememberBtHaptics
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.math.abs
import kotlin.math.floor

/**
 * The tax-mode form, shared by Settings → Taxes and one portfolio's override.
 *
 * ## Why one composable for two screens
 *
 * The two screens differ in *what they do with* a tax configuration — one saves
 * the account default, the other pins an override and triggers a reconcile — but
 * the configuration itself is one shape at every layer of the cascade (see
 * [at.bettertrack.app.data.repo.PortfolioTaxSettings]). Writing the form twice
 * would mean maintaining the mode/field consistency rules twice, in the one place
 * where getting them wrong produces a 400 whose message names a JSON path rather
 * than a control.
 *
 * ## The draft is the state, all of it
 *
 * [draft] is a [TaxSettingsDraft] and nothing else is hoisted, because that type
 * exists precisely so the illegal combinations are unrepresentable: there is no
 * way to hold a country while in manual mode, or a custom parameter block while
 * in none. Switching modes therefore *replaces* the draft rather than clearing
 * fields, and the only escape hatch — remembering what the user had typed in a
 * mode they stepped away from — lives in [remember]ed per-mode drafts here, not
 * in a wider state object that could leak one mode's data onto another's wire
 * body.
 *
 * The numeric fields are the one place where the draft is not sufficient on its
 * own: a `Double` cannot represent "27." mid-keystroke, so each numeric section
 * keeps a text buffer and pushes a parsed value up. Unparseable text becomes
 * `NaN` rather than `null`, which is what makes the draft's own [TaxSettingsDraft.problem]
 * report it — `null` means "no default set", which is legal, and silently
 * treating garbage as "unset" would let a user save something they did not type.
 */
@Composable
fun TaxModeEditor(
    draft: TaxSettingsDraft,
    onDraftChange: (TaxSettingsDraft) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Per-mode memory. Stepping to "no tax tracking" and back must not silently
    // erase the rate someone typed a moment ago — but the draft cannot hold two
    // modes at once (that is the whole point of it), so the parked ones live here.
    var lastManual by remember {
        mutableStateOf(draft as? TaxSettingsDraft.Manual ?: TaxSettingsDraft.Manual())
    }
    var lastCountry by remember {
        mutableStateOf(draft as? TaxSettingsDraft.CountrySpecific ?: TaxSettingsDraft.CountrySpecific(null))
    }
    var lastCustom by remember {
        mutableStateOf(draft as? TaxSettingsDraft.Custom ?: TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS))
    }

    // Every edit goes through here so the parked drafts stay current without any
    // state being written during composition.
    val emit: (TaxSettingsDraft) -> Unit = { next ->
        when (next) {
            is TaxSettingsDraft.Manual -> lastManual = next
            is TaxSettingsDraft.CountrySpecific -> lastCountry = next
            is TaxSettingsDraft.Custom -> lastCustom = next
            is TaxSettingsDraft.None -> Unit
        }
        onDraftChange(next)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtSectionHeader(stringResource(R.string.bt_tax_mode))
        BtGroup {
            TAX_MODE_OPTIONS.forEach { option ->
                TaxRadioRow(
                    title = stringResource(option.title),
                    subtitle = stringResource(option.subtitle),
                    selected = draft.mode == option.wire,
                    enabled = enabled,
                    onSelect = {
                        emit(
                            when (option.wire) {
                                "manual_per_trade" -> lastManual
                                "country_specific" -> lastCountry
                                "custom" -> lastCustom
                                else -> TaxSettingsDraft.None
                            },
                        )
                    },
                )
            }
        }

        when (draft) {
            is TaxSettingsDraft.None -> Unit
            is TaxSettingsDraft.CountrySpecific -> CountrySection(draft, emit, enabled)
            is TaxSettingsDraft.Manual -> ManualSection(draft, emit, enabled)
            is TaxSettingsDraft.Custom -> CustomSection(draft, emit, enabled)
        }

        // One blocking reason at a time — the draft returns the first, and a
        // draft can only ever carry one (each rule guards a different mode).
        draft.problem?.let { problem ->
            BtFormError(BtMessage(taxProblemRes(problem)))
        }

        // The forward-only promise belongs on every mode that records anything.
        // Under "none" there is nothing to be forward-only about.
        if (draft !is TaxSettingsDraft.None) {
            TaxFootnote(stringResource(R.string.bt_tax_forward_only))
        }
    }
}

/**
 * The read-only stand-in for a mode this build has no form for.
 *
 * Rendered instead of [TaxModeEditor] when [TaxSettings.isKnownMode] is false.
 * The alternative — letting [TaxSettingsDraft.fromSettings] fall back to `None`
 * and showing the editor — would present "no tax tracking" as the current state
 * and turn the next save into a silent downgrade of a setting the user made
 * somewhere else. Saying "we don't know this one" costs a screen the user cannot
 * act on and saves them a configuration they cannot get back.
 */
@Composable
fun TaxUnknownModeNotice(modifier: Modifier = Modifier) {
    BtGroup(modifier = modifier) {
        BtGroupRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.bt_tax_mode_unknown),
            subtitle = stringResource(R.string.bt_tax_mode_unknown_sub),
        )
    }
}

// ── Mode-specific sections ───────────────────────────────────────────────────

@Composable
private fun CountrySection(
    draft: TaxSettingsDraft.CountrySpecific,
    onDraftChange: (TaxSettingsDraft) -> Unit,
    enabled: Boolean,
) {
    BtSectionHeader(stringResource(R.string.bt_tax_country))
    BtGroup {
        SUPPORTED_TAX_COUNTRIES.forEach { code ->
            val label = taxCountryLabelRes(code) ?: return@forEach
            TaxRadioRow(
                title = stringResource(label),
                selected = draft.country == code,
                enabled = enabled,
                onSelect = { onDraftChange(TaxSettingsDraft.CountrySpecific(code)) },
            )
        }
    }
}

@Composable
private fun ManualSection(
    draft: TaxSettingsDraft.Manual,
    onDraftChange: (TaxSettingsDraft) -> Unit,
    enabled: Boolean,
) {
    // Seeded once per visit to this branch: leaving manual mode disposes the
    // section, so the buffers cannot outlive the mode they belong to.
    var amountText by rememberSaveable { mutableStateOf(draft.defaultAmountEur.asFieldText()) }
    var rateText by rememberSaveable { mutableStateOf(draft.defaultRatePct.asFieldText()) }

    val problem = draft.problem
    BtSectionHeader(stringResource(R.string.bt_tax_manual_defaults))
    TaxFootnote(stringResource(R.string.bt_tax_manual_defaults_sub))
    BtTextField(
        value = amountText,
        onValueChange = { raw ->
            amountText = sanitizeDecimalInput(raw, maxDecimals = 2)
            onDraftChange(draft.copy(defaultAmountEur = amountText.toOptionalNumber()))
        },
        label = stringResource(R.string.bt_tax_manual_amount),
        enabled = enabled,
        isError = problem == TaxDraftProblem.ManualAmountInvalid ||
            problem == TaxDraftProblem.ManualAmountAndRate,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Next,
    )
    BtTextField(
        value = rateText,
        onValueChange = { raw ->
            rateText = sanitizeDecimalInput(raw, maxDecimals = 4)
            onDraftChange(draft.copy(defaultRatePct = rateText.toOptionalNumber()))
        },
        label = stringResource(R.string.bt_tax_manual_rate),
        enabled = enabled,
        isError = problem == TaxDraftProblem.ManualRateOutOfRange ||
            problem == TaxDraftProblem.ManualAmountAndRate,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )
}

@Composable
private fun CustomSection(
    draft: TaxSettingsDraft.Custom,
    onDraftChange: (TaxSettingsDraft) -> Unit,
    enabled: Boolean,
) {
    var rateText by rememberSaveable { mutableStateOf(draft.params.ratePct.asFieldText()) }
    val params = draft.params

    BtSectionHeader(stringResource(R.string.bt_tax_custom_params))
    BtTextField(
        value = rateText,
        onValueChange = { raw ->
            rateText = sanitizeDecimalInput(raw, maxDecimals = 4)
            // Blank is NOT "no rate" here — the custom engine always has one — so
            // an empty field becomes NaN and the draft reports it as out of range.
            onDraftChange(
                TaxSettingsDraft.Custom(params.copy(ratePct = rateText.toRequiredNumber())),
            )
        },
        label = stringResource(R.string.bt_tax_custom_rate),
        enabled = enabled,
        isError = draft.problem == TaxDraftProblem.RateOutOfRange,
        keyboardType = KeyboardType.Decimal,
        imeAction = ImeAction.Done,
    )
    BtGroup {
        TaxSwitchRow(
            title = stringResource(R.string.bt_tax_custom_loss_offset),
            subtitle = stringResource(R.string.bt_tax_custom_loss_offset_sub),
            checked = params.lossOffset,
            enabled = enabled,
            onCheckedChange = { onDraftChange(TaxSettingsDraft.Custom(params.copy(lossOffset = it))) },
        )
        TaxSwitchRow(
            title = stringResource(R.string.bt_tax_custom_refund),
            subtitle = stringResource(R.string.bt_tax_custom_refund_sub),
            checked = params.refund,
            enabled = enabled,
            onCheckedChange = { onDraftChange(TaxSettingsDraft.Custom(params.copy(refund = it))) },
        )
        TaxSwitchRow(
            title = stringResource(R.string.bt_tax_custom_year_reset),
            subtitle = stringResource(R.string.bt_tax_custom_year_reset_sub),
            checked = params.yearReset,
            enabled = enabled,
            onCheckedChange = { onDraftChange(TaxSettingsDraft.Custom(params.copy(yearReset = it))) },
        )
        TaxSwitchRow(
            title = stringResource(R.string.bt_tax_custom_carry),
            subtitle = stringResource(R.string.bt_tax_custom_carry_sub),
            checked = params.carryForward,
            enabled = enabled,
            onCheckedChange = { onDraftChange(TaxSettingsDraft.Custom(params.copy(carryForward = it))) },
        )
    }

    BtSectionHeader(stringResource(R.string.bt_tax_custom_cost_basis))
    BtGroup {
        COST_BASIS_STRATEGIES.forEach { strategy ->
            val label = taxCostBasisLabelRes(strategy) ?: return@forEach
            TaxRadioRow(
                title = stringResource(label),
                selected = params.costBasis == strategy,
                enabled = enabled,
                onSelect = { onDraftChange(TaxSettingsDraft.Custom(params.copy(costBasis = strategy))) },
            )
        }
    }
}

// ── Shared rows ──────────────────────────────────────────────────────────────

/**
 * A radio row inside a [BtGroup]. The whole row is the target: a 20dp control at
 * the far edge of the screen is not a choice you can make one-handed, and every
 * other picker in this app (see the security screen's AFK threshold) already
 * behaves this way.
 */
@Composable
internal fun TaxRadioRow(
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val bt = BtTheme.colors
    BtGroupRow(
        modifier = modifier,
        title = title,
        titleColor = if (enabled) null else bt.textMuted,
        subtitle = subtitle,
        onClick = if (enabled) onSelect else null,
        trailing = {
            RadioButton(
                selected = selected,
                onClick = if (enabled) onSelect else null,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = bt.gold,
                    unselectedColor = bt.textMuted,
                ),
            )
        },
    )
}

/**
 * A switch row inside a [BtGroup] — the same shape the settings screens use, with
 * the app's toggle haptic so a switch here feels like a switch anywhere else.
 */
@Composable
internal fun TaxSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val haptics = rememberBtHaptics()
    val commit: (Boolean) -> Unit = { on -> haptics.toggle(on); onCheckedChange(on) }
    val rowClick: (() -> Unit)? = if (enabled) ({ commit(!checked) }) else null
    BtGroupRow(
        modifier = modifier,
        title = title,
        titleColor = if (enabled) null else bt.textMuted,
        subtitle = subtitle,
        onClick = rowClick,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = commit,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = bt.onGold,
                    checkedTrackColor = bt.gold,
                    checkedBorderColor = bt.gold,
                    uncheckedThumbColor = bt.textMuted,
                    uncheckedTrackColor = bt.surface,
                    uncheckedBorderColor = bt.borderStrong,
                ),
            )
        },
    )
}

/** Prose about a section — never a row of it, so it sits outside the group. */
@Composable
internal fun TaxFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
    )
}

// ── Labels ───────────────────────────────────────────────────────────────────

private class TaxModeOption(
    val wire: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
)

/**
 * The four modes, in the order the user should meet them: least to most
 * commitment. Spelled out rather than derived from
 * [at.bettertrack.app.domain.TAX_MODES] so the option→copy mapping is total by
 * construction — a mode the platform adds later shows up through
 * [TaxUnknownModeNotice], not as a row with a missing label.
 */
private val TAX_MODE_OPTIONS = listOf(
    TaxModeOption("none", R.string.bt_tax_mode_none, R.string.bt_tax_mode_none_sub),
    TaxModeOption("manual_per_trade", R.string.bt_tax_mode_manual, R.string.bt_tax_mode_manual_sub),
    TaxModeOption("country_specific", R.string.bt_tax_mode_country, R.string.bt_tax_mode_country_sub),
    TaxModeOption("custom", R.string.bt_tax_mode_custom, R.string.bt_tax_mode_custom_sub),
)

/** A mode's short label. Anything this build does not know reads as "unknown". */
@StringRes
fun taxModeLabelRes(mode: String): Int = when (mode) {
    "none" -> R.string.bt_tax_mode_none
    "manual_per_trade" -> R.string.bt_tax_mode_manual
    "country_specific" -> R.string.bt_tax_mode_country
    "custom" -> R.string.bt_tax_mode_custom
    else -> R.string.bt_tax_mode_unknown
}

/** Null for a country this build has no name for — such a row is not offered. */
@StringRes
fun taxCountryLabelRes(country: String?): Int? = when (country) {
    "AT" -> R.string.bt_tax_country_at
    "DE" -> R.string.bt_tax_country_de
    "FI" -> R.string.bt_tax_country_fi
    else -> null
}

/** Null for a strategy this build has no name for — such a row is not offered. */
@StringRes
fun taxCostBasisLabelRes(strategy: String): Int? = when (strategy) {
    "moving-average" -> R.string.bt_tax_custom_basis_avg
    "fifo" -> R.string.bt_tax_custom_basis_fifo
    else -> null
}

/** Which layer of the cascade supplied the value in effect. */
@StringRes
fun taxSourceLabelRes(source: SettingSource): Int = when (source) {
    SettingSource.PORTFOLIO -> R.string.bt_ptax_source_portfolio
    SettingSource.USER -> R.string.bt_ptax_source_user
    SettingSource.SYSTEM -> R.string.bt_ptax_source_system
}

/** One blocking reason → the one sentence that names the control it is about. */
@StringRes
fun taxProblemRes(problem: TaxDraftProblem): Int = when (problem) {
    TaxDraftProblem.CountryRequired -> R.string.bt_tax_country_required
    TaxDraftProblem.RateOutOfRange -> R.string.bt_tax_custom_rate_invalid
    TaxDraftProblem.CostBasisInvalid -> R.string.bt_tax_custom_rate_invalid
    TaxDraftProblem.ManualAmountAndRate -> R.string.bt_tax_manual_either
    TaxDraftProblem.ManualAmountInvalid -> R.string.bt_tax_manual_amount_invalid
    TaxDraftProblem.ManualRateOutOfRange -> R.string.bt_tax_manual_rate_invalid
}

/** Rebuild the draft a settings payload describes. */
fun TaxSettings.toDraft(): TaxSettingsDraft = TaxSettingsDraft.fromSettings(
    mode = mode,
    country = country,
    custom = custom,
    manualDefaultAmountEur = manualDefaultAmountEur,
    manualDefaultRatePct = manualDefaultRatePct,
)

// ── Numeric field plumbing ───────────────────────────────────────────────────

/**
 * An optional numeric field: blank means "no default", which is legal. Anything
 * present but unparseable becomes `NaN` so the draft reports it rather than
 * quietly treating a typo as an absent value.
 */
private fun String.toOptionalNumber(): Double? =
    if (isBlank()) null else parseLocalizedDecimal(this) ?: Double.NaN

/** A required numeric field: blank is as invalid as garbage. */
private fun String.toRequiredNumber(): Double =
    parseLocalizedDecimal(this) ?: Double.NaN

/** The editable form of a stored number: no grouping, no trailing `.0`. */
private fun Double?.asFieldText(): String {
    val value = this ?: return ""
    return when {
        !value.isFinite() -> ""
        value == floor(value) && abs(value) < 1e15 -> value.toLong().toString()
        else -> value.toString()
    }
}
