# BetterTrack App — Complete Build Handoff

You are building the official **BetterTrack Android app**, step by step, inside Android Studio. **This single document contains everything you need:**

- **PART 1 — Working instructions & the step checklist** (immediately below): how you must work, and the 19 build steps.
- **PART 2 — The full product spec** (appended at the bottom, after the `PRODUCT SPEC` divider): the complete description of what BetterTrack is, every feature, the exact visual design, the offline-sync design. It is the product contract. All `§` references in the checklist point into it.

### How you must work

- **One step at a time. Never more.** Complete the current step, report, then **STOP and wait for the user** to tell you to continue. Do not start the next step on your own, even if it seems obvious. The user decides when to proceed.
- The app is built incrementally: **after every step the project must compile and run.** Never leave the app broken at the end of a step. Never mark a step done if it doesn't build.
- **Stay inside the current step's scope.** Don't refactor unrelated code, don't add features from later steps, don't "improve" finished steps unless the user asks. If a later step will need a hook you're touching now, leave a `// TODO(step N)` comment instead of building it early.
- **Before each step, re-read the spec sections referenced by that step** — they contain the details (exact behaviors, colors, endpoints, rules) that the checklist deliberately doesn't repeat.
- The API reference is the OpenAPI page at `/docs` on the API origin. If an endpoint a step needs doesn't exist or rejects your token (some server-side pieces land in parallel — spec §10), **stub it behind a flag, note it under the step in TODO.md, and tell the user** — never silently drop functionality and never invent endpoints.
- If something is ambiguous, make the smallest reasonable choice, note it in your step report, and move on — save questions for genuinely blocking issues.

### Step report format

At the end of every step, tell the user, briefly:

1. What you built (a few bullets).
2. How to verify it (what to tap/run/look at).
3. Anything stubbed, blocked, or decided along the way.
4. Which step is next — **then wait.**

### RIGHT NOW — your first action

1. This file already lives inside the project — it stays the permanent reference; always read the relevant spec sections from it before a step.
2. The base project skeleton may already exist (the user created it with you before adding this file). Go through **Step 1** below and complete whatever is still missing (build-config fields, package name, docs) rather than recreating what's there.
3. Create `docs/TODO.md` containing the checklist (from "THE CHECKLIST" to the end of Step 19, with its checkboxes). From then on it is your progress tracker: check off `[x]` each finished step and add a one-line completion note under it. Keep it updated at the end of every step.
4. After finishing Step 1 and reporting, **stop and wait for further directions from the user.**

---
## THE CHECKLIST

### [ ] Step 1 — Project skeleton & docs

New Android project: Kotlin, single module to start, minSdk 26+, latest stable target, Jetpack Compose. Package `at.bettertrack.app`. Set up: build-config fields for `API_ORIGIN`, `WEB_ORIGIN`, `OAUTH_CLIENT_ID` (debug ⇒ `http://localhost:3000` / `http://localhost:8090` — use `10.0.2.2` for the emulator; release ⇒ `https://api.bettertrack.at` / `https://web.bettertrack.at`), version catalog for dependencies, `.gitignore`, and `docs/TODO.md` (see "RIGHT NOW" above). App shows a placeholder screen with the app name.
**Done when:** project builds and runs on an emulator; `docs/TODO.md` is saved.

### [ ] Step 2 — Design system (spec §3, all of it)

Implement the brand as a Compose theme + component library: color tokens (§3.3 table, dark-only), typography (system font, weights/tracking per §3.4, tabular digits for money), shapes (6–8px cards, §3.5), and reusable components: `Wordmark` (two-color + "App" edition, §3.2), `MoneyText` (EUR formatting, gain/loss coloring), stat card, list card, primary/secondary buttons, chip/badge, loading skeleton, empty state, error state with retry. Integrate the supplied `BT_AppIcon.png` as adaptive icon (§3.1). Add a hidden debug "gallery" screen rendering every component for visual checking.
**Done when:** gallery screen shows all components in correct brand styling; launcher icon is the BT mark.

### [ ] Step 3 — App shell & navigation

Bottom navigation with 4 tabs: **Portfolio · Assets · Social · Workboard** placeholder screens, top app bar with wordmark, notification-bell slot, and settings entry; navigation graph wired for all future destinations (placeholders); global offline-banner scaffold ("Offline — showing data as of …", static for now); pull-to-refresh scaffold on tab screens.
**Done when:** you can tab around the empty app and it looks like a BetterTrack shell.

### [ ] Step 4 — Login with BetterTrack (spec §4) + API client

