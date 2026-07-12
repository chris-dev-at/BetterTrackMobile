package at.bettertrack.app.data.update

/**
 * Pure decision logic for the dev update notifier (Step V) — no Android, no IO,
 * fully unit-tested. Keeps the "is it newer / should I check / should I nag"
 * rules in one place so they can be reasoned about and regression-tested.
 */
object UpdateCheckLogic {

    /** Default network-check cadence outside of cold start (~6h, polite client). */
    const val CHECK_INTERVAL_MS: Long = 6L * 60 * 60 * 1000

    /** A build is newer strictly by versionCode (run_number is monotonic). */
    fun isNewer(currentVersionCode: Int, latestVersionCode: Int): Boolean =
        latestVersionCode > currentVersionCode

    /**
     * Fetch the manifest on cold start (once per process) OR when the last
     * successful check is older than [intervalMs] — but only while the user keeps
     * "automatic update checks" ON ([autoCheckEnabled]). Everything else is
     * skipped so the app stays a polite API client and honours the opt-out.
     */
    fun shouldCheckNow(
        autoCheckEnabled: Boolean,
        nowMs: Long,
        lastCheckMs: Long,
        coldStart: Boolean,
        intervalMs: Long = CHECK_INTERVAL_MS,
    ): Boolean = autoCheckEnabled && (coldStart || (nowMs - lastCheckMs) >= intervalMs)

    /**
     * Show the ONE-per-version dialog only when the build is newer, has not been
     * permanently ignored for that exact version, and the user hasn't already
     * said "remind me later" this process (that suppression resets next cold
     * start).
     */
    fun shouldShowDialog(
        currentVersionCode: Int,
        latestVersionCode: Int,
        ignoredVersionCode: Int,
        remindedThisSession: Boolean,
    ): Boolean =
        isNewer(currentVersionCode, latestVersionCode) &&
            latestVersionCode != ignoredVersionCode &&
            !remindedThisSession

    /**
     * The settings badge is shown whenever a newer build exists — even if the
     * dialog was ignored/snoozed, so the owner can still reach it deliberately.
     */
    fun shouldShowBadge(currentVersionCode: Int, latestVersionCode: Int): Boolean =
        isNewer(currentVersionCode, latestVersionCode)
}
