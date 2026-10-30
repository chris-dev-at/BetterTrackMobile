package at.bettertrack.app.vault.pv.custody

import android.content.SharedPreferences

/**
 * §12 as amended by the owner on 2026-09-03 — the endpoint session belongs to
 * the DEVICE for a bounded time, not to the process.
 *
 * The web counterpart is `apps/web/src/user/vault/keystore/sessionPersistence.ts`
 * + `deviceLock.ts` (@ef73d282): K_dev survives a reload as a NON-EXTRACTABLE
 * key, a session ends only at an explicit lock, sign-out, an account switch, the
 * PIN idle lock or the absolute TTL, and every user-intended lock writes a
 * per-account device-locked MARKER first. On Android the reload is process
 * death; the shapes below are that design's Kotlin form (vault pass 2026-10-30
 * item 4, docs/TODO.md custody ruling). The program flag stays OFF.
 *
 * Persistence is an optimization, never a dependency: every failure resolves to
 * "nothing persisted", and the device password still works.
 */

/**
 * Absolute lifetime of a persisted session, measured from the unlock that
 * created it (`ENDPOINT_SESSION_PERSISTENCE_TTL_MS`, sessionPersistence.ts).
 * A resumed session never re-persists, so resuming never restarts this clock.
 */
const val PV_SESSION_PERSISTENCE_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

/** What a persisted session hands back: K_dev (a fresh array the caller owns) and its absolute end. */
class PvPersistedSession(val deviceKey: ByteArray, val expiresAt: Long)

/**
 * `EndpointSessionPersistence`. One difference, on purpose: [read] also returns
 * the record's `expiresAt`, because an Android process can outlive a tab by
 * days and the resumed in-memory session must end at the SAME absolute instant
 * the record does (see [PvDeviceCustody.expireIfPastTtl]).
 */
interface PvSessionPersistence {
    /** Store K_dev for the account; replaces any earlier record. Never throws. */
    fun persist(accountId: String, deviceKey: ByteArray, expiresAt: Long)

    /**
     * The persisted session, or null when absent, expired, malformed or
     * unreadable. An expired or unreadable record is deleted. Never throws.
     */
    fun read(accountId: String, now: Long): PvPersistedSession?

    /** Forget the account's persisted session. Idempotent. Never throws. */
    fun clear(accountId: String)
}

/** `NO_ENDPOINT_SESSION_PERSISTENCE` — the pre-amendment memory-only behaviour. */
object NoPvSessionPersistence : PvSessionPersistence {
    override fun persist(accountId: String, deviceKey: ByteArray, expiresAt: Long) = Unit
    override fun read(accountId: String, now: Long): PvPersistedSession? = null
    override fun clear(accountId: String) = Unit
}

/**
 * `createMemoryEndpointSessionPersistence` — the JVM-test implementation. It
 * models the contract exactly, expiry included (`expiresAt <= now` is expired
 * and deleted), and copies bytes in and out so a caller zeroing its array
 * cannot reach the stored one.
 */
class MemoryPvSessionPersistence : PvSessionPersistence {
    private class Record(val deviceKey: ByteArray, val createdAt: Long, val expiresAt: Long)

    private val records = HashMap<String, Record>()

    @Synchronized
    override fun persist(accountId: String, deviceKey: ByteArray, expiresAt: Long) {
        records.remove(accountId)?.deviceKey?.fill(0)
        records[accountId] = Record(deviceKey.copyOf(), expiresAt - PV_SESSION_PERSISTENCE_TTL_MS, expiresAt)
    }

    @Synchronized
    override fun read(accountId: String, now: Long): PvPersistedSession? {
        val record = records[accountId] ?: return null
        if (record.expiresAt <= now) {
            clear(accountId)
            return null
        }
        return PvPersistedSession(record.deviceKey.copyOf(), record.expiresAt)
    }

    @Synchronized
    override fun clear(accountId: String) {
        records.remove(accountId)?.deviceKey?.fill(0)
    }

    @Synchronized
    fun size(): Int = records.size

    /** The stored expiry, for TTL assertions; null when nothing is stored. */
    @Synchronized
    fun expiresAtOf(accountId: String): Long? = records[accountId]?.expiresAt
}

