package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import at.bettertrack.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure orientation decision (Android-free, unit-tested); mapped to an ActivityInfo
 * constant in the Activity. [FORCED_LANDSCAPE] is reachable only through the
 * debug override (see [BtOrientationOverride]).
 */
enum class ScreenOrientationMode { LOCKED_PORTRAIT, FOLLOW_SENSOR, FORCED_LANDSCAPE }

/**
 * What the portfolio hero chart draws, and what its scrub reports.
 *
 * The server ships both series aligned in one `/history` payload (`points` in €,
 * `performance` in %), so all three modes are a client-side choice over data the
 * app already has — switching never refetches.
 *
 * - [BALANCE] — the € curve with a € readout. The original chart.
 * - [PERFORMANCE] — the % curve with a % readout. What the web calls
 *   "Performance %": deposits and withdrawals are neutralised server-side, so the
 *   curve only moves when the holdings move.
 * - [HYBRID] — the owner's ask (2026-08-07): the % curve's SHAPE, because that is
 *   the shape that says how the investments actually did, with the € balance as
 *   the readout, because that is the number you want when you point at a day.
 *   **The default since 2026-08-07** — see [DEFAULT_CHART_MODE].
 */
enum class BtChartMode {
    BALANCE,
    PERFORMANCE,
    HYBRID,
    ;

    /** True when this mode plots the performance-% series rather than the € one. */
    val plotsPerformance: Boolean get() = this != BALANCE

    /**
     * True when this mode may paint a gain/loss verdict — green above zero, red
     * below it — anywhere on the hero surface.
     *
     * **Only [PERFORMANCE] is.** Owner order 2026-08-07: *"don't color it red or
     * green — only color in % mode."*
     *
     * "Anywhere" is the 2026-08-08 correction. This used to reach only the CURVE
     * (`BtAreaChart.colorBySign`), while the readouts framing it — the range
     * return beside the picker, the day-change line under the headline — kept
     * tinting off their own sign with no idea what mode they were in. They are
     * gated on this flag now, through `signColorAllowed` in the overview.
     *
     * The rule underneath it is the app's own (§4.1, `rangeAccent`): gold *is*
     * the portfolio and never means "up"; a red/green verdict belongs to an
     * asset, which is a bet. [PERFORMANCE] earns the exception because in that
     * mode the curve is *literally* the return — the number and the verdict are
     * the same thing. [HYBRID] does not: its headline is the € balance, so a
     * red/green curve would be colouring one quantity by the sign of another.
     *
     * Separate from [plotsPerformance] precisely because the two used to be one
     * flag, which is what made [HYBRID] inherit the gain/loss paint it should
     * never have had.
     */
    val colorsBySign: Boolean get() = this == PERFORMANCE
}

/**
 * The mode a user who has never chosen one gets.
 *
 * Was [BtChartMode.BALANCE]; [BtChartMode.HYBRID] by owner order 2026-08-07
 * (*"make this one the DEFAULT"*). A constant rather than an inline fallback so
 * the default is one edit in one place and the migration below can name it.
 */
val DEFAULT_CHART_MODE: BtChartMode = BtChartMode.HYBRID

/**
 * Decode a stored [BtChartMode] name, falling back to [DEFAULT_CHART_MODE] for
 * anything unrecognised (absent, or written by a build that knew a mode this one
 * does not). Pure, so the fallback is unit-tested rather than assumed.
 *
 * This IS the default-migration: a stored name is an explicit choice and is
 * honoured verbatim, including `"BALANCE"`, so moving the default does not
 * overrule anyone who actually picked a mode. Absent means never chose, which
 * now means hybrid.
 */
fun chartModeFromName(raw: String?): BtChartMode =
    BtChartMode.entries.firstOrNull { it.name == raw } ?: DEFAULT_CHART_MODE

fun orientationModeFor(locked: Boolean): ScreenOrientationMode =
    if (locked) ScreenOrientationMode.LOCKED_PORTRAIT else ScreenOrientationMode.FOLLOW_SENSOR

