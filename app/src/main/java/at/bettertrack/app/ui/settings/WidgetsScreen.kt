package at.bettertrack.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.bettertrack.app.R
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.components.BtPickerRow
import at.bettertrack.app.ui.components.BtPickerSheet
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.charts.viz.BtVizCanvas
import at.bettertrack.app.ui.charts.viz.BtVizChart
import at.bettertrack.app.ui.charts.viz.BtVizForm
import at.bettertrack.app.ui.charts.viz.BtVizFormat
import at.bettertrack.app.ui.charts.viz.BtVizHeatmap
import at.bettertrack.app.ui.charts.viz.VizDatum
import at.bettertrack.app.ui.charts.viz.VizHeatCell
import at.bettertrack.app.ui.charts.viz.VizRole
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import at.bettertrack.app.widget.BT_QUICK_LINKS_DEFAULT
import at.bettertrack.app.widget.BT_WIDGET_ROWS_MOVERS_DEFAULTS
import at.bettertrack.app.widget.BT_WIDGET_ROWS_WATCHLIST_DEFAULTS
import at.bettertrack.app.widget.BtAllocationWidgetReceiver
import at.bettertrack.app.widget.BtAssetWidgetReceiver
import at.bettertrack.app.widget.BtQuickLinksConfig
import at.bettertrack.app.widget.BtQuickLinksEditor
import at.bettertrack.app.widget.BtQuickLinksPreview
import at.bettertrack.app.widget.BtWidgetAssetPicker
import at.bettertrack.app.widget.BtWidgetCashConfig
import at.bettertrack.app.widget.BtBudgetWidgetReceiver
import at.bettertrack.app.widget.BtMoversWidgetReceiver
import at.bettertrack.app.widget.BtNetWorthWidgetReceiver
import at.bettertrack.app.widget.BtPortfolioWidgetReceiver
import at.bettertrack.app.widget.BtSpendingWidgetReceiver
import at.bettertrack.app.widget.BtWatchlistWidgetReceiver
import at.bettertrack.app.widget.BtWidgetAllocationCenter
import at.bettertrack.app.widget.BtWidgetAllocationConfig
import at.bettertrack.app.widget.BtWidgetAllocationForm
import at.bettertrack.app.widget.BtWidgetAllocationGroup
import at.bettertrack.app.widget.BtWidgetAssetConfig
import at.bettertrack.app.widget.BtWidgetBudget
import at.bettertrack.app.widget.BtWidgetBudgetConfig
import at.bettertrack.app.widget.BtWidgetBudgetEmphasis
import at.bettertrack.app.widget.BtWidgetBudgetStore
import at.bettertrack.app.widget.BtWidgetBudgetStyle
import at.bettertrack.app.widget.BtWidgetDeltaStyle
import at.bettertrack.app.widget.BtWidgetFlowMode
import at.bettertrack.app.widget.BtWidgetPinKind
import at.bettertrack.app.widget.BtWidgetPortfolioConfig
import at.bettertrack.app.widget.BtWidgetPulseConfig
import at.bettertrack.app.widget.BtWidgetRepository
import at.bettertrack.app.widget.BtWidgetRowDirection
import at.bettertrack.app.widget.BtWidgetRowSort
import at.bettertrack.app.widget.BtWidgetRowSource
import at.bettertrack.app.widget.BtWidgetRowsConfig
import at.bettertrack.app.widget.btWidgetAllCashSources
import at.bettertrack.app.widget.btWidgetAllocFormLabel
import at.bettertrack.app.widget.btWidgetAllocGroupLabel
import at.bettertrack.app.widget.btWidgetAssetChoices
import at.bettertrack.app.widget.btWidgetBudgetFraction
import at.bettertrack.app.widget.btWidgetBudgetPercent
import at.bettertrack.app.widget.btWidgetDeltaStyleLabel
import at.bettertrack.app.widget.btWidgetEmphasisLabel
import at.bettertrack.app.widget.btWidgetFlowModeLabel
import at.bettertrack.app.widget.btWidgetPinPayload
import at.bettertrack.app.widget.btWidgetPortfolioChoices
import at.bettertrack.app.widget.btWidgetRequestPin
import at.bettertrack.app.widget.btWidgetStashPin
import at.bettertrack.app.widget.btWidgetStyleLabel
import at.bettertrack.app.widget.btWidgetWarmCashSources
import kotlinx.coroutines.flow.first

