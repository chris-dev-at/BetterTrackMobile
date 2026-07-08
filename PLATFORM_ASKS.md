# Platform asks — what the BetterTrack **mobile app** needs from the **main platform**

Single source of truth for everything the **mobile app** (Android, this repo) needs the **main BetterTrack platform** (api / web) to build or change so features can go from *"UI built + stubbed"* to *"fully live."*

**Maintained by:** the mobile-app build (the coordinator working the Android app in this repo).
**For:** the main platform dev — this is your checklist of what the app is waiting on.

### How this file works
- **§ OPEN** = still needed. Each item says *what's needed*, *why* (which app feature it unblocks), and the concrete endpoint/scope shape where known.
- When you ship something, **move it to § DONE** — check it off with a date. Nothing is deleted (audit trail).
- The mobile side keeps appending here as more of the app is built. The app is designed so each item is a **thin adapter swap** (stub → real) with **no UI rework**.
- **Priorities:** **P1** = blocks a core feature from working live · **P2** = unlocks a built-but-stubbed feature · **P3** = polish / cosmetic / optional.
- **OAuth client to grant scopes to:** `BetterTrackMobile` (first-party, public/PKCE), client id `btc_IbT1mzw_7kBiPHPkGfaE0Q`. Currently granted: `portfolio:read portfolio:write workboard:read workboard:write market:read social:read`.

---

## ⭐ Current top priorities (start here — biggest unblock first)
1. **Bearer access to `GET /auth/me`** — unlocks BOTH the real username/email display in the app AND the `pinEnabled` flag needed for the "use my BetterTrack PIN" lock. (Today it's session-cookie-only → 403 under the app's bearer.)
2. **Bearer access to `POST /auth/pin/verify`** — lets the app verify a typed PIN against the account for the "use my BetterTrack PIN" app-lock. (Same session-only concern; being probed live from the app now.)
3. **`social:write` scope** for the BetterTrackMobile client — turns friend add/accept/decline/unfriend live and unblocks the Social v2 redesign.
4. **FCM device-token endpoints + server push-send** — real push notifications with the app closed.
5. **Idempotency key on transaction/cash mutations** — lets the app drop its `[bt:<uuid>]` note-marker exactly-once hack.
6. **Play-Store publishing blockers** (web account-deletion page + privacy-policy URL) — needed before store submission (Step 20).

*(1 and 2 are the immediate ones — they may be the same one change: "accept the mobile OAuth bearer on `/auth/me` and `/auth/pin/*`, with the right scope." Full details for every item below.)*

---

## § OPEN — needed from the main dev

### Auth & identity
- [ ] **P1 — Bearer coverage for user identity** (`/auth/me` under bearer, or a dedicated `/me`). Today `/auth/*` is session-cookie-only, so under an OAuth bearer the app can't read the logged-in username/email → Settings → Account shows "—". Want a bearer-callable endpoint returning `{ username, email, displayName, … }`.
- [ ] **P2 — Bearer-accessible token/grant revocation.** `/oauth/*` and `/settings/oauth-grants` are session-only, so app logout is **local-wipe-only** — the server-side token/grant isn't revoked from the app. Want a bearer-callable "revoke this token/grant."

- [ ] **P1 — Web-PIN status + verification under bearer** (owner wants the app-lock's "Use my BetterTrack PIN" option to reuse the EXACT web login PIN, verified). Two small bearer endpoints:
  - (a) **`GET /auth/pin-status`** → `{ "hasPin": bool }` — does the authenticated account have a web login PIN set? The app only OFFERS the "Use my BetterTrack PIN" option when `hasPin` is true; otherwise it shows device-PIN-only. (Could also be a field on a bearer `/auth/me`.)
  - (b) **`POST /auth/verify-pin { pin }`** → `200` if it matches the account's web PIN, else `401`. The app verifies the entered PIN really is the web PIN before activating it as the app-lock, and stores only a local Keystore **hash** (never the PIN itself). The app never sees the PIN during OAuth (it's typed in the web Custom Tab), so it needs this to validate.
  - Please **RATE-LIMIT** `verify-pin` (a 4-digit PIN is brute-forceable). A "pin changed" signal (or re-verify on token refresh) lets the app prompt a re-enter.
  - Until BOTH exist, the app **hides** the BetterTrack-PIN option and offers device-only PINs.

### Scopes for the BetterTrackMobile client
- [ ] **P1 — `social:write`.** Friends UI is fully built (add / accept / decline / cancel / unfriend) but can't perform writes (only `social:read` granted). Grant `social:write` → friend-graph mutations go live.
- [ ] **P2 — `chat:read` + `chat:write`** (pairs with Chat endpoints below).
- [ ] **P2 — notification scope(s)** guarding `/notifications` read + settings write under bearer (pairs with Notifications below).
- [ ] **P2/P3 — account/security scope(s)** for the Settings actions under bearer (password change, 2FA, sessions — below).