/**
 * Which colour table the app renders in (B2 design spec §1.3).
 *
 * Three-valued on purpose. AMOLED true-black is a **boolean under [Dark]**
 * ([DevicePrefs.trueBlack]), not a fourth entry — it overrides two neutrals and
 * nothing else, and keeping the enum at three means every `when` in the app
 * stays exhaustive when the toggle lands.
 */
enum class BtThemeMode { System, Light, Dark }

/**
 * Decode a stored [BtThemeMode] name, falling back to [BtThemeMode.System] for
 * anything unrecognised (absent, or written by a build that knew a mode this one
 * does not). Pure, so the fallback is unit-tested rather than assumed.
 */
fun themeModeFromName(raw: String?): BtThemeMode =
    BtThemeMode.entries.firstOrNull { it.name == raw } ?: BtThemeMode.System

/**
 * Device-scoped UI preferences (owner ask 2026-07-10). Deliberately NOT the
 * account-scoped Room `meta` KV — these are device/UI settings that must SURVIVE
 * logout and carry no secrets. Plain [SharedPreferences] so the value is readable
 * synchronously at Activity start (before the first frame) and observable as a
 * [StateFlow] for instant application when the user toggles it.
 */
class DevicePrefs internal constructor(
    private val prefs: SharedPreferences,
    /**
     * Phone or tablet — decides the orientation lock's DEFAULT only (see
     * [resolveOrientationLocked]). Defaults to [BtDeviceClass.Phone] so a test
     * that does not care gets the phone app's behaviour.
     */
    deviceClass: BtDeviceClass = BtDeviceClass.Phone,
    /**
     * Whether debug-only preferences exist at all. `BuildConfig.DEBUG` in the
     * real store; a parameter so the release half of the rule is testable.
     */
    private val debugFeatures: Boolean = false,
) {

    /**
     * The real one. The [SharedPreferences] primary constructor above exists so
     * the store's own rules — the true-black persistence, the orientation default
     * — can be gated in a plain JVM test; this project has no Robolectric, and a
     * `Context` cannot be faked without one.
     *
     * The device class comes from the APPLICATION context's smallest width: the
     * process-level configuration describes the display, not one activity window,
     * so a tablet in split screen is still a tablet here. It is read once per
     * process; a foldable that changes class mid-process keeps the class it
     * started with until the next cold start, which only ever affects the default
     * of a toggle the user has never touched.
     */
    constructor(context: Context) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        deviceClass = btDeviceClassFor(
            context.applicationContext.resources.configuration.smallestScreenWidthDp,
        ),
        debugFeatures = BuildConfig.DEBUG,
    )

    private val _orientationLocked = MutableStateFlow(
        resolveOrientationLocked(
            stored = if (prefs.contains(KEY_ORIENTATION_LOCKED)) {
                prefs.getBoolean(KEY_ORIENTATION_LOCKED, true)
            } else {
                null
            },
            deviceClass = deviceClass,
        ),
    )

    /**
     * True = the app stays portrait-locked; false = the app follows the device
     * sensor (e.g. a tablet can view in landscape). Applied at the activity level
     * via `requestedOrientation` on start and immediately on toggle.
     *
     * The default is per device class since T1 (2026-10-29): locked on phones,
     * unlocked on tablets — see [defaultOrientationLocked]. A stored value is an
     * explicit choice and always wins; reading never writes the default back.
     */
    val orientationLocked: StateFlow<Boolean> = _orientationLocked.asStateFlow()

    /** Synchronous read — the Activity needs the value before the first frame. */
    fun orientationLockedNow(): Boolean = _orientationLocked.value

    fun setOrientationLocked(locked: Boolean) {
        prefs.edit { putBoolean(KEY_ORIENTATION_LOCKED, locked) }
        _orientationLocked.value = locked
    }

    // ── Debug orientation override (T1, Settings → Developer) ─────────────────

    private val _orientationOverride = MutableStateFlow(
        if (debugFeatures) {
            orientationOverrideFromName(prefs.getString(KEY_DEBUG_ORIENTATION_OVERRIDE, null))
        } else {
            // Release: the key is never READ, so a value smuggled into the file
            // by a debug build of the same signing key cannot pin a release.
            BtOrientationOverride.System
        },
    )

    /**
     * DEBUG builds only: force the activity into portrait or landscape regardless
     * of the lock and the sensor, so the landscape layout can be checked on a
     * tablet lying flat. Always [BtOrientationOverride.System] in release.
     */
    val orientationOverride: StateFlow<BtOrientationOverride> = _orientationOverride.asStateFlow()

    /** Synchronous read — the Activity needs the value before the first frame. */
    fun orientationOverrideNow(): BtOrientationOverride = _orientationOverride.value

    /** A no-op in release builds: there is no control, so there is nothing to persist. */
    fun setOrientationOverride(override: BtOrientationOverride) {
        if (!debugFeatures) return
        prefs.edit { putString(KEY_DEBUG_ORIENTATION_OVERRIDE, override.name) }
        _orientationOverride.value = override
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
        prefs.edit { putBoolean(KEY_OVERVIEW_SELECTED, selected) }
        _overviewSelected.value = selected
    }

    // ── What the portfolio hero chart plots (owner ask 2026-08-07) ───────────

    private val _chartMode = MutableStateFlow(chartModeFromName(prefs.getString(KEY_CHART_MODE, null)))

    /**
     * Which curve/readout pairing the hero chart uses — see [BtChartMode].
     *
     * Device-scoped for the same reason the two flags above are: it is a view
     * preference, it says nothing about the account, and the friendlier behaviour
     * is for the phone to reopen on the view it was left on. Stored by enum NAME
     * rather than ordinal so reordering the enum can never silently reinterpret a
     * saved preference as a different mode.
     *
     * An ABSENT key means "never chose" and resolves to [DEFAULT_CHART_MODE].
     * That distinction is what lets the default move without overruling anyone,
     * so [setChartMode] is careful to persist every explicit tap — see there.
     */
    val chartMode: StateFlow<BtChartMode> = _chartMode.asStateFlow()

    fun setChartMode(mode: BtChartMode) {
        // Persist FIRST and unconditionally, even when the value is unchanged.
        //
        // This used to early-return on "same value", which silently made an
        // explicit tap on the already-selected mode a no-op that wrote nothing —
        // so a user who deliberately chose the then-default € was, on disk,
        // indistinguishable from one who never opened the app. Writing every tap
        // means the key genuinely records "the user chose this", which is exactly
        // what the next default move will need in order to respect them.
        prefs.edit { putString(KEY_CHART_MODE, mode.name) }
        if (_chartMode.value == mode) return
        _chartMode.value = mode
    }

    // ── Which colour table the app renders in (B2 §1.3) ─────────────────────

    private val _themeMode = MutableStateFlow(themeModeFromName(prefs.getString(KEY_THEME_MODE, null)))

    /**
     * The user's theme choice — see [BtThemeMode].
     *
     * Device-scoped for the same reason the flags above are: it is a view
     * preference, it says nothing about the account, and it must survive logout
     * (the login screen itself has to render in the chosen theme). Stored by
     * enum NAME so reordering the enum can never reinterpret a saved value.
     */
    val themeMode: StateFlow<BtThemeMode> = _themeMode.asStateFlow()

    /** Synchronous read — the Activity needs the value before the first frame. */
    fun themeModeNow(): BtThemeMode = _themeMode.value

    fun setThemeMode(mode: BtThemeMode) {
        if (_themeMode.value == mode) return
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
    }

    private val _trueBlack = MutableStateFlow(
        prefs.getBoolean(KEY_TRUE_BLACK, DEFAULT_TRUE_BLACK),
    )

    /**
     * AMOLED true-black, a sub-toggle **under Dark only**: it overrides the page
     * background to `#000000` and the recessed level to `#050608`, and nothing
     * else. Ignored entirely while the resolved mode is light.
     *
     * ## History, because this pref has been through something
     *
     * It shipped with a row in Settings → Appearance, the row was removed the
     * next day for web parity (the web has no such setting), and the class then
     * actively DESTROYED the stored key on every read — the reasoning being that
     * a black app with no control left to undo it is not a setting but a trap,
     * so the honest repair was to un-choose it for everyone.
     *
     * **The owner reinstated the row on 2026-08-17** (*"also the oled dark mode
     * dissapeared"*), which removes the premise: the control exists again, so
     * the choice is revisable again, so the value is his to keep. Web parity
     * does not decide this one — OLED pixel-off is a property of the panel in
     * his hand, and a browser tab has no equivalent to be at parity WITH.
     *
     * Devices that had it on before the row was pulled had their key deleted by
     * the old healing and come back OFF. That is not recoverable and is not
     * worth faking: the value was genuinely erased, and one tap restores it.
     */
    val trueBlack: StateFlow<Boolean> = _trueBlack.asStateFlow()

    /** Synchronous read — the Activity needs the value before the first frame. */
    fun trueBlackNow(): Boolean = _trueBlack.value

    /**
     * Persists again (owner 2026-08-17, see [trueBlack]).
     *
     * It was session-only while the row was gone, precisely so nothing could
     * write a value the user could not unwrite. With the row back that argument
     * is spent, and a display preference that forgets itself on every cold start
     * is worse than no preference at all.
     */
    fun setTrueBlack(enabled: Boolean) {
        if (_trueBlack.value == enabled) return
        prefs.edit { putBoolean(KEY_TRUE_BLACK, enabled) }
        _trueBlack.value = enabled
    }

    // ── DEBUG ONLY: auth fault injection (Settings → Developer) ───────────────
    private val _debugAuthFault = MutableStateFlow(
        at.bettertrack.app.data.auth.authFaultFromName(prefs.getString(KEY_DEBUG_AUTH_FAULT, null)),
    )

    /**
     * What the next token refresh should suffer — see
     * [at.bettertrack.app.data.auth.AuthFault]. Only ever read by the debug-only
     * fault interceptor; a release build never wires one.
     */
    val debugAuthFault: StateFlow<at.bettertrack.app.data.auth.AuthFault> = _debugAuthFault.asStateFlow()

    fun debugAuthFaultNow(): at.bettertrack.app.data.auth.AuthFault = _debugAuthFault.value

    /**
     * `commit = true`: disarming happens a moment before the
     * `KILL_AFTER_SEND` fault kills the process, and the relaunched process must
     * find it disarmed.
     */
    fun setDebugAuthFault(fault: at.bettertrack.app.data.auth.AuthFault) {
        prefs.edit(commit = true) { putString(KEY_DEBUG_AUTH_FAULT, fault.name) }
        _debugAuthFault.value = fault
    }

    /**
     * `internal`, not `private`, since 2026-08-18: the widget package reads
     * [KEY_THEME_MODE] out of [PREFS] DIRECTLY (see `btWidgetStoredThemeMode`).
     * A widget's transient first frame must know the app's theme without
     * touching `AppGraph` — forcing the graph is the slow thing on that path —
     * and a second copy of these two strings in the widget package would be a
     * silent-drift hazard the moment either one is renamed.
     */
    internal companion object {
        const val PREFS = "bt_device_prefs"
        const val KEY_ORIENTATION_LOCKED = "orientation_locked"

        /** Debug-only (see [orientationOverride]); the `debug_` prefix marks it as such. */
        const val KEY_DEBUG_ORIENTATION_OVERRIDE = "debug_orientation_override"
        const val KEY_OVERVIEW_SELECTED = "overview_selected"
        const val DEFAULT_OVERVIEW_SELECTED = true
        const val KEY_CHART_MODE = "chart_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_TRUE_BLACK = "true_black"
        const val DEFAULT_TRUE_BLACK = false
        const val KEY_DEBUG_AUTH_FAULT = "debug_auth_fault_next_refresh"
    }
}
