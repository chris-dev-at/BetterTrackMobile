package at.bettertrack.app.ui

import androidx.compose.material3.SnackbarDuration
import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtMessage
import at.bettertrack.app.data.api.asMessage
import at.bettertrack.app.ui.components.BtSnackbarController
import at.bettertrack.app.ui.components.BtSnackbarMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S6 P1-9 contract, tested at the seam that matters: what a screen HANDS to
 * the single app-level snackbar. Rendering is Compose's job; the decisions worth
 * pinning are that failures are given time to be read, that a Retry action only
 * appears when a caller supplied one, and that nothing raw slips into the body.
 */
class BtSnackbarStateTest {

    private fun recorder(): Pair<BtSnackbarController, MutableList<BtSnackbarMessage>> {
        val seen = mutableListOf<BtSnackbarMessage>()
        return BtSnackbarController { seen += it } to seen
    }

    @Test
    fun `confirmation is short and carries no action`() {
        val (controller, seen) = recorder()
        controller.show(R.string.bt_action_retry)
        assertEquals(1, seen.size)
        assertEquals(SnackbarDuration.Short, seen[0].duration)
        assertNull("a confirmation must not offer Retry", seen[0].onAction)
        assertNull(seen[0].diagnostic)
    }

    @Test
    fun `confirmation passes its format arguments through`() {
        val (controller, seen) = recorder()
        // Two arguments, not one: the app's most useful confirmations name both
        // the thing and the audience ("«Tech» is now shared with Family").
        controller.show(R.string.bt_action_retry, "Tech", "Family")
        assertEquals(listOf<Any>("Tech", "Family"), seen[0].formatArgs)
    }

    @Test
    fun `failure is long so it can actually be read`() {
        val (controller, seen) = recorder()
        controller.showError(BtMessage(R.string.bt_err_unknown))
        assertEquals(SnackbarDuration.Long, seen[0].duration)
    }

    @Test
    fun `failure offers Retry only when a caller supplied one`() {
        val (controller, seen) = recorder()
        controller.showError(BtMessage(R.string.bt_err_unknown))
        controller.showError(BtMessage(R.string.bt_err_unknown), onRetry = {})
        assertNull(seen[0].onAction)
        assertNotNull(seen[1].onAction)
    }

    @Test
    fun `the supplied retry lambda is the one that runs`() {
        val (controller, seen) = recorder()
        var ran = 0
        controller.showError(BtMessage(R.string.bt_err_unknown), onRetry = { ran++ })
        seen[0].onAction!!.invoke()
        assertEquals(1, ran)
    }

    @Test
    fun `a known error shows app copy and no server text`() {
        val (controller, seen) = recorder()
        val err = BtApiError(409, BtApiError.Codes.CASH_TAG_NAME_TAKEN, "Tag name already in use.")
        controller.showError(err.asMessage())
        assertEquals(BtMessage(R.string.bt_err_cash_tag_name_taken).res, seen[0].res)
        assertNull("catalogued copy must not be shadowed by the server's", seen[0].diagnostic)
    }

    @Test
    fun `an unknown error keeps the server text as a diagnostic`() {
        val (controller, seen) = recorder()
        val err = BtApiError(400, "SHIPPED_LATER", "Widget quota exceeded.")
        controller.showError(err.asMessage())
        assertEquals("Widget quota exceeded.", seen[0].diagnostic)
        // Still a resource, never the raw string promoted to the primary line.
        assertTrue(seen[0].res != 0)
    }

    // ── Counted confirmations ────────────────────────────────────────────────

    @Test
    fun `a counted confirmation carries the plural resource and the count`() {
        val (controller, seen) = recorder()
        controller.showQuantity(R.plurals.bt_rules_apply_done, 3)
        assertEquals(R.plurals.bt_rules_apply_done, seen[0].pluralRes)
        assertEquals(3, seen[0].quantity)
        // The count must reach the resource, not be concatenated onto a fixed
        // noun — German declines differently at one than at zero-or-many.
        assertNull("a plural body must not also carry a string body", seen[0].res)
    }

    @Test
    fun `a count of one is still routed through the plural resource`() {
        val (controller, seen) = recorder()
        controller.showQuantity(R.plurals.bt_rules_apply_done, 1)
        assertEquals(1, seen[0].quantity)
        assertEquals(R.plurals.bt_rules_apply_done, seen[0].pluralRes)
    }

    @Test
    fun `an undoable confirmation labels its action Undo, not Try again`() {
        // The action label is a parameter precisely so moving an existing Undo
        // onto this host cannot silently relabel it as a retry.
        val (controller, seen) = recorder()
        var undone = false
        controller.showUndoable(R.string.bt_err_unknown, R.string.bt_action_cancel) { undone = true }
        assertEquals(R.string.bt_action_cancel, seen[0].actionLabel)
        assertNotNull(seen[0].onAction)
        seen[0].onAction!!.invoke()
        assertTrue(undone)
    }

    @Test
    fun `a failure labels its action Try again`() {
        val (controller, seen) = recorder()
        controller.showError(BtMessage(R.string.bt_err_unknown), onRetry = {})
        assertEquals(R.string.bt_action_retry, seen[0].actionLabel)
    }

    @Test
    fun `a message must carry exactly one body`() {
        // Neither body renders blank; both silently drops one. Fail loudly.
        assertThrows(IllegalArgumentException::class.java) { BtSnackbarMessage() }
        assertThrows(IllegalArgumentException::class.java) {
            BtSnackbarMessage(res = R.string.bt_err_unknown, pluralRes = R.plurals.bt_rules_apply_done)
        }
    }

    @Test
    fun `the no-op default controller swallows messages without failing`() {
        // Screens rendered outside the shell (previews, the component gallery)
        // must not crash just because there is nowhere to show feedback — which
        // is why LocalBtSnackbar's default is this, not an error.
        val noop = BtSnackbarController {}
        noop.show(R.string.bt_action_retry)
        noop.showError(BtMessage(R.string.bt_err_unknown))
    }
}
