package at.bettertrack.app.data.applock

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-wide brain of the local app lock (spec §5). It owns the lock
 * state, the setup/change/disable operations, PIN verification with progressive
 * backoff, and the two lock triggers:
 *  - **cold start** — the initial [locked] value is simply "is the lock enabled",
 *    so a fresh process with a configured lock always starts locked;
 *  - **AFK return** — a ProcessLifecycleOwner observer records when the app is
 *    backgrounded and re-locks on return once the configured idle threshold
 *    (immediately / 1 / 5 / 15 min) has elapsed.
 *
 * The lock only *gates* while logged in — [at.bettertrack.app.ui.shell.BtRoot]
 * shows the lock screen inside the logged-in branch — but the config itself is
 * login-independent and persists across sessions in [AppLockStore].
 *
 * All mutations are quick (one HMAC + a small encrypted-prefs write) and run
 * synchronously on the caller; the pure policy lives in [AppLockModels].
 */
class AppLockController(private val store: AppLockStore) {

    private val _locked = MutableStateFlow(store.enabled)
    /** True while the lock screen must be shown instead of app content. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _config = MutableStateFlow(readConfig())
    /** The Security-screen snapshot: enabled, hasPin, biometric, threshold. */
    val config: StateFlow<AppLockConfig> = _config.asStateFlow()

    private val _attempts = MutableStateFlow(AttemptState(store.failureCount, store.lockoutUntilElapsed))
    /** Live failure count + lockout deadline, so the lock screen can count down. */
    val attempts: StateFlow<AttemptState> = _attempts.asStateFlow()

    /** Elapsed-real-time of the last background transition; null before any. */
    private var backgroundedAtElapsed: Long? = null
    private var lifecycleRegistered = false

    val isLockEnabled: Boolean get() = _config.value.enabled

    /** Register the AFK background/foreground trigger. Call once at app start. */
    fun start() {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // The app went to the background — remember when, for the AFK gate.
                if (isLockEnabled) backgroundedAtElapsed = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                // Returned to the foreground. Cold start leaves backgroundedAt null
                // (already locked = enabled), so only a real return evaluates the
                // threshold. Immediately (0 min) re-locks on any return.
                val since = backgroundedAtElapsed ?: return
                backgroundedAtElapsed = null
                if (!isLockEnabled) return
                val idle = SystemClock.elapsedRealtime() - since
                if (idle >= _config.value.afkThreshold.millis) lock()
            }
        })
    }

    /** Force the lock on (no-op when the lock is disabled). */
    fun lock() {
        if (isLockEnabled) _locked.value = true
    }

    // ── Unlock paths ────────────────────────────────────────────────────────

    /**
     * Verify an entered PIN. Honors any open backoff window first, then either
     * unlocks (resetting the backoff) or records the miss and escalates the
     * lockout per [lockoutMillisFor].
     */
    fun verifyPin(pin: String): PinVerifyResult {
        val now = SystemClock.elapsedRealtime()
        val remaining = store.lockoutUntilElapsed - now
        if (remaining > 0) return PinVerifyResult.LockedOut(remaining)

        val salt = store.pinSalt() ?: return PinVerifyResult.Wrong(store.failureCount, 0L)
        val candidate = AppLockCrypto.hashPin(pin, salt)
        return if (store.pinHashMatches(candidate)) {
            store.resetBackoff()
            _attempts.value = AttemptState()
            _locked.value = false
            PinVerifyResult.Success
        } else {
            val count = store.failureCount + 1
            store.failureCount = count
            val lockoutMs = lockoutMillisFor(count)
            if (lockoutMs > 0) store.lockoutUntilElapsed = now + lockoutMs
            _attempts.value = AttemptState(count, store.lockoutUntilElapsed)
            PinVerifyResult.Wrong(count, lockoutMs)
        }
    }

    /** Biometric authentication succeeded (BiometricPrompt callback). */
    fun onBiometricSuccess() {
        store.resetBackoff()
        _attempts.value = AttemptState()
        _locked.value = false
    }

    /** Milliseconds still to wait before another PIN attempt is allowed. */
    fun currentLockoutRemaining(): Long =
        (store.lockoutUntilElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    // ── Setup / change / disable (Settings → Security) ────────────────────────

    /**
     * Set (or replace) the PIN and turn the lock on. Resets backoff.
     *
     * [source] records whether this is a fresh device PIN or the user's BetterTrack
     * account PIN. Either way the PIN is only ever stored LOCALLY (Keystore-hashed);
     * the BetterTrack path is not server-verified yet — see the TODO below.
     */
    fun setupPin(pin: String, source: PinSource = PinSource.Default) {
        // TODO(platform verify-pin): once POST /auth/verify-pin ships, a
        // source == BETTERTRACK PIN must be validated against the account HERE
        // (before storing) and this call should become suspend/return a result so
        // the setup screen can surface a "that's not your BetterTrack PIN" error;
        // a "PIN changed since" signal would also drive a re-enter prompt. Until
        // then we capture + hash it locally exactly like a device PIN and make no
        // server-verified claim in the UI.
        val salt = AppLockCrypto.newSalt()
        store.savePin(hash = AppLockCrypto.hashPin(pin, salt), salt = salt, length = pin.length, source = source)
        store.enabled = true
        _attempts.value = AttemptState()
        refresh()
        Log.i(TAG, "App lock enabled (pin length ${pin.length}, source $source).")
    }

    /** A non-mutating PIN check used by the change-PIN flow (no lock/backoff side effects). */
    fun checkPin(pin: String): Boolean {
        val salt = store.pinSalt() ?: return false
        return store.pinHashMatches(AppLockCrypto.hashPin(pin, salt))
    }

    fun setBiometricEnabled(enabled: Boolean) {
        store.biometricEnabled = enabled
        refresh()
    }

    fun setAfkThreshold(threshold: AfkThreshold) {
        store.afkThreshold = threshold
        refresh()
    }

    /** Turn the lock off entirely and wipe the PIN. Used by disable + forgot-PIN. */
    fun disableLock() {
        store.clearAll()
        _locked.value = false
        _attempts.value = AttemptState()
        refresh()
        Log.i(TAG, "App lock disabled + PIN wiped.")
    }

    private fun refresh() {
        _config.value = readConfig()
    }

    private fun readConfig() = AppLockConfig(
        enabled = store.enabled,
        hasPin = store.hasPin,
        biometricEnabled = store.biometricEnabled,
        afkThreshold = store.afkThreshold,
        pinLength = store.pinLength,
        pinSource = store.pinSource,
    )

    private companion object {
        const val TAG = "BtAppLock"
    }
}
