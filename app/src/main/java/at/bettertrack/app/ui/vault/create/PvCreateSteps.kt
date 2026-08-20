package at.bettertrack.app.ui.vault.create

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtTextField
import at.bettertrack.app.ui.storage.BlockingAcknowledgment
import at.bettertrack.app.ui.storage.NoteTone
import at.bettertrack.app.ui.storage.WizardNote
import at.bettertrack.app.ui.storage.WizardScaffold
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import at.bettertrack.app.vault.pv.custody.PvCustodyMode

/**
 * The seven steps of the §21 creation ceremony, each a private body inside the
 * shared [WizardScaffold].
 *
 * Kept in their own file for the same reason `StorageSetupWizard` keeps its
 * steps together: the host file should read as the *shape* of the flow, and the
 * copy, the grids and the cards belong next to each other so the tone can be
 * held constant across them.
 *
 * The tone is §21 Q4's, and it applies to every string below: **facts, stated
 * calmly**. The vault's name is server-visible and the explainer says so as one
 * point among the others; the Drive option is honestly unavailable rather than
 * hidden; the one place that raises its voice is the §16 acknowledgment, which
 * is the one place where raising it is the truth.
 */

// ── 1 · Name ────────────────────────────────────────────────────────────────

/**
 * §21 Q4: names are cleartext, and the user is told so **calmly** — "the
 * paranoid explainer communicates it as a plain fact among the feature points
 * … no alarm banners, no bloat". So the note lists what is encrypted first,
 * what is not second, and stops.
 */
@Composable
internal fun PvCreateNameStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onNameChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_name_title),
        subtitle = stringResource(R.string.bt_pv_create_name_sub),
        onBack = onBack,
        primaryText = pvContinueLabel(),
        primaryEnabled = pvCanAdvance(state),
        onPrimary = onNext,
    ) {
        BtTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = stringResource(R.string.bt_pv_create_name_label),
        )
        Spacer(Modifier.height(18.dp))
        WizardNote(
            title = stringResource(R.string.bt_pv_create_name_note_title),
            body = stringResource(R.string.bt_pv_create_name_note),
        )
    }
}

// ── 2 · Media ───────────────────────────────────────────────────────────────

/**
 * Server / Drive / both.
 *
 * Drive is epic E5 and its separately-authenticated connection does not exist
 * yet, so both Drive-bearing options render as a **designed disabled state**
 * with one honest line underneath — never a dead button, never a hidden option
 * the user has to wonder about.
 */
@Composable
internal fun PvCreateMediaStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onSelectMedium: (PvVaultMedium) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_media_title),
        subtitle = stringResource(R.string.bt_pv_create_media_sub),
        onBack = onBack,
        primaryText = pvContinueLabel(),
        primaryEnabled = pvCanAdvance(state),
        onPrimary = onNext,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PvMediaCard(
                title = stringResource(R.string.bt_pv_create_media_server),
                body = stringResource(R.string.bt_pv_create_media_server_sub),
                selected = state.medium == PvVaultMedium.SERVER,
                medium = PvVaultMedium.SERVER,
                onSelect = onSelectMedium,
            )
            PvMediaCard(
                title = stringResource(R.string.bt_pv_create_media_drive),
                body = stringResource(R.string.bt_pv_create_media_drive_sub),
                selected = state.medium == PvVaultMedium.DRIVE,
                medium = PvVaultMedium.DRIVE,
                onSelect = onSelectMedium,
            )
            PvMediaCard(
                title = stringResource(R.string.bt_pv_create_media_both),
                body = stringResource(R.string.bt_pv_create_media_both_sub),
                selected = state.medium == PvVaultMedium.BOTH,
                medium = PvVaultMedium.BOTH,
                onSelect = onSelectMedium,
            )
        }
        if (!pvMediumAvailable(PvVaultMedium.DRIVE)) {
            Spacer(Modifier.height(16.dp))
            WizardNote(
                title = null,
                body = stringResource(R.string.bt_pv_create_media_drive_pending),
            )
        }
    }
}

/**
 * One storage option.
 *
 * The disabled rendition is a *quieter* version of the same card rather than a
 * different component: same shape, same layout, muted ink and no ripple. A
 * greyed card that still looks like a card is what tells the user the option is
 * real and simply not ready — which it is.
 */
@Composable
private fun PvMediaCard(
    title: String,
    body: String,
    selected: Boolean,
    medium: PvVaultMedium,
    onSelect: (PvVaultMedium) -> Unit,
) {
    val bt = BtTheme.colors
    val enabled = pvMediumAvailable(medium)
    Surface(
        onClick = { onSelect(medium) },
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
                tint = when {
                    !enabled -> bt.textFaint
                    selected -> bt.goldInk
                    else -> bt.textMuted
                },
                modifier = Modifier.width(20.dp).height(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) bt.textPrimary else bt.textMuted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) bt.textSecondary else bt.textFaint,
                )
            }
        }
    }
}

