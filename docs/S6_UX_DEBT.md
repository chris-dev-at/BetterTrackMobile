# S6 UX debt list (audited 2026-08-04, read-only sweep)

Scope: every Compose screen under `app/src/main/java/at/bettertrack/app/ui/**`, EN+DE string resources, `navigation/BtRoutes.kt` + `ui/shell/AppShell.kt`, and the three v5 screenshot runs. Citations were verified against `git show HEAD:` (pre-S2c-1) — line numbers may shift slightly after the S2c-1 merge but the findings stand. Good news recorded: EN/DE resource parity is exact and the DE file has no English leakage — every DE-language defect enters through Kotlin constants or server passthrough, which makes the fix bounded.

## P0 — user-facing confusion and bugs

### P0-1. Paranoid mode is an inescapable dead-end on every pushed route
`ui/shell/AppShell.kt` (six pushed routes: holding detail, transactions, cash, cash tags, cash rules, standing orders) · `ui/paranoid/ParanoidModeScreen.kt`
The guards `return@composable` into `ParanoidModeScreen()` before the real screen's Scaffold is built; the screen has no TopAppBar of its own and the routes are not top-level, so the shell suppresses both bars — no back arrow, no tabs. The copy sends the user to the web app, but `onOpenWeb` defaults to null and all six call sites pass it bare, so the promised action never renders.
Fix: shared `ParanoidGate` composable supplying a TopAppBar-with-back on pushed routes and always wiring `onOpenWeb` to the web origin. **M**

### P0-2. Debug "Clean up test data" archives the user's live default portfolio
`ui/debug/SyncDebugScreen.kt` · `debug/SyncDebugController.kt`
Two compounding faults: (1) the screen falls back to the FIRST live portfolio when "ZZ App Test" doesn't exist, with no visible selection, and the destructive button only checks `selectedId != null`; (2) the delete filter still greps the retired `[bt:` marker (matches nothing) while `archivePortfolio` fires unconditionally with no confirmation. Reproduced in `v5-w1-2026-08-04/27-cleanup.png` → `31-restored.png`.
Fix: explicit chip tap required (no silent default), gate on the selection being named `ZZ App Test`, drop the `[bt:` filter, add type-to-confirm. **S**

### P0-3. The 1D chart's last segment is a straight diagonal that contradicts the headline number
`ui/charts/BtAreaChart.kt` path building
Consecutive points are joined regardless of time gap; on 1D the final point sits hours after the last tick, so the last third of the plot is one straight ramp climbing off the chart while the header reads +0.79% 1D. Same class on 1M as a near-vertical cliff. X-keying (epoch millis) is correct; gap handling is missing.
Fix: break the line (and fill) when Δt between adjacent points exceeds a range-dependent threshold. **M**

### P0-4. Raw server and exception strings shown to users — English on German devices
`data/api/BtApiError.kt` · `ui/components/BtStates.kt` + ~50 call sites
`BtErrorState` documents "never a raw error string"; essentially every caller pipes `error.userMessage` straight in, including raw JVM exception text (`e.message`). Complete leak enumeration (the resource files are clean): MSG_KEY_INVALID / MSG_REPLAY_WINDOW_EXPIRED / MSG_ATTEMPT_TIMED_OUT (SyncEngine), MSG_MALFORMED (ApiOpExecutor), MSG_NO_VAULT (ModeRoutingOpExecutor), four mirrorSeamMessageFor messages + "Request failed (HTTP …)" + "Empty response body." + "No connection…" + `e.message` passthrough (BtApiError), "BetterTrack rejected this entry." (CashScreen), and the open channel of server `env.error.message` (e.g. server-authored notification bodies with web-style number formatting).
Fix: error-code→string-resource map (`@StringRes` on BtApiError), `userMessage` demoted to diagnostics, `BtErrorState` signature takes `@StringRes` so the compiler enforces the contract. **L**

### P0-5. Transactions refresh fails completely silently
`ui/portfolio/TransactionsScreen.kt` refresh + loadMore · `ui/shell/AppShell.kt` OfflineBanner gating
`is BtResult.Err -> Unit` with a comment claiming "banner explains offline" — but the banner only renders on top-level routes and Transactions is pushed. Failed pull-to-refresh = spinner stops, nothing changes, no message/retry; loadMore swallows identically; WatchlistScreen quotes have the same pattern.
Fix: render the offline/stale banner on pushed routes too, or a dismissible inline "couldn't refresh — showing saved data" row. **S**

## P1 — friction

