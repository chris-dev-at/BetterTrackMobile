package at.bettertrack.app.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.bettertrack.app.data.api.BtResult
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.data.storage.visibleTabSurfaces
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.StandingOrdersRoute
import at.bettertrack.app.navigation.AppLockSetupRoute
import at.bettertrack.app.navigation.AssetPageRoute
import at.bettertrack.app.navigation.MarketsTabRoute
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.navigation.CashRulesRoute
import at.bettertrack.app.navigation.CashTagsRoute
import at.bettertrack.app.navigation.ChangelogRoute
import at.bettertrack.app.navigation.CashRoute
import at.bettertrack.app.navigation.ChatListRoute
import at.bettertrack.app.navigation.ChatThreadRoute
import at.bettertrack.app.navigation.ConglomerateBuilderRoute
import at.bettertrack.app.navigation.ConglomerateDetailRoute
import at.bettertrack.app.navigation.CustomAssetDetailRoute
import at.bettertrack.app.navigation.CustomAssetsRoute
import at.bettertrack.app.navigation.FriendOverviewRoute
import at.bettertrack.app.navigation.GalleryRoute
import at.bettertrack.app.navigation.HoldingDetailRoute
import at.bettertrack.app.navigation.HomeTabRoute
import at.bettertrack.app.navigation.FriendGroupsRoute
import at.bettertrack.app.navigation.IdeaDetailRoute
import at.bettertrack.app.navigation.MarketIntelRoute
import at.bettertrack.app.navigation.NotificationsInboxRoute
import at.bettertrack.app.navigation.owningTab
import at.bettertrack.app.navigation.PendingSyncRoute
import at.bettertrack.app.navigation.PortfolioTabRoute
import at.bettertrack.app.navigation.SearchRoute
import at.bettertrack.app.navigation.SettingsAboutRoute
import at.bettertrack.app.navigation.ChangePasswordRoute
import at.bettertrack.app.navigation.TwoFactorRoute
import at.bettertrack.app.navigation.ActiveSessionsRoute
import at.bettertrack.app.navigation.DeleteAccountRoute
import at.bettertrack.app.navigation.SettingsLanguageRoute
import at.bettertrack.app.navigation.SettingsNotificationsRoute
import at.bettertrack.app.navigation.SettingsRoute
import at.bettertrack.app.navigation.StorageHomeRoute
import at.bettertrack.app.navigation.SettingsSecurityRoute
import at.bettertrack.app.navigation.SharedConglomerateViewRoute
import at.bettertrack.app.navigation.SharedPortfolioViewRoute
import at.bettertrack.app.navigation.SharedWatchlistViewRoute
import at.bettertrack.app.navigation.PeopleTabRoute
import at.bettertrack.app.navigation.DevBackendRoute
import at.bettertrack.app.navigation.SyncDebugRoute
import at.bettertrack.app.navigation.TransactionFormRoute
import at.bettertrack.app.navigation.TransactionsRoute
import at.bettertrack.app.navigation.WorkbenchTabRoute
import at.bettertrack.app.ui.components.BtBadgeOverlay
import at.bettertrack.app.ui.components.BtCountBadge
import at.bettertrack.app.ui.components.BtTabBadgeDot
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.home.HomeScreen
import at.bettertrack.app.ui.cash.CashScreen
import at.bettertrack.app.ui.customassets.CustomAssetDetailScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateBuilderScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateDetailScreen
import at.bettertrack.app.ui.customassets.CustomAssetsScreen
import at.bettertrack.app.ui.market.AssetPageScreen
import at.bettertrack.app.ui.market.SearchScreen
import at.bettertrack.app.ui.notifications.NotificationSettingsScreen
import at.bettertrack.app.ui.notifications.NotificationsInboxScreen
import at.bettertrack.app.ui.paranoid.ParanoidGate
import at.bettertrack.app.ui.debug.DevBackendScreen
import at.bettertrack.app.ui.debug.SyncDebugScreen
import androidx.navigation.toRoute
import at.bettertrack.app.ui.gallery.GalleryScreen
import at.bettertrack.app.ui.portfolio.HoldingDetailScreen
import at.bettertrack.app.ui.portfolio.PortfolioOverviewScreen
import at.bettertrack.app.ui.portfolio.TransactionFormScreen
import at.bettertrack.app.ui.portfolio.TransactionsScreen
import at.bettertrack.app.ui.sync.PendingSyncScreen
import at.bettertrack.app.ui.screens.MarketsTabScreen
import at.bettertrack.app.ui.screens.WorkbenchTabScreen
import at.bettertrack.app.ui.workboard.WorkboardEntry
import at.bettertrack.app.ui.chat.ChatListScreen
import at.bettertrack.app.ui.chat.ChatThreadScreen
import at.bettertrack.app.ui.ideas.IdeaDetailScreen
import at.bettertrack.app.ui.market.MarketIntelScreen
import at.bettertrack.app.ui.social.FriendGroupsScreen
import at.bettertrack.app.ui.social.FriendOverviewScreen
import at.bettertrack.app.ui.social.SharedConglomerateViewScreen
import at.bettertrack.app.ui.social.SharedPortfolioViewScreen
import at.bettertrack.app.ui.social.SharedWatchlistViewScreen
import at.bettertrack.app.ui.social.SocialScreen
import at.bettertrack.app.ui.settings.ChangelogScreen
import at.bettertrack.app.ui.settings.SecurityScreen
import at.bettertrack.app.ui.settings.SettingsScreen
import at.bettertrack.app.ui.settings.AboutScreen
import at.bettertrack.app.ui.settings.ActiveSessionsScreen
import at.bettertrack.app.ui.settings.ChangePasswordScreen
import at.bettertrack.app.ui.settings.DeleteAccountScreen
import at.bettertrack.app.ui.settings.LanguageScreen
import at.bettertrack.app.ui.settings.TwoFactorScreen
import at.bettertrack.app.ui.applock.AppLockSetupScreen
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.reflect.KClass

