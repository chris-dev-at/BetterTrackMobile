package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.charts.BtAreaChart
import at.bettertrack.app.ui.charts.BtChartPalette
import at.bettertrack.app.ui.charts.BtDonutChart
import at.bettertrack.app.ui.charts.DonutSegment
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.theme.BtTheme
import java.util.Locale

/**
 * Shared initializer for [PortfolioOverviewViewModel]. Both this screen and the
 * app-shell top-bar portfolio selector resolve the VM through THIS initializer,
 * scoped to the Portfolio nav-graph entry — so they share ONE instance (one
 * source of truth for the selected portfolio + the switcher sheet's open state).
 */
internal val PortfolioOverviewVmInitializer: CreationExtras.() -> PortfolioOverviewViewModel = {
    PortfolioOverviewViewModel(
        AppGraph.portfolioRepository,
        AppGraph.connectivityMonitor,
        AppGraph.database,
        AppGraph.json,
    )
}

/**
 * The Portfolio tab overview (Step 6, spec §6.1) — the app's home: Net-Worth
 * hero, §3.6 history graph (blended full-bleed into the page) with range chips,
 * holdings/cash line, allocation donut and the holdings list. The portfolio
 * selector lives in the app-shell top bar beside the wordmark (it opens the
 * switcher sheet hosted here). Renders ONLY server-computed numbers from Room
 * (§7.1); offline shows the cache under the global as-of banner. The buy/sell
 * FAB (≤2 taps) + the pending-changes strip (§7.4) live here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioOverviewScreen(
    onOpenHolding: (String) -> Unit,
    onOpenTransactions: (String) -> Unit,
    onNewTransaction: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCash: (String) -> Unit,
) {
    val vm: PortfolioOverviewViewModel = viewModel(initializer = PortfolioOverviewVmInitializer)

    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val hasEverSynced by vm.hasEverSynced.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val switcherBusy by vm.switcherBusy.collectAsStateWithLifecycle()
    val switcherError by vm.switcherError.collectAsStateWithLifecycle()
    val pendingTx by vm.pendingTx.collectAsStateWithLifecycle()

    // Sheet visibility lives in the VM so the shell top-bar selector can open it.
    val switcherOpen by vm.switcherVisible.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose { }
    }

    val bt = BtTheme.colors
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
                color = bt.gold,
            )
        },
    ) {
        when {
            // First run, nothing cached yet: skeleton, never a blank screen.
            selected == null && !hasEverSynced && loadError == null ->
                OverviewSkeleton()

            // Nothing cached AND the first load failed: honest error + retry.
            selected == null && !hasEverSynced ->
                ErrorFillState { vm.refresh() }

            // Synced but zero active portfolios: branded create-first state.
            selected == null ->
                NoPortfolioState(
                    isOnline = isOnline,
                    busy = switcherBusy,
                    error = switcherError,
                    onCreate = { name -> vm.createPortfolio(name) },
                )

            else -> OverviewContent(
                portfolio = selected!!,
                holdings = holdings,
                history = history,
                range = range,
                pendingTx = pendingTx,
                onRange = vm::setRange,
                onOpenHolding = onOpenHolding,
                onOpenTransactions = onOpenTransactions,
                onOpenPendingSync = onOpenPendingSync,
                onOpenCash = onOpenCash,
            )
        }

        // Step 8 (§6.2): recording a transaction is ≤2 taps from the overview —
        // this FAB opens the buy/sell form directly.
        selected?.let { p ->
            val fabCd = stringResource(R.string.bt_overview_fab_cd)
            FloatingActionButton(
                onClick = { onNewTransaction(p.id) },
                containerColor = bt.gold,
                contentColor = bt.onGold,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .semantics { contentDescription = fabCd },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
    }

    if (switcherOpen) {
        PortfolioSwitcherSheet(
            portfolios = portfolios,
            selectedId = selected?.id,
            isOnline = isOnline,
            busy = switcherBusy,
            error = switcherError,
            onDismiss = { vm.dismissSwitcher() },
            onSelect = { id ->
                vm.selectPortfolio(id)
                vm.dismissSwitcher()
            },
            onCreate = { name, onDone -> vm.createPortfolio(name) { ok -> onDone(ok) } },
            onRename = { id, name, onDone -> vm.renamePortfolio(id, name) { ok -> onDone(ok) } },
            onArchive = { id, onDone -> vm.archivePortfolio(id) { ok -> onDone(ok) } },
            onRestore = { id, onDone -> vm.restorePortfolio(id) { ok -> onDone(ok) } },
            onDelete = { id, onResult -> vm.deletePortfolio(id) { result -> onResult(result) } },
        )
    }
}

// ── Content ──────────────────────────────────────────────────────────────────

@Composable
private fun OverviewContent(
    portfolio: PortfolioEntity,
    holdings: List<HoldingEntity>,
    history: PortfolioHistory?,
    range: HistoryRange,
    pendingTx: List<PendingTxRow>,
    onRange: (HistoryRange) -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenTransactions: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCash: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val totals = portfolio.totals

    // Scrub state is hoisted here so touching the hero chart updates the big
    // Net-Worth readout (Robinhood-style). A fresh selection/range clears it.
    var scrub by remember { mutableStateOf<HistoryPoint?>(null) }
    LaunchedEffect(portfolio.id, range) { scrub = null }

    // Content is inset 16dp — EXCEPT the hero chart, which bleeds edge-to-edge.
    val inset = Modifier.fillMaxWidth().padding(horizontal = 16.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            // Clear the buy/sell FAB (56dp + 20dp inset + margin) so the last
            // holding row scrolls fully into view instead of under it.
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Net-Worth hero (server totals only) — scrub-aware: while scrubbing the
        // chart, the big number + label become the touched point's value + date.
        item(key = "hero") {
            val s = scrub
            Column(inset) {
                Text(
                    text = if (s != null) formatChartScrubDate(s.epochDay, locale)
                    else stringResource(R.string.bt_overview_net_worth),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                if (totals != null || s != null) {
                    MoneyText(
                        value = s?.valueEur ?: totals!!.totalValueEur,
                        style = BtTheme.type.moneyLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Reserve the sub-line height so scrubbing never shifts layout.
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        if (s == null && totals != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MoneyText(
                                    value = totals.dayChangeEur,
                                    style = BtTheme.type.numberCaption,
                                    colorMode = MoneyColorMode.GainLoss,
                                    showSign = true,
                                )
                                totals.dayChangePct?.let { pct ->
                                    Text(
                                        text = " (${formatPercent(pct, locale)})",
                                        style = BtTheme.type.numberCaption,
                                        color = deltaColor(pct),
                                    )
                                }
                                Text(
                                    text = " · " + stringResource(R.string.bt_overview_today),
                                    style = BtTheme.type.numberCaption,
                                    color = bt.textMuted,
                                )
                            }
                        }
                    }
                } else {
                    BtSkeleton(Modifier.width(220.dp).height(40.dp))
                    Spacer(Modifier.height(6.dp))
                    BtSkeleton(Modifier.width(120.dp).height(14.dp))
                }
            }
        }

        // Pending changes strip (§7.1/§7.4): queued entries live ALONGSIDE the
        // server-computed numbers, clearly marked, and open the Pending-sync
        // screen — they are never folded into the totals above.
        if (pendingTx.isNotEmpty()) {
            item(key = "pending-strip") {
                Box(inset) { PendingStrip(pendingTx = pendingTx, onClick = onOpenPendingSync) }
            }
        }

        // History graph (§3.6) — blended full-bleed hero: no card, gold gradient
        // fading into the page, edge-to-edge, minimal axis. Header + range chips
        // stay inset; only the canvas bleeds.
        item(key = "chart") {
            HeroChart(
                history = history,
                range = range,
                scrubbing = scrub != null,
                onRange = onRange,
                onScrub = { scrub = it },
                locale = locale,
            )
        }

        // The roll-up line: holdings value + cash (server totals). The cash
        // card opens the Step-9 cash screen (§6.3).
        item(key = "rollup") {
            Row(inset, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RollupCard(
                    label = stringResource(R.string.bt_overview_holdings_value),
                    value = totals?.marketValueEur,
                    modifier = Modifier.weight(1f),
                )
                RollupCard(
                    label = stringResource(R.string.bt_overview_cash),
                    value = totals?.cashEur,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenCash(portfolio.id) },
                )
            }
        }

        // Allocation donut (by asset / by category).
        if (holdings.isNotEmpty() || (totals?.cashEur ?: 0.0) > 0.0) {
            item(key = "allocation") {
                Box(inset) {
                    AllocationCard(holdings = holdings, cashEur = totals?.cashEur ?: 0.0, locale = locale)
                }
            }
        }

        // Holdings list.
        item(key = "holdings-header") {
            Row(
                modifier = inset.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bt_overview_holdings_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                BtChip(
                    text = stringResource(R.string.bt_tx_title),
                    onClick = { onOpenTransactions(portfolio.id) },
                )
            }
        }
        if (holdings.isEmpty()) {
            item(key = "holdings-empty") {
                Box(inset) {
                    BtEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = stringResource(R.string.bt_overview_no_holdings_title),
                        message = stringResource(R.string.bt_overview_no_holdings_message),
                    )
                }
            }
        } else {
            items(
                count = holdings.size,
                key = { "holding-" + holdings[it].assetId },
            ) { index ->
                val h = holdings[index]
                Box(inset) {
                    HoldingRow(
                        holding = h,
                        weightOfPortfolioPct = weightPct(h.marketValueEur, portfolio.totals?.marketValueEur),
                        locale = locale,
                        onClick = { onOpenHolding(h.assetId) },
                    )
                }
            }
        }
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────

/**
 * Compact §7.4 strip: how many recorded changes are waiting to sync (plus the
 * needs-attention count in red), tap-through to the Pending-sync screen.
 */
