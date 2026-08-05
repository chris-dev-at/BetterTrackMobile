package at.bettertrack.app.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import at.bettertrack.app.data.api.BtMessage
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
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.rememberBtCollapsingHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtFabVisibility
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatEur
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.components.resolveWithDiagnostic
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.market.rememberAssetTypeLabeller
import at.bettertrack.app.ui.theme.BtShapes
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import androidx.compose.ui.unit.Dp
import java.util.Locale

/**
 * The hero chart's canvas height (decision O-5).
 *
 * Named rather than inlined because it is a hierarchy decision, not a layout
 * detail: this number is what buys the allocation summary and the first holdings
 * their place on the first screen, and anyone raising it is undoing §3's reorder.
 */
private val HERO_CHART_HEIGHT: Dp = 150.dp

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
    /** Overview's ONE header action (search) — the mandate's §1 budget. */
    overviewAction: @Composable () -> Unit,
    /** Overview's header overflow: inbox · discreet · settings · dev backend. */
    overviewOverflow: @Composable () -> Unit,
) {
    val vm: PortfolioOverviewViewModel = viewModel(initializer = PortfolioOverviewVmInitializer)

    val overviewSelected by vm.overviewSelected.collectAsStateWithLifecycle()
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
    val switcherValueFailed by vm.switcherValueFailed.collectAsStateWithLifecycle()
    val pendingTx by vm.pendingTx.collectAsStateWithLifecycle()

    // Sheet visibility lives in the VM so the shell top-bar selector can open it.
    val switcherOpen by vm.switcherVisible.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose { }
    }

    val bt = BtTheme.colors
    val pullState = rememberPullToRefreshState()
    // S6 P1-7: the buy/sell FAB sits exactly over the allocation legend's value
    // column, so on a portfolio with more than a couple of slices the reader
    // simply cannot see the last percentages. Rather than inset the legend (which
    // would waste that width on every screen, FAB or no FAB), the FAB gets out of
    // the way while the user scrolls down and comes straight back on the way up.
    val fabVisibility = rememberBtFabVisibility()
    val scrollBehavior = rememberBtCollapsingHeaderBehavior()
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
            .nestedScroll(scrollBehavior.nestedScrollConnection),
    ) {
        val canSwitch = portfolios.isNotEmpty()
        BtCollapsingHeader(
            // The title says which of the switcher's entries you are looking at,
            // and Overview is one of them — so it names itself here exactly the
            // way a portfolio does. That is the whole IA change in one line: the
            // former Home tab is now a selection, not a place.
            title = if (overviewSelected) {
                stringResource(R.string.bt_overview_title)
            } else {
                selected?.name ?: stringResource(R.string.bt_tab_portfolio)
            },
            scrollBehavior = scrollBehavior,
            // Always tappable now. The switcher stopped being an optional
            // convenience the moment it became the only way between Overview and
            // the portfolios: an account with zero portfolios still needs the
            // sheet (to create its first one), and Overview itself is always in
            // it, so there is no state in which opening it is a dead end. It used
            // to be disabled while `portfolios` was empty, which after this change
            // would strand a fresh account on Overview with no way out.
            onTitleClick = { vm.openSwitcher() },
            titleClickLabel = stringResource(R.string.bt_switcher_open_cd),
            action = if (overviewSelected) overviewAction else null,
            overflow = if (overviewSelected) {
                overviewOverflow
            } else {
                {
                    PortfolioOverflow(
                        portfolioId = selected?.id,
                        canSwitch = canSwitch,
                        hasPending = pendingTx.isNotEmpty(),
                        onOpenTransactions = onOpenTransactions,
                        onOpenCash = onOpenCash,
                        onOpenPendingSync = onOpenPendingSync,
                        onManagePortfolios = { vm.openSwitcher() },
                    )
                }
            },
        )

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
                            listState = listState,
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
                }

                // Step 8 (§6.2): recording a transaction is ≤2 taps from the overview —
                // this FAB opens the buy/sell form directly. It stays the screen's ONLY
                // creation entry; the header deliberately carries no `+`.
                selected?.let { p ->
                    val fabCd = stringResource(R.string.bt_overview_fab_cd)
                    fabVisibility.Content(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    ) {
                        FloatingActionButton(
                            onClick = { onNewTransaction(p.id) },
                            containerColor = bt.gold,
                            contentColor = bt.onGold,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                            modifier = Modifier.semantics { contentDescription = fabCd },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
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
    pendingTx: List<PendingTxRow>,
    onRange: (HistoryRange) -> Unit,
    onOpenHolding: (String) -> Unit,
    onOpenTransactions: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onOpenCash: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val totals = portfolio.totals

    // W6: true when this mode has no live quotes, so an absent price is a state
    // the user can act on rather than a transient server gap.
    val noLivePrices = at.bettertrack.app.ui.prices.manualEntryAvailable(
        at.bettertrack.app.di.AppGraph.gatedStorageMode(
            at.bettertrack.app.di.AppGraph.storageModeStore.mode.collectAsStateWithLifecycle().value,
        ),
    )

    // How much of this portfolio could be priced at all. Hoisted to the content
    // scope because three separate places need it — the hero, its day-change
    // sub-line and the holdings-value roll-up — and all three must agree.
    val coverage = at.bettertrack.app.ui.prices.priceCoverage(holdings)

    // Scrub state is hoisted here so touching the hero chart updates the big
    // Net-Worth readout (Robinhood-style). A fresh selection/range clears it.
    var scrub by remember { mutableStateOf<HistoryPoint?>(null) }
    LaunchedEffect(portfolio.id, range) { scrub = null }

    // Content is inset 16dp — EXCEPT the hero chart, which bleeds edge-to-edge.
    val inset = Modifier.fillMaxWidth().padding(horizontal = 16.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 8.dp,
            // Clear the buy/sell FAB (56dp + 20dp inset + margin) so the last
            // holding row scrolls fully into view instead of under it. Kept even
            // though the FAB now hides on scroll (S6 P1-7): it comes back the
            // moment the user scrolls up, and the last row must still clear it.
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Net-Worth hero (server totals only) — scrub-aware: while scrubbing the
        // chart, the big number + label become the touched point's value + date.
        item(key = "hero") {
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
                    MoneyText(
                        value = s?.valueEur ?: totals!!.totalValueEur,
                        style = BtTheme.type.moneyLarge,
                    )
                    if (s == null) {
                        at.bettertrack.app.ui.prices.UnpricedNote(
                            coverage = coverage,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    // Reserve the sub-line height so scrubbing never shifts layout.
                    Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        // W6: with nothing priced, `dayChangeEur` is a sum of
                        // zeroes and would render "+0,00 € · today" — which reads
                        // as "no movement" when the truth is "not known". Same
                        // €0 lie, one line lower down.
                        if (s == null && totals != null && !coverage.nothingPriced) {
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

        // The pending strip's ONE promotion (mandate §3 vs §7.4): a queued change
        // the server refused is not status, it is work, and work belongs next to
        // the number it is about to change. Everything merely waiting to upload
        // is status and lives at the bottom of the screen.
        val attention = pendingTx.count { it.status == PendingUiStatus.NEEDS_ATTENTION }
        if (attention > 0) {
            item(key = "pending-attention") {
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

        // Allocation, PROMOTED above the holdings and reduced to a summary
        // (decision O-5). What a reader wants here is proportion, and a stacked
        // bar answers that in one glance and 10dp of height where the 132dp donut
        // needed a card of its own between the value and the positions.
        if (holdings.isNotEmpty() || (totals?.cashEur ?: 0.0) > 0.0) {
            item(key = "allocation") {
                Box(inset) {
                    AllocationSummary(
                        holdings = holdings,
                        cashEur = totals?.cashEur ?: 0.0,
                        locale = locale,
                    )
                }
            }
        }

        // Holdings — the thing this screen is for, now immediately after the
        // value and the summary (mandate §3).
        item(key = "holdings-header") {
            Column(inset.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.bt_overview_holdings_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = bt.textPrimary,
                )
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
        item(key = "secondary-rows") {
            Column(inset.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryRow(
                    label = stringResource(R.string.bt_overview_cash),
                    value = totals?.cashEur,
                    onClick = { onOpenCash(portfolio.id) },
                )
                SecondaryRow(
                    label = stringResource(R.string.bt_tx_title),
                    value = null,
                    onClick = { onOpenTransactions(portfolio.id) },
                )
            }
        }

        // Pending changes (§7.1/§7.4): queued entries live ALONGSIDE the
        // server-computed numbers, clearly marked, and open the Pending-sync
        // screen — never folded into the totals. Below the holdings unless
        // something in them needs attention, in which case it is already above.
        if (pendingTx.isNotEmpty() && attention == 0) {
            item(key = "pending-strip") {
                Box(inset) { PendingStrip(pendingTx = pendingTx, onClick = onOpenPendingSync) }
            }
        }
    }
}

/**
 * The Portfolio header's ⋮ (mandate §1: context, ONE action, overflow).
 *
 * Every entry here has an in-content twin on the screen below — Transactions and
 * Cash as the secondary rows under the holdings, Pending sync as the strip,
 * Manage portfolios as the tap on the title itself. That pairing is Fable's
 * design-review rule, and it is also why "Pending sync" is gated on there being
 * something pending: it appears and disappears together with the strip, so the
 * menu never offers a path the screen itself does not.
 */
@Composable
private fun PortfolioOverflow(
    portfolioId: String?,
    canSwitch: Boolean,
    hasPending: Boolean,
    onOpenTransactions: (String) -> Unit,
    onOpenCash: (String) -> Unit,
    onOpenPendingSync: () -> Unit,
    onManagePortfolios: () -> Unit,
) {
    val bt = BtTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.bt_top_more),
                tint = bt.textSecondary,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = bt.surface,
        ) {
            if (portfolioId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_tx_title)) },
                    onClick = { open = false; onOpenTransactions(portfolioId) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_overview_cash)) },
                    onClick = { open = false; onOpenCash(portfolioId) },
                )
            }
            if (hasPending) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_pending_title)) },
                    onClick = { open = false; onOpenPendingSync() },
                )
            }
            if (canSwitch) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_overview_manage_portfolios)) },
                    onClick = { open = false; onManagePortfolios() },
                )
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
 *
 * R-arc R1 (decision O-5): the canvas is [HERO_CHART_HEIGHT] rather than 200dp.
 * The chart is the owner's, it is praised, and it stays directly under the hero —
 * but at 200dp it plus its performance line and range chips were, on their own,
 * the reason the first holding row sat below the fold. 50dp is what the shape
 * costs, not what it says; nothing about reading the curve is worse at 150dp,
 * and the allocation summary and the first two positions now fit the first screen.
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
                    .height(HERO_CHART_HEIGHT)
                    .semantics { contentDescription = chartCd },
                lineColor = bt.gold,
                minimal = true,
                onScrub = onScrub,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(HERO_CHART_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                if (history == null) {
                    BtSkeleton(Modifier.fillMaxWidth().height(126.dp).padding(horizontal = 16.dp))
                } else {
                    // BtInlineEmpty, not BtEmptyState: a 64dp glyph badge plus
                    // 32dp of padding does not fit a 150dp chart slot, and an
                    // absent chart is an answer rather than a failure — so it
                    // gets the calm one-line form, inset to the page gutter.
                    BtInlineEmpty(
                        text = stringResource(R.string.bt_overview_chart_empty),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

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
 * Allocation as a summary first, the donut second (decision O-5).
 *
 * ## Why the bar replaced the card in the first screen
 *
 * The donut was never wrong — it is the right shape for "how is this divided" —
 * but at 132dp inside a card with a legend it cost roughly a third of a phone
 * screen, sitting between the portfolio's value and its positions. The mandate
 * asks for "value + allocation summary first; holdings list immediately after",
 * and a stacked bar is the smallest honest answer to "summary": it encodes the
 * same proportions, reads left to right in one glance, and takes 10dp. The three
 * largest names underneath it turn the shape into something you can also read.
 *
 * The donut is not deleted — it is one tap behind "See all", together with the
 * by-asset / by-category switch, because that switch is a question you ask
 * *while studying* allocation, not while glancing at it.
 */
@Composable
private fun AllocationSummary(holdings: List<HoldingEntity>, cashEur: Double, locale: Locale) {
    val bt = BtTheme.colors
    var byCategory by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    val otherLabel = stringResource(R.string.bt_overview_alloc_other)
    val cashLabel = stringResource(R.string.bt_overview_alloc_cash)
    // R3 §3: the category names are resolved HERE, like the two labels above,
    // because `allocationSegments` is a pure function and `assetTypeLabel` is a
    // composable. They used to come from a private `categoryLabel` in this file
    // that returned hard-coded English — so a German user's donut read "Stocks",
    // "ETFs", "Commodities". `assetTypeLabel` is the app's real, localized
    // mapping for exactly these server type strings, and it already carried the
    // identical unknown-type fallback; the duplicate is gone.
    val categoryLabels = rememberAssetTypeLabeller()
    val segments = remember(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels) {
        allocationSegments(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels)
    }
    val total = segments.sumOf { it.value }
    if (segments.isEmpty() || total <= 0.0) return

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.bt_overview_allocation_section),
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
                modifier = Modifier.weight(1f),
            )
            BtChip(
                text = stringResource(
                    if (expanded) R.string.bt_overview_alloc_less else R.string.bt_overview_alloc_see_all,
                ),
                onClick = { expanded = !expanded },
            )
        }
        Spacer(Modifier.height(12.dp))
        AllocationBar(segments = segments, total = total)
        Spacer(Modifier.height(12.dp))

        // The top three, as compact legend cells. Three because that is what fits
        // one row at a readable size on 360dp — and because past the third slice
        // the question stops being "what is this mostly" and starts being the one
        // the donut answers.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            segments.take(ALLOCATION_SUMMARY_LEGEND).forEach { segment ->
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(segment.color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = segment.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = bt.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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

        if (expanded) {
            Spacer(Modifier.height(16.dp))
            AllocationDetail(
                segments = segments,
                total = total,
                byCategory = byCategory,
                onByCategory = { byCategory = it },
                locale = locale,
            )
        }
    }
}

