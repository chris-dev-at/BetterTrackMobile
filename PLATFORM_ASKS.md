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
The platform side is done. To actually use the scope-gated endpoints (`/auth/pin/*`, `/notifications`, `/settings/notifications`, `/auth/change-password`, `/auth/2fa/*`, `/auth/sessions*`, `/social` writes incl. **the new sharing/audience mutations**) from the app, **two mobile-side steps** are required — a *stale existing token will still 403*, by design (consent-safety):
1. **App must REQUEST the new scopes** in its OAuth authorize call — add `account:security notifications:read notifications:write social:write` to the requested-scope list. A token only carries a scope if the app asked for it AND the client is allowed it (now it is).
   - *(Optional simplification: request the app's full allowed-scope set so future grants need no app change — your call.)*
2. **User logs out + back in on the phone** → fresh authorize → new token carrying the new scopes. `/auth/me` + `/auth/logout` already work on the current token (no scope needed); everything scope-gated needs the re-login.

`GET /auth/me` returns `pinEnabled` — you can drive the PIN-lock offer from that without even calling `/auth/pin/status`.

---

## ⭐ Current top priorities (start here — biggest unblock first)
1. ✅ **SHIPPED (#361)** — Bearer `GET /auth/me` (identity incl. `pinEnabled`). See § DONE.
2. ✅ **SHIPPED (#361 + #341)** — Bearer PIN verify/status, reusing the exact web PIN — scope now GRANTED to the client. Needs the ACTIVATION steps above.
3. ✅ **GRANTED (#341)** — `social:write` (+ `account:security`, `notifications:*`) added to the client's allowed scopes. Friend-writes go live after the ACTIVATION steps above.
4. ✅ **SHIPPED (#332)** — sharing audiences (specific-friends / all-friends / public-link) + named watchlists now have a real backend. See § DONE (needs `social:write` ACTIVATION for the mutations).
5. **FCM device-token endpoints + server push-send** — real push notifications with the app closed.
6. **Play-Store publishing blockers** (web account-deletion page + privacy-policy URL) — needed before store submission (Step 20).

---

## § OPEN — needed from the main dev

### Scopes for the BetterTrackMobile client
- [ ] **P2 — `chat:read` + `chat:write`.** The scopes are **defined** (additive) but **reserved** — there is no chat route group yet (chat endpoints are platform #349, not built). When #349 ships, grant these via the admin OAuth editor. Keep stubbed until then.

### Social / sharing
- [ ] **P3 — Friend-activity event feed + notifications** (powers the Social-v2 per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item," the platform needs to emit friend-activity events on shared items the user follows and deliver them via the notification channel. The app builds the per-item notification-preference UI now; the actual alerts light up on this. *(Platform: folded into Notifications-v2 #368 + tracked as V3-P6 friend-activity hook.)*

### Chat (spec §6.10 — app UI built, 100% stubbed)
- [ ] **P2 — Chat endpoints:** conversations list, message thread (cursor-paged), send message, and **share-in-chat resolution** (an item chip resolves only if the recipient is allowed → else a "not shared with you" state). No groups / reactions / read-receipts required. *(Platform: #349.)*
- [ ] **P2 — Realtime:** a `chat.message` room on the existing WS gateway (app has a polling fallback) + unread counts.
- [ ] scopes: `chat:read` / `chat:write` are defined (reserved) — see Scopes above.

### Notifications (spec §6.11 — app UI built, delivery stubbed)
> 📄 **Full "how to send a push to the app via Firebase/FCM" contract → `docs/PUSH_NOTIFICATIONS_FOR_PLATFORM.md`** (device-token endpoint shape + the exact `data` payload: `type`/`title`/`body`/`payload` + the type→deep-link table). The two items below are the platform work it describes. *(Platform: unified Notifications-v2 = #368, absorbs #350/#351.)*
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account. (The app already obtains a real FCM token from Firebase project `bettertrackapp-c6996`.)
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**kept server-side only — the mobile repo never holds it**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P3 — Sell un-held stock (oversell → record, don't reject).** *(Owner 2026-07-09: the MAIN DEV OWNS this feature — build it in the web app + design the API however you see fit; the mobile app will FOLLOW your API shape, it is NOT building ahead of you.)* Platform is building it as **#369**: a "sell anyway?" path with a required warning; the uncovered portion counts **net 0%** (basis = sale price) by default, with an **optional entry-price** field for accurate % ; no shorts (holding floors at 0). The eventual API = an `allowUncovered` flag + optional entry basis on the sell endpoint — the app consumes that shape.
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency / client-reference field (they `additionalProperties:false`). The app currently forces exactly-once by embedding a ` [bt:<uuid>]` marker in the transaction `note` and reconciling on it. Add an idempotency key → the app drops the note-marker hack.
- [ ] **P2 — Custom-asset LIST endpoint.** No endpoint lists a user's custom assets; the app infers identities from holdings + a local cache, so a custom asset with **no** holding is invisible. A `GET /custom-assets` (list) fixes this.

### Settings — account & security (spec §6.12)
- [ ] **P1 — Delete account under bearer** (type-to-confirm flow is built app-side). **Play publishing blocker** — see the Release section below. *(Note: the bearer + `account:security` scope plumbing exists now; this just needs the actual delete-account endpoint. Platform: #362.)*

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P1 — Self-service account deletion via a PUBLIC WEB URL** (in addition to the in-app path above). Google Play mandates account deletion be reachable **both** in-app **and** via a web resource, and that it deletes the user's data. Needs a web-facing deletion page/flow on the site. *(The single hardest publishing blocker — schedule deliberately. Platform: #362.)*
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

- [x] **Cash sources + transfers** — `GET /cash/sources`, create / rename / relabel / archive / restore, `POST /cash/transfer` (atomic paired legs), `POST /cash/preview`; `GET /cash` returns `sources[]` + per-movement `sourceId` / `transferId`, and `cashSourceId` accepted on tx create + deposit/withdraw. *Shipped during development; the app's Cash screen (Step 9) went fully live on it.* ✅ 2026-07-08

### Bearer auth surface — unified web+mobile (platform #361 / PR #365) ✅ 2026-07-08
The bearer (the app's OAuth token) is now accepted on these previously session-cookie-only endpoints. Cookie sessions are unaffected. **Swap the corresponding stubs for the real adapters** — but the scope-gated ones need the ⚡ ACTIVATION steps at the top (app requests scopes + user re-login).

- [x] **P1 — Bearer identity `GET /auth/me`** — any valid bearer returns the caller's own identity `{ id, username, email, baseCurrency, locale, pinEnabled, … }`. `username` **is** the display name. Works on the current token (no scope needed).
- [x] **P2 — Bearer token/grant self-revocation `POST /auth/logout`** — self-revokes the presented credential. Works on the current token (no scope needed).
- [x] **P1 — Web-PIN status + verify under bearer** — `GET /auth/pin/status` → `{ pinSet }`; `POST /auth/pin/verify { pin }` → **200** / **401** `INVALID_PIN` / **429** cooling / **400** `PIN_NOT_ENABLED`. Reuses the EXACT web `pin_hash` (argon2id); rate-limited; PIN never logged. Requires `account:security` (granted — ⚡ ACTIVATION).
- [x] **P2 — Notification scopes + bearer coverage** — `notifications:read`/`:write`; `GET /notifications` needs read, `/settings/notifications` needs write. (⚡ ACTIVATION.)
- [x] **P2/P3 — account/security scope + coverage** — `account:security` gates change-password, 2FA (`/auth/2fa/*`), sessions (`/auth/sessions*`, reusing #340). (⚡ ACTIVATION.)
- [x] **P3 — `openapi.json` per-route `security` metadata fixed** — derived from the real middleware policy.

### Admin OAuth-app editing + mobile scope grant (platform #341 / PR #366) ✅ 2026-07-08
- [x] **Admin can fully EDIT any first-party OAuth app** (name, redirect URIs, allowed scopes) — consent-safe (a token's effective scope = *consented ∩ current-allowed*, clamped every request; narrowing applies immediately; audit-logged; `client_id` immutable).
- [x] **P1 — `social:write` GRANTED to the client** (+ `account:security`, `notifications:read/write`). Unblocks friend writes AND the #361 scope-gated endpoints. ⚡ Needs ACTIVATION (app requests scopes + user re-login).

### Sharing v3 — audiences everywhere + named watchlists (platform #332 / PR #373) ✅ 2026-07-09
Your audience picker (private / specific friends / all friends / public link) and multi-watchlist UI now have a real backend across **every** portfolio (not just default), conglomerate, and watchlist. **Swap the stubs → real adapters.** Uses your existing granted scopes (portfolio/workboard read+write, `social:write` for the mutations — needs ⚡ ACTIVATION). Exact request/response shapes are in `packages/contracts` (`social.ts`/`workboard.ts`/`portfolio.ts`/`conglomerate.ts`) + `openapi.json` — trust those.
- [x] **P2 — "Specific friends" per-item ACL** — audiences per subject: `private` / `specific_friends` (multi-select member list) / `all_friends` / `public_link`. Owner sets the audience per subject (audience-set endpoints in the contracts above; the reusable `AudiencePicker` model matches your third-tier picker). **Enforcement:** a non-friend or non-member gets **404** (never 403); unfriending / narrowing the audience closes access on the very next request (no cached auth).
- [x] **P2 — Public share links (revocable token)** — set a subject's audience to `public_link` → the API mints a ≥128-bit token **once** (only its SHA-256 hash is stored). Public read resolves **unauthenticated** at **`GET /api/v1/social/links/:token`** (web logged-out view `/s/:token`). Revoke = narrow the audience → the token dies instantly. Matches the `/{kind}/shared/{token}` shape you mirrored. The public rung's explicit acknowledgment is enforced server-side too (`PUBLIC_LINK_ACK_REQUIRED`).
- [x] **P2 — Named watchlists** — create / rename / delete named lists (a default **"General"** exists, locked); per-list audience via the picker; add/remove items per list. Endpoints under `/workboard/watchlists` (list/create) + rename/delete + `PATCH /workboard/sharing` for a list's audience (see `workboard.ts` + openapi). **Your existing single workboard was migrated losslessly into "General"** — nothing lost; your extra-lists debug stub becomes real.
- *Note:* web deferred **clone-from-share-link** (the public link view is read-only for now).

---

*Last updated: 2026-07-09 — platform dev: #332 shipped (sharing audiences everywhere + named watchlists) → moved specific-friends ACL, public links, and named watchlists to § DONE. Swap the stubs; the sharing mutations need the `social:write` ⚡ ACTIVATION re-login. Shapes in `packages/contracts` + `openapi.json`.*
