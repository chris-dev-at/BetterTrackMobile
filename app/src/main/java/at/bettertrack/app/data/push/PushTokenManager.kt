package at.bettertrack.app.data.push

import android.content.Context
import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.DeregisterDeviceRequest
import at.bettertrack.app.data.api.dto.RegisterDeviceRequest
import at.bettertrack.app.data.notifications.NotificationFlags
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** What to do with an obtained FCM token, given session + flag state. */
enum class RegistrationAction { Register, DeferUntilLogin, Skip }

/**
 * Pure gating for device-token registration (unit-tested): nothing to do without
 * a token or while the feature is stubbed; a token obtained while **logged out**
 * is DEFERRED and re-tried on the next login; only a logged-in session registers
 * now. Keeps the side-effecting manager below a thin executor of this decision.
 */
fun registrationAction(hasToken: Boolean, loggedIn: Boolean, stubbed: Boolean): RegistrationAction =
    when {
        stubbed -> RegistrationAction.Skip
        !hasToken -> RegistrationAction.Skip
        !loggedIn -> RegistrationAction.DeferUntilLogin
        else -> RegistrationAction.Register
    }

/**
 * Obtains the FCM device token and registers it against the account
 * (Notifications-v2, §6.11 / platform PR #427).
 *
 * Registration is **bearer-gated**: a token obtained while logged out is remembered
 * (in memory) and registered on the next login ([onLoggedIn]); a rotated token
 * ([onNewToken]) re-registers; logout best-effort DELETEs the token BEFORE the
 * credentials are wiped ([deregisterCurrentToken], bounded + fail-soft). The token
 * VALUE is never persisted to disk or logged — only presence + length (Firebase
 * keeps its own copy, so we can always re-fetch it). Real end-to-end push delivery
 * still waits on the server's Firebase key (platform #421); token registration is live.
 */
class PushTokenManager(
    context: Context,
    private val api: BtApi,
    private val json: Json,
    private val isLoggedIn: () -> Boolean,
    private val scope: CoroutineScope,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bt_push", Context.MODE_PRIVATE)

    /** In-memory only — never persisted or logged. The last FCM token this process saw. */
    @Volatile
    private var cachedToken: String? = null

    /** True once a token has been obtained at least once (for debug display). */
    val hasToken: Boolean get() = prefs.getBoolean(KEY_HAS_TOKEN, false)

    /** Kick an async token fetch; logs presence + registers (or defers). */
    fun fetchToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    val present = !token.isNullOrBlank()
                    prefs.edit().putBoolean(KEY_HAS_TOKEN, present).apply()
                    Log.i(TAG, "FCM token obtained (present=$present, length=${token?.length ?: 0}).")
                    if (present) register(token!!)
                } else {
                    Log.w(TAG, "FCM token fetch failed.", task.exception)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FCM token fetch threw (Firebase not ready?).", e)
        }
    }

    /** Called by the messaging service when Firebase rotates the token. */
    fun onNewToken(token: String) {
        prefs.edit().putBoolean(KEY_HAS_TOKEN, true).apply()
        Log.i(TAG, "FCM onNewToken (present=true, length=${token.length}).")
        register(token)
    }

    /** Login just completed → register the current token (fetch it if not cached yet). */
    fun onLoggedIn() {
        val t = cachedToken
        if (t != null) register(t) else fetchToken()
    }

    /**
     * Logout is underway — best-effort DELETE the token BEFORE credentials are
     * wiped. Bounded by [DEREGISTER_TIMEOUT_MS] and fully fail-soft: logout must
     * never block or fail on it. A no-op when there's nothing registered/stubbed.
     */
    suspend fun deregisterCurrentToken() {
        if (NotificationFlags.stubDeviceRegistration) return
        val token = cachedToken ?: return
        withTimeoutOrNull(DEREGISTER_TIMEOUT_MS) {
            runCatching {
                when (val r = apiCall(json) { api.deregisterDevice(DeregisterDeviceRequest(token)) }) {
                    is BtResult.Ok -> Log.i(TAG, "Device token deregistered (length=${token.length}).")
                    is BtResult.Err -> Log.w(TAG, "Device deregister failed (HTTP ${r.error.httpStatus}); ignoring.")
                }
            }.onFailure { Log.w(TAG, "Device deregister threw; ignoring.") }
        }
    }

    private fun register(token: String) {
        if (token.isBlank()) return
        cachedToken = token
        when (registrationAction(hasToken = true, loggedIn = isLoggedIn(), stubbed = NotificationFlags.stubDeviceRegistration)) {
            RegistrationAction.Skip ->
                Log.i(TAG, "Device-token registration stubbed — skipped (length=${token.length}).")
            RegistrationAction.DeferUntilLogin ->
                Log.i(TAG, "Logged out — deferring device registration to next login (length=${token.length}).")
            RegistrationAction.Register -> scope.launch {
                when (val r = apiCall(json) { api.registerDevice(RegisterDeviceRequest(token, PLATFORM)) }) {
                    is BtResult.Ok -> Log.i(TAG, "Device token registered (ok=${r.value.ok}, length=${token.length}).")
                    is BtResult.Err -> Log.w(TAG, "Device registration failed (HTTP ${r.error.httpStatus} ${r.error.code}).")
                }
            }
        }
    }

    private companion object {
        const val TAG = "BtPush"
        const val KEY_HAS_TOKEN = "has_token"
        /** OpenAPI `platform` enum value for this client. */
        const val PLATFORM = "android"
        /** Logout must stay snappy — cap the best-effort DELETE. */
        const val DEREGISTER_TIMEOUT_MS = 4_000L
    }
}