OAuth 2.0 Authorization Code + PKCE via Chrome Custom Tab against `{WEB_ORIGIN}/oauth/authorize`, deep-link callback, token exchange at `POST {API_ORIGIN}/api/v1/oauth/token`, encrypted token storage, proactive refresh, logout with server-side revocation + full local wipe. Login screen per brand (wordmark + edition, tagline, single "Login with BetterTrack" button; "Need an account?" / "Forgot password?" open the web). Build the API client layer: bearer header, JSON mapping, error mapping (401→refresh→re-login, 403 `PASSWORD_CHANGE_REQUIRED`→ send to web, disabled account message), auth-gated navigation (logged out ⇒ login screen only).
**Done when:** real login round-trip works against the dev stack; kill/restart keeps the session; logout wipes and returns to login.

### [ ] Step 5 — Local database & sync engine core (spec §7.1, §7.3)

Room database: entities for portfolios, holdings, transactions, cash sources & movements, custom assets & value points, watchlists, conglomerates (read models), plus the **outbound operation queue** (client-UUID key, type, payload, status: pending/in-flight/needs-attention, error). Repository pattern: screens read ONLY from Room; network refreshes Room. Sync worker (WorkManager): connectivity-triggered + foreground-triggered FIFO queue drain with idempotency keys and exponential backoff; refetch-and-reconcile after drain; account-keyed storage wiped on logout/account-switch. Wire the offline banner to real connectivity + data-age. No new visible features this step — include a debug screen showing queue contents and a manual "drain now".
**Done when:** with airplane mode on, a manually-enqueued test op persists across app restarts and drains exactly once when connectivity returns (verify via debug screen + API).

### [ ] Step 6 — Portfolio read: switcher, overview, graph (spec §6.1)

Fetch → Room → UI for real portfolio data: portfolio switcher (list/create/rename/archive per §6.1), the **Portfolio tab overview** — Net Worth/total value, performance %, cash line, holdings list with P/L and weights, allocation donut — and the **history graph** with ranges 1D·1W·1M·3M·1Y·Max in the §3.6 area style. All values from the API, rendered from cache when offline with the as-of label.
**Done when:** your real portfolio renders correctly online; airplane mode ⇒ same screen from cache with offline banner.

### [ ] Step 7 — Transactions & holding detail, read-only (spec §6.2)

Per-portfolio transaction history (filterable by asset/type), holding detail screen (position value, P/L, its transactions, link-out spot for the asset page later).
**Done when:** every existing transaction from the web shows correctly; works offline from cache.

### [ ] Step 8 — Transaction writes + offline queue end-to-end (spec §6.2, §7)

Buy/sell form: asset (from held assets for now), price + amount, date, **pay-from-cash / add-proceeds-to-cash** with live cash-after preview and no silent negatives; edit/delete (online-direct for synced items; queued items just edit the queue); FAB on Portfolio tab (≤2 taps rule). Full offline path: instant pending row + badge, queue drain, reconcile, **needs-attention** flow with edit-and-retry/discard, and the **Pending sync** screen (§7.4) reachable from the offline banner.
**Done when:** a buy recorded in airplane mode shows as pending, syncs on reconnect, and appears on the web; a deliberately-invalid queued op lands in needs-attention with working retry/discard.

### [ ] Step 9 — Cash & cash sources (spec §6.3)

Cash screen per portfolio: Main + named sources with balances and typed labels, movement history per source, **deposit / withdraw / transfer** (source pickers, live previews), create/rename/archive sources. All three movement types work offline via the queue.
**Done when:** an offline transfer between two sources syncs correctly and Net Worth stays consistent with the web.

### [ ] Step 10 — Custom assets (spec §6.4)

Custom asset management: create/edit (name, category), value-point history with step-line chart, edit/delete points, and the **quick "update value now"** action from the holding row. Value points work offline via the queue.
**Done when:** updating a custom asset's value offline lands on the web after sync; step-line renders.

### [ ] Step 11 — Search & asset pages (spec §6.5) — online-only

Global search from the Assets tab (and a search affordance app-wide): 1-character start, debounce, fuzzy server results, "searching providers…" enrichment refetch; result-row direct actions (open asset, quick buy/sell into the selected portfolio, state-aware in-place watchlist icon). **Asset page**: header info, price, §3.6 area chart with ranges 1D·1W·1M·6M·1Y·5Y·Max, key stats, quick buy/sell, watchlist icon-button. Buy/sell from search/asset pages replaces the held-assets-only limitation from Step 8. Clean "requires connection" states offline.
**Done when:** typo'd search finds the right asset; chart ranges all render; buying a brand-new asset from its page works.

### [ ] Step 12 — Watchlists (spec §6.6)

Multiple named watchlists on the Assets tab: create/rename/delete, add/remove assets, quotes + day change per row, default "General", list-picker in every add flow (one-tap default + choose-list). Read-only offline from cache.
**Done when:** two lists behave independently; add-from-search and add-from-asset-page both offer the picker.

### [ ] Step 13 — Conglomerates lite (spec §6.7) — online-only

