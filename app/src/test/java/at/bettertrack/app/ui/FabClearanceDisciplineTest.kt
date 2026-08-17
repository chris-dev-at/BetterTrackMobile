package at.bettertrack.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How a FAB screen clears its floating button — and, just as importantly, how it
 * must NOT.
 *
 * ## The rule
 *
 * Bottom clearance for a FAB is **`contentPadding` on the scrolling list**, and
 * nothing else. The content scrolls freely underneath the button; the button
 * shrinks to a 40dp mini while the finger is moving and comes back at rest (see
 * [at.bettertrack.app.ui.components.BtFabVisibility]).
 *
 * ## The rejected alternative — do not re-apply it (owner, 2026-08-17)
 *
 * A floating FAB may overlap list content. An earlier round tried to make that
 * impossible by shrinking the scroll VIEWPORT — `Modifier.padding(bottom = …)`
 * on the `fillMaxSize()` scroll container, with a small `contentPadding` gap
 * behind it. The owner saw it on his phone and rejected it:
 *
 *  - the shrunken viewport **clips the last visible row through the middle**
 *    (he watched "+34,43 €" sliced in half with black underneath), and
 *  - the FAB is left sitting in an empty band, which reads as *"the plus button
 *    has its own background now"*.
 *
 * Both are worse than the occlusion the lane was meant to fix. Clearance is
 * contentPadding; occlusion at rest is handled by the FAB shrinking on scroll.
 *
 * ## Why a source scan
 *
 * The project has no Compose UI test suite, and this is a rule about layout
 * geometry between two sibling composables — the FAB and the list it floats
 * over. Nothing below the UI knows they are related, so there is no view model
 * to assert against, and the regression is invisible in review: the next FAB
 * screen gets written by copying an existing one. This file is what stops a bad
 * copy, in either direction.
 */
class FabClearanceDisciplineTest {

    /**
     * FAB screens that legitimately reserve no clearance -> why.
     *
     * The only honest reason to be in here is **there is no scroll container
     * under the FAB** — a fixed-layout screen whose content cannot move beneath
     * the button. Empty today: every FAB in the app floats over a `LazyColumn`.
     */
    private val clearanceExemptions = mapOf<String, String>()

    /** Matches `FloatingActionButton(`, and the Extended/Small/Large variants. */
    private val fabCall = "FloatingActionButton("

    /** The Scaffold slot — a FAB the screen hands over instead of aligning. */
    private val fabSlot = "floatingActionButton ="

