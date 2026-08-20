// ── :webApp — the browser application (web port, Phase W0) ─────────────────
// A Kotlin/Wasm executable that renders Compose Multiplatform onto a canvas and
// computes every number it shows with :shared's audited domain engine. It is the
// exact web counterpart of :iosApp — a thin host, no logic of its own beyond
// presentation — and the third consumer of :shared, after :app and :iosApp.
//
// Deliberately NOT an Android/JVM module: it applies no AGP plugin at all, so it
// cannot pull anything into :app's graph even by accident.
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Compose Multiplatform: the runtime/library half (the `compose.*` accessors)…
    alias(libs.plugins.compose.multiplatform)
    // …and the Kotlin COMPILER half. Both are required, exactly as on :shared.
    alias(libs.plugins.compose.compiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Pin the bundle name so index.html can reference it literally
            // instead of depending on how KGP derives a module name from the
            // project path.
            commonWebpackConfig {
                outputFileName = "webApp.js"
            }
        }
        // Without this the module compiles to a klib and there is no bundle;
        // `wasmJsBrowserDistribution` needs an executable binary to bundle.
        binaries.executable()
    }

    sourceSets {
        // Declared on commonMain rather than wasmJsMain since W1: the
        // compose-resources plugin generates its `Res` accessors into commonMain
        // (from `src/commonMain/composeResources/`), so the resources runtime has
        // to be visible there or the generated file does not compile. :webApp has
        // exactly one target, so commonMain and wasmJsMain resolve identically —
        // the hand-written sources stay in wasmJsMain, where the `js(...)` interop
        // they need is legal.
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            // The host's own chrome uses M3 `Text` so it inherits BtMaterialTypography
            // rather than re-specifying a style per label. :shared pulls material3 in
            // too, but transitively through an `implementation`, which is invisible
            // here by design.
            implementation(compose.material3)
            // compose.ui carries ComposeViewport — the browser counterpart of the
            // ComposeUIViewController bridge :iosApp hosts its content in — plus
            // the skiko-backed `Font(identity, bytes, …)` the embedded typeface is
            // built with.
            implementation(compose.ui)
            // Web port, W1 — the EMBEDDED ASSETS: the Roboto variable font and the
            // BT glyph. Deliberately declared HERE and not on :shared: Compose's
            // resource runtime is a real Android artifact on the android target,
            // and :app must not gain one for a font only the browser needs. When
            // W2 migrates 2984 string keys, compose-resources moves to :shared and
            // this line goes with it.
            implementation(compose.components.resources)
            // The startup sequence (fonts + glyph decoded before the first frame)
            // needs a coroutine to await a suspend read in. Already in the bundle
            // via :shared; naming it here only puts it on the compile classpath.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The generated `Res` class. Pinned explicitly so the accessors have a stable,
// obvious address instead of one derived from the project's group coordinate.
compose.resources {
    packageOfResClass = "at.bettertrack.web.resources"
    generateResClass = always
}
