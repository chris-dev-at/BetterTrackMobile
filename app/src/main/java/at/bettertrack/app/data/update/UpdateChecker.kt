package at.bettertrack.app.data.update

import android.util.Log
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtMessage
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The dev update notifier (Step V). On foreground it offers what it already knows
 * and (debounced) refetches the rolling prerelease's `version.json`, comparing
 * versionCode against the running build to drive:
 *  - [pendingDialog] — the update prompt (Download & Install / Go to GitHub /
 *    Remind me later / Ignore this version), and
 *  - [available] — the persistent "Update available" settings badge.
 *
 * **The rule (owner, 2026-08-08): if a newer release exists, opening the app says
 * so — every time, cold or warm — unless that exact version was ignored or the
 * prompt is inside a snooze.** Two things used to break it, and both were
 * invisible because the app looked busy and correct while doing nothing:
 *
 *  1. the network check ran at most once per six hours, and Android keeps the
 *     process alive for days, so almost every reopen was a warm foreground inside
 *     that window; and
 *  2. the dialog was only ever a SIDE EFFECT of a successful fetch, so a skipped
 *     or failed check meant no prompt even while the badge — fed by the same
 *     cache — sat there insisting an update existed.
 *
 * Now: [onForeground] starts a new session on every entry (clearing the session
 * suppression), offers the cached release immediately without waiting for or
 * needing the network, and only then decides whether to refetch.
 *
 * Still a polite client: cold-start once + at most one attempt per
 * [UpdateCheckLogic.FOREGROUND_DEBOUNCE_MS]; any network failure is a silent skip
 * on the automatic path (never a visible error). Development-phase only.
 */
