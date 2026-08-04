package at.bettertrack.app.ui.workboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One-shot "open the Workboard on the Alerts segment" request (S6 P1-10).
 *
 * The alerts manager is a SEGMENT of the Workboard tab, not a nav destination,
 * so a deep link cannot address it with a route. Rather than turn the tab route
 * into a parameterised one — which would change the tab's identity and break the
 * bottom bar's save/restore — the shell raises this flag and [WorkboardScreen]
 * consumes it on the next composition. Same idiom as `AppGraph.pendingDeepLink`,
 * which already parks a cold-start push target the same way.
 */
object WorkboardEntry {
    private val _pendingAlerts = MutableStateFlow(false)

    /** True while an Alerts entry is waiting to be honoured. */
    val pendingAlerts: StateFlow<Boolean> = _pendingAlerts

    fun requestAlerts() {
        _pendingAlerts.value = true
    }

    fun consume() {
        _pendingAlerts.value = false
    }
}
