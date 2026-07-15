package at.bettertrack.app.ui.cash

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.db.BtDatabase
import at.bettertrack.app.data.db.CashMovementEntity
import at.bettertrack.app.data.db.CashSourceEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.CashOpPayload
import at.bettertrack.app.sync.CashTransferOpPayload
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtDateField
import at.bettertrack.app.ui.components.BtDatePickerDialog
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.portfolio.PendingStatusBadge
import at.bettertrack.app.ui.portfolio.PendingUiStatus
import at.bettertrack.app.ui.portfolio.PortfolioOverviewViewModel
import at.bettertrack.app.ui.portfolio.formatTxDate
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.shell.OfflineBanner
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The Step-9 cash screen (spec §6.3): Main + named sources with per-source
 * balances and typed labels, the movement stream (filterable per source),
 * deposit / withdraw / transfer with live balance-after previews (cached
 * balances — the server stays final, §7.3), and source management
 * (create / rename / archive — online-only per §7.2). All three movement
 * types enqueue through the M3 queue and work offline.
 */

/** Which sheet is open. */
private sealed interface CashSheet {
    data class Entry(val deposit: Boolean, val editOpId: Long? = null) : CashSheet
    data class Transfer(val editOpId: Long? = null) : CashSheet
}

@OptIn(ExperimentalCoroutinesApi::class)
class CashViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    private val db: BtDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val json: Json,
    routePortfolioId: String?,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val portfolioId: StateFlow<String?> =
        combine(repo.portfolios, repo.selectedPortfolioId) { all, stored ->
            routePortfolioId ?: PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), routePortfolioId)

    val portfolioName: StateFlow<String?> =
        combine(repo.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Server-computed cash roll-up of the portfolio (§7.1). */
    val totalCashEur: StateFlow<Double?> =
        combine(repo.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.totals?.cashEur
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sources: StateFlow<List<CashSourceEntity>> = portfolioId
        .flatMapLatest { pid -> if (pid == null) flowOf(emptyList()) else repo.cashSources(pid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Movement-list source filter; null = all sources. */
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()

    val movements: StateFlow<List<CashMovementEntity>> = combine(
        portfolioId.flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList()) else repo.cashMovements(pid)
        },
        _sourceFilter,
    ) { rows, filter ->
        if (filter == null) rows else rows.filter { it.sourceId == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Queued cash ops of this portfolio (§7.4 pending rows). */
    val pendingRows: StateFlow<List<PendingCashRow>> = combine(
        db.syncOpDao().observeAll(),
        portfolioId,
    ) { ops, pid ->
        if (pid == null) emptyList() else decodePendingCashRows(ops, json, pid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Busy/error state of the online-only source-management actions. */
    private val _manageBusy = MutableStateFlow(false)
    val manageBusy: StateFlow<Boolean> = _manageBusy.asStateFlow()
    private val _manageError = MutableStateFlow<String?>(null)
    val manageError: StateFlow<String?> = _manageError.asStateFlow()

    /** Sheet submission state. */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()
    private val _sheetError = MutableStateFlow<String?>(null)
    val sheetError: StateFlow<String?> = _sheetError.asStateFlow()

    private var refreshedOnce = false

    init {
        viewModelScope.launch {
            portfolioId.collect { pid ->
                if (pid != null && !refreshedOnce) {
                    refreshedOnce = true
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val pid = portfolioId.value ?: return
        viewModelScope.launch {
            _refreshing.value = true
            repo.refreshCash(pid)
            repo.refreshPortfolioDetail(pid)
            _refreshing.value = false
        }
    }

    fun setSourceFilter(sourceId: String?) {
        _sourceFilter.value = if (_sourceFilter.value == sourceId) null else sourceId
    }

    fun clearSheetError() {
        _sheetError.value = null
    }

    fun clearManageError() {
        _manageError.value = null
    }

    // ── Source management (online-only, §7.2) ───────────────────────────────

    fun createSource(name: String, type: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.createCashSource(portfolioId.value ?: return@manageAction null, name.trim(), type) }

    fun renameSource(sourceId: String, name: String, type: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) {
            repo.updateCashSource(portfolioId.value ?: return@manageAction null, sourceId, name.trim(), type)
        }

    fun archiveSource(sourceId: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.archiveCashSource(portfolioId.value ?: return@manageAction null, sourceId) }

    fun restoreSource(sourceId: String, onDone: (Boolean) -> Unit) =
        manageAction(onDone) { repo.restoreCashSource(portfolioId.value ?: return@manageAction null, sourceId) }

    private fun manageAction(onDone: (Boolean) -> Unit, action: suspend () -> BtResult<Unit>?) {
        if (_manageBusy.value) return
        viewModelScope.launch {
            _manageBusy.value = true
            _manageError.value = null
            val r = action()
            if (r is BtResult.Err) _manageError.value = r.error.userMessage
            _manageBusy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    // ── Movement writes (offline-capable via the queue, §7.2) ────────────────

    /**
     * Deposit / withdraw. [editOpId] rebinds a queued op in place (same client
     * UUID — §7.3 edit-and-retry). Returns via [onDone]: true = sheet closes.
     */
    fun submitEntry(
        deposit: Boolean,
        amount: Double,
        sourceId: String?,
        note: String?,
        /** Chosen movement date; today omits `executedAt`, a past day backdates it. */
        date: LocalDate,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val pid = portfolioId.value ?: return
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            val payload = CashOpPayload(
                amountEur = amount,
                executedAt = cashExecutedAtOrNull(date),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                sourceId = sourceId,
            )
            val payloadJson = json.encodeToString(CashOpPayload.serializer(), payload)
            val type = if (deposit) OpType.CASH_DEPOSIT else OpType.CASH_WITHDRAW
            submitViaQueue(pid, type, payloadJson, editOpId, onDone)
            _submitting.value = false
        }
    }

    /** Transfer between two sources (§6.3), queued like every ledger event. */
    fun submitTransfer(
        fromSourceId: String,
        toSourceId: String,
        amount: Double,
        note: String?,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val pid = portfolioId.value ?: return
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            val payload = CashTransferOpPayload(
                fromSourceId = fromSourceId,
                toSourceId = toSourceId,
                amountEur = amount,
                executedAt = cashExecutedAtNow(),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
            val payloadJson = json.encodeToString(CashTransferOpPayload.serializer(), payload)
            submitViaQueue(pid, OpType.CASH_TRANSFER, payloadJson, editOpId, onDone)
            _submitting.value = false
        }
    }

    private suspend fun submitViaQueue(
        pid: String,
        type: OpType,
        payloadJson: String,
        editOpId: Long?,
        onDone: (Boolean) -> Unit,
    ) {
        val opId = if (editOpId == null) {
            engine.enqueue(type, pid, payloadJson).id
        } else {
            if (!engine.updateOp(editOpId, payloadJson)) {
                onDone(true) // resolved meanwhile — nothing to edit
                return
            }
            editOpId
        }
        if (isOnline.value) {
            try {
                engine.drain()
            } catch (_: Exception) {
                // stays queued; WorkManager resumes
            }
        }
        val after = db.syncOpDao().getById(opId)
        when (after?.status) {
            OpStatus.NEEDS_ATTENTION.wire -> {
                _sheetError.value = after.serverError ?: "BetterTrack rejected this entry."
                onDone(false)
            }

            else -> {
                if (after?.status == OpStatus.PENDING.wire || after?.status == OpStatus.IN_FLIGHT.wire) {
                    scheduler.scheduleDrain()
                }
                onDone(true)
            }
        }
    }

    /** Prefill for editing a queued cash op (pending / needs-attention). */
    suspend fun loadPendingRow(opId: Long): PendingCashRow? =
        db.syncOpDao().getById(opId)?.let { decodePendingCashRow(it, json) }
}

// ═════════════════════════════════ UI ═══════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashScreen(
    routePortfolioId: String?,
    editOpId: Long?,
    onBack: () -> Unit,
    onOpenPendingSync: () -> Unit,
) {
    val vm: CashViewModel = viewModel {
        CashViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            routePortfolioId = routePortfolioId,
        )
    }

    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val totalCashEur by vm.totalCashEur.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()
    val movements by vm.movements.collectAsStateWithLifecycle()
    val pendingRows by vm.pendingRows.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val manageBusy by vm.manageBusy.collectAsStateWithLifecycle()
    val manageError by vm.manageError.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var sheet by remember { mutableStateOf<CashSheet?>(null) }
    var newSourceOpen by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CashSourceEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<CashSourceEntity?>(null) }
    /** Prefill when editing a queued op. */
    var editPrefill by remember { mutableStateOf<PendingCashRow?>(null) }

    val active = activeSources(sources)
    val archived = sources.filter { it.archivedAt != null }
    val sourceNames = sources.associate { it.id to it.name }

    // Entry via the pending screen's "Edit & retry" (deep-linked edit).
    LaunchedEffect(editOpId) {
        if (editOpId != null) {
            val row = vm.loadPendingRow(editOpId)
            if (row != null && (row.status == PendingUiStatus.PENDING || row.status == PendingUiStatus.NEEDS_ATTENTION)) {
                editPrefill = row
                sheet = when (row.type) {
                    OpType.CASH_TRANSFER -> CashSheet.Transfer(editOpId)
                    OpType.CASH_WITHDRAW -> CashSheet.Entry(deposit = false, editOpId = editOpId)
                    else -> CashSheet.Entry(deposit = true, editOpId = editOpId)
                }
            }
        }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.bt_cash_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = bt.textPrimary,
                        )
                        portfolioName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                            tint = bt.textSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs, onClick = onOpenPendingSync)

            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = bt.surface,
                        color = bt.gold,
                    )
                },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Total cash (server roll-up of all sources, §7.1).
                    item(key = "hero") {
                        Column {
                            Text(
                                text = stringResource(R.string.bt_cash_total),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                            )
                            Spacer(Modifier.height(2.dp))
                            if (totalCashEur != null) {
                                MoneyText(value = totalCashEur!!, style = BtTheme.type.moneyLarge)
                            } else {
                                BtSkeleton(Modifier.width(180.dp).height(36.dp))
                            }
                        }
                    }

                    // Deposit · Withdraw · Transfer (§6.3).
                    item(key = "actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_cash_deposit),
                                onClick = {
                                    editPrefill = null
                                    sheet = CashSheet.Entry(deposit = true)
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                            )
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_cash_withdraw),
                                onClick = {
                                    editPrefill = null
                                    sheet = CashSheet.Entry(deposit = false)
                                },
                                modifier = Modifier.weight(1f).height(44.dp),
                            )
                            BtSecondaryButton(
                                text = stringResource(R.string.bt_cash_transfer),
                                onClick = {
                                    editPrefill = null
                                    sheet = CashSheet.Transfer()
                                },
                                enabled = active.size >= 2,
                                modifier = Modifier.weight(1f).height(44.dp),
                            )
                        }
                    }

                    // Sources (Main first), tap = filter movements.
                    item(key = "sources-header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.bt_cash_sources_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = bt.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            BtChip(
                                text = stringResource(R.string.bt_cash_new_source),
                                enabled = isOnline && !manageBusy,
                                onClick = { newSourceOpen = true },
                            )
                        }
                    }
                    if (manageError != null) {
                        item(key = "manage-error") {
                            Text(
                                text = manageError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.loss,
                            )
                        }
                    }
                    if (sources.isEmpty()) {
                        item(key = "sources-skeleton") {
                            BtSkeleton(Modifier.fillMaxWidth().height(64.dp))
                        }
                    }
                    items(count = active.size, key = { active[it].id }) { i ->
                        val s = active[i]
                        SourceRow(
                            source = s,
                            selected = sourceFilter == s.id,
                            actionsEnabled = isOnline && !manageBusy,
                            onClick = { vm.setSourceFilter(s.id) },
                            onRename = { renameTarget = s },
                            onArchive = { archiveTarget = s },
                        )
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archived-header") {
                            Text(
                                text = stringResource(R.string.bt_switcher_archived_section),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(count = archived.size, key = { "arch-" + archived[it].id }) { i ->
                            val s = archived[i]
                            BtCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = s.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = bt.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = { vm.restoreSource(s.id) { } },
                                        enabled = isOnline && !manageBusy,
                                    ) {
                                        Text(
                                            stringResource(R.string.bt_switcher_restore),
                                            color = if (isOnline) bt.goldEmphasis else bt.textMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Queued cash ops (§7.4) — clearly-pending rows, tap to edit.
                    if (pendingRows.isNotEmpty()) {
                        item(key = "pending-header") {
                            Text(
                                text = stringResource(R.string.bt_pending_section),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(count = pendingRows.size, key = { "pending-" + pendingRows[it].opId }) { i ->
                            val row = pendingRows[i]
                            PendingCashRowCard(
                                row = row,
                                sourceNames = sourceNames,
                                locale = locale,
                                onEdit = {
                                    if (row.status == PendingUiStatus.PENDING ||
                                        row.status == PendingUiStatus.NEEDS_ATTENTION
                                    ) {
                                        editPrefill = row
                                        sheet = when (row.type) {
                                            OpType.CASH_TRANSFER -> CashSheet.Transfer(row.opId)
                                            OpType.CASH_WITHDRAW -> CashSheet.Entry(false, row.opId)
                                            else -> CashSheet.Entry(true, row.opId)
                                        }
                                    }
                                },
                            )
                        }
                    }

                    // Movement stream (filtered per source when selected).
                    item(key = "movements-header") {
                        Text(
                            text = if (sourceFilter == null) {
                                stringResource(R.string.bt_cash_movements_section)
                            } else {
                                stringResource(
                                    R.string.bt_cash_movements_for,
                                    sourceNames[sourceFilter] ?: "",
                                )
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = bt.textPrimary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (movements.isEmpty() && pendingRows.isEmpty()) {
                        item(key = "movements-empty") {
                            BtEmptyState(
                                icon = Icons.Outlined.AccountBalanceWallet,
                                title = stringResource(R.string.bt_cash_empty_title),
                                message = stringResource(R.string.bt_cash_empty_message),
                            )
                        }
                    }
                    items(count = movements.size, key = { movements[it].id }) { i ->
                        MovementRow(movements[i], sourceNames, locale)
                    }
                }
            }
        }
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────────

    when (val s = sheet) {
        is CashSheet.Entry -> CashEntrySheet(
            vm = vm,
            deposit = s.deposit,
            sources = active,
            prefill = editPrefill,
            editOpId = s.editOpId,
            locale = locale,
            onDismiss = {
                sheet = null
                editPrefill = null
                vm.clearSheetError()
            },
        )

        is CashSheet.Transfer -> TransferSheet(
            vm = vm,
            sources = active,
            prefill = editPrefill,
            editOpId = s.editOpId,
            locale = locale,
            onDismiss = {
                sheet = null
                editPrefill = null
                vm.clearSheetError()
            },
        )

        null -> Unit
    }

    if (newSourceOpen) {
        SourceDialog(
            title = stringResource(R.string.bt_cash_new_source_title),
            confirmLabel = stringResource(R.string.bt_switcher_create_action),
            initialName = "",
            initialType = "bank",
            busy = manageBusy,
            onConfirm = { name, type ->
                vm.createSource(name, type) { ok -> if (ok) newSourceOpen = false }
            },
            onDismiss = {
                newSourceOpen = false
                vm.clearManageError()
            },
        )
    }

    renameTarget?.let { target ->
        SourceDialog(
            title = stringResource(R.string.bt_cash_rename_source_title),
            confirmLabel = stringResource(R.string.bt_switcher_rename_action),
            initialName = target.name,
            initialType = target.kind,
            busy = manageBusy,
            onConfirm = { name, type ->
                vm.renameSource(target.id, name, type) { ok -> if (ok) renameTarget = null }
            },
            onDismiss = {
                renameTarget = null
                vm.clearManageError()
            },
        )
    }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_cash_archive_title)) },
            text = { Text(stringResource(R.string.bt_cash_archive_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.archiveSource(target.id) { ok -> if (ok) archiveTarget = null }
                    },
                    enabled = !manageBusy,
                ) { Text(stringResource(R.string.bt_switcher_archive_action), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun SourceRow(
    source: CashSourceEntity,
    selected: Boolean,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    BtCard(modifier = Modifier.fillMaxWidth(), selected = selected, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.isMain) {
                        Spacer(Modifier.width(8.dp))
                        BtBadge(
                            text = stringResource(R.string.bt_cash_main_badge),
                            kind = BtBadgeKind.Gold,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sourceTypeLabel(source.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
            MoneyText(value = source.balanceEur, style = BtTheme.type.moneySmall)
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_cash_source_actions_cd),
                        tint = if (actionsEnabled) bt.textSecondary else bt.border,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = bt.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_switcher_rename), color = bt.textPrimary) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    if (!source.isMain) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bt_switcher_archive), color = bt.loss) },
                            onClick = {
                                menuOpen = false
                                onArchive()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MovementRow(
    movement: CashMovementEntity,
    sourceNames: Map<String, String>,
    locale: Locale,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = movementLabel(movement, sourceNames),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        formatTxDate(movement.executedAtMs, locale),
                        sourceNames[movement.sourceId],
                    ).joinToString(" · "),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
            }
            MoneyText(
                value = movement.amountEur,
                style = BtTheme.type.moneySmall,
                colorMode = MoneyColorMode.GainLoss,
                showSign = true,
            )
        }
    }
}

@Composable
private fun PendingCashRowCard(
    row: PendingCashRow,
    sourceNames: Map<String, String>,
    locale: Locale,
    onEdit: () -> Unit,
) {
    val bt = BtTheme.colors
    val editable = row.status == PendingUiStatus.PENDING || row.status == PendingUiStatus.NEEDS_ATTENTION
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = if (editable) onEdit else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pendingCashLabel(row, sourceNames),
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        PendingStatusBadge(row.status)
                    }
                }
                Spacer(Modifier.width(10.dp))
                MoneyText(
                    value = if (row.type == OpType.CASH_WITHDRAW) -row.amountEur else row.amountEur,
                    style = BtTheme.type.moneySmall,
                    colorMode = if (row.type == OpType.CASH_TRANSFER) MoneyColorMode.Neutral else MoneyColorMode.GainLoss,
                    showSign = row.type != OpType.CASH_TRANSFER,
                )
            }
            if (row.status == PendingUiStatus.NEEDS_ATTENTION && row.serverError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.serverError,
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.lossSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Sheets ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashEntrySheet(
    vm: CashViewModel,
    deposit: Boolean,
    sources: List<CashSourceEntity>,
    prefill: PendingCashRow?,
    editOpId: Long?,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by rememberSaveable { mutableStateOf(prefill?.amountEur?.let { trimNumber(it) } ?: "") }
    var noteText by rememberSaveable { mutableStateOf(prefill?.note ?: "") }
    var sourceId by rememberSaveable {
        mutableStateOf(prefill?.sourceId ?: sources.firstOrNull { it.isMain }?.id)
    }
    // Movement date — defaults to today; a queued backdated entry restores its day.
    val initialDate = remember(prefill) {
        prefill?.executedAt?.let { iso ->
            try {
                Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
            } catch (_: Exception) {
                null
            }
        } ?: LocalDate.now()
    }
    var pickedEpochDay by rememberSaveable { mutableStateOf(initialDate.toEpochDay()) }
    val pickedDate = LocalDate.ofEpochDay(pickedEpochDay)
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }

    val amount = parseLocalizedDecimal(amountText)
    val selectedSource = sources.firstOrNull { it.id == sourceId }
    val validation = validateCashEntry(amount, deposit, selectedSource?.balanceEur)
    val after = if (amount != null && selectedSource != null) {
        balanceAfterEntry(selectedSource.balanceEur, amount, deposit)
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollIfNeeded()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (deposit) R.string.bt_cash_deposit_title else R.string.bt_cash_withdraw_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            sheetError?.let { RejectionText(it) }

            SheetNumberField(
                value = amountText,
                onValue = { amountText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = stringResource(R.string.bt_cash_amount),
                error = validation.insufficient,
            )

            // Source picker (§6.3).
            Text(
                text = stringResource(R.string.bt_cash_source_picker),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(
                sources = sources,
                selectedId = sourceId,
                onSelect = { sourceId = it },
            )

            // Live balance-after preview vs the CACHED balance (§6.3).
            if (selectedSource != null) {
                Text(
                    text = if (after != null) {
                        stringResource(
                            R.string.bt_cash_balance_after,
                            selectedSource.name,
                            formatEur(after, locale),
                        )
                    } else {
                        stringResource(
                            R.string.bt_txform_cash_balance,
                            formatEur(selectedSource.balanceEur, locale),
                        )
                    },
                    style = BtTheme.type.numberCaption,
                    color = if (validation.insufficient) bt.loss else bt.textSecondary,
                )
            }
            if (validation.insufficient && selectedSource != null) {
                Text(
                    text = stringResource(
                        R.string.bt_txform_insufficient_cash,
                        formatEur(selectedSource.balanceEur, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            // Optional movement date — prefilled with today, one tap to backdate a
            // cash movement that happened earlier (no future dates, §6.3).
            BtDateField(
                date = pickedDate,
                label = stringResource(R.string.bt_txform_date),
                enabled = !submitting,
                locale = locale,
                onClick = { datePickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(900) },
                label = { Text(stringResource(R.string.bt_txform_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = sheetFieldColors(),
            )

            BtPrimaryButton(
                text = stringResource(
                    when {
                        editOpId != null -> R.string.bt_txform_save
                        deposit -> R.string.bt_cash_deposit_action
                        else -> R.string.bt_cash_withdraw_action
                    },
                ),
                onClick = {
                    val a = amount ?: return@BtPrimaryButton
                    vm.submitEntry(deposit, a, sourceId, noteText, pickedDate, editOpId) { ok ->
                        if (ok) onDismiss()
                    }
                },
                enabled = validation.canSubmit && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }

    if (datePickerOpen) {
        BtDatePickerDialog(
            initial = pickedDate,
            onPick = { picked ->
                pickedEpochDay = picked.toEpochDay()
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(
    vm: CashViewModel,
    sources: List<CashSourceEntity>,
    prefill: PendingCashRow?,
    editOpId: Long?,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by rememberSaveable { mutableStateOf(prefill?.amountEur?.let { trimNumber(it) } ?: "") }
    var noteText by rememberSaveable { mutableStateOf(prefill?.note ?: "") }
    var fromId by rememberSaveable {
        mutableStateOf(prefill?.sourceId ?: sources.firstOrNull { it.isMain }?.id)
    }
    var toId by rememberSaveable {
        mutableStateOf(prefill?.toSourceId ?: sources.firstOrNull { !it.isMain }?.id)
    }

    val amount = parseLocalizedDecimal(amountText)
    val from = sources.firstOrNull { it.id == fromId }
    val to = sources.firstOrNull { it.id == toId }
    val validation = validateTransfer(amount, fromId, toId, from?.balanceEur)
    val preview = if (amount != null && from != null && to != null && !validation.sameSource) {
        transferPreview(from.balanceEur, to.balanceEur, amount)
    } else {
        null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScrollIfNeeded()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_cash_transfer_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            sheetError?.let { RejectionText(it) }

            SheetNumberField(
                value = amountText,
                onValue = { amountText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = stringResource(R.string.bt_cash_amount),
                error = validation.insufficient,
            )

            Text(
                text = stringResource(R.string.bt_cash_transfer_from),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(sources = sources, selectedId = fromId, onSelect = { fromId = it })

            Text(
                text = stringResource(R.string.bt_cash_transfer_to),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            SourceChips(sources = sources, selectedId = toId, onSelect = { toId = it })

            if (validation.sameSource) {
                Text(
                    text = stringResource(R.string.bt_cash_transfer_same_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }
            // Dual live preview (§6.3) against cached balances.
            if (preview != null && from != null && to != null) {
                Text(
                    text = stringResource(
                        R.string.bt_cash_balance_after,
                        from.name,
                        formatEur(preview.fromAfterEur, locale),
                    ) + "  ·  " + stringResource(
                        R.string.bt_cash_balance_after,
                        to.name,
                        formatEur(preview.toAfterEur, locale),
                    ),
                    style = BtTheme.type.numberCaption,
                    color = if (validation.insufficient) bt.loss else bt.textSecondary,
                )
            }
            if (validation.insufficient && from != null) {
                Text(
                    text = stringResource(
                        R.string.bt_txform_insufficient_cash,
                        formatEur(from.balanceEur, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(900) },
                label = { Text(stringResource(R.string.bt_txform_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = sheetFieldColors(),
            )

            BtPrimaryButton(
                text = stringResource(
                    if (editOpId != null) R.string.bt_txform_save else R.string.bt_cash_transfer_action,
                ),
                onClick = {
                    val a = amount ?: return@BtPrimaryButton
                    val f = fromId ?: return@BtPrimaryButton
                    val t = toId ?: return@BtPrimaryButton
                    vm.submitTransfer(f, t, a, noteText, editOpId) { ok -> if (ok) onDismiss() }
                },
                enabled = validation.canSubmit && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

// ── Small shared pieces ──────────────────────────────────────────────────────

@Composable
private fun SourceChips(
    sources: List<CashSourceEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { s ->
            BtChip(
                text = s.name,
                selected = s.id == selectedId,
                onClick = { onSelect(s.id) },
            )
        }
    }
}

@Composable
private fun SheetNumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    error: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        suffix = { Text("€", color = BtTheme.colors.textMuted) },
        textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
        modifier = Modifier.fillMaxWidth(),
        colors = sheetFieldColors(),
    )
}

@Composable
private fun RejectionText(message: String) {
    val bt = BtTheme.colors
    Column {
        Text(
            text = stringResource(R.string.bt_txform_rejected_title),
            style = MaterialTheme.typography.titleSmall,
            color = bt.lossSoft,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = bt.textSecondary,
        )
    }
}

/** Name + type dialog shared by create + rename (§6.3 typed labels). */
@Composable
private fun SourceDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialType: String,
    busy: Boolean,
    onConfirm: (name: String, type: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    var name by rememberSaveable { mutableStateOf(initialName) }
    var type by rememberSaveable { mutableStateOf(initialType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = bt.surface,
        titleContentColor = bt.textPrimary,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.bt_switcher_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    colors = sheetFieldColors(),
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("bank", "retirement", "cash", "custom").forEach { t ->
                        BtChip(
                            text = sourceTypeLabel(t),
                            selected = type == t,
                            enabled = !busy,
                            onClick = { type = t },
                        )
                    }
                }
            }
        },
        confirmButton = {
            BtPrimaryButton(
                text = confirmLabel,
                onClick = { onConfirm(name, type) },
                enabled = name.trim().isNotEmpty() && !busy,
                loading = busy,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        },
    )
}

@Composable
private fun sheetFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.borderStrong,
    errorBorderColor = BtTheme.colors.loss,
    focusedLabelColor = BtTheme.colors.gold,
    unfocusedLabelColor = BtTheme.colors.textMuted,
    errorLabelColor = BtTheme.colors.loss,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    cursorColor = BtTheme.colors.gold,
)

/** No-op placeholder so sheets stay simple (content is short). */
private fun Modifier.verticalScrollIfNeeded(): Modifier = this

// ── Display mapping ──────────────────────────────────────────────────────────

@Composable
fun sourceTypeLabel(kind: String): String = when (kind) {
    "bank" -> stringResource(R.string.bt_cash_type_bank)
    "retirement" -> stringResource(R.string.bt_cash_type_retirement)
    "cash" -> stringResource(R.string.bt_cash_type_cash)
    "custom" -> stringResource(R.string.bt_cash_type_custom)
    else -> kind.replaceFirstChar { it.uppercase() }
}

@Composable
private fun movementLabel(m: CashMovementEntity, sourceNames: Map<String, String>): String =
    when (m.kind) {
        "deposit" -> stringResource(R.string.bt_cash_kind_deposit)
        "withdrawal" -> stringResource(R.string.bt_cash_kind_withdrawal)
        "buy" -> stringResource(R.string.bt_cash_kind_buy)
        "sell_proceeds" -> stringResource(R.string.bt_cash_kind_sell)
        "transfer_out" -> stringResource(
            R.string.bt_cash_kind_transfer_out,
            m.counterpartSourceId?.let { sourceNames[it] } ?: "…",
        )

        "transfer_in" -> stringResource(
            R.string.bt_cash_kind_transfer_in,
            m.counterpartSourceId?.let { sourceNames[it] } ?: "…",
        )

        else -> m.kind
    }

@Composable
private fun pendingCashLabel(row: PendingCashRow, sourceNames: Map<String, String>): String =
    when (row.type) {
        OpType.CASH_DEPOSIT -> stringResource(R.string.bt_cash_kind_deposit)
        OpType.CASH_WITHDRAW -> stringResource(R.string.bt_cash_kind_withdrawal)
        OpType.CASH_TRANSFER -> stringResource(
            R.string.bt_cash_pending_transfer,
            row.sourceId?.let { sourceNames[it] } ?: "…",
            row.toSourceId?.let { sourceNames[it] } ?: "…",
        )

        else -> row.type.wire
    }

/** Plain editable number for prefill. */
private fun trimNumber(value: Double): String =
    java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
