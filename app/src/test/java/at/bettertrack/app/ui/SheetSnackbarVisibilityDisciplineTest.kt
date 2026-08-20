package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shell's snackbar host must be drawn ABOVE the sheet layer.
 *
 * ## The defect this exists to prevent coming back
 *
 * `AppShell` is a `Box` with two children: a `Scaffold` that draws the four tab
 * pages and the bottom bar, and — after it, therefore over it — the full-screen
 * sheet layer that every one of the app's 60 subpages is composed into. Until
 * 2026-08-20 the one app-wide snackbar host lived in that `Scaffold`'s
 * `snackbarHost` slot, which put it **under** the sheets. The shell also clips
 * its own draw to the strip a settled sheet leaves showing (`BtOcclusion`), so
 * the host was not merely covered, it was not rastered at all.
 *
 * The consequence was not a corner case. An audit of the sheet graph on
 * 2026-08-20 found **21 of the 60 `btSheet` destinations** reporting outcomes
 * through `LocalBtSnackbar` — the notifications inbox (archive, undo, and every
 * refresh failure), Settings, Connections, Authorized apps, Trusted devices,
 * Passkeys, Account PIN, Public profile, Data export, Widgets, Cash rules, both
 * tax screens, the chat thread, the storage vault restore, and the whole social
 * toast surface (friend groups, friend overview, the three friend-shared views
 * and idea detail via `ItemThreadSection`). Exactly **three** surfaces that use
 * the mechanism are NOT sheets: the People tab, Overview's mirror-invites card,
 * and the first-run wizard, which mounts a host of its own.
 *
 * In other words the app's single feedback idiom was, in practice, a sheet
 * mechanism that did not work on sheets. It was proven live on the owner's phone
 * the same day: a feedback delete that answered `500` showed the user nothing.
 *
 * ## Why a source scan
 *
 * There is no Compose UI test suite in this project (`androidTest` holds one
 * instrumented stub), and z-order inside a `Box` is *source order* — a property
 * no behavioural test could observe anyway. It is also exactly the kind of thing
 * a well-meaning refactor undoes: moving the host back into the `snackbarHost`
 * slot looks tidier, compiles, and silently deletes feedback from 21 screens.
 *
 * ## The rules
 *
 *  1. `AppShell` passes NO `snackbarHost` to its `Scaffold`.
 *  2. It mounts exactly one [at.bettertrack.app.ui.components.BtSnackbarHost],
 *     and that call comes AFTER the `BtSheetHost(` call — later sibling, drawn
 *     on top.
 *  3. That host is bottom-aligned and offsets itself by the bottom bar only
 *     while the sheet stack is closed, so it clears the bar on a tab and sits on
 *     the gesture inset over a sheet.
 *  4. The sheet layer is wrapped in a `CompositionLocalProvider` that supplies
 *     the controller — otherwise sheets resolve `LocalBtSnackbar` to its no-op
 *     default and the outcomes vanish a second way.
 *  5. No file outside [hostsAllowedOutsideTheShell] mounts a host of its own. A
 *     second host anywhere the sheet layer can cover reintroduces the defect one
 *     screen at a time.
 */
class SheetSnackbarVisibilityDisciplineTest {

    private val shell = "at/bettertrack/app/ui/shell/AppShell.kt"

    /**
     * Files permitted to mount their own [at.bettertrack.app.ui.components.BtSnackbarHost].
     *
     * The bar for being added here is high and specific: the surface must be one
     * the shell's sheet layer can never draw over. Only two qualify.
     *
     *  - `AppShell.kt` — the app-wide host, and the one this whole test is about.
     *  - `FirstRunWizard.kt` — a BtRoot gate that runs BEFORE the shell exists,
     *    so there is no sheet layer and no shell host to reach. It already mounts
     *    its host as the last child of its own `Box`, which is the same shape the
     *    shell now uses.
     *
     * A screen that merely *wants* a snackbar does not belong here — it already
     * has one through `LocalBtSnackbar`. Anything added to this set must say, in
     * a comment, what draws over it and why that is nothing.
     */
    private val hostsAllowedOutsideTheShell = setOf(
        "at/bettertrack/app/ui/shell/AppShell.kt",
        "at/bettertrack/app/ui/firstrun/FirstRunWizard.kt",
        // The component's own declaration and KDoc.
        "at/bettertrack/app/ui/components/BtSnackbar.kt",
    )

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("main sources not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun sourceFile(path: String): File {
        val file = File(sourceRoot(), path)
        assertTrue("$path not found at ${file.absolutePath}", file.isFile)
        return file
    }

