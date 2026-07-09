# Platform asks — what the BetterTrack **mobile app** needs from the **main platform**

Single source of truth for everything the **mobile app** (Android, this repo) needs the **main BetterTrack platform** (api / web) to build or change so features can go from *"UI built + stubbed"* to *"fully live."*

**Maintained by:** the mobile-app build (the coordinator working the Android app in this repo).
**For:** the main platform dev — this is your checklist of what the app is waiting on.

### How this file works
- **§ OPEN** = still needed. Each item says *what's needed*, *why* (which app feature it unblocks), and the concrete endpoint/scope shape where known.
- When you ship something, **move it to § DONE** — check it off with a date. Nothing is deleted (audit trail).
- The mobile side keeps appending here as more of the app is built. The app is designed so each item is a **thin adapter swap** (stub → real) with **no UI rework**.
- **Priorities:** **P1** = blocks a core feature from working live · **P2** = unlocks a built-but-stubbed feature · **P3** = polish / cosmetic / optional.
- **OAuth client:** `BetterTrackMobile` (first-party, public/PKCE), client id `btc_IbT1mzw_7kBiPHPkGfaE0Q`. **Allowed scopes now:** `portfolio:read portfolio:write workboard:read workboard:write market:read social:read` **+** `account:security notifications:read notifications:write social:write` (#341) **+** `chat:read chat:write` (#386). ⚡ See the ACTIVATION box — the app must **request** the new scopes and the user must **re-login** to receive them in a token.

---

## ⚡ ACTIVATION — how the granted scopes go live on the app (READ THIS)
The platform side is done. To use the scope-gated endpoints (`/auth/pin/*`, `/notifications`, `/settings/notifications`, `/auth/change-password`, `/auth/2fa/*`, `/auth/sessions*`, `/social` writes incl. sharing/audience mutations, **and the new `/chat/*` endpoints**) from the app, **two mobile-side steps** — a *stale token still 403s*, by design (consent-safety):
1. **App must REQUEST the scopes** in its OAuth authorize call — add `account:security notifications:read notifications:write social:write chat:read chat:write` to the requested-scope list. A token only carries a scope if the app asked for it AND the client is allowed it (now it is).
   - *(Optional simplification: request the app's full allowed-scope set so future grants need no app change — your call.)*
2. **User logs out + back in on the phone** → fresh authorize → new token carrying the scopes. `/auth/me` + `/auth/logout` work on the current token (no scope needed); everything scope-gated (incl. chat) needs the re-login.

`GET /auth/me` returns `pinEnabled` — you can drive the PIN-lock offer from that without even calling `/auth/pin/status`.

---

## ⭐ Current top priorities (start here — biggest unblock first)
1. ✅ **SHIPPED (#361 + #341)** — bearer identity/PIN/scopes + admin OAuth editor. See § DONE. (Needs ACTIVATION re-login.)
2. ✅ **SHIPPED (#332)** — sharing audiences + named watchlists. See § DONE.
3. ✅ **SHIPPED (#349 + #386)** — **friend chat: endpoints + realtime + `chat:read`/`chat:write` granted.** See § DONE. (Needs the chat-scope ACTIVATION re-login.)
4. **FCM device-token endpoints + server push-send** — real push notifications with the app closed. (Platform: Notifications-v2 #368.)
5. **Play-Store publishing blockers** (web account-deletion page + privacy-policy URL) — needed before store submission (Step 20).

---

## § OPEN — needed from the main dev

### Social / sharing
- [ ] **P3 — Friend-activity event feed + delivery** (powers the per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item." **Partial platform progress:** the per-shared-item **preference is now persistable** — `PUT /social/shared/activity/:kind/:subjectId` stores a viewer's "notify me about activity on this shared item" toggle (built for V3-P6). **Still needed:** the platform must EMIT friend-activity events on followed shared items + DELIVER them through the notification channel — folded into **Notifications-v2 (#368)**. Wire your toggle to the endpoint now; the actual alerts light up with #368.

### Chat activation + realtime (found during the app's chat go-live, 2026-07-09)
- [ ] **P1 — First-party re-login does NOT mint the new `chat:*` scopes on this account (blocks live chat today).** The app requests the full allowed set incl. `chat:read chat:write` in its authorize URL (verified in `OAuthConfig.SCOPES`), and a fresh device login exchanges a token + `/auth/me` 200s — **but every `/chat/*` call returns 403**. **Discriminating evidence (from the same account, same day):** at the previous re-login — with the identical full scope string requested — `social:write` DID activate (the same `PUT /social/shared/activity/...` flipped 403→200 across that re-login). So re-consent demonstrably widens the grant for scopes the client is allowed; only `chat:*` stays dead across TWO fresh logins that requested it. **Likeliest root cause: `chat:read`/`chat:write` are not actually in the BetterTrackMobile client's allowed-scope set on PRODUCTION** — #386 ships the grant via migration `apps/api/drizzle/0027_mobile_chat_scopes.sql`, and if that migration/deploy hasn't rolled to prod, the consent-safe clamp (`effective = consented ∩ current-allowed`) strips exactly `chat:*` while the #341 scopes keep working — which matches every observation. **To unblock (in order):** (a) **main dev**: confirm migration 0027 ran on prod / the #386 deploy is live — the admin OAuth editor should show `chat:read chat:write` in BetterTrackMobile's allowed scopes; then a plain phone re-login activates chat (no revoke needed, per the social:write precedent). (b) Only if the admin panel DOES already show them allowed: then the stale-grant theory stands after all — **Christian** revokes the "BetterTrackMobile" connected app on the web (Settings → connected apps) and re-logs-in on the phone; and the platform should make first-party re-authorize widen grants / show incremental consent. *(Side finding either way: the app can't self-heal a grant — `DELETE /settings/oauth-grants/{id}` is session-cookie-only.)*
- [ ] **P3 — Mobile bearer auth + direct-websocket transport for the `/ws` realtime gateway (chat receive-side latency).** The app rides `/ws` for `chat.message` invalidations. **Confirmed on the wire:** the Engine.IO *polling* handshake at `…/ws/?EIO=4&transport=polling` opens fine (`0{"sid":…,"pingInterval":25000,…}`), but the app's **direct `transport=websocket` upgrade returns HTTP 400** (`ProtocolException`, logged as `BtChatWs ws failure: ProtocolException (http 400)`), so the Socket.IO handshake never completes. Likely either (i) the gateway requires the Engine.IO **polling handshake first** (to mint a `sid`) before allowing a websocket upgrade, or (ii) the `Authorization: Bearer` header on the upgrade is rejected. Separately, `contracts/realtime.ts` documents the handshake as **session-cookie**-authenticated with the socket admitted to its own `user:{id}` room **at connect** — the mobile app has no cookie, so it sends the bearer best-effort (upgrade header + `40{"token":…}` auth). The app **falls back to foreground polling** (open thread ~10s, conversation list ~30s — verified: `/chat/conversations` polled on a clean 30s cadence; WS reconnect uses 1→2→4→8→16s capped backoff), so chat is fully correct once the scope lands, just not instant on receive. **Ask:** confirm the mobile WS path — does the gateway accept a direct `transport=websocket` connection, and a bearer (upgrade header or `handshake.auth.token`) joined to `user:{id}`? *(Receive-push unverifiable app-side without a second live account.)*

### Notifications (spec §6.11 — app UI built, delivery stubbed)
> 📄 **Full FCM send contract → `docs/PUSH_NOTIFICATIONS_FOR_PLATFORM.md`.** *(Platform: unified Notifications-v2 = #368, absorbs #350/#351; owner wants ONE central dispatcher + presence-based suppression — don't notify for a chat you're actively viewing.)*
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account.
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**server-side only**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P3 — Sell un-held stock (oversell → record, don't reject).** *(Owner: MAIN DEV OWNS this — the app FOLLOWS the API shape.)* Platform building it as **#369**: a "sell anyway?" path with a required warning; uncovered portion counts **net 0%** (basis = sale price) by default + an **optional entry-price**; no shorts. API = an `allowUncovered` flag + optional entry basis on the sell endpoint.
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency field. The app hacks a ` [bt:<uuid>]` note-marker. Add an idempotency key → the app drops the hack.
- [ ] **P2 — Custom-asset LIST endpoint.** `GET /custom-assets` (list) — so a custom asset with no holding is visible.

### Settings — account & security (spec §6.12)
- [ ] **P1 — Delete account under bearer** (type-to-confirm built app-side). **Play publishing blocker.** *(bearer + `account:security` plumbing exists; just needs the endpoint. Platform: #362.)*

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P1 — Self-service account deletion via a PUBLIC WEB URL** (in addition to in-app). *(Hardest publishing blocker. Platform: #362.)*
- [ ] **P1 — Public privacy-policy page** at a stable URL (e.g. `https://bettertrack.at/privacy`).

### Lower priority / cosmetic
- [ ] **P3 — Portfolio hard-DELETE** (optional; archive exists).
- [ ] **P3 — Portfolio history 1D / 1W / 3M windows** (optional parity).

### Not a platform code change — reminders for Christian
- [x] ~~Extend the OAuth client registration to include each new scope~~ — ✅ `account:security` + `notifications:read/write` + `social:write` (#341) **and `chat:read` + `chat:write` (#386)** all granted. (Future scopes: self-serve via the admin OAuth editor.)
- [ ] **A second test account** — for the full social + chat two-account live loop (request → accept → share → chat phone↔web).

---

## § DONE — asked and implemented by the main dev
*(items move here, checked + dated, when shipped — kept for audit; nothing deleted)*

- [x] **Cash sources + transfers** — full cash-source model + transfers; the app's Cash screen (Step 9) went fully live on it. ✅ 2026-07-08

### Bearer auth surface — unified web+mobile (platform #361 / PR #365) ✅ 2026-07-08
Bearer now accepted on previously cookie-only endpoints (scope-gated ones need the ⚡ ACTIVATION re-login).
- [x] **P1 — Bearer identity `GET /auth/me`** → `{ id, username, email, baseCurrency, locale, pinEnabled, … }` (`username` is the display name). Works on the current token.
- [x] **P2 — Self-revocation `POST /auth/logout`.** Works on the current token.
- [x] **P1 — Web-PIN status + verify** — `GET /auth/pin/status` → `{ pinSet }`; `POST /auth/pin/verify` → 200/401/429/400. Reuses the web `pin_hash`; rate-limited. `account:security` (ACTIVATION).
- [x] **P2 — Notification scopes + coverage** — `notifications:read`/`:write` on `/notifications` + `/settings/notifications`. (ACTIVATION.)
- [x] **P2/P3 — account/security scope** — `account:security` gates change-password, 2FA, sessions. (ACTIVATION.)
- [x] **P3 — `openapi.json` security metadata fixed.**

### Admin OAuth-app editing + scope grants (platform #341 / PR #366) ✅ 2026-07-08
- [x] **Admin can fully EDIT any first-party OAuth app** (name/redirect URIs/scopes) — consent-safe (effective scope = consented ∩ current-allowed).
- [x] **`social:write` + `account:security` + `notifications:read/write` GRANTED** to the client. (ACTIVATION.)

### Sharing v3 — audiences everywhere + named watchlists (platform #332 / PR #373) ✅ 2026-07-09
Audience picker (private / specific friends / all friends / public link) + multi-watchlist backend across every portfolio, conglomerate, watchlist. Shapes in `packages/contracts` + `openapi.json`.
- [x] **P2 — "Specific friends" per-item ACL** — `private`/`specific_friends`/`all_friends`/`public_link` per subject; non-friend/non-member → 404; unfriend/narrow closes instantly (no cached auth).
- [x] **P2 — Public share links** — `public_link` mints a ≥128-bit token once (hash stored); public read at `GET /api/v1/social/links/:token` (web `/s/:token`, now with the value chart + BetterTrack-Web wordmark); revoke = narrow → token dies instantly.
- [x] **P2 — Named watchlists** — create/rename/delete named lists (default "General"); per-list audience; your old single workboard migrated losslessly into "General".

### Friend chat — endpoints + realtime + scopes (platform #349 + #386) ✅ 2026-07-09
Your chat UI (built + 100% stubbed) now has a real backend. **Swap the stubs → real adapters.** Needs the `chat:read`/`chat:write` ⚡ ACTIVATION re-login. Shapes in `packages/contracts` `chat.ts` + `openapi.json`.
- [x] **P2 — Chat endpoints** (#349): 1:1 **friend-only** conversations (one per pair) — list conversations + unread counts, paginated thread history, send message, mark-read. Non-friends → 404; unfriending closes the thread to new messages (history stays readable). **Share-in-chat chips:** send an asset ref or one of your shareable items as a **bare `(kind, subjectId)` reference** (no snapshot); the recipient resolves it through the sharing enforcement — an item not shared with them shows a **"not shared with you"** state with **no data**; **sending a chip never grants or widens access**. No groups/reactions/read-receipts.
- [x] **P2 — Realtime + unread** (#349): `chat.message` delivered over the existing `/ws` gateway to the recipient's `user:{id}` room as an **invalidation signal** (no body/chip crosses the socket → the app refetches, re-resolving chips through enforcement); **polling fallback** works when the gateway's off. Unread is per-participant, derived from last-read markers (survives reload). `chat.message` honors the notification matrix (muted → no bell, message still lands). ⚠️ **Presence-based suppression** (don't notify for a conversation you're actively viewing) is a **future** add via Notifications-v2 (#368) — not yet; today an open conversation still notifies.
- [x] **P2 — `chat:read` + `chat:write` GRANTED** to the client (#386). ⚡ ACTIVATION: add them to the app's authorize request + re-login → then chat goes live.

---

*Last updated: 2026-07-09 — platform dev: **friend chat shipped** (#349 endpoints+realtime, #386 chat scopes granted) → moved chat endpoints + realtime + scopes to § DONE. ⚡ Add `chat:read`/`chat:write` to the app's authorize request + re-login to activate. The whole web social system (friends, unified sharing + management, public profiles + charts, chat) is now live and matches the app's design. Shapes in `packages/contracts` + `openapi.json`.*
