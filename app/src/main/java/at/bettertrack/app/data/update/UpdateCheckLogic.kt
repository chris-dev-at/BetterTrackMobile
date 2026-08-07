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
     *
     * [manual] is the About screen's "Check for updates" button. Both
     * suppressions — ignore and remind-later — exist to stop the app nagging on
     * its own schedule, and neither has anything to say about a check the user
     * just asked for by name: answering "nothing to see here" to a deliberate
     * tap would look like the button is broken. So a manual check re-offers the
     * newest build whatever the stored suppressions say. It does not CLEAR them
     * (that is [UpdateChecker.ignorePending]'s job alone) — declining the
     * re-offer leaves automatic checks exactly as quiet as they were.
     */
    fun shouldShowDialog(
        currentVersionCode: Int,
        latestVersionCode: Int,
        ignoredVersionCode: Int,
        remindedThisSession: Boolean,
        manual: Boolean = false,
    ): Boolean =
        isNewer(currentVersionCode, latestVersionCode) &&
            (manual || (latestVersionCode != ignoredVersionCode && !remindedThisSession))

    /**
     * The settings badge is shown whenever a newer build exists — even if the
     * dialog was ignored/snoozed, so the owner can still reach it deliberately.
     */
    fun shouldShowBadge(currentVersionCode: Int, latestVersionCode: Int): Boolean =
        isNewer(currentVersionCode, latestVersionCode)
}
