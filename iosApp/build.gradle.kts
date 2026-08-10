// ── :iosApp — the iOS application binary (KMP/iOS port, Phase 1) ───────────
// A Kotlin/Native EXECUTABLE. There is no Xcode project and no CocoaPods here,
// on purpose: `binaries.executable` already emits a Mach-O that UIKit can boot,
// so the whole iOS app is `UIApplicationMain` + an AppDelegate written in
// Kotlin. tools/ios/build_ios_app.sh wraps that binary in a hand-assembled
// .app bundle, ad-hoc signs it and installs it on the simulator.
//
// This module holds ONLY the platform entry point. Every pixel comes from
// :shared, so iOS features are added as shared Kotlin, never as Swift.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // No Android target here at all — this module is iOS-only, which is why it
    // does NOT apply com.android.kotlin.multiplatform.library.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.executable {
            baseName = "BetterTrack"
            // Fully-qualified name of the `fun main()` in Main.kt. Without this
            // the linker looks for a `main` in the ROOT package and fails with
            // "Could not find 'main' in ''".
            entryPoint = "at.bettertrack.iosapp.main"
        }
    }

    sourceSets {
        iosMain.dependencies {
            // `implementation`, not `api`: nothing consumes :iosApp.
            implementation(project(":shared"))
        }
    }
}
