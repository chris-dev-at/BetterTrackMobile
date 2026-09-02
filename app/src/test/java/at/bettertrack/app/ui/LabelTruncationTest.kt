package at.bettertrack.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three labels that ran out of room (owner device pass 2026-09-01, #21).
 *
 * *"Truncated strings: mute description `…Deine Auswahl ble…`; Leute tab chip
 * `Meine Frei…`; update dialog `Diese Version ignorieren` wraps into the
 * adjacent action."*
 *
 * ## Why these are source assertions
 *
 * Every one of the three was correct in English and wrong in German, and every
 * one of them was introduced by an edit that looked right on the phone the
 * author had. A layout that clips only in the language you do not read is
 * invisible to a screenshot review and invisible to a unit test that checks
 * behaviour — what can be checked is the *structural cause*, which in all three
 * cases is a single expression: a `maxLines`, an equal-width split, a
 * `weight(1f)` on a text button.
 *
 * They are also the cases where the fix is one line and the regression is one
 * line, which is precisely the ratio a mechanical guard is for.
 */
class LabelTruncationTest {

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

    private fun strings(qualifier: String): String {
        val roots = listOf(File("src/main/res"), File("app/src/main/res"))
        val root = roots.firstOrNull { it.isDirectory } ?: error("res not found")
        return File(root, "$qualifier/strings.xml").readText()
    }

    // ── (a) The mute rule, in a row shared by ~130 others ────────────────────

    /**
     * [at.bettertrack.app.ui.components.BtGroupRow] can be told to let a
     * subtitle wrap.
     *
     * The default stays two lines: a settings list is *scanned*, and rows of
     * wildly uneven height are harder to scan than rows that end in an ellipsis.
     * What was missing was the opt-out for the handful of rows whose subtitle is
     * a rule rather than a hint.
     */
    @Test
    fun `the group row can let a subtitle wrap`() {
        val row = uiSource("BtGroup.kt")
        assertTrue(
            "BtGroupRow lost its `subtitleMaxLines` parameter; the rows whose subtitle is " +
                "a SENTENCE have no way to finish it — owner report #21.",
            row.contains("subtitleMaxLines: Int = 2"),
        )
        assertTrue(
            "BtGroupRow's subtitle no longer honours `subtitleMaxLines` — the parameter is " +
                "there and does nothing, which is worse than not having it.",
            row.contains("maxLines = subtitleMaxLines"),
        )
    }

    /**
     * The mute switch's subtitle is the rule that makes the switch safe to flip
     * — "nothing is delivered on any channel, your choices are kept" — and it
     * clipped mid-word beside the Switch.
     */
    @Test
    fun `the mute-all row lets its rule finish`() {
        val screen = uiSource("NotificationSettingsScreen.kt")
        val row = screen.substringAfter("R.string.bt_notif_mute_all_title")
            .substringBefore("BtFormError")
        assertTrue(
            "The mute-all row no longer passes `subtitleMaxLines = Int.MAX_VALUE`. Its " +
                "subtitle read \"…Deine Auswahl ble…\" on the owner's phone (#21).",
            row.contains("subtitleMaxLines = Int.MAX_VALUE"),
        )
        // The German is the case that actually clips; if it is ever shortened
        // past the point of needing this, the assertion above should be revisited
        // rather than silently left true.
        assertTrue(
            "bt_notif_mute_all_sub is missing from the German strings.",
            strings("values-de").contains("name=\"bt_notif_mute_all_sub\""),
        )
    }

    // ── (b) The People tab's third segment ───────────────────────────────────

    /**
     * The three segments are one control, so they keep equal widths — and the
     * row grows to fit the longest label instead of cutting it.
     *
     * The rejected alternative was shortening the German, and it costs the
     * distinction the label draws: "Geteilt" is what others shared with me,
     * "Meine Freigaben" is what I shared with them.
     */
    @Test
    fun `the People segments grow rather than truncate`() {
        val social = uiSource("SocialScreen.kt")
        val tabs = social.substringAfter("private fun SegmentedTabs(")
            .substringBefore("private fun Segment(")
        assertTrue(
            "SegmentedTabs no longer measures at IntrinsicSize.Min, so a segment that " +
                "needs two lines is taller than the two beside it (#21).",
            tabs.contains("height(IntrinsicSize.Min)"),
        )
        assertTrue(
            "SegmentedTabs' pills no longer fillMaxHeight; equal widths without equal " +
                "heights is three pills, not one control.",
            tabs.contains("Modifier.weight(1f).fillMaxHeight()"),
        )
        val segment = social.substringAfter("private fun Segment(")
            .substringBefore("\n}\n")
        assertTrue(
            "The segment label is back to one line. DE \"Meine Freigaben\" reads " +
                "\"Meine Frei…\" at that width — owner device pass #21.",
            segment.contains("maxLines = 2"),
        )
        assertEquals(
            "bt_social_tab_my_shares is not the label this test thinks it is; re-check " +
                "which chip truncated before relaxing anything above.",
            "Meine Freigaben",
            Regex("""name="bt_social_tab_my_shares">([^<]*)<""")
                .find(strings("values-de"))?.groupValues?.get(1),
        )
    }

    // ── (c) The update dialog's two quiet actions ────────────────────────────

    /**
     * "Remind me later" and "Ignore this version" are stacked, not split.
     *
     * Half a dialog is ~120dp of label, and the German ignore verb does not fit
     * in it — it wrapped across into the button beside it, so the two read as
     * one run-on phrase. Shortening the German only moves the cliff; the next
     * locale or a larger font scale walks off it again.
     */
    @Test
    fun `the update dialog does not split its tertiary actions across a row`() {
        val notifier = uiSource("UpdateNotifier.kt")
        val offer = notifier.substringAfter("private fun OfferBody(").substringBefore("\n}\n")
        assertTrue(
            "OfferBody puts its two tertiary actions in a Row again. Two weight(1f) " +
                "halves of a dialog cannot hold \"Diese Version ignorieren\" — owner " +
                "device pass #21.",
            !offer.contains("Row("),
        )
        assertEquals(
            "OfferBody's tertiary actions are no longer full-width rows of their own.",
            2,
            Regex("""TextButton\(\n\s*onClick = on(Remind|Ignore),\n\s*modifier = Modifier\.fillMaxWidth\(\),""")
                .findAll(offer).count(),
        )
    }
}