Workboard tab: conglomerate list (composition summary + current value), **builder** (search-add assets, weight editing validated to 100%, name), **past-performance graph** (single backtest curve, ranges, entry markers — nothing more), **budget calculator** (budget → weighted buy list, "at least 1 share" toggle, remainder display) with **"Add to portfolio"** committing the buy list (with pay-from-cash) in a couple of taps.
**Done when:** create a 3-asset conglomerate, see its past performance, calculate €1,000, commit to a portfolio — transactions appear.

### [ ] Step 14 — Friends & sharing (spec §6.8, §6.9) — online-only

Social tab: friends list, add-by-username/email, incoming vs outgoing requests (accept/decline/cancel, clear visual separation), unfriend. **Sharing**: audience picker on every portfolio/watchlist/conglomerate (private / specific friends / all friends / public link) with the **friction ladder exactly per §6.9** (blocking acknowledgment for public + Android share-sheet for the link); **Shared with me** (read-only friend views); **My shared items** (audit + revoke).
**Done when:** full loop with a second test account: request → accept → share portfolio to specific friend → friend sees it → revoke → friend loses it; public link requires the acknowledgment tick.

### [ ] Step 15 — Friend chat (spec §6.10)

Conversation list with unread badges + message threads with a friend; realtime via the platform gateway where available, polling fallback; **share-in-chat** chips (asset or shareable item; recipient resolves only if allowed — "not shared with you" state otherwise). No groups/reactions/read receipts.
**Done when:** two accounts chat phone↔web; a private-item chip shows the not-shared state; unread counts survive restart.

### [ ] Step 16 — Notifications (spec §6.11)

In-app **inbox** behind the bell (unread badge, mark-read, tap-through deep links) + **FCM push**: Firebase wiring, device-token register/refresh/delete against the platform endpoints, notification channels, deep-link taps, Android 13+ permission asked in context (never on cold first launch). **Notification settings**: the per-type × per-channel matrix (in-app / email / push / muted).
**Done when:** a friend request from the web pops a push with the app closed and deep-links to Social; muting a type in the matrix stops it.

### [ ] Step 17 — App lock (spec §5)

Local 4–6-digit PIN (Keystore-hashed) + BiometricPrompt unlock; triggers on cold start and configurable AFK return (immediately/1/5/15 min, default 1); progressive backoff on wrong PINs + shake feedback (reduced-motion aware); recents-screen content masking; "forgot PIN ⇒ log out" path; setup/change/disable under Settings → Security.
**Done when:** enabled lock gates every entry into the app, fingerprint unlocks, 5 wrong PINs back off, recents shows no data.

### [ ] Step 18 — Settings & account management (spec §6.12)

Full settings: **Account** (username/email display, change password, **delete account** — type-to-confirm, destructive styling), **Security** (2FA: TOTP enroll with QR/secret, email codes, recovery codes, disable; active-sessions list with revoke / revoke-all-others; app-lock config from Step 17), **Notifications** (matrix from Step 16), **Language** (English/German in-app switch), **About** (wordmark + edition, version, tagline, links). Stub-and-note any endpoint not yet live per the master prompt.
**Done when:** password change works end-to-end; sessions list shows the web session and revokes it; language switch flips the UI.

### [ ] Step 19 — Polish, i18n & release prep (spec §6.13)

Full **German localization** audit (every string externalized, EN + DE complete, locale-correct money/date formats), empty/error/loading sweep over every screen, accessibility pass (TalkBack labels, contrast, touch targets), reduced-motion audit, performance sanity (cold start, list scrolling), ProGuard/R8 release build, signing config placeholder, versioning, and a Play-listing asset checklist (icon, screenshots list, description draft) for the user.
**Done when:** release build installs and runs clean in both languages; TODO.md fully checked; a final summary report lists anything still stubbed for the user to resolve.

---

*End of checklist. Remember: after each step — report, then wait for the user.*


---

# ══════════ PRODUCT SPEC ══════════
# (PART 2 — the product contract; the checklist's § references point in here)

# BetterTrack App — Official Android App Specification

You are building the **official BetterTrack Android app** in Android Studio. This document tells you *what* to build: what BetterTrack is, what the app must do, what it must not do, and how it must look. All technical decisions (language, architecture, libraries, navigation, storage) are yours — choose modern Android best practice. Where this spec and the live API disagree, the API's OpenAPI reference (`/docs` on the API origin) is authoritative.

---

## 1. What BetterTrack is

BetterTrack is a **private, invite-only investment and portfolio tracking platform**. It is not a broker — users don't buy real securities through it; they *track* what they own and watch.

What users do with it:

