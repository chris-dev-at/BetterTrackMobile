package at.bettertrack.app.ui.storage

import at.bettertrack.app.data.storage.StorageMode

/**
 * The first-run storage wizard as a pure state machine (S3/S4 plan §4.2).
 *
 * ## Why the machine is separate from the screen
 *
 * This flow has a property almost no other screen in the app has: **it ends by
 * generating a key that, if lost, loses the user's data forever.** Every gate on
 * the way there — the passphrase confirm, the "I saved my recovery kit" tick, the
 * blocking unrecoverability acknowledgment — is a safety interlock, not a
 * decoration. Interlocks that live inside a `@Composable` are proven only by
 * running the app and tapping; interlocks that live in a pure function are proven
 * by a test that runs on every build. So the whole ladder lives here and
 * `StorageSetupWizard` renders it.
 *
 * ## The paths
 *
 * ```
 * CHOOSE ─┬─ SERVER ─► SERVER_LOGIN            (the existing LoginScreen, unchanged)
 *         ├─ DRIVE  ─► GOOGLE_CONNECT ─► PASSPHRASE ─► RECOVERY_KIT ─► ACKNOWLEDGE ─► FIRST_PORTFOLIO ─► WORKING ─► DONE
 *         └─ BOTH   ─► SERVER_LOGIN ─► GOOGLE_CONNECT ─► … (same tail)
 * ```
 *
 * `WORKING` is the verified round trip (write the envelope, read it back,
 * decrypt, compare `writeId`). The mode is persisted **only** on reaching `DONE`,
 * which is what makes a half-finished wizard a no-op rather than a broken install.
 */
enum class WizardChoice { SERVER, DRIVE, BOTH }

enum class WizardStep {
    /** Three cards, each naming what it does NOT give you (plan §4.5). */
    CHOOSE,

    /** The existing `LoginScreen`, rendered unchanged inside the wizard. */
    SERVER_LOGIN,

    /** Connect Google for `drive.appdata`. Continuable device-local when absent. */
    GOOGLE_CONNECT,

    /** Create + confirm the vault passphrase. */
    PASSPHRASE,

    /** Generate + save the recovery kit; mandatory "stored safely" tick. */
    RECOVERY_KIT,

    /** The blocking "lost passphrase + lost kit = unrecoverable" acknowledgment. */
    ACKNOWLEDGE,

    /** Name the first portfolio. */
    FIRST_PORTFOLIO,

    /** Creating the vault + verifying the round trip. No user input. */
    WORKING,

    /** Verified. The mode has been persisted. */
    DONE,
}

/**
 * What the wizard knows so far.
 *
 * The passphrase lives here in memory for exactly as long as the flow runs and is
 * never persisted by this class — [at.bettertrack.app.vault.VaultKeyCustody] takes
 * it, derives the KEK and keeps only the *wrapped* key.
 */
data class WizardState(
    val choice: WizardChoice? = null,
    val step: WizardStep = WizardStep.CHOOSE,
    val passphrase: String = "",
    val confirm: String = "",
    /** True once a `drive.appdata` token has actually been obtained. */
    val googleConnected: Boolean = false,
    /** The user explicitly chose to continue without Google (device-local vault). */
    val continueWithoutGoogle: Boolean = false,
    /** The recovery kit has been produced AND the user ticked "stored safely". */
    val kitSaved: Boolean = false,
    /** The blocking unrecoverability tick. */
    val acknowledged: Boolean = false,
    val portfolioName: String = "",
    /** Set when the verified round trip failed; the flow stays on WORKING's error. */
    val failure: WizardFailure? = null,
) {
    val isDriveBranch: Boolean get() = choice == WizardChoice.DRIVE || choice == WizardChoice.BOTH
}

/** Why provisioning stopped. Each renders as a designed state, never a toast. */
enum class WizardFailure {
    /** The envelope came back different (or not at all) — the vault is NOT trusted. */
    ROUND_TRIP_FAILED,

    /** Key generation or encryption failed on this device. */
    CRYPTO_FAILED,

    /** The first portfolio could not be written to the vault graph. */
    VAULT_WRITE_FAILED,
}

/** Strength buckets for the local passphrase check (plan §2.7). */
enum class PassphraseStrength { TOO_SHORT, WEAK, FAIR, STRONG }

/**
 * The shortest passphrase this app will wrap a vault key under.
 *
 * Ten is not a compliance number; it is the point below which Argon2id's cost —
 * the only thing standing between an attacker with the ciphertext and the money —
 * stops mattering because the search space is small enough to enumerate anyway.
 */
const val MIN_PASSPHRASE_LENGTH: Int = 10

/**
 * A deterministic, offline strength estimate.
 *
 * Deliberately simple and explainable rather than an entropy model: the user is
 * being asked to invent a secret they must remember for years, and a meter that
 * rewards length and variety in a way they can predict produces better
 * passphrases than one that scores mysteriously. No dictionary, no network, no
 * telemetry — the passphrase never leaves this function.
 */
fun passphraseStrength(value: String): PassphraseStrength {
    if (value.length < MIN_PASSPHRASE_LENGTH) return PassphraseStrength.TOO_SHORT

    val classes = listOf(
        value.any { it.isLowerCase() },
        value.any { it.isUpperCase() },
        value.any { it.isDigit() },
        value.any { !it.isLetterOrDigit() },
    ).count { it }

    val distinct = value.toSet().size
    // "aaaaaaaaaaaa" and "abababababab" are long and worthless. Variety of
    // characters is a better signal here than raw length.
    if (distinct <= 3) return PassphraseStrength.WEAK

    var score = classes
    if (value.length >= 16) score++
    if (value.length >= 24) score++
    // A passphrase of several words beats a mangled single word, and users
    // actually remember it — so spaces are rewarded rather than stripped.
    if (value.trim().contains(' ')) score++

    return when {
        score >= 5 -> PassphraseStrength.STRONG
        score >= 3 -> PassphraseStrength.FAIR
        else -> PassphraseStrength.WEAK
    }
}

