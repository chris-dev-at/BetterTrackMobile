package at.bettertrack.app.ui.standingorders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.api.dto.STANDING_ORDER_LABEL_MAX
import at.bettertrack.app.data.api.dto.StandingOrderDto
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.standingorders.StandingOrderCadence
import at.bettertrack.app.data.standingorders.StandingOrderDraft
import at.bettertrack.app.data.standingorders.StandingOrderField
import at.bettertrack.app.data.standingorders.StandingOrderKind
import at.bettertrack.app.data.standingorders.StandingOrderProblem
import at.bettertrack.app.data.standingorders.StandingOrderRepository
import at.bettertrack.app.data.standingorders.StandingOrderStatus
import at.bettertrack.app.data.standingorders.buildStandingOrderPatch
import at.bettertrack.app.data.standingorders.validateStandingOrder
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtFormError
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.customassets.dialogFieldColors
import at.bettertrack.app.ui.portfolio.PortfolioOverviewViewModel
import at.bettertrack.app.ui.portfolio.formatQuantity
import at.bettertrack.app.ui.portfolio.formatTxDate
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The v5 **standing orders** screen: the recurring buys and cash movements a
 * daily server job books on the user's behalf.
 *
 * Network-only by design (see [StandingOrderRepository]) — `nextRunDate` is
 * computed per request against the server's calendar day, so there is nothing
 * worth caching. That makes the three list states (loading / empty / error) load
 * bearing rather than decorative, and every write repaints straight from the
 * response body instead of refetching the list.
 *
 * Editing is deliberately narrow: only `amount`, `label` and `endDate` are
 * mutable server-side. The kind, asset, cadence, anchor day and start date are
 * rendered read-only with [R.string.bt_so_schedule_locked] explaining why, and
 * are never put in the PATCH.
 */

// ═══════════════════════════ Pure, testable logic ═══════════════════════════

/**
 * List order: active orders first, then the soonest next run, so the row a user
 * came to check is at the top and paused orders sink out of the way. Orders with
 * no next run (paused, or past their end date) sort last inside their group.
 *
 * ISO days sort lexicographically, so the string compare IS a date compare — the
 * same fact [validateStandingOrder] leans on.
 */
fun sortStandingOrders(orders: List<StandingOrderDto>): List<StandingOrderDto> =
    orders.sortedWith(
        compareBy<StandingOrderDto> { StandingOrderStatus.fromWire(it.status) != StandingOrderStatus.Active }
            .thenBy { it.nextRunDate ?: "￿" }
            .thenBy { it.label ?: it.assetSymbol ?: "" }
            .thenBy { it.id },
    )

/**
 * Replace one order in place from a server response and re-sort.
 *
 * `pause` / `resume` / `PATCH` all answer the FULL updated order, so the row can
 * be repainted from the body — no second round trip, and no window where the row
 * shows a stale status. Re-sorting is intentional: a just-paused order visibly
 * sinks below the active ones.
 */
fun applyUpdatedOrder(
    orders: List<StandingOrderDto>,
    updated: StandingOrderDto,
): List<StandingOrderDto> {
    val known = orders.any { it.id == updated.id }
    val merged = if (known) orders.map { if (it.id == updated.id) updated else it } else orders + updated
    return sortStandingOrders(merged)
}

/**
 * The three editable fields, in the tri-state form the PATCH builder needs:
 * a value means "set it", [clearLabel] / [clearEndDate] mean "send an explicit
 * JSON null", and absent means "leave it alone".
 */
data class StandingOrderEditIntent(
    val amount: Double? = null,
    val label: String? = null,
    val clearLabel: Boolean = false,
    val endDate: String? = null,
    val clearEndDate: Boolean = false,
)

/**
 * Diff the edit form against the order it was opened on.
 *
 * Only what actually changed travels — the server schema is `.strict()` and an
 * empty body is a 400, so [hasChanges] gates the save button. Emptying the label
 * or the end date is a CLEAR (explicit null), which is a different wire fact from
 * never having touched them.
 */
fun standingOrderEditIntent(
    original: StandingOrderDto,
    amount: Double?,
    labelText: String,
    endDate: String?,
): StandingOrderEditIntent {
    val label = labelText.trim()
    val originalLabel = original.label?.trim().orEmpty()
    val originalEnd = original.endDate.orEmpty()
    val end = endDate.orEmpty()
    return StandingOrderEditIntent(
        amount = amount?.takeIf { it != original.amount },
        label = label.takeIf { it.isNotEmpty() && it != originalLabel },
        clearLabel = label.isEmpty() && originalLabel.isNotEmpty(),
        endDate = end.takeIf { it.isNotEmpty() && it != originalEnd },
        clearEndDate = end.isEmpty() && originalEnd.isNotEmpty(),
    )
}

/** True when this intent would produce a non-empty PATCH body. */
val StandingOrderEditIntent.hasChanges: Boolean
    get() = buildStandingOrderPatch(
        amount = amount,
        label = label,
        clearLabel = clearLabel,
        endDate = endDate,
        clearEndDate = clearEndDate,
    ) != null

