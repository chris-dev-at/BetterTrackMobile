package at.bettertrack.app.vault.pv.custody

import android.content.Context
import android.os.SystemClock
import android.util.Log
import at.bettertrack.app.vault.Argon2Derive
import at.bettertrack.app.vault.RandomBytes
import at.bettertrack.app.vault.VAULT_ARGON2_PARAMS
import at.bettertrack.app.vault.VAULT_IV_BYTES
import at.bettertrack.app.vault.VAULT_SALT_BYTES
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.aesGcmDecrypt
import at.bettertrack.app.vault.aesGcmEncrypt
import at.bettertrack.app.vault.base64ToBytes
import at.bettertrack.app.vault.bouncyCastleArgon2id
import at.bettertrack.app.vault.bytesToBase64
import at.bettertrack.app.vault.concatBytes
import at.bettertrack.app.vault.deriveVaultKek
import at.bettertrack.app.vault.equalBytes
import at.bettertrack.app.vault.secureRandomBytes
import at.bettertrack.app.vault.utf8
import at.bettertrack.app.vault.zeroBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §12 device custody — the per-endpoint keystore of vault mnemonics.
 *
 * ## One password for the endpoint, not one per vault
 *
 * The 2026-08-12 correction survives into §12 verbatim: there is **one** device
 * password per install. `Argon2id(password, per-endpoint salt)` yields `K_dev`,
 * and every wrapped entry on the endpoint is AES-256-GCM'd under it. Entering
 * the password once per session opens all of them. Per-vault passwords produce
 * exactly the failure this design exists to avoid: a user with four vaults
 * keeps three of them plain, because four prompts is not a product.
 *
 * ## The Argon2 profile is imported, not chosen
 *
 * [VAULT_ARGON2_PARAMS] (m = 65536 KiB, t = 3, p = 1) is the app's existing
 * profile, and §12 names it by value. What moved between the v1 vault and this
 * design is *where* the stretching sits: v1 stretched a typed vault passphrase;
 * §4 took Argon2id off the mnemonic (128 random bits need no stretching, and
 * BIP-39's own PBKDF2 step keeps the words vector-compatible with every BIP-39
 * tool); §12 re-points the same cost family at the one human-chosen secret that
 * remains — this password. The params object is therefore re-used, never
 * re-typed: a hand-copied `65536` would be a second source of truth for a
 * number whose entire property is that there is one.
 *
 * Derivation runs on [kdfDispatcher] for the reason `VaultKeyCustody` documents
 * — 64 MiB and a few hundred milliseconds is a spinner problem, never a
 * weaken-the-parameters problem — and confining it here is what stops a dozen
 * call sites from each getting that wrong.
 *
 * ## The session belongs to the DEVICE, for seven days at most
 *
 * §12 as amended by the owner on 2026-09-03 (web: `keystore/sessionPersistence.ts`
 * + `deviceLock.ts` @ef73d282; vault pass 2026-10-30 item 4). `K_dev` lives in a
 * `@Volatile ByteArray?`, and — only when a [sessionPersistence] is wired and an
 * account is bound ([bindAccount]) — a successful PASSWORD unlock also persists
 * it, sealed under a non-extractable Android Keystore key
 * ([PvKeystoreSessionPersistence]), so process death no longer ends the session.
 * Never a log, never a bundle, never plaintext on disk. A session ends at
 * exactly five edges ([PvLockReason]) and no sixth is invented here:
 *
 *  1. [lock] `EXPLICIT` — the "Lock vaults" action (and the keystore reset);
 *  2. `SIGN_OUT` and 3. `ACCOUNT_SWITCH` ([bindAccount] to another account);
 *  4. `APP_LOCK_IDLE` — the app's EXISTING PIN idle-lock timer, via [bindToAppLock];
 *  5. `TTL` — seven days ABSOLUTE from the unlock that created the session
 *     ([PV_SESSION_PERSISTENCE_TTL_MS]); a resumed session never re-persists, so
 *     resuming never restarts the clock.
 *
 * Every user-intended lock (1-4) writes the per-account device-locked MARKER
 * ([PvDeviceLockMarker]) FIRST, then ends the session, then deletes the record:
 * even if the delete fails, the marker keeps the record inert. The marker is
 * cleared ONLY by a successful password unlock. [resumeSession] refuses while
 * it is set or unreadable, drops an expired record, and installs a candidate
 * only after the same local wrap check [unlock] runs.
 *
 * §12 forbids a second timeout setting ("one timer, one mental model"), which is
 * why [bindToAppLock] takes the app lock's own `locked` flow instead of owning a
 * clock, and why the TTL is enforced lazily ([expireIfPastTtl]) on the hot reads
 * rather than by a timer of its own. There is
 * still no "keep unlocked" option — the TTL is the bound, and plain custody
 * stays the per-phrase, warned-for convenience path.
 *
 * ## A wrong password never touches vault data
 *
 * The wrap check is a fixed marker sealed under `K_dev`. Verifying it is a local
 * GCM open: no vault blob is read, no network call is made, no entry is
 * decrypted. That is what lets the lockout ladder be purely client-side (§12:
 * "there is no server lockout because the server is not involved") and what
 * makes "Forgot the password?" survivable — [resetKeystore] loses the phrases
 * stored on this endpoint and nothing else at all.
 */
