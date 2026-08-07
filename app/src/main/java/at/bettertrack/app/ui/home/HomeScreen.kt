package at.bettertrack.app.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PriceChange
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import at.bettertrack.app.R
import at.bettertrack.app.data.db.HoldingEntity
import at.bettertrack.app.data.db.PortfolioEntity
import at.bettertrack.app.data.notifications.NotifDeepLink
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.shows
import at.bettertrack.app.di.AppGraph
import at.bettertrack.app.navigation.BtTab
import at.bettertrack.app.ui.components.BtCard
import at.bettertrack.app.ui.components.BtSecondaryButton
import at.bettertrack.app.ui.components.BtSkeleton
import at.bettertrack.app.ui.components.MoneyColorMode
import at.bettertrack.app.ui.components.MoneyText
import at.bettertrack.app.ui.components.formatPercent
import at.bettertrack.app.ui.format.BtDiscreetMode
import at.bettertrack.app.ui.mirrorchain.MirrorInvitesCard
import at.bettertrack.app.ui.portfolio.deltaColor
import at.bettertrack.app.ui.prices.NetWorthState
import at.bettertrack.app.ui.prices.NoPricesHero
import at.bettertrack.app.ui.prices.UnpricedNote
import at.bettertrack.app.ui.prices.priceCoverage
import at.bettertrack.app.ui.theme.BtTheme
import at.bettertrack.app.ui.util.rememberBtLocale
import at.bettertrack.app.ui.workboard.WorkboardEntry
import at.bettertrack.app.ui.components.BtRailedRow
import at.bettertrack.app.ui.portfolio.rangeRail

/**
 * The rhythm between Home's sections (mandate §4: more whitespace, fewer
 * boxes-in-boxes). Carried by each section rather than by the list's arrangement
 * — see the note on the LazyColumn.
 */
private val HOME_SECTION_GAP = 28.dp