@Composable
private fun PendingStrip(pendingTx: List<PendingTxRow>, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val attention = pendingTx.count { it.status == PendingUiStatus.NEEDS_ATTENTION }
    val waiting = pendingTx.size - attention
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = if (attention > 0) bt.loss else bt.goldEmphasis,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (waiting > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.bt_pending_strip_waiting, waiting, waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textSecondary,
                    )
                }
                if (attention > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.bt_pending_strip_attention,
                            attention,
                            attention,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.lossSoft,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The blended hero history graph (§3.6, owner redesign 2026-07-09): the area
 * chart sits directly on the page (no card), the gold gradient fades into the
 * background, and the canvas bleeds edge-to-edge. Only the range-performance
 * header line and the range chips stay inset; the chart itself is full-width
 * with minimal scaffolding. Scrubbing is reported up so the Net-Worth hero
 * shows the touched point.
 */
@Composable
private fun HeroChart(
    history: PortfolioHistory?,
    range: HistoryRange,
    scrubbing: Boolean,
    onRange: (HistoryRange) -> Unit,
    onScrub: (HistoryPoint?) -> Unit,
    locale: Locale,
) {
    val bt = BtTheme.colors
    val chartCd = stringResource(R.string.bt_overview_chart_cd)
    Column(Modifier.fillMaxWidth()) {
        // Range performance (inset), reserved height, hidden while scrubbing so
        // the hero's scrub readout is the single focus.
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val pct = history?.rangePerformancePct
            if (!scrubbing && pct != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatPercent(pct, locale),
                        style = BtTheme.type.numberCaption,
                        color = deltaColor(pct),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = rangeLabel(range),
                        style = BtTheme.type.numberCaption,
                        color = bt.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        val points = history?.points.orEmpty()
        if (points.size >= 2) {
            BtAreaChart(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .semantics { contentDescription = chartCd },
                lineColor = bt.gold,
                minimal = true,
                onScrub = onScrub,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (history == null) {
                    BtSkeleton(Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 16.dp))
                } else {
                    Text(
                        text = stringResource(R.string.bt_overview_chart_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // Range chips (inset) — the set the platform serves (1D/1W/3M need a
        // server-side window that doesn't exist yet; platform gap).
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HistoryRange.entries.forEach { r ->
                BtChip(
                    text = rangeLabel(r),
                    selected = r == range,
                    onClick = { onRange(r) },
                )
            }
        }
    }
}

@Composable
private fun RollupCard(
    label: String,
    value: Double?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val bt = BtTheme.colors
    BtCard(modifier = modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
            )
            Spacer(Modifier.height(2.dp))
            if (value != null) {
                MoneyText(value = value, style = BtTheme.type.moneyMedium)
            } else {
                BtSkeleton(Modifier.width(90.dp).height(22.dp))
            }
        }
    }
}

@Composable
private fun AllocationCard(holdings: List<HoldingEntity>, cashEur: Double, locale: Locale) {
    val bt = BtTheme.colors
    var byCategory by rememberSaveable { mutableStateOf(false) }

    val otherLabel = stringResource(R.string.bt_overview_alloc_other)
    val cashLabel = stringResource(R.string.bt_overview_alloc_cash)
    val segments = remember(holdings, cashEur, byCategory, otherLabel, cashLabel) {
        allocationSegments(holdings, cashEur, byCategory, otherLabel, cashLabel)
    }
    val total = segments.sumOf { it.value }

    BtCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bt_overview_allocation_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                BtChip(
                    text = stringResource(R.string.bt_overview_alloc_by_asset),
                    selected = !byCategory,
                    onClick = { byCategory = false },
                )
                Spacer(Modifier.width(8.dp))
                BtChip(
                    text = stringResource(R.string.bt_overview_alloc_by_category),
                    selected = byCategory,
                    onClick = { byCategory = true },
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BtDonutChart(
                    segments = segments,
                    modifier = Modifier.size(132.dp),
                )
                Spacer(Modifier.width(20.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    segments.forEach { segment ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(segment.color, CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = segment.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = bt.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            weightPct(segment.value, total)?.let { pct ->
                                Text(
                                    text = formatWeight(pct, locale),
                                    style = BtTheme.type.numberCaption,
                                    color = bt.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingRow(
    holding: HoldingEntity,
    weightOfPortfolioPct: Double?,
    locale: Locale,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = holding.assetName,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val amount = "${formatQuantity(holding.quantity, locale)} ${holding.assetSymbol}"
                val weight = weightOfPortfolioPct?.let {
                    " · " + formatWeight(it, locale)
                } ?: ""
                Text(
                    text = amount + weight,
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (holding.marketValueEur != null) {
                    MoneyText(value = holding.marketValueEur, style = BtTheme.type.moneySmall)
                } else {
                    Text(
                        text = stringResource(R.string.bt_switcher_value_pending),
                        style = BtTheme.type.moneySmall,
                        color = bt.textMuted,
                    )
                }
                Spacer(Modifier.height(2.dp))
                val plPct = holding.unrealizedPnlPct
                val plEur = holding.unrealizedPnlEur
                when {
                    plPct != null -> Text(
                        text = formatPercent(plPct, locale),
                        style = BtTheme.type.numberCaption,
                        color = deltaColor(plPct),
                    )

                    plEur != null -> Text(
                        text = formatEur(plEur, locale, showSign = true),
                        style = BtTheme.type.numberCaption,
                        color = deltaColor(plEur),
                    )
                }
            }
        }
    }
}

// ── Empty / error / skeleton fills ──────────────────────────────────────────

@Composable
private fun OverviewSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BtSkeleton(Modifier.width(160.dp).height(24.dp))
        Spacer(Modifier.height(4.dp))
        BtSkeleton(Modifier.width(220.dp).height(40.dp))
        BtSkeleton(Modifier.width(120.dp).height(14.dp))
        Spacer(Modifier.height(8.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(230.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BtSkeleton(Modifier.weight(1f).height(64.dp))
            BtSkeleton(Modifier.weight(1f).height(64.dp))
        }
        BtSkeleton(Modifier.fillMaxWidth().height(56.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(56.dp))
        BtSkeleton(Modifier.fillMaxWidth().height(56.dp))
    }
}

@Composable
private fun ErrorFillState(onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                BtErrorState(onRetry = onRetry)
            }
        }
    }
}

@Composable
private fun NoPortfolioState(
    isOnline: Boolean,
    busy: Boolean,
    error: String?,
    onCreate: (String) -> Unit,
) {
    val bt = BtTheme.colors
    var createOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BtEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = stringResource(R.string.bt_overview_no_portfolio_title),
                        message = stringResource(R.string.bt_overview_no_portfolio_message),
                        action = {
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_overview_create_portfolio),
                                onClick = { createOpen = true },
                                enabled = isOnline && !busy,
                                loading = busy,
                            )
                        },
                    )
                    if (!isOnline) {
                        Text(
                            text = stringResource(R.string.bt_switcher_requires_connection),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.loss,
                        )
                    }
                }
            }
        }
    }
    if (createOpen) {
        PortfolioNameDialog(
            title = stringResource(R.string.bt_switcher_create_title),
            confirmLabel = stringResource(R.string.bt_switcher_create_action),
            initialName = "",
            busy = busy,
            onConfirm = { name ->
                onCreate(name)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }
}

// ── Display mapping (proportions of server values only) ────────────────────

/**
 * Build the donut segments from server values: top slices by weight in fixed
 * palette-slot order, tail folded into "Other", cash always its own quiet
 * slice. Percentages are proportions of the server-provided EUR values — the
 * same display mapping the reference web app renders.
 */
private fun allocationSegments(
    holdings: List<HoldingEntity>,
    cashEur: Double,
    byCategory: Boolean,
    otherLabel: String,
    cashLabel: String,
): List<DonutSegment> {
    data class Part(val label: String, val value: Double)

    val parts: List<Part> = if (byCategory) {
        holdings
            .groupBy { it.assetType }
            .map { (type, rows) -> Part(categoryLabel(type), rows.sumOf { it.marketValueEur ?: 0.0 }) }
    } else {
        holdings.map { Part(it.assetSymbol, it.marketValueEur ?: 0.0) }
    }
        .filter { it.value > 0.0 }
        .sortedByDescending { it.value }

    val maxSlots = BtChartPalette.series.size
    val top = parts.take(maxSlots)
    val rest = parts.drop(maxSlots).sumOf { it.value }

    return buildList {
        top.forEachIndexed { i, part ->
            add(DonutSegment(part.label, part.value, BtChartPalette.series[i]))
        }
        if (rest > 0.0) add(DonutSegment(otherLabel, rest, BtChartPalette.rest))
        if (cashEur > 0.0) add(DonutSegment(cashLabel, cashEur, BtChartPalette.cash))
    }
}

/** Server asset types → display labels. TODO(step 19): localize. */
private fun categoryLabel(type: String): String = when (type) {
    "stock" -> "Stocks"
    "etf" -> "ETFs"
    "index" -> "Indices"
    "fx" -> "FX"
    "commodity" -> "Commodities"
    "crypto" -> "Crypto"
    "custom" -> "Custom"
    else -> type.replaceFirstChar { it.uppercase() }
}

@Composable
internal fun rangeLabel(range: HistoryRange): String = when (range) {
    HistoryRange.M1 -> stringResource(R.string.bt_range_1m)
    HistoryRange.M6 -> stringResource(R.string.bt_range_6m)
    HistoryRange.Y1 -> stringResource(R.string.bt_range_1y)
    HistoryRange.MAX -> stringResource(R.string.bt_range_max)
}

@Composable
internal fun deltaColor(value: Double) = when {
    value > 0.0 -> BtTheme.colors.gain
    value < 0.0 -> BtTheme.colors.loss
    else -> BtTheme.colors.textSecondary
}

private fun formatChartScrubDate(epochDay: Long, locale: Locale): String =
    java.time.LocalDate.ofEpochDay(epochDay)
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale))
