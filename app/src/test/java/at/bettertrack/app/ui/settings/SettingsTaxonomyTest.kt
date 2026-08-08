package at.bettertrack.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the 2026-08-08 settings regroup (owner order: *"group the
 * settings a bit more like web control center and like group new portfolio
 * defaults. and merge account and profile"*, plus *"authorized apps and
 * connections should be handled inside the app and should not redirect"*).
 *
 * ## Why this is a test and not a KDoc note
 *
 * The same reason [at.bettertrack.app.ui.shell.TopBarNavigationTest] exists: the
 * thing being protected is an *arrangement*, and an arrangement decays one
 * defensible addition at a time. The screen has already been reorganised twice;
 * both times the rationale lived only in a comment, and both times a later change
 * put a row back where the comment said it must not go. The order of the groups,
 * the fact that two named surfaces are native rather than hand-offs, and the fact
 * that Account and Profile are one section, are all invariants over the file
 * rather than facts about any one row — so they are checked by reading the source,
 * exactly as the string-parity and top-bar guards do.
 *
 * ## What it deliberately does NOT check
 *
 * The rows *inside* each group, or their order. Rows move for good reasons and a
 * test that pinned all of them would be a second copy of the screen that someone
 * has to update in lockstep — which is how a guard turns into a chore and then
 * into a `@Ignore`. The group skeleton is the part the owner actually ruled on.
 */
class SettingsTaxonomyTest {

    private fun settingsSource(): String {
        val candidates = listOf(
            File("src/main/java/at/bettertrack/app/ui/settings/SettingsScreen.kt"),
            File("app/src/main/java/at/bettertrack/app/ui/settings/SettingsScreen.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("SettingsScreen.kt not found; tried ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    /**
     * The source with its comments removed.
     *
     * Rules about what the screen *does* must be checked against code only. The
     * screen documents several deliberate absences by name — the visibility
     * default, true black, interface scale — and those notes are the whole reason
     * the absences survive contact with the next contributor. A naive substring
     * check would punish the file for explaining itself, so the explanations are
     * stripped before the code is judged.
     */
    private fun codeOnly(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    /** Section headers in the order the screen renders them. */
    private fun renderedSections(source: String): List<String> {
        // Only the body's own headers — the KDoc table above the composable names
        // several of the same keys in prose, so the scan starts at the composable.
        val body = source.substringAfter("fun SettingsScreen(")
        return Regex("""BtSectionHeader\(stringResource\(R\.string\.(\w+)\)\)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * The group skeleton mirrors the web control center's taxonomy, in order.
     *
     * Adapted rather than copied, and the three adaptations are the owner's:
     * Account absorbs Profile, Appearance is split out of Account because it is
     * device-scoped and must survive a Drive-only install with no account, and
     * "new portfolio defaults" is its own named group.
     */
    @Test
    fun `settings groups mirror the web control center taxonomy in order`() {
        assertEquals(
            listOf(
                "bt_settings_account_section",
                "bt_settings_appearance_section",
                "bt_settings_signin_section",
                "bt_settings_defaults_section",
                "bt_settings_preferences_section",
                "bt_settings_privacy_section",
                "bt_settings_integrations_section",
                "bt_settings_about_section",
                "bt_settings_developer_section",
                "bt_settings_danger_section",
            ),
            renderedSections(settingsSource()),
        )
    }

    /**
     * Account and Profile are ONE section (owner order 2026-08-08).
     *
     * The merge is only real if the profile rows moved *into* the account group
     * rather than the profile header being renamed: so the retired header must be
     * gone, and the icon picker — the one profile field a client may write — must
     * still be on the screen.
     */
    @Test
    fun `account and profile are one section`() {
        val source = settingsSource()
        assertTrue(
            "the retired Profile section header must not come back — Account absorbed it",
            !source.contains("bt_settings_profile_section"),
        )
        assertEquals(
            "Account is rendered exactly once",
            1,
            renderedSections(source).count { it == "bt_settings_account_section" },
        )
        assertTrue(
            "the profile icon picker must survive the merge",
            source.contains("R.string.bt_settings_profile_icon"),
        )
        assertTrue(
            "the merged section leads with the identity block",
            source.contains("AccountIdentity("),
        )
    }

    /**
     * Connections and Authorized apps are NATIVE — they must not redirect.
     *
     * The owner named exactly these two. The check is on the *hand-off*, not on
     * the row: a `BtWebLinkRow` opening `/control/connections` or
     * `/control/authorized-apps` is precisely the thing that was ordered away, and
     * it is the shape a future "just link it for now" change would take.
     */
    @Test
    fun `connections and authorized apps do not redirect to the web`() {
        val source = settingsSource()
        val code = codeOnly(source)
        listOf("/control/connections", "/control/authorized-apps").forEach { path ->
            assertTrue(
                "Settings must not hand $path off to the web — it is a native screen now",
                !code.contains("\"$path\""),
            )
        }
        assertTrue(
            "Connections opens the native screen",
            source.contains("onClick = onOpenConnections"),
        )
        assertTrue(
            "Authorized apps opens the native screen",
            source.contains("onClick = onOpenAuthorizedApps"),
        )
    }

    /**
     * The other three integration surfaces stay hand-offs.
     *
     * Each shows a secret exactly once at creation time, which is a job for a full
     * keyboard and a page you can copy out of. The owner named only two; this
     * pins that the regroup did not quietly widen the order.
     */
    @Test
    fun `api keys, oauth apps and webhooks remain web hand-offs`() {
        val source = settingsSource()
        listOf("/control/api", "/control/oauth-apps", "/control/webhooks").forEach { path ->
            assertTrue("$path must stay a web hand-off", source.contains("\"$path\""))
        }
    }

    /**
     * The new-portfolio-defaults group must not resurrect default VISIBILITY.
     *
     * The web forbids a new-portfolio visibility default (web test #377), so
     * offering the control here would promise something the platform does not
     * keep. The group is exactly the place a future contributor would "complete"
     * by adding it, which is why the check hangs off this test rather than a note.
     */
    @Test
    fun `portfolio defaults never offer a visibility default`() {
        assertTrue(
            "defaultPortfolioVisibility must not be read or written by the app (web test #377)",
            !codeOnly(settingsSource()).contains("defaultPortfolioVisibility"),
        )
    }
}
