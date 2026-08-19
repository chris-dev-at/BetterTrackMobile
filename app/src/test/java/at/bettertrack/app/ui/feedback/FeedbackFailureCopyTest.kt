package at.bettertrack.app.ui.feedback

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