// ── 3 · The 12 words ────────────────────────────────────────────────────────

/**
 * The phrase, in a numbered 2×6 grid.
 *
 * Numbered because order is part of the secret and a user copying twelve
 * unnumbered words onto paper will eventually transpose two. Tabular figures on
 * the index so the numbers form a straight column; medium weight on the word so
 * the eye lands on it and not on its number.
 *
 * A plain [Column] of six [Row]s rather than a lazy grid: twelve cells never
 * need virtualisation, and a lazy grid nested inside the scaffold's own scroll
 * is a measuring conflict for no benefit.
 *
 * There is no copy button, and the note says why. Together with the file's
 * FLAG_SECURE (held by `PvCreateWizard`) and the absence of any
 * `rememberSaveable`, this screen is the only place the words exist.
 */
@Composable
internal fun PvCreateWordsStep(
    state: PvCreateState,
    words: List<String>,
    stepIndex: Int,
    stepCount: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_words_title),
        subtitle = stringResource(R.string.bt_pv_create_words_sub),
        onBack = onBack,
        primaryText = stringResource(R.string.bt_pv_create_words_next),
        primaryEnabled = pvCanAdvance(state),
        onPrimary = onNext,
    ) {
        if (state.verifyMissed) {
            WizardNote(
                title = null,
                body = stringResource(R.string.bt_pv_create_words_missed),
                tone = NoteTone.GOLD,
            )
            Spacer(Modifier.height(16.dp))
        }
        PvWordGrid(words)
        Spacer(Modifier.height(16.dp))
        WizardNote(title = null, body = stringResource(R.string.bt_pv_create_words_no_copy))
    }
}

/** Six rows of two numbered cells — the whole phrase on one screen, unscrolled. */
@Composable
private fun PvWordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rows = (words.size + 1) / 2
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PvWordCell(position = row + 1, word = words.getOrNull(row), modifier = Modifier.weight(1f))
                PvWordCell(
                    position = rows + row + 1,
                    word = words.getOrNull(rows + row),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The grid runs DOWN the left column and then down the right one (1‥6, 7‥12)
 * rather than left-to-right, because that is how the numbers read as two
 * continuous lists — and a user checking their paper reads a list, not a table.
 */
@Composable
private fun PvWordCell(position: Int, word: String?, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.group,
        color = bt.surface,
        border = BorderStroke(1.dp, bt.groupBorder),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(22.dp)) {
                Text(
                    text = "$position",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = FONT_FEATURE_TABULAR,
                    ),
                    color = bt.textMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = word.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = bt.textPrimary,
            )
        }
    }
}

// ── 4 · The one-word check ──────────────────────────────────────────────────

/**
 * §21 Q2, verbatim: *"validate only one word. no 20 years waiting and lots of
 * friction."*
 *
 * One field, one word, one button. A wrong answer is not an error state and
 * carries no counter, no delay and no lockout — it returns to the words with a
 * calm line, which is the correct response to "I did not copy them properly".
 */
@Composable
internal fun PvCreateVerifyStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onVerifyInputChange: (String) -> Unit,
    onSubmitVerify: () -> Unit,
    onBack: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_verify_title),
        subtitle = stringResource(R.string.bt_pv_create_verify_sub, state.verifyPosition),
        onBack = onBack,
        primaryText = stringResource(R.string.bt_pv_create_verify_action),
        primaryEnabled = state.verifyInput.isNotBlank(),
        onPrimary = onSubmitVerify,
    ) {
        BtTextField(
            value = state.verifyInput,
            onValueChange = onVerifyInputChange,
            label = stringResource(R.string.bt_pv_create_verify_label, state.verifyPosition),
        )
        Spacer(Modifier.height(14.dp))
        WizardNote(title = null, body = stringResource(R.string.bt_pv_create_verify_hint))
    }
}

// ── 5 · The lost-phrase acknowledgment ──────────────────────────────────────

/**
 * §16 in one screen: the warning, the single tick, and — right beside it,
 * always — the contrast that keeps it proportionate.
 *
 * The contrast is not softening. A user who reads "lost phrase = lost data" and
 * nothing else concludes that this feature is a trap where every mistake is
 * fatal; the truth is narrower and worth stating in the same breath: a
 * forgotten **device password** loses nothing (§12's keystore reset), a lost
 * **phone** loses nothing while another copy of the phrase exists, and only
 * losing every copy of the phrase loses this one vault — never the others,
 * never the account.
 *
 * The warning uses [BlockingAcknowledgment], the app's established
 * "cannot be walked back" idiom, and the contrast uses the neutral
 * [WizardNote] — deliberately a different tone, so the two do not flatten into
 * one wall of alarm that users learn to tick without reading.
 */