/**
 * Settings → Widgets: the in-app widget BUILDER — round 2 exposes the full
 * per-family configuration matrix (the Codex study's config table, minus the
 * knobs the cached data cannot honestly serve) with the mock updating live as
 * knobs change. One card per launcher entry: sample-data preview in the
 * study's visual language, the knobs, "Add to home screen".
 *
 * Adding stashes the built configuration ([btWidgetStashPin]) and asks the
 * launcher to pin ([btWidgetRequestPin]); the widget's first draw claims the
 * stash and lands pre-configured. Launchers without pin support get the honest
 * hint. Mocks show SAMPLE figures (the same ones the picker previews use);
 * only the user's own budget/asset/portfolio NAMES appear, because naming the
 * choice is what makes it legible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetsScreen(onBack: () -> Unit) {
    val bt = BtTheme.colors
    val context = LocalContext.current
    val snackbar = LocalBtSnackbar.current

    var assetChoices by remember { mutableStateOf<List<BtWidgetAssetConfig>>(emptyList()) }
    var portfolioChoices by remember {
        mutableStateOf<List<at.bettertrack.app.data.db.PortfolioEntity>>(emptyList())
    }
    var budgetChoices by remember { mutableStateOf<List<BtWidgetBudget>>(emptyList()) }

    // Pulse knobs.
    var pulseScope by remember {
        mutableStateOf<at.bettertrack.app.data.db.PortfolioEntity?>(null)
    }
    var pulseStyle by remember { mutableStateOf(BtWidgetDeltaStyle.BOTH) }
    var pulseSpark by remember { mutableStateOf(true) }

    // Performance knobs.
    var perfPortfolio by remember {
        mutableStateOf<at.bettertrack.app.data.db.PortfolioEntity?>(null)
    }
    var perfRange by remember { mutableStateOf(at.bettertrack.app.data.repo.HistoryRange.M1) }

    // Asset knobs.
    var selectedAsset by remember { mutableStateOf<BtWidgetAssetConfig?>(null) }
    var assetSpark by remember { mutableStateOf(true) }

    // Row-family knobs, per preset.
    var watchlistCfg by remember { mutableStateOf(BT_WIDGET_ROWS_WATCHLIST_DEFAULTS) }
    var moversCfg by remember { mutableStateOf(BT_WIDGET_ROWS_MOVERS_DEFAULTS) }

    // Budget knobs.
    var selectedBudget by remember { mutableStateOf<BtWidgetBudget?>(null) }
    var budgetStyle by remember { mutableStateOf(BtWidgetBudgetStyle.RING) }
    var budgetEmphasis by remember { mutableStateOf(BtWidgetBudgetEmphasis.REMAINING) }

    // Allocation knobs.
    var allocGroup by remember { mutableStateOf(BtWidgetAllocationGroup.CLASS) }
    var allocCash by remember { mutableStateOf(true) }
    var allocCenter by remember { mutableStateOf(BtWidgetAllocationCenter.TOTAL) }
    var allocForm by remember { mutableStateOf(BtWidgetAllocationForm.DONUT) }

    // Flow knobs.
    var flowMode by remember { mutableStateOf(BtWidgetFlowMode.DONUT) }

    // Quick Links (round 3): the ordered tile set, edited live.
    var linksCfg by remember { mutableStateOf(BtQuickLinksConfig(BT_QUICK_LINKS_DEFAULT)) }

    // Cash Wallet (round 3): which wallet, and the 4x2 movements list.
    var cashChoices by remember {
        mutableStateOf<List<at.bettertrack.app.data.db.CashSourceEntity>>(emptyList())
    }
    var selectedCash by remember {
        mutableStateOf<at.bettertrack.app.data.db.CashSourceEntity?>(null)
    }
    var cashMovements by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val db = AppGraph.database
            val holdings = db.holdingDao().observeAll().first()
            val items = db.watchlistDao().observeAll().first()
                .flatMap { board -> db.watchlistDao().observeItems(board.id).first() }
            assetChoices = btWidgetAssetChoices(holdings, items)
            selectedAsset = assetChoices.firstOrNull()
            portfolioChoices = btWidgetPortfolioChoices(db.portfolioDao().getAll())
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Leaving the screen cancels this scope, and a cancellation is not
            // a load failure — swallowing it logged "Choice lists failed to
            // load" every time the user simply pressed back (device QA
            // 2026-08-17), which is the house rule these blocks already state
            // elsewhere: never catch Exception without letting cancellation by.
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WidgetsScreen", "Choice lists failed to load.", e)
        }
        // Both of the lists below need a network top-up, and BOTH read their
        // cache first. Awaiting the fetch ahead of the read left the widget's
        // own config Activity a black void for a full 20 s network timeout on
        // 2026-08-17; this screen would have shown the same empty knobs. A slow
        // network may only ever ADD rows to a list already on screen.
        try {
            budgetChoices = BtWidgetBudgetStore.read(AppGraph.database, AppGraph.json).budgets
            selectedBudget = budgetChoices.firstOrNull()
            BtWidgetRepository.warmBudgetsForPicker()
            BtWidgetBudgetStore.read(AppGraph.database, AppGraph.json).budgets
                .takeIf { it.isNotEmpty() }
                ?.let {
                    budgetChoices = it
                    if (selectedBudget == null) selectedBudget = it.firstOrNull()
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WidgetsScreen", "Budget list failed to load.", e)
        }
        try {
            // Cash sources reach Room only when something fetches them, so the
            // wallet picker tops up the same way the budget picker does.
            //
            // EVERY portfolio's wallets, not just the governing one's (owner
            // 2026-08-18): a Quick-Links tile aimed at "main cash in my savings
            // portfolio" cannot be built from a list that only ever contained
            // the selected portfolio's sources.
            cashChoices = btWidgetAllCashSources(portfolioChoices)
            selectedCash = cashChoices.firstOrNull { it.isMain } ?: cashChoices.firstOrNull()
            btWidgetWarmCashSources(portfolioChoices)
            btWidgetAllCashSources(portfolioChoices).takeIf { it.isNotEmpty() }?.let {
                cashChoices = it
                if (selectedCash == null) {
                    selectedCash = it.firstOrNull { s -> s.isMain } ?: it.firstOrNull()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WidgetsScreen", "Cash source list failed to load.", e)
        }
    }

    fun pin(receiver: Class<*>, stash: (() -> Unit)?) {
        stash?.invoke()
        if (btWidgetRequestPin(context, receiver)) {
            snackbar.show(R.string.bt_widgets_pin_requested)
        } else {
            snackbar.show(R.string.bt_widgets_pin_unsupported)
        }
    }

    Scaffold(
        containerColor = bt.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bt_dest_widgets),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.bt_action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bt.bg,
                    titleContentColor = bt.textPrimary,
                    navigationIconContentColor = bt.textSecondary,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.bt_widgets_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = bt.textSecondary,
                )
            }

            // ── Portfolio pulse ──────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_net_worth_title),
                    description = stringResource(R.string.bt_widget_net_worth_description),
                    preview = {
                        PulseMock(
                            subject = pulseScope?.name
                                ?: stringResource(R.string.bt_widget_pulse_all),
                            style = pulseStyle,
                        )
                    },
                    config = {
                        SelectorRow(
                            label = stringResource(R.string.bt_widgets_pick_portfolio_label),
                            value = pulseScope?.name
                                ?: stringResource(R.string.bt_widget_pulse_all),
                            selected = pulseScope,
                            options = listOf(null) + portfolioChoices,
                            optionLabel = {
                                it?.name ?: stringResource(R.string.bt_widget_pulse_all)
                            },
                            onSelect = { pulseScope = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_delta_style),
                            options = BtWidgetDeltaStyle.entries.toList(),
                            selected = pulseStyle,
                            optionLabel = { stringResource(btWidgetDeltaStyleLabel(it)) },
                            onSelect = { pulseStyle = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_sparkline),
                            options = listOf(true, false),
                            selected = pulseSpark,
                            optionLabel = {
                                stringResource(
                                    if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                                )
                            },
                            onSelect = { pulseSpark = it },
                        )
                    },
                    onAdd = {
                        pin(BtNetWorthWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.PULSE,
                                btWidgetPinPayload(
                                    BtWidgetPulseConfig(
                                        portfolioId = pulseScope?.id,
                                        portfolioName = pulseScope?.name.orEmpty(),
                                        style = pulseStyle,
                                        sparkline = pulseSpark,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }

            // ── Portfolio performance ────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_portfolio_pick_title),
                    description = stringResource(R.string.bt_widget_portfolio_pick_description),
                    preview = { PerformanceMock(perfRange) },
                    config = {
                        SelectorRow(
                            label = stringResource(R.string.bt_widgets_pick_portfolio_label),
                            value = perfPortfolio?.name
                                ?: stringResource(R.string.bt_widget_config_follow),
                            selected = perfPortfolio,
                            options = listOf(null) + portfolioChoices,
                            optionLabel = {
                                it?.name ?: stringResource(R.string.bt_widget_config_follow)
                            },
                            onSelect = { perfPortfolio = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_range),
                            options = at.bettertrack.app.widget.BT_WIDGET_PERF_RANGES,
                            selected = perfRange,
                            optionLabel = {
                                stringResource(at.bettertrack.app.widget.btWidgetRangeLabelRes(it))
                            },
                            onSelect = { perfRange = it },
                        )
                    },
                    onAdd = {
                        pin(BtPortfolioWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.PORTFOLIO,
                                btWidgetPinPayload(
                                    perfPortfolio?.let {
                                        BtWidgetPortfolioConfig(it.id, it.name)
                                    },
                                    perfRange,
                                ),
                            )
                        }
                    },
                )
            }

            // ── Asset focus ──────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_asset_title),
                    description = stringResource(R.string.bt_widget_asset_description),
                    preview = { AssetMock(selectedAsset) },
                    config = {
                        // One row, one sheet — and the sheet now SEARCHES.
                        // It used to be a fixed list of held ∪ watched, which
                        // could not answer the owner's ask (put a stock on the
                        // home screen that is neither). The local list is still
                        // what the sheet opens on, so the common pick costs no
                        // round trip; typing reaches everything else.
                        AssetSearchRow(
                            localChoices = assetChoices,
                            selected = selectedAsset,
                            onPick = { selectedAsset = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_sparkline),
                            options = listOf(true, false),
                            selected = assetSpark,
                            optionLabel = {
                                stringResource(
                                    if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                                )
                            },
                            onSelect = { assetSpark = it },
                        )
                    },
                    addEnabled = selectedAsset != null,
                    onAdd = {
                        pin(BtAssetWidgetReceiver::class.java) {
                            selectedAsset?.let { asset ->
                                btWidgetStashPin(
                                    context,
                                    BtWidgetPinKind.ASSET,
                                    btWidgetPinPayload(asset.copy(sparkline = assetSpark)),
                                )
                            }
                        }
                    },
                )
            }

            // ── Watchlist preset ─────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_watchlist_title),
                    description = stringResource(R.string.bt_widget_watchlist_description),
                    preview = { RowsMock(watchlistCfg) },
                    config = {
                        RowsKnobs(watchlistCfg) { watchlistCfg = it }
                    },
                    onAdd = {
                        pin(BtWatchlistWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.WATCHLIST,
                                btWidgetPinPayload(watchlistCfg),
                            )
                        }
                    },
                )
            }

            // ── Movers preset ────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_movers_title),
                    description = stringResource(R.string.bt_widget_movers_description),
                    preview = { RowsMock(moversCfg) },
                    config = {
                        RowsKnobs(moversCfg) { moversCfg = it }
                    },
                    onAdd = {
                        pin(BtMoversWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.MOVERS,
                                btWidgetPinPayload(moversCfg),
                            )
                        }
                    },
                )
            }

            // ── Budget meter — the owner's flagship flow ─────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_budget_title),
                    description = stringResource(R.string.bt_widget_budget_description),
                    preview = { BudgetMock(selectedBudget, budgetStyle, budgetEmphasis) },
                    config = {
                        if (budgetChoices.isEmpty()) {
                            EmptyHint(stringResource(R.string.bt_widgets_no_budgets))
                        } else {
                            SelectorRow(
                                label = stringResource(R.string.bt_widgets_pick_budget_label),
                                value = selectedBudget?.tagName
                                    ?: stringResource(R.string.bt_widget_config_all_budgets),
                                selected = selectedBudget,
                                options = listOf<BtWidgetBudget?>(null) + budgetChoices,
                                optionLabel = {
                                    it?.tagName
                                        ?: stringResource(R.string.bt_widget_config_all_budgets)
                                },
                                onSelect = { selectedBudget = it },
                            )
                            if (selectedBudget != null) {
                                ChipRow(
                                    label = stringResource(R.string.bt_widgets_pick_style_label),
                                    options = BtWidgetBudgetStyle.entries.toList(),
                                    selected = budgetStyle,
                                    optionLabel = { stringResource(btWidgetStyleLabel(it)) },
                                    onSelect = { budgetStyle = it },
                                )
                                ChipRow(
                                    label = stringResource(R.string.bt_widget_config_emphasis),
                                    options = BtWidgetBudgetEmphasis.entries.toList(),
                                    selected = budgetEmphasis,
                                    optionLabel = { stringResource(btWidgetEmphasisLabel(it)) },
                                    onSelect = { budgetEmphasis = it },
                                )
                            }
                        }
                    },
                    onAdd = {
                        pin(BtBudgetWidgetReceiver::class.java) {
                            selectedBudget?.let { budget ->
                                btWidgetStashPin(
                                    context,
                                    BtWidgetPinKind.BUDGET,
                                    btWidgetPinPayload(
                                        BtWidgetBudgetConfig(
                                            budgetId = budget.id,
                                            tagName = budget.tagName,
                                            style = budgetStyle,
                                            emphasis = budgetEmphasis,
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            // ── Allocation ───────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_allocation_title),
                    description = stringResource(R.string.bt_widget_allocation_description),
                    preview = { AllocationMock(allocCenter, allocForm) },
                    config = {
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_group_by),
                            options = BtWidgetAllocationGroup.entries.toList(),
                            selected = allocGroup,
                            optionLabel = { stringResource(btWidgetAllocGroupLabel(it)) },
                            onSelect = { allocGroup = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_viz_title),
                            options = BtWidgetAllocationForm.entries.toList(),
                            selected = allocForm,
                            optionLabel = { stringResource(btWidgetAllocFormLabel(it)) },
                            onSelect = { allocForm = it },
                        )
                        ChipRow(
                            label = stringResource(R.string.bt_widget_allocation_cash),
                            options = listOf(true, false),
                            selected = allocCash,
                            optionLabel = {
                                stringResource(
                                    if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                                )
                            },
                            onSelect = { allocCash = it },
                        )
                        // The centre figure belongs to the ring's hole; the
                        // other forms have no hole to fill.
                        if (allocForm == BtWidgetAllocationForm.DONUT) {
                            ChipRow(
                                label = stringResource(R.string.bt_widget_config_center),
                                options = BtWidgetAllocationCenter.entries.toList(),
                                selected = allocCenter,
                                optionLabel = {
                                    stringResource(
                                        when (it) {
                                            BtWidgetAllocationCenter.TOTAL ->
                                                R.string.bt_widget_config_center_total
                                            BtWidgetAllocationCenter.TOP ->
                                                R.string.bt_widget_config_center_top
                                        },
                                    )
                                },
                                onSelect = { allocCenter = it },
                            )
                        }
                    },
                    onAdd = {
                        pin(BtAllocationWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.ALLOCATION,
                                btWidgetPinPayload(
                                    BtWidgetAllocationConfig(
                                        allocGroup,
                                        allocCash,
                                        allocCenter,
                                        allocForm,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }

            // ── Monthly flow ─────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_spending_title),
                    description = stringResource(R.string.bt_widget_spending_description),
                    preview = { FlowMock(flowMode) },
                    config = {
                        ChipRow(
                            label = stringResource(R.string.bt_widget_config_flow_mode),
                            options = BtWidgetFlowMode.entries.toList(),
                            selected = flowMode,
                            optionLabel = { stringResource(btWidgetFlowModeLabel(it)) },
                            onSelect = { flowMode = it },
                        )
                    },
                    onAdd = {
                        pin(BtSpendingWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.FLOW,
                                btWidgetPinPayload(flowMode),
                            )
                        }
                    },
                )
            }

            // ── Quick links ──────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_actions_title),
                    description = stringResource(R.string.bt_widget_actions_description),
                    // The preview IS the editor's preview — one component, so
                    // the builder and the config Activity cannot drift about
                    // what the grid looks like.
                    preview = { BtQuickLinksPreview(linksCfg) },
                    config = {
                        ChipRow(
                            label = stringResource(R.string.bt_ql_config_captions),
                            options = listOf(false, true),
                            selected = linksCfg.captions,
                            optionLabel = {
                                stringResource(
                                    if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                                )
                            },
                            onSelect = { linksCfg = linksCfg.copy(captions = it) },
                        )
                        BtQuickLinksEditor(
                            config = linksCfg,
                            portfolios = portfolioChoices,
                            sources = cashChoices,
                        ) { linksCfg = it }
                    },
                    onAdd = {
                        pin(at.bettertrack.app.widget.BtQuickActionsWidgetReceiver::class.java) {
                            btWidgetStashPin(
                                context,
                                BtWidgetPinKind.LINKS,
                                btWidgetPinPayload(linksCfg),
                            )
                        }
                    },
                )
            }

            // ── Cash wallet ──────────────────────────────────────────────────
            item {
                WidgetCard(
                    title = stringResource(R.string.bt_widget_cash_title),
                    description = stringResource(R.string.bt_widget_cash_description),
                    preview = { CashWalletMock(selectedCash?.name) },
                    config = {
                        if (cashChoices.isEmpty()) {
                            EmptyHint(stringResource(R.string.bt_widgets_no_cash))
                        } else {
                            SelectorRow(
                                label = stringResource(R.string.bt_widgets_pick_cash_label),
                                value = selectedCash?.name.orEmpty(),
                                options = cashChoices,
                                selected = selectedCash,
                                optionLabel = { it.name },
                                onSelect = { selectedCash = it },
                            )
                        }
                        ChipRow(
                            label = stringResource(R.string.bt_widget_cash_config_movements),
                            options = listOf(true, false),
                            selected = cashMovements,
                            optionLabel = {
                                stringResource(
                                    if (it) R.string.bt_widget_config_on else R.string.bt_widget_config_off,
                                )
                            },
                            onSelect = { cashMovements = it },
                        )
                    },
                    addEnabled = selectedCash != null,
                    onAdd = {
                        pin(at.bettertrack.app.widget.BtCashWalletWidgetReceiver::class.java) {
                            selectedCash?.let { source ->
                                btWidgetStashPin(
                                    context,
                                    BtWidgetPinKind.CASH,
                                    btWidgetPinPayload(
                                        BtWidgetCashConfig(
                                            sourceId = source.id,
                                            sourceName = source.name,
                                            portfolioId = source.portfolioId,
                                            movements = cashMovements,
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            item {
                Text(
                    stringResource(R.string.bt_widgets_resize_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
            }
        }
    }
}

// ── The card scaffold ────────────────────────────────────────────────────────

@Composable
private fun WidgetCard(
    title: String,
    description: String,
    preview: @Composable () -> Unit,
    config: (@Composable () -> Unit)? = null,
    addEnabled: Boolean = true,
    onAdd: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        color = bt.surface,
        border = BorderStroke(1.dp, bt.border),
        shape = BtShapes.card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.bt_widgets_sample_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textSecondary,
            )
            Spacer(Modifier.height(10.dp))

            // The mock sits on the page colour so it reads as "a widget on a
            // launcher", not as more card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bt.bgAlt, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                preview()
            }

            if (config != null) {
                Spacer(Modifier.height(10.dp))
                config()
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = addEnabled, onClick = onAdd)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.AddCircleOutline,
                    contentDescription = null,
                    tint = if (addEnabled) bt.goldInk else bt.textMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.bt_widgets_add),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (addEnabled) bt.goldInk else bt.textMuted,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = BtTheme.colors.textMuted,
    )
}

/**
 * A labelled value row that opens a [BtPickerSheet] of [options].
 *
 * ## Why this is a sheet (owner order 2026-08-16)
 *
 * *"Every remaining anchored 3-dot dropdown / context menu becomes a bottom
 * sheet."* This row was the app's LAST anchored popup — an `M3 DropdownMenu`
 * that opened as a floating square over the config block, in a screen whose
 * every other transient surface slides up from the bottom. It is a single-choice
 * value picker with no destructive verb, so its home is [BtPickerSheet] (the
 * family already behind 14 other choices) rather than `BtActionSheet`, which is
 * for verbs.
 *
 * The sheet also repairs two real defects the menu had. An anchored menu is
 * capped by the window it is anchored in, so a user with a dozen portfolios got
 * a cramped scrolling square pinned to a row halfway down a scrolling list; the
 * sheet gets the picker family's height cap and its own scroll. And the menu
 * marked NOTHING as current — the collapsed row said which value was chosen and
 * then the open list gave no tick, so the reader had to remember. [BtPickerRow]
 * carries the wash + hairline + tick, which is the whole reason that component
 * exists.
 *
 * @param value the CURRENT value as the collapsed row prints it. Kept separate
 *   from `optionLabel(selected)` on purpose: the asset picker's row shows the
 *   bare symbol while its options show `SYMBOL — Name`, and collapsing the two
 *   would either bloat the row or starve the list.
 * @param selected the chosen option itself, so the tick is decided by IDENTITY
 *   rather than by matching rendered labels. Two portfolios may legitimately
 *   share a name, and a label match would tick both of them. Declared `T?`
 *   rather than `T` so a picker whose options are non-null (the asset list) can
 *   still say "nothing chosen yet" without widening its own element type.
 */