    /**
     * Source with `//` comments and KDoc body lines stripped.
     *
     * The shell now EXPLAINS this rule in prose, at length and by name, right
     * where the host is mounted. A naive substring check would read the
     * explanation as the code and pass on a shell that had lost the host.
     */
    private fun code(text: String): String = text.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .filterNot { it.trimStart().startsWith("/**") }
        .joinToString("\n")

    /** The argument text of the call whose name starts at [start], by paren matching. */
    private fun callArguments(source: String, start: Int): String {
        val open = source.indexOf('(', start)
        assertTrue("no argument list after index $start", open > start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("unbalanced parentheses after index $start")
    }

    @Test
    fun `the shell's Scaffold has no snackbarHost slot`() {
        val source = code(sourceFile(shell).readText())
        assertTrue(
            "AppShell must NOT pass `snackbarHost` to its Scaffold: the slot puts the host " +
                "inside the Scaffold, which the sheet layer is drawn over and the occlusion " +
                "clip cuts away — every subpage's feedback becomes invisible",
            !source.contains("snackbarHost"),
        )
    }

    @Test
    fun `the shell mounts exactly one host, after the sheet layer`() {
        val source = code(sourceFile(shell).readText())
        val mounts = Regex("""BtSnackbarHost\s*\(""").findAll(source).map { it.range.first }.toList()
        assertEquals(
            "the shell must mount exactly one snackbar host — two hosts on one " +
                "SnackbarHostState both render the same message",
            1,
            mounts.size,
        )
        val sheetLayer = source.indexOf("BtSheetHost(")
        assertTrue("the sheet layer is gone from AppShell — was it renamed?", sheetLayer >= 0)
        assertTrue(
            "the snackbar host must be composed AFTER `BtSheetHost(` — inside a Box, " +
                "later sibling means drawn on top, and that IS the fix",
            mounts.single() > sheetLayer,
        )
    }

    @Test
    fun `the host is bottom-aligned and clears the bottom bar only while the tabs show`() {
        val source = code(sourceFile(shell).readText())
        val args = callArguments(source, source.indexOf("BtSnackbarHost("))
        assertTrue(
            "the host must sit at the bottom of the shell's Box",
            args.contains("Alignment.BottomCenter"),
        )
        assertTrue(
            "the host's bottom offset must depend on `sheetsClosed`: the bottom bar's own " +
                "height while the tabs show (the Scaffold slot used to do that for free), and " +
                "the gesture inset while a sheet covers the bar",
            args.contains("sheetsClosed"),
        )
        assertTrue(
            "the tab-side offset must be the MEASURED bar height, not a hardcoded dp",
            source.contains("bottomBarPx = it.height"),
        )
    }

    @Test
    fun `the sheet layer is given the controller`() {
        val source = code(sourceFile(shell).readText())
        val provides = Regex("""LocalBtSnackbar provides""").findAll(source).map { it.range.first }.toList()
        val sheetLayer = source.indexOf("BtSheetHost(")
        assertTrue("the sheet layer is gone from AppShell — was it renamed?", sheetLayer >= 0)
        assertTrue(
            "the controller must be provided twice before the sheet layer is composed — once " +
                "over the tab pages and once over the sheets. Without the second, every sheet " +
                "resolves LocalBtSnackbar to its no-op default and reports into nothing",
            provides.count { it < sheetLayer } >= 2,
        )
    }

    @Test
    fun `no other file mounts a snackbar host`() {
        // Every SHIPPING source set, not just `main`: a flavor-only host would be
        // just as invisible under a sheet, and would only be caught on whichever
        // flavor happened to be built.
        val src = listOf(File("src"), File("app/src")).firstOrNull { it.isDirectory }
            ?: error("source sets not found")
        val shipping = src.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != "test" && it.name != "androidTest" }
        val mount = Regex("""BtSnackbarHost\s*\(""")
        val offenders = shipping.asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { mount.containsMatchIn(code(it.readText())) }
            .map { it.invariantSeparatorsPath.substringAfter("/java/") }
            .filterNot { it in hostsAllowedOutsideTheShell }
            .distinct()
            .sorted()
            .toList()
        assertEquals(
            "a snackbar host anywhere the sheet layer can draw over reintroduces the " +
                "2026-08-20 defect. Screens do not need one: they raise outcomes through " +
                "LocalBtSnackbar and the shell's host — above the sheets — shows them. " +
                "If a surface genuinely cannot be covered, add it to " +
                "`hostsAllowedOutsideTheShell` with a comment saying why",
            emptyList<String>(),
            offenders,
        )
    }
}
