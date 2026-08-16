package at.bettertrack.app.ui.cash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtListSurface
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.resolveListSurface
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.shell.BtSheetRefreshBox
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.ZoneId

/**
 * The cash TRANSACTIONS subpage (owner order 2026-08-16): the movement stream,
 * moved off the cash overview onto a page of its own — same doorway pattern the
 * overview's other subpages use.
 *
 * What lives here is the SYNCED ledger: filter chips, the movement rows, and
 * the correction surfaces that act on synced rows (edit / delete / tags).
 * Queued entries stay on the overview beside the sheets that made them — a
 * pending op is not a transaction yet.
 *
 * [initialSourceId] carries the overview switcher's selection through, so this
 * list opens narrowed to exactly what the user was looking at when they tapped
 * the door.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashLedgerScreen(
    routePortfolioId: String?,
    initialSourceId: String?,
    onBack: () -> Unit,
    onOpenPendingSync: () -> Unit,
) {
    val vm: CashViewModel = viewModel(key = "cash-ledger") {
        CashViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            routePortfolioId = routePortfolioId,
            classification = AppGraph.cashClassificationRepository,
            initialSourceId = initialSourceId,
        )
    }

    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sourcesLoaded by vm.sourcesLoaded.collectAsStateWithLifecycle()
    val ledgerError by vm.ledgerError.collectAsStateWithLifecycle()
    val ledgerLoaded by vm.ledgerLoaded.collectAsStateWithLifecycle()
    val tagsById by vm.tagsById.collectAsStateWithLifecycle()
    val correctionBusy by vm.correctionBusy.collectAsStateWithLifecycle()
    val correctionNotice by vm.correctionNotice.collectAsStateWithLifecycle()
    val resolvedPid by vm.portfolioId.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    /** The synced movement being corrected (the sheet looks it up live). */
    var editTargetId by remember { mutableStateOf<String?>(null) }
    /** The synced movement whose tag set is being edited. */
    var tagTarget by remember { mutableStateOf<CashMovementEntity?>(null) }
    /** The synced movement awaiting delete confirmation. */
    var deleteTarget by remember { mutableStateOf<CashMovementEntity?>(null) }

    val active = activeSources(sources)
    val sourceNames = sources.associate { it.id to it.name }

    // ── The selection (owner ask 2026-08-16) ────────────────────────────────
    //
    // Source × tags × window, held as ONE value so the list, the roll-up and
    // the reset control cannot disagree about what is currently being shown.
    // The source half is seeded from the overview's switcher (the door carried
    // it through) and then owned here.
    //
    // `rememberSaveable` for the tag/window halves: rotating the phone must not
    // silently widen a narrowed ledger back to everything.
    var tagFilter by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var window by rememberSaveable { mutableStateOf(CashLedgerWindow.ALL) }
    val selection = CashLedgerSelection(
        sourceId = sourceFilter,
        tagIds = tagFilter,
        window = window,
    )
    // `nowMs` is remembered per composition of this screen rather than read on
    // every recomposition: a window boundary that moved while the user was
    // scrolling would drop rows out from under them mid-gesture.
    val zone = remember { ZoneId.systemDefault() }
    val nowMs = remember(selection.window) { System.currentTimeMillis() }
    val shown = remember(movements, selection, nowMs) {
        filterCashMovements(movements, selection, nowMs, zone)
    }
    val stats = remember(shown) { cashLedgerStats(shown) }
    // Only tags the ledger actually uses are offered. A filter row listing every
    // tag the account owns would mostly offer rows that select nothing, and on
    // this page the tag set is a property of the movements, not of the user.
    val tagsInLedger = remember(movements, tagsById) {
        movements.flatMap { decodeTagIds(it.tagIds) }
            .distinct()
            .mapNotNull { tagsById[it] }
            .sortedBy { it.name.lowercase() }
    }

    // A filter can empty the visible list all by itself, and a failed fetch must
    // not be blamed for a view the user narrowed on purpose.
    val ledgerFailure = ledgerError.takeIf { !selection.isActive }
    val ledgerSurface = resolveListSurface(
        hasContent = shown.isNotEmpty(),
        firstLoadPending = cashLedgerPending(
            loaded = ledgerLoaded,
            hasPortfolio = resolvedPid != null,
            sourcesSeen = sourcesLoaded,
        ),
        failed = ledgerFailure != null,
        isOnline = isOnline,
    )

    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_tx_title),
                subtitle = portfolioName ?: "",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs, onClick = onOpenPendingSync)

            BtSheetRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ── The filter bar (owner ask 2026-08-16) ───────────────
                    //
                    // Three rows, each an absolute single-purpose selection,
                    // and they AND together. Rows appear only when they can
                    // change anything: one source is not a choice, and a ledger
                    // whose movements carry no tags has no tag axis.
                    //
                    // Each row scrolls horizontally on its own rather than
                    // wrapping, so the bar's height is fixed no matter how many
                    // sources or tags the account has — a filter bar that grows
                    // to four lines has eaten the list it filters.
                    if (active.size > 1) {
                        item(key = "filter-sources") {
                            LedgerFilterRow {
                                BtChip(
                                    text = stringResource(R.string.bt_cash_all_sources),
                                    selected = sourceFilter == null,
                                    onClick = { vm.setSourceFilter(null) },
                                )
                                active.forEach { s ->
                                    BtChip(
                                        text = s.name,
                                        selected = sourceFilter == s.id,
                                        onClick = { vm.setSourceFilter(s.id) },
                                    )
                                }
                            }
                        }
                    }

                    if (tagsInLedger.isNotEmpty()) {
                        item(key = "filter-tags") {
                            LedgerFilterRow {
                                BtChip(
                                    text = stringResource(R.string.bt_ledger_all_tags),
                                    selected = tagFilter.isEmpty(),
                                    onClick = { tagFilter = emptySet() },
                                )
                                tagsInLedger.forEach { tag ->
                                    BtChip(
                                        text = tag.name,
                                        selected = tag.id in tagFilter,
                                        // Additive: tags are labels, so picking
                                        // a second one widens the view rather
                                        // than replacing the first.
                                        onClick = {
                                            tagFilter = if (tag.id in tagFilter) {
                                                tagFilter - tag.id
                                            } else {
                                                tagFilter + tag.id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    item(key = "filter-window") {
                        LedgerFilterRow {
                            CASH_LEDGER_WINDOWS.forEach { w ->
                                BtChip(
                                    text = stringResource(cashLedgerWindowLabel(w)),
                                    selected = window == w,
                                    onClick = { window = w },
                                )
                            }
                        }
                    }

                    // The roll-up for whatever the three rows above left
                    // standing. It sits under the filters and above the list so
                    // it reads as the answer to the selection, and it is
                    // labelled for that selection — never as a portfolio figure.
                    if (shown.isNotEmpty()) {
                        item(key = "stats") {
                            CashLedgerStatsCard(
                                stats = stats,
                                narrowed = selection.isActive,
                                onReset = {
                                    vm.setSourceFilter(null)
                                    tagFilter = emptySet()
                                    window = CashLedgerWindow.ALL
                                },
                            )
                        }
                    }

                    when (ledgerSurface) {
                        // The items() below are the CONTENT branch.
                        BtListSurface.CONTENT -> Unit

                        BtListSurface.SKELETON -> item(key = "movements-loading") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(64.dp)) }
                            }
                        }

                        // Two different emptinesses, and conflating them is how
                        // a user concludes their ledger is gone: "you have no
                        // movements" is a fact about the account, "no movement
                        // matches this selection" is a fact about the three
                        // chips they just tapped — and only the second one has
                        // a way out, which it carries.
                        BtListSurface.EMPTY -> item(key = "movements-empty") {
                            if (selection.isActive) {
                                BtEmptyState(
                                    icon = Icons.Outlined.AccountBalanceWallet,
                                    title = stringResource(R.string.bt_ledger_no_match_title),
                                    message = stringResource(R.string.bt_ledger_no_match_message),
                                    action = {
                                        BtSecondaryButton(
                                            text = stringResource(R.string.bt_ledger_reset_filters),
                                            onClick = {
                                                vm.setSourceFilter(null)
                                                tagFilter = emptySet()
                                                window = CashLedgerWindow.ALL
                                            },
                                        )
                                    },
                                )
                            } else {
                                BtEmptyState(
                                    icon = Icons.Outlined.AccountBalanceWallet,
                                    title = stringResource(R.string.bt_cash_empty_title),
                                    message = stringResource(R.string.bt_cash_empty_message),
                                )
                            }
                        }

                        BtListSurface.OFFLINE -> item(key = "movements-offline") {
                            BtOfflineState(
                                message = stringResource(R.string.bt_cash_requires_connection),
                                onRetry = { vm.refresh() },
                            )
                        }

                        BtListSurface.ERROR -> item(key = "movements-error") {
                            BtErrorState(
                                title = stringResource(R.string.bt_cash_movements_error_title),
                                message = ledgerFailure ?: BtMessage.generic,
                                onRetry = { vm.refresh() },
                            )
                        }
                    }
                    items(count = shown.size, key = { shown[it].id }) { i ->
                        val m = shown[i]
                        // Corrections are online-only and exist only for the
                        // three hand-typed kinds — a derived row gets no menu at
                        // all rather than a menu certain to be refused.
                        val correctable = isEditableCashKind(m.kind) && isOnline
                        MovementRow(
                            movement = m,
                            sourceNames = sourceNames,
                            locale = locale,
                            tagsById = tagsById,
                            onEdit = if (correctable) {
                                { editTargetId = m.id }
                            } else {
                                null
                            },
                            onEditTags = if (isOnline) {
                                { tagTarget = m }
                            } else {
                                null
                            },
                            onDelete = if (correctable) {
                                { deleteTarget = m }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    // ── Correction surfaces (moved with the stream, 2026-08-16) ─────────────

    editTargetId?.let { targetId ->
        val target = shown.firstOrNull { it.id == targetId }
        if (target == null) {
            // The row vanished under us (a refresh landed while the sheet was
            // opening). Close rather than show an editor for nothing.
            editTargetId = null
        } else {
            CashCorrectionSheet(
                vm = vm,
                movement = target,
                sources = active,
                locale = locale,
                onDismiss = {
                    editTargetId = null
                    vm.clearCorrectionNotice()
                },
            )
        }
    }

    tagTarget?.let { target ->
        CashMovementTagsSheet(
            vm = vm,
            movement = target,
            allTags = tagsById,
            onDismiss = { tagTarget = null },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!correctionBusy) deleteTarget = null },
            containerColor = bt.surfaceHigh,
            title = { Text(stringResource(R.string.bt_cash_delete_title), color = bt.textPrimary) },
            text = {
                Text(stringResource(R.string.bt_cash_delete_message), color = bt.textSecondary)
            },
            confirmButton = {
                TextButton(
                    enabled = !correctionBusy,
                    onClick = {
                        vm.deleteCorrection(target.id) { ok -> if (ok) deleteTarget = null }
                    },
                ) {
                    Text(stringResource(R.string.bt_cash_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(enabled = !correctionBusy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }

    // A refusal the user cannot fix by retrying gets its own designed state, not
    // a red line under a form field.
    correctionNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.clearCorrectionNotice() },
            containerColor = bt.surfaceHigh,
            title = {
                Text(
                    text = stringResource(
                        if (notice.notEditable) {
                            R.string.bt_cash_not_editable_title
                        } else {
                            R.string.bt_cash_correction_failed_title
                        },
                    ),
                    color = bt.textPrimary,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (notice.notEditable) {
                        Text(
                            text = stringResource(R.string.bt_cash_not_editable_hint),
                            color = bt.textSecondary,
                        )
                    }
                    Text(text = notice.message.resolveWithDiagnostic(), color = bt.textMuted)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.clearCorrectionNotice() }) {
                    Text(stringResource(R.string.bt_action_done), color = bt.goldInk)
                }
            },
        )
    }
}

/**
 * One horizontally scrolling row of filter chips.
 *
 * Extracted because the bar has three of them and they must behave identically:
 * a bar whose source row scrolls and whose tag row wrapped would grow taller as
 * the account does, and the page's job is the list underneath.
 */
@Composable
private fun LedgerFilterRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** The chip label for a ledger window. `internal` so the mapping is testable. */
internal fun cashLedgerWindowLabel(window: CashLedgerWindow): Int = when (window) {
    CashLedgerWindow.ALL -> R.string.bt_ledger_window_all
    CashLedgerWindow.DAYS_30 -> R.string.bt_ledger_window_30d
    CashLedgerWindow.DAYS_90 -> R.string.bt_ledger_window_90d
    CashLedgerWindow.YEAR_1 -> R.string.bt_ledger_window_1y
}

/**
 * The roll-up of the current selection (owner ask 2026-08-16: *"some all-around
 * stats for the selected stuff — total and total plus and total minus"*).
 *
 * ## What it is allowed to claim
 *
 * Three sums and a count, over exactly the rows drawn beneath it. The heading
 * says *for this selection* in both languages and the card carries the count,
 * because the one way a figure like this misleads is by being read as a
 * portfolio number — and the platform's own cash aggregates are month-scoped, so
 * there is no server total for an arbitrary source × tag × window slice to defer
 * to. Every addend is a server-recorded amount; nothing here is valuation math
 * and nothing here touches the portfolio totals.
 *
 * In and Out both print as POSITIVE magnitudes under their own labels and their
 * own inks — a `−60,00 €` beneath a heading that already says "out" states the
 * sign twice. Net keeps its sign, because which way it went is the whole
 * question Net answers.
 *
 * Amounts go through [MoneyText], so discreet mode masks them like every other
 * figure in the app.
 */
@Composable
private fun CashLedgerStatsCard(
    stats: CashLedgerStats,
    narrowed: Boolean,
    onReset: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bt_ledger_stats_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                    modifier = Modifier.weight(1f),
                )
                // The way out of a narrowed view, next to the thing that tells
                // the user they are IN one. Absent when nothing is narrowed, so
                // it never offers to undo a state that does not exist.
                if (narrowed) {
                    Text(
                        text = stringResource(R.string.bt_ledger_reset_filters),
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.goldInk,
                        modifier = Modifier
                            .clip(BtShapes.pill)
                            .clickable(onClick = onReset)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    label = stringResource(R.string.bt_ledger_stats_in),
                    value = stats.inflowEur,
                    color = bt.gain,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = stringResource(R.string.bt_ledger_stats_out),
                    value = stats.outflowEur,
                    color = bt.loss,
                    modifier = Modifier.weight(1f),
                )
                StatCell(
                    label = stringResource(R.string.bt_ledger_stats_net),
                    value = stats.netEur,
                    // Net is the only signed figure, so it is the only one whose
                    // ink is a verdict rather than a fixed direction label.
                    color = when {
                        stats.netEur > 0.0 -> bt.gain
                        stats.netEur < 0.0 -> bt.loss
                        else -> bt.textSecondary
                    },
                    showSign = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = pluralStringResource(
                    R.plurals.bt_cash_summary_movements,
                    stats.count,
                    stats.count,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
        }
    }
}

/** One figure of the roll-up: its label above, the money below. */
@Composable
private fun StatCell(
    label: String,
    value: Double,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    showSign: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BtTheme.colors.textMuted,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        MoneyText(
            value = value,
            style = BtTheme.type.moneySmall,
            color = color,
            showSign = showSign,
        )
    }
}
