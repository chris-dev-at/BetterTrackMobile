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

/** A BetterTrack-account PIN is exactly 4 digits (matches the web login PIN). */
const val BETTERTRACK_PIN_LENGTH = 4

/**
 * Where the lock PIN comes from — the seam that lets "use my BetterTrack PIN"
 * grow real server verification later without touching storage or the lock screen.
 *
 *  - [DEVICE]      — a fresh 4–6 digit PIN the user invents just for this app.
 *  - [BETTERTRACK] — the user's existing 4-digit BetterTrack account PIN, entered
 *    once and stored LOCALLY (Keystore-hashed) exactly like a device PIN. The app
 *    never sees the account PIN during OAuth and there is no verify-PIN endpoint
 *    yet (requested in PLATFORM_ASKS.md), so today this is NOT server-verified;
 *    the enum only records intent. When `POST /auth/verify-pin` ships, the
 *    BETTERTRACK path gains verification + change-sync as a drop-in (see the
 *    `// TODO(platform verify-pin)` in [AppLockController.setupPin]).
 */
enum class PinSource {
    DEVICE,
    BETTERTRACK;

    companion object {
        val Default = DEVICE

        /** Tolerant parse from a stored name (unknown/null ⇒ default). */
        fun fromStorage(name: String?): PinSource =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

/**
 * The fixed PIN length a source mandates, or null when the length is the user's
 * choice. A BetterTrack PIN must match the 4-digit account PIN; a device PIN is
 * free to be 4–6 digits (so the setup flow shows a Continue button instead of
 * auto-submitting).
 */
fun fixedPinLengthFor(source: PinSource): Int? =
    if (source == PinSource.BETTERTRACK) BETTERTRACK_PIN_LENGTH else null

/** True when [pin] is a well-formed candidate PIN (4–6 ASCII digits). */
fun isValidPinFormat(pin: String): Boolean =
    pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && pin.all { it in '0'..'9' }

/**
 * True when [pin] is acceptable for the given [source]: a BetterTrack PIN must be
 * exactly 4 digits, a device PIN anything in the 4–6 range. Digits-only either way.
 */
fun isValidPinFor(pin: String, source: PinSource): Boolean =
    when (val fixed = fixedPinLengthFor(source)) {
        null -> isValidPinFormat(pin)
        else -> pin.length == fixed && pin.all { it in '0'..'9' }
    }

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
    val pinSource: PinSource = PinSource.Default,
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
