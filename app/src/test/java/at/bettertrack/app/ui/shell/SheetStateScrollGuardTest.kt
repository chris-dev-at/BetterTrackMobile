package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard: **no sheet state may be a gesture dead-end.**
 *
 * ## The bug, and why a test is the only thing that keeps it fixed
 *
 * Every subpage is a full-screen [BtSheet], and a sheet's pull-down dismiss is
 * taken from `onPostScroll` — from whatever the *content's* scroll container did
 * not consume. That is the correct way to arm the gesture at scroll top only, and
 * it has one consequence that is invisible while things are working: **a state
 * with no scroll container dispatches no nested scroll at all**, so the sheet's
 * connection is never called and the pull-down does nothing.
 *
 * Loaded content is always a `LazyColumn`, so the gesture worked in every state
 * anyone thought to look at. But [at.bettertrack.app.ui.components.BtEmptyState],
 * `BtErrorState` and `BtOfflineState` all render through a plain non-scrollable
 * `Column`, and the screens' skeletons were plain `Column`s too — so every
 * subpage was a dead-end while loading, and again on any surface that reported a
 * failure. Online those states last a few hundred milliseconds. Offline a screen
 * does not pass through them, it **sits** in them, which is why the owner met the
 * same defect from two directions: *"in offline mode it still doesn't work"* and
 * *"if pages are still loading … you can't scroll while the skeleton loader is
 * showing."*
 *
 * The cure was two shared containers ([at.bettertrack.app.ui.components.BtStateFill]
 * and `BtScrollFill`). The cure does not stay applied on its own: the next empty
 * state anyone writes will be a centred `Column`, because that is what an empty
 * state looks like. So the rule is checked mechanically, the way
 * [at.bettertrack.app.ui.theme.BtThemeDisciplineTest] and
 * [at.bettertrack.app.i18n.StringParityTest] check theirs — by reading the
 * sources.
 *
 * ## The rule
 *
 * In a screen file reachable as a sheet, a `BtEmptyState` / `BtErrorState` /
 * `BtOfflineState` call may not carry `Modifier.fillMaxSize()` or
 * `Modifier.align(Alignment.Center)`. Both are the signature of a state placed
 * directly into a `Box`/`Column` that fills the screen — i.e. exactly the
 * dead-end shape. Inside `BtStateFill` neither is needed (the container fills and
 * centres), and inside a `LazyColumn` item neither is correct.
 *
 * This is a shape check, not a proof: it cannot see a hand-rolled skeleton that
 * forgot a container. It catches the form the regression actually takes, which is
 * what a guard is for.
 */
class SheetStateScrollGuardTest {

    /**
     * Screens whose state surfaces are allowed to fill by hand.
     *
     * Every entry is a deliberate, explained decision rather than a quiet regex
     * loosening — adding one has to be a visible edit.
     */
    private val exemptions = mapOf<String, String>()

    /** The state composables whose placement this rule governs. */
    private val stateCalls = listOf("BtEmptyState(", "BtErrorState(", "BtOfflineState(")

    /** The two modifier forms that mark a state as filling a non-scrolling parent. */
    private val deadEndModifiers = listOf("fillMaxSize()", "align(Alignment.Center)")

    private fun uiRoot(): File = listOf(
        File("src/main/java/at/bettertrack/app/ui"),
        File("app/src/main/java/at/bettertrack/app/ui"),
    ).firstOrNull { it.isDirectory } ?: error("ui sources not found")

