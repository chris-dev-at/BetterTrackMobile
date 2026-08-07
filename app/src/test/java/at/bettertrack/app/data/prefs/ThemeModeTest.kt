package at.bettertrack.app.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decode rules for the persisted theme choice.
 *
 * Stored by enum NAME rather than ordinal, so the interesting cases are all
 * about a value this build did not write: a preference file from a build that
 * knew a mode this one does not, or a hand-edited/blank entry. None of them may
 * throw, and all of them must land on [BtThemeMode.System] — the mode that
 * defers to the device is the only safe thing to guess on someone's behalf.
 */
class ThemeModeTest {

    @Test
    fun `an absent preference is System`() {
        assertEquals(BtThemeMode.System, themeModeFromName(null))
    }

    @Test
    fun `every mode round-trips through its own name`() {
        BtThemeMode.entries.forEach { mode ->
            assertEquals(mode, themeModeFromName(mode.name))
        }
    }

    @Test
    fun `an unknown or malformed name degrades to System instead of throwing`() {
        listOf("", "   ", "AMOLED", "light", "DARK_MODE", "0", "System ")
            .forEach { assertEquals("for '$it'", BtThemeMode.System, themeModeFromName(it)) }
    }

    @Test
    fun `the enum stays three-valued`() {
        // True black is a boolean UNDER Dark, not a fourth mode — that is what
        // keeps every `when (BtThemeMode)` in the app exhaustive when it lands.
        assertEquals(3, BtThemeMode.entries.size)
        assertEquals(
            listOf("System", "Light", "Dark"),
            BtThemeMode.entries.map { it.name },
        )
    }
}
