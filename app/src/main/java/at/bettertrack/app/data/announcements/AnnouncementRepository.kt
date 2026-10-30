package at.bettertrack.app.data.announcements

import android.content.Context
import android.util.Log
import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.unitApiCall
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/** The announcement error codes the app branches on (contract §8). */
object AnnouncementCodes {
    const val ACKNOWLEDGEMENT_REQUIRED = "ACKNOWLEDGEMENT_REQUIRED"
    const val NOT_FOUND = "ANNOUNCEMENT_NOT_FOUND"
    const val NOT_DISMISSIBLE = "ANNOUNCEMENT_NOT_DISMISSIBLE"
    const val NOT_ACKNOWLEDGEABLE = "ANNOUNCEMENT_NOT_ACKNOWLEDGEABLE"
    const val NOT_PENDING = "ANNOUNCEMENT_NOT_PENDING"
    const val CONTENT_CHANGED = "ANNOUNCEMENT_CONTENT_CHANGED"
    const val LOCALE_UNAVAILABLE = "ANNOUNCEMENT_LOCALE_UNAVAILABLE"
}

/** What a `seen` or `dismiss` call achieved. */
sealed interface ReceiptOutcome {
    /** The server stamped it (`200`). */
    data object Recorded : ReceiptOutcome

    /**
     * `403 INSUFFICIENT_SCOPE` — a grant without `notifications:write` (contract
     * §9 step 5). For a dismiss the caller closes the item LOCALLY, per id.
     */
    data object ScopeMissing : ReceiptOutcome

    /** `404 ANNOUNCEMENT_NOT_FOUND` — the item is no longer in the caller's list. */
    data object Gone : ReceiptOutcome

    /** `409 ANNOUNCEMENT_NOT_DISMISSIBLE` — an `ack_required` (dismiss only). */
    data object NotDismissible : ReceiptOutcome

    /** Anything else: transport, 5xx, an unexpected refusal. */
    data class Failed(val error: BtApiError) : ReceiptOutcome
}

/** What an acknowledge attempt achieved (contract §4.5, evaluated in its order). */
sealed interface AckOutcome {
    /**
     * Recorded — or recorded EARLIER (first-write-wins returns the original).
     * [pending] is what is still owed, as the tri-state: the field is required
     * on the contract, but an odd body must not be read as "nothing owed".
     */
    data class Acknowledged(val pending: PendingAcks) : AckOutcome

    /** `409 ANNOUNCEMENT_CONTENT_CHANGED` → refetch the item, ask again. */
    data object ContentChanged : AckOutcome

    /** `409 ANNOUNCEMENT_NOT_PENDING` (or NOT_ACKNOWLEDGEABLE / 404) → refetch `/auth/me`, move on. */
    data class NoLongerPending(val code: String) : AckOutcome

    /** `400 ANNOUNCEMENT_LOCALE_UNAVAILABLE` → show an available locale, ask again. */
    data object LocaleUnavailable : AckOutcome

    /** Transport, 5xx after retries, or an unexpected refusal — the gate stays, the user may retry. */
    data class Failed(val error: BtApiError) : AckOutcome
}

/**
 * The announcement endpoints, mapped into outcomes the controller can branch on
 * without knowing HTTP. Every method is fail-soft: nothing here throws.
 */
