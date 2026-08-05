package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink

/**
 * The five bottom-navigation destinations, each paired with its typed route.
 *
 * Declaration order IS bar order (R-arc mandate §2): Home · Portfolio ·
 * Workbench · Markets · People. The shell filters this set per storage mode but
 * never reorders it, so a Drive-only bar is a subsequence of the full one rather
 * than a different bar.
 *
 * Having the tab set as an enum (rather than only as a list of UI specs inside
 * the shell) is what makes the deep-link routing rule below a PURE function the
 * unit tests can pin down — no NavController, no Compose.
 */
enum class BtTab(val route: Any) {
    Home(HomeTabRoute),
    Portfolio(PortfolioTabRoute),
    Workbench(WorkbenchTabRoute),
    Markets(MarketsTabRoute),
    People(PeopleTabRoute),
}

/**
 * Which tab OWNS a deep-link target (S6 P1-8).
 *
 * A notification tap pushes a detail route. If it is pushed onto whatever tab
 * the user happened to be standing on, the very next bottom-bar tap runs
 * `popUpTo(start){saveState}` and saves that detail under the WRONG tab — so
 * returning to that tab bounces the user straight back into the detail they had
 * just left. Every link therefore switches to its owning tab first (bottom-bar
 * semantics) and only then pushes.
 *
 * The account-level destinations (settings, security, notification settings)
 * have no tab of their own: they live behind Home's overflow, which the R-arc
 * top-bar rule (mandate §1) made their single entry point. They are mapped to
 * Home — the graph's START destination — so that they always get the same,
 * predictable parent instead of whichever tab happened to be selected when the
 * push arrived.
 *
 * That fallback is *stronger* after R1 than it was before it. It used to point
 * at Portfolio, which was the start destination only by accident of being first;
 * Home is the start destination by construction, and its surface is `FULL` in
 * every storage mode, so the fallback tab can never be one this install hides.
 */
fun owningTab(link: NotifDeepLink): BtTab = when (link) {
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

    // Account-level: no tab of their own — see the KDoc above.
    NotifDeepLink.Settings,
    NotifDeepLink.Security,
    NotifDeepLink.NotificationSettings,
    -> BtTab.Home
}
