package at.bettertrack.app.ui.workboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Workbench "Needs you" selection rule (R-arc R2 §3).
 *
 * The block is the screen's whole claim to leading with what is actionable, so
 * the two ways it can lie are what these tests pin: showing the wrong things
 * when the cap bites, and implying it is showing everything when it is not.
 *
 * ## And, since 2026-08-17, that the rule is actually WIRED
 *
 * These arithmetic tests all passed on 2026-08-07, the day the Workbench dropped
 * its Ideas segment and hard-coded `unfinished = emptyList()` into the block's
 * only call site. A green suite over a function nobody feeds is the exact shape
 * of the regression that followed — see [WorkbenchIdeasReachabilityTest], which
 * is deliberately in this file because it guards the half of this rule that
 * pure-function tests structurally cannot see.
 */
class WorkbenchLeadLogicTest {

    @Test
    fun `nothing waiting means an empty plan`() {
        val plan = needsYouPlan(emptyList<String>(), emptyList<String>(), max = 3)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.hidden)
    }

    @Test
    fun `alerts take the slots before ideas`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1", "a2", "a3"),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        // A fired alert is about money moving now; an unwritten thesis has been
        // waiting for weeks. If the cap forces a choice it is not a close call.
        assertEquals(listOf("a1", "a2", "a3"), plan.alerts)
        assertEquals(emptyList<String>(), plan.ideas)
    }

    @Test
    fun `ideas fill only what the alerts left`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1"),
            unfinishedIdeas = listOf("i1", "i2", "i3"),
            max = 3,
        )
        assertEquals(listOf("a1"), plan.alerts)
        assertEquals(listOf("i1", "i2"), plan.ideas)
    }

    @Test
    fun `hidden counts what was dropped from BOTH lists`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1", "a2", "a3", "a4"),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        // 6 actionable, 3 shown — the block must own up to the other 3, not just
        // to the ideas it never got to.
        assertEquals(3, plan.hidden)
    }

    @Test
    fun `nothing is hidden when everything fits`() {
        val plan = needsYouPlan(
            triggeredAlerts = listOf("a1"),
            unfinishedIdeas = listOf("i1"),
            max = 3,
        )
        assertEquals(0, plan.hidden)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `ideas alone still lead when no alert fired`() {
        val plan = needsYouPlan(
            triggeredAlerts = emptyList<String>(),
            unfinishedIdeas = listOf("i1", "i2"),
            max = 3,
        )
        assertEquals(emptyList<String>(), plan.alerts)
        assertEquals(listOf("i1", "i2"), plan.ideas)
        assertEquals(0, plan.hidden)
    }

    @Test
    fun `a zero cap hides everything rather than rendering a headless block`() {
        val plan = needsYouPlan(listOf("a1"), listOf("i1"), max = 0)
        assertTrue(plan.isEmpty)
        assertEquals(2, plan.hidden)
    }
}

/**
 * **Saved ideas must stay reachable.** The regression this guards, in full:
 *
 * On 2026-08-07 the Ideas pill was removed from the Workbench and the "Needs
 * you" block's ideas feed was replaced with a literal `emptyList()`. The removal
 * looked free — `IdeasSection`, `IdeasViewModel` and `IdeaDetailRoute` all still
 * compiled — but it left the idea-detail route with exactly ONE navigator in the
 * whole app: the clone-a-friend's-idea `LaunchedEffect` in
 * `ui/social/FriendOverviewScreen.kt`. An idea you owned was therefore viewable
 * exactly once, immediately after cloning it, and unreachable forever after. The
 * "New idea" FAB and create sheet had no caller at all. The owner reversed it on
 * 2026-08-17.
 *
 * ## Why a source scan, and why not something better
 *
 * The honest seam is missing on purpose: `WorkboardSection` is `private` (it is a
 * host-local detail and making it public to test it would be the test changing
 * the design), the wiring lives inside `@Composable` functions, and the project
 * has no Compose UI test suite — `androidTest` holds one instrumented stub. So
 * the only thing left below the phone is the source text, which is the same
 * pattern `ui/RowAnatomyDisciplineTest` uses for the money rows and for the same
 * reason.
 *
 * The scan is written to fail LOUD on renames rather than to pass on anything
 * that happens to contain the word "Ideas": every assertion names the construct
 * it expects and says what breaks if it is gone. If a refactor moves this wiring
 * somewhere better — a real ViewModel seam, a Compose test — delete this class
 * and assert it there. Do not delete it because it went red.
 */
class WorkbenchIdeasReachabilityTest {

    private fun source(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return (
            candidates.firstOrNull { it.isFile }
                ?: error("source not found; tried ${candidates.map { it.absolutePath }}")
            ).readText()
    }

    private val workbench get() = source("at/bettertrack/app/ui/workboard/WorkboardScreen.kt")

    /**
     * The argument list of the first [call] in [source], parens included, by
     * paren matching.
     *
     * Matching rather than a `[^)]*` regex because half the argument lists here
     * contain parens of their own — `onOpenConglomerate: (String) -> Unit` would
     * truncate a naive scan at its first `)` and quietly assert nothing.
     */
    private fun argsOf(source: String, call: String): String {
        val at = source.indexOf(call)
        require(at >= 0) { "$call not found — was it renamed or deleted?" }
        val open = at + call.length - 1
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced parens in $call")
    }

