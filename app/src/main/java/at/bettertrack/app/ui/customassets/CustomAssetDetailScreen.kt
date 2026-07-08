package at.bettertrack.app.ui.customassets

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.AlertDialog
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
import at.bettertrack.app.data.db.CustomAssetEntity
import at.bettertrack.app.data.db.ValuePointEntity
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.sync.OpStatus
import at.bettertrack.app.sync.OpType
import at.bettertrack.app.sync.SyncEngine
import at.bettertrack.app.sync.SyncScheduler
import at.bettertrack.app.sync.ValuePointOpPayload
import at.bettertrack.app.ui.charts.BtStepLineChart
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.portfolio.PendingStatusBadge
import at.bettertrack.app.ui.portfolio.PendingUiStatus
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Custom-asset detail (Step 10, §6.4): the value-point step-line chart, the
 * point history, the quick "update value now" action (offline-capable via the
 * queue), plus point delete and asset edit/delete (online-only per §7.2).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomAssetDetailViewModel(
    private val repo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    private val db: BtDatabase,
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val json: Json,
    private val assetId: String,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val asset: StateFlow<CustomAssetEntity?> = repo.customAsset(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val points: StateFlow<List<ValuePointEntity>> = repo.valuePoints(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<List<PendingValuePoint>> =
        db.syncOpDao().observeAll()
            .map { ops -> decodePendingValuePoints(ops, json, assetId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            repo.refreshValuePoints(assetId)
            _refreshing.value = false
        }
    }

    /** "Update value now" (§6.4) — enqueue a value point; drains when online. */
    fun recordValue(date: LocalDate, value: Double, onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val payload = ValuePointOpPayload(assetId, date.toString(), value)
            val op = engine.enqueue(
                OpType.CUSTOM_ASSET_VALUE_POINT,
                portfolioId = null,
                payloadJson = json.encodeToString(ValuePointOpPayload.serializer(), payload),
            )
            if (isOnline.value) {
                try {
                    engine.drain()
                } catch (_: Exception) {
                }
            }
            val after = db.syncOpDao().getById(op.id)
            when (after?.status) {
                OpStatus.NEEDS_ATTENTION.wire ->
                    _error.value = after.serverError ?: "BetterTrack rejected this value."

                else -> {
                    if (after?.status == OpStatus.PENDING.wire || after?.status == OpStatus.IN_FLIGHT.wire) {
                        scheduler.scheduleDrain()
                    }
                }
            }
            _busy.value = false
            onDone(after?.status != OpStatus.NEEDS_ATTENTION.wire)
        }
    }

    /** Delete a synced value point (online-only PUT full-replace). */
    fun deletePoint(date: String, onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val remaining = points.value.filter { it.date != date }
            val r = repo.putValuePoints(assetId, remaining)
            if (r is BtResult.Err) _error.value = r.error.userMessage
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    fun editAsset(name: String, category: String, onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val r = repo.updateCustomAsset(assetId, name, category)
            if (r is BtResult.Err) _error.value = r.error.userMessage
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    fun deleteAsset(onDone: (Boolean) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val r = repo.deleteCustomAsset(assetId)
            if (r is BtResult.Err) _error.value = r.error.userMessage
            _busy.value = false
            onDone(r is BtResult.Ok)
        }
    }

    fun clearError() {
        _error.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomAssetDetailScreen(
    assetId: String,
    onBack: () -> Unit,
) {
    val vm: CustomAssetDetailViewModel = viewModel {
        CustomAssetDetailViewModel(
            repo = AppGraph.portfolioRepository,
            connectivity = AppGraph.connectivityMonitor,
            db = AppGraph.database,
            engine = AppGraph.syncEngine,
            scheduler = AppGraph.syncScheduler,
            json = AppGraph.json,
            assetId = assetId,
        )
    }
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val asset by vm.asset.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
        .collectAsStateWithLifecycle(initialValue = null)

    var updateOpen by rememberSaveable { mutableStateOf(false) }
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var deleteAssetOpen by rememberSaveable { mutableStateOf(false) }
    var deletePointDate by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = asset?.name ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        asset?.let {
                            Text(
                                text = categoryLabel(it.category),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
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
                actions = {
                    IconButton(onClick = { editOpen = true }, enabled = isOnline) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.bt_custom_edit),
                            tint = if (isOnline) bt.textSecondary else bt.border,
                        )
                    }
                    IconButton(onClick = { deleteAssetOpen = true }, enabled = isOnline) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.bt_custom_delete),
                            tint = if (isOnline) bt.loss else bt.border,
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
            if (!isOnline) OfflineBanner(asOfMs = dataAgeMs)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Current value hero (latest recorded point).
                item(key = "hero") {
                    Column {
                        Text(
                            text = stringResource(R.string.bt_custom_current_value),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                        Spacer(Modifier.height(2.dp))
                        val current = latestValue(points)
                        if (current != null) {
                            MoneyText(value = current, style = BtTheme.type.moneyLarge)
                        } else {
                            Text(
                                text = stringResource(R.string.bt_switcher_value_pending),
                                style = BtTheme.type.moneyLarge,
                                color = bt.textMuted,
                            )
                        }
                    }
                }

                // Step-line chart (§3.6).
                item(key = "chart") {
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = stringResource(R.string.bt_custom_value_history),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textSecondary,
                            )
                            Spacer(Modifier.height(12.dp))
                            val stepPoints = remember(points) { toStepPoints(points) }
                            if (stepPoints.size >= 2) {
                                BtStepLineChart(
                                    points = stepPoints,
                                    modifier = Modifier.fillMaxWidth().height(180.dp),
                                    lineColor = bt.gold,
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().height(180.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.bt_custom_chart_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = bt.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                // Update value now (§6.4 quick action).
                item(key = "update") {
                    BtPrimaryButton(
                        text = stringResource(R.string.bt_custom_update_now),
                        onClick = { updateOpen = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                }

                // Pending queued value points (§7.4).
                if (pending.isNotEmpty()) {
                    item(key = "pending-header") {
                        Text(
                            text = stringResource(R.string.bt_pending_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(count = pending.size, key = { "p-" + pending[it].opId }) { i ->
                        val p = pending[i]
                        BtCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatPointDate(p.date, locale),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = bt.textPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        PendingStatusBadge(p.status)
                                    }
                                    if (p.status == PendingUiStatus.NEEDS_ATTENTION && p.serverError != null) {
                                        Text(
                                            text = p.serverError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = bt.lossSoft,
                                        )
                                    }
                                }
                                MoneyText(value = p.value, style = BtTheme.type.moneySmall)
                            }
                        }
                    }
                }

                // Synced value points (newest first) — delete online-only.
                item(key = "points-header") {
                    Text(
                        text = stringResource(R.string.bt_custom_points_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = bt.textPrimary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (points.isEmpty() && pending.isEmpty()) {
                    item(key = "points-empty") {
                        Text(
                            text = stringResource(R.string.bt_custom_no_points),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                }
                val sorted = points.sortedByDescending { it.date }
                items(count = sorted.size, key = { sorted[it].date }) { i ->
                    val p = sorted[i]
                    BtCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatPointDate(p.date, locale),
                                style = MaterialTheme.typography.titleSmall,
                                color = bt.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            MoneyText(value = p.value, style = BtTheme.type.moneySmall)
                            IconButton(onClick = { deletePointDate = p.date }, enabled = isOnline) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(R.string.bt_custom_delete_point),
                                    tint = if (isOnline) bt.textMuted else bt.border,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (updateOpen) {
        UpdateValueSheet(
            busy = busy,
            error = error,
            locale = locale,
            onSubmit = { date, value -> vm.recordValue(date, value) { ok -> if (ok) updateOpen = false } },
            onDismiss = {
                updateOpen = false
                vm.clearError()
            },
        )
    }

    if (editOpen) {
        CustomAssetDialog(
            title = stringResource(R.string.bt_custom_edit_title),
            confirmLabel = stringResource(R.string.bt_switcher_rename_action),
            initialName = asset?.name ?: "",
            initialCategory = asset?.category ?: "other",
            busy = busy,
            error = error,
            onConfirm = { name, cat -> vm.editAsset(name, cat) { ok -> if (ok) editOpen = false } },
            onDismiss = {
                editOpen = false
                vm.clearError()
            },
        )
    }

    deletePointDate?.let { date ->
        AlertDialog(
            onDismissRequest = { deletePointDate = null },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_custom_delete_point_title)) },
            text = { Text(stringResource(R.string.bt_custom_delete_point_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePoint(date) { }
                    deletePointDate = null
                }) { Text(stringResource(R.string.bt_txform_delete_action), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { deletePointDate = null }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }

    if (deleteAssetOpen) {
        AlertDialog(
            onDismissRequest = { deleteAssetOpen = false },
            containerColor = bt.surface,
            titleContentColor = bt.textPrimary,
            textContentColor = bt.textSecondary,
            title = { Text(stringResource(R.string.bt_custom_delete_title)) },
            text = { Text(stringResource(R.string.bt_custom_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAsset { ok -> if (ok) onBack() }
                    deleteAssetOpen = false
                }) { Text(stringResource(R.string.bt_txform_delete_action), color = bt.loss) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAssetOpen = false }) {
                    Text(stringResource(R.string.bt_action_cancel), color = bt.textSecondary)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateValueSheet(
    busy: Boolean,
    error: String?,
    locale: Locale,
    onSubmit: (LocalDate, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var valueText by rememberSaveable { mutableStateOf("") }
    val value = parseLocalizedDecimal(valueText)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bt.surface,
        contentColor = bt.textPrimary,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bt_custom_update_title),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
            Text(
                text = stringResource(R.string.bt_custom_update_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            OutlinedTextField(
                value = valueText,
                onValueChange = { valueText = sanitizeDecimalInput(it, maxDecimals = 2) },
                label = { Text(stringResource(R.string.bt_custom_new_value)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                suffix = { Text("€", color = bt.textMuted) },
                textStyle = BtTheme.type.moneySmall.copy(fontSize = 17.sp),
                modifier = Modifier.fillMaxWidth(),
                colors = dialogFieldColors(),
            )
            if (error != null) {
                Text(text = error, style = MaterialTheme.typography.bodySmall, color = bt.loss)
            }
            BtPrimaryButton(
                text = stringResource(R.string.bt_custom_record_value),
                onClick = { value?.let { onSubmit(LocalDate.now(), it) } },
                enabled = value != null && value >= 0.0 && !busy,
                loading = busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        }
    }
}

private fun formatPointDate(date: String, locale: Locale): String = try {
    val ld = LocalDate.parse(date)
    formatTxDate(ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), locale)
} catch (_: Exception) {
    date
}
