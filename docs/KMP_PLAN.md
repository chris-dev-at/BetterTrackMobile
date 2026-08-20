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
| R3 | Room schema continuity for existing Android users | Android users sit at schema v10; a broken migration chain corrupts real installs. | **CLOSED for the move** (§13, `c47b6f5`) — DB moved to `:shared` with golden v10 identityHash `a9fab166…` byte-identical, Android migrations + test verbatim/unchanged (7/7), shipping graph byte-identical, iOS DB opens+queries on the simulator |
| R4 | CMP maturity for the custom shell | The pager-tab + gesture-tuned sheet navigator is hand-built and heavily tuned. | NARROWED — shell is ~90% portable pure Compose; risk concentrates in **one 153-LOC file**, `BtSheetNavigator.kt` (see R7) |
| R5 | Android regression on this branch | The hardest constraint of the program. | CONTROLLED — 2637-case gate per phase; **weakened by CI running no tests (§4.8), fixed in P1** |
| R6 | Background work + push differ fundamentally on iOS | WorkManager has no iOS equal; FCM/APNs differ. | OPEN but SOFTENED — both workers are one-shot and idempotent with engine-owned retry, so iOS loses latency, not data |
| R7 | `BtSheetNavigator` subclasses navigation-compose internals-adjacent APIs | `Navigator.Name`, `NavigatorState.backStack`, `FloatingWindow`, `NavDestinationBuilder`, `NavBackStackEntry.LocalOwnersProvider` must behave identically in JetBrains' multiplatform artifact, or all 47 sheet routes need a new foundation. | **RESOLVED GREEN** (§8) — every API present and public; the file compiled **unchanged**; `FloatingWindow` behaviorally honored on-device. Hand-rolled fallback not needed |
| R8 | Byte-identity formatters (§4.2) | Two of the three decide encrypted bytes; one ULP of disagreement silently corrupts user data rather than throwing. | **1 of 3 CLOSED** — `jsNumberToString` proven byte-identical on-device, 10/10 (§11). `moneyString` and `BtNumberFormat` still open |
| R9 | `RawDeflate` sort stability on Kotlin/Native | If `sortWith` is not stable on Native, Huffman ties break differently and every vault envelope diverges. | OPEN — cheap to test, must be tested **first** (D3) |
| R10 | Two capabilities blocked on owner actions | Drive needs a Google Cloud OAuth client (exists on no platform); iOS push needs a Firebase iOS app + APNs `.p8` + entitlements. | OPEN — not on the critical path, but has lead time; raise early |
| R12 | **Pre-existing** JVM-vs-V8 number rendering divergence on Android | OpenJDK 17's `Double.toString` (pre-JDK-19 `FloatingDecimal`) emits a non-shortest representation for a small class of large 17-significant-digit doubles, so Android's `jsNumberToString` can differ from the web client **today**, independent of the port. Discovered by the P2 formatter work. | OPEN — **not introduced by this branch and not urgent** (unreachable by realistic money/quantity values, hit by no vector). Needs an owner/chief source-of-truth call — see §11 |

---

## 3. Phase gates

Every gate = Android suite green + iOS builds + the phase deliverable
demonstrated in the simulator, with screenshot evidence under
`/Users/cwiesi/bt_scratch/kmp-<date>/`. Each gate is a pushed commit.

- **P0 — survey + architecture.** [MET 2026-08-10] Codebase map, toolchain
  proven empirically, architecture decided and written here. R1, R2, R3, R7 all
  resolved green with on-device evidence.
- **P1 — buildable skeleton.** [MET 2026-08-10, commit `0df16e6`] KMP module
  structure, Android target builds THE SAME APP green (2637/0 both flavors,
  both APKs), iOS app boots on the simulator rendering real shared-domain
  output (`resolvePortfolioSetting` cascade). Screenshot:
  `/Users/cwiesi/bt_scratch/kmp-2026-08-10/ios_verify_coordinator.png`.
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

**DONE for the 622 DOMAIN vectors, 2026-08-10 (commit `1badadf`, §12).** All
three blockers resolved as planned and the vectors replay byte-exact on
Kotlin/Native. (The vault crypto/deflate fixtures are still to come, in the
vault phase.)

| Blocker | Answer | Outcome |
| --- | --- | --- |
| `org.junit.*` | move to `kotlin.test` | done — but JUnit's `assertEquals(d,d,delta)` NaN/-0.0 semantics had to be reimplemented via `toBits()`; kotlin.test's overload would silently reject NaN vectors |
| `@RunWith(Parameterized::class)` — no Native equivalent | collapse each into one looping test with the vector id in every message | done; failures are collected not fail-fast, so a systematic divergence shows its full shape |
| `getResourceAsStream` — Native has no classpath resources | code-generate the JSON at build time, assert the pinned commit | done via `:shared:generateVectorFixtures`; the 64 KB string-constant ceiling forced <=16000-char chunking joined at runtime, guarded by an FNV-1a-64 rejoin hash |

---

## 6. Toolchain probe results (empirical, 2026-08-09/10)

Six probe projects built on this Mac. **Everything below was executed, not read
in documentation.** The probe agent died during Q5; its notes and artifacts
were salvaged, so Q5 is the one open gap (§6.7).

### 6.1 R1 RESOLVED — AGP 9 and KMP do coexist

`p1` BUILD SUCCESSFUL; `p6` proved the full stack in one project.

Working shape:
- **root**: `com.android.application` 9.2.1 + `com.android.kotlin.multiplatform.library` 9.2.1 + `org.jetbrains.kotlin.multiplatform`, all `apply false`
- **shared**: applies `kotlin.multiplatform` + `com.android.kotlin.multiplatform.library`, declares `iosArm64()` + `iosSimulatorArm64()`
- **app**: applies **ONLY** `com.android.application` (plus compiler plugins). Both flavors work.

Two negative results pin the shape down:
- Applying `org.jetbrains.kotlin.android` alongside AGP 9 **fails**: `Cannot add
  extension with name (kotlin), as there is an extension already registered`.
  With AGP 9's built-in Kotlin the app module must stay plugin-free.
- `com.android.library` + `kotlin.multiplatform` **fails**: *"not compatible …
  since AGP 9.0. Solution: replace with `com.android.kotlin.multiplatform.library`."*

**Key mechanism:** AGP 9.2.1's built-in Kotlin *is* kotlin-gradle-plugin 2.2.10.
Declaring the KMP plugin at a **higher** version upgrades the entire build
classpath — including the app module's compiler — via normal Gradle conflict
resolution. That is how this program reaches Kotlin 2.3.20 without ever
applying `kotlin-android`.

### 6.2 R3 RESOLVED — Room KMP works AND preserves the Android API surface

Proven **running on the iOS simulator**, not merely compiling:

```
ROOM-KMP-PROBE-OK rows=2 first=morning run
ROOM-MIGRATION-PROBE-OK survived=legacy row newCount=2
```

The second line is the one that matters: a hand-built v9 database was migrated
to v10 through a real `Migration` object and **the legacy row survived**.

The classic Android API still compiles inside a KMP module:
- `Room.databaseBuilder(context, AppDatabase::class.java, "probe.db")` — works
- `object : Migration(8,9) { override fun migrate(db: SupportSQLiteDatabase) }` — works
- **mixing legacy and KMP-style migrations in one `.addMigrations(...)` compiles**
- `exportSchema = true` + `room { schemaDirectory(...) }` emits the same
  `schemas/<db>/10.json` layout as Android

**SQLDelight is off the table.** Room KMP delivers schema and migration
continuity for existing v10 installs, which is the only reason R3 outranked
convenience.

Failure chain worth keeping (each cost a rebuild):
1. KSP `2.2.10-2.0.2` + `com.android.kotlin.multiplatform.library` 9.2.1 →
   `KotlinMultiplatformAndroidCompilationImpl cannot be cast to
   KotlinJvmAndroidCompilation`, plus `Configuration with name kspAndroid not found`
2. Hand-writing the `@ConstructedBy` actual → `The @ConstructedBy definition
   must be an expect declaration`. **Do not write those actuals** — Room's KSP
   generates the constructor per target.
3. `androidx.sqlite` 2.7.0 klib is ABI 2.3.0, incompatible with Kotlin 2.2.10
   (2.6.2 there). `room-runtime` 2.8.4 is ABI 2.2.0 and fine either way.

### 6.3 CMP 1.10.3 is a hard ceiling on this Mac

- CMP **1.11.1 + Kotlin 2.2.10** → klib ABI 2.3.0 vs consumable ≤ 2.2.0. Fails.
- CMP **1.11.1 + Kotlin 2.3.20** → still fails, now at **link** time:
  `Undefined symbols for architecture arm64: "_OBJC_CLASS_$_UIViewLayoutRegion"`,
  `Could not find or use auto-linked framework (UIUtilities)`.
  **CMP 1.11.x needs a newer iOS SDK than 18.4 / Xcode 16.3.**
- CMP **1.10.3** builds and renders — the ceiling **regardless of Kotlin
  version**, until Xcode is upgraded.

**Mandatory runtime gotcha:** CMP crashes at launch unless `Info.plist` carries
`CADisableMinimumFrameDurationOnPhone = true`
(`kotlin.IllegalStateException: Error: Info.plist doesnt have a valid
CADisableMinimumFrameDurationOnPhone entry`).

Evidence: `/Users/cwiesi/bt_scratch/kmp-probe/q2-cmp-1103-ios-renders.png`
(Material3 on iOS 18.4), and the crash it replaced,
`q2-cmp-ios-crash-missing-plist-key.png`.

### 6.4 Ktor green — and the dev stack needs NO ATS weakening

Ktor 3.5.2 + Darwin engine on the simulator fetched
`http://192.168.0.114:8099/probe.json` **with no `NSAppTransportSecurity` key
in the bundle at all**.

The probe ran a control in the same binary: `http://neverssl.com/` failed with
`NSURLErrorDomain -1022 … App Transport Security policy requires the use of a
secure connection`. So ATS *is* enforced — **raw IPv4-literal origins are
simply exempt from it**.

This is the best possible outcome: the LAN dev stack
(`http://192.168.0.114:3000`) needs **no plist exception and no security
weakening whatsoever**. The exception format was verified separately against
the control domain and is recorded only in case a hostname-based dev origin
ever appears.

Also proven live: Compose recomposition ticks advanced (11 → 19) — the Compose
runtime and kotlinx-coroutines are alive on iOS.

Ktor/Kotlin pairing by klib ABI: **3.3.3** is newest for Kotlin 2.2.10;
**3.5.2** for Kotlin 2.3.20.

### 6.5 No CocoaPods, and no Xcode project needed to boot

kdoctor 1.1.0 (installed by the probe — the **only** thing installed) reports
*"Your operation system is ready for Kotlin Multiplatform Mobile Development"*.
It flags CocoaPods as missing, but that is advisory: the probe **built and ran
two iOS apps with zero pods**, via `binaries.executable` + a `UIApplicationMain`
AppDelegate, a hand-assembled `.app`, ad-hoc codesign and `simctl install/launch`.

Kotlin/Native toolchains (2.2.10 / 2.3.20 / 2.4.10 + LLVM 19/21 + libffi) were
auto-downloaded by Gradle into `~/.konan`.

### 6.6 The verified stack (built together in probe `p6`)

