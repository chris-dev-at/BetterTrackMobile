# Maintenance ledger — what was removed/simplified, why, and which test guards it (owner's maintainer program, 2026-10-30)

| MB | change | why | guard | commit |
|---|---|---|---|---|
| MB-30 | `OAuthConfig.kt` KDoc: 5 passages corrected to 21 scopes / `feedback:read` requested since 2026-08-20 | prose contradicted its own constants | `OAuthScopeTest` (21-scope assertions) | 9fafc36 |
| MB-31 | `TokenManager.kt`: platform citations by function (`exchangeRefreshToken`, `rotateRefreshToken`, `loadBearerAuth`) + platform SHA 5c2fc2ae instead of line ranges | cited ranges had moved (refusals now 1081-1139; 371-413 is a different function) | none (comments only) | ee7033b |
| MB-32 | `BtApi.kt` + `OAuthConfig.kt`: removed "chain admin is session-only / never rendered" | platform `MIRRORCHAIN_BEARER_ROUTE_ALLOWLIST` has all admin routes, session-only list empty (5c2fc2ae) | none (comments only) | 47c5ef6 |
| MB-33 | `OPUS_COORDINATOR_HANDOFF.md`: July claims corrected in place, marked *(updated 2026-10-30)* | 9+ false statements (identity, scopes, tests/Room, device, /auth/me, logout, gaps 1-5/7/8) | none (doc) | dc19b31 |
| MB-34 | `docs/TODO.md`: ticked WS re-test, S3, S5, S6; annotated S4 | shipped work left unticked (evidence = the file's own ledger) | none (doc) | 8cdff3d |
| MB-35 | `docs/S3S4_STORAGE_PLAN.md`: pin → `tools/domain-vectors/PINNED_AT` | `439d0d5d` stated as the live pin; port is at ef73d282 | none (doc) | 5e21742 |
| MB-37 | `COORDINATOR_MACHINE_MOVE.md` → `docs/archive/` with a header; `HandOffPlan.md` kept (product contract) | zero references, one-off 2026-08-13 prompt | none (doc) | 5429740 |
| MB-50 | CI: `:app:testGithubDebugUnitTest :app:lintGithubDebug` step before the APK build, reports uploaded on failure | tests + lint ran only by hand | the CI step itself | b30c73c |
| MB-54 | `gradle.properties`: heap comments trimmed to the rule; values unchanged (`org.gradle.jvmargs=-Xmx3g`, `kotlin.daemon.jvmargs=-Xmx5g`). History moved here: Kotlin daemon bounded after OOMs under concurrent invocations (2026-08-05); 3g→5g on 2026-08-23 when ~4100 tests made two-flavor compiles OOM ("Not enough memory to run compilation" / ICE / GC overhead), one run went ~30 min of thrash → 6m40s; Gradle daemon 2g→3g on 2026-08-26 after "running out of JVM heap space" on combined two-flavor `--rerun-tasks` gates. | the comments were a changelog | none (comments only) | af70040 |
