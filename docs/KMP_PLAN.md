# BetterTrack — Kotlin Multiplatform / iOS program plan

Owner order: rewrite the Android app into Kotlin Multiplatform so it runs on an
iPhone 16 (iOS 18-class), from a SINGLE codebase that keeps shipping Android.
Delivery target for now: free-Apple-ID on-device deploy, simulator-first.

This document is the program log for branch `kmp-ios`. It is written and owned
by the KMP sub-coordinator. It is NOT a main-tree document: `PLATFORM_ASKS.md`
and `docs/TODO.md` belong to the chief and are never edited from this branch.

---

## 0. Territory and rules (standing)

- Worktree: `/Users/cwiesi/AndroidStudioProjects/BetterTrack_KMP`, branch `kmp-ios`.
- The main tree `/Users/cwiesi/AndroidStudioProjects/BetterTrack_App` (branch
  `main`) is the chief's. Never touched from here. Never commit to `main`.
  Never merge `kmp-ios` into `main` — that is an owner gate. Merging `main`
  INTO `kmp-ios` at phase boundaries is allowed and encouraged.
- Commits authored as Christian Wiesinger <chris.dev.at@gmail.com>. No
  AI-attribution text anywhere. Public repo: secret-scan before every push.
- Devices: the iOS SIMULATOR is this program's device. The physical Android
  phone on adb belongs to the chief and is never touched from here. Android
  verification = JVM unit suite + assembles.
- Infra rule: large single-file code emissions get killed mid-generation.
  All code is written in small incremental edits and all plans/progress live
  on disk so a killed run resumes.

---

## 1. Verified baseline (2026-08-09)

Established by the sub-coordinator before any code change, as the regression
harness the whole program is measured against.

| Fact | Value |
| --- | --- |
| Branch point | `e98003d`, identical to `main` — zero divergence at start |
| Android unit suite | **2637 tests / 169 suites / 0 failures / 0 errors / 7 skipped** |
| Test task | `:app:testGithubDebugUnitTest` |
| Android assemble | `:app:assembleGithubDebug` BUILD SUCCESSFUL, 94 MB APK |
| Worktree fix applied | `local.properties` recreated (gitignored, absent in a fresh worktree) |

**The gate for every phase: this suite stays at 0 failures and the app still
assembles.** Any phase that cannot hold that line is redesigned, not forced.

### Toolchain present on this Mac

| Tool | Version |
| --- | --- |
| macOS | darwin 24.5.0, Apple Silicon |
| Xcode | 16.3 (16E140) |
| iOS runtime | 18.4; iPhone 16 / 16 Pro / 16 Pro Max / 16e / 16 Plus simulators |
| JDK | 17.0.18 (Homebrew) |
| Gradle | 9.4.1 (wrapper) |
| Homebrew | present |
| kdoctor / CocoaPods | NOT installed at start |

### The app as built today

| Aspect | Value |
| --- | --- |
| Module structure | single `:app` module, ~494 Kotlin files, ~145k LOC |
| AGP | 9.2.1, using AGP's **built-in Kotlin** 2.2.10 (no `kotlin-android` plugin) |
| Compose | BOM 2026.06.01, compose-compiler plugin 2.2.10 |
| Persistence | Room 2.8.4, schema **v10** with a real migration chain |
| Network | Retrofit 2.11 + OkHttp 4.12 + kotlinx-serialization 1.9.0 |
| Crypto | BTVAULT1 — Argon2id via BouncyCastle 1.80 (**JVM-only**) + Kotlin fflate port |
| Other | WorkManager, DataStore, FCM, BiometricPrompt, Custom Tabs, ZXing |
| SDK | minSdk 28, targetSdk 36, compileSdk 37 |
| Flavors | `github` (self-update, server picker) and `play` (neither) |

---

## 2. Standing risk register

Ordered by how badly each one can hurt the program. Status updated per phase.