| Component | Version | Note |
| --- | --- | --- |
| Gradle / JDK | 9.4.1 / 17.0.18 | unchanged |
| AGP | 9.2.1 | unchanged |
| Kotlin | **2.3.20** | up from 2.2.10 — see D6 |
| KSP | 2.3.11 | |
| compose compiler plugin | 2.3.20 | tracks Kotlin |
| Compose Multiplatform | **1.10.3** | ceiling (§6.3) |
| Room | 2.8.4 | unchanged |
| androidx.sqlite | 2.7.0 | bundled driver |
| Ktor | 3.5.2 | |

`p6` produced **both** flavor APKs, passed both iOS Room tests, and exported
the schema JSON — one build, no plugin conflicts between AGP's built-in Kotlin,
KMP, Compose, serialization, KSP and Room.

**Compose BOM caveat (must be decided in P2):** on Android, CMP artifacts *are*
`androidx.compose`, so `compose-bom:2026.06.01` wins (foundation → 1.11.4,
material3 → 1.4.0); on iOS the `org.jetbrains.compose` 1.10.3 klibs govern.
**Android and iOS would run different Compose patch versions** unless the BOM
is dropped or pinned to match CMP.

### 6.7 Q5 is the open gap — UNTESTED

The probe died before testing multiplatform availability of `androidx.datastore`,
`androidx.lifecycle` (viewmodel-compose), `org.jetbrains.androidx.navigation`,
koin, multiplatform-settings, a KMP argon2, and **Ktorfit**. None of those is
claimed as verified anywhere in this document.

The two that matter most are folded into P1 spikes: **navigation-compose
multiplatform** (this is R7 — the `BtSheetNavigator` question and the single
largest UI unknown) and **Ktorfit** (which decides whether 189 endpoints are
re-plumbed or rewritten).

---

## 7. Structural decisions (probe-backed)

### D6 — Move to Kotlin 2.3.20, gated on the 2637-case suite

The recommended stack needs Kotlin 2.3.20; the app is on AGP's built-in 2.2.10.
Both routes were proven to build:

- **A** — stay on 2.2.10: requires `com.android.library` plus the bypass flags
  `android.builtInKotlin=false` and `android.newDsl=false`, Ktor 3.3.3,
  androidx.sqlite 2.6.2.
- **B** — move to 2.3.20: the modern, supported path with
  `com.android.kotlin.multiplatform.library` and no bypass flags.

**Decision: pursue B, proven before anything was built on it.**

**RESOLVED 2026-08-10 — B is GREEN; route A is retired.** Commit `73d24a9`.

The override is classpath-only: the root build file declares
`org.jetbrains.kotlin.multiplatform` at 2.3.20 with `apply false`, applied to no
module. Its plugin marker pulls kotlin-gradle-plugin 2.3.20, and Gradle conflict
resolution raises AGP's built-in 2.2.10 across the whole build — `:app` never
applies a Kotlin plugin (which fails outright). Two files, four functional
lines, **zero source fixes required**.

Verified independently of the builder that made the change, from a
`--rerun-tasks` rebuild where every task actually executed:

| Leg | Result |
| --- | --- |
| `testGithubDebugUnitTest` | 169 suites / **2637 tests / 0 failures / 0 errors** / 7 skipped |
| `testPlayDebugUnitTest` | 169 suites / **2637 tests / 0 failures / 0 errors** / 19 skipped |

Both flavors assemble. The play leg had **never been executed anywhere** before
this; its larger skip count is the `assumeTrue(SELF_UPDATE_ENABLED)`-gated tests
correctly skipping off-flavor — which also de-risks the new CI gate's one
unproven leg.

**The bump was proven to have actually taken effect, two ways**, because a green
suite that silently still ran the old compiler would be a false gate: the
resolved classpath carries `kotlin-gradle-plugin:2.3.20` with zero occurrences
of 2.2.10 anywhere, and freshly emitted bytecode carries `@Metadata mv=[2,3,0]`
against the baseline's `mv=[2,2,0]`. A classpath can lie about what compiled;
the metadata the compiler stamps into a class file cannot.

Incidental finding: KSP dropped the `<kotlin>-<ksp>` scheme at 2.3.0 and now
tracks the Kotlin *language* line, so 2.3.11 pairs with any Kotlin 2.3.x — no
more KSP republish per Kotlin patch.

Also fixed under this gate (`be0052e`): `CashLedgerHandPortedTest.kt:163`
asserted `e is CashLedgerError` to guard that two error classes stay separate.
Kotlin 2.3 warns it is statically always false (KTLC-365) and **Kotlin 2.4 makes
that a hard compile error** — a deliberate guard would have become a build
failure at an arbitrary future moment. Widened through `as Any`, as the adjacent
line already did, so it remains a genuine runtime check.

### D7 — Persistence: Room KMP. Turn on `exportSchema` first.

R3's answer is Room KMP, on the §6.2 evidence. Sequencing is not optional:
**enable `exportSchema` and land the golden v10 schema JSON as a standalone
commit before touching anything in `db/`.** Today no golden schema exists, and
the 753-LOC migration test that guards the chain is sqlite-jdbc + reflection on
Room's generated `_Impl` and cannot follow the code into common code. Without
the exported schema, nothing mechanically proves a KMP Room build produces the
schema real users already have on disk.

### D8 — Network: Ktor 3.5.2, with Ktorfit as the preferred shape (UNVERIFIED)

**RESOLVED 2026-08-10 — GREEN, and it is a re-plumb, not a rewrite (§9).**

Pinned: `ktorfit-lib-light:2.7.3` + `ktorfit-converters-response:2.7.2` (the
converter has no 2.7.3 — it jumps 2.7.2 → 2.7.5) + `ktorfit-ksp:2.7.3`.
Ktorfit 2.7.4/2.7.5 are ABI 2.4.0, too new for Kotlin 2.3.20. The
`ktorfit-gradle-plugin` is the legacy compiler-subplugin path and is not needed.

Proven on the simulator: **8/8 endpoints against a real HTTP server**, covering
`@GET` with `@Path`+`@Query`, `@POST` with `@Body`, `@DELETE` returning 204
Unit, `@PATCH`, `@HTTP` with a body, a bare return, a 404 with `errorBody`, and
header echo. Evidence:
`/Users/cwiesi/bt_scratch/kmp-deps-spike/ktorfit-ios-proof.png`.

Why the cost collapsed:
- **Annotations are byte-identical to Retrofit's**, including the semantic the
  app depends on: a null `@Header` is omitted from the request exactly as
  Retrofit does — which is what the nullable `Idempotency-Key` params rely on.
- `Response<T>` is a near-exact analogue: `isSuccessful` and `body()` identical,
  `code()` → `code` property (mechanical), while `headers()`, `message()` and
  `raw()` have **zero uses** in the app.
- **All 189 endpoints return `Response<T>`, and ~93% of consumption (180 of 194
  call sites) funnels through just 3 functions in 2 files** — `apiCall` and
  `unitApiCall` (`data/api/BtApiError.kt`) plus `runMutation`
  (`sync/ApiOpExecutor.kt`). Only 14 inline `isSuccessful` sites remain.
- `errorBody()` is parsed in exactly **one** function, and its only Retrofit
  coupling is the `ResponseBody?` parameter type. Concretely it becomes
  `(resp.errorBody() as? HttpResponse)?.bodyAsText()` — one line, one place.
- Zero `Call<T>`, zero CallAdapters, zero custom `Converter.Factory`.

**Rewriting ~70 lines across 2 files converts the whole network layer.** The
1568-line interface file needs its 12 `retrofit2.http.*` imports swapped and
nothing else.

Three briefing premises were wrong, all in our favour: `@HTTP` is **2**
endpoints (not 4), `@Streaming` is **1** (not 2), and `@Header("Idempotency-Key")`
is **10** params (not 15) — the surplus grep hits were KDoc prose.

**The real cost was never the endpoints — it is the 5 OkHttp components.**
`TokenAuthenticator` (401→refresh→retry-once via `priorResponse`, with the
`X-Bt-No-Reauth` per-request opt-out) and `ConditionalGetInterceptor`
(304 → synthesised 200 replay) have no direct Ktor analogue and need genuine
redesign onto Ktor's `Auth` plugin and a custom plugin. Plus the one
`@Streaming` endpoint returning `okhttp3.ResponseBody`, and 16 MockWebServer
test harnesses.

### D9 — UI: Compose Multiplatform 1.10.3, pinned deliberately

1.10.3 is a hardware/toolchain ceiling, not a preference (§6.3). Two
consequences to carry: `Info.plist` must set
`CADisableMinimumFrameDurationOnPhone`, and the Compose BOM/CMP version
divergence (§6.6) must be resolved so Android and iOS do not silently run
different Compose patch levels.

### D10 — iOS app packaging: no CocoaPods

Not needed and not installed (§6.5). P1 boots via the proven direct-executable
path. A real Xcode project is introduced when P4 needs it for free-Apple-ID
cable deploy, since that is the phase whose requirements actually shape it.

### D13 — Module layout

```
:app      com.android.application only (no Kotlin plugin, ever). Android host.
:shared   kotlin.multiplatform + com.android.kotlin.multiplatform.library
          commonMain / androidMain / iosMain, targets iosArm64 + iosSimulatorArm64
iosApp/   Kotlin/Native executable + UIApplicationMain AppDelegate (no Xcode
          project until P4, no CocoaPods ever)
```

One shared module, not many. Rationale: the survey found the OS seam is narrow
(127 of 323 files already import no `android.*`) and the migration is
layer-by-layer, so multiple modules would add Gradle wiring cost with no
isolation benefit while `:app` still owns every screen. Split later only if
build times demand it.

Migration direction is **`:app` → `:shared`, lowest layer first**: `domain/`
(100% Android-free already) → vault pure files → `sync/` seams → `data/` →
`ui/`. The Android app keeps compiling against `:shared` at every step, so the
2637-case suite stays the gate throughout.

**`:shared` carries one exception to the "no KSP in commonMain" rule** — see
R11: Ktorfit interfaces must live in a source set mounted into the iOS targets,
behind an `expect`/`actual` seam.

---

## 8. R7 spike — the sheet architecture survives intact (2026-08-10)

**GREEN.** The 47-route sheet architecture ports to iOS. This was the single
largest UI unknown and it closes without a fallback.

`SpikeSheetNavigator.kt` is a literal copy of the app's 153-line
`BtSheetNavigator.kt` with only the class renamed. It compiled with **zero
changes to the navigation code** — the one compile error in the whole spike was
the spike author's own UIKit typo.

### API verdict — all PRESENT, none with a changed signature

`Navigator<D>` + `@Navigator.Name`, `FloatingWindow`, `NavigatorState` +
`state.backStack: StateFlow<List<NavBackStackEntry>>`, subclassable
`NavDestination`, `NavDestinationBuilder<D>(navigator, KClass, typeMap)` +
`instantiateDestination()`, `NavGraphBuilder.destination(...)`,
`provider[KClass]`, `@Serializable` routes + `toRoute<T>()` + `hasRoute(KClass)`,
`NavType`, `rememberNavController(vararg navigators)` — and critically
**`NavBackStackEntry.LocalOwnersProvider(saveableStateHolder)` is present and
public**, which was the API most likely to be missing or internal.

### The behavioral proof (not a compile-only answer)

