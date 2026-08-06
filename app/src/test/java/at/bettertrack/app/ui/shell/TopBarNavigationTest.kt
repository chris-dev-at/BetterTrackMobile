package at.bettertrack.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the navigation restoration of 2026-08-06 (owner directive,
 * board #66), extended on 2026-08-07 to the top-level tabs' shared identity strip
 * — wordmark leading, pinned, gear last.
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
     * The file that hosts each top-level tab's header, by tab.
     *
     * Named explicitly, and shared by every per-tab test below, so that adding a
     * fifth tab fails all of them at once until its author has decided about the
     * gear, the wordmark and the pin — which is exactly the moment to decide them,
     * and exactly the set of decisions that made the first four disagree.
     */
    private val tabScreens = mapOf(
        "PortfolioOverviewScreen.kt" to "Portfolio",
        "WorkboardScreen.kt" to "Workbench",
        "TabScreens.kt" to "Markets",
        "SocialScreen.kt" to "People",
    )

    /**
     * Each of the four top-level tabs puts the gear in its header's trailing slot.
     *
     * Checked per FILE rather than per composable because that is the unit a
     * regression arrives in: someone edits one tab's screen.
     */
    @Test
    fun `every top-level tab bar carries the settings gear`() {
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
     * Each of the four top-level tabs leads its header with the BetterTrack
     * wordmark (owner order 2026-08-07).
     *
     * *"Have the BetterTrack logo on the top of the main pages — like on EVERY
     * main page ... and do the same as with the portfolio page where it just gets
     * put up top, that works great."*
     *
     * This is the same class of invariant as the gear, and it decayed the same way
     * once already: the wordmark's 2026-08-06 restoration reached Portfolio and
     * stopped, so the brand was a property of one tab for a day. "Every" is the
     * word in the order, so "every" is what gets checked.
     *
     * Anchored to [at.bettertrack.app.ui.components.BtHeaderWordmark] rather than
     * to a bare `Wordmark(` on purpose. A tab that hand-rolled the mark would
     * satisfy a looser assertion while drifting in size, padding or the debug
     * gesture — which is precisely what the component exists to prevent.
     */
    @Test
    fun `every top-level tab bar leads with the wordmark`() {
        val sources = uiSources().associateBy { it.name }
        tabScreens.forEach { (file, tab) ->
            val src = (sources[file] ?: error("$file not found")).readText()
            assertTrue(
                "The $tab tab's header ($file) no longer passes " +
                    "`navigationIcon = { BtHeaderWordmark(`. The BetterTrack mark must lead " +
                    "every top-level tab — owner order 2026-08-07.",
                src.contains("navigationIcon = { BtHeaderWordmark("),
            )
        }
    }

    /**
     * The wordmark stops at the tab roots — no sub-page wears it.
     *
     * The other half of the same order: *"not a sub page (not asset view etc.)"*.
     * A pushed screen's leading slot belongs to its back arrow, and a brand mark
     * that displaced it would cost the one affordance a user must never have to
     * hunt for. Stated as a whole-app property because "don't put it on the other
     * thirty-odd screens" is not something any one of those screens knows.
     */
    @Test
    fun `no screen outside the four tabs carries the header wordmark`() {
        val offenders = uiSources().filter { f ->
            f.name !in tabScreens.keys &&
                f.name != "BtCollapsingHeader.kt" &&
                f.readText().contains("BtHeaderWordmark(")
        }
        assertEquals(
            "These non-tab screens render BtHeaderWordmark. The brand mark belongs to the " +
                "four top-level tabs only; a pushed screen's leading slot is its back arrow " +
                "— owner order 2026-08-07.",
            emptyList<String>(),
            offenders.map { it.name }.sorted(),
        )
    }

    /**
     * Each of the four top-level tabs draws the PINNED bar, with the pinned
     * behaviour to match.
     *
     * *"Do the same as with the portfolio page where it just gets put up top, that
     * works great."* — the praised property is that the bar does not move, so the
     * test checks the two things that make it not move, together.
     *
     * Both halves are required and neither implies the other, which is the whole
     * reason this is one test and not two:
     *
     *  - `pinned = true` with a *collapsing* behaviour gives a bar that renders at
     *    a fixed height while its behaviour still writes `heightOffset` — it
     *    scrolls partly off-screen, which is the exact failure the order is about,
     *    and it looks correct in a screenshot taken at the top.
     *  - [at.bettertrack.app.ui.components.rememberBtPinnedHeaderBehavior] without
     *    `pinned = true` gives a `LargeTopAppBar` that can never collapse — a
     *    permanently expanded 112dp bar, i.e. the opposite mistake.
     */
    @Test
    fun `every top-level tab bar is the pinned variant`() {
        val sources = uiSources().associateBy { it.name }
        tabScreens.forEach { (file, tab) ->
            val src = (sources[file] ?: error("$file not found")).readText()
            assertTrue(
                "The $tab tab's header ($file) no longer passes `pinned = true`. Every " +
                    "top-level tab bar is a fixed 64dp strip — owner order 2026-08-07.",
                src.contains("pinned = true"),
            )
            assertTrue(
                "The $tab tab ($file) passes `pinned = true` but does not use " +
                    "`rememberBtPinnedHeaderBehavior()`. A pinned bar on a collapsing " +
                    "behaviour still scrolls off — see this test's KDoc.",
                src.contains("rememberBtPinnedHeaderBehavior()"),
            )
        }
    }

    /**
     * App-wide: `pinned = true` and the pinned behaviour travel together.
     *
     * The per-tab test above pins that pairing for the four files that have it
     * today; this one states it as the rule, so a fifth pinned bar anywhere in the
     * app cannot be built on `rememberBtCollapsingHeaderBehavior` and half-work.
     */
    @Test
    fun `no screen pins a bar on a collapsing behaviour`() {
        val offenders = uiSources().filter { f ->
            if (f.name == "BtCollapsingHeader.kt") return@filter false
            val src = f.readText()
            src.contains("pinned = true") && !src.contains("rememberBtPinnedHeaderBehavior()")
        }
        assertEquals(
            "These screens pass `pinned = true` to BtCollapsingHeader without pairing it " +
                "with rememberBtPinnedHeaderBehavior(). The bar would render at a fixed " +
                "height and still scroll away.",
            emptyList<String>(),
            offenders.map { it.name }.sorted(),
        )
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
