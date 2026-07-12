package at.bettertrack.app.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * The in-app "Download & Install" flow (owner ask 2026-07-12): streams the release
 * APK from the GitHub release (302 → CDN, followed) into `cacheDir/updates/` with
 * live percent progress, then hands the file to the platform [PackageInstaller]
 * session API. The system confirm sheet appearing is expected — no silent install.
 *
 * Cleanup is belt-and-braces: the APK is deleted on any failure/cancel here, and
 * [sweepOnStart] wipes the whole `updates/` dir on every app start (a successful
 * install kills our process before we can delete, and a kill mid-download leaves a
 * partial file — the sweep removes both).
 *
 * Pure decisions (progress math, cache layout, status mapping) live in
 * [DownloadProgress] / [UpdateFiles] / [UpdateInstallStatus]; this class is only
 * the IO + Android glue.
 */
class UpdateInstaller(
    private val appContext: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile private var current: AvailableUpdate? = null

    /** Begin (or restart) the download → install for [update]. */
    fun start(update: AvailableUpdate) {
        val apkName = update.apkName
        if (apkName.isNullOrBlank()) {
            // No asset filename (older manifest): can't download — surface a failure
            // so the dialog falls back to "Go to GitHub".
            current = update
            _state.value = UpdateInstallState.Failed(update.versionName)
            return
        }
        job?.cancel()
        current = update
        _state.value = UpdateInstallState.Downloading(percent = null, versionName = update.versionName)
        job = scope.launch { runDownload(update, apkName) }
    }

    /** Retry the current (or a given) update after a failure. */
    fun retry() {
        current?.let { start(it) }
    }

    /** User cancelled the in-app download — stop, delete the partial, go Idle. */
    fun cancel() {
        job?.cancel()
        job = null
        current?.let { UpdateFiles.apkFile(appContext.cacheDir, it.versionCode).delete() }
        _state.value = UpdateInstallState.Idle
    }

    /** Return to Idle without deleting (e.g. user chose "Go to GitHub" instead). */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = UpdateInstallState.Idle
    }

    private suspend fun runDownload(update: AvailableUpdate, apkName: String) {
        val apk = UpdateFiles.apkFile(appContext.cacheDir, update.versionCode)
        try {
            UpdateFiles.updatesDir(appContext.cacheDir).mkdirs()
            val request = Request.Builder()
                .url(UpdateChecker.apkUrl(apkName))
                .header("User-Agent", "BetterTrackApp")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty body")
                val total = body.contentLength()
                var read = 0L
                var lastPercent = -1
                apk.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(DOWNLOAD_BUFFER)
                        while (true) {
                            coroutineContext.ensureActive() // cooperative cancel
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = DownloadProgress.percent(read, total)
                            if (pct != null && pct != lastPercent) {
                                lastPercent = pct
                                _state.value = UpdateInstallState.Downloading(pct, update.versionName)
                            }
                        }
                        out.flush()
                    }
                }
            }
            install(update, apk)
        } catch (ce: CancellationException) {
            apk.delete()
            throw ce
        } catch (e: Exception) {
            Log.d(TAG, "Update download failed: ${e.message}")
            apk.delete()
            _state.value = UpdateInstallState.Failed(update.versionName)
        }
    }

    private fun install(update: AvailableUpdate, apk: File) {
        _state.value = UpdateInstallState.Installing(update.versionName)
        try {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply { setAppPackageName(appContext.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(WRITE_NAME, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(appContext, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update install failed to start: ${e.message}")
            apk.delete()
            _state.value = UpdateInstallState.Failed(update.versionName)
        }
    }

    /**
     * Called from [UpdateInstallReceiver] for a terminal session status. Success
     * usually never reaches us (the install kills our process first) — the startup
     * sweep is the real cleanup. Any failure/abort deletes the APK and offers Retry.
     */
    fun onInstallOutcome(outcome: InstallOutcome) {
        val update = current ?: return
        when (outcome) {
            InstallOutcome.PENDING_USER_ACTION -> Unit // receiver launched the sheet
            InstallOutcome.SUCCESS -> {
                UpdateFiles.apkFile(appContext.cacheDir, update.versionCode).delete()
                _state.value = UpdateInstallState.Idle
            }
            InstallOutcome.FAILURE -> {
                UpdateFiles.apkFile(appContext.cacheDir, update.versionCode).delete()
                _state.value = UpdateInstallState.Failed(update.versionName)
            }
        }
    }

    /** App-start sweep of the entire updates dir (successful-install leftover + stale partials). */
    fun sweepOnStart() {
        scope.launch {
            runCatching { UpdateFiles.sweep(appContext.cacheDir) }
        }
    }

    companion object {
        private const val TAG = "BtUpdateInstaller"
        private const val DOWNLOAD_BUFFER = 64 * 1024
        private const val WRITE_NAME = "bt_update.apk"
    }
}