The question was a runtime lifecycle guarantee, so it was measured as one. Each
destination ticks a counter every 500 ms; a truth strip polls every entry's
lifecycle from **outside** those compositions, so it reports correctly even if a
page were disposed.

- Two sheets stacked, t=8.5 s → t=10.0 s: covered sheet A went `tick=12` →
  `tick=15` while B went `4` → `7`. **Both advanced exactly +3 in 1.5 s** — the
  covered page is not merely retained, it runs at full cadence, STARTED.
- Back stack while stacked: `<graph> RESUMED / SheetRootRoute STARTED /
  SheetARoute STARTED / SheetBRoute RESUMED` — the empty floor stays STARTED
  exactly as the app's KDoc claims it should.
- **Pop 2→1**: A's counter was **continuous, never reset**, and its
  `ViewModelStore` identity was unchanged from depth 2. The composition
  **moved between planes rather than being rebuilt** — precisely the premise of
  the two-plane connected slide. Each entry had a distinct store, so
  `LocalOwnersProvider` really does install per-entry ViewModelStore +
  SavedStateRegistry.
- **Control experiment**: two ordinary `composable<T>()` destinations, same nav
  version, same runtime, same screen. Pushing one over the other dropped the
  lower to `CREATED` and disposed its composition. That attributes the
  STARTED-while-covered guarantee to `FloatingWindow` **and nothing else**.

Screenshots: `/Users/cwiesi/bt_scratch/kmp-nav-spike/` (`01-one-sheet.png`,
`02-two-sheets-first.png`, `03-two-sheets-later.png`,
`04-after-pop-one-sheet.png`, `05-plain-E-alone.png`, `06-plain-D-over-E.png`).

### Version pin, and a trap

**`org.jetbrains.androidx.navigation:navigation-compose:2.9.2`** — pinned.

**2.10.0-alpha is poison.** It compiles, then silently drags
`org.jetbrains.compose.runtime` 1.10.3 → 1.12.0-alpha02 and the link dies with
the same `_OBJC_CLASS_$_UIViewLayoutRegion` / `UIUtilities` failure that rules
out CMP 1.11.x (§6.3). Note there was **never a klib ABI problem** here — nav
2.9.2 and CMP 1.10.3 are both `abi_version=2.2.0`; the transitive Compose bump
was the whole issue. Also: nav 2.9.2 publishes iOS artifacts under the older
`uikitsimarm64` classifier; Gradle attribute matching resolves it with no
special configuration.

### Three consequences for the port

1. **`androidx.activity.compose.PredictiveBackHandler` (used in `BtSheetStack`)
   is Android-only**, but CMP 1.10.3 ships `PredictiveBackHandler`,
   `BackHandler` and `BackEventCompat` in `org.jetbrains.compose.ui:ui-backhandler`
   (verified present in the 1.10.3 klib). **An import swap, not a rewrite.**
   Whether iOS should have a system-back driver at all remains the UX question
   already flagged for the owner.
2. **STANDING RULE — every new subpage goes through `btSheet<T>`, never
   `composable<T>`.** Navigating to any non-`FloatingWindow` destination
   auto-dismisses the entire sheet stack (observed: depth 2 → 0). This is stock
   androidx behavior faithfully reproduced on iOS. The graph is safe today
   because the only non-sheet destination is `SheetRootRoute`, which nothing
   navigates to directly — but it must stay that way.
3. `BtSheetStack` also pulls `R.string` via `androidx.compose.ui.res.stringResource`
   — that belongs to the compose-resources migration (§4.4), not to this risk.

**Effect on the estimate:** the UI survey's hardest item was 5–15 engineer-days
depending on this answer. It lands at the low end, and the ~15-day hand-rolled
back-stack branch is retired.

---

## 9. Dependency matrix at Kotlin 2.3.20 (2026-08-10, compile+link verified)

Verified by a module whose `commonMain` actually imports and uses each API;
`compileKotlinIosSimulatorArm64` and `linkDebugExecutableIosSimulatorArm64`
both succeeded.

**Baseline correction:** an earlier draft matrix was measured at Kotlin 2.2.10,
which rejects `abi_version=2.3.0` klibs. At 2.3.20 (which consumes ABI ≤ 2.3.0)
**every REJECT in that draft flips to OK.** The matrix below was re-derived,
not inherited.

| Library | Version | Status |
| --- | --- | --- |
| kotlinx-coroutines-core | 1.11.0 | GREEN |
| kotlinx-serialization-json | 1.11.0 | GREEN |
| kotlinx-datetime | 0.8.0 | GREEN |
| **multiplatform-settings** (+no-arg) | **1.3.0** | GREEN — chosen (D11) |
| androidx datastore-preferences | 1.3.0-alpha10 | AMBER (alpha) — not chosen |
| JB lifecycle-viewmodel / -compose / runtime-compose | 2.11.0 | GREEN, caveat below |
| koin-core / koin-compose | 4.2.2 | GREEN — but not adopted (D12) |

### D11 — Preferences: `multiplatform-settings`, not DataStore

Decided by a startup-ordering constraint, not taste. `ServerOrigins` and
`DriveModeGate` are read **synchronously during DI init, before any HTTP base
URL is captured**. DataStore's API is Flow/suspend-only, so honouring that
ordering would mean `runBlocking` on the iOS main thread at startup.
`multiplatform-settings` no-arg maps to **NSUserDefaults on iOS and
SharedPreferences on Android** — both synchronous — and maps 1:1 onto the
existing 10 plain `SharedPreferences` stores. `KeychainSettings` for secrets
ships in the same iOS artifact, which also serves the `SecureStore` seam.

### D12 — DI: keep the hand-written `AppGraph.kt`; do not adopt Koin

Koin 4.2.2 is verified compatible if ever wanted. Adopting it now would convert
compile-time wiring into runtime resolution across 41 ViewModels **for no
portability gain** — `AppGraph` is plain Kotlin and ports as-is. Changing
nothing is the lowest-risk option.

### Caveat carried: lifecycle 2.11.0 vs CMP 1.10.3

JB lifecycle 2.11.0 force-upgrades `androidx.compose.runtime` 1.10.5 → 1.11.1
while CMP UI stays at 1.10.3. That mix **compiles and links** on Xcode 16.3 —
the CMP 1.11.1 failure in §6.3 was in compose-**UI** (`UIViewLayoutRegion`), not
the runtime — but it was **not run on the simulator**, so it is UNTESTED at
runtime. Lower-risk alternative: pin JB lifecycle to 2.10.x to match CMP.

### `java.time` → `kotlinx-datetime`: the survey was right

`OffsetDateTime` has **no equivalent** — `Instant` alone does not retain the
source offset. The verified replacement is
`DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(s)` then
`toUtcOffset()` / `toLocalDateTime()` / `toInstantUsingOffset()`. Also note
`Clock` moved to `kotlin.time.Clock` in 0.8.0. Budget real work across the ~40
`java.time` files.

### Argon2id — nothing beats D2; the decision stands

Maven Central still has no vetted KMP Argon2, and `org.kotlincrypto.*` publishes
no KDF group. One candidate the earlier probe missed — `com.diglol.crypto:kdf:0.2.0`
— is a genuine KMP Argon2id with real iOS klibs, and is **rejected for the
vault**: v0.2.0, single small maintainer, stale (Kotlin 1.9.22 era), no audit,
no published vectors. That bar is not negotiable for the one function standing
between a user and their vault.
`com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings:0.9.5` remains the
only credible prebuilt fallback and bundles its own libsodium cinterop (no brew
needed). **The literal BouncyCastle translation with its test vectors stands.**

### R11 (new, AMBER) — KSP will not process Ktorfit interfaces in `commonMain`

Measured repeatedly with the build cache off and `--rerun-tasks`: KSP 2.3.11
generates Ktorfit clients from `iosMain` and from target source sets, but
produces **nothing** from `commonMain` or from a custom shared source set.
Generated code always lands in the **target** source set, so the
`createXxxApi()` caller must live there too.

Proven working layout:

```
src/apiShared/kotlin -> srcDir into iosMain                  (the @GET interfaces)
src/apiWiring/kotlin -> srcDir into iosArm64Main AND iosSimulatorArm64Main
```

Consequence: common-source repositories cannot reference the Ktorfit interface
directly — it needs an `expect`/`actual` seam. That seam was built and works.

**Unresolved anomaly, flagged rather than papered over:** Room 2.8.4 *does*
generate from `commonMain` under the same KSP 2.3.11 (probe `p6` proves it).
Adding an Android target did not change Ktorfit's behaviour, and Ktorfit's
processor contains no source-set filter. **One focused follow-up is owed before
committing to the layout above** — if this is a fixable configuration mistake,
the network layer gets a cleaner shape.

**Gradle trap worth remembering:** KSP task inputs are *relative*-path
sensitive, so moving a file between source sets keeps the same cache key and
replays a stale/empty result as `FROM-CACHE`/`UP-TO-DATE`. Re-runs must use
`org.gradle.caching=false` and `--rerun-tasks`. This produced several false
results before it was spotted.

---

## 10. Absorbed from platform `main` (merge `fe1a542`, 2026-08-10)

`kmp-ios` merged `origin/main` @`5a104c7` (7 commits, conflict-free — no file
was changed on both branches). Post-merge gates all green (§log).

1. **Portfolio history's REQUIRED `interval` field — ABSORBED** (main
   `90c635a`). `interval: String?` now on `PortfolioDtos.kt:91` and
   `MarketDtos.kt:82`; the app reads the echoed value and still sends nothing.
   The DTO layer carries the current contract before it moves to `:shared` —
   which mattered because the app's `Json` is `ignoreUnknownKeys = true`, so a
   stale-contract miss would have failed silently.
2. **Vaults v2 conformance families — ABSORBED** (main `7389f82`). Fixtures at
   `tools/domain-vectors/vendor/domain/src/vaultVectors/` (`v1.fixture.json` +
   `v2.fixture.json`), 11 new Kotlin classes under `vault/v2/`, and
   `VaultV2ConformanceTest`. The suite grew **+90 (2637 → 2727)**, green on both
   flavors. The P2 crypto conformance target is now **v1 + v2**.

**v2 crypto scope added to the port (2511 LOC, 11 files).** Beyond v1's
Argon2id + deflate, v2 introduces:
- **BouncyCastle HKDF** (`HKDFBytesGenerator` + `SHA256Digest`) — a *second*
  BouncyCastle KDF. Like Argon2, HKDF is fully specified (RFC 5869), so
  byte-identity is a property of the spec: literal-translate (per D2) or
  CryptoKit `HKDF`.
- **HMAC-SHA256** (`javax.crypto.Mac`) for the header MAC → CommonCrypto/CryptoKit.
- **`java.text.Normalizer` NFKD** at `VaultWords.kt:51`, on the BIP39
  mnemonic → seed path. Kotlin/Native has **no** `Normalizer`; this is a new
  `expect`/`actual` seam that **must be byte-identical** (iOS:
  `precomposedStringWithCompatibilityMapping` / `CFStringNormalize` NFKD).
- Otherwise clean: zero `BigDecimal`/`Math.`/`synchronized`/`ThreadLocal` in
  `vault/v2/`.

---

## 11. P2 chunk 1 — domain engine migrated (2026-08-10, commit `c51f172`)

