package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.repo.HistoryPoint
import at.bettertrack.app.data.repo.HistoryRange
import at.bettertrack.app.data.repo.PortfolioHistory
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.ui.charts.BtAreaChart
import at.bettertrack.app.ui.charts.rangeWord
import at.bettertrack.app.ui.shell.LocalBtTabChrome
import at.bettertrack.app.ui.shell.BtTabSelector
import at.bettertrack.app.ui.theme.FONT_FEATURE_TABULAR
import at.bettertrack.app.ui.components.BT_FAB_EDGE_INSET
import at.bettertrack.app.ui.components.BT_FAB_CONTENT_CLEARANCE
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtRangeSegmented
import at.bettertrack.app.ui.charts.rangeLabel
import at.bettertrack.app.ui.components.BtSegmented
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.BtStateFill
import at.bettertrack.app.ui.components.rememberBtFabVisibility
import at.bettertrack.app.ui.components.btFabIconSize
import at.bettertrack.app.ui.components.equalSegmentShareDp
import at.bettertrack.app.ui.components.fabVisibleForList
import at.bettertrack.app.ui.components.MoneyText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import at.bettertrack.app.data.prefs.BtChartMode
import at.bettertrack.app.ui.components.btPressScale
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import androidx.compose.ui.unit.Dp
import java.util.Locale
import at.bettertrack.app.ui.components.BtRailedRow

/**
 * The hero chart's canvas height.
 *
 * Named rather than inlined because it is a hierarchy decision, not a layout
 * detail.
 *
 * **240dp as of 2026-08-07, on the owner's direct order** — *"make the graphs
 * bigger and more main of the page"*. R1's decision O-5 had cut the praised
 * 200dp hero to 150dp to buy the allocation summary and the first holding rows a
 * place on the first screen; living with it, the owner's verdict was that the
 * trade went the wrong way. The chart is what this screen is looked at FOR
 * between the number at the top and the list below, and at 150dp it had stopped
 * being the page's subject and become a strip above the real content.
 *
 * So it is now larger than the shape O-5 shrank — the allocation summary and the
 * holdings keep their ORDER, they simply start below the fold, which is the
 * correct cost for the correct hierarchy. Anyone lowering this is re-making a
 * decision the owner has now made twice.
 */
private val HERO_CHART_HEIGHT: Dp = 240.dp

/**
 * Initializer for [PortfolioOverviewViewModel], scoped to the Portfolio nav-graph
 * entry.
 *
 * Private again as of R-arc R1 (decision O-10). It was `internal` because the
 * app-shell top-bar portfolio selector resolved the same VM from outside this
 * screen's composition, so the two had to share one instance. That chip is gone —
 * the switcher now opens from this screen's own collapsing header — and with
 * exactly one consumer left, widening the visibility would only invite a second
 * cross-composition consumer to appear without anyone deciding it should.
 */
private val PortfolioOverviewVmInitializer: CreationExtras.() -> PortfolioOverviewViewModel = {
    PortfolioOverviewViewModel(
        AppGraph.portfolioRepository,
        AppGraph.connectivityMonitor,
        AppGraph.database,
        AppGraph.json,
        AppGraph.devicePrefs,
    )
}

