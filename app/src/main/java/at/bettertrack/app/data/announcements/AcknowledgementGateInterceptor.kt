package at.bettertrack.app.data.announcements

import at.bettertrack.app.data.api.dto.ApiErrorEnvelope
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Watches every authenticated response for the gate's refusal — `403
 * { error: { code: "ACKNOWLEDGEMENT_REQUIRED", details: { pendingAcknowledgements } } }`
 * (contract §5.2) — and hands the obligations to the announcements pipeline,
 * which raises the blocking modal (contract §5.7.2).
 *
 * Observe-only, like `ParanoidModeInterceptor`: the body is PEEKED, never
 * consumed, so the caller's own error handling still runs and still sees the
 * same refusal (BtErrorCopy carries a sentence for it). The bearer flag ships
 * `off`, so today nothing the app sends is refused; this is what makes the
 * flip to `first_party` a server-side change with no app release.
 */
class AcknowledgementGateInterceptor(
    private val json: Json,
    private val onRefusal: (List<PendingAcknowledgement>?) -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403) {
            val peeked = try {
                response.peekBody(MAX_PEEK_BYTES).string()
            } catch (_: Exception) {
                null
            }
            val refusal = acknowledgementRefusalOf(json, peeked)
            if (refusal != null) {
                try {
                    onRefusal(refusal.pending)
                } catch (_: Exception) {
                    // An interceptor must never turn an observation into a failure.
                }
            }
        }
        return response
    }

    private companion object {
        /** The refusal lists at most a handful of obligations; never pull a large body. */
        const val MAX_PEEK_BYTES = 16_384L
    }
}

/** A parsed gate refusal. [pending] null = the code without a usable list (still a refusal). */
data class AcknowledgementRefusal(val pending: List<PendingAcknowledgement>?)

/**
 * Pure classifier: the refusal when [body] is the error envelope with code
 * `ACKNOWLEDGEMENT_REQUIRED`, else null. Every other 403 — scope, paranoid,
 * password change — is not this, and returns null.
 */
fun acknowledgementRefusalOf(json: Json, body: String?): AcknowledgementRefusal? {
    if (body.isNullOrBlank()) return null
    return try {
        val env = json.decodeFromString(ApiErrorEnvelope.serializer(), body)
        if (env.error.code != AnnouncementCodes.ACKNOWLEDGEMENT_REQUIRED) {
            null
        } else {
            AcknowledgementRefusal(pendingFromRefusalDetails(env.error.details))
        }
    } catch (_: Exception) {
        null
    }
}
