package at.bettertrack.app.ui.conglomerate

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.ConglomerateRepository
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.market.assetTypeLabel
import at.bettertrack.app.ui.portfolio.parseLocalizedDecimal
import at.bettertrack.app.ui.portfolio.sanitizeDecimalInput
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs

data class BuilderPosition(val asset: MarketAsset, val weightText: String)

@OptIn(FlowPreview::class)
class ConglomerateBuilderViewModel(
    private val repo: ConglomerateRepository,
    private val market: MarketRepository,
    private val conglomerateId: String?,
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _positions = MutableStateFlow<List<BuilderPosition>>(emptyList())
    val positions: StateFlow<List<BuilderPosition>> = _positions.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _loading = MutableStateFlow(conglomerateId != null)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ── Add-asset search ─────────────────────────────────────────────────────
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<List<MarketAsset>>(emptyList())
    val results: StateFlow<List<MarketAsset>> = _results.asStateFlow()

    init {
        viewModelScope.launch {
            _query.debounce(260).collectLatest { raw ->
                val q = raw.trim()
                if (q.isEmpty()) { _results.value = emptyList(); return@collectLatest }
                when (val r = market.search(q)) {
                    is BtResult.Ok -> _results.value = r.value.results
                    is BtResult.Err -> _results.value = emptyList()
                }
            }
        }
        if (conglomerateId != null) load(conglomerateId)
    }

    private fun load(id: String) {
        viewModelScope.launch {
            when (val r = repo.detail(id)) {
                is BtResult.Ok -> {
                    _name.value = r.value.name
                    _positions.value = r.value.positions.map {
                        BuilderPosition(
                            MarketAsset(it.assetId, it.symbol, it.name, null, it.type, it.currency, false),
                            trimWeight(it.weightPct),
                        )
                    }
                }

                is BtResult.Err -> _error.value = r.error.userMessage
            }
            _loading.value = false
        }
    }

    fun setName(v: String) { _name.value = v }
    fun setQuery(v: String) { _query.value = v }

    fun addAsset(asset: MarketAsset) {
        if (_positions.value.any { it.asset.id == asset.id }) return
        _positions.value = _positions.value + BuilderPosition(asset, "")
        _query.value = ""
        _results.value = emptyList()
    }

    fun removeAt(index: Int) {
        _positions.value = _positions.value.filterIndexed { i, _ -> i != index }
    }

    fun setWeight(index: Int, text: String) {
        _positions.value = _positions.value.mapIndexed { i, p ->
            if (i == index) p.copy(weightText = sanitizeDecimalInput(text, maxDecimals = 2)) else p
        }
    }

    /** Split 100% evenly across the current positions (convenience). */
    fun evenSplit() {
        val n = _positions.value.size
        if (n == 0) return
        val each = 100.0 / n
        val base = trimWeight(kotlin.math.floor(each * 100) / 100)
        _positions.value = _positions.value.mapIndexed { i, p ->
            // Give the remainder to the last row so it sums to exactly 100.
            if (i == n - 1) p.copy(weightText = trimWeight(100.0 - base.toDouble() * (n - 1))) else p.copy(weightText = base)
        }
    }

    val totalWeight: Double get() = _positions.value.sumOf { parseLocalizedDecimal(it.weightText) ?: 0.0 }

    fun canSave(): Boolean =
        _name.value.trim().isNotEmpty() && _positions.value.size >= 2 && abs(totalWeight - 100.0) < 0.5

    fun save(onDone: (String?) -> Unit) {
        if (_busy.value || !canSave()) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val weights = _positions.value.map { it.asset.id to (parseLocalizedDecimal(it.weightText) ?: 0.0) }
            val id = if (conglomerateId != null) {
                conglomerateId
            } else {
                when (val c = repo.create(_name.value, null)) {
                    is BtResult.Ok -> c.value.id
                    is BtResult.Err -> { _error.value = c.error.userMessage; _busy.value = false; onDone(null); return@launch }
                }
            }
            val r = repo.replacePositions(id, weights)
            _busy.value = false
            if (r is BtResult.Err) { _error.value = r.error.userMessage; onDone(null) } else onDone(id)
        }
    }

    private fun trimWeight(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConglomerateBuilderScreen(
    conglomerateId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val vm: ConglomerateBuilderViewModel = viewModel {
        ConglomerateBuilderViewModel(AppGraph.conglomerateRepository, AppGraph.marketRepository, conglomerateId)
    }
    val bt = BtTheme.colors
    val name by vm.name.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var searchOpen by remember { mutableStateOf(false) }

    val total = positions.sumOf { parseLocalizedDecimal(it.weightText) ?: 0.0 }
    val totalOk = abs(total - 100.0) < 0.5

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (conglomerateId == null) R.string.bt_conglo_new else R.string.bt_conglo_edit), color = bt.textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.bt_action_back), tint = bt.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bt.bg, titleContentColor = bt.textPrimary),
            )
        },
    ) { pad ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = bt.gold) }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = vm::setName,
                    label = { Text(stringResource(R.string.bt_conglo_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
                )
            }
            item(key = "weights-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bt_conglo_positions), style = MaterialTheme.typography.titleSmall, color = bt.textSecondary, modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.bt_conglo_total, trimPct(total)),
                        style = BtTheme.type.moneySmall,
                        color = if (totalOk) bt.gain else bt.textMuted,
                    )
                }
            }
            itemsIndexed(positions) { index, p ->
                PositionRow(p, onWeight = { vm.setWeight(index, it) }, onRemove = { vm.removeAt(index) })
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BtSecondaryButton(text = stringResource(R.string.bt_conglo_add_asset), onClick = { searchOpen = true }, modifier = Modifier.weight(1f))
                    if (positions.size >= 2) {
                        BtSecondaryButton(text = stringResource(R.string.bt_conglo_even), onClick = { vm.evenSplit() }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (error != null) {
                item(key = "error") { Text(error!!, style = MaterialTheme.typography.bodySmall, color = bt.loss) }
            }
            item(key = "save") {
                BtPrimaryButton(
                    text = stringResource(R.string.bt_conglo_save),
                    onClick = { vm.save { id -> if (id != null) onSaved(id) } },
                    enabled = name.trim().isNotEmpty() && positions.size >= 2 && totalOk && !busy,
                    loading = busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    if (searchOpen) {
        AssetSearchSheet(vm = vm, onPick = { vm.addAsset(it); searchOpen = false }, onDismiss = { searchOpen = false })
    }
}

@Composable
private fun PositionRow(p: BuilderPosition, onWeight: (String) -> Unit, onRemove: () -> Unit) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.asset.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                Text(p.asset.name, style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedTextField(
                value = p.weightText,
                onValueChange = onWeight,
                singleLine = true,
                suffix = { Text("%", color = bt.textMuted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.width(104.dp),
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.bt_conglo_remove), tint = bt.textMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetSearchSheet(
    vm: ConglomerateBuilderViewModel,
    onPick: (MarketAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    val bt = BtTheme.colors
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = bt.surface, contentColor = bt.textPrimary) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).imePadding().navigationBarsPadding()) {
            Text(stringResource(R.string.bt_conglo_add_asset), style = MaterialTheme.typography.titleMedium, color = bt.textPrimary, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.bt_txform_asset_search_hint), color = bt.textMuted) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.bt_search_clear), tint = bt.textMuted)
                    }
                },
                colors = at.bettertrack.app.ui.customassets.dialogFieldColors(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(count = results.size, key = { results[it].id }) { i ->
                    val a = results[i]
                    BtCard(modifier = Modifier.fillMaxWidth(), onClick = { onPick(a) }) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = bt.textPrimary)
                                Text(listOfNotNull(a.name, a.exchange).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = bt.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = bt.gold, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun trimPct(v: Double): String {
    val r = kotlin.math.round(v * 10) / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}
