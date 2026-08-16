package at.bettertrack.app.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the owner's bottom-sheet order (UI batch 2026-08-16):
 * *"every remaining anchored 3-dot dropdown / context menu becomes a bottom
 * sheet."*
 *
 * ## Why a source scan and not a screenshot
 *
 * The conversion touched eleven surfaces across nine files, and every one of
 * them is a transient popup — the kind of UI a screenshot suite only catches if
 * it happens to have the menu open. The rule, on the other hand, is perfectly
 * mechanical: after the sweep there is **no** `DropdownMenu` anywhere under
 * `ui/`, because every user-facing menu became a [BtActionSheet] (verbs) or a
 * [BtPickerSheet] (values). A rule that is true of the whole tree is cheapest to
 * defend by reading the tree.
 *
 * This matters more than the usual "keep it tidy" guard because the regression
 * is invisible in review. `DropdownMenu` is the Material default and the obvious
 * thing to reach for; a new screen that grows a row overflow will reach for it
 * without anyone noticing the app has a house component for exactly that, and
 * the result compiles, passes every other test, and looks — in the owner's own
 * words about the menus this replaced — cheap.
 *
 * ## Scope and the exemption door
 *
 * Every Kotlin source under `ui/`. The Glance widget package sits outside it
 * and has no menus to speak of — RemoteViews cannot host one.
 *
 * [exemptions] is empty ON PURPOSE and is the one honest place to record a
 * genuine anchored-menu case if one ever arrives. The category the owner
 * explicitly allowed is **autocomplete / typeahead** — a suggestion list bound
 * to a text field, which is not a context menu and must not slide up from the
 * bottom over the keyboard the user is typing on. The app has none today (its
 * search surfaces render inline results, not popups). Anything added here needs
 * a sentence saying why it is not a context menu.
 */
class AnchoredMenuDisciplineTest {

    /** path under `ui/` -> why an anchored menu is correct there. Empty today. */
    private val exemptions = mapOf<String, String>()

    /**
     * The Material popup-menu APIs. `ExposedDropdownMenuBox` is included even
     * though the app has never used one: it is the *other* obvious import for
     * "row that opens a list", and it renders the same anchored square.
     */
    private val bannedApis = listOf(
        "DropdownMenu(",
        "DropdownMenuItem(",
        "ExposedDropdownMenuBox(",
        "ExposedDropdownMenu(",
    )

    private fun uiRoot(): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
    }

    private fun uiSources(): List<Pair<String, File>> {
        val root = uiRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it }
            .toList()
    }

    @Test
    fun `no ui source opens an anchored dropdown menu`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { (path, file) ->
            if (path.replace(File.separatorChar, '/') in exemptions) return@forEach
            file.readLines().forEachIndexed { index, line ->
                // Comments discuss the retired menus at length — the KDoc on
                // BtActionSheet is literally about why they are gone — so only
                // CODE counts. A crude leading-marker strip is enough here: the
                // banned tokens are call expressions, and no line in this tree
                // both explains a dropdown and calls one.
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*")) return@forEachIndexed
                bannedApis.forEach { api ->
                    if (code.contains(api)) {
                        offenders += "$path:${index + 1}  $api"
                    }
                }
            }
        }
        assertTrue(
            "Anchored menus must be bottom sheets (BtActionSheet for verbs, " +
                "BtPickerSheet for values) — owner order 2026-08-16. Found:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * The positive half: the component the sweep converted TO is actually in
     * use. Without this, deleting every action sheet in the app would pass the
     * ban above — the tree would be clean and the features gone.
     */
    @Test
    fun `the action sheet is the app's context menu`() {
        val callSites = uiSources().count { (_, file) ->
            file.readText().contains("BtActionSheet(")
        }
        // Nine files host the converted menus (two of them host two each); the
        // component's own file defines it. A floor rather than an equality so
        // adding a screen does not fail an unrelated test — the point is that
        // the family is populated, not that it is frozen.
        assertTrue(
            "expected the converted action-sheet call sites to survive, found $callSites files",
            callSites >= 9,
        )
    }
}