    @Test
    fun `the Workbench still has an Ideas segment, and Alerts still leads`() {
        val enum = Regex("""private enum class WorkboardSection \{([^}]*)\}""")
            .find(workbench)
            ?.groupValues
            ?.get(1)
            ?: error("WorkboardSection is gone or no longer a single-line enum")
        val entries = enum.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        assertTrue(
            "the Ideas segment is gone again — with it, an idea you own is viewable " +
                "exactly once (right after cloning a friend's) and then never again. " +
                "Sections found: $entries",
            entries.contains("Ideas"),
        )
        // Standing owner order (2026-08-07): a fired alert is the only thing on
        // this tab that can be waiting for you, so it leads and it is the default.
        assertEquals("Alerts must stay the first segment", "Alerts", entries.first())
        assertTrue(
            "Alerts must stay the default section",
            workbench.contains("mutableStateOf(WorkboardSection.Alerts)"),
        )
    }

    @Test
    fun `the Ideas segment renders the ideas list and can open an idea`() {
        val branch = "WorkboardSection.Ideas -> IdeasSection("
        assertTrue(
            "the Ideas branch of the `when (section)` is gone — the enum entry " +
                "without it renders a blank segment",
            workbench.contains(branch),
        )
        val args = argsOf(workbench, branch)
        assertTrue(
            "IdeasSection must be handed the host's onOpenIdea, or its rows are " +
                "dead taps and the detail route has one navigator again: $args",
            args.contains("onOpenIdea = onOpenIdea"),
        )
        assertTrue(
            "the Ideas pill lost its label — reuse the existing `bt_ideas_segment`",
            workbench.contains("R.string.bt_ideas_segment"),
        )
        assertTrue(
            "the Ideas pill lost its `onSelect`, so the segment cannot be chosen",
            workbench.contains("onSelect(WorkboardSection.Ideas)"),
        )
    }

    @Test
    fun `the ideas ViewModel is hoisted to the host, not created inside the section`() {
        // Two `viewModel { IdeasViewModel(...) }` instances = two fetches and two
        // copies of the list, so the "Needs you" block and the Ideas segment start
        // disagreeing about what is unfinished. The host owns the one instance and
        // passes it down; IdeasSection's `vm` default exists only for standalone use.
        assertTrue(
            "the host no longer builds the IdeasViewModel — the block cannot read " +
                "ideas while another segment is selected",
            workbench.contains("val ideasVm: IdeasViewModel = viewModel {"),
        )
        assertTrue(
            "IdeasSection must be given the hoisted vm, not left on its own default",
            argsOf(workbench, "WorkboardSection.Ideas -> IdeasSection(")
                .contains("vm = ideasVm"),
        )
    }

    /**
     * The other half: `needsYouPlan`'s ideas arithmetic (tested above, six ways)
     * is worth nothing if the call site passes it a literal empty list — which is
     * precisely what happened for ten days.
     */
    @Test
    fun `the Needs you block is fed real unfinished ideas`() {
        // The CALL, not the declaration: the call site is the thing that was
        // stubbed, and it is the first occurrence in the file.
        val call = argsOf(workbench, "WorkbenchNeedsYou(")

        assertFalse(
            "`unfinished` is hard-coded empty again: every needsYouPlan test still " +
                "passes and the ideas rows never render. Call site: $call",
            call.contains("unfinished = emptyList()"),
        )
        assertTrue(
            "the block must be fed the host's computed unfinished ideas: $call",
            call.contains("unfinished = unfinishedIdeas"),
        )
        assertTrue(
            "an unfinished idea is one with no thesis — that is the only 'unfinished' " +
                "signal the ideas API actually carries (it has no status workflow)",
            workbench.contains("it.thesis.isNullOrBlank()"),
        )
        assertTrue(
            "the block must be able to OPEN the idea it is nagging about",
            call.contains("onOpenIdea = onOpenIdea"),
        )
    }

    @Test
    fun `an unfinished-idea row exists and opens that idea's detail`() {
        val block = bodyOf(workbench, "private fun WorkbenchNeedsYou(")
        assertTrue(
            "the idea rows are gone from the block's drawing code — `plan.ideas` is " +
                "computed and then thrown away",
            block.contains("shownIdeas.forEach"),
        )
        assertTrue(
            "an unfinished-idea row must navigate to that idea",
            block.contains("onClick = { onOpenIdea(idea.id) }"),
        )
        assertTrue(
            "the row must keep reusing the EXISTING `bt_ideas_no_thesis` — the ideas " +
                "API has no status workflow, so the row says what is true rather than " +
                "inventing a 'needs a decision' state the server never sent",
            block.contains("R.string.bt_ideas_no_thesis"),
        )
    }

    @Test
    fun `the tab host and the shell still thread onOpenIdea to the shared route`() {
        val tabs = source("at/bettertrack/app/ui/screens/TabScreens.kt")
        assertTrue(
            "WorkbenchTabScreen dropped its onOpenIdea parameter",
            argsOf(tabs, "fun WorkbenchTabScreen(").contains("onOpenIdea"),
        )
        assertTrue(
            "WorkbenchTabScreen no longer passes onOpenIdea down to WorkboardScreen",
            argsOf(tabs, "WorkboardScreen(").contains("onOpenIdea = onOpenIdea"),
        )

        // Scoped to the Workbench call on purpose: the identical navigate line
        // also appears on the FriendOverviewRoute entry, so an unscoped
        // `contains` would stay green with this door bricked up again.
        val shell = source("at/bettertrack/app/ui/shell/AppShell.kt")
        val call = argsOf(shell, "WorkbenchTabScreen(")
        assertTrue(
            "the shell must open the SAME IdeaDetailRoute the cloned-idea path uses — " +
                "one owner-only detail destination, two doors. Call site: $call",
            call.contains("navController.navigate(IdeaDetailRoute(ideaId))"),
        )
    }

    /** [function]'s whole body, by brace matching from its signature. */
    private fun bodyOf(source: String, function: String): String {
        val start = source.indexOf(function)
        require(start >= 0) { "$function not found — was it renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced braces after $function")
    }
}
