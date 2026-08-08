package at.bettertrack.app.ui.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The occlusion rule, and the wiring that makes it a real raster skip.
 *
 * Measured cost of getting this wrong (2026-08-09, 120Hz panel): 10.98ms of
 * RenderThread+GPU per sheet-drag frame against the tab pager's 3.91ms, entirely
 * from drawing a pager, a bottom bar and a header underneath an opaque sheet.
 */
class BtOcclusionTest {

    // The test phone's geometry: 420dpi, 31dp status bar, 8dp gap, 28dp corner.
    private val topEdge = 81f + 8f * 2.625f
    private val corner = 28f * 2.625f

    @Test
    fun `a settled sheet exposes only its top edge and its corner radius`() {
        assertEquals(topEdge + corner, sheetExposedTopPx(0f, topEdge, corner), 1e-4f)
    }

    @Test
    fun `the exposed strip clears the corners, or the curves would show a hole`() {
        // The clip line has to sit BELOW the rounded corners: between the sheet's
        // top edge and `edge + radius` the backdrop is visible beside the curve.
        assertTrue(sheetExposedTopPx(0f, topEdge, corner) >= topEdge + corner)
    }

    @Test
    fun `any travel at all brings the pages straight back`() {
        // Not "mostly settled", not a threshold: the first pixel of a dismiss pull
        // uncovers the pages, and `travel` is written synchronously by the finger,
        // so they are back in the display list on the same frame the sheet moves.
        listOf(0.0001f, 0.01f, 0.12f, 0.5f, 1f).forEach { travel ->
            assertEquals(
                "travel=$travel must draw the pages",
                NOT_COVERED,
                sheetExposedTopPx(travel, topEdge, corner),
                0f,
            )
        }
    }

    @Test
    fun `a closed stack draws everything`() {
        // At depth 0 the layer parks travel at 1f, which is the same answer.
        assertEquals(NOT_COVERED, sheetExposedTopPx(1f, topEdge, corner), 0f)
    }

    // ── The wiring. A correct rule read from the wrong phase buys nothing. ──

    private fun shellSource(name: String): String {
        val file = listOf(
            File("src/main/java/at/bettertrack/app/ui/shell/$name"),
            File("app/src/main/java/at/bettertrack/app/ui/shell/$name"),
        ).firstOrNull { it.isFile } ?: error("$name not found")
        val text = file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return text.lines().joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * The whole of one call to [name] — its argument list AND its trailing
     * lambda, which is where a Compose call keeps its children.
     */
    private fun argumentsOf(source: String, name: String): String {
        val start = source.indexOf('(', source.indexOf(name))
        var i = start
        var depth = 0
        while (i < source.length) {
            when (source[i]) {
                '(', '{' -> depth++
                ')', '}' -> if (--depth == 0) {
                    var j = i + 1
                    while (j < source.length && source[j].isWhitespace()) j++
                    if (j < source.length && source[j] == '{') {
                        i = j
                        continue
                    }
                    return source.substring(start, i + 1)
                }
            }
            i++
        }
        error("unbalanced call to $name")
    }

    @Test
    fun `the pager subtree is inside the clipped draw, not beside it`() {
        // The whole point: the tab pager is a CHILD of the node whose draw the
        // occlusion clip governs. Move BtTabPager out of this Scaffold and the
        // cull silently stops culling the most expensive thing on the screen.
        val shell = argumentsOf(shellSource("AppShell.kt"), "Scaffold(")
        assertTrue(
            "the Scaffold no longer carries the occlusion clip",
            shell.contains("drawWithContent") && shell.contains("occlusion.exposedTopPx()"),
        )
        assertTrue(
            "BtTabPager left the clipped subtree — it would be rasterised under " +
                "every settled sheet again",
            shell.contains("BtTabPager("),
        )
        assertTrue("the clip is a clipRect, not an alpha", shell.contains("clipRect(bottom ="))
    }

    @Test
    fun `the shell reads coverage in the draw phase only`() {
        // One read, inside the draw lambda. A second read from composition would
        // recompose all four tab pages on every frame of a drag.
        val shell = shellSource("AppShell.kt")
        assertEquals(
            "occlusion coverage must be read exactly once, in the draw lambda",
            1,
            Regex("exposedTopPx\\(\\)").findAll(shell).count(),
        )
        assertTrue(
            "the read must sit inside Modifier.drawWithContent",
            shell.indexOf("Modifier.drawWithContent {") in
                0 until shell.indexOf("occlusion.exposedTopPx()"),
        )
    }

    @Test
    fun `the sheet layer publishes coverage through derivedStateOf`() {
        // derivedStateOf is what makes the draw-phase read cheap: it notifies only
        // when coverage FLIPS, so the ~120 travel writes a drag makes every second
        // re-record nothing. Read `travel.value` straight into the probe and every
        // drag frame would re-record the shell's entire display list.
        val stack = shellSource("BtSheetStack.kt")
        assertTrue(
            "sheetExposedTopPx must be wrapped in derivedStateOf for the probe",
            Regex("derivedStateOf\\s*\\{\\s*sheetExposedTopPx\\(").containsMatchIn(stack),
        )
        assertTrue(
            "the sheet layer must install and clear its probe",
            stack.contains("occlusion?.probe = { exposed.value }") &&
                stack.contains("onDispose { occlusion?.probe = null }"),
        )
    }
}
