# Maintenance ledger — what was removed/simplified, why, and which test guards it (owner's maintainer program, 2026-10-30)

| MB | change | why | guard | commit |
|---|---|---|---|---|
| MB-54 | `gradle.properties`: heap comments trimmed to the rule; values unchanged (`org.gradle.jvmargs=-Xmx3g`, `kotlin.daemon.jvmargs=-Xmx5g`). History moved here: Kotlin daemon bounded after OOMs under concurrent invocations (2026-08-05); 3g→5g on 2026-08-23 when ~4100 tests made two-flavor compiles OOM ("Not enough memory to run compilation" / ICE / GC overhead), one run went ~30 min of thrash → 6m40s; Gradle daemon 2g→3g on 2026-08-26 after "running out of JVM heap space" on combined two-flavor `--rerun-tasks` gates. | the comments were a changelog | none (comments only) | (this row's commit) |