class AnnouncementRepository(
    private val api: BtApi,
    private val json: Json,
    /** Injected so tests drive the acknowledge retry without real time. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun list(locale: String?): BtResult<List<Announcement>> =
        when (val r = apiCall(json) { api.announcements(locale) }) {
            is BtResult.Ok -> BtResult.Ok(parseAnnouncementList(r.value))
            is BtResult.Err -> r
        }

    suspend fun get(id: String, locale: String?): BtResult<Announcement> =
        when (val r = apiCall(json) { api.announcement(id, locale) }) {
            is BtResult.Ok -> parseAnnouncement(r.value)?.let { BtResult.Ok(it) }
                ?: BtResult.Err(BtApiError(200, AnnouncementCodes.NOT_FOUND, "unparseable item"))
            is BtResult.Err -> r
        }

    suspend fun seen(id: String): ReceiptOutcome = receipt { api.announcementSeen(id) }

    suspend fun dismiss(id: String): ReceiptOutcome = receipt { api.dismissAnnouncement(id) }

    private suspend fun receipt(call: suspend () -> retrofit2.Response<*>): ReceiptOutcome =
        when (val r = unitApiCall(json, call)) {
            is BtResult.Ok -> ReceiptOutcome.Recorded
            is BtResult.Err -> when {
                r.error.httpStatus == 403 && r.error.isInsufficientScope -> ReceiptOutcome.ScopeMissing
                r.error.code == AnnouncementCodes.NOT_FOUND || r.error.httpStatus == 404 -> ReceiptOutcome.Gone
                r.error.code == AnnouncementCodes.NOT_DISMISSIBLE -> ReceiptOutcome.NotDismissible
                else -> ReceiptOutcome.Failed(r.error)
            }
        }

    /**
     * Acknowledge with the strict body, echoing [locale] and [contentSha256] of
     * the exact item on screen (the app never computes the hash).
     *
     * Retried on transport failures and 5xx: the route is idempotent and
     * first-write-wins (contract §4.5 step 3), so a timed-out acknowledgement
     * that DID land simply returns the original on the retry.
     */
    suspend fun acknowledge(id: String, locale: String, contentSha256: String): AckOutcome {
        val body = AcknowledgeAnnouncementRequest(confirm = true, locale = locale, contentSha256 = contentSha256)
        var attempt = 0
        while (true) {
            when (val r = apiCall(json) { api.acknowledgeAnnouncement(id, body) }) {
                is BtResult.Ok -> return AckOutcome.Acknowledged(pendingAcksOf(r.value["pendingAcknowledgements"]))
                is BtResult.Err -> {
                    val e = r.error
                    val retryable = e.isNetwork || e.httpStatus >= 500 || e.httpStatus == -1
                    if (retryable && attempt < ACK_RETRIES) {
                        sleep(ACK_RETRY_DELAYS_MS[attempt])
                        attempt++
                        continue
                    }
                    return when (e.code) {
                        AnnouncementCodes.CONTENT_CHANGED -> AckOutcome.ContentChanged
                        AnnouncementCodes.LOCALE_UNAVAILABLE -> AckOutcome.LocaleUnavailable
                        AnnouncementCodes.NOT_PENDING,
                        AnnouncementCodes.NOT_ACKNOWLEDGEABLE,
                        AnnouncementCodes.NOT_FOUND,
                        -> AckOutcome.NoLongerPending(e.code)
                        else -> {
                            Log.w(TAG, "acknowledge refused: HTTP ${e.httpStatus} ${e.code}")
                            AckOutcome.Failed(e)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "BtAnnouncements"
        const val ACK_RETRIES = 2
        val ACK_RETRY_DELAYS_MS = longArrayOf(600L, 1_800L)
    }
}

/**
 * Per-account local dismissals — the fallback for a grant without
 * `notifications:write` (contract §9 step 5: `403 INSUFFICIENT_SCOPE` from
 * dismiss → close the `notice`/`once` locally). Keyed by account id so a
 * different sign-in never inherits them.
 */
interface LocalDismissStore {
    fun load(accountId: String): Set<String>
    fun add(accountId: String, announcementId: String)
}

class PrefsLocalDismissStore(context: Context) : LocalDismissStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(accountId: String): Set<String> =
        if (accountId.isBlank()) emptySet() else prefs.getStringSet(key(accountId), emptySet()).orEmpty().toSet()

    override fun add(accountId: String, announcementId: String) {
        if (accountId.isBlank()) return
        val next = load(accountId) + announcementId
        // Bounded: announcements are rare, but a set that only grows is a leak.
        val bounded = if (next.size > MAX_IDS) next.toList().takeLast(MAX_IDS).toSet() else next
        prefs.edit().putStringSet(key(accountId), bounded).apply()
    }

    private fun key(accountId: String) = "dismissed:$accountId"

    private companion object {
        const val PREFS = "bt_announcements"
        const val MAX_IDS = 200
    }
}

/** In-memory store for tests and for builds where nothing may persist. */
class InMemoryLocalDismissStore : LocalDismissStore {
    private val map = mutableMapOf<String, MutableSet<String>>()
    override fun load(accountId: String): Set<String> = map[accountId]?.toSet().orEmpty()
    override fun add(accountId: String, announcementId: String) {
        map.getOrPut(accountId) { mutableSetOf() } += announcementId
    }
}
