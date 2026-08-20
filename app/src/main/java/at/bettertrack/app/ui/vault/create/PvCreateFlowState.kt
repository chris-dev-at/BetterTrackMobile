package at.bettertrack.app.ui.vault.create

import at.bettertrack.app.vault.pv.custody.PV_MNEMONIC_WORDS
import at.bettertrack.app.vault.pv.custody.PvCustodyMode
import at.bettertrack.app.vault.v2.normalizeVaultPassphrase
import java.security.SecureRandom

/**
 * **The §21 creation ceremony as pure logic** — the step order, the interlocks,
 * and the one-word write-down check, all free of Compose and Android so the
 * rules that matter are unit tests rather than a screenshot review.
 *
 * The shape follows the app's established wizard idiom
 * ([at.bettertrack.app.ui.storage.WizardState] and friends): a step enum, an
 * immutable state, a fixed path, and `canAdvance` / `advance` / `previous` as
 * plain functions. Same reason as there — an interlock is a promise the app
 * keeps on the user's behalf, and a promise in a composable is a promise nobody
 * can check.
 *
 * ## The ceremony, and why it is exactly this long
 *
 * ```
 * NAME → MEDIA → WORDS → VERIFY → ACKNOWLEDGE → CUSTODY → DONE
 * ```
 *
 * §21 Q2 is binding and it is a *ruling against friction*: the owner's words
 * were **"validate only one word. no 20 years waiting and lots of friction"**.
 * So issuance shows the 12 words, asks for exactly ONE randomly chosen word,
 * and takes ONE compact acknowledgment that a lost phrase means lost data
 * (§16). No multi-word drills, no timed "have you really written them down"
 * gates, no second confirmation of the same fact.
 *
 * ## What the state deliberately does NOT hold
 *
 * The words. A phrase minted by `vault/pv/keys` lives in the composition for
 * the length of the ceremony and nowhere else — not in this state object, which
 * is the thing a future author would be tempted to hoist into a `SavedStateHandle`
 * or log while debugging a step transition. [pvVerifyWord] therefore takes the
 * expected word as an argument instead of reading it from the state.
 */

/** The ceremony's steps, in order. */
enum class PvCreateStep {
    /** Name the vault. Cleartext by design (§21 Q4) — the step says so calmly. */
    NAME,

    /** Server / Drive / both. Drive is E5-gated and shows as a designed disabled state. */
    MEDIA,

    /** The 12 words, in a numbered grid, on a secure screen. */
    WORDS,

    /** §21 Q2's one-word write-down check. */
    VERIFY,

    /** §16's one compact "lost phrase = lost vault" acknowledgment. */
    ACKNOWLEDGE,

    /** §12's custody choice, reusing the standalone sheet. */
    CUSTODY,

    /** Summary + the honest "the server cannot create this yet" end state. */
    DONE,
}

/**
 * Where a vault's encrypted bytes are configured to live (§1, §3).
 *
 * `LOCAL` is deliberately absent: §22 reserves phone-only storage without
 * building it ("leave that out for now this will come in future versions"), and
 * an enum value with no flow behind it is how a reserved feature turns into a
 * silent promise.
 */
enum class PvVaultMedium { SERVER, DRIVE, BOTH }

/**
 * Whether a medium can actually be chosen on this build.
 *
 * Drive needs its own separately-authenticated connection (§8, epic E5) and
 * that connection does not exist yet. §21 Q4's tone rule applies to the refusal
 * as much as to the explainer: the option is *shown*, disabled, with one honest
 * line — never hidden (the user would wonder), never tappable-then-broken (the
 * app would be lying), never dressed in an alarm banner.
 */
fun pvMediumAvailable(medium: PvVaultMedium): Boolean = when (medium) {
    PvVaultMedium.SERVER -> true
    PvVaultMedium.DRIVE, PvVaultMedium.BOTH -> PV_DRIVE_CONNECTABLE
}

/**
 * Whether a Drive connection can be established.
 *
 * `val`, not `const val`, for the reason
 * [at.bettertrack.app.vault.pv.ParanoidVaultsFlags] gives: a compile-time
 * constant makes every branch behind it dead code the compiler warns about, and
 * the warnings push the next author to delete the branch rather than wire it up
 * when E5 lands.
 */
val PV_DRIVE_CONNECTABLE: Boolean = false

/** §13 caps the QR's optional name hint at 64 characters; the name matches it. */
const val PV_VAULT_NAME_MAX: Int = 64

/**
 * What the ceremony knows so far.
 *
 * [verifyIndex] is chosen once, when the phrase is minted, and does not move: a
 * wrong answer returns to the words and asks for the SAME word again. Re-rolling
 * on every attempt would look more rigorous and be less honest — the check is a
 * *write-down* check, not an authentication, and the friendly loop is precisely
 * what §21 Q2 asked for.
 */
data class PvCreateState(
    val step: PvCreateStep = PvCreateStep.NAME,
    val name: String = "",
    val medium: PvVaultMedium? = null,
    /** 0-based position of the word the verify step asks for. */
    val verifyIndex: Int = 0,
    val verifyInput: String = "",
    /** The previous attempt was wrong. Shown on the words step as a calm line. */
    val verifyMissed: Boolean = false,
    val verifyPassed: Boolean = false,
    val acknowledged: Boolean = false,
    /** The §12 choice, recorded once the custody sheet saves. */
    val custody: PvCustodyMode? = null,
) {
    /** The name as it would be stored — trimmed, capped, never blank-padded. */
    val trimmedName: String get() = name.trim().take(PV_VAULT_NAME_MAX)

    /** The verify step's prompt is 1-based, because the word grid is. */
    val verifyPosition: Int get() = verifyIndex + 1
}

