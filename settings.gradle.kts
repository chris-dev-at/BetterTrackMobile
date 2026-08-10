pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BetterTrack_App"
include(":app")
// KMP/iOS port, Phase 1 — the multiplatform module :app now depends on. It
// holds pure-Kotlin domain code (shared verbatim with iOS) plus the Compose
// Multiplatform UI the iOS executable renders. See docs/KMP_PLAN.md.
include(":shared")
// The iOS application binary: a Kotlin/Native executable that hosts :shared's
// Compose UI. Deliberately NOT an Xcode project and NOT CocoaPods — see
// iosApp/build.gradle.kts and tools/ios/build_ios_app.sh.
include(":iosApp")
 