package at.bettertrack.app.data.push

import android.content.Context
import android.util.Log
import at.bettertrack.app.data.notifications.NotificationFlags
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Obtains and tracks the FCM device token (Step 16, §6.11).
 *
 * Obtaining a token works WITHOUT the platform — verified for real on device.
 * We only ever log the token's PRESENCE (and length), never its value. Register/
 * refresh/delete against a platform device-token endpoint is STUBBED because no
 * such endpoint exists yet (confirmed against production OpenAPI) — see
 * docs/TODO.md. When it ships, [registerWithPlatform] becomes the real call.
 */
class PushTokenManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("bt_push", Context.MODE_PRIVATE)

    /** True once a token has been obtained at least once (for debug display). */
    val hasToken: Boolean get() = prefs.getBoolean(KEY_HAS_TOKEN, false)

    /** Kick an async token fetch; logs presence + hands off to (stubbed) register. */
    fun fetchToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    val present = !token.isNullOrBlank()
                    prefs.edit().putBoolean(KEY_HAS_TOKEN, present).apply()
                    Log.i(TAG, "FCM token obtained (present=$present, length=${token?.length ?: 0}).")
                    registerWithPlatform(token)
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
        registerWithPlatform(token)
    }

    private fun registerWithPlatform(token: String?) {
        if (token.isNullOrBlank()) return
        if (NotificationFlags.stubDeviceRegistration) {
            // Platform gap: no device-token endpoint. Real pushes wait on the
            // server; the client is otherwise fully wired.
            Log.i(TAG, "Device-token registration STUBBED — no platform endpoint yet.")
            return
        }
        // TODO(platform): POST the device token to the platform device-token endpoint.
    }

    private companion object {
        const val TAG = "BtPush"
        const val KEY_HAS_TOKEN = "has_token"
    }
}
