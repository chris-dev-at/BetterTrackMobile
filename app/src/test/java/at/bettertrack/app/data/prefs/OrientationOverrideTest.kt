package at.bettertrack.app.data.prefs

import at.bettertrack.app.vault.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The debug-only orientation override (Settings → Developer, T1 2026-10-29):
 * System defers to the normal preference, Portrait / Landscape force the
 * activity, and a release build neither reads nor honours it.
 */
class OrientationOverrideTest {

    private val prefs = FakeSharedPreferences()

    // ── the state mapping ─────────────────────────────────────────────────────

    @Test
    fun `system defers to the lock preference`() {
        assertEquals(
            ScreenOrientationMode.LOCKED_PORTRAIT,
            orientationModeFor(locked = true, override = BtOrientationOverride.System, debugBuild = true),
        )
        assertEquals(
            ScreenOrientationMode.FOLLOW_SENSOR,
            orientationModeFor(locked = false, override = BtOrientationOverride.System, debugBuild = true),
        )
    }

    @Test
    fun `portrait forces portrait whatever the lock says`() {
        listOf(true, false).forEach { locked ->
            assertEquals(
                "locked=$locked",
                ScreenOrientationMode.LOCKED_PORTRAIT,
                orientationModeFor(locked, BtOrientationOverride.Portrait, debugBuild = true),
            )
        }
    }

    @Test
    fun `landscape forces landscape whatever the lock says`() {
        // The case the override exists for: a locked (or flat-lying) tablet that
        // has to show its Expanded layout.
        listOf(true, false).forEach { locked ->
            assertEquals(
                "locked=$locked",
                ScreenOrientationMode.FORCED_LANDSCAPE,
                orientationModeFor(locked, BtOrientationOverride.Landscape, debugBuild = true),
            )
        }
    }

    @Test
    fun `a release build ignores every override`() {
        BtOrientationOverride.entries.forEach { override ->
            listOf(true, false).forEach { locked ->
                assertEquals(
                    "override=$override locked=$locked",
                    orientationModeFor(locked),
                    orientationModeFor(locked, override, debugBuild = false),
                )
            }
        }
    }

    @Test
    fun `forced landscape is reachable only through the debug override`() {
        listOf(true, false).forEach { locked ->
            BtOrientationOverride.entries.forEach { override ->
                if (override != BtOrientationOverride.Landscape) {
                    assertFalse(
                        orientationModeFor(locked, override, debugBuild = true) ==
                            ScreenOrientationMode.FORCED_LANDSCAPE,
                    )
                }
                assertFalse(
                    orientationModeFor(locked, override, debugBuild = false) ==
                        ScreenOrientationMode.FORCED_LANDSCAPE,
                )
            }
        }
    }

    @Test
    fun `stored names decode and junk falls back to system`() {
        BtOrientationOverride.entries.forEach { assertEquals(it, orientationOverrideFromName(it.name)) }
        assertEquals(BtOrientationOverride.System, orientationOverrideFromName(null))
        assertEquals(BtOrientationOverride.System, orientationOverrideFromName("Upside"))
        assertEquals(BtOrientationOverride.System, orientationOverrideFromName("landscape"))
    }

    // ── the store ─────────────────────────────────────────────────────────────

    @Test
    fun `a debug store reads the stored override`() {
        prefs.edit().putString(KEY, "Landscape").apply()

        assertEquals(
            BtOrientationOverride.Landscape,
            DevicePrefs(prefs, debugFeatures = true).orientationOverrideNow(),
        )
    }

    @Test
    fun `a release store never reads it`() {
        // The same signing key installs debug and release; a value a debug build
        // left in the file must not pin a release build's orientation.
        prefs.edit().putString(KEY, "Landscape").apply()

        assertEquals(
            BtOrientationOverride.System,
            DevicePrefs(prefs, debugFeatures = false).orientationOverrideNow(),
        )
    }

    @Test
    fun `a release store cannot write it`() {
        val devicePrefs = DevicePrefs(prefs, debugFeatures = false)

        devicePrefs.setOrientationOverride(BtOrientationOverride.Landscape)

        assertFalse(prefs.contains(KEY))
        assertEquals(BtOrientationOverride.System, devicePrefs.orientationOverride.value)
    }

    @Test
    fun `a debug choice persists across a restart and reaches the flow`() {
        val devicePrefs = DevicePrefs(prefs, debugFeatures = true)

        devicePrefs.setOrientationOverride(BtOrientationOverride.Portrait)

        assertEquals(BtOrientationOverride.Portrait, devicePrefs.orientationOverride.value)
        assertEquals(
            BtOrientationOverride.Portrait,
            DevicePrefs(prefs, debugFeatures = true).orientationOverrideNow(),
        )
    }

    @Test
    fun `the override never touches the lock`() {
        prefs.edit().putBoolean("orientation_locked", true).apply()
        val devicePrefs = DevicePrefs(prefs, deviceClass = BtDeviceClass.Tablet, debugFeatures = true)

        devicePrefs.setOrientationOverride(BtOrientationOverride.Landscape)

        assertEquals(true, devicePrefs.orientationLockedNow())
        assertEquals(true, prefs.getBoolean("orientation_locked", false))
    }

    private companion object {
        /** The literal key, `debug_`-prefixed — a rename must fail here. */
        const val KEY = "debug_orientation_override"
    }
}
