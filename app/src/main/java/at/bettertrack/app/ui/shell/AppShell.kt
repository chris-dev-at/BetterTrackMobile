package at.bettertrack.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.data.storage.visibleTabSurfaces
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.ActiveSessionsRoute
import at.bettertrack.app.navigation.AppLockSetupRoute
import at.bettertrack.app.navigation.AssetPageRoute
import at.bettertrack.app.navigation.AuthorizedAppsRoute
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.CashBudgetsRoute
import at.bettertrack.app.navigation.CashLedgerRoute
import at.bettertrack.app.navigation.CashRoute
import at.bettertrack.app.navigation.CashRulesRoute
import at.bettertrack.app.navigation.CashTagsRoute
import at.bettertrack.app.navigation.ChainManageRoute
import at.bettertrack.app.navigation.ChangePasswordRoute
import at.bettertrack.app.navigation.ChangelogRoute
import at.bettertrack.app.navigation.ChatListRoute
import at.bettertrack.app.navigation.ChatThreadRoute
import at.bettertrack.app.navigation.ConglomerateBuilderRoute
import at.bettertrack.app.navigation.ConglomerateDetailRoute
import at.bettertrack.app.navigation.ConnectionsRoute
import at.bettertrack.app.navigation.CustomAssetDetailRoute
import at.bettertrack.app.navigation.CustomAssetsRoute
import at.bettertrack.app.navigation.DeleteAccountRoute
import at.bettertrack.app.navigation.FriendGroupsRoute
import at.bettertrack.app.navigation.FriendOverviewRoute
import at.bettertrack.app.navigation.GalleryRoute
import at.bettertrack.app.navigation.HoldingDetailRoute
import at.bettertrack.app.navigation.IdeaDetailRoute
import at.bettertrack.app.navigation.MarketIntelRoute
import at.bettertrack.app.navigation.NotificationsInboxRoute
import at.bettertrack.app.navigation.PendingSyncRoute
import at.bettertrack.app.navigation.PortfolioInsightsRoute
import at.bettertrack.app.navigation.PortfolioSettingsRoute
import at.bettertrack.app.navigation.PortfolioTaxRoute
import at.bettertrack.app.navigation.SearchRoute
import at.bettertrack.app.navigation.ServerRoute
import at.bettertrack.app.navigation.SettingsAboutRoute
import at.bettertrack.app.navigation.SettingsLanguageRoute
import at.bettertrack.app.navigation.SettingsWidgetsRoute
import at.bettertrack.app.navigation.SettingsNotificationsRoute
import at.bettertrack.app.navigation.SettingsRoute
import at.bettertrack.app.navigation.SettingsSecurityRoute
import at.bettertrack.app.navigation.SharedConglomerateViewRoute
import at.bettertrack.app.navigation.SharedPortfolioViewRoute
import at.bettertrack.app.navigation.SharedWatchlistViewRoute
import at.bettertrack.app.navigation.SheetRootRoute
import at.bettertrack.app.navigation.StandingOrdersRoute
import at.bettertrack.app.navigation.StorageHomeRoute
import at.bettertrack.app.navigation.SyncDebugRoute
import at.bettertrack.app.navigation.TabTap
import at.bettertrack.app.navigation.TaxSettingsRoute
import at.bettertrack.app.navigation.TaxYearRoute
import at.bettertrack.app.navigation.TaxYearsRoute
import at.bettertrack.app.navigation.TransactionFormRoute
import at.bettertrack.app.navigation.TransactionsRoute
import at.bettertrack.app.navigation.TwoFactorRoute
import at.bettertrack.app.navigation.owningTab
import at.bettertrack.app.navigation.tabTapAction
import at.bettertrack.app.ui.applock.AppLockSetupScreen
import at.bettertrack.app.ui.cash.CashScreen
import at.bettertrack.app.ui.chat.ChatListScreen
import at.bettertrack.app.ui.chat.ChatThreadScreen
import at.bettertrack.app.ui.components.BtSnackbarHost
import at.bettertrack.app.ui.components.BtTabBadgeDot
import at.bettertrack.app.ui.components.LocalBtSnackbar
import at.bettertrack.app.ui.components.rememberBtSnackbarState
import at.bettertrack.app.ui.components.rememberReducedMotion
import at.bettertrack.app.ui.conglomerate.ConglomerateBuilderScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateDetailScreen
import at.bettertrack.app.ui.customassets.CustomAssetDetailScreen
import at.bettertrack.app.ui.customassets.CustomAssetsScreen
import at.bettertrack.app.ui.debug.SyncDebugScreen
import at.bettertrack.app.ui.gallery.GalleryScreen
import at.bettertrack.app.ui.home.HomeScreen
import at.bettertrack.app.ui.ideas.IdeaDetailScreen
import at.bettertrack.app.ui.market.AssetPageScreen
import at.bettertrack.app.ui.market.MarketIntelScreen
import at.bettertrack.app.ui.market.SearchScreen
import at.bettertrack.app.ui.notifications.NotificationSettingsScreen
import at.bettertrack.app.ui.notifications.NotificationsInboxScreen
import at.bettertrack.app.ui.paranoid.ParanoidGate
import at.bettertrack.app.ui.portfolio.HoldingDetailScreen
import at.bettertrack.app.ui.portfolio.PortfolioOverviewScreen
import at.bettertrack.app.ui.portfolio.PortfolioTabEntry
import at.bettertrack.app.ui.portfolio.TransactionFormScreen
import at.bettertrack.app.ui.portfolio.TransactionsScreen
import at.bettertrack.app.ui.screens.MarketsTabScreen
import at.bettertrack.app.ui.screens.WorkbenchTabScreen
import at.bettertrack.app.ui.settings.AboutScreen
import at.bettertrack.app.ui.settings.ActiveSessionsScreen
import at.bettertrack.app.ui.settings.ChangePasswordScreen
import at.bettertrack.app.ui.settings.ChangelogScreen
import at.bettertrack.app.ui.settings.DeleteAccountScreen
import at.bettertrack.app.ui.settings.LanguageScreen
import at.bettertrack.app.ui.settings.SecurityScreen
import at.bettertrack.app.ui.settings.ServerScreen
import at.bettertrack.app.ui.settings.SettingsScreen
import at.bettertrack.app.ui.settings.TwoFactorScreen
import at.bettertrack.app.ui.social.FriendGroupsScreen
import at.bettertrack.app.ui.social.FriendOverviewScreen
import at.bettertrack.app.ui.social.SharedConglomerateViewScreen
import at.bettertrack.app.ui.social.SharedPortfolioViewScreen
import at.bettertrack.app.ui.social.SharedWatchlistViewScreen
import at.bettertrack.app.ui.social.SocialScreen
import at.bettertrack.app.ui.sync.PendingSyncScreen
import at.bettertrack.app.ui.theme.BtIcons
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.workboard.WorkboardEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which shell-level signal lights this tab's badge dot (R-arc mandate §1). */
private enum class TabBadge {
    /** No dot, ever. */
    None,

    /** Unread chat messages — the affordance that left the top bar. */
    Chat,

    /** Alerts that have fired — the other badge the top bar used to carry. */
    Alerts,

    // ── Inbox: RETIRED 2026-08-09 ────────────────────────────────────────────
    //
    // Unread notifications used to light the Portfolio tab's dot, inherited from
    // Overview's ⋮ when that menu was dissolved (nav restoration 2026-08-06).
    // The reasoning then was that Portfolio is "the tab the inbox is reached
    // from" — true at the time, and the whole problem: it made a global signal
    // point at one tab because that tab happened to contain the only door.
    //
    // The bell in the shared bar is that door now, on every tab, and it carries
    // the COUNT rather than a dot. Keeping the Portfolio dot as well would mean
    // one fact drawn twice, in two grammars, one of which sends you to a tab
    // whose own content has nothing to do with it. So the bell is the single
    // unread surface and this constant is gone. The two remaining badges are
    // unaffected: Chat and Alerts point at tabs that really do CONTAIN their
    // destination, which is the property Inbox never had.
}

/**
 * Bottom-navigation tab metadata — Portfolio · Markets · Workbench · People
 * (R-arc mandate §2; order per the owner's 2026-08-07 reshuffle, see [BtTab]).
 *
 * @param surface the §4.5 surface this tab is the entry point for — drives mode
 *   gating. Note the deliberate name mismatch on Workbench: its surface constant
 *   is [BtSurface.CONGLOMERATES], because that name mirrors the storage plan's
 *   §4.5 table verbatim and renaming it would drift the app from the document it
 *   implements for no user-visible gain (R1 decision O-2). The label is the
 *   user-facing name; the constant is the contract's.
 *
 * There is no `ownsItsHeader` flag any more: with Home retired as a tab (owner IA
 * change 2026-08-05) **every** tab drives its own [BtCollapsingHeader] against
 * its own scroll container, which is the only way a collapsing bar can work — the
 * shell cannot see a destination's scroll state, so a shell-drawn large title
 * could only ever be a tall static bar spending vertical space for nothing. The
 * shell therefore draws no top bar at all, and the flag that used to say which
 * tabs were exceptions had no `false` left to describe.
 */
private data class TabSpec(
    val tab: BtTab,
    val labelRes: Int,
    val icon: ImageVector,
    val surface: BtSurface,
    val badge: TabBadge = TabBadge.None,
)

/**
 * The bar, in bar order — Portfolio · Markets · Workbench · People.
 *
 * This list's order MUST equal [BtTab]'s declaration order, and
 * `TopBarNavigationTest.the shell's tab list is in BtTab declaration order` reads
 * both files and fails if they drift. The enum is the contract (it is what the
 * pure deep-link and swipe helpers reason about); this list is the same order
 * wearing its labels and icons. Before 2026-08-07 the two were kept in sync only
 * by a comment that claimed the shell read the enum — it never did.
 */
private val Tabs = listOf(
    // No badge: unread notifications moved to the bell (see [TabBadge]).
    TabSpec(BtTab.Portfolio, R.string.bt_tab_portfolio, BtIcons.Pie, BtSurface.PORTFOLIO),
    TabSpec(BtTab.Markets, R.string.bt_tab_markets, BtIcons.Assets, BtSurface.MARKET),
    TabSpec(BtTab.Workbench, R.string.bt_tab_workbench, BtIcons.Workbench, BtSurface.CONGLOMERATES, badge = TabBadge.Alerts),
    TabSpec(BtTab.People, R.string.bt_tab_people, BtIcons.People, BtSurface.SOCIAL, badge = TabBadge.Chat),
)

