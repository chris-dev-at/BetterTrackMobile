package at.bettertrack.app.ui.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.repo.MarketAsset
import at.bettertrack.app.data.repo.MarketRepository
import at.bettertrack.app.data.repo.PortfolioRepository
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.sync.ConnectivityMonitor
import at.bettertrack.app.ui.components.BtBadge
import at.bettertrack.app.ui.components.BtBadgeKind
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val assets: List<MarketAsset>, val enriching: Boolean) : SearchUiState
    data object Empty : SearchUiState
    data object OfflineState : SearchUiState
    data class Error(val message: String) : SearchUiState
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val market: MarketRepository,
    private val portfolioRepo: PortfolioRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    val watchlistIds: StateFlow<Set<String>> = market.watchlistAssetIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val selectedPortfolioId: StateFlow<String?> = portfolioRepo.selectedPortfolioId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        // Debounced, cancel-previous search: a new keystroke abandons the old
        // request AND any in-flight enrichment refetch (collectLatest).
        viewModelScope.launch {
            _query.debounce(280).collectLatest { raw ->
                val q = raw.trim()
                when {
                    q.isEmpty() -> _state.value = SearchUiState.Idle
                    !isOnline.value -> _state.value = SearchUiState.OfflineState
                    else -> runSearch(q)
                }
            }
        }
        // Keep the star state honest with the server.
        viewModelScope.launch { market.refreshWorkboard() }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun clearQuery() {
        _query.value = ""
    }

    private suspend fun runSearch(q: String) {
        _state.value = SearchUiState.Loading
        when (val r = market.search(q)) {
            is BtResult.Ok -> {
                val res = r.value
                _state.value = if (res.results.isEmpty() && !res.enriching) {
                    SearchUiState.Empty
                } else {
                    SearchUiState.Results(res.results, res.enriching)
                }
                // Providers still resolving → refetch once and merge (§6.5).
                if (res.enriching) {
                    delay(750)
                    when (val r2 = market.search(q)) {
                        is BtResult.Ok -> _state.value =
                            if (r2.value.results.isEmpty()) SearchUiState.Empty
                            else SearchUiState.Results(r2.value.results, r2.value.enriching)

                        is BtResult.Err -> { /* keep the partial results already shown */ }
                    }
                }
            }

            is BtResult.Err ->
                _state.value = if (r.error.isNetwork) SearchUiState.OfflineState
                else SearchUiState.Error(r.error.userMessage)
        }
    }

    fun retry() {
        val q = _query.value.trim()
        if (q.isNotEmpty()) viewModelScope.launch { runSearch(q) }
    }

    fun toggleWatchlist(assetId: String) {
        viewModelScope.launch {
            if (assetId in watchlistIds.value) market.removeFromWatchlist(assetId)
            else market.addToWatchlist(assetId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenAsset: (String) -> Unit,
    onTrade: (assetId: String, symbol: String, name: String, currency: String, portfolioId: String?) -> Unit,
) {
    val vm: SearchViewModel = viewModel {
        SearchViewModel(AppGraph.marketRepository, AppGraph.portfolioRepository, AppGraph.connectivityMonitor)
    }
    val bt = BtTheme.colors
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val watchlistIds by vm.watchlistIds.collectAsStateWithLifecycle()
    val selectedPid by vm.selectedPortfolioId.collectAsStateWithLifecycle()

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var pickerAsset by remember { androidx.compose.runtime.mutableStateOf<MarketAsset?>(null) }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Hand-rolled top bar: consume the status-bar inset ourselves
                    // (a Material3 TopAppBar does this internally) so the search
                    // field isn't drawn under the status bar / camera cutout on the
                    // edge-to-edge window.
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.bt_action_back),
                        tint = bt.textSecondary,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text(stringResource(R.string.bt_search_hint), color = bt.textMuted) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = bt.textMuted) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.clearQuery() }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.bt_search_clear),
                                    tint = bt.textMuted,
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    colors = searchFieldColors(),
                )
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (val s = state) {
                SearchUiState.Idle -> BtEmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.bt_search_prompt_title),
                    message = stringResource(R.string.bt_search_prompt_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                SearchUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = bt.gold)
                }

                SearchUiState.Empty -> BtEmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.bt_search_no_results_title),
                    message = stringResource(R.string.bt_search_no_results_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                SearchUiState.OfflineState -> BtEmptyState(
                    icon = Icons.Outlined.Search,
                    title = stringResource(R.string.bt_requires_connection_title),
                    message = stringResource(R.string.bt_search_requires_connection_message),
                    modifier = Modifier.align(Alignment.Center),
                )

                is SearchUiState.Error -> BtErrorState(
                    message = s.message,
                    onRetry = vm::retry,
                    modifier = Modifier.align(Alignment.Center),
                )

                is SearchUiState.Results -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (s.enriching) {
                        item(key = "enriching") { EnrichingRow() }
                    }
                    items(count = s.assets.size, key = { s.assets[it].id }) { i ->
                        val asset = s.assets[i]
                        SearchResultRow(
                            asset = asset,
                            inWatchlist = asset.id in watchlistIds,
                            watchEnabled = isOnline,
                            onOpen = { onOpenAsset(asset.id) },
                            onToggleWatchlist = { pickerAsset = asset },
                            onBuy = { onTrade(asset.id, asset.symbol, asset.name, asset.currency, selectedPid) },
                        )
                    }
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }

    pickerAsset?.let { asset ->
        at.bettertrack.app.ui.watchlist.BoardPickerSheet(asset = asset, onDismiss = { pickerAsset = null })
    }
}

@Composable
private fun EnrichingRow() {
    val bt = BtTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = bt.gold,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.bt_search_enriching),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
    }
}

@Composable
private fun SearchResultRow(
    asset: MarketAsset,
    inWatchlist: Boolean,
    watchEnabled: Boolean,
    onOpen: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onBuy: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = asset.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = bt.textPrimary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    BtBadge(text = assetTypeLabel(asset.type), kind = BtBadgeKind.Neutral)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(asset.name, asset.exchange).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WatchlistStar(
                inWatchlist = inWatchlist,
                enabled = watchEnabled,
                onToggle = onToggleWatchlist,
            )
            QuickBuyButton(onClick = onBuy, enabled = watchEnabled)
        }
    }
}

@Composable
private fun QuickBuyButton(onClick: () -> Unit, enabled: Boolean) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = BtShapes.pill,
        color = if (enabled) bt.gold.copy(alpha = 0.14f) else bt.surface,
        contentColor = if (enabled) bt.goldEmphasis else bt.textMuted,
        border = BorderStroke(1.dp, if (enabled) bt.gold.copy(alpha = 0.45f) else bt.border),
        interactionSource = interaction,
        modifier = Modifier.btPressScale(interaction, pressedScale = 0.94f),
    ) {
        Text(
            text = stringResource(R.string.bt_action_buy),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BtTheme.colors.gold,
    unfocusedBorderColor = BtTheme.colors.border,
    focusedLeadingIconColor = BtTheme.colors.gold,
    focusedTextColor = BtTheme.colors.textPrimary,
    unfocusedTextColor = BtTheme.colors.textPrimary,
    cursorColor = BtTheme.colors.gold,
)
