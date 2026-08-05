# R1 IMPLEMENTATION SPEC (agent-authored 2026-08-05; coordinator decisions appended at bottom)

# R1 IMPLEMENTATION SPEC — nav skeleton + top bar + Home + Portfolio

**Sources read:** `PLATFORM_ASKS.md:461-498` (R-arc mandate), `docs/S6_UX_DEBT.md:1-62`, `ui/shell/AppShell.kt` (985 ln), `ui/shell/BtRoot.kt`, `navigation/BtRoutes.kt`, `navigation/DeepLinkTabs.kt`, `ui/screens/TabScreens.kt`, `ui/portfolio/PortfolioOverviewScreen.kt` (1010 ln) + `PortfolioOverviewViewModel.kt` + `PortfolioSwitcherLogic.kt`, `data/storage/StorageSurfaces.kt`, `ui/social/SocialScreen.kt`, `ui/workboard/WorkboardScreen.kt`, `data/repo/*`, `ui/components/*`.

**Baseline:** main @ `293980e`, 2039 unit tests green (commit `0e4e90a`). Dark-only theme (no `values-night`, `BtColors.kt:18` is the sole scheme) — see §6 open question O-12.

---

## 1. NAV MODEL

### 1.1 What exists today

`BtTab` is a 4-entry enum (`navigation/DeepLinkTabs.kt:12-17`) paired 1:1 with four `@Serializable data object` routes (`navigation/BtRoutes.kt:20-23`). The shell mirrors them as `TabSpec` records (`ui/shell/AppShell.kt:175-189`), each carrying a `BtSurface` used for W5 per-mode gating via `tabsFor()` (`AppShell.kt:204-207`) → `visibleTabSurfaces()` (`data/storage/StorageSurfaces.kt:135-137`). Start destination is `PortfolioTabRoute` (`AppShell.kt:609`).

### 1.2 The five destinations — every identifier

Bar order follows the mandate verbatim (`PLATFORM_ASKS.md:475`): **Home · Portfolio · Workbench · Markets · People**.

| # | `BtTab` | Route (`BtRoutes.kt`) | EN key / value | DE value | Icon (`material.icons.outlined`) | `BtSurface` |
|---|---|---|---|---|---|---|
| 1 | `Home` **NEW** | `HomeTabRoute` **NEW** | `bt_tab_home` = "Home" | "Start" | `Home` | `HOME` **NEW** |
| 2 | `Portfolio` | `PortfolioTabRoute` (unchanged) | `bt_tab_portfolio` = "Portfolio" | "Portfolio" | `PieChart` (unchanged) | `PORTFOLIO` |
| 3 | `Workbench` ← `Workboard` | `WorkbenchTabRoute` ← `WorkboardTabRoute` | `bt_tab_workbench` = "Workbench" | "Werkbank" | `Dashboard` (unchanged) | `CONGLOMERATES` (see note) |
| 4 | `Markets` ← `Assets` | `MarketsTabRoute` ← `AssetsTabRoute` | `bt_tab_markets` = "Markets" | "Märkte" | `AutoMirrored.ShowChart` (unchanged) | `MARKET` |
| 5 | `People` ← `Social` | `PeopleTabRoute` ← `SocialTabRoute` | `bt_tab_people` = "People" | "Leute" | `People` (unchanged) | `SOCIAL` |

**String keys:** `bt_tab_assets`, `bt_tab_social`, `bt_tab_workboard` (`res/values/strings.xml:42-44`, `values-de:42-44`) are **retired**, three new keys added. Do **not** delete the old keys in the R1 body commit — see §5.4 (WP-B collision rule); they are removed in the R1 tail commit.

**`BtSurface.CONGLOMERATES` stays named as-is.** It is the §4.5 table constant mirrored from `docs/S3S4_STORAGE_PLAN.md` and appears in `StorageSurfaces.kt:43-44,97,112-121` plus 7 tests. Renaming it to `WORKBENCH` would drift the app from the storage plan doc for zero user-visible gain. Document the mapping in the `TabSpec` KDoc instead. (Open decision O-2 if the coordinator disagrees.)

### 1.3 `BtSurface.HOME` and the gating interaction

Add `HOME` as the first constant of `BtSurface` (`StorageSurfaces.kt:30-66`) and return `FULL` for it in all three branches of `surfaceAvailability` (`StorageSurfaces.kt:88-122`). Rationale: Home is not a feature, it is an **index over whatever this mode has** — it can never be absent, and giving it a surface preserves the existing invariant ("the bars and the routes below cannot disagree about what this install can do", `AppShell.kt:227-229`) and keeps `visibleTabSurfaces` a pure, Compose-free, unit-testable function.

`visibleTabSurfaces` (`StorageSurfaces.kt:135-137`) becomes:

```
listOf(HOME, PORTFOLIO, CONGLOMERATES, MARKET, SOCIAL).filter { mode.shows(it) }
```

Resulting bars:

| Mode | Today (`StorageSurfacesTest.kt:123-138`) | After R1 |
|---|---|---|
| SERVER / BOTH / UNSET | Portfolio · Assets · Social · Workboard (4) | **Home · Portfolio · Workbench · Markets · People (5)** |
| DRIVE | Portfolio · Assets (2) | **Home · Portfolio · Markets (3)** |

**Drive-only now gets a real front door.** Workbench stays absent (`CONGLOMERATES → ABSENT`, `StorageSurfaces.kt:97`) and People stays absent (`SOCIAL → ABSENT`, line 98) — correct: a Drive install has no BetterTrack account, so those are features that *cannot exist*, not features it is missing (the reasoning already written at `AppShell.kt:191-203`). What Drive-only Home shows instead of the absent surfaces is specified in §3.5.

`tabsFor()` (`AppShell.kt:204-207`) is unchanged in shape — it keeps filtering `Tabs` by `visibleTabSurfaces(mode)`. Only the `Tabs` list (`AppShell.kt:184-189`) gains a row.

