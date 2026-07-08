// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Step 16 — declared here (apply false), applied in :app; processes
    // app/google-services.json into the FCM Firebase config resources.
    alias(libs.plugins.google.services) apply false
}