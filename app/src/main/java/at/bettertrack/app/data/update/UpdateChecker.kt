package at.bettertrack.app.data.update

import android.util.Log
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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

    /** Called from the process foreground observer (also fires the cold-start check). */
    fun onForeground() {
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
        prefs.setAutoCheckEnabled(enabled)
        if (enabled) {
            scope.launch { runCheck() }
        } else {
            _pendingDialog.value = null
        }
    }

    private suspend fun runCheck() {
        val manifest = fetchManifest() ?: return // offline / error → silent skip
        prefs.lastCheckMs = nowMs()
        if (!UpdateCheckLogic.isNewer(currentVersionCode, manifest.versionCode)) {
            // Up to date (or we just updated past the cached one): clear stale state.
            _available.value = null
            return
        }
        val update = AvailableUpdate(manifest.versionCode, manifest.versionName, manifest.apk)
        prefs.cachedLatestCode = manifest.versionCode
        prefs.cachedLatestName = manifest.versionName
        prefs.cachedLatestApk = manifest.apk
        _available.value = update
        if (
            UpdateCheckLogic.shouldShowDialog(
                currentVersionCode = currentVersionCode,
                latestVersionCode = manifest.versionCode,
                ignoredVersionCode = prefs.ignoredVersionCode,
                remindedThisSession = remindedThisSession,
            )
        ) {
            _pendingDialog.value = update
        }
    }

    /** "Remind me later" — hide the dialog; it returns next cold start. */
    fun remindLater() {
        remindedThisSession = true
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

    private suspend fun fetchManifest(): ReleaseManifestDto? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(VERSION_JSON_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "BetterTrackApp")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                json.decodeFromString(ReleaseManifestDto.serializer(), body)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check skipped: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BtUpdateChecker"
        const val REPO = "chris-dev-at/BetterTrackMobile"

        /** Stable asset download URL for the rolling prerelease manifest. */
        const val VERSION_JSON_URL =
            "https://github.com/$REPO/releases/download/latest-debug/version.json"

        /** The human release page, opened by "Go to GitHub" + the settings badge. */
        const val RELEASE_PAGE_URL =
            "https://github.com/$REPO/releases/tag/latest-debug"

        /** Stable base for the release APK asset; GitHub 302s to a CDN (followed). */
        const val RELEASE_DOWNLOAD_BASE =
            "https://github.com/$REPO/releases/download/latest-debug/"

        /** The direct APK download URL for a given release-asset filename. */
        fun apkUrl(apkName: String): String = RELEASE_DOWNLOAD_BASE + apkName
    }
}
