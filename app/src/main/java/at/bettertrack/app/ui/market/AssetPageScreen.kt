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
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
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
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.btExpandHeader
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtInlineError
import at.bettertrack.app.ui.components.BtOfflineState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The error code [at.bettertrack.app.data.storage.NoLivePricesMarketDataSource] raises. */
private const val NO_LIVE_PRICES_CODE = "NO_LIVE_PRICES"

sealed interface AssetDetailUiState {
    data object Loading : AssetDetailUiState
    data class Loaded(val snapshot: AssetSnapshot) : AssetDetailUiState
    data object OfflineState : AssetDetailUiState
    data class Error(val message: BtMessage) : AssetDetailUiState

    /**
     * W6 — this mode has no live quotes, so there is no asset page to render.
     *
     * Its own state rather than an [Error] carrying
     * `NoLivePricesMarketDataSource.MSG_NO_PRICES`: that message is a hardcoded
     * English constant on the data layer, and a designed, translated state is
     * what plan §5 W6 asks for. It also correctly drops the Retry button — there
     * is nothing to retry, and offering one would promise a fix that cannot come.
     */
    data object NoLivePrices : AssetDetailUiState
}

sealed interface AssetHistoryUiState {
    data object Loading : AssetHistoryUiState
    data class Loaded(val series: AssetPriceSeries) : AssetHistoryUiState
    data object Empty : AssetHistoryUiState

    /**
     * R3 §2: carries the failure's [BtMessage] rather than being a bare marker.
     * The screen used to render `Empty, Failed ->` as one branch, so a dropped
     * request read to the user as "this asset has no price history" — a
     * statement about the ASSET, made on the strength of a network error. The
     * two are different facts and now render differently; carrying the message
     * is what lets the failure branch say which failure it was.
     */
    data class Failed(val message: BtMessage) : AssetHistoryUiState
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
                is BtResult.Err -> _detail.value = when {
                    r.error.isNetwork -> AssetDetailUiState.OfflineState
                    r.error.code == NO_LIVE_PRICES_CODE -> AssetDetailUiState.NoLivePrices
                    else -> AssetDetailUiState.Error(r.error.asMessage())
                }
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

                is BtResult.Err -> _history.value = AssetHistoryUiState.Failed(r.error.asMessage())
            }
        }
    }

    /** Retry the chart alone, at the range the user is already looking at. */
    fun retryHistory() = loadHistory(_range.value)

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
    val locale = rememberBtLocale()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val watchlistIds by vm.watchlistIds.collectAsStateWithLifecycle()
    val selectedPid by vm.selectedPortfolioId.collectAsStateWithLifecycle()

    val loaded = detail as? AssetDetailUiState.Loaded
    val asset = loaded?.snapshot?.asset
    var pickerOpen by remember { mutableStateOf(false) }

    // Only the Loaded branch scrolls; the other four are centred states. A
    // collapsing header stranded half-way over a centred empty state is the
    // classic broken-looking bar — there is nothing on screen a finger could
    // scroll to bring the title back. The header still renders for those branches
    // (the screen needs its title and its back affordance no matter what it is
    // showing), and it keeps the one collapsing behaviour so Loaded behaves like
    // every other R2 screen.
    //
    // R3 §1 — two things guard the stranded bar now instead of one. `canScroll`
    // stops a non-scrolling branch from collapsing in the first place; the effect
    // handles the case `canScroll` cannot, which is arriving in such a branch
    // ALREADY collapsed (scroll down through a loaded page, hit Retry) — and it
    // now animates the bar back over the same 300ms the screen transitions use
    // rather than snapping 48dp of header in a single frame.
    val scrollable = detail is AssetDetailUiState.Loaded
    val scrollBehavior = rememberBtCollapsingHeaderBehavior(canScroll = { scrollable })
    val reducedMotion = rememberReducedMotion()
    LaunchedEffect(scrollable) {
        if (!scrollable) scrollBehavior.btExpandHeader(reducedMotion)
    }
    Scaffold(
        // AssetLoadedContent owns the LazyColumn; nestedScroll propagates down
        // from here, so that child keeps its current signature.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                // A ticker, not a sentence — single line, no subtitle to fade.
                title = asset?.symbol ?: stringResource(R.string.bt_asset_title),
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
                // The ONE action. The null check stays inside the slot so the star
                // remains absent — not merely disabled — until there is an asset
                // to watch, exactly as before.
                action = {
                    if (asset != null) {
                        WatchlistStar(
                            inWatchlist = asset.id in watchlistIds,
                            enabled = isOnline,
                            onToggle = { pickerOpen = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val d = detail) {
                AssetDetailUiState.Loading -> AssetPageSkeleton()

                AssetDetailUiState.OfflineState -> BtOfflineState(
                    message = stringResource(R.string.bt_asset_requires_connection_message),
                    onRetry = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )

                is AssetDetailUiState.Error -> BtErrorState(
                    message = d.message,
                    onRetry = { vm.load() },
                    modifier = Modifier.align(Alignment.Center),
                )

                AssetDetailUiState.NoLivePrices -> BtEmptyState(
                    icon = Icons.Outlined.QueryStats,
                    title = stringResource(R.string.bt_price_none),
                    message = stringResource(R.string.bt_price_asset_unavailable),
                    modifier = Modifier.align(Alignment.Center),
                )

                is AssetDetailUiState.Loaded -> AssetLoadedContent(
                    snapshot = d.snapshot,
                    history = history,
                    range = range,
                    isOnline = isOnline,
                    locale = locale,
                    onRange = { vm.setRange(it) },
                    onRetryHistory = { vm.retryHistory() },
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
    onRetryHistory: () -> Unit,
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
                        // R3 §2: a skeleton, not a spinner — the block that is
                        // coming is a chart of known size, and the rest of the app
                        // says so with BtSkeleton (see PortfolioOverviewScreen's
                        // hero chart). A spinner here was the odd one out.
                        AssetHistoryUiState.Loading -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) { BtSkeleton(Modifier.fillMaxWidth().height(140.dp)) }

                        AssetHistoryUiState.Empty -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) { BtInlineEmpty(stringResource(R.string.bt_asset_chart_empty)) }

                        // The chart is secondary to the price above it, so the
                        // failure stays inline and keeps the same shape the intel
                        // sections on this page use — one line plus a retry, never
                        // a full-surface error over content that already loaded.
                        is AssetHistoryUiState.Failed -> Box(
                            Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BtInlineError(
                                message = history.message,
                                onRetry = onRetryHistory,
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

        // V5 S2c: dividends, earnings, news and splits — below the price and the
        // stats, because those are why someone opened this page; intel is what
        // they read next. The section renders NOTHING at all when the provider
        // serves none of it (every custom asset), so this item costs an
        // unsupported asset no space and no heading.
        item(key = "intel") {
            AssetIntelSection(assetId = snapshot.asset.id, modifier = Modifier.fillMaxWidth())
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
