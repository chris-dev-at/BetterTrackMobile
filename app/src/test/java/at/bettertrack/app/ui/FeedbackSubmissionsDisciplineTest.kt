package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two properties of the submissions screen that are invisible in a diff and that
 * no behavioural test in this project can reach (there is no Compose UI suite —
 * `androidTest` holds one instrumented stub).
 *
 * ## 1. The reserved reply badge must be `count > 0`-gated
 *
 * `unreadReplyCount` is on the wire today and is **always 0**: the per-submission
 * reply thread is not live, so the server has nothing to count. The badge is built
 * anyway so it lights up the day threads ship without an app release — which means
 * the app ships rendering code for a feature that does not exist, and the ONE thing
 * standing between that and a bare "0" pinned to every row is the gate.
 *
 * A behavioural test cannot see this. The count is 0 in production, so an ungated
 * badge would be caught by exactly nobody until it was on the owner's phone — and
 * [at.bettertrack.app.ui.components.BtCountBadge]'s own internal `if (count <= 0)
 * return` makes it worse, not better: it means an ungated call site LOOKS fine
 * today and is one component refactor away from drawing zeroes. So the gate is
 * asserted where it lives, in the screen's source.
 *
 * ## 2. The status label mapping may not have an `else`
 *
 * `feedbackStatusLabelRes` is a `when` over `FeedbackStatus` with no `else`, so a
 * status the platform adds later fails to COMPILE until somebody writes German and
 * English for it. An `else` would turn that compile error into a silent fallback:
 * every affected submission would read as whatever the fallback says, about a
 * status that has actually moved on. The compiler enforces exhaustiveness only
 * while the `else` is absent, so absence is the thing to guard.
 */
class FeedbackSubmissionsDisciplineTest {

    private val screen = "at/bettertrack/app/ui/feedback/FeedbackSubmissionsScreen.kt"

    private fun sourceFile(path: String): File {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("$path not found; tried ${candidates.map { it.absolutePath }}")
    }

    /**
     * Source with `//` comments and KDoc body lines stripped.
     *
     * This file necessarily EXPLAINS the reserved badge and the missing `else` in
     * prose, repeatedly. A naive substring check would read the explanation as the
     * code and pass on a screen that had lost both.
     */
    private fun code(path: String): String = sourceFile(path).readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `every unreadReplyCount render site is gated on the count being positive`() {
        val lines = code(screen).lines()
        // Every line that draws the badge, and every line that reads the count for
        // display, has to be inside a `> 0` gate. Rather than parse Kotlin, the
        // rule is checked structurally: a `BtCountBadge(` may only appear after a
        // `unreadReplyCount > 0` gate has been opened and before its block closes.
        val offenders = mutableListOf<String>()
        var gateDepth: Int? = null
        var depth = 0
        lines.forEachIndexed { index, line ->
            if (gateDepth == null && line.contains("unreadReplyCount > 0")) {
                gateDepth = depth
            }
            if (line.contains("BtCountBadge(") && gateDepth == null) {
                offenders += "line ${index + 1}: ${line.trim()}"
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
            val open = gateDepth
            if (open != null && depth <= open) gateDepth = null
        }
        assertEquals(
            "the reserved reply badge must never render outside an `unreadReplyCount > 0` gate",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the screen actually carries the reserved badge, gated`() {
        // The complement of the test above, which would also pass on a screen that
        // simply deleted the badge. The point of building it now is that the day
        // threads ship costs no app change; a silently removed badge would be a
        // promise quietly withdrawn.
        val source = code(screen)
        assertTrue("the reserved reply badge is gone", source.contains("BtCountBadge("))
        assertTrue(
            "the reserved reply badge must be gated on `unreadReplyCount > 0`",
            source.contains("unreadReplyCount > 0"),
        )
    }

    /**
     * [function]'s declaration and body — signature included, by brace matching
     * from the first `{` after the name.
     *
     * Signature included on purpose: both functions here are expression-bodied
     * (`= when (x) { … }`), so the `when` subject sits *before* the opening brace
     * and a body-only slice could not tell a `when` from a hand-written lookup.
     * Brace matching rather than a character budget, because the very next
     * function in the file legitimately HAS an `else` and a budget that overran
     * would fail this test for the wrong reason.
     */
    private fun declarationOf(source: String, function: String): String {
        val start = source.indexOf("fun $function")
        assertTrue("$function not found — was it renamed?", start >= 0)
        val open = source.indexOf('{', start)
        assertTrue("$function has no body", open > start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        error("unbalanced braces after $function")
    }

    private val elseBranch = Regex("""\belse\s*->""")

    @Test
    fun `the status label mapping has no else branch`() {
        val declaration = declarationOf(code(screen), "feedbackStatusLabelRes")
        assertTrue(
            "feedbackStatusLabelRes must stay an exhaustive `when` with no `else` — " +
                "an else turns a new server status from a compile error into silently wrong copy",
            !elseBranch.containsMatchIn(declaration),
        )
        // …and it must still be the `when` that makes exhaustiveness meaningful.
        assertTrue(declaration.contains("when (status)"))
    }

    @Test
    fun `the category label mapping has no else branch either`() {
        val declaration = declarationOf(code(screen), "feedbackCategoryLabelRes")
        assertTrue(
            "feedbackCategoryLabelRes must stay exhaustive with no `else`",
            !elseBranch.containsMatchIn(declaration),
        )
        assertTrue(declaration.contains("when (category)"))
    }

    @Test
    fun `an unknown wire status still reaches the chip`() {
        // The other half of the unknown-value contract: `fromWire` returns null,
        // and the chip must fall back to the RAW wire string. A screen that dropped
        // `statusWire` would render an empty pill for a status the platform added —
        // which looks like a bug in the app rather than a word it does not know.
        val source = code(screen)
        assertTrue(
            "the status chip must fall back to the raw wire value for an unknown status",
            source.contains("item.statusWire"),
        )
        assertTrue(
            "the category label must fall back to the raw wire value too",
            source.contains("item.categoryWire"),
        )
    }
}