**Two guard tests must be rewritten, not just extended:**
- `StorageSurfacesTest.kt:132-138` (`the drive bar is portfolio and assets only`) → `the drive bar is home, portfolio and markets`.
- `StorageSurfacesTest.kt:140-148` (`every mode keeps the portfolio tab first`) → `every mode keeps HOME first` — and the reasoning in its comment ("It is the NavHost's start destination in every mode") transfers intact to Home, which is *strictly* stronger than today: `PORTFOLIO` happened to be `FULL` everywhere, `HOME` is `FULL` by construction.

### 1.4 Portfolio-as-start-destination → Home

`NavHost(startDestination = PortfolioTabRoute)` (`AppShell.kt:609`) → `HomeTabRoute`. Three consequences, all of which R1 must handle explicitly:

1. **`popUpTo(graph.findStartDestination().id) { saveState = true }`** appears twice — the deep-link tab switch (`AppShell.kt:242`) and the bottom-bar tap (`AppShell.kt:387`). Both now pop to Home. This is the correct Android idiom and it *improves* back-stack semantics: system-back from any tab lands on Home; back from Home exits. Today back from any tab lands on Portfolio, which is arbitrary now that Portfolio is one of five peers.
2. **`owningTab`'s account-level fallback** (`DeepLinkTabs.kt:53-57`) is explicitly documented as "the graph's START destination" and pinned by a test that asserts exactly that reasoning (`DeepLinkTabsTest.kt:95-101`). `Settings` / `Security` / `NotificationSettings` must move `Portfolio → Home`. The KDoc's justification ("always the same, predictable parent instead of whichever tab happened to be selected") transfers verbatim.
3. **`NotifDeepLink.Holding → BtTab.Portfolio` stays** (`DeepLinkTabs.kt:48`) — a held position is portfolio data, and that distinction (`DeepLinkTabsTest.kt:39-43`) is one of the things WP-C got right.

### 1.5 Deep-link migration — all 12 targets

No deep-link *contract* changes. `NotifDeepLink` (`data/notifications/NotificationModels.kt:187-223`), `resolveDeepLink`, `AppGraph.pendingDeepLink` (cold-start park, `AppShell.kt:304-310`), and the `open()` helper's three-step rule (`AppShell.kt:247-260`) are all untouched. Only the owning-tab constant changes:

| `NotifDeepLink` | Today | After R1 | Pushed route (unchanged) |
|---|---|---|---|
| `Social` | `Social` | `People` | — (tab only) |
| `SharedPortfolio(id)` | `Social` | `People` | `SharedPortfolioViewRoute` |
| `FriendOverview(uid,un)` | `Social` | `People` | `FriendOverviewRoute` |
| `PublicProfile(un)` | `Social` | `People` | `FriendOverviewRoute` after async friend lookup (`AppShell.kt:268-282`) |
| `SharedConglomerate(id)` | `Social` | `People` | `SharedConglomerateViewRoute` |
| `Chat(cid)` | `Social` | `People` | `ChatListRoute` |
| `Asset(id)` | `Assets` | `Markets` | `AssetPageRoute` |
| `Holding(id)` | `Portfolio` | `Portfolio` **(unchanged)** | `HoldingDetailRoute` |
| `Alerts` | `Workboard` | `Workbench` | none — `WorkboardEntry.requestAlerts()` + tab switch (`AppShell.kt:290-293`, `ui/workboard/WorkboardEntry.kt`) |
| `Settings` | `Portfolio` | **`Home`** | `SettingsRoute` |
| `Security` | `Portfolio` | **`Home`** | `SettingsSecurityRoute` |
| `NotificationSettings` | `Portfolio` | **`Home`** | `SettingsNotificationsRoute` |

`DeepLinkTabsTest.kt` needs: `Social`→`People` renames in 3 tests (lines 22-35), `Assets`→`Markets` (line 41), `Workboard`→`Workbench` (line 49), and the three account-level assertions (lines 54-59) flipped to `Home` — plus the structural guards at lines 85-101 re-pointed. The `every deep link target maps to a tab` guard (lines 63-83) and `each tab carries its own distinct typed route` (85-93) both survive with a fifth entry.

### 1.6 Back-stack semantics (unchanged mechanics, stated for the record)

Bottom-bar `onSelect` keeps `popUpTo(start){saveState}; launchSingleTop; restoreState` (`AppShell.kt:385-393`). Each tab keeps its own saved stack; deep links keep switching-then-pushing so a detail is never saved under the wrong tab (the S6 P1-8 fix). **New R1 rule to add:** re-tapping the already-selected tab should be a no-op-with-scroll-to-top rather than a re-navigate — see open decision O-6.

---

## 2. TOP BAR — the 3-element rule, per screen

### 2.1 What the shell bar carries today

`BtTopBar` (`AppShell.kt:420-488`) renders on every top-level tab (`isTopLevel`, `AppShell.kt:220-222, 336`) and carries up to **six** elements:

| Slot | Element | Site |
|---|---|---|
| title | `Wordmark` + hidden long-press → debug gallery | `AppShell.kt:441-449` |
| title | `PortfolioSelectorChip` (Portfolio tab only) | `AppShell.kt:451-458`, composable at `525-568`, VM wiring at `341-358` |
| action 1 | Search icon | `AppShell.kt:462-469` |
| action 2 | `BtTopBarChats` + gold unread badge (WP-C P1-10) | `AppShell.kt:473`, composable at `498-516` |
| action 3 | `NotificationBell` + unread badge | `AppShell.kt:474`, `ui/notifications/NotificationBell.kt:24-44` |
| action 4 | Settings icon | `AppShell.kt:475-481` |

This is precisely the accretion the owner is reacting to.

### 2.2 R1 target — the five top-level destinations