/**
 * The Portfolio tab (Step 6, spec §6.1; re-laid out for the R-arc mandate §3).
 *
 * ## What the screen leads with, and why the order changed
 *
 * The mandate's complaint — "some pages show you useless info first" — landed
 * squarely here: the old order put a sync strip and a pair of roll-up cards
 * between the value and the holdings, so on a 360×800 screen the user saw a
 * number, some infrastructure, and no positions. The order is now:
 *
 *  1. **value + today's change** — unchanged, including the W6 honest states;
 *  2. **the chart**, at 150dp instead of 200 (the owner's praised hero, kept, but
 *     no longer allowed to push the list off the screen on its own);
 *  3. **an allocation summary** — a slim stacked bar and the top three names,
 *     with the full donut one tap behind "See all" (decision O-5);
 *  4. **the holdings list**, which is what this screen is *for*;
 *  5. **cash and transactions**, demoted from 50/50 cards to secondary rows;
 *  6. **the pending-sync strip**, below everything — unless something in it
 *     needs attention, which is the one case where it is not status but work,
 *     and it moves back up under the hero.
 *
 * The "Holdings value" roll-up card is deleted outright: it printed the sum of a
 * list rendered 200px underneath it. Its one real job — the W6 "nothing could be
 * priced" honesty — moved into the holdings section header, where the absence is
 * next to the rows it is about.
 *
 * ## The header owns the switcher now
 *
 * The portfolio name is a collapsing large title ([BtCollapsingHeader]) and
 * tapping it opens the switcher sheet this screen already hosted. That is the
 * mandate's §1 relocation: the selector chip leaves the shell's top bar, and the
 * capability lands somewhere strictly more capable — the title says which
 * portfolio you are looking at even when you are not about to switch.
 *
 * Renders ONLY server-computed numbers from Room (§7.1); offline shows the cache
 * under the global as-of banner. The buy/sell FAB (≤2 taps) lives here, and the
 * header carries no `+`: one creation entry per screen (mandate §1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioOverviewScreen(
    onOpenHolding: (String) -> Unit,
    onOpenTransactions: (String) -> Unit,
    onNewTransaction: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCash: (String) -> Unit,
    /** Opens ONE portfolio's settings page (name, sharing, taxes, group, danger). */
    onOpenPortfolioSettings: (String) -> Unit,
    /** Opens the portfolio's insights subpage (allocation + future modules). */
    onOpenInsights: (String) -> Unit,
    /**
     * What to draw when the switcher's pinned **Overview** entry is selected —
     * the account-wide index that used to be the Home tab (owner IA change).
     *
     * A slot rather than a direct `HomeScreen(...)` call, so this screen stays
     * ignorant of Home: the shell owns which content Overview means and the
     * dozen callbacks that content needs, and this file keeps knowing only about
     * portfolios. It also means the Overview branch composes NOTHING while a
     * portfolio is selected — Home's view model is never even constructed.
     *
     * Receives the two things Overview's content needs from THIS screen's state
     * holder, so the view model stays the single writer of both: `onOpenSwitcher`
     * (Overview's "create a portfolio" — creation lives in the sheet) and
     * `onOpenPortfolioView` (leave Overview for the selected portfolio's page).
     */
    overviewContent: @Composable (
        onOpenSwitcher: () -> Unit,
        onOpenPortfolioView: () -> Unit,
    ) -> Unit,
) {
    val vm: PortfolioOverviewViewModel = viewModel(initializer = PortfolioOverviewVmInitializer)

    val overviewSelected by vm.overviewSelected.collectAsStateWithLifecycle()
    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    val portfolioKinds by vm.portfolioKinds.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    // The selected portfolio's kind (null on Overview or when unset) drives the
    // header chip's scope hue + glyph (B2-C handover).
    val selectedKindOrNull = if (overviewSelected) null else selected?.id?.let { portfolioKinds[it] }
    val history by vm.history.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val chartMode by vm.chartMode.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val hasEverSynced by vm.hasEverSynced.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val isOnline by vm.isOnline.collectAsStateWithLifecycle()
    val switcherBusy by vm.switcherBusy.collectAsStateWithLifecycle()
    val switcherError by vm.switcherError.collectAsStateWithLifecycle()
    val switcherValueFailed by vm.switcherValueFailed.collectAsStateWithLifecycle()
    val pendingTx by vm.pendingTx.collectAsStateWithLifecycle()

    // Sheet visibility lives in the VM so the shell top-bar selector can open it.
    val switcherOpen by vm.switcherVisible.collectAsStateWithLifecycle()

    // Bottom-bar re-tap on Portfolio opens the switcher (owner directive
    // 2026-08-07), routed through the SAME vm.openSwitcher() the header pill
    // calls — so the sheet, its state and its side effects have exactly one
    // implementation no matter which affordance asked for it. The shell cannot
    // reach this view model, hence the one-shot flag; see [PortfolioTabEntry].
    val pendingSwitcher by PortfolioTabEntry.pendingSwitcher.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSwitcher) {
        if (pendingSwitcher) {
            vm.openSwitcher()
            PortfolioTabEntry.consume()
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose { }
    }

    val bt = BtTheme.colors

    // ── This tab's half of the shared bar (hoist 2026-08-07) ────────────────
    //
    // Portfolio is the only tab whose bar content is not a constant, so it is the
    // only tab that has anything to say up this channel. What it publishes is
    // DATA, not a composable: the shell has to be able to draw this pill while
    // the user is swiping ONTO this tab, at which point this screen is not
    // composed and could not run a slot even if it had passed one.
    //
    // `title`/`titleIcon`/`titleIconTint` are the exact three values the retired
    // local header was assembling; only their destination changed.
    val chrome = LocalBtTabChrome.current
    val overviewLabel = stringResource(R.string.bt_overview_title)
    val fallbackLabel = stringResource(R.string.bt_tab_portfolio)
    val selector = BtTabSelector(
        label = if (overviewSelected) overviewLabel else selected?.name ?: fallbackLabel,
        // The selector's leading glyph is the Portfolio TAB's glyph, on purpose:
        // the pill states which entry of that tab you are in, so wearing the
        // tab's own icon makes the relationship legible without a word.
        icon = selectedKindOrNull?.let { portfolioKindIcon(it) } ?: Icons.Outlined.PieChart,
        // Scope hue per B2-C: a portfolio wears its own kind hue (same as its
        // switcher row); Overview keeps gold by rule — account-wide = brand.
        tint = selectedKindOrNull?.let { portfolioKindTint(it) },
    )
    // A `SideEffect` and not a `LaunchedEffect`: this must land before the frame
    // that composition is producing is drawn, or the bar would render one frame
    // behind the page on every portfolio switch.
    SideEffect { chrome.publishPortfolio(selector, overviewSelected) }

    val pullState = rememberPullToRefreshState()
    // S6 P1-7: the buy/sell FAB sits exactly over the allocation legend's value
    // column, so on a portfolio with more than a couple of slices the reader
    // simply cannot see the last percentages. Rather than inset the legend (which
    // would waste that width on every screen, FAB or no FAB), the FAB gets out of
    // the way while the user scrolls down and comes straight back on the way up.
    val fabVisibility = rememberBtFabVisibility()
    val listState = rememberLazyListState()
    // Back at the very top = nothing to get out of the way of. This also covers
    // the short-list case, where the FAB must never be able to stay hidden.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.collect { atTop -> if (atTop) fabVisibility.show() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // ── Three nested-scroll participants, in this order deliberately ──
            //
            // Compose offers a pre-scroll delta to the OUTERMOST connection first
            // and passes on what is left. The collapsing header CONSUMES delta
            // while it is collapsing; the FAB's connection observes and returns
            // Offset.Zero. So the FAB must be outer: inner, it would see a delta
            // the header had already eaten and could sit under the
            // FAB_SCROLL_THRESHOLD_PX dead band for the whole first 48dp of every
            // drag — i.e. the FAB would stop hiding exactly while the header is
            // doing the thing that makes room. Outer, it sees raw finger movement
            // and the header still gets the full delta afterwards, because the
            // FAB consumed none of it.
            .nestedScroll(fabVisibility.nestedScroll)
            // The bar this tab used to draw lives in the shell now (hoist
            // 2026-08-07): one instance, above everything the swipe moves, so it
            // cannot slide. All that is left here is the connection that lets the
            // shared bar take its tonal lift when this tab's content goes under
            // it. See [at.bettertrack.app.ui.shell.BtTabHeader].
            .nestedScroll(chrome.headerScroll),
    ) {

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (overviewSelected) {
                // Overview brings its own pull-to-refresh and its own scroll
                // container; it is NOT wrapped in this screen's PullToRefreshBox,
                // which refreshes one portfolio's detail/graph/ledger/cash and
                // would be the wrong verb here. Its LazyColumn still drives the
                // collapsing header above, because the nestedScroll connection
                // is on the Column that wraps both branches.
                // Positional because Kotlin forbids named arguments on function
                // types; the parameter names live on the slot's declaration.
                overviewContent(
                    /* onOpenSwitcher = */ { vm.openSwitcher() },
                    /* onOpenPortfolioView = */ { vm.leaveOverview() },
                )
            } else {
                // Step 8 (§6.2): recording a transaction is ≤2 taps from the overview —
                // this FAB opens the buy/sell form directly. It stays the screen's ONLY
                // creation entry; the header deliberately carries no `+`.
                // The app-wide empty-state rule ([fabVisibleForList]): a portfolio
                // with no holdings shows the "record your first buy" empty state,
                // and that state carries the CTA — so the FAB stands down until
                // there is a list to add to. `resolved` is deliberately not just
                // `true`: before the first sync lands, "no holdings" is a thing we
                // have not looked up yet, not an answer.
                val holdingsFabVisible = fabVisibleForList(
                    resolved = hasEverSynced || holdings.isNotEmpty(),
                    empty = holdings.isEmpty(),
                )
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
                            color = bt.goldInk,
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
                            listState = listState,
                            holdings = holdings,
                            history = history,
                            range = range,
                            chartMode = chartMode,
                            pendingTx = pendingTx,
                            onRange = vm::setRange,
                            onChartMode = vm::setChartMode,
                            onOpenHolding = onOpenHolding,
                            onOpenTransactions = onOpenTransactions,
                            onOpenPendingSync = onOpenPendingSync,
                            onOpenCash = onOpenCash,
                            onOpenPortfolioSettings = onOpenPortfolioSettings,
                            onOpenInsights = onOpenInsights,
                            onNewTransaction = onNewTransaction,
                        )
                    }
                }

                selected?.takeIf { holdingsFabVisible }?.let { p ->
                    val fabCd = stringResource(R.string.bt_overview_fab_cd)
                    // Shrinks while scrolling; never leaves. See
                    // [BtFabVisibility.ShrinkingContent] for the ruling.
                    fabVisibility.ShrinkingContent(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(BT_FAB_EDGE_INSET),
                    ) { size ->
                        FloatingActionButton(
                            onClick = { onNewTransaction(p.id) },
                            containerColor = bt.gold,
                            contentColor = bt.onGold,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                            modifier = Modifier
                                .size(size)
                                .semantics { contentDescription = fabCd },
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(btFabIconSize(size)),
                            )
                        }
                    }
                }
                // Overview carries no FAB: "record a transaction" needs one
                // ledger to record INTO, and Overview is deliberately the view
                // that is about all of them at once.
            }
        }
    }

    if (switcherOpen) {
        PortfolioSwitcherSheet(
            portfolios = portfolios,
            kinds = portfolioKinds,
            selectedId = selected?.id,
            overviewSelected = overviewSelected,
            onSelectOverview = {
                vm.selectOverview()
                vm.dismissSwitcher()
            },
            isOnline = isOnline,
            busy = switcherBusy,
            error = switcherError,
            valueFailedIds = switcherValueFailed,
            onDismiss = { vm.dismissSwitcher() },
            onSelect = { id ->
                vm.selectPortfolio(id)
                vm.dismissSwitcher()
            },
            // Closing the sheet before navigating: a modal left standing behind
            // a pushed screen re-appears on back, which reads as the app having
            // undone the navigation.
            onOpenSettings = { id ->
                vm.dismissSwitcher()
                onOpenPortfolioSettings(id)
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
    listState: LazyListState,
    holdings: List<HoldingEntity>,
    history: PortfolioHistory?,
    range: HistoryRange,
    chartMode: BtChartMode,
    pendingTx: List<PendingTxRow>,
    onRange: (HistoryRange) -> Unit,
    onChartMode: (BtChartMode) -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenTransactions: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCash: (String) -> Unit,
    onOpenPortfolioSettings: (String) -> Unit,
    onOpenInsights: (String) -> Unit,
    onNewTransaction: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val totals = portfolio.totals

    // The list's reading order (owner UI batch 2026-08-16): allocation size by
    // default — the DAO's own order — with a Profit re-sort one tap away.
    // `rememberSaveable` so the choice survives rotation; deliberately NOT
    // persisted further: the default is the order the owner named as right.
    var holdingsSort by rememberSaveable { mutableStateOf(HoldingsSort.ALLOCATION) }
    val shownHoldings = remember(holdings, holdingsSort) {
        sortedHoldings(holdings, holdingsSort)
    }

    // W6: true when this mode has no live quotes, so an absent price is a state
    // the user can act on rather than a transient server gap.
    val storedMode by at.bettertrack.app.di.AppGraph.storageModeStore.mode
        .collectAsStateWithLifecycle()
    val noLivePrices = remember(storedMode) {
        at.bettertrack.app.ui.prices.manualEntryAvailable(
            at.bettertrack.app.di.AppGraph.gatedStorageMode(storedMode),
        )
    }

    // How much of this portfolio could be priced at all. Hoisted to the content
    // scope because three separate places need it — the hero, its day-change
    // sub-line and the holdings-value roll-up — and all three must agree.
    // `remember`ed on the list because it is an O(n) count and this scope
    // recomposes for reasons that have nothing to do with the holdings (this is
    // what HomeScreen already does with the identical call).
    val coverage = remember(holdings) { at.bettertrack.app.ui.prices.priceCoverage(holdings) }

    // Scrub state is hoisted here so touching the hero chart updates the big
    // Net-Worth readout (Robinhood-style). A fresh selection/range clears it.
    var scrub by remember { mutableStateOf<HistoryPoint?>(null) }
    LaunchedEffect(portfolio.id, range) { scrub = null }
    // Same shape: the pending strip's promotion is an O(n) count that gates two
    // `item {}` declarations, so it ran on every rebuild of the item provider.
    val attention = remember(pendingTx) {
        pendingTx.count { it.status == PendingUiStatus.NEEDS_ATTENTION }
    }

    // Content is inset 16dp — EXCEPT the hero chart, which bleeds edge-to-edge.
    val inset = Modifier.fillMaxWidth().padding(horizontal = 16.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            // Clear the buy/sell FAB so the LAST holding row scrolls fully into
            // view instead of stopping under it. Deliberately `contentPadding`
            // and not a viewport inset — see [BT_FAB_CONTENT_CLEARANCE] for the
            // 2026-08-17 experiment that proved the inset worse than the
            // overlap it removed.
            bottom = BT_FAB_CONTENT_CLEARANCE,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Net-Worth hero (server totals only) — scrub-aware: while scrubbing the
        // chart, the big number + label become the touched point's value + date.
        item(key = "hero", contentType = "hero") {
            val s = scrub
            // Discreet mode: press and hold the hero to peek at the real numbers,
            // release to re-hide. Only armed while masking, so it costs a normal
            // user nothing — and it is bound to the gesture, never a latch that
            // could be left on by accident.
            val heroPeek = if (BtDiscreetMode.enabled) {
                Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            BtDiscreetMode.setRevealing(true)
                            try {
                                awaitRelease()
                            } finally {
                                BtDiscreetMode.setRevealing(false)
                            }
                        },
                    )
                }
            } else {
                Modifier
            }
            Column(inset.then(heroPeek)) {
                Text(
                    text = if (s != null) {
                        formatChartScrubDate(s.epochMillis, history?.isSubDaily == true, locale)
                    } else {
                        stringResource(R.string.bt_overview_net_worth)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(2.dp))
                // ── W6: never a €0 lie ──────────────────────────────────────
                //
                // `PortfolioTotals` is non-nullable and the vault projector sums
                // unpriced holdings as `?: 0.0`, so a Drive user with nothing
                // priced and no cash arrives here with a perfectly confident
                // 0.00. The coverage is recovered from the holdings this screen
                // already has, and decides whether a figure may be shown at all.
                val worth = totals?.let {
                    at.bettertrack.app.ui.prices.netWorthState(
                        totalValueEur = it.totalValueEur,
                        cashEur = it.cashEur,
                        coverage = coverage,
                    )
                }
                if (s == null && worth is at.bettertrack.app.ui.prices.NetWorthState.Unpriceable) {
                    at.bettertrack.app.ui.prices.NoPricesHero()
                } else if (totals != null || s != null) {
                    // What the scrub puts in the headline slot depends on the
                    // chart's mode (owner ask 2026-08-07):
                    //  · BALANCE  — the touched point IS the balance.
                    //  · HYBRID   — the curve is %, but the headline is the €
                    //    balance at that moment, looked up from the € series the
                    //    same payload shipped. That is the whole point of the mode.
                    //  · PERFORMANCE — the touched point is the return, and the
                    //    headline says so rather than pretending to be money.
                    val scrubEur = when {
                        s == null -> null
                        chartMode == BtChartMode.PERFORMANCE -> null
                        chartMode == BtChartMode.HYBRID ->
                            history?.points?.let { balanceAt(it, s.epochMillis) }
                        else -> s.valueEur
                    }
                    if (s != null && chartMode == BtChartMode.PERFORMANCE) {
                        Text(
                            text = formatPercent(s.valueEur, locale),
                            style = BtTheme.type.moneyLarge,
                            // Reachable only in PERFORMANCE, so this is the one
                            // headline that keeps its verdict colour — routed
                            // through the gate anyway so every tint on this
                            // surface has exactly one owner.
                            color = deltaTint(chartMode, s.valueEur),
                        )
                    } else {
                        MoneyText(
                            value = scrubEur ?: totals!!.totalValueEur,
                            style = BtTheme.type.moneyLarge,
                        )
                    }
                    if (s == null) {
                        at.bettertrack.app.ui.prices.UnpricedNote(
                            coverage = coverage,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Reserve the sub-line height so scrubbing never shifts layout.
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        // Hybrid scrub: the headline is the € balance, so the
                        // return the curve is actually drawing goes here — the
                        // mode exists to show both at once, and hiding one of
                        // them would make it just a re-labelled % mode.
                        if (s != null && chartMode == BtChartMode.HYBRID) {
                            Text(
                                text = formatPercent(s.valueEur, locale),
                                style = BtTheme.type.numberCaption,
                                // Neutral, matching the curve it annotates.
                                // Hybrid carries no red/green anywhere (owner
                                // order 2026-08-07) — a green number under a
                                // gold curve would re-introduce, in the one
                                // place it is most closely read, exactly the
                                // verdict-colouring the mode is meant not to do.
                                color = bt.textSecondary,
                            )
                        }
                        // ── The consolidated delta line (owner UI batch 2026-08-16) ──
                        //
                        // ONE statement about the selected window, directly above
                        // the chart that draws it: "+120 € (2,1 %) · past month".
                        // It replaced two half-statements — the fixed day-change
                        // line here and a "+x % 1M" readout beside the retired
                        // mode row — that each told part of the story in a
                        // different corner.
                        //
                        //  · 1D: the server's own day change (€ AND %), verbatim.
                        //  · Every other window: the server's range performance %
                        //    plus the € difference of the first and last points
                        //    of the server's balance series — a DISPLAY
                        //    subtraction of two server values, the same standing
                        //    `weightPct` has (§7.1: no derived performance, and
                        //    this derives none).
                        //
                        // Sign-coloured by owner order ("money and percent
                        // colored emerald/red"), deliberately NOT `deltaTint`:
                        // this line is the page's one verdict and keeps it in
                        // every chart mode. The window is a WORD ([rangeWord]),
                        // never the picker's `1M` shorthand.
                        //
                        // W6 guard unchanged: with nothing priced, a delta of
                        // summed zeroes would claim "no movement" when the truth
                        // is "not known".
                        if (s == null && totals != null && !coverage.nothingPriced) {
                            val deltaEur: Double?
                            val deltaPct: Double?
                            if (range == HistoryRange.D1) {
                                deltaEur = totals.dayChangeEur
                                deltaPct = totals.dayChangePct
                            } else {
                                deltaEur = remember(history) {
                                    rangeDeltaEur(history?.points.orEmpty())
                                }
                                deltaPct = history?.rangePerformancePct
                            }
                            if (deltaEur != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MoneyText(
                                        value = deltaEur,
                                        style = BtTheme.type.numberCaption,
                                        color = deltaColor(deltaEur),
                                        showSign = true,
                                    )
                                    deltaPct?.let { pct ->
                                        Text(
                                            text = if (samePairBasis(range)) {
                                                " (${formatPercent(pct, locale)})"
                                            } else {
                                                " · " + formatPercent(pct, locale)
                                            },
                                            style = BtTheme.type.numberCaption,
                                            color = deltaColor(pct),
                                        )
                                    }
                                    Text(
                                        text = " · " + rangeWord(range),
                                        style = BtTheme.type.numberCaption,
                                        color = bt.textMuted,
                                    )
                                }
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

        // The pending strip's ONE promotion (mandate §3 vs §7.4): a queued change
        // the server refused is not status, it is work, and work belongs next to
        // the number it is about to change. Everything merely waiting to upload
        // is status and lives at the bottom of the screen.
        if (attention > 0) {
            item(key = "pending-attention", contentType = "pending") {
                Box(inset) { PendingStrip(pendingTx = pendingTx, onClick = onOpenPendingSync) }
            }
        }

        // History graph (§3.6) — blended full-bleed hero: no card, gold gradient
        // fading into the page, edge-to-edge, minimal axis. Header + range chips
        // stay inset; only the canvas bleeds.
        item(key = "chart", contentType = "chart") {
            HeroChart(
                history = history,
                range = range,
                mode = chartMode,
                onRange = onRange,
                onMode = onChartMode,
                onScrub = { scrub = it },
            )
        }

        // Cash + Transactions — BELOW the graph (owner order 2026-08-08).
        //
        // They arrived above it a day earlier for the fast access ("I liked the
        // fast access"), which they keep: this is still the top of the screen,
        // still one scroll-free tap, still far above where they used to live —
        // under every holding, i.e. off-screen on any real portfolio.
        //
        // What moving them fixes is what they were standing between. The
        // net-worth number and the curve that explains it are one statement, and
        // a pair of navigation chips wedged between them made the reader step
        // over a nav control to get from the figure to its history. Now the
        // hero reads value → curve → the window that curve covers, and the two
        // links sit after it as what they are: where you go NEXT, not part of
        // what you are looking at.
        //
        // They also stay clear of the range picker's vocabulary. Directly under
        // a segmented track, two chips would have re-run the same "are these one
        // control or two?" collision the range row was just rescued from — but
        // these are full-width halves carrying an icon and a value, not pills in
        // a groove, and the list's 12dp gap separates the groups.
        item(key = "quick-access", contentType = "quick-access") {
            // ONE geometry for both chips (owner report 2026-08-08: "they render
            // unequal"). They already shared a width — `weight(1f)` each — but
            // not a height: Cash carries a value and is a two-line stack, while
            // Transactions is a label alone, so each took its own intrinsic
            // height and the pair sat in the row like two different components.
            //
            // `IntrinsicSize.Min` on the row makes the row as tall as the
            // TALLER chip wants to be, and `fillMaxHeight` makes the other one
            // match. Measured, not hard-coded, so the pair stays square at every
            // system font scale — a fixed dp height would equalise them today
            // and clip the money at 1.3x tomorrow.
            Row(
                modifier = inset.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickStatChip(
                    label = stringResource(R.string.bt_overview_cash),
                    value = totals?.cashEur,
                    icon = Icons.Outlined.AccountBalanceWallet,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onOpenCash(portfolio.id) },
                )
                QuickStatChip(
                    label = stringResource(R.string.bt_tx_title),
                    value = null,
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onOpenTransactions(portfolio.id) },
                )
            }
        }

        // The allocation summary LEFT this page (owner UI batch 2026-08-16): it
        // now lives on the insights subpage behind the "More insights" row at
        // the bottom, so the overview reads value → curve → positions with
        // nothing between the list and the number it explains.

        // Holdings — the thing this screen is for, immediately after the value
        // (mandate §3). The header row carries the sort toggle (owner UI batch
        // 2026-08-16): Allocation | Profit, in the same segmented vocabulary as
        // the chart's range picker, so "one of these orders is on" reads the
        // same way "one of these windows is on" does.
        item(key = "holdings-header", contentType = "section-header") {
            Column(inset.padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Deliberately NOT quietened. A pass at demoting this section
                    // shrank this header to `titleSmall`/`textSecondary`; the
                    // owner then specified the method (2026-08-17) and it was
                    // "weaken the holdings' background colour", not "shrink the
                    // header". The whole demotion is spent on [HoldingRow]'s
                    // surface, so nothing here has to move.
                    Text(
                        text = stringResource(R.string.bt_overview_holdings_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = bt.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (holdings.size > 1) {
                        val sortCd = stringResource(R.string.bt_holdings_sort_cd)
                        BtSegmented(
                            options = HOLDINGS_SORTS,
                            selected = holdingsSort,
                            label = { stringResource(holdingsSortLabel(it)) },
                            onSelect = { holdingsSort = it },
                            modifier = Modifier.semantics { contentDescription = sortCd },
                        )
                    }
                }
                // W6, inherited from the deleted "Holdings value" roll-up: with
                // nothing priced, the rows below all read "No price yet" and the
                // section says why once, here, instead of the card that used to
                // print an empty sum 200px above the list it summed.
                if (coverage.nothingPriced) {
                    Text(
                        text = stringResource(R.string.bt_price_none_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = bt.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (holdings.isEmpty()) {
            item(key = "holdings-empty", contentType = "empty") {
                Box(inset) {
                    // The FAB stands down while this is on screen, so this
                    // button is the portfolio's single "record a transaction"
                    // entry point — the empty state has to carry it.
                    BtEmptyState(
                        icon = Icons.Outlined.PieChart,
                        title = stringResource(R.string.bt_overview_no_holdings_title),
                        message = stringResource(R.string.bt_overview_no_holdings_message),
                        action = {
                            BtPrimaryButton(
                                text = stringResource(R.string.bt_overview_fab_cd),
                                onClick = { onNewTransaction(portfolio.id) },
                            )
                        },
                    )
                }
            }
        } else {
            items(
                count = shownHoldings.size,
                key = { "holding-" + shownHoldings[it].assetId },
                // Every `item {}` above declares its own contentType too. Without
                // them the whole list shared ONE reuse pool, so a holding row
                // could be handed a retained slot that last held the 150dp chart
                // or the hero — structures that cannot be reused, which makes
                // LazyLayout deactivate and fully recompose instead. The pool
                // then costs bookkeeping and pays nothing back (perf pass
                // 2026-08-06).
                contentType = { "holding" },
            ) { index ->
                val h = shownHoldings[index]
                Box(inset) {
                    HoldingRow(
                        holding = h,
                        locale = locale,
                        noLivePrices = noLivePrices,
                        onClick = { onOpenHolding(h.assetId) },
                    )
                }
            }
        }

        // Cash and transactions, demoted from 50/50 roll-up cards to secondary
        // rows (mandate §3: "cash/source metadata demoted into rows' secondary
        // lines"). They are also the in-content second path for the two entries
        // the header's ⋮ carries — overflow is a shortcut, never the only way.
        item(key = "secondary-rows", contentType = "secondary") {
            Column(inset.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Cash and Transactions are NOT repeated here: they moved to the
                // quick-access chips under the hero (owner ask 2026-08-07). Two
                // paths to one screen on one page is how a page starts feeling
                // padded, and the top one is the one he asked for.
                // Per-portfolio settings — name, sharing, taxes, group,
                // archive/delete. It belongs in this group and NOT behind the
                // header gear: the gear is the app's one fixed landmark and
                // means "the app's settings". A second gear meaning "this
                // portfolio's settings" would make the landmark ambiguous, which
                // is exactly the failure the nav restoration removed.
                // The insights subpage's door (owner UI batch 2026-08-16): the
                // allocation section that used to sit mid-page, plus whatever
                // insight modules come later. A peer of Portfolio settings —
                // both are "about this portfolio" rows, not positions.
                SecondaryRow(
                    label = stringResource(R.string.bt_overview_more_insights),
                    value = null,
                    onClick = { onOpenInsights(portfolio.id) },
                )
                SecondaryRow(
                    label = stringResource(R.string.bt_psettings_row),
                    value = null,
                    onClick = { onOpenPortfolioSettings(portfolio.id) },
                )
            }
        }

        // Pending changes (§7.1/§7.4): queued entries live ALONGSIDE the
        // server-computed numbers, clearly marked, and open the Pending-sync
        // screen — never folded into the totals. Below the holdings unless
        // something in them needs attention, in which case it is already above.
        if (pendingTx.isNotEmpty() && attention == 0) {
            item(key = "pending-strip", contentType = "pending") {
                Box(inset) { PendingStrip(pendingTx = pendingTx, onClick = onOpenPendingSync) }
            }
        }
    }
}

// `PortfolioOverflow` is deleted (nav restoration 2026-08-06). Its four entries
// and their in-content twins:
//   Transactions      -> the "Transactions" row under the holdings ([SecondaryRow])
//   Cash              -> the "Cash" row under the holdings, carrying the balance
//   Pending sync      -> [PendingStrip], which appears on exactly the same
//                        condition the menu entry used to be gated on
//   Manage portfolios -> tapping the selector pill, which is now drawn as a
//                        button and is the screen's most obvious control
// See the `settings =` comment on this screen's header.

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
 * chart sits directly on the page (no card), the gradient fades into the
 * background, and the canvas bleeds edge-to-edge. Only the control rows stay
 * inset; the chart itself is full-width with minimal scaffolding. Scrubbing is
 * reported up so the Net-Worth hero shows the touched point.
 *
 * ## The three display modes (owner ask 2026-08-07)
 *
 * The picker above the chart chooses what the curve is, out of the two series the
 * `/history` payload already carries together — so switching is instant and never
 * refetches. See [BtChartMode] for what each one means. The hybrid mode is the
 * owner's: *the % shape, because that is the shape that says how the investments
 * did, with the € balance as the readout, because that is the number you want
 * when you point at a day.*
 *
 * ## The controls sit BELOW the canvas, side by side (owner order 2026-08-16)
 *
 * *"Move the €/% toggle and the range selector side-by-side, below the chart."*
 * Their order was reversed to range-left on 2026-08-17 and reverted the same
 * evening (*"timespans is back on the right it looked better"*), so it is mode
 * left, range right — as originally shipped.
 * What made that impossible on 2026-08-08 — nine segments of fixed geometry
 * against 328dp of content width — is not the situation any more: 6M left the
 * range set (same batch), and the mode picker gives up its content-sized 46dp
 * pills for a compact equal-width track of a fixed, font-scaled width. Five
 * range segments then divide the remainder. Whether the pair actually fits is
 * MEASURED per composition ([chartControlsFitSideBySide], pinned by unit test):
 * whenever the widest range label would lose its breathing room — accessibility
 * font scales, narrow windows — the two controls stack instead, mode above
 * range, rather than squeezing or scrolling. Nothing is ever clipped and no
 * window ever hides behind a swipe.
 *
 * The range-% readout that lived beside the retired top row is gone with it:
 * the hero's delta line above the canvas now states €, % and the window in
 * words, in one place.
 */
@Composable
private fun HeroChart(
    history: PortfolioHistory?,
    range: HistoryRange,
    mode: BtChartMode,
    onRange: (HistoryRange) -> Unit,
    onMode: (BtChartMode) -> Unit,
    onScrub: (HistoryPoint?) -> Unit,
) {
    val bt = BtTheme.colors
    val chartCd = stringResource(R.string.bt_overview_chart_cd)
    Column(Modifier.fillMaxWidth()) {
        // Which series the curve draws. The performance modes plot `pct` through
        // the same [HistoryPoint] shape the chart already speaks — the chart maps
        // numbers to pixels and has no opinion about their unit; the readout
        // below, and [BtChartMode], own the meaning.
        val points = remember(history, mode) {
            if (mode.plotsPerformance) {
                history?.performance.orEmpty().map { HistoryPoint(it.epochMillis, it.pct) }
            } else {
                history?.points.orEmpty()
            }
        }
        if (points.size >= 2) {
            BtAreaChart(
                points = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HERO_CHART_HEIGHT)
                    .semantics { contentDescription = chartCd },
                // The hero is the brand gold in BOTH modes (§4.3 + owner order
                // 2026-08-07). It is 1.78:1 on a white card, i.e. under even the
                // 3:1 graphical floor, and that is the decision rather than an
                // oversight: this line went through `goldEmphasis` (the 4.5:1
                // text ink) and then `goldGraphic` (an on-ray 3:1 darkening), and
                // the owner rejected both as rust. What makes it read in light is
                // GEOMETRY — `chartLineWidth` is 3dp there against dark's 2dp and
                // `chartAreaTopAlpha` is heavier — never a darker hue.
                //
                // Passing `gold` itself also repairs a quiet defect: `wash()`
                // keys its light gold correction off `hue == gold`, so while this
                // said `goldGraphic` the hero's area fill was taking the ×0.86
                // ACCENT attenuation instead of the ×1.16 gold gain — the one
                // chart in the app whose fill was being thinned rather than
                // strengthened on white.
                lineColor = bt.gold,
                minimal = true,
                baseline = mode.plotsPerformance,
                // Only the pure % mode paints its verdict (owner order
                // 2026-08-07). Hybrid keeps the zero-baseline GEOMETRY — the
                // rule line and the fill mirrored about zero, which is what a
                // return series needs — but draws it in the brand gold, because
                // its headline is the € balance and colouring one quantity by
                // the sign of another is just a wrong statement.
                colorBySign = mode.colorsBySign,
                onScrub = onScrub,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(HERO_CHART_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                if (history == null) {
                    BtSkeleton(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp))
                } else {
                    // BtInlineEmpty, not BtEmptyState: an absent chart is an
                    // answer rather than a failure, so it gets the calm one-line
                    // form, inset to the page gutter.
                    BtInlineEmpty(
                        text = stringResource(R.string.bt_overview_chart_empty),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Both pickers, one row, below the canvas — see the KDoc.
        ChartControls(
            mode = mode,
            range = range,
            onMode = onMode,
            onRange = onRange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

/**
 * The chart's two pickers as one control row (owner order 2026-08-16): the
 * display-mode toggle on the left, the range selector filling the rest — or,
 * when the measured arithmetic says the range labels would suffocate, the same
 * two controls stacked. See [HeroChart]'s KDoc for the decision and
 * [chartControlsFitSideBySide] for the arithmetic.
 */
@Composable
private fun ChartControls(
    mode: BtChartMode,
    range: HistoryRange,
    onMode: (BtChartMode) -> Unit,
    onRange: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modeCd = stringResource(R.string.bt_chart_mode_cd)
    val rangeCd = stringResource(R.string.bt_chart_range_cd)
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceIn(CHART_MODE_SEGMENT_SCALE_RANGE)
    // The compact mode track: a FIXED width divided equally by its three
    // one-glyph segments, so the row's arithmetic has one unknown (the labels'
    // width) instead of three.
    val modeWidth = CHART_MODE_COMPACT_WIDTH * fontScale

    // Measured in the widest state (SemiBold winner), like BtRangeSegmented.
    val style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val rangeLabels = PORTFOLIO_RANGES.map { rangeLabel(it) }
    val widestRangeDp = remember(rangeLabels.joinToString(" "), density.density, density.fontScale) {
        with(density) {
            rangeLabels.maxOfOrNull { measurer.measure(it, style).size.width.toDp().value } ?: 0f
        }
    }

    BoxWithConstraints(modifier) {
        val sideBySide = chartControlsFitSideBySide(
            availableWidthDp = this.maxWidth.value,
            modeTrackWidthDp = modeWidth.value,
            widestRangeLabelDp = widestRangeDp,
        )
        if (sideBySide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(CHART_CONTROLS_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mode LEFT, range RIGHT. Swapped to range-left on 2026-08-17
                // on his instruction and swapped straight back the same evening
                // — *"swap the €% € % with the timespans again. so timespans is
                // back on the right it looked better."* Recorded rather than
                // silently reverted so the next person does not re-propose it.
                ChartModePicker(
                    mode = mode,
                    onMode = onMode,
                    modifier = Modifier
                        .width(modeWidth)
                        .semantics { contentDescription = modeCd },
                    equalWidths = true,
                )
                BtSegmented(
                    options = PORTFOLIO_RANGES,
                    selected = range,
                    label = { rangeLabel(it) },
                    onSelect = onRange,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = rangeCd },
                    equalWidths = true,
                )
            }
        } else {
            // The accessibility fallback: same controls, stacked — never
            // squeezed labels, never a scrolling range row. Same reading order
            // as the row above: mode first, range second.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChartModePicker(
                    mode = mode,
                    onMode = onMode,
                    modifier = Modifier.semantics { contentDescription = modeCd },
                    minSegmentWidth = CHART_MODE_SEGMENT_MIN_WIDTH * fontScale,
                )
                BtRangeSegmented(
                    options = PORTFOLIO_RANGES,
                    selected = range,
                    label = { rangeLabel(it) },
                    onSelect = onRange,
                    modifier = Modifier.fillMaxWidth(),
                    contentDescription = rangeCd,
                )
            }
        }
    }
}

/** The mode toggle itself — one definition for both ChartControls layouts. */
@Composable
private fun ChartModePicker(
    mode: BtChartMode,
    onMode: (BtChartMode) -> Unit,
    modifier: Modifier = Modifier,
    equalWidths: Boolean = false,
    minSegmentWidth: Dp = Dp.Unspecified,
) {
    BtSegmented(
        options = CHART_MODES,
        selected = mode,
        // No string labels: all three segments are drawn marks.
        label = null,
        onSelect = onMode,
        modifier = modifier,
        contentDescription = { stringResource(chartModeContentDescription(it)) },
        // A slot rather than a plain string, because the combined mode's label
        // is a composed MARK (`€%`, tightened) and not a word — [ChartModeLabel].
        labelContent = { ChartModeLabel(it) },
        equalWidths = equalWidths,
        minSegmentWidth = minSegmentWidth,
    )
}

/**
 * Whether the two chart pickers may share one row.
 *
 * True when the five range segments' equal share of what the mode track leaves
 * over still clears the widest range label with [CHART_CONTROLS_RANGE_BREATHING]
 * to spare per side. Pure arithmetic on the segmented control's own geometry
 * constants — unit-tested, so "it fits on a 360dp phone" is a checked claim
 * rather than a screenshot.
 */
internal fun chartControlsFitSideBySide(
    availableWidthDp: Float,
    modeTrackWidthDp: Float,
    widestRangeLabelDp: Float,
): Boolean {
    val rangeWidthDp = availableWidthDp - modeTrackWidthDp - CHART_CONTROLS_GAP.value
    val share = equalSegmentShareDp(rangeWidthDp, PORTFOLIO_RANGES.size)
    return share >= widestRangeLabelDp + 2 * CHART_CONTROLS_RANGE_BREATHING.value
}

/**
 * The compact mode track's width at `fontScale` 1.0. Three equal segments of
 * ~31dp — comfortably above the `€%` mark's ~25dp need under the equal-width
 * policy's 4dp side padding, compact enough to leave five range segments a
 * ~40dp share on a 360dp-class phone (328dp of content width).
 */
private val CHART_MODE_COMPACT_WIDTH = 105.dp

/** The gap between the two tracks — the quick-access chips' 8dp rhythm. */
internal val CHART_CONTROLS_GAP = 8.dp

/**
 * The air a range label must keep per side for the pair to stay on one row.
 * Matches the equal-width segment's own 4dp padding: at the threshold the label
 * touches its padding, never its pill. Below it the controls stack.
 */
internal val CHART_CONTROLS_RANGE_BREATHING = 4.dp

/**
 * The € balance the server reported closest to [epochMillis].
 *
 * Used by the hybrid chart mode, where the curve is the performance series and
 * the readout has to come from the balance series beside it. The two arrive in
 * one `/history` payload and are aligned by construction, but they are parsed
 * independently and nothing guarantees equal lengths — so this matches on TIME
 * rather than trusting a shared index.
 *
 * It returns a point the server actually sent; it never interpolates between two
 * of them (§7.1 — the app does not invent money). Pure, so the nearest-match rule
 * is unit-tested rather than eyeballed on a device.
 */
internal fun balanceAt(points: List<HistoryPoint>, epochMillis: Long): Double? =
    points.minByOrNull { kotlin.math.abs(it.epochMillis - epochMillis) }?.valueEur

/**
 * The € change across a server balance series — its last point minus its first,
 * for the hero's delta line (owner UI batch 2026-08-16).
 *
 * A DISPLAY difference of two server-reported values, the same standing
 * [weightPct] has: nothing is re-derived, no path is interpolated, and the two
 * operands are rendered by the very chart underneath the line. Null when the
 * series cannot carry a difference (fewer than two points), so the line simply
 * stays silent instead of claiming ±0.
 */
internal fun rangeDeltaEur(points: List<HistoryPoint>): Double? =
    if (points.size < 2) null else points.last().valueEur - points.first().valueEur

/**
 * Whether the delta line's € and % are two views of ONE quantity — and may
 * therefore be written as `+12,30 € (+0,8 %)` — or two different measurements
 * that merely share a window.
 *
 * ## The bug this closes
 *
 * The line shipped as `€ (%)` for every window, and for every window except 1D
 * that parenthesis was a false claim. The two numbers come from different server
 * series with **different bases**:
 *
 *  · The € is the change in the `points` series, which is NET WORTH — holdings
 *    plus cash. A deposit moves it by exactly the amount deposited.
 *  · The % is the last value of the `performance` series, which the platform
 *    computes as a chain-linked daily **time-weighted return** over external
 *    cash flows: a 1 000 € deposit causes no jump at all, by design.
 *
 * So a portfolio that received 3 000 € and barely moved reads `+3 004 €` beside
 * `+0,85 %`, and the bracket asserts that the first is the second expressed
 * differently. It is not. Roughly 3 000 € of that was contributed, not earned.
 *
 * ## Why the fix is punctuation and not arithmetic
 *
 * The two obvious "make them agree" repairs are both worse. Deriving the € from
 * the return would invent money the platform never computed — it publishes no
 * per-range EUR figure on any endpoint. Deriving the % from the balance series
 * would have the app publish a performance number that CONTRADICTS the server's
 * own, and contradicts the very curve the chart draws in % mode; the server owns
 * that answer (§7.1) and overriding it is the worse sin, not the lesser one.
 *
 * So both numbers stay exactly as the server reported them and the punctuation
 * stops lying: `+3 004,07 € · +0,85 % · letzter Monat` — three statements about
 * one window, in the same separator the window itself already uses. 1D keeps the
 * bracket, because there the pair really is one quantity: `dayChangePct` is
 * `dayChangeEur` over the same holdings' prior close, computed together by the
 * server.
 */
internal fun samePairBasis(range: HistoryRange): Boolean = range == HistoryRange.D1

/**
 * The display modes in picker order: **combined → € → %** (owner order
 * 2026-08-08, moving the combined mode from last to first).
 *
 * The previous order sorted by what each mode shows ("money, return, or both"),
 * which put the DEFAULT — the mode almost every session actually opens in — at
 * the far end of the control. Leading with it means the selected pill is where
 * the eye lands, and the two single-unit modes read as the ways to narrow it
 * down. The two of them keep their relative order, so the only thing that moved
 * is the combined mode.
 *
 * ## Why reordering this list is safe
 *
 * It is a DISPLAY order and nothing else. The preference is stored by enum NAME
 * (`chartModeFromName` / `setChartMode`), the enum's own declaration order is
 * untouched, and no code maps a segment INDEX to a mode — [BtSegmented] hands
 * back the option object it was given. Pinned by `ChartModeTest`.
 *
 * `internal` so that order is a tested fact rather than a thing the picker
 * happens to do, the same reason [balanceAt] is.
 */
internal val CHART_MODES = listOf(BtChartMode.HYBRID, BtChartMode.BALANCE, BtChartMode.PERFORMANCE)

/**
 * The windows the PORTFOLIO hero offers — [HistoryRange] minus 6M (owner order
 * 2026-08-16: *"remove the 6M range option"*). A display list, not a wire
 * change: the enum keeps the window because the platform still serves it and
 * the widgets still request it; only this picker stopped offering it. The
 * default (1M) is unaffected, and the range state is session-local, so nobody
 * can be stranded ON 6M. Pinned by `ChartRangeTest`.
 */
internal val PORTFOLIO_RANGES: List<HistoryRange> = listOf(
    HistoryRange.D1,
    HistoryRange.W1,
    HistoryRange.M1,
    HistoryRange.Y1,
    HistoryRange.MAX,
)

/** The holdings sort toggle's options, in display order (default first). */
internal val HOLDINGS_SORTS = listOf(HoldingsSort.ALLOCATION, HoldingsSort.PROFIT)

/** The segment label for a holdings sort. `internal` so the mapping is testable. */
internal fun holdingsSortLabel(sort: HoldingsSort): Int = when (sort) {
    HoldingsSort.ALLOCATION -> R.string.bt_holdings_sort_allocation
    HoldingsSort.PROFIT -> R.string.bt_holdings_sort_profit
}

/**
 * Every segment is one glyph wide, so the row is pinned to a common floor rather
 * than left to three different content widths (`€` ≈ 7dp, `%` ≈ 10dp, `€%` ≈
 * 17dp at `labelMedium`, each inside the segment's 14dp side padding).
 *
 * 46dp clears the widest of the three by a hair at `fontScale` 1.0, so all three
 * pills land on the floor and are exactly equal. See
 * [CHART_MODE_SEGMENT_SCALE_RANGE] for what happens either side of 1.0.
 */
private val CHART_MODE_SEGMENT_MIN_WIDTH = 46.dp

/**
 * How far the floor above is allowed to follow the system font scale.
 *
 * It has to follow it UP, or large text outgrows the floor and the widest label
 * pushes its own pill out of line. It must not follow it DOWN — the labels shrink
 * but the segment's fixed 14dp padding does not, so a floor scaled to 0.85 would
 * drop below the `€%` segment's actual content and re-ragged the row it exists to
 * even out. And it stops following at 1.3, because three pills scaled to a 2.0
 * accessibility font would be ~92dp each and crowd the range readout off the row
 * they share; past that the combined pill may run a dp or two wide, which is
 * invisible next to the alternative.
 */
private val CHART_MODE_SEGMENT_SCALE_RANGE = 1f..1.3f

/**
 * The combined mode's mark, tightened by this much.
 *
 * `sp`, deliberately: the tracking has to scale with the label it tightens, and
 * an `em` value inside a style whose size comes from the theme is exactly the
 * unit mix that has crashed this app before.
 */
private val CHART_MODE_HYBRID_TRACKING = (-0.6).sp

/** The segment label for a mode. `internal` so the mapping is testable. */
internal fun chartModeLabel(mode: BtChartMode): Int = when (mode) {
    BtChartMode.BALANCE -> R.string.bt_chart_mode_balance
    BtChartMode.PERFORMANCE -> R.string.bt_chart_mode_performance
    BtChartMode.HYBRID -> R.string.bt_chart_mode_hybrid
}

/** The spoken form — the labels are currency/percent glyphs and do not read aloud. */
internal fun chartModeContentDescription(mode: BtChartMode): Int = when (mode) {
    BtChartMode.BALANCE -> R.string.bt_chart_mode_balance_cd
    BtChartMode.PERFORMANCE -> R.string.bt_chart_mode_performance_cd
    BtChartMode.HYBRID -> R.string.bt_chart_mode_hybrid_cd
}

/**
 * A mode's label as the picker draws it.
 *
 * The combined mode's label used to be `€ / %` — three glyphs and two spaces,
 * five times the width of the `€` beside it, in a control where the other two
 * options are one character each (owner, 2026-08-08: *"give it a single char not
 * three"*).
 *
 * What ships is `€%`: the two units it combines, set tight enough
 * ([CHART_MODE_HYBRID_TRACKING]) to read as one mark rather than two labels. It
 * is language-neutral, it needs no new icon vocabulary, and it says what the mode
 * is out of the vocabulary the other two segments already established.
 *
 * ## Why not the stacked fraction
 *
 * The first design was a `½`-style composed glyph — `€` over `%` about a fraction
 * slash, one character cell. It does not survive this size. A real fraction keeps
 * its total ink inside ONE line box, which at `labelMedium`'s 12sp puts each part
 * at ~0.58× ≈ 7sp: the `€`'s two crossbars and the `%`'s two counters both close
 * up at that size on a phone. The alternatives were parts at a legible ~9.5sp in
 * a cell ~1.4 line-heights tall, which makes this one segment visibly bigger than
 * its neighbours and grows the whole control, or a fraction whose parts are
 * mush. Neither is better than two crisp full-size glyphs, so the owner's stated
 * fallback is what shipped.
 *
 * The style and the ink come from [BtSegmented] itself — this only says WHAT to
 * draw, never in which state.
 */
@Composable
private fun ChartModeLabel(mode: BtChartMode) {
    Text(
        text = stringResource(chartModeLabel(mode)),
        letterSpacing = if (mode == BtChartMode.HYBRID) CHART_MODE_HYBRID_TRACKING else TextUnit.Unspecified,
    )
}

/**
 * A quick-access stat chip: icon, label, and the number if there is one.
 *
 * The owner's *"I liked the fast access"* about Cash, answered without going back
 * to the 50/50 hero cards the R-arc removed. A chip states its value the way a
 * card does but costs a row rather than a block, so two of them fit side by side
 * directly under the net-worth hero and still leave the chart the page.
 *
 * A chip with no number (Transactions) is still a chip rather than a plain link:
 * the pair reads as one control group, and making them different shapes would
 * say they lead somewhere different in kind, which they do not.
 *
 * ## Deliberately untouched (owner, 2026-08-17)
 *
 * The first reading of *"make sure that the quick links … get more attention then
 * the holdings"* was to grow this chip. He corrected it: *"my idea was to make the
 * holdings less important and keep the 2 quick links as it is."* So the pair's
 * rank is bought entirely by [HoldingRow] and the holdings header standing DOWN —
 * nothing here moved. Do not "restore" a louder chip.
 */
@Composable
private fun QuickStatChip(
    label: String,
    value: Double?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = BtShapes.card,
        color = bt.surface,
        contentColor = bt.textSecondary,
        border = BorderStroke(1.dp, bt.border),
        interactionSource = interaction,
        modifier = modifier.btPressScale(interaction),
    ) {
        Row(
            // Fills whatever height the row hands down (see the call site's
            // `IntrinsicSize.Min`), so the one-line chip CENTRES its label
            // against the two-line one instead of hanging from the top edge.
            // Equal boxes with unequal content alignment would have swapped one
            // visible mismatch for a subtler one.
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    // The louder half of the page's new ranking, and the owner's
                    // own words for it: *"cash und transaktionen können ja mehr
                    // weißlich statt grau werden"*. `textMuted` is what made
                    // "Bargeld" and "Transaktionen" read grey; these two are the
                    // page's only quick links, and a link the eye has to hunt for
                    // is not a quick one.
                    //
                    // ONLY the word moves. The chip's geometry, surface, hairline,
                    // icon tint and the cash figure below are all untouched — an
                    // earlier pass grew this chip and he reversed it, so brightness
                    // is the entire licence here.
                    color = bt.textPrimary,
                )
                if (value != null) {
                    MoneyText(value = value, style = BtTheme.type.moneySmall)
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
 * A demoted metadata row: label, optional value, chevron.
 *
 * Card-less on purpose. Cash used to be half of a 50/50 pair of cards competing
 * with the value above it; as a row under the holdings it is still one tap away
 * and no longer claims to be a headline. That is the mandate's "demoted into
 * rows" applied literally, and it is why these sit below the list rather than
 * being folded into it: they are about the portfolio, not about a position.
 */
@Composable
private fun SecondaryRow(
    label: String,
    value: Double?,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = bt.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                MoneyText(value = value, style = BtTheme.type.moneySmall)
                Spacer(Modifier.width(8.dp))
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
 * One holding (owner redesign 2026-08-16, second pass 2026-08-17): two tight
 * two-line stacks.
 *
 *  · LEFT — the asset NAME over the P&L in PERCENT (emerald/red by sign).
 *  · RIGHT — the position's current market value in NEUTRAL text, over the P&L
 *    in € (emerald/red, explicit sign).
 *
 * Byte-for-byte the arrangement [at.bettertrack.app.ui.home.HomeScreen]'s
 * portfolio rows wear, on the owner's instruction to *"do the same arrangement
 * with the holdings in portfolio"* — the two lists are the same kind of object
 * at two scales, and reading one should teach you how to read the other.
 *
 * The quantity and the %-of-portfolio weight both stay off the row: the exact
 * quantity is a detail-screen figure (rule 3) and the proportion is the insights
 * subpage's subject.
 *
 * The unpriced case states itself ONCE, in the value slot, instead of filling
 * four cells with dashes: a holding with no quote has no value, no €, and no %,
 * and "Kein Preis" in the one place the value would be is the whole truth about
 * it. In Drive mode that is a fixable fact, which is why it reads differently
 * there (W6).
 *
 * ## The sizing is v0.120's, verbatim (owner, 2026-08-17)
 *
 * *"i dont like that its that smaller now make it normal again"* was read as
 * "bigger", and the row went to `titleMedium`/`moneyMedium`. His verdict on
 * that: *"why did the holdings text increase insanely. just leave it like it
 * was in v0.120 … the other stuff like the positioning and content with the new
 * arrangement keep it like it is. but the sizing and looks take from 0.120."*
 *
 * So the row's **metrics are lifted from `db3a049` (v0.120) property by
 * property** — `titleSmall` name, `moneySmall` value, `numberCaption` deltas,
 * 14dp/12dp row inset, 2dp stack gaps, 12dp column gap — and its **anatomy is
 * today's**: the 4-slot arrangement, the ticker annotation, the content. "Make
 * it normal again" meant *back to the size it was*, and the tightening pass
 * (10dp inset, 1dp gaps) is part of what he was calling smaller, so it goes
 * back too. Two separate decisions had been bundled; this un-bundles them by
 * taking a real commit's values instead of a judgement about them.
 *
 * ## …and the name now carries its ticker
 *
 * *"add the short (BAYN.DE for Bayer for example) to the end of the name … like
 * grayish and thin"*, and then *"make the text for the short names (NVDA or
 * BAYN.DE) be the same size as the text next to it"* — so "grayish and thin"
 * turned out to mean grayish and thin, not small: the annotation carries the
 * name's own `titleSmall`, and only colour and weight separate them. See
 * [holdingTicker] for when it is suppressed and why the NAME is the half that
 * ellipsizes.
 *
 * ## …and the row stands down so the quick links can be read first
 *
 * *"cash und transaktionen können ja mehr weißlich statt grau werden und die
 * holdings einfach weniger prominentere hintergrund farbe. nicht gleich die
 * hintergrund farbe entfernen. sondern nur leichter machen."* (owner,
 * 2026-08-17.)
 *
 * The diagnosis behind it is arithmetic. This row and [QuickStatChip] were both
 * painted `surface` — the same `#161B22` — so on his true-black page a list of
 * ten cards and a pair of two sat at an identical 9.4 L\* off the ground, and
 * the list won on sheer count. Two moves, both contrast, neither one a size:
 * the row drops to `quiet` (`surfaceQuiet`, ~6.1 L\*) and the chips' labels go
 * from `textMuted` to `textPrimary`. Nothing here is deleted and nothing is
 * shrunk — he has now corrected a shrink twice and a deletion once.
 */
@Composable
private fun HoldingRow(
    holding: HoldingEntity,
    locale: Locale,
    noLivePrices: Boolean,
    onClick: () -> Unit,
) {
    val bt = BtTheme.colors
    // `quiet` = the same card on a weaker fill (`surfaceQuiet`). Not a smaller
    // card, not a card-less row — see [BtCard]'s KDoc and the section below.
    // Everything else about this row is v0.120's, untouched.
    BtCard(modifier = Modifier.fillMaxWidth(), quiet = true, onClick = onClick) {
        // The rail states this holding's verdict at a glance; the P/L text below
        // states it precisely. Colour is never the only carrier (§4.4).
        BtRailedRow(rail = rangeRail(holding.unrealizedPnlPct ?: holding.unrealizedPnlEur)) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    // Name + ticker on ONE line, and the split of the remaining
                    // width between them is the whole point: the ticker takes
                    // its intrinsic width first (unweighted), the name takes
                    // what is left (weighted) and ellipsizes into it. So a very
                    // long name loses its tail, never the four characters that
                    // say WHICH listing this is. See [holdingTicker].
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = holding.assetName,
                            style = MaterialTheme.typography.titleSmall,
                            color = bt.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        holdingTicker(holding.assetName, holding.assetSymbol)?.let { ticker ->
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = ticker,
                                // The NAME'S OWN TYPE — same size, same
                                // line-height — so the two halves of the line
                                // read as one label. Owner, 2026-08-17: *"make
                                // the text for the short names … be the same
                                // size as the text next to it"*. It stays the
                                // secondary half on the two axes that cost no
                                // size: the muted ink and Normal weight against
                                // the name's SemiBold. Equal line-heights also
                                // make the row's Bottom alignment an exact
                                // baseline match.
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Normal,
                                    fontFeatureSettings = FONT_FEATURE_TABULAR,
                                ),
                                color = bt.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                    holding.unrealizedPnlPct?.let { plPct ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatPercent(plPct, locale),
                            style = BtTheme.type.numberCaption,
                            color = deltaColor(plPct),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val value = holding.marketValueEur
                    if (value != null) {
                        MoneyText(
                            value = value,
                            style = BtTheme.type.moneySmall,
                            color = bt.textPrimary,
                        )
                    } else {
                        // W6: a dash says "nothing here". In Drive mode the truth
                        // is "no price yet", which is different and fixable.
                        Text(
                            text = stringResource(
                                if (noLivePrices) R.string.bt_price_none else R.string.bt_switcher_value_pending,
                            ),
                            style = if (noLivePrices) {
                                MaterialTheme.typography.bodySmall
                            } else {
                                BtTheme.type.moneySmall
                            },
                            color = bt.textMuted,
                        )
                    }
                    holding.unrealizedPnlEur?.let { plEur ->
                        Spacer(Modifier.height(2.dp))
                        MoneyText(
                            value = plEur,
                            style = BtTheme.type.numberCaption,
                            color = deltaColor(plEur),
                            showSign = true,
                        )
                    }
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
    BtStateFill {
        BtErrorState(onRetry = onRetry)
    }
}

@Composable
private fun NoPortfolioState(
    isOnline: Boolean,
    busy: Boolean,
    error: BtMessage?,
    onCreate: (String) -> Unit,
) {
    val bt = BtTheme.colors
    var createOpen by rememberSaveable { mutableStateOf(false) }
    BtStateFill {
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
            error?.let { shown ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = shown.resolveWithDiagnostic(),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.loss,
                )
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

@Composable
internal fun deltaColor(value: Double) = when {
    value > 0.0 -> BtTheme.colors.gain
    value < 0.0 -> BtTheme.colors.loss
    else -> BtTheme.colors.textSecondary
}

/**
 * Whether the hero surface — chart AND readouts — may state a gain/loss verdict
 * in COLOUR at all, in the mode it is currently in.
 *
 * ## The bug this exists to close (owner report 2026-08-08)
 *
 * `BtChartMode.colorsBySign` was split out of `plotsPerformance` on 2026-08-07
 * so the combined mode could plot the % series and stay neutral. It was then
 * wired into exactly ONE consumer: `BtAreaChart`'s `colorBySign`, which governs
 * the line brush, the mirrored area wash and the crosshair dot. The canvas has
 * been correctly neutral in combined mode ever since.
 *
 * The TEXT around the canvas never learned the rule. The range-performance
 * readout beside the picker and the day-change line under the headline call
 * `deltaColor()` / `MoneyColorMode.GainLoss` directly, keyed off the sign of
 * their own number with no reference to the mode — so red/green kept bleeding
 * into the combined mode from the two readouts that frame the chart. The mode
 * flag simply never reached the text layer; nothing was "still coloured by
 * accident", it was never gated in the first place.
 *
 * This is that gate, in one place, so a fourth readout added later has an
 * obvious thing to consult. Only [BtChartMode.PERFORMANCE] passes: in that mode
 * the number IS the return, so the verdict and the quantity are the same thing.
 * The € mode was found tinted too — same two readouts — and is neutralised by
 * the same rule, which is what §4.1 said all along: gold is the portfolio, and a
 * red/green verdict belongs to an asset.
 */
internal fun signColorAllowed(mode: BtChartMode): Boolean = mode.colorsBySign

/**
 * [deltaColor] for a hero readout: the verdict colour where the mode allows one,
 * the neutral secondary ink everywhere else.
 *
 * The neutral is `textSecondary` rather than the ambient content colour so a
 * neutralised number stays a number — the same token the combined mode's scrub
 * sub-line already uses, and the same one [deltaColor] itself resolves an exact
 * zero to. Sign is never lost by neutralising: every call site that uses this
 * prints an explicit `+`/`−`.
 */
@Composable
internal fun deltaTint(mode: BtChartMode, value: Double): Color =
    if (signColorAllowed(mode)) deltaColor(value) else BtTheme.colors.textSecondary

/**
 * The accent an **asset-level** surface wears: gain or loss by the performance of
 * the range the user is currently looking at (§4.1).
 *
 * The rule this encodes, and the reason it is a separate function from
 * [deltaColor] even though the branches look the same:
 *
 * | scope | accent |
 * |---|---|
 * | **Portfolio-level** value — hero chart, hero number, portfolio identity | `gold`, always |
 * | **Asset-level** value — asset charts, holding rows, watchlist rows | this |
 * | **Controls** — range chips, buttons | `gold`, always |
 *
 * Gold *is* the portfolio; it never means "up". An asset is a bet, so its colour
 * is its verdict. Keeping the two apart is what stops the accent leaking into
 * chrome, which is how "colour as signal" decays back into decoration.
 *
 * Null (no data yet) and exactly zero both resolve to [textSecondary]: a rail
 * that claims a verdict the numbers do not support is worse than no rail.
 */
@Composable
internal fun rangeAccent(pct: Double?): Color = when {
    pct == null -> BtTheme.colors.textSecondary
    pct > 0.0 -> BtTheme.colors.gain
    pct < 0.0 -> BtTheme.colors.loss
    else -> BtTheme.colors.textSecondary
}

/**
 * The rail colour for a row, or `null` when the row has no verdict to state.
 *
 * Distinct from [rangeAccent] because a *rail* has a third state the text does
 * not: absent. A grey rail would read as a fourth verdict; no rail reads as
 * "nothing to say yet", which is the truth for an unpriced holding.
 */
@Composable
internal fun rangeRail(pct: Double?): Color? = when {
    pct == null || pct == 0.0 -> null
    else -> BtTheme.colors.edge(rangeAccent(pct), RAIL_ALPHA)
}

/** §4.3: the rail sits at 60% so it reads as a mark, not as a second border. */
private const val RAIL_ALPHA = 0.60f

/**
 * Scrub readout stamp. Sub-daily series (V5 intraday 1D/1W/1M) add the
 * time-of-day, otherwise the day-granular wording is kept verbatim — scrubbing a
 * 1Y curve should not suddenly claim a meaningless "00:00".
 */
private fun formatChartScrubDate(epochMillis: Long, subDaily: Boolean, locale: Locale): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(scrubDateFormatter(locale, subDaily))

/**
 * Cached scrub-date formatters — same idiom, and the same reason, as
 * `btNumberFormat` in `BtNumberFormat.kt` (2026-08-06 perf pass).
 *
 * `DateTimeFormatter.ofPattern` parses its pattern and builds a formatter on
 * every call, and this one is on the hottest path in the app: the scrub readout
 * re-renders each time the crosshair moves to a new point, which during a drag
 * across a dense 1M series is faster than the display refreshes. There are
 * exactly two patterns and one locale in practice, so they are built once.
 *
 * `ThreadLocal` for symmetry with the number formatters: `DateTimeFormatter` is
 * in fact immutable and thread-safe, but keeping both caches shaped the same way
 * means there is one rule about formatter reuse in this codebase rather than two.
 */
private val scrubDateFormatters: ThreadLocal<MutableMap<Pair<Locale, Boolean>, java.time.format.DateTimeFormatter>> =
    ThreadLocal.withInitial { HashMap() }

private fun scrubDateFormatter(locale: Locale, subDaily: Boolean): java.time.format.DateTimeFormatter =
    scrubDateFormatters.get()!!.getOrPut(locale to subDaily) {
        val pattern = if (subDaily) "d MMM yyyy, HH:mm" else "d MMM yyyy"
        java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
    }
