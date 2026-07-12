package at.bettertrack.app.data.update

import java.io.File

/**
 * Pure, Android-free helpers for the in-app "Download & Install" flow (owner ask
 * 2026-07-12) — download-progress math, the APK cache layout + startup sweep, and
 * the PackageInstaller status → outcome mapping. Kept here so the IO/Android glue
 * in [UpdateInstaller] stays thin and the decisions are unit-tested.
 */

/** Maps streamed bytes to a determinate percent, or null (indeterminate). */
object DownloadProgress {
    /**
     * Percent 0..100 of [bytesRead] against [totalBytes], or null when the total
     * is unknown/invalid (no Content-Length) so the UI shows an indeterminate bar.
     */
    fun percent(bytesRead: Long, totalBytes: Long): Int? =
        if (totalBytes <= 0L) null else ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/** The terminal (or hand-off) outcome of a PackageInstaller session. */
enum class InstallOutcome { PENDING_USER_ACTION, SUCCESS, FAILURE }

/**
 * Maps a PackageInstaller session `EXTRA_STATUS` int to an [InstallOutcome].
 * The constants mirror `PackageInstaller.STATUS_*` (kept as raw ints so this
 * stays a pure JVM unit — the framework class returns default 0s under the
 * `isReturnDefaultValues` test stub). Anything that is not an explicit
 * pending-user-action / success is treated as a failure (covers FAILURE,
 * FAILURE_ABORTED when the user cancels the sheet, BLOCKED, CONFLICT, …).
 */
object UpdateInstallStatus {
    const val PENDING_USER_ACTION = -1 // PackageInstaller.STATUS_PENDING_USER_ACTION
    const val SUCCESS = 0 // PackageInstaller.STATUS_SUCCESS

    fun outcome(status: Int): InstallOutcome = when (status) {
        PENDING_USER_ACTION -> InstallOutcome.PENDING_USER_ACTION
        SUCCESS -> InstallOutcome.SUCCESS
        else -> InstallOutcome.FAILURE
    }
}

/**
 * The app-private APK cache layout. Everything lives under `cacheDir/updates/`
 * so a single [sweep] on app start removes BOTH the APK left behind by a
 * successful install (the install kills our process before we can) AND any
 * stale partial download from a crash/kill mid-stream.
 */
object UpdateFiles {
    const val DIR = "updates"

    fun updatesDir(cacheDir: File): File = File(cacheDir, DIR)

    /** Deterministic per-version filename so a retry overwrites, never piles up. */
    fun apkFile(cacheDir: File, versionCode: Int): File =
        File(updatesDir(cacheDir), "BetterTrackApp-$versionCode.apk")

    /** Delete the whole updates dir; returns the number of files removed. */
    fun sweep(cacheDir: File): Int {
        val dir = updatesDir(cacheDir)
        if (!dir.exists()) return 0
        val count = dir.walkTopDown().count { it.isFile }
        dir.deleteRecursively()
        return count
    }
}
