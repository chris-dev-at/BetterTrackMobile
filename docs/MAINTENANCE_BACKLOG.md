# Maintenance backlog — read-only audit of `main` @ 0d5bca0 (2026-10-30)

Owner directive (2026-10-29): *"check the code for redundancy, dumb stuff that can be simplified, old waste; fix bugs, clean up; a cleaner/simpler code base is easier for everyone to work on."*

Scope: `app/src/main` (500 Kotlin files, 179,450 lines), `app/src/test` (307 files, 72,479 lines, 3,779 `@Test`), `tools/domain-vectors/generate.ts` (4,892 lines — the brief's 4,188 predates the ef73d282 re-pin), resources (3,325 EN = 3,325 DE strings), the Gradle build, the one CI workflow, and the docs. Lint figures come from the 2026-10-29 `lintGithubDebug` report on the `wire/drift-batch` worktree (299 warnings, 0 errors) and every resource hit was re-verified against this tree by name. Where a finding is already addressed on one of the five in-flight branches (`wire/drift-batch`, `tablet/t1-foundation`, `auth/background-refresh`, `ux/sheet-drag-thresholds`, `feat/cash-widget-refresh`) it says so.

Conventions: **Behaviour** = none / internal / user-visible. **Effort** S ≤ 1 h · M ≤ half a day · L more. Paths are relative to `app/src/main/java/at/bettertrack/app/` unless they start with `app/`, `docs/`, `tools/` or a branch name. Domain-port (`domain/**`, `tools/domain-vectors/**`) and vault (`vault/**`) findings are marked **Fable-only cleanup, conformance vectors as guard** — never a rewrite.

## Global rank

| Rank | Id | Title | Effort |
|---:|---|---|---|
| 1 | MB-02 | `completeLogin` wipes a fresh session on a 400 from `/auth/me` | S |
| 2 | MB-04 | Editing an existing SELL is checked against the holding *after* that sell | S |
| 3 | MB-01 | Chat list: an empty thread sits on top as "jetzt" with no preview | S |
| 4 | MB-03 | Transaction PATCH never sends `baseSeq` on mirror-chain rows | S |
| 5 | MB-50 | CI assembles only — 3,779 unit tests and lint never run in CI | M |
| 6 | MB-10 | 88 dead resources (84 strings/plurals + 4 colours), all verified | S |
| 7 | MB-30 | `OAuthConfig.kt` KDoc contradicts its own constants (5 places) | S |
| 8 | MB-26 | Remove the three always-on OAuth scope flags (fold into `BASE_SCOPES`) | S |
| 9 | MB-08 | Two `currencySymbol`s with different behaviour; alert formatting bypasses discreet mode | S |
| 10 | MB-27 | Remove `NotificationFlags.stubDeviceRegistration` / `archiveDeleteEnabled` (unchanged since 2026-07-11) | S |
| 11 | MB-28 | Remove `ChatFlags`, `FeedbackFlags.enabled`, `betterTrackPinLock`, `DeleteAccountFeature.armed`, `LIGHT_MODE_PUBLIC` | S |
| 12 | MB-11 | 25 Untranslatable hits: `StringParityTest` forces DE copies of `translatable="false"` keys | S |
| 13 | MB-19 | 11 copies of ISO→epoch parsing + 32 raw `Instant.parse` sites → one helper | M |
| 14 | MB-51 | No lint config: enable 12 rules as errors with a baseline for the rest | M |
| 15 | MB-32 | `BtApi.kt:1266-1269` says chain admin is session-only; 45 lines later it says the opposite | S |
| 16 | MB-33 | `OPUS_COORDINATOR_HANDOFF.md` §3/§4/§6 describe a July app (9 false claims) | S |
| 17 | MB-34 | `docs/TODO.md` boxes unticked for S5 (shipped), S6 (23/23), S3/W5, WS re-test | S |
| 18 | MB-12 | 17 declarations referenced nowhere in `app/src` (incl. `ChatFlags`, `ChatChipKinds`, `cadenceOf`) | S |
| 19 | MB-14 | Template tests + the four androidTest catalog entries that exist only for them | S |
| 20 | MB-23 | 32 copies of the MockWebServer/Retrofit/Json test harness → one rule | M |
| 21 | MB-24 | `completeLogin` / `refreshUser` share a 20-line Ok-branch | S |
| 22 | MB-21 | Shared-vs-plain DTO twins for one wire shape (4 classes) | M |
| 23 | MB-06 | Why sheet content can sit behind the Samsung taskbar: bottom insets belong to no one | M |
| 24 | MB-20 | 60 sheet pages each build their own `Scaffold`+`TopAppBar`+back button | L |
| 25 | MB-07 | `WatchlistScreen.kt:429` `if (true) {` | S |
| 26 | MB-15 | Five `TODO(step 10/11/12)` markers from July for shipped steps | S |
| 27 | MB-18 | 2,168 lines of debug-only UI compiled into release builds | M |
| 28 | MB-55 | 39 source-grep "discipline" tests pin file NAMES — every extraction below silently un-guards code | M |
| 29 | MB-40 | `AppShell.kt` (2,092): 60 sheet registrations + deep-link router + tab table → 9 files | L |
| 30 | MB-43 | `SettingsScreen.kt`: one 930-line composable → one file per settings group | M |
| 31 | MB-44 | `BtApi.kt` (2,221, 231 endpoints) → 6 interfaces composed into `BtApi` | M |
| 32 | MB-47 | `TransactionFormScreen.kt` (2,646): 1,080-line ViewModel in a screen file | L |
| 33 | MB-46 | `CashScreen.kt` (2,937): VM + 8 sheets + a second `NumberField` | L |
| 34 | MB-42 | `PortfolioOverviewScreen.kt` (1,991): hero chart + rows + tones | M |
| 35 | MB-45 | `di/AppGraph.kt` (1,154, 79 lazies) → composition root + 6 sub-graphs | M |
| 36 | MB-25 | Duplicated `buildConfigField`s and two keystore-env readers in `app/build.gradle.kts` | S |
| 37 | MB-53 | Gradle: build cache off, Java 11 on JDK 21, stale catalog comments, safe bumps | S |
| 38 | MB-22 | Two `TransactionDto → TransactionEntity` constructions | S |
| 39 | MB-09 | Four hard-coded `"€"` input suffixes | S |
| 40 | MB-16 | 25-line "RETIRED" narrative in `AppShell.kt:206-230` and siblings | S |
| 41 | MB-31 | `TokenManager.kt` cites platform line ranges that have moved | S |
| 42 | MB-41 | `BtSheetStack.kt` (648): motion state and chrome in one composable | M |
| 43 | MB-48 | `WidgetsScreen.kt` (1,909): 730 lines of hand-drawn widget mocks duplicate 11 XML previews | L |
| 44 | MB-49 | `BtWidgetConfigActivities.kt` (1,255): 10 config activities, one base | M |
| 45 | MB-56 | `CashLedgerStatsLayoutTest` guards a removal and goes vacuous after MB-10 | S |
| 46 | MB-57 | 7 `@Test` methods whose pass condition is "did not throw" | S |
| 47 | MB-52 | detekt trial: `LongMethod`/`LargeClass`/`UnusedPrivateMember` | M |
| 48 | MB-61 | `docs/S6_UX_DEBT.md`: all 23 resolved; close the file | S |
| 49 | MB-62 | `docs/TODO.md` open boxes — status table | S |
| 50 | MB-37 | Root docs: `COORDINATOR_MACHINE_MOVE.md`, `HandOffPlan.md` (0 code refs) | S |
| 51 | MB-35 | `docs/S3S4_STORAGE_PLAN.md` pins `439d0d5d` four times; port is at ef73d282 | S |
| 52 | MB-29 | Flags that STAY (and one on the widget branch nothing reads) | — |
| 53 | MB-05 | Back from the debug gallery lands on Märkte — mechanism not pinned (debug builds) | M |
| 54 | MB-13 | ~100 declarations used only in their own file → `private`/`internal` | M |
| 55 | MB-58 | Domain hand-ported tests are honest; `TOLERANCES` grew 1→4, each needs its ULP note | S |
| 56 | MB-17 | `vault/v2` frozen port: ~1,000 of 2,529 lines consumed only by its own conformance test | ✅ DONE 2026-10-30 (vault pass; ledger) |
| 57 | MB-36 | `generate.ts` (4,892): split along its own `// ===` sections | M |
| 58 | MB-59 | Test-precision baseline (0 tautologies found) — record, do not act | — |
| 59 | MB-60 | 98 `assertNotNull(` — sweep for ones not followed by a value assertion | S |
| 60 | MB-54 | `kotlin.daemon.jvmargs`/`org.gradle.jvmargs` prose is a changelog; trim | S |

---

## 1. Real bugs

### MB-02 · `completeLogin` wipes a fresh session on a 400 from `/auth/me` — **rank 1**
- **Evidence**: `data/auth/AuthRepository.kt:245-249` — `if (me.error.isAuthHardFailure) wipeAndFail(LoginError.GENERIC)`. `data/api/BtApiError.kt:40`: `val isAuthHardFailure get() = httpStatus == 400 || httpStatus == 401`, whose KDoc (L35-39) says *"A refresh/token call that fails this way means the refresh token is genuinely dead"* — the predicate was written for `POST /oauth/token`, and here it judges an identity READ. A 400 on `/auth/me` (body-parser, proxy, validation — the catalogue copy for body-parser codes was just added on `wire/drift-batch` 74db925) logs the user out seconds after a successful token exchange. Same family as the owner's P0 "I keep getting kicked out" (TODO 2026-09-02). Not changed on `auth/background-refresh` (the branch diff of `AuthRepository.kt` touches no `completeLogin` line).
- **Do**: abort only on 401 (or an explicit token-rejection code); let 400 fall into the existing "proceed with `SessionUser.unknown()`, Settings refetches later" branch (L253-262). Give `/auth/me` its own predicate (`isIdentityRejected`) instead of reusing the refresh one.
- **Behaviour**: user-visible. **Risk**: low (narrows a wipe). **Effort**: S.
- **Test**: MockWebServer, `ForcedSignOutTest` style — token exchange 200 then `/auth/me` 400 ⇒ `AuthState.LoggedIn(unknown)` and tokens still stored; `/auth/me` 401 ⇒ wiped. Prove the 400 case red first.
- **Owner value**: high — a lock-out bug on the login path.

### MB-04 · Editing an existing SELL is checked against the holding *after* that sell — **rank 2**
- **Evidence**: `ui/portfolio/TransactionFormScreen.kt:402-407` — `heldQty = held.firstOrNull { it.assetId == pick.assetId }?.quantity ?: pick.heldQuantity` feeds `uncoveredSellState(...)`; the same `heldQuantity` feeds `validation` at L392 → `TransactionFormLogic.kt:188` `oversell = … quantity > heldQuantity` and `:225-228`. In `FormMode.EditSynced` (L553-560, `origSynced = tx`) the cached holding already has the original sell applied, so re-saving a 10-share sell on a now-0 holding is flagged "uncovered by 10" and demands the "sell anyway" acknowledgment; raising it to 12 reports 12 uncovered instead of 2. `wire/drift-batch` 64deac9 stopped the PATCH from *sending* uncovered flags, so the warning is now also pointless on edit.
- **Do**: in `TransactionFormLogic.kt` add pure `heldQuantityForEdit(held: Double?, original: TransactionEntity?)` (= held + original sell qty, − original buy qty) and use it at both L392 and L402; queued edits (`FormMode.EditQueued`) are unaffected (their op never reached the holding).
- **Behaviour**: user-visible. **Risk**: low. **Effort**: S.
- **Test**: `TransactionFormLogicTest` — editing a 10-share sell on a 0 holding is not uncovered; raising it to 12 is uncovered by 2; a new sell on the same holding is still uncovered by 10 (negative space). Red first.
- **Owner value**: high — false "sell anyway" prompts on every sell edit.

### MB-01 · Chat list: an empty thread sits on top as "jetzt" with no preview — **rank 3**
- **Evidence**: `data/repo/ChatRepository.kt:214` — `val at = lastMessageAt?.let { isoToEpochMs(it) }?.takeIf { it > 0L } ?: System.currentTimeMillis()`; `:249` `list.sortedByDescending { it.lastAtMs }`; `ui/chat/ChatListScreen.kt:296` `btTimeAgoLabel(c.lastAtMs)`; `ui/format/BtRelativeTime.kt:61-66` renders anything under a minute as `bt_time_ago_now` ("jetzt"). `ChatConversationDto.lastMessage/lastMessageAt` are `null` for a freshly opened thread (`data/api/dto/ChatDtos.kt:89-98`), so every empty thread is re-stamped *now* on every refresh, sorts first, shows "jetzt" and an empty preview (`lastPreview = last?.body.orEmpty()`). `isoToEpochMs` (L175-186) also returns `0L` for an unparsable stamp, which the `takeIf` turns into "now" as well.
- **Do**: `Conversation.lastAtMs: Long?`; sort `nulls last` (the server already returns newest-active first — or keep its order); the row shows `bt_chat_no_messages_yet` instead of a time when null; make `isoToEpochMs` return `Long?`. Delete the second wall-clock read at `:610` if it only feeds this.
- **Behaviour**: user-visible. **Risk**: none. **Effort**: S.
- **Test**: `ChatConversationMappingTest` — null `lastMessageAt` ⇒ null time, sorts after a thread with a message; a valid stamp still sorts by it; an unparsable stamp is null, not now.
- **Owner value**: high — visible every time the chat list opens.

### MB-03 · Transaction PATCH never sends `baseSeq` on mirror-chain rows — **rank 4**
- **Evidence**: `data/api/dto/PortfolioDtos.kt:321-336` `UpdateTransactionRequest` has no `baseSeq`. Contract: `packages/contracts/src/portfolio.ts:703` `baseSeq: z.number().int().nonnegative().optional()`; web: `apps/web/src/user/components/TransactionDialog.tsx:945` `if (transaction.mirror) patch.baseSeq = transaction.mirror.version`. The app already stores the value (`data/db/PortfolioEntities.kt:271` `RowMirror.mirrorVersion` — KDoc: *"echoed back as `baseSeq` for optimistic concurrency"*) and the cash edit path sends it (`ui/cash/CashEditLogic.kt:59`, `CashScreen.kt:2161`). Consequence: an edit of a group-portfolio row skips the 409 `MIRROR_CONFLICT` guard the sync engine is built to park on (`PortfolioRepository.kt:290`) — a silent lost update. `wire/drift-batch` 64deac9 rewrote this request (drops uncovered flags; adds `UpdateTransactionWireTest`) and still sends no `baseSeq`.
- **Do**: add `baseSeq: Int? = null` with `@EncodeDefault(NEVER)`; set from `origSynced.mirror?.mirrorVersion` in `TransactionFormViewModel` (`TransactionFormScreen.kt:557`).
- **Behaviour**: internal (wire). **Risk**: low. **Effort**: S.
- **Test**: extend `UpdateTransactionWireTest` (drift branch): mirror row ⇒ body carries `baseSeq == mirrorVersion`; plain row ⇒ key absent.
- **Owner value**: medium-high — data integrity in group portfolios.

### MB-08 · Two `currencySymbol`s with different behaviour; alert formatting bypasses discreet mode — **rank 9**
- **Evidence**: `ui/components/MoneyText.kt:77` delegates to the canonical `btMoneySymbol` (`ui/format/BtNumberFormat.kt:47`); `ui/workboard/AlertFormLogic.kt:34` re-implements it as `Currency.getInstance(code).getSymbol(locale)` with a raw-code fallback, so the alert sheet can print a different symbol for the same code than every other screen. `formatAlertNumber` / `formatAlertPrice` (`:42`, `:60`) format money outside `btFormatMoneyCore`, which is where S2b put discreet-mode masking "by construction" (TODO 2026-08-04) — alerts are the one place amounts escape the mask.
- **Do**: delete the three functions; call `formatPrice`/`btMoneySymbol`. **Behaviour**: user-visible (alert sheet). **Effort**: S.
- **Test**: `AlertFormLogicTest`: `formatAlertPrice(x, "USD")` equals the format package for USD/EUR/GBP; discreet mode masks it.

### MB-07 · `WatchlistScreen.kt:429` `if (true) { // named watchlists are LIVE (V3-P5)` — **rank 25**
- **Evidence**: the only `if (true)` in main; the branch wraps the "new list" `IconButton`. **Do**: drop the condition and comment. **Effort**: S. **Test**: none needed (compile).

### MB-09 · Four hard-coded `"€"` input suffixes — **rank 39**
- **Evidence**: `ui/standingorders/StandingOrdersScreen.kt:1574`, `ui/cash/CashScreen.kt:2731`, `ui/conglomerate/ConglomerateDetailScreen.kt:408`, `ui/customassets/CustomAssetDetailScreen.kt:711` — `Text("€", …)` suffixes while every other label goes through `btMoneySymbol`. Standing orders are per-asset native currency since `wire/drift-batch` 1bddce1 ("native-currency trade figures"), so that one is wrong for a USD asset. **Do**: `btMoneySymbol(currency, locale)`. **Effort**: S.

### MB-06 · Why sheet content can sit behind the Samsung taskbar: bottom insets belong to no one — **rank 23**
- **Evidence**: `ui/shell/AppShell.kt:738-741` zeroes the shell Scaffold's `contentWindowInsets` with the comment *"the sheet layer consumes its own"*. The sheet layer pads only the status bar: the only inset reads in `ui/shell` are `BtSheetStack.kt:159` and `:397` (`WindowInsets.statusBars`) and `AppShell.kt:959` (`navigationBars`, for the bottom bar). The bottom inset is therefore left to each of the 91 `Scaffold(` calls in 70 UI files (Material3 default `contentWindowInsets`), and only 8 pages `consumeWindowInsets(inner)` (`DeleteAccountScreen.kt:124`, `TwoFactorScreen.kt:156`, `ConglomerateBuilderScreen.kt:253`, …); a page whose list or submit bar ignores `inner` sits behind a taskbar-sized navigation inset. The T1 branch (`tablet/t1-foundation` 95618fe) pads the rail shell's content column but not the sheet layer — which is why its ledger entry could only *observe* the taskbar overlap.
- **Do**: own the bottom inset once, in the shared page scaffold of MB-20 (or in `BtSheetStack` around the page slot) and zero it in the pages. **Behaviour**: user-visible on devices with a taskbar/3-button bar. **Effort**: M (with MB-20).
- **Test**: source-grep guard "no `Scaffold(` inside a `btSheet<>` page outside `BtSheetScaffold`".

### MB-05 · Back from the debug gallery lands on Märkte — mechanism not pinned — **rank 53**
- **Evidence**: the gallery is a sheet (`AppShell.kt:2065 btSheet<GalleryRoute>`), opened from the wordmark long-press (`:847`) or Settings → Developer (`:1837`); `onClose = back` = `sheets.dismissTop()` (`:1497`, `BtSheetHost.kt:42`). The shell's own back handler (`:504-506`) hops to page 0 when `sheetsClosed && currentPage != 0`, and `sheetsClosed` (`:374`) flips the instant the NavController pops, while the sheet's `PredictiveBackHandler(enabled = depth > 0)` (`BtSheetStack.kt:271`) still owns the exit animation. Tab order is Portfolio 0 · Markets 1 · Workbench 2 · People 3 (`:262-266`), so this is NOT the page-0 hop. Remaining candidates: the pager is keyed on `tabOrder`/`visibleTabs` (`:384-396`) and the gallery mutates theme/storage prefs; `BtTabLiveSet` lazy page init after occlusion (`BtOcclusion.kt`). `GalleryScreen.kt` itself references no pager/tab symbol.
- **Do**: needs a Compose UI test or a device trace with `pagerState.settledPage` logging; there is no JVM guard (`TopBarNavigationTest` is a source scan). **Behaviour**: debug builds only. **Effort**: M (diagnosis).

## 2. Dead code and unused resources

### MB-10 · 88 dead resources, all verified against this tree — **rank 6**
- **Evidence**: every UnusedResources hit re-checked by name across `app/src/**` (Kotlin, XML, JSON): zero `R.string.`/`@string/`/`R.color.`/`@color/` references, zero `getIdentifier(` or name-built lookups anywhere (grep: 0), Glance widgets reference strings only via `R.string`. 4 colours: `bt_surface`, `bt_border` (`res/values/colors.xml:14-15` + `values-night/colors.xml:12-13`), `black`, `white` (`colors.xml:54-55`). 84 strings/plurals in `res/values/strings.xml` (+ their DE twins; the lint report's line numbers are from the wire worktree and sit ~44 lines later than in this tree — anchors below are this tree's): 44 `bt_insight_*`/`bt_viz_*` (Insights studio leftovers, L3364 `bt_viz_cash_hide` … L3863 `bt_insight_pdf_period`), 11 `bt_widget_*` (L2864 `bt_widget_budget_spent_of` … L3053), 10 overview/allocation (`bt_overview_alloc*` L94-97, `bt_overview_manage_portfolios` + `bt_overview_alloc_see_all*` ~L2172-2176), 6 settings (`bt_settings_default_visibility*` L2637-2640, `bt_settings_visibility_*`, `bt_settings_data_export`, `bt_settings_profile_web` ~L2707-2708), 4 cash/ledger (`bt_cash_menu`, `bt_cash_movements_*`, `bt_ledger_stats_title`), `bt_insights_title`, `bt_asset_currency`, `bt_server_vault_history_loading`, `bt_top_more`, `bt_export_download_failed`. The one test that names a dead key (`CashLedgerStatsLayoutTest.kt:51`) asserts it is *absent* from the source, so deletion keeps it green (see MB-56).
- **Do**: delete from EN and DE in one commit (`StringParityTest` keeps the sets equal). **Behaviour**: none. **Effort**: S. **Guard**: lint `UnusedResources` as error (MB-51).

### MB-11 · 25 Untranslatable hits are caused by `StringParityTest` — **rank 12**
- **Evidence**: all 25 are `translatable="false"` widget-preview strings (`strings.xml:2910-3096`: "Rocket Lab USA", "1M", "€ + %", "16:42", "42 %", "Alltagskasse", …) with byte-identical rows in `values-de/strings.xml`. `app/src/test/.../i18n/StringParityTest.kt` has no `translatable` handling (grep: 0), so it demands a DE copy of every EN key — the test manufactures the lint warning.
- **Do**: parity test excludes `translatable="false"` keys and asserts they are absent from DE; delete the 25 DE rows. **Effort**: S. **Test**: the parity test itself (add a negative case: a `translatable="false"` key present in DE fails).

### MB-12 · 17 declarations referenced nowhere in `app/src` — **rank 18**
- **Evidence** (grep-based scan of 3,144 top-level non-private declarations against main+test; "own-file mentions = 1" = the declaration only): `ChatChipKinds` (`data/api/dto/ChatDtos.kt:25`), `JsonElement.nullableString` (`data/api/dto/VaultDtos.kt:68`), `cadenceOf` (`data/notifications/NotificationCatalog.kt:403`), `AppNotification.asRead` (`data/notifications/NotificationModels.kt:496`), `ChatFlags` (`data/repo/ChatRepository.kt:48`), `CASH_EXPORT_LARGE_ROWS` (`ui/cash/CashExport.kt:327`), `BtSnackbarErrorEffect` (`ui/components/BtSnackbar.kt:288`), `categoryLabelKey` (`ui/customassets/CustomAssetLogic.kt:110`), `insightDefaultPeriod` / `insightRowLimit` (`ui/insights/InsightsConfig.kt:156` / `:227`), `BT_INSIGHT_TAX_ROW_LABELS` (`ui/insights/InsightsData.kt:1322`), `earningsBarSpread` (`ui/market/EarningsChart.kt:307`), `PvCreateVaultRoute` (`ui/vault/create/PvCreateWizard.kt:61`), `PvLockVaultsRow` (`ui/vault/custody/PvLockVaultsSurface.kt:47`), `DRIVE_APPDATA_SCOPE` (`vault/drive/GoogleAuthProvider.kt:62`), `generateQrCode` / `isValidQrCode` (`vault/v2/VaultQr.kt:49` / `:94`).
- **Do**: delete the 12 non-vault ones; the 5 vault/pv ones are **Fable-only cleanup** (the two `Pv*` composables may be the un-wired half of a flagged epic — confirm against `ParanoidVaultsFlags`). **Effort**: S. **Guard**: detekt `UnusedPrivateMember` after MB-13.

### MB-13 · ~100 declarations used only inside their own file → `private`/`internal` — **rank 54**
- **Evidence**: the same scan found 362 top-level declarations with zero cross-file references; 100 have exactly one own-file use (e.g. `cashRangeToken` `ui/cash/CashLedgerScreen.kt:798`, `summaryBarFraction` `ui/cash/CashSummarySection.kt:112`, `vizAutoLimit` `ui/charts/viz/VizForm.kt:300`, `BtAccentRail` `ui/components/BtAccentRail.kt:34`, 9 ViewModels, ~50 DTO classes that only their response wrapper uses). **Do**: mechanical visibility tightening, one package at a time. **Effort**: M. **Behaviour**: none.

### MB-14 · Template tests and the androidTest stack nothing uses — **rank 19**
- **Evidence**: `app/src/test/java/at/bettertrack/app/ExampleUnitTest.kt` (`assertEquals(4, 2 + 2)`), `app/src/androidTest/java/at/bettertrack/app/ExampleInstrumentedTest.kt` (the ONLY androidTest file). Catalog entries used by nothing else: `androidx-junit`, `androidx-espresso-core`, `androidx-ui-test-junit4` (`gradle/libs.versions.toml`), plus `debugImplementation(libs.androidx.ui.test.manifest)` (`app/build.gradle.kts:368`) and the four `androidTestImplementation` lines (`:377-380`).
- **Do**: delete both files and the five dependency lines (keep `ui-test-junit4` only when MB-05 gets a Compose test). **Effort**: S.

### MB-15 · Five `TODO(step 10/11/12)` markers for steps shipped in July — **rank 26**
- **Evidence**: `ui/portfolio/TransactionFormScreen.kt:293`, `:1484`, `data/db/BtDaos.kt:74` ("TODO(step 11): search replaces this" — search-buy shipped Step 11), `data/db/MarketEntities.kt:15` (step 10), `:45` (step 12 — named lists live since V3-P5). The "held assets" picker universe (`otherHeldAssets`, `HoldingDao.observeAll()`) is still a second picker path beside search.
- **Do**: decide: keep the held-assets picker as the offline fallback (then reword the comments as a rule) or delete `otherHeldAssets` + `observeAll()` + `HeldAssetSheet`'s fallback branch. **Effort**: S.

### MB-16 · "RETIRED" narratives in code — **rank 40**
- **Evidence**: `ui/shell/AppShell.kt:206-230` (25 lines on a Portfolio-tab dot removed 2026-08-09); similar "HISTORY:" blocks in `OAuthConfig.kt:22-27`, `:43-50`, `:157-170`, `BtApi.kt:1313-1322`. **Do**: one line + a TODO.md ledger date each. **Effort**: S.

### MB-17 · `vault/v2` frozen port — **Fable-only cleanup, conformance vectors as guard** — **rank 56** — ✅ DONE 2026-10-30 (coordinator ruled "follow the platform"; the three consumed files moved to `vault/VaultHkdf.kt`, `vault/bip39/`, and `PvDocBuckets` became the literal `VAULT_ENTITY_DOC_BUCKETS` mirror — see the ledger row and `tools/domain-vectors/PINNED_AT`)
- **Evidence**: 2,529 lines / 11 files kept green against retired upstream bytes (TODO 2026-10-29 "PENDING COORDINATOR DECISION"). By file-name reference: `VaultV2Contract` (10 refs), `VaultHkdf` (3), `VaultBlobCrypto` (2), `VaultQr` (2), `VaultHeaderMac` (1) are consumed by `vault/pv/**` and `ui/vault/**`; `VaultHeaderCrypto` (313 lines), `VaultMigrationCrypto` (181), `VaultRecoveryKitV2` (132), `VaultUpgrade` (244), `VaultWords` (168) are consumed only by `VaultV2ConformanceTest` (687 lines). **Do**: nothing until the coordinator rules; if "follow the platform", ~1,040 lines + the test + `v2.fixture.json` go, the five consumed files stay (rename out of `v2`).

### MB-18 · 2,168 lines of debug-only UI compiled into release — **rank 27**
- **Evidence**: `ui/gallery/` (1,116 lines), `ui/debug/` (843), `debug/` (209) live in `src/main`; `AppShell.kt:2065-2078` registers `btSheet<GalleryRoute>` and `btSheet<SyncDebugRoute>` unconditionally, so they are reachable code R8 keeps; only the *entry points* are `BuildConfig.DEBUG`-gated (`:847`, 19 `BuildConfig.DEBUG` sites in main). `src/debug/` today holds only a manifest overlay + `network_security_config.xml`.
- **Do**: move the three packages to `src/debug/java`, register their routes through a `debugSheets(builder)` hook with a no-op in `src/release/java`. **Behaviour**: none for users (release gains nothing reachable, loses dead weight). **Effort**: M. **Test**: `BtWidgetManifestTest`-style assertion that the release merged source set has no `ui.gallery` package.

## 3. Duplication

### MB-19 · 11 copies of ISO→epoch parsing + 32 raw parse sites — **rank 13**
- **Evidence**: `data/repo/ChatRepository.kt:175 isoToEpochMs`, `data/repo/MarketRepository.kt:283 parseIsoToMs`, `data/repo/PortfolioRepository.kt:547 parseIsoMs`, `data/repo/PortfolioHistory.kt:163 parseInstantMillis`, `data/notifications/NotificationRepository.kt:545 parseIso`, `data/notifications/ChannelSetupRepository.kt:513 parseIsoMillis`, `data/account/AccountModels.kt:107 parseIsoMs`, `ui/portfolio/TransactionFormLogic.kt:552 parseInstantMsOrZero`, `ui/portfolio/TransactionFormScreen.kt:1266 epochMsToLocalDate`, `ui/standingorders/StandingOrdersScreen.kt:1674 parseIsoOrToday`, `vault/VaultMerge.kt:346 parseInstant` (vault one stays — Fable-only); plus 32 `Instant.parse`/`OffsetDateTime.parse` call sites in 22 files. They disagree on the failure value (`0L`, `null`, today) — which is exactly what MB-01 tripped over.
- **Do**: one `BtIso.parseMs(String?): Long?` (+ `parseLocalDateOrNull`) in `data/i18n` (Android-free), table-tested; delete the 10 non-vault copies. **Effort**: M. **Test**: one table test (Z, ±offset, millis, blank, garbage) replacing the per-file ad-hoc cases.

### MB-20 · 60 sheet pages each build their own `Scaffold` + `TopAppBar` + back button — **rank 24**
- **Evidence**: 33 `TopAppBar(` in 33 files, 127 `ArrowBack`/`BtIcons.Back` sites, 37 `TopAppBarDefaults.` colour blocks, 91 `Scaffold(` in 70 files, 48 `LazyColumn(` in 38 files; the 15-line title/navigationIcon/colors blocks in `ui/cash/CashTagsScreen.kt` and `ui/standingorders/StandingOrdersScreen.kt` are the same text with a different string id. `BtSectionHeader` (60 uses) shows the components package already works as the shared vocabulary; the page frame never got one (0 hits for a `BtSheetHeader`/`BtPageHeader`).
- **Do**: `BtSheetScaffold(title, subtitle = null, onBack, actions = {}, bottomBar = {}, content)` in `ui/components`, owning colours, back icon, content description and — with MB-06 — the bottom inset. Migrate sheet by sheet; every migration is a ~15-line deletion. **Behaviour**: none (pixel-identical by construction). **Effort**: L overall, S per page. **Guard**: source scan "no `TopAppBar(` outside `ui/components`".

### MB-21 · Shared-vs-plain DTO twins for one wire shape — **rank 22**
- **Evidence**: `SharedHoldingDto` (`data/api/dto/SocialDtos.kt:306`) has the same 11 fields as `HoldingDto` (`PortfolioDtos.kt:210`), differing only in `asset: SharedAssetDto` vs `AssetDto`; `SharedPortfolioTotalsDto` (`:294`) = `PortfolioTotalsDto` (`:225`), 8 identical fields; `SharedHistoryPointDto` (`:340`) ⊂ `HistoryPointDto` (`:178`, lacks `time`); `SharedAssetDto` (`:320`) = `AssetDto` with `id: String?`. The scan also lists 20 `Shared*Dto`/`MyShared*Dto` classes referenced only by their own file.
- **Do**: decode the shared routes with the portfolio DTOs (confirm against `packages/contracts` whether `asset.id` is really optional on the shared route; if so make `AssetDto.id` nullable there or keep one small `SharedAssetDto`); delete 3-4 classes and their `toDomain` twins. **Effort**: M. **Test**: existing `SharedDetail*`/`SocialRepository` MockWebServer tests + one shared-holdings fixture decoded through `HoldingDto`.

### MB-22 · Two `TransactionDto → TransactionEntity` constructions — **rank 38**
- **Evidence**: `data/storage/ServerPortfolioBackend.kt:712 TransactionDto.toEntity(portfolioId)` and an inline `TransactionEntity(` at `data/storage/VaultProjection.kt:262`. **Do**: one mapper in `data/db` (the vault side is a projection of vault rows, so check the field sources first — if they differ, keep both but name the difference). **Effort**: S.

### MB-23 · 32 copies of the MockWebServer/Retrofit/Json test harness — **rank 20**
- **Evidence**: 32 `MockWebServer()` constructions in 33 files, 28 `Retrofit.Builder()`, 45 `OkHttpClient` builders, 70 `Json { ignoreUnknownKeys = true … }` blocks; the `@Before` blocks of `ui/social/AudienceRecipientRefusalTest.kt:54-75`, `data/repo/FeedbackMineTest.kt:46-61`, `data/standingorders/StandingOrderRepositoryTest.kt:35-50` are the same 12 lines. Only 11 `Fake*` classes exist (4 of them vault fixtures); the two files with helper functions (`DeadServerBoundaryTest`, `ParanoidModeTest`) are not shared.
- **Do**: `BtApiServerRule` (JUnit `TestRule`) exposing `server`, `api`, `json`, `enqueueJson(status, body)`, `takeJson()`; migrate tests opportunistically. **Effort**: M. **Behaviour**: none.

### MB-24 · `completeLogin` / `refreshUser` share a 20-line Ok-branch — **rank 21**
- **Evidence**: `data/auth/AuthRepository.kt:218-243` vs `:263-290` — identical admin/disabled checks, `saveUser`, `applyPrivacyMode`, `onSessionEstablished`, `_authState` assignment. **Do**: `adoptSessionUser(user)` used by both (bundle with MB-02). **Effort**: S. **Test**: existing `ForcedSignOutTest`/login tests.

### MB-25 · Duplicated `buildConfigField`s and two keystore-env readers — **rank 36**
- **Evidence**: `app/build.gradle.kts:189-192` and `:210-213` repeat `API_ORIGIN`/`WEB_ORIGIN`/`OAUTH_CLIENT_ID`/`OAUTH_REDIRECT_URI` for debug and release (release hard-codes the values the default already resolves to); `:90-110` (`btSigning`) and `:127-140` (`playUpload*`) both parse a `KEY=VALUE` env file with the same `readLines().mapNotNull { indexOf('=') … }` block (`:101`, `:131`). **Do**: lift the four fields to `defaultConfig`; one `readEnvFile(File): Map<String,String>`. **Effort**: S.

## 4. Flags — keep / remove

| Flag | Value · since (commit) | Verdict | What removing deletes |
|---|---|---|---|
| `ParanoidVaultsFlags.enabled` (`vault/pv/ParanoidVaultsFlags.kt:27`) | `false` | **STAY** — program gate, 32 files / 46 refs; dead only when the arc ships | — |
| `FreshStartNoticeFlags.enabled` (`data/auth/FreshStartNotice.kt:56`) | `false` | **STAY** — still platform-blocked: `apps/api/src/http/middleware/bearerAuth.ts:794-801` admits only `POST /auth/first-run/complete` and `POST /auth/google/link/start`; `authRoutes.ts:318` fresh-start route is session `requireAuth` | — |
| `OAuthConfig.ALERTS_SCOPES_ENABLED` | `true` · 2026-07-11 (067b53c) | **REMOVE** (MB-26) | 3 consts, 2 private scope consts, 3 `requestedScopes` params, ~90 KDoc lines, flag branches in `OAuthScopeTest` |
| `OAuthConfig.FEEDBACK_SCOPE_ENABLED` | `true` · 2026-08-19 (a1ab875) | **REMOVE** (MB-26) | ↑ |
| `OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED` | `true` · 2026-08-20 (54cc79d) | **REMOVE** (MB-26) | ↑ |
| `NotificationFlags.stubDeviceRegistration` | `false` · 2026-07-11 (1d5e280) | **REMOVE** (MB-27) | `PushTokenManager.kt:102,117` stub branches + `registrationAction(stubbed=)` param + its tripwire test |
| `NotificationFlags.archiveDeleteEnabled` | `true` · 2026-07-11 (452f2e4) | **REMOVE** (MB-27) | `NotificationsInboxScreen.kt:150` flat-inbox branch, `NotificationRepository.kt:191` `view=` null branch |
| `ChatFlags.enabled` (`data/repo/ChatRepository.kt:48`) | `true` · 2026-07-09 (75dc224) | **REMOVE** (MB-28) — nothing reads it (0 refs) | 4 lines |
| `FeedbackFlags.enabled` (`data/repo/FeedbackRepository.kt:76`) | `true` · 2026-08-19 | **REMOVE** (MB-28) | `feedbackEntryVisible` → `mode.shows(ACCOUNT_SETTINGS)` (`:95`); the "two flags must move together" KDoc |
| `AppLockFeatures.betterTrackPinLock` (`data/applock/AppLockModels.kt:158`) | `true` · 2026-07-08 (b9d9cb3) | **REMOVE** (MB-28) | `AppLockSetupScreen.kt:104-110` two branches + 1 test branch |
| `DeleteAccountFeature.armed` (`data/account/DeleteAccountFeature.kt:21`) | `true` · 2026-07-14 (75975f1) | **REMOVE** (MB-28) — Play requires the path armed; the real guard is username + password + confirm | `AccountRepository.kt:294-298` `DELETE_DISABLED`, `DeleteAccountScreen.kt:91,170,179` disarmed UI, 1 test |
| `BtThemeFeatures.LIGHT_MODE_PUBLIC` (`ui/theme/BetterTrackTheme.kt:57`) | `true` · 2026-08-07 (37909c4) | **REMOVE** (MB-28) — a `const` is not a kill switch anyone can pull at runtime | 2 default params (`resolveDarkTheme`, `BetterTrackTheme`) + the `lightAllowed=false` test branch |
| `BuildConfig.SERVER_SETTING_ENABLED` | github `true` / play `false` | **KEEP** — flavour-differentiated | — |
| `BuildConfig.SELF_UPDATE_ENABLED` | github `true` / play `false` | **KEEP** — flavour-differentiated (Play policy) | — |
| `BuildConfig.DIRECT_PROVIDER_PRICES` | `false` (owner decision) | **KEEP** — `DirectProviderMarketDataSource` is scaffolding by design (`app/build.gradle.kts:152-161`) | — |
| `BtWidgetServerSignals.ENABLED` (`feat/cash-widget-refresh` 04a6ad6, `widget/BtWidgetCashRefresh.kt:185`) | `true` | **WIRE OR DROP before merge** — on the branch nothing reads `BtWidgetServerSignals.ENABLED` (grep: 0); `parsePush`/`onPush` run regardless | the const |
| `PV_E3_PINNED` (`vault/pv/keys/PvVaultKeyDerivation.kt:101`) | `true` | **Fable-only** — main never reads it (0 refs); 3 tests use it as a skip guard; the "val not const" KDoc argument is about compiler warnings in guarded branches, and there are none | move into the test as a constant |

### MB-26 · Remove the three OAuth scope flags — **rank 8** — ✅ DONE 2026-10-30 (8ac7cec)
- **Evidence**: all three `true` for 2–3 months (table). The repo already set the precedent: the V5 scope gate was removed on 2026-08-12 and the scopes folded into the constant (`data/auth/OAuthConfig.kt:164-170`: *"the same lever … applies: drop the offending scopes from this constant, rebuild, re-login"*). Keeping three toggles plus `requestedScopes(alertsScopesEnabled, feedbackScopeEnabled, feedbackReadScopeEnabled)` for scopes that are part of the client ceiling is the inconsistency.
- **Do**: fold `ALERTS_SCOPES`, `FEEDBACK_SCOPE`, `FEEDBACK_READ_SCOPE` into `BASE_SCOPES`; `requestedScopes()` stays a pure function for `OAuthScopeTest`, which pins the exact 21-scope string. **Behaviour**: none (same wire string). **Effort**: S. **Test**: `OAuthScopeTest` — the string equals the 21 scopes, each exactly once.

### MB-27 · Remove the two notification kill-switches — **rank 10** — ✅ DONE 2026-10-30 (a200475)
- **Evidence**: `data/notifications/NotificationRepository.kt:22-50`; both unchanged since 2026-07-11 (3.5 months). **Do**: delete + the `stubbed` parameter path in `PushTokenManager`; keep `registrationAction()` pure. **Effort**: S. **Test**: `NotificationLogicTest`/`PushRegistrationLogicTest` lose the flag matrix rows.

### MB-28 · Remove the five shipped-feature flags — **rank 11** — ✅ DONE 2026-10-30 (85b08be)
- **Evidence/Do**: table rows for `ChatFlags`, `FeedbackFlags.enabled`, `betterTrackPinLock`, `DeleteAccountFeature.armed`, `LIGHT_MODE_PUBLIC`. **Effort**: S each. **Behaviour**: none.

### MB-29 · Flags that stay — **rank 52** — ✅ RECORDED 2026-10-30
- `ParanoidVaultsFlags.enabled`, `FreshStartNoticeFlags.enabled` (verified blocked on the platform today), the three `BuildConfig` switches. Record the `FreshStartNotice` check so the next audit does not redo it: the allowlist is `bearerAuth.ts:785-805`.
- **Verdict, re-checked 2026-10-30 during the MB-26/27/28 removal — do not redo:**
  - `ParanoidVaultsFlags.enabled` (`false`) **STAYS** — the paranoid-vaults program gate (32 files / 46 refs); it goes dead only when that arc ships, and `vault/` is owned by its own builder.
  - `FreshStartNoticeFlags.enabled` (`false`) **STAYS** — still platform-blocked: the bearer allowlist (`bearerAuth.ts`, `oauthGrantRouteAcceptsBearer`) admits only `POST /auth/first-run/complete` and `POST /auth/google/link/start`; the fresh-start acknowledgement route is session `requireAuth` with no bearer carve-out. The carve-out is platform **#2068, in the next train**; flip (and then remove) the flag once that ships and is verified with a bearer.
  - `BuildConfig.SERVER_SETTING_ENABLED` / `SELF_UPDATE_ENABLED` **STAY** — flavour-differentiated (github `true` / play `false`; self-update is Play policy). `BuildConfig.DIRECT_PROVIDER_PRICES` (`false`) **STAYS** — the owner's decision, tripwired by `DirectProviderFlagTest`.
  - Not flags in this sense and out of scope here: `BtWidgetServerSignals.ENABLED` (branch-only, see the table), `PV_E3_PINNED` (Fable-only, `vault/`).
  - The removed set is pinned by `RemovedFlagsDisciplineTest` (source scan of `app/src/main` + `app/src/test`, code and comments); the names above are deliberately not in it.

## 5. Docs and KDocs that lie

### MB-30 · `data/auth/OAuthConfig.kt` contradicts its own constants — **rank 7**
- L37 *"exactly the 20 scopes this file requests"* → 21. L105-108 *"feedback:read … is deliberately NOT requested yet — see [FEEDBACK_READ_SCOPE_ENABLED], which is the one-line flip once #1393 lands"* → requested since 2026-08-20 (`L79 = true`). L153-155 *"14 + 5 = 19 — the full ceiling"* → 21. L188-193 *"[FEEDBACK_READ_SCOPE_ENABLED], which is `false` today … #1393 (in final review)"* → `true`. L223-227 *"NOT requested today … an accidental append breaks sign-in"* → it is appended on every login. **True statement**: the app requests 21 scopes, all three toggles are on, and the client ceiling is 21. (Moot after MB-26.) **Effort**: S.

### MB-31 · `data/auth/TokenManager.kt` cites platform line ranges that moved — **rank 41**
- L452 `bearerAuth.ts:371-413` → that range is now `oauthGrantRouteAcceptsBearer`; `bearerAuth.ts` contains no refresh logic at all (grep "refresh": 0). The refresh rotation lives in `apps/api/src/services/oauth/oauthService.ts:917-932` (L481 cites it correctly) and `apps/api/src/data/repositories/oauthRepository.ts:646-700` (`rotateRefreshToken`; L482's range is right, its path is missing). L375 `packages/contracts/src/oauth.ts:29-34` still matches. **Do**: cite function names, not lines. **Effort**: S.

### MB-32 · `data/api/BtApi.kt:1266-1269` vs `:1313-1322` — **rank 15**
- L1266-1269: *"ONLY these seven routes accept a bearer — chain administration (create, rename, invite, revoke, roles, transfer, kick, dissolve) is session-only … The app therefore never renders those actions at all."* L1313-1322: *"Chain ADMINISTRATION — bearer-reachable since board #67 … That is no longer true."* `ui/mirrorchain/ChainManageScreen.kt` (1,310 lines) renders them. **Do**: delete the first paragraph. **Effort**: S.

### MB-33 · `OPUS_COORDINATOR_HANDOFF.md` §3/§4/§6/§9 describe the July app — **rank 16**
- §4 *"Request scopes EXACTLY: portfolio:read … social:read (more don't exist yet)"* → 21 scopes. §6 *"no `/auth/me` for bearer ⇒ app can't show username"* → `@GET("auth/me")` is called in `completeLogin`. §6 *"logout is local-wipe-only"* → true on main; `wire/drift-batch` 07501eb adds bearer `POST /auth/logout`. §6 gaps 1 (no idempotency key → 15 `Idempotency-Key` sites in `BtApi.kt`), 2 (no cash sources/transfer → `cash/transfer` exists), 3 (no custom-asset list → `@GET("custom-assets")`), 4 (single unnamed watchlist → named lists live, 4 routes), 5 (history `1M|6M|1Y|MAX` only → `HistoryRange` has 1D/1W since V5), 7 (no hard DELETE → `@DELETE("portfolios/{portfolioId}")` L827). §3 *"39/39 unit tests … DB at v2"* → 3,779 tests, Room v15. §1 commit identity `chrisiclemi@gmail.com` → retired 2026-07-30 (`chris.dev.at@gmail.com`). §9's milestone plan is a historical artefact. **Do**: replace §3/§6/§9 with a dated pointer to `docs/TODO.md` and `PLATFORM_ASKS.md`; fix §1 and §4. **Effort**: S.

### MB-34 · `docs/TODO.md` boxes unticked for shipped work — **rank 17**
- L320 `[ ] S5 (P2) — paranoid-with-server` → shipped: L347 *"S5 (`7bb5c8e`): ServerVaultDataHome over the live vault:sync surface — wire-verified"*, `vault/server/` 1,566 lines, `AppGraph.kt:860` section. L321 `[ ] S6 (P2)` → L358 *"the S6 audit is 23/23 DONE"*. L318 `[~] S3` → W5 wizard DONE (L344). L319 `[~] S4` → W4 core merged, W6 market data is scaffolding behind the owner's `DIRECT_PROVIDER_PRICES` decision — annotate, don't tick. L193 `[ ] WS realtime re-test — PLATFORM-BLOCKED` → L214 *"WS LIVE"*. **Do**: tick/annotate. **Effort**: S.

### MB-35 · `docs/S3S4_STORAGE_PLAN.md` pins `439d0d5d` (L237, L367, L402) — **rank 51**
- The port is at `ef73d282` (`tools/domain-vectors/PINNED_AT`), generator mode is `--experimental-transform-types` (README L55-60, correct). **Do**: one "superseded by PINNED_AT" note at the top of §3. **Effort**: S.

### MB-36 · `tools/domain-vectors/generate.ts` (4,892 lines) — **Fable-only, byte-identical vectors as guard** — **rank 57**
- The brief's 4,188 is stale (the ef73d282 re-pin added `computeTwrStats`, Modified Dietz, the tax row engine). Seam: split along its own `// ===` sections — `holdings` (L365-1299), `seriesStats` (L1300-2020), `cashLedger` (L2021-3187), `settingsScope` (L3188-3235), `serverTwrParity` (L3236-3377), `tax` (L3378-4757), `emit.ts` (L200-250: `skip`/`emit`/`emitAsync`), with `main()` (L4777-4814) importing the 35 `gen*` functions. **Guard**: `npm run generate` then `git diff --exit-code app/src/test/resources/domain-vectors` — the output must be byte-identical. **Effort**: M.

### MB-37 · Root docs — **rank 50**
- `COORDINATOR_MACHINE_MOVE.md` (a paste-in bootstrap prompt for an Opus coordinator), `HandOffPlan.md` (409 lines, 0 references from code; the live spec is TODO.md + PLATFORM_ASKS.md). **Do**: move to `docs/history/` or delete after MB-33. `PLATFORM_ASKS.md` (2,719 lines, last entry 2026-10-29) is the live board — keep.

## 6. Oversized files — concrete seams

### MB-40 · `ui/shell/AppShell.kt` (2,092) — **rank 29**
- **Shape**: 4 composables: `BtApp()` L359-990 (630 lines), `BtTabContent` L990-1256, `BtBottomBar` L1256-1470, `BtSheetHost` L1470-2085 (615 lines holding 60 `btSheet<…>` registrations). **Extraction**: (a) `ui/shell/sheets/{Portfolio,Cash,Market,Social,Settings,Vault,Debug}Sheets.kt`, each `fun BtSheetGraphBuilder.xSheets(nav, back, deps)` — 60 routes → 7 files of ~90 lines, called from `BtSheetHost`; (b) the `navigateDeepLink` lambda (L510-600) + `owningTab` → `ui/shell/BtDeepLinkRouter.kt` (pure, testable without Compose); (c) `Tabs`/`TabSpec`/`TabBadge`/`tabsFor` (L206-300, L384) → `ui/shell/BtTabs.kt`. **Behaviour**: none. **Effort**: L. **Guard**: `TopBarNavigationTest`, `BtOcclusionTest`, `SheetStateScrollGuardTest` scan `"AppShell.kt"` BY NAME (MB-55) — switch them to the `ui/shell` directory first. Four in-flight branches touch `AppShell.kt` (`tablet/t1-foundation` adds the rail; `ux/sheet-drag-thresholds`; `auth/background-refresh` adds `AuthReconnectBanner`; `wire/drift-batch`) — land them first.

### MB-41 · `ui/shell/BtSheetStack.kt` (648) — **rank 42**
- `BtSheetStack` composable L122-459 (337 lines) mixes travel/slide animation state, predictive back, drag and planes. Seam: `BtSheetMotionState` (travel/slide/leaving/`backOne`/`closeAll`/`settle`, L200-260 — the KDoc already names `[BtSheetMotion]`) → `BtSheetMotion.kt`; `BtSheetPlanes`/`BtSheetGrabber`/`BtSheetHintChip` (L459-604) → `BtSheetChrome.kt`. `ux/sheet-drag-thresholds` already extracted the gesture math into `BtSheetGestures.kt` with 3 tests — follow that cut. **Effort**: M.

### MB-42 · `ui/portfolio/PortfolioOverviewScreen.kt` (1,991) — **rank 34**
- `OverviewContent` L490-978 (490 lines); `HeroChart`/`ChartControls`/`ChartModePicker`/`ChartModeLabel` L1064-1489 (425 lines) → `PortfolioHeroChart.kt`; `HoldingRow`/`SecondaryRow`/`QuickStatChip` → `PortfolioOverviewRows.kt`; `deltaColor`/`deltaTint`/`rangeAccent`/`rangeRail` (L1863-1960, already `internal`) → `ui/portfolio/DeltaTone.kt`. The ViewModel is already separate (424 lines). `wire/drift-batch` 64d737f edits this file (drops the TWR line) — land first. **Effort**: M.

### MB-43 · `ui/settings/SettingsScreen.kt` (1,467) — **rank 30**
- The `SettingsScreen` composable is L197-1130: ~930 lines in ONE function (10 composables in the file). Seam: one file per settings group matching the skeleton `SettingsTaxonomyTest` pins (Account, Security, Notifications, Appearance, Data, Developer, About) → `ui/settings/groups/*.kt` each `fun LazyListScope.xGroup(state, actions)`; `ProfileIconSheet`/`ProfileIconCell` (L1178-1306) → `ProfileIconSheet.kt`. **Guard**: `SettingsTaxonomyTest.kt:35-37` reads `SettingsScreen.kt` by path — point it at the groups directory. `tablet/t1-foundation` and `auth/background-refresh` both edit this file. **Effort**: M.

### MB-44 · `data/api/BtApi.kt` (2,221 lines, 231 endpoints) — **rank 31**
- 30 `// ──` sections already draw the cut (L409 portfolio reads, 473 custom assets, 489/531 market, 600 watchlists, 638 alerts, 684 conglomerates, 717 queue mutations, 796/852/897/1007 portfolio management/cash/classification/standing orders, 1057-1266 social, 1266-1394 mirrorchain, 1394 ideas, 1436-1576 notifications, 1576 chat, 1616-1786 account/security, 1786 taxes, 1872 export, 1955 feedback, 190/2003 vaults). Retrofit composes interfaces: `interface BtApi : BtPortfolioApi, BtMarketApi, BtSocialApi, BtNotificationsApi, BtAccountApi, BtVaultApi` keeps every call site and all 33 MockWebServer tests on `BtApi`. **Effort**: M. **Test**: `BtApiRouteInventoryTest` — the count of annotated methods stays 231 and no path string is duplicated.

### MB-45 · `di/AppGraph.kt` (1,154; 79 `by lazy`, 74 members) — **rank 35**
- 9 `// ──` sections (L151 token client, 179 API client, 270 DB + sync, 461 S2c-2 surfaces, 545 notifications, 609 app lock, 772 Drive medium, 860 server medium, 998 wizard/mode). Seam: `AppGraph` stays the composition root and exposes `net: NetworkGraph`, `data: DataGraph`, `notifications: NotificationsGraph`, `appLock: AppLockGraph`, `vault: VaultGraph`, `storage: StorageGraph` — plain classes with the same lazies, so call sites change from `AppGraph.x` to `AppGraph.data.x` (mechanical). `feat/cash-widget-refresh` and `auth/background-refresh` both edit this file — land them first. **Effort**: M.

### MB-46 · `ui/cash/CashScreen.kt` (2,937) — **rank 33**
- `CashViewModel` L277-856 (580 lines) → `CashViewModel.kt`; the sheets `CashSourceSwitcherSheet` 1533, `CashBudgetSheet` 1666, `CashMovementTagsSheet` 1829, `CashCorrectionSheet` 2128, `CashEntrySheet` 2265, `TransferSheet` 2555, `SourceDialog` 2772 → `ui/cash/sheets/*.kt`; `SheetNumberField` (2715) + `sheetFieldColors` (2831) duplicate `FormNumberField` (`TransactionFormScreen.kt:1858`) + `btFieldColors` (`:2633`) → one `BtNumberField` in `ui/components`. **Effort**: L.

### MB-47 · `ui/portfolio/TransactionFormScreen.kt` (2,646) — **rank 32**
- `TransactionFormViewModel` L213-1295 (1,080 lines) → `TransactionFormViewModel.kt` (bundle with MB-03/MB-04 — `heldQuantityForEdit` and `baseSeq` live there); `HeldAssetSheet` + rows (L2385-2611) → `HeldAssetSheet.kt`; `CashCouplingCard`/`UncoveredSellCard`/`RejectionCard`/`RequiresConnectionCard`/`SubmitBar` (L2021-2385) → `TransactionFormCards.kt`. **Effort**: L. `wire/drift-batch` edits this file (1bddce1, 64deac9) — land first.

### MB-48 · `ui/settings/WidgetsScreen.kt` (1,909) — **rank 43**
- 11 `*Mock` composables (L1085-1810, ~730 lines of hand-drawn widget previews: `PulseMock`, `PerformanceMock`, `AssetMock`, `RowsMock`, `BudgetMock`, `AllocationMock`, `AllocationFormMock`, `FlowMock`, `CashWalletMock`, …) duplicate what `res/layout/bt_widget_preview_*.xml` (11 layouts; source of the 14 `SmallSp` + 5 `DisableBaselineAlignment` + 3 `UseCompoundDrawables` lint hits) already draw for the launcher picker. Pick one preview source: render the XML previews via `AndroidView` in Settings, or drop the XML in favour of Glance previews (`previewLayout` is API 31+ anyway — the source of 10 of the 38 `UnusedAttribute` hits). **Effort**: L. **Behaviour**: none if the chosen source is pixel-checked once.

### MB-49 · `widget/BtWidgetConfigActivities.kt` (1,255) — **rank 44**
- 10 `*WidgetConfigActivity` classes (L488-1138) sharing `BtConfigDraft` (L477) and the same load/draft/save/`redraw` shape. Seam: `abstract class BtWidgetConfigActivity<D>` (draft load, save, `redraw`, result) + one small file per widget. `BtWidgetConfigHydrationTest` scans this file by name (MB-55). **Effort**: M.

## 7. Tests

### MB-55 · 39 source-grep "discipline" tests pin file names — **rank 28**
- **Evidence**: 39 test files read `.kt` source as text (`ui/DestructiveTapDisciplineTest`, `ui/QaRoundADisciplineTest`, `ui/RowAnatomyDisciplineTest`, `ui/shell/TopBarNavigationTest`, `ui/theme/BtThemeDisciplineTest`, `widget/BtWidget*Test`, …). They are the project's only UI-rule guards (no Compose UI tests exist). Two risks: (1) exemption lists that can grow silently — `BtThemeDisciplineTest.kt:50 literalExemptions`, `BtWidgetPaletteMirrorTest.kt:92 flatExempt`, `TopBarNavigationTest.kt:284 allowed` (three are already empty maps, good); (2) they name FILES — `"AppShell.kt"`, `"SettingsScreen.kt"`, `"GalleryScreen.kt"`, `"BtSheetStack.kt"`, `BtWidgetConfigActivities.kt`, `CashScreen.kt`, `CashSummarySection.kt`, `WatchlistScreen.kt`, `PvVaultSyncChip.kt`, `PvVaultKeyDerivation.kt`, `BtColors.kt` — so every extraction in §6 moves code out of their scan without failing anything.
- **Do**: every scanner takes a package directory (most already fall back to `ui`); each exemption entry must match an existing file or the test fails; tag them `@Category(SourceScan::class)` so a future Compose-test suite can retire them one by one. **Effort**: M. This is the precondition for §6.

### MB-56 · `CashLedgerStatsLayoutTest.kt:48-52` — **rank 45**
- Asserts `R.string.bt_ledger_stats_title` is NOT referenced in `CashLedgerScreen.kt` — a negative-space guard for a 2026-08-17 removal; after MB-10 deletes the resource the assertion can never fail. **Do**: assert the positive shape ("the stats card's first child is the figures row") or delete with the resource. **Effort**: S.

### MB-57 · 7 `@Test` methods without an assertion call — **rank 46**
- `BtCrashGuardTest` "the background handler returns normally instead of propagating" (46 lines), `ui/BtSnackbarStateTest` "the no-op default controller swallows messages without failing", `data/api/DeadServerBoundaryTest` "transportErr re-throws cancellation rather than reporting it as an error", `vault/pv/envelope/PvEnvelopeConformanceTest` ×3 (`= antiSwap(n)` one-liners — assertions live in the helper, fine), `vault/server/ParanoidServerVaultE2ETest.theBrowsersArgon2idIsByteIdenticalToOurs`. For the first three the pass condition is "did not throw". **Do**: `assertFailsWith<CancellationException>` / `assertFalse(handlerThrew)` so the intent is in the assertion. **Effort**: S.

### MB-58 · Domain hand-ported tests — **Fable-only** — **rank 55**
- `domain/DomainHandPortedTest.kt:1-20` states what it covers: `vi.fn()` interaction (FX coalescing), non-finite inputs, error fields — exactly the cases a vector cannot carry; no hand-ported test merely re-states a vector (checked `DomainHandPortedTest`, `TaxHandPortedTest`, `DeTaxFixturesHandPortedTest` which replays the vendored fixture's declared outcomes). The only drift: `DomainVectorTest.TOLERANCES` has 4 entries (1 at W2); each needs the V8-vs-JVM ULP rationale the first one has. **Do**: a comment per entry. **Effort**: S.

### MB-59 · Test-precision baseline — record, do not act — **rank 58**
- 0 `assertTrue(true)`, 0 `assertEquals(x, x)`, 0 `@Ignore` (the one hit is a word in a KDoc), 3 `val expected = <main fn>()` sites — all legitimate (`RawDeflateTest` reads fixture bytes; `InsightsPaletteMirrorTest:51` parses `BtColors.kt` source and compares it to `insightsPdfPalette()`, a real cross-check). Keep this as the bar for MB-52.

### MB-60 · 98 `assertNotNull(` — **rank 59**
- Not individually reviewed. **Do**: sweep for ones not followed by an assertion on the value. **Effort**: S.

## 8. Lint, detekt, CI, Gradle

### MB-50 · CI runs no tests and no lint — **rank 5**
- **Evidence**: `.github/workflows/android-apk.yml:92-96` runs `:app:assembleGithubDebug` only; 3,779 unit tests and `lintGithubDebug` run by hand on the Mac (every TODO ledger "4362/0 BOTH flavors, lint 0 errors" is a manual gate). No `pull_request` trigger.
- **Do**: a `check` job before `build`: `./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug --no-daemon` on `push` and `pull_request`, uploading `lint-results-githubDebug.html` + the JUnit XML as artifacts; keep the publish steps on `main` only. Memory: `org.gradle.jvmargs=-Xmx3g` is already set for this. **Effort**: M. **Owner value**: very high — every bug in §1 would have been caught by a test that only exists on a laptop.

### MB-51 · No lint configuration — **rank 14**
- **Evidence**: no `lint {}` block in `app/build.gradle.kts`, no `lint.xml`, no baseline; 299 warnings. **Enable as errors** (hit → after fix): `UnusedResources` (88 → 0, MB-10), `Untranslatable` (25 → 0, MB-11), `MissingTranslation`/`ExtraTranslation` (0, guard), `UseKtx` (65: 53 `SharedPreferences.edit { }`, 6 `toUri()`, 6 `createBitmap` — mechanical), `LocalContextResourcesRead` (4), `ModifierParameter` (3), `AutoboxingStateCreation` (3), `EmptySuperCall` (2), `InlinedApi` (1), `PluralsCandidate` (6 — review), `ApplySharedPref` (4: `SecureStore.kt:171,210,324`, `SignOutLedger.kt:159` — `commit()` on the token store is probably deliberate; annotate or switch), `ConfigurationScreenWidthHeight` (7 sheets read `LocalConfiguration.current.screenHeightDp`; `tablet/t1-foundation` 8df5c70 adds `ui/adaptive/BtWindow.kt` as the one window-size source — migrate to it, after that branch lands). **Keep as info**: `UnusedAttribute` (38 = Glance `previewLayout`/`targetCell*`/`maxResizeHeight` API-31 attributes on 10 widget-info XMLs, expected at minSdk 28, + `enableOnBackInvokedCallback` API 33 + 1 `clipToOutline`), `SmallSp` (14, widget preview layouts — resolved by MB-48), `GradleDependency`/`NewerVersionAvailable` (30, see MB-53).
- **Do**: `lint { abortOnError = true; warningsAsErrors = false; error += setOf(…ids…); baseline = file("lint-baseline.xml") }`; commit the baseline for what remains. **Effort**: M.

### MB-52 · detekt trial — **rank 47**
- Not configured. Trial with `LongMethod` (threshold 200 → expect ~15: `SettingsScreen`, `BtApp`, `BtSheetHost`, `OverviewContent`, `CashScreen`, `WidgetsScreen`, `BtSheetStack`…), `LargeClass` (~10), `TooManyFunctions` (off for Compose files), `UnusedPrivateMember`/`UnusedParameter` (3 `@Suppress("UNUSED_PARAMETER")` already: `AudiencePickerSheet.kt:380`, `ChannelSetupRepository.kt:593`, `DriveDataHome.kt:690`), `ComplexCondition`; `MagicNumber` off. Run it on `main` once, keep the baseline, make it a CI step with MB-50. **Effort**: M.

### MB-53 · Gradle hygiene — **rank 37**
- `gradle.properties`: `org.gradle.caching` absent (add `true` — the two-flavour gates recompile identical inputs); `org.gradle.parallel` commented (single module — harmless). `app/build.gradle.kts:260-263` `compileOptions` Java 11 while CI uses JDK 21 and Kotlin 2.2 — move to 17 + `kotlin { jvmToolchain(17) }`. Catalog comments stale: `glance = "1.1.1"` says 1.2.0 *"has only reached rc01"* — lint reports 1.2.0 stable; `securityCrypto = "1.1.0-alpha06"` — 1.1.0 stable is out. Safe bumps from the 30 version hits: `security-crypto 1.1.0`, `glance 1.2.0`, `room 2.8.5`, `fragment 1.9.1`, `camera 1.6.2`, `firebase-bom 35.1.0`, `work 2.12.0`, `browser 1.10.0`, `appcompat 1.8.0`; hold `retrofit 3.0`/`okhttp 5` (API break), `kotlin 2.4.x` (must move with AGP's built-in Kotlin), `navigation 2.10`. Wrapper 9.4.1 → 9.8.2, AGP 9.2.1 → 9.5.0 with a build. **Effort**: S each.

### MB-54 · `gradle.properties` prose — **rank 60**
- The `-Xmx` comments are a changelog (2026-08-05/23/26). Keep the numbers, move the history to the ledger. **Effort**: S.

## 9. S6 UX-debt and TODO.md status

### MB-61 · `docs/S6_UX_DEBT.md` — all 23 resolved; close the file — **rank 48**

| Item | Status | Evidence |
|---|---|---|
| P0-1 ParanoidGate | done | `ParanoidGate(` in 3 files |
| P0-2 debug cleanup archives live portfolio | done | `ZZ App Test` gate in `debug/SyncDebugController.kt` ×2 + `ui/debug/SyncDebugScreen.kt`; the only `[bt:` left is a KDoc at `SyncDebugController.kt:157` |
| P0-3 1D chart diagonal | done | `chartSegments(...)` splits runs, `BtAreaChart.kt:404-417` |
| P0-4 raw server strings | done | `BtErrorCopy` catalogue (`data/api/BtErrorCopy.kt`), per-endpoint overrides (TODO 2026-10-29) |
| P0-5 silent refresh failure | done | offline banner `AppShell.kt:876-878`; snackbar overlay |
| P1-6..P1-16 | done | per R1/WP-B/WP-C ledger (TODO L358) |
| P1-9 three feedback idioms | done | `Toast.makeText`: 0 sites |
| P1-17 `mergeNotePreservingMarker` | done | 0 references |
| P2-18 NonObservableLocale | done | `rememberBtLocale()` 63 uses; the single `LocalConfiguration.current.locales` left is the helper (`ui/util/BtLocale.kt:34`) |
| P2-19 orphan routes | done | none of the five names in `navigation/BtRoutes.kt` |
| P2-20..P2-21 | done | per ledger |
| P2-22 raw placeholders | done | `"unknown"` only as a filter + KDoc (`SyncDebugScreen.kt:78,85`); `@deleted` only in a comment (`ChatListScreen.kt:277`) |
| P2-23 `—` escape | done | EN 201 / DE 195 literal em-dashes, 0 `—` in either |

**Do**: add a one-line "CLOSED 2026-08-05 — all 23 landed (R1 + WP-A/B/C)" header, or delete. **Effort**: S.

### MB-62 · `docs/TODO.md` open boxes — **rank 49**

| Box | Status | Evidence |
|---|---|---|
| L193 WS realtime re-test | stale — done | L214 "WS LIVE"; `data/repo/ChatRealtime.kt` |
| L314 S2 / L317 S2c | S2c-2 remainder is partly in: `ui/ideas/` (IdeasSection + IdeaDetailScreen, 1,425 lines), market intel (`ui/market/MarketIntelScreen.kt`), mirrorchain read (`ui/mirrorchain/`) | tick S2c-2's shipped halves, keep the box for what is left (name it) |
| L318 S3 | done (W1 seam + W5 wizard, L344) | tick |
| L319 S4 | W4 core merged; W6 direct-provider data is scaffolding behind `DIRECT_PROVIDER_PRICES` (owner decision) | annotate |
| L320 S5 | shipped (L347) | tick |
| L321 S6 | 23/23 (L358) | tick |
| L322 S7 polish | open — `tablet/t1-foundation` is the first slice (tablet sanity) | keep |

## Method notes

- Resource verification: Python scan of every `.kt/.xml/.java/.json` under `app/src` for each lint-named resource, excluding its own definition rows; reflection check `getIdentifier(` → 0 hits.
- Unused-symbol scan: regex over column-0 declarations (3,144 non-private), cross-file word-boundary search across main+test; "own-file mentions" counts the declaring file's total mentions. Reflection-free codebase (`di/AppGraph.kt` is plain lazies), so grep is sound; Room DAOs and `@Serializable` DTOs are referenced by Kotlin code, not by name.
- Flag dates: `git log -S'<literal>' -- app/src/main | tail -1`.
- Branch overlap: `git diff --name-only main...origin/<branch>`; `wire/drift-batch` is the common base of the other four.
- Not run: Gradle (no lint re-run was needed — the 2026-10-29 report was re-verified by hand), device, platform probes beyond reading `apps/api` source.