    /** Strip `//` comments and KDoc bodies so prose about the rule never trips it. */
    private fun code(source: String): String {
        val noBlocks = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return noBlocks.lines().joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * The argument list of one call to [name] starting at [from], by brace/paren
     * depth — a regex cannot find the matching close paren through nested calls
     * like `action = { BtSecondaryButton(...) }`.
     */
    private fun argumentsOf(source: String, from: Int): String {
        var depth = 0
        var i = source.indexOf('(', from)
        val start = i
        while (i in source.indices) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    @Test
    fun `no sheet state surface fills a non-scrolling parent by hand`() {
        val root = uiRoot()
        val offenders = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (exemptions.keys.any { rel.endsWith(it) }) return@forEach
                val source = code(file.readText())
                stateCalls.forEach { call ->
                    var at = source.indexOf(call)
                    while (at >= 0) {
                        val args = argumentsOf(source, at)
                        deadEndModifiers.forEach { bad ->
                            if (args.contains(bad)) {
                                offenders += "$rel: ${call.dropLast(1)} carries $bad"
                            }
                        }
                        at = source.indexOf(call, at + call.length)
                    }
                }
            }

        assertTrue(
            "A sheet state that fills its parent by hand is a state the sheet " +
                "cannot be pulled closed from. Wrap it in BtStateFill instead " +
                "(or place it in a LazyColumn item), and drop the modifier:\n" +
                offenders.joinToString("\n").prependIndent("  "),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the shared containers are the only scroll wrappers left`() {
        // The idiom existed twice privately before it existed once publicly
        // (`EmptyFill` in TransactionsScreen, `HoldingStateFill` in
        // HoldingDetailScreen). Both are gone; this stops a third from growing.
        val root = uiRoot()
        val privateCopies = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { code(it.readText()).contains("fillParentMaxSize()") }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .toList()

        assertEquals(
            "fillParentMaxSize is the shared containers' own mechanism — a copy " +
                "elsewhere is a private re-implementation of BtStateFill",
            listOf("components/BtStates.kt"),
            privateCopies,
        )
    }

    private fun sheetStackSource(): String {
        val file = listOf(
            File("src/main/java/at/bettertrack/app/ui/shell/BtSheetStack.kt"),
            File("app/src/main/java/at/bettertrack/app/ui/shell/BtSheetStack.kt"),
        ).firstOrNull { it.isFile } ?: error("BtSheetStack.kt not found")
        return code(file.readText())
    }

    @Test
    fun `the sheet chrome does not ride the depth axis`() {
        // Owner's rule for depth->=2: only the PAGES slide; the grabber strip
        // stays put, the way the four main pages' top bar stays put while the
        // pages move under it. Structurally: the grabber is composed before the
        // planes in the sheet's Column, and the horizontal translation lives
        // inside BtSheetPlanes — never around the chrome. A single misplaced
        // brace would slide the grabber with the content, and nothing else in the
        // suite would notice.
        val source = sheetStackSource()
        val grabber = source.indexOf("BtSheetGrabber(")
        val planes = source.indexOf("BtSheetPlanes(")
        val planesFun = source.indexOf("private fun BtSheetPlanes(")
        val slideAt = source.indexOf("translationX =")

        assertTrue("the grabber must be composed", grabber > 0)
        assertTrue("the planes must be composed", planes > 0)
        assertTrue("the grabber must come first in the sheet's Column", grabber < planes)
        assertTrue(
            "the depth axis must live inside BtSheetPlanes, below the chrome",
            slideAt > planesFun,
        )
        assertEquals(
            "exactly one thing may move on the depth axis",
            1,
            Regex("translationX =").findAll(source).count(),
        )
    }

    @Test
    fun `both live pages are composed, always`() {
        // The fix for "goes blank": the container renders the top TWO entries, at
        // ONE call site, keyed by page identity so a push or pop MOVES a page's
        // composition between the planes instead of tearing it down. Any of the
        // three going missing is the old bug back.
        val source = sheetStackSource()
        assertTrue("the top two entries must be rendered", source.contains("pages.takeLast(2)"))
        assertTrue("pages must be keyed by identity", source.contains("key(page.key)"))
        assertTrue(
            "the parent plane must be drawn, not left blank",
            source.contains("page.content()"),
        )
    }

    @Test
    fun `the graph contributes no motion of its own`() {
        // Depth transitions used to ride the NavHost, which is what made a
        // back-swipe reveal nothing and the returning page arrive with a vertical
        // scale. Every transition slot must stay None: the sheet layer owns the
        // whole travel, because a drag and a transition cannot share the job.
        val shell = listOf(
            File("src/main/java/at/bettertrack/app/ui/shell/AppShell.kt"),
            File("app/src/main/java/at/bettertrack/app/ui/shell/AppShell.kt"),
        ).firstOrNull { it.isFile } ?: error("AppShell not found")
        val source = code(shell.readText())
        listOf("enterTransition", "exitTransition", "popEnterTransition", "popExitTransition")
            .forEach { slot ->
                val at = source.indexOf("$slot = {")
                assertTrue("$slot must be declared on the NavHost", at > 0)
                val value = source.substring(at, source.indexOf("}", at))
                assertTrue(
                    "$slot must contribute no motion, was: ${value.trim()}",
                    value.contains("Transition.None"),
                )
            }
    }

    @Test
    fun `every sheet route resolves to a screen this rule can see`() {
        // Guards rot silently when the thing they scan moves. If the sheet
        // registrations ever leave AppShell, this fails rather than passing
        // vacuously.
        val shell = listOf(
            File("src/main/java/at/bettertrack/app/ui/shell/AppShell.kt"),
            File("app/src/main/java/at/bettertrack/app/ui/shell/AppShell.kt"),
        ).firstOrNull { it.isFile } ?: error("AppShell not found")
        val routes = Regex("btSheet<").findAll(shell.readText()).count()
        assertTrue("expected the sheet routes to still live in AppShell", routes > 40)
    }
}
