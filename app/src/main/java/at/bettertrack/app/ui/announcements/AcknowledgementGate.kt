package at.bettertrack.app.ui.announcements

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtErrorCopy
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.announcements.AnnouncementSeverity
import at.bettertrack.app.data.announcements.AnnouncementsFlags
import at.bettertrack.app.data.announcements.GateView
import at.bettertrack.app.data.announcements.LOCALE_DE
import at.bettertrack.app.data.announcements.LOCALE_EN
import at.bettertrack.app.data.announcements.ackConfirmEnabled
import at.bettertrack.app.data.announcements.gate
import at.bettertrack.app.data.auth.SignOutReason
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtSnackbarHost
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtSnackbarState
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.settings.DataExportScreen
import at.bettertrack.app.ui.settings.DeleteAccountScreen
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The `ack_required` surface — the BLOCKING acknowledgement modal (contract
 * §5.7.3). Mounted by the root gate stack (BtRoot) below the app lock and above
 * the first-run wizard and the shell, because the server's own gate comes before
 * both: until the person acknowledges, nothing else is theirs to use.
 *
 * ## Why a full-screen Dialog window
 *
 * A Dialog is its own window: TalkBack is confined to it, no sheet, snackbar or
 * deep-linked screen of the shell can be drawn over it, and — with
 * `dismissOnBackPress = false` / `dismissOnClickOutside = false` — neither the
 * back gesture nor a tap anywhere closes it. The only ways out are the ones the
 * contract names: Confirm (enabled only once the box is ticked), Sign out, and
 * the data-egress row (Export my data, Delete account), which a legal
 * acknowledgement must never block (Chief ruling 2026-10-29). Both of those are
 * the app's NATIVE flows, shown inside this same window, so they are reachable
 * without passing the gate; back from them returns here.
 *
 * One obligation at a time, oldest first; `seen` is reported once the text is
 * on screen. Renders nothing while the flag is off or nothing is owed.
 */
