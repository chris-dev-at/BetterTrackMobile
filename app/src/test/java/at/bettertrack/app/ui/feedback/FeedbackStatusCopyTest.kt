package at.bettertrack.app.ui.feedback

import at.bettertrack.app.R
import at.bettertrack.app.data.api.dto.FeedbackStatus
import at.bettertrack.app.data.repo.FeedbackCategory
import at.bettertrack.app.ui.components.BtBadgeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The display copy for the submission lifecycle — the half of this feature that
 * is genuinely ours to get right.
 *
 * The wire values are the platform's and never move. The words a user reads are
 * the product: the owner asked for *"status rejected or like saved as future idea.
 * or working on rn… make up better names but like that"*, and what came back is
 *
 * | wire | DE | EN | tone |
 * |---|---|---|---|
 * | `new` | Eingegangen | Received | neutral |
 * | `triaged` | Angesehen | Reviewed | neutral |
 * | `working_on_it` | In Arbeit | In progress | gold |
 * | `saved_as_future_idea` | Für später vorgemerkt | Saved for later | gold |
 * | `declined` | Nicht umgesetzt | Not planned | neutral |
 * | `shipped` | Umgesetzt | Shipped | gain |
 *
 * These tests pin the structure of that table, not the sentences themselves
 * (`StringParityTest` owns the sentences): every status has copy, no two statuses
 * share it, and the tone rules hold — most importantly that **`declined` is never
 * red**, which is the one colour choice that would turn a product decision into an
 * accusation.
 */
class FeedbackStatusCopyTest {

    @Test
    fun `every status has its own label`() {
        // The mapping is a `when` with no `else`, so a new constant fails to
        // compile until somebody writes both languages for it. This is the other
        // half of that guard: the branch that gets written must be a NEW string,
        // not a copy-paste of its neighbour's, which compiles perfectly and tells
        // the user the wrong thing.
        val res = FeedbackStatus.entries.map { feedbackStatusLabelRes(it) }
        assertEquals(FeedbackStatus.entries.size, res.toSet().size)
        assertTrue(res.none { it == 0 })
    }

    @Test
    fun `the six labels are the six the copy table names`() {
        assertEquals(R.string.bt_feedback_mine_status_new, feedbackStatusLabelRes(FeedbackStatus.New))
        assertEquals(
            R.string.bt_feedback_mine_status_triaged,
            feedbackStatusLabelRes(FeedbackStatus.Triaged),
        )
        assertEquals(
            R.string.bt_feedback_mine_status_working,
            feedbackStatusLabelRes(FeedbackStatus.WorkingOnIt),
        )
        assertEquals(
            R.string.bt_feedback_mine_status_future,
            feedbackStatusLabelRes(FeedbackStatus.SavedAsFutureIdea),
        )
        assertEquals(
            R.string.bt_feedback_mine_status_declined,
            feedbackStatusLabelRes(FeedbackStatus.Declined),
        )
        assertEquals(
            R.string.bt_feedback_mine_status_shipped,
            feedbackStatusLabelRes(FeedbackStatus.Shipped),
        )
    }

    @Test
    fun `declined is never drawn in the loss colour`() {
        // THE rule. `loss` in this app means money went the wrong way; spending it
        // on "we are not building this" would read as an alarm about the user's own
        // message. The maintainer's written reason carries the meaning instead.
        assertNotEquals(BtBadgeKind.Loss, feedbackStatusTone(FeedbackStatus.Declined))
        assertEquals(BtBadgeKind.Neutral, feedbackStatusTone(FeedbackStatus.Declined))
    }

    @Test
    fun `no status anywhere in the lifecycle is drawn in the loss colour`() {
        FeedbackStatus.entries.forEach {
            assertNotEquals("$it must not use the loss colour", BtBadgeKind.Loss, feedbackStatusTone(it))
        }
        assertNotEquals(BtBadgeKind.Loss, feedbackStatusTone(null))
    }

    @Test
    fun `gold marks the two statuses that are still going somewhere`() {
        assertEquals(BtBadgeKind.Gold, feedbackStatusTone(FeedbackStatus.WorkingOnIt))
        assertEquals(BtBadgeKind.Gold, feedbackStatusTone(FeedbackStatus.SavedAsFutureIdea))
        // The app's one accent, spent on exactly those two and nothing else.
        assertEquals(
            listOf(FeedbackStatus.WorkingOnIt, FeedbackStatus.SavedAsFutureIdea),
            FeedbackStatus.entries.filter { feedbackStatusTone(it) == BtBadgeKind.Gold },
        )
    }

    @Test
    fun `shipped is the only status that reads as good news`() {
        assertEquals(
            listOf(FeedbackStatus.Shipped),
            FeedbackStatus.entries.filter { feedbackStatusTone(it) == BtBadgeKind.Gain },
        )
    }

