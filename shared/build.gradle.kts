// ── :shared — the KMP module (KMP/iOS port, Phase 1) ───────────────────────
// Holds Kotlin that both the Android app and the iOS app compile from the SAME
// source. Phase 1 keeps it deliberately thin: the pure-domain seam plus the
// Compose Multiplatform UI the iOS executable renders. No Room, no Ktor, no
// settings/lifecycle libraries yet — those land in Phase 2.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The ANDROID target. MUST be the KMP-specific Android plugin: plain
    // `com.android.library` is rejected outright by AGP 9 when combined with
    // kotlin.multiplatform. See gradle/libs.versions.toml [plugins].
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Compose Multiplatform: the runtime/library half (dependency accessors)…
    alias(libs.plugins.compose.multiplatform)
    // …and the Kotlin COMPILER half. Both are required on a KMP module; the
    // JetBrains plugin does not imply the compiler plugin.
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // At Kotlin 2.3.20 the Android target of a KMP module is configured through
    // `kotlin { android { … } }`. (The older top-level `androidLibrary { }`
    // block still resolves but is deprecated at 2.3.x and warns.)
    android {
        namespace = "at.bettertrack.shared"
        // Match :app exactly so the two never disagree about the platform they
        // compile against — :app is compileSdk 37 / minSdk 28.
        compileSdk = 37
        minSdk = 28
    }

    // Real devices (arm64) and the Apple-silicon simulator. No iosX64: this Mac
    // is Apple silicon, and an Intel-simulator target would only add dead build
    // time. Both targets share one `iosMain` source set via Kotlin's default
    // hierarchy template, so iOS code is written once.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // commonMain — compiled for BOTH platforms. Phase 1 puts only pure
        // domain code here (at.bettertrack.app.domain.SettingsScope), which is
        // why it declares no dependencies at all.
        //
        // androidMain — the Compose RUNTIME only, and pinned by :app's own BOM.
        //
        // This is the load-bearing decision of Phase 1. On an Android target the
        // JetBrains `compose.*` accessors resolve to the ANDROIDX Compose
        // artifacts, so declaring the UI stack (foundation/material3/ui) in
        // commonMain would push a second, CMP-chosen androidx.compose version
        // into :app's dependency graph and silently move the shipping app off
        // its `composeBom` — CMP 1.10.3 pulls androidx.compose.runtime 1.10.5.
        // The Phase-1 gate is "the Android app is completely unharmed", so the
        // UI is scoped to iosMain below.
        //
        // androidMain cannot be left EMPTY, though: the Compose compiler plugin
        // is applied per-module, not per-target, so it also runs over the
        // Android compilation and aborts there with
        //   IncompatibleComposeRuntimeVersionException: The Compose Compiler
        //   requires the Compose Runtime to be on the class path
        // even though no androidMain/commonMain file is @Composable. The runtime
        // is therefore declared here — but via `androidx-compose-bom`, THE SAME
        // BOM :app resolves against, so the coordinate and version are byte-for-
        // byte what :app already had and no resolution result changes.
        //
        // When shared UI genuinely moves cross-platform (Phase 3+), promoting
        // the UI deps to commonMain has to be done together with reconciling the
        // BOM — it is not a free move.
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.runtime)
        }

        // iosMain — the Compose Multiplatform UI, iOS-only for now.
        //
        // These use the `compose.*` ACCESSORS from the JetBrains plugin rather
        // than explicit coordinates, on purpose, even though 1.10.3 marks them
        // deprecated ("Specify dependency directly" — four warnings at configure
        // time, expected, not a defect). The accessors know pairings that are
        // NOT derivable from the CMP version: `compose.material3` at CMP 1.10.3
        // resolves to org.jetbrains.compose.material3:material3:**1.9.0**, since
        // material3 versions on its own line. Hand-pinning that to 1.10.3 asks
        // for an artifact that does not exist. Revisit only when the deprecated
        // accessors are actually removed, and re-derive every pairing from a
        // real `:shared:dependencies` report at that time.
        iosMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // compose.ui carries ComposeUIViewController, the UIKit bridge the
            // iOS executable hosts its Compose content in.
            implementation(compose.ui)
        }
    }
}