/**
 * The tabs this mode may show (S3/S4 plan §4.5, "absent, not greyed").
 *
 * A Drive-only install has no BetterTrack account, so People and the Workbench's
 * conglomerates are not features it is missing — they are features that cannot
 * exist for it. Rendering them disabled would turn part of the bottom bar into a
 * permanent advertisement for something the user deliberately opted out of;
 * dropping them leaves a bar where every entry works.
 *
 * The Markets tab stays: search and watchlists are DEGRADED rather than absent
 * (no live quotes until W6, device-local watchlist membership per board #40.3),
 * and each of those surfaces renders its own honest reduced state.
 *
 * Owner IA change 2026-08-05: with Home no longer a tab, a Drive-only bar is
 * Portfolio + Markets — and it loses nothing by it, because Overview (Home's
 * content) is now reached from inside the Portfolio tab, which every mode shows.
 * BtSurface.HOME is `FULL` in every mode and still gates that content; it simply
 * no longer gates a bar entry.
 */
private fun tabsFor(mode: StorageMode): List<TabSpec> {
    val visible = visibleTabSurfaces(mode)
    return Tabs.filter { it.surface in visible }
}

/**
 * What each tab puts in the ONE shared bar's variable slots — the entire
 * difference between the four tabs' headers, in one `when`.
 *
 * Writing it out here rather than letting each tab pass its own content is the
 * point of the hoist and not an accident of it. The bar has to be able to render
 * a tab that is **not composed** (the incoming side of a swipe is a frozen
 * bitmap, deliberately — see [BtTabPeekLayers]), so the answer cannot live in the
 * page. It also puts the R-arc's 3-element budget back under one roof: this
 * function is now the complete list of everything the tab chrome can say, which
 * is a thing a reviewer can read in six lines instead of four files.
 *
 * Only Portfolio's face is dynamic, and it is dynamic through [BtTabChrome]
 * rather than through a callback, so a Portfolio the user has swiped *away* from
 * still has a face to show while its page slides back in.
 */
private fun headerFaceOf(tab: BtTab?, chrome: BtTabChrome): BtTabHeaderFace = when (tab) {
    BtTab.Portfolio -> BtTabHeaderFace(
        selector = chrome.portfolioSelector,
        // Overview's ONE action (R1 decision O-3): search is the affordance the
        // whole app shares and Overview is the only screen that is about all of
        // it. A selected portfolio has no bar action — its verbs are in content.
        action = if (chrome.portfolioIsOverview) {
            BtTabHeaderAction.Search
        } else {
            BtTabHeaderAction.None
        },
    )

    // R1 put Messages in the shell bar as People's ONE action; R2 moved it into
    // People's own header; the hoist brings it back to the shell — same
    // affordance, same pixel, now drawn by the thing that owns the bar. The
    // unread COUNT stays off it (mandate §1): that is the People tab's dot.
    BtTab.People -> BtTabHeaderFace(action = BtTabHeaderAction.Messages)

    // Markets and Workbench carry brand and gear and nothing else — their own
    // subjects (the search field, the segment row) are the first thing in their
    // content, which is where a subject belongs when the bar is shared.
    else -> BtTabHeaderFace.Plain
}