@Composable
private fun <T> SelectorRow(
    label: String,
    value: String,
    selected: T?,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val bt = BtTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        BtPickerSheet(
            // The row's own label is the sheet's title: it is already the noun
            // the user tapped ("Portfolio", "Asset", "Budget"), so the sheet
            // names itself out of the vocabulary that opened it.
            title = label,
            onDismiss = { open = false },
        ) {
            options.forEach { option ->
                BtPickerRow(
                    label = optionLabel(option),
                    selected = option == selected,
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

/** One labelled row of exclusive chips; scrolls when the options overflow. */
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    val bt = BtTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            modifier = Modifier.width(96.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel(option)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bt.goldSurface,
                        selectedLabelColor = bt.goldInk,
                    ),
                )
            }
        }
    }
}

/** The row family's three knob rows, shared by both preset cards. */
@Composable
private fun RowsKnobs(config: BtWidgetRowsConfig, onChange: (BtWidgetRowsConfig) -> Unit) {
    ChipRow(
        label = stringResource(R.string.bt_widget_config_source),
        options = BtWidgetRowSource.entries.toList(),
        selected = config.source,
        optionLabel = {
            stringResource(
                when (it) {
                    BtWidgetRowSource.WATCHLIST -> R.string.bt_widget_watchlist_title
                    BtWidgetRowSource.HOLDINGS -> R.string.bt_widget_config_source_holdings
                },
            )
        },
        onSelect = { onChange(config.copy(source = it)) },
    )
    ChipRow(
        label = stringResource(R.string.bt_widget_config_sort),
        options = BtWidgetRowSort.entries.toList(),
        selected = config.sort,
        optionLabel = {
            stringResource(
                when (it) {
                    BtWidgetRowSort.MOVEMENT -> R.string.bt_widget_config_sort_movement
                    BtWidgetRowSort.VALUE -> R.string.bt_widget_config_sort_value
                    BtWidgetRowSort.MANUAL -> R.string.bt_widget_config_sort_manual
                },
            )
        },
        onSelect = { onChange(config.copy(sort = it)) },
    )
    ChipRow(
        label = stringResource(R.string.bt_widget_config_direction),
        options = BtWidgetRowDirection.entries.toList(),
        selected = config.direction,
        optionLabel = {
            stringResource(
                when (it) {
                    BtWidgetRowDirection.ALL -> R.string.bt_widget_config_dir_all
                    BtWidgetRowDirection.WINNERS -> R.string.bt_widget_config_dir_winners
                    BtWidgetRowDirection.LOSERS -> R.string.bt_widget_config_dir_losers
                    BtWidgetRowDirection.SPLIT -> R.string.bt_widget_config_dir_split
                },
            )
        },
        onSelect = { onChange(config.copy(direction = it)) },
    )
}

