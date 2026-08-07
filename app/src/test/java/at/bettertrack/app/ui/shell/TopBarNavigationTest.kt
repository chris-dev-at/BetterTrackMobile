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
    /** The ONE bar the four top-level tabs share since the 2026-08-07 hoist. */
    private fun sharedTabBar(): String =
        uiSources().first { it.name == "BtTabHeader.kt" }.readText()

    private val tabScreens = mapOf(
        "PortfolioOverviewScreen.kt" to "Portfolio",
        "WorkboardScreen.kt" to "Workbench",
        "TabScreens.kt" to "Markets",
        "SocialScreen.kt" to "People",
    )

    /**
     * The gear is in the trailing slot of the ONE bar the top-level tabs share.
     *
     * Until the 2026-08-07 hoist this was checked per file, because the gear was
     * written out four times — once in each tab's own header — and "identical on
     * every tab" was a property four authors had to keep agreeing on. There is
     * one bar now ([at.bettertrack.app.ui.shell.BtTabHeader], drawn by the shell
     * above everything the tab swipe moves), so the invariant has exactly one
     * place it can be broken, and this test guards that place.
     *
     * The other half of the old guarantee — that no tab has quietly grown a
     * SECOND gear of its own — is
     * [`only the shell's shared bar renders the tab chrome`].
     */
    @Test
    fun `the shared tab bar carries the settings gear`() {
        val bar = sharedTabBar()
        assertTrue(
            "BtTabHeader no longer renders BtSettingsGear. Settings must stay one tap from " +
                "every tab — board #66.",
            bar.contains("BtSettingsGear(onOpenSettings)"),
        )
    }

    /**
     * The gear is the LAST thing in the shared bar's actions row.
     *
     * The "same slot app-wide" half of the directive. The bar's variable action
     * (Overview's search, People's messages) is rendered by the swap zone
     * immediately before it, so a future edit that appends anything after
     * `BtSettingsGear` would slide the landmark inward on whichever tab carried
     * it — and a landmark that moves is not a landmark. It is also now the
     * literal guarantee the owner asked for on the swipe: the gear cannot travel
     * during a hand-over if nothing is ever placed after it.
     */
    @Test
    fun `the shared tab bar renders settings last in its actions row`() {
        val bar = sharedTabBar()
        val marker = "actions = {"
        assertTrue("BtTabHeader has no `actions = {` row.", bar.contains(marker))
        val actions = bar.substringAfter(marker).substringBefore("expandedHeight")
        val swapZone = actions.indexOf("BtHeaderSwapZone(")
        val gear = actions.indexOf("BtSettingsGear(")
        assertTrue("BtTabHeader's actions row lost its swap zone or its gear.", swapZone >= 0 && gear >= 0)
        assertTrue(
            "The settings gear must be the LAST thing in BtTabHeader's actions row; the " +
                "tab's own action is rendered before it.",
            gear > swapZone,
        )
        assertEquals(
            "Something is rendered after BtSettingsGear in BtTabHeader's actions row. The " +
                "corner is the gear's address — nothing may push it inward.",
            "",
            actions.substringAfter("BtSettingsGear(onOpenSettings)")
                .substringBefore("},")
                .replace(Regex("//[^\n]*"), "")
                .trim(),
        )
    }

    /**
     * The shared bar LEADS with the BetterTrack wordmark (owner order 2026-08-07).
     *
     * *"Have the BetterTrack logo on the top of the main pages — like on EVERY
     * main page ... and do the same as with the portfolio page where it just gets
     * put up top, that works great."*
     *
     * "Every" used to be four assertions over four files, and it decayed exactly
     * that way once already: the wordmark's 2026-08-06 restoration reached
     * Portfolio and stopped, so the brand was a property of one tab for a day.
     * After the hoist "every" is not an invariant at all — it is arithmetic. One
     * bar renders the mark unconditionally, and every top-level tab is drawn
     * under that one bar, so a tab without the wordmark would have to be a tab
     * outside the shell.
     *
     * Anchored to [at.bettertrack.app.ui.components.BtHeaderWordmark] rather than
     * to a bare `Wordmark(`: a hand-rolled mark would satisfy a looser assertion
     * while drifting in size, padding or the debug gesture.
     */
    @Test
    fun `the shared tab bar leads with the wordmark`() {
        val bar = sharedTabBar()
        assertTrue(
            "BtTabHeader no longer passes `navigationIcon = { BtHeaderWordmark(`. The " +
                "BetterTrack mark must lead the tab chrome — owner order 2026-08-07.",
            bar.contains("navigationIcon = { BtHeaderWordmark("),
        )
    }

    /**
     * The tab chrome — wordmark AND gear — is rendered in exactly one place.
     *
     * This single test now carries what three used to: the "every tab has it"
     * half, the "no sub-page wears it" half (*"not a sub page (not asset view
     * etc.)"* — a pushed screen's leading slot belongs to its back arrow, the one
     * affordance a user must never have to hunt for), and the half that had no
     * test at all, which is that no tab quietly keeps a SECOND copy after the
     * hoist.
     *
     * That last one is the regression this file exists to prevent in its newest
     * form. A tab screen that still drew its own `BtHeaderWordmark` would
     * compile, would look almost right standing still, and would go wrong only
     * during a swipe — as a second brand strip sliding under a static one, which
     * is the exact defect the hoist was ordered to fix.
     */
    @Test
    fun `only the shell's shared bar renders the tab chrome`() {
        // The component definitions themselves, the one bar that calls them, and
        // the debug gallery — which renders chrome as a SPECIMEN. A catalogue
        // showing you the gear is not a screen carrying one: it is never on the
        // navigation path, and the whole point of it is to render components out
        // of context so they can be looked at.
        val allowed = setOf("BtCollapsingHeader.kt", "BtTabHeader.kt", "GalleryScreen.kt")
        val offenders = uiSources().filter { f ->
            if (f.name in allowed) return@filter false
            val src = f.readText()
            src.contains("BtHeaderWordmark(") || src.contains("BtSettingsGear(")
        }
        assertEquals(
            "These screens render tab chrome (BtHeaderWordmark / BtSettingsGear) of their " +
                "own. Since the 2026-08-07 hoist there is exactly ONE tab bar — " +
                "ui/shell/BtTabHeader.kt — drawn by the shell above everything the tab " +
                "swipe moves. A second copy inside a page is what made the brand strip " +
                "slide during a swipe.",
            emptyList<String>(),
            offenders.map { it.name }.sorted(),
        )
    }

    /**
     * The shared bar is a fixed 64dp strip on a PINNED behaviour.
     *
     * *"Do the same as with the portfolio page where it just gets put up top,
     * that works great."* — the praised property is that the bar does not move,
     * so the test checks the two things that make it not move, together. Neither
     * implies the other, which is why this is one test and not two:
     *
     *  - a fixed `expandedHeight` on a *collapsing* behaviour gives a bar that
     *    renders at one height while its behaviour still writes `heightOffset` —
     *    it scrolls partly off-screen, and looks correct in any screenshot taken
     *    at the top of a list;
     *  - a pinned behaviour without the fixed height gives a `LargeTopAppBar`
     *    stuck permanently expanded — 112dp of chrome, the opposite mistake.
     *
     * Both are still worth pinning after the hoist even though the bar can no
     * longer slide sideways: not scrolling away and not sliding are different
     * properties with different causes, and only the second one was fixed by
     * moving the bar out of the pages.
     */
    @Test
    fun `the shared tab bar is a fixed strip on a pinned behaviour`() {
        val bar = sharedTabBar()
        assertTrue(
            "BtTabHeader no longer fixes its height to BT_HEADER_COLLAPSED_HEIGHT. The tab " +
                "strip is a fixed 64dp row — owner order 2026-08-07.",
            bar.contains("expandedHeight = BT_HEADER_COLLAPSED_HEIGHT"),
        )
        assertTrue(
            "BtTabHeader's scroll behaviour is no longer built on " +
                "rememberBtPinnedHeaderBehavior. A fixed height on a collapsing behaviour " +
                "still scrolls away — see this test's KDoc.",
            bar.contains("rememberBtPinnedHeaderBehavior("),
        )
    }

    /**
     * The bar is drawn OUTSIDE the area the tab swipe displaces.
     *
     * The structural fact the whole hoist rests on, and the one a refactor is
     * most likely to undo by accident: [at.bettertrack.app.ui.shell.BtTabHeader]
     * must be a sibling *before* the `Box` that carries `btTabSwipe`, not a child
     * of it. Inside that Box it would ride `pageOffsetPx` like the pages do and
     * the owner's report would be back, with every other test still green —
     * because nothing else in this suite can see a layout order.
     */
    @Test
    fun `the shared tab bar sits outside the swiped page area`() {
        val shell = uiSources().first { it.name == "AppShell.kt" }.readText()
        val bar = shell.indexOf("BtTabHeader(")
        val swipe = shell.indexOf(".btTabSwipe(")
        assertTrue("AppShell no longer draws BtTabHeader.", bar >= 0)
        assertTrue("AppShell no longer applies btTabSwipe.", swipe >= 0)
        assertTrue(
            "AppShell draws BtTabHeader after (i.e. inside) the swiped page area. The one " +
                "tab bar must be a sibling ABOVE the Box that carries btTabSwipe, or it " +
                "will slide with the pages again — owner report 2026-08-07.",
            bar < swipe,
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
    fun `the collapsing header renders settings last in its actions row`() {
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
    /**
     * The shell's bottom-bar list is in [at.bettertrack.app.navigation.BtTab]
     * declaration order.
     *
     * `AppShell.Tabs` is a SECOND list of the same four tabs, carrying their
     * labels and icons. Its order is what the user sees; the enum's order is what
     * every pure helper reasons about (deep-link owning tabs, swipe neighbours,
     * lateral slide direction). Until 2026-08-07 nothing checked that the two
     * agreed — `DeepLinkTabsTest` even carried a comment asserting the shell read
     * the enum, which it never did, so reordering the enum alone would have left
     * the bar unchanged with every test green.
     *
     * Read from source rather than by importing `Tabs`, which is private: making
     * it internal purely to be testable would widen the shell's API for the sake
     * of the test, and the file is the unit a regression actually arrives in.
     */
    @Test
    fun `the shell's tab list is in BtTab declaration order`() {
        val shell = uiSources().first { it.name == "AppShell.kt" }.readText()
        val tabsBlock = shell.substringAfter("private val Tabs = listOf(").substringBefore("\n)")
        val shellOrder = Regex("""TabSpec\(BtTab\.(\w+)""").findAll(tabsBlock).map { it.groupValues[1] }.toList()

        val navRoots = listOf(
            File("src/main/java/at/bettertrack/app/navigation/DeepLinkTabs.kt"),
            File("app/src/main/java/at/bettertrack/app/navigation/DeepLinkTabs.kt"),
        )
        val enumFile = navRoots.firstOrNull { it.isFile }
            ?: error("DeepLinkTabs.kt not found; tried ${navRoots.map { it.absolutePath }}")
        val enumBlock = enumFile.readText()
            .substringAfter("enum class BtTab(val route: Any) {")
            .substringBefore("}")
        val enumOrder = Regex("""^\s*(\w+)\(""", RegexOption.MULTILINE)
            .findAll(enumBlock).map { it.groupValues[1] }.toList()

        assertEquals(
            "AppShell.Tabs is not in BtTab declaration order. Both lists are the " +
                "bar; the enum is the contract every pure nav helper reads. " +
                "Reorder BOTH or neither.",
            enumOrder,
            shellOrder,
        )
        assertEquals("expected exactly four tabs in the shell list", 4, shellOrder.size)
    }

    /**
     * Every top-level tab hangs the SHARED bar's nested-scroll connection.
     *
     * The one thing each tab still owes the bar after the hoist. A pinned
     * behaviour's only remaining job is to accumulate `contentOffset`, and
     * `contentOffset` is what swaps the bar's container colour for its scrolled
     * tone — so a tab that forgets this line gets a bar that looks like page
     * background while its list slides through it, which is the exact seam the
     * app's header work spent two milestones removing.
     *
     * It fails silently and only on that one tab, which is precisely the kind of
     * regression a per-file assertion is for. Adding a fifth tab fails this test
     * until its author has wired it up.
     */
    @Test
    fun `every top-level tab hangs the shared bar's scroll connection`() {
        val sources = uiSources().associateBy { it.name }
        tabScreens.forEach { (file, tab) ->
            val src = (sources[file] ?: error("$file not found")).readText()
            assertTrue(
                "The $tab tab ($file) does not hang `LocalBtTabChrome.current.headerScroll` " +
                    "on its scroll container. Without it the one shared bar never takes its " +
                    "tonal lift over this tab's content — see BtTabHeader.",
                src.contains("LocalBtTabChrome.current.headerScroll") ||
                    src.contains("chrome.headerScroll"),
            )
        }
    }

    /**
     * [at.bettertrack.app.ui.components.BtCollapsingHeader] renders ONE bar.
     *
     * It used to render two — a `TopAppBar` when `pinned`, a `LargeTopAppBar`
     * otherwise — and a test here checked that both were handed identical slots,
     * because a tab whose bar carried a different control surface would have
     * broken the gear's universal address. The pinned branch left with the four
     * per-page tab bars it existed for; this replaces that guard with the reason
     * it is no longer needed, so that re-introducing a second bar in this file
     * has to come past a failing test rather than past nobody.
     */
    @Test
    fun `the collapsing header renders exactly one bar`() {
        val header = uiSources().first { it.name == "BtCollapsingHeader.kt" }.readText()
        val code = header.lineSequence().filterNot { it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertEquals(
            "BtCollapsingHeader renders more than one app bar. Since the 2026-08-07 hoist " +
                "the tab strip is ui/shell/BtTabHeader.kt and this component is the " +
                "COLLAPSING bar for pushed screens only — a second variant here would give " +
                "the app two control surfaces to keep in agreement again.",
            1,
            Regex("""\bLargeTopAppBar\(""").findAll(code).count() +
                Regex("""(?<!Large)\bTopAppBar\(""").findAll(code).count(),
        )
    }
}
