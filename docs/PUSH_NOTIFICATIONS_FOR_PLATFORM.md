# How to send push alerts to the BetterTrack mobile app (Firebase / FCM)

**For the main platform dev.** The mobile app's push **client** is fully built (Step 16): it obtains an FCM token, handles incoming pushes, shows a system notification with a deep-link tap, and keeps an in-app inbox. To make *"the platform sends an alert → it pops on the user's phone"* work, the **platform** needs to do 3 things. This is the exact contract the app already implements — build to it and it lights up with no app change.

---

## The Firebase project
- **Project:** `bettertrackapp-c6996` (the app's `google-services.json` is already wired to it — the app pulls a real FCM token today).
- **Service-account key** (to authenticate FCM HTTP v1 sends): Christian has it — client email `firebase-adminsdk-fbsvc@bettertrackapp-c6996.iam.gserviceaccount.com`. **Server-side ONLY — never commit it to any repo.** Load it from env / secret storage in the worker.

## 1. Store device tokens — build a device-token endpoint
The app gets an FCM device token per install and needs to register it against the account so you know where to send. The app's `PushTokenManager.registerWithPlatform(token)` is wired and just stubbed — it POSTs the moment an endpoint exists. Please build (bearer-auth):
- **`POST /notifications/devices`** body `{ "token": "<fcm-token>", "platform": "android" }` → upsert `(account_id, token)`. A user can have several devices — store all active tokens.
- **`DELETE /notifications/devices`** body `{ "token": "<fcm-token>" }` → remove on logout.
- Token rotation: the app re-POSTs on `onNewToken` — upsert by token. When a send returns `404`/`UNREGISTERED`, prune that token.
- Scope: guard it with a notifications scope. The app requests scopes at login — **tell us which scope to require** (likely `notifications:write`, or reuse `account:security`) and grant it to the `BetterTrackMobile` client; we'll add it to the app's authorize request.

## 2. Send via FCM HTTP v1
From the worker, send to the user's stored token(s) using **FCM HTTP v1** — `POST https://fcm.googleapis.com/v1/projects/bettertrackapp-c6996/messages:send` — authenticated with the service-account key (or the `firebase-admin` SDK, which wraps this).

## 3. The message payload — send a **DATA** message (this is the important part)
The app reads `message.data` in `onMessageReceived` to build the notification with the correct channel + deep-link and to add it to the in-app inbox. **Put the fields in the `data` block** (all FCM `data` values are strings):

```json
{
  "message": {
    "token": "<the user's fcm token>",
    "data": {
      "type":    "friend.request",
      "title":   "New friend request",
      "body":    "@alice wants to connect with you.",
      "payload": "{\"portfolioId\":\"abc123\"}"
    },
    "android": { "priority": "high" }
  }
}
```
- `type` — **required**, drives the channel + deep-link + the user's mute matrix.
- `title`, `body` — **required**, shown on the notification.
- `payload` — **optional**, a **stringified JSON** with the type-specific deep-link id(s) (table below).

### `type` values the app understands (and where a tap goes)
| `type` | tap opens | `payload` (stringified JSON) |
|---|---|---|
| `friend.request`   | Social tab              | — |
| `friend.accepted`  | Social tab              | — |
| `portfolio.shared` | the shared portfolio    | `{"portfolioId":"<id>"}` (or `"id"`) |
| `alert.triggered`  | the asset page, or the held position | `{"assetId":"<id>"}` (or `"symbol"`); add `"portfolioId"` to open the holding instead |
| `chat.message`     | the conversation        | `{"conversationId":"<id>"}` |
| `account.invite`   | Account settings        | — |
| `account.temp_password` | Security settings  | — |
| anything else / `system` | the inbox only    | — |

- The app **auto-maps `type` → channel** (`bt_social` / `bt_portfolio` / `bt_account` / `bt_general`) and builds the notification itself — **you do NOT need to set a channel.**
- `payload` **must be a JSON string** (not a nested object) — FCM `data` values are strings; the app `JSON.parse`s it.

### Delivery-mode note (data-only vs. notification block)
The app's handler runs on the `data` block. Data messages deliver reliably in foreground/background; when the app is **force-killed**, Android may deprioritize *data-only* pushes. If guaranteed display on a killed app matters, **also include a `notification` block** (`{"title":..,"body":..}`) alongside `data` — Android displays that when killed, and the `data` (type/payload) still rides along for the tap. The app prefers `data.title/body` and falls back to the `notification` block, so including both is safe. **Recommendation:** `android.priority: "high"` + both blocks for user-facing alerts.

## 4. (Related) the in-app inbox — server-persisted list
Push is the real-time nudge; the app also shows a persistent **inbox** behind the bell that reads **`GET /notifications`** (already wired in the app, currently `403` until the client has a notifications-read scope). So please also: (a) persist notifications server-side + expose `GET /notifications` (+ a mark-read), and (b) the per-type prefs matrix `GET/PATCH /settings/notifications`. Same `type` taxonomy as above.

## Test loop (once §1 + §2 exist)
1. App logs in → POSTs its FCM token to `/notifications/devices`.
2. Worker sends a `friend.request` **data** message with `android.priority:"high"` to that token.
3. Expect: it pops on the phone → tap → deep-links to the Social tab → also appears in the in-app inbox.

The whole on-device pipeline (mute-gate → inbox insert → system notification → deep-link tap) is already verified via a local "simulate" that runs the identical `onMessageReceived` path — so once the token endpoint + sender exist, it should work first try.

---
*Maintained by the mobile-app coordinator. The app-side client is complete; only §1 (device-token endpoint) and §2 (FCM sender) are platform work. These are also tracked in `PLATFORM_ASKS.md` (the "FCM device-token endpoints" + "Server push send" items).*