All six remaining `domain/` files now live in `:shared/commonMain`, package
unchanged. Four moved verbatim (`R100` renames); only `Tax.kt` (`R097`) and
`DomainTypes.kt` (`R063`) needed edits — the engine really was as portable as
§4.4 predicted.

### The formatter seam is built and proven (R8, 1 of 3 closed)

`jsNumberToString` had exactly one platform-specific line. It is now
`expect fun formatScientific(value, fractionDigits)`:

- **Android actual delegates to the original `String.format(Locale.ROOT, "%.Ne", v)`.**
  This is the important design choice: Android's runtime output is unchanged
  **by construction**, so the 2727-case suite is *unaffected* rather than merely
  *observed green*. The migration cannot regress Android number rendering.
- **iOS actual** reproduces it without `java.text`, via an exact base-10
  expansion (no `BigDecimal` on Native). Key discovery from the build: Java's
  `%e` does not round the exact binary value — it renders the shortest
  round-trip digits and then rounds/zero-pads *those* HALF_UP. The iOS actual
  mirrors that two-stage behaviour, including the ≥2-digit exponent width.
- **Proven on-device: 10/10 byte-identical** vs the JVM as source of truth,
  including `1e21`, `1e-7`, `5e-324` (min subnormal), `MAX_VALUE`, and the
  17-significant-digit `0.1+0.2 → 0.30000000000000004`.
  Evidence: `/Users/cwiesi/bt_scratch/kmp-2026-08-10/jsnts_ios_proof_all10.png`.

### The date seam

kotlinx-datetime 0.8.0, added to `:shared` only. The load-bearing detail was
**catch widening**: the old shims caught `java.time.format.DateTimeParseException`
to return `NaN`, and kotlinx-datetime throws `IllegalArgumentException` instead.
The KDoc documents that callers *rely* on `NaN` propagating (so an impossible
date yields an empty series, not an exception), so a missed catch would have
converted a designed fallback into a crash on malformed server data.

### R12 — a pre-existing Android/web divergence this work uncovered

While validating the iOS actual against a JVM twin over hundreds of thousands
of random doubles, roughly 1 in 300k disagreed — always large
17-significant-digit values (>1e17). Ground truth shows the cause is **not** the
new code: **OpenJDK 17's `Double.toString` emits a non-shortest representation**
for those inputs (the known pre-JDK-19 `FloatingDecimal` imperfection), while
the iOS implementation produces the true shortest round-trip — i.e. it matches
**V8/ECMAScript**.

That direction matters. `jsNumberToString` exists *specifically* to emulate
JavaScript number rendering, and the conformance vectors were generated from the
platform's TypeScript running on V8. So on those inputs **iOS is right and the
JVM is wrong against the contract** — Android may already differ from the web
client today, independently of this port.

Assessment, and why nothing is being changed unilaterally:
- **Not urgent.** The affected values are >1e17 with 17 significant digits —
  unreachable by realistic money amounts or share quantities — and no current
  vector or test touches them.
- **Not introduced here.** Android's output is byte-identical before and after
  this commit.
- **The clean end-state** is for *both* platforms to match V8, since that is the
  spec the function emulates. But that means changing the shipping Android app's
  behaviour on the money path, which is an owner/chief decision, not a
  sub-coordinator's — and it should be driven by a vector that demonstrates it.
- **One caveat that must not be lost:** the 2727 tests run on the **JVM
  (OpenJDK 17)**, not on ART. Android's *runtime* rendering is therefore not
  actually pinned by the suite for these values, and ART's libcore may differ
  from both OpenJDK 17 and V8. Anyone settling this should measure on a device
  rather than reason from the JVM.

### Still open in this layer

The domain engine compiles for iOS (gate d) but its **numerical output is not
yet vector-replayed on-device** — only the formatter path is. [DONE in chunk 2,
§12.]

---

## 12. P2 chunk 2 — 622 domain vectors replay on Kotlin/Native (2026-08-10, `1badadf`)

**The audited calculation engine is now confirmed byte-identical on Android and
iOS.** All 622 vectors replay green on `:shared:iosSimulatorArm64Test`
(Native) and `:shared:testAndroidHostTest` (JVM), first run, zero divergence,
no tolerance loosened. This is the payoff of the whole domain migration: not
"it compiles for iOS" but "it computes the same numbers as the web client, on
the device, proven against the platform's own oracle."

### How a fake pass was made impossible

The count *shape* changed (622 parameterized cases → a handful of looping
functions), so the test count is not the proof. The proof is layered:
- Each family loop asserts **both** `vectors.size` (present in the fixture)
  **and** a separate `processed` counter (actually replayed) against a
  hardcoded per-module literal — tax 273, cashLedger 191, holdings 104,
  seriesStats 41, settingsScope 9, serverTwrParity 4.
- `DomainVectorFixtureTest` independently asserts 622 decoded across the six
  modules, re-derives each fixture's FNV-1a-64 hash from the rejoined string
  vs the value the generator recorded before splitting, and asserts
  `MANIFEST.pinnedAt` against a hash pinned in `build.gradle.kts`.
So an empty, truncated, or swapped fixture fails the build rather than passing
green. Verified independently: a 1e-9 perturbation of `num()` made 206/273 tax
vectors fail with fully-identified messages — the loop genuinely compares.

### Three subtleties that would have been silent bugs

1. **JUnit vs kotlin.test tolerance.** JUnit's `assertEquals(d,d,delta)` passes
   when `Double.compare==0` OR `abs(diff)<=delta` — which is what makes
   `NaN==NaN` and `-0.0==+0.0`. kotlin.test's overload checks only the absolute
   difference and would have silently started rejecting a NaN vector. JUnit's
   exact semantics were reimplemented via `toBits()` (canonical NaN on both
   targets). These are money vectors.
2. **The 64 KB string-constant ceiling.** commonTest compiles for the JVM too,
   where a single string literal caps at 65535 modified-UTF-8 bytes, and
   `tax.json` is 177 KB. Fixtures are chunked to ≤16000 chars (≤48000 bytes)
   and joined at runtime; escaping emits pure ASCII so source encoding leaves
   the trust chain. The JSON files stay the single source of truth
   (`generate.ts` writes them); the Kotlin is generated by
   `:shared:generateVectorFixtures`, reproducible across `--rerun-tasks`.
3. **`withHostTestBuilder {}.configure {}`** — AGP's KMP library plugin creates
   no JVM host-test compilation without this opt-in, so commonTest would have
   run on **iOS only** and the JVM half would have silently not existed.

### Accounting and isolation

`:app` reconciles exactly **2727 → 2101** on both flavors (626 moved: the 622
vectors as JUnit methods across 3 suites, plus the 4 hand-written TWR parity
tests). `commonTest`'s new deps (`kotlin-test`, `serialization-json`) are
test-scoped on `:shared` and verified absent from `:app`'s runtime classpath;
`:shared` main still contributes only kotlinx-datetime.

### Left behind, deliberately

A ~110-line `app/.../domain/VectorSupport.kt` holds only the JSON accessor
plumbing the three hand-ported JVM suites (`DomainHandPortedTest`,
`DeTaxFixturesHandPortedTest`, `StorageDriftVectorsHandPortedTest`) still need.
No vector data is duplicated — those suites read from `resources/` on the JVM.
Revisit when they move to commonTest.

**Still JVM-only (no Native coverage yet):** the 46 generator-skipped edge
cases (non-finite inputs, signed-zero identity, relational two-call
assertions), `JsNumberToStringTest`, `TaxSettingsDraftTest`, and the three
hand-ported suites above. **UNTESTED on real `iosArm64` hardware** — the
vectors executed only on `iosSimulatorArm64`; the device target compiles clean
but running on hardware needs a connected device (a P4 concern).

---

## 13. P2 Room persistence seam → `:shared` (2026-08-11, `c47b6f5`) — R3 closed

The single riskiest migration in the program, done under an explicit
stop-condition from the chief: keep the golden v10 identityHash stable and
Android DB behaviour byte-identical, or STOP and report rather than push
through. No stop trigger was hit; both invariants were **independently
re-verified by the sub-coordinator** before the commit.

### The strategy that avoided rewriting battle-tested migrations

The probe established that classic `SupportSQLiteDatabase` migrations still
compile on the **Android target** of a KMP module. So the split is:
- **commonMain** — `@Database` (version 10, `exportSchema = true`, unchanged),
  the 18 entities and 13 DAOs (5 files, R100 verbatim renames), plus
  `@ConstructedBy(BtDatabaseConstructor::class)` and
  `internal expect object BtDatabaseConstructor : RoomDatabaseConstructor<BtDatabase>`
  (Room KSP generates the actual per target — never hand-written).
- **androidMain** — the 9 migration bodies, the Cursor-based
  `hasColumn`/`addColumnIfMissing` guards, and the `MIGRATIONS` array moved
  **verbatim**. Android still constructs via
  `Room.databaseBuilder(context, BtDatabase::class.java, "bettertrack.db")
  .addMigrations(*MIGRATIONS).build()`, so `db.openHelper` stays live and
  `AccountDataManager`'s wipe path (which relies on invalidation triggers
  firing on its DELETEs) is untouched — it stayed in `:app`.
- **iosMain** — a fresh `Room.databaseBuilder<BtDatabase>(path)
  .setDriver(BundledSQLiteDriver())` into `NSDocumentDirectory`, v10, no
  migrations (there is no existing iOS data to migrate).

### The two invariants, verified

1. **identityHash byte-identical.** The schema is now exported by `:shared`;
   `shared/schemas/…/10.json` is byte-for-byte identical to the committed golden
   `app/schemas/…/10.json` — same `a9fab166f6bcb1451ac240972a08a408`, same md5,
   empty `diff`. Moving `@Database` to commonMain does not touch the schema, so
   no existing install's DB can be rejected at open. The golden `app/schemas`
   file is itself git-untouched.
2. **Android behaviour byte-identical.** Migration bodies verbatim;
   `BtDatabaseMigrationTest` stayed in `:app` **unchanged** and passes **7/7**
   (`MIGRATIONS`/`create` exposed as `BtDatabase.Companion` extensions so the
   same-package test resolves them with no edit). Shipping graph unchanged:
   `:app` runtime resolves `room-runtime:2.8.4` and `sqlite:2.6.2` exactly as
   before, and `sqlite-bundled` does **not** leak into `:app` (iosMain-only).

### On-device proof

The iOS app links Room + `BundledSQLiteDriver` into the Native binary and boots.
The builder additionally opened the KMP DB on the simulator and ran a DAO
write→read plus a `price_cache` query (smoke test then reverted; `Main.kt`
byte-identical to HEAD) — **the database runs on Kotlin/Native, not just
compiles.**

### Deliberate leftovers

- `app/schemas/10.json` is now a frozen golden reference (`:app` has no
  `@Database`, so its Room export is a NO-SOURCE no-op); `shared/schemas/10.json`
  is the live export. Both byte-identical.
- `:app` still applies the room/ksp plugins as a green no-op — left to minimise
  `:app` churn; an optional future cleanup.