/**
 * A schedule day (`YYYY-MM-DD`) as "5 Jun 2026" — the same shape the ledger uses
 * for transaction dates. A day this app can't parse is shown verbatim rather than
 * swallowed: a future server day format should degrade, not disappear.
 */
fun formatIsoDay(iso: String?, locale: Locale): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    } catch (_: Exception) {
        iso
    }
}

/** `lastRunAt` is an ISO-8601 INSTANT (not a day) — render its local calendar day. */
fun formatIsoInstantDay(iso: String?, locale: Locale): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = try {
            Instant.parse(iso)
        } catch (_: Exception) {
            OffsetDateTime.parse(iso).toInstant()
        }
        formatTxDate(instant.toEpochMilli(), locale)
    } catch (_: Exception) {
        iso
    }
}

/**
 * Plain editable text for a numeric prefill — no grouping separators, no trailing
 * zeros, so the value round-trips through [parseLocalizedDecimal] unchanged when
 * the user doesn't touch it (which keeps the "nothing changed" PATCH empty).
 */
fun plainAmountText(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

// ═════════════════════════════════ ViewModel ════════════════════════════════

@OptIn(FlowPreview::class)
class StandingOrdersViewModel(
    private val repo: StandingOrderRepository,
    portfolios: PortfolioRepository,
    private val market: MarketRepository,
    routePortfolioId: String?,
) : ViewModel() {

    val portfolioId: StateFlow<String?> =
        combine(portfolios.portfolios, portfolios.selectedPortfolioId) { all, stored ->
            routePortfolioId ?: PortfolioOverviewViewModel.resolveSelection(all, stored)?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), routePortfolioId)

    val portfolioName: StateFlow<String?> =
        combine(portfolios.portfolios, portfolioId) { all, pid ->
            all.firstOrNull { it.id == pid }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _orders = MutableStateFlow<List<StandingOrderDto>>(emptyList())
    val orders: StateFlow<List<StandingOrderDto>> = _orders.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Non-null only when the LIST itself could not be read (drives [BtErrorState]). */
    private val _loadError = MutableStateFlow<BtMessage?>(null)
    val loadError: StateFlow<BtMessage?> = _loadError.asStateFlow()

    /** The order id whose pause / resume / delete is in flight. */
    private val _rowBusyId = MutableStateFlow<String?>(null)
    val rowBusyId: StateFlow<String?> = _rowBusyId.asStateFlow()

    private val _rowError = MutableStateFlow<BtMessage?>(null)
    val rowError: StateFlow<BtMessage?> = _rowError.asStateFlow()

    /** Create / save state of the open sheet. */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _sheetError = MutableStateFlow<BtMessage?>(null)
    val sheetError: StateFlow<BtMessage?> = _sheetError.asStateFlow()

    // ── Asset search (buy-asset only) ───────────────────────────────────────
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MarketAsset>>(emptyList())
    val results: StateFlow<List<MarketAsset>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** The portfolio the current list belongs to — guards against re-loading it. */
    private var loadedFor: String? = null

    init {
        viewModelScope.launch {
            portfolioId.collect { pid ->
                if (pid != null && pid != loadedFor) {
                    loadedFor = pid
                    load(pid, initial = true)
                }
            }
        }
        viewModelScope.launch {
            _query.debounce(260).collectLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) {
                    _results.value = emptyList()
                    _searching.value = false
                    return@collectLatest
                }
                _searching.value = true
                when (val r = market.search(q)) {
                    is BtResult.Ok -> _results.value = r.value.results
                    is BtResult.Err -> _results.value = emptyList()
                }
                _searching.value = false
            }
        }
    }

    fun retry() {
        val pid = portfolioId.value ?: return
        load(pid, initial = true)
    }

    fun refresh() {
        val pid = portfolioId.value ?: return
        load(pid, initial = false)
    }

    private fun load(pid: String, initial: Boolean) {
        viewModelScope.launch {
            if (initial) _loading.value = true else _refreshing.value = true
            when (val r = repo.list(pid)) {
                is BtResult.Ok -> {
                    _orders.value = sortStandingOrders(r.value)
                    _loadError.value = null
                }
                // A failed REFRESH keeps whatever is on screen and reports itself
                // as a row-level problem; only a failed FIRST load owns the screen.
                is BtResult.Err -> if (initial) {
                    _loadError.value = r.error.asMessage()
                } else {
                    _rowError.value = r.error.asMessage()
                }
            }
            _loading.value = false
            _refreshing.value = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun clearSheetError() {
        _sheetError.value = null
    }

    fun clearRowError() {
        _rowError.value = null
    }

    // ── Row actions ─────────────────────────────────────────────────────────

    fun pause(id: String) = rowAction(id) { repo.pause(id) }

    fun resume(id: String) = rowAction(id) { repo.resume(id) }

    private fun rowAction(id: String, action: suspend () -> BtResult<StandingOrderDto>) {
        if (_rowBusyId.value != null) return
        viewModelScope.launch {
            _rowBusyId.value = id
            _rowError.value = null
            when (val r = action()) {
                // The endpoint answers the FULL updated order — repaint from it.
                is BtResult.Ok -> _orders.value = applyUpdatedOrder(_orders.value, r.value)
                is BtResult.Err -> _rowError.value = r.error.asMessage()
            }
            _rowBusyId.value = null
        }
    }

    fun delete(id: String, onDone: (Boolean) -> Unit) {
        if (_rowBusyId.value != null) return
        viewModelScope.launch {
            _rowBusyId.value = id
            _rowError.value = null
            val r = repo.delete(id)
            when (r) {
                is BtResult.Ok -> _orders.value = _orders.value.filterNot { it.id == id }
                is BtResult.Err -> _rowError.value = r.error.asMessage()
            }
            _rowBusyId.value = null
            onDone(r is BtResult.Ok)
        }
    }

    // ── Sheet actions ───────────────────────────────────────────────────────

    fun create(draft: StandingOrderDraft, onDone: (Boolean) -> Unit) {
        if (_submitting.value) return
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            when (val r = repo.create(draft)) {
                is BtResult.Ok -> {
                    _orders.value = applyUpdatedOrder(_orders.value, r.value)
                    _submitting.value = false
                    onDone(true)
                }

                is BtResult.Err -> {
                    _sheetError.value = r.error.asMessage()
                    _submitting.value = false
                    onDone(false)
                }
            }
        }
    }

    fun save(id: String, intent: StandingOrderEditIntent, onDone: (Boolean) -> Unit) {
        if (_submitting.value) return
        if (!intent.hasChanges) {
            onDone(true)
            return
        }
        viewModelScope.launch {
            _submitting.value = true
            _sheetError.value = null
            val r = repo.update(
                id = id,
                amount = intent.amount,
                label = intent.label,
                clearLabel = intent.clearLabel,
                endDate = intent.endDate,
                clearEndDate = intent.clearEndDate,
            )
            when (r) {
                is BtResult.Ok -> _orders.value = applyUpdatedOrder(_orders.value, r.value)
                is BtResult.Err -> _sheetError.value = r.error.asMessage()
            }
            _submitting.value = false
            onDone(r is BtResult.Ok)
        }
    }
}