@Composable
fun AcknowledgementGateHost() {
    if (!AnnouncementsFlags.enabled) return
    val controller = AppGraph.announcements
    val state by controller.state.collectAsStateWithLifecycle()
    // A language switch re-reads the list in the new language.
    val uiLanguage = rememberBtLocale().language
    LaunchedEffect(uiLanguage) { controller.onUiLocale(uiLanguage) }

    val gate = state.gate() ?: return

    Dialog(
        onDismissRequest = { /* not dismissible — by contract */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val snackbar = rememberBtSnackbarState()
        // Which surface of the gate window is showing. Saveable so a rotation in
        // the middle of an export keeps the person where they were.
        var sub by rememberSaveable { mutableStateOf(GateSub.GATE) }
        CompositionLocalProvider(LocalBtSnackbar provides snackbar.controller) {
            Box(Modifier.fillMaxSize()) {
                Surface(modifier = Modifier.fillMaxSize(), color = BtTheme.colors.bg) {
                    when (sub) {
                        GateSub.EXPORT -> {
                            BackHandler { sub = GateSub.GATE }
                            DataExportScreen(onBack = { sub = GateSub.GATE })
                        }
                        GateSub.DELETE -> {
                            BackHandler { sub = GateSub.GATE }
                            DeleteAccountScreen(onBack = { sub = GateSub.GATE })
                        }
                        GateSub.GATE -> AcknowledgementGateContent(
                            gate = gate,
                            onConfirm = controller::acknowledge,
                            onSignOut = {
                                if (gate.preview) {
                                    // The preview never signs anyone out.
                                    controller.clearPreviews()
                                } else {
                                    AppGraph.authRepository.requestLogout(SignOutReason.USER_LOGOUT)
                                }
                            },
                            onExport = { sub = GateSub.EXPORT },
                            onDelete = { sub = GateSub.DELETE },
                            onLocale = controller::switchGateLocale,
                            onRetry = controller::retryGateContent,
                            onShown = controller::markGateSeen,
                        )
                    }
                }
                // This window is above the shell, so the shell's snackbar host can
                // never reach it; the native export screen reports through this one.
                BtSnackbarHost(
                    hostState = snackbar.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }
}

private enum class GateSub { GATE, EXPORT, DELETE }

@Composable
fun AcknowledgementGateContent(
    gate: GateView,
    onConfirm: () -> Unit,
    onSignOut: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onLocale: (String) -> Unit,
    onRetry: () -> Unit,
    onShown: () -> Unit,
) {
    val bt = BtTheme.colors
    val content = gate.content
    // The tick belongs to ONE text: a new item, a re-fetched (changed) text or a
    // language switch all start unticked again.
    var ticked by rememberSaveable(gate.id, content?.contentSha256, content?.contentLocale) { mutableStateOf(false) }
    LaunchedEffect(gate.id, content?.contentSha256) { if (content != null) onShown() }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Short windows (a phone in landscape) scroll as one column; otherwise the
        // decision stays pinned under a scrolling text.
        val pinned = maxHeight >= 520.dp
        val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()
        if (pinned) {
            Column(column.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) { GateText(gate, onLocale, onRetry) }
                HorizontalDivider(thickness = 1.dp, color = bt.border)
                GateDecision(gate, ticked, { ticked = it }, onConfirm, onSignOut, onExport, onDelete)
            }
        } else {
            Column(column.verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { GateText(gate, onLocale, onRetry) }
                HorizontalDivider(thickness = 1.dp, color = bt.border)
                GateDecision(gate, ticked, { ticked = it }, onConfirm, onSignOut, onExport, onDelete)
            }
        }
    }
}

@Composable
private fun GateText(gate: GateView, onLocale: (String) -> Unit, onRetry: () -> Unit) {
    val bt = BtTheme.colors
    val content = gate.content
    val severity = content?.severity ?: gate.pending.severity
    val locale = rememberBtLocale()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = severityIcon(severity),
            contentDescription = severityLabel(severity),
            tint = if (severity == AnnouncementSeverity.INFO) bt.textMuted else bt.goldEmphasis,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.bt_ann_gate_eyebrow).uppercase(locale),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
            modifier = Modifier.weight(1f),
        )
        if (gate.total > 1) {
            Text(
                text = stringResource(R.string.bt_ann_gate_progress, gate.position, gate.total),
                style = MaterialTheme.typography.labelMedium,
                color = bt.textMuted,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = content?.title ?: gate.pending.title,
            style = MaterialTheme.typography.headlineSmall,
            color = bt.textPrimary,
            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
        )
        if (gate.preview) PreviewTag()
    }

    val endsAt = content?.endsAtMs ?: gate.pending.endsAtMs
    if (endsAt != null) {
        val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(endsAt).atZone(ZoneId.systemDefault()))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.bt_ann_gate_until, formatted),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }

    // Both locales exist → offer the switch (contract §5.7.3); the acknowledgement
    // then names the locale and hash of whatever is on screen.
    val locales = content?.availableLocales.orEmpty()
    val shownLocale = content?.contentLocale
    if (LOCALE_EN in locales && LOCALE_DE in locales && shownLocale != null) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_ann_gate_language),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(end = 12.dp),
            )
            BtSegmented(
                options = listOf(LOCALE_EN, LOCALE_DE),
                selected = shownLocale,
                label = { stringResource(if (it == LOCALE_DE) R.string.bt_ann_lang_de else R.string.bt_ann_lang_en) },
                onSelect = { if (!gate.busy) onLocale(it) },
            )
        }
    }

    Spacer(Modifier.height(18.dp))
    when {
        content != null -> AnnouncementBody(doc = content.renderDoc(), color = bt.textPrimary)
        gate.loadFailed -> {
            Text(
                text = stringResource(R.string.bt_ann_gate_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bt_ann_gate_retry), color = bt.goldInk)
            }
        }
        else -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = bt.gold, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
    }
    if (gate.preview) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bt_ann_gate_preview_note),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun GateDecision(
    gate: GateView,
    ticked: Boolean,
    onTick: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onSignOut: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        gate.notice?.let { code ->
            BtErrorCopy.resFor(code)?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.goldInk,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        gate.error?.let { err ->
            Text(
                text = err.asMessage().resolveWithDiagnostic(),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val canTick = gate.content != null && !gate.busy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = ticked, enabled = canTick, role = Role.Checkbox, onValueChange = onTick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = ticked,
                // The row owns the toggle, so TalkBack reads one control.
                onCheckedChange = null,
                enabled = canTick,
                colors = CheckboxDefaults.colors(
                    checkedColor = bt.gold,
                    uncheckedColor = bt.borderStrong,
                    checkmarkColor = bt.onGold,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.bt_ann_gate_checkbox),
                style = MaterialTheme.typography.bodyMedium,
                color = if (canTick) bt.textPrimary else bt.textMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        BtPrimaryButton(
            text = stringResource(R.string.bt_ann_gate_confirm),
            onClick = onConfirm,
            enabled = ackConfirmEnabled(ticked, gate),
            loading = gate.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
        Spacer(Modifier.height(10.dp))
        BtSecondaryButton(
            text = stringResource(R.string.bt_ann_gate_sign_out),
            onClick = onSignOut,
            enabled = !gate.busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.bt_ann_gate_alternatives),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onExport, enabled = !gate.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bt_ann_gate_export), color = bt.goldInk, fontWeight = FontWeight.Medium)
            }
            Text("·", color = bt.textFaint)
            TextButton(onClick = onDelete, enabled = !gate.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bt_ann_gate_delete), color = bt.textSecondary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