/** Which shell-level signal lights this tab's badge dot (R-arc mandate §1). */
private enum class TabBadge {
    /** No dot, ever. */
    None,

    /** Unread chat messages — the affordance that left the top bar. */
    Chat,

    /** Alerts that have fired — the other badge the top bar used to carry. */
    Alerts,
}

/**
 * Bottom-navigation tab metadata — Home · Portfolio · Workbench · Markets ·
 * People (R-arc mandate §2).
 *
 * @param surface the §4.5 surface this tab is the entry point for — drives mode
 *   gating. Note the deliberate name mismatch on Workbench: its surface constant
 *   is [BtSurface.CONGLOMERATES], because that name mirrors the storage plan's
 *   §4.5 table verbatim and renaming it would drift the app from the document it
 *   implements for no user-visible gain (R1 decision O-2). The label is the
 *   user-facing name; the constant is the contract's.
 * @param ownsItsHeader true when the DESTINATION renders its own header and the
 *   shell must not add one on top of it. Portfolio does, as of R1-B: its title is
 *   the portfolio's own name in an in-screen collapsing large title that also
 *   carries the switcher and that screen's overflow (mandate §1). R2 flips this
 *   flag for the remaining tabs as each one grows its own header.
 */
private data class TabSpec(
    val tab: BtTab,
    val routeClass: KClass<*>,
    val labelRes: Int,
    val icon: ImageVector,
    val surface: BtSurface,
    val badge: TabBadge = TabBadge.None,
    val ownsItsHeader: Boolean = false,
)