- **Track portfolios** of stocks, ETFs, crypto, commodities, and self-defined **custom assets** (e.g. a watch collection, a private loan) — with full transaction history (buys/sells at price + amount), per-portfolio **cash** (deposits, withdrawals, named cash sources like "Bank account" or "Retirement fund", transfers between them), value and performance over time, and a **Net Worth** view rolling everything up.
- **Watch assets** via **watchlists** (multiple named lists), backed by a fast fuzzy **asset search** and per-asset detail pages with price charts.
- **Build "conglomerates"** — custom index-like compositions of assets with percentage weights — see how the composition would have performed in the past, and use a **budget calculator** ("I have €1,000 — what do I buy to match my weights?") that can commit the resulting buy list straight into a portfolio.
- **Be social, privately**: add **friends**, share portfolios / watchlists / conglomerates with chosen audiences (private / specific friends / all friends / public link), see what friends shared with them, and **chat 1:1** with friends, including sharing assets and shared items in chat.
- **Get notifications** (friend requests, shares, chat messages, price alerts) through an in-app notification inbox, email, and push — each notification type routed per the user's channel preferences.

Platform facts you must respect:

- **API-first**: the entire product is the REST API at `/api/v1` on the API origin. The web app is just the first client; your app is the second. Interactive **OpenAPI documentation lives at `/docs`** on the API origin — that is your endpoint reference. If a capability seems missing from the API, surface it as an open question; do not invent endpoints or work around the API.
- **Base currency is EUR.** All money displays in euros (users may have a different display currency setting later; render what the API returns).
- **Two account kinds exist: `user` and `admin`.** Admins are management-only accounts with no portfolios and no user features. **This app is for `user` accounts only** — there is no admin functionality in the app, ever, and admin accounts cannot log into it.
- **Registration is closed / invite-based.** Accounts are created by an admin or via invite links on the web. **The app has no sign-up flow.** Login only. "Need an account?" and "Forgot password?" link out to the web app in the browser.
- **Origins** (make all base URLs build-time configurable):
  - Production API: `https://api.bettertrack.at`
  - Production web app: `https://web.bettertrack.at`
  - Local development: API `http://localhost:3000`, web `http://localhost:8090`
- English is the default language; **full German localization is required** (the platform is EN/DE; architect strings so more languages are easy later). Language is user-selectable in Settings, defaulting to the device locale when it's EN or DE.

## 2. What this app is (and is not)

The **on-the-go companion** to the desktop web app. A user must be able to run their whole investment life from the phone at the *basic* level: check everything, add and manage transactions and cash, manage watchlists, build a simple conglomerate, run the calculator, manage friends and shares, chat, get notified, and manage their account — all fast, clean, and phone-first. The portfolio is **offline-first**: it lives on the phone, stays fully viewable without a connection, accepts new transactions and cash entries offline, and syncs up automatically when connectivity returns (§7).

It is **not** the desktop analysis workstation. Deep analytics (the Analytics deep-dive page with configurable overlays, comparisons, benchmark stats, inflation modes), tax configuration, CSV broker imports, API-key/OAuth-app management, and all admin surfaces stay on desktop. Don't cram them in; where the web app would go deeper, it's fine to show the basic numbers and stop.

Target: **phones, portrait-first** (tablets may work but are not a design target). Play Store distribution. `mobile.bettertrack.at` will eventually redirect to the store listing.

## 3. Brand & visual design

BetterTrack has an established identity, extracted here from the live web app. Match it exactly — same colors, same construction rules, phone-shaped layouts.

### 3.1 Logo / app icon

The **BT icon** is a supplied asset (`BT_AppIcon.png`, 997×985 RGBA) — do not redraw it, adapt it:

- A **rounded-square near-black tile** (`#0b0e14`-family dark, large corner radius ≈ 22% of width).
- Inside, the letters **"B" in pure white and "T" in gold `#F6B82E`**, set in a very heavy geometric grotesque (letters are part of the supplied artwork — no font matching needed), tightly kerned so the T's crossbar tucks over the B's right side; the pair sits optically centered.
- For Android **adaptive icons**: background layer = the near-black tile color, foreground layer = the white-B + gold-T lettermark with safe-zone padding; provide monochrome layer (white BT) for themed icons.
- The BT glyph may also be used in-app as a small brand mark (e.g. splash screen, about).

### 3.2 Wordmark

The product name is always set as **one word, two colors**:

