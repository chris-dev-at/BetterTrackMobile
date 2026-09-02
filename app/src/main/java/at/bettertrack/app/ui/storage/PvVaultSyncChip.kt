package at.bettertrack.app.ui.storage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.ui.format.btSanitizeUntrustedLabel
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.vault.pv.ParanoidVaultsFlags
import at.bettertrack.app.vault.pv.PvVaultSummary
import at.bettertrack.app.vault.pv.sync.PvKeptCandidate
import at.bettertrack.app.vault.pv.sync.PvMedium
import at.bettertrack.app.vault.pv.sync.PvSavedLocallyReason
import at.bettertrack.app.vault.pv.sync.PvSyncFailureReason
import at.bettertrack.app.vault.pv.sync.PvVaultSyncRuntime
import at.bettertrack.app.vault.pv.sync.PvVaultSyncState
import at.bettertrack.app.vault.pv.sync.PvVaultSyncStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * **The sync chip, generalized to N vaults** (`paranoid-design.md` §14).
 *
 * The owner kept this indicator by name — *"i like the 'synched' UI up top with
 * the current paranoid mode. its really cool design stuff"* — and the spec's
 * instruction is precise about what may change: **"look unchanged, data source
 * generalized"**. So nothing here restyles [VaultSyncCard]. What this file adds
 * is what §14 asks for and only that:
 *
 * - one **aggregate** line computed as the worst state across vaults, using the
 *   engine's own severity fold ([PvVaultSyncState.fold]) rather than a second
 *   opinion about which state outranks which;
 * - a **row per vault** behind one tap, with its per-medium detail, its last
 *   sync time and its remedy;
 * - **one chip, never one per vault** (the spec's anti-bloat rule).
 *
 * ## A sheet, not a popover
 *
 * The web anchors a popover under the chip. This app's settled rule is that
 * user-facing menus and detail panels come from the BOTTOM (owner order
 * 2026-08-16; anchored dropdowns are rejected), so the popover's CONTENT is
 * carried over row for row and its container is a `ModalBottomSheet`. The
 * outcome of a retry is reported INSIDE the sheet, never through the shell
 * snackbar — the sheet is the surface the user is looking at, and a verdict that
 * appears behind it is a verdict nobody reads.
 *
 * ## Dormancy
 *
 * [PvVaultSyncSection] returns before emitting anything while
 * `ParanoidVaultsFlags.enabled` is false, and there is no other public
 * composable in this file. With the flag off the enclosing card therefore emits
 * exactly the tree it emitted before this file existed —
 * `PvVaultSyncChipDisciplineTest` holds both halves of that.
 *
 * Discreet mode does not apply: nothing here is an amount.
 */
@Immutable
data class PvVaultSyncRow(
    val vaultId: String,
    /** Cleartext config label (§21 Q4), already sanitized for rendering. */
    val name: String,
    val media: List<PvMedium>,
    val status: PvVaultSyncStatus,
    val perMedium: Map<PvMedium, PvVaultSyncStatus>,
    val lastSyncedAtMs: Long?,
    val pendingDocs: Int,
    val keptCandidates: Int,
) {
    /**
     * Whether "Try again" would do anything.
     *
     * A push retries what a pass could not place; it cannot fix a document
     * written by a newer app version (§5 makes those read-only until the app
     * updates) and it has nowhere to write when the vault has no reachable
     * medium at all. Offering the button anyway would teach the user that the
     * button does nothing.
     */
    val canRetry: Boolean
        get() = when (val current = status) {
            is PvVaultSyncStatus.Error ->
                current.failure.reason != PvSyncFailureReason.UPDATE_REQUIRED

            is PvVaultSyncStatus.SavedLocally -> when (current.reason) {
                PvSavedLocallyReason.OFFLINE, PvSavedLocallyReason.RETRY_QUEUED -> true
                PvSavedLocallyReason.LOCKED, PvSavedLocallyReason.NO_MEDIUM -> false
            }

            else -> false
        }
}

/**
 * The vaults and their states, folded into rows.
 *
 * Pure, so the projection is unit-testable without Compose — the same split the
 * v1 storage surfaces use. A vault the engine has never run a pass for still
 * gets a row: "this vault exists and nothing has happened yet" is a state, and a
 * chip that silently omitted it would under-report.
 */
fun pvVaultSyncRows(
    vaults: List<PvVaultSummary>,
    states: Map<String, PvVaultSyncState>,
): List<PvVaultSyncRow> = vaults.map { vault ->
    val state = states[vault.id] ?: PvVaultSyncState(vault.id)
    PvVaultSyncRow(
        vaultId = vault.id,
        name = btSanitizeUntrustedLabel(vault.name),
        media = vault.media,
        status = state.status,
        perMedium = state.perMedium,
        lastSyncedAtMs = state.lastSyncedAtMs,
        pendingDocs = state.pendingDocIds.size,
        keptCandidates = vault.keptCandidates,
    )
}

/**
 * The one state the chip shows, across every vault.
 *
 * Delegates to the engine's fold: §14's order (attention > syncing > locked >
 * synced) is the same "most actionable first" rule
 * [PvVaultSyncState.Companion.fold] already implements across media, and two
 * orderings of the same five states would be one ordering too many.
 */
fun pvAggregateSyncStatus(rows: List<PvVaultSyncRow>): PvVaultSyncStatus =
    PvVaultSyncState.fold(rows.map { it.status })

/** The vault named in the aggregate line when something needs a human (§14). */
fun pvAttentionVaultName(rows: List<PvVaultSyncRow>): String? =
    rows.firstOrNull { it.status is PvVaultSyncStatus.Error }?.name

/** How many vaults are locked on this device — the aggregate's `Locked (N)`. */
fun pvLockedVaultCount(rows: List<PvVaultSyncRow>): Int = rows.count { row ->
    val status = row.status
    status is PvVaultSyncStatus.SavedLocally && status.reason == PvSavedLocallyReason.LOCKED
}

// ── Copy ────────────────────────────────────────────────────────────────────

/** The five statuses, as one line each. `SavedLocally` speaks through its reason. */
@StringRes
fun PvVaultSyncStatus.pvLabelRes(): Int = when (this) {
    PvVaultSyncStatus.Idle -> R.string.bt_pv_sync_state_idle
    PvVaultSyncStatus.Pushing -> R.string.bt_pv_sync_state_pushing
    PvVaultSyncStatus.ConflictMerging -> R.string.bt_pv_sync_state_merging
    is PvVaultSyncStatus.SavedLocally -> reason.pvLabelRes()
    is PvVaultSyncStatus.Error -> R.string.bt_pv_sync_state_error
}

@StringRes
fun PvSavedLocallyReason.pvLabelRes(): Int = when (this) {
    PvSavedLocallyReason.LOCKED -> R.string.bt_pv_sync_saved_locked
    PvSavedLocallyReason.NO_MEDIUM -> R.string.bt_pv_sync_saved_no_medium
    PvSavedLocallyReason.OFFLINE -> R.string.bt_pv_sync_saved_offline
    PvSavedLocallyReason.RETRY_QUEUED -> R.string.bt_pv_sync_saved_retry
}

/**
 * Why a human has to look, in the words of the remedy rather than of the fault.
 *
 * One sentence per reason, because that enum exists precisely because the
 * remedies differ — collapsing them into "sync failed" would throw away the only
 * information the row carries.
 */
@StringRes
fun PvSyncFailureReason.pvLabelRes(): Int = when (this) {
    PvSyncFailureReason.TOO_LARGE -> R.string.bt_pv_sync_fail_too_large
    PvSyncFailureReason.UPDATE_REQUIRED -> R.string.bt_pv_sync_fail_update_required
    PvSyncFailureReason.CANDIDATE_KEPT -> R.string.bt_pv_sync_fail_candidate_kept
    PvSyncFailureReason.PRECONDITION_MISSING -> R.string.bt_pv_sync_fail_precondition_missing
    PvSyncFailureReason.UNMERGEABLE -> R.string.bt_pv_sync_fail_unmergeable
    PvSyncFailureReason.CONFLICT_UNRESOLVED -> R.string.bt_pv_sync_fail_conflict_unresolved
    PvSyncFailureReason.REFUSED -> R.string.bt_pv_sync_fail_refused
    PvSyncFailureReason.NOT_WRITABLE -> R.string.bt_pv_sync_fail_not_writable
}

@StringRes
fun PvMedium.pvLabelRes(): Int = when (this) {
    PvMedium.SERVER -> R.string.bt_pv_sync_medium_server
    PvMedium.DRIVE -> R.string.bt_pv_sync_medium_drive
}

/** A kept candidate's document kind — `null` when its framing was unreadable. */
@StringRes
fun pvDocKindLabelRes(wire: String?): Int = when (wire) {
    "header" -> R.string.bt_pv_doc_kind_header
    "common" -> R.string.bt_pv_doc_kind_common
    "portfolio" -> R.string.bt_pv_doc_kind_portfolio
    else -> R.string.bt_pv_doc_kind_unknown
}

// ── Surface ─────────────────────────────────────────────────────────────────

/**
 * The generalized chip's row, mounted inside the existing sync card.
 *
 * Renders nothing at all — no spacer, no divider, no empty `Column` — while the
 * program flag is off. The guard is the FIRST statement for that reason: it is
 * the whole of the byte-identity promise the enclosing card makes.
 */
@Composable
fun PvVaultSyncSection(modifier: Modifier = Modifier) {
    if (!ParanoidVaultsFlags.enabled) return
    val session by PvVaultSyncRuntime.session.collectAsStateWithLifecycle()
    val running = session ?: return
    val states by running.states.collectAsStateWithLifecycle()
    var vaults by remember { mutableStateOf<List<PvVaultSummary>>(emptyList()) }
    var open by remember { mutableStateOf(false) }

    // Re-read on every state change: a pass that kept a candidate changes a
    // count the states map knows nothing about.
    LaunchedEffect(running, states) { vaults = running.vaults() }
    if (vaults.isEmpty()) return

    val rows = pvVaultSyncRows(vaults, states)
    val aggregate = pvAggregateSyncStatus(rows)
    val bt = BtTheme.colors

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = bt.border)
    Surface(
        onClick = { open = true },
        color = Color.Transparent,
        contentColor = bt.textPrimary,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = aggregate.pvGlyph(),
                contentDescription = null,
                tint = aggregate.pvTint(),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = pvAggregateLabel(rows, aggregate),
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (open) {
        PvVaultSyncSheet(
            rows = rows,
            onRetry = { vaultId -> running.syncNow(vaultId) },
            onCandidates = { vaultId -> running.candidates(vaultId) },
            onDismiss = { open = false },
        )
    }
}

/** §14's aggregate sentence. Two of the four carry a value, so it resolves here. */
@Composable
private fun pvAggregateLabel(rows: List<PvVaultSyncRow>, aggregate: PvVaultSyncStatus): String = when {
    aggregate is PvVaultSyncStatus.Error -> stringResource(
        R.string.bt_pv_sync_aggregate_attention,
        pvAttentionVaultName(rows).orEmpty(),
    )

    aggregate is PvVaultSyncStatus.SavedLocally && aggregate.reason == PvSavedLocallyReason.LOCKED ->
        pluralStringResource(
            R.plurals.bt_pv_sync_aggregate_locked,
            pvLockedVaultCount(rows),
            pvLockedVaultCount(rows),
        )

    aggregate is PvVaultSyncStatus.SavedLocally -> stringResource(aggregate.reason.pvLabelRes())
    aggregate == PvVaultSyncStatus.Pushing -> stringResource(R.string.bt_pv_sync_aggregate_syncing)
    aggregate == PvVaultSyncStatus.ConflictMerging -> stringResource(R.string.bt_pv_sync_aggregate_merging)
    else -> stringResource(R.string.bt_pv_sync_aggregate_synced)
}

@Composable
private fun PvVaultSyncStatus.pvGlyph(): ImageVector = when (this) {
    PvVaultSyncStatus.Idle -> Icons.Outlined.CloudDone
    PvVaultSyncStatus.Pushing, PvVaultSyncStatus.ConflictMerging -> Icons.Outlined.CloudSync
    is PvVaultSyncStatus.Error -> Icons.Outlined.WarningAmber
    is PvVaultSyncStatus.SavedLocally ->
        if (reason == PvSavedLocallyReason.LOCKED) Icons.Outlined.Lock else Icons.Outlined.CloudOff
}

@Composable
private fun PvVaultSyncStatus.pvTint(): Color {
    val bt = BtTheme.colors
    return when (this) {
        PvVaultSyncStatus.Idle -> bt.gain
        is PvVaultSyncStatus.Error -> bt.loss
        else -> bt.textSecondary
    }
}

/**
 * The per-vault rows (§14's popover, as a sheet).
 *
 * State lives here rather than in the caller because all of it is the SHEET's:
 * which vault's kept copies are expanded, and what the last retry answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PvVaultSyncSheet(
    rows: List<PvVaultSyncRow>,
    onRetry: suspend (String) -> PvVaultSyncState,
    onCandidates: suspend (String) -> List<PvKeptCandidate>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<PvKeptCandidate>>(emptyList()) }
    var retrying by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<Pair<String, Int>?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = bt.textMuted) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.8f).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                // A ModalBottomSheet ships no content insets; no `ime` in the
                // union because nothing here takes text.
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text(
                text = stringResource(R.string.bt_pv_sync_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = bt.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.bt_pv_sync_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = bt.border)
                    Spacer(Modifier.height(14.dp))
                }
                PvVaultSyncSheetRow(
                    row = row,
                    retrying = retrying == row.vaultId,
                    outcomeRes = outcome?.takeIf { it.first == row.vaultId }?.second,
                    expandedCandidates = if (expanded == row.vaultId) candidates else null,
                    onRetry = {
                        retrying = row.vaultId
                        outcome = null
                        scope.launch {
                            val state = onRetry(row.vaultId)
                            retrying = null
                            // Inline, in the sheet the user is looking at — never
                            // the shell snackbar, which would land behind it.
                            outcome = row.vaultId to when (state.status) {
                                PvVaultSyncStatus.Idle -> R.string.bt_pv_sync_retry_done
                                else -> state.status.pvLabelRes()
                            }
                        }
                    },
                    onToggleCandidates = {
                        if (expanded == row.vaultId) {
                            expanded = null
                            candidates = emptyList()
                        } else {
                            expanded = row.vaultId
                            scope.launch { candidates = onCandidates(row.vaultId) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PvVaultSyncSheetRow(
    row: PvVaultSyncRow,
    retrying: Boolean,
    @StringRes outcomeRes: Int?,
    expandedCandidates: List<PvKeptCandidate>?,
    onRetry: () -> Unit,
    onToggleCandidates: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = row.status.pvGlyph(),
            contentDescription = null,
            tint = row.status.pvTint(),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            color = bt.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(row.status.pvLabelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = if (row.status is PvVaultSyncStatus.Error) bt.loss else bt.textSecondary,
        )
    }
    // The failure's own sentence: "needs attention" says nothing a person can act
    // on, and the reason is the only part of the row that names a remedy.
    (row.status as? PvVaultSyncStatus.Error)?.let { error ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(error.failure.reason.pvLabelRes()),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
        )
    }
    // Per medium, because "Google Drive · synced" above "BetterTrack · sign in
    // again" is two independent facts (§14, and the v1 rail's own lesson).
    row.media.forEach { medium ->
        val status = row.perMedium[medium]
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(medium.pvLabelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(status?.pvLabelRes() ?: R.string.bt_pv_sync_saved_no_medium),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
    row.lastSyncedAtMs?.let { at ->
        Spacer(Modifier.height(4.dp))
        Text(
            // Silence on null rather than "Never": a vault whose first pass has
            // not finished has no last-sync fact, and inventing one to print is
            // how a chip starts lying.
            text = stringResource(R.string.bt_pv_sync_last, pvFormatSyncTime(at)),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
    if (outcomeRes != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(outcomeRes),
            style = MaterialTheme.typography.bodySmall,
            color = bt.goldSoft,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.canRetry) {
            TextButton(onClick = onRetry, enabled = !retrying) {
                Text(
                    text = stringResource(R.string.bt_action_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (retrying) bt.textMuted else bt.goldInk,
                )
            }
        }
        if (row.keptCandidates > 0) {
            TextButton(onClick = onToggleCandidates) {
                Text(
                    text = pluralStringResource(
                        R.plurals.bt_pv_sync_candidates,
                        row.keptCandidates,
                        row.keptCandidates,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = bt.goldInk,
                )
            }
        }
    }
    if (expandedCandidates != null) PvKeptCandidates(expandedCandidates)
}

/**
 * **The restore picker's surface, in its minimal form** (§6 keeps them, §16 says
 * what they are for).
 *
 * It lists what was kept and nothing more: kind, version and the refusal's own
 * words. There is deliberately **no restore action** — restoring a candidate is
 * §16/§7 work (it re-enters bytes into a vault, which is a write behind the
 * media/retirement gates), and a button that looked like it could would be the
 * cruellest possible place to be wrong. The closing sentence says so out loud
 * instead of leaving the user to discover it.
 */
@Composable
private fun PvKeptCandidates(candidates: List<PvKeptCandidate>) {
    val bt = BtTheme.colors
    Spacer(Modifier.height(6.dp))
    Surface(color = bt.surface, shape = BtShapes.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (candidates.isEmpty()) {
                Text(
                    text = stringResource(R.string.bt_pv_sync_candidates_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textSecondary,
                )
            }
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(pvDocKindLabelRes(candidate.docKind)),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = candidate.docVersion
                            ?.let { stringResource(R.string.bt_pv_sync_candidate_version, it) }
                            ?: stringResource(R.string.bt_pv_sync_candidate_version_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    // The refusal's own words. Untrusted: it can quote bytes a
                    // medium served, so it is sanitized like any foreign label.
                    text = btSanitizeUntrustedLabel(candidate.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.bt_pv_sync_candidates_note),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
        }
    }
}

/**
 * A sync time, in the reader's own locale.
 *
 * `MEDIUM` date with a `SHORT` time — the pairing `AboutScreen` and the export
 * screen already use. A bare medium date would print "2 Sept 2026" for something
 * that happened four minutes ago, which is a worse answer than no answer.
 */
@Composable
private fun pvFormatSyncTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
