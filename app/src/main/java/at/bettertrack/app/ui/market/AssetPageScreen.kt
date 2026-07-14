package at.bettertrack.app.ui.market

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.AssetPriceSeries
import at.bettertrack.app.data.repo.AssetRange
import at.bettertrack.app.data.repo.AssetSnapshot
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.data.repo.PricePoint
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.charts.BtPriceChart
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

sealed interface AssetDetailUiState {
    data object Loading : AssetDetailUiState
    data class Loaded(val snapshot: AssetSnapshot) : AssetDetailUiState
    data object OfflineState : AssetDetailUiState
    data class Error(val message: String) : AssetDetailUiState
}

sealed interface AssetHistoryUiState {
    data object Loading : AssetHistoryUiState
    data class Loaded(val series: AssetPriceSeries) : AssetHistoryUiState
    data object Empty : AssetHistoryUiState
    data object Failed : AssetHistoryUiState
}

class AssetPageViewModel(
    private val market: MarketRepository,
    private val portfolioRepo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
    private val assetId: String,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val watchlistIds: StateFlow<Set<String>> = market.watchlistAssetIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val selectedPortfolioId: StateFlow<String?> = portfolioRepo.selectedPortfolioId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _detail = MutableStateFlow<AssetDetailUiState>(AssetDetailUiState.Loading)
    val detail: StateFlow<AssetDetailUiState> = _detail.asStateFlow()

    private val _range = MutableStateFlow(AssetRange.DEFAULT)
    val range: StateFlow<AssetRange> = _range.asStateFlow()

    private val _history = MutableStateFlow<AssetHistoryUiState>(AssetHistoryUiState.Loading)
    val history: StateFlow<AssetHistoryUiState> = _history.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch { market.refreshWorkboard() }
        loadDetail()
        loadHistory(_range.value)
    }

    private fun loadDetail() {
        viewModelScope.launch {
            _detail.value = AssetDetailUiState.Loading
            when (val r = market.assetDetail(assetId)) {
                is BtResult.Ok -> _detail.value = AssetDetailUiState.Loaded(r.value)
                is BtResult.Err -> _detail.value =
                    if (r.error.isNetwork) AssetDetailUiState.OfflineState
                    else AssetDetailUiState.Error(r.error.userMessage)
            }
        }
    }

    private fun loadHistory(range: AssetRange) {
        viewModelScope.launch {
            _history.value = AssetHistoryUiState.Loading
            when (val r = market.assetHistory(assetId, range)) {
                is BtResult.Ok -> _history.value =
                    if (r.value.points.size < 2) AssetHistoryUiState.Empty
                    else AssetHistoryUiState.Loaded(r.value)

                is BtResult.Err -> _history.value = AssetHistoryUiState.Failed
            }
        }
    }

    fun setRange(range: AssetRange) {
        if (range == _range.value) return
        _range.value = range
        loadHistory(range)
    }

    fun toggleWatchlist() {
        viewModelScope.launch {
            if (assetId in watchlistIds.value) market.removeFromWatchlist(assetId)
            else market.addToWatchlist(assetId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPageScreen(
    assetId: String,
    onBack: () -> Unit,
    onTrade: (assetId: String, symbol: String, name: String, currency: String, portfolioId: String?, sell: Boolean) -> Unit,
) {
    val vm: AssetPageViewModel = viewModel {
        AssetPageViewModel(
            AppGraph.marketRepository,
            AppGraph.portfolioRepository,
            AppGraph.connectivityMonitor,
            assetId,
        )
    }
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val watchlistIds by vm.watchlistIds.collectAsStateWithLifecycle()
    val selectedPid by vm.selectedPortfolioId.collectAsStateWithLifecycle()

    val loaded = detail as? AssetDetailUiState.Loaded
    val asset = loaded?.snapshot?.asset
    var pickerOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = asset?.symbol ?: stringResource(R.string.bt_asset_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = bt.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    if (asset != null) {
                        WatchlistStar(
                            inWatchlist = asset.id in watchlistIds,
                            enabled = isOnline,
                            onToggle = { pickerOpen = true },
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
            when (val d = detail) {
                AssetDetailUiState.Loading -> AssetPageSkeleton()

                AssetDetailUiState.OfflineState -> BtEmptyState(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    title = stringResource(R.string.bt_requires_connection_title),
                    message = stringResource(R.string.bt_asset_requires_connection_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                is AssetDetailUiState.Error -> BtErrorState(
                    message = d.message,
                    onRetry = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )

                is AssetDetailUiState.Loaded -> AssetLoadedContent(
                    snapshot = d.snapshot,
                    history = history,
                    range = range,
                    isOnline = isOnline,
                    locale = locale,
                    onRange = { vm.setRange(it) },
                    onBuy = {
                        onTrade(d.snapshot.asset.id, d.snapshot.asset.symbol, d.snapshot.asset.name, d.snapshot.asset.currency, selectedPid, false)
                    },
                    onSell = {
                        onTrade(d.snapshot.asset.id, d.snapshot.asset.symbol, d.snapshot.asset.name, d.snapshot.asset.currency, selectedPid, true)
                    },
                )
            }
        }
    }

    if (pickerOpen && asset != null) {
        at.bettertrack.app.ui.watchlist.BoardPickerSheet(asset = asset, onDismiss = { pickerOpen = false })
    }
}

@Composable
private fun AssetLoadedContent(
    snapshot: AssetSnapshot,
    history: AssetHistoryUiState,
    range: AssetRange,
    isOnline: Boolean,
    locale: Locale,
    onRange: (AssetRange) -> Unit,
    onBuy: () -> Unit,
    onSell: () -> Unit,
) {
    val bt = BtTheme.colors
    var scrub by remember { mutableStateOf<PricePoint?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Name + meta.
        item(key = "header") {
            Column {
                Text(
                    text = snapshot.asset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(snapshot.asset.exchange, assetTypeLabel(snapshot.asset.type))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }

        // Price hero (scrub overrides the live price).
        item(key = "price") {
            val scrubbed = scrub
            val priceValue = scrubbed?.close ?: snapshot.nativePrice
            Column {
                Text(
                    text = if (scrubbed != null) formatScrubTime(scrubbed.timeMs, locale)
                    else stringResource(R.string.bt_asset_price),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = priceValue?.let { formatPrice(it, snapshot.quoteCurrency, locale) }
                        ?: stringResource(R.string.bt_value_dash),
                    style = BtTheme.type.moneyLarge,
                    color = bt.textPrimary,
                )
                if (scrubbed == null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        snapshot.dayChangePct?.let { pct ->
                            BtBadge(
                                text = formatPercent(pct, locale),
                                kind = if (pct >= 0) BtBadgeKind.Gain else BtBadgeKind.Loss,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (snapshot.quoteCurrency.uppercase() != "EUR" && snapshot.eurPrice != null) {
                            Text(
                                // The ≈EUR is itself a UNIT price → sub-cent aware (rule 4),
                                // so a sub-cent asset shows "≈ 0,0000039 €", not "≈ 0,00 €".
                                text = "≈ " + formatPrice(snapshot.eurPrice, "EUR", locale),
                                style = MaterialTheme.typography.bodyMedium,
                                color = bt.textSecondary,
                            )
                        }
                    }
                }
            }
        }

        // Chart + range chips.
        item(key = "chart") {
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
                    when (history) {
                        AssetHistoryUiState.Loading -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(color = bt.gold) }

                        AssetHistoryUiState.Empty, AssetHistoryUiState.Failed -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.bt_asset_chart_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textMuted,
                            )
                        }

                        is AssetHistoryUiState.Loaded -> BtPriceChart(
                            points = history.series.points,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            onScrub = { scrub = it },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssetRange.entries.forEach { r ->
                            BtChip(
                                text = r.label,
                                selected = r == range,
                                onClick = { onRange(r) },
                            )
                        }
                    }
                }
            }
        }

        // Key stats.
        item(key = "stats") {
            BtCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.bt_asset_stats),
                        style = MaterialTheme.typography.titleSmall,
                        color = bt.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    StatRow(
                        stringResource(R.string.bt_asset_prev_close),
                        snapshot.prevClose?.let { formatPrice(it, snapshot.quoteCurrency, locale) }
                            ?: stringResource(R.string.bt_value_dash),
                    )
                    snapshot.dayChangePct?.let {
                        StatRow(stringResource(R.string.bt_asset_day_change), formatPercent(it, locale))
                    }
                    StatRow(stringResource(R.string.bt_asset_currency), snapshot.quoteCurrency.uppercase())
                    snapshot.asOf?.let {
                        StatRow(stringResource(R.string.bt_asset_as_of), formatAsOf(it, locale))
                    }
                }
            }
        }

        // Quick buy / sell.
        item(key = "trade") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BtPrimaryButton(
                    text = stringResource(R.string.bt_action_buy),
                    onClick = onBuy,
                    enabled = isOnline,
                    modifier = Modifier.weight(1f).height(48.dp),
                )
                BtSecondaryButton(
                    text = stringResource(R.string.bt_action_sell),
                    onClick = onSell,
                    enabled = isOnline,
                    modifier = Modifier.weight(1f).height(48.dp),
                )
            }
            if (!isOnline) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.bt_requires_connection_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = bt.textMuted)
        Text(
            value,
            style = BtTheme.type.moneySmall,
            fontWeight = FontWeight.Medium,
            color = bt.textPrimary,
        )
    }
}

@Composable
private fun AssetPageSkeleton() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BtSkeleton(Modifier.fillMaxWidth(0.55f).height(20.dp))
        BtSkeleton(Modifier.fillMaxWidth(0.4f).height(40.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(200.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(140.dp))
    }
}

private fun formatScrubTime(timeMs: Long, locale: Locale): String =
    Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

private fun formatAsOf(iso: String, locale: Locale): String = try {
    Instant.parse(iso).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
} catch (_: Exception) {
    iso
}
