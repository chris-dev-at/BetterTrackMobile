package at.bettertrack.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **A card that already has content never regresses to a loading frame.**
 *
 * ## The defect
 *
 * Owner, 2026-08-18: *"ich ändere zb das portfolio und nichts passiert sondern
 * ich muss warten. oder ich ändere welches budget angezeigt wird oder welche
 * aktie ich sehen will und es updated nicht und braucht lange."*
 *
 * It was never a missing repaint — the config activities already write, update
 * and refresh — and `BtWidgetRepository.load` never touches the network. It was
 * the frame policy: `btProvideContent` gave EVERY pass the same
 * [BT_WIDGET_FIRST_FRAME_GRACE_MS] head start and published the loading card
 * when the load lost it. On a reconfigure the load is the heaviest in the family
 * (snapshot + history + cash ledger), so it lost routinely, and the user's card
 * went good content → *"Wird geladen…"* → new content. That reads as slowness
 * even when the total time is unchanged — and, before the sibling fix, that
 * Pending frame was also the wrongly-themed one, so the two reports the owner
 * filed that day were one event.
 *
 * ## The rule
 *
 * A loading card is only ever better than *nothing*. Never painted ⇒ "nothing"
 * is the host's `initialLayout`, so the card is progress and the grace stands
 * (that is the never-a-white-void guarantee, and it must survive intact).
 * Already painted ⇒ "nothing" is the user's own figures, so the previous frame
 * stays up until the real one is ready.
 */
class BtWidgetRepaintPolicyTest {

    private fun projectFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile || it.isDirectory }
            ?: error("$relative not found; tried ${candidates.map { it.absolutePath }}")
    }

    private fun source(): String =
        projectFile("src/main/java/at/bettertrack/app/widget/BtWidgets.kt").readText()

    // ── The decision ──────────────────────────────────────────────────────────

    @Test
    fun `a card with no frame yet gets the loading card`() {
        // The white-void case. Something painted must go up inside the grace.
        assertTrue(btWidgetShouldPublishLoadingFrame(hasPainted = false, loadFinished = false))
    }

    @Test
    fun `a card that already has content never gets the loading card`() {
        // THE fix. Replacing correct figures with "Wird geladen…" to announce
        // that better figures are coming is what the owner experienced as the
        // widget not updating.
        assertFalse(btWidgetShouldPublishLoadingFrame(hasPainted = true, loadFinished = false))
    }

    @Test
    fun `a finished load never shows a loading card, either way`() {
        assertFalse(btWidgetShouldPublishLoadingFrame(hasPainted = false, loadFinished = true))
        assertFalse(btWidgetShouldPublishLoadingFrame(hasPainted = true, loadFinished = true))
    }

    // ── The wait each case gets ───────────────────────────────────────────────

    @Test
    fun `a first paint still races the same short grace`() {
        // Unchanged on purpose: this number is the WORST CASE for how long an
        // instance can sit on the host's initialLayout, and it may not grow.
        assertEquals(BT_WIDGET_FIRST_FRAME_GRACE_MS, btWidgetLoadWaitMs(hasPainted = false))
        assertTrue(
            "the first-frame grace must stay sub-second; it is $BT_WIDGET_FIRST_FRAME_GRACE_MS ms",
            BT_WIDGET_FIRST_FRAME_GRACE_MS in 1..500,
        )
    }

    @Test
    fun `a repaint waits for real content instead of blinking`() {
        assertEquals(BT_WIDGET_REPAINT_TIMEOUT_MS, btWidgetLoadWaitMs(hasPainted = true))
        assertTrue(
            "a repaint must be given far longer than a first paint, or the heavy loads keep " +
                "losing the race and the blink comes back",
            BT_WIDGET_REPAINT_TIMEOUT_MS > BT_WIDGET_FIRST_FRAME_GRACE_MS * 4,
        )
    }

    @Test
    fun `the repaint wait stays inside the broadcast budget`() {
        // GlanceAppWidgetReceiver services the update broadcast under
        // goAsync(); a BroadcastReceiver that holds its async result past ~10 s
        // is killed. The wait plus the composition and RemoteViews publish that
        // follow it all have to fit, so the wait alone must leave real margin.
        assertTrue(
            "a $BT_WIDGET_REPAINT_TIMEOUT_MS ms wait leaves no room under the 10 s goAsync ceiling",
            BT_WIDGET_REPAINT_TIMEOUT_MS <= 7_000,
        )
        // …and it must still be a bound, not a hang: a load that never returns
        // has to reach the syncing card eventually.
        assertTrue(BT_WIDGET_REPAINT_TIMEOUT_MS > 0)
    }

    // ── The wiring the decision depends on ────────────────────────────────────

    @Test
    fun `the painted marker is keyed on the appWidgetId, not on a debug string`() {
        val text = source()
        assertTrue(
            "the marker must resolve a real appWidgetId via GlanceAppWidgetManager",
            text.contains("GlanceAppWidgetManager(context).getAppWidgetId(id)"),
        )
        // GlanceId.toString() is a debug rendering of an internal type; keying
        // on it would orphan every marker the first time Glance changed it.
        assertTrue(
            "the marker is keyed on GlanceId.toString()",
            !Regex("""id\.toString\(\)""").containsMatchIn(text),
        )
    }

    @Test
    fun `a deleted or restored instance is treated as never painted`() {
        val text = source()
        assertTrue(
            "no onDeleted cleanup; the marker file grows for the life of the install",
            text.contains("override fun onDeleted(context: Context, appWidgetIds: IntArray)"),
        )
        // The one that is correctness rather than housekeeping: a restore hands
        // the app NEW ids for instances the launcher has no frames for. A marker
        // wrongly saying "painted" would put a genuinely blank card on the
        // repaint timeout instead of the grace — a longer white-void window.
        assertTrue(
            "no onRestored cleanup; restored ids can inherit a stale painted marker",
            text.contains("override fun onRestored("),
        )
    }

    @Test
    fun `the decision is wired into the frame, not just declared`() {
        // A pure seam nothing calls is a test of nothing. btProvideContent has
        // to consult it: an overrun repaint says "syncing" (the card HAD data
        // and is failing to get newer data), a first paint says "loading".
        assertTrue(
            "btProvideContent no longer consults btWidgetShouldPublishLoadingFrame",
            source().contains("btWidgetShouldPublishLoadingFrame(hasPainted, loadFinished = false)"),
        )
        assertTrue(
            "btProvideContent no longer applies the two-case wait",
            source().contains("withTimeoutOrNull(btWidgetLoadWaitMs(hasPainted))"),
        )
    }

    @Test
    fun `every widget hands its GlanceId to the frame policy`() {
        // Without the id there is no marker, and every instance would be
        // treated as never painted — i.e. the defect, silently reinstated.
        val widgets = projectFile("src/main/java/at/bettertrack/app/widget")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""class\s+\w+\s*:\s*GlanceAppWidget\(\)""").containsMatchIn(it.readText()) }
            .toList()
        assertEquals("expected the ten Glance widgets, found ${widgets.map { it.name }}", 10, widgets.size)
        val offenders = widgets.filter { !it.readText().contains("id = id,") }
        assertTrue(
            "these call btProvideContent without their GlanceId: ${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }
}
