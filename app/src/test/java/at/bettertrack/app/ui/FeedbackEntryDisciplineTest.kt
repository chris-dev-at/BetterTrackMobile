package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * There are exactly two doors to the feedback composer, and both must ask the same
 * two questions before they are drawn.
 *
 * ## The rule
 *
 * A feedback entry row is rendered only when
 * [at.bettertrack.app.data.repo.feedbackEntryVisible] says so — whether this
 * install has a BetterTrack account
 * ([at.bettertrack.app.data.storage.BtSurface.ACCOUNT_SETTINGS]).
 *
 * ## Why the account half matters, and why a source scan guards it
 *
 * `POST /feedback` is a server route authenticated by a bearer token. A
 * Drive-autonomous install has no BetterTrack account and therefore no token, so a
 * row there opens a composer whose Send button is permanently disabled behind its
 * signed-in check — "a row that opens a form that can only fail", the exact outcome
 * the old global capability flag (removed 2026-10-30, MB-28) never encoded: the
 * ABOUT group the row sits in is not gated on the account the way the account,
 * notification and log-out sections are.
 *
 * [at.bettertrack.app.data.repo.FeedbackTest] pins the helper's *behaviour*. This
 * file pins that the two call sites actually go through it — a behavioural test
 * cannot see a third door being added next month with a bare flag check, and the
 * project has no Compose UI suite that could. The regression would be invisible in
 * review.
 */
class FeedbackEntryDisciplineTest {

    /** The screens that own a feedback entry row today. */
    private val entryScreens = listOf(
        "at/bettertrack/app/ui/settings/SettingsScreen.kt",
        "at/bettertrack/app/ui/settings/AboutScreen.kt",
    )

    private fun uiRoot(): File {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
    }

    /**
     * Source with `//` comments and KDoc body lines stripped.
     *
     * These files necessarily *explain* the gate — and the history of the bare flag
     * they no longer use — in prose. A naive substring check would read the
     * explanation as the code and let a genuinely un-gated row pass.
     */
    private fun code(file: File): String = file.readLines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .joinToString("\n")

    /** Every ui source, relative path -> its comment-stripped code. */
    private fun uiSources(): List<Pair<String, String>> {
        val root = uiRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to code(it) }
            .toList()
    }

    private fun sourceOf(path: String): String {
        val candidates = listOf(File("src/main/java/$path"), File("app/src/main/java/$path"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("$path not found; tried ${candidates.map { it.absolutePath }}")
        return code(file)
    }

    @Test
    fun `both feedback entry rows are gated on the shared helper`() {
        entryScreens.forEach { path ->
            val code = sourceOf(path)
            assertTrue(
                "$path renders a feedback row but does not call feedbackEntryVisible",
                code.contains("feedbackEntryVisible("),
            )
        }
    }

    @Test
    fun `the helper is passed the install's storage mode, not a literal`() {
        // `feedbackEntryVisible(StorageMode.SERVER)` would type-check and be a lie
        // on a Drive install. Both screens resolve the real mode the same way the
        // rest of the app does — stored mode through the debug Drive gate.
        entryScreens.forEach { path ->
            val code = sourceOf(path)
            assertTrue(
                "$path must read the live storage mode before gating the feedback row",
                code.contains("AppGraph.gatedStorageMode("),
            )
            assertTrue(
                "$path must pass the resolved mode to feedbackEntryVisible",
                code.contains("feedbackEntryVisible(storageMode)"),
            )
        }
    }
}
