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
 * The dev update notifier (Step V). On foreground (rate-limited) it fetches the
 * rolling prerelease's `version.json`, compares versionCode against the running
 * build, and drives:
 *  - [pendingDialog] — a ONE-per-version prompt (Go to GitHub / Remind me later
 *    / Ignore this version), and
 *  - [available] — the persistent "Update available" settings badge.
 *
 * A polite client: cold-start once + at most once per 6h; any network failure is
 * a silent skip (never a visible error). Development-phase only.
 */
class UpdateChecker(
    private val prefs: UpdatePrefs,
    private val currentVersionCode: Int,
    private val client: OkHttpClient,
    private val json: Json,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            at.bettertrack.app.btBackgroundExceptionHandler("UpdateChecker"),
    ),
) {
    private var checkedThisProcess = false
    private var remindedThisSession = false

    private val _available = MutableStateFlow(seededAvailability())
    /** Non-null when a newer build exists (badge). Seeded from cache for offline. */
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _pendingDialog = MutableStateFlow<AvailableUpdate?>(null)
    /** Non-null when the one-per-version dialog should be shown right now. */
    val pendingDialog: StateFlow<AvailableUpdate?> = _pendingDialog.asStateFlow()

    /** "Automatic update checks" toggle (About) — observed by the settings UI. */
    val autoCheckEnabled: StateFlow<Boolean> = prefs.autoCheckEnabled

    private val _manualCheck = MutableStateFlow<ManualUpdateCheck>(ManualUpdateCheck.Idle)
    /** State of the About screen's "Check for updates" button. See [checkNow]. */
    val manualCheck: StateFlow<ManualUpdateCheck> = _manualCheck.asStateFlow()

    /** Called from the process foreground observer (also fires the cold-start check). */
    fun onForeground() {
        // Play builds (Task B1) have self-update compiled off — never check.
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        val cold = !checkedThisProcess
        checkedThisProcess = true
        if (!UpdateCheckLogic.shouldCheckNow(prefs.autoCheckEnabledNow(), nowMs(), prefs.lastCheckMs, cold)) return
        scope.launch { runCheck() }
    }

    /**
     * About-screen toggle. Turning OFF stops all checks and clears any pending
     * prompt; turning ON re-checks immediately (even mid-process) so the dialog
     * can return without waiting for the next cold start.
     */
    fun setAutoCheckEnabled(enabled: Boolean) {
        if (!BuildConfig.SELF_UPDATE_ENABLED) return
        prefs.setAutoCheckEnabled(enabled)
        if (enabled) {
            scope.launch { runCheck() }
        } else {
            _pendingDialog.value = null
        }
    }

    /**
     * "Check for updates" (About → this build). Runs the fetch RIGHT NOW,
     * bypassing every rate limit the automatic path honours — the cold-start
     * once-per-process flag and the 6h interval both exist to keep the app a
     * polite client on its OWN schedule, and neither applies to a check the user
     * asked for. The "automatic update checks" opt-out is likewise not consulted:
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
        // now asked GitHub, so a later foreground must fall through to the interval.
        checkedThisProcess = true
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
            // Up to date (or we just updated past the cached one): clear stale state.
            _available.value = null
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
                remindedThisSession = remindedThisSession || nowMs() < prefs.remindAfterMs,
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

    /** "Ignore this version" — never prompt again for this exact build. */
    fun ignorePending() {
        _pendingDialog.value?.let { prefs.ignoredVersionCode = it.versionCode }
        _pendingDialog.value = null
    }

    /** Dialog dismissed via its action buttons / scrim without a lasting choice. */
    fun dismissDialog() {
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
                .url(VERSION_JSON_URL)
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