class UpdateChecker(
    private val prefs: UpdateStore,
    private val currentVersionCode: Int,
    private val client: OkHttpClient,
    private val json: Json,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            at.bettertrack.app.btBackgroundExceptionHandler("UpdateChecker"),
    ),
    private val versionJsonUrl: String = VERSION_JSON_URL,
) {
    private var checkedThisProcess = false

    /**
     * "Remind me later" was tapped in THIS foreground session. Reset by every
     * [onForeground] — the persisted [UpdateStore.remindAfterMs] is what actually
     * enforces the 24 hours, and this flag exists only so the choice takes effect
     * before the clock is consulted again. As a process-lifetime flag it was a
     * bug: it outlived the snooze it was mirroring by however many days Android
     * kept the process around.
     */
    private var remindedThisSession = false

    /**
     * When the last automatic fetch was STARTED (in-memory).
     *
     * The debounce anchors on `max(this, lastCheckMs)` because
     * [UpdateStore.lastCheckMs] only advances on success: offline, it never moves,
     * and without this an airplane-mode user app-switching in a loop would fire a
     * doomed request on every single foreground.
     */
    private var lastAttemptMs = 0L

    /**
     * A short quiet window opened by [dismissDialog] — i.e. by the user leaving
     * for GitHub with the offer answered.
     *
     * The spec wants the prompt back on the next app-open, and returning from the
     * browser IS an app-open; without this, "Go to GitHub" would re-prompt the
     * instant the user came back, and again after the next tab switch. In-memory
     * on purpose: it is about one hand-off, not a preference, so a cold start
     * clears it and the prompt returns as the spec says it should.
     */
    private var dialogQuietUntilMs = 0L

    private val _available = MutableStateFlow(seededAvailability())
    /** Non-null when a newer build exists (badge). Seeded from cache for offline. */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _pendingDialog = MutableStateFlow<AvailableUpdate?>(null)
    /** Non-null when the update dialog should be shown right now. */
    val pendingDialog: StateFlow<AvailableUpdate?> = _pendingDialog.asStateFlow()

    /** "Automatic update checks" toggle (About) — observed by the settings UI. */
    val autoCheckEnabled: StateFlow<Boolean> = prefs.autoCheckEnabled

    private val _manualCheck = MutableStateFlow<ManualUpdateCheck>(ManualUpdateCheck.Idle)
    /** State of the About screen's "Check for updates" button. See [checkNow]. */
    val manualCheck: StateFlow<ManualUpdateCheck> = _manualCheck.asStateFlow()

    /**
     * A foreground session begins: the app just became visible
     * (ProcessLifecycleOwner `ON_START` — cold launch, return from recents, back
     * from the browser, unlock). This is the moment the owner means by "opening
     * the app", so it is the moment the prompt is owed.
     *
     * Three steps, in this order and independent of each other:
     *  1. a new session clears the in-session "remind me later" flag (the
     *     persisted 24h deadline still applies — see [remindedThisSession]);
     *  2. anything already KNOWN to be newer is offered right now, from cache,
     *     with no network call and no waiting for one; then
     *  3. the manifest is refetched if the debounce allows, which can only ever
     *     ADD news (a still-newer build), never withhold it.
     */
    fun onForeground() {
        // Play builds (Task B1) have self-update compiled off — never check.
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        val cold = !checkedThisProcess
        checkedThisProcess = true
        remindedThisSession = false
        offerKnownUpdate()
        val anchor = maxOf(prefs.lastCheckMs, lastAttemptMs)
        if (!UpdateCheckLogic.shouldCheckNow(prefs.autoCheckEnabledNow(), nowMs(), anchor, cold)) return
        lastAttemptMs = nowMs()
        scope.launch { runCheck() }
    }

    /**
     * Offer the newest build the app already knows about, straight from cache.
     *
     * This is the half of the fix that does not involve the network at all. The
     * cache is what the settings badge has always rendered from, so declining to
     * render the DIALOG from it meant the app could show "Update available" in
     * settings while the prompt it exists to raise stayed silent. Eligibility is
     * the same [UpdateCheckLogic.shouldShowDialog] the fetched path uses, so
     * ignore and snooze bind identically here.
     *
     * Gated on the "automatic update checks" toggle: OFF means the app makes no
     * unprompted noise about updates, and a cached offer is exactly that noise.
     */
    private fun offerKnownUpdate() {
        if (!prefs.autoCheckEnabledNow()) return
        val known = seededAvailability() ?: return
        _available.value = known
        if (
            UpdateCheckLogic.shouldShowDialog(
                currentVersionCode = currentVersionCode,
                latestVersionCode = known.versionCode,
                ignoredVersionCode = prefs.ignoredVersionCode,
                snoozed = autoDialogSuppressed(),
            )
        ) {
            _pendingDialog.value = known
        }
    }

    /**
     * Every reason the AUTOMATIC path currently owes silence, in one place so the
     * cached offer and the fetched one cannot drift: the choice made this session,
     * the persisted 24h deadline behind it, and the hand-off quiet window. A
     * manual check overrides all three by construction (see
     * [UpdateCheckLogic.shouldShowDialog]).
     */
    private fun autoDialogSuppressed(): Boolean =
        remindedThisSession || nowMs() < prefs.remindAfterMs || nowMs() < dialogQuietUntilMs

    /**
     * About-screen toggle. Turning OFF stops all checks and clears any pending
     * prompt; turning ON re-checks immediately (even mid-process) so the dialog
     * can return without waiting for the next cold start.
     */
    fun setAutoCheckEnabled(enabled: Boolean) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        prefs.setAutoCheckEnabled(enabled)
        if (enabled) {
            // Same order as a foreground entry: say what we already know first,
            // then go ask. Switching the toggle back on is a question, and the
            // answer should not depend on the network being up.
            offerKnownUpdate()
            lastAttemptMs = nowMs()
            scope.launch { runCheck() }
        } else {
            _pendingDialog.value = null
        }
    }

    /**
     * "Check for updates" (About → this build). Runs the fetch RIGHT NOW,
     * bypassing every rate limit the automatic path honours — the cold-start
     * once-per-process flag and the foreground debounce both exist to keep the
     * app a polite client on its OWN schedule, and neither applies to a check the
     * user asked for. The "automatic update checks" opt-out is likewise not consulted:
     * it turns off checks the app makes by itself, not the button that exists to
     * replace them.
     *
     * An ignored version IS re-offered here — see [UpdateCheckLogic.shouldShowDialog]
     * for why, and for the guarantee that this never clears the stored ignore, so
     * automatic checks stay as quiet after a manual look as they were before it.
     *
     * The one gate kept: [BuildConfig.SELF_UPDATE_ENABLED]. A Play build has no
     * self-update at all, and the button that calls this is compiled out there.
     */
    fun checkNow() {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        if (_manualCheck.value is ManualUpdateCheck.Checking) return // already in flight
        _manualCheck.value = ManualUpdateCheck.Checking
        // A manual check satisfies the cold-start obligation too: the process has
        // now asked GitHub, so a later foreground must fall through to the debounce.
        checkedThisProcess = true
        lastAttemptMs = nowMs()
        scope.launch { runCheck(manual = true) }
    }

    /**
     * Drop a finished manual result. Called when About leaves composition: the
     * up-to-date / failed line answers ONE tap and should not be waiting on the
     * screen the next time it is opened, hours later, still claiming to describe
     * the current state of the world.
     */
    fun clearManualCheck() {
        _manualCheck.value = ManualUpdateCheck.Idle
    }

    private suspend fun runCheck(manual: Boolean = false) {
        val manifest = when (val fetched = fetchManifest()) {
            is ManifestFetch.Ok -> fetched.manifest
            is ManifestFetch.Failed -> {
                // Automatic: silent skip, exactly as before — a background check
                // must never produce a visible error. Manual: the user is looking
                // at a spinner and is owed an answer.
                if (manual) _manualCheck.value = ManualUpdateCheck.Failed(fetched.message)
                return
            }
        }
        prefs.lastCheckMs = nowMs()
        if (!UpdateCheckLogic.isNewer(currentVersionCode, manifest.versionCode)) {
            // Up to date (or we just updated past the cached one): clear stale
            // state — including the CACHE, which now drives the offer and not
            // just the badge. A cached release that no longer exists (yanked,
            // or already installed) would otherwise be re-offered on every
            // foreground forever, pointing at an APK that 404s.
            _available.value = null
            _pendingDialog.value = null
            prefs.cachedLatestCode = 0
            prefs.cachedLatestName = null
            prefs.cachedLatestApk = null
            if (manual) _manualCheck.value = ManualUpdateCheck.UpToDate
            return
        }
        val update = AvailableUpdate(manifest.versionCode, manifest.versionName, manifest.apk)
        prefs.cachedLatestCode = manifest.versionCode
        prefs.cachedLatestName = manifest.versionName
        prefs.cachedLatestApk = manifest.apk
        _available.value = update
        // A newer build is answered by the dialog, so the inline manual state
        // stands down rather than competing with it.
        if (manual) _manualCheck.value = ManualUpdateCheck.Idle
        if (
            UpdateCheckLogic.shouldShowDialog(
                currentVersionCode = currentVersionCode,
                latestVersionCode = manifest.versionCode,
                ignoredVersionCode = prefs.ignoredVersionCode,
                snoozed = autoDialogSuppressed(),
                manual = manual,
            )
        ) {
            _pendingDialog.value = update
        }
    }

    /**
     * "Remind me later" — hide the dialog and stay quiet for 24 hours ACROSS
     * cold starts (persisted; the in-session flag alone made the prompt return
     * on every launch, which the owner reported as a nag).
     */
    fun remindLater() {
        remindedThisSession = true
        prefs.remindAfterMs = nowMs() + REMIND_SNOOZE_MS
        _pendingDialog.value = null
    }

    /**
     * The active "remind me later" deadline, or **null** when no snooze is in
     * effect — a READ, nothing more. [remindLater] remains the only writer.
     *
     * It exists because a silenced update prompt was invisible: the user tapped
     * "remind me later", the dialog stopped appearing for 24 hours, and About
     * had no way to say so. A quiet line under the update controls turns that
     * from "the checker seems broken" into "you asked for this, and it ends at
     * half past four".
     */
    fun snoozedUntilMs(): Long? = updateSnoozeDeadlineMs(prefs.remindAfterMs, nowMs())

    /** "Ignore this version" — never prompt again for this exact build. */
    fun ignorePending() {
        _pendingDialog.value?.let { prefs.ignoredVersionCode = it.versionCode }
        _pendingDialog.value = null
    }

    /**
     * The offer was answered by leaving for GitHub — no lasting choice recorded,
     * but the app is about to go to the background and come straight back, and
     * "opening the app re-offers the update" must not turn that round trip into a
     * loop. So the prompt stays down for [HANDOFF_QUIET_MS] and no longer: not a
     * snooze (nothing is persisted, About shows no paused line, a cold start
     * clears it), just enough silence to let one hand-off finish.
     */
    fun dismissDialog() {
        dialogQuietUntilMs = nowMs() + HANDOFF_QUIET_MS
        _pendingDialog.value = null
    }

    private fun seededAvailability(): AvailableUpdate? {
        val code = prefs.cachedLatestCode
        val name = prefs.cachedLatestName
        return if (name != null && UpdateCheckLogic.isNewer(currentVersionCode, code)) {
            AvailableUpdate(code, name, prefs.cachedLatestApk)
        } else {
            null
        }
    }

    /**
     * The outcome of one manifest fetch. It used to be `ReleaseManifestDto?`,
     * which was enough while every failure was a silent skip; the manual check
     * has to TELL the user why nothing happened, and "null" cannot be translated.
     */
    private sealed interface ManifestFetch {
        data class Ok(val manifest: ReleaseManifestDto) : ManifestFetch
        data class Failed(val message: BtMessage) : ManifestFetch
    }

    private suspend fun fetchManifest(): ManifestFetch = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("Accept", "application/json")
                .header("User-Agent", "BetterTrackApp")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "Update check declined: HTTP ${resp.code}")
                    return@withContext UNREACHABLE
                }
                val body = resp.body?.string() ?: return@withContext UNREACHABLE
                ManifestFetch.Ok(json.decodeFromString(ReleaseManifestDto.serializer(), body))
            }
        } catch (e: IOException) {
            // No route to GitHub: airplane mode, no WiFi, DNS, timeout. The one
            // failure the user can actually fix, so it gets its own sentence.
            Log.d(TAG, "Update check skipped (offline): ${e.message}")
            ManifestFetch.Failed(BtMessage(R.string.bt_err_network_error))
        } catch (e: Exception) {
            // Reached the network but the answer was unusable (malformed manifest).
            Log.d(TAG, "Update check skipped: ${e.message}")
            UNREACHABLE
        }
    }

    companion object {
        /** 24h quiet window after "remind me later". */
        const val REMIND_SNOOZE_MS = 24L * 60 * 60 * 1000

        /**
         * How long the prompt stays down after the user leaves for GitHub — long
         * enough to cover the trip (open the release page, download, come back)
         * without turning that return into a re-prompt. Deliberately the same 15
         * minutes as the network debounce: both answer "the app just did this,
         * give it a moment".
         */
        const val HANDOFF_QUIET_MS = UpdateCheckLogic.FOREGROUND_DEBOUNCE_MS

        private const val TAG = "BtUpdateChecker"
        const val REPO = "chris-dev-at/BetterTrackMobile"

        /**
         * GitHub answered but not with a usable manifest — rate limit, 5xx, or a
         * manifest this build cannot parse. One sentence covers all three because
         * the user's next move is identical for each: wait, then tap again.
         */
        private val UNREACHABLE: ManifestFetch =
            ManifestFetch.Failed(BtMessage(R.string.bt_update_check_failed))

        /** Stable asset download URL for the rolling prerelease manifest. */
        const val VERSION_JSON_URL =
            "https://github.com/$REPO/releases/download/latest-debug/version.json"

        /** The human release page, opened by "Go to GitHub" + the settings badge. */
        const val RELEASE_PAGE_URL =
            "https://github.com/$REPO/releases/tag/latest-debug"

        /**
         * The repo's full releases INDEX — every tagged release, not just the
         * rolling `latest-debug` prerelease that [RELEASE_PAGE_URL] pins. This is
         * the About screen's "GitHub releases" link, and unlike the rest of this
         * companion it is not self-update machinery: it is a public web page, so
         * it is linked from BOTH flavors (see AboutScreen).
         */
        const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases"

        /** Host + path shown as the link row's subtitle (no scheme — it is furniture). */
        const val RELEASES_PAGE_LABEL = "github.com/$REPO"

        /** Stable base for the release APK asset; GitHub 302s to a CDN (followed). */
        const val RELEASE_DOWNLOAD_BASE =
            "https://github.com/$REPO/releases/download/latest-debug/"

        /** The direct APK download URL for a given release-asset filename. */
        fun apkUrl(apkName: String): String = RELEASE_DOWNLOAD_BASE + apkName
    }
}

/**
 * The "remind me later" deadline that is still in the FUTURE, or null.
 *
 * A one-line predicate, extracted from [UpdateChecker.snoozedUntilMs] so About's
 * "reminder paused until …" line and the checker's own dialog gate cannot drift
 * apart on the boundary. Strictly `>`: a deadline that has arrived is no longer
 * a snooze, which is exactly how the dialog gate reads it
 * (`nowMs() < prefs.remindAfterMs`).
 *
 * `0L` — the stored default, meaning "never snoozed" — is in the past for every
 * real clock and therefore returns null without needing its own case.
 */
fun updateSnoozeDeadlineMs(remindAfterMs: Long, nowMs: Long): Long? =
    remindAfterMs.takeIf { it > nowMs }
