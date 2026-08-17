package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink

/**
 * The four bottom-navigation destinations.
 *
 * Declaration order IS bar order: Portfolio · Markets · Workbench · People. The
 * shell filters this set per storage mode but never reorders it, so a Drive-only
 * bar is a subsequence of the full one rather than a different bar.
 *
 * ## Why Workbench sits third (owner order, 2026-08-07)
 *
 * The bar used to read Portfolio · Workbench · Markets · People. The owner moved
 * Workbench behind Markets: the two tabs you reach for constantly are your own
 * holdings and the market you are buying into, so they belong side by side under
 * the thumb, and the Workbench — alerts and conglomerates, both of which you set
 * up once and then let run — does not earn the second slot. It also makes the
 * horizontal swipe (see [tabNeighbour]) read as a sensible progression: your
 * money, then the market, then the tools you point at it, then the people.
 *
 * ## Why Home is not here (owner IA change, 2026-08-05)
 *
 * R1 made Home a fifth tab. The owner's verdict on living with it: *"bottom nav
 * has too many items; I liked the portfolio top selector; move the home/overview
 * there as the top most portfolio."* So Home did not lose its content — it lost
 * its **tab**. It is now "Overview", the pinned first entry of the portfolio
 * switcher sheet, and selecting it makes the Portfolio tab render exactly what
 * the Home tab used to. One tab fewer in the bar, one entry more in a sheet that
 * already existed, and nothing the user could do before is gone.
 *
 * Having the tab set as an enum (rather than only as a list of UI specs inside
 * the shell) is what makes the deep-link routing rule below a PURE function the
 * unit tests can pin down — no NavController, no Compose.
 *
 * ## Why there is no `route` any more (architecture change 2026-08-08)
 *
 * Each entry used to carry its typed `...TabRoute`, because a tab was a NavHost
 * destination and switching to one meant navigating to it. The four tabs are now
 * four live pages in [at.bettertrack.app.ui.shell.BtTabPager] — they are not in
 * the graph at all, and their route objects were deleted rather than left
 * pointing at nothing. A tab's identity is its position in this enum, which is
 * also its page index, which is the only address the pager needs.
 */
enum class BtTab {
    Portfolio,
    Markets,
    Workbench,
    People,
}

/**
 * What a bottom-bar tap means.
 *
 * [Switch] is the normal case. [OpenPortfolioSwitcher] is the owner's 2026-08-07
 * directive: tapping **Portfolio** while the Portfolio tab is already the screen
 * you are looking at opens the portfolio selector sheet — the same sheet the
 * header pill opens — instead of re-navigating to a tab you are already on.
 *
 * That is a Portfolio-only rule. Every other tab keeps re-tap doing nothing new,
 * because no other tab has a "which one of these am I looking at" question to
 * answer, and inventing per-tab re-tap behaviours would make the bar's one
 * gesture mean four things.
 */
enum class TabTap { Switch, OpenPortfolioSwitcher }

/**
 * Decide what a tap on [tapped] does.
 *
 * @param exactTab the tab whose route the current destination IS — **not** the
 *   tab it merely lives under. `null` while a pushed screen is showing, which is
 *   deliberate: re-tapping Portfolio from a holding detail should get you back to
 *   the tab, which is what an ordinary [TabTap.Switch] already does. Opening a
 *   sheet on top of a screen the user is trying to leave would be the opposite of
 *   what the tap asked for.
 */
fun tabTapAction(tapped: BtTab, exactTab: BtTab?): TabTap =
    if (tapped == BtTab.Portfolio && exactTab == BtTab.Portfolio) {
        TabTap.OpenPortfolioSwitcher
    } else {
        TabTap.Switch
    }

/*
 * `tabNeighbour` is deleted (architecture change 2026-08-08).
 *
 * It answered "which tab does a swipe in this direction land on", against the
 * VISIBLE bar so a filtered Drive-only bar could not swipe onto a tab it does not
 * render. [at.bettertrack.app.ui.shell.BtTabPager] is built from that same
 * visible list and a pager cannot leave its own page range, so the rule is now
 * enforced by construction instead of by a lookup — and the shell has nothing
 * left to ask it. The guarantee it protected is pinned in `BtTabPagerTest`
 * instead, at the level where it now lives.
 */

