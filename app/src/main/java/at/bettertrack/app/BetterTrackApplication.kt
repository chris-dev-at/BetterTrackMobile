package at.bettertrack.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
            }
        })
    }
}
