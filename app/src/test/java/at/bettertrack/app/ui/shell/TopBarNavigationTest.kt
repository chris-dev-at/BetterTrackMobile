package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the navigation restoration of 2026-08-06 (owner directive,
 * board #66).
 *
 * ## Why this is a test and not a code review note
 *
 * The owner's report was about a *rule* being violated screen by screen, not
 * about one bad screen: *"the settings menu is absolutely inaccessible, so
 * niche"* and *"every page shouldn't have the same 3 dots leading to 1000
 * different results depending on the page"*. Both halves of the fix are
 * invariants over the whole app rather than facts about any one file — the gear
 * is on *every* tab, and *no* top bar carries a ⋮ — and an invariant that only
 * one person is holding in their head is an invariant with a half-life.
 *
 * Every previous version of this rule was written as prose in a KDoc ("the ONE
 * action", "context, ONE action, overflow") and every one of them decayed: the
 * shell bar grew to six elements one defensible addition at a time, and five
 * separate overflows appeared, each individually justified. So this file checks
 * the two properties mechanically, the same way [at.bettertrack.app.i18n.StringParityTest]
 * checks EN↔DE parity — by reading the sources.
 *
 * ## What it deliberately does NOT check
 *
 * Row-level overflows. A ⋮ inside a list row (a cash movement, a friend, a
 * standing order) is a per-item context menu, which is a different thing from a
 * per-page one: it names the item it hangs off, its contents are predictable from
 * that item, and it was never what the owner was describing. Ten of them exist
 * and they stay.
 */
class TopBarNavigationTest {

    private fun uiSources(): List<File> {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * The whole app carries **no** top-bar overflow.
     *
     * [at.bettertrack.app.ui.components.BtCollapsingHeader] is the only bar
     * component with an `overflow` slot, so a call site passing one is the only
     * way a page-level ⋮ can come back. The slot itself is kept on the component
     * on purpose (see its KDoc) — this is what keeps the slot honest.
     */
    @Test
    fun `no screen passes a top-bar overflow`() {
        val offenders = uiSources().filter { f ->
            f.readText().lineSequence().any { line ->
                // `TextOverflow.Ellipsis` is assigned as `overflow = TextOverflow…`
                // on dozens of Text composables — match only the slot form.
                line.trimStart().startsWith("overflow = {")
            }
        }
        assertEquals(
            "These screens pass an overflow (⋮) to a top bar. Every entry in a page-level " +
                "menu must instead have an in-content path on the screen itself — see " +
                "board #66 and the KDoc on BtSettingsGear.",
            emptyList<String>(),
            offenders.map { it.name }.sorted(),
        )
    }

    /**
     * Each of the four top-level tabs puts the gear in its header's trailing slot.
     *
     * Checked per FILE rather than per composable because that is the unit a
     * regression arrives in: someone edits one tab's screen. The four files are
     * named explicitly so that adding a fifth tab fails here until its author has
     * decided about the gear, which is exactly the moment to decide it.
     */
    @Test
    fun `every top-level tab bar carries the settings gear`() {
        val tabScreens = mapOf(
            "PortfolioOverviewScreen.kt" to "Portfolio",
            "WorkboardScreen.kt" to "Workbench",
            "TabScreens.kt" to "Markets",
            "SocialScreen.kt" to "People",
        )
        val sources = uiSources().associateBy { it.name }
        tabScreens.forEach { (file, tab) ->
            val src = (sources[file] ?: error("$file not found")).readText()
            assertTrue(
                "The $tab tab's header ($file) no longer passes `settings = { BtSettingsGear(...) }`. " +
                    "Settings must stay one tap from every tab — board #66.",
                src.contains("settings = { BtSettingsGear("),
            )
        }
    }

    /**
     * The gear is rendered after everything else in the actions row.
     *
     * This is the "same slot app-wide" half of the directive. A bar that grew an
     * action or an overflow between `action` and `settings` would slide the gear
     * inward on that one screen, and a landmark that moves is not a landmark.
     *
     * ## Why this reads `actionsSlot` and no longer an inline `actions = {`
     *
     * The Portfolio tab's header was pinned (owner directive 2026-08-06), which
     * gave [at.bettertrack.app.ui.components.BtCollapsingHeader] two bars to
     * render — a `TopAppBar` when `pinned`, the `LargeTopAppBar` otherwise. The
     * actions row was lifted into ONE named `actionsSlot` handed to both, rather
     * than written out twice.
     *
     * That is a stronger guarantee than the one this test used to check, not a
     * weaker one: with a single definition the two bars cannot drift into
     * different orders, so the rule now has exactly one place it can be broken.
     * The assertion below is deliberately anchored to that definition and fails
     * loudly if it is ever inlined again — at which point whoever inlines it has
     * to come here and decide what the rule means for two bars.
     */
    @Test
    fun `the header renders settings last in its actions row`() {
        val header = uiSources().first { it.name == "BtCollapsingHeader.kt" }.readText()
        val marker = "val actionsSlot: @Composable RowScope.() -> Unit = {"
        assertTrue(
            "BtCollapsingHeader no longer defines a single `actionsSlot`. Both the pinned " +
                "and collapsing bars must share one actions row, or the gear can sit in a " +
                "different place on each — see this test's KDoc.",
            header.contains(marker),
        )
        val actions = header.substringAfter(marker).substringBefore("}")
        val order = listOf("action?.invoke()", "overflow?.invoke()", "settings?.invoke()")
            .map { actions.indexOf(it) }
        assertTrue("BtCollapsingHeader's actions row is missing a slot: $order", order.all { it >= 0 })
        assertEquals(
            "The settings gear must be invoked LAST in BtCollapsingHeader's actions row.",
            order.sorted(),
            order,
        )
    }

    /**
     * The pinned bar and the collapsing bar are handed the SAME slots.
     *
     * The pinned branch exists only to stop the Portfolio tab's selector pill
     * from resizing under the user's thumb; it is not licence for that tab to
     * carry a different set of controls. If a future edit gives one bar a slot
     * the other lacks, the gear's address stops being universal — which is the
     * exact failure the whole navigation restoration was undoing.
     */
    @Test
    fun `both header variants render the same slots`() {
        val header = uiSources().first { it.name == "BtCollapsingHeader.kt" }.readText()
        listOf("TopAppBar(", "LargeTopAppBar(").forEach { bar ->
            val body = header.substringAfter(bar).substringBefore("scrollBehavior = scrollBehavior,")
            listOf("title = titleSlot", "navigationIcon = navigationIcon", "actions = actionsSlot")
                .forEach { slot ->
                    assertTrue(
                        "$bar in BtCollapsingHeader does not pass `$slot` — the pinned and " +
                            "collapsing bars must expose an identical control surface.",
                        body.contains(slot),
                    )
                }
        }
    }
}
