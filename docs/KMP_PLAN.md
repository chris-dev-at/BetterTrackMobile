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
| R2 | Argon2id byte-identity on iOS | BouncyCastle is pure Java, unusable on Kotlin/Native. Vault data is unreadable if the KEK differs by one byte. Owner rule: never approximate crypto — gate the feature off on iOS instead. | UNDER TEST |
| R3 | Room schema continuity for existing Android users | Android users sit at schema v10. Any persistence change that breaks the migration chain corrupts real installs. This is why SQLDelight is not the default choice. | OPEN |
| R4 | Compose Multiplatform maturity for the custom shell | The pager-tab + gesture-tuned sheet navigator is hand-built and heavily tuned (notches, detents, haptics, occlusion culling). It is the most Android-coupled UI in the app. | OPEN |
| R5 | Android regression on this branch | The hardest constraint of the program: the Android app must never get worse. Mitigated by the 2637-test gate on every phase. | CONTROLLED |
| R6 | Background work + push differ fundamentally on iOS | WorkManager has no iOS equal (BGTaskScheduler is far more restrictive); FCM/APNs differ. Must be planned honestly, never improvised. | OPEN |

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

## 4. Architecture decisions

_Pending P0 survey + probe results. Each decision lands here with the evidence
that justified it, not as an assertion._

---

## 5. Program log

- **2026-08-09** — P0 opened. Baseline captured (2637 green, assemble green).
  `local.properties` recreated in the worktree. Five parallel surveys
  dispatched: data layer, vault/domain/sync, UI, Android platform surface,
  and an empirical toolchain probe (AGP9+KMP, CMP, Room KMP, Ktor).
