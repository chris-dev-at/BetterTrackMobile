package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.FeedbackCreatedResponse
import at.bettertrack.app.data.api.dto.SubmitFeedbackRequest
import at.bettertrack.app.data.storage.BtSurface
import at.bettertrack.app.data.storage.StorageMode
import at.bettertrack.app.data.storage.shows
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app feedback (platform #1315 / #1316 / #1317) — the wire contract, the
 * composer's validation, and the two conditions the entry rows are gated on.
 *
 * The contract was agreed on 2026-08-17 and went live on production on 2026-08-18.
 * These assertions were written before the endpoint existed and are worth more now
 * than they were then: they are what stands between "we built to the spec" and "we
 * built to what we remembered of the spec". They assert the exact bytes.
 */
class FeedbackTest {

    /** Mirror AppGraph.json exactly. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val ctx = feedbackContextOf(
        appVersion = "1.4.2 (142)",
        osVersion = "Android 15 (API 35)",
        device = "samsung SM-S911B",
        locale = "de-AT",
        screen = FeedbackOrigin.SETTINGS,
    )

    // ── The two gates: the capability flag and the account condition ────────────

    @Test
    fun `the feedback surface is ON now the platform has seeded the scope`() {
        // Flipped 2026-08-19 on the platform's go-live tick: `POST /feedback` is on
        // production, accepts bearer, and `feedback:write` is seeded to the
        // BetterTrackMobile client (existing consents widened, so no re-login).
        // If the platform ever retracts the seed, this and
        // OAuthConfig.FEEDBACK_SCOPE_ENABLED go back to false together.
        assertTrue(FeedbackFlags.enabled)
    }

    @Test
    fun `a Drive-autonomous install never shows a feedback entry`() {
        // THE regression guard. `POST /feedback` is a SERVER route authenticated by
        // a bearer token; a Drive-only install has no BetterTrack account and no
        // token, so the row would open a composer whose Send is permanently
        // disabled behind its signed-in check — the exact "a row that opens a form
        // that can only fail" outcome the flag was created to prevent. The flag
        // alone does not encode this, which is why the rows call
        // `feedbackEntryVisible` and not `FeedbackFlags.enabled`.
        assertFalse(feedbackEntryVisible(StorageMode.DRIVE))
    }

    @Test
    fun `every mode that has an account shows the feedback entry`() {
        // SERVER and BOTH both have a BetterTrack account behind them, and UNSET
        // resolves to SERVER (an install that has not answered the wizard behaves
        // exactly as the app always has), so all three carry the row.
        assertTrue(feedbackEntryVisible(StorageMode.SERVER))
        assertTrue(feedbackEntryVisible(StorageMode.BOTH))
        assertTrue(feedbackEntryVisible(StorageMode.UNSET))
    }

    @Test
    fun `the entry gate is exactly the flag AND the account surface`() {
        // Pins the composition rather than the current values: whichever way the
        // flag is set, the gate must never be looser than `ACCOUNT_SETTINGS`.
        StorageMode.entries.forEach { mode ->
            assertEquals(
                FeedbackFlags.enabled && mode.shows(BtSurface.ACCOUNT_SETTINGS),
                feedbackEntryVisible(mode),
            )
        }
    }

    // ── The wire enum ───────────────────────────────────────────────────────────

    @Test
    fun `the category wire values are the deployed enum, in its order`() {
        // Copied from production's `openapi.json` on 2026-08-20, not from the
        // widening's prose: `CreateFeedbackRequest.category.enum` is
        // ["feature","bug","other","help","improvement"] and `MyFeedbackResponse`'s
        // row declares the identical five.
        assertEquals(
            listOf("feature", "bug", "other", "help", "improvement"),
            FeedbackCategory.entries.map { it.wire },
        )
    }

    @Test
    fun `the widening left the first three wire values byte-unchanged`() {
        // THE compatibility assertion. Platform #1400 APPENDED two values; had it
        // renamed one, every submission already in the account would read back
        // under a category this build cannot name — and the row would silently
        // start printing a raw wire word.
        assertEquals("feature", FeedbackCategory.Feature.wire)
        assertEquals("bug", FeedbackCategory.Bug.wire)
        assertEquals("other", FeedbackCategory.Other.wire)
    }

    @Test
    fun `help and improvement are known values now, not unknown ones`() {
        assertEquals(FeedbackCategory.Help, FeedbackCategory.fromWire("help"))
        assertEquals(FeedbackCategory.Improvement, FeedbackCategory.fromWire("improvement"))
        // …and the tolerance path still exists for whatever the platform adds next.
        assertNull(FeedbackCategory.fromWire("question"))
    }

    @Test
    fun `the wire enum is never translated`() {
        // The German UI reads "Feature · Verbesserung · Bug · Hilfe · Sonstiges"
        // and still sends these five ASCII values. A localised category is a 400.
        FeedbackCategory.entries.forEach {
            assertTrue(it.wire.all { c -> c in 'a'..'z' })
        }
        assertNull(FeedbackCategory.fromWire("Sonstiges"))
        assertNull(FeedbackCategory.fromWire("Verbesserung"))
        assertNull(FeedbackCategory.fromWire(null))
        assertEquals(FeedbackCategory.Other, FeedbackCategory.fromWire("other"))
    }

    // ── Validation ──────────────────────────────────────────────────────────────

    @Test
    fun `a draft needs a category and a non-blank message`() {
        assertFalse(FeedbackDraft().isSendable())
        assertFalse(FeedbackDraft(message = "it broke").isSendable())
        assertFalse(FeedbackDraft(category = FeedbackCategory.Bug).isSendable())
        assertTrue(FeedbackDraft(category = FeedbackCategory.Bug, message = "it broke").isSendable())
    }

    @Test
    fun `whitespace is not a message`() {
        // 200 spaces satisfies the server's min(1) and is not feedback.
        val blank = FeedbackDraft(category = FeedbackCategory.Bug, message = " ".repeat(200))
        assertFalse(blank.isSendable())
        assertNull(blank.toRequest(null))
    }

    @Test
    fun `the length ceilings are enforced below the UI, not only in it`() {
        val long = FeedbackDraft(
            category = FeedbackCategory.Other,
            message = "x".repeat(FEEDBACK_MESSAGE_MAX + 1),
        )
        assertFalse(long.isSendable())
        assertTrue(
            FeedbackDraft(category = FeedbackCategory.Other, message = "x".repeat(FEEDBACK_MESSAGE_MAX))
                .isSendable(),
        )
        val subject = FeedbackDraft(
            category = FeedbackCategory.Other,
            message = "hi",
            subject = "s".repeat(FEEDBACK_SUBJECT_MAX + 1),
        )
        assertFalse(subject.isSendable())
    }

    @Test
    fun `the contract limits are 5000 and 120`() {
        assertEquals(5000, FEEDBACK_MESSAGE_MAX)
        assertEquals(120, FEEDBACK_SUBJECT_MAX)
    }

    @Test
    fun `the open-submission cap is mirrored but never enforced client-side`() {
        // `FEEDBACK_OPEN_SUBMISSION_LIMIT = 20` (platform #1400). It is a number the
        // refusal copy quotes, NOT a rule the composer may apply: "open" is the
        // server's own definition and this app cannot compute it, so a draft is
        // sendable regardless of how many submissions exist. Pre-refusing on a
        // guess would block a user the server would have accepted.
        assertEquals(20, FEEDBACK_OPEN_SUBMISSION_LIMIT)
        assertTrue(FeedbackDraft(category = FeedbackCategory.Help, message = "?").isSendable())
    }

    // ── The request body ────────────────────────────────────────────────────────

    @Test
    fun `a minimal submission carries only category and message`() {
        val body = FeedbackDraft(category = FeedbackCategory.Feature, message = "add dark mode")
            .toRequest(null)!!
        assertEquals(
            """{"category":"feature","message":"add dark mode"}""",
            json.encodeToString(body),
        )
    }

    @Test
    fun `the two widened categories serialise as their own wire words`() {
        // Byte-exact, same standard as the three that came before: the whole point
        // of `improvement` existing is that it is NOT `feature`, and a body that
        // sent the old value would look perfectly correct on this phone while
        // filing every improvement under the wrong heading on the server.
        assertEquals(
            """{"category":"improvement","message":"make the chart scrub smoother"}""",
            json.encodeToString(
                FeedbackDraft(
                    category = FeedbackCategory.Improvement,
                    message = "make the chart scrub smoother",
                ).toRequest(null)!!,
            ),
        )
        assertEquals(
            """{"category":"help","message":"where do I set my tax rate?"}""",
            json.encodeToString(
                FeedbackDraft(
                    category = FeedbackCategory.Help,
                    message = "where do I set my tax rate?",
                ).toRequest(null)!!,
            ),
        )
    }

    @Test
    fun `every category produces a body whose category is its own wire value`() {
        // The general form, so a sixth value cannot ship mapped to a neighbour's.
        FeedbackCategory.entries.forEach { category ->
            val body = FeedbackDraft(category = category, message = "m").toRequest(null)!!
            assertEquals(category.wire, body.category)
            assertTrue(json.encodeToString(body).contains(""""category":"${category.wire}""""))
        }
    }

    @Test
    fun `a blank subject is omitted, not sent as an empty string`() {
        val body = FeedbackDraft(
            category = FeedbackCategory.Bug, message = "broken", subject = "   ",
        ).toRequest(null)!!
        assertNull(body.subject)
        assertFalse(json.encodeToString(body).contains("subject"))
    }

    @Test
    fun `both text fields are trimmed`() {
        val body = FeedbackDraft(
            category = FeedbackCategory.Bug,
            message = "  the total is wrong  ",
            subject = "  totals  ",
        ).toRequest(null)!!
        assertEquals("the total is wrong", body.message)
        assertEquals("totals", body.subject)
    }

    @Test
    fun `the full body matches the locked contract byte for byte`() {
        val body = FeedbackDraft(
            category = FeedbackCategory.Bug,
            message = "the portfolio total is wrong",
            subject = "Totals",
        ).toRequest(ctx)!!
        assertEquals(
            """{"category":"bug","message":"the portfolio total is wrong","subject":"Totals",""" +
                """"context":{"platform":"android","appVersion":"1.4.2 (142)",""" +
                """"osVersion":"Android 15 (API 35)","device":"samsung SM-S911B",""" +
                """"locale":"de-AT","screen":"settings"}}""",
            json.encodeToString(body),
        )
    }

    @Test
    fun `an unsendable draft produces no request at all`() {
        assertNull(FeedbackDraft().toRequest(ctx))
    }

    // ── The attached context ────────────────────────────────────────────────────

    @Test
    fun `context always names the platform and drops what it does not know`() {
        val sparse = feedbackContextOf(
            appVersion = null, osVersion = "", device = "  ", locale = "en-GB", screen = null,
        )
        assertEquals(FEEDBACK_PLATFORM, sparse.platform)
        assertNull(sparse.appVersion)
        assertNull("a blank is absence, not an empty fact", sparse.osVersion)
        assertNull(sparse.device)
        assertEquals("en-GB", sparse.locale)
        assertNull(sparse.screen)
        assertEquals("""{"platform":"android","locale":"en-GB"}""", json.encodeToString(sparse))
    }

    @Test
    fun `context carries nothing about the account or the portfolio`() {
        // A feedback form is not a place to widen what leaves the phone. The server
        // already knows who is calling, from the credential.
        val encoded = json.encodeToString(ctx).lowercase()
        listOf("token", "userid", "email", "portfolio", "balance", "holding").forEach {
            assertFalse("context must not carry $it", encoded.contains(it))
        }
    }

    @Test
    fun `origins are the two the route accepts`() {
        assertEquals("settings", FeedbackOrigin.SETTINGS)
        assertEquals("about", FeedbackOrigin.ABOUT)
    }

    // ── The response ────────────────────────────────────────────────────────────

    @Test
    fun `a 201 decodes even if the shape drifts`() {
        // Telling somebody their feedback failed when it is already stored is the
        // one lie this screen must not tell, so the response is tolerant.
        assertEquals("abc", json.decodeFromString<FeedbackCreatedResponse>("""{"id":"abc","createdAt":"x"}""").id)
        json.decodeFromString<FeedbackCreatedResponse>("""{}""")
        json.decodeFromString<FeedbackCreatedResponse>("""{"id":"abc"}""")
    }

    @Test
    fun `the request type rejects nothing the contract allows`() {
        // A 5000-character message is legal and must serialise unaltered.
        val msg = "x".repeat(FEEDBACK_MESSAGE_MAX)
        val body = SubmitFeedbackRequest(category = "other", message = msg)
        assertTrue(json.encodeToString(body).contains(msg))
    }
}