/**
 * Which tab OWNS a deep-link target (S6 P1-8), or `null` for the targets that no
 * tab owns.
 *
 * A notification tap pushes a detail route. If it is pushed onto whatever tab
 * the user happened to be standing on, the very next bottom-bar tap runs
 * `popUpTo(start){saveState}` and saves that detail under the WRONG tab — so
 * returning to that tab bounces the user straight back into the detail they had
 * just left. Every link with an owning tab therefore switches to it first
 * (bottom-bar semantics) and only then pushes.
 *
 * ## The account-level exception, and why it is `null` rather than a tab
 *
 * Settings, Security, Notification settings and the Inbox are not *about* any
 * tab: they are account-level surfaces reachable from every one of them. Before the IA change
 * they were mapped to Home purely because Home was the graph's start
 * destination — a deterministic parent, chosen for the absence of a better one.
 *
 * With Home gone that mapping would have to become Portfolio, and that would be
 * strictly worse than it looks: a "your password was changed" tap would yank the
 * user off Workbench or People onto Portfolio and leave them there when they
 * backed out of settings. The owner's rule is explicit — these *push over
 * whatever is selected, don't force a switch*. `null` says exactly that, and the
 * shell reads it as "skip the tab switch, just push". The saved-state hazard the
 * owning-tab rule exists to prevent does not apply to them, because none of the
 * three is a tab's detail: backing out returns to the tab the user was already
 * on, which is where they wanted to be.
 */
fun owningTab(link: NotifDeepLink): BtTab? = when (link) {
    // People, and everything people share with you.
    NotifDeepLink.Social,
    is NotifDeepLink.SharedPortfolio,
    is NotifDeepLink.FriendOverview,
    is NotifDeepLink.PublicProfile,
    is NotifDeepLink.SharedConglomerate,
    is NotifDeepLink.Chat,
    -> BtTab.People

    // Market pages live under Markets…
    is NotifDeepLink.Asset -> BtTab.Markets
    // …but a HELD position is portfolio data, not market data.
    is NotifDeepLink.Holding -> BtTab.Portfolio

    // The price-alert manager is a Workbench segment.
    NotifDeepLink.Alerts -> BtTab.Workbench

    // Overview is a SELECTION inside the Portfolio tab, not a tab of its own
    // (owner IA change 2026-08-05). It still names Portfolio here, because the
    // tab is genuinely where it renders — the shell pairs this switch with the
    // selection itself, exactly as it does for the Alerts segment above.
    NotifDeepLink.Overview -> BtTab.Portfolio

    // Cash is portfolio-scoped data (budgets, the ledger) reached from the
    // Portfolio overview, so it names the Portfolio tab and the shell pushes the
    // Cash sheet over it — the same shape as a held-position detail above.
    is NotifDeepLink.Cash -> BtTab.Portfolio

    // A specific portfolio is a SELECTION inside the Portfolio tab (the
    // switcher's own state), so it names the tab that renders it and the shell
    // pairs the switch with the selection — the same shape as Overview above.
    // Added for the home-screen portfolio widgets (2026-08-16).
    is NotifDeepLink.Portfolio -> BtTab.Portfolio

    // Quick-actions widget (2026-08-16): a new trade is portfolio work, a new
    // cash entry lives on the Portfolio tab's Cash sheet, and search is the
    // Markets tab's own first control.
    NotifDeepLink.AddTransaction -> BtTab.Portfolio
    is NotifDeepLink.AddCashEntry -> BtTab.Portfolio
    NotifDeepLink.MarketSearch -> BtTab.Markets

    // Quick Links widget (2026-08-17): the watchlist is a PANEL inside the
    // Markets tab, so the tab that renders it is the tab that owns it — see
    // [NotifDeepLink.Watchlist] for why it is its own target and not an alias
    // of MarketSearch.
    NotifDeepLink.Watchlist -> BtTab.Markets

    // Account-level: owned by no tab, pushed over the current one — see the KDoc.
    // The inbox belongs here for the same reason and one of its own: it is now
    // reachable from the bell on ALL FOUR tabs, so there is no tab it could be
    // said to live under, and forcing a switch would move the user for nothing.
    NotifDeepLink.Settings,
    NotifDeepLink.Security,
    NotifDeepLink.NotificationSettings,
    NotifDeepLink.Inbox,
    -> null
}
