package at.bettertrack.app.vault.pv.envelope

import kotlinx.serialization.json.JsonElement

/**
 * The non-envelope halves of the E0 contract the conformance vectors pin: the
 * media enum, the key fingerprint, the create-vault body, the §15 step-up
 * credential and the §9/§10 move-in / move-out bodies.
 *
 * These are **request shapes**, not routes. Nothing here calls anything: E1/E4
 * deploy the endpoints, and until then a client that knows the shape can be
 * proven to agree with the server's contract without inventing an endpoint.
 *
 * Each validator answers `null` for "valid" and a reason otherwise — the shape
 * of zod's `safeParse`, kept as a reason string because every one of these
 * failures ends up in front of a user or in a test message.
 *
 * ## One open bound, recorded rather than invented
 *
 * The platform's step-up schema caps `password` at `MAX_PASSWORD_LENGTH` from
 * its auth contract, which is not part of the extracted E0 file. This port
 * validates "present and non-empty" and deliberately does NOT invent a maximum:
 * a client-side cap that is smaller than the server's would refuse a password
 * the account actually has.
 */
object PvVaultConfig {

    /** `portfolioDataRevisionSchema` — an opaque capture digest (§9 step 2). */
    const val PORTFOLIO_DATA_REVISION_MAX: Int = 128

    /**
     * `vaultMediaListSchema` — non-empty, no duplicates, no unknown value.
     *
     * ACCEPTS `local`: the reserved-value rejection is a SERVER decision (§22),
     * so a newer client talking about `local` gets the server's clear "reserved"
     * error instead of a generic contract violation here.
     */
    fun mediaListProblem(media: List<String>): String? {
        if (media.isEmpty()) return "media must not be empty"
        if (media.size > PvVaultContract.MEDIA_VALUES.size) return "media has too many values"
        if (media.toSet().size != media.size) return "media must not repeat a value"
        val unknown = media.firstOrNull { it !in PvVaultContract.MEDIA_VALUES }
        if (unknown != null) return "unknown medium '$unknown'"
        return null
    }

    /** True only for the media set the server accepts today (§22). */
    fun isServerAcceptedMedia(media: List<String>): Boolean =
        mediaListProblem(media) == null && media.all { it in PvVaultContract.SERVER_ACCEPTED_MEDIA }

    /** `vaultKeyFingerprintSchema` — base64url, exactly 16 characters. */
    fun keyFingerprintProblem(value: String): String? = when {
        !PV_BASE64URL_REGEX.matches(value) -> "must be base64url"
        value.length != PvVaultContract.KEY_FINGERPRINT_CHARS ->
            "must be exactly ${PvVaultContract.KEY_FINGERPRINT_CHARS} characters"
        else -> null
    }

    /** `vaultNameSchema` / `vaultAliasSchema` — trimmed, 1..120, cleartext (§21 Q4). */
    fun labelProblem(value: String, max: Int): String? {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> "must not be empty"
            trimmed.length > max -> "must be at most $max characters"
            else -> null
        }
    }

    /**
     * `createVaultRequestSchema` — the client supplies everything derived from
     * its OWN key material (the fingerprint of the `K_c` it generated, the
     * public half of the retirement keypair whose private half lives inside the
     * encrypted common doc); the server assigns the id.
     *
     * The Drive binding is required **iff** the drive medium is selected, in
     * both directions — a binding without the medium is as wrong as a medium
     * without the binding.
     */
    fun createVaultProblem(
        name: String,
        media: List<String>,
        driveConnectionId: String?,
        keyFingerprint: String,
        retirementProofPublicKey: String,
    ): String? {
        labelProblem(name, PvVaultContract.NAME_MAX)?.let { return "name $it" }
        mediaListProblem(media)?.let { return it }
        keyFingerprintProblem(keyFingerprint)?.let { return "keyFingerprint $it" }
        if (retirementProofPublicKey.isEmpty() || !PV_BASE64URL_REGEX.matches(retirementProofPublicKey)) {
            return "retirementProofPublicKey must be non-empty base64url"
        }
        val driveSelected = media.contains("drive")
        val bound = driveConnectionId != null
        if (driveSelected && !bound) return "the drive medium requires a bound Drive connection"
        if (!driveSelected && bound) return "a Drive connection binding requires the drive medium"
        if (bound && !pvIsUuid(driveConnectionId!!)) return "driveConnectionId must be a uuid"
        return null
    }

    /** `portfolioVaultMoveInRequestSchema` (§9 step 4). */
    fun moveInProblem(
        vaultId: String,
        docVersion: Int,
        portfolioDataRevision: String,
        stepUp: PvStepUpCredential?,
    ): String? {
        if (!pvIsUuid(vaultId)) return "vaultId must be a uuid"
        if (docVersion < 1) return "docVersion must be at least 1"
        val revision = portfolioDataRevision.trim()
        if (revision.isEmpty() || revision.length > PORTFOLIO_DATA_REVISION_MAX ||
            !PV_BASE64URL_REGEX.matches(revision)
        ) {
            return "portfolioDataRevision must be an opaque 1..$PORTFOLIO_DATA_REVISION_MAX token"
        }
        return stepUpRequired(stepUp)
    }

    /**
     * `portfolioVaultMoveOutRequestSchema` (§10) — from an unlocked device only.
     *
     * `moveOutId` is the client-supplied idempotency key (the v1 `rehydrationId`
     * pattern: retry-safe, never double-restores). `document` is transport shape
     * only here — E4 pins the STRICT per-portfolio restore graph and nothing may
     * apply the document without that fail-closed parse.
     */
    fun moveOutProblem(
        vaultId: String,
        moveOutId: String,
        document: JsonElement?,
        stepUp: PvStepUpCredential?,
    ): String? {
        if (!pvIsUuid(vaultId)) return "vaultId must be a uuid"
        if (!pvIsUuid(moveOutId)) return "moveOutId must be a uuid"
        if (document == null) return "document is required"
        return stepUpRequired(stepUp)
    }

    private fun stepUpRequired(stepUp: PvStepUpCredential?): String? =
        // NOT `stepUp?.problem() ?: message`: a VALID credential answers null,
        // and an elvis on that would report the missing-credential error for the
        // one case that is actually fine.
        if (stepUp == null) {
            "Re-authentication is required: send your password or a two-factor code."
        } else {
            stepUp.problem()
        }
}

/**
 * `vaultStepUpCredentialSchema` (§15, the #1326 carry-over) — the in-body
 * re-auth that replaces CSRF + same-origin on the bearer path.
 *
 * At least one credential must be present; the server verifies it inside the
 * same account lock as the transition it gates.
 */
data class PvStepUpCredential(
    val password: String? = null,
    /** A fresh 6-digit authenticator (TOTP) code — 2FA-enrolled accounts only. */
    val code: String? = null,
    /** An unused recovery code — consumed on success AND on a failed match. */
    val recoveryCode: String? = null,
) {
    /** `null` when this credential satisfies the contract. */
    fun problem(): String? {
        if (password == null && code == null && recoveryCode == null) {
            return "Re-authentication is required: send your password or a two-factor code."
        }
        if (password != null && password.isEmpty()) return "password must not be empty"
        if (code != null && (code.trim().length < 4 || code.trim().length > 16)) {
            return "code must be 4..16 characters"
        }
        if (recoveryCode != null && (recoveryCode.trim().length < 4 || recoveryCode.trim().length > 64)) {
            return "recoveryCode must be 4..64 characters"
        }
        return null
    }
}