| # | Risk | Why it matters | Status |
| --- | --- | --- | --- |
| R1 | AGP 9 built-in Kotlin vs the KMP plugin | KMP is built on the `kotlin.sourceSets` DSL, which AGP 9's built-in Kotlin rejects by default — the project already carries `android.disallowKotlinSourceSets=false` as an escape hatch for KSP. If these cannot coexist, the whole module strategy changes. | UNDER TEST |
| R2 | Argon2id byte-identity on iOS | BouncyCastle is pure Java, unusable on Kotlin/Native. Vault data is unreadable if the KEK differs by one byte. | **RESOLVED** — achievable; RFC 9106 makes byte-identity a property of the spec. Route chosen in D2 |
| R3 | Room schema continuity for existing Android users | Android users sit at schema v10; a broken migration chain corrupts real installs. | OPEN — but Room usage is unusually clean (§4.4). **`exportSchema = false` is the live sub-risk: no golden schema JSON exists** |
| R4 | CMP maturity for the custom shell | The pager-tab + gesture-tuned sheet navigator is hand-built and heavily tuned. | NARROWED — shell is ~90% portable pure Compose; risk concentrates in **one 153-LOC file**, `BtSheetNavigator.kt` (see R7) |
| R5 | Android regression on this branch | The hardest constraint of the program. | CONTROLLED — 2637-case gate per phase; **weakened by CI running no tests (§4.8), fixed in P1** |
| R6 | Background work + push differ fundamentally on iOS | WorkManager has no iOS equal; FCM/APNs differ. | OPEN but SOFTENED — both workers are one-shot and idempotent with engine-owned retry, so iOS loses latency, not data |
| R7 | `BtSheetNavigator` subclasses navigation-compose internals-adjacent APIs | `Navigator.Name`, `NavigatorState.backStack`, `FloatingWindow`, `NavDestinationBuilder`, `NavBackStackEntry.LocalOwnersProvider` must behave identically in JetBrains' multiplatform artifact, or all 47 sheet routes need a new foundation. | OPEN — **mandatory spike before any shell work**; fallback (hand-rolled stack) is ~15 d, and the codebase already contains the pattern |
| R8 | Byte-identity formatters (§4.2) | Two of the three decide encrypted bytes; one ULP of disagreement silently corrupts user data rather than throwing. | OPEN — highest correctness risk in the program. Addressed by D1 |
| R9 | `RawDeflate` sort stability on Kotlin/Native | If `sortWith` is not stable on Native, Huffman ties break differently and every vault envelope diverges. | OPEN — cheap to test, must be tested **first** (D3) |
| R10 | Two capabilities blocked on owner actions | Drive needs a Google Cloud OAuth client (exists on no platform); iOS push needs a Firebase iOS app + APNs `.p8` + entitlements. | OPEN — not on the critical path, but has lead time; raise early |

---

## 3. Phase gates

Every gate = Android suite green + iOS builds + the phase deliverable
demonstrated in the simulator, with screenshot evidence under
`/Users/cwiesi/bt_scratch/kmp-<date>/`. Each gate is a pushed commit.

- **P0 — survey + architecture.** Codebase map, toolchain proven empirically,
  architecture decided and written here. Gate: this document, with decisions
  backed by a probe build that actually ran.
- **P1 — buildable skeleton.** KMP module structure, Android target builds THE
  SAME APP green, iOS app boots to a real screen in the simulator.
- **P2 — shared core migration.** Models/API/persistence/sync in common with
  expect/actual. Android suite green throughout. iOS compiles the shared core.
  Conformance vectors replay green on BOTH platforms.
- **P3 — UI.** Compose Multiplatform screens on iOS, the shell ported, login
  against the dev stack working in the simulator, core journeys: login →
  portfolio → charts → transactions.
- **P4 — device-ready.** Free-Apple-ID cable deploy documented and scripted;
  report upward to request the owner's iPhone session. Do not block on it.

Beyond P4: feature parity planned honestly per capability (notifications,
widgets, biometrics, Drive), never improvised.

---

## 4. Codebase map (P0 survey, 2026-08-09)

Five parallel surveys. Every count below was re-verified; where a survey
disagreed with the briefing or with another survey, the measured value is
recorded and the discrepancy noted.

### 4.1 Size, corrected

| Layer | Files | LOC | Free of `android.*` |
| --- | --- | --- | --- |
| `ui/` (+`navigation/`, `MainActivity`) | 160 (+3) | ~67,150 | 144 of 160 (90%) |
| `data/` | 111 | 23,396 | 70 of 111 (63%) |
| `vault/` | 28 | 8,810 | 21 of 28 (13 fully platform-clean) |
| `domain/` | 7 | 4,489 | 7 of 7 (**100%**) |
| `sync/` | 9 | 1,782 | 4 of 9 (57% of LOC) |
| **main total** | **323** | **~145k** | **127 of 323** |

