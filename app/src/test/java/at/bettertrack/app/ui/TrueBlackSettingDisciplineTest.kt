package at.bettertrack.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OLED true black, reinstated by the owner on 2026-08-17: *"also the oled dark
 * mode dissapeared."*
 *
 * It vanished twice over, which is why it is worth a guard rather than a
 * comment. First the ROW was deleted from Settings → Appearance for web parity;
 * then `DevicePrefs` began deleting the stored key on every read, so even the
 * people who had already turned it on lost it. Both are undone, and both are
 * pinned here: a setting that has already disappeared once should not be able to
 * do it quietly a second time.
 *
 * The parity reasoning is recorded rather than re-litigated — the web has no
 * OLED setting because a browser tab has no panel, so it is not a surface this
 * one can be at parity with.
 */
class TrueBlackSettingDisciplineTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private fun res(qualifier: String): String {
        val name = "src/main/res/values$qualifier/strings.xml"
        val candidates = listOf(File(name), File("app/$name"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("strings.xml not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private val settings = "at/bettertrack/app/ui/settings/SettingsScreen.kt"
    private val prefs = "at/bettertrack/app/data/prefs/DevicePrefs.kt"

    @Test
    fun `the row is in Settings and drives the real preference`() {
        val screen = source(settings)
        assertTrue(
            "the True black row is gone from Settings again",
            screen.contains("R.string.bt_settings_true_black"),
        )
        assertTrue(
            "the row no longer writes the preference",
            screen.contains("AppGraph.devicePrefs.setTrueBlack("),
        )
        assertTrue(
            "the row no longer reads the preference",
            screen.contains("AppGraph.devicePrefs.trueBlack"),
        )
    }

    @Test
    fun `it sits under Theme, where a sub-setting of the theme belongs`() {
        val screen = source(settings)
        val theme = screen.indexOf("R.string.bt_settings_theme")
        val trueBlack = screen.indexOf("R.string.bt_settings_true_black")
        val language = screen.indexOf("R.string.bt_dest_settings_language")
        assertTrue("Theme row missing", theme >= 0)
        assertTrue("True black must follow Theme", theme < trueBlack)
        assertTrue("True black must stay above Language", trueBlack < language)
    }

    @Test
    fun `the preference persists again — no healing, no session-only write`() {
        val devicePrefs = source(prefs)
        assertTrue(
            "the stranding-heal is back; it deletes the user's choice on read",
            !devicePrefs.contains("healStrandedTrueBlack"),
        )
        assertTrue(
            "setTrueBlack no longer writes to disk — the choice would not survive a cold start",
            devicePrefs.contains("putBoolean(KEY_TRUE_BLACK"),
        )
        assertTrue(
            "the flag is no longer read back from disk at construction",
            devicePrefs.contains("prefs.getBoolean(KEY_TRUE_BLACK"),
        )
    }

    @Test
    fun `the row says why it cannot bite in light mode, in both languages`() {
        // Greyed with a reason, not hidden: "where did my OLED setting go" is
        // the exact report this row is back to answer.
        assertTrue(
            "the row is not gated on the resolved palette",
            source(settings).contains("BtTheme.colors.isLight"),
        )
        listOf("" to "EN", "-de" to "DE").forEach { (qualifier, label) ->
            assertTrue(
                "$label: the light-mode hint string is missing",
                res(qualifier).contains("""name="bt_settings_true_black_light_hint""""),
            )
        }
    }
}