/**
 * The Home tab — the app's front door (R-arc mandate §2/§3).
 *
 * ## Home is an index, and that decides its whole shape
 *
 * Every row Home offers is *owned* by another tab. It holds no data of its own,
 * it duplicates no screen, and it must never become a fifth place where a
 * feature half-lives. That is why the cross-tab seam is exactly two callbacks and
 * no navigation controller:
 *
 *  - [onOpen] routes through the shell's deep-link helper, which switches to the
 *    target's OWNING tab before pushing its detail;
 *  - [onSwitchTab] switches to a tab with bottom-bar semantics.
 *
 * A bare push from here would stack a Portfolio-owned or People-owned detail on
 * the Home tab, and the next bottom-bar tap would save it under the wrong tab
 * and bounce the user back into it — precisely the bug S6 P1-8 fixed. Keeping
 * the seam this narrow makes that class of mistake impossible to write rather
 * than merely discouraged, which is why this package holds no navigation-
 * controller reference at all: it is a one-line grep in review.
 *
 * [onOpenInbox] and [onOpenDataHome] are the two exceptions, and they prove the
 * rule rather than bending it: the notification inbox and "Where your data lives"
 * are **Home's own** destinations — Home is the tab that carries them, so pushing
 * them onto Home's stack is what SHOULD happen, and there is no `NotifDeepLink`
 * for either. They are named, typed and few; the shell still owns every
 * `navigate` call.
 *
 * ## The order of the screen is the mandate's, and it is load-bearing
 *
 * Value → what moved → what needs a decision → the rest. Nothing about sync,
 * status or scaffolding appears above the fold (mandate §3: "screens opening with
 * infrastructure before value" is the complaint being answered). The pending-sync
 * strip stays on Portfolio for exactly that reason.
 *
 * ## The quiet tail exists because of the overflow rule
 *
 * Fable's design-review addendum: *overflow is a shortcut, never the ONLY path*.
 * Home's ⋮ carries the inbox, discreet mode and settings; [HomeQuickLinks] at the
 * bottom of the screen is the in-content second path for all three. It is placed
 * last and styled as plain rows — no cards, muted glyphs — so it reads as the
 * bottom of an index rather than as content competing with the hero.
 *
 * @param discreetMode current masking state, hoisted from the shell so the row
 *   here and the ⋮ item there are the same control rather than two that can
 *   disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (NotifDeepLink) -> Unit,
    onSwitchTab: (BtTab) -> Unit,
    /**
     * Leave Overview for the selected portfolio's own page (owner IA change).
     *
     * Was `onSwitchTab(BtTab.Portfolio)` while this screen was the Home TAB. It
     * is now hosted BY the Portfolio tab, so that call became a hop to the tab we
     * are already standing on — a no-op the user would read as a dead tap. The
     * intent it always meant ("show me that portfolio") is the one thing left, so
     * it says so.
     */
    onOpenPortfolioView: () -> Unit,
    /**
     * Create a portfolio — opens the switcher sheet, which is where creation
     * lives. Split out from [onOpenPortfolioView] because the two used to share
     * one call and never should have: landing someone on an empty portfolio page
     * and hoping they find the sheet was the old behaviour, and after the IA
     * change that page is the one they are already looking at.
     */
    onCreatePortfolio: () -> Unit,
    onOpenInbox: () -> Unit,
    onOpenDataHome: () -> Unit,
    discreetMode: Boolean,
    onToggleDiscreet: (Boolean) -> Unit,
) {
    val bt = BtTheme.colors
    val storedMode by AppGraph.storageModeStore.mode.collectAsStateWithLifecycle()
    val storageMode = remember(storedMode) { AppGraph.gatedStorageMode(storedMode) }

    val vm: HomeViewModel = viewModel {
        HomeViewModel(
            repo = AppGraph.portfolioRepository,
            alerts = AppGraph.alertsRepository,
            social = AppGraph.socialRepository,
            chat = AppGraph.chatRepository,
            notifications = AppGraph.notificationRepository,
            connectivity = AppGraph.connectivityMonitor,
            gatedMode = { AppGraph.gatedStorageMode(AppGraph.storageModeStore.mode.value) },
        )
    }

    LifecycleResumeEffect(Unit) {
        vm.onScreenResumed()
        onPauseOrDispose { }
    }

    val active by vm.active.collectAsStateWithLifecycle()
    val holdings by vm.holdings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val triggeredAlerts by vm.triggeredAlerts.collectAsStateWithLifecycle()
    val friendRequests by vm.friendRequests.collectAsStateWithLifecycle()
    val unreadMessages by vm.unreadMessages.collectAsStateWithLifecycle()
    val unreadNotifications by vm.unreadNotifications.collectAsStateWithLifecycle()
    val newestNotification by vm.newestNotificationTitle.collectAsStateWithLifecycle()

    // Coverage crosses the portfolio boundary: the W6 caveat is about the union
    // of everything the hero claims to have summed.
    val coverage = remember(holdings) { priceCoverage(holdings) }
    val hero = remember(active, coverage) { homeNetWorth(active, coverage) }
    val movers = remember(holdings) { homeMovers(holdings) }
    val unpriced = remember(storageMode, holdings) { homeUnpriced(storageMode, holdings) }
    val actionRows = remember(
        storageMode,
        triggeredAlerts,
        friendRequests,
        unreadMessages,
        unreadNotifications,
        newestNotification,
    ) {
        homeActionRows(
            mode = storageMode,
            triggeredAlerts = triggeredAlerts,
            friendRequests = friendRequests,
            unreadMessages = unreadMessages,
            unreadNotifications = unreadNotifications,
            newestNotificationTitle = newestNotification,
        )
    }

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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            // No `Arrangement.spacedBy` here, deliberately. The generous rhythm
            // the mandate asks for is carried by each section's OWN top padding
            // instead, because one child on this screen — the mirrorchain invites
            // card — decides for itself whether to draw anything, and a lazy
            // arrangement would still charge 28dp of gap for a zero-height item.
            // A section that renders nothing must cost nothing, so the space
            // belongs to the content rather than to the space between items.
        ) {
            item(key = "hero") {
                HomeHero(
                    state = hero,
                    onCreatePortfolio = onCreatePortfolio,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            // Movers: absent, never an empty card. With no priced day-change
            // anywhere — every Drive install, and any account before its first
            // sync — there is nothing true to put here, and a "no movers" card
            // would spend the second-best position on that fact.
            if (movers.isNotEmpty()) {
                item(key = "movers") {
                    HomeMovers(
                        movers = movers,
                        onOpen = onOpen,
                        modifier = Modifier.padding(top = HOME_SECTION_GAP),
                    )
                }
            }

            // "Needs you". The mirrorchain invites card lives inside the block
            // and self-hides, so the block is rendered whenever EITHER it or the
            // rows might draw. In Drive mode neither can, and the unpriced-
            // holdings block takes the slot instead (§3.5).
            val showsSocial = storageMode.shows(BtSurface.SOCIAL)
            if (actionRows.isNotEmpty() || showsSocial) {
                item(key = "needs-you") {
                    HomeSection(
                        title = stringResource(R.string.bt_home_needs_you),
                        // The header would otherwise announce a section that the
                        // self-hiding invites card may leave completely empty —
                        // and for the same reason the section's top gap is paid
                        // by whichever child actually draws, never by the item.
                        showTitle = actionRows.isNotEmpty(),
                        modifier = Modifier
                            .padding(top = if (actionRows.isEmpty()) 0.dp else HOME_SECTION_GAP)
                            .padding(horizontal = 20.dp),
                    ) {
                        actionRows.forEach { row ->
                            when (row) {
                                is HomeActionRow.TriggeredAlerts -> HomeActionCard(
                                    icon = Icons.Outlined.NotificationsActive,
                                    title = pluralStringResource(
                                        R.plurals.bt_home_alerts_triggered,
                                        row.count,
                                        row.count,
                                    ),
                                    subtitle = stringResource(R.string.bt_home_alerts_triggered_sub),
                                    accented = true,
                                    onClick = {
                                        // The alerts manager is a SEGMENT of
                                        // Workbench, not a route: ask the tab to
                                        // open that segment, then switch —
                                        // identical to the shell's own
                                        // NotifDeepLink.Alerts handling.
                                        WorkboardEntry.requestAlerts()
                                        onSwitchTab(BtTab.Workbench)
                                    },
                                )

                                is HomeActionRow.FriendRequests -> HomeActionCard(
                                    icon = Icons.Outlined.People,
                                    title = pluralStringResource(
                                        R.plurals.bt_home_friend_requests,
                                        row.count,
                                        row.count,
                                    ),
                                    subtitle = stringResource(R.string.bt_home_friend_requests_sub),
                                    onClick = { onOpen(NotifDeepLink.Social) },
                                )

                                is HomeActionRow.UnreadMessages -> HomeActionCard(
                                    icon = Icons.AutoMirrored.Outlined.Chat,
                                    title = pluralStringResource(
                                        R.plurals.bt_home_unread_messages,
                                        row.count,
                                        row.count,
                                    ),
                                    subtitle = stringResource(R.string.bt_home_unread_messages_sub),
                                    onClick = { onOpen(NotifDeepLink.Chat(null)) },
                                )

                                is HomeActionRow.UnreadNotifications -> HomeActionCard(
                                    icon = Icons.Outlined.Inbox,
                                    title = pluralStringResource(
                                        R.plurals.bt_home_unread_notifications,
                                        row.count,
                                        row.count,
                                    ),
                                    // The newest one's title, when there is one:
                                    // a count alone says "there is work", a title
                                    // says whether it is work worth doing now.
                                    subtitle = row.newestTitle
                                        ?: stringResource(R.string.bt_home_unread_notifications_sub),
                                    onClick = onOpenInbox,
                                )
                            }
                        }
                        if (showsSocial) {
                            // The card returns before it touches this modifier
                            // when it has nothing to answer, so an empty invite
                            // list costs exactly zero height AND zero gap.
                            MirrorInvitesCard(
                                modifier = Modifier.padding(
                                    top = if (actionRows.isEmpty()) HOME_SECTION_GAP else 0.dp,
                                ),
                            )
                        }
                    }
                }
            }

            // The Drive user's actual actionable item (§3.5.3): a holding with no
            // price is money missing from the hero, and it is the one thing this
            // mode's user can personally fix.
            unpriced?.let { state ->
                item(key = "unpriced") {
                    HomeUnpricedBlock(
                        state = state,
                        onOpenHolding = { assetId -> onOpen(NotifDeepLink.Holding(assetId)) },
                        onSeeAll = onOpenPortfolioView,
                        modifier = Modifier
                            .padding(top = HOME_SECTION_GAP)
                            .padding(horizontal = 20.dp),
                    )
                }
            }

            if (active.isNotEmpty()) {
                item(key = "portfolios") {
                    HomeSection(
                        title = stringResource(R.string.bt_home_portfolios),
                        modifier = Modifier
                            .padding(top = HOME_SECTION_GAP)
                            .padding(horizontal = 20.dp),
                    ) {
                        active.forEach { p ->
                            HomePortfolioRow(
                                portfolio = p,
                                onClick = {
                                    // Selection first, then the view: the
                                    // portfolio page reads the governing
                                    // selection on composition, so leaving
                                    // Overview first would show the previous
                                    // portfolio for a frame.
                                    vm.selectPortfolio(p.id)
                                    onOpenPortfolioView()
                                },
                            )
                        }
                    }
                }
            }

            item(key = "quick-links") {
                HomeQuickLinks(
                    showInbox = storageMode.shows(BtSurface.ALERTS_NOTIFICATIONS),
                    showDataHome = storageMode.shows(BtSurface.VAULT_SETTINGS),
                    unreadNotifications = unreadNotifications,
                    discreetMode = discreetMode,
                    onOpenInbox = onOpenInbox,
                    onOpenDataHome = onOpenDataHome,
                    onToggleDiscreet = onToggleDiscreet,
                    onOpenSettings = { onOpen(NotifDeepLink.Settings) },
                    modifier = Modifier
                        .padding(top = HOME_SECTION_GAP)
                        .padding(horizontal = 20.dp),
                )
            }
        }
    }
}