The briefing's per-package file counts were all high (they counted tests).
Test suite: 2000 `@Test` methods in 170 JVM files; three
`@RunWith(Parameterized)` suites expand the 622 domain vectors, giving the
2637 runtime cases measured. **No Robolectric; nothing needs a device.**

### 4.2 The three byte-identity formatters (sharpest correctness risk)

Money/number rendering must agree with the web client exactly. Three separate
functions carry that contract and **two of them decide encrypted bytes**:

| Site | Feeds | JVM dependency | Why dangerous |
| --- | --- | --- | --- |
| `domain/DomainTypes.kt:104` `jsNumberToString` | vault **plaintext** + **GCM AAD header bytes** (via `VaultJson`) | `String.format(Locale.ROOT, "%.Ne", v)` | one-ULP disagreement = a vault the web client cannot open; Native `Double.toString` is not JS-compatible |
| `vault/VaultEntityGraph.kt:179` `moneyString` | vault **payload** strings compared by `canonicalJson` in the merge path | `toBigDecimal().stripTrailingZeros().toPlainString()` + `Math.floor/abs` | its KDoc: "the string IS the value" — two spellings of one trade produce phantom merge conflicts |
| `ui/format/BtNumberFormat.kt` (194 LOC) | every money label in the UI | `java.text.NumberFormat` (ICU), `BigDecimal`+`MathContext`, `Currency.getSymbol`, `ThreadLocal` | contractually byte-identical to web (PLATFORM_ASKS #18/#19) |

`moneyString` was missed by every import-based sweep because `toBigDecimal()`
is a stdlib extension and `Math.` needs no import — see 4.3.

### 4.3 The invisible JVM tail

Import greps systematically under-count. Measured directly over
`app/src/main/java`:

| Pattern | Count | Note |
| --- | --- | --- |
| fully-qualified `Math.` | 23 (9 real code sites) | all map to `kotlin.math`, but invisible to import sweeps |
| `synchronized(...)` | 14 | unavailable on Native — surveys reported 5 |
| `@Volatile` | 22 | |
| `::class.java` / `javaClass` | 12 / 5 | JVM reflection |
| `ThreadLocal` | 6 | Java-only; formatter caching |
| `System.currentTimeMillis` | 24 | |
| `toBigDecimal` | 1 | on the byte-identity path |

**Standing rule: every portability claim is verified by behaviour or by a
non-import grep, never by an import list alone.**

### 4.4 Per-layer verdicts

- **`domain/` — ports nearly free.** Arithmetic is **100% `Double`**; zero
  `BigDecimal`/`BigInteger`/`MathContext`/`setScale`. Eight `java.*` import
  lines total. Dates cross the public API only as ISO `String`s through four
  internal shims, so `kotlinx-datetime` touches ~7 lines and changes no public
  signature. The single quantizer `floorCents` uses `kotlin.math.floor`,
  bit-identical on Native. **3372 of 4489 LOC need no edits.**
- **`sync/` — the seams already exist.** `interface OpStore` is Room-free and
  Android-free. Conflict handling is idempotency-key replay, not a reconcile
  pass: re-executing an ambiguous op *is* the reconcile. `SyncEngine` has no
  dependency on `SyncScheduler` (direction is Worker → Engine), so the engine
  is already schedule-agnostic. WorkManager is confined to two files.
- **`data/` — Room is clean, the network layer is the bulk.** 189 Retrofit
  endpoints; 261 `@Serializable` DTOs across 20 files already platform-free.
  No DataStore anywhere (10 plain `SharedPreferences` stores). Room v10: 18
  entities, 13 DAOs, **zero** `@TypeConverter`/`@RawQuery`/FTS/autoMigration/
  callback/WAL/multi-process. Whole Room platform seam = `SupportSQLiteDatabase`
  at 11 sites in one 341-LOC file.
- **`ui/` — 90% android-free, but the bulk cost lives here.** 4 pager tabs + 47
  typed sheet routes. Charts 100% hand-drawn Canvas (free). 41 ViewModels with
  zero `AndroidViewModel`/`SavedStateHandle`/`Context` (free). Costs: 2274 `R.`
  refs across 93 files, 144 `material-icons-extended` symbols, 54
  `LocalContext` uses in 26 files.

### 4.5 Platform capability findings

- Exactly **one** deep link: `bettertrack://oauth/callback`. No App Links, no
  notification URL routing. iOS = one `CFBundleURLTypes` entry +
  `ASWebAuthenticationSession`. **No Universal Links work at all.**
- **4** notification channels, not six. The "six" is the server's delivery
  matrix (inapp/email/push/webpush/telegram/discord) — a different axis that
  ports unchanged as data. Channels have no iOS equivalent; that per-family
  tuning is simply lost.
- **2 WorkManager workers**, both one-shot, idempotent, always
  `Result.success()`; retry is engine-owned. iOS degrades to foreground-drain +
  connectivity-drain + opportunistic BGAppRefresh with **no data loss, only
  latency**.
- **Google Drive is the most portable code in the repo** — plain OkHttp over
  Drive REST v3, deliberately no Play Services, MockWebServer-testable.
- No widgets, no Glance, no PowerManager, no Coil/Glide, no `AndroidView`.
- **Zero `@Preview`s and zero UI tests** — the porting loop will be
  device-driven.

### 4.6 Blockers that are owner actions, not engineering

Both have lead time; neither is on the critical path. Start them early.

1. **Google Cloud OAuth client for Drive.** The shipped provider is
   `SignedOutGoogleAuthProvider`, which always returns null — the OAuth client
   does not exist on *any* platform yet.
2. **Push on iOS.** Needs an iOS app in the Firebase project, an APNs `.p8` key
   uploaded, and Push Notifications + Background Modes entitlements. None
   exist. (`app/google-services.json` exists and is git-tracked; there is no
   `GoogleService-Info.plist`.)

### 4.7 Two redesigns, not ports

- **App-lock PIN custody.** `AppLockCrypto` HMACs the salted PIN under a
  non-exportable AndroidKeyStore HMAC key. The Secure Enclave is **EC-P256 only
  and cannot hold HMAC keys** — the custody design must be rethought, not
  translated.
- **In-app language switching.** `LocaleManager.wrap`/`attachBaseContext`/
  `recreate()` is Android resource-framework mechanics with no iOS analogue.
  Becomes a locale `CompositionLocal` driving CMP resource lookup.

### 4.8 Infrastructure gap found during the survey

**CI does not run tests.** `.github/workflows/android-apk.yml` runs
`:app:assembleGithubDebug` and publishes releases — no `test`, no `lint`, no
`check`. The 2637-case suite is a local-only gate today. A port of this size
without CI-enforced regression is unacceptable, so **adding a test job is a P1
prerequisite**. Equally true on `main`; the chief should know.

---

## 5. Architecture decisions

Decisions resting only on the surveys are recorded now. Decisions gated on the
empirical toolchain probe (module layout, persistence engine, network client)
land when the probe reports — asserting them first would be guessing.

### D1 — Money/number formatting becomes a first-class, vector-gated seam

The three formatters in §4.2 are the program's sharpest correctness edge and
two of them decide encrypted bytes. Therefore:

- One shared `expect`/`actual` shortest-round-trip double formatter serves
  `jsNumberToString`; it must be **bit-exact**, not approximately right.
- `moneyString` loses `BigDecimal` and is rebuilt on that same shim.
- Golden vectors are captured from the current JVM implementation **and** from
  the web client, and become a `commonTest` suite running on Android AND iOS,
  **before** either formatter is rewritten.

Rationale: these are the only places where being one ULP wrong silently
corrupts user data instead of throwing.

### D2 — Argon2id: literal pure-Kotlin translation of BouncyCastle, in `commonMain`

**R2 is resolved: byte-identical Argon2id on iOS is achievable.** The vault
uses only the lightweight BC API — two classes (`Argon2BytesGenerator`,
`Argon2Parameters`) at one call site, `VaultCrypto.kt:137`, with a pinned
profile (ARGON2_id, **version 0x13 explicit**, m=65536 KiB, t=3, p=1, len=32,
16-byte salt, empty secret, empty AD). The JCA provider is never registered.

Decision: **translate `Argon2BytesGenerator` + `Blake2bDigest` literally into
common Kotlin (~1400-1500 LOC) and retire BouncyCastle from both platforms.**

Rationale:
1. Argon2id is fully specified (RFC 9106): for a fixed profile every correct
   implementation yields the same 32 bytes. Byte-identity is guaranteed by the
   **specification**, not by sharing an implementation. Categorically unlike
   DEFLATE, where encoder choices are implementation-defined.
2. It matches the discipline already in this codebase — `RawDeflate.kt` is an
   855-LOC literal port with per-function source-line markers, proven by
   vectors. Whoever can audit that file can audit this one.
3. BC's Argon2 is unusually good translation material: self-contained,
   single-threaded even at p>1, dependency surface of six `Pack` methods and
   two `Arrays` methods; `Longs.rotateRight` maps to a Kotlin intrinsic.
4. One implementation = one test surface. The existing JVM
   `VaultConformanceTest` proof transfers directly rather than being
   re-established per platform.
5. Retires an ~8 MB dependency whose only job is one function.

Guardrails:
- Flatten BC's `Block[65536]` of `long[128]` into **one contiguous
  `LongArray(65536*128)`** — 65k small objects would punish Native's GC.
- 64 MiB working set is fine in a foreground app but **exceeds an iOS app
  extension budget (~50 MB)**: vault unlock must never run in a share or
  widget extension.
- **Measure before committing.** If a real iPhone-class device exceeds ~2.5 s,
  swap a **libsodium cinterop actual for iOS only** (`crypto_pwhash`,
  `ALG_ARGON2ID13`, opslimit=3, memlimit=67108864). The existing
  `fun interface Argon2Derive` seam makes that a one-line change; the
  pure-Kotlin version stays as the cross-check.
- Never adopt an unvetted third-party KMP argon2 for the one function standing
  between a user and their vault.

The rest of the crypto is easier, because output is uniquely determined by
input: AES-GCM → CommonCrypto/CryptoKit; `SecureRandom` → `SecRandomCopyBytes`;
SHA-256 → `CC_SHA256`; Base64 → `kotlin.io.encoding.Base64`, which must
reproduce `VaultBytes`' double canonicality check (shape regex **and**
re-encode round-trip).