/** How many slices the collapsed allocation summary names. */
private const val ALLOCATION_SUMMARY_LEGEND = 3

/**
 * The slim stacked bar.
 *
 * Weighted rather than measured: the slices are proportions of a total that is
 * already known, so laying them out with `weight` keeps them exact at any width
 * without a single pixel calculation. The 2dp gaps are what make adjacent slices
 * of similar colour readable as two things; without them a stacked bar of a
 * six-colour palette turns into a gradient at small sizes.
 */
@Composable
private fun AllocationBar(segments: List<DonutSegment>, total: Double) {
    val cd = stringResource(R.string.bt_overview_alloc_bar_cd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .semantics { contentDescription = cd },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { segment ->
            val share = (segment.value / total).toFloat()
            if (share > 0f) {
                Box(
                    Modifier
                        .weight(share)
                        .fillMaxHeight()
                        .background(segment.color, BtShapes.pill),
                )
            }
        }
    }
}

/** The full donut + legend, behind "See all". Unchanged in substance from S6. */
@Composable
private fun AllocationDetail(
    segments: List<DonutSegment>,
    total: Double,
    byCategory: Boolean,
    onByCategory: (Boolean) -> Unit,
    locale: Locale,
) {
    val bt = BtTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtChip(
                text = stringResource(R.string.bt_overview_alloc_by_asset),
                selected = !byCategory,
                onClick = { onByCategory(false) },
            )
            Spacer(Modifier.width(8.dp))
            BtChip(
                text = stringResource(R.string.bt_overview_alloc_by_category),
                selected = byCategory,
                onClick = { onByCategory(true) },
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

@Composable
private fun HoldingRow(
    holding: HoldingEntity,
    weightOfPortfolioPct: Double?,
    locale: Locale,
    noLivePrices: Boolean,
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
                    // W6: a dash says "nothing here". In Drive mode the truth is
                    // "no price yet", which is a different and fixable statement.
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
    error: BtMessage?,
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
    categoryLabel: (String) -> String,
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

@Composable
internal fun rangeLabel(range: HistoryRange): String = when (range) {
    HistoryRange.D1 -> stringResource(R.string.bt_range_1d)
    HistoryRange.W1 -> stringResource(R.string.bt_range_1w)
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

/**
 * Scrub readout stamp. Sub-daily series (V5 intraday 1D/1W/1M) add the
 * time-of-day, otherwise the day-granular wording is kept verbatim — scrubbing a
 * 1Y curve should not suddenly claim a meaningless "00:00".
 */
private fun formatChartScrubDate(epochMillis: Long, subDaily: Boolean, locale: Locale): String {
    val pattern = if (subDaily) "d MMM yyyy, HH:mm" else "d MMM yyyy"
    return java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}