### P1-6. Portfolio switcher shows a bare em-dash for every portfolio you haven't opened — render `BtSkeleton` shimmer instead of `—`, optionally prefetch `GET /portfolios/{id}` for visible rows. `ui/portfolio/PortfolioSwitcherSheet.kt`. **S/M**
### P1-7. FAB permanently occludes the allocation legend's value column — hide/shrink FAB on scroll or inset the legend's trailing column. `ui/portfolio/PortfolioOverviewScreen.kt`. **S**
### P1-8. Deep links push onto whatever tab you're on — only `goSocial()` uses tab-switch semantics; the other nine links plain-`navigate`. Route every deep link through a switch-tab-then-push helper. `ui/shell/AppShell.kt`. **M**
### P1-9. Three feedback idioms (system Toast / Snackbar / inline text) for "we did the thing" — one app-level `BtSnackbarHost` in AppShell; inline reserved for field validation; retire `Toast.makeText`. **M**
### P1-10. Newer features undiscoverable — Alerts behind a chip inside Workboard; Chat only via a Social-tab card (no top-bar affordance/unread badge); cash tools three levels deep behind `⋮`. Surface unread-message count next to the bell; promote Alerts to a labelled destination. **M**
### P1-11. Assets tab "search field" is a button that looks like an input, duplicating top-bar search — make it a real focusable field (keyboard up on nav) or style it as a button. `ui/screens/TabScreens.kt`. **S**
### P1-12. Empty Workboard shows a full CTA button AND an identical FAB — suppress the FAB while the empty-state CTA shows; note the gold `+` FAB means three different things on three tabs. **S**
### P1-13. One object, three names (Movement/Entry/Change) across one user flow; "Net worth" vs "Portfolio value" on one screen — glossary + string sweep. **S**
### P1-14. Paranoid guards non-reactive on six routes (read `.value` at composition) vs reactive on one — folds into the P0-1 `ParanoidGate`. **S**
### P1-15. "Main / Main / Main" on the cash screen (portfolio name, source name, primary badge) — rename badge to "Primary"; give the four cash actions hierarchy (Deposit primary). **S**
### P1-16. Three unlabelled tap targets per friend row (bubble, chevron, row) — drop the chevron, add content descriptions, baseline-align trailing elements (inbox rows too). **S**
### P1-17. Retired `[bt:` marker still drives live paths — `mergeNotePreservingMarker` re-attaches legacy markers on edit (can write the dead format back to the server); delete it + its call, keep display-strip for historical rows. `ui/portfolio/TransactionFormLogic.kt` / `TransactionFormScreen.kt`. **S**

## P2 — polish

### P2-18. 26 lint errors, one root cause — 23× `NonObservableLocale` (21 identical `LocalConfiguration.current.locales[0] ?: Locale.getDefault()` lines + bare `Locale.getDefault()` in composables) + 3× `LocalContextGetResourceValueCall`. One `rememberBtLocale()` helper + hoist five `context.getString` to `stringResource`. **M**
### P2-19. Five orphaned route registrations (`LoginRoute`, `AppLockRoute`, `WatchlistRoute`, `ConglomerateListRoute`, `SettingsAccountRoute`) — three render "Under construction" if ever reached. **S**
### P2-20. Alert badge colours invert semantics (Active=gold, Triggered=green) + `Down 15 % from $63,812.77` breaks number conventions (USD prefix, space before %). **S**
### P2-21. Debug flask icon inline with real inbox actions; gold-outlined archive implies a selected state that doesn't exist. **S**
### P2-22. Raw placeholders in shipped copy — "unknown · unknown / unknown" (SyncDebugScreen) instead of `BT_EM_DASH`; "@deleted" surfaced as a tappable username in chat. **S**
### P2-23. `—` escape in EN vs literal `—` in DE for the same key — defeats grep auditing. **S**

## Batching

- **WP-A — "Never lie, never trap"** (ship alone, conflict-free): P0-1, P0-2, P0-3, P0-5, P1-14, P1-17. P0-1+P1-14 share the ParanoidGate refactor; P0-2+P1-17 share the `[bt:` retirement.
- **WP-B — "One way to say it"** (i18n/feedback/terminology; after S2c merges so the 163 new cash strings are swept once): P0-4 (spine), P1-9, P1-13, P2-18, P2-20, P2-22, P2-23.
- **WP-C — "Find it, tap it"** (discoverability/affordance/layout): P1-6, P1-7, P1-8, P1-10, P1-11, P1-12, P1-15, P1-16, P2-19, P2-21. P1-8+P1-10 both edit AppShell — sequence together.

Recommended order: **A → C → B.**