// ── Hero ────────────────────────────────────────────────────────────────────

/**
 * Net worth across every active portfolio.
 *
 * The shapes [homeNetWorth] can return map onto visually distinct things on
 * purpose: a confident number, a number with a stated scope, and no number at
 * all. Nothing here renders a figure the logic did not authorise.
 */
@Composable
private fun HomeHero(
    state: HomeHeroState,
    onCreatePortfolio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()

    // Discreet mode: press and hold the hero to peek, release to re-hide. Bound
    // to the gesture rather than a latch, and only armed while masking — exactly
    // the Portfolio hero's contract, because it is the same act on a bigger
    // number and learning it twice would be absurd.
    val peek = if (BtDiscreetMode.enabled) {
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

    Column(modifier.fillMaxWidth().then(peek)) {
        Text(
            text = stringResource(R.string.bt_home_net_worth),
            style = MaterialTheme.typography.bodySmall,
            color = bt.textMuted,
        )
        Spacer(Modifier.height(4.dp))
        when (state) {
            HomeHeroState.Loading -> {
                BtSkeleton(Modifier.width(240.dp).height(48.dp))
                Spacer(Modifier.height(8.dp))
                BtSkeleton(Modifier.width(140.dp).height(14.dp))
            }

            // R3 §2: the app's front door, in the one state where the user has
            // nothing yet, used to state the problem and then stop — while
            // Portfolio's equivalent empty state has carried a Create button
            // since R1. Home is an INDEX, so it must not own the create flow; it
            // hands the user to the tab that does, through onSwitchTab like every
            // other cross-tab move on this screen. The body copy already named
            // that tab, which is exactly the sentence a button should replace.
            HomeHeroState.NoPortfolios -> {
                Text(
                    text = stringResource(R.string.bt_home_no_portfolios_title),
                    style = BtTheme.type.moneyLarge,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.bt_home_no_portfolios_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = bt.textMuted,
                )
                Spacer(Modifier.height(14.dp))
                BtSecondaryButton(
                    text = stringResource(R.string.bt_overview_create_portfolio),
                    onClick = onCreatePortfolio,
                )
            }

            is HomeHeroState.Ready -> when (val worth = state.netWorth) {
                is NetWorthState.Unpriceable -> NoPricesHero()

                is NetWorthState.Value -> {
                    MoneyText(value = worth.eur, style = BtTheme.type.moneyHero)
                    // Two independent caveats, both load-bearing, neither
                    // allowed to stand in for the other: how many PORTFOLIOS
                    // the sum covers, and how many HOLDINGS inside them could
                    // be priced at all.
                    if (state.partial) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // "X of Y portfolios" — the noun belongs to Y, so
                            // the ACTIVE count picks the form, not the covered.
                            text = pluralStringResource(
                                R.plurals.bt_home_across_portfolios,
                                state.active,
                                state.covered,
                                state.active,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = bt.textMuted,
                        )
                    }
                    UnpricedNote(
                        coverage = worth.coverage,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (state.showDayChange) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoneyText(
                                value = state.dayChangeEur,
                                style = BtTheme.type.numberCaption,
                                colorMode = MoneyColorMode.GainLoss,
                                showSign = true,
                            )
                            state.dayChangePct?.let { pct ->
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
            }
        }
    }
}

// ── Movers ──────────────────────────────────────────────────────────────────

/**
 * Today's biggest moves, as a horizontally scrolling row of compact cards.
 *
 * A row rather than a list, for two reasons that both come from position: movers
 * are the SECOND thing on the screen, so they must not cost a full screen-third
 * of vertical space; and five equal-weight items with no natural ranking beyond
 * "size of move" are exactly what a horizontal set reads as, where a vertical
 * list would imply a top-to-bottom priority the data does not have. The row
 * bleeds past the page inset so a half-visible sixth card says "there is more
 * here" without a chevron saying it.
 */
@Composable
private fun HomeMovers(
    movers: List<HoldingEntity>,
    onOpen: (NotifDeepLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Column(modifier) {
        Text(
            text = stringResource(R.string.bt_home_movers),
            style = MaterialTheme.typography.titleMedium,
            color = bt.textPrimary,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(count = movers.size, key = { movers[it].assetId }) { index ->
                MoverCard(
                    holding = movers[index],
                    onClick = { onOpen(NotifDeepLink.Holding(movers[index].assetId)) },
                )
            }
        }
    }
}

@Composable
private fun MoverCard(holding: HoldingEntity, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val pct = holding.dayChangePct ?: 0.0
    BtCard(modifier = Modifier.width(132.dp), onClick = onClick) {
        // Rail basis is DAY change, not the selected range — the section header
        // says "today's movers", so a range-based accent would make the label lie.
        BtRailedRow(rail = rangeRail(holding.dayChangePct)) {
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    text = holding.assetSymbol,
                    style = MaterialTheme.typography.titleSmall,
                    color = bt.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatPercent(pct, locale),
                    style = BtTheme.type.moneyMedium,
                    color = deltaColor(pct),
                )
                Spacer(Modifier.height(2.dp))
                holding.marketValueEur?.let {
                    MoneyText(value = it, style = BtTheme.type.numberCaption)
                }
            }
        }
    }
}

// ── Sections and rows ───────────────────────────────────────────────────────

/**
 * A titled block.
 *
 * [showTitle] exists for the one case where a section's content is a self-hiding
 * child: rendering a heading above nothing is worse than rendering nothing, and
 * the alternative — lifting the child's emptiness into Home — would mean Home
 * re-deriving state a component already owns.
 */
@Composable
private fun HomeSection(
    title: String,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val bt = BtTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = bt.textPrimary,
            )
        }
        content()
    }
}