// ── The device-locked marker (deviceLock.ts) ────────────────────────────────

/**
 * `EndpointDeviceLockMarker`: "I could not read it" is kept DISTINCT from "it
 * is set". The resume path folds [UNREADABLE] into locked (fail closed: it
 * would install a session nobody proved on this run).
 */
enum class PvDeviceLockState { LOCKED, CLEAR, UNREADABLE }

/**
 * The per-account §12 device-locked marker. It holds no secret and opens
 * nothing: it records that the last deliberate act on this device was a lock,
 * so a persisted K_dev stays inert until the next password even if deleting the
 * record failed.
 */
interface PvDeviceLockMarker {
    /** `rememberEndpointDeviceLocked`: written FIRST on every user-intended lock. Never throws. */
    fun remember(accountId: String)

    /** `forgetEndpointDeviceLocked`: cleared ONLY by a successful password unlock. Never throws. */
    fun forget(accountId: String)

    /** `readEndpointDeviceLockMarker`. Never throws. */
    fun read(accountId: String): PvDeviceLockState
}

/**
 * The marker in the pv endpoint prefs, one boolean per account.
 *
 * [remember] COMMITS synchronously (not `apply()`): the marker is the backstop
 * for a record delete that is slow or fails, so it has to be on disk before the
 * lock goes on to anything else — a process killed right after the lock must
 * still find it.
 */
class PrefsPvDeviceLockMarker(private val prefs: SharedPreferences) : PvDeviceLockMarker {
    override fun remember(accountId: String) {
        try {
            prefs.edit().putBoolean(key(accountId), true).commit()
        } catch (_: RuntimeException) {
            // The session still ended in memory, and an unwritable store reads
            // back UNREADABLE, which the resume treats as locked.
        }
    }

    override fun forget(accountId: String) {
        try {
            prefs.edit().remove(key(accountId)).commit()
        } catch (_: RuntimeException) {
            // Keeping the marker is the fail-closed outcome.
        }
    }

    override fun read(accountId: String): PvDeviceLockState = try {
        if (prefs.getBoolean(key(accountId), false)) PvDeviceLockState.LOCKED else PvDeviceLockState.CLEAR
    } catch (_: RuntimeException) {
        PvDeviceLockState.UNREADABLE
    }

    private fun key(accountId: String) = "$KEY_PREFIX$accountId"

    companion object {
        /** `bettertrack:endpoint-device-locked:` (deviceLock.ts), in this file's key style. */
        internal const val KEY_PREFIX = "pv_device_locked."
    }
}

/** In-memory marker for JVM tests; [unreadable] simulates a store that throws on read. */
class MemoryPvDeviceLockMarker : PvDeviceLockMarker {
    private val locked = HashSet<String>()
    var unreadable: Boolean = false

    @Synchronized
    override fun remember(accountId: String) {
        locked += accountId
    }

    @Synchronized
    override fun forget(accountId: String) {
        locked -= accountId
    }

    @Synchronized
    override fun read(accountId: String): PvDeviceLockState = when {
        unreadable -> PvDeviceLockState.UNREADABLE
        accountId in locked -> PvDeviceLockState.LOCKED
        else -> PvDeviceLockState.CLEAR
    }
}

// ── Lock edges and resume outcomes ──────────────────────────────────────────

/**
 * The five edges that end a session (§12 as amended 2026-09-03). The first four
 * are user-intended and write the marker first; [TTL] is the clock running out,
 * which ends the session and drops the record without claiming the user locked.
 */
enum class PvLockReason(val userIntended: Boolean) {
    EXPLICIT(true),
    SIGN_OUT(true),
    ACCOUNT_SWITCH(true),
    APP_LOCK_IDLE(true),
    TTL(false),
}

/** What [PvDeviceCustody.resumeSession] did — every refusal leaves the password prompt standing. */
enum class PvResumeOutcome {
    RESUMED,
    ALREADY_UNLOCKED,
    NO_ACCOUNT,
    DEVICE_LOCKED,
    MARKER_UNREADABLE,
    LOCKED_OUT,
    NO_KEYSTORE,
    NOTHING_PERSISTED,
    WRAP_CHECK_FAILED,
    SUPERSEDED,
}
