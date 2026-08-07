# Batch 2 Design Spec — dual theme, modernization, web-icon integration (agent-authored 2026-08-07)

I have everything I need. Here is the build-ready spec.

---

# BetterTrack App — Batch 2 Design Spec: Dual Theme, Modernization, Web-Icon Integration

## 0. The read

The app is not old because it is ugly. It is old because it says everything **once, in grey, inside a box**. Three structural facts produce the "I can't point out what specifically" feeling: (a) the palette has exactly **two** neutral levels and one accent, so nothing on screen is ranked by colour; (b) the near-black page and the neutral-grey card are **different hue families** (`#0B0E14` blue-graphite vs `#171717` pure neutral — `BtColors.kt:18,22`), which reads as a slightly dirty card rather than a raised one; (c) every affordance is announced by a **1px hairline**, which was a 2019 idiom and is now the only hard edge in a system that otherwise moved to tonal grouping (`BtGroup.kt:33-57` explicitly retired dividers — but the bottom bar and every `BtCard` still draw them).

The codebase is in unusually good shape for this work: **13** hardcoded `Color(0x…)` literals outside the theme (`BtDonutChart.kt:99-110`, `BtAvatar.kt:69-73`, `CashTagUi.kt:48`), one `Color.White` (`BtQrCode.kt:52`), and **100 files** reading `BtTheme.colors`. The hard part is not the literals — it is the ~**70 alpha-composited brand washes** (`bt.gold.copy(alpha = …)` and friends across 22 files) that silently assume a dark substrate.

Trade-Republic-clean-but-not-simple, made operational: **reduce the number of visual devices, not the number of controls.** Every capability the app has today survives this batch.

---

## 1. Dual theme system

### 1.1 The reference is the platform, not a new invention