/**
 * The BetterTrack app shell: a 3-element top bar, 5-tab bottom navigation, the
 * global offline-banner scaffold and the full typed navigation graph.
 *
 * ## The R-arc top-bar rule (mandate §1)
 *
 * The bar carries **(a) context/title, (b) ONE contextual action, (c) overflow —
 * nothing else**. Before R1 it carried six things on every tab: wordmark,
 * portfolio selector chip, search, chats+badge, bell+badge and settings. Each
 * had been a defensible local decision; together they were the accretion the
 * owner reacted to.
 *
 * Where the six went, so no capability was merely deleted:
 *  - **chat unread** → a dot on the People tab, with Messages as People's one
 *    action (the S6 P1-10 problem — "a new message announced itself nowhere" —
 *    solved at zero bar cost);
 *  - **triggered alerts** → a dot on the Workbench tab, fed by the same count
 *    the Workbench's own segment badge shows;
 *  - **notification bell** → one inbox entry in Home's overflow, count included;
 *  - **portfolio selector** → the Portfolio screen's own header (R1-B);
 *  - **search** → Home's one action (Markets already had a better in-content
 *    search field, and the top-bar glyph was a duplicate of it — S6 P1-11);
 *  - **settings** and **discreet mode** → Home's overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtApp() {
    val bt = BtTheme.colors
    val scope = rememberCoroutineScope()
    // The subpages' own navigator. Registered BEFORE the graph is built, because
    // that is when its destinations are instantiated. See [BtSheetNavigator] for
    // why the sheets left ComposeNavigator: a NavHost composes one destination at
    // a time, and a connected depth transition needs two.
    val sheetNavigator = remember { BtSheetNavigator() }
    val navController = rememberNavController(sheetNavigator)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // True when nothing is stacked over the tabs. It is the ONE question the shell
    // still asks the nav graph, and it replaces `isTopLevel`: the graph's floor is
    // an empty destination, so "the graph is at its floor" and "the user is looking
    // at a tab" are now the same fact.
    val sheetsClosed = currentDestination?.hasRoute(SheetRootRoute::class) != false

    // V5 W5: per-mode surface gating (plan §4.5). Read once here so the bars and
    // the pages below cannot disagree about what this install can do.
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    // The GATED mode, not the raw one: a release build resolves a stale stored
    // DRIVE/BOTH down to SERVER, and the bars must agree with the backend the app
    // is actually running on — otherwise a release APK with a leftover pref would
    // hide Social while still talking to the server.
    val storageMode = remember(storedMode) { AppGraph.gatedStorageMode(storedMode) }
    val visibleTabs = remember(storageMode) { tabsFor(storageMode) }
    val tabOrder = remember(visibleTabs) { visibleTabs.map { it.tab } }
    val showSocialSurfaces = storageMode.shows(BtSurface.SOCIAL)
    val showNotificationSurfaces = storageMode.shows(BtSurface.ALERTS_NOTIFICATIONS)

    // ── The pager, and everything that keeps four pages alive ────────────────
    //
    // These four objects are the whole of part A. The pager state is the single
    // coordinate every piece of chrome reads; the scopes give each page the view
    // model lifetime its nav entry used to give it; the live set is the lazy-init
    // gate that keeps a cold start paying for one page instead of four; and the
    // sheet stack is how a subpage tells the shell it is on top.
    val pagerState = rememberBtTabPagerState(tabOrder, BtTab.Portfolio)
    val tabScopes = rememberBtTabScopes()
    val live = remember { BtTabLiveSet(tabOrder.getOrNull(pagerState.currentPage)) }
    val sheets = remember(navController) {
        BtSheetHostState(
            pop = { navController.popBackStack() },
            // Stage two of the two-stage dismiss: everything goes, in one move,
            // back to the empty floor the pager shows through.
            popAll = { navController.popBackStack(SheetRootRoute, inclusive = false) },
        )
    }
    // The one channel by which the sheet layer tells the pages they are hidden.
    // See [BtOcclusion]: it is why a settled sheet no longer costs the GPU a whole
    // tab pager, a bottom bar and a header per frame.
    val occlusion = remember { BtOcclusion() }

    // The tab the pager has SETTLED on. Deliberately `settledPage` and not
    // `currentPage`: this drives the header's per-tab scroll state and the badge
    // gating, and `currentPage` flips at the halfway mark of a drag, so reading it
    // here would swap the bar's tonal state under a page still visibly in motion.
    val currentTab = visibleTabs.getOrNull(pagerState.settledPage)
    // The tab a bar tap would be a RE-tap of. A sheet covers the bar, so a tap can
    // only ever arrive with the stack closed; the check keeps that explicit rather
    // than implied.
    val exactTab = if (sheetsClosed) currentTab?.tab else null

    // The optimistic TAP latch, as a page index (owner ask 2026-08-08).
    //
    // The swipe latches are gone with the peek layer: a gesture and the bar read
    // the same `pagerState` in the same frame now, so there is nothing left for
    // them to disagree about and nothing to be optimistic about. A tap still needs
    // one, because `animateScrollToPage` is a journey and the ask was that the tap
    // be acknowledged on its own frame rather than when the journey ends. See
    // [tapLatchHolds] for when it must let go.
    var tapCommit by remember { mutableStateOf<Int?>(null) }
    var tapOrigin by remember { mutableIntStateOf(0) }

    // The channel between the four tabs and the ONE bar they share — see
    // [BtTabChrome]. Portfolio publishes its pill up it; every tab hangs the bar's
    // nested-scroll connection off it.
    val chrome = remember { BtTabChrome() }
    val headerBehavior = rememberBtTabHeaderBehavior(currentTab?.tab)
    SideEffect { chrome.headerScroll = headerBehavior.nestedScrollConnection }

    // Hop to a tab. ONE implementation for the bottom bar, the deep-link router
    // and Overview's cross-tab rows, so tapping, linking and swiping can never
    // mean three different things.
    //
    // REMEMBERED, and that is not tidiness (perf pass 2026-08-06, restated): this
    // lambda is handed to `BtSheetHost`, and a fresh instance on every
    // composition makes that composable unskippable — which also re-evaluates its
    // `NavGraphBuilder` lambda, invalidating `NavHost`'s internal
    // `remember(builder) { createGraph(...) }` and rebuilding all 46 typed
    // destinations, kotlinx-serialization route reflection and all. The pager
    // recomposes this shell once per hop, so an unremembered lambda here would
    // rebuild the whole graph on every swipe.
    val switchToTab: (BtTab) -> Unit = remember(tabOrder, pagerState, scope) {
        { tab ->
            val index = tabOrder.indexOf(tab)
            if (index >= 0 && index != pagerState.currentPage) {
                tapOrigin = pagerState.settledPage
                tapCommit = index
                scope.launch { pagerState.animateScrollToPage(index, animationSpec = TabHopSpec) }
            }
        }
    }

    // Wake the sleeping pages once the app is idle — see [TAB_WARMUP_DELAY_MS].
    // Nearest first, so the two the user is one swipe away from are ready before
    // the far one.
    LaunchedEffect(tabOrder) {
        val here = pagerState.settledPage
        tabOrder.getOrNull(here)?.let(live::wake)
        withFrameNanos {}
        delay(TAB_WARMUP_DELAY_MS)
        tabWarmOrder(tabOrder, here).forEach { tab ->
            if (!live.isLive(tab)) {
                live.wake(tab)
                delay(TAB_WARMUP_STAGGER_MS)
            }
        }
    }
    // ...and wake a page the instant a gesture aims at it, which is what makes the
    // warm-up schedule invisible: a swipe inside the startup window does not wait
    // for it. `targetPage` is the pager's own answer to "where is this going to
    // land", and it is written when the drag starts, not when it ends.
    LaunchedEffect(pagerState, tabOrder) {
        snapshotFlow { pagerState.targetPage }.collect { index ->
            tabOrder.getOrNull(index)?.let(live::wake)
        }
    }
    // Release the tap latch the moment it stops being believable. The timeout is
    // the same insurance it always carried: if a hop is refused outright (a
    // storage-mode change hides the target mid-tap) the pager never settles on the
    // target and never returns to the origin either.
    LaunchedEffect(tapCommit, pagerState.settledPage) {
        val target = tapCommit ?: return@LaunchedEffect
        if (!tapLatchHolds(target, tapOrigin, pagerState.settledPage)) {
            tapCommit = null
            return@LaunchedEffect
        }
        delay(600)
        tapCommit = null
    }
    // System back on a tab that is not the first one returns to the first tab, and
    // back from THERE exits — the semantic the nav graph used to give for free via
    // `popUpTo(startDestination)`, restated now that the tabs are not destinations.
    // Disabled while a sheet is up, where the sheet's own handler owns back.
    BackHandler(enabled = sheetsClosed && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0, animationSpec = TabHopSpec) }
    }

    // Notification deep-link routing (Step 16): shared by inbox taps AND tapped
    // system-push intents (surfaced via AppGraph.pendingDeepLink).
    // Remembered for the same reason as `switchToTab` above — it is the shell's
    // other lambda that crosses into the graph builder.
    val navigateDeepLink: (NotifDeepLink) -> Unit = remember(navController, scope, switchToTab) {
        { link ->
        // EVERY deep link lands the same way (S6 P1-8):
        //   1. clear the sheet stack, so the target does not open on top of
        //      whatever the user happened to have open — including the
        //      notifications inbox the tap may have come from;
        //   2. switch to the tab that OWNS the target (see `owningTab`);
        //   3. open the target's sheet over it, if the link has one.
        fun open(l: NotifDeepLink, push: (() -> Unit)? = null) {
            navController.popBackStack(SheetRootRoute, inclusive = false)
            // `null` = an account-level target that no tab owns (settings,
            // security, notification settings). Those open over whatever tab the
            // user is standing on rather than yanking them somewhere first —
            // owner's rule, and see `owningTab`'s KDoc for why it is safe here.
            owningTab(l)?.let(switchToTab)
            push?.invoke()
        }
        when (link) {
            NotifDeepLink.Social -> open(link)
            is NotifDeepLink.SharedPortfolio ->
                open(link) { navController.navigate(SharedPortfolioViewRoute(link.portfolioId)) }
            is NotifDeepLink.FriendOverview ->
                open(link) { navController.navigate(FriendOverviewRoute(link.userId, link.username)) }
            is NotifDeepLink.PublicProfile -> {
                // No userId on the wire (FCM friend.activity / follow.published).
                // Resolve the username against the friends list at tap time: a
                // friend opens their overview; anyone else (e.g. a non-friend
                // followee, which the app has no profile screen for) lands on the
                // Social tab — never a dead tap (mobile-push.md §4). The tab
                // switch happens NOW so the user is never left staring at the
                // wrong tab while the lookup is in flight.
                open(link)
                scope.launch {
                    val friend = (AppGraph.socialRepository.friends() as? BtResult.Ok)
                        ?.value?.firstOrNull { it.username.equals(link.username, ignoreCase = true) }
                    if (friend != null) navController.navigate(FriendOverviewRoute(friend.userId, friend.username))
                }
            }
            is NotifDeepLink.SharedConglomerate ->
                open(link) { navController.navigate(SharedConglomerateViewRoute(link.conglomerateId)) }
            is NotifDeepLink.Chat -> open(link) { navController.navigate(ChatListRoute) }
            is NotifDeepLink.Asset -> open(link) { navController.navigate(AssetPageRoute(link.assetId)) }
            is NotifDeepLink.Holding -> open(link) { navController.navigate(HoldingDetailRoute(link.assetId)) }
            // The alerts manager is a SEGMENT of the Workbench tab, not a route
            // of its own: switch to the tab and ask it to open that segment.
            NotifDeepLink.Alerts -> {
                WorkboardEntry.requestAlerts()
                open(link)
            }
            // Overview is a SELECTION inside the Portfolio tab, not a route:
            // assert the selection, then let `open` switch to the tab that
            // renders it. Same shape as Alerts directly above — the pairing IS
            // the destination, because the IA change left Overview addressable
            // only as "Portfolio tab, overview selected".
            NotifDeepLink.Overview -> {
                AppGraph.devicePrefs.setOverviewSelected(true)
                open(link)
            }
            // A portfolio widget: select THAT portfolio, then land on the tab
            // that renders it — the same pairing shape Overview uses above, and
            // the same order the switcher's own selectPortfolio uses (leave
            // Overview synchronously, persist the choice async). A stale id is
            // safe: resolveSelection falls back to the default portfolio.
            is NotifDeepLink.Portfolio -> {
                AppGraph.devicePrefs.setOverviewSelected(false)
                scope.launch { AppGraph.portfolioRepository.selectPortfolio(link.portfolioId) }
                open(link)
            }
            // The Budget widget: open the Cash sheet for the ledger it budgeted.
            // A null portfolioId lets CashScreen resolve the selected one, exactly
            // as the overview's own onOpenCash does.
            is NotifDeepLink.Cash ->
                open(link) { navController.navigate(CashRoute(portfolioId = link.portfolioId)) }
            // Quick-actions widget: straight into the blank buy form / the cash
            // screen — the shortcut's worth is being INSIDE in one tap.
            NotifDeepLink.AddTransaction ->
                open(link) { navController.navigate(TransactionFormRoute()) }
            NotifDeepLink.AddCashEntry ->
                open(link) { navController.navigate(CashRoute()) }
            // Search is the Markets tab's own first control — a pure switch.
            NotifDeepLink.MarketSearch -> open(link)
            NotifDeepLink.Settings -> open(link) { navController.navigate(SettingsRoute) }
            NotifDeepLink.Security -> open(link) { navController.navigate(SettingsSecurityRoute) }
            NotifDeepLink.NotificationSettings ->
                open(link) { navController.navigate(SettingsNotificationsRoute) }
            // The fallback destination for a tapped push with no specific target
            // — the same sheet the bell opens. See [NotifDeepLink.Inbox].
            NotifDeepLink.Inbox -> open(link) { navController.navigate(NotificationsInboxRoute) }
        }
        }
    }
    // A push tapped while the app was closed/backgrounded: MainActivity parked the
    // target; consume it once here (StateFlow so a cold tap is never lost).
    val pendingDeepLink by AppGraph.pendingDeepLink.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDeepLink) {
        pendingDeepLink?.let {
            navigateDeepLink(it)
            AppGraph.pendingDeepLink.value = null
        }
    }

    // Inbox unread count: refresh once on entry so the number next to Overview's
    // inbox row is live. Drive-only has no server to hold an inbox; asking would
    // be a guaranteed failed call on every launch, not a feature.
    val notifUnread by AppGraph.notificationRepository.unreadCount.collectAsStateWithLifecycle()
    LaunchedEffect(showNotificationSurfaces) {
        if (showNotificationSurfaces) AppGraph.notificationRepository.refresh()
    }

    // Chat was only reachable from a card inside the People tab — invisible from
    // the other tabs, and a new message announced itself nowhere (S6 P1-10). The
    // repository already keeps a server-derived total; the shell primes it once
    // and it now lights the People tab's dot instead of a top-bar icon.
    val chatUnread by AppGraph.chatRepository.totalUnread.collectAsStateWithLifecycle()
    LaunchedEffect(showSocialSurfaces) {
        if (showSocialSurfaces) AppGraph.chatRepository.refreshConversations()
    }

    // Triggered alerts: the Workbench tab's dot AND Overview's actionable row read
    // this one cached count, primed here so it is live from whichever tab the
    // user happens to open the app on. The repository does its own mode gating.
    val triggeredAlerts by AppGraph.alertsRepository.triggered.collectAsStateWithLifecycle()
    LaunchedEffect(storageMode) {
        AppGraph.alertsRepository.refreshTriggered(storageMode)
    }

    // One feedback idiom for the whole app (S6 P1-9). Hoisted here so every
    // screen — a tab page or inside a sheet — answers the same way, in the app's
    // own dark/gold styling, with room for a Retry action.
    val snackbar = rememberBtSnackbarState()

    // Discreet mode as a first-class quick toggle (mandate §5). Overview's quiet
    // tail is that home; the Settings row stays the canonical control.
    val discreetMode by AppGraph.discreetModeStore.enabled.collectAsStateWithLifecycle()
    // ONE implementation with THREE call sites — Overview's quick-links row, the
    // tail's discreet row, and its own Retry below — so they can never diverge on
    // what "flip discreet mode" means.
    //
    // A REMEMBERED anonymous function object rather than a local `fun` (perf pass
    // 2026-08-06): `::localFun` compiles to a fresh `Lambda` on every composition,
    // with no `equals`, which made the whole sheet graph unskippable and rebuilt
    // its typed destinations on every badge tick. `object : (Boolean) -> Unit`
    // rather than a lambda because the retry needs to name the act as the way out
    // of its own failure, and a lambda cannot refer to itself; `invoke` can.
    val toggleDiscreet: (Boolean) -> Unit = remember(scope, snackbar) {
        object : (Boolean) -> Unit {
            override fun invoke(wanted: Boolean) {
                // Flip locally FIRST — the point of masking is that amounts
                // vanish the instant the user asks, not a round-trip later.
                AppGraph.discreetModeStore.set(wanted)
                scope.launch {
                    val r = AppGraph.accountRepository.updateDiscreetMode(wanted)
                    if (r is BtResult.Err) {
                        // Roll back AND say why. Settings stays the canonical
                        // control and renders this same `asMessage()` line inline
                        // beside its row; the shell has no inline place for it, so
                        // it goes to the one feedback idiom instead.
                        AppGraph.discreetModeStore.set(!wanted)
                        snackbar.controller.showError(r.error.asMessage()) { invoke(wanted) }
                    }
                }
            }
        }
    }

    // The shell is a Box, not just a Scaffold, because the sheet layer has to be
    // able to cover the bottom bar as well as the pages. A full-screen sheet that
    // stopped at the Scaffold's content slot would be a full-screen sheet with a
    // navigation bar sitting on top of it.
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // OCCLUSION CULLING. While a sheet sits at its resting height it is
            // opaque and full-bleed from its top edge down, so everything below
            // that edge here is drawn only to be painted over. Clipping the draw
            // to the strip the sheet leaves showing makes HWUI reject the pager's,
            // the bottom bar's and the header's render nodes outright — real
            // raster skipped, not `alpha = 0`, which rasterises just the same.
            //
            // Pixel-identical, by construction: the clip line is the sheet's own
            // top edge plus its corner radius, so it only ever removes what the
            // sheet already covers. And because the read is a draw-phase read of a
            // derivedStateOf, the pages are back in the display list on the SAME
            // FRAME the sheet first moves — there is no fade to schedule and no
            // frame in which the wrong thing is on screen. See [BtOcclusion].
            modifier = Modifier.drawWithContent {
                val exposedTop = occlusion.exposedTopPx()
                if (exposedTop < 0f) {
                    drawContent()
                } else {
                    clipRect(bottom = exposedTop) { this@drawWithContent.drawContent() }
                }
            },
            containerColor = bt.bg,
            // The bars below consume their own system-bar insets, and the sheet
            // layer consumes its own. Zeroing here keeps the two from being paid
            // for twice.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { BtSnackbarHost(snackbar.hostState) },
            // No shell top bar: every tab drives its own collapsing header, and
            // Overview's — the last one the shell used to draw — now travels with
            // the Portfolio tab that hosts it.
            bottomBar = {
                // Drawn UNCONDITIONALLY now, where it used to be gated on
                // `isTopLevel`. A sheet covers it, so hiding it would buy nothing
                // and cost the thing that gating always costs: the page area
                // changing height the moment a sheet opens, which reflows all four
                // live pages and is visible in the strip above the sheet and again
                // on the way out. The bar is simply always there, and sometimes
                // something is in front of it.
                BtBottomBar(
                    tabs = visibleTabs,
                    // The ink and the selected weight, as 0f/1f. A tap latches this
                    // to its target on the tap's own frame; otherwise it follows
                    // the pager's `currentPage`, which flips at the halfway mark of
                    // a drag — the point at which the incoming page is the one you
                    // are mostly looking at.
                    //
                    // The PILL does not read this. It reads the pager's continuous
                    // position in the draw phase, so it travels with the pages
                    // instead of stepping between them — see [tabPillX]. Splitting
                    // the two is what lets the ink be cheap (a couple of
                    // recompositions per hop) while the travel stays frame-exact.
                    selectedIndex = tapCommit ?: pagerState.currentPage,
                    pager = pagerState,
                    // Pinned while a tap is travelling: the tap's whole promise is
                    // that the bar answers immediately, and a pill that set off on
                    // its own journey would arrive after the page did.
                    pinnedIndex = tapCommit,
                    // The badges the top bar used to carry, on the tabs that own
                    // them. Both are dots, not counts — see [BtTabBadgeDot].
                    hasBadge = { tab ->
                        when (tab.badge) {
                            TabBadge.None -> false
                            TabBadge.Chat -> showSocialSurfaces && chatUnread > 0
                            TabBadge.Alerts -> showNotificationSurfaces && triggeredAlerts > 0
                        }
                    },
                    // Owner directive 2026-08-07: tapping Portfolio while the
                    // Portfolio tab IS the current screen opens the switcher
                    // sheet instead of re-navigating to where you already are.
                    // A swipe never reaches this path, so a page change can never
                    // be mistaken for a re-tap.
                    onSelect = { tab ->
                        when (tabTapAction(tab.tab, exactTab)) {
                            TabTap.OpenPortfolioSwitcher -> PortfolioTabEntry.requestSwitcher()
                            TabTap.Switch -> switchToTab(tab.tab)
                        }
                    },
                )
            },
        ) { innerPadding ->
            CompositionLocalProvider(
                LocalBtSnackbar provides snackbar.controller,
                LocalBtTabChrome provides chrome,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    // ── The ONE tab bar (owner report 2026-08-07) ─────────────
                    //
                    // *"Since the header is consistent in all the main pages it
                    // shouldn't scroll [with the swipe]."*
                    //
                    // It sits HERE — above the paged area, outside everything that
                    // moves — which is the whole fix: the bar is static during a
                    // gesture because it is not in the thing being dragged. It is
                    // also above the offline banner, which it has to be: this bar
                    // consumes the status-bar inset (the shell's Scaffold zeroes
                    // its own), so anything drawn before it lands under the clock.
                    //
                    // Drawn unconditionally, for the same reason as the bottom bar.
                    BtTabHeader(
                        // Every tab's face at once, in bar order. The bar can now
                        // show a face for a page it is only partway onto — which is
                        // what the frozen-bitmap era could not do at all, and is
                        // why the faces were data rather than slots in the first
                        // place. See [BtTabHeader].
                        faces = remember(
                            tabOrder,
                            chrome.portfolioSelector,
                            chrome.portfolioIsOverview,
                        ) { tabOrder.map { headerFaceOf(it, chrome) } },
                        pager = pagerState,
                        scrollBehavior = headerBehavior,
                        onLongPressWordmark = {
                            if (BuildConfig.DEBUG) navController.navigate(GalleryRoute)
                        },
                        // The shell cannot reach the Portfolio view model, and does
                        // not need to: the switcher already has a shell-visible
                        // door, opened for the bottom bar's re-tap. One signal, two
                        // affordances, one implementation.
                        onOpenSwitcher = { PortfolioTabEntry.requestSwitcher() },
                        onAction = { action ->
                            when (action) {
                                BtTabHeaderAction.Search -> navController.navigate(SearchRoute)
                                BtTabHeaderAction.Messages -> navController.navigate(ChatListRoute)
                                BtTabHeaderAction.None -> Unit
                            }
                        },
                        // The bell, restored to the chrome (owner 2026-08-09). Same
                        // count the Portfolio tab's dot used to carry — that dot is
                        // retired, see [TabBadge].
                        unreadNotifications = notifUnread,
                        showInbox = showNotificationSurfaces,
                        onOpenInbox = { navController.navigate(NotificationsInboxRoute) },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                    )
                    // Global offline banner (§7.4): real connectivity + cached-data
                    // age. The gallery's debug toggle can still force it.
                    val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
                    val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
                        .collectAsStateWithLifecycle(initialValue = null)
                    if (!online || DebugPreviewState.showOfflineBanner) {
                        // §7.4: the indicator opens the Pending-sync sheet.
                        OfflineBanner(
                            asOfMs = dataAgeMs,
                            onClick = { navController.navigate(PendingSyncRoute) },
                        )
                    }
                    // The four pages. All of them, all the time.
                    BtTabPager(
                        tabs = tabOrder,
                        state = pagerState,
                        scopes = tabScopes,
                        live = live,
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        BtTabContent(
                            tab = tab,
                            navController = navController,
                            onDeepLink = navigateDeepLink,
                            onSwitchTab = switchToTab,
                            discreetMode = discreetMode,
                            onToggleDiscreet = toggleDiscreet,
                        )
                    }
                }
            }
        }
        // The sheet layer, over everything the Scaffold drew. At the graph's floor
        // this composes one empty destination: it lays out nothing and registers no
        // pointer input, so touches fall straight through to the pager beneath it.
        CompositionLocalProvider(
            LocalBtSnackbar provides snackbar.controller,
            LocalBtTabChrome provides chrome,
        ) {
            BtSheetHost(
                navController = navController,
                sheetNavigator = sheetNavigator,
                sheets = sheets,
                onDeepLink = navigateDeepLink,
                onSwitchTab = switchToTab,
                notifUnread = notifUnread,
                showNotifications = showNotificationSurfaces,
                occlusion = occlusion,
            )
        }
    }
}

/**
 * How a TAP travels between tabs.
 *
 * A tap is not a drag, so it has no finger to follow and has to choose its own
 * pace. Faster than a page turn used to be, because the destination is already
 * composed — the old 267ms the pill took to arrive was mostly the incoming tab
 * being BUILT on the same thread the animation was clocked by, and there is
 * nothing left to build.
 */
