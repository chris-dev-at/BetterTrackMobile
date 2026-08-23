package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * **E1 — the per-vault blind blob store** (`paranoid-design.md` §3/§6/§7,
 * platform epic E1, live on production 2026-08-23).
 *
 * Every shape below is transcribed from the DEPLOYED
 * `https://api.bettertrack.at/openapi.json`, read on 2026-08-23, not from prose:
 * the tick that announced the epic described
 * `DELETE /vaults/{vaultId}/media/server-candidate/{candidateId}`, and the
 * deployed schema declares that route as a **GET** that returns the candidate's
 * bytes plus a verification receipt. OpenAPI wins; the drift is reported.
 *
 * ## The `.strict()` question, answered in both directions
 *
 * Every schema here carries `additionalProperties: false` server-side. That is a
 * rule about two different things and this file honours both halves differently:
 *
 * - **Outgoing** (requests) — the app must never send a field the contract does
 *   not name. That is a property of the declarations below: each request DTO
 *   declares exactly the contract's members and nothing else, and
 *   `VaultDtoContractTest` pins the serialized key set of every one of them.
 * - **Incoming** (responses) — the app's shared `Json` runs
 *   `ignoreUnknownKeys = true` (`AppGraph.json`), deliberately, so a platform
 *   that adds a field does not break a shipped client. Required-ness is kept as
 *   far as that instance allows: every member the contract lists in `required`
 *   is declared here **without a default**, so a NON-nullable one that the
 *   server omits raises `MissingFieldException` instead of becoming a silent
 *   default. A nullable one is the measured exception — `explicitNulls = false`
 *   decodes an absent nullable member as `null`, so this client cannot tell
 *   "sent null" from "sent nothing" for `driveConnectionId`, `mediaAttestedAt`,
 *   `mediaAttestedDriveConnectionId`, `nextCursor` and `server.retirement`.
 *   Every one of those means an absence either way, which is why the boundary is
 *   recorded and pinned by a test rather than papered over with a second `Json`
 *   whose settings would drift from the app's.
 *
 * ## The trap this file exists to defuse: `explicitNulls = false`
 *
 * The app's shared `Json` also runs `explicitNulls = false` so PATCH bodies stay
 * sparse. On these routes that setting is actively dangerous: three REQUEST
 * members are `required` **and** `nullable`
 * (`PerVaultMediaTransitionRequest.expected.driveConnectionId`,
 * `.expected.mediaAttestedAt`, `.next.driveConnectionId`). A Kotlin `String?`
 * holding `null` would be DROPPED from the body, and a zod `.nullable()` member
 * with the key absent fails the parse — the request would come back `400` with
 * no hint that a client-side serializer setting ate it.
 *
 * Those three members are therefore typed [JsonElement] and built through
 * [jsonNullableString], so an explicit `null` is transmitted as an explicit
 * `null`. Nothing else in this file needs the treatment: `CreateVaultRequest`'s
 * `driveConnectionId` is genuinely OPTIONAL (`default: null`, not in `required`),
 * so omitting it is what the contract asks for.
 *
 * Dormant: nothing outside `vault/pv/…` and its tests references these types
 * while `ParanoidVaultsFlags.enabled` is `false`.
 */

/** `null` as a transmitted JSON `null` rather than an omitted key — see the file note. */
internal fun jsonNullableString(value: String?): JsonElement =
    if (value == null) JsonNull else JsonPrimitive(value)

/** The string behind a [jsonNullableString] cell, or `null` for a JSON `null`. */
internal fun JsonElement.nullableString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// ── Vault configuration CRUD (`/vaults`, `/vaults/{vaultId}`) ────────────────

/**
 * `VaultConfig` — one vault's **cleartext storage configuration**.
 *
 * Cleartext by design and ruled so (§21 Q4): the server has to know THAT a vault
 * exists, where it stores and which portfolios are inside to enforce §11 and
 * render locked stubs. What it can never do is read a doc.
 *
 * [headerDocId] and [commonDocId] are **client-minted at creation** and are the
 * whole of the doc-addressing map: a doc id that is neither of them is a
 * portfolio doc, and then the doc id *is* the portfolio uuid. See
 * `PvVaultDocDirectory`, which is the only thing allowed to make that call.
 */
