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
import androidx.compose.runtime.derivedStateOf
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
import at.bettertrack.app.ui.theme.BtColors
import at.bettertrack.app.ui.charts.BtDonutChart
import at.bettertrack.app.ui.charts.DonutSegment
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtChip
import at.bettertrack.app.ui.components.BtCollapsingHeader
import at.bettertrack.app.ui.components.BtEmptyState
import at.bettertrack.app.ui.components.BtErrorState
import at.bettertrack.app.ui.components.BtHeaderWordmark
import at.bettertrack.app.ui.components.BtInlineEmpty
import at.bettertrack.app.ui.components.BtPrimaryButton
import at.bettertrack.app.ui.components.BtSettingsGear
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.rememberBtPinnedHeaderBehavior
import at.bettertrack.app.ui.components.rememberBtFabVisibility
import at.bettertrack.app.ui.components.fabVisibleForList
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import at.bettertrack.app.data.prefs.BtChartMode
import at.bettertrack.app.ui.components.btPressScale
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
    /**
     * The Settings gear — this tab's trailing anchor, on BOTH of the header's
     * modes (Overview and a selected portfolio), because a landmark that is
     * present only on one of a tab's two states is not a landmark.
     *
     * A callback rather than a slot (unlike [overviewAction]): the gear is
     * identical on all four tabs by design, so there is nothing here for a
     * caller to vary and a slot would only be an invitation to try.
     */
    onOpenSettings: () -> Unit,
    /**
     * Debug builds: the wordmark's long-press opens the component gallery.
     *
     * The owner asked for this affordance twice — once as the original hidden
     * gallery entry, and again in the Step-18 "secret menu" request ("keep the
     * wordmark long-press too"). It was lost when the R-arc retired the shell top
     * bar and the wordmark with it, and the gallery moved into Overview's ⋮
     * instead. The wordmark is back in this header, so the long-press comes back
     * with it — which is what lets that ⋮ entry, and with it the whole menu, go.
     */
    onLongPressWordmark: () -> Unit,
) {
    val vm: PortfolioOverviewViewModel = viewModel(initializer = PortfolioOverviewVmInitializer)

    val overviewSelected by vm.overviewSelected.collectAsStateWithLifecycle()
    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    val portfolioKinds by vm.portfolioKinds.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
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
    val pullState = rememberPullToRefreshState()
    // S6 P1-7: the buy/sell FAB sits exactly over the allocation legend's value
    // column, so on a portfolio with more than a couple of slices the reader
    // simply cannot see the last percentages. Rather than inset the legend (which
    // would waste that width on every screen, FAB or no FAB), the FAB gets out of
    // the way while the user scrolls down and comes straight back on the way up.
    val fabVisibility = rememberBtFabVisibility()
    // Pinned, not collapsing (owner directive 2026-08-06): "the selector for
    // portfolio can always be on top and doesn't need to drop down when scrolled
    // all the way up". See BtCollapsingHeader's `pinned` branch for why the
    // behaviour is still a real one rather than null.
    val scrollBehavior = rememberBtPinnedHeaderBehavior()
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
            pinned = true,
            // The selector's leading glyph is the Portfolio TAB's glyph, on
            // purpose: the pill states which entry of that tab you are in, so
            // wearing the tab's own icon makes the relationship legible without a
            // word. The web app tints its trigger chip per portfolio; this app has
            // no per-portfolio icon or hue in its model, so one glyph in the
            // house accent is the honest equivalent rather than a colour invented
            // client-side.
            titleIcon = Icons.Outlined.PieChart,
            // The wordmark lives in the leading slot of the always-visible top
            // row (owner report 2026-08-06: "the BetterTrack logo up top is
            // missing — just an empty space now"). Two things were true and both
            // are fixed here. The brand HAD left the app entirely when the shell
            // top bar was retired — it survived only on login and About, so the
            // running app never said its own name. And M3's `LargeTopAppBar`
            // fades its collapsed title to nothing while the header is expanded,
            // which left that 64dp row holding a search icon, an overflow, and
            // ~200dp of deliberate blankness on the screen the app OPENS on.
            //
            // Putting the wordmark there answers both with one element: the app
            // is named again, in the corner every Android app puts its identity,
            // and the empty row now has a subject. It does NOT fade with the
            // collapse — brand is not context, and a logo that dissolves when you
            // scroll reads as a rendering bug, which is precisely the report this
            // change is answering.
            //
            // As of the owner's 2026-08-07 order it is on all four tabs, not this
            // one, so the mark itself moved into [BtHeaderWordmark] and this slot
            // just names it — identical padding and size on every tab by
            // construction rather than by four authors agreeing. The pushed
            // screens' leading slot is still their back arrow.
            //
            // This tab is the only caller that passes the gallery door, because it
            // is the only screen that has one to pass.
            navigationIcon = { BtHeaderWordmark(onLongPress = onLongPressWordmark) },
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
            // ── Both of this tab's ⋮ menus are gone (nav restoration 2026-08-06) ──
            //
            // Overview's carried inbox · discreet · settings · gallery; a
            // portfolio's carried transactions · cash · pending · manage. Between
            // them they were the clearest case of the owner's report — the SAME
            // glyph in the SAME corner of the SAME tab, meaning two entirely
            // different things depending on which entry the switcher was on.
            //
            // Every one of those eight entries had an in-content twin already
            // (that was the design rule when they were written), so dissolving
            // them removed no capability: Settings and the inbox and discreet
            // mode are rows on Overview itself, cash and transactions are rows
            // under the holdings, pending sync is the strip that appears with the
            // pending work it is about, manage-portfolios is the pill above, and
            // the gallery is back on the wordmark's long-press. What the corner
            // holds now is one thing that never changes.
            settings = { BtSettingsGear(onOpenSettings) },
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
                            chartMode = chartMode,
                            pendingTx = pendingTx,
                            onRange = vm::setRange,
                            onChartMode = vm::setChartMode,
                            onOpenHolding = onOpenHolding,
                            onOpenTransactions = onOpenTransactions,
                            onOpenPendingSync = onOpenPendingSync,
                            onOpenCash = onOpenCash,
                            onOpenPortfolioSettings = onOpenPortfolioSettings,
                            onNewTransaction = onNewTransaction,
                        )
                    }
                }

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
                selected?.takeIf { holdingsFabVisible }?.let { p ->
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
    onNewTransaction: (String) -> Unit,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val totals = portfolio.totals

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
    // The chart only needs to know WHETHER a scrub is in progress. Passing
    // `scrub != null` subscribed the chart's item scope to the scrubbed POINT,
    // so every pointer sample recomposed the hero — its readout, its six range
    // chips and the canvas — instead of only the two frames where the boolean
    // actually flips (perf pass 2026-08-06).
    val scrubbing by remember { derivedStateOf { scrub != null } }
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
                            color = deltaColor(s.valueEur),
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
                                color = deltaColor(s.valueEur),
                            )
                        }
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

        // Cash + Transactions, back at the top (owner ask 2026-08-07: "I liked
        // the fast access"). They used to be the last rows of the screen, below
        // every holding — which on any real portfolio means off-screen. A pair of
        // stat chips directly under the value gives Cash its number back AND
        // one-tap reach, in ~56dp, without re-inflating them to the 50/50 cards
        // the R-arc deleted for spending half a screen on two links.
        item(key = "quick-access", contentType = "quick-access") {
            Row(
                modifier = inset,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickStatChip(
                    label = stringResource(R.string.bt_overview_cash),
                    value = totals?.cashEur,
                    icon = Icons.Outlined.AccountBalanceWallet,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenCash(portfolio.id) },
                )
                QuickStatChip(
                    label = stringResource(R.string.bt_tx_title),
                    value = null,
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenTransactions(portfolio.id) },
                )
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
                scrubbing = scrubbing,
                onRange = onRange,
                onMode = onChartMode,
                onScrub = { scrub = it },
                locale = locale,
            )
        }

        // Allocation, PROMOTED above the holdings and reduced to a summary
        // (decision O-5). What a reader wants here is proportion, and a stacked
        // bar answers that in one glance and 10dp of height where the 132dp donut
        // needed a card of its own between the value and the positions.
        if (holdings.isNotEmpty() || (totals?.cashEur ?: 0.0) > 0.0) {
            item(key = "allocation", contentType = "allocation") {
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
        item(key = "holdings-header", contentType = "section-header") {
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
                count = holdings.size,
                key = { "holding-" + holdings[it].assetId },
                // Every `item {}` above declares its own contentType too. Without
                // them the whole list shared ONE reuse pool, so a holding row
                // could be handed a retained slot that last held the 150dp chart
                // or the hero — structures that cannot be reused, which makes
                // LazyLayout deactivate and fully recompose instead. The pool
                // then costs bookkeeping and pays nothing back (perf pass
                // 2026-08-06).
                contentType = { "holding" },
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
 * The chips above the chart pick what the curve is, out of the two series the
 * `/history` payload already carries together — so switching is instant and never
 * refetches. See [BtChartMode] for what each one means. The hybrid mode is the
 * owner's: *the % shape, because that is the shape that says how the investments
 * did, with the € balance as the readout, because that is the number you want
 * when you point at a day.*
 *
 * The mode chips sit ABOVE the canvas and the range chips BELOW it, deliberately
 * split rather than crowded onto one row: they answer different questions ("what
 * am I looking at" vs "over what window"), the range row already carries six
 * chips and had no room, and the split matches the web, which puts its display
 * toggle in the section header and its range toggle inside the chart.
 */
@Composable
private fun HeroChart(
    history: PortfolioHistory?,
    range: HistoryRange,
    mode: BtChartMode,
    scrubbing: Boolean,
    onRange: (HistoryRange) -> Unit,
    onMode: (BtChartMode) -> Unit,
    onScrub: (HistoryPoint?) -> Unit,
    locale: Locale,
) {
    val bt = BtTheme.colors
    val chartCd = stringResource(R.string.bt_overview_chart_cd)
    val modeCd = stringResource(R.string.bt_chart_mode_cd)
    Column(Modifier.fillMaxWidth()) {
        // Mode chips (left) + range performance (right). One row, because they
        // are both statements about the curve directly beneath them.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = modeCd },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartModeChip(BtChartMode.BALANCE, mode, R.string.bt_chart_mode_balance, R.string.bt_chart_mode_balance_cd, onMode)
            ChartModeChip(BtChartMode.PERFORMANCE, mode, R.string.bt_chart_mode_performance, R.string.bt_chart_mode_performance_cd, onMode)
            ChartModeChip(BtChartMode.HYBRID, mode, R.string.bt_chart_mode_hybrid, R.string.bt_chart_mode_hybrid_cd, onMode)
            Spacer(Modifier.weight(1f))
            // Hidden while scrubbing so the hero's scrub readout is the single
            // focus — the same rule this line has always followed.
            val pct = history?.rangePerformancePct
            if (!scrubbing && pct != null) {
                Text(
                    text = formatPercent(pct, locale),
                    style = BtTheme.type.numberCaption,
                    color = deltaColor(pct),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = rangeLabel(range),
                    style = BtTheme.type.numberCaption,
                    color = bt.textMuted,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

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
                lineColor = bt.gold,
                minimal = true,
                baseline = mode.plotsPerformance,
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

        // What a neutralized curve means, said once, only where it applies —
        // same placement and same sentence as the web's perf-mode hint.
        if (mode.plotsPerformance) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.bt_chart_perf_hint),
                style = MaterialTheme.typography.bodySmall,
                color = bt.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
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

/** One display-mode chip. Reuses [BtChip] so the control needs no new vocabulary. */
@Composable
private fun ChartModeChip(
    value: BtChartMode,
    selected: BtChartMode,
    labelRes: Int,
    contentDescriptionRes: Int,
    onSelect: (BtChartMode) -> Unit,
) {
    val cd = stringResource(contentDescriptionRes)
    BtChip(
        text = stringResource(labelRes),
        selected = value == selected,
        onClick = { onSelect(value) },
        modifier = Modifier.semantics { contentDescription = cd },
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
                    color = bt.textMuted,
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
    val palette = BtTheme.colors
    val segments = remember(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels, palette) {
        allocationSegments(holdings, cashEur, byCategory, otherLabel, cashLabel, categoryLabels, palette)
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
        // The rail states this holding's verdict at a glance; the P/L text below
        // states it precisely. Colour is never the only carrier (§4.4).
        BtRailedRow(rail = rangeRail(holding.unrealizedPnlPct ?: holding.unrealizedPnlEur)) {
            Row(
                modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp),
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
    palette: BtColors,
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

    val maxSlots = palette.chartSeries.size
    val top = parts.take(maxSlots)
    val rest = parts.drop(maxSlots).sumOf { it.value }

    return buildList {
        top.forEachIndexed { i, part ->
            add(DonutSegment(part.label, part.value, palette.chartSeries[i]))
        }
        if (rest > 0.0) add(DonutSegment(otherLabel, rest, palette.chartRest))
        if (cashEur > 0.0) add(DonutSegment(cashLabel, cashEur, palette.chartCash))
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
