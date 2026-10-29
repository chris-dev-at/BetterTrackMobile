package at.bettertrack.app.data.prefs

/**
 * Orientation policy for large screens (T1 large-screen foundation, 2026-10-29).
 *
 * Android-free, so every rule below is pinned by a plain JVM test; the Activity
 * only maps the resulting [ScreenOrientationMode] onto an `ActivityInfo`
 * constant.
 */

/**
 * Whether the DEVICE is a phone or a tablet — the class the orientation default
 * is decided by.
 *
 * Deliberately not the window's width class
 * ([at.bettertrack.app.ui.adaptive.BtWidthClass]). A layout follows the window,
 * which changes with rotation and split screen; "should this app rotate at all"
 * is a question about the glass in the user's hand, and it must not flip because
 * the user dragged a split-screen divider. The smallest width is the one
 * dimension rotation does not change.
 */
enum class BtDeviceClass { Phone, Tablet }

/** Smallest width, in dp, at which a device counts as a tablet — Android's own sw600dp line. */
const val BT_TABLET_SMALLEST_WIDTH_DP: Int = 600

/** The device class for a device whose smallest width is [smallestWidthDp]. */
fun btDeviceClassFor(smallestWidthDp: Int): BtDeviceClass =
    if (smallestWidthDp >= BT_TABLET_SMALLEST_WIDTH_DP) BtDeviceClass.Tablet else BtDeviceClass.Phone

/**
 * The orientation lock a user who has never touched the toggle gets.
 *
 * Phones stay portrait-locked, exactly as before. Tablets are UNLOCKED: a tablet
 * is used in both orientations as a matter of course, and a lock defaulting ON
 * meant the owner's tablet never once showed the app in landscape. (Android 16
 * goes further and ignores orientation requests on sw600dp+ for apps targeting
 * API 36; this default agrees with that direction on every older release too.)
 */
fun defaultOrientationLocked(deviceClass: BtDeviceClass): Boolean = deviceClass == BtDeviceClass.Phone

/**
 * The effective lock: an explicit choice ([stored]) always wins; only an absent
 * key falls back to the device-class default. The default is computed, never
 * written — "never chose" stays never chose, so the same install moved to a
 * different device class gets that class's default.
 */
fun resolveOrientationLocked(stored: Boolean?, deviceClass: BtDeviceClass): Boolean =
    stored ?: defaultOrientationLocked(deviceClass)

/**
 * The DEBUG-ONLY orientation override (Settings → Developer).
 *
 * It exists to verify the landscape layout on a test tablet that lies flat on a
 * desk with the system rotation settings out of bounds. [System] means "no
 * override": the normal preference decides. Release builds never read the
 * stored value and never show the control.
 */
enum class BtOrientationOverride { System, Portrait, Landscape }

/**
 * Decode a stored [BtOrientationOverride] name; anything unrecognised (absent,
 * or written by a build that knew a value this one does not) is [System].
 */
fun orientationOverrideFromName(raw: String?): BtOrientationOverride =
    BtOrientationOverride.entries.firstOrNull { it.name == raw } ?: BtOrientationOverride.System

/**
 * The orientation the Activity requests, all inputs considered.
 *
 * [debugBuild] is passed in rather than read from `BuildConfig` so the release
 * half of the rule — the override is ignored outright — is testable from a debug
 * test run.
 */
fun orientationModeFor(
    locked: Boolean,
    override: BtOrientationOverride,
    debugBuild: Boolean,
): ScreenOrientationMode {
    if (!debugBuild) return orientationModeFor(locked)
    return when (override) {
        BtOrientationOverride.System -> orientationModeFor(locked)
        BtOrientationOverride.Portrait -> ScreenOrientationMode.LOCKED_PORTRAIT
        BtOrientationOverride.Landscape -> ScreenOrientationMode.FORCED_LANDSCAPE
    }
}
