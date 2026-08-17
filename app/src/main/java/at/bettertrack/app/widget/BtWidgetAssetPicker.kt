package at.bettertrack.app.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.resolve
import at.bettertrack.app.ui.customassets.dialogFieldColors
import at.bettertrack.app.ui.market.SearchUiState
import at.bettertrack.app.ui.market.assetTypeLabel
import at.bettertrack.app.ui.market.searchWithEnrichPolling
import at.bettertrack.app.ui.theme.BtTheme
import kotlinx.coroutines.delay

/**
 * The asset picker both widget hosts use: **any** asset, not just the ones the
 * user already owns or watches.
 *
 * ## What changed, and why the old rule had to go
 *
 * The picker used to offer held ∪ watched only, and said so proudly: *"a config
 * screen that needs a network round trip to render a list the app already has
 * would be config for config's sake."* That reasoning was sound for its own
 * premise and wrong about the requirement. The owner's ask is explicit — it must
 * be possible to put a stock on the home screen that is **neither held nor
 * watched** — and no amount of Room can answer a question about an asset the
 * device has never heard of.
 *
 * So the local list stays as the instant, offline, zero-latency default (it is
 * still the right answer for the common case), and typing turns the same list
 * into a real search over the app's own `/search`.
 *
 * ## Honest degradation
 *
 * Search needs a session and a connection (`market:read`, and the platform
 * returns 401 unauthenticated). Offline, the field stays visible but the list
 * falls back to held ∪ watched with the app's own
 * `bt_search_requires_connection_message` under it — the same sentence the
 * Markets tab uses, so the constraint reads as the app's, not as this screen's
 * private failure.
 *
 * ## Why it reuses [searchWithEnrichPolling]
 *
 * That is the app's real search behaviour, including the enrichment re-poll
 * that makes an uncached ticker appear a few seconds later without re-typing. A
 * hand-rolled one-shot call here would have looked identical and quietly
 * behaved worse for exactly the assets this feature exists to reach.
 */
@Composable
fun BtWidgetAssetPicker(
    /** Held ∪ watched — the instant default, and the offline fallback. */
    localChoices: List<BtWidgetAssetConfig>,
    onPick: (BtWidgetAssetConfig) -> Unit,
) {
    val bt = BtTheme.colors
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchUiState>(SearchUiState.Idle) }
    val online by AppGraph.connectivityMonitor.isOnline.collectAsState()

    // The app's own 260 ms debounce, and its own collectLatest semantics: a new
    // keystroke cancels the in-flight request AND the enrichment poll, because
    // LaunchedEffect restarts on the key.
    LaunchedEffect(query, online) {
        val q = query.trim()
        when {
            q.isEmpty() -> state = SearchUiState.Idle
            !online -> state = SearchUiState.OfflineState
            else -> {
                delay(BT_WIDGET_SEARCH_DEBOUNCE_MS)
                searchWithEnrichPolling(
                    query = q,
                    search = { AppGraph.marketRepository.search(it) },
                ) { state = it }
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        label = { Text(stringResource(R.string.bt_txform_asset_search_hint)) },
        colors = dialogFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )

    when (val s = state) {
        // Nothing typed: the local list, which is what most placements want and
        // costs no request at all.
        SearchUiState.Idle -> LocalList(localChoices, onPick)

        SearchUiState.Loading ->
            Hint(stringResource(R.string.bt_search_enriching))

        SearchUiState.Empty ->
            Hint(stringResource(R.string.bt_search_no_results_title))

        SearchUiState.OfflineState -> {
            Hint(stringResource(R.string.bt_search_requires_connection_message))
            LocalList(localChoices, onPick)
        }

        is SearchUiState.Error -> Hint(s.message.resolve())

        is SearchUiState.Results -> {
            if (s.enriching) Hint(stringResource(R.string.bt_search_enriching))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                s.assets.forEach { asset ->
                    AssetRow(
                        symbol = asset.symbol,
                        // The type badge is what separates "AAPL the stock"
                        // from "AAPL the CFD" in a provider-fed result list.
                        subtitle = listOfNotNull(
                            asset.name,
                            asset.exchange,
                            assetTypeLabel(asset.type),
                        ).joinToString(" · "),
                    ) {
                        onPick(
                            BtWidgetAssetConfig(
                                assetId = asset.id,
                                symbol = asset.symbol,
                                name = asset.name,
                                currency = asset.currency,
                                exchange = asset.exchange.orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalList(choices: List<BtWidgetAssetConfig>, onPick: (BtWidgetAssetConfig) -> Unit) {
    if (choices.isEmpty()) {
        Hint(stringResource(R.string.bt_widgets_no_assets))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        choices.forEach { choice ->
            AssetRow(choice.symbol, choice.name) { onPick(choice) }
        }
    }
}

@Composable
private fun AssetRow(symbol: String, subtitle: String, onClick: () -> Unit) {
    val bt = BtTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.bodyLarge,
                color = bt.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
    Spacer(Modifier.height(2.dp))
}

/**
 * The app's search debounce, restated here rather than imported: the constant
 * lives as a magic `260` inside five different ViewModels, and reaching into a
 * screen's private literal would be worse than naming it once.
 */
const val BT_WIDGET_SEARCH_DEBOUNCE_MS: Long = 260L