private val TabHopSpec = tween<Float>(durationMillis = 260, easing = FastOutSlowInEasing)

/**
 * One live tab page.
 *
 * This is the four `composable<...TabRoute>` bodies, unchanged, as one `when`.
 * They moved out of the graph rather than changing: the tabs are the same
 * screens, wired to the same lambdas, and every difference in this file is about
 * *where they live*, not what they do.
 */
@Composable
private fun BtTabContent(
    tab: BtTab,
    navController: NavHostController,
    onDeepLink: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
    discreetMode: Boolean,
    onToggleDiscreet: (Boolean) -> Unit,
) {
    when (tab) {
        BtTab.Portfolio ->
            // V5 S2a: a paranoid account's portfolio family is server-blind. Route
            // ONLY these killed surfaces to the explainer — Markets/People/
            // Workbench and everything under them keep working, because they do.
            // A tab page, so no onBack — the shell's own bars are showing.
            ParanoidGate {
                PortfolioOverviewScreen(
                    onOpenHolding = { assetId -> navController.navigate(HoldingDetailRoute(assetId)) },
                    onOpenTransactions = { portfolioId ->
                        navController.navigate(TransactionsRoute(portfolioId))
                    },
                    onNewTransaction = { portfolioId ->
                        navController.navigate(TransactionFormRoute(portfolioId = portfolioId))
                    },
                    onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
                    onOpenCash = { portfolioId ->
                        navController.navigate(CashRoute(portfolioId = portfolioId))
                    },
                    // ── Overview: the former Home tab, as a switcher selection ──
                    // Composed here rather than inside the portfolio screen so that
                    // screen stays ignorant of Home, and so Home's whole callback
                    // surface — which is all navigation — stays in the one file
                    // that owns it.
                    overviewContent = { openSwitcher, leaveOverview ->
                        // Overview crosses tabs ONLY through onOpen/onSwitchTab —
                        // see HomeScreen's KDoc. No `navController` is handed to
                        // it, deliberately.
                        HomeScreen(
                            onOpen = onDeepLink,
                            onSwitchTab = onSwitchTab,
                            // "See all holdings" / "open this portfolio": leave
                            // Overview for the portfolio page.
                            onOpenPortfolioView = leaveOverview,
                            // "Create a portfolio": open the switcher, which is
                            // where creation lives.
                            onCreatePortfolio = openSwitcher,
                            onOpenInbox = { navController.navigate(NotificationsInboxRoute) },
                            onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                            discreetMode = discreetMode,
                            onToggleDiscreet = onToggleDiscreet,
                        )
                    },
                    // The in-content door to one portfolio's own settings. The gear
                    // in the corner is the APP's settings and must keep meaning only
                    // that — so per-portfolio management gets a row in the page's
                    // management area instead. The switcher's overflow carries the
                    // second path.
                    onOpenPortfolioSettings = { portfolioId ->
                        navController.navigate(PortfolioSettingsRoute(portfolioId))
                    },
                    // The overview's "More insights" row — allocation and its
                    // future siblings live one page deep (owner batch 2026-08-16).
                    onOpenInsights = { portfolioId ->
                        navController.navigate(PortfolioInsightsRoute(portfolioId))
                    },
                )
            }

        BtTab.Markets ->
            MarketsTabScreen(
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenCustomAssets = { navController.navigate(CustomAssetsRoute) },
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onAddToWatchlist = { navController.navigate(SearchRoute) },
                onOpenMarketIntel = { navController.navigate(MarketIntelRoute) },
            )

        BtTab.Workbench ->
            WorkbenchTabScreen(
                onOpenConglomerate = { id -> navController.navigate(ConglomerateDetailRoute(id)) },
                onCreateConglomerate = { navController.navigate(ConglomerateBuilderRoute()) },
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
            )

        BtTab.People ->
            SocialScreen(
                onOpenFriend = { userId, username ->
                    navController.navigate(FriendOverviewRoute(userId, username))
                },
                onOpenChats = { navController.navigate(ChatListRoute) },
                onOpenChatWith = { friendUserId, username ->
                    navController.navigate(ChatThreadRoute(friendUserId = friendUserId, friendUsername = username))
                },
                onOpenGroups = { navController.navigate(FriendGroupsRoute) },
            )
    }
}

