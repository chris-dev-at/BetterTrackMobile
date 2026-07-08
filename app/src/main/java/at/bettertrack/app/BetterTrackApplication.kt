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

        // Step 16: create the FCM notification channels (before any push) and
        // obtain the device token (works without the platform; logs presence
        // only — never the value). Device-token registration is stubbed.
        PushChannels.ensure(this)
        AppGraph.pushTokenManager.fetchToken()

        // Step 17 (§5): arm the app lock's AFK background/foreground trigger. The
        // initial locked state (cold start) is set when the controller is built.
        AppGraph.appLockController.start()

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
