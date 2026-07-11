# Google Play listing — asset & decision checklist (BetterTrack App)

Everything the **owner (Christian)** must provide, decide, or verify before the
BetterTrack Android app can be submitted to Google Play. Drafts are provided for
copy so you can edit rather than start blank. Pairs with **Step 20** in
`docs/TODO.md` (the publishing gate) and the platform-side items in
`PLATFORM_ASKS.md`.

- Package: `at.bettertrack.app`  ·  Category: **Finance**  ·  Content rating: expected **Everyone**
- Languages: **English (default)** + **German** — provide store copy in both.
- Ads: **none**  ·  In-app purchases: **none**  ·  Trades/money movement: **none** (tracker only)

---

## 1. Owner action items (what only you can do)

| # | Item | Notes |
|---|------|-------|
| 1 | **Play Console account + identity verification** | Personal accounts created after 2023-11-13 need the **closed-test / 12-tester / 14-day** gate — start it EARLY, in parallel (see Step 20). Complete identity/address verification. |
| 2 | **Upload keystore** | Generate the real release upload key and enrol in **Play App Signing**. The app's signing config auto-uses `~/.bettertrack/bt-dev-signing.keystore` (or `BT_KEYSTORE*` env) when present, else the debug key — replace with the true upload key for release. NEVER commit it (repo is public). |
| 3 | **Contact email** | Decide the public support email shown on the listing. Default suggestion: a `support@bettertrack.at` alias (cleaner than a personal Gmail). Required by Play. |
| 4 | **Privacy policy URL** | `https://bettertrack.at/privacy/` (live). Platform still owes a pre-submit completeness pass to name the FCM token + link the web deletion path (`PLATFORM_ASKS` § OPEN P2). |
| 5 | **Icon final check** | The adaptive launcher icon (`BT_AppIcon`) is in the app. Play also needs a **512×512 32-bit PNG** hi-res icon (with the BT mark, safe padding, no rounded corners baked in). Confirm it matches the in-app icon. |
| 6 | **Feature graphic** | **1024×500 PNG/JPG** (no alpha). Suggested: BetterTrack wordmark (Better = white, Track = gold `#F6B82E`) on the near-black `#0b0e14` page bg, with a subtle gold area-chart motif. No screenshots-of-text (Play rejects busy feature graphics). |
| 7 | **Screenshots** | ≥2 required; **8 recommended** — see §4 for the exact suggested screens (capture in BOTH languages if you want localized listings). |
| 8 | **Approve store copy** | Edit/approve the EN + DE short + full descriptions in §3. |
| 9 | **Content rating questionnaire** | Submit the IARC questionnaire; draft answers in §5. |
| 10 | **Data safety form** | Complete per the draft in §6 — it must match the privacy policy. |
| 11 | **Financial-features declaration** | Declare: portfolio tracker, does **not** execute trades or move money (no money-transmitter license implied). |

---

## 2. Store listing basics

- **App name:** BetterTrack (≤30 chars) — confirm exact casing/edition wording.
- **Category:** Finance.
- **Tags:** portfolio, investing, stocks, net worth, tracker.
- **Website:** `https://bettertrack.at`
- **Privacy policy:** `https://bettertrack.at/privacy/`
- **Account deletion (Play-required, both live):**
  - In-app: Settings → Account → Delete account (`DELETE /api/v1/account`).
  - Web: `https://web.bettertrack.at/account/delete`.

---

## 3. Description drafts (edit freely)

### English — short (≤80 chars)
> Track your whole portfolio — stocks, funds, cash and custom assets — in one place.

### English — full (≤4000 chars)
> BetterTrack is the official companion app for your BetterTrack account — a clean,
> fast, private way to see your entire net worth in one place.
>
> • Portfolios & net worth — every holding with live prices, profit/loss, weights and an elegant value-over-time chart.
> • Transactions — record buys and sells in a couple of taps, with pay-from-cash and add-to-cash handled for you. Works offline: entries queue and sync automatically when you're back online.
> • Cash — multiple named cash sources with deposits, withdrawals and transfers.
> • Custom assets — track anything (property, collectibles, private holdings) with your own value history.
> • Search & asset pages — find any asset, see its price history and key stats, and buy or sell straight into a portfolio.
> • Watchlists & conglomerates — build weighted baskets, backtest their past performance and turn a budget into a buy list.
> • Friends & sharing — share a portfolio, watchlist or conglomerate with specific friends, all friends, or a revocable public link — you're always in control.
> • Chat — message friends and share ideas; sharing never widens who can see your data.
> • Price alerts & notifications — get told when an asset crosses your threshold.
> • Private by design — app lock with PIN or fingerprint, and a screen that stays blank in the app switcher.
>
> BetterTrack does not execute trades or move money — it's a private tracker for your own numbers. Requires a BetterTrack account. English and German, euro formatting throughout.

### German — short (≤80 chars)
> Behalte dein ganzes Portfolio im Blick — Aktien, Fonds, Bargeld und eigene Werte.