/**
 * One actionable row.
 *
 * [accented] tints the leading glyph gold and is reserved for the alerts row —
 * the only one of the four that can be about money moving without the user.
 * Everything else on this screen that is merely waiting gets the same neutral
 * weight, so the accent keeps meaning something.
 */
@Composable
private fun HomeActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accented: Boolean = false,
) {
    val bt = BtTheme.colors
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accented) bt.goldEmphasis else bt.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = bt.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = bt.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = bt.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * "N holdings need a price", with the first few named.
 *
 * Named rather than counted because the count alone sends the user hunting: three
 * symbols and a tap each is the whole task, done from Home. The tap lands on the
 * holding's own detail, which is where the manual-price sheet already lives — no
 * new plumbing, and the price the user enters is applied by the code that already
 * owns applying it.
 */
@Composable
private fun HomeUnpricedBlock(
    state: HomeUnpriced,
    onOpenHolding: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    HomeSection(
        title = pluralStringResource(R.plurals.bt_home_unpriced, state.total, state.total),
        modifier = modifier,
    ) {
        state.preview.forEach { holding ->
            BtCard(modifier = Modifier.fillMaxWidth(), onClick = { onOpenHolding(holding.assetId) }) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PriceChange,
                        contentDescription = null,
                        tint = bt.goldEmphasis,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = holding.assetSymbol,
                            style = MaterialTheme.typography.bodyLarge,
                            color = bt.textPrimary,
                        )
                        Text(
                            text = holding.assetName,
                            style = MaterialTheme.typography.labelSmall,
                            color = bt.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.bt_price_add),
                        style = MaterialTheme.typography.labelLarge,
                        color = bt.gold,
                    )
                }
            }
        }
        if (state.hasMore) {
            HomeQuietRow(
                icon = Icons.Outlined.MoreHoriz,
                label = pluralStringResource(
                    R.plurals.bt_home_unpriced_more,
                    state.total - state.preview.size,
                    state.total - state.preview.size,
                ),
                onClick = onSeeAll,
            )
        }
    }
}

