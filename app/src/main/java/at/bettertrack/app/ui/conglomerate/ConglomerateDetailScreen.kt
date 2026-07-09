package at.bettertrack.app.ui.conglomerate

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import at.bettertrack.app.data.repo.AllocateMode
import at.bettertrack.app.data.repo.Allocation
import at.bettertrack.app.data.repo.Backtest
import at.bettertrack.app.data.repo.BacktestRange
import at.bettertrack.app.data.repo.ConglomerateDetail
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.sync.TxOpPayload
import at.bettertrack.app.ui.charts.BtPriceChart
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.portfolio.executedAtIso
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.Locale

sealed interface BacktestUiState {
    data object Loading : BacktestUiState
    data class Loaded(val backtest: Backtest) : BacktestUiState
    data object Empty : BacktestUiState
    data object Failed : BacktestUiState
}

class ConglomerateDetailViewModel(
    private val repo: ConglomerateRepository,
    private val portfolioRepo: PortfolioRepository,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val db: at.bettertrack.app.data.db.BtDatabase,
    private val json: Json,
    private val conglomerateId: String,
) : ViewModel() {

    private val _detail = MutableStateFlow<ConglomerateDetail?>(null)
    val detail: StateFlow<ConglomerateDetail?> = _detail.asStateFlow()

    private val _range = MutableStateFlow(BacktestRange.DEFAULT)
    val range: StateFlow<BacktestRange> = _range.asStateFlow()
    private val _backtest = MutableStateFlow<BacktestUiState>(BacktestUiState.Loading)
    val backtest: StateFlow<BacktestUiState> = _backtest.asStateFlow()

    private val _allocation = MutableStateFlow<Allocation?>(null)
    val allocation: StateFlow<Allocation?> = _allocation.asStateFlow()
    private val _calculating = MutableStateFlow(false)
    val calculating: StateFlow<Boolean> = _calculating.asStateFlow()
    private val _committing = MutableStateFlow(false)
    val committing: StateFlow<Boolean> = _committing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val selectedPortfolioName: StateFlow<String?> =
        kotlinx.coroutines.flow.combine(portfolioRepo.portfolios, portfolioRepo.selectedPortfolioId) { all, sel ->
            all.firstOrNull { it.id == sel }?.name ?: all.firstOrNull { it.isDefault }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { load() }

    fun load() {
        viewModelScope.launch {
            when (val r = repo.detail(conglomerateId)) {
                is BtResult.Ok -> {
                    _detail.value = r.value
                    runBacktest(r.value)
                }

                is BtResult.Err -> _backtest.value = BacktestUiState.Failed
            }
        }
    }

    private fun runBacktest(d: ConglomerateDetail) {
        if (d.positions.size < 2) { _backtest.value = BacktestUiState.Empty; return }
        viewModelScope.launch {
            _backtest.value = BacktestUiState.Loading
            val weights = d.positions.map { it.assetId to it.weightPct }
            _backtest.value = when (val r = repo.backtest(weights, _range.value)) {
                is BtResult.Ok -> if (r.value.series.size < 2) BacktestUiState.Empty else BacktestUiState.Loaded(r.value)
                is BtResult.Err -> BacktestUiState.Failed
            }
        }
    }

    fun setRange(range: BacktestRange) {
        if (range == _range.value) return
        _range.value = range
        _detail.value?.let(::runBacktest)
    }

    fun calculate(budgetEur: Double, mode: AllocateMode, atLeastOneShare: Boolean, step: Double?) {
        if (_calculating.value) return
        viewModelScope.launch {
            _calculating.value = true
            _message.value = null
            when (val r = repo.allocate(conglomerateId, budgetEur, mode, atLeastOneShare, step)) {
                is BtResult.Ok -> _allocation.value = r.value
                is BtResult.Err -> _message.value = r.error.userMessage
            }
            _calculating.value = false
        }
    }

    /** Commit the buy list into the selected portfolio via the queue (pay-from-cash). */
    fun commit(onDone: (Boolean) -> Unit) {
        val alloc = _allocation.value ?: return
        val buyable = alloc.lines.filter { !it.unbuyable && it.qty > 0.0 }
        if (_committing.value || buyable.isEmpty()) return
        viewModelScope.launch {
            _committing.value = true
            _message.value = null
            val pid = portfolioRepo.selectedPortfolioIdNow()
                ?: portfolioRepo.portfolios.first().firstOrNull { it.isDefault }?.id
            if (pid == null) { _message.value = "No portfolio selected."; _committing.value = false; onDone(false); return@launch }
            val opIds = buyable.map { line ->
                val payload = TxOpPayload(
                    assetId = line.assetId,
                    side = "buy",
                    quantity = line.qty,
                    price = line.nativePrice,
                    fee = 0.0,
                    executedAt = executedAtIso(LocalDate.now()),
                    note = null,
                    payFromCash = true,
                    addProceedsToCash = null,
                    assetSymbol = line.symbol,
                    assetName = line.name,
                )
                engine.enqueue(OpType.TX_BUY, pid, json.encodeToString(TxOpPayload.serializer(), payload)).id
            }
            try { engine.drain() } catch (_: Exception) {}
            scheduler.scheduleDrain()
            portfolioRepo.refreshPortfolioDetail(pid)
            _committing.value = false
            // Honest outcome: only report success when the buys actually landed
            // (the server re-validates cash/oversell; §7.3).
            val statuses = opIds.mapNotNull { db.syncOpDao().getById(it)?.status }
            val done = statuses.count { it == at.bettertrack.app.sync.OpStatus.DONE.wire }
            if (done == buyable.size) {
                _message.value = null
                onDone(true)
            } else {
                _message.value = "Added $done of ${buyable.size}. The rest need attention — open Pending sync."
                onDone(false)
            }
        }
    }

    fun clearAllocation() { _allocation.value = null }

    fun deleteConglomerate(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.delete(conglomerateId)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConglomerateDetailScreen(
    conglomerateId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val vm: ConglomerateDetailViewModel = viewModel {
        ConglomerateDetailViewModel(
            AppGraph.conglomerateRepository, AppGraph.portfolioRepository,
            AppGraph.syncEngine, AppGraph.syncScheduler, AppGraph.database, AppGraph.json, conglomerateId,
        )
    }
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val backtest by vm.backtest.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val allocation by vm.allocation.collectAsStateWithLifecycle()
    val calculating by vm.calculating.collectAsStateWithLifecycle()
    val committing by vm.committing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val portfolioName by vm.selectedPortfolioName.collectAsStateWithLifecycle()

    var budgetText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AllocateMode.DEFAULT) }
    var atLeastOne by remember { mutableStateOf(true) }
    var stepText by remember { mutableStateOf("") }
    var committed by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "", color = bt.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back), tint = bt.textSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(conglomerateId) }) { Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.bt_conglo_edit), tint = bt.textSecondary) }
                    IconButton(onClick = { deleteConfirm = true }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.bt_conglo_delete), tint = bt.loss) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bt.bg, titleContentColor = bt.textPrimary),
            )
        },
    ) { pad ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = bt.gold) }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Composition summary.
            item(key = "composition") {
                BtCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.bt_conglo_composition), style = MaterialTheme.typography.titleSmall, color = bt.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        d.positions.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(p.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = bt.textPrimary, modifier = Modifier.width(88.dp))
                                Text(p.name, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatPercent(p.weightPct, locale, showSign = false), style = BtTheme.type.moneySmall, color = bt.textPrimary)
                            }
                        }
                    }
                }
            }

            // Past-performance backtest.
            item(key = "backtest") {
                BtCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
                        Text(stringResource(R.string.bt_conglo_performance), style = MaterialTheme.typography.titleSmall, color = bt.textSecondary)
                        Spacer(Modifier.height(10.dp))
                        when (val b = backtest) {
                            BacktestUiState.Loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = bt.gold) }
                            BacktestUiState.Empty, BacktestUiState.Failed -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.bt_conglo_no_backtest), style = MaterialTheme.typography.bodySmall, color = bt.textMuted)
                            }
                            is BacktestUiState.Loaded -> {
                                BtPriceChart(points = b.backtest.series, modifier = Modifier.fillMaxWidth().height(180.dp))
                                b.backtest.stats?.let { st ->
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        st.totalReturnPct?.let { StatCell(stringResource(R.string.bt_conglo_total_return), formatPercent(it, locale), if (it >= 0) bt.gain else bt.loss) }
                                        st.cagrPct?.let { StatCell(stringResource(R.string.bt_conglo_cagr), formatPercent(it, locale), bt.textPrimary) }
                                        st.maxDrawdownPct?.let { StatCell(stringResource(R.string.bt_conglo_drawdown), formatPercent(it, locale), bt.loss) }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BacktestRange.entries.forEach { r ->
                                BtChip(text = r.label, selected = r == range, onClick = { vm.setRange(r) })
                            }
                        }
                    }
                }
            }

            // Budget calculator.
            item(key = "budget") {
                BtCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.bt_conglo_budget), style = MaterialTheme.typography.titleSmall, color = bt.textSecondary)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = budgetText,
                            onValueChange = { budgetText = sanitizeDecimalInput(it, maxDecimals = 2) },
                            label = { Text(stringResource(R.string.bt_conglo_budget_label)) },
                            singleLine = true,
                            suffix = { Text("€", color = bt.textMuted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                            textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
                        )
                        Spacer(Modifier.height(12.dp))
                        // Buying mode (§6.7) — whole vs fractional, mirroring the web
                        // budget calculator (default = whole). Whole mode shows the
                        // "at least one share" opt-in; fractional mode shows an
                        // optional quantity step (server default when left empty).
                        Text(
                            stringResource(R.string.bt_conglo_mode_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = bt.textMuted,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BtChip(
                                text = stringResource(R.string.bt_conglo_mode_whole),
                                selected = mode == AllocateMode.WHOLE,
                                onClick = { mode = AllocateMode.WHOLE },
                            )
                            BtChip(
                                text = stringResource(R.string.bt_conglo_mode_fractional),
                                selected = mode == AllocateMode.FRACTIONAL,
                                onClick = { mode = AllocateMode.FRACTIONAL },
                            )
                        }
                        if (mode == AllocateMode.WHOLE) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.bt_conglo_at_least_one), style = MaterialTheme.typography.bodyMedium, color = bt.textPrimary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = atLeastOne, onCheckedChange = { atLeastOne = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = bt.gold, checkedThumbColor = bt.onGold, uncheckedTrackColor = bt.border, uncheckedThumbColor = bt.textMuted, uncheckedBorderColor = bt.borderStrong),
                                )
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = stepText,
                                onValueChange = { stepText = sanitizeDecimalInput(it, maxDecimals = 6) },
                                label = { Text(stringResource(R.string.bt_conglo_step_label)) },
                                placeholder = { Text("0.0001", color = bt.textMuted) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_conglo_calculate),
                            onClick = {
                                parseLocalizedDecimal(budgetText)?.let { budget ->
                                    val step = if (mode == AllocateMode.FRACTIONAL) {
                                        parseLocalizedDecimal(stepText)?.takeIf { it > 0.0 }
                                    } else {
                                        null
                                    }
                                    vm.calculate(budget, mode, atLeastOne, step)
                                }
                            },
                            enabled = (parseLocalizedDecimal(budgetText) ?: 0.0) > 0.0 && !calculating,
                            loading = calculating,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                        )
                    }
                }
            }

            // Allocation result + commit.
            allocation?.let { alloc ->
                item(key = "alloc-header") {
                    Text(stringResource(R.string.bt_conglo_buy_list), style = MaterialTheme.typography.titleMedium, color = bt.textPrimary, modifier = Modifier.padding(top = 4.dp))
                }
                items(count = alloc.lines.size, key = { "al-" + alloc.lines[it].assetId }) { i ->
                    val line = alloc.lines[i]
                    BtCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (line.unbuyable) bt.textMuted else bt.textPrimary)
                                Text(
                                    text = if (line.unbuyable) (line.note ?: stringResource(R.string.bt_conglo_unbuyable))
                                    else stringResource(R.string.bt_conglo_qty, trimQty(line.qty), formatPercent(line.actualPct, locale, showSign = false)),
                                    style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            MoneyText(value = line.costEur, style = BtTheme.type.moneySmall)
                        }
                    }
                }
                item(key = "alloc-totals") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.bt_conglo_invested, formatEur(alloc.totalCostEur, locale)), style = MaterialTheme.typography.bodyMedium, color = bt.textSecondary)
                        Text(stringResource(R.string.bt_conglo_remainder, formatEur(alloc.leftoverEur, locale)), style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
                    }
                }
                alloc.quoteNotice?.let { item(key = "notice") { Text(it, style = MaterialTheme.typography.bodySmall, color = bt.textMuted) } }
                item(key = "commit") {
                    if (committed) {
                        Text(stringResource(R.string.bt_conglo_committed, portfolioName ?: ""), style = MaterialTheme.typography.bodyMedium, color = bt.gain, modifier = Modifier.padding(vertical = 4.dp))
                    } else {
                        BtPrimaryButton(
                            text = stringResource(R.string.bt_conglo_add_to_portfolio, portfolioName ?: ""),
                            onClick = { vm.commit { ok -> committed = ok } },
                            enabled = !committing && alloc.lines.any { !it.unbuyable && it.qty > 0.0 },
                            loading = committing,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        )
                    }
                }
            }

            message?.let { item(key = "msg") { Text(it, style = MaterialTheme.typography.bodySmall, color = bt.loss) } }
        }
    }

    if (deleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_conglo_delete)) },
            text = { Text(stringResource(R.string.bt_conglo_delete_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = false; vm.deleteConglomerate { onDelete() } }) {
                    Text(stringResource(R.string.bt_conglo_delete), color = bt.loss)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { deleteConfirm = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val bt = BtTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = bt.textMuted)
        Text(value, style = BtTheme.type.moneySmall, color = color)
    }
}

private fun trimQty(v: Double): String {
    val r = kotlin.math.round(v * 100000) / 100000.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