### D3 — DEFLATE outranks Argon2 on the risk register

Two line-level items must be settled before any vault code is written:

1. **`RawDeflate.kt:316` and `:351` — sort stability on Kotlin/Native.** The
   file header states stability is *required*: comparators tie on equal
   frequency and V8's sort is TimSort. `MutableList.sortWith` is stable on the
   JVM; if it is not on Native, Huffman ties break differently and **every
   envelope diverges**. Verify first, with a test, before writing anything
   else in `vault/`.
2. **`RawDeflate.inflate` is not a port** — it delegates to
   `java.util.zip.Inflater`. Needs a Native inflater (`platform.zlib`,
   `inflateInit2(-15)`). Low byte-identity exposure: inflate output is
   uniquely determined by its input.

### D4 — Vault features gate off on iOS rather than ship approximate crypto

The owner's rule encoded as a capability flag rather than a hope: if
byte-identity is not *demonstrated* by the conformance vectors replaying green
on iOS, vault features are disabled on iOS behind a clean flag. No
approximation, no "close enough", no ship-and-fix-later.

### D5 — The conformance vectors become the cross-platform gate

622 domain vectors + the vault crypto/deflate fixtures are the oracle. Three
concrete blockers to running them on Kotlin/Native, with the chosen answers:

| Blocker | Answer |
| --- | --- |
| `org.junit.*` (34 `@Test`, `Assert.*`) | move to `kotlin.test` |
| `@RunWith(Parameterized::class)` — no Native equivalent | collapse each into one test looping the vector list, with the vector id in every assertion message so failures stay diagnosable |
| `javaClass.getResourceAsStream` — 9 sites; Native has no classpath resources | **code-generate the JSON into Kotlin constants at build time**, so the suite stays hermetic and the generator can assert the pinned platform commit hash |

---

## 5. Program log

- **2026-08-09** — P0 opened. Baseline captured (2637 green, assemble green).
  `local.properties` recreated in the worktree. Five parallel surveys
  dispatched: data layer, vault/domain/sync, UI, Android platform surface,
  and an empirical toolchain probe (AGP9+KMP, CMP, Room KMP, Ktor).
- **2026-08-09** — Four surveys returned; codebase map written (§4).
  Sub-coordinator verification caught two things the surveys missed:
  `moneyString` (`VaultEntityGraph.kt:179`) is a **third** byte-identity
  formatter using `BigDecimal`, invisible to import-based sweeps; and the
  wider "invisible JVM tail" of fully-qualified `Math.`, `synchronized`,
  `ThreadLocal` and reflection (§4.3). Standing rule adopted: verify
  portability by behaviour or non-import grep, never by import lists.
  R2 resolved (D2). Risk register extended to R10. Toolchain probe still
  running — module/persistence/network decisions deliberately deferred.
