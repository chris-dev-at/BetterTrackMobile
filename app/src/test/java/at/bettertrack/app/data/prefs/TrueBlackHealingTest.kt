package at.bettertrack.app.data.prefs

import at.bettertrack.app.vault.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AMOLED true-black flag is *stranded state*, and [DevicePrefs] destroys it.
 *
 * The Appearance section carried a True-black row for about a day before it was
 * removed for web parity. Removing the row left the stored flag behind, and a
 * stored `true` is not a preference any more — it is a black app with no control
 * anywhere in the UI that can turn it off, on a preference file that
 * deliberately survives logout. These tests pin the healing so a later reader
 * cannot "restore" the read and re-trap those devices.
 */
class TrueBlackHealingTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun `a stored true is not honoured`() {
        prefs.edit().putBoolean(KEY, true).apply()

        assertFalse(DevicePrefs(prefs).trueBlackNow())
    }

    @Test
    fun `a stored true is removed, not merely ignored`() {
        prefs.edit().putBoolean(KEY, true).apply()

        DevicePrefs(prefs)

        // Removed rather than overwritten with `false`: an ABSENT key is this
        // store's "never chose", which is exactly true again once the value the
        // user can no longer revise is gone.
        assertFalse("the key survived construction", prefs.contains(KEY))
    }

    @Test
    fun `the flow agrees with the synchronous read`() {
        prefs.edit().putBoolean(KEY, true).apply()

        val devicePrefs = DevicePrefs(prefs)

        // The Activity reads one before the first frame and collects the other;
        // they must never disagree, or the app paints black and then repaints.
        assertEquals(devicePrefs.trueBlackNow(), devicePrefs.trueBlack.value)
        assertFalse(devicePrefs.trueBlack.value)
    }

    @Test
    fun `healing touches nothing else in the file`() {
        prefs.edit()
            .putBoolean(KEY, true)
            .putBoolean("orientation_locked", false)
            .putString("theme_mode", "Dark")
            .apply()

        val devicePrefs = DevicePrefs(prefs)

        assertFalse(prefs.contains(KEY))
        assertFalse("the orientation lock was collateral damage", devicePrefs.orientationLockedNow())
        assertEquals(BtThemeMode.Dark, devicePrefs.themeModeNow())
    }

    @Test
    fun `a file that never had the key is left alone`() {
        val devicePrefs = DevicePrefs(prefs)

        assertFalse(devicePrefs.trueBlackNow())
        // No key is written on the way past — "never chose" stays never chose,
        // so exposing the setting again later starts from a clean file.
        assertFalse(prefs.contains(KEY))
    }

    @Test
    fun `an in-session override is honoured but never persisted`() {
        val devicePrefs = DevicePrefs(prefs)

        devicePrefs.setTrueBlack(true)

        // Live for the token machinery that reads the flow…
        assertTrue(devicePrefs.trueBlack.value)
        // …and gone at the next launch, because persisting it would recreate the
        // very stranding the healing above exists to undo.
        assertFalse("setTrueBlack wrote to disk", prefs.contains(KEY))
        assertFalse(DevicePrefs(prefs).trueBlackNow())
    }

    private companion object {
        /**
         * Spelled out rather than imported: the constant in [DevicePrefs] is
         * private, and the thing under test is the value sitting in *existing*
         * preference files under this exact name.
         */
        const val KEY = "true_black"
    }
}