/**
 * **Overview's** ONE header action (R-arc mandate §1: context, ONE action,
 * overflow).
 *
 * Search is the affordance the whole app shares and Overview is the only screen
 * that is about all of it, so it keeps the slot it had on the Home tab's bar.
 *
 * ## Why this is a bare action and not a top bar any more
 *
 * R1 shipped a shell-drawn `BtHomeTopBar` covering four tabs, deliberately: the
 * *rule* is what has to be enforceable, and a per-screen bar is exactly how the
 * old one grew to six elements, one defensible addition at a time. R2 moved the
 * bars anyway, because a **collapsing** large title cannot be drawn by the shell
 * — `LargeTopAppBar` needs a scroll behaviour wired to the destination's own
 * scroll container, which the shell cannot see. Home was the one tab left on the
 * shell bar, because its context slot was the wordmark rather than a title and so
 * it had nothing to collapse.
 *
 * The owner IA change settles it: Overview is no longer a tab, it is a selection
 * inside the Portfolio tab, and it takes that tab's collapsing header with the
 * title **Overview** in the context slot. So the shell draws no bar at all, and
 * the two things Home's bar carried travel to the header as slots — this action
 * and [BtOverviewOverflow]. The 3-element budget is unchanged; only who renders
 * it moved.
 *
 * The wordmark went with the bar. It stopped being wayfinding the moment it sat
 * on four tabs, and a header whose job is to say *which switcher entry you are
 * looking at* cannot also say "BetterTrack". Its hidden long-press gallery entry
 * was the one thing worth keeping, and it is now an explicit debug-only row in
 * the overflow, where a debug affordance is easier to find than a secret.
 */
/*
 * `BtOverviewSearchAction` is gone with the per-page bars (hoist 2026-08-07).
 *
 * The action itself is untouched — Overview still owns exactly one, and it is
 * still search. It is simply no longer a composable this file passes DOWN into
 * the Portfolio tab for that tab to place in its own header, because there is no
 * per-tab header left to place it in. It is [BtTabHeaderAction.Search], declared
 * beside People's Messages in the one enum the shared bar renders from, and
 * routed by [headerFaceOf] — which is also the only way the bar can show it while
 * swiping ONTO a Portfolio tab that has not composed yet.
 */

/*
 * `BtOverviewOverflow` is deleted (nav restoration 2026-08-06, owner directive).
 *
 * It held four entries and every one of them already lived on the screen it hung
 * over, because the second-path rule was in force when it was written — which is
 * what made it removable rather than merely undesirable:
 *
 *   Notifications      -> Overview's "Needs you" card when there IS unread mail,
 *                         and unconditionally the Inbox row in Home's quiet tail,
 *                         which carries the same count.
 *   Discreet mode      -> the tail's discreet row (with its On/Off state) and
 *                         Settings -> Privacy, which is the canonical toggle.
 *   Settings           -> [BtSettingsGear], now on all four tab bars, plus the
 *                         tail's own Settings row.
 *   Component gallery  -> the wordmark's long-press (debug), restored with the
 *                         wordmark itself, and Settings -> Developer.
 *
 * The one thing that did NOT have a second home was the unread DOT the menu wore
 * on its ⋮. That signal moved to the Portfolio tab's bottom-bar dot
 * ([TabBadge.Inbox]) — a better address for it than the old one, since the bottom
 * bar is on screen from every tab while the ⋮ was only ever visible on Overview.
 */

/**
 * §6.2 metrics: the selection pill.
 *
 * **48dp, not M3's 56dp** (craft pass 2026-08-07). M3 sizes its indicator for a
 * Material glyph, which is drawn edge to edge in its 24dp box; the four Origin
 * glyphs are not. Measured on device at 2.625×, their ink is 15.6–17.9dp wide in
 * that 24dp box — about 73% fill against Material's ~87% — so a 56dp pill left
 * 19.3dp of air on each side of a 17.5dp glyph while leaving only 7.3dp above and
 * below it. A 2.7:1 padding asymmetry around a square-ish mark is what reads as a
 * lozenge with something small rattling inside it, and it is the geometry half of
 * the owner's *"still a little geeked"*.
 *
 * 48dp brings that to 15.3 : 7.3 (2.1:1) and still clears the widest glyph
 * (People, 17.9dp) by 15dp a side. The height is untouched: 32dp is set by the
 * 64dp bar's icon+label stack, not by the glyph.
 */
private val NAV_INDICATOR_WIDTH: Dp = 48.dp
private val NAV_INDICATOR_HEIGHT: Dp = 32.dp

/**
 * The bottom bar — the backbone the mandate keeps (§2), rebuilt to B2 §6.
 *
 * ## What was rough, and what each change answers
 *
 * 1. **`containerColor` was `bt.surface` — exactly the card colour.** The bar had
 *    no identity: cards floated on the bar's own tone and it read as a stuck card
 *    rather than as the app's frame. It now takes the dedicated
 *    [at.bettertrack.app.ui.theme.BtColors.navBar] tone, ΔL\* 9.4 above the page
 *    in dark, mirroring the web's own `--bt-nav` token.
 * 2. **It drew a 1px divider — the app's own retired idiom.** `BtGroup` states
 *    the rule ("tonal elevation instead of divider lines"), and the old hairline
 *    could not work anyway: `#262626` on `#171717` is a **1.28:1** step, a smudge
 *    rather than a rule. The top edge is now `groupBorder`: nothing in dark,
 *    where the bar's own tone separates it, and a real hairline in light, where
 *    ~5 L\* of ramp cannot.
 * 3. **80dp + divider + gesture inset ≈ 104dp of permanent chrome**, 13% of a
 *    360×800 viewport for four destinations. `ShortNavigationBar` is 64dp — 16dp
 *    reclaimed for free, and it is stable API in material3 1.4.0, not
 *    experimental. The label survives the cut by moving to
 *    [at.bettertrack.app.ui.theme.BtTypography.labelNav] (11sp): dropping labels
 *    is the exact Trade-Republic failure mode the owner named.
 * 4. **`PieChart` and `Dashboard` mushed** — both multi-part Material glyphs at a
 *    2.0dp stroke, sharing a silhouette, sitting next to each other. The four
 *    Origin glyphs ([BtIcons]) are the web's own nav set at a 1.6 stroke, and
 *    `pie` (two arcs) against `workbench` (three dots on rules) tell apart at a
 *    glance.
 *
 * ## The indicator is ONE layer, not four (§6.3)
 *
 * `ShortNavigationBarItem`'s per-item indicator is switched off
 * (`selectedIndicatorColor = Color.Transparent`) and the pill is drawn once, in
 * this composable's own `drawBehind`. Four independent indicators can only ever
 * cross-fade; one layer **translates**, so a tab change reads as the selection
 * travelling to where you sent it.
 *
 * ## §6.3's real pager, three architectures later
 *
 * §6.3 was written against a `HorizontalPager` the first swipe batch was expected
 * to introduce. It shipped a gesture layer instead — a pager would have had to
 * take the four tabs out of the nav graph, which at the time meant rebuilding
 * per-tab back stacks by hand — and the bar spent two batches approximating a
 * `currentPageOffsetFraction` it did not have: a lead derived from the outgoing
 * page's damped displacement, a spring for taps, and two latches to stop the nav
 * graph's one-frame lag from dragging the pill backwards mid-hop.
 *
 * All three are deleted. The pager is real ([BtTabPager]), so:
 *
 *  - **The pill reads the pager's position directly**, in the DRAW phase, and
 *    lands between two icon centres exactly where the pages are between two
 *    pages ([tabPillX]). No spring: there is nothing to catch up to, because the
 *    number it reads is the same number the pages are drawn from, in the same
 *    frame. No lead, no sign inversion, no `barWidthPx` standing in for a page
 *    width — the position *is* the answer.
 *  - **The handoff latch is gone.** It existed because a committed swipe told the
 *    nav graph and snapped the page offset to zero in one breath, and the graph
 *    answered a frame later; between those two the bar believed the tab the user
 *    had just left, and the pill fell back a whole step. There is no graph in
 *    this path at all now, so there is no window to be caught in.
 *  - **The tap latch stays**, and only that. `animateScrollToPage` is a real
 *    journey and the owner's ask was that a tap be acknowledged on its own frame;
 *    so [selectedIndex] carries the tap immediately for the ink and the weight,
 *    [pinnedIndex] holds the pill at the destination, and the pages travel.
 *
 * ## Geometry is measured, never assumed
 *
 * The pill's position comes from each icon slot reporting its own centre via
 * `onGloballyPositioned`, not from re-deriving M3's internal item metrics. That
 * survives a two-tab Drive-only bar, RTL, font scaling and any future change to
 * `ShortNavigationBar`'s padding tokens — all of which a hardcoded 6dp-from-top
 * would get silently wrong.
 *
 * [hasBadge] is a predicate rather than a count map on purpose: the bar renders
 * dots, so a count here would be information the component cannot use and the
 * caller would be free to get wrong. What "there is something on that tab" means
 * — including its per-mode gating — stays with the shell, which owns the flows.
 */