/** One portfolio: name, value, today's change. */
@Composable
private fun HomePortfolioRow(portfolio: PortfolioEntity, onClick: () -> Unit) {
    val bt = BtTheme.colors
    val locale = rememberBtLocale()
    val totals = portfolio.totals
    BtCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = portfolio.name,
                style = MaterialTheme.typography.titleSmall,
                color = bt.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (totals != null) {
                    MoneyText(value = totals.totalValueEur, style = BtTheme.type.moneySmall)
                    totals.dayChangePct?.let { pct ->
                        Text(
                            text = formatPercent(pct, locale),
                            style = BtTheme.type.numberCaption,
                            color = deltaColor(pct),
                        )
                    }
                } else {
                    // Not "0,00 €": this portfolio's detail has not landed yet,
                    // and the hero above already says the sum is partial. The
                    // skeleton is the same statement at row scale.
                    BtSkeleton(Modifier.width(72.dp).height(16.dp))
                }
            }
        }
    }
}

// ── The quiet tail ──────────────────────────────────────────────────────────

/**
 * Home's in-content path to everything its ⋮ carries (Fable design review #1:
 * *overflow is a shortcut, never the ONLY path*).
 *
 * Plain rows, no cards, muted glyphs, last on the screen. The visual quiet is the
 * design: these are not things to do, they are places to go, and an index earns
 * its keep by listing them somewhere findable without competing with the money at
 * the top. The inbox row carries its unread count so the ⋮ is never the only
 * place that number appears either.
 */