// ═════════════════════════════════════ UI ═══════════════════════════════════

/** Which sheet is open. */
private sealed interface StandingOrderSheet {
    data object Create : StandingOrderSheet
    data class Edit(val orderId: String) : StandingOrderSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandingOrdersScreen(
    routePortfolioId: String?,
    onBack: () -> Unit,
) {
    val vm: StandingOrdersViewModel = viewModel {
        StandingOrdersViewModel(
            repo = AppGraph.standingOrderRepository,
            portfolios = AppGraph.portfolioRepository,
            market = AppGraph.marketRepository,
            routePortfolioId = routePortfolioId,
        )
    }

    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val portfolioId by vm.portfolioId.collectAsStateWithLifecycle()
    val portfolioName by vm.portfolioName.collectAsStateWithLifecycle()
    val orders by vm.orders.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val rowBusyId by vm.rowBusyId.collectAsStateWithLifecycle()
    val rowError by vm.rowError.collectAsStateWithLifecycle()

    var sheet by remember { mutableStateOf<StandingOrderSheet?>(null) }
    var deleteTarget by remember { mutableStateOf<StandingOrderDto?>(null) }

    val openCreate = { sheet = StandingOrderSheet.Create }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.bt_so_title),
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
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Plain locals so the null checks narrow the type at the render sites —
            // a `by` delegate never smart-casts.
            val listFailure = loadError
            val actionFailure = rowError
            when {
                loading && orders.isEmpty() -> LoadingList()

                listFailure != null && orders.isEmpty() -> BtErrorState(
                    modifier = Modifier.fillMaxSize(),
                    title = stringResource(R.string.bt_so_error_title),
                    message = listFailure,
                    onRetry = { vm.retry() },
                )

                else -> {
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
                                color = bt.goldInk,
                            )
                        },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 4.dp,
                                bottom = 28.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item(key = "subtitle") {
                                Text(
                                    text = stringResource(R.string.bt_so_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textMuted,
                                )
                            }

                            if (actionFailure != null) {
                                item(key = "row-error") {
                                    // This channel carries two things: a failed
                                    // REFRESH (for which re-listing is the exact
                                    // retry) and a failed pause / resume / delete
                                    // (for which it is the honest one — the row's
                                    // true state after a refused write is whatever
                                    // the server says, and this asks). The clear is
                                    // not cosmetic: `load` only nulls `loadError`,
                                    // so without it a successful refresh would sit
                                    // under a stale failure sentence.
                                    BtInlineError(
                                        message = actionFailure,
                                        onRetry = {
                                            vm.clearRowError()
                                            vm.refresh()
                                        },
                                    )
                                }
                            }

                            if (orders.isEmpty()) {
                                item(key = "empty") {
                                    BtEmptyState(
                                        icon = Icons.Outlined.Autorenew,
                                        title = stringResource(R.string.bt_so_empty_title),
                                        message = stringResource(R.string.bt_so_empty_message),
                                        action = {
                                            BtPrimaryButton(
                                                text = stringResource(R.string.bt_so_new),
                                                onClick = openCreate,
                                                enabled = portfolioId != null,
                                            )
                                        },
                                    )
                                }
                            } else {
                                item(key = "new") {
                                    BtSecondaryButton(
                                        text = stringResource(R.string.bt_so_new),
                                        onClick = openCreate,
                                        enabled = portfolioId != null,
                                        modifier = Modifier.fillMaxWidth().height(44.dp),
                                    )
                                }
                                items(count = orders.size, key = { orders[it].id }) { i ->
                                    val order = orders[i]
                                    StandingOrderRow(
                                        order = order,
                                        locale = locale,
                                        busy = rowBusyId == order.id,
                                        actionsEnabled = rowBusyId == null,
                                        onEdit = { sheet = StandingOrderSheet.Edit(order.id) },
                                        onPauseResume = {
                                            if (StandingOrderStatus.fromWire(order.status) ==
                                                StandingOrderStatus.Active
                                            ) {
                                                vm.pause(order.id)
                                            } else {
                                                vm.resume(order.id)
                                            }
                                        },
                                        onDelete = { deleteTarget = order },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Sheets & dialogs ────────────────────────────────────────────────────

    when (val open = sheet) {
        StandingOrderSheet.Create -> {
            val pid = portfolioId
            if (pid == null) {
                sheet = null
            } else {
                CreateStandingOrderSheet(
                    vm = vm,
                    portfolioId = pid,
                    locale = locale,
                    onDismiss = {
                        sheet = null
                        vm.setQuery("")
                        vm.clearSheetError()
                    },
                )
            }
        }

        is StandingOrderSheet.Edit -> {
            val target = orders.firstOrNull { it.id == open.orderId }
            if (target == null) {
                // A refresh landed while the sheet was opening and the row is
                // gone — close rather than edit nothing.
                sheet = null
            } else {
                EditStandingOrderSheet(
                    vm = vm,
                    order = target,
                    locale = locale,
                    onDismiss = {
                        sheet = null
                        vm.clearSheetError()
                    },
                )
            }
        }

        null -> Unit
    }

    deleteTarget?.let { target ->
        val busy = rowBusyId == target.id
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            containerColor = bt.surfaceHigh,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_so_delete_title)) },
            text = { Text(stringResource(R.string.bt_so_delete_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { vm.delete(target.id) { ok -> if (ok) deleteTarget = null } },
                ) {
                    Text(stringResource(R.string.bt_so_delete_action), color = bt.loss)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

// ── List states ─────────────────────────────────────────────────────────────

@Composable
private fun LoadingList() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BtSkeleton(Modifier.width(220.dp).height(14.dp))
        repeat(4) { BtSkeleton(Modifier.fillMaxWidth().height(76.dp)) }
    }
}

// ── Rows ────────────────────────────────────────────────────────────────────

@Composable
private fun StandingOrderRow(
    order: StandingOrderDto,
    locale: Locale,
    busy: Boolean,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val bt = BtTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val kind = StandingOrderKind.fromWire(order.kind)
    val active = StandingOrderStatus.fromWire(order.status) == StandingOrderStatus.Active

    // A paused order reads quieter across the board: muted title, no gold badge,
    // and a de-coloured amount — it isn't going to happen, so it shouldn't shout.
    val titleColor = if (active) bt.textPrimary else bt.textMuted
    val metaColor = if (active) bt.textSecondary else bt.textMuted

    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = standingOrderIcon(kind),
                contentDescription = null,
                tint = if (active) bt.textSecondary else bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = standingOrderTitle(order, kind),
                        style = MaterialTheme.typography.titleSmall,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    BtBadge(
                        text = stringResource(
                            if (active) R.string.bt_so_status_active else R.string.bt_so_status_paused,
                        ),
                        kind = if (active) BtBadgeKind.Gold else BtBadgeKind.Neutral,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = scheduleSummary(order),
                    style = BtTheme.type.numberCaption,
                    color = metaColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = runSummary(order, locale),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StandingOrderAmount(order = order, kind = kind, locale = locale, active = active)
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = actionsEnabled && !busy) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.bt_so_actions_cd),
                        tint = if (actionsEnabled && !busy) bt.textSecondary else bt.border,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = bt.surfaceHigh,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    if (active) R.string.bt_so_pause else R.string.bt_so_resume,
                                ),
                                color = bt.textPrimary,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPauseResume()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.bt_so_delete_action), color = bt.loss)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The row's headline number.
 *
 * `buy-asset` carries a share QUANTITY, not money — it goes through
 * [formatQuantity] and must never touch [MoneyText] / `formatEur`, which would
 * stamp a "€" on a share count (and mask it in discreet mode, where a quantity is
 * not a secret). The two cash kinds are EUR magnitudes and DO go through
 * [MoneyText], which is where discreet-mode masking lives.
 */
@Composable
private fun StandingOrderAmount(
    order: StandingOrderDto,
    kind: StandingOrderKind?,
    locale: Locale,
    active: Boolean,
) {
    val bt = BtTheme.colors
    when (kind) {
        StandingOrderKind.CashAdd, StandingOrderKind.CashDeduct -> {
            val signed = if (kind == StandingOrderKind.CashDeduct) -order.amount else order.amount
            MoneyText(
                value = signed,
                style = BtTheme.type.moneySmall,
                colorMode = if (active) MoneyColorMode.GainLoss else MoneyColorMode.Neutral,
                showSign = true,
                color = if (active) Color.Unspecified else bt.textMuted,
            )
        }

        else -> Text(
            text = formatQuantity(order.amount, locale),
            style = BtTheme.type.moneySmall,
            color = if (active) bt.textPrimary else bt.textMuted,
            maxLines = 1,
        )
    }
}

/** Cadence line: "Monthly · Day of month 14" / "Daily". */
@Composable
private fun scheduleSummary(order: StandingOrderDto): String {
    val cadence = StandingOrderCadence.fromWire(order.cadence)
    val cadenceText = cadence?.let { stringResource(cadenceLabelRes(it)) } ?: order.cadence
    val anchor = order.anchorDay
    return if (cadence == StandingOrderCadence.Monthly && anchor != null) {
        "$cadenceText · ${stringResource(R.string.bt_so_anchor_day)} $anchor"
    } else {
        cadenceText
    }
}

/** Run line: "Next 5 Jun 2026 · Last booked 5 May 2026". */
@Composable
private fun runSummary(order: StandingOrderDto, locale: Locale): String {
    val next = formatIsoDay(order.nextRunDate, locale)
        ?.let { stringResource(R.string.bt_so_next_run, it) }
        ?: stringResource(R.string.bt_so_no_next_run)
    val last = formatIsoInstantDay(order.lastRunAt, locale)
        ?.let { stringResource(R.string.bt_so_last_run, it) }
    return listOfNotNull(next, last).joinToString(" · ")
}

/**
 * Row headline: the user's own label wins, then the asset a buy names, then the
 * kind itself — so a row is never blank.
 */
@Composable
private fun standingOrderTitle(order: StandingOrderDto, kind: StandingOrderKind?): String {
    val label = order.label?.trim()?.takeIf { it.isNotEmpty() }
    if (label != null) return label
    if (kind == StandingOrderKind.BuyAsset) {
        val asset = order.assetName?.takeIf { it.isNotBlank() } ?: order.assetSymbol?.takeIf { it.isNotBlank() }
        if (asset != null) return asset
    }
    return kind?.let { stringResource(kindLabelRes(it)) } ?: order.kind
}

private fun standingOrderIcon(kind: StandingOrderKind?): ImageVector = when (kind) {
    StandingOrderKind.CashAdd -> Icons.Outlined.SouthWest
    StandingOrderKind.CashDeduct -> Icons.Outlined.NorthEast
    StandingOrderKind.BuyAsset -> Icons.Outlined.ShoppingCart
    // A kind this app version doesn't know still renders, as a neutral schedule.
    null -> Icons.Outlined.Autorenew
}

// ── Create sheet ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateStandingOrderSheet(
    vm: StandingOrdersViewModel,
    portfolioId: String,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by rememberSaveable { mutableStateOf(StandingOrderKind.BuyAsset) }
    var cadence by rememberSaveable { mutableStateOf(StandingOrderCadence.Monthly) }
    var assetId by rememberSaveable { mutableStateOf<String?>(null) }
    var assetSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    var assetName by rememberSaveable { mutableStateOf<String?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var labelText by rememberSaveable { mutableStateOf("") }
    var anchorText by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf<String?>(null) }
    var endDate by rememberSaveable { mutableStateOf<String?>(null) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    /** The picker takes over the sheet body rather than stacking a second sheet. */
    var picking by rememberSaveable { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf<DateTarget?>(null) }

    val draft = StandingOrderDraft(
        portfolioId = portfolioId,
        kind = kind,
        cadence = cadence,
        amount = parseLocalizedDecimal(amountText),
        // The two "REJECTED otherwise" keys are kept off the draft entirely, so
        // validation never reports a problem the form can't show.
        assetId = assetId.takeIf { kind == StandingOrderKind.BuyAsset },
        label = labelText,
        anchorDay = anchorText.toIntOrNull().takeIf { cadence == StandingOrderCadence.Monthly },
        startDate = startDate,
        endDate = endDate,
    )
    val validation = validateStandingOrder(draft)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        if (picking) {
            AssetPickerBody(
                vm = vm,
                onPick = { asset ->
                    assetId = asset.id
                    assetSymbol = asset.symbol
                    assetName = asset.name
                    picking = false
                    vm.setQuery("")
                },
                onCancel = {
                    picking = false
                    vm.setQuery("")
                },
            )
            return@ModalBottomSheet
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_so_new_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            FieldLabel(stringResource(R.string.bt_so_kind))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandingOrderKind.entries.forEach { candidate ->
                    BtChip(
                        text = stringResource(kindLabelRes(candidate)),
                        selected = kind == candidate,
                        enabled = !submitting,
                        onClick = {
                            kind = candidate
                            // Switching away from a buy drops the asset: the
                            // server REJECTS assetId on a cash kind outright.
                            if (candidate != StandingOrderKind.BuyAsset) {
                                assetId = null
                                assetSymbol = null
                                assetName = null
                            }
                        },
                    )
                }
            }

            if (kind == StandingOrderKind.BuyAsset) {
                FieldLabel(stringResource(R.string.bt_so_asset))
                BtSecondaryButton(
                    text = assetSymbol?.let { symbol ->
                        listOfNotNull(symbol, assetName?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    } ?: stringResource(R.string.bt_so_asset_pick),
                    onClick = { picking = true },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                )
                FieldError(
                    problem = validation[StandingOrderField.AssetId],
                    visible = submitted,
                )
            }

            AmountField(
                value = amountText,
                onValue = {
                    amountText = sanitizeDecimalInput(
                        it,
                        maxDecimals = if (kind == StandingOrderKind.BuyAsset) 8 else 2,
                    )
                },
                kind = kind,
                enabled = !submitting,
                isError = (submitted || amountText.isNotEmpty()) &&
                    validation[StandingOrderField.Amount] != null,
            )
            FieldError(
                problem = validation[StandingOrderField.Amount],
                visible = submitted || amountText.isNotEmpty(),
            )

            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it.take(STANDING_ORDER_LABEL_MAX) },
                label = { Text(stringResource(R.string.bt_so_label)) },
                placeholder = { Text(stringResource(R.string.bt_so_label_hint), color = bt.textMuted) },
                singleLine = true,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                colors = dialogFieldColors(),
            )
            FieldError(problem = validation[StandingOrderField.Label], visible = true)

