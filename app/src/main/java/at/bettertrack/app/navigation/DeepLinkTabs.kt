package at.bettertrack.app.navigation

import at.bettertrack.app.data.notifications.NotifDeepLink

/**
 * The four bottom-navigation destinations, each paired with its typed route.
 *
 * Having the tab set as an enum (rather than only as a list of UI specs inside
 * the shell) is what makes the deep-link routing rule below a PURE function the
 * unit tests can pin down — no NavController, no Compose.
 */
enum class BtTab(val route: Any) {
    Portfolio(PortfolioTabRoute),
    Assets(AssetsTabRoute),
    Social(SocialTabRoute),
    Workboard(WorkboardTabRoute),
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
 * have no tab of their own: they hang off the top bar, which is visible on all
 * four tabs. They are mapped to Portfolio — the graph's START destination — so
 * that they always get the same, predictable parent instead of whichever tab
 * happened to be selected when the push arrived.
 */
fun owningTab(link: NotifDeepLink): BtTab = when (link) {
    // People, and everything people share with you.
    NotifDeepLink.Social,
    is NotifDeepLink.SharedPortfolio,
    is NotifDeepLink.FriendOverview,
    is NotifDeepLink.PublicProfile,
    is NotifDeepLink.SharedConglomerate,
    is NotifDeepLink.Chat,
    -> BtTab.Social

    // Market pages live under Assets…
    is NotifDeepLink.Asset -> BtTab.Assets
    // …but a HELD position is portfolio data, not market data.
    is NotifDeepLink.Holding -> BtTab.Portfolio

    // The price-alert manager is a Workboard segment.
    NotifDeepLink.Alerts -> BtTab.Workboard

    // Account-level: no tab of their own — see the KDoc above.
    NotifDeepLink.Settings,
    NotifDeepLink.Security,
    NotifDeepLink.NotificationSettings,
    -> BtTab.Portfolio
}
