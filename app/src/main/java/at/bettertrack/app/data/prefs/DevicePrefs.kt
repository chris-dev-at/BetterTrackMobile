package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure orientation decision (Android-free, unit-tested); mapped to an ActivityInfo constant in the Activity. */
enum class ScreenOrientationMode { LOCKED_PORTRAIT, FOLLOW_SENSOR }

fun orientationModeFor(locked: Boolean): ScreenOrientationMode =
    if (locked) ScreenOrientationMode.LOCKED_PORTRAIT else ScreenOrientationMode.FOLLOW_SENSOR

/**
 * Device-scoped UI preferences (owner ask 2026-07-10). Deliberately NOT the
 * account-scoped Room `meta` KV — these are device/UI settings that must SURVIVE
 * logout and carry no secrets. Plain [SharedPreferences] so the value is readable
 * synchronously at Activity start (before the first frame) and observable as a
 * [StateFlow] for instant application when the user toggles it.
 */
class DevicePrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _orientationLocked =
        MutableStateFlow(prefs.getBoolean(KEY_ORIENTATION_LOCKED, DEFAULT_ORIENTATION_LOCKED))

    /**
     * True (default) = the app stays portrait-locked; false = the app follows the
     * device sensor (e.g. a tablet can view in landscape). Applied at the activity
     * level via `requestedOrientation` on start and immediately on toggle.
     */
    val orientationLocked: StateFlow<Boolean> = _orientationLocked.asStateFlow()

    /** Synchronous read — the Activity needs the value before the first frame. */
    fun orientationLockedNow(): Boolean = _orientationLocked.value

    fun setOrientationLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_ORIENTATION_LOCKED, locked).apply()
        _orientationLocked.value = locked
    }

    private companion object {
        const val PREFS = "bt_device_prefs"
        const val KEY_ORIENTATION_LOCKED = "orientation_locked"
        const val DEFAULT_ORIENTATION_LOCKED = true
    }
}