            FieldLabel(stringResource(R.string.bt_so_cadence))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandingOrderCadence.entries.forEach { candidate ->
                    BtChip(
                        text = stringResource(cadenceLabelRes(candidate)),
                        selected = cadence == candidate,
                        enabled = !submitting,
                        onClick = {
                            cadence = candidate
                            // anchorDay is REJECTED on a daily order — drop it.
                            if (candidate != StandingOrderCadence.Monthly) anchorText = ""
                        },
                    )
                }
            }

            if (cadence == StandingOrderCadence.Monthly) {
                OutlinedTextField(
                    value = anchorText,
                    onValueChange = { raw -> anchorText = raw.filter { it.isDigit() }.take(2) },
                    label = { Text(stringResource(R.string.bt_so_anchor_day)) },
                    singleLine = true,
                    enabled = !submitting,
                    isError = (submitted || anchorText.isNotEmpty()) &&
                        validation[StandingOrderField.AnchorDay] != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogFieldColors(),
                )
                Text(
                    text = stringResource(R.string.bt_so_anchor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                FieldError(
                    problem = validation[StandingOrderField.AnchorDay],
                    visible = submitted || anchorText.isNotEmpty(),
                )
            }

            ScheduleDateField(
                iso = startDate,
                label = stringResource(R.string.bt_so_start_date),
                emptyText = null,
                locale = locale,
                enabled = !submitting,
                onClick = { datePicker = DateTarget.Start },
                onClear = { startDate = null },
            )
            ScheduleDateField(
                iso = endDate,
                label = stringResource(R.string.bt_so_end_date),
                emptyText = stringResource(R.string.bt_so_end_date_none),
                locale = locale,
                enabled = !submitting,
                onClick = { datePicker = DateTarget.End },
                onClear = { endDate = null },
            )
            FieldError(problem = validation[StandingOrderField.EndDate], visible = true)

            sheetError?.let { SheetError(it) }

            BtPrimaryButton(
                text = stringResource(R.string.bt_so_create_action),
                onClick = {
                    submitted = true
                    if (validation.isValid) vm.create(draft) { ok -> if (ok) onDismiss() }
                },
                enabled = !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }

    datePicker?.let { target ->
        val current = when (target) {
            DateTarget.Start -> startDate
            DateTarget.End -> endDate
        }
        ScheduleDatePickerDialog(
            initial = parseIsoOrToday(current),
            onPick = { picked ->
                val iso = picked.toString()
                when (target) {
                    DateTarget.Start -> startDate = iso
                    DateTarget.End -> endDate = iso
                }
                datePicker = null
            },
            onDismiss = { datePicker = null },
        )
    }
}

// ── Edit sheet ──────────────────────────────────────────────────────────────

/**
 * Edit an existing order. Only `amount`, `label` and `endDate` are mutable —
 * kind, asset, portfolio, cadence, anchor day and start date are fixed for the
 * lifetime of the order server-side, so they are rendered read-only with
 * [R.string.bt_so_schedule_locked] saying why, and never enter the PATCH.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStandingOrderSheet(
    vm: StandingOrdersViewModel,
    order: StandingOrderDto,
    locale: Locale,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val submitting by vm.submitting.collectAsStateWithLifecycle()
    val sheetError by vm.sheetError.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val kind = StandingOrderKind.fromWire(order.kind)
    val cadence = StandingOrderCadence.fromWire(order.cadence)

    var amountText by rememberSaveable(order.id) { mutableStateOf(plainAmountText(order.amount)) }
    var labelText by rememberSaveable(order.id) { mutableStateOf(order.label.orEmpty()) }
    var endDate by rememberSaveable(order.id) { mutableStateOf(order.endDate) }
    var datePickerOpen by remember { mutableStateOf(false) }

    val amount = parseLocalizedDecimal(amountText)

    // Validate against the REAL order so the end-vs-start rule sees the immutable
    // start date; only the three editable fields' problems are ever shown.
    val validation = validateStandingOrder(
        StandingOrderDraft(
            portfolioId = order.portfolioId,
            kind = kind ?: StandingOrderKind.CashAdd,
            cadence = cadence ?: StandingOrderCadence.Daily,
            amount = amount,
            assetId = order.assetId.takeIf { kind == StandingOrderKind.BuyAsset },
            label = labelText,
            anchorDay = order.anchorDay.takeIf { cadence == StandingOrderCadence.Monthly },
            startDate = order.startDate,
            endDate = endDate,
        ),
    )
    val intent = standingOrderEditIntent(
        original = order,
        amount = amount,
        labelText = labelText,
        endDate = endDate,
    )
    val editableValid = validation[StandingOrderField.Amount] == null &&
        validation[StandingOrderField.Label] == null &&
        validation[StandingOrderField.EndDate] == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surfaceHigh,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_so_edit_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )

            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LockedRow(
                        label = stringResource(R.string.bt_so_kind),
                        value = kind?.let { stringResource(kindLabelRes(it)) } ?: order.kind,
                    )
                    if (kind == StandingOrderKind.BuyAsset) {
                        LockedRow(
                            label = stringResource(R.string.bt_so_asset),
                            value = listOfNotNull(
                                order.assetSymbol?.takeIf { it.isNotBlank() },
                                order.assetName?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifEmpty { "—" },
                        )
                    }
                    LockedRow(
                        label = stringResource(R.string.bt_so_cadence),
                        value = scheduleSummary(order),
                    )
                    LockedRow(
                        label = stringResource(R.string.bt_so_start_date),
                        value = formatIsoDay(order.startDate, locale) ?: "—",
                    )
                }
            }
            Text(
                text = stringResource(R.string.bt_so_schedule_locked),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )

            AmountField(
                value = amountText,
                onValue = {
                    amountText = sanitizeDecimalInput(
                        it,
                        maxDecimals = if (kind == StandingOrderKind.BuyAsset) 8 else 2,
                    )
                },
                kind = kind ?: StandingOrderKind.CashAdd,
                enabled = !submitting,
                isError = validation[StandingOrderField.Amount] != null,
            )
            FieldError(problem = validation[StandingOrderField.Amount], visible = true)

            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it.take(STANDING_ORDER_LABEL_MAX) },
                label = { Text(stringResource(R.string.bt_so_label)) },
                placeholder = { Text(stringResource(R.string.bt_so_label_hint), color = bt.textMuted) },
                singleLine = true,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
                colors = dialogFieldColors(),
            )
            FieldError(problem = validation[StandingOrderField.Label], visible = true)

            ScheduleDateField(
                iso = endDate,
                label = stringResource(R.string.bt_so_end_date),
                emptyText = stringResource(R.string.bt_so_end_date_none),
                locale = locale,
                enabled = !submitting,
                onClick = { datePickerOpen = true },
                onClear = { endDate = null },
            )
            FieldError(problem = validation[StandingOrderField.EndDate], visible = true)

            sheetError?.let { SheetError(it) }

            BtPrimaryButton(
                text = stringResource(R.string.bt_so_save_action),
                onClick = { vm.save(order.id, intent) { ok -> if (ok) onDismiss() } },
                enabled = intent.hasChanges && editableValid && !submitting,
                loading = submitting,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }

    if (datePickerOpen) {
        ScheduleDatePickerDialog(
            initial = parseIsoOrToday(endDate),
            onPick = {
                endDate = it.toString()
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }
}

// ── Asset picker (buy-asset) ────────────────────────────────────────────────

/**
 * Searchable asset picker, rendered INSIDE the create sheet instead of stacking a
 * second modal sheet on top of it — the form's half-typed state stays alive and
 * there is no nested-dialog dismissal to get wrong. Same debounced
 * [MarketRepository.search] every other asset-picking surface uses.
 */
@Composable
private fun AssetPickerBody(
    vm: StandingOrdersViewModel,
    onPick: (MarketAsset) -> Unit,
    onCancel: () -> Unit,
) {
    val bt = BtTheme.colors
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_so_asset_pick),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(stringResource(R.string.bt_txform_asset_search_hint), color = bt.textMuted)
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.bt_search_clear),
                            tint = bt.textMuted,
                        )
                    }
                }
            },
            colors = dialogFieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        if (searching && results.isEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { BtSkeleton(Modifier.fillMaxWidth().height(56.dp)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(count = results.size, key = { results[it].id }) { i ->
                    val asset = results[i]
                    BtCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(asset) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = asset.symbol,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = bt.textPrimary,
                                )
                                Text(
                                    text = listOfNotNull(asset.name, asset.exchange).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bt.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Small shared pieces ─────────────────────────────────────────────────────

private enum class DateTarget { Start, End }

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
    )
}

/** One validation problem, worded by the UI (the logic layer only names it). */
@Composable
private fun FieldError(problem: StandingOrderProblem?, visible: Boolean) {
    if (problem == null || !visible) return
    Text(
        text = stringResource(problemRes(problem)),
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.lossSoft,
    )
}

/**
 * A refused create / save inside the sheet. No retry, deliberately: the sheet's
 * own submit button is the retry and is still armed a few dp below — see
 * [BtFormError]. This stays as a named helper only because both sheets reach for
 * it; it adds nothing over the component itself.
 */
@Composable
private fun SheetError(message: BtMessage) = BtFormError(message)

@Composable
private fun LockedRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Amount input — a share quantity for a buy, a EUR magnitude for the cash kinds. */
@Composable
private fun AmountField(
    value: String,
    onValue: (String) -> Unit,
    kind: StandingOrderKind,
    enabled: Boolean,
    isError: Boolean,
) {
    val cash = kind != StandingOrderKind.BuyAsset
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = {
            Text(
                stringResource(
                    if (cash) R.string.bt_so_amount_cash else R.string.bt_so_amount_shares,
                ),
            )
        },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        suffix = if (cash) {
            { Text("€", color = BtTheme.colors.textMuted) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        textStyle = BtTheme.type.moneySmall,
        modifier = Modifier.fillMaxWidth(),
        colors = dialogFieldColors(),
    )
}

/**
 * Optional schedule day. Read-only field that opens the picker on press, with a
 * clear affordance because both dates are genuinely optional (no start = the
 * server's today, no end = runs forever).
 */
@Composable
private fun ScheduleDateField(
    iso: String?,
    label: String,
    /** Wording for "unset" when that state has a meaning worth naming. */
    emptyText: String?,
    locale: Locale,
    enabled: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val bt = BtTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtSecondaryButton(
                text = formatIsoDay(iso, locale) ?: emptyText ?: "—",
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(46.dp),
            )
            if (iso != null) {
                IconButton(onClick = onClear, enabled = enabled) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.bt_search_clear),
                        tint = bt.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Icon(
                    Icons.Outlined.CalendarToday,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp).size(18.dp),
                )
            }
        }
    }
}

