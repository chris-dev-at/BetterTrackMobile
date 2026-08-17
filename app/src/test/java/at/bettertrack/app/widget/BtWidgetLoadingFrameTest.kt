package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The one layout every `appwidget-provider` must name as its `initialLayout`. */
internal const val BT_WIDGET_LOADING_LAYOUT = "bt_widget_loading"

/**
 * **The "never a white void" guard.**
 *
 * ## The defect
 *
 * The owner's budget widget for the tag "Essen" rendered as a large empty WHITE
 * rectangle on his home screen (2026-08-17) while the app was themed dark. The
 * blank surface was never one of our composables — every Glance branch paints a
 * `BtWidgetCard`. It was the HOST's `android:initialLayout`, which is what a
 * launcher inflates from the moment an instance is placed until Glance publishes
 * its first `RemoteViews`. Every provider pointed at glance-appwidget's
 * `glance_default_loading_layout`, whose background is
 * `?android:attr/colorBackground` — an attribute resolved in the LAUNCHER's
 * theme, i.e. white on a light system. And because `provideGlance` did all of
 * its I/O (Room, tokens, vault, the per-instance config read) BEFORE
 * `provideContent`, any instance that stalled or threw on that path never
 * published a frame at all, and kept the white rectangle permanently.
 *
 * ## What this file pins
 *
 * Two independent halves of the fix, because either one alone still leaves a
 * hole:
 *
 *  1. **The layout** — it exists, it paints OUR surface (both sides, day and
 *     night), it takes the launcher's own corner radius on API 31+, and it
 *     carries the house loading affordance rather than being an empty frame.
 *     [BtWidgetManifestTest] pins that every provider points at it.
 *  2. **The lifecycle** — no `provideGlance` may do its loading ahead of
 *     `provideContent` again. Every widget goes through
 *     [btProvideContent], which publishes a painted frame first and swaps in
 *     content when the load returns.
 *
 * Source- and resource-scanning like [BtWidgetPaletteMirrorTest] and
 * [BtWidgetManifestTest]: there is no Robolectric host here, and the thing being
 * checked is a wiring decision, not a runtime value.
 */
class BtWidgetLoadingFrameTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun layout(): String =
        projectFile("src/main/res/layout/$BT_WIDGET_LOADING_LAYOUT.xml").readText()

    private fun colors(qualifier: String = ""): Map<String, Long> =
        Regex("""<color name="([^"]+)">#([0-9A-Fa-f]{8})</color>""")
            .findAll(projectFile("src/main/res/values$qualifier/colors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].uppercase(Locale.ROOT).toLong(16) }

    private fun hex(argb: Long): String = String.format(Locale.ROOT, "#%08X", argb)

    /** The `@color/...` a shape drawable fills itself with. */
    private fun solidOf(drawable: String): String? =
        Regex("""<solid\s+android:color="@color/([A-Za-z0-9_]+)"\s*/>""")
            .find(projectFile("src/main/res/$drawable").readText())
            ?.groupValues?.get(1)

    // ── 1. The layout ─────────────────────────────────────────────────────────

    @Test
    fun `the loading layout paints the widget's own surface, both sides`() {
        // The whole point: a colour resource with a values-night twin instead of
        // an attribute resolved in someone else's theme. If this ever resolves to
        // white on a light system again, that IS the bug.
        val background = Regex("""android:background="@drawable/([A-Za-z0-9_]+)"""")
            .find(layout())?.groupValues?.get(1)
        assertEquals(
            "the loading layout must paint a BetterTrack shape, not a theme attribute",
            "bt_widget_loading_bg",
            background,
        )

        val day = solidOf("drawable/$background.xml")
        val night = solidOf("drawable-v31/$background.xml")
        assertEquals("bt_widget_surface", day)
        assertEquals("the API 31+ twin must fill with the same token", day, night)

        // …and that token is the widget card colour itself, on both sides.
        assertEquals(
            "loading card (day) must equal BtGlanceColor.Surface.day",
            hex(BtGlanceColor.Surface.day),
            hex(colors().getValue("bt_widget_surface")),
        )
        assertEquals(
            "loading card (night) must equal BtGlanceColor.Surface.night",
            hex(BtGlanceColor.Surface.night),
            hex(colors("-night").getValue("bt_widget_surface")),
        )
        // Day and night really are different values — a single-valued token here
        // would be the white-void defect wearing a resource name.
        assertTrue(
            "the loading card does not change with the theme",
            colors().getValue("bt_widget_surface") != colors("-night").getValue("bt_widget_surface"),
        )
    }

    @Test
    fun `the loading card rounds like every other widget on the screen`() {
        // btWidgetCardModifier gives the live card @android:dimen/
        // system_app_widget_background_radius on API 31+; the pre-first-frame
        // card has to agree or the hand-off visibly pops.
        assertTrue(
            "drawable-v31/bt_widget_loading_bg.xml must use the system widget radius",
            projectFile("src/main/res/drawable-v31/bt_widget_loading_bg.xml").readText()
                .contains("@android:dimen/system_app_widget_background_radius"),
        )
        // Below 31 the dimension does not exist, so the base drawable may not
        // reference it — that would fail to compile the resource on old devices.
        assertTrue(
            "the base drawable must not reference an API 31 dimension",
            !projectFile("src/main/res/drawable/bt_widget_loading_bg.xml").readText()
                .contains("system_app_widget_background_radius"),
        )
        assertTrue(
            "the loading layout must tag itself @android:id/background so the " +
                "launcher can round and animate it",
            layout().contains("""android:id="@android:id/background""""),
        )
    }

    @Test
    fun `the loading card is designed, not an empty frame`() {
        // The house affordance BtWidgetMessage draws in Glance: the gold dot over
        // one short muted line. A painted-but-blank card would fix the colour and
        // keep the "is this broken?" reading.
        val text = layout()
        assertTrue("no gold dot", text.contains("@color/bt_widget_gold"))
        assertTrue("no muted ink", text.contains("@color/bt_widget_text_muted"))
        assertTrue("no loading line", text.contains("@string/bt_widget_loading"))
        assertTrue("the dot glyph is missing", text.contains("@string/bt_widget_preview_dot"))
        assertTrue("the content is not centred", text.contains("""android:gravity="center""""))
    }

    @Test
    fun `the loading line is translated, in both languages`() {
        fun value(qualifier: String): String? =
            Regex("""<string name="bt_widget_loading">(.*?)</string>""")
                .find(projectFile("src/main/res/values$qualifier/strings.xml").readText())
                ?.groupValues?.get(1)
        val en = value("")
        val de = value("-de")
        assertEquals("Loading…", en)
        assertEquals("Wird geladen…", de)
        assertTrue("the German line was never translated", en != de)
    }

    // ── 2. The lifecycle ──────────────────────────────────────────────────────

    private fun widgetSources(): List<File> =
        projectFile("src/main/java/at/bettertrack/app/widget")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** `class Foo : GlanceAppWidget()` → the file it is declared in. */
    private fun widgetClasses(): List<Pair<String, File>> = widgetSources().flatMap { file ->
        Regex("""class\s+([A-Za-z0-9_]+)\s*:\s*GlanceAppWidget\(\)""")
            .findAll(file.readText())
            .map { it.groupValues[1] to file }
            .toList()
    }

    @Test
    fun `the scan can see the widget classes it is checking`() {
        assertEquals(
            "expected the nine Glance widgets, found " + widgetClasses().map { it.first },
            9,
            widgetClasses().size,
        )
    }

    @Test
    fun `no widget loads before it has published a frame`() {
        // The regression that produced the white void: work on the path to the
        // first frame. Every provideGlance must hand off to btProvideContent
        // immediately — which calls provideContent first and loads after — so a
        // bare provideContent (or, worse, a load ahead of one) fails here.
        val body = Regex(
            """override suspend fun provideGlance\([^)]*\)\s*\{(.*?)\n {4}\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val offenders = widgetClasses().mapNotNull { (name, file) ->
            val source = file.readText()
            val match = body.find(source)
                ?: return@mapNotNull "$name: could not read its provideGlance body"
            val statements = match.groupValues[1].trim()
            when {
                !statements.startsWith("btProvideContent(") -> "$name: provideGlance does not " +
                    "open with btProvideContent — anything before the first frame is time the " +
                    "launcher spends on the initialLayout, and a throw there means no frame ever"

                statements.contains("provideContent {") -> "$name: still calls provideContent " +
                    "directly; go through btProvideContent so the loading card is published first"

                else -> null
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the head start the load gets is a bound, not a budget`() {
        // btProvideContent lets the load try to beat the first frame so a
        // routine refresh does not blink "Wird geladen…" over good figures. That
        // grace is also the WORST CASE for how long an instance can sit on the
        // host's initialLayout, so it may never grow into a second white-void
        // window. A generous quarter-second is the ceiling this pins.
        assertTrue(
            "the first-frame grace must stay sub-second; it is $BT_WIDGET_FIRST_FRAME_GRACE_MS ms",
            BT_WIDGET_FIRST_FRAME_GRACE_MS in 1..500,
        )
    }

    @Test
    fun `the per-instance config read cannot cost a widget its frame`() {
        // getAppWidgetState and the pinning claims are the un-guarded throws the
        // 2026-08-17 diagnosis named. Inside btProvideContent's load they are
        // already caught, but a bare call would still lose the CONTENT frame and
        // leave the card stuck on "syncing" — btWidgetConfigOrNull degrades it to
        // the unconfigured reading instead, which the user can long-press to fix.
        val offenders = widgetClasses().mapNotNull { (name, file) ->
            val source = file.readText()
            if (!source.contains("getAppWidgetState(")) return@mapNotNull null
            if (source.contains("btWidgetConfigOrNull(")) null
            else "$name: reads getAppWidgetState without btWidgetConfigOrNull"
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}
