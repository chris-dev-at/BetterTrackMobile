package at.bettertrack.app.ui.cash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The cash TRANSACTIONS subpage (owner order 2026-08-16): the movement stream,
 * moved off the cash overview onto a page of its own — same doorway pattern the
 * overview's other subpages use.
 *
 * What lives here is the SYNCED ledger: the filter rail, the selection's
 * statistics, the export, the movement rows, and the correction surfaces that
 * act on synced rows (edit / delete / tags). Queued entries stay on the overview
 * beside the sheets that made them — a pending op is not a transaction yet.
 *
 * ## Filters v2 (owner 2026-08-17: *"the current filters are too basic"*)
 *
 * v1 was three permanent chip rows — single-select source, additive tags, four
 * preset windows — stacked above the list, and by the owner's count that was
 * both too little filtering and too much furniture. v2 follows the commissioned
 * study (`DESIGN_NOTES_LEDGER.md`):
 *
 *  - **one 56dp rail** of three tokens, pinned under the app bar, where three
 *    rows used to eat ~150dp of a 915dp screen;
 *  - each token opens a **bottom sheet** that stages a multi-select and commits
 *    with a button previewing its own result count;
 *  - the date facet gains an explicit **custom range** beside the presets, and
 *    every window — preset included — resolves to a dated token;
 *  - the roll-up gains average, largest, transfers and a per-tag breakdown;
 *  - the whole selection can be **exported** to CSV or PDF.
 *
 * [initialSourceId] carries the overview switcher's selection through, so this
 * list opens narrowed to exactly what the user was looking at when they tapped
 * the door. [initialMonth] (`YYYY-MM`) does the same for the cash-flow chart's
 * selected bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashLedgerScreen(
    routePortfolioId: String?,
    initialSourceId: String?,
    initialMonth: String?,
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
    /** Which filter sheet is open, if any. */
    var sheet by remember { mutableStateOf<LedgerSheet?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }

    val active = activeSources(sources)
    val sourceNames = sources.associate { it.id to it.name }

    // ── The selection ───────────────────────────────────────────────────────
    //
    // Held as ONE encoded string so a rotation restores all five parts together
    // or none of them: a restore that brought back the custom dates but lost the
    // window would silently widen a narrowed ledger, which is the exact failure
    // the v1 comment here was already worried about.
    var encoded by rememberSaveable {
        mutableStateOf(
            encodeCashSelection(
                CashLedgerSelection(
                    sourceIds = setOfNotNull(initialSourceId),
                    window = if (initialMonth != null) CashLedgerWindow.CUSTOM else CashLedgerWindow.ALL,
                    customStart = initialMonth?.let { trendMonthRange(it)?.first },
                    customEnd = initialMonth?.let { trendMonthRange(it)?.second },
                ),
            ),
        )
    }
    val selection = remember(encoded) { decodeCashSelection(encoded) }
    fun apply(next: CashLedgerSelection) {
        encoded = encodeCashSelection(next)
    }

    // The VM narrows `movements` by its own single-source filter, which v2 has
    // outgrown — the multi-source facet lives in this screen. So the VM's filter
    // is released the moment this screen mounts (its seed has already been read
    // into the selection above) and the whole ledger arrives here to be filtered
    // once, by one function.
    LaunchedEffect(Unit) { vm.setSourceFilter(null) }

    val zone = remember { ZoneId.systemDefault() }
    // `nowMs` is remembered per selection rather than read on every
    // recomposition: a window boundary that moved while the user was scrolling
    // would drop rows out from under them mid-gesture.
    val nowMs = remember(selection.window) { System.currentTimeMillis() }
    val today = remember(nowMs) { Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() }
    val latestBooked = remember(movements) {
        movements.maxOfOrNull { it.executedAtMs }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }
    val range = remember(selection, today) { resolveCashRange(selection, today) }
    val shown = remember(movements, selection, nowMs) {
        filterCashMovements(movements, selection, nowMs, zone)
    }
    val stats = remember(shown) { cashLedgerStats(shown) }

    // Only tags the ledger actually uses are offered. A filter sheet listing
    // every tag the account owns would mostly offer rows that select nothing,
    // and on this page the tag set is a property of the movements.
    val tagsInLedger = remember(movements, tagsById) {
        movements.flatMap { decodeTagIds(it.tagIds) }
            .distinct()
            .mapNotNull { tagsById[it] }
            .sortedBy { it.name.lowercase() }
    }
    val hasUntagged = remember(movements) { movements.any { decodeTagIds(it.tagIds).isEmpty() } }

    val untaggedLabel = stringResource(R.string.bt_cash_summary_untagged)
    val tagLabels = remember(tagsInLedger, untaggedLabel) {
        tagsInLedger.associate { it.id to it.name } + (CASH_UNTAGGED_KEY to untaggedLabel)
    }
    val selectedSourceNames = selection.sourceIds.mapNotNull { sourceNames[it] }.sorted()
    val selectedTagNames = selection.tagIds.map { tagLabels[it] ?: it }.sorted()

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

    val resetAll = {
        apply(CashLedgerSelection())
    }

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
                action = {
                    // Persistent, and DISABLED rather than hidden when the
                    // selection is empty: an action that vanishes teaches the
                    // user it does not exist, where a disabled one that says why
                    // teaches them what to change.
                    IconButton(
                        onClick = { exporting = true },
                        enabled = shown.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(
                                if (shown.isEmpty()) {
                                    R.string.bt_ledger_export_disabled_cd
                                } else {
                                    R.string.bt_ledger_export_title
                                },
                            ),
                            tint = if (shown.isEmpty()) bt.textMuted else bt.textSecondary,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs, onClick = onOpenPendingSync)

            // ── The filter rail ─────────────────────────────────────────────
            //
            // ONE row, pinned above the list rather than scrolling with it. The
            // study's reasoning, and it survives contact with the phone: the
            // active definition of what you are looking at must stay readable
            // while you scroll the thing it defines. It scrolls HORIZONTALLY, so
            // its height is fixed no matter how many facets are active.
            LedgerFilterRail(
                selection = selection,
                range = range,
                locale = locale,
                sourceLabel = selectedSourceNames.singleOrNull(),
                tagLabel = selectedTagNames.singleOrNull(),
                showSources = active.size > 1,
                showTags = tagsInLedger.isNotEmpty() || hasUntagged,
                onOpen = { sheet = it },
                onClearDate = { apply(selection.copy(window = CashLedgerWindow.ALL)) },
                onClearSources = { apply(selection.copy(sourceIds = emptySet())) },
                onClearTags = { apply(selection.copy(tagIds = emptySet())) },
                onResetAll = resetAll,
            )

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
                    item(key = "summary") {
                        Text(
                            // Joined in code rather than through a format
                            // string: the separator is punctuation, not copy,
                            // and both halves are already translated resources.
                            text = if (selection.isActive) {
                                pluralStringResource(
                                    R.plurals.bt_cash_summary_movements,
                                    shown.size,
                                    shown.size,
                                ) + " \u00b7 " + pluralStringResource(
                                    R.plurals.bt_ledger_filters_active,
                                    selection.facetCount,
                                    selection.facetCount,
                                )
                            } else {
                                pluralStringResource(
                                    R.plurals.bt_cash_summary_movements,
                                    shown.size,
                                    shown.size,
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = bt.textMuted,
                        )
                    }

                    // The roll-up for whatever the rail above left standing. It
                    // sits under the filters and above the list so it reads as
                    // the answer to the selection, and it is labelled for that
                    // selection — never as a portfolio figure.
                    if (shown.isNotEmpty()) {
                        item(key = "stats") {
                            CashLedgerStatsCard(
                                stats = stats,
                                narrowed = selection.isActive,
                                expanded = statsExpanded,
                                tagLabels = tagLabels,
                                largestLabel = largestMovementLabel(stats.largest),
                                onToggleDetails = { statsExpanded = !statsExpanded },
                                onReset = resetAll,
                                onExport = { exporting = true },
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
                        // matches this selection" is a fact about the filters
                        // they just set — and only the second one has a way out,
                        // which it carries.
                        BtListSurface.EMPTY -> item(key = "movements-empty") {
                            if (selection.isActive) {
                                BtEmptyState(
                                    icon = Icons.Outlined.AccountBalanceWallet,
                                    title = stringResource(R.string.bt_ledger_no_match_title),
                                    message = stringResource(R.string.bt_ledger_no_match_message),
                                    action = {
                                        BtSecondaryButton(
                                            text = stringResource(R.string.bt_ledger_reset_filters),
                                            onClick = resetAll,
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

    // ── Filter sheets ───────────────────────────────────────────────────────

    when (sheet) {
        LedgerSheet.DATE -> CashDateRangeSheet(
            window = selection.window,
            customStart = selection.customStart,
            customEnd = selection.customEnd,
            today = today,
            latestBooked = latestBooked,
            locale = locale,
            resultCount = { w, s, e ->
                filterCashMovements(
                    movements,
                    selection.copy(window = w, customStart = s, customEnd = e),
                    nowMs,
                    zone,
                ).size
            },
            onApply = { w, s, e ->
                apply(selection.copy(window = w, customStart = s, customEnd = e))
                sheet = null
            },
            onDismiss = { sheet = null },
        )

        LedgerSheet.SOURCES -> {
            val counts = remember(movements, selection, nowMs) {
                cashSourceCounts(movements, selection, nowMs, zone)
            }
            val available = active.map { it.id }.toSet()
            CashFacetSheet(
                title = stringResource(R.string.bt_ledger_facet_sources),
                options = active.map {
                    CashFacetOption(it.id, it.name, counts[it.id] ?: 0)
                },
                selected = selection.sourceIds,
                searchLabel = stringResource(R.string.bt_ledger_search_sources),
                emptySearchLabel = stringResource(R.string.bt_ledger_no_sources_found),
                resultCount = { staged ->
                    filterCashMovements(
                        movements,
                        selection.copy(sourceIds = normalizeCashFacet(staged, available)),
                        nowMs,
                        zone,
                    ).size
                },
                onApply = {
                    apply(selection.copy(sourceIds = normalizeCashFacet(it, available)))
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
        }

        LedgerSheet.TAGS -> {
            val counts = remember(movements, selection, nowMs) {
                cashTagCounts(movements, selection, nowMs, zone)
            }
            val options = buildList {
                tagsInLedger.forEach { add(CashFacetOption(it.id, it.name, counts[it.id] ?: 0)) }
                if (hasUntagged) {
                    add(
                        CashFacetOption(
                            CASH_UNTAGGED_KEY,
                            untaggedLabel,
                            counts[CASH_UNTAGGED_KEY] ?: 0,
                        ),
                    )
                }
            }
            val available = options.map { it.key }.toSet()
            CashFacetSheet(
                title = stringResource(R.string.bt_ledger_facet_tags),
                options = options,
                selected = selection.tagIds,
                searchLabel = stringResource(R.string.bt_ledger_search_tags),
                emptySearchLabel = stringResource(R.string.bt_ledger_no_tags_found),
                resultCount = { staged ->
                    filterCashMovements(
                        movements,
                        selection.copy(tagIds = normalizeCashFacet(staged, available)),
                        nowMs,
                        zone,
                    ).size
                },
                onApply = {
                    apply(selection.copy(tagIds = normalizeCashFacet(it, available)))
                    sheet = null
                },
                onDismiss = { sheet = null },
            )
        }

        null -> Unit
    }

    if (exporting && shown.isNotEmpty()) {
        CashExportSheet(
            movements = shown,
            range = range,
            selectedSourceNames = selectedSourceNames,
            selectedTagNames = selectedTagNames,
            sourceNames = sourceNames,
            tagNames = tagLabels,
            stats = stats,
            onDismiss = { exporting = false },
        )
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

/** Which filter sheet is open. */
private enum class LedgerSheet { DATE, SOURCES, TAGS }

// ── The rail ────────────────────────────────────────────────────────────────

/**
 * The one filter row: three tokens and a global reset.
 *
 * A token is idle (the facet's name) or applied (its resolved value plus a
 * remove affordance that clears only that facet). The remove is a real 48dp
 * target inside the token rather than a 12dp glyph, because clearing one facet
 * is the single most-repeated action on a filtered list.
 *
 * A facet is HIDDEN when it genuinely cannot change the result — one source is
 * not a choice — but never once it is active, because an active filter the user
 * cannot see is an active filter they cannot remove.
 */
@Composable
private fun LedgerFilterRail(
    selection: CashLedgerSelection,
    range: CashDateRange?,
    locale: java.util.Locale,
    sourceLabel: String?,
    tagLabel: String?,
    showSources: Boolean,
    showTags: Boolean,
    onOpen: (LedgerSheet) -> Unit,
    onClearDate: () -> Unit,
    onClearSources: () -> Unit,
    onClearTags: () -> Unit,
    onResetAll: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterToken(
            label = if (selection.dateActive && range != null) {
                cashRangeToken(range, locale)
            } else {
                stringResource(R.string.bt_ledger_facet_date)
            },
            applied = selection.dateActive,
            removeCd = stringResource(R.string.bt_ledger_remove_date),
            onClick = { onOpen(LedgerSheet.DATE) },
            onRemove = onClearDate,
        )
        if (showSources || selection.sourceIds.isNotEmpty()) {
            FilterToken(
                label = when {
                    selection.sourceIds.isEmpty() -> stringResource(R.string.bt_ledger_facet_sources)
                    sourceLabel != null -> sourceLabel
                    else -> pluralStringResource(
                        R.plurals.bt_ledger_token_sources,
                        selection.sourceIds.size,
                        selection.sourceIds.size,
                    )
                },
                applied = selection.sourceIds.isNotEmpty(),
                removeCd = stringResource(R.string.bt_ledger_remove_sources),
                onClick = { onOpen(LedgerSheet.SOURCES) },
                onRemove = onClearSources,
            )
        }
        if (showTags || selection.tagIds.isNotEmpty()) {
            FilterToken(
                label = when {
                    selection.tagIds.isEmpty() -> stringResource(R.string.bt_ledger_facet_tags)
                    tagLabel != null -> tagLabel
                    else -> pluralStringResource(
                        R.plurals.bt_ledger_token_tags,
                        selection.tagIds.size,
                        selection.tagIds.size,
                    )
                },
                applied = selection.tagIds.isNotEmpty(),
                removeCd = stringResource(R.string.bt_ledger_remove_tags),
                onClick = { onOpen(LedgerSheet.TAGS) },
                onRemove = onClearTags,
            )
        }
        if (selection.isActive) {
            Text(
                text = stringResource(R.string.bt_ledger_reset_all),
                style = MaterialTheme.typography.labelMedium,
                color = bt.goldInk,
                maxLines = 1,
                modifier = Modifier
                    .clip(BtShapes.pill)
                    .clickable(onClick = onResetAll)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

/** One token: the facet, and — when applied — the way to clear just this one. */
@Composable
private fun FilterToken(
    label: String,
    applied: Boolean,
    removeCd: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        BtChip(text = label, selected = applied, onClick = onClick)
        if (applied) {
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = removeCd,
                    tint = bt.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The resolved date token: `1. Juni – 16. Aug.`
 *
 * The year is omitted when both endpoints are in the current one and carried
 * when they are not, and a same-day range collapses to a single date. The rail
 * is 412dp wide with two more tokens on it, so every character the token can
 * honestly drop is one the other facets get to keep.
 */
@Composable
internal fun cashRangeToken(range: CashDateRange, locale: java.util.Locale): String {
    val thisYear = LocalDate.now().year
    val sameYear = range.start.year == range.end.year && range.start.year == thisYear
    val short = DateTimeFormatter.ofPattern(if (sameYear) "d. MMM" else "d. MMM yyyy", locale)
    val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    if (range.start == range.end) return range.start.format(full)
    return range.start.format(short) + " – " + range.end.format(short)
}

// ── The statistics card ─────────────────────────────────────────────────────

/**
 * The roll-up of the current selection (owner ask 2026-08-16, deepened 2026-08-17).
 *
 * ## What it is allowed to claim
 *
 * Bookkeeping over exactly the rows drawn beneath it, and nothing else. The
 * heading says *for this selection* in both languages and the card carries the
 * count, because the one way a figure like this misleads is by being read as a
 * portfolio number — and the platform's own cash aggregates are month-scoped, so
 * there is no server total for an arbitrary source × tag × window slice to defer
 * to. Every addend is a server-recorded amount; nothing here is valuation math,
 * nothing here is a return, and nothing here touches the portfolio totals. The
 * study's own rule: *"no benchmark, return, percent change, market chart, or
 * trend arrow appears."*
 *
 * ## The hierarchy
 *
 * Netto is the headline, because "which way did it go" is what a selection
 * answers. Zufluss and Abfluss are equal supporting cells; average, largest and
 * transfers are compact facts; the per-tag breakdown is behind a disclosure
 * because it is the only part that is a list rather than a number.
 *
 * In and Out both print as POSITIVE magnitudes under their own labels and their
 * own inks — a `−60,00 €` beneath a heading that already says "out" states the
 * sign twice. Amounts go through [MoneyText], so discreet mode masks them like
 * every other figure in the app. (The EXPORT is the documented exception; see
 * `CashExport.kt`.)
 */
@Composable
private fun CashLedgerStatsCard(
    stats: CashLedgerStats,
    narrowed: Boolean,
    expanded: Boolean,
    tagLabels: Map<String, String>,
    largestLabel: String?,
    onToggleDetails: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit,
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
            Spacer(Modifier.height(6.dp))
            // Netto leads and the count sits beside it: the primary figure and
            // its denominator, together, which is the owner's standing rule
            // about related values not feeling disconnected.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.bt_ledger_stats_net),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                    MoneyText(
                        value = stats.netEur,
                        style = BtTheme.type.moneyMedium,
                        color = when {
                            stats.netEur > 0.0 -> bt.gain
                            stats.netEur < 0.0 -> bt.loss
                            else -> bt.textPrimary
                        },
                        showSign = true,
                    )
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.bt_cash_summary_movements,
                        stats.count,
                        stats.count,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
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
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell(
                    label = stringResource(R.string.bt_ledger_stats_avg),
                    value = stats.avgAbsEur,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (stats.largest != null) {
                    StatCell(
                        label = largestLabel
                            ?: stringResource(R.string.bt_ledger_stats_largest),
                        value = stats.largest.amountEur,
                        color = if (stats.largest.amountEur < 0.0) bt.loss else bt.gain,
                        showSign = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            // Transfers are only mentioned when a complete pair is in view —
            // otherwise the line would be a permanent zero explaining a concept
            // the user's selection does not contain.
            if (stats.transferCount > 0) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.bt_ledger_stats_transfers),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    MoneyText(
                        value = stats.transferEur,
                        style = BtTheme.type.moneySmall,
                        color = bt.textSecondary,
                    )
                }
            }

            if (stats.outByTag.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (expanded) R.string.bt_ledger_stats_hide else R.string.bt_ledger_stats_show,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.goldInk,
                    modifier = Modifier
                        .clip(BtShapes.pill)
                        .clickable(onClick = onToggleDetails)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.bt_ledger_stats_out_by_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    val (head, rest) = cashTagSplitHead(stats.outByTag)
                    val peak = head.maxOfOrNull { it.count } ?: 1
                    head.forEach { row ->
                        TagSplitRow(
                            label = row.tagId?.let { tagLabels[it] ?: it }
                                ?: stringResource(R.string.bt_cash_summary_untagged),
                            count = row.count,
                            fraction = row.count.toFloat() / peak.coerceAtLeast(1),
                        )
                    }
                    if (rest > 0) {
                        TagSplitRow(
                            label = stringResource(R.string.bt_ledger_stats_other),
                            count = rest,
                            fraction = rest.toFloat() / peak.coerceAtLeast(1),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.bt_ledger_stats_tag_count_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = bt.textMuted,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.bt_ledger_export_title),
                style = MaterialTheme.typography.labelLarge,
                color = bt.goldInk,
                modifier = Modifier
                    .clip(BtShapes.pill)
                    .clickable(onClick = onExport)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** One tag row of the breakdown: a name, a quiet track, and a movement count. */
@Composable
private fun TagSplitRow(label: String, count: Int, fraction: Float) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                // Gold, never red: this is a count of movements. The study is
                // explicit that the breakdown must not become a red warning panel.
                color = bt.textMuted,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(BtShapes.pill)
                .background(bt.border),
        ) {
            Spacer(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(3.dp)
                    .clip(BtShapes.pill)
                    .background(bt.goldWashStrong),
            )
        }
    }
}

/** One figure of the roll-up: its label above, the money below. */
@Composable
private fun StatCell(
    label: String,
    value: Double,
    color: Color,
    modifier: Modifier = Modifier,
    showSign: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = BtTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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

/**
 * `Größte · Miete` — the biggest movement named by its own description.
 *
 * Returns null when the row has no description, so the cell falls back to the
 * plain "Largest transaction" label rather than printing a dangling separator.
 */
@Composable
private fun largestMovementLabel(movement: CashMovementEntity?): String? {
    val note = movement?.note?.trim().orEmpty()
    if (note.isEmpty()) return null
    return stringResource(R.string.bt_ledger_stats_largest_named, note)
}

/** The chip label for a ledger window. `internal` so the mapping is testable. */
internal fun cashLedgerWindowLabel(window: CashLedgerWindow): Int = when (window) {
    CashLedgerWindow.ALL -> R.string.bt_ledger_window_all
    CashLedgerWindow.DAYS_30 -> R.string.bt_ledger_window_30d
    CashLedgerWindow.DAYS_90 -> R.string.bt_ledger_window_90d
    CashLedgerWindow.YEAR_1 -> R.string.bt_ledger_window_1y
    CashLedgerWindow.CUSTOM -> R.string.bt_ledger_window_custom
}