- Five `:app` UI null-guards captured into locals (a cross-module entity `val`
  no longer smart-casts) — behaviour byte-identical, the 2101-test suite is the
  proof.

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
- **2026-08-10** — Toolchain probe died during Q5; notes and artifacts
  salvaged, five of six questions answered empirically (§6). **R1 and R3 both
  resolved green**: AGP 9 and KMP coexist, and Room KMP preserves the classic
  Android builder/`Migration`/`SupportSQLiteDatabase` surface with a real v9→v10
  migration proven **on the simulator**. CMP 1.10.3 found to be a hard ceiling
  (1.11.x needs a newer iOS SDK than Xcode 16.3 ships). Ktor proven, and the
  LAN dev origin turns out to need **no ATS exception at all** — IPv4-literal
  origins are exempt, confirmed with a control. Structural decisions D6–D10
  recorded. Q5 (datastore/lifecycle/navigation/koin/Ktorfit) remains untested
  and is explicitly labelled so; the two that matter became P1 spikes.
  P1 opened.
- **2026-08-10** — P1 progress. **Kotlin 2.3.20 gate GREEN** (D6, `73d24a9`):
  2637/0 on BOTH flavors, bump proven real via bytecode metadata, zero source
  fixes. Route A retired. **CI now has a real test gate** (`968c83a`) — the
  suite had never run in CI at all; hardened against a green tick on red tests
  and verified against synthetic red XML. A Kotlin-2.4 hard-error was fixed
  before it could bite (`be0052e`). `testPlayDebugUnitTest` executed for the
  first time ever, green. Reported upward: every action in the pre-existing
  `android-apk.yml` is a Node 20 action and Node 20 is EOL — works today, but
  it is a scheduled outage; recommend bumping both workflows together.
  Navigation (R7) and Ktorfit spikes still running.
- **2026-08-10** — **R7 RESOLVED GREEN** (§8). `BtSheetNavigator` compiled
  unchanged against `navigation-compose:2.9.2`, and `FloatingWindow` was proven
  behaviorally on-device: the covered sheet kept ticking at full cadence while
  STARTED, and a pop preserved both the counter and the `ViewModelStore`
  identity, i.e. the composition moved between planes instead of being rebuilt.
  A control experiment with plain `composable<T>()` destinations dropped the
  covered page to CREATED, attributing the guarantee to `FloatingWindow` alone.
  The ~15-day hand-rolled back-stack fallback is retired. nav 2.10.0-alpha
  identified as a trap (drags Compose runtime to 1.12.0-alpha and breaks the
  iOS link). New standing rule recorded: subpages must always use `btSheet<T>`,
  never `composable<T>`, or the whole sheet stack auto-dismisses.
- **2026-08-10** — **D8 RESOLVED GREEN**: Ktorfit 2.7.3 proven on the simulator,
  8/8 endpoints against a real server. The 189-endpoint layer is a ~70-line
  re-plumb across 2 files, not a rewrite, because 93% of call sites funnel
  through 3 functions. Full dependency matrix re-derived at Kotlin 2.3.20 (§9),
  correcting a draft measured at 2.2.10 where every ABI 2.3.0 klib had been
  wrongly marked REJECT. D11 (multiplatform-settings over DataStore), D12 (keep
  manual DI), D13 (module layout) recorded. New AMBER risk R11: KSP will not
  process Ktorfit interfaces in `commonMain`; workaround proven, but a Room
  counter-example makes it worth one follow-up. Platform `main` changes logged
  for absorption at next merge (§10). **All P0/P1 unknowns are now closed** —
  next is the `:shared` module and the simulator boot.
- **2026-08-10** — **P1 GATE MET** (commit `0df16e6`). `:shared` KMP module
  created; `SettingsScope.kt` moved verbatim into `commonMain` (git rename,
  100% similarity, package unchanged so no `:app` import moved); `:app` depends
  on `:shared`. iOS app (`iosApp/`) boots on iPhone 16 / iOS 18.4 rendering the
  live `resolvePortfolioSetting` cascade — CHF/portfolio, USD/user, EUR/system,
  each computed at render time by shared code that `DomainVectorTest`'s 9
  vectors verify. All five gates verified independently of the builder:
  `testGithubDebugUnitTest` 2637/0/0/7, `testPlayDebugUnitTest` 2637/0/0/19,
  both assembles green, `shared:compileKotlinIosSimulatorArm64` green, and a
  fresh coordinator reinstall+launch screenshotted. Load-bearing choice: CMP UI
  deps scoped to `iosMain` so the shipping Android graph is byte-for-byte
  unchanged; `androidMain` gets only `androidx.compose.runtime` via `:app`'s own
  BOM. P0 and P1 both MET. **Next: P2 — shared core migration, lowest layer
  first, starting with the rest of `domain/`; merge `main` first to absorb §10.**