    private fun uiRoot(): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
    }

    /**
     * Source with `//` comments and KDoc body lines stripped, so the notes that
     * necessarily *quote* the rejected design cannot satisfy — or trip — an
     * assertion about what the code actually does.
     */
    private fun code(file: File): String = file.readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .joinToString("\n")

    /** Every ui source, relative path -> its comment-stripped code. */
    private fun uiSources(): List<Pair<String, String>> {
        val root = uiRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to code(it) }
            .toList()
    }

    /** Every ui source that puts a FAB on screen: relative path -> its code. */
    private fun fabScreens(): List<Pair<String, String>> = uiSources()
        .filter { (_, code) -> code.contains(fabCall) || code.contains(fabSlot) }

    private val why =
        "Bottom clearance for a FAB is contentPadding on the scrolling list — " +
            "BT_FAB_CONTENT_CLEARANCE — and nothing else.\n\n" +
            "A floating FAB may overlap list content. Shrinking the scroll " +
            "viewport to avoid that (Modifier.padding(bottom = …) on a " +
            "fillMaxSize scroll container) clips the last visible row through " +
            "the middle and gives the button an empty band of its own — the " +
            "owner rejected exactly that on 2026-08-17, on his phone, after " +
            "watching a row's value sliced in half with black underneath.\n\n" +
            "Clearance is contentPadding; occlusion at rest is handled by the " +
            "FAB shrinking on scroll (BtFabVisibility)."

    @Test
    fun `every FAB screen clears its button with contentPadding`() {
        val screens = fabScreens()
        assertTrue(
            "no FAB screens found at all — did the scan lose the ui source root?",
            screens.size >= 8,
        )
        val offenders = screens
            .filterNot { (path, _) -> path in clearanceExemptions }
            .filterNot { (_, code) -> code.contains("BT_FAB_CONTENT_CLEARANCE") }
            .map { (path, _) -> path }
        assertTrue(
            "These screens float a FAB over a list without giving it " +
                "BT_FAB_CONTENT_CLEARANCE:\n" +
                offenders.joinToString("\n") { "  $it" } + "\n\n" + why,
            offenders.isEmpty(),
        )
    }

    /**
     * The rejected "FAB lane" tokens must not come back. Asserting they exist
     * nowhere under the ui source root is both the simplest and the most
     * robust form of "no
     * scroll container is padded to make room for a FAB": with no token to
     * reach for, the lane cannot be re-applied by copy-paste.
     */
    @Test
    fun `the rejected FAB lane tokens are gone`() {
        val offenders = uiSources()
            .filter { (_, code) ->
                code.contains("BT_FAB_LANE") || code.contains("BT_FAB_LANE_CONTENT_GAP")
            }
            .map { (path, _) -> path }
        assertTrue(
            "BT_FAB_LANE / BT_FAB_LANE_CONTENT_GAP were DELETED and must stay " +
                "deleted:\n" + offenders.joinToString("\n") { "  $it" } +
                "\n\n" + why,
            offenders.isEmpty(),
        )
    }

    /**
     * The same rule stated as geometry rather than as a token name, so a lane
     * open-coded under a different spelling is caught too: a `fillMaxSize()`
     * scroll container is never followed by a bottom padding on a FAB screen.
     */
    @Test
    fun `no FAB screen shrinks its scroll viewport`() {
        val offenders = fabScreens()
            .filter { (_, code) ->
                code.filterNot { it.isWhitespace() }.contains("fillMaxSize().padding(bottom")
            }
            .map { (path, _) -> path }
        assertTrue(
            "A fillMaxSize scroll container with a bottom padding IS the " +
                "rejected FAB lane:\n" + offenders.joinToString("\n") { "  $it" } +
                "\n\n" + why,
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no FAB screen hand-types the 96dp clearance`() {
        val offenders = fabScreens()
            .filter { (_, code) -> code.contains("bottom = 96.dp") }
            .map { (path, _) -> path }
        assertTrue(
            "96dp is right, but it must be BT_FAB_CONTENT_CLEARANCE — a literal " +
                "drifts the day the FAB or its inset changes:\n" +
                offenders.joinToString("\n") { "  $it" } + "\n\n" + why,
            offenders.isEmpty(),
        )
    }

    /**
     * The clearance and the button have to be measured from the same numbers. A
     * FAB inset by a hand-typed `20.dp` next to a clearance of `BT_FAB_SIZE +
     * 2 * BT_FAB_EDGE_INSET` is two constants pretending to be one, and the day
     * either moves the occlusion comes back silently.
     */
    @Test
    fun `no FAB is aligned with a hand-typed edge inset`() {
        val offenders = mutableListOf<String>()
        fabScreens().forEach { (path, code) ->
            var at = code.indexOf("Alignment.BottomEnd")
            while (at >= 0) {
                // The modifier chain that positions the FAB: same line, or the
                // handful of lines it is broken across.
                val window = code.substring(at, minOf(at + 240, code.length))
                if (window.contains("padding(20.dp)")) {
                    offenders += "$path (near offset $at)"
                }
                at = code.indexOf("Alignment.BottomEnd", at + 1)
            }
            if (code.contains("Alignment.BottomEnd") && !code.contains("BT_FAB_EDGE_INSET")) {
                offenders += "$path (aligns a FAB but never mentions BT_FAB_EDGE_INSET)"
            }
        }
        assertTrue(
            "A bottom-end FAB must be inset with BT_FAB_EDGE_INSET, not a " +
                "literal, so the button and BT_FAB_CONTENT_CLEARANCE cannot " +
                "drift apart:\n" + offenders.joinToString("\n") { "  $it" },
            offenders.isEmpty(),
        )
    }

    /**
     * Pins the arithmetic so a well-meaning tidy-up cannot redefine the
     * clearance as, say, the mini size and quietly reopen the defect.
     *
     * Two insets, not one: the FAB sits [BT_FAB_EDGE_INSET] off the bottom
     * edge, and the last row wants the same breathing room above the button
     * that the button has below itself.
     */
    @Test
    fun `the clearance is the FAB plus both insets`() {
        assertTrue(
            "BT_FAB_CONTENT_CLEARANCE must clear the resting FAB and an edge " +
                "inset on either side of it — it is " +
                at.bettertrack.app.ui.components.BT_FAB_CONTENT_CLEARANCE.toString(),
            at.bettertrack.app.ui.components.BT_FAB_CONTENT_CLEARANCE ==
                at.bettertrack.app.ui.components.BT_FAB_SIZE +
                at.bettertrack.app.ui.components.BT_FAB_EDGE_INSET * 2,
        )
    }
}
