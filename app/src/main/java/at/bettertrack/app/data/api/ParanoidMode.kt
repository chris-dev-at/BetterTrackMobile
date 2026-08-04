package at.bettertrack.app.data.api

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response

/**
 * V5 defensive layer — **paranoid mode** (PLATFORM_ASKS #39.1).
 *
 * An account can live in "paranoid mode": its portfolio/tax/import data is held
 * in an opaque client-side vault, and the platform deliberately answers the
 * portfolio-family endpoints with `403 { error: { code: "PARANOID_MODE" } }`.
 * Bearer clients get **no pre-flight signal** today — the vault routes are session-only
 * and `MeResponse` does not expose `privacyMode` (that ask is filed) — so the
 * app can only learn about it from a refusal.
 *
 * Without this layer such an account would see a generic error, or worse an
 * apparently-legitimate **€0 portfolio**. Instead the first PARANOID_MODE
 * refusal flips [ParanoidModeState], and the killed surfaces swap to a designed
 * explanation screen. The surviving features (friends, chat, watchlists, price
 * alerts, notifications) are untouched and keep working normally.
 */
object ParanoidModeState {

    private val _active = MutableStateFlow(false)

    /** True once the server has refused a call with `403 PARANOID_MODE`. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun markActive() {
        if (!_active.value) {
            Log.i(TAG, "Account is in PARANOID MODE — routing portfolio surfaces to the explainer")
            _active.value = true
        }
    }

    /**
     * Clears the flag. Called on logout/account-switch: the next account is a
     * different user and must not inherit this one's mode.
     */
    fun clear() {
        _active.value = false
    }

    private const val TAG = "BtParanoid"
}

/**
 * Global OkHttp interceptor that watches every authenticated response for the
 * paranoid-mode refusal and flips [ParanoidModeState].
 *
 * Deliberately **observe-only**: the response is passed through untouched (the
 * body is read via `peekBody`, never consumed) so existing per-call error
 * handling still runs exactly as before. This layer only adds the app-level
 * state that lets the UI say something true instead of "something went wrong".
 */
class ParanoidModeInterceptor(
    private val json: Json,
    private val onDetected: () -> Unit = { ParanoidModeState.markActive() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403) {
            val peeked = try {
                response.peekBody(MAX_PEEK_BYTES).string()
            } catch (_: Exception) {
                null
            }
            if (isParanoidModeBody(json, peeked)) onDetected()
        }
        return response
    }

    private companion object {
        /** Error envelopes are tiny; never pull a large body into memory. */
        const val MAX_PEEK_BYTES = 4_096L
    }
}

/** The platform error code for a paranoid-mode refusal. */
const val PARANOID_MODE_CODE = "PARANOID_MODE"

/**
 * Pure body classifier (kept top-level so the decision is unit-testable without
 * OkHttp): true when [body] is the platform error envelope carrying
 * `error.code == "PARANOID_MODE"`. Anything else — another 403 code, a
 * non-envelope body, empty, malformed — is false, so an unrelated 403 can never
 * black out the app's portfolio surfaces.
 */
fun isParanoidModeBody(json: Json, body: String?): Boolean {
    if (body.isNullOrBlank()) return false
    return try {
        json.decodeFromString(
            at.bettertrack.app.data.api.dto.ApiErrorEnvelope.serializer(),
            body,
        ).error.code == PARANOID_MODE_CODE
    } catch (_: Exception) {
        false
    }
}