- **2026-08-10** — **P2 opened. Merged `origin/main` @`5a104c7` into `kmp-ios`**
  (merge `fe1a542`, 7 commits, conflict-free). Absorbed the required `interval`
  DTO field and the Vaults v2 conformance families (§10). Re-verified all four
  gates on the merged tree: `testGithubDebugUnitTest` **2727**/0/0/7,
  `testPlayDebugUnitTest` **2727**/0/0/19 (the +90 is Vaults v2, reconciled
  against main's count), `:shared` compiles for iOS, and the iOS app rebuilt
  from the merged tree still boots and renders the domain cascade
  (`ios_postmerge.png`). v2 crypto scope now folded into the port plan (§10):
  it adds HKDF, an HMAC header MAC, and — the sharp new item — a
  `java.text.Normalizer` NFKD seam on the BIP39 path. Now beginning the
  lowest-layer shared-core migration (`domain/`).
- **2026-08-10** — **P2 chunk 1 COMPLETE** (commit `c51f172`, §11). The whole
  domain calculation engine now lives in `:shared/commonMain`; four of six files
  moved verbatim. Both platform seams built as `expect`/`actual`: the scientific
  formatter (Android delegating to the original call, so Android is unchanged by
  construction; iOS **proven 10/10 byte-identical on-device**) and the
  kotlinx-datetime port with its catches correctly widened. Gates re-verified
  from a forced `--rerun-tasks` run: 2727/0 both flavors, both APKs, iOS
  compile green, and every migrated vector suite passing from `:shared`
  (TaxVectorTest 273, CashLedgerVectorTest 191, DomainVectorTest 158).
  **R8 is now 1-of-3 closed.** New R12 logged: a *pre-existing* Android-vs-web
  number-rendering divergence uncovered by this work — not introduced here, not
  urgent, but it needs a source-of-truth call and must be measured on ART rather
  than the JVM. **Next: replay the 622 domain vectors on Kotlin/Native.**
- **2026-08-10** — **P2 chunk 2 COMPLETE** (commit `1badadf`, §12). All 622
  domain conformance vectors now replay on Kotlin/Native
  (`:shared:iosSimulatorArm64Test`) as well as the JVM
  (`:shared:testAndroidHostTest`) — byte-exact, first run, zero divergence, no
  tolerance loosened. The engine's numerical output is now *proven* on-device,
  not merely compiled. Vector replay moved from `:app`'s JVM-only test source
  set into `:shared/commonTest`; `:app` reconciles exactly 2727 → 2101 on both
  flavors (626 moved). Fixtures code-generated past the 64 KB constant ceiling
  with a rejoin-hash guard; JUnit delta/NaN semantics preserved bit-for-bit;
  vector-count assertions make an empty fixture impossible to pass. All six
  gates verified independently of the builder. **This is a real milestone: the
  entire audited calculation engine is confirmed identical on Android and iOS.**
  Next P2 chunk: the `data/` layer — DTOs and the Room persistence seam (turn on
  `exportSchema` first, per D7).
- **2026-08-10/11** — **P2 data-layer, steps 1 & 2 COMPLETE.**
  - Step 1 (commit `fc697bf`): Room `exportSchema` turned on via the
    `androidx.room` Gradle plugin; **golden v10 schema committed** as a
    standalone contract artifact BEFORE any `db/` work, per D7. 18 entities,
    identityHash `a9fab166…`; spot-checked to reflect the migration endpoint
    (`sync_ops.errorCode`, the 9→10 column), not just base entities.
    Behaviourally inert — both flavors stay 2101/0, migration test green.
  - Step 2 (commit `7e67344`): **all 20 API DTO files (261 `@Serializable`
    classes) migrated to `:shared/commonMain`** — 19 R100 verbatim renames,
    `VersionDto.kt` ported java.time→kotlinx-datetime, one cross-module
    smart-cast fix in `IdeasRepository`. The `interval` field rode along
    verbatim. Gates verified independently: 2101/0 both flavors (unchanged),
    Native vector harness 13/0, iOS boots, and the Android shipping graph
    **proven byte-identical** (single serialization-json 1.9.0 / datetime 0.8.0,
    no Compose/Ktor/test-dep leak into `:app` runtime). The wire contract now
    compiles once for both platforms.
  **Next: the Room persistence seam** — the R3 heavy lift (entities, 13 DAOs,
  `@ConstructedBy` expect/actual, 9 migrations `SupportSQLiteDatabase` →
  `SQLiteConnection`), guarded by the golden schema whose identityHash must
  survive unchanged.
- **2026-08-11** — **P2 Room persistence seam COMPLETE — R3 CLOSED for the move**
  (§13, commit `c47b6f5`). The single riskiest migration in the program, done
  under the chief's explicit stop-condition, with **both invariants
  independently re-verified by the sub-coordinator before commit**: the golden
  v10 identityHash `a9fab166…` is byte-identical (schema unchanged), and Android
  DB behaviour is byte-identical (the 9 migrations + `hasColumn` guards + test
  moved **verbatim** into `androidMain`, unchanged; migration test 7/7). The
  chosen strategy sidestepped rewriting any Android migration: `@Database` +
  entities + DAOs live in `commonMain`, Android keeps its classic
  `SupportSQLiteDatabase` path, and iOS gets a fresh `BundledSQLiteDriver` v10
  (no existing data to migrate). Shipping graph proven unchanged (room 2.8.4 /
  sqlite 2.6.2; bundled driver iOS-only). The KMP DB was opened and queried on
  the simulator. All gates green; no stop trigger hit. **The entire data core —
  DTOs and persistence — plus the calculation engine now compile and run on iOS
  from shared source. Next: the network layer** (Ktorfit re-plumb per D8) and
  the `sync/` seams.
---

## 14. Phase W — the web target (opened 2026-08-19)

Owner ruling, 2026-08-19: the KMP program is the real port, and the UI that
moves into `:shared` serves **two** consumers, not one — a future iOS app *and* a
browser build via **Kotlin/Wasm**, to be served at `mobile-dev.bettertrack.at/app`.
Hosting, CORS and a web OAuth client are platform-side asks that are already
filed; none of them blocks the engineering below.

This section extends the plan; it overrules nothing above it. In particular the
CMP 1.10.3 ceiling (§6.3) and the verified stack (§6.6) still hold — **W0 moved
no version at all**, which is the first thing worth reporting.

### 14.1 W0 result — the browser target is real

`:shared` now declares `wasmJs { browser(); nodejs() }` alongside `android`,
`iosArm64` and `iosSimulatorArm64`, and a third consumer module `:webApp`
produces a browser bundle from it. The screen it renders is not a "hello canvas":
it runs `reducePosition` over a real transaction log and `computeSeriesStats`
over a real value series, and prints every number through `jsNumberToString` —
i.e. through the `formatScientific` `expect`/`actual` that decides vault
plaintext and the GCM AAD header — with de-AT separators.

**What compiles for `wasmJs`, measured not assumed.** Every dependency below was
checked against its published Gradle module metadata (`*.module`) at the exact
version §6.6 pins, before a line of build script was written:

| commonMain piece | wasmJs | Note |
| --- | --- | --- |
| `domain/` (7 files, the calculation engine) | **yes** | pure Kotlin; only `formatScientific` needed an actual |
| `data/api/dto/` (21 files, 261 `@Serializable`) | **yes** | kotlinx-serialization-json 1.9.0 publishes wasmJs |
| `data/net/` interfaces (`BtApiClient`, `ApiResponse`, `TokenRefresher`, …) | **yes** | pure interfaces |
| the Ktor client + 2 session plugins (was `iosMain`) | **yes** | ktor 3.5.2 core + `ktor-client-js` both publish wasmJs |
| kotlinx-datetime 0.8.0 | **yes** | |
| kotlinx-coroutines-core 1.10.2 | **yes** | but single-threaded — see R15 |
| CMP 1.10.3 (`runtime`/`foundation`/`ui`), material3 1.9.0 | **yes** | the ceiling is an *iOS-SDK* ceiling, not a web one |
| `data/db/` (Room: `@Database`, 18 entities, 13 DAOs) | **NO** | see 14.2 |

**Two new intermediate source sets** carry the split. Neither changes a byte of
what Android or iOS compiles — the same files, the same packages, the same
golden v10 identityHash:

- **`dbMain` = {android, ios}** — the Room layer, lifted out of `commonMain` so
  the browser target never sees it.
- **`nonAndroidMain` = {ios, wasmJs}** — the pure-Kotlin `formatScientific`
  actual and the seven engine-agnostic Ktor files, lifted out of `iosMain`. The
  formatter especially: a second Wasm copy of that arithmetic is the one thing
  this phase could have done that would put vault bytes at risk, so there is
  exactly one copy and the 622-vector harness gates every target that compiles it.

Both required an explicit `applyDefaultHierarchyTemplate()` call: KGP applies the
default template automatically only while a module declares no `dependsOn` edge
of its own, and silently falls back to the pre-1.9.20 layout otherwise (which
would detach `iosMain` from both iOS targets).

**It runs, and it is right.** Headless Chrome against the production bundle:
`/Users/cwiesi/bt_scratch/kmp-2026-08-19/web_w0_bringup.png`. Every value on that
page came out of `:shared/commonMain`, in de-AT:

| On screen | From |
| --- | --- |
| Ø cost `80,88 €`, realised `+74,92 €` | `reducePosition` over a 3-transaction log |
| market value `1.369,50 €`, unrealised `+156,37 €  +12,89 %` | the same position × a quote |
| total return `+8,88 %`, max drawdown `-4,36 %`, best day `2026-07-21 +5,93 %` | `computeSeriesStats` |
| `0.1 + 0.2` → `0.30000000000000004`, `1.23e-7`, `9007199254740992` | `jsNumberToString`, i.e. the `formatScientific` actual |
| `2025-12-31T22:30:00Z` → **2025**, `23:30:00Z` → **2026** | `Tax.viennaYearOf` across the CET tax-year boundary |

The last row is the one that had to be earned — see 14.2.

**Bundle size.** `:webApp:wasmJsBrowserDistribution`, production, measured with
`gzip -9`:

| Artifact | Raw | gzip -9 |
| --- | --- | --- |
| `skiko.wasm` | 8,642,989 (8.24 MiB) | 3,277,754 (3.13 MiB) |
| app `.wasm` | 1,830,381 (1.75 MiB) | 608,956 (595 KiB) |
| `webApp.js` (incl. `skiko.mjs` + js-joda core & tz) | 777,443 (759 KiB) | 131,819 (129 KiB) |
| `index.html` + licence | 4,980 | 1,412 |
| **shipped total** (sourcemap excluded) | **11,255,793 (10.7 MiB)** | **4,019,846 (3.83 MiB)** |

Excluded: `webApp.js.map`, 1,917,778 bytes, which production does not serve.

That is a first-load budget the product has to accept knowingly (R16): **78% of
it is Skia**, it is constant regardless of how much of the app ships, and it is
paid before the first pixel. It caches well and it does not grow linearly with
screens — but it is not a number a marketing page can hide.

**Gates**, all re-run with `--rerun-tasks` on the final tree:

| Gate | Result |
| --- | --- |
| `:shared:testAndroidHostTest` | **13 / 0 failures / 0 errors** |
| `:shared:iosSimulatorArm64Test` | **23 / 0 / 0** |
| `:shared:compileKotlinIosArm64` (+ SimulatorArm64) | green |
| `:shared:compileKotlinWasmJs` | green |
| `:shared:wasmJsNodeTest` | **13 / 0 / 0 — NEW** |
| `:webApp:wasmJsBrowserDistribution` | green |
| golden v10 schema | md5 `d90e198351d6e5ac062a7c55fdd7a2c5`, identityHash `a9fab166f6bcb1451ac240972a08a408` — **unchanged**, and `shared/schemas` still byte-identical to `app/schemas` |
| `:app` githubDebugRuntimeClasspath | **zero** `io.ktor`, `org.jetbrains.compose`, `skiko`, `js-joda`, `sqlite-bundled`; room 2.8.4 / sqlite 2.6.2 exactly as before |

**`wasmJsNodeTest` 13/0 is the headline.** It is the same commonTest harness that
gives 13/0 on the JVM, so **all 622 domain conformance vectors — holdings, series
stats, cash ledger, tax, server-TWR parity — now replay byte-exact on
Kotlin/Wasm as well as on the JVM and Kotlin/Native.** No tolerance was loosened
and no vector was skipped. D5's cross-platform gate now covers three runtimes.

### 14.1a Three things W0 found that a compile-only bring-up would have shipped

1. **`Tax.viennaYearOf` crashes the wasm module.** kotlinx-datetime on Wasm is
   js-joda, and js-joda carries no IANA zone rules; kotlinx-datetime declares
   `@js-joda/core` and nothing else. `TimeZone.of("Europe/Vienna")` does not
   throw — it takes the module down with
   `RuntimeError: dereferencing a null pointer` inside `toLocalDateTime`, i.e.
   on every taxable transaction. Fixed by adding `npm("@js-joda/timezone",
   "2.3.0")` **and** a `@JsModule` external declaration that is reachable from
   `:webApp`'s `main()` — the npm dependency alone changes nothing, because the
   package is side-effect-only and DCE removes an unreferenced import from the
   production bundle. `2.3.0` is the newest line whose peer range admits the
   `@js-joda/core` 3.2.0 that kotlinx-datetime pins (2.25.2 wants core ≥ 5.7.0).
   Cost: 200 KiB in the bundle. Found by running the vectors, not by reading a
   changelog.
2. **Kotlin 2.3.20 crashes on incremental wasmJs recompilation.** Editing a
   wasmJs source file and rebuilding fails with
   `ArrayIndexOutOfBoundsException … WasmIrFileMetadata.fromByteArray` →
   "Internal compiler error". Reproduced twice; deleting
   `shared/build/kotlin/compileKotlinWasmJs` made the identical sources compile.
   `kotlin.incremental.wasm=false` is now set in `gradle.properties` with the
   stack trace recorded next to it. Revisit past 2.3.20.
3. **`FAIL_ON_PROJECT_REPOS` is incompatible with a Kotlin/Wasm browser build.**
   KGP downloads Node.js, Yarn and Binaryen's `wasm-opt` by registering ivy
   repositories from inside its own setup tasks — project code — and the guard
   fails on the ADD, so declaring identical repositories in settings does not
   satisfy it. `settings.gradle.kts` now uses `PREFER_SETTINGS` and declares all
   three toolchain repositories itself, each `content { includeModule(...) }`-
   scoped so nothing else can resolve against them. Project-declared
   repositories are still ignored, which is the property that mattered.

### 14.2 R13 (new, RED for storage) — Room has no wasm target

Checked against the published Gradle module metadata on `dl.google.com`, not
against documentation:

- `androidx.room:room-runtime` **2.8.4 — the newest release** — publishes
  android, jvm, and 17 native targets. There is **no** `room-runtime-wasm-js`
  and no `room-runtime-js`, at *any* version. Only `room-common` (the bare
  annotations) ships a wasmJs artifact, from 2.8.0.
- Its driver is in the same state: `androidx.sqlite:sqlite-bundled` has no wasm
  variant either. `androidx.sqlite` **has** begun publishing browser pieces
  (`sqlite-wasm-js`, `sqlite-web-wasm-js` at 2.7.0), so the platform intent
  exists — Room simply cannot consume them yet.

**Decision: absence with a named seam, never a stub.** No fake driver was added
to make the browser compile. `dbMain` carves the persistence layer out of the
wasm compilation entirely, so the browser build has *no* local database and says
so at the type level rather than pretending to persist. Three futures, to be
chosen in W6:

1. **Server-only mode in the browser** — no local mirror, every read hits the
   API. This is how the existing web client already behaves, so it is the
   least surprising, and it is the recommended W6 default.
2. **An IndexedDB/OPFS store behind the existing interfaces.** `interface
   OpStore` is already Room-free and Android-free (§4.4), so the outbound queue
   in particular has a clean place to land.
3. **Wait for Room over `sqlite-web-wasm-js`.** Free when it arrives; not
   plannable.

The sharp consequence of (1) is not technical: §7.3's durable outbound sync
queue — "an app update must never drop queued ledger events" — has **no durable
home in a browser tab** under server-only mode. Mutations on the web would be
online-only. That is a product decision for the owner, not an engineering one.

### 14.3 Per-layer verdicts for the browser

Same discipline as §4.4: measured over `app/src/main/java` in this worktree
(note the branch is behind `main` — `main` carries 2984 string keys and a
32-file widget package this branch has not merged yet).

- **Strings/resources — shares after seam; the seam is a build migration, not a
  code one.** 2984 keys × EN/DE (2021 + 35 plurals on this branch), reached
  through 2483 `R.string.` refs in 99 files, of which 1823 are
  `stringResource(...)` inside composition and ~660 are not. compose-resources
  (`components-resources`, wasmJs-published at 1.10.3) reads the *same*
  Android-style `<string>`/`<plurals>` XML, so the files move rather than
  convert and the refs rewrite mechanically to `Res.string.*`. Three sharp
  risks: **(a)** on web, resources are fetched, so `stringResource` resolves
  asynchronously and a naive port shows an **empty first paint**; **(b)** the
  ~660 non-composable sites need `getString(Res.string.x)`, which is `suspend` —
  each one is a decision, not a find-and-replace; **(c)** §4.7's in-app EN/DE
  switch (`LocaleManager.wrap`/`recreate()`, no iOS analogue) needs the same
  `CompositionLocal` redesign here, seeded from `navigator.language`.
- **Navigation — shares after seam.** `org.jetbrains.androidx.navigation:navigation-compose`
  2.9.2 publishes wasmJs, and R7 already proved `BtSheetNavigator` compiles
  against it unchanged, so the 49 `btSheet<T>` routes and 4 pager tabs port. The
  seam is the thing neither Android nor iOS has: **the URL bar and the browser
  Back button**. Compose nav does not wire itself to `window.history`; without an
  explicit bridge, Back leaves the app and no screen is addressable or
  bookmarkable. Not hard, but it is invisible until a user presses Back.
- **ViewModels — shares as-is.** 42 of them, still zero `AndroidViewModel` /
  `SavedStateHandle` / `Context`; `lifecycle-viewmodel-compose` 2.11.0 publishes
  wasmJs. The caveat is not the ViewModels, it is the runtime underneath them:
  **Kotlin/Wasm is single-threaded**. `runBlocking` does not exist there at all
  (6 sites in 4 files) and neither does `Dispatchers.IO` (12 sites in 8 files);
  `synchronized` (14 sites) is unavailable on Native and Wasm alike. Every one is
  a compile error the moment that file moves to `commonMain`, which is the good
  kind of risk — loud, and countable in advance.
- **Storage — gates off (Room) / shares after seam (prefs).** See 14.2 for the
  database. The 10 `SharedPreferences` stores go to `multiplatform-settings`
  (D11), which publishes wasmJs and is backed by `localStorage`; the token store
  does **not** follow them (see auth).
- **Crypto / vault — gates off, on D4's precedent.** D4 already says: if
  byte-identity is not *demonstrated* by the vectors, vault features are disabled
  behind a clean flag rather than approximated. The web has two independent
  reasons to invoke it. First, Argon2id at m=65536 KiB / t=3 wants 64 MB of
  linear memory and runs on the **single UI thread** of a tab — the canvas
  freezes for its whole duration, and there is no Web Worker path wired here
  (R15). Second, AES-GCM in a browser means WebCrypto (`crypto.subtle`), which
  is async and secure-context-only. Also worth flagging early: D3 puts DEFLATE
  *above* Argon2 on the risk register, and browsers ship `CompressionStream`
  natively — an advantage over Native, but its exact byte output is not
  specified to match zlib's default strategy, so it is a vector question, never
  an assumption.
- **Auth / OAuth — redesign, small but real.** Android's flow is Chrome Custom
  Tabs returning to `bettertrack://oauth/callback`, the app's *only* deep link
  (§4.5). A browser has no custom-scheme return leg, so it becomes a normal
  https redirect — the platform ask is filed and a web client is being minted.
  Three consequences the redirect drags along: it is a **public** client, so
  PKCE is mandatory and there is no secret; there is no
  `EncryptedSharedPreferences`/Keychain, so a refresh token in `localStorage` is
  XSS-readable and the honest choices are a short-lived in-memory access token
  with an httpOnly refresh cookie (a platform endpoint) or re-login on reload;
  and a full-page redirect **destroys all in-memory state**, so state restore
  has to survive a reload before login can be called done. `TokenRefresher`
  itself is untouched — it is already a common interface, and its single-flight
  contract is unchanged.
- **Charts / canvas — shares as-is.** 9 `Canvas(` sites in 7 files, 100%
  hand-drawn, rendered by Skiko on WebGL. The caveat is not the charts, it is
  the medium (R14): CMP paints the **entire app into one canvas**, so there is
  no DOM text anywhere — no browser text selection, no find-in-page, no SEO, and
  accessibility is CMP's own bridge rather than real semantics. Acceptable for
  an authenticated dashboard; unacceptable to discover late.
- **Widgets — N/A.** 32 files / 9 Glance widgets on `main`. Glance is
  `RemoteViews` in an Android launcher; there is no browser equivalent, and the
  nearest analogues (PWA install, `manifest.json` shortcuts) are a different
  product rather than a port. This branch has not merged them yet either.
- **Theme / typography — shares as-is after a move.** `ui/theme` is 1439 LOC of
  pure Compose (`BtColors`/`BtTypography`/`BtShapes`). One real cost: a canvas
  has no system font, so every face must be embedded as a compose-resource, and
  **132 distinct `Icons.*` symbols** currently come from androidx
  `material-icons-extended` 1.7.8 (dropped from recent BOMs) — the JetBrains
  coordinate is a different artifact and its pairing must be re-derived, with
  the bundle-size budget watching.
- **Formatters (D1 / R8) — shares after seam, and this is the highest-value item
  in the whole web phase.** `BtNumberFormat` is 194 LOC on ICU
  (`java.text.NumberFormat`, `BigDecimal`, `Currency.getSymbol`), contractually
  byte-identical to the web client (PLATFORM_ASKS #18/#19). Wasm has no ICU —
  but the browser has `Intl.NumberFormat`, **which is exactly what the web
  client already uses and what the Android formatter was written to match**. So
  the wasm actual is plausibly a thin `Intl` call that reaches byte-identity by
  construction. Doing it properly finishes D1 for all three runtimes at once and
  closes **R8 2-of-3**, which iOS needs anyway. Do not let it be improvised as a
  presentation shim; W3 exists for it.
- **Notifications — redesign / gates off.** 4 channels + FCM today. Web push
  needs a service worker and VAPID keys; the server's delivery matrix already
  carries a `webpush` axis (§4.5), so the data side exists and the client side
  does not.
- **App lock / biometrics — gates off.** §4.7 already calls the Android
  Keystore-HMAC PIN custody a redesign rather than a port for iOS; a browser is
  a third custody model again (WebAuthn), and it is not on the critical path.
- **Google Drive — shares after seam.** The most portable code in the repo
  (§4.5), plain REST over OkHttp; it becomes the same Ktor client. The new
  questions are the *web* OAuth scope on the same new client, and whether
  `googleapis.com` CORS admits a browser client at all.
- **WorkManager / sync scheduling — redesign.** 2 one-shot idempotent workers.
  A tab has foreground drain and nothing else — the same degradation §4.5
  already accepted for iOS (no data loss, only latency), minus even the
  opportunistic background refresh.
- **Discreet mode — shares as-is.** Pure Kotlin state; nothing platform-shaped.

### 14.4 New risks

| # | Risk | Colour |
| --- | --- | --- |
| R13 | Room publishes no wasmJs artifact at any version; the browser has no local database and, in server-only mode, no durable outbound queue | RED (storage) |
| R14 | CMP web is one canvas: no DOM text, no selection, no find-in-page, no SEO, a11y via CMP's bridge only | AMBER |
| R15 | Kotlin/Wasm is single-threaded: no `runBlocking`, no `Dispatchers.IO`, no `synchronized`; Argon2id at 64 MB / t=3 freezes the tab | AMBER |
| R16 | First-load bundle is dominated by Skia and is paid before the first pixel | AMBER |
| R17 | Browser token custody: no Keychain, no EncryptedSharedPreferences; `localStorage` is XSS-readable | AMBER, needs an owner call |
| R18 | Kotlin 2.3.20 crashes on incremental wasmJs recompilation (`WasmIrFileMetadata.fromByteArray`); mitigated by `kotlin.incremental.wasm=false`, so wasm builds are always non-incremental until the compiler moves | GREEN (mitigated), do not silently re-enable |
| R19 | The IANA zone database reaches the browser ONLY through a live call to `btInstallTimeZoneDatabase()` from the host's `main()`. A future web host that forgets it compiles fine and then crashes the module on the first tax computation — a runtime cliff with no compile-time signal | AMBER; W1 should make it unforgettable (an entry-point helper, or an `Intl`-based `viennaYearOf` actual) |
| R20 | compose-resources' readers only work from inside composition on wasm: `Res.readBytes(...)` from a plain coroutine suspends forever and never issues the request. W2 moves 2984 string keys onto this library, and every non-composable `getString(...)` site (~660 of them, §14.3) is exactly this shape | AMBER, and it is W2's problem more than W1's |
| R21 | compose-resources' DRAWABLE path renders nothing on CMP 1.10.3 / wasm — `painterResource(Res.drawable.x)` fetches the file and returns a painter that draws no pixels. Worked around in W1 by carrying the one image the shell needs inside the binary; a screen with real imagery cannot do that | AMBER |
| R22 | A `:shared` wasm test binary now links Skia, and skiko's emscripten glue has its Node branch compiled out, so `wasmJsNodeTest` cannot load its own `.wasm`. The conformance harness moved to `wasmJsBrowserTest` (Karma + headless Chrome), which is closer to production but adds Chrome to the gate's requirements | GREEN (resolved), but CI needs a browser |
| R23 | The brand mark `splash_bt_glyph.png` is white-on-transparent, so on the LIGHT page only the gold "T" is visible — the "B" disappears into `#FFFFFF`. Pre-existing on Android (same asset, same light table), surfaced by rendering both tables side by side. Not fixed here: it is an Android visual change and therefore an owner call | AMBER, product |

### 14.5 Milestones

Sized in **builder-days** (one focused builder, this machine, serialized Gradle
lane). They assume W0 as landed and exclude anything the platform owes.

- **W1 — the shell renders (8–10 d). LANDED — see §14.6.** Move `ui/theme` and
  one real screen into `:shared/commonMain`; **reconcile the Compose BOM against
  CMP** (§6.6's open caveat — this, not the screens, is the blocker); embed
  fonts; settle the icon artifact; seed locale from `navigator.language`. Exit:
  one production screen in the browser with the real theme, Android
  byte-identical.
- **W2 — strings (6–8 d).** 2984 keys × EN/DE to compose-resources; 2483 refs
  rewritten; the ~660 non-composable sites decided one by one; async resolution
  proven not to flash empty. Exit: no `R.string` in shared code, both platforms
  green.
- **W3 — the formatter (5–7 d; highest value).** Finish D1: `BtNumberFormat`
  becomes one vector-gated `expect`/`actual`, wasm actual over
  `Intl.NumberFormat`, vectors replaying on JVM + Native + Wasm. Closes R8
  2-of-3 and pays for itself on iOS.
- **W4 — navigation and history (4–6 d).** 49 sheet routes + 4 tabs on
  multiplatform navigation; bridge the back stack to `history.pushState` /
  `popstate`; Back must not exit the app; decide the URL shape.
- **W5 — auth (5–7 d, platform-gated).** PKCE redirect against the new web
  client; token custody decided explicitly (R17); a reload must not silently log
  the user out mid-session without saying so.
- **W6 — data (8–12 d).** Server-only mode behind the existing repository
  interfaces, or the IndexedDB/OPFS `OpStore` — the R13 decision. Includes the
  mutation story without a durable queue.
- **W7 — the long tail (open).** Vault gate-off flag, web push, Drive, a11y
  pass, bundle budget, CI job for the wasm build.

**Honest total: ~36–50 builder-days** from here to a browser build showing real
logged-in data, W7 and platform work excluded. The three that can surprise are
W1 (the BOM reconciliation is a real unknown), W2 (async resources) and W6 (a
product decision sits inside it).

### 14.6 W1 result — the shell renders

The browser now draws a **production screen out of `:shared/commonMain`**: the
login screen, in both colour tables, in the real type ramp, with the app's own
brand mark and its own gear glyph, formatted by a locale seeded from
`navigator.language`. Nothing on that page re-implements a token, a dp or a
layout — it is the same source `:app` compiles.

**The BOM caveat is closed, and it cost zero version moves.** §6.6 asked whether
the Compose BOM and CMP can coexist once UI is in `commonMain`. They can, and
the reason is a property of the artifacts rather than a compromise. Read from
the published Gradle module metadata of each 1.10.3 artifact: **every
`org.jetbrains.compose` androidJvm variant publishes an EMPTY file list** — they
are pure redirects. On Android:

| `compose.*` accessor | org.jetbrains version | its android variant `requires` | :app's BOM 2026.06.01 | resolved |
| --- | --- | --- | --- | --- |
| `compose.runtime` | 1.10.3 | androidx.compose.runtime 1.10.5 | 1.11.4 | **1.11.4** |
| `compose.foundation` | 1.10.3 | androidx.compose.foundation 1.10.5 | 1.11.4 | **1.11.4** |
| `compose.ui` | 1.10.3 | androidx.compose.ui 1.10.5 | 1.11.4 | **1.11.4** |
| `compose.material3` | **1.9.0** | androidx.compose.material3 **1.4.0** | 1.4.0 | **1.4.0** |

Those are `requires`, not `strictly`, so Gradle takes the higher and `:app` stays
on exactly the versions it shipped before — verified in the resolved
`githubDebugRuntimeClasspath`, where every CMP request appears literally as
`1.10.5 -> 1.11.4`. The shared UI is therefore *compiled against* the same
androidx Compose the app runs. The BOM was neither dropped nor pinned to CMP; it
stayed in `androidMain`, where being a platform is what pulls CMP's redirects up.
On iOS and wasmJs there is no BOM and the 1.10.3 klibs govern, unchanged from W0.

**What that adds to the APK: one 4,183-byte AAR.** `:app`'s runtime classpath
gains 20 `org.jetbrains.compose.*` and 7 `org.jetbrains.androidx.{lifecycle,savedstate}`
nodes, and every one of them was checked: all publish `files: []` on
`releaseRuntimeElements`. The single exception is
`org.jetbrains.compose.ui:ui-backhandler-android:1.9.1`, a real 4,183-byte
release AAR pulled by material3. `androidx.lifecycle` stays at 2.11.0 (the JB
redirects ask for 2.9.4). Still **zero** `io.ktor`, `org.jetbrains.skiko`,
`js-joda` or `sqlite-bundled` on `:app` — W0's invariant holds.

**What moved, and what stayed behind which seam.** 2,678 lines, of which the
theme moved as a literal relocation — `BtColors.kt`, `BtIcons.kt`, `BtShapes.kt`
and `BetterTrackTheme.kt` are **byte-identical to their `:app` originals**
(`diff` against `HEAD`, zero lines), and so are `BtButtons.kt` and `BtMotion.kt`.

| Now in | What | Note |
| --- | --- | --- |
| `commonMain/ui/theme` | the whole package (1,439 LOC) + `BtFonts.kt` | 4 of 5 files byte-identical; `BtTypography.kt` differs by exactly two lines |
| `commonMain/ui/components` | `Wordmark`, `BtButtons`, `BtMotion`, the `BtHaptics` contract, the `rememberReducedMotion` expect | Wordmark differs by one line |
| `commonMain/ui/auth` | `BtLoginScreen` + `LoginStrings` | the layout, weights, entrance animation and every dp are the Android screen's |
| `commonMain/data/prefs` | `BtThemeMode` | package unchanged, so no import in `:app` moved |
| `commonMain/data/auth` | `LoginPhase`, `LoginError` | same |
| `androidMain/ui/components` | the whole `BtHaptics` motor + `rememberReducedMotion` actual | verbatim, including its device measurements |
| `nonAndroidMain` / `wasmJsMain` / `iosMain` | the no-op haptics, `prefers-reduced-motion`, `UIAccessibility` | 59 lines total |
| stayed in `:app` | `LoginScreen.kt` — now a 111-line Android host | 16 `R.string` reads, one `R.drawable`, `Icons.Outlined.Settings`, `PreLoginSettingsSheet` |

`:app`'s two call sites (`BtRoot`, `StorageSetupWizard`) are **untouched**: the
host keeps the original signature. Three things could not cross into
`commonMain` and are parameters rather than rewrites — the strings (W2), the two
images (W2/W7), and the pre-login settings sheet, which reads
`SharedPreferences` and switches server origins (W5/W6). Android passes exactly
what it passed before, so its pixels are unchanged by construction.

**Fonts.** Roboto ships as ONE variable font — Google Fonts
`ofl/roboto/Roboto[wdth,wght].ttf`, SIL OFL 1.1, **488,584 bytes / 280,258
gzipped** — registered four times at four `wght` positions, because the design's
hierarchy is built almost entirely out of weight and synthetic bolding is not a
600. `BtTypography`'s face became `by lazy { BtFonts.appFontFamily }`; on Android
and iOS nothing ever installs anything, so that reads `FontFamily.Default`
exactly as the eager line it replaced. Digits line up in one ruler-straight
column on the canvas — and the screenshot states the honest reason, which is that
Roboto's default lining figures are already tabular, so the app's `tnum` is
belt-and-braces rather than the thing doing the work.

**Icons, settled two different ways.** The favicon is `art/BT_AppIcon.png`
base64-inlined as a `data:` URI in `index.html` — no request, ever, which matters
because a favicon is fetched before anything else on an authenticated page. The
in-app 72dp mark is `splash_bt_glyph.png`, the same bytes Android draws, carried
inside the wasm binary (see R21). **Material's icon set has no answer on this
stack**: `org.jetbrains.compose.material:material-icons-extended` stops at
**1.7.3**, whose klibs are `abi_version=1.8.0 / compiler_version=1.9.24 /
metadata_version=1.4.1` — pre-2.0 metadata that Kotlin 2.3.20 will not read — and
the wasmJs klib is **23,888,396 bytes** anyway. So the web substitutes the app's
own `BtIcons.Settings` for the login gear; that is the one glyph where the two
renders differ today, and the 132-symbol question is still open for W7.

**Locale.** `navigator.language` is read exactly once, in `main()`, and mapped to
de-AT or an English fallback; it seeds `<html lang>` and the number conventions.
Proven side by side: `de-AT` → `1.369,50 €`, `+12,89 %`; `en-US` → `1,369.50 €`,
`+12.89%`. The formatter itself is still W0's provisional shim — **W3 owns
`BtNumberFormat`** and nothing here pre-empts it.

**Gates**, all re-run with `--rerun-tasks` on the final tree:

| Gate | Result |
| --- | --- |
| `:app:testGithubDebugUnitTest` | **2099 / 0 failures / 0 errors** / 7 skipped, 172 suites |
| `:app:testPlayDebugUnitTest` | **2099 / 0 / 0** / 19 skipped, 172 suites |
| `:app:assembleGithubDebug` | green — 95,721,794-byte debug APK |
| `:app:lintGithubDebug` | **0 errors**, 102 warnings, 2 hints |
| `:shared:testAndroidHostTest` | **13 / 0 / 0** |
| `:shared:iosSimulatorArm64Test` | **23 / 0 / 0** |
| `:shared:wasmJsBrowserTest` | **13 / 0 / 0 — the 622 vectors, now in a real browser** |
| `:shared:compileKotlinIosArm64` (+ SimulatorArm64, WasmJs) | green |
| `:webApp:wasmJsBrowserDistribution` | green |
| golden v10 schema | md5 `d90e198351d6e5ac062a7c55fdd7a2c5`, identityHash `a9fab166f6bcb1451ac240972a08a408` — unchanged; `shared/schemas` still byte-identical to `app/schemas` |

The `:app` count reads 2099 rather than §13's 2101 because that figure predates
`02f889d`/`a3f81ce`; W1 moved no test at all, which is checkable rather than
assertable — `git grep -E '^[[:space:]]*@Test' -- app/src/test` returns **2082 in
167 files at `HEAD` and 2082 in 167 files in the worktree**.

**Two guards were repointed rather than left to rot.** `BtThemeDisciplineTest`
and `PreLoginSettingsTest` read `:app`'s `ui/` tree off disk, so a migration
narrows them silently. Both now walk `:shared`'s `ui/` trees as well, and the
pre-login guard checks each owner-mandated invariant on whichever side of the
seam now owns it — the server line is still display-only and the gear still has
a `bt_dest_settings` description, asserted across two files instead of one.

**Bundle**, `gzip -9`, sourcemap excluded:

| Artifact | Raw | gzip -9 | vs W0 |
| --- | --- | --- | --- |
| `skiko.wasm` | 8,642,989 | 3,277,728 | unchanged |
| app `.wasm` | 2,393,521 | 786,912 | +563,140 / +177,956 |
| `webApp.js` | 780,512 | 132,495 | +3,069 / +676 |
| `index.html` + licence | 19,021 | 9,401 | +14,041 / +7,989 (the inlined favicon) |
| `composeResources/…/roboto_variable.ttf` | 488,584 | 280,238 | new |
| **shipped total** | **12,324,627 (11.75 MiB)** | **4,486,774 (4.28 MiB)** | **+1,068,834 raw (+9.5%) / +466,928 gz (+11.6%)** |

Skia is still 70% of it. The whole product UI — theme, components, a screen, and
the brand mark carried as bytes — cost 563 KB of wasm; the typeface cost 489 KB.
R16 stands unchanged in character.

Screenshots (headless Chrome, production distribution, 1:1 device pixels):
`/Users/cwiesi/bt_scratch/kmp-2026-08-19/web_w1_shell_de.png` and
`…/web_w1_shell_en.png`.

### 14.6a Four things W1 found that a compile-only move would have shipped

1. **The wasm conformance harness cannot run under Node any more.** Putting
   Compose in `commonMain` puts Skia on every wasmJs binary the module makes,
   the test binary included, and skiko's emscripten glue is a browser build with
   its Node branch compiled out — literally `if (false) { …fs.readFileSync… }`.
   Under Node it has no `readBinary` and `ENVIRONMENT_IS_WEB` is false, so it
   never reaches `fetch` either: `Aborted(both async and sync fetching of the
   wasm failed)`, with the 8.2 MB `skiko.wasm` sitting next to it in the same
   directory. Fixed by moving the harness to `wasmJsBrowserTest` (Karma +
   `ChromeHeadlessNoSandbox`), which is a better gate — the vectors now replay in
   the engine the product ships in — at the cost of a browser in CI (R22). Karma
   resolved out of the shared `~/.kotlin/kotlin-npm-tooling` install, so
   `kotlin-js-store/yarn.lock` is untouched.
2. **compose-resources only reads from inside composition here.**
   `Res.readBytes(...)` from a plain `CoroutineScope(Dispatchers.Main)` coroutine
   suspends and never resumes, and never issues the request. Localised with
   stage markers written into the page's bootstrap element: execution reached the
   coroutine and resumed across a `delay(1)`, then stopped dead at the first
   `Res.readBytes`; the dev server logged `index.html`, `webApp.js` and both
   `.wasm` files and nothing else. The composable readers work. This is R20, and
   it is aimed straight at W2's ~660 non-composable `getString` sites.
3. **compose-resources' drawable path renders nothing.**
   `painterResource(Res.drawable.bt_glyph)` fetches the PNG — 200 in the server
   log — and returns a painter that draws no pixels. Proven rather than assumed
   by putting a `goldWash` background behind the `Image`: the 64dp square
   painted, the mark did not. The font half of the same library works on the same
   page. R21; worked around by carrying the mark's bytes in the binary.
4. **The brand mark disappears on the light page.** `splash_bt_glyph.png` is a
   white "B" and a gold "T" on transparency, so on `#FFFFFF` only the T survives.
   This is **pre-existing on Android** — same asset, same light table — and it
   became visible only because the shell renders both tables side by side. Left
   alone: fixing it changes Android pixels, which is an owner call (R23).
