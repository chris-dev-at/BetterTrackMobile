package at.bettertrack.app.ui.vault.qr

import androidx.annotation.StringRes
import at.bettertrack.app.R
import at.bettertrack.app.vault.pv.VaultHeaderProbe
import at.bettertrack.app.vault.pv.VaultQrPayload
import at.bettertrack.app.vault.pv.VaultQrRejection

/**
 * The receiver leg's state, kept free of Compose and Android so the decisions
 * that matter — what the user is told, and what is never told apart — are plain
 * unit tests rather than a screenshot review.
 */
sealed interface VaultQrScanState {

    /** Permission has not been asked yet, or was dismissed. Offers "Allow camera". */
    data object PermissionNeeded : VaultQrScanState

    /** Permission refused with "don't ask again". Offers app settings + manual entry. */
    data object PermissionBlocked : VaultQrScanState

    /** The device has no camera at all. Offers manual entry only. */
    data object NoCamera : VaultQrScanState

    /** Live preview, analyzer running. */
    data object Scanning : VaultQrScanState

    /** The camera itself failed to bind (in use by another app, hardware error). */
    data object CameraError : VaultQrScanState

    /** A code was read and refused. [reason] drives the message via [vaultQrRejectionMessage]. */
    data class Rejected(val reason: VaultQrRejection) : VaultQrScanState

    /**
     * A code passed all four offline checks. [verification] carries the
     * fetch-then-compare half, which is what decides whether anything may be
     * stored.
     */
    data class Accepted(
        val payload: VaultQrPayload,
        val verification: VaultQrVerification,
    ) : VaultQrScanState
}

/**
 * The §13 verified-open step. Nothing is ever persisted from any state but
 * [Verified] — and this build has no code path that produces [Verified], because
 * `NotAvailableVaultHeaderProbe` is the only probe that exists (see its KDoc).
 */
sealed interface VaultQrVerification {

    /** The header fetch is in flight. */
    data object Checking : VaultQrVerification

    /**
     * No medium could serve the vault's header document, so the words cannot be
     * proven against the vault. The designed dead end: it says so, and stores
     * nothing.
     */
    data object Unavailable : VaultQrVerification

    /**
     * The header decrypted with these words (and matched `f` when the code
     * carried one). The only state from which custody may store the phrase.
     */
    data object Verified : VaultQrVerification
}

/**
 * The four offline checks, rendered as a status list on the result state.
 *
 * They are modelled explicitly rather than implied by "we got a payload" because
 * the screen's job is to show the user *what was actually verified* next to
 * what was not — the fingerprint line right below this list is the honest
 * "cannot be checked offline" (see `VaultQrPayload`'s KDoc on the `f` defect).
 */
data class VaultQrChecks(
    val prefix: Boolean,
    val requiredKeys: Boolean,
    val phraseChecksum: Boolean,
    val vaultIdShape: Boolean,
) {
    companion object {
        /** All four passed — the only combination a [VaultQrScanState.Accepted] can carry. */
        val ALL_PASSED: VaultQrChecks = VaultQrChecks(
            prefix = true,
            requiredKeys = true,
            phraseChecksum = true,
            vaultIdShape = true,
        )
    }
}

/**
 * §13's **verified open**, as a pure suspend function so its one binding
 * property is a unit test and not a code review: *no input reaches
 * [VaultQrVerification.Verified] on this build.*
 *
 * The real chain is: fetch the vault's header envelope → derive `K_wrap` from
 * the words → unwrap `keySlots[0]` to recover `K_c` → compare `f` against
 * `fingerprint(K_c)` when the code carried one → decrypt the header body → only
 * then may custody persist the phrase.
 *
 * The *cryptographic* half of that chain now exists in this app — E3's
 * derivation chain (`vault.pv.keys`) and `decryptPvDoc` landed with the E1/E3
 * tick — so the missing piece is no longer the maths. It is the seam: no
 * [VaultHeaderProbe] is bound to a real medium (`NotAvailableVaultHeaderProbe`
 * is still the only implementation in the tree), and the header→unwrap→compare
 * steps are not wired into this function. Until both land, every input reaches
 * the same honest dead end.
 *
 * Both dead ends answer [VaultQrVerification.Unavailable] — the honest,
 * stores-nothing state — rather than defaulting to success. That default is the
 * failure mode §13 names outright: "a mis-scan can never store dead words".
 */
suspend fun verifyScannedPhrase(
    payload: VaultQrPayload,
    probe: VaultHeaderProbe,
): VaultQrVerification {
    val header = probe.fetch(payload.vaultId)
    return when {
        header == null || header.isEmpty() -> VaultQrVerification.Unavailable
        // Bytes came back — and bytes are not a proof. Returning Verified here
        // because "the server answered" would store unverified words, which is
        // exactly the bug this function exists to make impossible. The arm stays
        // written out so that wiring a real probe is a compile-guided edit here.
        else -> VaultQrVerification.Unavailable
    }
}

/**
 * Rejection → user-facing message.
 *
 * **This mapping is deliberately lossy.** §13's discipline is that a bystander
 * watching the screen must not learn whether a failed code was nearly right, so
 * every reason that touches the payload's *content* — a wrong BIP-39 checksum, a
 * truncated phrase, a corrupt percent escape, a missing key, a bad vault id, an
 * over-long name — resolves to ONE generic message. Only the three reasons that
 * say something about the code's *provenance* rather than its content get their
 * own text, because each has a different next action:
 *
 * - a newer format → update the app,
 * - the retired v2 code → make a new code on the sender,
 * - not our code at all → you scanned the wrong thing.
 *
 * `VaultQrScanStateTest` pins the collapse, so adding a helpful-looking specific
 * message for "checksum failed" fails the build instead of shipping.
 */
@StringRes
fun vaultQrRejectionMessage(reason: VaultQrRejection): Int = when (reason) {
    VaultQrRejection.NOT_A_VAULT_CODE -> R.string.bt_pv_qr_reject_foreign
    VaultQrRejection.UNSUPPORTED_VERSION -> R.string.bt_pv_qr_reject_update
    VaultQrRejection.LEGACY_CODE -> R.string.bt_pv_qr_reject_legacy
    VaultQrRejection.MALFORMED,
    VaultQrRejection.MISSING_REQUIRED_KEY,
    VaultQrRejection.PHRASE_INVALID,
    VaultQrRejection.VAULT_ID_INVALID,
    VaultQrRejection.NAME_TOO_LONG,
    -> R.string.bt_pv_qr_reject_generic
}
