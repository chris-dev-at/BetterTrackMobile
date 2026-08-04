package at.bettertrack.app.data.prefs

import android.content.Context
import at.bettertrack.app.ui.format.BtDiscreetMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local persistence for discreet mode.
 *
 * The flag lives on the account server-side, but it MUST also be cached on the
 * device. Masking is a privacy promise: if it only took effect once
 * `GET /settings/account` came back, then every cold start would flash real
 * amounts on screen before hiding them, and an offline launch would never hide
 * them at all. So the local value is authoritative for RENDERING and the server
 * is authoritative for the user's INTENT — the two are reconciled on each
 * successful read.
 */
class DiscreetModeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        // Apply the cached value before the first frame renders.
        BtDiscreetMode.setEnabled(_enabled.value)
    }

    /** Set from a user toggle (optimistic) or from a server read (reconcile). */
    fun set(value: Boolean) {
        if (_enabled.value == value) {
            // Still (re)assert the render flag: a process restart can leave the
            // global at its default while the flow already holds the real value.
            BtDiscreetMode.setEnabled(value)
            return
        }
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        BtDiscreetMode.setEnabled(value)
    }

    /** Wipe on logout / account switch — the next account starts unmasked. */
    fun clear() {
        prefs.edit().remove(KEY_ENABLED).apply()
        _enabled.value = false
        BtDiscreetMode.setEnabled(false)
    }

    private companion object {
        const val PREFS = "bt_discreet_mode"
        const val KEY_ENABLED = "enabled"
    }
}
