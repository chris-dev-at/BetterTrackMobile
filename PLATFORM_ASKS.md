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
5. ✅ **SHIPPED (#362 + privacy URL live)** — **Play publishing blockers cleared:** in-app bearer deletion + public web deletion page + privacy-policy URL. See § DONE. Step 20 now only needs the Data-safety form to match (platform does a privacy-page completeness pass before submit).
6. ✅ **RESOLVED (2026-07-10)** — the "chat scopes not effective on prod" gate: **disproven with DB evidence — prod is fine; a plain re-login (or even a token refresh) activates chat.** See § DONE.

---

## § OPEN — needed from the main dev

### Social / sharing
- [ ] **P3 — Friend-activity event feed + delivery** (powers the per-shared-item alerts): so the app can notify "friend X bought on their shared portfolio Y" / "added a watchlist item." **Partial platform progress:** the per-shared-item **preference is now persistable** — `PUT /social/shared/activity/:kind/:subjectId` stores a viewer's "notify me about activity on this shared item" toggle (built for V3-P6). **Still needed:** the platform must EMIT friend-activity events on followed shared items + DELIVER them through the notification channel — folded into **Notifications-v2 (#368)**. Wire your toggle to the endpoint now; the actual alerts light up with #368.

### Chat activation + realtime (found during the app's chat go-live, 2026-07-09)
- [ ] **P3 — Bearer self-service grant management** *(side-finding from the scope hunt)*: `DELETE /settings/oauth-grants/{id}` is session-cookie-only, so the app can't self-heal a wonky grant from the device. Small bearer-coverage ask; park with the next bearer batch.
- [ ] **P3 — Mobile bearer auth + direct-websocket transport for the `/ws` realtime gateway (chat receive-side latency).** 🔨 **IN BUILD by the platform right now (2026-07-10):** bearer handshake (both the `Authorization` upgrade header and socket.io `auth.token`) validated through the same path as HTTP bearer auth + socket joined to `user:{id}`, plus direct `transport=websocket` connects fixed. PR in flight — moves to § DONE when merged & deployed; your current polling fallback keeps working unchanged. The app rides `/ws` for `chat.message` invalidations. **Confirmed on the wire:** the Engine.IO *polling* handshake at `…/ws/?EIO=4&transport=polling` opens fine (`0{"sid":…,"pingInterval":25000,…}`), but the app's **direct `transport=websocket` upgrade returns HTTP 400** (`ProtocolException`, logged as `BtChatWs ws failure: ProtocolException (http 400)`), so the Socket.IO handshake never completes. Likely either (i) the gateway requires the Engine.IO **polling handshake first** (to mint a `sid`) before allowing a websocket upgrade, or (ii) the `Authorization: Bearer` header on the upgrade is rejected. Separately, `contracts/realtime.ts` documents the handshake as **session-cookie**-authenticated with the socket admitted to its own `user:{id}` room **at connect** — the mobile app has no cookie, so it sends the bearer best-effort (upgrade header + `40{"token":…}` auth). The app **falls back to foreground polling** (open thread ~10s, conversation list ~30s — verified: `/chat/conversations` polled on a clean 30s cadence; WS reconnect uses 1→2→4→8→16s capped backoff), so chat is fully correct once the scope lands, just not instant on receive. **Ask:** confirm the mobile WS path — does the gateway accept a direct `transport=websocket` connection, and a bearer (upgrade header or `handshake.auth.token`) joined to `user:{id}`? *(Receive-push unverifiable app-side without a second live account.)*

### Notifications (spec §6.11 — app UI built, delivery stubbed)
> 📄 **Full FCM send contract → `docs/PUSH_NOTIFICATIONS_FOR_PLATFORM.md`.** *(Platform: unified Notifications-v2 = #368, absorbs #350/#351; owner wants ONE central dispatcher + presence-based suppression — don't notify for a chat you're actively viewing.)*
- [ ] **P1-for-push — FCM device-token endpoints:** register / refresh / delete a device push token against the account.
- [ ] **P1-for-push — Server push send:** FCM HTTP v1 send from the platform worker using the service-account key (**server-side only**). Needed for "friend request pops a push with the app closed."

### Data model / correctness
- [ ] **P3 — Sell un-held stock (oversell → record, don't reject).** *(Owner: MAIN DEV OWNS this — the app FOLLOWS the API shape.)* Platform building it as **#369**: a "sell anyway?" path with a required warning; uncovered portion counts **net 0%** (basis = sale price) by default + an **optional entry-price**; no shorts. API = an `allowUncovered` flag + optional entry basis on the sell endpoint.
- [ ] **P1 — Idempotency key on portfolio mutations.** `POST /portfolios/{id}/transactions`, `/cash/deposit`, `/cash/withdraw` have no idempotency field. The app hacks a ` [bt:<uuid>]` note-marker. Add an idempotency key → the app drops the hack. *(Platform: scheduled as V4-P2a — stays parked until v4 per the version plan; your marker workaround is the blessed interim.)*

### Release / Play Store publishing blockers (pairs with docs/TODO.md "Step 20")
- [ ] **P2 — Data-safety-form readiness pass on the privacy page** (platform side, before store submit): name the FCM push token once #368 ships and link the web deletion path. Page itself is live (see § DONE).

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

### Chat-scope "not effective on prod" — RESOLVED, no platform gap (audit 2026-07-10)
- [x] **P1 — chat:* 403s root-caused with DB evidence: prod is fine; activate by plain re-login (or just a token refresh).** The platform dev audited the exact DB snapshot prod was restored from (2026-07-09 21:12 UTC): migration `0027_mobile_chat_scopes` is recorded applied; the client ceiling holds **all 12 scopes** incl. `chat:read chat:write`; and **your account's grant AND refresh tokens already carried `chat:*`** (13 chat-scoped access tokens pre-snapshot, first at 19:26 UTC, grants re-consented 20:58/21:11 UTC). The consent-clamp mechanics you described are correct, but the ceiling was never missing — the 403s were almost certainly measured against prod mid-server-migration that night (prod moved machines 2026-07-09/10) or on a pre-consent token. Scope derivation happens fresh from the DB on every request (no cache, no restart needed), and `exchangeRefreshToken` re-derives scopes from the grant — **so even without a logout, the next refresh yields chat**. If any `/chat/*` still 403s after a refresh: (1) confirm the app targets the current prod host, (2) ask Christian for the 2-sec admin check (admin → OAuth apps → BetterTrackMobile → `chat:read chat:write` ticked — per the audit they are).

### Account deletion — in-app bearer + public web URL (platform #362 / PR #392) ✅ 2026-07-10
Both Play-blocker deletion asks shipped in one piece, chat messages anonymize on deletion.
- [x] **P1 — Delete account under bearer:** `DELETE /api/v1/account` — bearer with `account:security` (⚡ already in your activated set). Body per `deleteAccountRequestSchema` (contracts `auth.ts`): `{ confirmUsername, password?, code?, recoveryCode? }` — type-to-confirm username + one credential proof (password, or for 2FA accounts a fresh TOTP `code` / one `recoveryCode`). Rate-limited like login. On success every credential dies with the user (your token too — treat any post-delete call as logged-out).
- [x] **P1 — Public WEB deletion URL:** `https://web.bettertrack.at/account/delete` (route `account/delete` in the web app) — the URL for the Play Data-safety form.

### Custom-asset LIST endpoint (platform #387 / PR #391) ✅ 2026-07-10
- [x] **P2 — `GET /api/v1/custom-assets`** — ALL custom assets owned by the user, **including zero-holding ones**: id, name, category, currency, latest value point + its date. Bearer scope `portfolio:read` (same as the other `/custom-assets` routes). In `openapi.json` + contracts. Cross-user isolation covered by tests.

### Public privacy-policy page ✅ (live)
- [x] **P1 — Stable URL: `https://bettertrack.at/privacy/`** — real content (what we store / your data / your exit). Platform still owes the pre-submit completeness pass (see the § OPEN P2) — the URL itself is stable and safe to put in the Play form.

### Friend chat — endpoints + realtime + scopes (platform #349 + #386) ✅ 2026-07-09
Your chat UI (built + 100% stubbed) now has a real backend. **Swap the stubs → real adapters.** Needs the `chat:read`/`chat:write` ⚡ ACTIVATION re-login. Shapes in `packages/contracts` `chat.ts` + `openapi.json`.
- [x] **P2 — Chat endpoints** (#349): 1:1 **friend-only** conversations (one per pair) — list conversations + unread counts, paginated thread history, send message, mark-read. Non-friends → 404; unfriending closes the thread to new messages (history stays readable). **Share-in-chat chips:** send an asset ref or one of your shareable items as a **bare `(kind, subjectId)` reference** (no snapshot); the recipient resolves it through the sharing enforcement — an item not shared with them shows a **"not shared with you"** state with **no data**; **sending a chip never grants or widens access**. No groups/reactions/read-receipts.
- [x] **P2 — Realtime + unread** (#349): `chat.message` delivered over the existing `/ws` gateway to the recipient's `user:{id}` room as an **invalidation signal** (no body/chip crosses the socket → the app refetches, re-resolving chips through enforcement); **polling fallback** works when the gateway's off. Unread is per-participant, derived from last-read markers (survives reload). `chat.message` honors the notification matrix (muted → no bell, message still lands). ⚠️ **Presence-based suppression** (don't notify for a conversation you're actively viewing) is a **future** add via Notifications-v2 (#368) — not yet; today an open conversation still notifies.
- [x] **P2 — `chat:read` + `chat:write` GRANTED** to the client (#386). ⚡ ACTIVATION: add them to the app's authorize request + re-login → then chat goes live.

---

*Last updated: 2026-07-10 — platform dev: (1) **chat-scope mystery RESOLVED** — DB audit proves prod's ceiling/grants/tokens already carry `chat:*`; **just re-login or let the token refresh**, then chat is live end-to-end. (2) **Play blockers cleared:** `DELETE /api/v1/account` (bearer, type-to-confirm) + public web deletion page `web.bettertrack.at/account/delete` (#362) and privacy URL `bettertrack.at/privacy/` confirmed live. (3) **`GET /api/v1/custom-assets`** shipped (#387). (4) **WS bearer + direct websocket transport for `/ws` is IN BUILD right now** — your polling fallback stays valid until it lands. Heads-up: prod moved to a new machine 2026-07-09/10 — if you saw weirdness that night, retest before filing.*
