package at.bettertrack.app.ui.feedback

import at.bettertrack.app.R
import at.bettertrack.app.data.repo.FeedbackCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The composer's category picker after the #1400 widening: five options, each with
 * its own words, and no way for a sixth wire value to ship unpickable.
 *
 * ## The order is a product decision, and it is not the wire order
 *
 * [FeedbackCategory]'s declaration order mirrors the deployed enum
 * (`feature, bug, other, help, improvement`) — `other` sits in the middle because
 * that is where the platform appended things, which is fine for a wire contract and
 * wrong for a picker. [FEEDBACK_CATEGORY_ORDER] is the reading order: the two "I
 * want something" options, then the two "something is wrong / I don't understand"
 * options, then the catch-all last, where a catch-all belongs.
 *
 * Keeping them separate has a cost — two lists that can disagree — and this class is
 * the payment: the display list must be a permutation of the enum, so a category the
 * platform adds cannot become a value the app can receive and file but never choose.
 */
class FeedbackComposerCategoryTest {

    @Test
    fun `every category the enum names is drawn, exactly once`() {
        assertEquals(
            "the composer must offer every wire category",
            FeedbackCategory.entries.toSet(),
            FEEDBACK_CATEGORY_ORDER.toSet(),
        )
        assertEquals(
            "no category may be listed twice",
            FEEDBACK_CATEGORY_ORDER.size,
            FEEDBACK_CATEGORY_ORDER.toSet().size,
        )
        assertEquals(5, FEEDBACK_CATEGORY_ORDER.size)
    }

    @Test
    fun `the reading order puts the catch-all last`() {
        assertEquals(
            listOf(
                FeedbackCategory.Feature,
                FeedbackCategory.Improvement,
                FeedbackCategory.Bug,
                FeedbackCategory.Help,
                FeedbackCategory.Other,
            ),
            FEEDBACK_CATEGORY_ORDER,
        )
        assertEquals(FeedbackCategory.Other, FEEDBACK_CATEGORY_ORDER.last())
    }

    @Test
    fun `each category has a label and a subline of its own`() {
        // Copy-pasting a neighbour's string compiles perfectly and tells the user
        // the wrong thing — the exact failure the `when`-with-no-`else` cannot
        // catch on its own.
        val labels = FeedbackCategory.entries.map { feedbackCategoryLabelRes(it) }
        val sublines = FeedbackCategory.entries.map { feedbackCategorySubRes(it) }
        assertEquals(FeedbackCategory.entries.size, labels.toSet().size)
        assertEquals(FeedbackCategory.entries.size, sublines.toSet().size)
        assertTrue((labels + sublines).none { it == 0 })
        // …and no subline is reused as a label.
        assertTrue(labels.toSet().intersect(sublines.toSet()).isEmpty())
    }

    @Test
    fun `each category has a glyph, and feature and improvement do not share one`() {
        val icons = FeedbackCategory.entries.map { feedbackCategoryIcon(it) }
        assertEquals(
            "two categories drawn with the same glyph read as one option twice",
            FeedbackCategory.entries.size,
            icons.map { it.name }.toSet().size,
        )
    }

    @Test
    fun `the two new labels are the widening's own strings`() {
        assertEquals(
            R.string.bt_feedback_cat_improvement,
            feedbackCategoryLabelRes(FeedbackCategory.Improvement),
        )
        assertEquals(
            R.string.bt_feedback_cat_help,
            feedbackCategoryLabelRes(FeedbackCategory.Help),
        )
    }

    /**
     * The label that HAD to change, checked in the resource file itself.
     *
     * Until 2026-08-20 `feature` was drawn "Feature/Verbesserung" / "Feature or
     * improvement", because one wire value covered both meanings. `improvement` is
     * its own value now, so a label still promising both would send every
     * improvement to the `feature` bucket while looking entirely correct on screen
     * — a wrong-routing bug with no visible symptom, which is why it is pinned in
     * the strings rather than left to review.
     */
    @Test
    fun `the feature label no longer claims to cover improvements`() {
        mapOf("" to "improvement", "-de" to "Verbesserung").forEach { (qualifier, word) ->
            val body = string(qualifier, "bt_feedback_cat_feature")
            assertTrue(
                "values$qualifier feature label must not still say \"$word\": $body",
                !body.contains(word, ignoreCase = true),
            )
        }
        // And the improvement label says it, in both languages.
        assertTrue(string("", "bt_feedback_cat_improvement").contains("Improvement"))
        assertTrue(string("-de", "bt_feedback_cat_improvement").contains("Verbesserung"))
    }

    /**
     * "Anything that is neither of the above" was true of three options and is a
     * plain factual error about five. Copy that counts its neighbours has to be
     * re-read whenever the neighbours change.
     */
    @Test
    fun `the other subline no longer counts two neighbours`() {
        assertTrue(!string("", "bt_feedback_cat_other_sub").contains("neither"))
        assertTrue(!string("-de", "bt_feedback_cat_other_sub").contains("keines von beiden"))
    }

    private fun string(qualifier: String, name: String): String {
        val path = "src/main/res/values$qualifier/strings.xml"
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("strings.xml not found for values$qualifier")
        return Regex("""<string\s+name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?: error("$name missing from values$qualifier")
    }
}
