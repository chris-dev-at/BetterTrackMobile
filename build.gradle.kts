// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // KMP/iOS port — applied by :shared, never by :app (:app must never apply a
    // Kotlin plugin: AGP 9's built-in Kotlin already owns the `kotlin`
    // extension). Declaring it here also puts kotlin-gradle-plugin 2.3.20 on the
    // build classpath, and Gradle conflict resolution raises AGP's built-in
    // 2.2.10 to it for the whole build. See gradle/libs.versions.toml `kotlin`.
    alias(libs.plugins.kotlin.multiplatform) apply false
    // KMP/iOS Phase 1 — :shared's Android target and its Compose Multiplatform
    // UI. Declared here so both land on the build classpath once; :shared
    // applies them. See the notes in gradle/libs.versions.toml [plugins].
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    // Step 16 — declared here (apply false), applied in :app; processes
    // app/google-services.json into the FCM Firebase config resources.
    alias(libs.plugins.google.services) apply false
}