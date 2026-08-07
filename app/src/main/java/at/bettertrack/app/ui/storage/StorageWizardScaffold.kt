package at.bettertrack.app.ui.storage

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme

/**
 * The shared frame every wizard step is poured into.
 *
 * ## Why one frame and not six screens
 *
 * The wizard is the first thing a new user ever sees, and it asks for an
 * irreversible commitment across six steps. If each step were its own layout,
 * each would drift a few dp and the flow would feel like six unrelated forms
 * rather than one conversation. Holding the frame constant — same header
 * position, same rhythm, same anchored action — means the only thing that moves
 * between steps is the content, which is exactly what a user following a process
 * needs: the page changes, the ground does not.
 *
 * The gold is spent deliberately and almost nowhere: the progress rail, the
 * primary action, and the selected card. Everything else is neutral, so the one
 * thing you are meant to do next is unmistakable without a single decorative
 * flourish.
 */
@Composable
fun WizardScaffold(
    stepIndex: Int,
    stepCount: Int,
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    primaryText: String?,
    primaryEnabled: Boolean = true,
    primaryLoading: Boolean = false,
    onPrimary: (() -> Unit)? = null,
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val bt = BtTheme.colors

    // A short settle on entry (spec §3.7), skipped entirely under reduced motion.
    val reducedMotion = rememberReducedMotion()
    var appeared by remember(stepIndex) { mutableStateOf(false) }
    LaunchedEffect(stepIndex) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared || reducedMotion) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "wizardStep",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bt.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ── Header: back + progress rail ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.bt_action_back),
                        tint = bt.textSecondary,
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            StepRail(
                stepIndex = stepIndex,
                stepCount = stepCount,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    alpha = entrance
                    translationY = (1f - entrance) * 12.dp.toPx()
                }
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = bt.textPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
            }
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(32.dp))
        }

        // ── Anchored action block (thumb zone) ──────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (primaryText != null && onPrimary != null) {
                BtPrimaryButton(
                    text = primaryText,
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    loading = primaryLoading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            }
            secondary?.invoke()
        }
    }
}

/**
 * The progress rail: one segment per step, filled ones gold.
 *
 * Chosen over a numeric "3 of 6" because the wizard's step count changes with
 * the branch the user picked, and a number that silently grows from 2 to 7 when
 * you tap a different card reads as the app changing its mind.
 */
@Composable
private fun StepRail(stepIndex: Int, stepCount: Int, modifier: Modifier = Modifier) {
    val bt = BtTheme.colors
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(stepCount) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index <= stepIndex) bt.gold else bt.border),
            )
        }
    }
}

/**
 * A choice card: title, what it gives you, and — always — what it does not.
 *
 * The "Not available" line is not fine print. Plan §4.5's rule is that missing
 * features are absent rather than greyed, which only works if the user was told
 * before they chose; otherwise "absent" is indistinguishable from "broken".
 */
@Composable
fun WizardChoiceCard(
    title: String,
    body: String,
    missing: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        shape = BtShapes.card,
        color = if (selected) bt.wash(bt.gold, 0.07f) else bt.surface,
        border = BorderStroke(1.dp, if (selected) bt.gold else bt.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) bt.gold else bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(BtShapes.pill)
                            .background(bt.gold),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.bt_storage_not_available).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = missing,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/**
 * The blocking acknowledgment rung, reused verbatim from the shipped public-link
 * pattern (`ui/social/AudiencePickerSheet.kt`): red-tinted surface, warning
 * glyph, and a checkbox the whole card toggles. It is the app's established
 * "you are about to do something that cannot be walked back" idiom, and the vault
 * is the one place that idiom matters most.
 */