private val Tabs = listOf(
    TabSpec(BtTab.Home, HomeTabRoute::class, R.string.bt_tab_home, Icons.Outlined.Home, BtSurface.HOME),
    TabSpec(BtTab.Portfolio, PortfolioTabRoute::class, R.string.bt_tab_portfolio, Icons.Outlined.PieChart, BtSurface.PORTFOLIO, ownsItsHeader = true),
    TabSpec(BtTab.Workbench, WorkbenchTabRoute::class, R.string.bt_tab_workbench, Icons.Outlined.Dashboard, BtSurface.CONGLOMERATES, badge = TabBadge.Alerts),
    TabSpec(BtTab.Markets, MarketsTabRoute::class, R.string.bt_tab_markets, Icons.AutoMirrored.Outlined.ShowChart, BtSurface.MARKET),
    TabSpec(BtTab.People, PeopleTabRoute::class, R.string.bt_tab_people, Icons.Outlined.People, BtSurface.SOCIAL, badge = TabBadge.Chat),
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
 * R-arc R1: Home joins as the first entry and is `FULL` in every mode, so a
 * Drive-only install goes from a two-tab bar to a three-tab one with a real
 * front door — the mode that had the least navigation gains the most from it.
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
            switchToTab(owningTab(link))
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

    // Discreet mode as a first-class quick toggle (mandate §5: "give it a sane
    // home, e.g. overflow or profile, not bar chrome"). Home's overflow is that
    // home; the Settings row stays the canonical control.
    val discreetMode by AppGraph.discreetModeStore.enabled.collectAsStateWithLifecycle()
    // Hoisted to a named lambda because it now has TWO call sites — Home's
    // overflow item and Home's in-content quick-links row (Fable's rule: an
    // overflow entry may never be the only path to its act). One implementation,
    // so the two can never diverge on what "flip discreet mode" means.
    val toggleDiscreet: (Boolean) -> Unit = { wanted ->
        // Flip locally FIRST — the point of masking is that amounts vanish the
        // instant the user asks, not a round-trip later. Settings owns the error
        // copy for a refusal (mandate §5 keeps it the canonical control); here
        // the state visibly reverting IS the feedback.
        AppGraph.discreetModeStore.set(wanted)
        scope.launch {
            val r = AppGraph.accountRepository.updateDiscreetMode(wanted)
            if (r is BtResult.Err) AppGraph.discreetModeStore.set(!wanted)
        }
    }

    Scaffold(
        containerColor = bt.bg,
        // The bars below consume their own system-bar insets; full-screen
        // destinations (gallery, settings, placeholders) run their own Scaffold.
        // Zeroing here prevents double status-bar padding on those routes.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // `ownsItsHeader` destinations render their own (R1-B's collapsing
            // large title); the shell adds nothing on top of them.
            if (currentTab != null && !currentTab.ownsItsHeader) {
                BtTopBar(
                    spec = currentTab,
                    notifUnread = notifUnread,
                    showNotifications = showNotificationSurfaces,
                    discreetMode = discreetMode,
                    onWordmarkLongPress = {
                        if (BuildConfig.DEBUG) navController.navigate(GalleryRoute)
                    },
                    onSearch = { navController.navigate(SearchRoute) },
                    // The chat list belongs to People, so it opens with the very
                    // same tab semantics a notification tap uses (S6 P1-8).
                    onMessages = { navigateDeepLink(NotifDeepLink.Chat(null)) },
                    onFriendGroups = { navController.navigate(FriendGroupsRoute) },
                    onNotifications = { navController.navigate(NotificationsInboxRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                    onDevBackend = { navController.navigate(DevBackendRoute) },
                    onToggleDiscreet = toggleDiscreet,
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                BtBottomBar(
                    tabs = visibleTabs,
                    isSelected = { tab ->
                        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                    },
                    // The badges the top bar used to carry, on the tabs that own
                    // them. Both are dots, not counts — see [BtTabBadgeDot].
                    hasBadge = { tab ->
                        when (tab.badge) {
                            TabBadge.None -> false
                            TabBadge.Chat -> showSocialSurfaces && chatUnread > 0
                            TabBadge.Alerts -> showNotificationSurfaces && triggeredAlerts > 0
                        }
                    },
                    onSelect = { tab -> switchToTab(tab.tab) },
                )
            }
        },
    ) { innerPadding ->
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
            BtNavHost(navController, navigateDeepLink, switchToTab, discreetMode, toggleDiscreet)
        }
    }
}

/**
 * The 3-element top bar (R-arc mandate §1): context/title, ONE action, overflow.
 *
 * One composable rather than five per-screen bars, because the *rule* is what
 * has to be enforceable — a per-screen bar is exactly how the old one grew to
 * six elements, one defensible addition at a time. The budget is spent per tab:
 *
 * | Tab | Context | ONE action | Overflow |
 * |---|---|---|---|
 * | Home | wordmark | Search | inbox · discreet · settings · (debug) dev backend |
 * | Workbench | title | — (segments are content) | — |
 * | Markets | title | — (the in-content field IS the entry) | — |
 * | People | title | Messages | Friend groups |
 *
 * **Portfolio is not in that table**: as of R1-B it sets [TabSpec.ownsItsHeader]
 * and renders its own collapsing large title (the portfolio's name, tap to
 * switch) with its own overflow. This composable is never called for it.
 *
 * **The wordmark appears on Home only.** It stopped being wayfinding the moment
 * it was on all four tabs — the user knows which app they opened — and Home is
 * the one screen with no better claim on that space. Its hidden long-press debug
 * gallery entry moves with it.
 *
 * An empty overflow renders **no button**. A menu affordance that opens onto
 * nothing is worse than the icon it saved, so Workbench and Markets carry a
 * title and nothing else in R1 (decision O-4: Markets takes no action at all,
 * killing the S6 P1-11 search duplication at its root rather than restyling it).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BtTopBar(
    spec: TabSpec,
    notifUnread: Int,
    showNotifications: Boolean,
    discreetMode: Boolean,
    onWordmarkLongPress: () -> Unit,
    onSearch: () -> Unit,
    onMessages: () -> Unit,
    onFriendGroups: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onDevBackend: () -> Unit,
    onToggleDiscreet: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    val isHome = spec.tab == BtTab.Home
    TopAppBar(
        title = {
            if (isHome) {
                // Plain wordmark, no edition (§3.2). Hidden debug gallery entry:
                // long-press (debug builds only).
                Wordmark(
                    fontSize = 20.sp,
                    modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onWordmarkLongPress,
                    ),
                )
            } else {
                // A step up from `titleLarge` towards the mandate's large-title
                // idiom, without faking a collapsing header these screens cannot
                // yet drive: `LargeTopAppBar` needs a scroll behaviour wired to
                // the destination's own scroll container, and R1-B builds the
                // shared component (`BtCollapsingHeader`) that does that properly
                // for every screen. Rendering a tall static bar in the meantime
                // would cost vertical space and buy nothing.
                Text(
                    text = stringResource(spec.labelRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = bt.textPrimary,
                )
            }
        },
        actions = {
            when (spec.tab) {
                // Home's ONE action (decision O-3): search is the affordance the
                // whole app shares and Home is the only screen that is about all
                // of it. A profile/avatar entry was the alternative — revisit at
                // R2, when settings get their own pass.
                BtTab.Home -> IconButton(onClick = onSearch) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.bt_search_cd),
                        tint = bt.textSecondary,
                    )
                }
                // Where the WP-C chat affordance LANDS. It solved a real problem
                // (P1-10) and it keeps solving it — as this tab's one action plus
                // the tab's own dot, at zero cost to the other four bars.
                BtTab.People -> IconButton(onClick = onMessages) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = stringResource(R.string.bt_top_messages),
                        tint = bt.textSecondary,
                    )
                }
                else -> Unit
            }
            BtTopBarOverflow(
                tab = spec.tab,
                notifUnread = notifUnread,
                showNotifications = showNotifications,
                discreetMode = discreetMode,
                onFriendGroups = onFriendGroups,
                onNotifications = onNotifications,
                onSettings = onSettings,
                onDevBackend = onDevBackend,
                onToggleDiscreet = onToggleDiscreet,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = bt.bg,
            titleContentColor = bt.textPrimary,
        ),
    )
}

/**
 * The overflow (⋮) — the third and last thing a top bar may carry.
 *
 * This is where the secondary surfaces the mandate evicted from the bar live:
 * "settings, sync status, dev screen, About live behind overflow/profile, never
 * as bar icons" (§2). The notification inbox keeps its unread count here,
 * because a number the user could act on is exactly what an overflow item may
 * carry — it is the persistent *bell competing for bar space* that had to go,
 * not the information.
 */
@Composable
private fun BtTopBarOverflow(
    tab: BtTab,
    notifUnread: Int,
    showNotifications: Boolean,
    discreetMode: Boolean,
    onFriendGroups: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
    onDevBackend: () -> Unit,
    onToggleDiscreet: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    // Only two tabs have anything to put behind it in R1 — and a ⋮ that opens
    // onto an empty menu is a broken promise, so the other three render nothing.
    val hasMenu = tab == BtTab.Home || tab == BtTab.People
    if (!hasMenu) return

    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.bt_top_more),
                tint = bt.textSecondary,
            )
        }
        // The inbox count rides the ⋮ itself as a dot, so "something is waiting"
        // survives the bell's removal without a second glyph. The exact number
        // is on the menu item one tap in.
        BtBadgeOverlay(
            count = 0,
            showDot = tab == BtTab.Home && showNotifications && notifUnread > 0,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-6).dp, y = 8.dp),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = bt.surface,
        ) {
            if (tab == BtTab.People) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_groups_title)) },
                    onClick = { open = false; onFriendGroups() },
                )
            }
            if (tab == BtTab.Home) {
                if (showNotifications) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_top_notifications)) },
                        onClick = { open = false; onNotifications() },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = bt.textSecondary,
                            )
                        },
                        trailingIcon = { BtCountBadge(count = notifUnread) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_settings_discreet)) },
                    onClick = { open = false; onToggleDiscreet(!discreetMode) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (discreetMode) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = null,
                            // Gold while ON: the one state in this menu that
                            // changes what every other screen renders should not
                            // look like the rows that merely navigate.
                            tint = if (discreetMode) bt.gold else bt.textSecondary,
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bt_top_settings)) },
                    onClick = { open = false; onSettings() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = null,
                            tint = bt.textSecondary,
                        )
                    },
                )
                if (BuildConfig.DEBUG) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bt_settings_dev_backend)) },
                        onClick = { open = false; onDevBackend() },
                    )
                }
            }
        }
    }
}

