package at.bettertrack.app.data.storage

/**
 * Adding and removing a storage medium (S3/S4 plan §1.4), as a pure state machine.
 *
 * ## Why only four transitions exist
 *
 * A mode is a **set of media**, and the plan's table is the exhaustive list of
 * legal set changes: you may add the medium you do not have, or drop one of the
 * two you do. What is deliberately missing is as important as what is here:
 *
 *  - **SERVER → DRIVE** and **DRIVE → SERVER** are not offered.** Each would
 *    remove the only medium the user has *and* add a different one in the same
 *    gesture — i.e. abandon everything currently stored. The safe path is through
 *    BOTH, which is exactly why BOTH exists: add the second medium, verify the
 *    data is really in it, and only then drop the first. Plan §5 rule 3 —
 *    "removing the last medium is never offered" — falls out of this by
 *    construction rather than by a check someone has to remember.
 *  - **UNSET → anything** is not a transition at all. UNSET is answered by the
 *    first-run wizard, which *creates* the initial medium set; there is no data
 *    to move.
 *
 * ## Prerequisites are reported, never assumed
 *
 * Every transition needs something the app may not have: a Google connection, a
 * BetterTrack session, an unlocked vault. [evaluateTransition] answers with a
 * [TransitionBlocker] rather than a boolean so the UI can render *which* thing is
 * missing and what the user can do about it. Nothing here ever reports success it
 * has not achieved — a transition whose prerequisite is absent stays blocked and
 * the mode is not written.
 */
enum class StorageTransition(val from: StorageMode, val to: StorageMode) {

    /**
     * Add an encrypted Drive mirror to a server account.
     *
     * Sequence (plan §1.4 row 1, `docs/paranoid-design.md:266` §5): connect Drive,
     * create a passphrase, project the current Room read models into vault
     * entities, write the envelope, **read it back and compare `writeId`**, and
     * only then record the mode.
     */
    SERVER_TO_BOTH(StorageMode.SERVER, StorageMode.BOTH),

    /**
     * Attach a BetterTrack account to a Drive-only vault.
     *
     * The import is free: each vault transaction/cash movement replays as an
     * ordinary `SyncOp` through the existing `ApiOpExecutor`, which already
     * carries a per-op `Idempotency-Key` — so an interrupted import resumes
     * exactly-once.
     */
    DRIVE_TO_BOTH(StorageMode.DRIVE, StorageMode.BOTH),

    /**
     * Promote the mirror to the live vault and drop the server account.
     *
     * The server's data is untouched — this logs out, it does not delete.
     */
    BOTH_TO_DRIVE(StorageMode.BOTH, StorageMode.DRIVE),

    /**
     * Remove the Drive medium and keep the BetterTrack account.
     *
     * The appdata file is deleted **best effort** and the user is told if that
     * failed (plan §5 rule 2): it is their own ciphertext in their own Drive, so
     * silently reporting success would be a lie about their data.
     */
    BOTH_TO_SERVER(StorageMode.BOTH, StorageMode.SERVER),
    ;

    /** True when this transition adds a medium rather than dropping one. */
    val isAdditive: Boolean get() = to == StorageMode.BOTH
}

/** Why a transition cannot run right now. Each maps to a designed explainer state. */
enum class TransitionBlocker {
    /**
     * No Google connection, so there is no Drive to mirror into.
     *
     * With the shipped [at.bettertrack.app.vault.drive.SignedOutGoogleAuthProvider]
     * this is the permanent answer until the OAuth client exists (plan §6.8) —
     * which is why the UI for it is a real, honest state and not a toast.
     */
    NEEDS_GOOGLE,

    /** No BetterTrack session; a server medium cannot be added without one. */
    NEEDS_SERVER_ACCOUNT,

    /** The vault is locked, so its entities cannot be read or re-encrypted. */
    NEEDS_VAULT_UNLOCK,

    /**
     * Replaying a Drive vault into a server account is not built yet (plan §1.4
     * row 2 is specified; the replay itself is queued work). Stated plainly rather
     * than hidden, because a user who reaches this screen is asking a reasonable
     * question and deserves a real answer.
     */
    ATTACH_REPLAY_UNAVAILABLE,
}

/** The answer [evaluateTransition] gives. */
sealed interface TransitionOutcome {
    data class Allowed(val transition: StorageTransition) : TransitionOutcome
    data class Blocked(val transition: StorageTransition, val blocker: TransitionBlocker) : TransitionOutcome
}

/** What the app currently has available, as facts rather than hopes. */
data class TransitionCapabilities(
    /** A Google account is connected AND can mint a `drive.appdata` token. */
    val googleConnected: Boolean = false,
    /** A BetterTrack session exists (tokens present). */
    val serverSignedIn: Boolean = false,
    /** The vault key is in memory — required to read or re-encrypt entities. */
    val vaultUnlocked: Boolean = false,
    /** The vault→server replay path exists. False until that work ships. */
    val attachReplayAvailable: Boolean = false,
)

