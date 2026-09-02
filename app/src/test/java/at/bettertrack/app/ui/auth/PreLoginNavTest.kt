package at.bettertrack.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pre-login back stack (owner device pass 2026-09-01, report #4).
 *
 * *"System Back on the pre-login Server screen exits the app … The in-app ←
 * skips the Einstellungen sheet and lands on the login screen."* Both are one
 * missing fact — what is underneath the surface you are looking at — so the
 * rules that answer it are pure functions and this file is where they are held
 * to "one back is one level".
 *
 * The three source assertions at the end guard the wiring, because the rules
 * being right is worth nothing if the composables stop consulting them: the
 * defect that shipped was not a wrong rule, it was three booleans and no rule
 * at all.
 */
class PreLoginNavTest {

    @Test
    fun `the floor is the login screen`() {
        assertEquals(listOf(PreLoginStep.Login), PRE_LOGIN_FLOOR)
    }

    @Test
    fun `back at the login screen belongs to the system`() {
        // NOT an empty list and NOT the floor again: a handler that answered
        // here would replace "exits the app" with "does nothing", which is the
        // same defect wearing the other shoe.
        assertNull(preLoginBack(PRE_LOGIN_FLOOR))
    }

    @Test
    fun `back from the settings sheet lands on the login screen`() {
        val open = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Settings)
        assertEquals(listOf(PreLoginStep.Login, PreLoginStep.Settings), open)
        assertEquals(PRE_LOGIN_FLOOR, preLoginBack(open))
    }

    @Test
    fun `back from the Server screen lands on the sheet it was opened from`() {
        // The half of #4 that the in-app ← got wrong: `showSettings` lived
        // inside the login screen that the Server screen replaces, so returning
        // rebuilt it closed and one press had travelled two levels.
        var stack = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Settings)
        stack = preLoginOpen(stack, PreLoginStep.Server)
        assertEquals(
            listOf(PreLoginStep.Login, PreLoginStep.Settings, PreLoginStep.Server),
            stack,
        )
        val back = preLoginBack(stack)
        assertEquals(listOf(PreLoginStep.Login, PreLoginStep.Settings), back)
        assertTrue("the sheet must be showing again", preLoginSheetOpen(back!!.last()))
    }

    @Test
    fun `the debug shortcut opens Server straight from the login screen and returns there`() {
        // The wordmark long-press skips the sheet, so the sheet is not what is
        // underneath — the login screen is. A parent map keyed on the STEP would
        // get this wrong; a stack cannot.
        val stack = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Server)
        assertEquals(listOf(PreLoginStep.Login, PreLoginStep.Server), stack)
        assertEquals(PRE_LOGIN_FLOOR, preLoginBack(stack))
    }

    @Test
    fun `back from diagnostics returns to the settings list, not out of the sheet`() {
        var stack = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Settings)
        stack = preLoginOpen(stack, PreLoginStep.Diagnostics)
        val back = preLoginBack(stack)
        assertEquals(listOf(PreLoginStep.Login, PreLoginStep.Settings), back)
        assertTrue(preLoginSheetOpen(back!!.last()))
    }

    @Test
    fun `every back pops exactly one level, from every depth`() {
        // The property, stated once over the whole reachable space rather than
        // as N examples: nothing may ever shorten the stack by two.
        val reachable = listOf(
            PRE_LOGIN_FLOOR,
            listOf(PreLoginStep.Login, PreLoginStep.Settings),
            listOf(PreLoginStep.Login, PreLoginStep.Server),
            listOf(PreLoginStep.Login, PreLoginStep.Settings, PreLoginStep.Diagnostics),
            listOf(PreLoginStep.Login, PreLoginStep.Settings, PreLoginStep.Server),
        )
        reachable.forEach { stack ->
            val back = preLoginBack(stack)
            if (stack.size == 1) {
                assertNull("the floor hands back to the system", back)
            } else {
                assertEquals("one back is one level, from $stack", stack.size - 1, back?.size)
                assertEquals("and it is the PARENT", stack.dropLast(1), back)
            }
        }
    }

    @Test
    fun `opening the surface you are already on is not a second level`() {
        val open = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Settings)
        assertEquals(open, preLoginOpen(open, PreLoginStep.Settings))
    }

    @Test
    fun `opening Login is going home, not pushing a second login screen`() {
        val stack = preLoginOpen(PRE_LOGIN_FLOOR, PreLoginStep.Settings)
        assertEquals(PRE_LOGIN_FLOOR, preLoginOpen(stack, PreLoginStep.Login))
    }

    @Test
    fun `dismissing the sheet takes everything the sheet was holding`() {
        // A pull-down is "close this sheet", not "back" — and the diagnostics
        // page lives INSIDE the sheet, so it cannot outlive it.
        val deep = listOf(PreLoginStep.Login, PreLoginStep.Settings, PreLoginStep.Diagnostics)
        assertEquals(PRE_LOGIN_FLOOR, preLoginSheetDismissed(deep))
        assertEquals(
            PRE_LOGIN_FLOOR,
            preLoginSheetDismissed(listOf(PreLoginStep.Login, PreLoginStep.Settings)),
        )
    }

    @Test
    fun `only the sheet steps show the sheet`() {
        assertTrue(preLoginSheetOpen(PreLoginStep.Settings))
        assertTrue(preLoginSheetOpen(PreLoginStep.Diagnostics))
        assertFalse(preLoginSheetOpen(PreLoginStep.Login))
        // The Server screen REPLACES the login screen, so the sheet the login
        // screen composes is not on stage while it is up.
        assertFalse(preLoginSheetOpen(PreLoginStep.Server))
    }

    @Test
    fun `the holder walks the stack the pure rules describe`() {
        val nav = PreLoginNav()
        assertEquals(PreLoginStep.Login, nav.current)
        assertFalse("the floor refuses back", nav.back())

        nav.open(PreLoginStep.Settings)
        nav.open(PreLoginStep.Diagnostics)
        assertTrue(nav.back())
        assertEquals(PreLoginStep.Settings, nav.current)

        nav.open(PreLoginStep.Server)
        assertTrue(nav.back())
        assertEquals(PreLoginStep.Settings, nav.current)
        assertTrue(nav.back())
        assertEquals(PreLoginStep.Login, nav.current)
        assertFalse(nav.back())
    }

    // ── The wiring ──────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("sources not found; tried ${roots.map { it.absolutePath }}")
        val file = File(root, relative)
        require(file.isFile) { "${file.absolutePath} not found" }
        return file.readText()
    }

    /**
     * The pre-login Server screen has a back handler.
     *
     * The literal defect: it had none, so the press reached the activity and
     * finished the task with the "route" still set — relaunching landed straight
     * back on the Server screen, which is how the QA pass proved it had never
     * been popped.
     */
    @Test
    fun `the logged-out gate handles back on the Server screen`() {
        val gate = source("at/bettertrack/app/ui/shell/BtRoot.kt")
        assertTrue(
            "BtRoot no longer imports BackHandler. System back on the pre-login " +
                "Server screen then falls through to the activity and exits the app — " +
                "owner device pass 2026-09-01, #4.",
            gate.contains("import androidx.activity.compose.BackHandler"),
        )
        assertTrue(
            "BtRoot's logged-out branch no longer registers a BackHandler.",
            gate.contains("BackHandler(onBack = goBack)"),
        )
        assertTrue(
            "BtRoot drives the pre-login swap from a local boolean again. It must own a " +
                "[PreLoginNav], or the settings sheet is destroyed by the swap and back " +
                "from the Server screen skips it.",
            gate.contains("rememberPreLoginNav()") && !gate.contains("var showServer"),
        )
    }

    /** The diagnostics level inside the sheet pops itself, not the whole sheet. */
    @Test
    fun `the pre-login sheet handles back at its diagnostics level`() {
        val sheet = source("at/bettertrack/app/ui/auth/PreLoginSettingsSheet.kt")
        assertTrue(
            "PreLoginSettingsSheet no longer imports BackHandler; back at the diagnostics " +
                "level would dismiss the whole sheet — two levels for one press (#4).",
            sheet.contains("import androidx.activity.compose.BackHandler"),
        )
        assertTrue(
            "The diagnostics branch no longer registers `BackHandler(onBack = onCloseDiagnostics)`.",
            sheet.contains("BackHandler(onBack = onCloseDiagnostics)"),
        )
        assertTrue(
            "The diagnostics flag is remembered inside the sheet again. It must be hoisted " +
                "onto the pre-login stack, or nothing outside the sheet can pop it.",
            !sheet.contains("var diagnostics by remember"),
        )
    }

    /**
     * Predictive back is ON, and the app's back handling is all Compose-side.
     *
     * `PredictiveBackHandler` (the sheet layer's whole back preview) receives
     * progress events only when the platform's `OnBackInvokedCallback` is
     * enabled for the application. Without the flag the preview is dead code and
     * logcat says so on every press, which is what the owner's pass captured.
     *
     * The second half is the audit that makes the flag safe: androidx forwards
     * the `OnBackPressedDispatcher` to the platform callback, so every
     * Compose-side handler keeps working unchanged — but an Activity-level
     * `onBackPressed()` override would be silently skipped. There is none, and
     * this fails the build if one appears.
     */
    @Test
    fun `predictive back is enabled and no Activity overrides onBackPressed`() {
        val manifestRoots = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        val manifest = (manifestRoots.firstOrNull { it.isFile } ?: error("manifest not found")).readText()
        assertTrue(
            "android:enableOnBackInvokedCallback is gone from the manifest. The sheet " +
                "layer's PredictiveBackHandler then never sees a progress event — see " +
                "ui/shell/BtSheetStack.kt and SHEET_BACK_PREVIEW.",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\""),
        )

        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("sources not found")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readText().lineSequence().any { line ->
                    val code = line.substringBefore("//").trim()
                    code.startsWith("override fun onBackPressed(")
                }
            }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "These files override onBackPressed(). With predictive back enabled the " +
                "platform routes back through OnBackInvokedDispatcher and the override is " +
                "NEVER called — migrate it to an OnBackPressedCallback (or a Compose " +
                "BackHandler) before this flag can stay on.",
            emptyList<String>(),
            offenders,
        )
    }
}