class PvDeviceCustody(
    private val keystore: PvEndpointKeystore,
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val randomBytes: RandomBytes = secureRandomBytes,
    private val argon2: Argon2Derive = bouncyCastleArgon2id,
    private val clock: PvElapsedClock = PvElapsedClock { SystemClock.elapsedRealtime() },
    private val sessionPersistence: PvSessionPersistence = NoPvSessionPersistence,
    private val deviceLockMarker: PvDeviceLockMarker = MemoryPvDeviceLockMarker(),
    /** Wall clock for the absolute TTL — it must survive a reboot, unlike [clock]. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val sessionTtlMs: Long = PV_SESSION_PERSISTENCE_TTL_MS,
) {

    private val _unlocked = MutableStateFlow(false)

    /** True while `K_dev` is in memory. The same shape as `AppLockController.locked`, inverted. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Volatile only — see the class KDoc. Zeroed by [lock]. */
    @Volatile
    private var deviceKey: ByteArray? = null

    /** The account the session belongs to; null = nobody bound, nothing persists. */
    @Volatile
    private var accountId: String? = null

    /** Wall-clock instant the live session ends at (the TTL edge); 0 while locked. */
    @Volatile
    private var sessionExpiresAt: Long = 0L

    /** Bumped by every session change; a resume installs only if it is unchanged. */
    @Volatile
    private var sessionGeneration: Long = 0L

    private val sessionMonitor = Any()

    /** The edge that ended the last session, for diagnostics and tests; null before any. */
    @Volatile
    var lastLockReason: PvLockReason? = null
        private set

    /** True once this endpoint has a device password at all. */
    val hasDevicePassword: Boolean get() = keystore.hasDevicePassword

    /** Live failure count, for the unlock sheet's copy. */
    val failureCount: Int get() = keystore.failureCount

    /** Every vault this endpoint holds a phrase for. */
    fun storedVaultIds(): List<String> = keystore.vaultIds()

    /**
     * The §12 state of one vault on this endpoint — the value a surface renders
     * and whose [nextAction] it must offer inline.
     */
    fun stateFor(vaultId: String): PvCustodyState {
        expireIfPastTtl()
        val entry = keystore.entry(vaultId) ?: return PvCustodyState.Absent(vaultId)
        return when (entry.custody) {
            PvCustodyMode.WRAPPED -> PvCustodyState.Wrapped(vaultId, sessionUnlocked = _unlocked.value)
            PvCustodyMode.PLAIN -> PvCustodyState.Plain(vaultId)
        }
    }

    // ── The account boundary ────────────────────────────────────────────────

    /**
     * Bind (or release) the account this endpoint session belongs to
     * (`bindAccount`, keystore/core.ts). The first bind is not a change. A CHANGE
     * of account is a revocation — the live session was proven by the previous
     * account's password — so it is the `ACCOUNT_SWITCH` lock for the previous
     * account: its marker first, then its record goes, or signing back in would
     * reopen without a password. Sign-out is [lock] `SIGN_OUT` followed by
     * `bindAccount(null)`.
     */
    fun bindAccount(accountId: String?) {
        val next = accountId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(sessionMonitor) {
            val previous = this.accountId
            if (previous == next) return
            if (previous != null) lock(PvLockReason.ACCOUNT_SWITCH)
            this.accountId = next
        }
    }

    /** The account currently bound, for surfaces that must not guess it. */
    fun boundAccountId(): String? = accountId

    // ── The device password ─────────────────────────────────────────────────

    /**
     * Chooses the endpoint's device password for the first time and opens the
     * session.
     *
     * Refuses when one already exists: silently replacing it would orphan every
     * wrapped entry on the endpoint — they are sealed under the old `K_dev`, and
     * nothing here can re-wrap what it cannot open. Replacing a *forgotten*
     * password is [resetKeystore]; replacing a *known* one is
     * [changeDevicePassword].
     */
    suspend fun setDevicePassword(password: String): Boolean = withContext(kdfDispatcher) {
        if (keystore.hasDevicePassword) return@withContext false
        if (password.length < PV_DEVICE_PASSWORD_MIN_LENGTH) return@withContext false
        val salt = bytesToBase64(randomBytes(VAULT_SALT_BYTES))
        try {
            val key = deriveDeviceKey(password, salt)
            var adopted = false
            try {
                keystore.putDevicePasswordMaterial(salt = salt, check = sealWrapCheck(key))
                adoptPasswordSession(key)
                adopted = true
            } finally {
                if (!adopted) zeroBytes(key)
            }
            true
        } catch (cause: VaultCryptoError) {
            // Presence-only diagnostics: never the password, never a payload.
            Log.d(TAG, "pv endpoint setup rejected: ${cause.code}")
            false
        }
    }

    /**
     * Re-wraps every wrapped entry under a new device password.
     *
     * The entries are re-sealed in memory FIRST and written only once all of
     * them succeeded, so a failure halfway leaves the endpoint on the old
     * password with every phrase still openable rather than half on each.
     *
     * @return false on a wrong [current], a too-short [next], or no keystore.
     */
    suspend fun changeDevicePassword(current: String, next: String): Boolean = withContext(kdfDispatcher) {
        if (next.length < PV_DEVICE_PASSWORD_MIN_LENGTH) return@withContext false
        val oldSalt = keystore.endpointSalt ?: return@withContext false
        var currentKey: ByteArray? = null
        var nextKey: ByteArray? = null
        try {
            val openingKey = deriveDeviceKey(current, oldSalt)
            currentKey = openingKey
            if (!wrapCheckPasses(openingKey)) return@withContext false

            val newSalt = bytesToBase64(randomBytes(VAULT_SALT_BYTES))
            val sealingKey = deriveDeviceKey(next, newSalt)
            nextKey = sealingKey

            val resealed = keystore.vaultIds().mapNotNull { vaultId ->
                val entry = keystore.entry(vaultId) ?: return@mapNotNull null
                if (entry.custody != PvCustodyMode.WRAPPED) return@mapNotNull null
                val opened = unwrapEntropy(entry, openingKey)
                try {
                    PvKeystoreEntry(vaultId, PvCustodyMode.WRAPPED, wrapEntropy(vaultId, opened, sealingKey))
                } finally {
                    zeroBytes(opened)
                }
            }

            keystore.putDevicePasswordMaterial(salt = newSalt, check = sealWrapCheck(sealingKey))
            resealed.forEach(keystore::putEntry)
            adoptPasswordSession(sealingKey)
            nextKey = null
            true
        } catch (cause: VaultCryptoError) {
            Log.d(TAG, "pv endpoint rekey rejected: ${cause.code}")
            false
        } finally {
            currentKey?.let { zeroBytes(it) }
            nextKey?.let { zeroBytes(it) }
        }
    }

    /**
     * Opens the session with the endpoint's device password.
     *
     * Order matters: the backoff window is honoured **before** anything is
     * derived, so a caller inside a lockout cannot spend the wait on Argon2id
     * runs, and the failure is recorded against the local wrap check only.
     */
    suspend fun unlock(password: String): PvUnlockResult {
        val remaining = currentLockoutRemaining()
        if (remaining > 0) return PvUnlockResult.LockedOut(remaining)
        val salt = keystore.endpointSalt ?: return PvUnlockResult.NoKeystore
        if (keystore.wrapCheck == null) return PvUnlockResult.NoKeystore

        return withContext(kdfDispatcher) {
            try {
                val key = deriveDeviceKey(password, salt)
                var adopted = false
                try {
                    if (wrapCheckPasses(key)) {
                        keystore.resetBackoff()
                        adoptPasswordSession(key)
                        adopted = true
                        PvUnlockResult.Success
                    } else {
                        recordFailure()
                    }
                } finally {
                    if (!adopted) zeroBytes(key)
                }
            } catch (cause: VaultCryptoError) {
                Log.d(TAG, "pv unlock rejected: ${cause.code}")
                recordFailure()
            }
        }
    }

    /** Milliseconds still to wait before another attempt is accepted. */
    fun currentLockoutRemaining(): Long {
        val until = keystore.lockoutUntilElapsed
        if (until <= 0L) return 0L
        val remaining = until - clock.elapsedMillis()
        // Elapsed-realtime restarts at ~0 after a reboot, which would turn a
        // 30-second window into one that outlives the ladder's own ceiling. A
        // remaining time larger than the cap is therefore not a lockout, it is a
        // restarted clock — treat it as expired rather than strand the user.
        if (remaining > PV_LOCKOUT_CAP_MS) return 0L
        return remaining.coerceAtLeast(0L)
    }

    /**
     * Ends the session (`lockDevice`, keystore/core.ts). The entries stay; they
     * simply cannot be opened.
     *
     * ORDER IS THE GUARANTEE: for a user-intended [reason] the marker is written
     * FIRST, before anything that could throw, then `K_dev` is zeroed, then the
     * persisted record is deleted. A record whose delete is slow or fails is
     * inert behind the marker until the next password. [PvLockReason.TTL] is not
     * user-intended: no marker, the record goes.
     */
    fun lock(reason: PvLockReason = PvLockReason.EXPLICIT) {
        synchronized(sessionMonitor) {
            val account = accountId
            if (account != null && reason.userIntended) {
                // A marker that cannot be written must not leave a session standing.
                try {
                    deviceLockMarker.remember(account)
                } catch (_: RuntimeException) {
                    // Fail closed: the session still ends below.
                }
            }
            endSession()
            lastLockReason = reason
            if (account != null) {
                try {
                    sessionPersistence.clear(account)
                } catch (_: RuntimeException) {
                    // The marker keeps an undeletable record inert.
                }
            }
        }
    }

    /**
     * The existing app-lock idle timer, reused (§12: one timer, one mental
     * model). Mirrors `AppGraph.linkVaultLockToAppLock` for this keystore.
     *
     * Only a TRANSITION to locked is the PIN idle lock: `AppLockController.locked`
     * starts `true` on every cold start with a configured PIN, and that gate is
     * not a lock of a session (there is none yet) — reacting to it would write
     * the marker on every launch and make the persisted session unreachable for
     * every PIN user. An emission of `true` while a session IS live still locks.
     *
     * Takes the flow rather than the controller so the binding is testable
     * without Android, and so this class can never grow a timeout of its own.
     */
    fun bindToAppLock(appLocked: Flow<Boolean>, scope: CoroutineScope): Job = scope.launch {
        var seenAny = false
        appLocked.collect { locked ->
            if (locked && (seenAny || deviceKey != null)) lock(PvLockReason.APP_LOCK_IDLE)
            seenAny = true
        }
    }

    /**
     * The TTL edge, checked lazily: ends a live session whose absolute end has
     * passed. Called on every hot read ([stateFor], [entropyFor], [storeEntropy])
     * — the reads a session exists to serve — so the seven days need no timer.
     *
     * @return true when it ended a session.
     */
    fun expireIfPastTtl(): Boolean {
        if (deviceKey == null) return false
        val expiresAt = sessionExpiresAt
        if (expiresAt <= 0L || wallClock() < expiresAt) return false
        lock(PvLockReason.TTL)
        return true
    }

    /**
     * Re-install the device's persisted session after process death — the
     * Android "reload" (`runSessionResume`, keystore/core.ts, minus the sibling-
     * tab grant, which has no Android counterpart).
     *
     * Speculative and silent: every refusal is an outcome, never a throw, and
     * leaves the surface asking for the password, which it would have done
     * anyway. The order is the web's: the marker (LOCKED and UNREADABLE both
     * refuse), the lockout window, the endpoint's password material, the record
     * (an expired one is dropped by [PvSessionPersistence.read]), the wrap check
     * — the authoritative binding, a key not derived from THIS endpoint's
     * password cannot pass it — and once more, with nothing in between, the
     * generation, the account, the marker and "no session already standing"
     * immediately before the install. A resumed session keeps the record's
     * `expiresAt` and is NOT re-persisted (the TTL is absolute).
     */
    fun resumeSession(): PvResumeOutcome {
        if (deviceKey != null) return PvResumeOutcome.ALREADY_UNLOCKED
        val account = accountId ?: return PvResumeOutcome.NO_ACCOUNT
        val generation = sessionGeneration
        when (deviceLockMarker.read(account)) {
            PvDeviceLockState.LOCKED -> return PvResumeOutcome.DEVICE_LOCKED
            PvDeviceLockState.UNREADABLE -> return PvResumeOutcome.MARKER_UNREADABLE
            PvDeviceLockState.CLEAR -> Unit
        }
        if (currentLockoutRemaining() > 0) return PvResumeOutcome.LOCKED_OUT
        if (!keystore.hasDevicePassword) {
            // A reset endpoint: whatever is persisted can never open anything.
            sessionPersistence.clear(account)
            return PvResumeOutcome.NO_KEYSTORE
        }
        val candidate = sessionPersistence.read(account, wallClock())
            ?: return PvResumeOutcome.NOTHING_PERSISTED
        var installed = false
        try {
            if (!wrapCheckPasses(candidate.deviceKey)) {
                sessionPersistence.clear(account)
                return PvResumeOutcome.WRAP_CHECK_FAILED
            }
            synchronized(sessionMonitor) {
                if (sessionGeneration != generation || accountId != account || deviceKey != null ||
                    deviceLockMarker.read(account) != PvDeviceLockState.CLEAR
                ) {
                    return PvResumeOutcome.SUPERSEDED
                }
                adoptSessionKey(candidate.deviceKey, expiresAt = candidate.expiresAt)
                installed = true
            }
            return PvResumeOutcome.RESUMED
        } finally {
            if (!installed) zeroBytes(candidate.deviceKey)
        }
    }

    // ── Entries ─────────────────────────────────────────────────────────────

    /**
     * Stores a vault's mnemonic entropy under the chosen custody.
     *
     * Wrapped storage needs an open session — there is no way to seal an entry
     * without `K_dev`, and prompting from inside a storage call would put a
     * password sheet in the middle of an arbitrary code path.
     *
     * The caller keeps ownership of [entropy] and should zero it afterwards;
     * nothing beyond the ciphertext written here is retained.
     */
    fun storeEntropy(vaultId: String, entropy: ByteArray, custody: PvCustodyMode): Boolean {
        if (entropy.size != PV_ENTROPY_BYTES) return false
        expireIfPastTtl()
        return when (custody) {
            PvCustodyMode.PLAIN -> {
                keystore.putEntry(PvKeystoreEntry(vaultId, PvCustodyMode.PLAIN, bytesToBase64(entropy)))
                true
            }

            PvCustodyMode.WRAPPED -> {
                val key = deviceKey ?: return false
                try {
                    keystore.putEntry(
                        PvKeystoreEntry(vaultId, PvCustodyMode.WRAPPED, wrapEntropy(vaultId, entropy, key)),
                    )
                    true
                } catch (cause: VaultCryptoError) {
                    Log.d(TAG, "pv entry not stored: ${cause.code}")
                    false
                }
            }
        }
    }

    /**
     * Flips one phrase between wrapped and plain (§12: "the toggle is per stored
     * phrase, changeable both ways").
     *
     * Both directions need the session: plain → wrapped because sealing needs
     * `K_dev`, wrapped → plain because the entropy must be read before it can be
     * rewritten. That is the honest shape — "store it plain" is a decision about
     * a phrase you currently hold, never a way to reach one you do not.
     */
    fun setCustodyMode(vaultId: String, custody: PvCustodyMode): Boolean {
        val entry = keystore.entry(vaultId) ?: return false
        if (entry.custody == custody) return true
        val entropy = entropyFor(vaultId) ?: return false
        return try {
            storeEntropy(vaultId, entropy, custody)
        } finally {
            zeroBytes(entropy)
        }
    }

    /**
     * The vault's mnemonic entropy, or null when it is not here or the session
     * is locked.
     *
     * Returns a fresh array the caller owns and should zero — nothing is cached
     * between calls, so a session that ended really is a read that fails.
     */
    fun entropyFor(vaultId: String): ByteArray? {
        expireIfPastTtl()
        val entry = keystore.entry(vaultId) ?: return null
        return try {
            when (entry.custody) {
                PvCustodyMode.PLAIN -> base64ToBytes(entry.payload, VaultCryptoErrorCode.ENVELOPE_INVALID)
                PvCustodyMode.WRAPPED -> unwrapEntropy(entry, deviceKey ?: return null)
            }
        } catch (cause: VaultCryptoError) {
            Log.d(TAG, "pv entry unreadable: ${cause.code}")
            null
        }
    }

    /** The 12 words for a vault, re-derived from its entropy. Null under the same conditions. */
    fun wordsFor(vaultId: String): List<String>? {
        val entropy = entropyFor(vaultId) ?: return null
        return try {
            pvEntropyToWords(entropy)
        } catch (cause: VaultCryptoError) {
            Log.d(TAG, "pv render unavailable: ${cause.code}")
            null
        } finally {
            zeroBytes(entropy)
        }
    }

    /** Forgets one vault's phrase on this endpoint. The vault itself is untouched. */
    fun forget(vaultId: String) = keystore.removeEntry(vaultId)

    /**
     * "Forgot the password?" → the §12 keystore reset.
     *
     * Deletes the entries, the salt, the wrap check and the counters on THIS
     * endpoint. No vault data is lost: the phrases come back by typing the words
     * or by scanning the §13 QR from another device — which is the one sentence
     * the confirm sheet says out loud.
     */
    fun resetKeystore() {
        // The most deliberate lock there is (web `reset()` → `lockDevice()`):
        // the marker is set, the record goes, and the next password clears it.
        lock(PvLockReason.EXPLICIT)
        keystore.reset()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun adoptSessionKey(key: ByteArray, expiresAt: Long) {
        synchronized(sessionMonitor) {
            deviceKey?.let { zeroBytes(it) }
            deviceKey = key
            sessionExpiresAt = expiresAt
            sessionGeneration += 1
            _unlocked.value = true
        }
    }

    /**
     * A session the user just PROVED with the password (setup, unlock, change):
     * installed with a fresh absolute TTL, the marker's ONLY clearing edge
     * (`forgetEndpointDeviceLocked`), then persisted (`rememberSession`) — when an
     * account is bound. Persistence failures are swallowed by the implementation;
     * the session stands in memory regardless.
     */
    private fun adoptPasswordSession(key: ByteArray) {
        val expiresAt = wallClock() + sessionTtlMs
        adoptSessionKey(key, expiresAt)
        val account = accountId ?: return
        deviceLockMarker.forget(account)
        sessionPersistence.persist(account, key, expiresAt)
    }

    private fun endSession() {
        deviceKey?.let { zeroBytes(it) }
        deviceKey = null
        sessionExpiresAt = 0L
        sessionGeneration += 1
        _unlocked.value = false
    }

    private fun recordFailure(): PvUnlockResult.Wrong {
        val count = keystore.failureCount + 1
        keystore.failureCount = count
        val lockoutMs = pvLockoutMillisFor(count)
        if (lockoutMs > 0) keystore.lockoutUntilElapsed = clock.elapsedMillis() + lockoutMs
        return PvUnlockResult.Wrong(failureCount = count, lockoutMillis = lockoutMs)
    }

    /**
     * `K_dev = Argon2id(password, endpoint salt)` at the app's profile.
     *
     * Routed through [deriveVaultKek] rather than calling [argon2] directly so
     * that the strict profile check comes along: parameters that are not
     * [VAULT_ARGON2_PARAMS] refuse to derive at all instead of quietly producing
     * a weaker key.
     */
    private fun deriveDeviceKey(password: String, salt: String): ByteArray =
        deriveVaultKek(password, VAULT_ARGON2_PARAMS.copy(salt = salt), argon2)

    private fun sealWrapCheck(key: ByteArray): String {
        val iv = randomBytes(VAULT_IV_BYTES)
        return try {
            bytesToBase64(concatBytes(iv, aesGcmEncrypt(key, iv, WRAP_CHECK_MARKER, WRAP_CHECK_MARKER)))
        } finally {
            zeroBytes(iv)
        }
    }

    /**
     * Local verification: does [key] open the stored marker?
     *
     * The GCM tag alone already answers it; the plaintext is compared as well so
     * a future marker change cannot silently pass an old check.
     */
    private fun wrapCheckPasses(key: ByteArray): Boolean {
        val stored = keystore.wrapCheck ?: return false
        return try {
            val bytes = base64ToBytes(stored, VaultCryptoErrorCode.ENVELOPE_INVALID)
            if (bytes.size <= VAULT_IV_BYTES) return false
            val opened = aesGcmDecrypt(
                key,
                bytes.copyOfRange(0, VAULT_IV_BYTES),
                bytes.copyOfRange(VAULT_IV_BYTES, bytes.size),
                WRAP_CHECK_MARKER,
            )
            equalBytes(opened, WRAP_CHECK_MARKER)
        } catch (_: VaultCryptoError) {
            // A wrong password is an ordinary user event, not an exception.
            false
        }
    }

    private fun wrapEntropy(vaultId: String, entropy: ByteArray, key: ByteArray): String {
        val iv = randomBytes(VAULT_IV_BYTES)
        return try {
            bytesToBase64(concatBytes(iv, aesGcmEncrypt(key, iv, entropy, utf8(vaultId))))
        } finally {
            zeroBytes(iv)
        }
    }

    private fun unwrapEntropy(entry: PvKeystoreEntry, key: ByteArray): ByteArray {
        val bytes = base64ToBytes(entry.payload, VaultCryptoErrorCode.ENVELOPE_INVALID)
        if (bytes.size <= VAULT_IV_BYTES) {
            throw VaultCryptoError(
                VaultCryptoErrorCode.AUTHENTICATION_FAILED,
                "Custody entry is structurally invalid.",
            )
        }
        return aesGcmDecrypt(
            key,
            bytes.copyOfRange(0, VAULT_IV_BYTES),
            bytes.copyOfRange(VAULT_IV_BYTES, bytes.size),
            utf8(entry.vaultId),
        )
    }

    companion object {
        private const val TAG = "BtPvCustody"

        /**
         * The wrap check's plaintext AND its AAD.
         *
         * A constant marker, not a vault byte: §12 requires a wrong password to
         * be caught "locally without touching any vault data", and the cheapest
         * honest way to do that is one value on the endpoint whose only job is
         * to be openable.
         */
        private val WRAP_CHECK_MARKER: ByteArray = utf8("bettertrack-pv-device-check-v1")

        /**
         * The production wiring: the keystore, the device-locked marker and the
         * Keystore-sealed session record all in the ONE pv endpoint prefs file.
         * Reached only through `AppGraph.startParanoidVaults` (flag OFF).
         */
        fun create(context: Context): PvDeviceCustody {
            val prefs = PvEndpointKeystore.openPrefs(context)
            return PvDeviceCustody(
                keystore = PvEndpointKeystore(prefs),
                sessionPersistence = PvKeystoreSessionPersistence(prefs, AndroidKeystoreSessionCipher()),
                deviceLockMarker = PrefsPvDeviceLockMarker(prefs),
            )
        }
    }
}

/**
 * The floor for a device password.
 *
 * Eight characters is what the copy (`bt_pv_custody_choice_too_short`) promises
 * in both languages, spelled as a word rather than formatted from this constant
 * — so the two are pinned together by `PvCustodyStateTest` instead of by a
 * placeholder nobody would notice going stale.
 */
const val PV_DEVICE_PASSWORD_MIN_LENGTH: Int = 8
