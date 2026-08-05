package at.bettertrack.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import at.bettertrack.app.data.push.PushChannels
import at.bettertrack.app.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Initialises the manual dependency graph once, at process start, and wires
 * the two ambient sync-drain triggers (spec §7.3):
 *  - app-foreground (ProcessLifecycleOwner),
 *  - connectivity-restored (in-process NetworkCallback; the parked
 *    CONNECTED-constrained WorkManager job covers the process-dead case).
 *
 * ## Startup is fail-soft, on purpose
 *
 * Every step below except [AppGraph.init] is an ambient side effect: push
 * channels, the app lock's AFK trigger, the update sweep, the first-of-session
 * load. None of them is what the user opened the app to do, so none of them may
 * decide the app does not start. They each run inside [startupStep], which logs
 * a failure under `BT-NONFATAL` and moves to the next one — a server that is
 * down, a Firebase that will not initialise or a Keystore that is unhappy leaves
 * the user at a working (if emptier) screen instead of at the launcher.
 *
 * [AppGraph.init] is deliberately NOT wrapped: without the application context
 * there is no app, and pretending otherwise would only move the crash somewhere
 * less legible.
 */
class BetterTrackApplication : Application() {

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + btBackgroundExceptionHandler("Application.appScope"),
    )

    override fun onCreate() {
        super.onCreate()
        // FIRST, before anything can throw: whatever kills this process from here
        // on gets its stack trace written under `BT-CRASH` on the way out.
        BtCrashGuard.installCrashLogger()

        AppGraph.init(this)

        // V5 W1 (S3/S4 plan §4.3): resolve an existing install's storage mode to
        // SERVER exactly once, so an upgrade-in-place never meets the W5
        // first-run wizard. A clean install stays UNSET and behaves as SERVER.
        startupStep("grandfatherStorageMode") { AppGraph.grandfatherStorageMode() }

        // Step 16 (LIVE on Notifications-v2): create the FCM notification channels
        // (before any push) and obtain the device token (logs presence only — never
        // the value). Registration is bearer-gated inside the manager: it fires now
        // if this cold start is already logged in, else it defers to the next login.
        startupStep("pushChannels") { PushChannels.ensure(this) }
        startupStep("pushToken") { AppGraph.pushTokenManager.fetchToken() }

        // Step 17 (§5): arm the app lock's AFK background/foreground trigger. The
        // initial locked state (cold start) is set when the controller is built.
        startupStep("appLock") { AppGraph.appLockController.start() }

        // V5 W5 (S3/S4 plan §2.7/§4.4): the vault follows the SAME idle lock, so
        // there is one timer and one mental model rather than two competing ones.
        startupStep("vaultLockLink") { AppGraph.linkVaultLockToAppLock() }

        // In-app update install (owner ask 2026-07-12): sweep cacheDir/updates on
        // every start — removes the APK left by a successful install (that install
        // killed the previous process) and any partial download from a mid-stream kill.
        startupStep("updateSweep") { AppGraph.updateInstaller.sweepOnStart() }

        // Fire the first-of-session data load on login-success / logged-in cold
        // start so no screen sits on skeletons until a manual pull-to-refresh.
        startupStep("sessionInitializer") { AppGraph.sessionInitializer.start() }

        // Live connectivity for the offline banner + the reconnect trigger.
        startupStep("connectivityMonitor") { AppGraph.connectivityMonitor.start() }
        appScope.launch {
            AppGraph.connectivityMonitor.isOnline
                .drop(1) // only true transitions, not the initial state
                .filter { it }
                .collect {
                    // Scheduling is WorkManager IPC: it can fail (process being
                    // torn down, WM not yet initialised) and a failure here must
                    // not end the collector — the next reconnect retries.
                    startupStep("scheduleDrain(reconnect)") { AppGraph.syncScheduler.scheduleDrain() }
                }
        }

        // Drain whenever the app comes to the foreground (§7.3). The engine
        // no-ops instantly when logged out or the queue is empty.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Lifecycle callbacks run on the MAIN thread: a throw here is an
                // immediate, user-visible crash on every foreground return, which
                // is the worst shape a background chore can fail in.
                startupStep("scheduleDrain(foreground)") { AppGraph.syncScheduler.scheduleDrain() }
                // Dev update notifier (Step V): rate-limited, silent on failure.
                startupStep("updateCheck(foreground)") { AppGraph.updateChecker.onForeground() }
            }
        })
    }

    /**
     * Runs one ambient startup/foreground side effect. A failure is logged and
     * skipped; the app still starts, and the screens fall back to the empty /
     * error states they already render.
     */
    private inline fun startupStep(name: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            Log.w(BtCrashGuard.NONFATAL_TAG, "Startup step '$name' failed; continuing without it.", e)
        }
    }
}
