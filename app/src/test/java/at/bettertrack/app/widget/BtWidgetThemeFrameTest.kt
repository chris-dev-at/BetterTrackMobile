package at.bettertrack.app.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import at.bettertrack.app.data.prefs.BtThemeMode
import at.bettertrack.app.data.prefs.themeModeFromName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import androidx.glance.color.ColorProvider as dayNightColorProvider

/**
 * **The "no wrongly-themed frame" guard** (owner defect 2026-08-18: *"wenn sich
 * die widgets aktualisieren zeigen sie kurz schwarz an obwohl ich white mode
 * angemacht habe"*).
 *
 * ## The defect
 *
 * `btProvideContent` painted its TRANSIENT frame — the loading / syncing card it
 * publishes while the load runs — with a hardcoded [BtThemeMode.System], on the
 * reasoning that reading the app's stored theme meant forcing `AppGraph`. Every
 * CONTENT frame in the package meanwhile used `btWidgetThemeMode()`, the app's
 * persisted preference.
 *
 * Those two resolve differently by design. `System` becomes a day/night pair,
 * which the LAUNCHER resolves against the SYSTEM's night mode; the stored
 * preference is a deliberate disagreement with the system. So on any phone where
 * the two differ, every frame published during a refresh flashed the opposite
 * theme — black on system-dark + app-light, white on the inverse. One defect,
 * two polarities.
 *
 * ## What this pins
 *
 * 1. The pure resolution itself: a forced mode is a fixed side, `System` is the
 *    pair. If that ever stops being true, the widget's whole theme story is
 *    wrong and every other test here is measuring the wrong thing.
 * 2. **Frame parity** — for one stored preference, the transient palette and
 *    the content palette are the same thirteen providers. This is the property
 *    the defect violated.
 * 3. A source guard, in this package's established style, that no frame anywhere
 *    in `widget/` goes back to painting with a literal [BtThemeMode].
 */
class BtWidgetThemeFrameTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    // ── 1. What a stored preference resolves to ───────────────────────────────

    // Compared against the two PUBLIC factories the production palette itself
    // uses, rather than against the provider classes: those carry
    // `@RestrictTo(LIBRARY)` members, and reaching for them here would put a
    // lint error in the test source to assert something the factories already
    // say (both provider types are data classes, so equality is structural).

    @Test
    fun `a forced Light frame paints the day side, with no pair for the host to reinterpret`() {
        BtGlanceColor.entries.forEach { token ->
            assertEquals(
                "${token.name} forced Light must be the fixed day value",
                ColorProvider(Color(token.day)),
                token.provider(BtThemeMode.Light),
            )
            // A day/night pair here would let the LAUNCHER paint dark on a dark
            // phone — exactly the disagreement a forced mode exists to express.
            assertNotEquals(
                "${token.name} forced Light handed the host a pair to reinterpret",
                dayNightColorProvider(day = Color(token.day), night = Color(token.night)),
                token.provider(BtThemeMode.Light),
            )
        }
    }

    @Test
    fun `a forced Dark frame paints the night side`() {
        BtGlanceColor.entries.forEach { token ->
            assertEquals(
                "${token.name} forced Dark must be the fixed night value",
                ColorProvider(Color(token.night)),
                token.provider(BtThemeMode.Dark),
            )
        }
    }

    @Test
    fun `following the system hands the host the pair, not a guess`() {
        BtGlanceColor.entries.forEach { token ->
            assertEquals(
                "${token.name} in System mode must be a day/night pair, so a mid-session system " +
                    "flip repaints with no widget update at all",
                dayNightColorProvider(day = Color(token.day), night = Color(token.night)),
                token.provider(BtThemeMode.System),
            )
            assertNotEquals(
                "${token.name} in System mode resolved to a fixed side",
                ColorProvider(Color(token.day)),
                token.provider(BtThemeMode.System),
            )
        }
    }

    @Test
    fun `a stored preference is decoded before it is painted`() {
        // The seam both frames now bottom out in. Garbage and absence mean
        // "never chose", which is the only case where deferring to the host is
        // the right answer.
        assertEquals(BtThemeMode.Light, themeModeFromName("Light"))
        assertEquals(BtThemeMode.Dark, themeModeFromName("Dark"))
        assertEquals(BtThemeMode.System, themeModeFromName("System"))
        assertEquals(BtThemeMode.System, themeModeFromName(null))
        assertEquals(BtThemeMode.System, themeModeFromName("Solarized"))
    }

    // ── 2. Frame parity: the property the defect violated ─────────────────────

    /** The thirteen providers a palette carries, in a comparable shape. */
    private fun palette(colors: BtGlanceColors) = listOf(
        "surface" to colors.surface,
        "border" to colors.border,
        "textPrimary" to colors.textPrimary,
        "textSecondary" to colors.textSecondary,
        "textMuted" to colors.textMuted,
        "gold" to colors.gold,
        "gain" to colors.gain,
        "loss" to colors.loss,
        "chip" to colors.chip,
        "gainWash" to colors.gainWash,
        "lossWash" to colors.lossWash,
        "goldWash" to colors.goldWash,
        "onGold" to colors.onGold,
    )

    @Test
    fun `one decoded mode gives exactly one palette, token for token`() {
        // Frame parity reduces to this: both frames call btGlanceColors with a
        // mode decoded from the same stored string, and btGlanceColors is a pure
        // function of that mode straight off the token table. So if the two
        // frames agree on the MODE they cannot disagree on a colour — which is
        // what the flash proved they were doing, because the transient frame was
        // not decoding the stored string at all.
        listOf(null, "System", "Light", "Dark", "nonsense").forEach { stored ->
            val mode = themeModeFromName(stored)
            val expected = listOf(
                "surface" to BtGlanceColor.Surface,
                "border" to BtGlanceColor.Border,
                "textPrimary" to BtGlanceColor.TextPrimary,
                "textSecondary" to BtGlanceColor.TextSecondary,
                "textMuted" to BtGlanceColor.TextMuted,
                "gold" to BtGlanceColor.Gold,
                "gain" to BtGlanceColor.Gain,
                "loss" to BtGlanceColor.Loss,
                "chip" to BtGlanceColor.Chip,
                "gainWash" to BtGlanceColor.GainWash,
                "lossWash" to BtGlanceColor.LossWash,
                "goldWash" to BtGlanceColor.GoldWash,
                "onGold" to BtGlanceColor.OnGold,
            ).map { (name, token) -> name to token.provider(mode) }
            assertEquals("stored=$stored", expected, palette(btGlanceColors(mode)))
        }
    }

    @Test
    fun `the widget reads the same theme bytes DevicePrefs writes`() {
        // The claim the cheap read rests on: btWidgetStoredThemeMode does not
        // ask DevicePrefs, it reads DevicePrefs' FILE. That is only sound while
        // DevicePrefs seeds its own StateFlow from the same key with the same
        // decoder and persists every change to it — if it ever cached the theme
        // elsewhere, the widget would paint a value the app had moved on from.
        val prefs = projectFile(
            "src/main/java/at/bettertrack/app/data/prefs/DevicePrefs.kt",
        ).readText()
        assertTrue(
            "DevicePrefs no longer seeds its theme from themeModeFromName(KEY_THEME_MODE)",
            prefs.contains("themeModeFromName(prefs.getString(KEY_THEME_MODE, null))"),
        )
        assertTrue(
            "DevicePrefs.setThemeMode no longer persists to KEY_THEME_MODE",
            prefs.contains("putString(KEY_THEME_MODE, mode.name)"),
        )
        assertTrue(
            "DevicePrefs.PREFS / KEY_THEME_MODE must stay visible to the widget package",
            prefs.contains("internal companion object"),
        )
    }

    @Test
    fun `a forced palette really differs from the system pair`() {
        // Guards the test above from being vacuous: if every mode produced the
        // same providers, parity would prove nothing and the original defect
        // would have been invisible.
        val light = palette(btGlanceColors(BtThemeMode.Light))
        val dark = palette(btGlanceColors(BtThemeMode.Dark))
        val system = palette(btGlanceColors(BtThemeMode.System))
        // OnGold is deliberately the same on both sides (ink on the brand gold),
        // so it is excluded from the "these are different" claim.
        val comparable = { list: List<Pair<String, Any>> -> list.filter { it.first != "onGold" } }
        assertTrue("light and dark palettes are identical", comparable(light) != comparable(dark))
        assertTrue("light and system palettes are identical", comparable(light) != comparable(system))
        assertTrue("dark and system palettes are identical", comparable(dark) != comparable(system))
    }

    // ── 3. No frame may hardcode a theme again ────────────────────────────────

    private fun widgetSources(): List<File> =
        projectFile("src/main/java/at/bettertrack/app/widget")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `no widget frame paints with a literal theme mode`() {
        // `btGlanceColors(BtThemeMode.System)` in btProvideContent WAS the bug.
        // Every palette in this package must come from the app's persisted
        // preference — btWidgetThemeMode / btWidgetStoredThemeMode — never from
        // a constant, because a constant cannot know what the user chose.
        val call = Regex("""btGlanceColors\(\s*BtThemeMode\.[A-Za-z]+""")
        val offenders = widgetSources().filter { call.containsMatchIn(it.readText()) }
        assertTrue(
            "these paint a frame with a hardcoded theme instead of the stored one: " +
                offenders.map { it.name },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the transient frame reads the stored theme without forcing the graph`() {
        val source = projectFile(
            "src/main/java/at/bettertrack/app/widget/BtWidgets.kt",
        ).readText()
        // The cheap read exists…
        assertTrue(
            "btWidgetStoredThemeMode is gone; the transient frame has no way to know the theme",
            source.contains("internal fun btWidgetStoredThemeMode(context: Context)"),
        )
        // …it reads the real DevicePrefs key rather than a second copy of the
        // strings that could drift out from under it…
        assertTrue(
            "the stored-theme read must use DevicePrefs.PREFS / DevicePrefs.KEY_THEME_MODE",
            source.contains("DevicePrefs.PREFS") && source.contains("DevicePrefs.KEY_THEME_MODE"),
        )
        // …and it is what btProvideContent paints its transient card with.
        assertTrue(
            "btProvideContent no longer resolves its chrome from the stored theme",
            source.contains("btGlanceColors(btWidgetStoredThemeMode(context))"),
        )
        // The point of the cheap read: the transient frame must not touch the
        // graph. AppGraph may appear in this file (btWidgetThemeMode prefers it
        // when it is already up) but never inside the stored read.
        val storedRead = Regex(
            """internal fun btWidgetStoredThemeMode\(context: Context\).*?\n}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(source)?.value ?: error("could not read btWidgetStoredThemeMode")
        assertTrue(
            "btWidgetStoredThemeMode touches AppGraph — that is the slow thing it exists to avoid",
            !storedRead.contains("AppGraph"),
        )
    }
}
