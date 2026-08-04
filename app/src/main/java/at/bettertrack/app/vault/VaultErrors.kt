package at.bettertrack.app.vault

/**
 * The BTVAULT1 vault core — a literal Kotlin port of the platform's audited
 * browser vault (everything under `apps/web/src/user/vault/` in the BetterTrack
 * monorepo — note: no glob star after that path, because Kotlin block comments
 * NEST and a literal slash-star inside KDoc silently swallows the rest of the
 * file),
 * pinned by `tools/domain-vectors/PINNED_AT` and vendored read-only under
 * `tools/domain-vectors/vendor/web-vault/`.
 *
 * **This package is pure Kotlin/JVM.** No `android.*`, no Compose, no Room. The
 * only third-party code it touches is Bouncy Castle's *lightweight* Argon2id
 * generator (never the JCA provider) and `kotlinx.serialization`'s JSON tree.
 * That is deliberate and load-bearing: the byte-for-byte conformance suite in
 * `app/src/test/java/at/bettertrack/app/vault/` runs as a plain JVM unit test,
 * so CI proves cross-client compatibility on every build without a device.
 *
 * The correctness argument for this package is not "it looks right" — it is
 * `app/src/test/resources/vault-vectors/vectors.fixture.json`, the platform's
 * published oracle, replayed byte-identically.
 *
 * Port of `apps/web/src/user/vault/errors.ts`.
 */

/** Port of `VaultCryptoErrorCode` (errors.ts:1-11). The wire values are contract. */
enum class VaultCryptoErrorCode(val wire: String) {
    AUTHENTICATION_FAILED("authentication-failed"),
    CUSTODY_FAILED("custody-failed"),
    DOCUMENT_INVALID("document-invalid"),
    ENVELOPE_INVALID("envelope-invalid"),
    KDF_FAILED("kdf-failed"),
    LOCKED("locked"),
    RECOVERY_KIT_INVALID("recovery-kit-invalid"),
    STORAGE_FAILED("storage-failed"),
    UNSUPPORTED_CRYPTO("unsupported-crypto"),
    UPDATE_REQUIRED("update-required"),
    ;

    override fun toString(): String = wire
}

/**
 * A fail-closed error raised by the vault core — port of the TypeScript
 * `VaultCryptoError` class (errors.ts:14-24).
 *
 * The [code] is what callers branch on; the message is human-facing and is
 * reproduced verbatim from the reference so that a divergence in behaviour is
 * visible in a test failure rather than hidden behind rephrasing.
 */
class VaultCryptoError(
    val code: VaultCryptoErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    override fun toString(): String = "VaultCryptoError[$code]: $message"
}

/**
 * Port of `asVaultCryptoError` (errors.ts:26-30): keep an already-typed failure
 * exactly as it is, otherwise wrap the cause under [code].
 *
 * This is what makes the reference's error *codes* stable through nested
 * `try/catch` layers — e.g. an `envelope-invalid` raised deep inside
 * `decryptVaultDocument` must not be relabelled `authentication-failed` on the
 * way out.
 */
internal fun asVaultCryptoError(
    code: VaultCryptoErrorCode,
    message: String,
    cause: Throwable?,
): VaultCryptoError =
    cause as? VaultCryptoError ?: VaultCryptoError(code, message, cause)