/**
 * The bottom bar — the backbone the mandate keeps (§2), now five wide and
 * carrying the two signals the top bar gave up.
 *
 * [hasBadge] is a predicate rather than a count map on purpose: the bar renders
 * dots, so a count here would be information the component cannot use and the
 * caller would be free to get wrong. What "there is something on that tab" means
 * — including its per-mode gating — stays with the shell, which owns the flows.
 */
@Composable
private fun BtBottomBar(
    tabs: List<TabSpec>,
    isSelected: (TabSpec) -> Boolean,
    hasBadge: (TabSpec) -> Boolean,
    onSelect: (TabSpec) -> Unit,
) {
    val bt = BtTheme.colors
    Column {
        HorizontalDivider(thickness = 1.dp, color = bt.border)
        NavigationBar(containerColor = bt.surface) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = isSelected(tab),
                    onClick = { onSelect(tab) },
                    icon = {
                        Box {
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
                    label = { Text(stringResource(tab.labelRes)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = bt.gold,
                        selectedTextColor = bt.gold,
                        // Clean translucent-gold selection pill (matches the chip
                        // + badge tint language) instead of a muddy amber fill.
                        indicatorColor = bt.gold.copy(alpha = 0.16f),
                        unselectedIconColor = bt.textMuted,
                        unselectedTextColor = bt.textMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BtNavHost(
    navController: NavHostController,
    onDeepLink: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
    /** Discreet-mode state + its one implementation, shared with the top bar. */
    discreetMode: Boolean,
    onToggleDiscreet: (Boolean) -> Unit,
) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        // R-arc R1: Home, not Portfolio. Two things follow, both improvements:
        // system-back from any tab now lands on Home and back from Home exits
        // (before, back from any tab landed on Portfolio, which was arbitrary
        // the moment Portfolio became one of five peers); and the two
        // `popUpTo(findStartDestination())` call sites — the deep-link tab
        // switch and the bottom-bar tap — pop to a tab that is `FULL` in every
        // storage mode, which Portfolio only happened to be.
        startDestination = HomeTabRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Tabs
        composable<HomeTabRoute> {
            // Home crosses tabs ONLY through onOpen/onSwitchTab — see HomeScreen's
            // KDoc. No `navController` is handed to it, deliberately: a bare push
            // from an index screen stacks another tab's detail on Home and the
            // next bottom-bar tap saves it under the wrong tab (S6 P1-8). The two
            // typed pushes below are Home's OWN destinations — the tab that
            // carries them is the tab they belong on — and neither has a
            // NotifDeepLink to route through.
            HomeScreen(
                onOpen = onDeepLink,
                onSwitchTab = onSwitchTab,
                onOpenInbox = { navController.navigate(NotificationsInboxRoute) },
                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                discreetMode = discreetMode,
                onToggleDiscreet = onToggleDiscreet,
            )
        }
        composable<PortfolioTabRoute> {
            // V5 S2a: a paranoid account's portfolio family is server-blind. Route
            // ONLY these killed surfaces to the explainer — Home/Markets/People/
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
                )
            }
        }
        composable<MarketsTabRoute> {
            MarketsTabScreen(
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenCustomAssets = { navController.navigate(CustomAssetsRoute) },
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onAddToWatchlist = { navController.navigate(SearchRoute) },
                onOpenMarketIntel = { navController.navigate(MarketIntelRoute) },
            )
        }
        composable<PeopleTabRoute> {
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
        composable<FriendGroupsRoute> {
            FriendGroupsScreen(onBack = back)
        }
        composable<WorkbenchTabRoute> {
            WorkbenchTabScreen(
                onOpenConglomerate = { id -> navController.navigate(ConglomerateDetailRoute(id)) },
                onCreateConglomerate = { navController.navigate(ConglomerateBuilderRoute()) },
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onOpenIdea = { ideaId -> navController.navigate(IdeaDetailRoute(ideaId)) },
            )
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
                onOpenAbout = { navController.navigate(SettingsAboutRoute) },
                onOpenDeleteAccount = { navController.navigate(DeleteAccountRoute) },
                onOpenChangelog = { navController.navigate(ChangelogRoute) },
                onOpenDataHome = { navController.navigate(StorageHomeRoute) },
                onOpenGallery = { navController.navigate(GalleryRoute) },
                onOpenSyncDebug = { navController.navigate(SyncDebugRoute) },
                onOpenDevBackend = { navController.navigate(DevBackendRoute) },
            )
        }
        composable<ChangelogRoute> { ChangelogScreen(onBack = back) }
        composable<StorageHomeRoute> {
            at.bettertrack.app.ui.storage.WhereYourDataLivesScreen(onBack = back)
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
        composable<DevBackendRoute> {
            DevBackendScreen(onBack = { navController.popBackStack() })
        }
        composable<SyncDebugRoute> {
            SyncDebugScreen(
                onClose = back,
                onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
            )
        }
    }
}