@Serializable
data class VaultConfigDto(
    val id: String,
    val name: String,
    val headerDocId: String,
    val commonDocId: String,
    /** ⊆ {`server`,`drive`,`local`}; `local` is contract-reserved and server-refused (§22). */
    val media: List<String>,
    val driveConnectionId: String?,
    val keyFingerprint: String,
    val retirementProofPublicKey: String,
    val retirementGeneration: Int,
    val mediaAttestedAt: String?,
    val mediaAttestedDriveConnectionId: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** `VaultListResponse` — `GET /vaults`. */
@Serializable
data class VaultListResponse(val vaults: List<VaultConfigDto>)

/**
 * `CreateVaultResponse` — and, member for member, `PatchVaultResponse`.
 *
 * The deployed schema declares the two under different names with an identical
 * required set and an identical inline `vault` object; `GET /vaults/{vaultId}`
 * answers with the `CreateVaultResponse` schema as well. Declaring one Kotlin
 * type for all three is what keeps them from drifting apart in this client, and
 * `VaultDtoContractTest` pins that the same bytes decode as each.
 */
@Serializable
data class VaultConfigResponse(val vault: VaultConfigDto)

/**
 * `CreateVaultRequest` — the client supplies everything derived from its OWN key
 * material and the two singleton doc ids; the server assigns the vault id.
 *
 * [driveConnectionId] is optional in the contract (`default: null`), so a null
 * one is correctly OMITTED under `explicitNulls = false` — unlike the three
 * required-and-nullable members called out in the file note.
 */
@Serializable
data class CreateVaultRequest(
    val name: String,
    val headerDocId: String,
    val commonDocId: String,
    val media: List<String>,
    val driveConnectionId: String? = null,
    val keyFingerprint: String,
    val retirementProofPublicKey: String,
)

/**
 * `PatchVaultRequest` — "rename one caller-owned vault without changing its
 * media transition state".
 *
 * The contract lists NO required member, so [name] is optional here too; the
 * repository's rename entry point is what makes it non-null in practice.
 */
@Serializable
data class PatchVaultRequest(val name: String? = null)

/**
 * `vaultStepUpCredentialSchema` (§15) — the in-body re-auth that replaces CSRF +
 * same-origin on the bearer path. At least one member must be present; the
 * server verifies it inside the same account lock as the transition it gates.
 */
@Serializable
data class VaultStepUpDto(
    val password: String? = null,
    /** A fresh authenticator (TOTP) code — 4..16 characters per the contract. */
    val code: String? = null,
    /** An unused recovery code — consumed on success AND on a failed match. */
    val recoveryCode: String? = null,
)

/** `DeleteVaultRequest` — step-up is `required`, which is why it is not nullable. */
@Serializable
data class DeleteVaultRequest(val stepUp: VaultStepUpDto)

/** `DeleteVaultResponse` — `{ ok: true }`. */
@Serializable
data class DeleteVaultResponse(val ok: Boolean)

// ── Per-doc history (`/vaults/{vaultId}/docs/{docId}/history`) ───────────────

/**
 * `VaultHistoryListResponse.items[]` — bounded ciphertext-history metadata.
 * Metadata only; the bytes are fetched separately and are the only thing that
 * ever holds money.
 */
@Serializable
data class VaultHistoryItemDto(
    val version: Int,
    val createdAt: String,
    val sizeBytes: Long,
    /** The contract's enum has exactly one member today: `server`. */
    val medium: String,
)

/** `VaultHistoryListResponse` — `nextCursor` is required AND nullable. */
@Serializable
data class VaultHistoryListResponse(
    val items: List<VaultHistoryItemDto>,
    val nextCursor: Int?,
)

// ── Media state, candidates, retirement (`/vaults/{vaultId}/media*`) ─────────

/**
 * `PerVaultServerCandidateMetadata` — one staged, not-yet-committed server
 * candidate. The same object is the item type of `server.candidates` in the
 * media-state response.
 *
 * [candidateId] rotates on every re-stage: an old receipt is deliberately not
 * reusable, which is what stops a partial transition from being assembled out
 * of receipts collected across attempts.
 */
@Serializable
data class PerVaultServerCandidateDto(
    val candidateId: String,
    val transitionId: String,
    val docId: String,
    val docKind: String,
    val docVersion: Int,
    val formatVersion: Int,
    val writeId: String,
    val sizeBytes: Long,
    val expiresAt: String,
)

/**
 * The retirement row of a vault whose `server` medium was removed — the recovery
 * set that `DELETE /vaults/{vaultId}` refuses to step over and that only the §7
 * signed purge gate can destroy.
 */
@Serializable
data class PerVaultRetirementDto(
    val generation: Int,
    /** base64url sha-256 over the sorted `(docId, docVersion)` pairs at retirement. */
    val versionSetHash: String,
    val retiredAt: String,
    /** The retention floor: a purge before this instant is refused. */
    val purgeAfter: String,
)

/** The `server` sub-object of the media state. */
@Serializable
data class PerVaultServerStateDto(
    /** `active` | `inactive-candidates` | `retired` | `empty`. */
    val disposition: String,
    val candidates: List<PerVaultServerCandidateDto>,
    val retirement: PerVaultRetirementDto?,
)

/**
 * `PerVaultMediaStateResponse` — and, member for member,
 * `PerVaultMediaTransitionResponse`: the deployed schemas are identical, so the
 * commit answers with the fresh state rather than a bespoke acknowledgement.
 */
@Serializable
data class PerVaultMediaStateResponse(
    val vaultId: String,
    val media: List<String>,
    val driveConnectionId: String?,
    val mediaAttestedAt: String?,
    val mediaAttestedDriveConnectionId: String?,
    val server: PerVaultServerStateDto,
)

/** `PerVaultMediaTransitionResponse` — byte-identical to [PerVaultMediaStateResponse]. */
typealias PerVaultMediaTransitionResponse = PerVaultMediaStateResponse

/** One `(docId, docVersion, writeId)` triple — the contract's proof-of-readback row. */
@Serializable
data class PerVaultDocRefDto(
    val docId: String,
    val docVersion: Int,
    val writeId: String,
)

/** One server-candidate readback receipt, as handed back by the candidate GET. */
@Serializable
data class PerVaultCandidateReadbackDto(
    val candidateId: String,
    val docId: String,
    /** The opaque receipt from `X-BetterTrack-Vault-Candidate-Readback`. */
    val readback: String,
)

/**
 * The CAS half of a media transition: the state the client believes it is moving
 * FROM. Two of its three members are `required` and `nullable`, so both are
 * [JsonElement] cells — see the file note on `explicitNulls = false`.
 */
@Serializable
data class PerVaultMediaExpectedDto(
    val media: List<String>,
    val driveConnectionId: JsonElement,
    val mediaAttestedAt: JsonElement,
) {
    companion object {
        fun of(
            media: List<String>,
            driveConnectionId: String?,
            mediaAttestedAt: String?,
        ): PerVaultMediaExpectedDto = PerVaultMediaExpectedDto(
            media = media,
            driveConnectionId = jsonNullableString(driveConnectionId),
            mediaAttestedAt = jsonNullableString(mediaAttestedAt),
        )
    }
}

/** The state the client is moving TO. `driveConnectionId` is required-and-nullable. */
@Serializable
data class PerVaultMediaNextDto(
    val media: List<String>,
    val driveConnectionId: JsonElement,
) {
    companion object {
        fun of(media: List<String>, driveConnectionId: String?): PerVaultMediaNextDto =
            PerVaultMediaNextDto(media, jsonNullableString(driveConnectionId))
    }
}

/**
 * The `verification` member of a media transition — a `oneOf` over three
 * branches discriminated by `kind`.
 *
 * Modelled as one closed shape with per-branch members rather than a kotlinx
 * sealed hierarchy on purpose: kotlinx's polymorphic encoding needs a
 * `classDiscriminator` on the `Json` instance, and this app has ONE shared
 * `Json` that every other endpoint rides. Re-pointing its discriminator to
 * `kind` to serve three request bodies would change how unrelated payloads
 * encode. With `explicitNulls = false` the unused branch members are omitted, so
 * each factory below emits exactly one branch's key set — pinned by
 * `VaultDtoContractTest`.
 */
@Serializable
data class PerVaultMediaVerificationDto(
    val kind: String,
    val readbacks: List<PerVaultCandidateReadbackDto>? = null,
    val driveConnectionId: String? = null,
    val docs: List<PerVaultDocRefDto>? = null,
) {
    /** `null` when this value is exactly one of the contract's three branches. */
    fun problem(): String? = when (kind) {
        KIND_SERVER_CANDIDATES -> when {
            readbacks == null -> "server-candidates verification needs readbacks"
            driveConnectionId != null || docs != null ->
                "server-candidates verification carries only readbacks"

            else -> null
        }

        KIND_DRIVE -> when {
            driveConnectionId == null -> "drive verification needs a driveConnectionId"
            docs == null -> "drive verification needs docs"
            readbacks != null -> "drive verification carries no readbacks"
            else -> null
        }

        KIND_SERVER -> when {
            docs == null -> "server verification needs docs"
            readbacks != null || driveConnectionId != null ->
                "server verification carries only docs"

            else -> null
        }

        else -> "unknown verification kind '$kind'"
    }

    companion object {
        const val KIND_SERVER_CANDIDATES: String = "server-candidates"
        const val KIND_DRIVE: String = "drive"
        const val KIND_SERVER: String = "server"

        fun serverCandidates(readbacks: List<PerVaultCandidateReadbackDto>) =
            PerVaultMediaVerificationDto(kind = KIND_SERVER_CANDIDATES, readbacks = readbacks)

        fun drive(driveConnectionId: String, docs: List<PerVaultDocRefDto>) =
            PerVaultMediaVerificationDto(
                kind = KIND_DRIVE,
                driveConnectionId = driveConnectionId,
                docs = docs,
            )

        fun server(docs: List<PerVaultDocRefDto>) =
            PerVaultMediaVerificationDto(kind = KIND_SERVER, docs = docs)
    }
}

/**
 * `PerVaultMediaTransitionRequest` — "commit one **verified full-document-set**
 * media transition; removing server retires bytes instead of purging them".
 *
 * The batch-attestation rule lives in the server's commit: a transition commits
 * only when every live doc carries a verified candidate under this same
 * [transitionId]. Partial sets never commit, so the client's job is to stage the
 * WHOLE set first and hand back every receipt at once.
 */
@Serializable
data class PerVaultMediaTransitionRequest(
    val transitionId: String,
    val expected: PerVaultMediaExpectedDto,
    val next: PerVaultMediaNextDto,
    val verification: PerVaultMediaVerificationDto,
)

/** `PerVaultRetiredServerPurgeChallengeRequest` (§7). */
@Serializable
data class PerVaultRetiredServerPurgeChallengeRequest(
    val vaultId: String,
    val generation: Int,
    val versionSetHash: String,
)

/** `PerVaultRetiredServerPurgeChallengeResponse` — short-lived, generation-bound. */
@Serializable
data class PerVaultRetiredServerPurgeChallengeResponse(
    val vaultId: String,
    val generation: Int,
    val versionSetHash: String,
    val challenge: String,
    val expiresAt: String,
)

/**
 * `PerVaultRetiredServerPurgeRequest` — the destructive half, gated on an
 * Ed25519 signature made with the private key that lives INSIDE the encrypted
 * common doc. Possession of the vault's key, not of a session.
 */
@Serializable
data class PerVaultRetiredServerPurgeRequest(
    val vaultId: String,
    val generation: Int,
    val versionSetHash: String,
    val observedDocs: List<PerVaultDocRefDto>,
    val challenge: String,
    val signature: String,
)

/** `PerVaultRetiredServerPurgeResponse` — `{ purged: true, … }`. */
@Serializable
data class PerVaultRetiredServerPurgeResponse(
    val purged: Boolean,
    val vaultId: String,
    val generation: Int,
    val versionSetHash: String,
)
