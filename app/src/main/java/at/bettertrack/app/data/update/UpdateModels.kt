package at.bettertrack.app.data.update

import kotlinx.serialization.Serializable

/**
 * Dev update notifier (Step V). The CI publishes a rolling prerelease
 * `latest-debug` carrying a `version.json` asset — this app parses it, compares
 * the versionCode against its own [android.os.BuildConfig], and surfaces a
 * "new build available" prompt. Development-phase only; the whole feature is a
 * no-op once real store distribution replaces it.
 */

/** The `version.json` CI attaches to the rolling prerelease. */
@Serializable
data class ReleaseManifestDto(
    val versionCode: Int,
    val versionName: String,
    val apk: String? = null,
)

/** A newer build the app has detected (drives the dialog + settings badge). */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
)
