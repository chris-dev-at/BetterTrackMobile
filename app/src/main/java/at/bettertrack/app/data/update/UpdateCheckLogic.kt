package at.bettertrack.app.data.update

/**
 * Pure decision logic for the dev update notifier (Step V) — no Android, no IO,
 * fully unit-tested. Keeps the "is it newer / should I check / should I nag"
 * rules in one place so they can be reasoned about and regression-tested.
 */
object UpdateCheckLogic {

    /**
     * Anti-hammer debounce for FOREGROUND network checks (owner bug 2026-08-08).
     *
     * This used to be a six-hour cadence, and that was the bug: Android keeps a
     * process alive for days, so "reopen the app" is almost always a WARM
     * foreground, and a warm foreground inside the six-hour window did nothing at
     * all — no fetch, and therefore (because the dialog only ever appeared as a
     * side effect of a fetch) no prompt. The owner's model is the spec: if a newer
     * release exists, opening the app says so, every time, unless he snoozed or
     * ignored it.
     *
     * So the window shrinks to the only job it still has — stopping a burst of
     * app-switching from turning into a burst of requests. 15 minutes caps the
     * automatic path at 4 requests/hour; GitHub's unauthenticated budget is 60
     * requests/hour/IP (and this URL is a release-asset download, which does not
     * even draw on the REST quota), so the polite-client promise survives with two
     * orders of magnitude to spare.
     *
     * Cold start ignores this window entirely — see [shouldCheckNow].
     */
    const val FOREGROUND_DEBOUNCE_MS: Long = 15L * 60 * 1000

    /** A build is newer strictly by versionCode (run_number is monotonic). */
    fun isNewer(currentVersionCode: Int, latestVersionCode: Int): Boolean =
        latestVersionCode > currentVersionCode

    /**
     * Fetch the manifest on cold start (once per process) OR when the last
     * attempt is older than [debounceMs] — but only while the user keeps
     * "automatic update checks" ON ([autoCheckEnabled]).
     *
     * Note what this function is NOT: it is not the dialog gate. Whether the
     * prompt appears is decided by [shouldShowDialog] against what the app
     * already KNOWS (the cached release), so a foreground that skips the network
     * here still shows a pending update. Tying the two together is what made a
     * warm reopen silent.
     */
    fun shouldCheckNow(
        autoCheckEnabled: Boolean,
        nowMs: Long,
        lastCheckMs: Long,
        coldStart: Boolean,
        debounceMs: Long = FOREGROUND_DEBOUNCE_MS,
    ): Boolean = autoCheckEnabled && (coldStart || (nowMs - lastCheckMs) >= debounceMs)

    /**
     * Show the dialog when the build is newer, has not been permanently ignored
     * for that exact version, and is not currently silenced ([snoozed]).
     *
     * [snoozed] is every "be quiet for now" reason folded into one boolean by
     * [UpdateChecker]: the persisted 24h "remind me later" deadline, the same
     * choice made moments ago in this session, and the short quiet window a
     * hand-off to GitHub/the installer buys. What it deliberately no longer
     * contains is "this process has already shown the dialog once" — that
     * suppression outlived the 24h snooze by days, because the process does.
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
        snoozed: Boolean,
        manual: Boolean = false,
    ): Boolean =
        isNewer(currentVersionCode, latestVersionCode) &&
            (manual || (latestVersionCode != ignoredVersionCode && !snoozed))

    /**
     * The settings badge is shown whenever a newer build exists — even if the
     * dialog was ignored/snoozed, so the owner can still reach it deliberately.
     */
    fun shouldShowBadge(currentVersionCode: Int, latestVersionCode: Int): Boolean =
        isNewer(currentVersionCode, latestVersionCode)
}
