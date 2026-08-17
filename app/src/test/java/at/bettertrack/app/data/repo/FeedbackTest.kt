package at.bettertrack.app.data.repo

import at.bettertrack.app.data.api.dto.FeedbackCreatedResponse
import at.bettertrack.app.data.api.dto.SubmitFeedbackRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app feedback (platform #1315 / #1316 / #1317) — the wire contract and the
 * composer's validation, pinned before the endpoint is live.
 *
 * The contract was agreed on 2026-08-17 and the route has not shipped yet, so these
 * are the only thing standing between "we built to the spec" and "we built to what
 * we remembered of the spec". They assert the exact bytes.
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

    // ── The flag, which is the whole reason this ships dark ─────────────────────

    @Test
    fun `the feedback surface is OFF until the platform seeds the scope`() {
        // Turning this on before `feedback:write` is seeded puts a form in front of
        // the user whose POST can only ever 403 INSUFFICIENT_SCOPE.
        assertFalse(FeedbackFlags.enabled)
    }

    // ── The wire enum ───────────────────────────────────────────────────────────

    @Test
    fun `the category wire values are exactly feature, bug and other`() {
        assertEquals(
            listOf("feature", "bug", "other"),
            FeedbackCategory.entries.map { it.wire },
        )
    }

    @Test
    fun `the wire enum is never translated`() {
        // The German UI reads "Feature/Verbesserung · Bug · Sonstiges" and still
        // sends these three ASCII values. A localised category is a 400.
        FeedbackCategory.entries.forEach {
            assertTrue(it.wire.all { c -> c in 'a'..'z' })
        }
        assertNull(FeedbackCategory.fromWire("Sonstiges"))
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
