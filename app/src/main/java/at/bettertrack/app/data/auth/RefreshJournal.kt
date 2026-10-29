package at.bettertrack.app.data.auth

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The last few token renewals, kept on the phone — the evidence the
 * [SignOutLedger] cannot hold.
 *
 * The ledger records the END of a session. The owner's recurring "signed out
 * after a day or two" is decided earlier, by whichever renewal lost its answer
 * hours before the refusal arrived, and nothing kept that. This does: one row
 * per `POST /oauth/token` refresh attempt (who asked, which attempt of the token,
 * what came back, how long the app then waited). It lives in its own plain file
 * for the ledger's reasons — a forced sign-out wipes the secure store, and the
 * rows describing it must survive that.
 *
 * **No tokens, no fingerprints, no ids, no hosts.** A timestamp and short
 * machine labels only.
 */
interface RefreshJournal {
    /** Never throws — diagnostics must not break a refresh. */
    fun record(entry: RefreshJournalEntry)

    /** Newest first, at most [REFRESH_JOURNAL_CAP]. */
    fun recent(): List<RefreshJournalEntry>
}

const val REFRESH_JOURNAL_CAP: Int = 30

/**
 * @param caller a [RefreshCaller] name.
 * @param attempt 1 for a token's first presentation, 2+ for a replay of the same token.
 * @param verdict a [RefreshVerdict] name.
 * @param waitMs the pause the app put in force afterwards (the replay delay or the backoff), if any.
 */
@Serializable
data class RefreshJournalEntry(
    val at: Long,
    val caller: String,
    val attempt: Int,
    val verdict: String,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val waitMs: Long? = null,
)

internal fun appendCappedJournal(
    existing: List<RefreshJournalEntry>,
    entry: RefreshJournalEntry,
    cap: Int = REFRESH_JOURNAL_CAP,
): List<RefreshJournalEntry> = (listOf(entry) + existing).take(cap)

class PrefsRefreshJournal(
    appContext: Context,
    private val json: Json,
) : RefreshJournal {
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun record(entry: RefreshJournalEntry) {
        try {
            prefs.edit()
                .putString(KEY, json.encodeToString(SERIALIZER, appendCappedJournal(recent(), entry)))
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the refresh outcome.", e)
        }
    }

    override fun recent(): List<RefreshJournalEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(SERIALIZER, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh journal unreadable; starting a new one.", e)
            prefs.edit().remove(KEY).apply()
            emptyList()
        }
    }

    private companion object {
        const val TAG = "BtAuth"
        const val FILE = "bt_refresh_journal"
        const val KEY = "entries"
        val SERIALIZER = ListSerializer(RefreshJournalEntry.serializer())
    }
}

object NoopRefreshJournal : RefreshJournal {
    override fun record(entry: RefreshJournalEntry) = Unit
    override fun recent(): List<RefreshJournalEntry> = emptyList()
}