@Composable
private fun BtBottomBar(
    tabs: List<TabSpec>,
    /** Which tab wears the ink and the selected weight, right now. */
    selectedIndex: Int,
    /** The pager, read in the draw phase so a drag frame never recomposes the bar. */
    pager: PagerState,
    /** Hold the pill here instead of interpolating — a tap in flight. */
    pinnedIndex: Int?,
    hasBadge: (TabSpec) -> Boolean,
    onSelect: (TabSpec) -> Unit,
) {
    val bt = BtTheme.colors
    val density = LocalDensity.current

    // Measured geometry, kept in ROOT coordinates on purpose: onGloballyPositioned
    // fires children-first, so an icon cannot ask the bar where it is yet. Root
    // coordinates make both callbacks order-independent and the subtraction
    // happens at draw time, when both are known.
    var barOriginX by remember { mutableFloatStateOf(Float.NaN) }
    var barOriginY by remember { mutableFloatStateOf(Float.NaN) }
    val iconCentres = remember { mutableStateMapOf<Int, Offset>() }

    val indicatorW = with(density) { NAV_INDICATOR_WIDTH.toPx() }
    val indicatorH = with(density) { NAV_INDICATOR_HEIGHT.toPx() }
    val ringPx = with(density) { 1.dp.toPx() }

    // Where the pill sits vertically. One row, so any laid-out icon answers it;
    // taking the selected one keeps it correct while the bar's item count changes
    // under a storage-mode switch.
    val centreY: Float? = tabs.indices.firstNotNullOfOrNull { iconCentres[it]?.y }

    Column {
        // Nothing in dark (the bar's own tone separates it), a real hairline in
        // light. One token, the same rule BtGroup and BtCard follow.
        HorizontalDivider(thickness = 1.dp, color = bt.groupBorder)
        ShortNavigationBar(
            modifier = Modifier
                .onGloballyPositioned {
                    val p = it.positionInRoot()
                    barOriginX = p.x
                    barOriginY = p.y
                }
                .drawBehind {
                    // The bar's own container, painted here rather than by
                    // ShortNavigationBar, so the single indicator layer can sit
                    // between the container and the items. It must cover the
                    // gesture inset too, hence the full node rect.
                    drawRect(bt.navBar)

                    val y0 = centreY ?: return@drawBehind
                    if (barOriginX.isNaN()) return@drawBehind

                    // THE state read that makes the pill travel, and the only one
                    // in this component. It happens HERE — inside the draw lambda
                    // — so a drag frame costs a redraw of this one node and never
                    // a recomposition of the bar or of its four items.
                    val pos = tabPagerPosition(
                        pager.currentPage,
                        pager.currentPageOffsetFraction,
                        tabs.size,
                    )

                    // A tap is pinned at its destination; a drag interpolates.
                    // Both go through the same measured icon centres, so a bar
                    // with two items (Drive-only), a bar under RTL and a bar at a
                    // large font scale are all correct without a special case.
                    val x0 = pinnedIndex?.let { iconCentres[it]?.x }
                        ?: tabPillX(pos, tabs.size) { iconCentres[it]?.x }
                        ?: return@drawBehind

                    // Clamped to the outermost icon centres: `currentPageOffsetFraction`
                    // reports overscroll at the ends of the bar, and the pill has
                    // nowhere to put it. Read over `tabs.indices` rather than over
                    // the map's values, so a storage-mode change that shortens the
                    // bar cannot clamp against two stale entries.
                    val laid = tabs.indices.mapNotNull { iconCentres[it]?.x }
                    val minX = laid.minOrNull() ?: return@drawBehind
                    val maxX = laid.maxOrNull() ?: return@drawBehind
                    val x = x0.coerceIn(minX, maxX) - barOriginX
                    val y = y0 - barOriginY

                    val topLeft = Offset(x - indicatorW / 2f, y - indicatorH / 2f)
                    val size = Size(indicatorW, indicatorH)
                    val radius = CornerRadius(indicatorH / 2f)
                    drawRoundRect(bt.goldWashStrong, topLeft, size, radius)
                    // Light only: on white a 26% gold fill alone is a faint
                    // smudge, so the pill gets the edge the rule gives every
                    // other light container. `goldEdge` is already the ink hue
                    // in light — a pale gold hairline on white is invisible.
                    if (bt.isLight) {
                        drawRoundRect(bt.goldEdge, topLeft, size, radius, style = Stroke(ringPx))
                    }
                },
            // Painted in drawBehind above; M3 must not lay its own opaque
            // container over the indicator layer.
            containerColor = Color.Transparent,
        ) {
            tabs.forEachIndexed { index, tab ->
                // 0f/1f, deliberately, where the pill is continuous. The ink and
                // the weight are COMPOSITION values, so a continuous fraction
                // would recompose four items on every frame of a drag for a
                // difference nobody can read; the pill carries the travel, in the
                // draw phase, for free. The flip point is the pager's own
                // `currentPage`, i.e. the moment the incoming page is the one you
                // are mostly looking at.
                val selected = index == selectedIndex
                val fraction = if (selected) 1f else 0f
                val ink = lerp(bt.textMuted, bt.goldInk, fraction)
                ShortNavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(tab) },
                    icon = {
                        Box(
                            Modifier.onGloballyPositioned {
                                val p = it.positionInRoot()
                                iconCentres[index] = Offset(
                                    p.x + it.size.width / 2f,
                                    p.y + it.size.height / 2f,
                                )
                            },
                        ) {
                            Icon(tab.icon, contentDescription = null)
                            // Nudged onto the glyph's top-right corner rather
                            // than the item's, so the dot reads as belonging to
                            // the icon and does not drift into the neighbour's
                            // touch target on a narrow screen.
                            //
                            // ── Placed against the PILL, not just the glyph ──
                            //
                            // (Craft pass 2026-08-07.) The old offset put the
                            // dot's centre 12dp right and 10dp above the icon
                            // box's centre. With the 1.5dp ring that is a 6.5dp
                            // radius object whose outer edge reached 16.5dp from
                            // the pill's right cap centre — and the cap's radius
                            // is 16dp, so the ring poked half a dp THROUGH the
                            // pill's edge. On screen that is a badge that looks
                            // like it is falling out of its own container, which
                            // is the placement half of "geeked".
                            //
                            // (13, -7) from the box centre puts the ring 8.6dp
                            // from that cap centre, i.e. 0.9dp of clear pill all
                            // the way round — the dot sits IN the pill instead of
                            // straddling it. From the TopEnd anchor of a 24dp box
                            // a 10dp dot starts at (7, -7), so the nudge is x
                            // only. `onIndicator` gives its ring the pill's own
                            // fill on the selected tab; see [BtTabBadgeDot].
                            BtTabBadgeDot(
                                show = hasBadge(tab),
                                onIndicator = selected,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 6.dp, y = 0.dp),
                            )
                        }
                    },
                    label = {
                        // The selected label carries WEIGHT, not only hue (craft
                        // pass 2026-08-07). Both states used one 11sp Medium and
                        // let colour do the ranking, and on the white bar the
                        // colour ranks them backwards: `goldInk` #866419 sits at
                        // 4.97:1 on white while the unselected `textMuted`
                        // #56616D sits at 6.31:1, so the selected tab was the
                        // FAINTEST word in the row. The pill said "here" and the
                        // label quietly said "not here".
                        //
                        // Weight is the honest carrier — it ranks without
                        // spending contrast, and it leaves the gold free to mean
                        // brand rather than having to mean emphasis as well.
                        Text(
                            text = stringResource(tab.labelRes),
                            style = BtTheme.type.labelNav,
                            fontWeight = if (selected) FontWeight.SemiBold else null,
                        )
                    },
                    colors = ShortNavigationBarItemDefaults.colors(
                        // Both states carry the SAME lerped ink, because the
                        // ranking is already in `fraction`. Letting M3 pick by
                        // its boolean would re-introduce the cross-fade the
                        // single indicator layer exists to remove.
                        selectedIconColor = ink,
                        selectedTextColor = ink,
                        selectedIndicatorColor = Color.Transparent,
                        unselectedIconColor = ink,
                        unselectedTextColor = ink,
                    ),
                )
            }
        }
    }
}

