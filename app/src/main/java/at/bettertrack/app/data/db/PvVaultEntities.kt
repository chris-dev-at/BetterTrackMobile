package at.bettertrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * **Dormant** local tables for the redefined paranoid vaults
 * (`paranoid-design.md` §3/§5/§6, platform epic E0 #1410).
 *
 * Nothing reads or writes these. There is deliberately **no DAO**: the epic that
 * owns the sync rail (E1/E5) adds one when there is a real store to talk to, and
 * a DAO that exists before its caller is an invitation to wire it up early. The
 * whole program is gated by `ParanoidVaultsFlags.enabled`, which is `false`, so
 * this build is behaviourally identical to a build without these two tables —
 * they are empty, unreferenced, and cost one `CREATE TABLE` each at upgrade.
 *
 * They land NOW, in their own migration, for one reason: schema changes are the
 * one thing that cannot be added late without a user-visible cost. Two devices
 * that meet the same feature at different app versions must meet the same
 * `user_version` chain, and this project has already shipped two different
 * physical schemas under one version number twice (see [BtDatabase]'s migration
 * comments). Adding the tables while nothing depends on them is the cheap
 * moment; adding them under time pressure next to a live feature is the
 * expensive one.
 *
 * ## What they mirror
 *
 * The client-relevant shape of the E0 server schema, per §3:
 *
 * - `vaults` mirrors the server's config row — id, the CLEARTEXT-BY-DESIGN label
 *   (§21 Q4: a vault is account config and the UI needs its name while locked),
 *   the media set, the bound Drive connection, the non-secret `key_fingerprint`
 *   and the per-vault retirement-proof verifier.
 * - `vault_docs` mirrors `vault_blobs` keyed `(vault_id, doc_id)` — the envelope
 *   metadata the CAS protocol needs plus, optionally, the last-known ciphertext.
 *   That copy is a CACHE, never a medium (§6): a phone-local-only medium is
 *   reserved and unbuilt, and promoting this table to one is exactly the move
 *   §22 forbids.
 *
 * **No seed phrase, no `K_c`, no device password, no unlock state lives here** —
 * those are the endpoint keystore's business (`vault/pv/custody`), never the
 * database's, and there is no server table for them either.
 */

/** One vault's server-visible CONFIG, cached locally so a locked UI can render. */
@Entity(tableName = "vaults")
data class PvVaultRow(
    /** The server-assigned uuidv7. */
    @PrimaryKey val id: String,
    /** User-visible label. Cleartext by design (§21 Q4); the TRUE name is in the header doc. */
    val name: String,
    /** The media set, comma-joined in the client's chosen order — e.g. `server,drive`. */
    val media: String,
    /** Non-null exactly when `drive ∈ media` (§3). */
    val driveConnectionId: String?,
    /** `base64url(HKDF-SHA256(K_c, "…-fingerprint-v1"))[0..16]` — non-secret (§4). */
    val keyFingerprint: String,
    /** The per-vault Ed25519 purge verifier (§7); its private half lives in the common doc. */
    val retirementProofPublicKey: String,
    val createdAt: String,
    val updatedAt: String,
    /** Wall-clock ms of the last config sync; 0 = never. */
    val syncedAtMs: Long = 0,
)

/**
 * One document of one vault — the CAS unit (§6).
 *
 * [envelope] is the whole `BTVAULT1` wire blob including its cleartext header,
 * kept opaque: this table never interprets a byte past what the server itself is
 * allowed to read ([formatVersion] and [docVersion]). Null means "we know this
 * doc exists at this version but hold no copy".
 */
@Entity(
    tableName = "vault_docs",
    primaryKeys = ["vaultId", "docId"],
    indices = [Index("vaultId")],
)
data class PvVaultDocRow(
    val vaultId: String,
    val docId: String,
    /** `header` | `common` | `portfolio`. */
    val docKind: String,
    /** Set iff [docKind] is `portfolio` — the member portfolio's id. */
    val portfolioId: String?,
    /** The monotonic per-doc CAS token. */
    val docVersion: Int,
    val formatVersion: Int,
    val sizeBytes: Int,
    /** The last-known ciphertext envelope, or null when only metadata is known. */
    val envelope: ByteArray?,
    /** Wall-clock ms this row was last refreshed; 0 = never. */
    val cachedAtMs: Long = 0,
) {
    // A ByteArray member makes the generated equals/hashCode reference-based,
    // which silently breaks every `assertEquals` and every `distinct()` on these
    // rows. Spelled out rather than left to surprise the first caller.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is PvVaultDocRow &&
                vaultId == other.vaultId &&
                docId == other.docId &&
                docKind == other.docKind &&
                portfolioId == other.portfolioId &&
                docVersion == other.docVersion &&
                formatVersion == other.formatVersion &&
                sizeBytes == other.sizeBytes &&
                cachedAtMs == other.cachedAtMs &&
                (envelope?.contentEquals(other.envelope ?: ByteArray(0)) ?: (other.envelope == null))
            )

    override fun hashCode(): Int {
        var result = vaultId.hashCode()
        result = 31 * result + docId.hashCode()
        result = 31 * result + docKind.hashCode()
        result = 31 * result + (portfolioId?.hashCode() ?: 0)
        result = 31 * result + docVersion
        result = 31 * result + formatVersion
        result = 31 * result + sizeBytes
        result = 31 * result + (envelope?.contentHashCode() ?: 0)
        return 31 * result + cachedAtMs.hashCode()
    }
}
