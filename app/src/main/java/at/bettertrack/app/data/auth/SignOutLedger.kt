package at.bettertrack.app.data.auth

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The sign-out reason ledger — the answer to *"why am I at the login screen
 * again?"*.
 *
 * The owner has been thrown back to the login screen repeatedly and the app,
 * until now, kept no record of it: `logcat` holds a few hours at best, the
 * session blob is wiped by the very transition we want to explain, and every
 * path into [AuthState.LoggedOut] looked identical from the outside — a user
 * logout, a rejected refresh and a Keystore hiccup all just showed the login
 * screen. So every transition to signed-out now writes one small row here, and
 * the login screen can show them back.
 *
 * ## Where it lives, and why not in [SecureStore]
 *
 * A PLAIN, app-private `SharedPreferences` file of its own, deliberately:
 *
 *  - [SecureStore.wipeAll] runs on exactly the transitions this ledger exists to
 *    record, so a row written there would be erased by the event it describes;
 *  - one of the recorded reasons IS "the Keystore-backed store could not be
 *    opened", which cannot be written into the store that failed;
 *  - it holds nothing secret by construction (see [SignOutEvent]), so the
 *    encryption would buy nothing and cost the ability to read it when the
 *    Keystore is the thing that broke.
 *
 * Writes use `commit()` rather than `apply()`: a forced sign-out is frequently
 * followed by the process going away, and an `apply()` that never reached disk
 * would lose precisely the evidence that matters.
 */
interface SignOutLedger {
    /** Append one event. Never throws — diagnostics must not break a logout. */
    fun record(event: SignOutEvent)

    /** The most recent events, NEWEST FIRST, at most [LEDGER_CAP]. */
    fun recent(): List<SignOutEvent>
}

/** How many rows are kept. Twenty covers weeks of a "few times a week" fault. */
const val LEDGER_CAP: Int = 20

/**
 * One signed-out transition.
 *
 * **Contains no tokens, no user id, no email, no host, no request body.** The
 * whole row is a timestamp plus five short machine-readable labels, which is
 * what makes it safe to render on a login screen and to read out over the phone.
 *
 * @param at wall-clock epoch millis of the transition.
 * @param reason WHY — a [SignOutReason] name.
 * @param httpStatus the HTTP status that decided it, when one did (`0` is a
 *   transport failure, `null` means no HTTP call was involved).
 * @param errorCode the platform error envelope's `code`, when there was one.
 * @param trigger WHICH RAIL ended the session — a [SignOutTrigger] name.
 * @param caller the code site, e.g. `TokenManager.doRefresh`, so a report names
 *   a line rather than a feeling.
 */
@Serializable
data class SignOutEvent(
    val at: Long,
    val reason: String,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val trigger: String,
    val caller: String,
)

/**
 * Why the session ended. Stable names — they are persisted and rendered, so a
 * rename is a data migration, not a refactor.
 */
enum class SignOutReason {
    /** The user tapped "Log out". */
    USER_LOGOUT,

    /** "Forgot PIN" on the app lock, which logs out on purpose. */
    APP_LOCK_FORGOT_PIN,

    /** The account was deleted from Settings. */
    ACCOUNT_DELETED,

    /** A storage-mode transition that leaves the BetterTrack account. */
    STORAGE_MODE_SWITCH,

    /** `/auth/me` said admin or disabled — the app refuses those accounts. */
    ACCOUNT_GATE,

    /** The server refused the refresh token definitively (revoked/expired/reused). */
    REFRESH_REJECTED,

    /**
     * The server refused the refresh token definitively, AND the immediately
     * preceding refresh ended with its response lost in flight.
     *
     * The signature of a rotation the server completed and the phone never
     * received: the token we still hold was spent server-side, so presenting it
     * trips reuse detection. Recorded separately because it is the difference
     * between "the grant was revoked" and "a flaky network cost you your
     * session", and only the second one has a server-side remedy.
     */
    REFRESH_REJECTED_AFTER_LOST_RESPONSE,

    /** The Keystore-backed store could not be opened; the session is unreadable. */
    SECURE_STORE_UNAVAILABLE,

    /** The Keystore-backed store was rebuilt from scratch (keyset unrecoverable). */
    SECURE_STORE_RECREATED,

    /** The stored session blob did not decode. */
    SESSION_DECODE_FAILED,
}

/** Which rail ended the session. */
enum class SignOutTrigger {
    /** A deliberate action by the person holding the phone. */
    USER,

    /** The token refresh rail (proactive or 401-reactive). */
    REFRESH,

    /** Startup routing, before any UI. */
    STARTUP,

    /** Encrypted storage. */
    SECURE_STORE,

    /** An account-level gate from `/auth/me`. */
    ACCOUNT_GATE,
}

/**
 * The cap-and-order rule, pure so it is asserted without a `Context`: newest
 * first, at most [cap] rows, oldest dropped.
 */
internal fun appendCapped(
    existing: List<SignOutEvent>,
    event: SignOutEvent,
    cap: Int = LEDGER_CAP,
): List<SignOutEvent> = (listOf(event) + existing).take(cap)

/** The real ledger: one small unencrypted app-private prefs file. */
class PrefsSignOutLedger(
    appContext: Context,
    private val json: Json,
) : SignOutLedger {
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun record(event: SignOutEvent) {
        try {
            val next = appendCapped(recent(), event)
            prefs.edit()
                .putString(KEY_EVENTS, json.encodeToString(ListSerializer(SignOutEvent.serializer()), next))
                .commit()
            Log.w(
                TAG,
                "Signed out: reason=${event.reason} trigger=${event.trigger} " +
                    "caller=${event.caller} http=${event.httpStatus} code=${event.errorCode}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the sign-out reason.", e)
        }
    }

    override fun recent(): List<SignOutEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(SignOutEvent.serializer()), raw)
        } catch (e: Exception) {
            // A row shape that no longer decodes is not worth a crash on a
            // diagnostics screen; drop the file and start a fresh history.
            Log.w(TAG, "Ledger unreadable; starting a new one.", e)
            prefs.edit().remove(KEY_EVENTS).apply()
            emptyList()
        }
    }

    private companion object {
        const val TAG = "BtAuth"
        const val FILE = "bt_signout_ledger"
        const val KEY_EVENTS = "events"
    }
}

/** No-op ledger for tests and for any construction path that has no context. */
object NoopSignOutLedger : SignOutLedger {
    override fun record(event: SignOutEvent) = Unit
    override fun recent(): List<SignOutEvent> = emptyList()
}
