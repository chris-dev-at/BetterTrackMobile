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
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            // compose.ui carries ComposeViewport — the browser counterpart of the
            // ComposeUIViewController bridge :iosApp hosts its content in.
            implementation(compose.ui)
        }
    }
}
