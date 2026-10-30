package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * MB-07 guard: no `if (true)` / `if (false)` in app code. A constant condition
 * is a feature flag that was decided and never removed — the branch it guards
 * either always runs (so the `if` is noise that reads like a live switch) or
 * never runs (so it is dead code that still has to compile and be maintained).
 * `WatchlistScreen`'s "new list" button sat behind `if (true)` for a year after
 * named watchlists went live. Comments and KDoc are not code and are skipped.
 */
class ConstantConditionDisciplineTest {

    private val constantIf = Regex("""\bif\s*\(\s*(true|false)\s*\)""")

    @Test
    fun `no constant if conditions in main sources`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory }
            ?: error("main sources not found")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) ->
                        val code = line.trim()
                        !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") &&
                            constantIf.containsMatchIn(code.substringBefore("//"))
                    }
                    .map { (i, line) -> "${f.relativeTo(root).path}:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertEquals("constant if conditions:\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `the pattern catches the shapes it is meant to and nothing else`() {
        // Precision as well as recall: a guard that matched `if (trueBlack)` or
        // `if (isTrue)` would be switched off by the first person it annoyed.
        listOf("if (true) {", "if(false)", "} else if ( true ) {").forEach {
            assertEquals(it, true, constantIf.containsMatchIn(it))
        }
        listOf("if (trueBlack) {", "if (isTrue)", "if (flag == true)", "while (true) {", "if (!false)").forEach {
            assertEquals(it, false, constantIf.containsMatchIn(it))
        }
    }
}