/**
 * The sheet stack — every subpage in the app, over the four live tabs.
 *
 * ## What this graph still is, and what it stopped being
 *
 * It stopped being the app's page host. The four top-level pages left it for
 * [BtTabPager], where they stay composed forever; what is left here is the 45
 * subpages, and every one of them is now a full-screen sheet ([BtSheet]) drawn
 * OVER the tabs rather than instead of them.
 *
 * It is still a `NavHost`, and deliberately so. Everything the graph was actually
 * good at is untouched and is worth keeping: 49 typed `kotlinx.serialization`
 * routes with their arguments, a `ViewModelStore` and a `SavedStateRegistry` per
 * entry (so `viewModel(key = "friend-$id")` still dies with the screen that asked
 * for it), the system-back dispatcher, and process-death restore. The things it
 * was carrying that no longer fit — `popUpTo(start){saveState}` + `restoreState`,
 * the per-tab back stacks, the lateral tab-hop motion — are gone because the
 * thing they served is gone. A subpage is not *under* a tab any more.
 *
 * @param sheets the live sheet stack, so `onBack` can reach the top sheet.
 */
@Composable
private fun BtSheetHost(
    navController: NavHostController,
    sheetNavigator: BtSheetNavigator,
    sheets: BtSheetHostState,
    onDeepLink: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
    // `discreetMode` / `onToggleDiscreet` were removed 2026-08-09: declared here,
    // handed a live lambda by the caller, and never invoked by any of the 47
    // sheets. Discreet mode keeps both of its real doorways — Home's tail row
    // (`HomeScreen`, fed from this same hoisted pair) and Settings → Preferences,
    // which reads `AppGraph.discreetModeStore` directly. Nothing user-facing
    // moved; only the third, unused thread through the sheet layer is gone.
    /** Inbox unread count, rendered on Overview's inbox row. */
    notifUnread: Int,
    showNotifications: Boolean,
    /** Passed straight to the sheet layer, which is the only thing that can fill it. */
    occlusion: BtOcclusion,
) {
    // What all 45 screens' `onBack` runs.
    //
    // It asks the TOP SHEET to leave rather than popping the graph, and the
    // difference is the whole dismissal: a bare `popBackStack()` deletes the
    // destination from composition on the spot, so the sheet would vanish instead
    // of travelling off the bottom. The pop belongs at the END of that travel, and
    // only the sheet knows when it has finished — so the sheet owns it, and this
    // is how a screen asks for it. Unchanged in shape: still `() -> Unit`, so no
    // screen signature moved.
    val back: () -> Unit = { sheets.dismissTop() }
    CompositionLocalProvider(LocalBtSheetHost provides sheets) {
    NavHost(
        navController = navController,
        // The graph has no motion left to contribute. Its sheets are not its own
        // any more — they belong to [BtSheetNavigator] and are drawn by
        // [BtSheetStack], which owns every frame of their travel because a drag
        // and a transition cannot share the job. What is left in here is one
        // empty floor that never animates into or out of anything.
        enterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        // An empty floor. The tabs are not in this graph any more, so its start
        // destination has no page to show — its whole job is to be something for
        // the last sheet to pop back TO. It draws nothing and takes no pointer
        // input, so the live pager underneath is fully interactive through it
        // whenever the sheet stack is empty.
        startDestination = SheetRootRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        composable<SheetRootRoute> { }

        // Tabs
        btSheet<FriendGroupsRoute> {
            FriendGroupsScreen(onBack = back)
        }

        // S6 P2-19: LoginRoute / AppLockRoute were registered here as "Under
        // construction" placeholders. Nothing navigates to either — auth and the
        // app lock are BtRoot gates that run OUTSIDE this graph — so they are gone.

        // Portfolio
        btSheet<HoldingDetailRoute> { entry ->
            // V5 S2a: portfolio-scoped detail is part of the server-blind family.
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<HoldingDetailRoute>()
                HoldingDetailScreen(
                    assetId = route.holdingId,
                    onBack = back,
                    onNewTransaction = { portfolioId, assetId ->
                        navController.navigate(
                            TransactionFormRoute(portfolioId = portfolioId, assetId = assetId),
                        )
                    },
                    onEditSynced = { txId ->
                        navController.navigate(TransactionFormRoute(transactionId = txId))
                    },
                    onEditQueued = { opId ->
                        navController.navigate(TransactionFormRoute(opId = opId))
                    },
                    onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
                    onOpenCustomAsset = { customAssetId ->
                        navController.navigate(CustomAssetDetailRoute(customAssetId))
                    },
                    onOpenAssetPage = { assetId ->
                        navController.navigate(AssetPageRoute(assetId))
                    },
                )
            }
        }
        btSheet<TransactionsRoute> { entry ->
            // V5 S2a: portfolio-scoped detail is part of the server-blind family.
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<TransactionsRoute>()
                TransactionsScreen(
                    routePortfolioId = route.portfolioId,
                    onBack = back,
                    onEditSynced = { txId ->
                        navController.navigate(TransactionFormRoute(transactionId = txId))
                    },
                    onEditQueued = { opId ->
                        navController.navigate(TransactionFormRoute(opId = opId))
                    },
                    onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
                )
            }
        }
        btSheet<TransactionFormRoute> { entry ->
            val route = entry.toRoute<TransactionFormRoute>()
            TransactionFormScreen(route = route, onBack = back)
        }
        btSheet<CashRoute> { entry ->
            // V5 S2a: portfolio-scoped detail is part of the server-blind family.
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<CashRoute>()
                CashScreen(
                    routePortfolioId = route.portfolioId,
                    editOpId = route.editOpId,
                    onBack = back,
                    onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
                    onOpenTags = { navController.navigate(CashTagsRoute) },
                    onOpenRules = { navController.navigate(CashRulesRoute) },
                    onOpenStandingOrders = {
                        navController.navigate(StandingOrdersRoute(route.portfolioId))
                    },
                    // The decrowded overview's two subpage doors (owner batch
                    // 2026-08-16). The ledger door carries the source filter so
                    // the list opens on what the switcher was showing.
                    onOpenLedger = { portfolioId, sourceId ->
                        navController.navigate(CashLedgerRoute(portfolioId, sourceId))
                    },
                    onOpenBudgets = { portfolioId ->
                        navController.navigate(CashBudgetsRoute(portfolioId))
                    },
                )
            }
        }
        btSheet<CashLedgerRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<CashLedgerRoute>()
                at.bettertrack.app.ui.cash.CashLedgerScreen(
                    routePortfolioId = route.portfolioId,
                    initialSourceId = route.sourceId,
                    onBack = back,
                    onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
                )
            }
        }
        btSheet<CashBudgetsRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<CashBudgetsRoute>()
                at.bettertrack.app.ui.cash.CashBudgetsScreen(
                    routePortfolioId = route.portfolioId,
                    onBack = back,
                )
            }
        }
        // V5 S2c. The cash-classification layer and standing orders are
        // server-only surfaces over portfolio data, so they ride the same
        // paranoid guard as the rest of that family: a paranoid account has no
        // server-side ledger to classify or schedule against.
        btSheet<CashTagsRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.cash.CashTagsScreen(onBack = back)
            }
        }
        btSheet<CashRulesRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.cash.CashRulesScreen(onBack = back)
            }
        }
        btSheet<StandingOrdersRoute> { entry ->
            ParanoidGate(onBack = back) {
                val soRoute = entry.toRoute<StandingOrdersRoute>()
                at.bettertrack.app.ui.standingorders.StandingOrdersScreen(
                    routePortfolioId = soRoute.portfolioId,
                    onBack = back,
                )
            }
        }
        btSheet<CustomAssetsRoute> {
            CustomAssetsScreen(
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(CustomAssetDetailRoute(assetId)) },
            )
        }
        btSheet<CustomAssetDetailRoute> { entry ->
            val route = entry.toRoute<CustomAssetDetailRoute>()
            CustomAssetDetailScreen(assetId = route.assetId, onBack = back)
        }

        // Market
        btSheet<AssetPageRoute> { entry ->
            val route = entry.toRoute<AssetPageRoute>()
            AssetPageScreen(
                assetId = route.assetId,
                onBack = back,
                onTrade = { assetId, symbol, name, currency, pid, sell ->
                    navController.navigate(
                        TransactionFormRoute(
                            portfolioId = pid,
                            assetId = assetId,
                            assetSymbol = symbol,
                            assetName = name,
                            assetCurrency = currency,
                            sell = sell,
                        ),
                    )
                },
            )
        }
        btSheet<SearchRoute> {
            SearchScreen(
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onTrade = { assetId, symbol, name, currency, pid ->
                    navController.navigate(
                        TransactionFormRoute(
                            portfolioId = pid,
                            assetId = assetId,
                            assetSymbol = symbol,
                            assetName = name,
                            assetCurrency = currency,
                        ),
                    )
                },
            )
        }
        // V5 S2c: portfolio-wide market intel (earnings + dividend calendars,
        // projected income, news digest) — reached from the Assets tab.
        btSheet<MarketIntelRoute> {
            MarketIntelScreen(
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
            )
        }
        // Workboard
        // S6 P2-19: ConglomerateListRoute is gone — the list is a SEGMENT of the
        // Workboard tab (WorkboardScreen composes ConglomerateListScreen directly),
        // never a route of its own. V5 S2c added an Ideas segment the same way;
        // only the idea DETAIL is a destination.
        btSheet<IdeaDetailRoute> { entry ->
            val route = entry.toRoute<IdeaDetailRoute>()
            IdeaDetailScreen(
                ideaId = route.ideaId,
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
            )
        }
        btSheet<ConglomerateBuilderRoute> { entry ->
            val route = entry.toRoute<ConglomerateBuilderRoute>()
            ConglomerateBuilderScreen(
                conglomerateId = route.conglomerateId,
                onBack = back,
                // Replace this sheet with the detail sheet, rather than stacking
                // the result on top of the form that produced it: a back press
                // from the new conglomerate should land on the Workbench, not
                // back inside the builder that just saved.
                onSaved = { id ->
                    navController.popBackStack()
                    navController.navigate(ConglomerateDetailRoute(id))
                },
            )
        }
        btSheet<ConglomerateDetailRoute> { entry ->
            val route = entry.toRoute<ConglomerateDetailRoute>()
            ConglomerateDetailScreen(
                conglomerateId = route.conglomerateId,
                onBack = back,
                onEdit = { id -> navController.navigate(ConglomerateBuilderRoute(id)) },
                onDelete = back,
            )
        }

        // Social — per-friend overview (Social v2) + read-only friend-shared views (§6.9)
        btSheet<FriendOverviewRoute> { entry ->
            val route = entry.toRoute<FriendOverviewRoute>()
            FriendOverviewScreen(
                friendUserId = route.userId,
                username = route.username,
                onBack = back,
                onOpenChat = { uid, un ->
                    navController.navigate(ChatThreadRoute(friendUserId = uid, friendUsername = un))
                },
                onOpenSharedPortfolio = { id -> navController.navigate(SharedPortfolioViewRoute(id)) },
                onOpenSharedWatchlist = { id -> navController.navigate(SharedWatchlistViewRoute(id)) },
                onOpenSharedConglomerate = { id -> navController.navigate(SharedConglomerateViewRoute(id)) },
                // A cloned idea is the caller's OWN idea from the moment it
                // exists, so it opens on the ordinary owner-only detail route —
                // there is no "shared idea" screen to send it to, and that is
                // exactly why cloning is the affordance in the first place.
                onOpenIdea = { ideaId -> navController.navigate(IdeaDetailRoute(ideaId)) },
            )
        }
        btSheet<SharedPortfolioViewRoute> { entry ->
            val route = entry.toRoute<SharedPortfolioViewRoute>()
            SharedPortfolioViewScreen(portfolioId = route.portfolioId, onBack = back)
        }
        btSheet<SharedWatchlistViewRoute> { entry ->
            val route = entry.toRoute<SharedWatchlistViewRoute>()
            SharedWatchlistViewScreen(watchlistId = route.watchlistId, onBack = back)
        }
        btSheet<SharedConglomerateViewRoute> { entry ->
            val route = entry.toRoute<SharedConglomerateViewRoute>()
            SharedConglomerateViewScreen(conglomerateId = route.conglomerateId, onBack = back)
        }
        btSheet<ChatListRoute> {
            ChatListScreen(
                onBack = back,
                onOpenConversation = { id, username ->
                    navController.navigate(ChatThreadRoute(conversationId = id, friendUsername = username))
                },
                onStartWithFriend = { friendUserId, username ->
                    navController.navigate(ChatThreadRoute(friendUserId = friendUserId, friendUsername = username))
                },
            )
        }
        btSheet<ChatThreadRoute> { entry ->
            val route = entry.toRoute<ChatThreadRoute>()
            ChatThreadScreen(
                conversationId = route.conversationId,
                friendUserId = route.friendUserId,
                friendUsername = route.friendUsername,
                onBack = back,
                // Share-chip taps resolve through the EXISTING read paths: a viewable
                // chip opens the friend-shared read-only view (or the asset page).
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onOpenSharedPortfolio = { id -> navController.navigate(SharedPortfolioViewRoute(id)) },
                onOpenSharedWatchlist = { id -> navController.navigate(SharedWatchlistViewRoute(id)) },
                onOpenSharedConglomerate = { id -> navController.navigate(SharedConglomerateViewRoute(id)) },
            )
        }
        btSheet<NotificationsInboxRoute> {
            NotificationsInboxScreen(onBack = back, onDeepLink = onDeepLink)
        }

        // Settings — account + logout surface; Security section is Step 17, the
        // rest grows in Step 18.
        btSheet<SettingsRoute> {
            SettingsScreen(
                onBack = back,
                onOpenSecurity = { navController.navigate(SettingsSecurityRoute) },
                onOpenNotifications = { navController.navigate(SettingsNotificationsRoute) },
                onOpenChangePassword = { navController.navigate(ChangePasswordRoute) },
                onOpenLanguage = { navController.navigate(SettingsLanguageRoute) },
                onOpenWidgets = { navController.navigate(SettingsWidgetsRoute) },
                onOpenTaxSettings = { navController.navigate(TaxSettingsRoute) },
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                onOpenDeleteAccount = { navController.navigate(DeleteAccountRoute) },
                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                onOpenGallery = { navController.navigate(GalleryRoute) },
                onOpenSyncDebug = { navController.navigate(SyncDebugRoute) },
                onOpenServer = { navController.navigate(ServerRoute) },
                onOpenConnections = { navController.navigate(ConnectionsRoute) },
                onOpenAuthorizedApps = { navController.navigate(AuthorizedAppsRoute) },
            )
        }
        btSheet<ChangelogRoute> { ChangelogScreen(onBack = back) }
        btSheet<StorageHomeRoute> {
            at.bettertrack.app.ui.storage.WhereYourDataLivesScreen(onBack = back)
        }

        // ── Management parity 2026-08-06 ─────────────────────────────────────
        //
        // Every one of these is portfolio- or chain-SCOPED and takes its subject
        // from the route rather than from the ambient switcher selection, so a
        // selection change while one of them is open cannot silently retarget
        // the settings the user is editing.
        //
        // They sit behind ParanoidGate for the same reason the rest of the
        // portfolio family does: a paranoid account's server-side portfolio
        // routes are 403 by a platform route guard, so these screens have
        // nothing to show and must not pretend otherwise.

        btSheet<PortfolioSettingsRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<PortfolioSettingsRoute>()
                at.bettertrack.app.ui.portfolio.PortfolioSettingsScreen(
                    portfolioId = route.portfolioId,
                    onBack = back,
                    onOpenTax = { navController.navigate(PortfolioTaxRoute(it)) },
                    onOpenTaxReports = { navController.navigate(TaxYearsRoute(it)) },
                    onOpenGroup = { navController.navigate(ChainManageRoute(it)) },
                    onOpenFriendGroups = { navController.navigate(FriendGroupsRoute) },
                    // A deleted portfolio has no settings screen to return to.
                    onDeleted = back,
                )
            }
        }

        btSheet<PortfolioInsightsRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<PortfolioInsightsRoute>()
                at.bettertrack.app.ui.portfolio.PortfolioInsightsScreen(
                    portfolioId = route.portfolioId,
                    onBack = back,
                )
            }
        }

        btSheet<TaxSettingsRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.tax.TaxSettingsScreen(
                    onBack = back,
                    // The one exception to the "subject from the route" rule
                    // above, and it is not one: this screen resolves the §6.1
                    // selection itself and NAMES it on the row, so the id handed
                    // over here is the one the user just read.
                    onOpenTaxReports = { navController.navigate(TaxYearsRoute(it)) },
                )
            }
        }

        btSheet<PortfolioTaxRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<PortfolioTaxRoute>()
                at.bettertrack.app.ui.tax.PortfolioTaxScreen(
                    portfolioId = route.portfolioId,
                    onBack = back,
                )
            }
        }

        btSheet<TaxYearsRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<TaxYearsRoute>()
                at.bettertrack.app.ui.tax.TaxYearsScreen(
                    portfolioId = route.portfolioId,
                    onBack = back,
                    onOpenYear = { year ->
                        navController.navigate(TaxYearRoute(route.portfolioId, year))
                    },
                )
            }
        }

        btSheet<TaxYearRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<TaxYearRoute>()
                at.bettertrack.app.ui.tax.TaxYearDetailScreen(
                    portfolioId = route.portfolioId,
                    year = route.year,
                    onBack = back,
                )
            }
        }

        btSheet<ChainManageRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<ChainManageRoute>()
                at.bettertrack.app.ui.mirrorchain.ChainManageScreen(
                    chainId = route.chainId,
                    onBack = back,
                )
            }
        }
        btSheet<SettingsSecurityRoute> {
            SecurityScreen(
                onBack = back,
                onSetupPin = { navController.navigate(AppLockSetupRoute(change = false)) },
                onChangePin = { navController.navigate(AppLockSetupRoute(change = true)) },
                onOpenTwoFactor = { navController.navigate(TwoFactorRoute) },
                onOpenSessions = { navController.navigate(ActiveSessionsRoute) },
            )
        }
        btSheet<AppLockSetupRoute> { entry ->
            val route = entry.toRoute<AppLockSetupRoute>()
            AppLockSetupScreen(change = route.change, onDone = back, onBack = back)
        }
        btSheet<SettingsNotificationsRoute> { NotificationSettingsScreen(onBack = back) }
        btSheet<SettingsLanguageRoute> { LanguageScreen(onBack = back) }
        btSheet<SettingsWidgetsRoute> {
            at.bettertrack.app.ui.settings.WidgetsScreen(onBack = back)
        }
        btSheet<SettingsAboutRoute> {
            AboutScreen(onBack = back, onOpenChangelog = { navController.navigate(ChangelogRoute) })
        }
        // Connections & authorized apps, native (owner order 2026-08-08). Drive
        // is NOT reimplemented here — the Connections screen hands off in-app to
        // "Where your data lives", which already owns the vault's media set.
        btSheet<ConnectionsRoute> {
            at.bettertrack.app.ui.connections.ConnectionsScreen(
                onBack = back,
                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
            )
        }
        btSheet<AuthorizedAppsRoute> {
            at.bettertrack.app.ui.connections.AuthorizedAppsScreen(onBack = back)
        }
        btSheet<ChangePasswordRoute> { ChangePasswordScreen(onBack = back) }
        btSheet<TwoFactorRoute> { TwoFactorScreen(onBack = back) }
        btSheet<ActiveSessionsRoute> { ActiveSessionsScreen(onBack = back) }
        btSheet<DeleteAccountRoute> { DeleteAccountScreen(onBack = back) }

        // Sync & debug
        btSheet<PendingSyncRoute> {
            PendingSyncScreen(
                onBack = back,
                onEditTxOp = { opId ->
                    navController.navigate(TransactionFormRoute(opId = opId))
                },
                onEditCashOp = { opId, portfolioId ->
                    navController.navigate(CashRoute(portfolioId = portfolioId, editOpId = opId))
                },
            )
        }
        btSheet<GalleryRoute> {
            GalleryScreen(
                onClose = back,
                onOpenSyncDebug = { navController.navigate(SyncDebugRoute) },
            )
        }
        btSheet<ServerRoute> {
            ServerScreen(onBack = back)
        }
        btSheet<SyncDebugRoute> {
            SyncDebugScreen(
                onClose = back,
                onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
            )
        }
    }
    // THE sheet layer. One container, composed once, for every subpage in the
    // app — it renders the top TWO entries of the sheet back stack so a depth
    // change is a slide between two live planes rather than a hand-over between
    // two destinations. At the floor it draws nothing and takes no pointer
    // input, so the pager underneath is fully interactive through it.
    BtSheetStack(
        host = sheets,
        pages = rememberBtSheetPages(sheetNavigator),
        occlusion = occlusion,
    )
    }
}
