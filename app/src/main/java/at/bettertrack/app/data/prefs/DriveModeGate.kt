package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import at.bettertrack.app.BuildConfig
import at.bettertrack.app.data.storage.StorageMode

/**
 * The debug-only gate on Drive-autonomous mode (S3/S4 plan §5 W4: *"Everything
 * behind the debug-only mode flag … Flag off ⇒ release build unchanged"*).
 *
 * W4 builds the whole Drive medium; W5 ships the first-run wizard that lets a
 * user actually choose it. In between there must be a way for a developer to
 * reach DRIVE mode on a debug build, and **no** way for it to be reachable in a
 * release build — not by a stale prefs file, not by a mis-set stored mode, not
 * by a bug in a code path that has not shipped yet.
 *
 * The shape is deliberately the same as [ServerOrigins] (V5 S1): plain
 * [SharedPreferences] (this is read synchronously from `AppGraph.init`, before
 * the DB and network exist, and it holds no secret), a hard [BuildConfig.DEBUG]
 * guard on every read, and restart-applied semantics with the dev screen saying
 * so rather than pretending otherwise.
 *
 * ## Why the gate is a filter on the stored mode, not a separate switch
 *
 * [gatedMode] takes whatever [StorageMode] the store resolved and returns
 * [StorageMode.SERVER] for the Drive-holding modes on a release build. So the
 * mode a release APK acts on can only ever be SERVER, whatever is persisted —
 * which is the property that makes "release build unchanged" a fact rather than
 * a hope. A second boolean the code had to remember to check everywhere would
 * not have been.
 */
object DriveModeGate {

    private const val PREFS = "bt_dev_drive_mode"
    private const val KEY_ENABLED = "drive_mode_enabled"

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile private var enabledValue: Boolean = false

    /**
     * Loads the flag. Called synchronously from `AppGraph.init`.
     * No-ops entirely on release builds, so the field stays `false` forever.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        enabledValue = p.getBoolean(KEY_ENABLED, false)
    }

    /** True when this build may act on a Drive-holding [StorageMode]. */
    val isEnabled: Boolean get() = BuildConfig.DEBUG && enabledValue

    /** Debug menu toggle. Restart-applied — the object graph is built once. */
    fun setEnabled(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        enabledValue = enabled
    }

    /**
     * The mode the app may actually act on.
     *
     * Pure delegation to [gatedStorageMode] so the rule is unit-tested without
     * Android or BuildConfig.
     */
    fun gatedMode(stored: StorageMode): StorageMode = gatedStorageMode(stored, isEnabled)
}

/**
 * Pure gate rule: a Drive-holding mode survives only when the Drive medium is
 * enabled; otherwise the app behaves exactly as it does today.
 *
 * `UNSET` is left alone rather than mapped: it already *behaves* as SERVER
 * through `StorageMode.effective`, and collapsing it here would destroy the one
 * distinction W5's wizard needs ("never asked" vs "chose the server").
 */
internal fun gatedStorageMode(stored: StorageMode, driveEnabled: Boolean): StorageMode = when {
    driveEnabled -> stored
    stored == StorageMode.DRIVE || stored == StorageMode.BOTH -> StorageMode.SERVER
    else -> stored
}