`apps/web/src/styles/origin.css` already ships a **complete light token block** at `:root[data-bt-theme='light']` (lines 78-102) — but nothing in the web sets that attribute (`git grep data-bt-theme` returns only the CSS, its test, and `tagChipColor.ts`). That is exactly why the owner ordered "tell webdev to implement white mode" (PLATFORM_ASKS.md #68 item 2). **The Android light palette below is derived from that block**, so the two clients converge, with two documented corrections where the web's block is under-specified (gold ink and the pos/neg ramp fail AA on light — see §1.4).

### 1.2 Structure: one token set, two value tables

`BtColors` stays a `@Immutable data class` and stays the source of truth. Three changes:

```
BtColors.kt
  - data class BtColors(...)                        // fields only, no defaults
  + val BtDarkColors  = BtColors(...)               // dark value table
  + val BtLightColors = BtColors(...)               // light value table
  + val isLight: Boolean                            // one flag components branch on
  + LocalBtColors = staticCompositionLocalOf { BtDarkColors }
```

`BetterTrackTheme.kt:24` (`private val Bt = BtColors()`) and `:26-65` (the single `darkColorScheme`) become a function of the resolved mode:

```
@Composable fun BetterTrackTheme(mode: BtThemeMode = BtThemeMode.System, content: …)
    val dark = when (mode) { System -> isSystemInDarkTheme(); Dark -> true; Light -> false }
    val bt   = if (dark) BtDarkColors else BtLightColors
    val scheme = if (dark) darkColorScheme(…bt…) else lightColorScheme(…bt…)
```

The M3 mapping at `BetterTrackTheme.kt:26-65` becomes one `materialSchemeFrom(bt: BtColors, dark: Boolean)` builder — the 30 role assignments are already written token-by-token, so they port verbatim. **Keep `surfaceTint = bt.bg`** (`:48`) in both modes: M3's elevation tinting fights an explicit tonal ramp.

### 1.3 The setting

`DevicePrefs` (`DevicePrefs.kt:22-43`) is the correct home — device-scoped, survives logout, and already proves the pattern the Activity needs: a **synchronous read before the first frame** (`orientationLockedNow()`, `:37-38`) plus a `StateFlow` for instant application.

```
enum class BtThemeMode { System, Light, Dark }     // default System
DevicePrefs.themeMode: StateFlow<BtThemeMode>
DevicePrefs.themeModeNow(): BtThemeMode            // for MainActivity.onCreate
DevicePrefs.trueBlack: StateFlow<Boolean>          // sub-toggle under Dark only
```

`trueBlack` is a **boolean under Dark, not a fourth mode** — it overrides `bg` to `#000000` and `surfaceLow` to `#050608`, nothing else. It was already in the owner's idea pool (`OPUS_COORDINATOR_HANDOFF.md:136`, "theme variants (AMOLED true-black, optional light)"), and keeping the enum 3-valued means every `when` in the app stays exhaustive when it lands.

UI: Settings → new **Appearance** group above Privacy, one `BtGroupRow` opening a 3-option picker (the `SettingsPicker` pattern at `SettingsScreen.kt:679` already exists — add `SettingsPicker.Theme`).

### 1.4 The token tables

Semantics are unchanged; only values flip. **Raised = lighter in both modes** (following the web: light `--bt-surface #fafafa` sits above `--bt-bg #f1f2f3`). This is deliberate — it means `BtGroup`'s "one tonal step up" logic (`BtGroup.kt:50-53`) needs no inversion.

**Neutrals** (L\* in brackets — perceptual lightness, the metric that matters here; WCAG ratios between near-blacks are meaningless):

| token | dark | L\* | light | L\* | role |
|---|---|---|---|---|---|
| `bg` | `#0A0D12` | 3.6 | `#EEF0F2` | 94.7 | page |
| `surfaceLow` | `#10141A` | 6.2 | `#F4F5F7` | 96.9 | inset wells, recessed rows |
| `surface` | `#161B22` | 9.6 | `#FFFFFF` | 100 | cards, groups |
| `surfaceHigh` | `#1C222B` | 13.0 | `#FFFFFF` | 100 | sheets, dialogs, **nav bar** |
| `surfaceHighest` | `#232A34` | 16.8 | `#E8EAEC` | 92.3 | pressed/hover, skeleton highlight |
| `border` | `#DEE6EF` @ 8.5% | — | `#141B23` @ 10% | — | hairline |
| `borderStrong` | `#DEE6EF` @ 15% | — | `#141B23` @ 16% | — | emphasised hairline |
| `textPrimary` | `#F4F6F8` | | `#131820` | | |
| `textSecondary` | `#C7CDD5` | | `#3E4650` | | |
| `textMuted` | `#8B949F` | | `#56616D` | | 5.16:1 / 5.23:1 min (web-measured) |
| `textFaint` | `#828C96` | | `#5D6773` | | 4.63:1 / 4.77:1 min |

Two things to note. **Borders become alpha, not opaque** — the current `#262626` (`BtColors.kt:24`) only looks right over `#171717`; an alpha border composites correctly on all five surface levels, which is what makes a five-step ramp usable at all. And the dark neutrals move to **one hue family** (blue-graphite, hue ≈ 216°), killing the page/card hue mismatch that reads as grime.

The five-step ramp is monotone at ΔL\* ≈ 2.6 / 3.4 / 3.4 / 3.8 in dark — perceptible without being noisy. **Light mode cannot do this**: `#EEF0F2 → #FFFFFF` is only 5.3 L\* total and the intermediate steps are ~1-2 L\* apart. This is the single most important consequence of going dual-theme, and it produces one app-wide rule:

> **Tone separates in dark; tone + hairline separates in light.**
> `BtGroup` (borderless today, `BtGroup.kt:64-72`) gains a `groupBorder` token = `Color.Transparent` in dark, `border` in light. Same for the bottom bar's top edge, `BtStates`' badge (`BtStates.kt:73-76`), and sheet edges.

One token, one rule, applied in ~6 components. Do not let this become per-screen `if (isLight)`.

**Brand**

| token | dark | light | note |
|---|---|---|---|
| `gold` | `#F6B82E` | `#F6B82E` | **constant.** Fills, wordmark, brand. 10.4:1 vs `onGold` |
| `goldInk` | `#F6B82E` | `#8F5F00` | text/icon *on a surface*. Light: 5.52:1 on white, 4.83:1 on `bg` |
| `onGold` | `#171105` | `#171105` | ink on a gold fill (web `--bt-gold-ink`) |
| `goldWash` | `gold` @ 14% | `gold` @ 16% | tinted pill/card fill |
| `goldWashStrong` | `gold` @ 22% | `gold` @ 26% | selected chip, indicator |
| `goldEdge` | `gold` @ 30% | `goldInk` @ 30% | wash borders (light uses the *ink* hue — a pale gold hairline on white is invisible) |

`gold` at `#F6B82E` is **1.78:1 against white** — it can never be text in light mode. That is exactly the defect the web has today (`origin.css` uses `color: var(--bt-gold)` in 20+ rules and never overrides `--bt-gold` for light). Splitting `gold` (fills) from `goldInk` (text) is the fix, and it is worth relaying to Fable as a head start on the web's white mode.

**Semantic**

| token | dark | light | light ratio |
|---|---|---|---|
| `gain` | `#34D399` | `#0F7A55` | 5.10:1 on `surface`, 4.67:1 on `bg` |
| `loss` | `#FB7185` | `#B23A4E` | 5.57:1 / 5.10:1 |
| `gainSoft` / `lossSoft` | `#6EE7B7` / `#FCA5A5` | *= `gain` / `loss`* | see below |
| `gainWash` / `lossWash` | @ 14% | @ 12% | badge fill |

Adopt the web's `#FB7185` over the app's current `#F87171` (`BtColors.kt:49`) — the platform value moved and the app didn't.

The asymmetry that will bite implementers: **"soft" means lighter, and lighter fails on white.** `BtBadge` (`BtChip.kt:81-86`) is `tint@14% fill + soft ink` in dark; in light it must be `tint@12% fill + full-strength ink`. Encode that in the token (`gainSoft` == `gain` in light), not in the component.

**Chart** (new tokens — currently these values are inlined in the draw code)

| token | dark | light |
|---|---|---|
| `chartGrid` | `#DEE6EF` @ 6% | `#141B23` @ 8% |
| `chartAxis` | `#77818D` | `#5D6773` |
| `chartAreaTopAlpha` | `0.24f` | `0.18f` |

The gradient at `BtAreaChart.kt:174-178` and `BtPriceChart.kt:171-176` hardcodes `0.24f → 0f`. 24% of a saturated hue reads far heavier on white than on near-black; the alpha becomes a token.

### 1.5 What breaks — the enumerated audit

| # | Break | Sites | Fix |
|---|---|---|---|
| B1 | **~70 alpha-composited brand washes** — the single largest surface | 22 files; e.g. `BtChip.kt:33-35`, `BtGroup.kt:232` (7% gold wash → invisible cream on white), `BtCollapsingHeader.kt:610-611`, `AppShell.kt:649`, `NotificationsInboxScreen.kt:569-582`, all of `ui/storage/StorageWizardScaffold.kt` | hoist to `goldWash` / `goldWashStrong` / `goldEdge` / `lossWash` / `gainWash`; **guard test** forbidding `bt.gold.copy(alpha` outside `ui/theme/` |
| B2 | **Hardcoded `Color(0x…)`** | `BtDonutChart.kt:99-110` (7), `BtAvatar.kt:69-73` (5), `CashTagUi.kt:48` (1) | donut ramp → `chartCategorical` tokens aligned to `CATEGORICAL_SERIES`; `BtAvatar`'s ramp **deleted** (§3); `FallbackTagColor` → token |
| B3 | **Donut/avatar palette has drifted from the platform** | `BtDonutChart.kt:99-103` = `#3987E5,#1D9DBF,#6D5BD0,#C25B8E,#B58840` vs `palette.ts CATEGORICAL_SERIES` = `#3987e5,#d95926,#199e70,#c98500,#d55181,…` — only slot 1 matches | adopt `CATEGORICAL_SERIES` verbatim for dark; darken the 4 hues the web hasn't (`#199e70→#12805B`, `#c98500→#9A6600`, `#0d9488→#0B7A70`, `#c0453f→#A03832`) for light; the 6 pf-chip hues already have web light counterparts (`origin.css:5918-5940`) |
| B4 | **Chart gradients + gridlines** | `BtAreaChart.kt:143` (`bt.border.copy(alpha=0.55f)`), `:174-178`; `BtPriceChart.kt:171-176`; `BtStepLineChart.kt:106` | `chartGrid` / `chartAxis` / `chartAreaTopAlpha` tokens |
| B5 | **Skeleton shimmer is OLED-tuned** | `BtColors.kt:55-57` (`#1C1C1C` base / `#262626` highlight); `BtSkeleton.kt:68-69,77-84` | base = `surfaceLow`, highlight = `surfaceHighest`; the 3-stop linear gradient is direction-agnostic and needs no other change |
| B6 | **Forced-dark system bars** | `MainActivity.kt:70-74` (`SystemBarStyle.dark(TRANSPARENT)` × 2, comment "Dark-only app ⇒ force dark") | `SystemBarStyle.auto(lightScrim, darkScrim)` driven by `themeModeNow()`, re-applied on the mode flow |
| B7 | **XML window/splash theme is dark-only** | `themes.xml` (`windowLightStatusBar false`, `windowSplashScreenBackground @color/bt_bg`) | add `res/values-night/themes.xml` + `values-night/colors.xml`; `bt_bg` gains a light value. *Note:* the XML theme follows the **system**, not the in-app setting — accept a one-frame splash mismatch when the user forces Light on a dark system, or gate the splash background on the persisted pref via `SplashScreen.setSplashScreenTheme` |
| B8 | **`Color.White` QR background** | `BtQrCode.kt:52` | correct as-is (QR scanners need white quiet zone) — **exempt it explicitly** in the guard test |
| B9 | **`bt.surface` used as a "ring" colour** | `BtCountBadge.kt:130` (dot ringed in `bt.surface`) | ring must match whatever it sits on — becomes `navBar` for the tab dot |
| B10 | **`bt.bg` as an inset fill inside a card** | `AudiencePickerSheet.kt:323`, `FriendGroupsScreen.kt:552`, `SettingsScreen.kt:928` | semantically correct in both modes (`bg` is darker than `surface` in light too) — **no change**, but verify visually |
| B11 | **`colors.xml` brand mirror** | `bt_bg/bt_surface/bt_border/bt_gold` | mirror the new dark values; add `values-night` counterparts |

### 1.6 Migration order (app shippable after every step)

1. **Token expansion, dark-only.** Add every new field to `BtColors` with dark values ≈ visually identical to today. `LocalBtColors` still provides dark. Zero call-site change. Ship.
2. **De-literalise.** B1 + B2 + B4 + B5. Still dark-only, still pixel-comparable. Land the guard test. Ship.
3. **Theme plumbing.** `BtThemeMode` in `DevicePrefs`, `BetterTrackTheme(mode)`, B6, B7, B11. Light reachable **only** from the gallery's debug toggle. Ship.
4. **Component sweep.** `BtGroup`, `BtCards`, `BtChip`/`BtBadge`, `BtStates`, `BtSkeleton`, `BtCollapsingHeader`, `BtTextField`, `BtButtons`, `BtSnackbar` — apply the tone-vs-hairline rule. Verify all 16 gallery sections × 2 themes. Ship.
5. **Charts.** B3 + per-mode chart tokens. Ship.
6. **Expose the setting.** Settings → Appearance. **Light mode goes public here** — not before.
7. **Screen sweep** in traffic order: Portfolio → Markets → Workbench → People → Settings → storage/tax/social long tail.

The point of this order: light mode is user-reachable only after every shared component has been verified in it, so no intermediate commit can ship a broken light screen.

---

## 2. The "looks old" diagnosis — named agers and their replacements

| # | The ager | Evidence | Modern replacement |
|---|---|---|---|
| A1 | **Two-level neutral system.** Everything on screen is either `bg` or `surface`; nothing is ranked by depth. 164 uses of `bt.surface`, 94 of `bt.bg`, and no third level. | `BtColors.kt:18-26` | The 5-step ramp (§1.4). Sheets/dialogs move to `surfaceHigh`, inset wells to `surfaceLow`, pressed to `surfaceHighest`. Depth becomes information. |
| A2 | **Page and card are different hue families.** `#0B0E14` is blue-graphite (hue 220°); `#171717` is pure neutral. The card looks slightly warm and dirty on the page. | `BtColors.kt:18` vs `:22` | Unify at hue ≈ 216° across the whole ramp. This one change does more for "modern" than any layout edit. |
| A3 | **The hairline is the dominant visual device.** Every `BtCard` draws a 1px border (`BtCards.kt:41`) — and `#262626` on `#171717` is a **1.28:1** luminance step, which reads as a smudge, not a rule. | `BtCards.kt:40-41`; `border` used in 88 places | `BtCard` drops its border in **dark** (tone does the work) and keeps it in **light**. `BtGroup` already proved the pattern (`BtGroup.kt:33-57`) — finish the job the R2 mandate started. |
| A4 | **Muted monochrome outside gold.** 243 uses of `bt.gold` vs 211 of gain/loss and *nothing else*. Portfolio identity, categories, chart series, tags — all grey. | grep census | Colour-as-signal: portfolio icon-chip tints (§3), performance-driven accents (§4), the categorical palette on donuts/legends. The owner said "don't be afraid to use more color" — this is where it goes, and it is all **semantic**, never decorative. |
| A5 | **Icon weight mismatch.** 143 distinct Material Outlined glyphs at 2.0dp stroke, sitting next to 1px hairlines and 1.6-equivalent web glyphs. Sizes run 11/14/15/16/18/20/22/24/26/28/32/36dp with no scale. | `Icons.*` census; `size(20.dp)`×14, `size(22.dp)`×12, `size(18.dp)`×7 | Origin stroke set (1.6/24, round caps) for the chrome + identity layer (§3.3); a **3-step size scale** (16 / 20 / 24dp) replacing the 12 ad-hoc values. |
| A6 | **The bottom bar.** See §6 for the precise diagnosis. | `AppShell.kt:614-657` | §6. |
| A7 | **Dense text blocks.** 37 strings over 120 chars, 93 more in the 80-119 band, 47 `*_body` / 37 `*_hint` / 16 `*_note` / 26 `*_desc` keys. Storage wizard and Settings are the worst offenders. | `strings.xml` census | §5. |
| A8 | **Radii are already right — don't touch them.** `BtShapes` is 12/12/16dp with a documented rationale (`BtShapes.kt:8-30`). | — | No change. Cite this at review time so nobody "modernises" it. |
| A9 | **Typography is already right.** `moneyHero` 44sp / tight tracking / tabular figures (`BtTypography.kt:37-79`). | — | No change. Add **one** style: `labelNav` 11sp Medium for the bar. |

A3 + A1 + A2 together are ~80% of the "too old" feeling. A4 is the other 20% and is the part the owner articulated directly.

---

## 3. Web icon integration

### 3.1 Where they live (all read-only from `origin/main`)

| Asset | Path | Shape |
|---|---|---|
| 16 profile avatars | `apps/web/src/user/components/profileIcons.tsx` | Per-icon 3-colour `IconPaint {bg,fg,accent}` (lines 24-41) + a `viewBox="0 0 64 64"` renderer each (lines 46-215), drawn over `<rect width=64 height=64 rx=12 fill={bg}>` (line 233) |
| Icon id contract | `packages/contracts/src/social.ts:45-64` | `PROFILE_ICON_IDS` — **already byte-identical** to the app's `BT_PROFILE_ICONS` (`AccountSecurityDtos.kt:213-216`) |
| Portfolio kind glyphs | `apps/web/src/ui/origin/icons.tsx:459-505` | `user-lock`, `family`, `briefcase`, `piggy-bank`, `building`, `users` — 24×24, stroke 1.6, round caps |
| Kind→glyph→tint map | `apps/web/src/user/portfolio/portfolioKinds.ts` | `PORTFOLIO_KINDS` (5) + `PORTFOLIO_GROUP_ICON`; tints in `origin.css:5893-5940` |
| Chip rendering | `apps/web/src/user/portfolio/PortfolioIconChip.tsx` | 26px (30px `lg`) rounded chip, hue @ 14% fill, hue @ 26% border, glyph at full strength; `--shared` corner marker for group portfolios |
| Full Origin set | `apps/web/src/ui/origin/icons.tsx` (543 lines, ~80 glyphs) | uniform 1.6/24 round-cap stroke |

**Licensing: none.** `icons.tsx:6` — *"Drawn in-house so the GUI ships no icon dependency."* Same monorepo, same owner.

### 3.2 The gap the owner is actually pointing at

`BtAvatar` (`BtAvatar.kt:27-53`) renders **initials on a hashed tint**. The web *never* renders initials — `Avatar.tsx` always renders a curated SVG, falling back to a deterministic name-derived one. Eighteen call sites (`ChatThreadScreen`, `ChatListScreen`, `SocialScreen`, `FriendOverviewScreen`, `ChainManageScreen`, `AudiencePickerSheet`, `FriendGroupsScreen`, `SharedDetailScreens`, `MirrorInvitesCard`, `ChainDetailSheet`) show a grey "CW" where the web shows a fox.

Worse, **the data is already on the wire and gets thrown away**: `SocialUserDto.profileIcon` exists (`SocialDtos.kt:32-36`), but the domain models drop it — `Friend` (`SocialRepository.kt:80-84`) and `FriendRequest` (`:86-93`) have no icon field. `SocialThreadRepository.kt:165` and `FriendGroupRepository.kt:30` do carry it, so the seam is half-built already.

And the picker itself: `profileIconVector()` (`SettingsScreen.kt:959-976`) maps the 16 ids onto **Material glyphs**, with fox and panda deliberately sharing `Icons.Outlined.Pets` because "there is no fox" (`:950-951`). Its own KDoc says *"The platform ships no artwork for these ids"* (`:800`) — that was true when it was written and is now false.

### 3.3 Delivery: two mechanisms, two jobs

**(a) Profile avatars → 16 XML vector drawables.** Multicolour artwork, never tinted. `res/drawable/ic_bt_avatar_<id>.xml`, `viewportWidth/Height="64"`, `width/height="64dp"`.

Conversion: paste each icon's `<g>` under its `<rect rx=12>` into a standalone SVG with the palette substituted, run Android Studio's Vector Asset import (or `svg2vector`). All primitives used (`circle`, `ellipse`, `rect`, `line`, `path`) convert to `pathData`; `stroke-width` / `stroke-linecap="round"` / `stroke-linejoin` / `opacity` map to `android:strokeWidth` / `strokeLineCap` / `strokeLineJoin` / `fillAlpha`+`strokeAlpha`. **Verify each one against the web render** — `panda` and `ghost` use `fill={bg}` for cut-outs (lines 66-77, 175-181), which must resolve to the tile colour, not transparent.

The palettes are **theme-independent by design** (`profileIcons.tsx:19-21`: *"Colours pass AA contrast against both light and dark tiles at every size"*) — one drawable serves both modes.

New API:

```
ui/components/BtProfileIcon.kt
  @DrawableRes fun profileIconRes(id: String?): Int?          // null → unknown id
  fun defaultProfileIconIdFor(seed: String): String            // literal port, see below
```

`BtAvatar` becomes:

```
BtAvatar(name: String, iconId: String? = null, size: Dp = 40.dp, gold: Boolean = false)
  → Image(painterResource(profileIconRes(iconId ?: defaultProfileIconIdFor(name))!!),
          modifier = Modifier.size(size).clip(CircleShape))
  gold=true → keep the current gold ring as an overlay border (self-chip marker)
```

The web clips the 64×64 rounded tile to a **circle** (`origin.css:1584` `border-radius: 50%` + `overflow-hidden`) — match that exactly.

**`defaultProfileIconIdFor` must be a literal port** (`profileIcons.tsx:229-237`):
```
hash = 0; for each char: hash = (hash * 31 + char.code) % 16
```
The modulo is *inside* the loop, so `hash` never leaves `[0,16)` and Kotlin `Int` arithmetic matches JS exactly. Ship a vector test with ~10 usernames cross-checked against a Node run of the web function. Get this wrong and the same person shows a different avatar on phone and web — the exact "integrate the way the webapp works" failure the owner is guarding against.

**(b) Portfolio kind glyphs + the chrome set → Kotlin `ImageVector`s.** These are single-colour and get **tinted**, and the app's existing params take `ImageVector` (`BtGroupRow.icon`, `TabSpec.icon` at `AppShell.kt:221`, `BtCollapsingHeader.titleIcon` at `PortfolioOverviewScreen.kt:305`). Building them as `ImageVector`s means **zero signature churn**.

```
ui/theme/BtIcons.kt
  object BtIcons {
    // Portfolio kinds (6)
    val UserLock, Family, Briefcase, PiggyBank, Building, Users
    // Bottom-bar + chrome (~20): Pie, Workbench, Assets, People, Search, Settings,
    // Bell, Inbox, Plus, X, Check, ChevronRight/Left/Up/Down, More, TrendingUp,
    // TrendingDown, Sun, Moon
  }
```
Every one of those exists verbatim in `icons.tsx`. Shared builder: `viewportWidth/Height = 24f`, `strokeLineWidth = 1.6f`, `strokeLineCap = Round`, `strokeLineJoin = Round`, `stroke = SolidColor(Color.Black)` (tinted at draw).

**Scope discipline on the icon swap.** Do **not** convert all 143 Material glyphs — the Origin set covers roughly 60 of them, and drawing the other 83 is literally "make up new icons", which the owner forbade. The rule:

> **Origin owns chrome + identity** (bottom bar, headers, portfolio chips, avatars, primary actions). **Material Outlined owns the utility/domain long tail** (deep settings, tax, storage wizard). A single row group never mixes the two.

That keeps the layer the owner looks at every day uniform, and the seam falls on screen boundaries where a weight difference is invisible. Growth path: Origin gains a glyph whenever the web does — which is a platform ask (§8).

### 3.4 Where they surface

| Surface | Change |
|---|---|
| **Profile-icon picker** (`SettingsScreen.kt:810-943`) | `ProfileIconCell` (`:918-943`) renders the real avatar instead of `Icon(profileIconVector(id))`; keep the 4×4 grid in contract order (`:876`), bump `PROFILE_ICON_CELL` 52→56dp, selection = `goldEdge` ring + `goldWash` backing rather than a tint swap. **Delete `profileIconVector` entirely** (`:959-976`), including the fox/panda collision. Fixes the TalkBack gap noted at `:934-937` — each id now gets a `contentDescription` string. |
| **Every social/chat surface** | 18 `BtAvatar(name = …)` call sites gain `iconId = …`. Requires `Friend` (`SocialRepository.kt:80`) and `FriendRequest` (`:86`) to carry `profileIcon` — a 2-line DTO→domain mapping, already present on `SocialUserDto:34`. |
| **Settings account row** | `SettingsScreen.kt:456-457` currently shows a Material glyph tinted gold; becomes the real avatar. |
| **Portfolio switcher** (`PortfolioSwitcherSheet.kt`) | Each row gains a `BtPortfolioChip(kind, group)` leading slot — 26dp rounded chip, kind hue @14% fill, @26% border, glyph at full strength. Overview's pinned entry keeps `Icons.Outlined.Home` (`:363`) or moves to Origin `home`. |
| **Collapsing header selector** (`BtCollapsingHeader.kt:600-622`) | The gold chip becomes the **portfolio's own kind chip** — `bt.gold@14%` at `:610` becomes the kind hue. This is the single most visible "more colour" win in the app and it is pure web parity: `PortfolioIconChip` `size='lg'` is exactly this control. Keep gold for Overview (which is account-wide, i.e. brand scope). |
| **Portfolio settings** | New "Icon" section, 5 kinds + preview — mirrors the web's own naming (`portfolioKinds.ts:12-16`: *"To the user this is the portfolio's **Icon**"*, never "kind"). |

**Storage problem, flagged loudly:** the web keeps `kind` in `localStorage` under `bt.portfolio.kinds` (`portfolioKinds.ts:78`) because **there is no API field** — the file documents the graduation path explicitly (`:20-31`). So the app must also store it locally (Room `meta`, account-keyed, wiped on logout), and **a kind chosen on the web will not appear on the phone or vice-versa.** That is a real cross-client divergence the owner will notice within a day of using it. It is a platform ask, not an app bug (§8).

---

## 4. Colour semantics

Owner: *"portfolio staying gold/yellow but assets and other stuff red or green depending on the current timespan."*

### 4.1 The rule

| Scope | Accent | Rationale |
|---|---|---|
| **Portfolio-level value** — the hero chart, hero number, portfolio identity | `gold`, always | Gold *is* the portfolio. It never means "up". |
| **Asset-level value** — asset charts, mover cards, watchlist rows, holding rows, holding detail | `gain` / `loss` by the **currently selected range's** performance | An asset is a bet; its colour is its verdict. |
| **Neutral / zero** | `textSecondary` | Already the behaviour at `PortfolioOverviewScreen.kt:1463`. |
| **Category identity** — donut slices, portfolio kinds, tags | categorical palette | Identity, never rank. |
| **Destructive / error** | `loss` | Unchanged. |

### 4.2 Which timespan drives it

`HistoryRange` (`PortfolioHistory.kt:65-79`: `D1 W1 M1 M6 Y1 MAX`, default `M1`) is already the app's one timespan concept, already user-selectable via chips (`PortfolioOverviewScreen.kt:977-984`), and already produces `history.rangePerformancePct` (`:921`).

**Rule: the accent follows the range chip the user is looking at.** One helper, in the same file as `deltaColor` (`PortfolioOverviewScreen.kt:1460-1464`):

```
@Composable fun rangeAccent(pct: Double?): Color = when {
    pct == null -> BtTheme.colors.textSecondary
    pct >  0.0  -> BtTheme.colors.gain
    pct <  0.0  -> BtTheme.colors.loss
    else        -> BtTheme.colors.textSecondary
}
```

Movers already carry their own basis (`holding.dayChangePct`, `HomeScreen.kt:597`) — those stay **day-based** because the card explicitly says "today's movers"; changing them to range-based would make the label lie.

### 4.3 The exact surfaces that change

| Surface | Today | After |
|---|---|---|
| **Asset price chart** | `BtPriceChart` defaults `lineColor = gold` (`BtPriceChart.kt:57`) and `AssetPageScreen.kt:443` never overrides it | `lineColor = rangeAccent(rangePct)`; area gradient follows (it derives from `lineColor`, `:171-176`) |
| **Custom asset chart** | `lineColor = bt.gold` (`CustomAssetDetailScreen.kt:432`) | same |
| **Portfolio hero chart** | `lineColor = bt.gold` (`PortfolioOverviewScreen.kt:946`) | **unchanged — gold** |
| **Mover cards** | `deltaColor(pct)` on the percent text only (`HomeScreen.kt:613`); the card is a plain `BtCard` | percent text unchanged **+** a 3dp left accent rail in `gain`/`loss` @ 60% on the card. Scannable at a glance without shouting. |
| **Holding rows** | neutral card + `deltaColor` on the P/L text (`PortfolioOverviewScreen.kt:1297-1303`) | same accent rail, driven by the row's range P/L |
| **Watchlist rows** | neutral | same rail |
| **Range chips** | `selected` = gold wash (`BtChip.kt:33-35`) | **stay gold** — the chip is a control, not a value. Do not let the accent leak into controls. |
| **Holding detail hero** | `deltaColor` on text (`HoldingDetailScreen.kt:445,537,565`) | text unchanged + hero chart accent |
| **Donut** | 5 drifted hues (`BtDonutChart.kt:99-103`) | `CATEGORICAL_SERIES` (B3) |

### 4.4 Accessibility

- Light-mode `gain`/`loss` are darkened to clear AA at body size (§1.4). The dark ramp is unchanged and already passes.
- **Colour is never the only carrier.** Every accented surface already ships a signed number (`showSign`, `MoneyText.kt:39`) or an arrow. The 3dp rail is redundant encoding by construction — keep it that way.
- The green/red pair is the app's *only* red/green usage and is on a **signed numeric**, which is the one case dichromats can still read from the sign. The categorical palette deliberately excludes green/teal/red-brown/yellow to avoid collision (`origin.css:5864-5869`) — preserve that exclusion when porting.
- **Run the six-checks validator on the light categorical palette.** The web validated its palette against `#10151b` only (`palette.ts:8-11`); nobody has ever validated the light counterparts. This is a real, unfinished piece of work, not a formality.

---

## 5. Copy trim

Owner: *"don't put so much unnecessary text everywhere — disclaimers fine, overexplaining hurts."*

### 5.1 The rules

**KEEP, verbatim, do not shorten:**
1. **Legal one-liners.** `bt_taxyear_disclaimer` ("not tax advice"), `bt_about_privacy`, `bt_about_terms`.
2. **Consequence statements for destructive acts** — anything naming what is permanently lost: `bt_del_warning_body`, `bt_switcher_delete_warning`, `bt_storage_delete_warning_body`, `bt_groups_delete_body`, `bt_chain_leave_message`.
3. **Consent acknowledgements**: `bt_social_public_ack_body`, `bt_storage_ack_body`.
4. **Failure consequences** — what state the system is in after something broke: `bt_storage_fail_round_trip_body`, `bt_storage_remote_delete_failed`, `bt_server_vault_restore_wrong_key`.
5. **Non-obvious system behaviour the user cannot discover by acting**: `bt_txform_backdated_cash_warning`, `bt_txform_uncovered_body`, `bt_cash_not_editable_hint`.

**CUT or compress to ≤ 1 line:**
1. **Screen preambles** — "This screen lets you…". The header already said it.
2. **Instructions for self-evident UI** — anything describing how to tap a control that is on screen.
3. **Repeated hints** — the same sentence parameterised by noun (see below).
4. **Empty-state essays** — an empty state gets a title, one line, and a CTA. `BtStates` already has exactly those three slots (`BtStates.kt:53-62`: `title`, `message`, `detail`, `action`); the `detail` slot is where the essays leaked in.
5. **Restatements of a label directly above them.**

**The test for a builder:** delete the string, look at the screen, and ask *"can a competent adult still act correctly?"* If yes, it goes. If the answer depends on something invisible (money moving, data being erased, a legal claim), it stays.

### 5.2 The sweep's file list

`grep -oE '<string name="[^"]+">[^<]{120,}</string>'` → **37 candidates**; the 80-119 band adds **93** more. Suffix census: 47 `_body`, 37 `_hint`, 26 `_desc`, 16 `_note`, 7 `_intro`, 1 `_footnote`.

**Tier 1 — pure duplication (8 strings → 2, mechanical, do first):**
```
bt_social_hint_public_active_portfolio / _watchlist / _conglomerate   (all >120 chars)
bt_share_hint_public_active_idea
bt_social_hint_all_friends_portfolio / _watchlist / _conglomerate
bt_share_hint_all_friends_idea
```
Four nouns × two states, eight strings, one sentence each. Collapse to two `%1$s`-parameterised strings. −6 keys × 2 languages.

**Tier 2 — preambles and instructions (kill or ≤1 line):**
```
bt_storage_wizard_intro     bt_sessions_intro          bt_2fa_intro
bt_2fa_enroll_scan          bt_storage_choose_drive_body
bt_storage_choose_server_body  bt_storage_choose_both_body
bt_storage_google_body      bt_storage_pass_body       bt_storage_working_body
bt_storage_kit_body         bt_paranoid_body           bt_price_search_body
bt_shared_idea_copy_hint    bt_prices_toggle_needs_account
bt_settings_applock_footnote   bt_applock_setup_bt_hint
```

**Tier 3 — empty-state essays (→ title + one line + CTA):**
```
bt_ideas_empty_message      bt_alerts_empty_message    bt_rules_empty_message
bt_so_empty_message         bt_overview_no_holdings_message
bt_social_no_friends_body   bt_social_swm_empty_body   bt_social_fo_empty_body
bt_chat_empty_hint
```

**By file, densest first:** `ui/storage/` (7 files — the wizard is the single worst offender), `ui/settings/` (10), `ui/social/` (7), `ui/paranoid/`, `ui/ideas/`, `ui/prices/`.

### 5.3 The two hard constraints

1. **`StringParityTest` (`i18n/StringParityTest.kt`) enforces EN↔DE key-set equality and placeholder equality.** Every deletion must be mirrored in `values-de/strings.xml` (both files are at exactly 1855 `<string>` entries today) and every rewrite must keep its `%n$s` set identical. The test will catch a miss — but it fails the whole build, so do the pairs together.
2. **`BtErrorCopyTest` guards error copy** — do not touch anything reachable from `BtErrorCopy.kt`.

**Deliverable gate:** a table of `key → old → new → verdict(KEEP/CUT/COMPRESS)` reviewed before any edit lands, so the owner can veto in one pass rather than screen by screen.

---

## 6. Bottom bar redesign

### 6.1 Precise diagnosis of "a little rough"

Current (`AppShell.kt:614-657`):
- `NavigationBar` = **80dp** (`NavigationBarTokens.TallContainerHeight`, material3 1.4.0), `containerColor = bt.surface` (`:623`).
- A full-width `HorizontalDivider(1.dp, bt.border)` above it (`:622`).
- Indicator **56×32dp** `CornerFull`, `gold@16%` (`:649`).
- Labels always shown, `labelMedium` 12sp (`:643`).
- Unselected `bt.textMuted` #8A8A8A (`:650-651`) — 5.20:1 on #171717, passes.
- Icons: `PieChart`, `Dashboard`, `ShowChart`, `People` (`:227-230`), Material Outlined 24dp / 2.0dp stroke.
- Badge: 10dp gold dot, 1.5dp `bt.surface` ring, offset (+5,−3) onto the glyph (`:637-639`, `BtCountBadge.kt:126-131`).

What actually reads as rough, in order of impact:

1. **`containerColor = bt.surface` is *exactly* the card colour.** The bar has no identity of its own — cards float on the bar's own tone and the bar reads as a stuck card rather than a frame. The web solved this with a dedicated `--bt-nav` token (`origin.css:18`, `:81`).
2. **A 1px hard divider is the app's own retired idiom.** `BtGroup.kt:33-42` states the rule — *"tonal elevation instead of divider lines… adding dividers would have been the same mistake with thinner lines"* — and then the bar draws one. It also can't work: `#262626` on `#171717` is a **1.28:1** step, so it reads as a smudge, not a rule.
3. **80dp + divider + gesture inset ≈ 104dp of permanent chrome** — 13% of a 360×800 viewport for four destinations.
4. **The 32dp indicator under a 24dp glyph reads as a crop, not a pill.** 4dp of breathing room on each side is a capsule that looks accidental.
5. **The two densest glyphs sit next to each other.** `PieChart` and `Dashboard` are both multi-part; at 24dp/2.0dp stroke they mush and share a silhouette.
6. **The tab icons are the heaviest strokes on screen** — 2.0dp against the app's 1px hairlines.

### 6.2 The spec

**Container.** `ShortNavigationBar` — **stable, not experimental** in material3 1.4.0 (verified: zero `Experimental` annotations in `ShortNavigationBar.kt`). `NavigationBarTokens.ContainerHeight = 64.dp`; the indicator tokens are unchanged (`NavigationBarVerticalItemTokens`: 56×32, `CornerFull`, 24dp icon, 6dp icon-label gap). **16dp reclaimed for free**, and it also unlocks `NavigationItemIconPosition.Start` for a future compact/landscape variant.

**Colour.**

| | dark | light |
|---|---|---|
| container (`navBar` token) | `surfaceHigh` `#1C222B` — ΔL\* 9.4 above the page | `#FFFFFF` — ΔL\* 5.3 above `#EEF0F2` |
| top edge | **none** (tone separates) | 1dp `border` hairline |
| indicator fill | `goldWashStrong` (gold @ 22%) | gold @ 26% |
| indicator ring | none | 1dp `goldEdge` |
| selected icon + label | `gold` | `goldInk` `#8F5F00` |
| unselected | `textMuted` | `textMuted` `#56616D` |

Same tone-vs-hairline rule as §1.4 — the bar is not a special case.

**Icons.** Origin glyphs at 24dp: `pie` (Portfolio, `icons.tsx:403`), `workbench` (`:23`), `assets` (`:33`), `people` (`:40`). All four exist verbatim in the web's own nav set. This is precisely *"completely integrate the way the webapp works"*: the phone's tabs become the web's nav glyphs, and the mush problem disappears because `workbench` is three dots-on-lines and `pie` is two arcs — different silhouettes at a glance.

**Labels: keep, always shown.** New `BtTypography.labelNav` = 11sp Medium, tracking 0. Dropping labels is the exact Trade Republic failure mode the owner named — *"too simplified… annoying."* 11sp buys the 64dp height without losing the word.

**Badges.** Keep the 10dp dot and the predicate API (`hasBadge`, `AppShell.kt:606-611` — its KDoc is right that a count the component can't render would be an invitation to get it wrong). One required change: the ring at `BtCountBadge.kt:130` is `bt.surface` and must become `navBar`, or the dot gets a visible halo the moment the bar has its own tone. The (+5,−3) glyph-corner offset (`AppShell.kt:637-639`) stays.

**Metrics summary:** 64dp container · 56×32 pill · 24dp icons at 1.6 stroke · 6dp icon-label gap · 11sp labels · no divider in dark, 1dp in light · 48dp minimum touch target (unchanged, `ShortNavigationBarItem` enforces it).

### 6.3 Swipe-pager interplay (Batch 1 seam)

There is **no `HorizontalPager` in the tree today** — Batch 1 introduces it. The bar must be built to accept it, because a bar that snaps while the content slides is worse than a bar that never moves.

Design:

1. `isSelected: (TabSpec) -> Boolean` (`AppShell.kt:616`) becomes `selectionFraction: (TabSpec) -> Float`, derived by the shell from `pagerState.currentPage + pagerState.currentPageOffsetFraction`.
2. **The indicator moves to a single layer behind the item row**, positioned by that fraction, so it *translates* continuously between neighbours instead of cross-fading. `ShortNavigationBarItem`'s per-item indicator is disabled (`indicatorColor = Color.Transparent`) — you cannot get continuous travel out of N independent indicators.
3. Per-item icon and label colour `lerp(textMuted, gold, fraction)`.
4. **Settled state remains nav-graph-driven.** The pager owns the accent in-flight; `currentDestination.hierarchy` (`AppShell.kt:489`) owns it at rest. Two writers, one arbiter: the pager wins while `isScrollInProgress`, the graph wins otherwise. Write this down in the KDoc — it is the kind of thing that gets "simplified" into a bug.
5. Non-top-level routes keep hiding the bar (`AppShell.kt:485`); the pager only spans the four tab roots.

**If Batch 1 lands first,** Batch 2 consumes its `pagerState`. **If Batch 2 lands first,** ship `selectionFraction` returning `0f`/`1f` from the boolean — the indicator layer is already in place and Batch 1 becomes a one-line change at the call site.

---

## 7. Work split

Three builder-sized packages. Each leaves the app installable, logged-in-capable, and visually coherent.

### Package B2-A — Theme infrastructure

**Scope:** §1.6 steps 1-3, plus §1.5 B1/B2/B4/B5/B6/B7/B11.
**Not in scope:** any screen file outside `ui/components/` and `ui/charts/`; the bottom bar; icons; copy.

**Deliverables**
- `BtColors.kt` restructured: field-only data class + `BtDarkColors` / `BtLightColors` + `isLight`.
- `BetterTrackTheme(mode)` + `materialSchemeFrom(bt, dark)`.
- `BtThemeMode` + `themeMode`/`themeModeNow()`/`trueBlack` in `DevicePrefs`.
- `MainActivity` system-bar styles follow the mode; `values-night/themes.xml` + `values-night/colors.xml`.
- All 70 alpha washes → named tokens; all 13 literals → tokens.
- Chart tokens wired into `BtAreaChart`/`BtPriceChart`/`BtStepLineChart`/`BtDonutChart`.
- **Gallery gains a theme toggle** in its own header (debug-only, `GalleryScreen.kt` — reachable via wordmark long-press and Settings → Developer, `SettingsScreen.kt:550-552`).
- **Two new JVM tests**, following the existing source-reading guard tradition (`TopBarNavigationTest.kt`, `StringParityTest.kt`):
  - `BtThemeDisciplineTest` — no `Color(0x` and no `bt.<brand>.copy(alpha` under `ui/` outside `ui/theme/`; explicit exemption for `BtQrCode.kt:52`.
  - `BtContrastTest` — a direct port of `apps/web/src/styles/origin.test.ts` (WCAG relative luminance over both token tables): `textMuted`/`textFaint` ≥ 4.5:1 on all five surfaces in **both** modes; `goldInk`/`gain`/`loss` ≥ 4.5:1 on `bg` and `surface`; `gold` fill ≥ 4.5:1 against `onGold`.

**Gate:** existing suite green (2287 today) + the 2 new tests; gallery screenshot matrix (16 sections × 2 themes = 32 shots) inspected; dark mode visually indistinguishable from `c60f921` except for the intended neutral-ramp retune; **light mode not user-reachable.**

### Package B2-B — Screen migration + bottom bar

**Scope:** §1.6 steps 4, 6, 7 + §6 + §2 A1/A2/A3.
**Depends on:** B2-A merged.

**Deliverables**
- Component sweep with the tone-vs-hairline rule: `BtGroup` (`groupBorder`), `BtCards` (border dark→none), `BtChip`/`BtBadge` (the soft-ink asymmetry), `BtStates`, `BtSkeleton`, `BtCollapsingHeader`, `BtTextField`, `BtButtons`, `BtSnackbar`.
- Bottom bar → `ShortNavigationBar` per §6.2, incl. `selectionFraction` and the single indicator layer.
- `BtTypography.labelNav`.
- Settings → Appearance; light mode **goes public here**.
- Screen sweep in traffic order across ~138 UI files.

**Gate:** full light-mode screenshot matrix (§7.2); the `TopBarNavigationTest` structural guards still green (the gear-on-every-tab and no-page-overflow invariants must survive the bar rewrite); no regression in `BtNavMotionTest`.

### Package B2-C — Icons, colour semantics, copy

**Scope:** §3 + §4 + §5.
**Depends on:** B2-A (tokens). Independent of B2-B — **can run in parallel in a separate worktree**, since it touches `ui/settings/`, `ui/social/`, `ui/chat/`, `ui/market/`, `res/drawable/`, `res/values*/strings.xml` and B2-B touches `ui/shell/` + `ui/components/`. The one collision is `BtCollapsingHeader.kt` (B2-B: hairline rule; B2-C: kind chip) — **assign that file to B2-B and have B2-C hand over a patch**, exactly the collision rule R1 used (`docs/R1_SPEC.md §5.4`).

**Deliverables**
- 16 `ic_bt_avatar_*.xml` + `BtProfileIcon.kt` + `defaultProfileIconIdFor` (with the JS-parity vector test).
- `BtAvatar` rewritten; 18 call sites pass `iconId`; `Friend`/`FriendRequest` carry `profileIcon`.
- `profileIconVector` deleted; picker rebuilt; per-id `contentDescription` strings (EN+DE).
- `BtIcons` object (6 kind glyphs + ~20 chrome glyphs); bar + header + chips adopt them.
- `BtPortfolioChip` + kind store in Room `meta` + Portfolio-settings "Icon" section.
- `rangeAccent` + the accent-rail treatment on movers/holdings/watchlist; asset chart line colour.
- Copy trim table reviewed, then applied EN+DE.

**Gate:** `StringParityTest` + `BtErrorCopyTest` green; avatar parity spot-check (same 5 usernames rendered on phone and on the dev web origin, side by side); copy-trim table signed off **before** edits land.

### 7.1 Device-verify strategy

Per `OPUS_COORDINATOR_HANDOFF.md:50-55` — Samsung Note20 Ultra `R5CN80ABXBK`, USB, `svc power stayon usb` while working and **`false` at session end**. Screenshot policy per `:99`: full-res PNG as evidence, inspect a `sips --resampleWidth 540` copy.

Two forcing functions specific to this batch:
- `adb shell "cmd uimode night yes|no"` toggles the system theme without touching app state — verifies **System** mode live.
- The in-app setting verifies **Light** and **Dark** overrides against a dark and a light system, i.e. all four combinations.

### 7.2 Light-mode screenshot matrix

**Anchor: the gallery.** `GalleryScreen.kt:125-140` renders 16 sections covering wordmark, money, collapsing header, groups, home cards, allocation bar, stat card, list card, buttons, chips/badges, skeleton, empty, offline, error, offline banner. That is the entire shared component system in one scroll. **32 shots** (16 × 2 themes) catch component-level defects before a single screen is touched — this is why the gallery toggle is in B2-A, not B2-C.

**Then, per theme (× 2):**

| Group | Screens |
|---|---|
| Tabs (4) | Portfolio-Overview, Portfolio-selected, Workbench, Markets, People |
| Money surfaces | hero chart at a **gain** range and a **loss** range; asset page both ways; donut; holding detail; transactions |
| States | skeleton, empty, error, offline banner, snackbar |
| Chrome | bottom bar selected/unselected/with-badge; collapsing header expanded + collapsed; switcher sheet |
| Overlays | a dialog, a bottom sheet, the profile-icon picker, a destructive confirm |
| Text-heavy | Settings root, Security, storage wizard step, tax year report |
| System | status/nav bar in all four theme×system combinations |

≈ 30 per theme, 60 total, plus the 32 gallery = **~92**. Comparable to the 54-shot round already done for `e8aab04`, so this is a known-cost exercise.

**Two automatable checks worth the setup:** the `BtContrastTest` unit test (free, runs every build) and a downscaled dark-vs-light diff of the same screen — if the *layout* differs at all between themes, something branched on `isLight` that shouldn't have.

### 7.3 Fable rubber-stamp vs owner-preview

Per the #66 ack (PLATFORM_ASKS.md:729): *"screenshot rounds are no longer a gate — treat them as optional second opinions on request."* So Fable **reviews artefacts, not rounds**:

**Fable rubber-stamps (send as a table/diff, ask for a yes/no):**
- The §1.4 token tables — because the web's white mode (#68 item 2) should adopt them, and because **the Android light values are corrections to `origin.css`'s own light block** (gold ink, pos/neg AA). This is directly useful to her, not a courtesy.
- The icon-sharing ask (§8 #1) — she owns whether Origin becomes a published cross-client asset.
- The chart-palette light counterparts, since `palette.ts` is platform-owned.

**Owner previews on device (he reviews live — do not ask for approval on a spec, show him a phone):**
- The bottom bar. He named it; he decides.
- Light mode, on his own account, on his own data.
- The copy-trim table before it lands — *he* is the one who said overexplaining hurts, so he is the arbiter of what counts as over.
- The portfolio kind chip in the header (the "more colour" call made visible).

**Neither gates:** the internal migration order, the guard tests, the component sweep.

---

## 8. Platform asks for the next PLATFORM_ASKS entry (#69)

1. **Publish the Origin icon set as a shared asset.** `apps/web/src/ui/origin/icons.tsx` is in-house (line 6) and both clients now draw from it. Without a shared source the two drift the moment either adds a glyph. A generated `packages/icons` (raw path data, framework-free) would let the app codegen `BtIcons` instead of hand-porting.
2. **`kind` on the portfolio row.** `portfolioKinds.ts:20-31` documents the gap and the graduation path itself. Today it is per-browser `localStorage`, so a portfolio the owner marks "Business" on the web shows as "Private" on the phone. The app will ship a local store and adopt the field the day it exists, with no call-site change — the web's public surface (`PORTFOLIO_KINDS`, `portfolioIconName`, `portfolioIconTint`) is already API-shaped.
3. **The web's light block needs two fixes before white mode ships:** `--bt-gold` (#f6b82e) is used as `color:` in 20+ rules and is **1.78:1 on white**; `--bt-pos` (#168861) is **4.44:1 on white**, just short of AA. The Android tables in §1.4 carry validated replacements (`#8F5F00`, `#0F7A55`, `#B23A4E`) — take them.
4. **No runtime theme switch exists on the web yet** — `data-bt-theme` is set by nothing (`git grep` finds only the CSS, its test, and `tagChipColor.ts`). Confirming that is the *whole* remaining scope of #68 item 2 is worth a line, because the token work is already done.

---

### Critical Files for Implementation

- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/theme/BtColors.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/theme/BetterTrackTheme.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/shell/AppShell.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/components/BtAvatar.kt`
- `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App/app/src/main/java/at/bettertrack/app/ui/settings/SettingsScreen.kt`

Supporting, in order of edit volume: `ui/components/BtGroup.kt`, `ui/components/BtCards.kt`, `ui/components/BtChip.kt`, `ui/components/BtCollapsingHeader.kt`, `ui/charts/BtDonutChart.kt`, `ui/portfolio/PortfolioOverviewScreen.kt`, `data/prefs/DevicePrefs.kt`, `MainActivity.kt`, `res/values/strings.xml` + `res/values-de/strings.xml`.

---

COORDINATOR SEQUENCING: B2-A starts after owner Batch 1 (pager/charts) merges; B2-B after B2-A; B2-C parallel to B2-B in a worktree with the BtCollapsingHeader hand-over rule.
