package at.bettertrack.app.vault.pv.envelope

import kotlinx.serialization.json.JsonElement

/**
 * The canonical DER SubjectPublicKeyInfo encoding of an Ed25519 public key is
 * **44 bytes**: a fixed 12-byte prefix followed by the raw 32-byte key
 * (RFC 8410 §4). Nothing about it varies, which is why an exact length is a
 * legitimate schema rule rather than a brittle one.
 */
internal const val PV_ED25519_SPKI_BYTES: Int = 44

/**
 * `30 2a 30 05 06 03 2b 65 70 03 21 00` —
 * `SEQUENCE(42) { SEQUENCE(5) { OID 1.3.101.112 } BIT STRING(33) { 0 unused bits } }`.
 *
 * `2b 65 70` is the id-Ed25519 OID body; the `00` is the BIT STRING's unused-bit
 * count, which is what makes the 32 key bytes start on a byte boundary.
 */
internal val PV_ED25519_SPKI_PREFIX: ByteArray = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

/**
 * 44 bytes in unpadded base64url is always 59 characters, and the 12-byte prefix
 * lands exactly on a 3-byte group boundary — so every conforming key literally
 * begins with [PV_ED25519_SPKI_B64URL_PREFIX]. That is the "family" the platform
 * named its answer after, and it is checkable without decoding anything.
 */
internal const val PV_ED25519_SPKI_B64URL_CHARS: Int = 59

/** The 16 characters every 44-byte DER SPKI Ed25519 key starts with. */
internal const val PV_ED25519_SPKI_B64URL_PREFIX: String = "MCowBQYDK2VwAyEA"

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
 * ## One bound, answered and still not enforced here
 *
 * The platform's step-up schema caps `password` at `MAX_PASSWORD_LENGTH`, which
 * came back on 2026-08-20 as **200**. It is recorded on [PvStepUpCredential] and
 * deliberately NOT enforced: see the reasoning there.
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

    /**
     * `vaultRetirementProofPublicKeySchema` — the §7 purge verifier, and the one
     * field in this file whose validation is worth spelling out.
     *
     * The platform's answer (2026-08-20): base64url over `/^[A-Za-z0-9_-]+$/`,
     * **exactly the length of the 44-byte canonical DER SubjectPublicKeyInfo
     * encoding of an Ed25519 key** — the `MCowBQYDK2VwAyEA…` family — and it must
     * BE such a key, not merely be that long.
     *
     * So three things are checked, and the third is the one that matters:
     *
     *  1. the alphabet (base64url, unpadded, canonical — non-zero padding bits in
     *     the final group are two spellings of the same bytes and both are refused
     *     by [pvBase64UrlDecode]);
     *  2. the decoded length, [PV_ED25519_SPKI_BYTES];
     *  3. the **12-byte DER prefix** [PV_ED25519_SPKI_PREFIX] —
     *     `SEQUENCE { SEQUENCE { OID 1.3.101.112 } BIT STRING (32 bytes, 0 unused) }`.
     *     Without it, any 44 random bytes would pass and the server would be handed
     *     a "verifier" that can never verify anything — discovered at the one
     *     moment it is needed, which is a purge the user is trying to prove.
     *
     * The remaining 32 bytes are the raw key. Whether they decompress to a point
     * on the curve is not decidable from the encoding and is not what the schema
     * asserts; this validator claims exactly what the platform's claims and no more.
     *
     * Note this is a REQUEST validator. The same public key also travels inside
     * the encrypted common doc, where [PvRetirementProof] keeps the looser
     * base64url check on purpose — refusing to *send* a malformed key costs a
     * retry, refusing to *open* a doc costs the vault (§16).
     */
    fun retirementProofPublicKeyProblem(value: String): String? {
        if (value.isEmpty() || !PV_BASE64URL_REGEX.matches(value)) return "must be non-empty base64url"
        if (value.length != PV_ED25519_SPKI_B64URL_CHARS) {
            return "must be a $PV_ED25519_SPKI_BYTES-byte DER SPKI Ed25519 key " +
                "($PV_ED25519_SPKI_B64URL_CHARS base64url characters)"
        }
        val decoded = runCatching { pvBase64UrlDecode(value, "retirementProofPublicKey") }.getOrNull()
            ?: return "must be canonical base64url"
        if (decoded.size != PV_ED25519_SPKI_BYTES) {
            return "must decode to $PV_ED25519_SPKI_BYTES bytes"
        }
        for (index in PV_ED25519_SPKI_PREFIX.indices) {
            if (decoded[index] != PV_ED25519_SPKI_PREFIX[index]) {
                return "must be a DER SPKI Ed25519 key (the $PV_ED25519_SPKI_B64URL_PREFIX… family)"
            }
        }
        return null
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
        retirementProofPublicKeyProblem(retirementProofPublicKey)
            ?.let { return "retirementProofPublicKey $it" }
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
 *
 * ## `MAX_PASSWORD_LENGTH = 200`, recorded and not enforced
 *
 * The platform's schema caps `password` at its auth contract's
 * `MAX_PASSWORD_LENGTH`, answered on 2026-08-20 as **200 characters**. This port
 * keeps "present and non-empty" and deliberately does not add the maximum:
 * nothing real gets refused by leaving it out (the server rejects an over-long
 * password on the same request either way, with its own message), while a
 * client-side cap that is ever set BELOW the server's would refuse a password
 * the account actually has — locking a user out of the one gate that stands in
 * front of their destructive operations. The number is written down here so the
 * next author knows it was a decision.
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
