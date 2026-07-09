package at.bettertrack.app.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Dashboard
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import at.bettertrack.app.debug.DebugPreviewState
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.AppLockRoute
import at.bettertrack.app.navigation.AppLockSetupRoute
import at.bettertrack.app.navigation.AssetPageRoute
import at.bettertrack.app.navigation.AssetsTabRoute
import at.bettertrack.app.navigation.CashRoute
import at.bettertrack.app.navigation.ChatListRoute
import at.bettertrack.app.navigation.ChatThreadRoute
import at.bettertrack.app.navigation.ConglomerateBuilderRoute
import at.bettertrack.app.navigation.ConglomerateDetailRoute
import at.bettertrack.app.navigation.ConglomerateListRoute
import at.bettertrack.app.navigation.CustomAssetDetailRoute
import at.bettertrack.app.navigation.CustomAssetsRoute
import at.bettertrack.app.navigation.FriendOverviewRoute
import at.bettertrack.app.navigation.GalleryRoute
import at.bettertrack.app.navigation.HoldingDetailRoute
import at.bettertrack.app.navigation.LoginRoute
import at.bettertrack.app.navigation.NotificationsInboxRoute
import at.bettertrack.app.navigation.PendingSyncRoute
import at.bettertrack.app.navigation.PortfolioTabRoute
import at.bettertrack.app.navigation.SearchRoute
import at.bettertrack.app.navigation.SettingsAboutRoute
import at.bettertrack.app.navigation.SettingsAccountRoute
import at.bettertrack.app.navigation.SettingsLanguageRoute
import at.bettertrack.app.navigation.SettingsNotificationsRoute
import at.bettertrack.app.navigation.SettingsRoute
import at.bettertrack.app.navigation.SettingsSecurityRoute
import at.bettertrack.app.navigation.SharedConglomerateViewRoute
import at.bettertrack.app.navigation.SharedPortfolioViewRoute
import at.bettertrack.app.navigation.SharedWatchlistViewRoute
import at.bettertrack.app.navigation.SocialTabRoute
import at.bettertrack.app.navigation.SyncDebugRoute
import at.bettertrack.app.navigation.TransactionFormRoute
import at.bettertrack.app.navigation.TransactionsRoute
import at.bettertrack.app.navigation.WatchlistRoute
import at.bettertrack.app.navigation.WorkboardTabRoute
import at.bettertrack.app.ui.components.Wordmark
import at.bettertrack.app.ui.cash.CashScreen
import at.bettertrack.app.ui.customassets.CustomAssetDetailScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateBuilderScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateDetailScreen
import at.bettertrack.app.ui.conglomerate.ConglomerateListScreen
import at.bettertrack.app.ui.customassets.CustomAssetsScreen
import at.bettertrack.app.ui.market.AssetPageScreen
import at.bettertrack.app.ui.market.SearchScreen
import at.bettertrack.app.ui.notifications.NotificationBell
import at.bettertrack.app.ui.notifications.NotificationSettingsScreen
import at.bettertrack.app.ui.notifications.NotificationsInboxScreen
import at.bettertrack.app.ui.debug.SyncDebugScreen
import androidx.navigation.toRoute
import at.bettertrack.app.ui.gallery.GalleryScreen
import at.bettertrack.app.ui.portfolio.HoldingDetailScreen
import at.bettertrack.app.ui.portfolio.PortfolioOverviewScreen
import at.bettertrack.app.ui.portfolio.TransactionFormScreen
import at.bettertrack.app.ui.portfolio.TransactionsScreen
import at.bettertrack.app.ui.sync.PendingSyncScreen
import at.bettertrack.app.ui.screens.AssetsTabScreen
import at.bettertrack.app.ui.screens.PlaceholderScreen
import at.bettertrack.app.ui.screens.WorkboardTabScreen
import at.bettertrack.app.ui.chat.ChatListScreen
import at.bettertrack.app.ui.chat.ChatThreadScreen
import at.bettertrack.app.ui.social.FriendOverviewScreen
import at.bettertrack.app.ui.social.SharedConglomerateViewScreen
import at.bettertrack.app.ui.social.SharedPortfolioViewScreen
import at.bettertrack.app.ui.social.SharedWatchlistViewScreen
import at.bettertrack.app.ui.social.SocialScreen
import at.bettertrack.app.ui.settings.SecurityScreen
import at.bettertrack.app.ui.settings.SettingsScreen
import at.bettertrack.app.ui.applock.AppLockSetupScreen
import at.bettertrack.app.ui.theme.BtTheme
import kotlin.reflect.KClass