| Screen | Today | Context/title | ONE action | Overflow (⋮) | What dies / where it lands |
|---|---|---|---|---|---|
| **Home** (new) | n/a | `Wordmark` (the one place in-app it still earns its keep) + long-press→gallery kept | **Search** → `SearchRoute` | Notifications inbox · Discreet mode toggle · Settings · (debug: Dev backend) | — |
| **Portfolio** | wordmark + selector chip + 4 icons | **Collapsing large title = portfolio name**, tap→switcher sheet (§4) | none (FAB owns creation — mandate §1 "never both") | Transactions · Cash · Pending sync · Manage portfolios | selector chip → header title; search/chat/bell/settings → gone from this screen |
| **Workbench** | wordmark + 4 icons | Large title "Workbench" | none in R1 (segments are content) | (R2) | triggered-alerts count stays as the *segment* badge (`WorkboardScreen.kt:189-194`) **and** is promoted to a tab dot |
| **Markets** | wordmark + 4 icons | Large title "Markets" | none — the in-content search field **is** the entry (`TabScreens.kt:221-252`) | (R2) | top-bar Search icon dies here; this kills the S6 P1-11 duplication at its root instead of restyling it |
| **People** | wordmark + 4 icons | Large title "People" | **Messages** → `navigateDeepLink(NotifDeepLink.Chat(null))` | Friend groups · Invites | this is where the WP-C chat affordance *lands*; unread count moves to the People tab dot |

**Element relocation ledger** (mandate §1, `PLATFORM_ASKS.md:470-472`):

- **Chat unread** → dot on the **People** tab. Source already live: `chatRepository.totalUnread` (`ChatRepository.kt:243, 293-294`), already primed in the shell (`AppShell.kt:324-327`). Keep the priming, drop the icon.
- **Triggered alerts** → dot on the **Workbench** tab. Needs a new shell-visible source (§5.1).
- **System notifications / bell** → single inbox entry in **Home's overflow**. Delete `NotificationBell.kt` entirely; keep `notificationRepository.unreadCount` (`NotificationRepository.kt:71,133`) to (a) show a count next to the overflow item and (b) feed Home's actionable row (§3.3). The `refresh()` priming at `AppShell.kt:313-318` stays, gated on `showNotificationSurfaces` exactly as today.
- **Portfolio switcher** → the Portfolio collapsing header (§4.1).
- **Settings** → Home overflow.
- **Discreet mode** (mandate §5: "a sane home, e.g. overflow or profile, not bar chrome") → Home overflow, reading `AppGraph.discreetModeStore.enabled` (`ui/settings/SettingsScreen.kt:236-255`); the Settings row stays as the canonical control.

### 2.3 Honest reconciliation with WP-C (`4f8a5a2`)

| WP-C item | Verdict | Why |
|---|---|---|
| Top-bar chat icon + gold badge (`AppShell.kt:473, 498-516`) | **REVERTED as chrome, intent preserved** | It solved a real problem (P1-10: chat announced itself nowhere). The mandate's answer — People tab dot + first-class action on People — solves the same problem with zero bar cost. |
| Alerts segment badge inside Workboard (`WorkboardScreen.kt:189-194`) | **KEPT + promoted** | It is *content*, not chrome; the mandate asks for exactly this signal, one level up. |
| Inbox "Manage alerts" overflow entry (`NotificationsInboxScreen.kt`, `NotifDeepLink.Alerts`) | **KEPT** | Overflow is where the mandate wants it. |
| Owning-tab deep-link routing (`DeepLinkTabs.kt`) | **KEPT, extended** | Mandate §2 explicitly blesses it: "your S6 owning-tab work was right — keep it". |
| Scroll-aware FAB (`ui/components/BtFabVisibility.kt`) | **KEPT** | Mandate §1: "S6's scroll-aware FAB is good". |
| Switcher shimmer + capped prefetch (`PortfolioOverviewViewModel.kt:150-175`, `PortfolioSwitcherLogic.kt:39-52`) | **KEPT unchanged** | The sheet survives; only its *opener* moves. |
| Search keeps field shape + keyboard-on-land (`TabScreens.kt:207-252`) | **KEPT** | Its own reasoning ("a full-width input silhouette is the strongest 'you can search here' signal") is what justifies dropping the *duplicate* top-bar icon on Markets. |
| Top-bar portfolio selector chip (`AppShell.kt:525-568`) | **DELETED** | ⚠️ This was a direct owner ask (2026-07-09, `OPUS_COORDINATOR_HANDOFF.md` §9 M11 note: "move the portfolio switcher UP into the top bar beside the wordmark"). The R-arc mandate explicitly supersedes conflicting earlier polish guidance (`PLATFORM_ASKS.md:463`) and names this element specifically (line 471). **Flag this in the board tick** — the coordinator should not let it look like an oversight. |

### 2.4 Pushed screens (R2 scope — table for planning only)

39 screens host their own `TopAppBar`. Auditing the main ones against the 3-element rule: most are already compliant — `TransactionsScreen.kt:282-313` (title+subtitle, back, zero actions), `HoldingDetailScreen.kt:206-241` (same), `AssetPageScreen.kt:213-244` (title, back, one watchlist star), `CashScreen.kt:788-850` (title+portfolio subtitle, back, one overflow — with the right reasoning already in-comment at lines 819-822). The one over-budget bar is `NotificationsInboxScreen.kt:214-283` (title + Archive-all action + overflow = compliant at 3, but only just). **R1 changes none of them**; R1 only ships `BtCollapsingHeader` (§5.1) so R2 can convert them uniformly.

---

## 3. HOME SCREEN — composition from data the app already has

**No new endpoints.** Every source below is already wired in `di/AppGraph.kt`.

### 3.0 The architectural spine (non-negotiable)

**Home is an index; every row it offers is *owned* by another tab.** Therefore: **every Home navigation goes through the existing `navigateDeepLink` helper (`AppShell.kt:238-301`), never a bare `navController.navigate`.** A bare push from Home would stack a Portfolio-owned or People-owned detail on the Home tab — the exact class of bug S6 P1-8 fixed. The `HomeScreen` composable therefore takes a single `onOpen: (NotifDeepLink) -> Unit` callback plus a `onSwitchTab: (BtTab) -> Unit`, not a dozen typed lambdas. This is the single most important structural decision for Home and it should be enforced by review.

