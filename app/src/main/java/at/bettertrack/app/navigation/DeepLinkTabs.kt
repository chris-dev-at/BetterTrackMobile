package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink

/**
 * The four bottom-navigation destinations, each paired with its typed route.
 *
 * Declaration order IS bar order: Portfolio · Workbench · Markets · People. The
 * shell filters this set per storage mode but never reorders it, so a Drive-only
 * bar is a subsequence of the full one rather than a different bar.
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
 */
enum class BtTab(val route: Any) {
    Portfolio(PortfolioTabRoute),
    Workbench(WorkbenchTabRoute),
    Markets(MarketsTabRoute),
    People(PeopleTabRoute),
}

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
 * Settings, Security and Notification settings are not *about* any tab: they are
 * account-level surfaces reachable from every one of them. Before the IA change
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

    // Account-level: owned by no tab, pushed over the current one — see the KDoc.
    NotifDeepLink.Settings,
    NotifDeepLink.Security,
    NotifDeepLink.NotificationSettings,
    -> null
}
