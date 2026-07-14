# Google Play release checklist — BetterTrack App

The step-by-step Play Console runbook for Christian's final submit. Pairs with
`docs/PLAY_LISTING.md` (copy/assets) and Step 20 in `docs/TODO.md`.

Legend: **[APP]** = already done on the app side (this repo) · **[OWNER]** =
Christian does it in the Play Console / on his account. Nothing below contains any
password — paths only.

---

## 0. Artifacts & versioning (produced by the app side)

| Thing | Value / path |
|-------|--------------|
| Release bundle (.aab) | `/Users/cwiesi/bt_scratch/step20-2026-07-14/BetterTrack-1.0-vc10001.aab` |
| Bundle in build tree | `app/build/outputs/bundle/playRelease/app-play-release.aab` |
| versionCode / versionName | **10001 / 1.0** |
| Signed with | **upload key** `~/.bettertrack/bt-play-upload.keystore`, alias `btupload`, RSA 4096 |
| Upload cert SHA-256 | `27:0C:3F:4D:B3:C7:CF:C8:C7:32:DB:94:66:E4:BE:94:D2:F0:55:C5:1A:39:72:EE:C6:E1:6D:4B:88:BD:3F:19` |
| Keystore passwords | `~/.bettertrack/play-signing.env` (chmod 600) — **never in the repo, never printed** |
| Application ID | `at.bettertrack.app` (identical for both flavors; no suffix — Firebase/OAuth depend on it) |
| Rebuild command | `./gradlew :app:bundlePlayRelease -PbtVersionCode=10001 -PbtVersionName=1.0` |

**Versioning scheme (keep the two channels disjoint):**
- **Play track** — versionCode **10001+**, versionName **1.x**. Bump the code on
  every Play upload (10002, 10003, …).
- **GitHub dev channel** — versionCode = CI run number, versionName `0.<run#>`
  (the rolling `latest-debug` APK). Deliberately below 10000 so it can never
  collide with or shadow a Play build.

> Regenerate the .aab whenever code changes: the upload key on this Mac signs it;
> a fresh clone or CI without `~/.bettertrack/` falls back to the debug key and
> will NOT produce an uploadable bundle (by design — the key never leaves this
> machine because the repo is public).

---

## 1. Create the app — **[OWNER]**
1. Play Console → **Create app**. Name **BetterTrack**, default language **English (US)**,
   type **App**, **Free**. Accept the declarations.
2. Category **Finance**. Confirm the app is not directed at children (target audience = adults).

## 2. Play App Signing enrollment — **[OWNER]** (app side prepared the key **[APP]**)
- Play App Signing is on by default for new apps. Google holds the **app signing
  key**; we upload with our **upload key** (generated app-side, above).
- On the first bundle upload Play will register `bt-play-upload.keystore`'s
  certificate as the upload certificate (SHA-256 above). Nothing to pre-upload;
  just make sure the very first .aab is the one signed with this key (it is).
- Keep `~/.bettertrack/bt-play-upload.keystore` + `play-signing.env` backed up
  **off the repo**. If the upload key is ever lost, Play supports an upload-key
  reset — but avoid that; back it up.

## 3. Closed testing track + the 12-tester / 14-day gate — **[OWNER]**
- Personal Play accounts created after 2023-11-13 need **≥12 testers opted in for
  14 continuous days** before production access. **Start this early.**
- Play Console → **Testing → Closed testing** → create a track → add the .aab →
  create an email tester list (≥12) → share the opt-in link → keep the test
  running ≥14 days. (Org / pre-2023-11-13 accounts are exempt — skip if so.)
- Roll out to the closed track first; production comes after the gate clears.

## 4. Store listing — **[OWNER]**, assets **[APP]**
- Copy: `docs/PLAY_LISTING.md` §2/§3 (EN + DE short + full descriptions — edit to taste).
- **Hi-res icon (512×512, 32-bit PNG):** `docs/play/icon-512.png` **[APP]**
- **Feature graphic (1024×500, no alpha):** `docs/play/feature-graphic-1024x500.png` **[APP]**
- **Phone screenshots (≥2, 8 recommended, EN + DE):** captured on the Note20 into
  `/Users/cwiesi/bt_scratch/step20-2026-07-14/screenshots/{en,de}/` **[APP]** — the
  owner picks the final set (real account data → they are NOT in the repo).
- Category **Finance**; contact email (owner decides — `support@bettertrack.at`
  suggested); website `https://bettertrack.at`; **privacy policy** `https://bettertrack.at/privacy/`.

## 5. Data Safety form — **[OWNER]** (answers must match the app — see §6 of PLAY_LISTING)
Declare exactly what the app does:
- **Collects:** account info (username, email); financial info (self-entered
  portfolio holdings/values/transactions/cash); device ID (FCM push token, only
  with notifications on); messages (friend chat, only if used).
- **All data encrypted in transit** (HTTPS/TLS): **Yes**.
- **Users can request deletion:** **Yes** — in-app (Settings → Account → Delete
  account) **and** web `https://web.bettertrack.at/account/delete`.
