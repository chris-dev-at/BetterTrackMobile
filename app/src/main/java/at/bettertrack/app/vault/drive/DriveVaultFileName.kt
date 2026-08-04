package at.bettertrack.app.vault.drive

import java.security.MessageDigest
import java.util.Base64

/**
 * The Drive `appDataFolder` object name — literal port of `driveVaultFileName`
 * (`apps/web/src/user/vault/drive/driveDataHome.ts:916-924`, vendored at
 * `tools/domain-vectors/vendor/web-vault/driveDataHome.ts`, platform
 * `origin/main` @ `8ac3c6a2`).
 *
 * ```
 * bettertrack-vault-<base64url(SHA-256("bettertrack-drive-vault-account-v1:" + accountId))>.btenc
 * ```
 *
 * ## Why this must be byte-identical and not merely "a stable name"
 *
 * `appDataFolder` is one flat namespace **shared by every app the user has
 * granted appdata to under one Google principal**, and it has no directory
 * structure and no listing the user can inspect. The name is the entire
 * selector. If the Android app hashed the account id even slightly differently
 * from the web PWA, the two clients would each create their own file, both would
 * work, and the user would silently have two divergent vaults with no error
 * anywhere — the worst possible failure mode for this feature.
 *
 * So the derivation is pinned by a test vector produced by **executing the
 * vendored reference function itself** (`DriveVaultFileNameTest`), not by
 * re-reading the algorithm and agreeing it looks right.
 *
 * ## The account id for an account-less user (board #41.2)
 *
 * The reference hashes a *BetterTrack* account id, which a Drive-only user does
 * not have. Per the board decision the app hashes a locally-minted
 * `vaultAccountId` UUID instead (see
 * [at.bettertrack.app.vault.VaultAccountIdentity]); the context string and the
 * hashing are unchanged, so a later attach can keep the same file by keeping the
 * same id. That is the whole reason the id is a value passed in here rather than
 * something this function derives.
 */
private const val DRIVE_VAULT_FILE_CONTEXT = "bettertrack-drive-vault-account-v1:"
private const val DRIVE_VAULT_FILE_PREFIX = "bettertrack-vault-"
private const val DRIVE_VAULT_FILE_SUFFIX = ".btenc"

fun driveVaultFileName(accountId: String): String {
    val scoped = (DRIVE_VAULT_FILE_CONTEXT + accountId).toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(scoped)
    return DRIVE_VAULT_FILE_PREFIX + base64Url(digest) + DRIVE_VAULT_FILE_SUFFIX
}

/**
 * `base64url` (driveDataHome.ts:926-930) — RFC 4648 §5 alphabet, **padding
 * stripped**. `Base64.getUrlEncoder().withoutPadding()` is the exact equivalent
 * of the reference's `btoa(...).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')`.
 */
private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
