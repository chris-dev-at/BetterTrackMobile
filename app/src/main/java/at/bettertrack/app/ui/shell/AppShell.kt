package at.bettertrack.app.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import at.bettertrack.app.navigation.BtTab
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
import at.bettertrack.app.navigation.CustomAssetDetailRoute
import at.bettertrack.app.navigation.CustomAssetsRoute
import at.bettertrack.app.navigation.DeleteAccountRoute
import at.bettertrack.app.navigation.FriendGroupsRoute
import at.bettertrack.app.navigation.FriendOverviewRoute
import at.bettertrack.app.navigation.GalleryRoute
import at.bettertrack.app.navigation.HoldingDetailRoute
import at.bettertrack.app.navigation.IdeaDetailRoute
import at.bettertrack.app.navigation.MarketIntelRoute
import at.bettertrack.app.navigation.MarketsTabRoute
import at.bettertrack.app.navigation.NotificationsInboxRoute
import at.bettertrack.app.navigation.PendingSyncRoute
import at.bettertrack.app.navigation.PeopleTabRoute
import at.bettertrack.app.navigation.PortfolioSettingsRoute
import at.bettertrack.app.navigation.PortfolioTabRoute
import at.bettertrack.app.navigation.PortfolioTaxRoute
import at.bettertrack.app.navigation.SearchRoute
import at.bettertrack.app.navigation.ServerRoute
import at.bettertrack.app.navigation.SettingsAboutRoute
import at.bettertrack.app.navigation.SettingsLanguageRoute
import at.bettertrack.app.navigation.SettingsNotificationsRoute
import at.bettertrack.app.navigation.SettingsRoute
import at.bettertrack.app.navigation.SettingsSecurityRoute
import at.bettertrack.app.navigation.SharedConglomerateViewRoute
import at.bettertrack.app.navigation.SharedPortfolioViewRoute
import at.bettertrack.app.navigation.SharedWatchlistViewRoute
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
import at.bettertrack.app.navigation.WorkbenchTabRoute
import at.bettertrack.app.navigation.owningTab
import at.bettertrack.app.navigation.tabNeighbour
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
import kotlin.reflect.KClass
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

    /**
     * Unread notifications — inherited from Overview's ⋮ when that menu was
     * dissolved (nav restoration 2026-08-06).
     *
     * It belongs on **Portfolio** because that is the tab the inbox is reached
     * from: Overview lives inside it, and Overview is where both entry points to
     * the inbox are (the "Needs you" card and the quiet tail's Inbox row). The
     * old home for this dot was the ⋮ glyph itself, which meant the app could
     * only tell you something was waiting while you were already looking at the
     * screen that would have told you anyway.
     */
    Inbox,
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
    val routeClass: KClass<*>,
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
    TabSpec(BtTab.Portfolio, PortfolioTabRoute::class, R.string.bt_tab_portfolio, BtIcons.Pie, BtSurface.PORTFOLIO, badge = TabBadge.Inbox),
    TabSpec(BtTab.Markets, MarketsTabRoute::class, R.string.bt_tab_markets, BtIcons.Assets, BtSurface.MARKET),
    TabSpec(BtTab.Workbench, WorkbenchTabRoute::class, R.string.bt_tab_workbench, BtIcons.Workbench, BtSurface.CONGLOMERATES, badge = TabBadge.Alerts),
    TabSpec(BtTab.People, PeopleTabRoute::class, R.string.bt_tab_people, BtIcons.People, BtSurface.SOCIAL, badge = TabBadge.Chat),
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
@Composable
fun BtApp() {
    val bt = BtTheme.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // Which top-level tab is showing, if any. Resolved once: the top bar needs
    // the SPEC (its title, its one action), not just the yes/no the bars used to
    // share, and two independent lookups would be free to disagree.
    val currentTab = Tabs.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }
    val isTopLevel = currentTab != null
    // The tab whose route the current destination IS, as opposed to the tab it
    // merely lives UNDER (`currentTab`, which matches through the hierarchy so a
    // pushed detail keeps its tab lit). Only an exact match counts as a re-tap —
    // see [tabTapAction].
    val exactTab = Tabs.firstOrNull { tab ->
        currentDestination?.hasRoute(tab.routeClass) == true
    }?.tab
    // V5 W5: per-mode surface gating (plan §4.5). Read once here so the bars and
    // the routes below cannot disagree about what this install can do.
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    // The GATED mode, not the raw one: a release build resolves a stale stored
    // DRIVE/BOTH down to SERVER, and the bars must agree with the backend the app
    // is actually running on — otherwise a release APK with a leftover pref would
    // hide Social while still talking to the server.
    val storageMode = remember(storedMode) { AppGraph.gatedStorageMode(storedMode) }
    val visibleTabs = remember(storageMode) { tabsFor(storageMode) }
    val showSocialSurfaces = storageMode.shows(BtSurface.SOCIAL)
    val showNotificationSurfaces = storageMode.shows(BtSurface.ALERTS_NOTIFICATIONS)

    // Notification deep-link routing (Step 16): shared by inbox taps AND tapped
    // system-push intents (surfaced via AppGraph.pendingDeepLink).
    val scope = rememberCoroutineScope()
    // Switch to a top-level TAB with bottom-bar semantics. Hoisted out of the
    // deep-link handler because Home needs it too: Home is an index whose rows
    // are owned by other tabs, so "go to Workbench's alerts" must land exactly
    // the way the bottom bar and a notification tap do, not as a bare push.
    val switchToTab: (BtTab) -> Unit = remember(navController) {
        { tab ->
            navController.navigate(tab.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // ONE displacement, read by the outgoing page, the incoming page and the
    // bottom bar's indicator. See [BtTabSwipeState].
    val swipeState = rememberBtTabSwipeState()
    // The last frame each tab drew, so the neighbour can be ON SCREEN during the
    // drag without being composed a second time. See [BtTabPeekLayers] for why a
    // second composition is not an option in this app.
    val peekLayers = rememberBtTabPeekLayers()
    // Where a hop in this direction would land, or null at the ends of the bar.
    // Consulted DURING the drag too: it is what decides between a 1:1 follow and
    // the damped overscroll hint.
    val neighbourOf: (Boolean) -> BtTab? = { forward ->
        currentTab?.tab?.let { tabNeighbour(it, forward, visibleTabs.map { spec -> spec.tab }) }
    }
    // A committed swipe routes through the SAME [switchToTab] the bottom bar
    // uses, which is what keeps saved state, restored back stacks and bar
    // selection identical whether you tapped or swiped.
    val onSwipeCommit: (BtTab) -> Unit = { tab -> switchToTab(tab) }
    // The second page, or null for "draw one page". Pure — see [swipePeekTab].
    val peekTab = swipePeekTab(
        handoff = swipeState.handoff,
        peekSide = swipeState.peekSide,
        current = currentTab?.tab,
        visible = visibleTabs.map { spec -> spec.tab },
    )
    // Freeze the face of the tab the user is LEAVING.
    //
    // This instant is the only one that works. Later, the tab root is gone and
    // its recorded drawing commands resolve against render nodes that have been
    // handed to something else (see [BtTabPeekLayers] — the layer would replay
    // as a copy of whatever is on screen now). Earlier would freeze a page the
    // user then carried on scrolling. Here the tab is complete AND still on its
    // way out, so the picture is both correct and current.
    //
    // It fires for pushed screens too (`exactTab` goes null): opening a holding
    // and coming back leaves Portfolio's face as it was before the push, which
    // is what the next swipe should show.
    var lastTabRoot by remember { mutableStateOf<BtTab?>(null) }
    LaunchedEffect(exactTab) {
        val leaving = lastTabRoot
        lastTabRoot = exactTab
        if (leaving != null && leaving != exactTab) peekLayers.freeze(leaving)
    }
    // Drop the handoff pin once the NavHost is actually showing the tab the
    // swipe committed to. Until then the peek covers the swap, which is what
    // makes the swap invisible: the pixels under it are already identical.
    //
    // The timeout is not decoration. If the hop is ever refused (a mode change
    // that hides the target tab mid-gesture, say) `currentTab` never becomes
    // `handoff`, and without this the peek would cover the app forever.
    LaunchedEffect(swipeState.handoff, currentTab?.tab) {
        val target = swipeState.handoff ?: return@LaunchedEffect
        if (currentTab?.tab == target) {
            // Two frames, so the NavHost has certainly DRAWN the new page — not
            // merely composed it — before the cover comes off.
            withFrameNanos {}
            withFrameNanos {}
        } else {
            delay(600)
        }
        swipeState.handoff = null
        swipeState.peekSide = null
    }
    val navigateDeepLink: (NotifDeepLink) -> Unit = remember(navController, scope, switchToTab) {
        // EVERY deep link lands the same way (S6 P1-8):
        //   1. drop the notifications inbox (inclusive) if the tap came from it, so
        //      it is never saved under a tab's restored state — a no-op on a
        //      cold-start push tap, where the inbox isn't on the stack;
        //   2. switch to the tab that OWNS the target (see `owningTab`) with
        //      bottom-bar semantics — a plain push would stack the detail on
        //      whatever tab happened to be selected, and the next bottom-bar tap
        //      would pop+restore it and bounce the user straight back;
        //   3. push the detail route on top of that tab, if the link has one.
        fun open(link: NotifDeepLink, push: (() -> Unit)? = null) {
            navController.popBackStack(NotificationsInboxRoute, inclusive = true)
            // `null` = an account-level target that no tab owns (settings,
            // security, notification settings). Those push over whatever tab the
            // user is standing on rather than yanking them somewhere first —
            // owner's rule, and see `owningTab`'s KDoc for why it is safe here.
            owningTab(link)?.let(switchToTab)
            push?.invoke()
        }
        val handler: (NotifDeepLink) -> Unit = { link ->
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
                NotifDeepLink.Settings -> open(link) { navController.navigate(SettingsRoute) }
                NotifDeepLink.Security -> open(link) { navController.navigate(SettingsSecurityRoute) }
                NotifDeepLink.NotificationSettings ->
                    open(link) { navController.navigate(SettingsNotificationsRoute) }
            }
        }
        handler
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

    // Inbox unread count: refresh once on entry so the number next to Home's
    // overflow inbox entry is live. The bell that used to carry this is gone
    // (mandate §1) but the count is not — it moved, it did not die.
    // Drive-only has no server to hold an inbox; asking would be a guaranteed
    // failed call on every launch, not a feature.
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

    // Triggered alerts: the Workbench tab's dot AND Home's actionable row read
    // this one cached count, primed here so it is live from whichever tab the
    // user happens to open the app on. The repository does its own mode gating.
    val triggeredAlerts by AppGraph.alertsRepository.triggered.collectAsStateWithLifecycle()
    LaunchedEffect(storageMode) {
        AppGraph.alertsRepository.refreshTriggered(storageMode)
    }

    // One feedback idiom for the whole app (S6 P1-9). Hoisted here so every
    // screen — top-level or pushed, inside a sheet or not — answers the same
    // way, in the app's own dark/gold styling, with room for a Retry action.
    // Declared ahead of the shell's own action lambdas below: they report
    // through it, so it has to exist before they capture it.
    val snackbar = rememberBtSnackbarState()

    // Discreet mode as a first-class quick toggle (mandate §5: "give it a sane
    // home, e.g. overflow or profile, not bar chrome"). Home's overflow is that
    // home; the Settings row stays the canonical control.
    val discreetMode by AppGraph.discreetModeStore.enabled.collectAsStateWithLifecycle()
    // ONE implementation with THREE call sites — Home's overflow item, Home's
    // in-content quick-links row (Fable's rule: an overflow entry may never be
    // the only path to its act), and its own Retry below — so they can never
    // diverge on what "flip discreet mode" means.
    //
    // Written as a REMEMBERED anonymous function object rather than the local
    // `fun` it used to be (perf pass 2026-08-06). `::localFun` compiles to a
    // fresh `Lambda` instance on every composition, with no `equals`, so
    // `onToggleDiscreet` was a different value each time and `BtNavHost` could
    // never skip. That is not a cheap miss: an unskippable `BtNavHost` also
    // re-evaluates its `NavGraphBuilder` lambda, which invalidates `NavHost`'s
    // internal `remember(builder) { createGraph(...) }` and rebuilds the whole
    // 43-destination typed graph — kotlinx-serialization route reflection and
    // all — on every navigation and every badge tick. Remembering it makes the
    // instance stable and the whole subtree skippable again.
    //
    // An `object : (Boolean) -> Unit` rather than a lambda because the retry
    // needs to name the act as the way out of its own failure, and a lambda
    // cannot refer to itself; `invoke` can.
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
                        // it goes to the one feedback idiom instead. Letting the
                        // revert speak for itself — as this call site did before
                        // the app-wide snackbar existed — leaves the user watching
                        // the switch flick back with no reason given, which is the
                        // exact silence S6 P1-9 set out to remove.
                        AppGraph.discreetModeStore.set(!wanted)
                        snackbar.controller.showError(r.error.asMessage()) { invoke(wanted) }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = bt.bg,
        // The bars below consume their own system-bar insets; full-screen
        // destinations (gallery, settings, placeholders) run their own Scaffold.
        // Zeroing here prevents double status-bar padding on those routes.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { BtSnackbarHost(snackbar.hostState) },
        // No shell top bar: every tab drives its own collapsing header, and
        // Overview's — the last one the shell used to draw — now travels with the
        // Portfolio tab that hosts it. See [BtOverviewOverflow].
        bottomBar = {
            if (isTopLevel) {
                BtBottomBar(
                    tabs = visibleTabs,
                    // §6.3's arbiter, stated at the call site: the NAV GRAPH owns
                    // the settled selection, so this is 0f/1f. The gesture layer
                    // owns only the in-flight lead, and only inside the bar's own
                    // draw phase. Two writers, and neither can be mistaken for
                    // the other. A future pager returns intermediate values here
                    // and nothing else changes.
                    selectionFraction = { tab ->
                        if (currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true) {
                            1f
                        } else {
                            0f
                        }
                    },
                    swipe = swipeState,
                    // The badges the top bar used to carry, on the tabs that own
                    // them. Both are dots, not counts — see [BtTabBadgeDot].
                    hasBadge = { tab ->
                        when (tab.badge) {
                            TabBadge.None -> false
                            TabBadge.Chat -> showSocialSurfaces && chatUnread > 0
                            TabBadge.Alerts -> showNotificationSurfaces && triggeredAlerts > 0
                            TabBadge.Inbox -> showNotificationSurfaces && notifUnread > 0
                        }
                    },
                    // Owner directive 2026-08-07: tapping Portfolio while the
                    // Portfolio tab IS the current screen opens the switcher
                    // sheet instead of re-navigating to where you already are.
                    // A swipe never reaches this path (it calls switchToTab
                    // directly, and only when the neighbour differs), so a page
                    // change can never be mistaken for a re-tap.
                    onSelect = { tab ->
                        when (tabTapAction(tab.tab, exactTab)) {
                            TabTap.OpenPortfolioSwitcher -> PortfolioTabEntry.requestSwitcher()
                            TabTap.Switch -> switchToTab(tab.tab)
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        CompositionLocalProvider(LocalBtSnackbar provides snackbar.controller) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Global offline banner (§7.4): real connectivity + cached-data age.
            // The gallery's debug toggle can still force it for visual checks.
            val online by AppGraph.connectivityMonitor.isOnline.collectAsStateWithLifecycle()
            val dataAgeMs by AppGraph.portfolioRepository.portfolioDataAgeMs
                .collectAsStateWithLifecycle(initialValue = null)
            if (isTopLevel && (!online || DebugPreviewState.showOfflineBanner)) {
                // §7.4: the indicator opens the Pending-sync screen.
                OfflineBanner(
                    asOfMs = dataAgeMs,
                    onClick = { navController.navigate(PendingSyncRoute) },
                )
            }
            // The page area, and the surface the tab drag is detected on.
            //
            // The gesture lives on this Box rather than on the NavHost because
            // the two page layers are SIBLINGS inside it: the outgoing page (the
            // NavHost) and the incoming one (the peek). Detecting on the common
            // parent also keeps chart-scrub precedence exactly as it was —
            // children still see every pointer first, in the Main pass, and a
            // change they consume aborts this layer's slop detection.
            //
            // Owner ask 2026-08-07: swipe left/right between the four tabs. Only
            // on a top-level page — a pushed screen keeps its whole horizontal
            // axis for its own content. The neighbour is resolved against the
            // VISIBLE bar, so a Drive-only install swipes Portfolio ↔ Markets
            // and stops there rather than landing on a tab it does not render.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .btTabSwipe(
                        state = swipeState,
                        enabled = isTopLevel,
                        neighbourOf = neighbourOf,
                        onCommit = onSwipeCommit,
                    ),
            ) {
                BtNavHost(
                    navController = navController,
                    onDeepLink = navigateDeepLink,
                    onSwitchTab = switchToTab,
                    discreetMode = discreetMode,
                    onToggleDiscreet = toggleDiscreet,
                    notifUnread = notifUnread,
                    showNotifications = showNotificationSurfaces,
                    swipeState = swipeState,
                    peekLayers = peekLayers,
                )
                // The incoming page. Composed ONLY while a drag or its settle is
                // in flight (`peekTab` is null at rest, by construction), so an
                // app sitting still draws exactly one page.
                if (peekTab != null) {
                    BtTabPeek(
                        tab = peekTab,
                        // `handoff` pins the peek at rest, where the side no
                        // longer matters; `peekSide` is the live drag's.
                        forward = swipeState.peekSide ?: true,
                        layers = peekLayers,
                        state = swipeState,
                    )
                }
            }
        }
        }
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
@Composable
private fun BtOverviewSearchAction(onSearch: () -> Unit) {
    val bt = BtTheme.colors
    IconButton(onClick = onSearch) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = stringResource(R.string.bt_search_cd),
            tint = bt.textSecondary,
        )
    }
}

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

/** §6.2 metrics: the selection pill, unchanged from M3's own indicator tokens. */
private val NAV_INDICATOR_WIDTH: Dp = 56.dp
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
 * this composable's own `drawBehind`, positioned by [selectionFraction]. Four
 * independent indicators can only ever cross-fade; one layer **translates**, so
 * a tab change reads as the selection travelling to where you sent it.
 *
 * ## Two writers, one arbiter — and the honest version of it
 *
 * §6.3 was written against a `HorizontalPager` that Batch 1 was expected to
 * introduce. Batch 1 shipped something else: a **gesture layer** ([btTabSwipe]),
 * because a pager would have had to take the four tab routes out of the nav
 * graph and rebuild per-tab back stacks by hand. So there is no
 * `currentPageOffsetFraction` to read, and the adaptation is:
 *
 *  - **Rest is nav-graph truth.** [selectionFraction] is fed by
 *    `currentDestination.hierarchy`, so it is 0f/1f — the exact fallback §6.3
 *    prescribes. It also drives the per-item icon/label `lerp`, in composition,
 *    where it is cheap because it only changes when the destination does.
 *  - **In flight, the pill leads by exactly what the page moved.** The gesture
 *    layer displaces the outgoing page by at most 40dp, damped; the pill travels
 *    the same *fraction of one item step* that the page travelled *of one page
 *    width* — `-pageOffsetPx / barWidthPx`, read in the draw phase so a drag
 *    frame costs a redraw and not a recomposition. The sign is inverted because
 *    content and indicator move opposite ways, which is the pager idiom.
 *
 * That proportion is the point. Letting the pill run the full step while the
 * page nudges 11% would have the bar arrive somewhere the content never went —
 * a bar that lies about the gesture, which is worse than a bar that does not
 * move. When a real pager lands, [selectionFraction] starts returning true
 * intermediate values and this composable needs no edit.
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
    selectionFraction: (TabSpec) -> Float,
    hasBadge: (TabSpec) -> Boolean,
    onSelect: (TabSpec) -> Unit,
    swipe: BtTabSwipeState,
) {
    val bt = BtTheme.colors
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()

    // Measured geometry, kept in ROOT coordinates on purpose: onGloballyPositioned
    // fires children-first, so an icon cannot ask the bar where it is yet. Root
    // coordinates make both callbacks order-independent and the subtraction
    // happens at draw time, when both are known.
    var barOriginX by remember { mutableFloatStateOf(Float.NaN) }
    var barOriginY by remember { mutableFloatStateOf(Float.NaN) }
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val iconCentres = remember { mutableStateMapOf<Int, Offset>() }

    val indicatorW = with(density) { NAV_INDICATOR_WIDTH.toPx() }
    val indicatorH = with(density) { NAV_INDICATOR_HEIGHT.toPx() }
    val ringPx = with(density) { 1.dp.toPx() }

    // Where the pill belongs at rest: the fraction-weighted centroid of the icon
    // centres. With 0f/1f fractions that is simply the selected tab's centre.
    val restCentre: Offset? = run {
        var weight = 0f
        var x = 0f
        var y = 0f
        tabs.forEachIndexed { index, tab ->
            val f = selectionFraction(tab)
            if (f <= 0f) return@forEachIndexed
            val centre = iconCentres[index] ?: return@forEachIndexed
            weight += f
            x += f * centre.x
            y += f * centre.y
        }
        if (weight > 0f) Offset(x / weight, y / weight) else null
    }

    // The travel. Snapped on the first placement (nothing to animate towards yet)
    // and under reduced motion; sprung otherwise, which is what makes a TAP slide
    // rather than jump — the same win the swipe gets, for free.
    val travelX = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(restCentre, reducedMotion) {
        val target = restCentre ?: return@LaunchedEffect
        if (!placed || reducedMotion) {
            travelX.snapTo(target.x)
            placed = true
        } else {
            travelX.animateTo(
                target.x,
                spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            )
        }
    }

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
                    barWidthPx = it.size.width.toFloat()
                }
                .drawBehind {
                    // The bar's own container, painted here rather than by
                    // ShortNavigationBar, so the single indicator layer can sit
                    // between the container and the items. It must cover the
                    // gesture inset too, hence the full node rect.
                    drawRect(bt.navBar)

                    val centre = restCentre
                    if (!placed || centre == null || barOriginX.isNaN()) return@drawBehind

                    // One item step, measured. Zero on a one-tab bar, which
                    // correctly disables the lead rather than dividing by it.
                    val first = if (tabs.size >= 2) iconCentres[0] else null
                    val second = if (tabs.size >= 2) iconCentres[1] else null
                    val step = if (first != null && second != null) second.x - first.x else 0f

                    // The in-flight lead: the same fraction of a step that the
                    // page moved of a page. Pure, and unit-tested — see
                    // [tabIndicatorLead], which is also where the sign inversion
                    // is argued.
                    val lead = tabIndicatorLead(swipe.pageOffsetPx, barWidthPx)

                    // Clamping to the outermost icon centres is what stops the
                    // pill drifting off the end of the bar when the user swipes
                    // past the last tab and the gesture layer springs back.
                    //
                    // Read over `tabs.indices` and not over the map's values: the
                    // map is keyed by index and survives a change in how many
                    // tabs the bar shows. A storage-mode change (FULL → Drive,
                    // which drops People and the Workbench) would otherwise leave
                    // two stale entries behind and clamp the pill to a bar that
                    // is no longer there.
                    val live = tabs.indices.mapNotNull { iconCentres[it]?.x }
                    val minX = live.minOrNull() ?: return@drawBehind
                    val maxX = live.maxOrNull() ?: return@drawBehind
                    val x = (travelX.value + lead * step).coerceIn(minX, maxX) - barOriginX
                    val y = centre.y - barOriginY

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
                val fraction = selectionFraction(tab)
                val ink = lerp(bt.textMuted, bt.goldInk, fraction)
                ShortNavigationBarItem(
                    selected = fraction >= 0.5f,
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
                            BtTabBadgeDot(
                                show = hasBadge(tab),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 5.dp, y = (-3).dp),
                            )
                        }
                    },
                    label = {
                        Text(stringResource(tab.labelRes), style = BtTheme.type.labelNav)
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
 * One top-level tab's page, recording its own face for the swipe peek.
 *
 * Wrapping the four tab roots individually — rather than the NavHost once — is
 * what keeps a frozen face clean: around the NavHost the recording would also
 * capture `AnimatedContent` mid-transition, so a tab frozen as it left would be
 * frozen half-replaced by the tab arriving. Here each tab records only itself.
 *
 * Pushed screens are deliberately NOT wrapped. A holding detail lives under the
 * Portfolio tab and would otherwise become Portfolio's face, so a swipe onto
 * Portfolio would show a detail screen that is not even where the swipe lands.
 */
@Composable
private fun BtTabPage(
    peekLayers: BtTabPeekLayers,
    tab: BtTab,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().btTabPeekCapture(peekLayers, tab)) { content() }
}