/** Bottom-navigation tab metadata — Portfolio · Assets · Social · Workboard. */
private data class TabSpec(
    val route: Any,
    val routeClass: KClass<*>,
    val labelRes: Int,
    val icon: ImageVector,
)

private val Tabs = listOf(
    TabSpec(PortfolioTabRoute, PortfolioTabRoute::class, R.string.bt_tab_portfolio, Icons.Outlined.PieChart),
    TabSpec(AssetsTabRoute, AssetsTabRoute::class, R.string.bt_tab_assets, Icons.AutoMirrored.Outlined.ShowChart),
    TabSpec(SocialTabRoute, SocialTabRoute::class, R.string.bt_tab_social, Icons.Outlined.People),
    TabSpec(WorkboardTabRoute, WorkboardTabRoute::class, R.string.bt_tab_workboard, Icons.Outlined.Dashboard),
)

/**
 * The BetterTrack app shell (Step 3): top bar (wordmark + bell slot + settings),
 * 4-tab bottom navigation, global offline-banner scaffold, and the full typed
 * navigation graph with placeholders for every future destination.
 */
@Composable
fun BtApp() {
    val bt = BtTheme.colors
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isTopLevel = Tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
    }

    // Notification deep-link routing (Step 16): shared by inbox taps AND tapped
    // system-push intents (surfaced via AppGraph.pendingDeepLink).
    val navigateDeepLink: (NotifDeepLink) -> Unit = remember(navController) {
        { link ->
            when (link) {
                NotifDeepLink.Social -> navController.navigate(SocialTabRoute) { launchSingleTop = true }
                is NotifDeepLink.SharedPortfolio -> navController.navigate(SharedPortfolioViewRoute(link.portfolioId))
                is NotifDeepLink.Chat -> navController.navigate(ChatListRoute)
                is NotifDeepLink.Asset -> navController.navigate(AssetPageRoute(link.assetId))
                is NotifDeepLink.Holding -> navController.navigate(HoldingDetailRoute(link.assetId))
                NotifDeepLink.Settings -> navController.navigate(SettingsRoute)
                NotifDeepLink.Security -> navController.navigate(SettingsSecurityRoute)
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

    // Bell unread badge: refresh the inbox once on entry so the count is live.
    val notifUnread by AppGraph.notificationRepository.unreadCount.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { AppGraph.notificationRepository.refresh() }

    Scaffold(
        containerColor = bt.bg,
        // The bars below consume their own system-bar insets; full-screen
        // destinations (gallery, settings, placeholders) run their own Scaffold.
        // Zeroing here prevents double status-bar padding on those routes.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isTopLevel) {
                BtTopBar(
                    notifUnread = notifUnread,
                    onWordmarkLongPress = {
                        if (BuildConfig.DEBUG) navController.navigate(GalleryRoute)
                    },
                    onSearch = { navController.navigate(SearchRoute) },
                    onNotifications = { navController.navigate(NotificationsInboxRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                BtBottomBar(
                    isSelected = { tab ->
                        currentDestination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                    },
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
            BtNavHost(navController, navigateDeepLink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BtTopBar(
    notifUnread: Int,
    onWordmarkLongPress: () -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onSettings: () -> Unit,
) {
    val bt = BtTheme.colors
    TopAppBar(
        title = {
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
        },
        actions = {
            // App-wide search affordance (§6.5).
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.bt_search_cd),
                    tint = bt.textSecondary,
                )
            }
            // Notification bell + unread badge → in-app inbox (Step 16, §6.11).
            NotificationBell(unread = notifUnread, onClick = onNotifications)
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.bt_top_settings),
                    tint = bt.textSecondary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = bt.bg,
            titleContentColor = bt.textPrimary,
        ),
    )
}

@Composable
private fun BtBottomBar(
    isSelected: (TabSpec) -> Boolean,
    onSelect: (TabSpec) -> Unit,
) {
    val bt = BtTheme.colors
    Column {
        HorizontalDivider(thickness = 1.dp, color = bt.border)
        NavigationBar(containerColor = bt.surface) {
            Tabs.forEach { tab ->
                NavigationBarItem(
                    selected = isSelected(tab),
                    onClick = { onSelect(tab) },
                    icon = { Icon(tab.icon, contentDescription = null) },
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
) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = PortfolioTabRoute,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Tabs
        composable<PortfolioTabRoute> {
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
        composable<AssetsTabRoute> {
            AssetsTabScreen(
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenCustomAssets = { navController.navigate(CustomAssetsRoute) },
                onOpenAsset = { assetId -> navController.navigate(AssetPageRoute(assetId)) },
                onAddToWatchlist = { navController.navigate(SearchRoute) },
            )
        }
        composable<SocialTabRoute> {
            SocialScreen(
                onOpenFriend = { userId, username ->
                    navController.navigate(FriendOverviewRoute(userId, username))
                },
                onOpenChats = { navController.navigate(ChatListRoute) },
                onOpenChatWith = { friendUserId, username ->
                    navController.navigate(ChatThreadRoute(friendUserId = friendUserId, friendUsername = username))
                },
            )
        }
        composable<WorkboardTabRoute> {
            ConglomerateListScreen(
                onOpen = { id -> navController.navigate(ConglomerateDetailRoute(id)) },
                onCreate = { navController.navigate(ConglomerateBuilderRoute()) },
            )
        }

        // Auth & lock
        composable<LoginRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_login), back) }
        composable<AppLockRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_app_lock), back) }

        // Portfolio
        composable<HoldingDetailRoute> { entry ->
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
        composable<TransactionsRoute> { entry ->
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
        composable<TransactionFormRoute> { entry ->
            val route = entry.toRoute<TransactionFormRoute>()
            TransactionFormScreen(route = route, onBack = back)
        }
        composable<CashRoute> { entry ->
            val route = entry.toRoute<CashRoute>()
            CashScreen(
                routePortfolioId = route.portfolioId,
                editOpId = route.editOpId,
                onBack = back,
                onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
            )
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
                onTrade = { assetId, symbol, name, pid, sell ->
                    navController.navigate(
                        TransactionFormRoute(
                            portfolioId = pid,
                            assetId = assetId,
                            assetSymbol = symbol,
                            assetName = name,
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
                onTrade = { assetId, symbol, name, pid ->
                    navController.navigate(
                        TransactionFormRoute(
                            portfolioId = pid,
                            assetId = assetId,
                            assetSymbol = symbol,
                            assetName = name,
                        ),
                    )
                },
            )
        }
        composable<WatchlistRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_watchlists), back) }

        // Workboard
        composable<ConglomerateListRoute> {
            ConglomerateListScreen(
                onOpen = { id -> navController.navigate(ConglomerateDetailRoute(id)) },
                onCreate = { navController.navigate(ConglomerateBuilderRoute()) },
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
                onOpenNotifications = { navController.navigate(SettingsNotificationsRoute) },
                onOpenSecurity = { navController.navigate(SettingsSecurityRoute) },
            )
        }
        composable<SettingsAccountRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_settings_account), back) }
        composable<SettingsSecurityRoute> {
            SecurityScreen(
                onBack = back,
                onSetupPin = { navController.navigate(AppLockSetupRoute(change = false)) },
                onChangePin = { navController.navigate(AppLockSetupRoute(change = true)) },
            )
        }
        composable<AppLockSetupRoute> { entry ->
            val route = entry.toRoute<AppLockSetupRoute>()
            AppLockSetupScreen(change = route.change, onDone = back, onBack = back)
        }
        composable<SettingsNotificationsRoute> { NotificationSettingsScreen(onBack = back) }
        composable<SettingsLanguageRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_settings_language), back) }
        composable<SettingsAboutRoute> { PlaceholderScreen(stringResource(R.string.bt_dest_settings_about), back) }

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
        composable<SyncDebugRoute> {
            SyncDebugScreen(
                onClose = back,
                onOpenPendingSync = { navController.navigate(PendingSyncRoute) },
            )
        }
    }
}
