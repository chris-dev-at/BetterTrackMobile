package at.bettertrack.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the pre-login settings surface (owner order 2026-08-08).
 *
 * Owner verbatim: *"on the login page there should be a small setting thing up
 * top corner so you move the change server and other settings you need to do
 * before login there. still keep the display of the server on the bottom but
 * only visual text no button."*
 *
 * ## Why these are source assertions
 *
 * Three of the four properties below are things a screenshot cannot tell you and
 * a Compose UI test would need a device for — and all four are the kind that get
 * undone by a well-meaning edit rather than by a bug:
 *
 *  - "no button" is invisible once it looks right. A `TextButton` styled to look
 *    like a line of text passes every visual check and still ripples, still takes
 *    focus, and still announces itself as a button.
 *  - The gear opening a NAV ROUTE would compile and work, and would quietly put a
 *    pre-auth destination in the shell's graph — the one thing the login screen's
 *    outside-the-NavHost position exists to avoid.
 *  - A row on this sheet that needs an account is a row that opens a 401 for the
 *    only people who can ever see it.
 *
 * Written the same way [at.bettertrack.app.ui.shell.TopBarNavigationTest] is, and
 * for the same reason: an invariant only one person is holding in their head is
 * an invariant with a half-life.
 */
class PreLoginSettingsTest {

    private fun uiSource(name: String): String {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == name }
            ?: error("$name not found under ${root.absolutePath}")
        return file.readText()
    }

    /** Source with `//` and KDoc lines removed — prose must not satisfy an assertion. */
    private fun code(src: String): String = src.lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    /**
     * The bottom server line is DISPLAY, not a control.
     *
     * It states which backend the password is about to be handed to, which is all
     * almost anyone needs from it. The tap it used to carry now lives in exactly
     * one place — the gear — so that there is one address for "change something
     * before signing in" rather than two, one of which was a line of small grey
     * text at the very bottom of the screen.
     */
    @Test
    fun `the login screen's server line is plain text`() {
        val src = code(uiSource("LoginScreen.kt"))
        assertTrue(
            "LoginScreen no longer renders the bottom `Server: <host>` line at all. The " +
                "owner asked for it to stay — as text.",
            src.contains("R.string.bt_login_server_label"),
        )
        // Exactly what wraps the line: everything between the guard that decides
        // whether to draw it and the string it draws.
        val guard = "if (serverHost != null) {"
        assertTrue(
            "LoginScreen no longer guards the server line on `serverHost` alone. It must not " +
                "depend on `onOpenServer` either — the line is display, and display does not " +
                "need a destination.",
            src.contains(guard),
        )
        val block = src.substringAfter(guard).substringBefore("R.string.bt_login_server_label")
        assertTrue(
            "The bottom server line is not rendered by a plain `Text(`.",
            block.contains("Text("),
        )
        listOf("TextButton", "Button(", "clickable", "onClick", "selectable").forEach { control ->
            assertTrue(
                "The bottom server line is wrapped in `$control`. Owner order 2026-08-08: " +
                    "\"only visual text no button\" — the way to CHANGE the server is the " +
                    "corner gear.",
                !block.contains(control),
            )
        }
        assertTrue(
            "`onOpenServer` is wired to a control on the login screen itself. It belongs to " +
                "the pre-login settings sheet's Server row and nowhere else.",
            !src.contains("onClick = onOpenServer"),
        )
    }

    /**
     * The gear is in the top corner, is described as Settings, and opens the
     * sheet. Its `contentDescription` is the assertion that matters most: it is
     * the only name the affordance has — there is no label next to it — so an
     * icon button that lost it would be an unreachable control for a TalkBack
     * user, and the pre-login settings would be reachable by sighted users only.
     */
    @Test
    fun `the login screen carries a settings gear that opens the pre-login sheet`() {
        val src = code(uiSource("LoginScreen.kt"))
        assertTrue(
            "LoginScreen no longer renders a settings gear — owner order 2026-08-08.",
            src.contains("Icons.Outlined.Settings"),
        )
        assertTrue(
            "The login screen's gear has no `bt_dest_settings` contentDescription. It is an " +
                "unlabelled icon on a screen with no other chrome; the description is the " +
                "only name it has.",
            src.contains("contentDescription = stringResource(R.string.bt_dest_settings)"),
        )
        assertTrue(
            "The gear no longer opens PreLoginSettingsSheet.",
            src.contains("PreLoginSettingsSheet("),
        )
    }

    /**
     * The sheet is the app's sheet, and it is NOT a nav destination.
     *
     * The login screen is rendered outside the NavHost (see `BtRoot`), so there is
     * no graph to register in while logged out — and adding one to get a sheet
     * would put a pre-auth route in the shell's graph, which the shell's own guard
     * counts. Driving [at.bettertrack.app.ui.shell.BtSheet] from local state gives
     * the identical grabber, scrim, pull-down and predictive-back behaviour with
     * no route at all.
     */
    @Test
    fun `the pre-login sheet drives BtSheet directly and registers no route`() {
        val src = code(uiSource("PreLoginSettingsSheet.kt"))
        assertTrue(
            "PreLoginSettingsSheet no longer composes the app's own BtSheet. A hand-rolled " +
                "sheet here would be the one surface in the app that dismisses differently.",
            src.contains("BtSheet(host)") && src.contains("BtSheetHostState("),
        )
        listOf("btSheet<", "composable<", "NavGraphBuilder", "navigate(").forEach { nav ->
            assertTrue(
                "PreLoginSettingsSheet reaches for navigation (`$nav`). Logged out there is " +
                    "no NavHost — see this test's KDoc.",
                !src.contains(nav),
            )
        }
    }

    /**
     * Every row on the sheet works with no session.
     *
     * The rule the owner's *"settings you need to do before login"* states, read
     * strictly: Server (device prefs, restart-applied), Theme (device prefs) and
     * Language (per-app locale). None of the three touches the network. A row that
     * called an account endpoint would be a row that only ever runs unauthenticated
     * — its single audience is people who have not signed in.
     *
     * `LocaleManager`'s account mirror is the specific trap: Settings→Language
     * best-effort PATCHes the locale to the account, and copying that call into
     * this file would send it with no session on every language change.
     */
    @Test
    fun `nothing on the pre-login sheet needs an account`() {
        val src = code(uiSource("PreLoginSettingsSheet.kt"))
        val offenders = listOf(
            "accountRepository",
            "authRepository",
            "apiCall(",
            "updateAccountLocale",
        ).filter { src.contains(it) }
        assertEquals(
            "The pre-login settings sheet reaches for an account. Only logged-OUT users can " +
                "ever see it, so every row on it must work with no session — see this " +
                "test's KDoc.",
            emptyList<String>(),
            offenders,
        )
    }
}