/**
 * Whether the passphrase pair may be accepted.
 *
 * WEAK is allowed but warned about; TOO_SHORT is refused. Blocking WEAK outright
 * would be the app deciding it knows the user's threat model better than they do,
 * on a secret only they can ever hold — a warning is honest, a wall is not.
 */
fun passphrasePairAccepted(passphrase: String, confirm: String): Boolean =
    passphraseStrength(passphrase) != PassphraseStrength.TOO_SHORT && passphrase == confirm

/** The ordered steps for a choice — the machine's whole topology in one place. */
fun wizardPath(choice: WizardChoice): List<WizardStep> = when (choice) {
    WizardChoice.SERVER -> listOf(WizardStep.CHOOSE, WizardStep.SERVER_LOGIN)
    WizardChoice.DRIVE -> listOf(
        WizardStep.CHOOSE,
        WizardStep.GOOGLE_CONNECT,
        WizardStep.PASSPHRASE,
        WizardStep.RECOVERY_KIT,
        WizardStep.ACKNOWLEDGE,
        WizardStep.FIRST_PORTFOLIO,
        WizardStep.WORKING,
        WizardStep.DONE,
    )
    WizardChoice.BOTH -> listOf(
        WizardStep.CHOOSE,
        WizardStep.SERVER_LOGIN,
        WizardStep.GOOGLE_CONNECT,
        WizardStep.PASSPHRASE,
        WizardStep.RECOVERY_KIT,
        WizardStep.ACKNOWLEDGE,
        WizardStep.FIRST_PORTFOLIO,
        WizardStep.WORKING,
        WizardStep.DONE,
    )
}

/**
 * Can the user leave [WizardState.step]?
 *
 * This is the interlock list. Every `false` here is a promise the app is keeping
 * on the user's behalf.
 */
fun canAdvance(state: WizardState): Boolean = when (state.step) {
    WizardStep.CHOOSE -> state.choice != null
    // Login is not "advanced" by a button — the auth state advances it.
    WizardStep.SERVER_LOGIN -> false
    // Drive-only may continue device-local — a local vault is a complete,
    // working vault (LocalDataHome) that syncs to Drive whenever a connection
    // appears, so "keep it on this device" is a true description of the result.
    //
    // BOTH may NOT. "Both" is a claim about two media, and without a Google
    // connection the second one does not exist. Letting it through would record a
    // mode whose entire promise — "an encrypted backup also goes to your Drive" —
    // is false the moment it is written, which is the one thing this flow is not
    // allowed to do. The step offers the two honest exits instead.
    WizardStep.GOOGLE_CONNECT -> when (state.choice) {
        WizardChoice.BOTH -> state.googleConnected
        else -> state.googleConnected || state.continueWithoutGoogle
    }
    WizardStep.PASSPHRASE -> passphrasePairAccepted(state.passphrase, state.confirm)
    WizardStep.RECOVERY_KIT -> state.kitSaved
    WizardStep.ACKNOWLEDGE -> state.acknowledged
    WizardStep.FIRST_PORTFOLIO -> state.portfolioName.isNotBlank()
    WizardStep.WORKING -> false
    WizardStep.DONE -> false
}

/** The next step, or the same state when an interlock refuses. */
fun advance(state: WizardState): WizardState {
    if (!canAdvance(state)) return state
    val choice = state.choice ?: return state
    val path = wizardPath(choice)
    val index = path.indexOf(state.step)
    if (index < 0 || index == path.lastIndex) return state
    return state.copy(step = path[index + 1], failure = null)
}

/**
 * The previous step, or `null` when the wizard should close/return to the choice.
 *
 * Going back out of `WORKING` or `DONE` is refused: by then a vault key exists on
 * the device and the round trip is in flight or complete. "Back" there would mean
 * abandoning key material mid-creation, which is exactly how a half-written vault
 * happens.
 */
fun previousStep(state: WizardState): WizardState? = when (state.step) {
    WizardStep.CHOOSE -> null
    WizardStep.WORKING, WizardStep.DONE -> state
    else -> {
        val choice = state.choice
        val path = choice?.let { wizardPath(it) }
        val index = path?.indexOf(state.step) ?: -1
        when {
            path == null || index <= 0 -> state.copy(step = WizardStep.CHOOSE, choice = null)
            path[index - 1] == WizardStep.CHOOSE -> state.copy(step = WizardStep.CHOOSE, choice = null)
            else -> state.copy(step = path[index - 1], failure = null)
        }
    }
}

/**
 * The step a login success should land on.
 *
 * SERVER is finished the moment there is a session — the wizard's whole job was
 * to record that choice. BOTH has only just begun.
 */
fun afterServerLogin(state: WizardState): WizardState = when (state.choice) {
    WizardChoice.SERVER -> state.copy(step = WizardStep.DONE)
    WizardChoice.BOTH -> state.copy(step = WizardStep.GOOGLE_CONNECT)
    else -> state
}

/** The mode a completed wizard persists. */
fun modeFor(choice: WizardChoice): StorageMode = when (choice) {
    WizardChoice.SERVER -> StorageMode.SERVER
    WizardChoice.DRIVE -> StorageMode.DRIVE
    WizardChoice.BOTH -> StorageMode.BOTH
}
