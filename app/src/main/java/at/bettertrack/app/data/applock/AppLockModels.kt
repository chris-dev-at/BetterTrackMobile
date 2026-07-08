package at.bettertrack.app.data.applock

/**
 * App-lock domain model + the pure rules behind it (spec §5). Everything here is
 * side-effect-free and unit-tested; the [AppLockController] wires it to storage,
 * crypto and lifecycle. Keeping the policy pure means the backoff schedule and
 * PIN-format rules are provable without an emulator.
 */

/** Lock trigger threshold on returning from background (§5). Default = 1 min. */
enum class AfkThreshold(val minutes: Int) {
    Immediately(0),
    OneMinute(1),
    FiveMinutes(5),
    FifteenMinutes(15);

    /** Elapsed-real-time budget before a background→foreground return re-locks. */
    val millis: Long get() = minutes * 60_000L

    companion object {
        val Default = OneMinute

        /** Tolerant parse from a stored minute count (unknown ⇒ default). */
        fun fromMinutes(m: Int): AfkThreshold = entries.firstOrNull { it.minutes == m } ?: Default
    }
}

/** PINs are 4–6 digits (§5) — this is the single source of truth for that rule. */
const val PIN_MIN_LENGTH = 4
const val PIN_MAX_LENGTH = 6

/** True when [pin] is a well-formed candidate PIN (4–6 ASCII digits). */
fun isValidPinFormat(pin: String): Boolean =
    pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

/**
 * Progressive backoff (§5): the first four misses are free, then each wrong
 * entry from the fifth imposes an escalating lockout — 30s, 1m, 2m, 5m, capped
 * at 5m. [consecutiveFailures] is the running count INCLUDING the miss that just
 * happened, so the fifth miss (count = 5) returns the first 30s lockout.
 */
fun lockoutMillisFor(consecutiveFailures: Int): Long = when {
    consecutiveFailures < 5 -> 0L
    consecutiveFailures == 5 -> 30_000L
    consecutiveFailures == 6 -> 60_000L
    consecutiveFailures == 7 -> 120_000L
    else -> 300_000L
}

/** Config snapshot the Security UI renders + edits (§6.12 hosts this in Step 18). */
data class AppLockConfig(
    val enabled: Boolean = false,
    val hasPin: Boolean = false,
    val biometricEnabled: Boolean = false,
    val afkThreshold: AfkThreshold = AfkThreshold.Default,
    val pinLength: Int = 0,
)

/** Live attempt/lockout state, surfaced so the lock screen can count down. */
data class AttemptState(
    val consecutiveFailures: Int = 0,
    /** Elapsed-real-time (SystemClock.elapsedRealtime) when the lockout ends, or 0. */
    val lockoutUntilElapsed: Long = 0L,
)

/** Outcome of a PIN verification attempt — drives the lock screen's reaction. */
sealed interface PinVerifyResult {
    data object Success : PinVerifyResult
    /** Wrong PIN. [lockoutMillis] > 0 means a backoff window just began. */
    data class Wrong(val consecutiveFailures: Int, val lockoutMillis: Long) : PinVerifyResult
    /** Rejected without checking because a backoff window is still open. */
    data class LockedOut(val remainingMillis: Long) : PinVerifyResult
}
