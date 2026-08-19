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
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS, since the web port (Phase W0).
    // Bundling a Kotlin/Wasm browser build makes KGP fetch a pinned Node.js (and
    // Yarn), and it registers the ivy repository for that download from INSIDE
    // the `kotlinWasmNodeJsSetup` task — project code. FAIL_ON_PROJECT_REPOS
    // rejects that outright, on the ADD rather than on the resolve, so declaring
    // an identical repository below does not satisfy it:
    //   "repository 'Distributions at https://nodejs.org/dist' was added by
    //    unknown code".
    // PREFER_SETTINGS keeps the property that actually matters — project-declared
    // repositories are IGNORED and everything resolves against the list below —
    // while downgrading the "someone added a repo" failure to a warning. The two
    // ivy repositories below are what then serves the toolchain download.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // ── Kotlin/Wasm browser toolchain (web port, Phase W0) ──────────────
        // Layouts copied from KGP's own NodeJsSetupTask / YarnSetupTask, and
        // `content { includeModule(...) }` means no other dependency in the build
        // can ever resolve against them. Deliberately NOT `download = false`
        // against the machine's Homebrew Node: the toolchain version must be a
        // property of the repo, not of whoever's laptop is building.
        ivy {
            name = "Node.js distributions"
            setUrl("https://nodejs.org/dist")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn distributions"
            setUrl("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        // Binaryen's `wasm-opt`, which the PRODUCTION browser distribution runs
        // over the module (the development one skips it). Third and last of the
        // toolchain downloads.
        ivy {
            name = "Binaryen distributions"
            setUrl("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
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
// The BROWSER application (web port, Phase W0): a Kotlin/Wasm executable that
// hosts :shared's Compose UI, served at mobile-dev.bettertrack.at/app. It is the
// third consumer of the same :shared module, alongside :app and :iosApp.
include(":webApp")
 