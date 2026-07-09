# Platform asks — what the BetterTrack **mobile app** needs from the **main platform**

Single source of truth for everything the **mobile app** (Android, this repo) needs the **main BetterTrack platform** (api / web) to build or change so features can go from *"UI built + stubbed"* to *"fully live."*

**Maintained by:** the mobile-app build (the coordinator working the Android app in this repo).
**For:** the main platform dev — this is your checklist of what the app is waiting on.

### How this file works
- **§ OPEN** = still needed. Each item says *what's needed*, *why* (which app feature it unblocks), and the concrete endpoint/scope shape where known.
- When you ship something, **move it to § DONE** — check it off with a date. Nothing is deleted (audit trail).
- The mobile side keeps appending here as more of the app is built. The app is designed so each item is a **thin adapter swap** (stub → real) with **no UI rework**.
- **Priorities:** **P1** = blocks a core feature from working live · **P2** = unlocks a built-but-stubbed feature · **P3** = polish / cosmetic / optional.
- **OAuth client:** `BetterTrackMobile` (first-party, public/PKCE), client id `btc_IbT1mzw_7kBiPHPkGfaE0Q`. **Allowed scopes now (as of #341, 2026-07-08):** `portfolio:read portfolio:write workboard:read workboard:write market:read social:read` **+ newly added** `account:security notifications:read notifications:write social:write`. ⚡ See the ACTIVATION box below — the app must **request** the new scopes and the user must **re-login** to receive them in a token.

---

## ⚡ ACTIVATION — how the #361/#341 bearer + scope work goes live on the app (READ THIS)
The platform side is done. To actually use the scope-gated endpoints (`/auth/pin/*`, `/notifications`, `/settings/notifications`, `/auth/change-password`, `/auth/2fa/*`, `/auth/sessions*`, `/social` writes) from the app, **two mobile-side steps** are required — a *stale existing token will still 403*, by design (consent-safety):
1. **App must REQUEST the new scopes** in its OAuth authorize call — add `account:security notifications:read notifications:write social:write` to the requested-scope list. A token only carries a scope if the app asked for it AND the client is allowed it (now it is).
   - *(Optional simplification: request the app's full allowed-scope set so future grants need no app change — your call.)*
2. **User logs out + back in on the phone** → fresh authorize → new token carrying the new scopes. `/auth/me` + `/auth/logout` already work on the current token (no scope needed); everything scope-gated needs the re-login.

`GET /auth/me` returns `pinEnabled` — you can drive the PIN-lock offer from that without even calling `/auth/pin/status`.

---

## ⭐ Current top priorities (start here — biggest unblock first)
1. ✅ **SHIPPED (#361)** — Bearer `GET /auth/me` (identity incl. `pinEnabled`). See § DONE.
2. ✅ **SHIPPED (#361 + #341)** — Bearer PIN verify/status, reusing the exact web PIN — scope now GRANTED to the client. Needs the ACTIVATION steps above.
3. ✅ **GRANTED (#341)** — `social:write` (+ `account:security`, `notifications:*`) added to the client's allowed scopes. Friend-writes go live after the ACTIVATION steps above.
4. **FCM device-token endpoints + server push-send** — real push notifications with the app closed.
5. **Idempotency key on transaction/cash mutations** — lets the app drop its `[bt:<uuid>]` note-marker exactly-once hack.
6. **Play-Store publishing blockers** (web account-deletion page + privacy-policy URL) — needed before store submission (Step 20).

---

## § OPEN — needed from the main dev

### Scopes for the BetterTrackMobile client
- [ ] **P2 — `chat:read` + `chat:write`.** The scopes are **defined** (additive) but **reserved** — there is no chat route group yet (chat endpoints are platform #349, not built). When #349 ships, grant these via the admin OAuth editor. Keep stubbed until then.

### Social / sharing
- [ ] **P2 — "Specific friends" per-item sharing (per-friend ACL).** Platform models only `private ↔ friends (all)`. The app's audience picker has a third tier "specific friends" → needs a per-friend share ACL on portfolios / watchlists / conglomerates.
- [ ] **P2 — Public share links (revocable token).** The app's audience picker has a "public link" tier (blocking acknowledgment + Android share-sheet). Needs a revocable public-link token + a public read route. App mirrors the web's planned `/{kind}/shared/{token}` shape.

- [ ] **P3 — Friend-activity event feed + notifications** (powers the Social-v2 per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item," the platform needs to emit friend-activity events on shared items the user follows and deliver them via the notification channel. The app builds the per-item notification-preference UI now; the actual alerts light up on this. Nice-to-have.

### Chat (spec §6.10 — app UI built, 100% stubbed)
- [ ] **P2 — Chat endpoints:** conversations list, message thread (cursor-paged), send message, and **share-in-chat resolution** (an item chip resolves only if the recipient is allowed → else a "not shared with you" state). No groups / reactions / read-receipts required.
- [ ] **P2 — Realtime:** a `chat.message` room on the existing WS gateway (app has a polling fallback) + unread counts.
- [ ] scopes: `chat:read` / `chat:write` are defined (reserved) — see Scopes above.

### Notifications (spec §6.11 — app UI built, delivery stubbed)
> 📄 **Full "how to send a push to the app via Firebase/FCM" contract → `docs/PUSH_NOTIFICATIONS_FOR_PLATFORM.md`** (device-token endpoint shape + the exact `data` payload: `type`/`title`/`body`/`payload` + the type→deep-link table). The two items below are the platform work it describes.
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account. (The app already obtains a real FCM token from Firebase project `bettertrackapp-c6996`.)
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**kept server-side only — the mobile repo never holds it**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P3 — Sell un-held stock (oversell → record, don't reject).** *(Owner 2026-07-09: the MAIN DEV OWNS this feature — build it in the web app + design the API however you see fit; the mobile app will FOLLOW your API shape, it is NOT building ahead of you. Listed here only so the eventual API is on the mobile radar.)* Idea: a "sell anyway?" path for selling a stock the user holds 0 (or fewer) shares of — today the server rejects oversells (Step-8: → needs-attention). If/when you add it, recording it as a 0-cost-basis realized sale / short position (performance 0% until a matching buy/basis exists), ideally with an optional cost-basis field, is what the mobile UX would consume.
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency / client-reference field (they `additionalProperties:false`). The app currently forces exactly-once by embedding a ` [bt:<uuid>]` marker in the transaction `note` and reconciling on it. Add an idempotency key → the app drops the note-marker hack.
- [ ] **P2 — Custom-asset LIST endpoint.** No endpoint lists a user's custom assets; the app infers identities from holdings + a local cache, so a custom asset with **no** holding is invisible. A `GET /custom-assets` (list) fixes this.
- [ ] **P2 — Named watchlists.** The app's multi-watchlist UI is built, but the platform exposes only one unnamed workboard list (`/workboard`). Need create / rename / delete + add / remove for multiple **named** lists → multi-list goes live (today extra lists are a debug stub).

### Settings — account & security (spec §6.12)
- [ ] **P1 — Delete account under bearer** (type-to-confirm flow is built app-side). **Play publishing blocker** — see the Release section below. *(Note: the bearer + `account:security` scope plumbing exists now; this just needs the actual delete-account endpoint.)*

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P1 — Self-service account deletion via a PUBLIC WEB URL** (in addition to the in-app path above). Google Play mandates account deletion be reachable **both** in-app **and** via a web resource, and that it deletes the user's data. Needs a web-facing deletion page/flow on the site. *(The single hardest publishing blocker — schedule deliberately.)*
- [ ] **P1 — Public privacy-policy page** at a stable URL (e.g. `https://bettertrack.at/privacy`): data collected, use, sharing, retention, and the deletion path. Required to complete the Play **Data Safety** form and shown in the store listing.

### Lower priority / cosmetic
- [ ] **P3 — Portfolio hard-DELETE** (optional). Only archive exists; the app treats archive as removal. Fine as-is; noted for completeness.
- [ ] **P3 — Portfolio history 1D / 1W / 3M windows** (optional parity). Portfolio history serves `1M / 6M / 1Y / MAX` only (asset history already has the full `1D–MAX` set). The web ships the same subset, so low priority.

### Not a platform code change — reminders for Christian
- [x] ~~Extend the BetterTrackMobile OAuth client registration to include each new scope~~ — ✅ `account:security` + `notifications:read/write` + `social:write` granted (#341, 2026-07-08). `chat:*` still to be granted later when chat endpoints (#349) ship — **now self-serve** via the admin panel's OAuth-app editor.
- [ ] **A second test account** — for the full social + chat two-account live loop (request → accept → share → chat phone↔web).

---

## § DONE — asked and implemented by the main dev
*(items move here, checked + dated, when shipped — kept for audit; nothing deleted)*

- [x] **Cash sources + transfers** — `GET /cash/sources`, create / rename / relabel / archive / restore, `POST /cash/transfer` (atomic paired legs), `POST /cash/preview`; `GET /cash` returns `sources[]` + per-movement `sourceId` / `transferId`, and `cashSourceId` accepted on tx create + deposit/withdraw. *Shipped during development; the app's Cash screen (Step 9) went fully live on it — deposit / withdraw / transfer, offline via the queue.* ✅ 2026-07-08

### Bearer auth surface — unified web+mobile (platform #361 / PR #365) ✅ 2026-07-08
The bearer (the app's OAuth token) is now accepted on these previously session-cookie-only endpoints. Cookie sessions are unaffected. **Swap the corresponding stubs for the real adapters** — but the scope-gated ones need the ⚡ ACTIVATION steps at the top (app requests scopes + user re-login).

- [x] **P1 — Bearer identity `GET /auth/me`** — any valid bearer returns the caller's own identity `{ id, username, email, baseCurrency, locale, pinEnabled, … }`. NOTE: no separate `displayName` — `username` **is** the display name. Works on the current token (no scope needed).
- [x] **P2 — Bearer token/grant self-revocation `POST /auth/logout`** — a bearer self-revokes the presented credential (personal key revokes itself; OAuth token revokes its whole grant). Works on the current token (no scope needed).
- [x] **P1 — Web-PIN status + verify under bearer** — `GET /auth/pin/status` → `{ pinSet: boolean }`; `POST /auth/pin/verify { pin }` → **200** match / **401** `INVALID_PIN` / **429** cooling / **400** `PIN_NOT_ENABLED`. Reuses the **EXACT** web login `pin_hash` (argon2id, constant-time) — one PIN, no mobile store. Rate-limited per-account; PIN never logged. Requires `account:security` (now granted — ⚡ needs ACTIVATION).
- [x] **P2 — Notification scopes + bearer coverage** — `notifications:read` / `notifications:write`, granularly enforced: `GET /notifications` needs `notifications:read`; `/settings/notifications` writes need `notifications:write`. (Scopes now granted — ⚡ needs ACTIVATION.)
- [x] **P2/P3 — account/security scope + bearer coverage** — `account:security` gates: **change password** (`/auth/change-password`), **2FA** (`/auth/2fa/{enroll,confirm,disable,status,recovery-codes,email/*}`), **sessions** (`/auth/sessions` list + `/auth/sessions/:id` revoke + `/auth/sessions/revoke-others`, reusing #340). (Scope now granted — ⚡ needs ACTIVATION.)
- [x] **P3 — `openapi.json` per-route `security` metadata fixed** — derived from the real middleware policy; each route advertises `sessionCookie` + `apiKeyBearer` exactly where a bearer is admitted.

### Admin OAuth-app editing + mobile scope grant (platform #341 / PR #366) ✅ 2026-07-08
- [x] **Admin can fully EDIT any first-party OAuth app** (name, redirect URIs, allowed scopes) in the admin panel — no more delete+recreate. **Consent-safe:** widening an app's scopes never widens an existing token (a token's effective scope = *consented ∩ current-allowed*, clamped on every request); narrowing/removing a scope or redirect URI applies immediately; every edit is audit-logged. `client_id` stays immutable.
- [x] **P1 — `social:write` GRANTED to the client** (+ `account:security`, `notifications:read`, `notifications:write`) — added to `BetterTrackMobile`'s allowed-scope set. Unblocks friend add/accept/decline/cancel/unfriend AND the #361 scope-gated endpoints above. ⚡ Needs the ACTIVATION steps (app requests the scopes + user re-login) — a stale token won't carry them (consent-safety).

---

*Last updated: 2026-07-08 — platform dev: #341 shipped (admin OAuth-app editing, consent-safe) + granted the mobile client `account:security`/`notifications:*`/`social:write`. See the ⚡ ACTIVATION box — app must request the new scopes + user must re-login to receive them.*
