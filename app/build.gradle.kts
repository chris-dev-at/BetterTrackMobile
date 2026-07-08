plugins {
    alias(libs.plugins.android.application)
    // AGP 9 has built-in Kotlin (no kotlin-android plugin needed); only the
    // Kotlin compiler plugins (Compose, serialization) are applied on top.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    // KSP runs the Room annotation processor (Step 5). Applied after the
    // Android plugin so it picks up AGP 9's built-in Kotlin compilation.
    alias(libs.plugins.ksp)
    // Step 16 — Firebase Cloud Messaging: the google-services plugin reads the
    // already-placed app/google-services.json (project bettertrackapp-c6996) and
    // generates the Firebase config resources the FCM SDK needs at runtime.
    alias(libs.plugins.google.services)
}

android {
    namespace = "at.bettertrack.app"
    // compileSdk 37: androidx.core 1.19 / androidx.lifecycle 2.11 (latest stable)
    // require compiling against API 37. targetSdk stays 36 (runtime behavior
    // unchanged); minSdk stays 28 per project contract.
    compileSdk {
        version = release(37)
    }

    // ── BuildConfig origins & OAuth client (§4) ─────────────────────────────
    // Debug points at the local dev stack (10.0.2.2 == host loopback from an
    // emulator) but every value is overridable WITHOUT code edits via optional
    // Gradle properties, because the dev stack is often not running and a debug
    // build may need to be pointed at production (gradle.properties currently
    // points debug at production for Step-4 testing):
    //   ./gradlew … -PbtApiOrigin=https://api.bettertrack.at -PbtWebOrigin=https://web.bettertrack.at
    // Reading via `providers` keeps the configuration cache valid. Declared here
    // (before defaultConfig) so defaultConfig + buildTypes can both use them.
    val apiOriginDebug = providers.gradleProperty("btApiOrigin").getOrElse("http://10.0.2.2:3000")
    val webOriginDebug = providers.gradleProperty("btWebOrigin").getOrElse("http://10.0.2.2:8090")
    // OAUTH_CLIENT_ID: the production-registered first-party PUBLIC client id (§4).
    // A public identifier (not a secret), so it is baked in as the default for
    // BOTH build types; still overridable via -PbtOauthClientId=btc_… .
    val oauthClientId = providers.gradleProperty("btOauthClientId").getOrElse("btc_IbT1mzw_7kBiPHPkGfaE0Q")
    // OAuth redirect URI — must match the client registration EXACTLY (§4). The
    // scheme is also declared as a manifest placeholder for the deep-link filter.
    val oauthRedirectScheme = "bettertrack"
    val oauthRedirectUri = "$oauthRedirectScheme://oauth/callback"

    // CI-aware versioning (Step V, dev update notifier): CI injects
    // -PbtVersionCode=<github.run_number> and -PbtVersionName=0.<run_number> so
    // each rolling build is strictly newer; local Android Studio builds keep the
    // dev defaults (versionCode 1 / "dev") so the notifier fires against CI.
    val btVersionCode = providers.gradleProperty("btVersionCode").map { it.toInt() }.getOrElse(1)
    val btVersionName = providers.gradleProperty("btVersionName").getOrElse("dev")

    defaultConfig {
        applicationId = "at.bettertrack.app"
        minSdk = 28
        targetSdk = 36
        versionCode = btVersionCode
        versionName = btVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Deep-link scheme for the OAuth callback intent-filter (see manifest).
        manifestPlaceholders["oauthRedirectScheme"] = oauthRedirectScheme
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_ORIGIN", "\"$apiOriginDebug\"")
            buildConfigField("String", "WEB_ORIGIN", "\"$webOriginDebug\"")
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
            buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthRedirectUri\"")
        }
        release {
            // TODO(step 19): enable R8 / resource shrinking for the release build.
            optimization {
                enable = false
            }
            buildConfigField("String", "API_ORIGIN", "\"https://api.bettertrack.at\"")
            buildConfigField("String", "WEB_ORIGIN", "\"https://web.bettertrack.at\"")
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
            buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthRedirectUri\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Step 4 — login (Custom Tabs) + API client (Retrofit/OkHttp/kotlinx) +
    // Keystore-backed encrypted token storage.
    implementation(libs.androidx.browser)
    implementation(libs.androidx.security.crypto)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    // Logging interceptor referenced from shared network code but only installed
    // when BuildConfig.DEBUG, so it must be a full implementation dependency.
    implementation(libs.okhttp.logging.interceptor)

    // Step 16 — Firebase Cloud Messaging (push client). BoM keeps the FCM
    // artifact version consistent; only the client is wired (register/send are
    // platform-gated — no device-token endpoint yet, see docs/TODO.md).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // Step 5 — local database (Room) & sync engine core (WorkManager).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