@Composable
fun BlockingAcknowledgment(
    title: String,
    body: String,
    checkboxLabel: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val bt = BtTheme.colors
    // KEEPS its border, deliberately — do not "finish" the tonal sweep here.
    // R2 moved the app off border walls and left exactly one exception: the
    // danger zone. This is that category — the gate in front of an action that
    // cannot be walked back — and the border is what separates it from the
    // merely-serious notices (WizardNote LOSS, PublicAcknowledgment) that went
    // tonal in the leftovers pass. An outline only means "stop" while it is
    // rare, so spending it anywhere else is what would weaken this one.
    Surface(
        shape = BtShapes.card,
        color = bt.wash(bt.loss, 0.09f),
        border = BorderStroke(1.dp, bt.edge(bt.loss, 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = { onToggle() },
            ),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = bt.loss,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = bt.lossSoft,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggle() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = bt.loss,
                            uncheckedColor = bt.edge(bt.loss, 0.6f),
                            checkmarkColor = bt.textPrimary,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = checkboxLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = bt.textPrimary,
                    )
                }
            }
        }
    }
}

/**
 * The neutral counterpart of [BlockingAcknowledgment] — a required tick that
 * confirms a *safety* action rather than warning about a destructive one.
 *
 * Kept visually distinct on purpose: "I saved my recovery kit" is a good thing
 * the user did, and dressing it in the same red as "nobody can recover your data"
 * would flatten the two into one undifferentiated wall of alarm, which is how
 * users learn to tick without reading.
 */
@Composable
fun RequiredTick(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.card,
        color = if (checked) bt.wash(bt.gold, 0.07f) else bt.surface,
        border = BorderStroke(1.dp, if (checked) bt.edge(bt.gold, 0.5f) else bt.border),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = { onToggle() },
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = bt.gold,
                    uncheckedColor = bt.borderStrong,
                    checkmarkColor = bt.onGold,
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) bt.textPrimary else bt.textMuted,
            )
        }
    }
}

/**
 * A calm note — the honest "this isn't available yet" surface.
 *
 * ## Tonal, not bordered
 *
 * All three tones used to draw a 1dp ring: a full-opacity `borderStrong` for
 * NEUTRAL, and a 40% accent for GOLD and LOSS. R2 moved the app off border walls
 * onto tonal steps ([at.bettertrack.app.ui.components.BtGroup]) and R3 took the
 * ring off the state badge for the same reason — a tinted fill on a darker page
 * is already a boundary, so the outline is a second boundary doing the first
 * one's job. This note was one of the last three semantic banners still drawing
 * one.
 *
 * Each tone's fill steps up to hold the shape on its own now that nothing is
 * drawn around it (GOLD 6% → 11%, LOSS 8% → 13%). NEUTRAL needs no bump: a plain
 * `surface` fill with no border at `BtShapes.group` is *exactly* what [BtGroup]
 * already is, which is the point — the neutral note stops being a special case
 * and becomes the app's ordinary containment.
 *
 * The tone still reads, because it was never the border doing that work: the
 * fill's hue and the title's colour (`lossSoft` / `gold`) carry it, which is the
 * same thing R3 kept when it un-ringed the error badge.
 */
@Composable
fun WizardNote(
    title: String?,
    body: String,
    tone: NoteTone = NoteTone.NEUTRAL,
) {
    val bt = BtTheme.colors
    Surface(
        shape = BtShapes.group,
        color = when (tone) {
            NoteTone.NEUTRAL -> bt.surface
            NoteTone.GOLD -> bt.wash(bt.gold, 0.11f)
            NoteTone.LOSS -> bt.wash(bt.loss, 0.13f)
        },
        // The GOLD and LOSS tones are washes and state their own shape; NEUTRAL
        // is `surface`, which on the all-white light table (2026-08-07) is the
        // page itself — so without this the neutral note was an invisible block.
        border = BorderStroke(1.dp, bt.groupBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when (tone) {
                        NoteTone.LOSS -> bt.lossSoft
                        NoteTone.GOLD -> bt.gold
                        NoteTone.NEUTRAL -> bt.textPrimary
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
    }
}

enum class NoteTone { NEUTRAL, GOLD, LOSS }
