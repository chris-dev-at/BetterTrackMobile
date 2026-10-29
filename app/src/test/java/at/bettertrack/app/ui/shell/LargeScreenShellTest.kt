package at.bettertrack.app.ui.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The large-screen shell's structure (T1, 2026-10-29), as source assertions.
 *
 * This project has no Robolectric, so a Compose test cannot render the shell on
 * the JVM. What CAN regress silently is structural — a second tab model creeping
 * in for the rail, the rail escaping the occlusion clip, a page rendered outside
 * the content column, a width read from `screenWidthDp` — and those are exactly
 * what the file shows. Rendering is the device pass's job (docs/TODO.md, "T1 —
 * large-screen foundation", DEVICE CHECKLIST).
 */
class LargeScreenShellTest {

    private fun source(path: String): String {
        val file = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
            .firstOrNull { it.isFile } ?: error("$path not found")
        // Comments out: a KDoc that mentions a call must not satisfy a check
        // for the call itself.
        val text = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return text.lines().joinToString("\n") { it.substringBefore("//") }
    }

    private val shell by lazy { source("at/bettertrack/app/ui/shell/AppShell.kt") }

    /** The argument list of the first call to [name] at or after [from]. */
    private fun argumentsOf(src: String, name: String, from: Int = 0): String {
        val at = src.indexOf(name, from)
        assertTrue("no call to $name", at >= 0)
        val open = src.indexOf('(', at)
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return src.substring(open, i + 1)
            }
        }
        error("unbalanced call to $name")
    }

    @Test
    fun `the bar and the rail are fed from one tab model`() {
        val bar = argumentsOf(shell, "BtBottomBar(")
        val rail = argumentsOf(shell, "BtNavRail(")
        listOf(
            "tabs = visibleTabs",
            "selectedIndex = tapCommit ?: pagerState.currentPage",
            "pager = pagerState",
            "pinnedIndex = tapCommit",
            "hasBadge = hasTabBadge",
            "onSelect = onTabSelect",
        ).forEach { arg ->
            assertTrue("the bottom bar lost `$arg`", bar.contains(arg))
            assertTrue("the rail lost `$arg` — it must read the bar's model, not its own", rail.contains(arg))
        }
    }

    @Test
    fun `exactly one of the two presentations is composed`() {
        val barAt = shell.indexOf("BtBottomBar(")
        val railAt = shell.indexOf("BtNavRail(")
        val notRail = shell.lastIndexOf("if (!railShell)", barAt)
        val rail = shell.lastIndexOf("if (railShell)", railAt)
        assertTrue("the bottom bar is not gated on !railShell", notRail in 0 until barAt)
        assertTrue("the rail is not gated on railShell", rail in 0 until railAt)
        assertEquals("one bottom bar call", 1, Regex("BtBottomBar\\(").findAll(shell).count() - 1)
        assertEquals("one rail call", 1, Regex("BtNavRail\\(").findAll(shell).count())
    }

    @Test
    fun `the rail lives inside the occlusion-clipped Scaffold`() {
        // Same rule BtOcclusionTest pins for the pager: anything outside this
        // Scaffold is rasterised under every settled full-width sheet.
        val scaffoldAt = shell.indexOf("Scaffold(")
        val scaffoldArgs = argumentsOf(shell, "Scaffold(")
        val contentEnd = shell.indexOf("BtSheetHost(", scaffoldAt)
        val railAt = shell.indexOf("BtNavRail(")
        assertTrue(scaffoldArgs.contains("drawWithContent"))
        assertTrue("the rail left the Scaffold", railAt in scaffoldAt until contentEnd)
    }

    @Test
    fun `every tab page is rendered inside the content column`() {
        val pagerArgsEnd = shell.indexOf("BtTabPager(").let { argumentsOf(shell, "BtTabPager(").length + it }
        val column = shell.indexOf("BtContentColumn {", pagerArgsEnd)
        val content = shell.indexOf("BtTabContent(", pagerArgsEnd)
        assertTrue("BtContentColumn no longer wraps the pager's page", column in pagerArgsEnd until content)
    }

    @Test
    fun `the rail's content column pays the insets the bottom bar used to`() {
        // Without a bar to stand on, the column must stop above the taskbar.
        assertTrue(shell.contains(".windowInsetsPadding(railContentInsets)"))
        assertTrue(shell.contains(".consumeWindowInsets(railStartInsets)"))
        assertTrue(
            Regex("railContentInsets = WindowInsets\\.navigationBars[\\s\\S]{0,120}WindowInsetsSides\\.Bottom")
                .containsMatchIn(shell),
        )
    }

    @Test
    fun `page sheets read their cap from the window`() {
        val stack = source("at/bettertrack/app/ui/shell/BtSheetStack.kt")
        assertTrue(stack.contains("LocalBtWindow.current.sheetMaxWidth"))
        assertTrue(stack.contains(".widthIn(max = sheetMaxWidth)"))
        assertTrue(
            "a capped sheet must switch the occlusion cull off",
            stack.contains("spansWidth = sheetSpansWidth(widthPx, layerWidthPx)"),
        )
    }

    @Test
    fun `no layout decision reads the raw screen width`() {
        // One source of truth: LocalBtWindow. A `screenWidthDp` sprinkled into a
        // screen would be a second, disagreeing breakpoint.
        val roots = listOf(File("src/main/java/at/bettertrack/app"), File("app/src/main/java/at/bettertrack/app"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("source root not found")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val code = f.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .lines().joinToString("\n") { it.substringBefore("//") }
                code.contains("screenWidthDp")
            }
            .map { it.name }
            .toList()
        assertEquals("read LocalBtWindow instead of screenWidthDp", emptyList<String>(), offenders)
    }
}
