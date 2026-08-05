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

    // ── Which entry the portfolio switcher has selected (owner IA change) ────

    private val _overviewSelected =
        MutableStateFlow(prefs.getBoolean(KEY_OVERVIEW_SELECTED, DEFAULT_OVERVIEW_SELECTED))

    /**
     * True = the Portfolio tab is showing **Overview** (the account-wide index
     * that used to be the Home tab); false = it is showing the portfolio the
     * switcher selected.
     *
     * ## Why this is a separate flag and not a sentinel in the selected-portfolio id
     *
     * Overloading `meta[selected_portfolio]` with an `"__overview__"` sentinel
     * was the shorter change and the wrong one. That key is read by six other
     * screens — cash, transactions, the buy/sell form, holding detail, standing
     * orders, the asset page — each of which needs a REAL portfolio id and
     * resolves the stored value against the portfolio list. A sentinel would
     * have every one of them silently fall back to "first portfolio" while the
     * user believed their choice was still selected, and `SessionInitializer`
     * would race to overwrite it on each cold start. A separate boolean leaves
     * the selection semantics of the whole app untouched: picking Overview
     * remembers *which portfolio you would go back to*, because it never
     * disturbed it.
     *
     * Device-scoped rather than account-scoped, like the orientation lock above:
     * it is a view preference with no account meaning and nothing secret in it,
     * and surviving logout is the friendlier behaviour — the next sign-in opens
     * on the same page the phone was left on.
     *
     * Defaults to true: Overview is the pinned top entry and the app's front
     * door, so a fresh install lands there rather than on an arbitrary portfolio.
     */
    val overviewSelected: StateFlow<Boolean> = _overviewSelected.asStateFlow()

    fun setOverviewSelected(selected: Boolean) {
        if (_overviewSelected.value == selected) return
        prefs.edit().putBoolean(KEY_OVERVIEW_SELECTED, selected).apply()
        _overviewSelected.value = selected
    }

    private companion object {
        const val PREFS = "bt_device_prefs"
        const val KEY_ORIENTATION_LOCKED = "orientation_locked"
        const val DEFAULT_ORIENTATION_LOCKED = true
        const val KEY_OVERVIEW_SELECTED = "overview_selected"
        const val DEFAULT_OVERVIEW_SELECTED = true
    }
}
