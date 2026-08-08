package at.bettertrack.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the defect that made the component gallery unscrollable
 * (owner report, device pass 2026-08-08).
 *
 * ## The bug this remembers
 *
 * `BtCollapsingHeader` wraps M3's `LargeTopAppBar`, and `TwoRowsTopAppBar` hangs a
 * vertical `Modifier.draggable` on the entire bar whenever the `TopAppBarScrollBehavior`
 * it is handed reports `isPinned == false` — that is how a user resizes the bar by
 * dragging the bar. The modifier is a plain `draggable`: it does **not** participate
 * in nested scroll, so whatever it receives is simply gone.
 *
 * Above a list that is the intended affordance. *Inside* one it is a dead zone the
 * exact size of the bar. The gallery renders four specimen headers as `LazyColumn`
 * content, 112–132dp each, which between them covered nearly a whole sheet viewport
 * — so once that section scrolled into view, every mid-screen swipe was swallowed
 * and the gallery looked frozen after one gesture.
 *
 * ## Why a source scan and not a UI test
 *
 * The property is a rule about *call sites*, the same shape as
 * [at.bettertrack.app.ui.shell.TopBarNavigationTest]'s two invariants, and it is
 * cheap to state exactly: a collapsing bar belongs in a screen's `topBar` slot, and
 * anything that renders one somewhere else has to say so by passing a pinned
 * behaviour. A UI test would have to reproduce a gesture landing on a specific
 * composable at a specific scroll offset to catch the same regression.
 */
class CollapsingHeaderScrollGuardTest {

    private fun uiSources(): List<File> {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Every place the component is actually rendered, as `file:line`. */
    private fun callSites(): List<Triple<File, Int, List<String>>> =
        uiSources()
            // The component's own file defines it and documents it; it never calls it.
            .filter { it.name != "BtCollapsingHeader.kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.mapIndexedNotNull { i, line ->
                    if (line.contains("BtCollapsingHeader(")) Triple(file, i, lines) else null
                }
            }

    /** Guards against the scan silently matching nothing after a rename. */
    @Test
    fun `the scan finds the app's collapsing headers`() {
        assertTrue(
            "expected the app's ~23 BtCollapsingHeader call sites, found ${callSites().size}",
            callSites().size >= 20,
        )
    }

    /**
     * A collapsing bar is a `topBar`, or it is pinned. No third option.
     *
     * "Is a topBar" is read off the nearest preceding non-blank line, which is
     * `topBar = {` at all 19 screen call sites and is the shape ktlint enforces.
     * A call site that is NOT in that slot is rendered inside something else — in
     * practice a scrollable — and must hand the bar a pinned behaviour so M3 leaves
     * the vertical gesture alone. The pinned behaviour may be inlined in the
     * argument or hoisted into a local a few lines above (the gallery's forced-
     * collapse specimen does the latter), so a window around the call is searched
     * rather than the one line.
     */
    @Test
    fun `a collapsing header is a topBar or is pinned`() {
        val offenders = callSites().filter { (_, at, lines) ->
            val precededByTopBar = lines.take(at)
                .lastOrNull { it.isNotBlank() }
                ?.trim() == "topBar = {"
            if (precededByTopBar) return@filter false
            val window = lines.subList(
                (at - WINDOW_BEFORE).coerceAtLeast(0),
                (at + WINDOW_AFTER).coerceAtMost(lines.size),
            )
            window.none { it.contains("rememberBtPinnedHeaderBehavior") }
        }
        assertTrue(
            "BtCollapsingHeader rendered outside a topBar slot without a pinned " +
                "behaviour — M3 will hang a vertical draggable on it and eat the " +
                "surrounding scroll (see this class's KDoc): " +
                offenders.joinToString { (f, at, _) -> "${f.name}:${at + 1}" },
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** Enough to reach a behaviour hoisted into a local above the call. */
        const val WINDOW_BEFORE = 8

        /** Enough to reach the `scrollBehavior =` argument inside the call. */
        const val WINDOW_AFTER = 14
    }
}
