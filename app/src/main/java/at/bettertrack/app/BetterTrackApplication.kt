package at.bettertrack.app

import android.app.Application
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
 */
class BetterTrackApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        // V5 W1 (S3/S4 plan §4.3): resolve an existing install's storage mode to
        // SERVER exactly once, so an upgrade-in-place never meets the W5
        // first-run wizard. A clean install stays UNSET and behaves as SERVER.
        AppGraph.grandfatherStorageMode()

        // Step 16 (LIVE on Notifications-v2): create the FCM notification channels
        // (before any push) and obtain the device token (logs presence only — never
        // the value). Registration is bearer-gated inside the manager: it fires now
        // if this cold start is already logged in, else it defers to the next login.
        PushChannels.ensure(this)
        AppGraph.pushTokenManager.fetchToken()

        // Step 17 (§5): arm the app lock's AFK background/foreground trigger. The
        // initial locked state (cold start) is set when the controller is built.
        AppGraph.appLockController.start()

        // V5 W5 (S3/S4 plan §2.7/§4.4): the vault follows the SAME idle lock, so
        // there is one timer and one mental model rather than two competing ones.
        AppGraph.linkVaultLockToAppLock()

        // In-app update install (owner ask 2026-07-12): sweep cacheDir/updates on
        // every start — removes the APK left by a successful install (that install
        // killed the previous process) and any partial download from a mid-stream kill.
        AppGraph.updateInstaller.sweepOnStart()

        // Fire the first-of-session data load on login-success / logged-in cold
        // start so no screen sits on skeletons until a manual pull-to-refresh.
        AppGraph.sessionInitializer.start()

        // Live connectivity for the offline banner + the reconnect trigger.
        AppGraph.connectivityMonitor.start()
        appScope.launch {
            AppGraph.connectivityMonitor.isOnline
                .drop(1) // only true transitions, not the initial state
                .filter { it }
                .collect { AppGraph.syncScheduler.scheduleDrain() }
        }

        // Drain whenever the app comes to the foreground (§7.3). The engine
        // no-ops instantly when logged out or the queue is empty.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppGraph.syncScheduler.scheduleDrain()
                // Dev update notifier (Step V): rate-limited, silent on failure.
                AppGraph.updateChecker.onForeground()
            }
        })
    }
}
