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
1. ✅ **SHIPPED (platform #361, 2026-07-08)** — Bearer `GET /auth/me` returns identity (incl. `pinEnabled` for the PIN lock). See § DONE.
2. ✅ **SHIPPED (platform #361, 2026-07-08)** — Bearer `POST /auth/pin/verify` + `GET /auth/pin/status`, reusing the exact web PIN. See § DONE.
3. **`social:write` scope** for the BetterTrackMobile client — ⚠️ the scope is now **defined + enforced** platform-side (#361), but the client must still be **GRANTED** `social:write` (it holds only `social:read` today). Friend add/accept/decline/unfriend will 403 until the grant lands. → awaiting the client-registration grant (see § OPEN).
4. **FCM device-token endpoints + server push-send** — real push notifications with the app closed.
5. **Idempotency key on transaction/cash mutations** — lets the app drop its `[bt:<uuid>]` note-marker exactly-once hack.
6. **Play-Store publishing blockers** (web account-deletion page + privacy-policy URL) — needed before store submission (Step 20).

*(1 and 2 shipped in #361. Item 3 now only needs the client-registration grant. Full details for every remaining item below.)*

---

## § OPEN — needed from the main dev

### Scopes for the BetterTrackMobile client
- [ ] **P1 — `social:write` — GRANT to the client.** ⚠️ The scope itself is now **defined + granularly enforced** platform-side (shipped in #361): `/social` writes require `social:write`. **Remaining:** grant `social:write` to the `BetterTrackMobile` OAuth client registration (it currently holds only `social:read`), then friend add/accept/decline/cancel/unfriend go live. Until granted, friend-graph mutations return 403 — keep the write path stubbed.
- [ ] **P2 — `chat:read` + `chat:write`.** The scopes are now **defined** (additive) but **reserved** — there is no chat route group yet (chat endpoints are platform #349, not built). Keep stubbed until the chat endpoints ship.

### Social / sharing
- [ ] **P2 — "Specific friends" per-item sharing (per-friend ACL).** Platform models only `private ↔ friends (all)`. The app's audience picker has a third tier "specific friends" → needs a per-friend share ACL on portfolios / watchlists / conglomerates.
- [ ] **P2 — Public share links (revocable token).** The app's audience picker has a "public link" tier (blocking acknowledgment + Android share-sheet). Needs a revocable public-link token + a public read route. App mirrors the web's planned `/{kind}/shared/{token}` shape.

- [ ] **P3 — Friend-activity event feed + notifications** (powers the Social-v2 per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item," the platform needs to emit friend-activity events on shared items the user follows and deliver them via the notification channel. The app builds the per-item notification-preference UI now; the actual alerts light up on this. Nice-to-have.

### Chat (spec §6.10 — app UI built, 100% stubbed)
- [ ] **P2 — Chat endpoints:** conversations list, message thread (cursor-paged), send message, and **share-in-chat resolution** (an item chip resolves only if the recipient is allowed → else a "not shared with you" state). No groups / reactions / read-receipts required.
- [ ] **P2 — Realtime:** a `chat.message` room on the existing WS gateway (app has a polling fallback) + unread counts.
- [ ] scopes: `chat:read` / `chat:write` are defined (reserved) — see Scopes above.

### Notifications (spec §6.11 — app UI built, delivery stubbed)
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account. (The app already obtains a real FCM token from Firebase project `bettertrackapp-c6996`.)
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**kept server-side only — the mobile repo never holds it**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency / client-reference field (they `additionalProperties:false`). The app currently forces exactly-once by embedding a ` [bt:<uuid>]` marker in the transaction `note` and reconciling on it. Add an idempotency key → the app drops the note-marker hack.
- [ ] **P2 — Custom-asset LIST endpoint.** No endpoint lists a user's custom assets; the app infers identities from holdings + a local cache, so a custom asset with **no** holding is invisible. A `GET /custom-assets` (list) fixes this.
- [ ] **P2 — Named watchlists.** The app's multi-watchlist UI is built, but the platform exposes only one unnamed workboard list (`/workboard`). Need create / rename / delete + add / remove for multiple **named** lists → multi-list goes live (today extra lists are a debug stub).

### Settings — account & security (spec §6.12)
- [ ] **P1 — Delete account under bearer** (type-to-confirm flow is built app-side). **Play publishing blocker** — see the Release section below.

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P1 — Self-service account deletion via a PUBLIC WEB URL** (in addition to the in-app path above). Google Play mandates account deletion be reachable **both** in-app **and** via a web resource, and that it deletes the user's data. Needs a web-facing deletion page/flow on the site. *(The single hardest publishing blocker — schedule deliberately.)*
- [ ] **P1 — Public privacy-policy page** at a stable URL (e.g. `https://bettertrack.at/privacy`): data collected, use, sharing, retention, and the deletion path. Required to complete the Play **Data Safety** form and shown in the store listing.

### Lower priority / cosmetic
- [ ] **P3 — Portfolio hard-DELETE** (optional). Only archive exists; the app treats archive as removal. Fine as-is; noted for completeness.
- [ ] **P3 — Portfolio history 1D / 1W / 3M windows** (optional parity). Portfolio history serves `1M / 6M / 1Y / MAX` only (asset history already has the full `1D–MAX` set). The web ships the same subset, so low priority.

### Not a platform code change — reminders for Christian
- [ ] **Extend the BetterTrackMobile OAuth client registration** to include each new scope as it ships — **next up: grant `social:write`** (scope is live + enforced as of #361; granting it turns the Friends write path live). `chat:*` later, once chat endpoints ship.
- [ ] **A second test account** — for the full social + chat two-account live loop (request → accept → share → chat phone↔web).

---

## § DONE — asked and implemented by the main dev
*(items move here, checked + dated, when shipped — kept for audit; nothing deleted)*

- [x] **Cash sources + transfers** — `GET /cash/sources`, create / rename / relabel / archive / restore, `POST /cash/transfer` (atomic paired legs), `POST /cash/preview`; `GET /cash` returns `sources[]` + per-movement `sourceId` / `transferId`, and `cashSourceId` accepted on tx create + deposit/withdraw. *Shipped during development; the app's Cash screen (Step 9) went fully live on it — deposit / withdraw / transfer, offline via the queue.* ✅ 2026-07-08

### Bearer auth surface — unified web+mobile (platform #361 / PR #365) ✅ 2026-07-08
The bearer (the app's OAuth token) is now accepted on these previously session-cookie-only endpoints. Cookie sessions are unaffected. **Swap the corresponding stubs for the real adapters** — exact shapes below.

- [x] **P1 — Bearer identity `GET /auth/me`** — any valid bearer returns the caller's own identity `{ id, username, email, baseCurrency, locale, pinEnabled, … }`. NOTE: there is **no separate `displayName`** field — `username` **is** the display name. Fixes Settings → Account showing "—".
- [x] **P2 — Bearer token/grant self-revocation `POST /auth/logout`** — a bearer self-revokes the presented credential: a personal API key revokes itself; an OAuth access token revokes its whole grant (dies instantly). App logout is now a real server-side revoke, not local-wipe-only.
- [x] **P1 — Web-PIN status + verify under bearer** — `GET /auth/pin/status` → `{ pinSet: boolean }`; `POST /auth/pin/verify { pin }` → **200** on match, **401** `INVALID_PIN` on mismatch, **429** when cooling, **400** `PIN_NOT_ENABLED` when no web PIN. Reuses the **EXACT** web login `pin_hash` (argon2id, constant-time) — one PIN, both clients, no mobile PIN store. Rate-limited by a per-account progressive brute-force throttle; the PIN is never logged. Both endpoints require the `account:security` scope. *(Paths match what the app already built.)*
- [x] **P2 — Notification scopes + bearer coverage** — new `notifications:read` / `notifications:write` scopes, granularly enforced: `GET /notifications` (inbox list) needs `notifications:read`; notification-settings writes at `/settings/notifications` need `notifications:write`. (Covers both the "bearer read on /notifications" and "notification settings under bearer" asks.)
- [x] **P2/P3 — account/security scope + bearer coverage** — new `account:security` scope gating the security surface under a bearer: **change password** (`/auth/change-password`), **2FA management** (`/auth/2fa/{enroll,confirm,disable,status,recovery-codes,email/*}`), **active sessions** (`/auth/sessions` list + `/auth/sessions/:id` revoke + `/auth/sessions/revoke-others`, reusing the #340 backend). (Covers the change-password, 2FA-management, and active-sessions asks.)
- [x] **P3 — `openapi.json` per-route `security` metadata fixed** — now derived from the real middleware policy, so each route advertises `sessionCookie` + `apiKeyBearer` exactly where a bearer is actually admitted (no more blanket "sessionCookie only").

---

*Last updated: 2026-07-08 — platform dev moved the #361 bearer-auth surface to § DONE (identity, self-revocation, PIN status/verify, notification + account-security scopes & coverage, openapi fix). `social:write` scope is live+enforced but still needs granting to the client.*