- **Data shared with third parties:** **No**. **Ads:** **No**. **Analytics/crash
  SDK:** **No** (none bundled — verified: no Sentry/Crashlytics/analytics dep).
- **Advertising ID:** **No** — the `AD_ID` permission is stripped from the manifest
  (see §8), so answer "does not use an advertising ID".

## 6. Content rating (IARC) — **[OWNER]**
- Run the IARC questionnaire (draft answers in `docs/PLAY_LISTING.md` §5): no
  violence/sex/profanity/gambling; **user-generated content = Yes** (1:1 friend
  chat + sharing, no public feeds); no location sharing; no digital purchases.
  Expected result **Everyone / PEGI 3**.

## 7. Financial-features declaration — **[OWNER]**
- Declare: **portfolio tracker only**; does **not** execute trades, and does **not**
  move/transmit money → no money-transmitter licence implied. (No brokerage,
  no payments, no crypto exchange.)

## 8. Permissions audit — **[APP]** (verified on the merged manifest + packaged APK)
`play` release ships ONLY the set below. `github` additionally holds
`REQUEST_INSTALL_PACKAGES` (the dev self-update permission), which is **absent**
from `play`. `AD_ID` is **absent** from both (stripped via `tools:node="remove"`).

| Permission | In play release? | Source | Why it's there |
|------------|:---:|--------|----------------|
| `INTERNET` | ✅ | app | Talk to the BetterTrack API. |
| `ACCESS_NETWORK_STATE` | ✅ | app | Detect offline for the offline-first sync. |
| `POST_NOTIFICATIONS` | ✅ | app | Android 13+ runtime notif permission, asked **in context** (never on first launch). |
| `USE_BIOMETRIC` | ✅ | app | Optional fingerprint/face app-lock (local only; no biometric data leaves the device). |
| `USE_FINGERPRINT` | ✅ | androidx.biometric | Legacy (API 28) fingerprint path the biometric lib injects for the same app-lock. |
| `FOREGROUND_SERVICE` | ✅ | androidx.work | WorkManager declares it; the sync uses background WorkManager jobs. No app-started user-visible FGS type is declared. |
| `WAKE_LOCK` | ✅ | Firebase Messaging / WorkManager | Wake to deliver a push / finish a queued sync. |
| `RECEIVE_BOOT_COMPLETED` | ✅ | Firebase Messaging | Re-register FCM after a reboot. |
| `com.google.android.c2dm.permission.RECEIVE` | ✅ | Firebase Messaging | Receive FCM push messages. |
| `at.bettertrack.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ✅ | androidx.core | Auto-added signature-level self-permission for runtime-registered non-exported receivers. App-internal, not user-facing. |
| `REQUEST_INSTALL_PACKAGES` | ❌ (github only) | app (github flavor) | Dev self-update install. Forbidden on Play → excluded from `play`. |
| `com.google.android.gms.permission.AD_ID` | ❌ (removed) | — | No ads/analytics; stripped with `tools:node="remove"` so the Data Safety "no advertising ID" answer holds. |

No location, contacts, SMS, storage, call-log, or other sensitive/restricted
permissions → the Data Safety form stays small and no sensitive-permissions
declaration / video review is triggered.

## 9. Target API level — **[APP]**, re-verify **[OWNER]**
- Play requires new submissions to target **API 35 (Android 15)** minimum. The app
  targets **SDK 36** (compileSdk 37, minSdk 28), which satisfies it. Re-check
  Play's rolling target-API policy at submit time.

## 10. Account deletion is LIVE — **[APP]**
- In-app: **Settings → Account → Delete account** → type-to-confirm (exact
  username) + password re-auth → `DELETE /api/v1/account`
  `{ confirmUsername, password }`. On success the token is dead → full local wipe
  → login screen. `DeleteAccountFeature.armed = true`.
- Verified safely on the owner's real account: correct username + **deliberately
  wrong password** → server **401**, surfaced inline, account intact (see the
  device-verification note in the build report). Success/error mapping covered by
  `AccountRepositoryDeleteTest` (MockWebServer).
- Web deletion path (Play's second required channel): `https://web.bettertrack.at/account/delete`.

## 11. Final submit — **[OWNER]**
- Upload `BetterTrack-1.0-vc10001.aab` to the closed track (then, after the gate,
  promote to production).
- Confirm: Data Safety complete + consistent with `https://bettertrack.at/privacy/`;
  content rating done; financial-features declared; all listing assets in; both
  deletion paths working; the 12-tester / 14-day closed test running or complete.

---

## Build / QA gates that passed on the app side (evidence)
- `:app:assembleGithubDebug`, `:app:assemblePlayRelease`, `:app:bundlePlayRelease` — all clean.
- Full unit suite on `testGithubDebugUnitTest` — **386 tests, 0 failures** (352 prior + 34 new).
- .aab signature = the upload key (SHA-256 above) — confirmed via `keytool -printcert` on `META-INF/BTUPLOAD.RSA`.
- Merged-manifest permission audit — table in §8.
- `github` is the default Android Studio flavor (alphabetical); `play` compiles the
  self-update checker/UI/permission out (`BuildConfig.SELF_UPDATE_ENABLED=false`).
