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

    // Stable dev signing (owner ask 2026-07-10): sign DEBUG builds with ONE
    // stable key everywhere so Android's in-place "Update" works between any
    // two builds (CI↔CI, local↔CI). Runner-generated debug keystores differ
    // per CI run, which forced uninstall+reinstall on every update.
    // Source of the key, in priority order (never committed — repo is public):
    //   1. env BT_KEYSTORE / BT_KEYSTORE_ALIAS / BT_KEYSTORE_PASS (CI decodes
    //      it from the BT_SIGNING_KEYSTORE_B64 secret into RUNNER_TEMP), or
    //   2. ~/.bettertrack/bt-dev-signing.keystore + signing.env on this Mac
    //      (local Android Studio / builder installs share the CI identity), or
    //   3. fallback: the default debug keystore (previous behavior).
    val btSigning: Triple<File, String, String>? = run {
        val envKs = System.getenv("BT_KEYSTORE")
        if (envKs != null && file(envKs).exists()) {
            val alias = System.getenv("BT_KEYSTORE_ALIAS") ?: "btdev"
            val pass = System.getenv("BT_KEYSTORE_PASS") ?: return@run null
            Triple(file(envKs), alias, pass)
        } else {
            val home = System.getProperty("user.home")
            val ks = File(home, ".bettertrack/bt-dev-signing.keystore")
            val envFile = File(home, ".bettertrack/signing.env")
            if (ks.exists() && envFile.exists()) {
                val kv = envFile.readLines().mapNotNull {
                    val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
                }.toMap()
                val pass = kv["BT_KEYSTORE_PASS"]
                if (pass != null) Triple(ks, kv["BT_KEYSTORE_ALIAS"] ?: "btdev", pass) else null
            } else null
        }
    }
    if (btSigning != null) {
        signingConfigs {
            create("btDev") {
                storeFile = btSigning.first
                storePassword = btSigning.third
                keyAlias = btSigning.second
                keyPassword = btSigning.third
            }
        }
    }

    // ── Play upload key (Step 20, Task B2) ──────────────────────────────────
    // The REAL Play upload keystore + passwords live ONLY in ~/.bettertrack/
    // (repo is public — never committed). When present, they wire the
    // `btPlayUpload` signingConfig used by the PLAY RELEASE variant only (see the
    // `play` flavor below). Absent (e.g. CI, a fresh clone) → the play flavor
    // falls back to the debug key so the build still succeeds; the shippable
    // upload-signed .aab is produced only on the machine that holds the key.
    val playUploadKeystore = File(System.getProperty("user.home"), ".bettertrack/bt-play-upload.keystore")
    val playUploadEnvFile = File(System.getProperty("user.home"), ".bettertrack/play-signing.env")
    val playUploadAvailable = playUploadKeystore.exists() && playUploadEnvFile.exists()
    if (playUploadAvailable) {
        val kv = playUploadEnvFile.readLines().mapNotNull {
            val i = it.indexOf('='); if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.toMap()
        val storePass = kv["BT_PLAY_STORE_PASS"]
        if (storePass != null) {
            signingConfigs {
                create("btPlayUpload") {
                    storeFile = playUploadKeystore
                    storePassword = storePass
                    keyAlias = kv["BT_PLAY_KEY_ALIAS"] ?: "btupload"
                    keyPassword = kv["BT_PLAY_KEY_PASS"] ?: storePass
                    // Play App Signing accepts either scheme; v2 is the modern default.
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }
    }

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
            // Stable dev key when available (see btSigning above) → in-place updates.
            if (btSigning != null) signingConfig = signingConfigs.getByName("btDev")
            buildConfigField("String", "API_ORIGIN", "\"$apiOriginDebug\"")
            buildConfigField("String", "WEB_ORIGIN", "\"$webOriginDebug\"")
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
            buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthRedirectUri\"")
        }
        release {
            // Step 19: R8 code shrink/optimize + resource shrinking. Keep rules
            // live in proguard-rules.pro (kept minimal — libraries ship their own
            // consumer rules; we only protect our kotlinx-serialization models +
            // the sync worker). proguard-android-optimize.txt is the optimizing
            // default variant.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release SIGNING is per-flavor (Step 20): a build-type signingConfig
            // would override the flavor's, so we leave it unset here and let each
            // flavor pick its key — `github` keeps the stable BT dev key / debug
            // fallback (unchanged from before), `play` uses the real upload key.
            buildConfigField("String", "API_ORIGIN", "\"https://api.bettertrack.at\"")
            buildConfigField("String", "WEB_ORIGIN", "\"https://web.bettertrack.at\"")
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
            buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthRedirectUri\"")
        }
    }

    // ── Distribution flavors (Step 20, Task B1) ─────────────────────────────
    // Same applicationId for both (no suffix — Firebase/google-services.json and
    // the OAuth client are registered for `at.bettertrack.app`).
    //   • github — today's behavior: the dev update checker + in-app Download &
    //     Install (REQUEST_INSTALL_PACKAGES lives in app/src/github/AndroidManifest).
    //   • play   — NO self-update anything (Play's Device-and-Network-Abuse policy
    //     forbids it): SELF_UPDATE_ENABLED=false hard-gates the checker + all update
    //     UI, and REQUEST_INSTALL_PACKAGES is absent from its manifest.
    // `github` sorts first alphabetically ⇒ it is Android Studio's default variant.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
            // Unchanged github signing: stable BT dev key when present, else debug.
            signingConfig = if (btSigning != null) {
                signingConfigs.getByName("btDev")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
            // Play RELEASE is signed with the real upload key when it is available
            // on this machine (~/.bettertrack); otherwise the build still succeeds
            // with the debug key (no shippable .aab is produced without the key).
            signingConfig = if (playUploadAvailable && signingConfigs.findByName("btPlayUpload") != null) {
                signingConfigs.getByName("btPlayUpload")
            } else {
                signingConfigs.getByName("debug")
            }
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
    testOptions {
        // JVM unit tests exercise code that calls android.util.Log (e.g. the
        // sync executor's presence-only diagnostics); return defaults instead of
        // throwing "not mocked" so pure-logic tests can run on the JVM.
        unitTests.isReturnDefaultValues = true
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

    // Step 17 — app lock: BiometricPrompt (fingerprint/face) + PIN. `fragment`
    // is the FragmentActivity host BiometricPrompt requires; pinned modern so it
    // aligns with lifecycle 2.11 instead of biometric 1.1.0's transitive 1.2.5.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)

    // Step 18 — settings & account management: per-app language switch
    // (AppCompatDelegate locales + autoStoreLocales service) and the offline
    // ZXing QR encoder for 2FA authenticator (TOTP) enrollment.
    implementation(libs.androidx.appcompat)
    implementation(libs.zxing.core)

    // Step 5 — local database (Room) & sync engine core (WorkManager).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
