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

/**
 * A newer build the app has detected (drives the dialog + settings badge).
 * [apkName] is the release asset filename from `version.json` — the in-app
 * "Download & Install" flow needs it to build the download URL; null when the
 * manifest omitted it (older CI), in which case only "Go to GitHub" is offered.
 */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkName: String? = null,
)

/**
 * The live state of the in-app "Download & Install" flow (owner ask 2026-07-12).
 * Drives the update dialog's content: offer → download progress → system-installer
 * hand-off → (on failure) retry. [Idle] means no download/install is active, so the
 * dialog shows its normal "update available" offer.
 */
sealed interface UpdateInstallState {
    /** No download/install in progress — the dialog shows the offer actions. */
    data object Idle : UpdateInstallState

    /** Streaming the APK. [percent] null = indeterminate (no Content-Length). */
    data class Downloading(val percent: Int?, val versionName: String) : UpdateInstallState

    /** APK written to the session; the system confirm sheet is being handed off. */
    data class Installing(val versionName: String) : UpdateInstallState

    /** Download/install failed or was aborted — the dialog offers Retry + GitHub. */
    data class Failed(val versionName: String) : UpdateInstallState
}