### German — full (≤4000 chars)
> BetterTrack ist die offizielle Begleit-App für dein BetterTrack-Konto — klar,
> schnell und privat siehst du dein gesamtes Vermögen an einem Ort.
>
> • Portfolios & Vermögen — jede Position mit Live-Kursen, Gewinn/Verlust, Gewichtung und einem eleganten Wertverlauf-Chart.
> • Transaktionen — Käufe und Verkäufe mit wenigen Tipps erfassen, inklusive Zahlung aus Bargeld. Funktioniert offline: Einträge werden vorgemerkt und synchronisieren automatisch, sobald du wieder online bist.
> • Bargeld — mehrere benannte Bargeldquellen mit Ein-, Auszahlungen und Überträgen.
> • Eigene Werte — verfolge alles (Immobilien, Sammlerstücke, private Beteiligungen) mit deinem eigenen Wertverlauf.
> • Suche & Asset-Seiten — jeden Wert finden, Kursverlauf und Kennzahlen sehen und direkt ins Portfolio kaufen oder verkaufen.
> • Watchlists & Konglomerate — gewichtete Körbe bauen, ihre Wertentwicklung rückwirkend testen und aus einem Budget eine Kaufliste machen.
> • Freunde & Teilen — Portfolio, Watchlist oder Konglomerat mit einzelnen Freunden, allen Freunden oder einem widerrufbaren öffentlichen Link teilen — du behältst die Kontrolle.
> • Chat — mit Freunden schreiben und Ideen teilen; Teilen erweitert nie, wer deine Daten sieht.
> • Kursalarme & Benachrichtigungen — werde informiert, wenn ein Wert deine Schwelle überschreitet.
> • Privat by design — App-Sperre mit PIN oder Fingerabdruck und ein im App-Wechsler leerer Bildschirm.
>
> BetterTrack führt keine Trades aus und bewegt kein Geld — es ist ein privater Tracker für deine eigenen Zahlen. Ein BetterTrack-Konto ist erforderlich. Englisch und Deutsch, durchgehend Euro-Formatierung.

---

## 4. Suggested 8 phone screenshots (capture EN + DE)

Portrait, real device (Note20 Ultra is fine), app-lock OFF, on a demo/throwaway
portfolio with tidy numbers (not the real account). Order matters — the first two
carry the listing.

1. **Portfolio overview** — net-worth hero + gold value chart + holdings. (The money shot.)
2. **Asset page** — price chart with range chips + key stats + Buy/Sell.
3. **Transaction form** — the Buy/Sell form (show the clean cash-after preview).
4. **Cash screen** — sources + a transfer preview.
5. **Watchlist / conglomerate builder** — weighted basket at 100 %.
6. **Conglomerate past-performance backtest** — the return/CAGR/drawdown card.
7. **Social / sharing** — the audience picker (private / friends / public link).
8. **App lock** — the PIN unlock screen (privacy selling point) OR the alerts list.

Optional 9th: **notifications inbox** with an alert.triggered row.

---

## 5. Content rating (IARC) — draft answers

- Violence, sexual content, profanity, controlled substances, gambling: **No** to all.
- User-generated content / social features: **Yes** — 1:1 friend chat + sharing.
  (Declare it; there are no public feeds/forums. No user-to-stranger discovery.)
- Does the app share the user's location: **No**.
- Digital purchases: **No**.
- Expected result: **Everyone** (PEGI 3 / ESRB Everyone). Re-answer honestly in the
  live questionnaire — the chat/social answer may nudge some regional ratings.

---

## 6. Data safety form — draft (must match the privacy policy)

**Does the app collect or share user data?** Yes (collect), **no sharing** with third parties.
**Is all data encrypted in transit?** Yes (HTTPS/TLS). **Can users request deletion?** Yes — in-app + web URL.

| Data type | Collected | Purpose | Shared | Optional? |
|-----------|-----------|---------|--------|-----------|
| **Account info** — username, email | Yes | App functionality, account management | No | Required (account-based app) |
| **Financial info** — portfolio holdings, values, transactions, cash | Yes | App functionality (the core tracker) | No | Required |
| **Device/other IDs** — FCM push token | Yes | Push notifications (alerts, friend requests, chat) | No | Optional (only if notifications enabled) |
| **Messages** — friend chat content | Yes | App functionality (chat) | No | Optional (only if chat used) |

**Not collected:** location, contacts, photos/media, calendar, SMS/call logs,
health, browsing history, precise identifiers/advertising ID. **No ads. No
third-party analytics or crash SDK** (none is bundled — verified: no Sentry/
Crashlytics/analytics dependency).

**Permissions declared (justify each in the listing if asked):**
- `INTERNET`, `ACCESS_NETWORK_STATE` — talk to the BetterTrack API, detect offline.
- `POST_NOTIFICATIONS` (Android 13+) — asked in context, never on first launch.
- `USE_BIOMETRIC` — optional fingerprint/face app-lock (local only; no biometric data leaves the device).
- No location, contacts, SMS, storage, or other sensitive/restricted permissions → keeps the Data-safety form small and avoids the sensitive-permissions declaration + video review.

---

## 7. Cross-checks before you hit submit

- [ ] Signed **.aab** (not APK) uploaded, R8 + resource shrink on, version code/name bumped (CI `-PbtVersionCode/-PbtVersionName`).
- [ ] Target API level meets Play's current minimum (app targets SDK 36 — re-verify Play's rolling policy at submit).
- [ ] Data-safety answers match the live privacy policy word-for-word (deletion path, FCM token named).
- [ ] Account deletion works against LIVE endpoints (in-app + web) on a throwaway account.
- [ ] Screenshots are from a demo portfolio, not the real account; no visible PIN/password.
- [ ] Closed-testing track running with ≥12 testers if the account is gated (start early!).

*Drafted in the Step-19 release-prep pass. Copy is editable — treat as a starting point, not final marketing.*