// ── The mocks (sample data, the study's language) ────────────────────────────

/** The gold-dot subject row every mock opens with. */
@Composable
private fun MockSubjectRow(subject: String, trailing: String? = null) {
    val bt = BtTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(bt.goldInk, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            subject,
            color = bt.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = bt.textMuted, fontSize = 9.sp)
        }
    }
}

/** The tinted delta pill mock. */
@Composable
private fun MockPill(text: String, up: Boolean = true) {
    val bt = BtTheme.colors
    Box(
        Modifier
            .background(if (up) bt.gainWash else bt.lossWash, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            color = if (up) bt.gain else bt.loss,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PulseMock(subject: String, style: BtWidgetDeltaStyle) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        MockSubjectRow(subject, stringResource(R.string.bt_widget_preview_time))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.bt_widget_preview_networth_value),
            color = bt.textPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        MockPill(
            when (style) {
                BtWidgetDeltaStyle.BOTH -> stringResource(R.string.bt_widget_preview_networth_day)
                BtWidgetDeltaStyle.ABSOLUTE -> stringResource(R.string.bt_widget_preview_networth_day_abs)
                BtWidgetDeltaStyle.PERCENT -> stringResource(R.string.bt_widget_preview_networth_day_pct)
            },
        )
    }
}

@Composable
private fun PerformanceMock(range: at.bettertrack.app.data.repo.HistoryRange) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        // The CONFIGURED span as the corner chip — exactly what the real widget
        // renders (owner ruling: no range switcher inside a widget, so the mock
        // must not advertise one; the knob below is the switcher).
        MockSubjectRow(
            stringResource(R.string.bt_widget_preview_portfolio_name),
            stringResource(at.bettertrack.app.widget.btWidgetRangeLabelRes(range)),
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.bt_widget_preview_portfolio_value),
                color = bt.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            MockPill(stringResource(R.string.bt_widget_preview_portfolio_day))
        }
        Spacer(Modifier.height(6.dp))
        MockCurve()
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.bt_widget_preview_low_high),
            color = bt.textMuted,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun MockCurve() {
    val bt = BtTheme.colors
    val line = bt.goldInk
    val washFill = bt.goldWash
    val ring = bt.bgAlt
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val pts = listOf(0.83f, 0.69f, 0.78f, 0.53f, 0.61f, 0.33f, 0.44f, 0.22f, 0.28f)
        val stepX = size.width / (pts.size - 1)
        val path = Path()
        pts.forEachIndexed { i, v ->
            val x = i * stepX
            val y = v * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, washFill)
        drawPath(path, line, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        // The study's direct endpoint.
        val end = Offset(size.width, pts.last() * size.height)
        drawCircle(ring, radius = 5.dp.toPx() / 2 + 1.2.dp.toPx(), center = end)
        drawCircle(line, radius = 5.dp.toPx() / 2, center = end)
    }
}

