package at.bettertrack.app.vault.pv.custody

/**
 * The vocabulary of §12 device custody: how a phrase is held on THIS endpoint,
 * what the user can do about it next, and how a wrong device password is
 * throttled.
 *
 * Everything in this file is pure — no Android, no storage, no crypto — so the
 * rules that matter (the state → affordance invariant and the lockout ladder)
 * are provable in a unit test rather than only observable on a phone.
 */

/**
 * How one stored phrase is held. The wire values are `'wrapped'` / `'plain'`
 * verbatim from §12's entry shape, because the same keystore entry is described
 * for the web endpoint and a divergent spelling would be a silent
 * incompatibility the day the two are compared.
 */
enum class PvCustodyMode(val wire: String) {
    /** AES-256-GCM under the endpoint's device password (the default). */
    WRAPPED("wrapped"),

    /**
     * Stored without the device password.
     *
     * "Plain" is relative to the device password only — the payload still sits
     * in `EncryptedSharedPreferences` under a Keystore master key, exactly like
     * every other secret this app persists. §12: *"on platforms with native
     * custody (Android Keystore / iOS keychain) 'plain' still means 'not
     * protected by the device password' — the platform baseline applies
     * underneath."*
     */
    PLAIN("plain"),
    ;

    companion object {
        /** Unknown/absent wire value ⇒ null, never a silent default. */
        fun fromWire(value: String?): PvCustodyMode? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * What this endpoint holds for one vault.
 *
 * §12 is explicit that **a state without a next action is a design bug** — the
 * recorded v2 anti-pattern was a locked vault with no unlock path. The sealed
 * hierarchy is what makes that mechanical: [nextAction] is a `when` with no
 * `else`, so a fourth state cannot be added without the compiler demanding its
 * affordance, and `PvCustodyStateTest` asserts the mapping stays total.
 */
sealed interface PvCustodyState {

    val vaultId: String

    /**
     * The phrase is here, wrapped under the endpoint's device password.
     *
     * [sessionUnlocked] is not persisted state — it is whether `K_dev` is in
     * memory right now (§12: the password and `K_dev` live only in volatile
     * process memory). It rides in the state because the affordance differs:
     * the same stored entry offers "Unlock" before the session and opens
     * silently after it.
     */
    data class Wrapped(override val vaultId: String, val sessionUnlocked: Boolean) : PvCustodyState

    /** The phrase is here without the device password. Opens silently, always. */
    data class Plain(override val vaultId: String) : PvCustodyState

    /** This endpoint has never seen the phrase (or the keystore was reset). */
    data class Absent(override val vaultId: String) : PvCustodyState
}

/**
 * The one thing the user can do next about a vault. Deliberately three values
 * and no "nothing" — see [PvCustodyState].
 */
sealed interface PvCustodyAction {

    /** Ask for the endpoint device password (the unlock sheet). */
    data object Unlock : PvCustodyAction

    /** No prompt: the phrase is readable right now. */
    data object Open : PvCustodyAction

    /** §12's third affordance: "Enter words / Scan QR from another device". */
    data object Acquire : PvCustodyAction
}

/** The binding state → affordance mapping (§12). Total by construction. */
fun PvCustodyState.nextAction(): PvCustodyAction = when (this) {
    is PvCustodyState.Wrapped -> if (sessionUnlocked) PvCustodyAction.Open else PvCustodyAction.Unlock
    is PvCustodyState.Plain -> PvCustodyAction.Open
    is PvCustodyState.Absent -> PvCustodyAction.Acquire
}

/** The outcome of one device-password attempt. */
sealed interface PvUnlockResult {

    /** `K_dev` is in memory; every wrapped phrase on this endpoint is readable. */
    data object Success : PvUnlockResult

    /**
     * The wrap-check refused the derived key.
     *
     * Verification is **local** (§12: "there is no server lockout because the
     * server is not involved"), so a wrong password is detected without any
     * vault byte being touched, let alone fetched.
     */
    data class Wrong(val failureCount: Int, val lockoutMillis: Long) : PvUnlockResult

    /** An attempt arrived inside an open backoff window; nothing was derived. */
    data class LockedOut(val remainingMillis: Long) : PvUnlockResult

    /**
     * There is no endpoint password yet — no salt, no wrap check.
     *
     * A distinct outcome rather than a `Wrong`, because the affordance is
     * different: the user is not failing to remember a password, they have not
     * chosen one, and the UI must offer the custody-choice step instead of a
     * retry.
     */
    data object NoKeystore : PvUnlockResult
}

// ── The lockout ladder ──────────────────────────────────────────────────────

/** Attempts allowed before the ladder starts (§12: "5 wrong → 30 s"). */
const val PV_LOCKOUT_FREE_ATTEMPTS: Int = 5

/** The first rung, in milliseconds. */
const val PV_LOCKOUT_BASE_MS: Long = 30_000L

/** The ceiling (§12: "doubling, capped at 5 min"). */
const val PV_LOCKOUT_CAP_MS: Long = 300_000L

/**
 * How long the next attempt must wait after [consecutiveFailures] wrong device
 * passwords.
 *
 * ```
 * 1‥4 → 0        5 → 30 s      6 → 60 s
 * 7   → 120 s    8 → 240 s     9+ → 300 s (cap)
 * ```
 *
 * The doubling is computed rather than tabulated on purpose: `AppLockModels`'
 * `lockoutMillisFor` is a hand-written table that jumps straight from 120 s to
 * the cap, and copying a table is how a "doubling" schedule quietly stops
 * doubling. The shift is clamped before it runs so an absurd failure count
 * cannot overflow the `Long` and wrap to a negative delay.
 */
fun pvLockoutMillisFor(consecutiveFailures: Int): Long {
    if (consecutiveFailures < PV_LOCKOUT_FREE_ATTEMPTS) return 0L
    val rung = (consecutiveFailures - PV_LOCKOUT_FREE_ATTEMPTS).coerceIn(0, MAX_LADDER_SHIFT)
    return (PV_LOCKOUT_BASE_MS shl rung).coerceAtMost(PV_LOCKOUT_CAP_MS)
}

/** 30 s ≪ 20 is ~10 hours — far past the cap, and nowhere near `Long` overflow. */
private const val MAX_LADDER_SHIFT: Int = 20

/** Elapsed-realtime source, injectable so the ladder is testable without waiting. */
fun interface PvElapsedClock {
    fun elapsedMillis(): Long
}