/**
 * Day picker for a SCHEDULE.
 *
 * Deliberately not [at.bettertrack.app.ui.components.BtDatePickerDialog]: that one
 * is the shared *no-future* picker for recording things that already happened, and
 * a standing order's start/end dates are almost always in the future ("starts next
 * month", "ends next year"). Same brand treatment, no upper bound.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePickerDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis ?: return@TextButton
                    onPick(Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate())
                },
            ) { Text(stringResource(R.string.bt_txform_date_ok), color = bt.goldInk) }
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

private fun parseIsoOrToday(iso: String?): LocalDate = try {
    if (iso.isNullOrBlank()) LocalDate.now() else LocalDate.parse(iso)
} catch (_: Exception) {
    LocalDate.now()
}

// ── Display mapping ─────────────────────────────────────────────────────────

private fun kindLabelRes(kind: StandingOrderKind): Int = when (kind) {
    StandingOrderKind.BuyAsset -> R.string.bt_so_kind_buy
    StandingOrderKind.CashAdd -> R.string.bt_so_kind_cash_add
    StandingOrderKind.CashDeduct -> R.string.bt_so_kind_cash_deduct
}

private fun cadenceLabelRes(cadence: StandingOrderCadence): Int = when (cadence) {
    StandingOrderCadence.Daily -> R.string.bt_so_cadence_daily
    StandingOrderCadence.Monthly -> R.string.bt_so_cadence_monthly
}

/**
 * Problem → wording.
 *
 * Three problems have no dedicated string because this form cannot produce them:
 * [StandingOrderProblem.AssetNotAllowed] and
 * [StandingOrderProblem.AnchorDayNotAllowed] are impossible (the draft drops both
 * keys the moment the kind/cadence changes), and the two malformed-date problems
 * are impossible because dates only ever come from the picker. They map to the
 * nearest true sentence rather than rendering blank.
 */
private fun problemRes(problem: StandingOrderProblem): Int = when (problem) {
    StandingOrderProblem.AssetRequired,
    StandingOrderProblem.AssetNotAllowed,
    -> R.string.bt_so_err_asset_required

    StandingOrderProblem.AmountNotPositive -> R.string.bt_so_err_amount

    // Its own sentence: "enter an amount greater than zero" is simply untrue
    // when the problem is that the amount is above the ledger's ceiling.
    StandingOrderProblem.AmountTooLarge -> R.string.bt_so_err_amount_large

    StandingOrderProblem.LabelTooLong -> R.string.bt_so_err_label_long

    StandingOrderProblem.AnchorDayRequired -> R.string.bt_so_err_anchor_required

    StandingOrderProblem.AnchorDayNotAllowed,
    StandingOrderProblem.AnchorDayOutOfRange,
    -> R.string.bt_so_err_anchor_range

    StandingOrderProblem.StartDateMalformed,
    StandingOrderProblem.EndDateMalformed,
    StandingOrderProblem.EndDateBeforeStart,
    -> R.string.bt_so_err_end_before_start
}
