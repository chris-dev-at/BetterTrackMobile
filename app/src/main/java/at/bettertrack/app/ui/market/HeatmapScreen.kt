package at.bettertrack.app.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.charts.viz.BtVizHeatmap
import at.bettertrack.app.ui.charts.viz.VizHeatCell
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import at.bettertrack.app.widget.btWidgetHeatTiles
import kotlinx.coroutines.flow.first

/**
 * The in-app heatmap — area for size, hue for today's move.
 *
 * ## Why this screen is not called "the market"
 *
 * Because it is not one, and the platform cannot make it one. There is no
 * market-universe endpoint: no screener, no gainers list, no index constituents
 * (33 route files, 418 paths, checked). Every honest universe this app can draw
 * comes from the account itself, so both scopes here are named for exactly what
 * they contain — **my positions** and **my watchlist** — and neither is ever
 * labelled "Markt". A heatmap captioned with a market's name while showing
 * fourteen of the user's own holdings would be the single most misleading screen
 * in the app.
 *
 * ## The two scopes are NOT the same picture
 *
 * They encode different things, and the caption under the map says which:
 *
 *  - **My positions** sizes each tile by `marketValueEur` — a server-computed
 *    figure — and colours it by the server's `dayChangePct`. Both channels are
 *    real, and the tile areas mean something.
 *  - **My watchlist** has no position size, because a watchlist row is not a
 *    holding. Its tiles are therefore **equal-weight**: area carries no
 *    information at all and the caption says so. Inventing a size — by market
 *    cap, by price, by alphabetical rank — would make the prettier picture and
 *    the dishonest one. Only the hue means anything here.
 *
 * ## What is reused rather than rebuilt
 *
 * The geometry, the colour ramp and the 3 % intensity anchor all come from
 * [BtVizHeatmap], the same composable the widget builder previews. The
 * holdings→tiles reduction (merge one asset across portfolios, value-weight its
 * change, fold the tail into one uncoloured `Andere` cell) comes from
 * [btWidgetHeatTiles]. Nothing about the heat scale is re-derived here — a
 * second copy of the anchor is exactly how the in-app map and the widget would
 * start disagreeing about what a flat day looks like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapScreen(
    onBack: () -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val db = AppGraph.database
    val market = AppGraph.marketRepository

    var scope by remember { mutableStateOf(HeatScope.POSITIONS) }
    var selected by remember { mutableStateOf<String?>(null) }

    // ── Positions ────────────────────────────────────────────────────────────
    var positionCells by remember { mutableStateOf<List<HeatEntry>?>(null) }
    LaunchedEffect(Unit) {
        val holdings = db.holdingDao().observeAll().first()
        // Capped, not unlimited. A full screen can label roughly a dozen tiles;
        // past that the tail becomes unlabelled slivers, and one honest `Andere`
        // cell that preserves the total says more than eight anonymous marks.
        positionCells = btWidgetHeatTiles(holdings, maxTiles = HEATMAP_MAX_TILES).map { tile ->
            HeatEntry(
                key = tile.assetId.ifEmpty { "bucket-${tile.symbol}" },
                label = tile.symbol,
                weight = tile.weight,
                changePct = tile.changePct,
                assetId = tile.assetId,
            )
        }
    }

    // ── Watchlist ────────────────────────────────────────────────────────────
    //
    // A watchlist row carries no value, so the only honest weight is the same
    // one for every tile. The CHANGE, though, is real and server-computed — one
    // batch quote call fetches all of them, which is the whole reason a
    // watchlist scope can exist here at all.
    val watchItems by market.watchlistItems.collectAsStateWithLifecycle(emptyList())
    var watchCells by remember { mutableStateOf<List<HeatEntry>?>(null) }
    // Keyed on the ID LIST rather than the rows: renaming a note or reordering
    // the board changes the entities but not the question, and re-firing a quote
    // call for that would spend the user's rate limit on nothing.
    val watchIds = remember(watchItems) { watchItems.map { it.assetId }.distinct() }
    LaunchedEffect(scope, watchIds) {
        if (scope != HeatScope.WATCHLIST) return@LaunchedEffect
        if (watchIds.isEmpty()) {
            watchCells = emptyList()
            return@LaunchedEffect
        }
        val ids = watchIds
        val quotes = (market.quotes(ids) as? BtResult.Ok)?.value
        watchCells = watchItems.distinctBy { it.assetId }.map { item ->
            HeatEntry(
                key = item.assetId,
                label = item.assetSymbol,
                // Equal weight, stated once here and reflected in the caption.
                weight = 1.0,
                changePct = quotes?.quotes?.get(item.assetId)?.dayChangePct,
                assetId = item.assetId,
            )
        }
    }

    val cells = when (scope) {
        HeatScope.POSITIONS -> positionCells
        HeatScope.WATCHLIST -> watchCells
    }

    val scrollBehavior = rememberBtCollapsingHeaderBehaviorSafe()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = bt.bg,
        topBar = {
            BtCollapsingHeader(
                title = stringResource(R.string.bt_viz_form_heatmap),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BtSegmented(
                options = HeatScope.entries,
                selected = scope,
                label = { option ->
                    stringResource(
                        when (option) {
                            HeatScope.POSITIONS -> R.string.bt_heatmap_scope_positions
                            HeatScope.WATCHLIST -> R.string.bt_heatmap_scope_watchlist
                        },
                    )
                },
                onSelect = { option ->
                    scope = option
                    selected = null
                },
                equalWidths = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when {
                cells == null -> BtSkeleton(
                    Modifier.fillMaxWidth().weight(1f),
                    shape = BtShapes.card,
                )

                cells.isEmpty() -> BtInlineEmpty(
                    text = stringResource(
                        when (scope) {
                            HeatScope.POSITIONS -> R.string.bt_heatmap_empty_positions
                            HeatScope.WATCHLIST -> R.string.bt_heatmap_empty_watchlist
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> {
                    BtVizHeatmap(
                        cells = cells.map {
                            VizHeatCell(
                                key = it.key,
                                label = it.label,
                                weight = it.weight,
                                changePct = it.changePct,
                            )
                        },
                        changeText = { pct -> formatPercent(pct, locale) },
                        emptyText = stringResource(R.string.bt_heatmap_empty_positions),
                        // Equal-weight tiles have no order worth squarifying for;
                        // the ordered mosaic keeps the watchlist's own sequence,
                        // which is the only ranking that scope actually has.
                        squarified = scope == HeatScope.POSITIONS,
                        selectedKey = selected,
                        onSelect = { key ->
                            selected = key
                            // A tap that lands on a real asset opens it; the
                            // folded `Andere` cell is several assets and opens
                            // nothing, so it only takes the keyline.
                            val id = cells.firstOrNull { it.key == key }?.assetId
                            if (!id.isNullOrEmpty()) onOpenAsset(id)
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )

                    Text(
                        text = stringResource(
                            when (scope) {
                                HeatScope.POSITIONS -> R.string.bt_heatmap_legend_positions
                                HeatScope.WATCHLIST -> R.string.bt_heatmap_legend_watchlist
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * How many positions get their own tile before the tail folds into `Andere`.
 *
 * A full screen is bigger than the study's 380x240dp card (treemap cap 10), but
 * not unboundedly so: below roughly 40x22dp a tile cannot carry its own name,
 * and an unlabelled mark is not information.
 */
private const val HEATMAP_MAX_TILES = 12

/** The two universes this app can name honestly. Neither of them is "the market". */
private enum class HeatScope { POSITIONS, WATCHLIST }

/** One tile before it becomes a [VizHeatCell]: the cell plus where it navigates. */
private data class HeatEntry(
    val key: String,
    val label: String,
    val weight: Double,
    val changePct: Double?,
    val assetId: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun rememberBtCollapsingHeaderBehaviorSafe() =
    at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior()