@Composable
private fun AssetMock(asset: BtWidgetAssetConfig?) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(bt.goldInk, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                asset?.symbol ?: stringResource(R.string.bt_widget_preview_asset_symbol),
                color = bt.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .background(bt.surfaceHigh, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    asset?.currency
                        ?: stringResource(R.string.bt_widget_preview_asset_currency),
                    color = bt.textMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val subline = asset?.let {
            listOf(it.name, it.exchange).filter { part -> part.isNotEmpty() }
                .joinToString(" · ")
        } ?: stringResource(R.string.bt_widget_preview_asset_subline)
        Text(subline, color = bt.textMuted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.bt_widget_preview_asset_price),
            color = bt.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        MockPill(stringResource(R.string.bt_widget_preview_asset_day))
    }
}

/** One RowsMock entry: the study's two-line row, fully populated. */
private data class MockQuoteRow(
    val symbol: String,
    val name: String,
    val price: String,
    val pct: String,
    val up: Boolean,
)

@Composable
private fun RowsMock(config: BtWidgetRowsConfig) {
    val bt = BtTheme.colors
    val rows = listOf(
        MockQuoteRow(
            stringResource(R.string.bt_widget_preview_mover1_symbol),
            stringResource(R.string.bt_widget_preview_mover1_name),
            stringResource(R.string.bt_widget_preview_mover1_price),
            stringResource(R.string.bt_widget_preview_mover1_pct),
            true,
        ),
        MockQuoteRow(
            stringResource(R.string.bt_widget_preview_mover2_symbol),
            stringResource(R.string.bt_widget_preview_mover2_name),
            stringResource(R.string.bt_widget_preview_mover2_price),
            stringResource(R.string.bt_widget_preview_mover2_pct),
            false,
        ),
        MockQuoteRow(
            stringResource(R.string.bt_widget_preview_mover3_symbol),
            stringResource(R.string.bt_widget_preview_mover3_name),
            stringResource(R.string.bt_widget_preview_mover3_price),
            stringResource(R.string.bt_widget_preview_mover3_pct),
            true,
        ),
    )
    val shown = when (config.direction) {
        BtWidgetRowDirection.WINNERS -> rows.filter { it.up }
        BtWidgetRowDirection.LOSERS -> rows.filterNot { it.up }
        else -> rows
    }
    Column(Modifier.fillMaxWidth()) {
        if (config.direction == BtWidgetRowDirection.SPLIT) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bt_widget_preview_split_up), color = bt.gain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(" · ", color = bt.textMuted, fontSize = 13.sp)
                Text(stringResource(R.string.bt_widget_preview_split_down), color = bt.loss, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(2.dp))
        }
        shown.forEachIndexed { i, row ->
            if (i > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(bt.border))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.symbol,
                        color = bt.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(row.name, color = bt.textMuted, fontSize = 9.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(row.price, color = bt.textSecondary, fontSize = 12.sp)
                    Text(
                        row.pct,
                        color = if (row.up) bt.gain else bt.loss,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (config.source == BtWidgetRowSource.HOLDINGS) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bt_widget_rows_moved_short, 8, 14),
                    color = bt.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.bt_widget_rows_day, stringResource(R.string.bt_widget_preview_day_pct)),
                    color = bt.gain,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BudgetMock(
    budget: BtWidgetBudget?,
    style: BtWidgetBudgetStyle,
    emphasis: BtWidgetBudgetEmphasis,
) {
    val bt = BtTheme.colors
    val tag = budget?.tagName ?: stringResource(R.string.bt_widget_preview_budget_tag)
    val locale = at.bettertrack.app.ui.util.rememberBtLocale()
    val spentOf = if (budget == null) {
        stringResource(R.string.bt_widget_preview_budget_spent)
    } else {
        stringResource(
            R.string.bt_widget_budget_of_pair,
            at.bettertrack.app.ui.components.formatMoney(budget.spent, budget.currency, locale),
            at.bettertrack.app.ui.components.formatMoney(budget.amount, budget.currency, locale),
        )
    }
    val lead = if (budget == null) {
        if (emphasis == BtWidgetBudgetEmphasis.REMAINING) {
            stringResource(R.string.bt_widget_budget_left, stringResource(R.string.bt_widget_preview_budget_left_amount))
        } else {
            stringResource(R.string.bt_widget_preview_budget_spent_amount)
        }
    } else {
        val remaining = budget.amount - budget.spent
        if (emphasis == BtWidgetBudgetEmphasis.REMAINING) {
            stringResource(
                R.string.bt_widget_budget_left,
                at.bettertrack.app.ui.components.formatMoney(
                    remaining.coerceAtLeast(0.0), budget.currency, locale,
                ),
            )
        } else {
            at.bettertrack.app.ui.components.formatMoney(budget.spent, budget.currency, locale)
        }
    }
    // 0.62 mirrors the sample strings' "62 %" (the study's Food month).
    val fraction = budget?.let { btWidgetBudgetFraction(it.spent, it.amount) } ?: 0.62f
    val exceeded = budget?.exceeded == true
    val fill = if (exceeded) bt.loss else bt.goldInk

    Column(Modifier.fillMaxWidth()) {
        MockSubjectRow(tag, stringResource(R.string.bt_widget_preview_budget_month))
        Spacer(Modifier.height(6.dp))
        when (style) {
            BtWidgetBudgetStyle.RING -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    val track = bt.border
                    Canvas(Modifier.size(60.dp)) {
                        val strokeW = 8.dp.toPx()
                        val stroke = Stroke(width = strokeW, cap = StrokeCap.Butt)
                        val inset = strokeW / 2f
                        val arcSize = Size(size.width - strokeW, size.height - strokeW)
                        drawArc(
                            track, -90f, 360f, false,
                            topLeft = Offset(inset, inset), size = arcSize, style = stroke,
                        )
                        drawArc(
                            fill, -90f, 360f * fraction, false,
                            topLeft = Offset(inset, inset), size = arcSize, style = stroke,
                        )
                    }
                    Text(
                        // The REAL budget's percent when one drives the card —
                        // the static "62 %" beside live "0,00 € von 400,00 €"
                        // contradicted its own numbers (device QA 2026-08-16).
                        budget?.let {
                            btWidgetBudgetPercent(it.spent, it.amount)?.let { pct ->
                                at.bettertrack.app.ui.components.formatPercent(
                                    pct, locale, showSign = false,
                                )
                            }
                        } ?: stringResource(R.string.bt_widget_preview_budget_pct),
                        color = if (exceeded) bt.loss else bt.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        lead,
                        color = if (exceeded) bt.loss else bt.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(spentOf, color = bt.textMuted, fontSize = 10.sp)
                }
            }

            BtWidgetBudgetStyle.BAR -> Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.bt_widget_budget_spent_label),
                        color = bt.textMuted,
                        fontSize = 9.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.bt_widget_preview_budget_pct),
                        color = if (exceeded) bt.loss else bt.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(bt.border, CircleShape),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(fill, CircleShape),
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row {
                    Text(
                        spentOf,
                        color = bt.textMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        lead,
                        color = if (exceeded) bt.loss else bt.goldInk,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            BtWidgetBudgetStyle.AMOUNT -> Column(Modifier.fillMaxWidth()) {
                Text(
                    lead,
                    color = if (exceeded) bt.loss else bt.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(spentOf, color = bt.textMuted, fontSize = 10.sp)
            }
        }
    }
}

/**
 * The allocation card's preview.
 *
 * It follows the `Darstellung` chip, because a picker whose preview never
 * changes is a picker the user has to place a widget to evaluate. The sample
 * numbers are the study's set A so the shapes are compared on the same data the
 * design was decided on.
 */
@Composable
private fun AllocationMock(center: BtWidgetAllocationCenter, form: BtWidgetAllocationForm) {
    if (form != BtWidgetAllocationForm.DONUT) {
        AllocationFormMock(form)
        return
    }
    val bt = BtTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val slices = listOf(0.42f, 0.28f, 0.16f, 0.14f)
        val colors = listOf(bt.chartSeries[0], bt.chartSeries[1], bt.chartSeries[2], bt.chartRest)
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(72.dp)) {
                val strokeW = 9.dp.toPx()
                val stroke = Stroke(width = strokeW)
                val inset = strokeW / 2f
                val arcSize = Size(size.width - strokeW, size.height - strokeW)
                var start = -90f
                slices.forEachIndexed { i, f ->
                    val sweep = f * 360f - 3f
                    drawArc(
                        colors[i], start + 1.5f, sweep, false,
                        topLeft = Offset(inset, inset), size = arcSize, style = stroke,
                    )
                    start += f * 360f
                }
            }
            Text(
                when (center) {
                    BtWidgetAllocationCenter.TOTAL ->
                        stringResource(R.string.bt_widget_preview_alloc_total)
                    BtWidgetAllocationCenter.TOP ->
                        stringResource(R.string.bt_widget_preview_alloc1_pct)
                },
                color = bt.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            listOf(
                stringResource(R.string.bt_widget_preview_alloc1) to
                    stringResource(R.string.bt_widget_preview_alloc1_pct),
                stringResource(R.string.bt_widget_preview_alloc2) to
                    stringResource(R.string.bt_widget_preview_alloc2_pct),
                stringResource(R.string.bt_widget_preview_alloc3) to
                    stringResource(R.string.bt_widget_preview_alloc3_pct),
            ).forEachIndexed { i, (label, pct) ->
                Row(
                    Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        color = bt.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pct,
                        color = bt.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Treemap / mosaic / bar / heatmap preview.
 *
 * Drawn with the shared `ui.charts.viz` renderer rather than a hand-built mock,
 * so what the builder shows and what the launcher paints come from one
 * description of each shape. The heatmap sample carries signed changes because
 * that is the whole point of the form.
 */
@Composable
private fun AllocationFormMock(form: BtWidgetAllocationForm) {
    val bt = BtTheme.colors
    val heat = form == BtWidgetAllocationForm.HEATMAP
    val sample = remember(heat) {
        if (heat) {
            listOf(
                VizDatum("a", "MSFT", 12.8, colorIndex = 0),
                VizDatum("b", "VWCE", 11.8, colorIndex = 1),
                VizDatum("c", "ETH", 10.4, colorIndex = 2),
                VizDatum("d", "BAYN", 8.4, colorIndex = 3),
                VizDatum("e", "RKLB", 7.5, colorIndex = 4),
                VizDatum("f", "NVDA", 6.8, colorIndex = 5),
            )
        } else {
            listOf(
                VizDatum("a", "Aktien", 16_203.28, colorIndex = 0),
                VizDatum("b", "ETFs", 10_802.18, colorIndex = 1),
                VizDatum("c", "Krypto", 6_172.68, colorIndex = 2),
                VizDatum("d", "Cash", 3_086.34, role = VizRole.Cash),
                VizDatum("e", "Anleihen", 1_543.17, colorIndex = 3),
            )
        }
    }
    // Direction for the heatmap sample: both hues, and one unquoted cell, so the
    // preview shows the neutral case too rather than implying every tile moves.
    val signs = listOf(2.4, 1.1, -0.6, -2.8, 3.9, null)
    val locale = rememberBtLocale()
    val format = remember(locale) {
        BtVizFormat(
            amount = { v -> formatEur(v, locale) },
            share = { f -> formatPercent(f * 100.0, locale, showSign = false) },
        )
    }
    // A Column, not a bare stack: the `preview` slot is a Box, so a Spacer and a
    // caption emitted after the chart would paint ON TOP of it.
    Column(Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(if (form == BtWidgetAllocationForm.BAR) 30.dp else 112.dp)) {
        if (heat) {
            BtVizHeatmap(
                cells = sample.mapIndexed { i, d ->
                    VizHeatCell(d.key, d.label, d.value, signs.getOrNull(i))
                },
                changeText = { formatPercent(it, locale, showSign = true) },
                emptyText = "",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BtVizChart(
                items = sample,
                form = when (form) {
                    BtWidgetAllocationForm.TREEMAP -> BtVizForm.TREEMAP
                    BtWidgetAllocationForm.MOSAIC -> BtVizForm.MOSAIC
                    else -> BtVizForm.STACKED_BAR
                },
                canvas = BtVizCanvas.APP_FULL,
                format = format,
                emptyText = "",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    // Only the heatmap needs a caption, and it needs it for a reason rather
    // than for symmetry: its universe is the account's own holdings, and the
    // scope of a map has to be stated or the reader supplies a wrong one.
    if (heat) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.bt_widget_heatmap_scope),
            color = bt.textMuted,
            fontSize = 11.sp,
        )
    }
    }
}

@Composable
private fun FlowMock(mode: BtWidgetFlowMode) {
    val bt = BtTheme.colors
    when (mode) {
        BtWidgetFlowMode.EQUATION -> Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bt_widget_flow_current_month).uppercase(),
                color = bt.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.bt_widget_preview_budget_month),
                color = bt.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    Triple(stringResource(R.string.bt_widget_flow_in), stringResource(R.string.bt_widget_preview_flow_in), bt.gain),
                    Triple(stringResource(R.string.bt_widget_flow_out), stringResource(R.string.bt_widget_preview_flow_out), bt.loss),
                    Triple(stringResource(R.string.bt_widget_flow_net), stringResource(R.string.bt_widget_preview_flow_net), bt.gain),
                ).forEach { (label, value, color) ->
                    Column(Modifier.weight(1f)) {
                        Text(
                            label.uppercase(),
                            color = bt.textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        BtWidgetFlowMode.BARS -> Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.bt_widget_flow_net_window, 6).uppercase(),
                color = bt.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.bt_widget_preview_flow_window), color = bt.gain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val gain = bt.gain
            val loss = bt.loss
            val track = bt.border
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val ins = listOf(0.5f, 0.7f, 0.4f, 0.9f, 0.6f, 1f)
                val outs = listOf(0.4f, 0.3f, 0.6f, 0.5f, 0.4f, 0.45f)
                val slot = size.width / ins.size
                val zero = size.height / 2f
                val barW = slot * 0.34f
                ins.forEachIndexed { i, f ->
                    val left = i * slot + (slot - barW) / 2f
                    drawRect(
                        gain,
                        topLeft = Offset(left, zero - 2.dp.toPx() - f * (zero - 4.dp.toPx())),
                        size = Size(barW, f * (zero - 4.dp.toPx())),
                    )
                    drawRect(
                        loss,
                        topLeft = Offset(left, zero + 2.dp.toPx()),
                        size = Size(barW, outs[i] * (zero - 4.dp.toPx())),
                    )
                }
                drawRect(
                    track,
                    topLeft = Offset(0f, zero - 0.5.dp.toPx()),
                    size = Size(size.width, 1.dp.toPx()),
                )
            }
        }

        BtWidgetFlowMode.DONUT -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val slices = listOf(0.45f, 0.30f, 0.25f)
            val colors = listOf(bt.chartSeries[0], bt.chartSeries[1], bt.chartSeries[2])
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(64.dp)) {
                    val strokeW = 8.dp.toPx()
                    val stroke = Stroke(width = strokeW)
                    val inset = strokeW / 2f
                    val arcSize = Size(size.width - strokeW, size.height - strokeW)
                    var start = -90f
                    slices.forEachIndexed { i, f ->
                        val sweep = f * 360f - 4f
                        drawArc(
                            colors[i], start + 2f, sweep, false,
                            topLeft = Offset(inset, inset), size = arcSize, style = stroke,
                        )
                        start += f * 360f
                    }
                }
                Text(
                    stringResource(R.string.bt_widget_preview_spending_total),
                    color = bt.textPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                listOf(
                    stringResource(R.string.bt_widget_preview_legend1),
                    stringResource(R.string.bt_widget_preview_legend2),
                    stringResource(R.string.bt_widget_preview_legend3),
                ).forEachIndexed { i, label ->
                    Row(
                        Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(colors[i], CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = bt.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/**
 * The Cash Wallet mock: the 2x1 rendition on SAMPLE money, with the user's own
 * wallet NAME when they have picked one — the same rule every other mock here
 * follows (sample figures, real names, because naming the choice is what makes
 * the preview legible).
 */
@Composable
private fun CashWalletMock(sourceName: String?) {
    val bt = BtTheme.colors
    Column(Modifier.fillMaxWidth()) {
        MockSubjectRow(
            subject = sourceName ?: stringResource(R.string.bt_widget_preview_cash_source),
        )
        Text(
            stringResource(R.string.bt_widget_preview_cash_balance),
            color = bt.textPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            // Bezahlt first, Erhalten second — the owner's fixed order, and the
            // only two colours this family is allowed to spend.
            MockCashAction(
                label = stringResource(R.string.bt_cash_withdraw),
                icon = R.drawable.ic_bt_widget_paid,
                ink = bt.loss,
                wash = bt.lossWash,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            MockCashAction(
                label = stringResource(R.string.bt_cash_deposit),
                icon = R.drawable.ic_bt_widget_received,
                ink = bt.gain,
                wash = bt.gainWash,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        // The reserved third slot, shown as it ships: present, understandable,
        // and labelled as not live yet.
        MockCashAction(
            label = stringResource(R.string.bt_widget_cash_photo),
            icon = R.drawable.ic_bt_widget_camera,
            ink = bt.goldInk,
            wash = bt.goldWash,
            badge = stringResource(R.string.bt_widget_cash_soon),
            fullWidth = true,
        )
    }
}

/** One mock action button: glyph + verb on the direction's own wash. */
@Composable
private fun MockCashAction(
    label: String,
    icon: Int,
    ink: androidx.compose.ui.graphics.Color,
    wash: androidx.compose.ui.graphics.Color,
    badge: String? = null,
    fullWidth: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .background(wash, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = if (badge != null) Modifier.weight(1f) else Modifier,
        )
        if (badge != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                badge,
                color = bt.onGold,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(bt.gold, RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The builder's asset row: shows the chosen symbol, opens the SEARCHING picker.
 *
 * Same sheet family as [SelectorRow] (owner order: everything user-facing pops
 * from the bottom) but hosting [BtWidgetAssetPicker], so the builder and the
 * widget's own config Activity offer the identical universe of assets.
 */
@Composable
private fun AssetSearchRow(
    localChoices: List<BtWidgetAssetConfig>,
    selected: BtWidgetAssetConfig?,
    onPick: (BtWidgetAssetConfig) -> Unit,
) {
    val bt = BtTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.bt_widgets_pick_asset_label),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            selected?.symbol.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = bt.textPrimary,
            fontWeight = FontWeight.Medium,
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = bt.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        BtPickerSheet(
            title = stringResource(R.string.bt_widget_config_pick_asset),
            onDismiss = { open = false },
        ) {
            BtWidgetAssetPicker(localChoices = localChoices) { picked ->
                onPick(picked)
                open = false
            }
        }
    }
}