/**
 * Which transitions the §1.4 table declares legal from [mode].
 *
 * Blocked ones are still returned: the plan's §4.5 "absent, not greyed" rule is
 * about features a mode can *never* have, and these are features it can have as
 * soon as a prerequisite is met. So this is the *model* answer — what the state
 * machine permits — and it stays complete regardless of which of them the app can
 * currently run. What the screen may actually put in front of a user is
 * [offerableTransitions].
 */
fun availableTransitions(mode: StorageMode): List<StorageTransition> =
    StorageTransition.entries.filter { it.from == mode.effective }

/**
 * Which transitions "Where your data lives" may actually OFFER today.
 *
 * ## Why this is narrower than [availableTransitions]
 *
 * The two additive transitions ([StorageTransition.isAdditive], i.e.
 * `SERVER_TO_BOTH` and `DRIVE_TO_BOTH`) are legal in the model and
 * [StorageModeSwitcher.apply] answers [SwitchResult.NeedsFlow] for both: adding a
 * medium is connect → passphrase → recovery kit → acknowledgment → verified round
 * trip, and only a multi-screen flow can run that. `StorageSetupWizard` holds
 * exactly that flow, but its single call site is the first-run gate
 * (`ui/shell/BtRoot.kt`, `RootGate.WIZARD`), so an installed app has no way to
 * enter it — the row could only ever print "not available here".
 *
 * A row that exists solely to say no is worse than no row: it reads as a working
 * offer, costs a tap to disprove, and teaches the user that this screen's
 * controls are decorative. Until the flow has a second entry point, the honest
 * surface is silence.
 *
 * **This narrowing is temporary and belongs to the Vaults v2 P4 rebuild**, which
 * re-enters `StorageSetupWizard` in add-medium mode from this screen. When that
 * lands, drop the filter and the offer comes back with a real destination behind
 * it — nothing else in the transition machinery has to change, which is why the
 * restriction lives here as one filter rather than as deletions across the model.
 */
fun offerableTransitions(mode: StorageMode): List<StorageTransition> =
    availableTransitions(mode).filterNot { it.isAdditive }

/**
 * The prerequisite check. Pure, so every row of the §1.4 table is a unit test.
 *
 * Order matters where more than one thing is missing: the blocker reported is the
 * one the user must resolve *first*, so the screen never sends someone to connect
 * Google when they are not signed in to the thing Google is being attached to.
 */
fun evaluateTransition(
    transition: StorageTransition,
    capabilities: TransitionCapabilities,
): TransitionOutcome {
    fun blocked(blocker: TransitionBlocker) = TransitionOutcome.Blocked(transition, blocker)
    return when (transition) {
        StorageTransition.SERVER_TO_BOTH ->
            // The mirror is written from Room, so no vault unlock is needed: the
            // passphrase is CREATED as part of this flow.
            if (!capabilities.serverSignedIn) blocked(TransitionBlocker.NEEDS_SERVER_ACCOUNT)
            else if (!capabilities.googleConnected) blocked(TransitionBlocker.NEEDS_GOOGLE)
            else TransitionOutcome.Allowed(transition)

        StorageTransition.DRIVE_TO_BOTH ->
            if (!capabilities.serverSignedIn) blocked(TransitionBlocker.NEEDS_SERVER_ACCOUNT)
            else if (!capabilities.vaultUnlocked) blocked(TransitionBlocker.NEEDS_VAULT_UNLOCK)
            else if (!capabilities.attachReplayAvailable) blocked(TransitionBlocker.ATTACH_REPLAY_UNAVAILABLE)
            else TransitionOutcome.Allowed(transition)

        StorageTransition.BOTH_TO_DRIVE ->
            // Promotion: the mirror becomes the live vault, so it has to be
            // readable. Google is NOT required — a promoted vault that cannot
            // reach Drive yet is simply a local vault with a pending push, which
            // is a designed state the whole medium is built around.
            if (!capabilities.vaultUnlocked) blocked(TransitionBlocker.NEEDS_VAULT_UNLOCK)
            else TransitionOutcome.Allowed(transition)

        StorageTransition.BOTH_TO_SERVER ->
            // Dropping the Drive medium needs nothing: the server already holds
            // everything (BOTH is server-authoritative), and the remote delete is
            // best effort by design.
            TransitionOutcome.Allowed(transition)
    }
}

/**
 * The mode-aware meaning of "log out" (plan §4.4 rows 1–2).
 *
 * In SERVER this is the logout the app has always had. In BOTH it must not
 * destroy the vault, so the account is dropped and the install **demotes to
 * DRIVE** — the user still owns their data, it simply has one medium now. In
 * DRIVE there is no account to log out of at all, and offering the word would be
 * meaningless; the screen offers lock / disconnect / delete-everything instead.
 */
fun modeAfterLogout(mode: StorageMode): StorageMode = when (mode.effective) {
    StorageMode.BOTH -> StorageMode.DRIVE
    StorageMode.DRIVE -> StorageMode.DRIVE
    else -> mode
}

/** True when the word "log out" means anything in this mode. */
val StorageMode.hasServerAccount: Boolean
    get() = effective == StorageMode.SERVER || effective == StorageMode.BOTH
