package at.bettertrack.app.ui.feedback

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.repo.FEEDBACK_OPEN_SUBMISSION_LIMIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one place the feedback composer overrides the app-wide error catalogue, and
 * the boundary of that override.
 *
 * `/feedback` is rate-limited to roughly five submissions per user per hour, while
 * the catalogue's generic `RATE_LIMITED` sentence says "wait a moment" — advice
 * that produces a second refusal thirty seconds later. So the composer owns one
 * sentence of its own for that case.
 *
 * It is keyed on the HTTP **status**, not on an error code: production's live
 * `openapi.json` documents only `201`, `400`, `401` and a generic envelope for this
 * route, so the limiter's `code` is not knowable from the contract, and putting a
 * guessed code in `BtErrorCopy` would be inventing a wire fact. `429` is the status
 * the contract itself names. These tests pin both halves — the override fires, and
 * it fires for nothing else.
 */
class FeedbackFailureCopyTest {

    @Test
    fun `a 429 gets the feedback-specific hourly-limit sentence`() {
        // Whatever code rides along — the catalogue may or may not know it — the
        // status is what decides, so an unmapped code can never leak the server's
        // English diagnostic onto a German phone for this case.
        listOf("RATE_LIMITED", "FEEDBACK_RATE_LIMITED", "UNKNOWN").forEach { code ->
            val msg = feedbackFailureMessage(
                BtApiError(httpStatus = 429, code = code, diagnostic = "Too many requests"),
            )
            assertEquals(R.string.bt_feedback_err_rate_limited, msg.res)
            // No dim second line: the app has real copy here, so the server's own
            // words would only repeat it in the wrong language.
            assertNull(msg.diagnostic)
        }
    }

    @Test
    fun `every other failure still resolves through the app-wide catalogue`() {
        val validation = feedbackFailureMessage(
            BtApiError(httpStatus = 400, code = "VALIDATION_ERROR"),
        )
        assertEquals(R.string.bt_err_validation_error, validation.res)

        val forbidden = feedbackFailureMessage(
            BtApiError(httpStatus = 403, code = "INSUFFICIENT_SCOPE"),
        )
        assertNotEquals(R.string.bt_feedback_err_rate_limited, forbidden.res)

        // Transport failure (httpStatus 0) must stay the network sentence — the
        // composer's own offline line already predicted it.
        val offline = feedbackFailureMessage(
            BtApiError(httpStatus = 0, code = BtApiError.Codes.NETWORK),
        )
        assertEquals(R.string.bt_err_network_error, offline.res)
    }

    // ── The open-submission cap (platform #1400) ─────────────────────────────

    @Test
    fun `FEEDBACK_OPEN_LIMIT resolves to its own catalogued sentence`() {
        val msg = feedbackFailureMessage(
            BtApiError(
                httpStatus = 409,
                code = BtApiError.Codes.FEEDBACK_OPEN_LIMIT,
                diagnostic = "Too many open feedback submissions.",
            ),
        )
        assertEquals(R.string.bt_err_feedback_open_limit, msg.res)
        // The catalogue owns the sentence, so the server's English never shows.
        assertNull(msg.diagnostic)
        assertNotEquals(R.string.bt_err_unknown, msg.res)
    }

    @Test
    fun `the cap's copy survives arriving on a 429`() {
        // THE ordering bug this branch exists to prevent. The contract does not say
        // which status carries `FEEDBACK_OPEN_LIMIT`, and 429 is a plausible one —
        // in which case a status-first branch would answer with the hourly
        // rate-limit sentence, whose advice ("try again a bit later") is false here:
        // no amount of waiting clears twenty open requests. The CODE wins.
        val msg = feedbackFailureMessage(
            BtApiError(httpStatus = 429, code = BtApiError.Codes.FEEDBACK_OPEN_LIMIT),
        )
        assertEquals(R.string.bt_err_feedback_open_limit, msg.res)
        assertNotEquals(R.string.bt_feedback_err_rate_limited, msg.res)
    }

    @Test
    fun `the cap's copy names the remedy and the number, in both languages`() {
        // A refusal whose copy does not say what to DO is the generic fallback with
        // extra steps. Both sentences have to name the cap
        // (FEEDBACK_OPEN_SUBMISSION_LIMIT, so the platform changing it fails the
        // build rather than leaving a sentence that lies) and both ways out: wait
        // for triage, or delete one.
        val cap = FEEDBACK_OPEN_SUBMISSION_LIMIT.toString()
        listOf("" to "EN", "-de" to "DE").forEach { (qualifier, label) ->
            val body = string(qualifier, "bt_err_feedback_open_limit")
            assertTrue("$label copy must name the cap ($cap): $body", body.contains(cap))
            val remedy = if (qualifier.isEmpty()) "delete" else "lösche"
            assertTrue("$label copy must name the delete remedy: $body", body.contains(remedy))
            val wait = if (qualifier.isEmpty()) "Wait" else "Warte"
            assertTrue("$label copy must name the wait remedy: $body", body.contains(wait))
        }
    }

    /** One string's body, read straight out of the resource XML. */
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

    @Test
    fun `an unmapped code keeps the server's words as a dim diagnostic`() {
        // The catalogue's fallback contract, unchanged by the override: a code this
        // build has never seen still says something concrete rather than vanishing.
        val msg = feedbackFailureMessage(
            BtApiError(httpStatus = 400, code = "SOME_FUTURE_CODE", diagnostic = "nope"),
        )
        assertEquals(R.string.bt_err_unknown, msg.res)
        assertEquals("nope", msg.diagnostic)
    }
}