@Composable
private fun HomeQuickLinks(
    showInbox: Boolean,
    showDataHome: Boolean,
    unreadNotifications: Int,
    discreetMode: Boolean,
    onOpenInbox: () -> Unit,
    onOpenDataHome: () -> Unit,
    onToggleDiscreet: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bt = BtTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.bt_home_more),
            style = MaterialTheme.typography.labelMedium,
            color = bt.textMuted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (showInbox) {
            HomeQuietRow(
                icon = Icons.Outlined.Inbox,
                label = stringResource(R.string.bt_top_notifications),
                trailing = if (unreadNotifications > 0) unreadNotifications.toString() else null,
                onClick = onOpenInbox,
            )
        }
        HomeQuietRow(
            // Gold while ON: the one row here that changes what every other
            // screen renders should not look like the rows that merely navigate.
            icon = if (discreetMode) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            iconAccented = discreetMode,
            label = stringResource(R.string.bt_settings_discreet),
            trailing = stringResource(
                if (discreetMode) R.string.bt_home_on else R.string.bt_home_off,
            ),
            onClick = { onToggleDiscreet(!discreetMode) },
        )
        if (showDataHome) {
            HomeQuietRow(
                icon = Icons.Outlined.Shield,
                label = stringResource(R.string.bt_storage_settings_row),
                onClick = onOpenDataHome,
            )
        }
        HomeQuietRow(
            icon = Icons.Outlined.Settings,
            label = stringResource(R.string.bt_top_settings),
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun HomeQuietRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    iconAccented: Boolean = false,
) {
    val bt = BtTheme.colors
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = bt.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (iconAccented) bt.gold else bt.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = bt.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = bt.textMuted,
                )
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = bt.textMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
