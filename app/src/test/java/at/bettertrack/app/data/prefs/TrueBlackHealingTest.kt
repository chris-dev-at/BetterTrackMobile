package at.bettertrack.app.data.prefs

import at.bettertrack.app.vault.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AMOLED true-black flag is a **persisted preference again** — owner
 * override, 2026-08-17: *"also the oled dark mode dissapeared."*
 *
 * ## What this file used to assert, and why it flipped
 *
 * The Appearance section carried a True-black row for about a day before it was
 * removed for web parity. That left the stored flag behind with no control able
 * to unset it, so `DevicePrefs` destroyed the key on read and this file pinned
 * that destruction: a choice you cannot revise is not a setting, it is a trap.
 *
 * The reasoning was sound and its premise is now false. The owner put the row
 * back, so the control exists, so the value is his to keep — and a display
 * preference that forgets itself on every cold start would be its own bug. The
 * healing is gone and the assertions below are its mirror image: the flag is
 * read, written, and survives a restart.
 *
 * The class name is kept so the history stays greppable from the commit that
 * introduced the healing.
 */
class TrueBlackHealingTest {

    private val prefs = FakeSharedPreferences()

    @Test
    fun `a stored true is honoured`() {
        prefs.edit().putBoolean(KEY, true).apply()

        assertTrue(DevicePrefs(prefs).trueBlackNow())
    }

    @Test
    fun `a stored true survives construction`() {
        prefs.edit().putBoolean(KEY, true).apply()

        DevicePrefs(prefs)

        // The exact regression this file now guards: an earlier build deleted
        // this key on every read, which is how the owner's OLED mode vanished.
        assertTrue("the key was destroyed on read", prefs.contains(KEY))
    }

    @Test
    fun `the flow agrees with the synchronous read`() {
        prefs.edit().putBoolean(KEY, true).apply()

        val devicePrefs = DevicePrefs(prefs)

        // The Activity reads one before the first frame and collects the other;
        // they must never disagree, or the app paints white and then repaints.
        assertEquals(devicePrefs.trueBlackNow(), devicePrefs.trueBlack.value)
        assertTrue(devicePrefs.trueBlack.value)
    }

    @Test
    fun `writing it touches nothing else in the file`() {
        prefs.edit()
            .putBoolean("orientation_locked", false)
            .putString("theme_mode", "Dark")
            .apply()

        val devicePrefs = DevicePrefs(prefs)
        devicePrefs.setTrueBlack(true)

        assertTrue(prefs.contains(KEY))
        assertFalse("the orientation lock was collateral damage", devicePrefs.orientationLockedNow())
        assertEquals(BtThemeMode.Dark, devicePrefs.themeModeNow())
    }

    @Test
    fun `a file that never had the key defaults to off and stays clean`() {
        val devicePrefs = DevicePrefs(prefs)

        assertFalse(devicePrefs.trueBlackNow())
        // Nothing is written just by reading — "never chose" stays never chose.
        assertFalse(prefs.contains(KEY))
    }

    @Test
    fun `the choice survives the next launch`() {
        val devicePrefs = DevicePrefs(prefs)

        devicePrefs.setTrueBlack(true)

        assertTrue(devicePrefs.trueBlack.value)
        assertTrue("setTrueBlack did not reach the disk", prefs.contains(KEY))
        assertTrue("the choice was lost on a cold start", DevicePrefs(prefs).trueBlackNow())
    }

    @Test
    fun `turning it back off persists too`() {
        prefs.edit().putBoolean(KEY, true).apply()
        val devicePrefs = DevicePrefs(prefs)

        devicePrefs.setTrueBlack(false)

        assertFalse(devicePrefs.trueBlack.value)
        assertFalse("the app came back black", DevicePrefs(prefs).trueBlackNow())
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