/** The whole topology, in one place. Unlike the storage wizard it does not branch. */
fun pvCreatePath(): List<PvCreateStep> = PvCreateStep.entries.toList()

/**
 * Can the user leave [PvCreateState.step]?
 *
 * Every `false` here is an interlock. The one that matters most is
 * [PvCreateStep.ACKNOWLEDGE]: §16 is the design's hardest edge — no escrow, no
 * reset, no support path — and the acknowledgment is the single place the user
 * is told so. It is not skippable, and the state machine is where that is true
 * rather than in whichever composable happens to render the button.
 */
fun pvCanAdvance(state: PvCreateState): Boolean = when (state.step) {
    PvCreateStep.NAME -> state.trimmedName.isNotEmpty()
    PvCreateStep.MEDIA -> state.medium?.let { pvMediumAvailable(it) } == true
    // Reading the words is not a task to complete — the user either wrote them
    // down or did not, and the next step is what finds out. Gating this on a
    // tick would add the friction §21 Q2 ruled against, twice over.
    PvCreateStep.WORDS -> true
    PvCreateStep.VERIFY -> state.verifyPassed
    PvCreateStep.ACKNOWLEDGE -> state.acknowledged
    PvCreateStep.CUSTODY -> state.custody != null
    PvCreateStep.DONE -> false
}

/** The next step, or the same state when an interlock refuses. */
fun pvAdvance(state: PvCreateState): PvCreateState {
    if (!pvCanAdvance(state)) return state
    val path = pvCreatePath()
    val index = path.indexOf(state.step)
    if (index < 0 || index == path.lastIndex) return state
    return state.copy(step = path[index + 1])
}

/**
 * The previous step, or `null` when "back" means closing the ceremony.
 *
 * Going back out of [PvCreateStep.DONE] is refused: by then the user has made
 * their custody choice and the summary is the end of the flow. Everything
 * before it is freely reversible — including the words, which is the whole
 * point of the verify loop.
 */
fun pvPrevious(state: PvCreateState): PvCreateState? {
    if (state.step == PvCreateStep.NAME) return null
    if (state.step == PvCreateStep.DONE) return state
    val path = pvCreatePath()
    val index = path.indexOf(state.step)
    if (index <= 0) return null
    return state.copy(step = path[index - 1], verifyInput = "")
}

// ── §21 Q2: the one-word check ──────────────────────────────────────────────

/**
 * Picks which of the 12 words the ceremony will ask for.
 *
 * A `fun interface` so a test can pin the index. Production passes
 * [PvSecureVerifyIndexPicker]; nothing else may, because a predictable index
 * (always the last word, say) would make the check a formality the user learns
 * to skip by looking at one line.
 */
fun interface PvVerifyIndexPicker {
    /** @return a 0-based index in `0 until wordCount`. */
    fun pick(wordCount: Int): Int
}

/** The production picker: a CSPRNG draw, uniform over the 12 positions. */
val PvSecureVerifyIndexPicker: PvVerifyIndexPicker = PvVerifyIndexPicker { wordCount ->
    require(wordCount > 0) { "a phrase with no words has no word to verify" }
    SecureRandom().nextInt(wordCount)
}

/** A fresh ceremony state with the verify position drawn for a [PV_MNEMONIC_WORDS]-word phrase. */
fun pvNewCreateState(picker: PvVerifyIndexPicker = PvSecureVerifyIndexPicker): PvCreateState =
    PvCreateState(verifyIndex = picker.pick(PV_MNEMONIC_WORDS))

/**
 * Does [typed] match the word the ceremony asked for?
 *
 * Case- and whitespace-forgiving, through the SAME normalisation the §13 QR and
 * the manual-entry path use (`vault/v2`'s `normalizeVaultPassphrase`: NFKD,
 * lower-case, trimmed, single-spaced). Anything else would mean a word typed
 * into this field and the same word typed into the recovery field being
 * different strings, which is exactly the class of inconsistency that makes a
 * user distrust the whole flow.
 *
 * A stray second word does NOT pass: the normaliser keeps both, and one word is
 * what was asked for.
 */
fun pvVerifyWord(expected: String, typed: String): Boolean {
    val wanted = normalizeVaultPassphrase(expected)
    if (wanted.isEmpty()) return false
    return normalizeVaultPassphrase(typed) == wanted
}

/**
 * Submits the verify step.
 *
 * Right → straight on to the acknowledgment. Wrong → **back to the words**,
 * with a calm "that was not the one" flag and no counter, no delay, no lockout.
 * §21 Q2 again: the ceremony is a write-down check, and the correct response to
 * "I did not write them down properly" is to show them again.
 */
fun pvSubmitVerify(state: PvCreateState, expected: String): PvCreateState {
    if (state.step != PvCreateStep.VERIFY) return state
    return if (pvVerifyWord(expected, state.verifyInput)) {
        pvAdvance(state.copy(verifyPassed = true, verifyMissed = false, verifyInput = ""))
    } else {
        state.copy(
            step = PvCreateStep.WORDS,
            verifyPassed = false,
            verifyMissed = true,
            verifyInput = "",
        )
    }
}
