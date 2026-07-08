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
 *  - [BETTERTRACK] — the user's existing 4-digit BetterTrack account (web) PIN.
 *    When the [AppLockFeatures.betterTrackPinLock] switch is on, it is entered
 *    once, **verified live** against `POST /auth/pin/verify` (see
 *    [AccountPinService]), and only on a match stored LOCALLY (Keystore-hashed)
 *    exactly like a device PIN — the PIN itself is never persisted. The switch is
 *    OFF today because the mobile OAuth bearer is 403-forbidden on that endpoint
 *    (see [AppLockFeatures]); until it flips, the app only ever creates DEVICE
 *    PINs and this value merely records intent for an already-migrated interim.
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

// ── BetterTrack-account PIN verification (server round-trip) ──────────────────

/**
 * Outcome of POSTing the entered PIN to `/auth/pin/verify` when the user chose to
 * reuse their existing BetterTrack account (web) PIN. This is a PURE mapping from
 * the HTTP status so it is unit-testable without a network;
 * [at.bettertrack.app.data.applock.AccountPinService] attaches the real call.
 *
 *  - [Correct]   200 — the PIN matches; activate the lock (store only a local hash).
 *  - [WrongPin]  401 — the account exists but this isn't its PIN; reject inline.
 *  - [NoPinSet]  400 — the account has no web PIN; explain + offer a device PIN.
 *  - [Forbidden] 403 — the OAuth bearer can't reach the endpoint (session-only).
 *  - [Offline]   transport failure — needs a connection to verify.
 *  - [Error]     any other status — a generic "try again".
 */
enum class BtPinVerifyOutcome { Correct, WrongPin, NoPinSet, Forbidden, Offline, Error }

/**
 * Map a `/auth/pin/verify` HTTP status to a [BtPinVerifyOutcome]. httpStatus `0`
 * is the app's transport-failure sentinel (see `apiCall`). The real feature only
 * ever sends a well-formed 4-digit PIN, so a 400 here means "no PIN on the
 * account" rather than a malformed body.
 */
fun pinVerifyOutcomeFor(httpStatus: Int): BtPinVerifyOutcome = when (httpStatus) {
    200 -> BtPinVerifyOutcome.Correct
    400 -> BtPinVerifyOutcome.NoPinSet
    401 -> BtPinVerifyOutcome.WrongPin
    403 -> BtPinVerifyOutcome.Forbidden
    0 -> BtPinVerifyOutcome.Offline
    else -> BtPinVerifyOutcome.Error
}

/**
 * Whether the OAuth bearer can read `/auth/me` (which carries `pinEnabled`). Lets
 * the setup flow decide whether it can gate the "use my BetterTrack PIN" option
 * on the account actually having a web PIN, or must fall back to letting the
 * verify call be the gate. Pure status → category mapping (unit-tested).
 */
enum class MeAccess { Ok, Forbidden, Offline, Error }

fun meAccessFor(httpStatus: Int): MeAccess = when (httpStatus) {
    200 -> MeAccess.Ok
    403 -> MeAccess.Forbidden
    0 -> MeAccess.Offline
    else -> MeAccess.Error
}

/** Build-time feature switches for the app lock. */
object AppLockFeatures {
    /**
     * Whether the "use my BetterTrack account PIN" unlock option is OFFERED.
     *
     * `false` today. On-device probing (2026-07-08, production api.bettertrack.at)
     * shows the mobile OAuth bearer receives **403 API_KEY_FORBIDDEN** on BOTH
     * `GET /auth/me` and `POST /auth/pin/verify` — the whole `/auth/` group is
     * session-cookie-only for the bearer. Reusing the web PIN needs a live
     * `/auth/pin/verify` round-trip, so the option is HIDDEN (device-PIN-only)
     * until the platform lets the BetterTrackMobile OAuth bearer reach
     * `/auth/pin/verify` (and ideally `/auth/me`, so the option can be gated on
     * `pinEnabled`). The full verify flow + the [AccountPinService] network seam
     * are already built behind this switch — flip to `true` once the API change
     * ships, then RE-VERIFY on-device. See docs/TODO.md (Step 17c).
     */
    val betterTrackPinLock: Boolean = false
}

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