### Social / sharing
- [ ] **P2 — "Specific friends" per-item sharing (per-friend ACL).** Platform models only `private ↔ friends (all)`. The app's audience picker has a third tier "specific friends" → needs a per-friend share ACL on portfolios / watchlists / conglomerates.
- [ ] **P2 — Public share links (revocable token).** The app's audience picker has a "public link" tier (blocking acknowledgment + Android share-sheet). Needs a revocable public-link token + a public read route. App mirrors the web's planned `/{kind}/shared/{token}` shape.

- [ ] **P3 — Friend-activity event feed + notifications** (powers the Social-v2 per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item," the platform needs to emit friend-activity events on shared items the user follows and deliver them via the notification channel. The app builds the per-item notification-preference UI now; the actual alerts light up on this. Nice-to-have.

### Chat (spec §6.10 — app UI built, 100% stubbed)
- [ ] **P2 — Chat endpoints:** conversations list, message thread (cursor-paged), send message, and **share-in-chat resolution** (an item chip resolves only if the recipient is allowed → else a "not shared with you" state). No groups / reactions / read-receipts required.
- [ ] **P2 — Realtime:** a `chat.message` room on the existing WS gateway (app has a polling fallback) + unread counts.
- [ ] scopes: see `chat:read` / `chat:write` above.

### Notifications (spec §6.11 — app UI built, delivery stubbed)
- [ ] **P2 — Bearer read on `/notifications`** for the in-app inbox list (if `social:read` doesn't already cover it, add coverage / a scope).
- [ ] **P2 — Notification settings under bearer** — persist the per-type × per-channel (in-app / email / push / muted) matrix the app renders.
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account. (The app already obtains a real FCM token from Firebase project `bettertrackapp-c6996`.)
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**kept server-side only — the mobile repo never holds it**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency / client-reference field (they `additionalProperties:false`). The app currently forces exactly-once by embedding a ` [bt:<uuid>]` marker in the transaction `note` and reconciling on it. Add an idempotency key → the app drops the note-marker hack.
- [ ] **P2 — Custom-asset LIST endpoint.** No endpoint lists a user's custom assets; the app infers identities from holdings + a local cache, so a custom asset with **no** holding is invisible. A `GET /custom-assets` (list) fixes this.
- [ ] **P2 — Named watchlists.** The app's multi-watchlist UI is built, but the platform exposes only one unnamed workboard list (`/workboard`). Need create / rename / delete + add / remove for multiple **named** lists → multi-list goes live (today extra lists are a debug stub).

### Settings — account & security (spec §6.12 — app UI built, calls stubbed)
- [ ] **P2 — Change password under bearer** (or a clear "manage on web" deep link the app can rely on).
- [ ] **P1 — Delete account under bearer** (type-to-confirm flow is built app-side). **Play publishing blocker** — see the Release section below.
- [ ] **P2 — 2FA management under bearer:** TOTP enroll (QR/secret) + verify, email codes, recovery codes, disable.
- [ ] **P2 — Active sessions under bearer:** list sessions + revoke one / revoke-all-others.

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P1 — Self-service account deletion via a PUBLIC WEB URL** (in addition to the in-app path above). Google Play mandates account deletion be reachable **both** in-app **and** via a web resource, and that it deletes the user's data. Needs a web-facing deletion page/flow on the site. *(The single hardest publishing blocker — schedule deliberately.)*
- [ ] **P1 — Public privacy-policy page** at a stable URL (e.g. `https://bettertrack.at/privacy`): data collected, use, sharing, retention, and the deletion path. Required to complete the Play **Data Safety** form and shown in the store listing.

### Lower priority / cosmetic
- [ ] **P3 — Fix `openapi.json` per-route `security` metadata.** It declares `sessionCookie`-only on every route, which is misleading — the runtime middleware correctly accepts the OAuth bearer on module routes. Cosmetic docs bug; the app trusts the middleware, not the spec.
- [ ] **P3 — Portfolio hard-DELETE** (optional). Only archive exists; the app treats archive as removal. Fine as-is; noted for completeness.
- [ ] **P3 — Portfolio history 1D / 1W / 3M windows** (optional parity). Portfolio history serves `1M / 6M / 1Y / MAX` only (asset history already has the full `1D–MAX` set). The web ships the same subset, so low priority.

### Not a platform code change — reminders for Christian
- [ ] **Extend the BetterTrackMobile OAuth client registration** to include each new scope above as it ships.
- [ ] **A second test account** — for the full social + chat two-account live loop (request → accept → share → chat phone↔web).

---

## § DONE — asked and implemented by the main dev
*(items move here, checked + dated, when shipped — kept for audit; nothing deleted)*

- [x] **Cash sources + transfers** — `GET /cash/sources`, create / rename / relabel / archive / restore, `POST /cash/transfer` (atomic paired legs), `POST /cash/preview`; `GET /cash` returns `sources[]` + per-movement `sourceId` / `transferId`, and `cashSourceId` accepted on tx create + deposit/withdraw. *Shipped during development; the app's Cash screen (Step 9) went fully live on it — deposit / withdraw / transfer, offline via the queue.* ✅ 2026-07-08

---

*Last updated: 2026-07-08 by the mobile-app coordinator.*