    @Test
    fun `an unknown status is calm, not alarming`() {
        // A status this build does not know is not an emergency, and colouring it
        // would be claiming to know what it means.
        assertEquals(BtBadgeKind.Neutral, feedbackStatusTone(null))
    }

    @Test
    fun `the category labels are the composer's own five`() {
        // Not new copy: a user who picked "Verbesserung" three screens ago has to
        // read the same word back, or the list is describing something else. Five
        // since the #1400 widening — `help` and `improvement` are KNOWN values now,
        // so a row carrying either prints a translated label instead of falling
        // through to the raw wire word.
        assertEquals(
            R.string.bt_feedback_cat_feature,
            feedbackCategoryLabelRes(FeedbackCategory.Feature),
        )
        assertEquals(R.string.bt_feedback_cat_bug, feedbackCategoryLabelRes(FeedbackCategory.Bug))
        assertEquals(R.string.bt_feedback_cat_other, feedbackCategoryLabelRes(FeedbackCategory.Other))
        assertEquals(R.string.bt_feedback_cat_help, feedbackCategoryLabelRes(FeedbackCategory.Help))
        assertEquals(
            R.string.bt_feedback_cat_improvement,
            feedbackCategoryLabelRes(FeedbackCategory.Improvement),
        )
        assertEquals(5, FeedbackCategory.entries.size)
        assertEquals(
            FeedbackCategory.entries.size,
            FeedbackCategory.entries.map { feedbackCategoryLabelRes(it) }.toSet().size,
        )
    }

    // ── Stamps ───────────────────────────────────────────────────────────────

    private val vienna: ZoneId = ZoneId.of("Europe/Vienna")

    /** 2026-08-20T10:00:00+02:00 — a Thursday mid-morning in Vienna. */
    private val now = 1_787_212_800_000L

    @Test
    fun `same calendar day is Today`() {
        assertEquals(FeedbackDay.Today, feedbackDayBucket(now, now, vienna))
        // 00:05 local, still today, even though it is nearly ten hours back.
        val justAfterMidnight = 1_787_177_100_000L
        assertEquals(FeedbackDay.Today, feedbackDayBucket(justAfterMidnight, now, vienna))
    }

    @Test
    fun `the boundary is midnight, not twenty-four hours`() {
        // THE case elapsed-time arithmetic gets wrong: 23:50 yesterday is
        // "Gestern" at 00:10, not "vor 20 Minuten". Counting calendar days is what
        // makes that true.
        val lateYesterday = 1_787_176_200_000L // 2026-08-19T23:50+02:00
        val earlyToday = 1_787_178_600_000L // 2026-08-20T00:30+02:00
        assertEquals(FeedbackDay.Yesterday, feedbackDayBucket(lateYesterday, earlyToday, vienna))
        // …and twenty hours earlier on the SAME day is still today.
        assertEquals(FeedbackDay.Today, feedbackDayBucket(lateYesterday, lateYesterday + 60_000, vienna))
    }

    @Test
    fun `anything older than yesterday gets a date`() {
        val twoDaysBack = now - 2 * 24 * 60 * 60 * 1000L
        assertEquals(FeedbackDay.Date, feedbackDayBucket(twoDaysBack, now, vienna))
    }

    @Test
    fun `a stamp in the future is never narrated as a countdown`() {
        // Clock skew between phone and server is not a fact about the submission,
        // so it is never told as one. A few hours ahead is still the same calendar
        // day and reads "Heute" — which is both true and unremarkable; a whole day
        // ahead falls through to its date rather than to "in 1 Tag".
        assertEquals(
            FeedbackDay.Today,
            feedbackDayBucket(now + 5 * 60 * 60 * 1000L, now, vienna),
        )
        assertEquals(
            FeedbackDay.Date,
            feedbackDayBucket(now + 2 * 24 * 60 * 60 * 1000L, now, vienna),
        )
    }

    @Test
    fun `the date is formatted in the app's locale, not the JVM default`() {
        val de = feedbackDateText(now, vienna, Locale.GERMAN)
        val en = feedbackDateText(now, vienna, Locale.ENGLISH)
        assertTrue(de.contains("2026"))
        assertTrue(en.contains("2026"))
        // The two must actually differ, or the locale argument is decorative and
        // the in-app language switch would leave dates in the wrong language.
        assertNotEquals(de, en)
    }

    @Test
    fun `the zone is honoured, so a late-evening UTC stamp is the next day in Vienna`() {
        // 2026-08-19T23:30Z is 2026-08-20T01:30 in Vienna.
        val utcLateEvening = 1_787_182_200_000L
        assertEquals(FeedbackDay.Today, feedbackDayBucket(utcLateEvening, now, vienna))
        assertEquals(
            FeedbackDay.Yesterday,
            feedbackDayBucket(utcLateEvening, now, ZoneId.of("UTC")),
        )
    }
}
