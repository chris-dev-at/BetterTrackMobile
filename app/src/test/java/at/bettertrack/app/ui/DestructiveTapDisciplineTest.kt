package at.bettertrack.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A destructive tap asks first, and says how it went (owner device pass
 * 2026-09-01, #27 + #6).
 *
 * Two properties that are invisible until they bite, both found on the same
 * pass and both fixed the same way — by giving a one-tap action a door:
 *
 *  - *"Watchlist rows carry a one-tap inline delete icon; not verified whether
 *    it confirms (not tapped on the live account)."* It did not. The trash glyph
 *    called `removeAsset` straight through, and the result was dropped, so a
 *    refused delete was a row that quietly stayed put.
 *  - *"Insights edit mode has no exit control … Back leaves Insights
 *    entirely."* A mode with no visible way out, whose only exit was the back
 *    arrow — which everywhere else in the app means "leave the screen".
 */
class DestructiveTapDisciplineTest {

    private fun uiSource(name: String): String {
        val roots = listOf(
            File("src/main/java/at/bettertrack/app/ui"),
            File("app/src/main/java/at/bettertrack/app/ui"),
        )
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("ui sources not found; tried ${roots.map { it.absolutePath }}")
        return (
            root.walkTopDown().firstOrNull { it.isFile && it.name == name }
                ?: error("$name not found under ${root.absolutePath}")
            ).readText()
    }

    /**
     * The watchlist row's trash icon opens a confirmation, from the bottom edge.
     *
     * Anchored to the fact that the icon's handler arms STATE rather than
     * calling the mutation: an assertion that a confirm sheet merely exists in
     * the file would still pass if someone re-pointed the icon straight at
     * `vm.removeAsset`, which is exactly the shape of the defect.
     */
    @Test
    fun `the watchlist row's delete confirms before it deletes`() {
        val src = uiSource("WatchlistScreen.kt")
        assertTrue(
            "The watchlist row's trash icon calls vm.removeAsset directly again — a " +
                "one-tap delete on live data (owner device pass #27).",
            !src.contains("onRemove = { selectedId?.let { vm.removeAsset("),
        )
        assertTrue(
            "The row's trash icon no longer arms a confirmation.",
            src.contains("onRemove = { removeConfirm = item }"),
        )
        assertTrue(
            "WatchlistRemoveConfirmSheet is gone. User-facing confirmations pop from the " +
                "BOTTOM in this app — an anchored dialog here would be the one exception.",
            src.contains("private fun WatchlistRemoveConfirmSheet(") &&
                src.contains("ModalBottomSheet("),
        )
    }

    /**
     * ...and the outcome is reported INLINE.
     *
     * The standing rule for verdict-shaped outcomes (deletions): a snackbar over
     * a sheet is the one place a verdict goes unread. `removeAsset` used to
     * discard its `BtResult` entirely.
     */
    @Test
    fun `a refused watchlist removal is said out loud`() {
        val src = uiSource("WatchlistScreen.kt")
        assertTrue(
            "WatchlistViewModel.removeAsset drops its result again; a refused delete is a " +
                "row that silently stays put.",
            src.contains("_removeFailure.value = (watchlist.removeAsset("),
        )
        assertTrue(
            "The panel no longer renders the removal failure inline.",
            src.contains("removeFailure?.let") && src.contains("BtInlineError("),
        )
    }

    /**
     * Insights edit mode has both doors: system back ends the MODE, and the top
     * bar carries a visible "Done".
     *
     * The `enabled = editing` gate is load-bearing in both directions — an
     * ungated handler would eat the back that leaves Insights, which is the
     * opposite complaint.
     */
    @Test
    fun `insights edit mode can be left`() {
        val src = uiSource("InsightsStudioScreen.kt")
        assertTrue(
            "InsightsStudioScreen no longer imports BackHandler; system back leaves " +
                "Insights entirely instead of ending edit mode (owner device pass #6).",
            src.contains("import androidx.activity.compose.BackHandler"),
        )
        assertTrue(
            "The edit-mode BackHandler is gone or is no longer gated on `editing`. " +
                "Ungated it would swallow the back that leaves the screen.",
            src.contains("BackHandler(enabled = editing) { editing = false }"),
        )
        assertTrue(
            "Edit mode has no visible exit in the top bar. The back arrow is not one: it " +
                "means \"leave the screen\" on every other page in the app.",
            src.contains("action = if (editing)") && src.contains("R.string.bt_insight_done"),
        )
    }
}