### 3.1 Hero — total value + today's change

- **Source:** `AppGraph.portfolioRepository.portfolios: Flow<List<PortfolioEntity>>` (`PortfolioRepository.kt:48` → `portfolioDao().observeAll()`). Each entity embeds `totals: PortfolioTotals?` (`PortfolioEntities.kt:34`, shape at `57-66`: `totalValueEur`, `dayChangeEur`, `dayChangePct`, `cashEur`, `marketValueEur`).
- **Scope:** sum over **active** portfolios only (`archivedAt == null` — the same predicate as `switcherSections`, `PortfolioSwitcherLogic.kt:23-27`). This makes Home ≠ Portfolio: Home is *net worth across everything*, Portfolio is *this portfolio*. See open decision O-1.
- **The honesty gate.** `totals` is `null` until that portfolio's detail was synced once (`PortfolioEntities.kt:33-36`, `detailSyncedAtMs`). A naive `sumOf { it.totals?.totalValueEur ?: 0.0 }` is a fresh €0 lie of exactly the kind W6 spent a package killing. Rule, as a pure function `homeNetWorth(active: List<PortfolioEntity>, coverage: PriceCoverage): HomeHeroState`:
  - all active have `totals` → exact sum, full hero.
  - some have `totals` → sum + explicit "across N of M portfolios" secondary line (never a bare number).
  - none have `totals` → `BtSkeleton` (reuse `OverviewSkeleton`'s idiom, `PortfolioOverviewScreen.kt:837-858`).
- **Filling the gaps:** reuse `switcherPrefetchIds(all, alreadyFailed)` + `SWITCHER_PREFETCH_CONCURRENCY = 4` (`PortfolioSwitcherLogic.kt:39-52`) and `repo.refreshPortfolioDetail(id)` (`PortfolioRepository.kt:122`) — the identical fan-out `prefetchSwitcherTotals` already runs (`PortfolioOverviewViewModel.kt:150-175`), including its offline short-circuit and its don't-retry-the-doomed set. Lift that logic into a shared helper rather than copying it.
- **W6 price honesty:** reuse `at.bettertrack.app.ui.prices.priceCoverage(holdings)` and `netWorthState(totalValueEur, cashEur, coverage)` + `NoPricesHero()` / `UnpricedNote()` exactly as the Portfolio hero does (`PortfolioOverviewScreen.kt:268-278, 342-361`). Cross-portfolio coverage = coverage over the union of active portfolios' holdings.
- **Day change:** `sum(totals.dayChangeEur)`; percentage = `sum(dayChangeEur) / (sum(totalValueEur) - sum(dayChangeEur))` computed in the pure helper — **never** an average of per-portfolio `dayChangePct`. Suppress the whole line when `coverage.nothingPriced`, mirroring `PortfolioOverviewScreen.kt:369`.
- **Discreet mode:** wrap the hero in the same press-to-peek gesture as Portfolio (`PortfolioOverviewScreen.kt:308-323`) — `BtDiscreetMode.enabled` / `setRevealing`.
- **Type:** `BtTheme.type.moneyLarge` (36sp, `ui/theme/BtTypography.kt:27-34`). Mandate §4 asks for "a bigger type ramp for hero numbers" — see open decision O-8 (add `moneyHero` at ~44-48sp vs reuse `moneyLarge`).

### 3.2 Movers

- **Source:** `repo.holdings(portfolioId)` (`PortfolioRepository.kt:50`) per active portfolio, flattened. `HoldingEntity.dayChangePct` / `dayChangeEur` (`PortfolioEntities.kt:92-93`).
- **Pure function** `homeMovers(holdings: List<HoldingEntity>, limit: Int): List<HoldingEntity>` in `ui/home/HomeLogic.kt`: filter `dayChangePct != null && marketValueEur != null`, sort by `abs(dayChangePct)` desc, take `limit` (default 5). Unit-tested — same pattern as `allocationSegments` (`PortfolioOverviewScreen.kt:939-969`) and `switcherPrefetchIds`.
- **States:** loading → 3 skeleton chips; **no holding has a non-null `dayChangePct` → the entire section is ABSENT**, not an empty card (the §4.5 "absent, not greyed" rule + W6's honest-states discipline). Error → nothing (the hero already carries the error surface; a second error block on one screen is noise).
- **Tap:** `onOpen(NotifDeepLink.Holding(assetId))` → switches to Portfolio, then pushes `HoldingDetailRoute` (per §3.0).
- **Rendering:** horizontal row of compact cards (symbol + % + small money), or a 5-row list. Craft call, but keep it under one screen-third — movers are second, not first.

### 3.3 Actionable rows

Rendered as a single "Needs you" block, each row absent when its count is 0. Ordering: alerts → invites/requests → chat → notifications.

| Row | Source | Gate | Tap |
|---|---|---|---|
| Triggered alerts | **NEW** `AlertsRepository.triggered: StateFlow<Int>` (§5.1), rule = `count { it.status == AlertStatus.Triggered }` copied from `WorkboardScreen.kt:132-134` | `storageMode.shows(ALERTS_NOTIFICATIONS)` | `WorkboardEntry.requestAlerts()` then `onSwitchTab(Workbench)` — identical to `AppShell.kt:290-293` |
| Pending mirrorchain invites (S2c-2) | **Embed `MirrorInvitesCard()` verbatim** (`ui/mirrorchain/MirrorInvitesCard.kt:195-216`) — it owns its VM, calls `MirrorchainRepository.invites()` (`MirrorchainRepository.kt:171`) and **self-hides when empty** (line 215-216) | `shows(SOCIAL)` | its own accept/decline, already built |
| Friend requests | `SocialRepository.requests()` (`SocialRepository.kt:213`) → `incoming.size`; same call `SocialScreen` already makes | `shows(SOCIAL)` | `onOpen(NotifDeepLink.Social)` |
| Unread messages | `chatRepository.totalUnread` (`ChatRepository.kt:243`) — already a StateFlow, already primed in the shell | `shows(SOCIAL)` | `onOpen(NotifDeepLink.Chat(null))` |
| Unread notifications | `notificationRepository.unreadCount` + `items` (`NotificationRepository.kt:69-71`) for a 1-line preview of the newest | `shows(ALERTS_NOTIFICATIONS)` | push `NotificationsInboxRoute` |

**Deliberately NOT on Home:** the pending-sync strip. It is status, not value, and duplicating `PendingStrip` (`PortfolioOverviewScreen.kt:509-555`) would put a sync row on the app's front door — the precise complaint in mandate §3 ("screens opening with infrastructure … before value"). It stays on Portfolio. (Exception considered in O-7.)

### 3.4 "The rest"

- **Your portfolios** — one row per active portfolio: name, value, day change; tap → `repo.selectPortfolio(id)` (`PortfolioRepository.kt:80`) then `onSwitchTab(Portfolio)`. Genuinely useful (the demo account has 2) and reuses data already loaded for the hero.
- **Recent activity** — last 3 transactions across active portfolios via `repo.transactions(pid)` (`PortfolioRepository.kt:53`). *Optional for R1* — cut it if scope bites (O-9).
- Pull-to-refresh on the whole screen, `PullToRefreshBox` with the app's gold indicator, copied from `PortfolioOverviewScreen.kt:156-169`.
- Refetch-on-focus throttled at 60 s, copying `onScreenResumed` + `FOCUS_REFRESH_MIN_INTERVAL_MS` (`PortfolioOverviewViewModel.kt:212-217, 314`).

### 3.5 Home in DRIVE-only mode

Absent by construction: alerts, chat, notifications, invites, friend requests — the whole "Needs you" block collapses to nothing. What is honest and useful instead:

1. **Hero** — same code path; totals come from the vault projector, and `netWorthState` already renders `NoPricesHero()` rather than a €0 lie (`PortfolioOverviewScreen.kt:349-350`, W6).
2. **Movers** — absent in practice. Manual price points (W6) yield a `marketValueEur` but not a previous close, so `dayChangePct` is null and §3.2's absent-rule fires automatically. No special-casing needed; verify, don't hard-code.
3. **The Drive user's actual actionable item: unpriced holdings.** When `at.bettertrack.app.ui.prices.manualEntryAvailable(gatedMode)` is true (the same call the Portfolio overview makes at `PortfolioOverviewScreen.kt:268-272`) and `coverage` shows unpriced positions, show **"N holdings need a price"** → tap opens the manual price entry. This is the single most valuable row a Drive Home can have and it costs nothing new.
4. **Vault/backup status** — "Backed up to Drive · as of X" as a *low* row (below the portfolios list), tapping through to `StorageHomeRoute`. Below the fold, so it does not violate "no sync/status rows above value" while still answering the Drive user's real anxiety.
5. **Your portfolios** — unchanged.

---

## 4. PORTFOLIO SCREEN

### 4.1 Collapsing large-title header

- **Component:** new shared `BtCollapsingHeader` (§5.1) wrapping M3 `LargeTopAppBar` + `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`. No screen in the app uses `LargeTopAppBar` today (all 39 bars are plain `TopAppBar`), so this is genuinely new vocabulary — it must be added to the debug gallery per the standing rule (`OPUS_COORDINATOR_HANDOFF.md` §5).
- **Title = portfolio name**, from the *same* nav-entry-scoped VM the screen already uses: `vm.selected` (`PortfolioOverviewViewModel.kt:58-61`). Because the header now lives **inside** `PortfolioOverviewScreen`, the whole cross-composition plumbing at `AppShell.kt:341-358` (`getBackStackEntry(PortfolioTabRoute)` → `viewModel(viewModelStoreOwner = portfolioEntry, initializer = PortfolioOverviewVmInitializer)`) **is deleted**. The shared-initializer KDoc at `PortfolioOverviewScreen.kt:83-88` must be updated: after R1 there is exactly one consumer, so `PortfolioOverviewVmInitializer` can go back to being private (or stay `internal` for the future — O-10).
- **Tap-to-switch:** title row is clickable → `vm.openSwitcher()` (`PortfolioOverviewViewModel.kt:125-128`). Gold `ExpandMore` chevron trailing the name, same glyph and tint as today's chip (`AppShell.kt:560-565`). `contentDescription` reuses `R.string.bt_switcher_open_cd` ("Switch portfolio" / "Portfolio wechseln", `strings.xml:121`) — the key survives, only its call site moves.
- **The switcher sheet is unchanged.** `PortfolioSwitcherSheet(...)` stays hosted by the screen exactly as at `PortfolioOverviewScreen.kt:224-243`, driven by `vm.switcherVisible`, with WP-C's shimmer/prefetch/`valueFailedIds` intact.
- **Collapse behavior:** expanded ≈112dp, name at `headlineSmall` (bold, tight — `BtTypography.kt:72`) + chevron; collapsed to 64dp with the name at `titleMedium`. `containerColor = bt.bg`, `scrolledContainerColor = bt.surface` (mandate §4's "tonal elevation instead of divider lines").
- **Nested-scroll composition (the one real integration risk).** The screen already hangs `fabVisibility.nestedScroll` on the `PullToRefreshBox` (`PortfolioOverviewScreen.kt:160`) and a `LazyListState` with an at-top watcher (`151-155`). The header adds a third participant. Chain as `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).nestedScroll(fabVisibility.nestedScroll)` — the app bar's connection **consumes** delta, the FAB's returns `Offset.Zero` (`BtFabVisibility.kt:57-62`) so it observes without consuming and chains safely. **But** once the app bar consumes the first N px of a downward drag, the FAB observer sees a smaller (or zero) pre-scroll delta and may not cross `FAB_SCROLL_THRESHOLD_PX = 4f` (`BtFabVisibility.kt:23`) at the very start of a fling. Mitigation: keep the FAB connection *outer* to the app bar's so it sees raw deltas first. This needs a device check that R1 cannot do (phone offline) — record it as explicit device-pass debt in `docs/TODO.md`, alongside the existing debt list at `docs/TODO.md:350`.

### 4.2 §3 hierarchy reorder

Current `OverviewContent` item order (`PortfolioOverviewScreen.kt:287-499`): hero → pending-strip → chart → rollup(Holdings | Cash) → allocation → holdings-header → holdings.

Target (mandate `PLATFORM_ASKS.md:480`: "value + allocation summary first; holdings list immediately after; cash/source metadata demoted into rows' secondary lines and expanders"):

| # | Item | Change |
|---|---|---|
| 1 | Hero: value + today's change | **Keep as-is** (`302-398`), including W6 `NoPricesHero`/`UnpricedNote` and the discreet-mode peek |
| 2 | Hero chart | **KEEP — praised. Stays directly under the hero**, at reduced height (see below) |
| 3 | **Allocation summary** | **PROMOTED** above the rollups. Rendered as a slim segmented bar + top-3 legend; the full 132dp donut card (`684-757`) moves behind "See all"/expand |
| 4 | Holdings header + list | **PROMOTED** (`455-498`); absorbs the retired "Holdings value" rollup's W6 unpriced state (`433`) into its section header |
| 5 | Cash | **DEMOTED** from a 50/50 `RollupCard` to a single secondary row (label + value + chevron → `CashRoute`) below the holdings |
| 6 | Pending-sync strip | **DEMOTED** below holdings — **except** when `attention > 0` (`PortfolioOverviewScreen.kt:511`), where it is genuinely actionable and stays at position 2 |

**The chart-vs-"immediately after" tension.** A 200dp full-bleed chart + 18dp perf line + 14dp gap + chips (`HeroChart`, `566-649`) between value and allocation pushes the holdings list past the first screen. Recommendation: (a) reduce the canvas to ~150dp, (b) fold the range chips into a single compact row, (c) make position 3 a *summary* (slim stacked bar + top-3 %), not the full donut card. With those three, hero + chart + allocation summary + the first two holdings fit one 360×800 screen. **This is craft that Fable reviews** — see open decision O-5.

**"Holdings value" rollup card is deleted outright.** It printed the sum of the list rendered 200px below it (`PortfolioOverviewScreen.kt:427-435`) — boxes-in-boxes, exactly what mandate §4 asks to reduce. Its one real job (the W6 "nothing could be priced" honesty at line 431-433) migrates to the holdings-section header.

### 4.3 FAB interaction

Unchanged: gold `+`, bottom-end, 20dp inset, scroll-aware via `BtFabVisibility` (`PortfolioOverviewScreen.kt:204-221`). The 96dp bottom `contentPadding` (`296`) stays — its comment already explains why (the FAB returns on scroll-up and the last row must clear it). **The collapsing header carries no `+`**: one creation entry per screen (mandate §1, `PLATFORM_ASKS.md:472`).

---

## 5. COMPONENT INVENTORY & CHANGE SCOPE

### 5.1 New shared pieces R1 builds

| Piece | Location | ~LOC | Notes |
|---|---|---|---|
| `BtCollapsingHeader` | `ui/components/BtCollapsingHeader.kt` (new) | ~130 | `LargeTopAppBar` + scroll behavior + brand colors + optional `onTitleClick`/chevron + one action slot + overflow slot. Add to `GalleryScreen`. R2 converts every other bar to it. |
| Tab badge dot | extend `ui/components/BtCountBadge.kt` | ~20 | ⚠️ `BtBadgeOverlay`'s KDoc already **promises** a `showDot` parameter (`BtCountBadge.kt:54-55`) that does not exist in its signature (`57-61`) — a stale dead promise. Either implement it or use the existing `BtUnreadDot` (`83-87`) inside `NavigationBarItem`'s icon slot. Fix the KDoc either way. |
| `AlertsRepository.triggered: StateFlow<Int>` + `refreshTriggered()` | `data/repo/AlertsRepository.kt` (~130 ln today) | ~25 | Today `list()` is a bare suspend (`AlertsRepository.kt:91`) and only `WorkboardScreen`'s VM consumes it (`WorkboardScreen.kt:124-134`). Both the Workbench tab dot and Home's alert row need it — one cached StateFlow, one fetch, gated on `shows(ALERTS_NOTIFICATIONS)`. |
| Home screen + VM + logic | `ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `HomeLogic.kt` (new package) | ~450 / ~200 / ~120 | Pure functions in `HomeLogic.kt` (`homeNetWorth`, `homeMovers`, `homeActionRows`) so R1 lands real unit tests without a device. |
| Home cards | inside `HomeScreen.kt` | — | Reuse `BtCard`, `MoneyText`, `BtSkeleton`, `BtEmptyState`, `BtErrorState`, `BtChip`, `BtUnreadDot` — no new card vocabulary. |
| `BtSurface.HOME` | `data/storage/StorageSurfaces.kt` | ~15 | +1 enum constant, 3 `when` branches, `visibleTabSurfaces` list. |

### 5.2 Pieces that die

| Piece | Site | LOC |
|---|---|---|
| `BtTopBarChats` (WP-C) | `AppShell.kt:490-516` + call at `473` | −27 |
| `PortfolioSelectorChip` + its VM plumbing | `AppShell.kt:518-568` + `341-358` + `364-365` | −70 |
| `NotificationBell` | `ui/notifications/NotificationBell.kt` (whole file) + call at `AppShell.kt:474` | −45 |
| Top-bar Search / Settings icon buttons | `AppShell.kt:461-481` | −20 (relocated to Home) |
| **Dead code found in the sweep** — `SocialTabScreen` (`TabScreens.kt:254-261`), `RefreshableTabScreen` (`TabScreens.kt:60-105`), `PlaceholderScreen.kt` (whole file, `31-…`) | verified zero call sites | −200 |
| String keys `bt_top_messages` (`strings.xml:46`), `bt_tab_assets/social/workboard` (`42-44`), `bt_tab_*_empty_*` (`55-62`), `bt_placeholder_message` + orphan `bt_dest_*` — EN **and** DE | see §5.4 | ~−20 keys ×2 |

### 5.3 Per-file change scope

| File | Today | R1 delta |
|---|---|---|
| `navigation/BtRoutes.kt` | 152 | +1 route, 3 renames, KDoc — **~15 lines** |
| `navigation/DeepLinkTabs.kt` | 58 | enum 4→5, `owningTab` fallback + 3 renames, KDoc rewrite — **~25 lines** |
| `ui/shell/AppShell.kt` | 985 | `Tabs` list, `TabSpec`, topBar block rewrite, `BtTopBar` rewrite, 2 composables deleted, `BtBottomBar` +badges, `startDestination`, `composable<HomeTabRoute>`, 3 route renames — **~250 lines touched, net ≈ −60** |
| `data/storage/StorageSurfaces.kt` | 190 | **~15 lines** |
| `ui/portfolio/PortfolioOverviewScreen.kt` | 1010 | header + item reorder + rollup demotion + allocation summary — **~200 lines touched** |
| `data/repo/AlertsRepository.kt` | ~130 | **~25 lines** |
| `ui/components/BtCountBadge.kt` | 88 | **~15 lines** |
| `ui/screens/TabScreens.kt` | 278 | dead-code removal — **−200** |
| `ui/components/BtCollapsingHeader.kt` | new | **+130** |
| `ui/home/*` | new | **+770** |
| `res/values/strings.xml` + `values-de` | — | **+~25 keys each, APPEND-ONLY** (see §5.4) |
| Tests: `DeepLinkTabsTest.kt` (103), `StorageSurfacesTest.kt` (149), new `HomeLogicTest.kt`, new `AlertsTriggeredTest` | — | ~**+150 net test lines** |

### 5.4 WP-B coordination — the collision rule

The in-flight WP-B builder lives in worktree `.claude/worktrees/agent-ac72f3fe102783d21` (branched off `53adb97`). Its current diff: **new** `app/src/main/java/at/bettertrack/app/data/api/BtErrorCopy.kt`, **+249** lines in `res/values/strings.xml`, **+243** in `res/values-de/strings.xml` (~100 `bt_err_*` keys plus edits to `bt_switcher_requires_connection`, `bt_switcher_value_pending`, `bt_switcher_delete_warning`). It touches **no** nav, shell, or screen file — the mid-flight assumption in the brief is confirmed.

Rules for R1:

1. **Strings are append-only in the R1 body commit.** New R1 keys go in a clearly-fenced `<!-- R-arc R1 -->` block at the *end* of both files. Never edit or delete inside the existing region — that turns a clean append-vs-append 3-way merge into a manual conflict across ~500 lines.
2. **All string *deletions* (§5.2 last row) move to an R1 tail commit** landed after WP-B merges.
3. **`BtErrorState` signature risk.** WP-B's spine (P0-4, `docs/S6_UX_DEBT.md:25`) changes `BtErrorState` to take `@StringRes`. Portfolio calls it at `PortfolioOverviewScreen.kt:865`, and Home will add 1-2 more call sites. R1 must not pre-empt or refactor that signature; if WP-B lands first, R1 rebases and adapts its (few) call sites. If R1 lands first, WP-B's sweep picks up the new call sites naturally.
4. **`StringParityTest` (`app/src/test/java/at/bettertrack/app/i18n/StringParityTest.kt:56-102`) is a hard gate**: every new EN key needs a real DE value, placeholders must match, and no DE value may be byte-identical to an EN value longer than 12 chars (line 98). Short tab labels ("Home", "Markets") are below the length threshold, so identical values would pass — but write real German anyway (§1.2).
5. Recommended sequencing: **if WP-B is < 1 day from landing, land it first**; otherwise R1 proceeds on `main` and rebases. Either way R1's builder must be told to rebase-not-merge on strings.

---

## 6. R1 WORK SPLIT

### Package R1-A — "Skeleton and bar"

**Owns:** `navigation/BtRoutes.kt`, `navigation/DeepLinkTabs.kt`, `ui/shell/AppShell.kt`, `data/storage/StorageSurfaces.kt`, `data/repo/AlertsRepository.kt`, `ui/components/BtCountBadge.kt`, `ui/screens/TabScreens.kt` (dead-code sweep), `ui/notifications/NotificationBell.kt` (delete), tests for all of the above.

**Scope:** §1 (whole), §2.1-2.3 (whole), §5.1 rows 2+3+6, §5.2 (whole). Ships a **stub `ui/home/HomeScreen.kt`** with the final signature (`onOpen: (NotifDeepLink) -> Unit`, `onSwitchTab: (BtTab) -> Unit`) rendering hero + one actionable row, so the tab is never empty and R1-B has a compile-clean seam.

**Done when:**
- `./gradlew assembleGithubDebug` clean; `./gradlew testGithubDebugUnitTest` green at **≥ 2039** tests.
- `DeepLinkTabsTest` rewritten: 5 tabs, all 12 links, account-level → `Home`, `Holding` → `Portfolio`, distinct-route + exhaustiveness guards intact.
- `StorageSurfacesTest`: SERVER/BOTH/UNSET bar = 5 in mandate order; DRIVE bar = `[HOME, PORTFOLIO, MARKET]`; `HOME` first in every mode; `HOME` is `FULL` in every mode.
- New `AlertsRepository.triggered` unit-tested (triggered-only counting; no fetch when `ALERTS_NOTIFICATIONS` is absent).
- `StringParityTest` green with the new keys.
- Grep proof: zero remaining references to `AssetsTabRoute` / `SocialTabRoute` / `WorkboardTabRoute` / `BtTab.Assets|Social|Workboard`; zero to `NotificationBell` / `BtTopBarChats` / `PortfolioSelectorChip` / `SocialTabScreen` / `RefreshableTabScreen` / `PlaceholderScreen`.
- Debug gallery updated with the tab badge dot.
- `docs/TODO.md` ticked; device-pass debt recorded.

### Package R1-B — "Home and Portfolio"

**Owns:** `ui/home/*`, `ui/portfolio/PortfolioOverviewScreen.kt` (+ VM if the shared-initializer visibility changes), `ui/components/BtCollapsingHeader.kt`, gallery entries, tests.

**Scope:** §3 (whole), §4 (whole), §5.1 rows 1+4+5.

**Done when:**
- Build + full suite green, no regressions.
- `HomeLogicTest`: `homeNetWorth` — all-totals exact sum, partial coverage never prints a bare number, zero-totals yields skeleton state, archived portfolios excluded, day-change % computed from sums not averaged; `homeMovers` — sorting by |pct|, null filtering, limit, empty-in-empty-out.
- Home renders correctly in a DRIVE-gated harness: actionable block absent, movers absent, unpriced-holdings row present.
- Every Home navigation provably routes through `onOpen`/`onSwitchTab` (grep: no `navController` reference inside `ui/home/`).
- `BtCollapsingHeader` in the gallery; Portfolio header opens the *existing* switcher sheet with WP-C's shimmer/prefetch behavior unchanged.
- `nextFabVisible` tests still green (`BtFabVisibility.kt:35-43`).
- Device-pass debt for the 3-way nested-scroll interaction explicitly recorded in `docs/TODO.md`.

**Sequencing:** A → B if one builder. If two in parallel, A must land the `HomeScreen` stub signature in its *first* commit; A owns `AppShell.kt` exclusively (B never edits it).

**Screenshots deferred** (phone offline, `docs/TODO.md:350`): both packages ship gallery entries as the device-free visual proof path, and the board tick states plainly that R1 is code-verified, not device-verified, with the screenshot round owed to Fable as soon as the phone is back. Note §6's "light+dark" ask against a dark-only app — O-12.

---

### Design decisions R1 must make that the mandate leaves open

| # | Decision | Recommendation |
|---|---|---|
| **O-1** | Home hero = **all active portfolios summed** or the selected one? | All active. Otherwise Home duplicates Portfolio's number and earns no place. Terminology: Home = "Net worth", Portfolio = "Portfolio value" — **handshake with WP-B**, which owns the S6 P1-13 glossary. |
| **O-2** | Rename `BtSurface.CONGLOMERATES` → `WORKBENCH`? | No — it mirrors the storage plan doc; document the mapping instead. |
| **O-3** | Home's ONE action: **Search icon** or **profile/avatar opening a profile sheet**? | Search for R1 (zero new plumbing); revisit at R2 when settings get their pass. |
| **O-4** | Does Markets keep *any* top-bar action, given its in-content search field? | No action, overflow only. Kills the S6 P1-11 duplication at the root. |
| **O-5** | Portfolio hierarchy: full 200dp chart + full donut card between value and holdings, or shrunk chart + slim allocation summary bar? | Shrink chart to ~150dp + slim segmented allocation bar with donut behind "See all". Fable reviews. |
| **O-6** | Re-tap on the selected tab: no-op, scroll-to-top, or pop-to-tab-root? | Scroll-to-top. Needs a small per-tab `LazyListState` hoist — decide now or defer to R2. |
| **O-7** | Does Home ever surface pending-sync / needs-attention ops? | Not in R1 (Portfolio owns it). Reconsider only for `NEEDS_ATTENTION` count > 0. |
| **O-8** | New `moneyHero` type style (~44-48sp) or reuse `moneyLarge` (36sp)? | Add `moneyHero`; mandate §4 explicitly asks for a bigger ramp, and `BtTypography.kt` is the right home. |
| **O-9** | Is "Recent activity" in R1's Home or R2's? | Cut from R1 if scope bites. |
| **O-10** | `PortfolioOverviewVmInitializer` visibility once the shell stops using it (`PortfolioOverviewScreen.kt:83-96`) | Make it private again; re-widen only if a real second consumer appears. |
| **O-11** | German tab labels: Home→"Start"?, People→"Leute" vs "Freunde", Workbench→"Werkbank" vs keeping "Workboard" | Proposed: Start · Portfolio · Werkbank · Märkte · Leute. R3 does the full copy pass, but these ship in R1 — pre-decide or delegate. |
| **O-12** | Mandate §6 asks for **light+dark** screenshots; the app is **dark-only** (no `values-night`, single `BtColors` scheme) | Ask Fable on the board: is a light theme now in scope for the R-arc, or does "light+dark" simply not apply? This materially changes R2/R3 scope. |

---

### Critical Files for Implementation

- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/shell/AppShell.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/navigation/DeepLinkTabs.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/navigation/BtRoutes.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/data/storage/StorageSurfaces.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/portfolio/PortfolioOverviewScreen.kt`

---

## COORDINATOR DECISIONS on the open questions (binding for R1 builders)

O-1 all-active sum; Home="Net worth", Portfolio="Portfolio value" (glossary handshake with WP-B). O-2 keep `CONGLOMERATES`, document mapping. O-3 Search. O-4 Markets: overflow only. O-5 shrink chart ~150dp + slim allocation bar, donut behind "See all" (Fable reviews on screenshots). O-6 scroll-to-top IF cheap, else defer to R2 (builder's call, state it). O-7 not in R1. O-8 add `moneyHero`. O-9 cut Recent-activity if scope bites. O-10 private again. O-11 DE labels: Start · Portfolio · Werkbank · Märkte · Leute. O-12 asked on the board (light theme scope).

Sequencing in force: S5-E2E lands in main first; WP-B merges next; R1-A runs in a WORKTREE with the §5.4 append-only strings rule and rebases over both at merge time (coordinator handles the merge).
