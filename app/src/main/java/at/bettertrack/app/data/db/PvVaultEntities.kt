package at.bettertrack.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * **Dormant** local tables for the redefined paranoid vaults
 * (`paranoid-design.md` §3/§5/§6, platform epic E0 #1410).
 *
 * `vaults` and `vault_docs` are still read and written by nothing. The third
 * table, [PvVaultDocCursorRow], arrived with the per-vault sync engine
 * (`vault/pv/sync`) and has the one DAO in this file — the engine is itself
 * dormant behind `ParanoidVaultsFlags.enabled`, so the whole set is still
 * behaviourally invisible: the tables are empty, nothing outside `vault/pv/…`
 * constructs a caller, and an upgrade costs one `CREATE TABLE` each.
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
 * - `vault_doc_cursors` is not a server mirror at all — it is this device's
 *   per-`(vault, doc, medium)` CAS bookmark, because §6 gives every medium its
 *   OWN cursor and a shared one would let a landed Drive write claim the server
 *   had the bytes too.
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

/**
 * **One medium's CAS bookmark for one doc** — the per-`(vault, doc, medium)`
 * cursor the sync engine reads before every write and advances after every
 * landed one (`paranoid-design.md` §6).
 *
 * ## Why the medium is part of the key
 *
 * §6 gives each medium its own cursor on purpose, and the live v1 rail learned
 * the same lesson the expensive way: `vaultLastPushedKey` suffixes the meta key
 * per medium because *"sharing one cursor across media would make a successful
 * Drive push claim the server had the bytes too."* Here that is structural — the
 * medium is a primary-key column, so a row for `server` cannot be mistaken for a
 * row for `drive`.
 *
 * ## What a row asserts, and why every column is NOT NULL
 *
 * A row means: *"at this address, on this medium, version [docVersion] under
 * validator [etag] is a version this device has already adopted or written."*
 * The three facts are useless apart — an ETag with no version cannot address
 * history, a version with no ETag cannot build an `If-Match` — so the row is
 * complete or it does not exist. That is the shipped
 * `ServerVaultEtagCache` discipline ("validator and payload live and die
 * together") re-keyed: what the validator is paired with here is not a cached
 * body but the **claim that local state already contains that version**, which
 * is exactly what makes a `304` answerable as a no-op instead of an empty read.
 *
 * The corollary is a rule the engine owns: anything that discards local vault
 * state must discard these rows with it, or a stale validator would tell the
 * server to skip sending data this device no longer holds.
 *
 * [lastWriteId] is the idempotency key of the write this cursor came from — the
 * remote header's `writeId` on an adopted pull, this device's own on a landed
 * push. It is what lets a later `412` be recognised as *this device's own
 * earlier write having landed after all* rather than as another device's edit.
 */
@Entity(
    tableName = "vault_doc_cursors",
    primaryKeys = ["vaultId", "docId", "medium"],
    indices = [Index("vaultId", "medium")],
)
data class PvVaultDocCursorRow(
    val vaultId: String,
    val docId: String,
    /** `server` | `drive` — the medium's wire name (§3 `media`). */
    val medium: String,
    /** The `ETag` VERBATIM, quotes included: the server compares validators, not integers. */
    val etag: String,
    /** The envelope `docVersion` the validator names. */
    val docVersion: Int,
    /** The `writeId` of the write this cursor came from. */
    val lastWriteId: String,
    /** Wall-clock ms this cursor last advanced. */
    val syncedAtMs: Long,
)

/**
 * The one DAO of the per-vault rail. Cursors only: `vaults` and `vault_docs`
 * stay caller-less until the epic that needs them lands, and a DAO that exists
 * before its caller is an invitation to wire it up early.
 */
@Dao
interface PvVaultSyncDao {

    @Query("SELECT * FROM vault_doc_cursors WHERE vaultId = :vaultId AND medium = :medium AND docId = :docId")
    suspend fun cursor(vaultId: String, medium: String, docId: String): PvVaultDocCursorRow?

    @Query("SELECT * FROM vault_doc_cursors WHERE vaultId = :vaultId AND medium = :medium")
    suspend fun cursors(vaultId: String, medium: String): List<PvVaultDocCursorRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(row: PvVaultDocCursorRow)

    @Query("DELETE FROM vault_doc_cursors WHERE vaultId = :vaultId AND medium = :medium AND docId = :docId")
    suspend fun forgetCursor(vaultId: String, medium: String, docId: String)

    /** A vault left the account, or its local state was discarded. */
    @Query("DELETE FROM vault_doc_cursors WHERE vaultId = :vaultId")
    suspend fun forgetVault(vaultId: String)

    /** Account teardown: no validator may outlive the state it claims. */
    @Query("DELETE FROM vault_doc_cursors")
    suspend fun clearCursors()
}
