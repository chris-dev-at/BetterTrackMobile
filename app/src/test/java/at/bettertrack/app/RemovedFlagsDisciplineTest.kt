package at.bettertrack.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * MB-26/27/28 guard: the always-on flags removed on 2026-10-30 stay removed.
 *
 * Each of these was a `const`/`val` that had not moved in months (`true` or
 * `false` since July/August 2026), so the branch it guarded either always ran or
 * never ran. They were folded away: the OAuth scope toggles into the scope
 * constant, the notification kill-switches, and five shipped-feature flags. A name
 * coming back — in code OR in a comment that still points readers at it — is a
 * dead switch being reintroduced, or a KDoc lying about one. Flags that are still
 * live (the paranoid-vaults gate, the fresh-start notice gate, the flavour-split
 * BuildConfig switches) are deliberately NOT listed: see MB-29 in
 * docs/MAINTENANCE_BACKLOG.md.
 */
class RemovedFlagsDisciplineTest {

    private val removed = listOf(
        "ALERTS_SCOPES_ENABLED",
        "FEEDBACK_SCOPE_ENABLED",
        "FEEDBACK_READ_SCOPE_ENABLED",
        "NotificationFlags",
        "stubDeviceRegistration",
        "archiveDeleteEnabled",
        "ChatFlags",
        "FeedbackFlags",
        "AppLockFeatures",
        "betterTrackPinLock",
        "DeleteAccountFeature",
        "BtThemeFeatures",
        "LIGHT_MODE_PUBLIC",
    )

    private val pattern = Regex("""\b(${removed.joinToString("|")})\b""")

    @Test
    fun `no source mentions a removed flag`() {
        val roots = listOf("src/main", "src/test", "app/src/main", "app/src/test")
            .map(::File)
            .filter { it.isDirectory }
        check(roots.isNotEmpty()) { "sources not found" }
        val self = "${javaClass.simpleName}.kt"
        val hits = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts", "xml") && it.name != self }
                .flatMap { f ->
                    f.readLines().withIndex()
                        .filter { (_, line) -> pattern.containsMatchIn(line) }
                        .map { (i, line) -> "${f.path}:${i + 1}: ${line.trim()}" }
                }
                .toList()
        }
        assertEquals("removed flags reappeared:\n${hits.joinToString("\n")}", emptyList<String>(), hits)
    }

    @Test
    fun `the pattern catches the removed names and nothing else`() {
        // Precision as well as recall: a guard that tripped on a neighbouring
        // name would be switched off by the first person it annoyed.
        listOf(
            "if (NotificationFlags.archiveDeleteEnabled) {",
            "assertTrue(DeleteAccountFeature.armed)",
            "allowLight: Boolean = BtThemeFeatures.LIGHT_MODE_PUBLIC,",
            " * [at.bettertrack.app.data.repo.FeedbackFlags.enabled]",
            "alertsScopesEnabled = OAuthConfig.ALERTS_SCOPES_ENABLED,",
            "val x = AppLockFeatures.betterTrackPinLock",
            "ChatFlags.enabled",
            "registrationAction(stubbed = NotificationFlags.stubDeviceRegistration)",
            "OAuthConfig.FEEDBACK_READ_SCOPE_ENABLED",
        ).forEach { assertEquals(it, true, pattern.containsMatchIn(it)) }
        listOf(
            "ParanoidVaultsFlags.enabled",
            "FreshStartNoticeFlags.enabled",
            "BuildConfig.SERVER_SETTING_ENABLED",
            "feedbackEntryVisible(mode)",
            "OAuthConfig.SCOPES",
            "MyChatFlagsHelper",
            "deleteAccountFeatureTest",
            "PinSource.BETTERTRACK",
        ).forEach { assertEquals(it, false, pattern.containsMatchIn(it)) }
    }
}