@Composable
internal fun PvCreateAcknowledgeStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onToggleAcknowledged: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_ack_title),
        subtitle = null,
        onBack = onBack,
        primaryText = pvContinueLabel(),
        primaryEnabled = pvCanAdvance(state),
        onPrimary = onNext,
    ) {
        BlockingAcknowledgment(
            title = stringResource(R.string.bt_pv_create_ack_warn_title),
            body = stringResource(R.string.bt_pv_create_ack_warn_body),
            checkboxLabel = stringResource(R.string.bt_pv_create_ack_check),
            checked = state.acknowledged,
            onToggle = onToggleAcknowledged,
        )
        Spacer(Modifier.height(14.dp))
        WizardNote(
            title = stringResource(R.string.bt_pv_create_ack_contrast_title),
            body = stringResource(R.string.bt_pv_create_ack_contrast_body),
        )
    }
}

// ── 6 · Custody ─────────────────────────────────────────────────────────────

/**
 * The §12 choice, delegated to [at.bettertrack.app.ui.vault.custody.PvCustodyChoiceSheet].
 *
 * The sheet was built standalone-previewable for exactly this moment, so the
 * step does not re-render its options: it explains what the choice is about,
 * opens the sheet, and afterwards shows what was chosen. That also keeps the
 * app's bottom-sheet idiom intact — every user-facing choice pops from the
 * bottom — without giving the wizard two primary actions on one screen.
 */
@Composable
internal fun PvCreateCustodyStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onOpenCustody: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val chosen = state.custody
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_custody_title),
        subtitle = stringResource(R.string.bt_pv_create_custody_sub),
        onBack = onBack,
        primaryText = if (chosen == null) {
            stringResource(R.string.bt_pv_create_custody_open)
        } else {
            pvContinueLabel()
        },
        primaryEnabled = true,
        onPrimary = if (chosen == null) onOpenCustody else onNext,
        secondary = if (chosen == null) {
            null
        } else {
            {
                BtSecondaryButton(
                    text = stringResource(R.string.bt_pv_create_custody_change),
                    onClick = onOpenCustody,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        },
    ) {
        WizardNote(
            title = null,
            body = stringResource(R.string.bt_pv_create_custody_explainer),
        )
        if (chosen != null) {
            Spacer(Modifier.height(14.dp))
            PvChosenRow(
                label = stringResource(R.string.bt_pv_create_summary_custody),
                value = stringResource(pvCustodySummary(chosen)),
            )
        }
    }
}

/** The one-line description of a custody mode, reusing §12's own copy. */
@StringRes
private fun pvCustodySummary(mode: PvCustodyMode): Int = when (mode) {
    PvCustodyMode.WRAPPED -> R.string.bt_pv_custody_state_wrapped_sub
    PvCustodyMode.PLAIN -> R.string.bt_pv_custody_state_plain_sub
}

// ── 7 · Done ────────────────────────────────────────────────────────────────

/**
 * The honest end of the flow.
 *
 * `POST /vaults` is epic E1 and is not deployed, so there is no vault to show
 * and nothing was written anywhere. Rather than a spinner that never resolves or
 * an invented endpoint, the step states the summary the user assembled and one
 * plain sentence: this will be activated as soon as the server supports it.
 */
@Composable
internal fun PvCreateDoneStep(
    state: PvCreateState,
    stepIndex: Int,
    stepCount: Int,
    onFinish: () -> Unit,
) {
    WizardScaffold(
        stepIndex = stepIndex,
        stepCount = stepCount,
        title = stringResource(R.string.bt_pv_create_done_title),
        subtitle = stringResource(R.string.bt_pv_create_done_sub),
        onBack = null,
        primaryText = stringResource(R.string.bt_action_done),
        onPrimary = onFinish,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PvChosenRow(
                label = stringResource(R.string.bt_pv_create_summary_name),
                value = state.trimmedName,
            )
            PvChosenRow(
                label = stringResource(R.string.bt_pv_create_summary_media),
                value = stringResource(pvMediumSummary(state.medium)),
            )
            val custody = state.custody
            PvChosenRow(
                label = stringResource(R.string.bt_pv_create_summary_custody),
                value = if (custody == null) "" else stringResource(pvCustodySummary(custody)),
            )
        }
        Spacer(Modifier.height(16.dp))
        WizardNote(
            title = stringResource(R.string.bt_pv_create_done_pending_title),
            body = stringResource(R.string.bt_pv_create_done_pending),
            tone = NoteTone.GOLD,
        )
    }
}

@StringRes
private fun pvMediumSummary(medium: PvVaultMedium?): Int = when (medium) {
    PvVaultMedium.SERVER -> R.string.bt_pv_create_media_server
    PvVaultMedium.DRIVE -> R.string.bt_pv_create_media_drive
    PvVaultMedium.BOTH -> R.string.bt_pv_create_media_both
    null -> R.string.bt_pv_create_summary_unset
}

/** A label/value line for the summary — related values belong visually together. */
@Composable
private fun PvChosenRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            modifier = Modifier.width(110.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = bt.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}