- **"Better" in white** + **"Track" in gold `#F6B82E`** — no space between them, **bold weight, tight letter-spacing** (tracking-tight).
- Edition label: the word **"App"** after a normal space — **~0.78em** of the wordmark size, **medium** weight, muted gray **`#8A8A8A`** → "**BetterTrack App**". (The web uses "Web", admin uses "Admin"; the native client's reserved edition is "App".)
- All sizing is em-relative, so the same construction works small (top bar) and large (login screen).
- Use wordmark + edition on the **login screen, lock screen, and About**; plain wordmark or the BT glyph elsewhere. Never recolor, outline, or restyle it.
- **Tagline**: `BetterTrack — finances under your control` — under the wordmark on login and About, muted gray, small.

### 3.3 Color tokens (dark-first)

The app is **dark by default**; a light theme is optional and NOT required for v1. Exact values (Tailwind-neutral-based, as the web app uses):

| Role | Value |
|---|---|
| Page background | `#0b0e14` (alt near-black `#0a0a0a`, neutral-950) |
| Card / surface | `#171717` (neutral-900) |
| Border / divider (the dominant separator — borders, not shadows) | `#262626` (neutral-800); stronger `#404040` (neutral-700) |
| Text primary | `#ffffff` / near-white |
| Text secondary | `#a3a3a3` (neutral-400) |
| Text muted / hints / edition | `#8A8A8A` |
| **Brand accent (the ONLY accent)** | **gold `#F6B82E`**; amber tints `#fbbf24` / `#fcd34d` for emphasis; dark amber-tinted surfaces (amber-900/950 equivalents) for highlighted/selected cards |
| Gains / positive | emerald `#34d399` (strong) / `#6ee7b7` (soft) |
| Losses / negative / destructive | red `#f87171` (strong) / `#fca5a5` (soft); dark red-tinted surfaces for destructive confirms |

Gold is reserved for brand and primary actions/selection — don't spread it everywhere. Green/red are reserved for money movement and destructive actions.

### 3.4 Typography

- **No custom typeface.** The web app deliberately runs the system UI sans stack; on Android that means **Roboto / the device system font**. The brand look comes from **weight and spacing**, not a font: bold + tight letter-spacing for the wordmark, screen titles, and big numbers; medium for labels and buttons; regular for body.
- Money and figures are first-class citizens: big bold values, smaller muted labels, and **tabular (monospaced-digit) figures in any column of numbers** so amounts align.

### 3.5 Shape & components

- **Card-based layout**: dark surfaces (`#171717`) with **1px `#262626` borders** and **6–8px corner radius** (the web's rounded-md/rounded-lg) — flat design, borders instead of elevation shadows. Full-round (pill) shape only for badges, chips, and small state buttons.
- Generous spacing, no clutter, no decorative gradients, no glassmorphism. Serious, minimal fintech.
- Icons: a single consistent **thin/outlined icon set** (Material Symbols Outlined is the natural Android choice) — no mixed icon styles, no colorful/filled icon soup.

### 3.6 Charts

- Price/history charts are **area-style line charts** in the TradingView lightweight-charts look (the web literally uses lightweight-charts v5): thin ~2px line, soft vertical gradient fill fading to transparent below, minimal gridlines, muted axis labels. Line color by context: gold for brand/portfolio curves or green/red by period performance.
- Custom assets render as **step-lines** (value points, not continuous prices).
- Allocation is a simple donut/pie with a small legend (the web pairs recharts for these) — flat colors, no 3D, no exploded slices.

### 3.7 Motion

- Subtle and quick: small fades/slides, a brief shake on wrong PIN. Respect the system **reduced-motion** setting for every animation.

## 4. Login — "Login with BetterTrack" (OAuth 2.0)

The app authenticates via OAuth as a registered **first-party client** of the platform. First-party clients skip the consent/scope screen server-side, so to the user this feels like simply logging into BetterTrack.

- **Flow**: OAuth 2.0 **Authorization Code + PKCE (S256)**, public client (no client secret in the app).
  1. Open the authorization URL on the **web origin**: `{WEB_ORIGIN}/oauth/authorize?response_type=code&client_id=…&redirect_uri=…&scope=…&state=…&code_challenge=…&code_challenge_method=S256` in a **Custom Tab** (not an embedded WebView — the user logs in on the real web login, which also handles 2FA challenges, forced password changes, etc. transparently).
  2. The redirect URI is an app deep link (e.g. `bettertrack://oauth/callback` or an `https` App Link — your choice; it must exactly match the client registration).
  3. Exchange the code at `POST {API_ORIGIN}/api/v1/oauth/token`; store access + refresh tokens in **EncryptedSharedPreferences/Keystore**. Refresh proactively; on refresh failure, return to login.
  4. All API calls send `Authorization: Bearer …`. Bearer requests need no CSRF handling.
- **Account confirmation — always ask**: the authorize page never silently continues with whatever account the browser happens to hold. If a web session already exists in the Custom Tab, it asks "You're signed in as **\<username\>** — continue with this account?" with **Continue** (proceeds straight into the app; first-party means no further consent screen) and **Use another account** (sign in as someone else). No session ⇒ the normal login form. This screen is rendered by the web platform, not by the app — the app just must not assume an instant redirect **[platform-prereq: account-chooser step on the authorize page, shown to first-party clients too — auto-approve skips the permission prompt, never the account confirmation]**.
- **Client registration**: the owner registers the app in the admin panel and supplies the `btc_…` client ID as build configuration. Scopes are the platform's coarse per-module scopes (`portfolio:read`, `portfolio:write`, `workboard:read`, `workboard:write`, `market:read`, `social:read`, plus the write/notification/account/chat scopes the platform is adding for the mobile app — request the full set the registration grants).
- **Logout**: revoke the token server-side (revocation endpoint per `/docs`), wipe all local state — tokens, caches, preferences tied to the account — so a second account can log in with zero stale data.
- **2FA**: users may have TOTP or email-code 2FA enabled. Because login happens on the web page inside the Custom Tab, the challenge is handled there — the app needs no 2FA UI at login. (The app *managing* 2FA settings is §6.12; the phone *being* a 2FA device is future, §8.)

## 5. App lock (local PIN + biometrics)

Independent of login, the app has a **local lock** guarding the UI (the WhatsApp model — it gates the screen, not the session):

- Optional **4–6 digit PIN**, set up and stored **locally on the device** (hashed, Keystore-backed). This is separate from the user's web-app PIN.
- **Biometric unlock** (fingerprint / face via BiometricPrompt) as the primary convenience path once a PIN exists; PIN is the fallback.
- Lock triggers: app cold start, and returning from background after a configurable idle time (immediately / 1 / 5 / 15 minutes — default 1 minute). While locked, show only the lock screen (wordmark + PIN pad) — no data, and mask the app in the recents/task switcher.
- Wrong-PIN attempts back off progressively (brief lockouts, e.g. after 5 misses); a short shake animation on a wrong entry (skipped under reduced motion). Forgot PIN ⇒ log out fully and log back in.
- Configured under Settings → Security.

## 6. Features

Everything below exists in the platform API (the few flagged **[platform-prereq]** items are being added server-side alongside this project — build the UI against them per `/docs`). List/read views come first, but this app is explicitly **read AND write**: full basic management from the phone.

### 6.1 Portfolios & Net Worth

- **Multiple portfolios**: list, create, rename, archive/restore; one is the default. A persistent, obvious **portfolio switcher** (the web's header-switcher equivalent) governs the portfolio-scoped screens. Selecting a portfolio must stick across all portfolio-scoped screens until changed.
- **Overview** (the app's home tab): total value, **Net Worth** roll-up (holdings + all cash), performance % over selectable ranges, cash line, holdings list (asset, amount, value, P/L, weight), allocation breakdown (by asset / category), and the **portfolio history graph** with ranges 1D · 1W · 1M · 3M · 1Y · Max. Show what the API computes — no client-side analytics.
- **Holding detail**: tap a holding → its position view (value, P/L, transactions for that asset, jump to the asset page).

### 6.2 Transactions

- Full transaction management: **buy / sell with price + amount entry**, date, per-transaction edit and delete, and a filterable per-portfolio transaction history.
- Cash coupling: "**Pay from cash**" on buys and "**Add proceeds to cash**" on sells, with a live cash-after preview; never allow silently negative cash. The per-portfolio default for this toggle is sticky.
- Adding a transaction must be reachable in ≤2 taps from the overview (FAB or equivalent) and from any asset page.

### 6.3 Cash & cash sources

- Per-portfolio cash with **Main cash** plus named **cash sources** ("Bank account X", "Retirement fund Y"; typed bank/retirement/cash/custom): create, rename, archive; **deposit / withdraw** with a source picker; **transfers** between sources; per-source balance + movement history. Net Worth and the overview roll up all sources.

### 6.4 Custom assets

- Create and manage custom assets (name, user-defined **category**), record **value points** over time (step-line chart), edit/delete points. Updating a custom asset's current value is a first-class quick action — this is a phone-natural "I'm at the dealer, my watch is now worth X" flow.

### 6.5 Asset search & asset pages

- **Search** from anywhere (persistent search affordance): works from 1 character, debounced, fuzzy/typo-tolerant (server-side catalog search; a brief "searching providers…" enrichment state may follow — refetch and merge). Result rows offer direct actions: open asset, quick buy/sell into the selected portfolio, state-aware add-to-watchlist **in place** (shows already-added, re-tap does not error, search stays open).
- **Asset page**: name/symbol/category, price, area chart with ranges 1D · 1W · 1M · 6M · 1Y · 5Y · Max, key stats the API provides, quick buy/sell, watchlist icon-button (state-aware, with a list picker when multiple watchlists exist).

### 6.6 Watchlists

- **Multiple named watchlists** ("General" is the default): create, rename, delete; add/remove assets; each list shows live-ish quotes and day-change. Every add-to-watchlist flow offers the one-tap default plus a list picker.

### 6.7 Conglomerates — the lite version

Basic conglomerate functionality, none of the fancy analysis:

- **List** my conglomerates with composition summary and current value.
- **Builder**: create/edit a conglomerate — search-add assets, set percentage weights (validated to 100%), name it. Keep the editor simple and touch-friendly.
- **Past-performance graph**: the standard backtest chart for the composition over selectable ranges — one curve, entry markers as the API provides them. **No** benchmark comparisons, no rebalancing modes beyond the API default, no side-by-side stats.
- **Budget calculator**: enter a budget → weighted buy list (shares per position, cost, remainder), including the platform's "**at least 1 share**" toggle; **"Add to portfolio"** commits the buy list into a chosen portfolio (with pay-from-cash) in a couple of taps.

### 6.8 Friends

- Friends list; send requests (by username/email), incoming/outgoing request management (accept / decline / cancel), unfriend. Clear visual separation of incoming vs pending sections. **[platform-prereq: social write scope for bearer clients]**

### 6.9 Sharing

- Share **any portfolio, watchlist, or conglomerate** with an audience: **private** (default) / **specific friends** (multi-select) / **all friends** / **public link** (revocable token URL — offer Android share-sheet for the link).
- Replicate the platform's **friction ladder** exactly: a strong explicit-acknowledgment warning before making anything **public** ("anyone with the link can see your holdings and net worth" — cannot submit without ticking the acknowledgment), a light confirm for **all friends**, none for specific friends.
- **Shared with me**: browse portfolios/watchlists/conglomerates friends shared with me (read-only views); **My shared items**: audit and revoke everything I've shared, in one place.

### 6.10 Friend chat

- **1:1 chats with friends**: conversation list with unread badges, message thread, realtime delivery where available (platform gateway) with polling fallback; new-message notifications flow through the notification system (§6.11) and respect the user's channel matrix.
- **Share-in-chat**: send an asset or one of my shareable items as a rich chip. Sending **never widens access** — the recipient sees the content only if its audience already allows them, otherwise a "not shared with you" chip state.
- No groups, reactions, or read receipts.

### 6.11 Notifications

- **In-app inbox** (bell): list of notifications (friend requests, shares, chat, price alerts, system), unread badge, mark-read, tap-through to the relevant screen.
- **Push notifications via FCM**: register/refresh/delete the device token against the platform's device-token endpoints; honor the per-type channel matrix server-side (phone-push is a channel column). Deep-link taps to the right screen. Follow Android 13+ runtime notification permission flow — ask in context (e.g. from notification settings or a gentle post-login prompt), never on first launch cold.
- **Notification settings**: the per-type × per-channel matrix (in-app / email / push / muted) mirroring the web, editable in the app.

### 6.12 Settings & account management

Google Play requires real account management in-app, so this section is complete, not a stub:

- **Account**: username & email display, **change password** (current + new, honoring platform password rules), **delete account** (type-to-confirm, clearly destructive, per Play account-deletion policy) **[platform-prereq: self-service delete endpoint]**.
- **Security**: **2FA management** — enroll TOTP (QR/secret), email-code channel, recovery codes display, disable — mirroring web Settings → Security **[platform-prereq: bearer coverage for security endpoints]**; **active sessions** list with per-session revoke and revoke-all-others; the local **app lock** configuration (§5).
- **Notifications**: the matrix (§6.11) + system notification-permission status.
- **Language**: English / German.
- **About**: wordmark + edition, version, tagline, links (web app, product page).

### 6.13 App basics & quality bar

- **States everywhere**: skeleton loading, empty states with a helpful next action, human-readable error states with retry — no raw error strings, no blank screens.
- **Offline**: the portfolio is offline-first with queued write sync — full spec in §7. Non-portfolio surfaces cache last-fetched data for read-only viewing.
- **Freshness**: refetch on screen focus and pull-to-refresh on every list/overview screen.
- **Be a polite API client**: reasonable poll intervals, no hot loops; the platform rate-limits progressively and your app should never trip it in normal use.
- EUR money formatting per locale (de-AT style for German), green/red change coloring, accessible contrast, TalkBack-sane labels on interactive elements.
- Handle `401` (token refresh → re-login), `403 PASSWORD_CHANGE_REQUIRED` (send the user to the web to complete the change), and disabled-account errors with clear messaging.

## 7. Offline-first portfolio & sync

This is a headline requirement, not a nice-to-have: **the user's portfolio must live on the phone.** Opening the app in a dead zone shows the full portfolio; recording a buy on a plane works; everything reconciles when the connection returns. Scope it exactly as follows — offline-first for the portfolio, online-only for everything else.

### 7.1 Principles

- **Local database as the display source of truth** for all portfolio surfaces (overview, holdings, transactions, cash, custom assets, watchlist *views*, conglomerate *views*). Screens render from the local DB; network syncs the DB. The app never shows a spinner where cached data exists.
- **The server is the only calculator.** Portfolio value, performance %, TWR, history graphs, holdings math all come from the API. **Never recompute these on the phone** — offline shows the last-synced numbers labeled "as of \<time\>", with locally-recorded pending entries listed alongside (clearly marked), *not* folded into the computed totals. Duplicating the platform's domain math client-side is forbidden; it will drift.
- **Writes are an append-only outbound queue of events**, not bidirectional state sync. No CRDTs, no merge engine — portfolio writes are ledger events (a buy, a deposit, a value point), which barely conflict by nature.

### 7.2 What works offline

**Readable offline** (cached snapshots): everything portfolio-scoped listed above, per portfolio, for all the user's portfolios.

**Writable offline** (queued): the ledger-event set —

- transactions: buy / sell (incl. the pay-from-cash flag),
- cash: deposit / withdraw / transfer between sources,
- custom assets: add a value point,
- plus edit/delete of a **not-yet-synced** queued item (which simply mutates the local queue).

**Online-only** (disabled offline with a clear "requires connection" state): everything else — search and asset pages (live market data), portfolio/watchlist/conglomerate create-rename-delete, the conglomerate builder and calculator, all social (friends, sharing, chat — privacy-affecting actions must never fire from a stale queue), notifications, and all of Settings. Editing/deleting an **already-synced** transaction offline is also out of scope for v1 — keep the queue append-only and simple.

### 7.3 Sync mechanics

- Every offline write is stored in the local DB (so it renders immediately as a pending row) and appended to a **durable outbound queue** with a **client-generated UUID** as idempotency key **[platform-prereq: API accepts an idempotency key / client reference on portfolio mutations and dedupes replays]**.
- A background sync worker (WorkManager or equivalent) drains the queue **FIFO** whenever connectivity returns, plus on app foreground and via manual pull-to-sync. Retries use exponential backoff; a crash mid-drain must never double-submit (that's what the idempotency key is for).
- After a successful drain, refetch the affected portfolio state and reconcile the local DB — server truth replaces local projections; confirmed pending rows lose their pending badge.
- **Rejected items** (e.g. a cash-funded buy that no longer covers because the balance changed from the PC) don't block the queue: mark them **"needs attention"** with the server's reason and offer edit-and-retry or discard. Offline validation is best-effort against the cached balance; final validation is the server's.
- The queue is **keyed to the logged-in account**: it survives token refresh and re-login of the same user, and is wiped — along with all cached data — on logout or account switch. Expired sessions never lose queued entries; the user re-authenticates and the drain resumes.

### 7.4 Offline UX

- A persistent, unobtrusive **offline indicator** with data age: "Offline — showing data as of 14:32".
- Pending items carry a small **sync badge** inline wherever they appear; a **Pending sync** screen (reachable from the indicator and Settings) lists queued/failed items with per-item status, retry, and discard.
- Sync is silent when everything succeeds; only "needs attention" items may notify.

## 8. Explicitly OUT (do not build)

- Admin anything (separate account kind; the API 404s admins on user routes anyway).
- Registration / sign-up, invite management.
- The Analytics deep-dive page: configurable multi-overlay graphs, hide/filter-by-category analysis, compare-vs-benchmark/portfolio/conglomerate, inflation modes, contribution tables.
- Conglomerate extras beyond §6.7: comparisons, benchmark stats, rebalanced/alternative backtest modes, nested conglomerates, JSON import/export.
- Tax mode configuration and tax reports (render nothing tax-specific; desktop feature).
- Broker CSV imports / data export, backups.
- API-key and OAuth-app management (web Settings → API Access stays web-only).
- Saved Ideas (not shipped on the platform yet).

## 9. FUTURE — mention in architecture, do not build

Design so these can land later without rework; none of them ships in v1:

- **Home-screen widgets**: portfolio value / net-worth glance widget, watchlist ticker widget.
- **Phone as 2FA device**: approve-login push and/or a built-in TOTP generator for the user's BetterTrack account.
- **Saved Ideas** once the platform ships it.
- Extending offline writes beyond the §7.2 ledger-event set (offline edits of synced transactions, offline watchlist edits, …).

## 10. Working agreement

- `/docs` (OpenAPI, on the API origin) is the endpoint contract; this document is the product contract. The web app at `{WEB_ORIGIN}` is the reference implementation for behavior and tone — when in doubt, do what the web app does, phone-shaped.
- Items tagged **[platform-prereq]** have server-side work landing in parallel (scope expansion, bearer coverage for settings/security/notifications/chat, self-service account deletion, FCM channel + device-token endpoints, idempotency keys on portfolio mutations for offline sync, the account-chooser on the OAuth authorize page). Build the UI and integration against the documented contract; if an endpoint isn't live yet in your test environment, stub behind a flag and list it in your status report rather than silently dropping the feature.
- Test against the local development stack (API `http://localhost:3000`, web `http://localhost:8090`) with a normal user account; production origins are configuration, not code.