@Composable
private fun BtNavHost(
    navController: NavHostController,
    onDeepLink: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
    /** Discreet-mode state + its one implementation, shared with Overview's ⋮. */
    discreetMode: Boolean,
    onToggleDiscreet: (Boolean) -> Unit,
    /** Inbox unread count, rendered on Overview's overflow entry. */
    notifUnread: Int,
    showNotifications: Boolean,
    /** Shared with the bottom bar so page and indicator move together. */
    swipeState: BtTabSwipeState,
    /** Where each tab records the face a swipe shows — see [BtTabPeekLayers]. */
    peekLayers: BtTabPeekLayers,
) {
    val back: () -> Unit = { navController.popBackStack() }
    // A swipe that committed has already MOVED the pages into their final
    // position before the NavHost is told anything, so the swap must not animate
    // on top of that — a lateral slide here would be a second page turn over a
    // picture that is already correct, which is exactly the double-draw the
    // handoff exists to remove. Tap-driven hops are untouched and still slide.
    val handingOff = swipeState.handoff != null
    // R3 §1 — the app's one screen-transition idiom. Read once, here, and
    // captured by the four lambdas below: they are plain (non-composable)
    // lambdas, so the reduced-motion preference cannot be sampled inside them.
    val reducedMotion = rememberReducedMotion()
    NavHost(
        navController = navController,
        // Which of BtNavMotion's two shapes a navigation gets is decided by the
        // PAIR of routes, never by the destination entry — see BtNavMotion's
        // KDoc. All four lambdas ask the same question because a tab hop can
        // arrive as a pop: the bottom bar navigates with
        // `popUpTo(startDestination) { saveState = true }`, so hopping back to
        // Home runs popEnter/popExit, not enter/exit.
        enterTransition = {
            when {
                reducedMotion || handingOff -> EnterTransition.None
                BtNavMotion.isLateral(
                    initialState.destination.route,
                    targetState.destination.route,
                ) -> BtNavMotion.lateralEnter(
                    BtNavMotion.lateralForward(
                        initialState.destination.route,
                        targetState.destination.route,
                    ),
                )

                else -> BtNavMotion.forwardEnter()
            }
        },
        exitTransition = {
            when {
                reducedMotion || handingOff -> ExitTransition.None
                BtNavMotion.isLateral(
                    initialState.destination.route,
                    targetState.destination.route,
                ) -> BtNavMotion.lateralExit(
                    BtNavMotion.lateralForward(
                        initialState.destination.route,
                        targetState.destination.route,
                    ),
                )

                else -> BtNavMotion.forwardExit()
            }
        },
        popEnterTransition = {
            when {
                reducedMotion || handingOff -> EnterTransition.None
                BtNavMotion.isLateral(
                    initialState.destination.route,
                    targetState.destination.route,
                ) -> BtNavMotion.lateralEnter(
                    BtNavMotion.lateralForward(
                        initialState.destination.route,
                        targetState.destination.route,
                    ),
                )

                else -> BtNavMotion.backEnter()
            }
        },
        popExitTransition = {
            when {
                reducedMotion || handingOff -> ExitTransition.None
                BtNavMotion.isLateral(
                    initialState.destination.route,
                    targetState.destination.route,
                ) -> BtNavMotion.lateralExit(
                    BtNavMotion.lateralForward(
                        initialState.destination.route,
                        targetState.destination.route,
                    ),
                )

                else -> BtNavMotion.backExit()
            }
        },
        // Owner IA change: Portfolio is the start destination again — but for a
        // reason it did not have the first time round. It is not "first by
        // accident of declaration order" now; it is the tab that hosts Overview,
        // the app's front door, so system-back from any tab lands on the index
        // and back from there exits. It is also `FULL` in every storage mode, so
        // the two `popUpTo(findStartDestination())` call sites — the deep-link
        // tab switch and the bottom-bar tap — can never pop to a hidden tab.
        startDestination = PortfolioTabRoute,
        // The outgoing page. The gesture itself is detected on the parent Box
        // (see BtApp) because the incoming page is this one's sibling; all this
        // layer does is follow the shared displacement. Each TAB ROOT records
        // its own pixels separately — see [BtTabPage].
        modifier = Modifier
            .fillMaxSize()
            .btTabPageOffset { swipeState.pageOffsetPx },
    ) {
        // Tabs
        composable<PortfolioTabRoute> {
            BtTabPage(peekLayers, BtTab.Portfolio) {
                // V5 S2a: a paranoid account's portfolio family is server-blind. Route
                // ONLY these killed surfaces to the explainer — Markets/People/
                // Workbench and everything under them keep working, because they do.
                // Top-level tab: no onBack — the shell's own bars are showing.
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
                        // Composed here rather than inside the portfolio screen so
                        // that screen stays ignorant of Home, and so Home's whole
                        // callback surface — which is all navigation — stays in the
                        // one file that owns the graph.
                        overviewContent = { openSwitcher, leaveOverview ->
                            // Overview crosses tabs ONLY through onOpen/onSwitchTab —
                            // see HomeScreen's KDoc. No `navController` is handed to
                            // it, deliberately: a bare push from an index screen
                            // stacks another tab's detail on it and the next
                            // bottom-bar tap saves it under the wrong tab (S6 P1-8).
                            HomeScreen(
                                onOpen = onDeepLink,
                                onSwitchTab = onSwitchTab,
                                // "See all holdings" / "open this portfolio": leave
                                // Overview for the portfolio page. This used to be
                                // `onSwitchTab(BtTab.Portfolio)`, which after the IA
                                // change would be a hop to the tab we are already on
                                // — i.e. a no-op the user would read as a dead tap.
                                onOpenPortfolioView = leaveOverview,
                                // "Create a portfolio": open the switcher, which is
                                // where creation lives. Sending the user to an empty
                                // portfolio page and hoping they find the sheet was
                                // the old behaviour and it was never good; it is
                                // simply impossible now, since the page they would
                                // land on is the one they are already looking at.
                                onCreatePortfolio = openSwitcher,
                                onOpenInbox = { navController.navigate(NotificationsInboxRoute) },
                                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                                discreetMode = discreetMode,
                                onToggleDiscreet = onToggleDiscreet,
                            )
                        },
                        overviewAction = {
                            // Overview's ONE action (R1 decision O-3): search is the
                            // affordance the whole app shares and Overview is the
                            // only screen that is about all of it.
                            BtOverviewSearchAction(onSearch = { navController.navigate(SearchRoute) })
                        },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        // The in-content door to one portfolio's own settings. The
                        // gear in the corner is the APP's settings and must keep
                        // meaning only that — so per-portfolio management gets a row
                        // in the page's management area instead, where it sits next
                        // to Cash and Transactions as another thing about THIS
                        // portfolio. The switcher's ⋮ carries the second path.
                        onOpenPortfolioSettings = { portfolioId ->
                            navController.navigate(PortfolioSettingsRoute(portfolioId))
                        },
                        onLongPressWordmark = {
                            if (BuildConfig.DEBUG) navController.navigate(GalleryRoute)
                        },
                    )
                }
            }
        }
        composable<MarketsTabRoute> {
            BtTabPage(peekLayers, BtTab.Markets) {
                MarketsTabScreen(
                    onOpenSearch = { navController.navigate(SearchRoute) },
                    onOpenCustomAssets = { navController.navigate(CustomAssetsRoute) },
                    onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                    onAddToWatchlist = { navController.navigate(SearchRoute) },
                    onOpenMarketIntel = { navController.navigate(MarketIntelRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
        }
        composable<PeopleTabRoute> {
            BtTabPage(peekLayers, BtTab.People) {
                SocialScreen(
                    onOpenFriend = { userId, username ->
                        navController.navigate(FriendOverviewRoute(userId, username))
                    },
                    onOpenChats = { navController.navigate(ChatListRoute) },
                    onOpenChatWith = { friendUserId, username ->
                        navController.navigate(ChatThreadRoute(friendUserId = friendUserId, friendUsername = username))
                    },
                    onOpenGroups = { navController.navigate(FriendGroupsRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
        }
        composable<FriendGroupsRoute> {
            FriendGroupsScreen(onBack = back)
        }
        composable<WorkbenchTabRoute> {
            BtTabPage(peekLayers, BtTab.Workbench) {
                WorkbenchTabScreen(
                    onOpenConglomerate = { id -> navController.navigate(ConglomerateDetailRoute(id)) },
                    onCreateConglomerate = { navController.navigate(ConglomerateBuilderRoute()) },
                    onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
        }

        // S6 P2-19: LoginRoute / AppLockRoute were registered here as "Under
        // construction" placeholders. Nothing navigates to either — auth and the
        // app lock are BtRoot gates that run OUTSIDE this graph — so they are gone.

        // Portfolio
        composable<HoldingDetailRoute> { entry ->
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
        composable<TransactionsRoute> { entry ->
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
        composable<TransactionFormRoute> { entry ->
            val route = entry.toRoute<TransactionFormRoute>()
            TransactionFormScreen(route = route, onBack = back)
        }
        composable<CashRoute> { entry ->
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
                )
            }
        }
        // V5 S2c. The cash-classification layer and standing orders are
        // server-only surfaces over portfolio data, so they ride the same
        // paranoid guard as the rest of that family: a paranoid account has no
        // server-side ledger to classify or schedule against.
        composable<CashTagsRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.cash.CashTagsScreen(onBack = back)
            }
        }
        composable<CashRulesRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.cash.CashRulesScreen(onBack = back)
            }
        }
        composable<StandingOrdersRoute> { entry ->
            ParanoidGate(onBack = back) {
                val soRoute = entry.toRoute<StandingOrdersRoute>()
                at.bettertrack.app.ui.standingorders.StandingOrdersScreen(
                    routePortfolioId = soRoute.portfolioId,
                    onBack = back,
                )
            }
        }
        composable<CustomAssetsRoute> {
            CustomAssetsScreen(
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(CustomAssetDetailRoute(assetId)) },
            )
        }
        composable<CustomAssetDetailRoute> { entry ->
            val route = entry.toRoute<CustomAssetDetailRoute>()
            CustomAssetDetailScreen(assetId = route.assetId, onBack = back)
        }

        // Market
        composable<AssetPageRoute> { entry ->
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
        composable<SearchRoute> {
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
        composable<MarketIntelRoute> {
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
        composable<IdeaDetailRoute> { entry ->
            val route = entry.toRoute<IdeaDetailRoute>()
            IdeaDetailScreen(
                ideaId = route.ideaId,
                onBack = back,
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
            )
        }
        composable<ConglomerateBuilderRoute> { entry ->
            val route = entry.toRoute<ConglomerateBuilderRoute>()
            ConglomerateBuilderScreen(
                conglomerateId = route.conglomerateId,
                onBack = back,
                onSaved = { id ->
                    navController.popBackStack()
                    navController.navigate(ConglomerateDetailRoute(id))
                },
            )
        }
        composable<ConglomerateDetailRoute> { entry ->
            val route = entry.toRoute<ConglomerateDetailRoute>()
            ConglomerateDetailScreen(
                conglomerateId = route.conglomerateId,
                onBack = back,
                onEdit = { id -> navController.navigate(ConglomerateBuilderRoute(id)) },
                onDelete = { navController.popBackStack() },
            )
        }

        // Social — per-friend overview (Social v2) + read-only friend-shared views (§6.9)
        composable<FriendOverviewRoute> { entry ->
            val route = entry.toRoute<FriendOverviewRoute>()
            FriendOverviewScreen(
                friendUserId = route.userId,
                username = route.username,
                onBack = back,
                onOpenChat = { uid, un ->
                    navController.navigate(ChatThreadRoute(friendUserId = uid, friendUsername = un))
                },
                onOpenSharedPortfolio = { id -> navController.navigate(SharedPortfolioViewRoute(id)) },
                onOpenSharedWatchlist = { watchlistId, ownerName ->
                    navController.navigate(SharedWatchlistViewRoute(watchlistId, ownerName))
                },
                onOpenSharedConglomerate = { id -> navController.navigate(SharedConglomerateViewRoute(id)) },
                // A cloned idea is the caller's OWN idea from the moment it
                // exists, so it opens on the ordinary owner-only detail route —
                // there is no "shared idea" screen to send it to, and that is
                // exactly why cloning is the affordance in the first place.
                onOpenIdea = { ideaId -> navController.navigate(IdeaDetailRoute(ideaId)) },
            )
        }
        composable<SharedPortfolioViewRoute> { entry ->
            val route = entry.toRoute<SharedPortfolioViewRoute>()
            SharedPortfolioViewScreen(portfolioId = route.portfolioId, onBack = back)
        }
        composable<SharedWatchlistViewRoute> { entry ->
            val route = entry.toRoute<SharedWatchlistViewRoute>()
            SharedWatchlistViewScreen(watchlistId = route.watchlistId, ownerName = route.ownerName, onBack = back)
        }
        composable<SharedConglomerateViewRoute> { entry ->
            val route = entry.toRoute<SharedConglomerateViewRoute>()
            SharedConglomerateViewScreen(conglomerateId = route.conglomerateId, onBack = back)
        }
        composable<ChatListRoute> {
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
        composable<ChatThreadRoute> { entry ->
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
                onOpenSharedWatchlist = { watchlistId, ownerName ->
                    navController.navigate(SharedWatchlistViewRoute(watchlistId, ownerName))
                },
                onOpenSharedConglomerate = { id -> navController.navigate(SharedConglomerateViewRoute(id)) },
            )
        }
        composable<NotificationsInboxRoute> {
            NotificationsInboxScreen(onBack = back, onDeepLink = onDeepLink)
        }

        // Settings — account + logout surface; Security section is Step 17, the
        // rest grows in Step 18.
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = back,
                onOpenSecurity = { navController.navigate(SettingsSecurityRoute) },
                onOpenNotifications = { navController.navigate(SettingsNotificationsRoute) },
                onOpenChangePassword = { navController.navigate(ChangePasswordRoute) },
                onOpenLanguage = { navController.navigate(SettingsLanguageRoute) },
                onOpenTaxSettings = { navController.navigate(TaxSettingsRoute) },
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                onOpenDeleteAccount = { navController.navigate(DeleteAccountRoute) },
                onOpenChangelog = { navController.navigate(ChangelogRoute) },
                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                onOpenGallery = { navController.navigate(GalleryRoute) },
                onOpenSyncDebug = { navController.navigate(SyncDebugRoute) },
                onOpenServer = { navController.navigate(ServerRoute) },
            )
        }
        composable<ChangelogRoute> { ChangelogScreen(onBack = back) }
        composable<StorageHomeRoute> {
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

        composable<PortfolioSettingsRoute> { entry ->
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

        composable<TaxSettingsRoute> {
            ParanoidGate(onBack = back) {
                at.bettertrack.app.ui.tax.TaxSettingsScreen(onBack = back)
            }
        }

        composable<PortfolioTaxRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<PortfolioTaxRoute>()
                at.bettertrack.app.ui.tax.PortfolioTaxScreen(
                    portfolioId = route.portfolioId,
                    onBack = back,
                )
            }
        }

        composable<TaxYearsRoute> { entry ->
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

        composable<TaxYearRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<TaxYearRoute>()
                at.bettertrack.app.ui.tax.TaxYearDetailScreen(
                    portfolioId = route.portfolioId,
                    year = route.year,
                    onBack = back,
                )
            }
        }

        composable<ChainManageRoute> { entry ->
            ParanoidGate(onBack = back) {
                val route = entry.toRoute<ChainManageRoute>()
                at.bettertrack.app.ui.mirrorchain.ChainManageScreen(
                    chainId = route.chainId,
                    onBack = back,
                )
            }
        }
        composable<SettingsSecurityRoute> {
            SecurityScreen(
                onBack = back,
                onSetupPin = { navController.navigate(AppLockSetupRoute(change = false)) },
                onChangePin = { navController.navigate(AppLockSetupRoute(change = true)) },
                onOpenTwoFactor = { navController.navigate(TwoFactorRoute) },
                onOpenSessions = { navController.navigate(ActiveSessionsRoute) },
            )
        }
        composable<AppLockSetupRoute> { entry ->
            val route = entry.toRoute<AppLockSetupRoute>()
            AppLockSetupScreen(change = route.change, onDone = back, onBack = back)
        }
        composable<SettingsNotificationsRoute> { NotificationSettingsScreen(onBack = back) }
        composable<SettingsLanguageRoute> { LanguageScreen(onBack = back) }
        composable<SettingsAboutRoute> {
            AboutScreen(onBack = back, onOpenChangelog = { navController.navigate(ChangelogRoute) })
        }
        composable<ChangePasswordRoute> { ChangePasswordScreen(onBack = back) }
        composable<TwoFactorRoute> { TwoFactorScreen(onBack = back) }
        composable<ActiveSessionsRoute> { ActiveSessionsScreen(onBack = back) }
        composable<DeleteAccountRoute> { DeleteAccountScreen(onBack = back) }

        // Sync & debug
        composable<PendingSyncRoute> {
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
        composable<GalleryRoute> {
            GalleryScreen(
                onClose = back,
                onOpenSyncDebug = { navController.navigate(SyncDebugRoute) },
            )
        }
        composable<ServerRoute> {
            ServerScreen(onBack = { navController.popBackStack() })
        }
        composable<SyncDebugRoute> {
            SyncDebugScreen(
                onClose = back,
                onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
            )
        }
    }
}
