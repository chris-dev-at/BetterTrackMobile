package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.asBtApiError
import at.bettertrack.app.data.api.dto.CreateVaultRequest
import at.bettertrack.app.data.api.dto.DeleteVaultRequest
import at.bettertrack.app.data.api.dto.PatchVaultRequest
import at.bettertrack.app.data.api.dto.PerVaultMediaStateResponse
import at.bettertrack.app.data.api.dto.PerVaultMediaTransitionRequest
import at.bettertrack.app.data.api.dto.PerVaultMediaTransitionResponse
import at.bettertrack.app.data.api.dto.PerVaultRetiredServerPurgeChallengeRequest
import at.bettertrack.app.data.api.dto.PerVaultRetiredServerPurgeChallengeResponse
import at.bettertrack.app.data.api.dto.PerVaultRetiredServerPurgeRequest
import at.bettertrack.app.data.api.dto.PerVaultRetiredServerPurgeResponse
import at.bettertrack.app.data.api.dto.VaultConfigDto
import at.bettertrack.app.data.api.dto.VaultHistoryListResponse
import at.bettertrack.app.data.api.dto.VaultStepUpDto
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeHeader
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeInspection
import at.bettertrack.app.vault.pv.envelope.PvStepUpCredential
import at.bettertrack.app.vault.pv.envelope.inspectPvDocEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response

/**
 * **The client of the per-vault blind blob store (E1).**
 *
 * The server is a store that cannot read what it holds. Exactly six cleartext
 * envelope fields are legible to it — `formatVersion`, `docVersion`, `vaultId`,
 * `docId`, `docKind`, `writeId` — and everything else, including the whole
 * payload, round-trips byte-identical. This class is the transport for those
 * bytes and nothing else: the envelope codec in `vault/pv/envelope/` produces
 * them and is neither re-implemented nor modified here.
 *
 * ## What this layer is responsible for
 *
 * 1. **Addressing.** Every doc call goes through [PvVaultDocDirectory], so a
 *    portfolio doc is addressed by the portfolio's own uuid (identity, never a
 *    mapping) and the two singletons by the ids the vault row registered.
 * 2. **Preconditions.** A write cannot be expressed without one — see
 *    [PvDocPrecondition]. Reads hand the validator back so the next write has
 *    one to carry.
 * 3. **Refusing early what can only fail late.** The per-kind size ceiling is
 *    checked here, against the kind the VAULT gives the address rather than the
 *    kind the envelope claims, so the app never ships an 8 MiB request that was
 *    always going to be refused.
 * 4. **Turning statuses into remedies.** Every outcome in
 *    `PvBlobStoreOutcomes.kt` exists because its repair differs from the others'.
 *
 * ## What this layer is deliberately NOT
 *
 * Not the sync coordinator. There is no scheduling, no queue, no cursor and no
 * WorkManager here; a caller decides when to read and when to write. The
 * re-architecture of the app's single `pushLock` and its single unique work
 * chain — which today serialise every vault behind one another — is a separate
 * round, and this class was written to be usable from a per-vault one: it holds
 * no per-instance mutable state except the bounded [PvWriteIdLedger], and every
 * entry point is keyed by `(vaultId, docId)`.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled`: nothing outside `vault/pv/…`
 * and its tests constructs it, which is what makes "flag off ⇒ behaviourally
 * identical to a build without the code" a fact rather than a claim.
 */
class PvBlobStore(
    private val api: BtApi,
    private val json: Json,
    /** Shared across vaults on purpose: `writeId`s are uuids and never collide. */
    private val ledger: PvWriteIdLedger = PvWriteIdLedger(),
) {

    // ── Vault configuration ─────────────────────────────────────────────────

    /** `GET /vaults` — the caller's cleartext storage configurations. */
    suspend fun listVaults(): BtResult<List<VaultConfigDto>> =
        when (val result = apiCall(json) { api.pvVaults() }) {
            is BtResult.Ok -> BtResult.Ok(result.value.vaults)
            is BtResult.Err -> result
        }

    /** `GET /vaults/{vaultId}` — another owner's id is not found, never forbidden. */
    suspend fun readVault(vaultId: String): BtResult<VaultConfigDto> =
        when (val result = apiCall(json) { api.pvVault(vaultId) }) {
            is BtResult.Ok -> BtResult.Ok(result.value.vault)
            is BtResult.Err -> result
        }

    /**
     * `POST /vaults` — the client mints both singleton doc ids and supplies the
     * key fingerprint and retirement verifier derived from its own key material;
     * the server assigns the vault id.
     */
    suspend fun createVault(request: CreateVaultRequest): BtResult<VaultConfigDto> =
        when (val result = apiCall(json) { api.pvCreateVault(request) }) {
            is BtResult.Ok -> BtResult.Ok(result.value.vault)
            is BtResult.Err -> result
        }

    /** `PATCH /vaults/{vaultId}` — rename only; media transition state is untouched. */
    suspend fun renameVault(vaultId: String, name: String): BtResult<VaultConfigDto> =
        when (val result = apiCall(json) { api.pvPatchVault(vaultId, PatchVaultRequest(name)) }) {
            is BtResult.Ok -> BtResult.Ok(result.value.vault)
            is BtResult.Err -> result
        }

    /**
     * `DELETE /vaults/{vaultId}` — §15 step-up rides in the body, and the server
     * refuses while any portfolio references the vault or any retirement row
     * exists.
     *
     * [stepUp] is a required parameter rather than an optional one: "forgot to
     * re-authenticate" is not a runtime condition worth handling, it is a
     * mistake worth making unspellable, exactly as with a write's precondition.
     */
    suspend fun deleteVault(vaultId: String, stepUp: PvStepUpCredential): PvVaultDeleteOutcome {
        stepUp.problem()?.let { return PvVaultDeleteOutcome.StepUpMissing(it) }
        val body = DeleteVaultRequest(
            VaultStepUpDto(
                password = stepUp.password,
                code = stepUp.code,
                recoveryCode = stepUp.recoveryCode,
            ),
        )
        val response = try {
            api.pvDeleteVault(vaultId, body)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvVaultDeleteOutcome.Transport(asBtApiError(cause))
        }
        if (response.isSuccessful) return PvVaultDeleteOutcome.Deleted
        val error = parseApiError(json, response.code(), response.errorBody())
        return when {
            error.code == BtApiError.Codes.INSUFFICIENT_SCOPE -> PvVaultDeleteOutcome.ScopeMissing(error)
            error.code == CODE_API_KEY_FORBIDDEN -> PvVaultDeleteOutcome.SessionRequired(error)
            response.code() == 403 -> PvVaultDeleteOutcome.StepUpRejected(error)
            else -> PvVaultDeleteOutcome.Refused(error)
        }
    }

    // ── Media, candidates, retirement ───────────────────────────────────────

    /** `GET /vaults/{vaultId}/media` — media selection, candidates, retirement. */
    suspend fun readMedia(vaultId: String): BtResult<PerVaultMediaStateResponse> =
        apiCall(json) { api.pvVaultMedia(vaultId) }

    /**
     * `PATCH /vaults/{vaultId}/media` — commit one verified media transition.
     *
     * The batch-attestation rule is the server's: a transition commits only when
     * EVERY live doc carries a verified candidate under the same `transitionId`,
     * and partial sets never commit. The client's part is to stage the whole set
     * first, which is why the request's verification member is validated here
     * before it is sent — a malformed `oneOf` branch is a `400` the caller can
     * be told about without a round trip.
     */
    suspend fun commitMediaTransition(
        vaultId: String,
        request: PerVaultMediaTransitionRequest,
    ): BtResult<PerVaultMediaTransitionResponse> {
        request.verification.problem()?.let { problem ->
            return BtResult.Err(
                BtApiError(400, BtApiError.Codes.VALIDATION_ERROR, diagnostic = problem),
            )
        }
        return apiCall(json) { api.pvPatchVaultMedia(vaultId, request) }
    }

    /**
     * `GET /vaults/{vaultId}/media/server-candidate/{candidateId}` — read one
     * staged candidate back and collect its verification receipt.
     *
     * The tick that announced E1 described this route as a `DELETE`. The
     * deployed OpenAPI declares a `GET` that answers the candidate's opaque bytes
     * plus `X-BetterTrack-Vault-Candidate-*` headers; there is no `DELETE` on the
     * path at all. OpenAPI wins.
     */
    suspend fun readServerCandidate(vaultId: String, candidateId: String): PvCandidateReadOutcome {
        val response = try {
            api.pvReadVaultServerCandidate(vaultId, candidateId)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvCandidateReadOutcome.Transport(asBtApiError(cause))
        }
        if (!response.isSuccessful) {
            return PvCandidateReadOutcome.Refused(
                parseApiError(json, response.code(), response.errorBody()),
            )
        }
        val bytes = response.body()?.bytes() ?: ByteArray(0)
        return PvCandidateReadOutcome.Loaded(
            envelope = bytes,
            candidateId = response.headers()[HEADER_CANDIDATE_ID],
            expiresAt = response.headers()[HEADER_CANDIDATE_EXPIRES_AT],
            readback = response.headers()[HEADER_CANDIDATE_READBACK],
        )
    }

    /** `POST /vaults/{vaultId}/media/retired/purge/challenge` (§7). */
    suspend fun retiredPurgeChallenge(
        vaultId: String,
        request: PerVaultRetiredServerPurgeChallengeRequest,
    ): BtResult<PerVaultRetiredServerPurgeChallengeResponse> =
        apiCall(json) { api.pvVaultRetiredPurgeChallenge(vaultId, request) }

    /** `POST /vaults/{vaultId}/media/retired/purge` — retention floor + Ed25519 proof (§7). */
    suspend fun retiredPurge(
        vaultId: String,
        request: PerVaultRetiredServerPurgeRequest,
    ): BtResult<PerVaultRetiredServerPurgeResponse> =
        apiCall(json) { api.pvVaultRetiredPurge(vaultId, request) }

    // ── Docs ────────────────────────────────────────────────────────────────

    /**
     * The doc surface of ONE vault.
     *
     * Handing out a bound handle rather than taking a `vaultId` on every call is
     * not sugar: it makes "a doc reference of vault A sent to vault B" a shape
     * the caller cannot build, on a route whose server-side check would answer a
     * flat `400` with nothing to say about which of the two ids was wrong.
     */
    fun docsOf(directory: PvVaultDocDirectory): PvVaultDocs = PvVaultDocs(this, directory)

    /** The doc surface of the vault this configuration row describes. */
    fun docsOf(config: VaultConfigDto): PvVaultDocs = docsOf(PvVaultDocDirectory.of(config))

    internal suspend fun readDoc(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        ifNoneMatch: PvDocEtag?,
    ): PvDocReadOutcome {
        if (!directory.accepts(ref)) return PvDocReadOutcome.Corrupt(addressReason(directory, ref))
        val response = try {
            api.pvReadVaultDoc(directory.vaultId, ref.docId, ifNoneMatch?.header)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvDocReadOutcome.Transport(asBtApiError(cause))
        }
        return interpretRead(directory, ref, response, ifNoneMatch)
    }

    internal suspend fun readDocHistory(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        cursor: Int?,
        limit: Int?,
    ): BtResult<VaultHistoryListResponse> {
        if (!directory.accepts(ref)) {
            return BtResult.Err(
                // 400, not 0: `httpStatus == 0` is this app's "network failure"
                // marker (`BtApiError.isNetwork`), and a caller that retried this
                // as a transient outage would retry an address that is wrong.
                BtApiError(400, BtApiError.Codes.VALIDATION_ERROR, addressReason(directory, ref)),
            )
        }
        return apiCall(json) { api.pvVaultDocHistory(directory.vaultId, ref.docId, cursor, limit) }
    }

    internal suspend fun readDocHistoryVersion(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        version: Int,
    ): PvDocReadOutcome {
        if (!directory.accepts(ref)) return PvDocReadOutcome.Corrupt(addressReason(directory, ref))
        val response = try {
            api.pvVaultDocHistoryVersion(directory.vaultId, ref.docId, version)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvDocReadOutcome.Transport(asBtApiError(cause))
        }
        return interpretRead(directory, ref, response, ifNoneMatch = null)
    }

    /**
     * `PUT /vaults/{vaultId}/docs/{docId}` — the compare-and-swap write.
     *
     * The order of the checks below is deliberate and is what the size-cap tests
     * pin:
     *
     * 1. the reference belongs to this vault at this kind;
     * 2. **the size ceiling of the VALIDATED kind** — before the envelope is even
     *    parsed, so a doc that claims a bigger kind in its header cannot borrow
     *    that kind's ceiling;
     * 3. the envelope decodes, and this build can read it;
     * 4. the envelope's own `vaultId` / `docId` / `docKind` agree with the route.
     *
     * Then the `writeId` pre-flight, which is where the livelock the two `412`s
     * exist to prevent is actually prevented — see [classifyPreconditionFailure].
     */
    internal suspend fun writeDoc(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        precondition: PvDocPrecondition,
        envelope: ByteArray,
    ): PvDocWriteOutcome {
        if (!directory.accepts(ref)) {
            return PvDocWriteOutcome.NotWritable(addressReason(directory, ref))
        }
        capProblem(ref.kind, envelope.size)?.let { return it }

        val header = when (val checked = outgoingHeader(directory, ref, envelope)) {
            is Outgoing.Rejected -> return PvDocWriteOutcome.NotWritable(checked.reason)
            is Outgoing.Ok -> checked.header
        }

        if (ledger.isReplayWithDifferentBytes(directory.vaultId, ref.docId, header.writeId, envelope)) {
            return PvDocWriteOutcome.WriteIdReplayRefused(header.writeId, detectedLocally = true)
        }

        val body = envelope.toRequestBody(OCTET_STREAM)
        val response = try {
            when (precondition) {
                PvDocPrecondition.CreateOnly ->
                    api.pvCreateVaultDoc(directory.vaultId, ref.docId, body)

                is PvDocPrecondition.Replace ->
                    api.pvReplaceVaultDoc(directory.vaultId, ref.docId, precondition.headerValue, body)
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvDocWriteOutcome.Transport(asBtApiError(cause))
        }

        if (response.isSuccessful) {
            ledger.record(directory.vaultId, ref.docId, header.writeId, envelope)
            val etag = PvDocEtag.parse(response.headers()[HEADER_ETAG])
                ?: return PvDocWriteOutcome.Transport(
                    BtApiError(
                        response.code(),
                        BtApiError.Codes.UNKNOWN,
                        "The vault store acknowledged a doc write without an ETag.",
                    ),
                )
            return PvDocWriteOutcome.Written(etag, header.docVersion)
        }

        val error = parseApiError(json, response.code(), response.errorBody())
        return when (response.code()) {
            412 -> classifyPreconditionFailure(directory, ref, header, envelope, response)
            413 -> PvDocWriteOutcome.TooLarge(
                kind = ref.kind,
                limitBytes = ref.kind.maxBytes,
                actualBytes = envelope.size,
                refusedBy = PvSizeRefusedBy.SERVER,
            )

            428 -> PvDocWriteOutcome.PreconditionMissing(error)
            else -> PvDocWriteOutcome.Refused(error)
        }
    }

    internal suspend fun stageServerCandidate(
        directory: PvVaultDocDirectory,
        transitionId: String,
        ref: PvDocRef,
        envelope: ByteArray,
    ): PvCandidateStageOutcome {
        if (!directory.accepts(ref)) {
            return PvCandidateStageOutcome.NotWritable(addressReason(directory, ref))
        }
        if (envelope.size > ref.kind.maxBytes) {
            return PvCandidateStageOutcome.TooLarge(
                kind = ref.kind,
                limitBytes = ref.kind.maxBytes,
                actualBytes = envelope.size,
                refusedBy = PvSizeRefusedBy.CLIENT,
            )
        }
        when (val checked = outgoingHeader(directory, ref, envelope)) {
            is Outgoing.Rejected -> return PvCandidateStageOutcome.NotWritable(checked.reason)
            is Outgoing.Ok -> Unit
        }
        val response = try {
            api.pvStageVaultServerCandidate(
                vaultId = directory.vaultId,
                transitionId = transitionId,
                docId = ref.docId,
                envelope = envelope.toRequestBody(OCTET_STREAM),
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvCandidateStageOutcome.Transport(asBtApiError(cause))
        }
        val body = response.body()
        if (response.isSuccessful && body != null) return PvCandidateStageOutcome.Staged(body)
        if (response.code() == 413) {
            return PvCandidateStageOutcome.TooLarge(
                kind = ref.kind,
                limitBytes = ref.kind.maxBytes,
                actualBytes = envelope.size,
                refusedBy = PvSizeRefusedBy.SERVER,
            )
        }
        return PvCandidateStageOutcome.Refused(
            parseApiError(json, response.code(), response.errorBody()),
        )
    }

    // ── Interpretation ──────────────────────────────────────────────────────

    private fun interpretRead(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        response: Response<ResponseBody>,
        ifNoneMatch: PvDocEtag?,
    ): PvDocReadOutcome {
        if (response.code() == 304) {
            val etag = PvDocEtag.parse(response.headers()[HEADER_ETAG]) ?: ifNoneMatch
            return etag?.let { PvDocReadOutcome.NotModified(it) }
                ?: PvDocReadOutcome.Corrupt("The vault store answered 304 without a validator.")
        }
        if (response.code() == 404) return PvDocReadOutcome.Absent
        if (!response.isSuccessful) {
            return PvDocReadOutcome.Refused(parseApiError(json, response.code(), response.errorBody()))
        }

        val bytes = try {
            response.body()?.bytes()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return PvDocReadOutcome.Transport(asBtApiError(cause))
        } ?: return PvDocReadOutcome.Corrupt("The vault store answered with no document bytes.")

        // The validator is required, not decorative: it is the CAS token the next
        // write is built on, and a write built on a missing one silently
        // overwrites whatever another device put there in between.
        val etag = PvDocEtag.parse(response.headers()[HEADER_ETAG])
            ?: return PvDocReadOutcome.Corrupt("The vault store returned document bytes without an ETag.")

        val inspection = try {
            inspectPvDocEnvelope(bytes)
        } catch (cause: VaultCryptoError) {
            return PvDocReadOutcome.Corrupt(cause.message ?: "The vault document could not be read.")
        }
        return when (inspection) {
            is PvDocEnvelopeInspection.UpdateRequired ->
                PvDocReadOutcome.UpdateRequired(inspection.formatVersion, inspection.schemaVersion)

            is PvDocEnvelopeInspection.Supported -> {
                val header = inspection.envelope.header
                headerAddressProblem(directory, ref, header)
                    ?.let { return PvDocReadOutcome.Corrupt(it) }
                PvDocReadOutcome.Loaded(ref, bytes, etag, header)
            }
        }
    }

    /**
     * **Which `412` is this?**
     *
     * The two reasons the contract gives for a `412` — a stale precondition and a
     * `writeId` replayed with different bytes — have opposite remedies, so they
     * must not be one outcome. They are also indistinguishable on the wire: the
     * deployed schema types `ApiError.code` as a bare `string` and publishes no
     * per-status code for this route, so there is no name to key off and this
     * client will not invent one.
     *
     * So the distinction is drawn from the fact that actually decides it, which
     * the client owns: is this `writeId` already bound to different bytes at this
     * address? [PvWriteIdLedger] answers that, and `writeDoc` asks it BEFORE the
     * request goes out, which is where the livelock is prevented rather than
     * merely reported:
     *
     * - retry with the same `writeId` and the **same** bytes ⇒ the server
     *   converges, by contract. No loop.
     * - retry with the same `writeId` and **different** bytes ⇒ refused locally,
     *   immediately, with the verdict that names the fix. No loop.
     * - retry with a fresh `writeId` ⇒ an ordinary CAS attempt. No loop.
     *
     * Those three cases are exhaustive, so a caller that follows the
     * re-read/re-merge/retry remedy cannot end up in a retry that can never
     * succeed — including when the ledger is cold (a fresh process), where this
     * method reports the stale verdict and the NEXT attempt goes through the
     * pre-flight above.
     */
    private fun classifyPreconditionFailure(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        header: PvDocEnvelopeHeader,
        envelope: ByteArray,
        response: Response<Unit>,
    ): PvDocWriteOutcome {
        if (ledger.isReplayWithDifferentBytes(directory.vaultId, ref.docId, header.writeId, envelope)) {
            return PvDocWriteOutcome.WriteIdReplayRefused(header.writeId, detectedLocally = true)
        }
        return PvDocWriteOutcome.PreconditionStale(PvDocEtag.parse(response.headers()[HEADER_ETAG]))
    }

    private fun capProblem(kind: PvDocKind, size: Int): PvDocWriteOutcome.TooLarge? =
        if (size > kind.maxBytes) {
            PvDocWriteOutcome.TooLarge(
                kind = kind,
                limitBytes = kind.maxBytes,
                actualBytes = size,
                refusedBy = PvSizeRefusedBy.CLIENT,
            )
        } else {
            null
        }

    private sealed interface Outgoing {
        data class Ok(val header: PvDocEnvelopeHeader) : Outgoing

        data class Rejected(val reason: String) : Outgoing
    }

    private fun outgoingHeader(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        envelope: ByteArray,
    ): Outgoing {
        val inspection = try {
            inspectPvDocEnvelope(envelope)
        } catch (cause: VaultCryptoError) {
            return Outgoing.Rejected(cause.message ?: "The vault document envelope is malformed.")
        }
        return when (inspection) {
            is PvDocEnvelopeInspection.UpdateRequired -> Outgoing.Rejected(
                "This build cannot write an envelope of format ${inspection.formatVersion}.",
            )

            is PvDocEnvelopeInspection.Supported -> {
                val header = inspection.envelope.header
                headerAddressProblem(directory, ref, header)
                    ?.let { return Outgoing.Rejected(it) }
                Outgoing.Ok(header)
            }
        }
    }

    /**
     * The client-side mirror of the server's own check that the path's `vaultId`
     * and `docId` equal the header's — plus the kind check the size ceiling
     * depends on.
     *
     * Duplicating a server-side validation is usually waste. Here it is not: the
     * refusal it produces names WHICH of the three disagreed, where the server's
     * `400` cannot, and it fires before a mis-addressed document is handed to a
     * store that will accept any bytes it is given.
     */
    private fun headerAddressProblem(
        directory: PvVaultDocDirectory,
        ref: PvDocRef,
        header: PvDocEnvelopeHeader,
    ): String? = when {
        header.vaultId != directory.vaultId ->
            "The document envelope belongs to vault ${header.vaultId}, not ${directory.vaultId}."

        header.docId != ref.docId ->
            "The document envelope is addressed to doc ${header.docId}, not ${ref.docId}."

        header.docKind != ref.kind.wire ->
            "The document envelope claims kind '${header.docKind}' at an address this vault " +
                "gives kind '${ref.kind.wire}'."

        else -> null
    }

    private fun addressReason(directory: PvVaultDocDirectory, ref: PvDocRef): String =
        "Doc ${ref.docId} is not a '${ref.kind.wire}' document of vault ${directory.vaultId}."

    companion object {
        /** `VAULT_CONTENT_TYPE` — the blind store speaks opaque bytes. */
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private const val HEADER_ETAG = "ETag"

        /** Declared on the candidate read-back response by the deployed OpenAPI. */
        private const val HEADER_CANDIDATE_ID = "X-BetterTrack-Vault-Candidate-Id"
        private const val HEADER_CANDIDATE_EXPIRES_AT = "X-BetterTrack-Vault-Candidate-Expires-At"
        private const val HEADER_CANDIDATE_READBACK = "X-BetterTrack-Vault-Candidate-Readback"

        /** A bearer reaching a session-only vault route. */
        private const val CODE_API_KEY_FORBIDDEN = "API_KEY_FORBIDDEN"
    }
}

/**
 * The doc surface of one vault, bound to its [directory].
 *
 * Every method takes a [PvDocRef] the directory produced, so the vault id and the
 * doc id can never come from two different places — which is the shape a mapping
 * table would need in order to exist.
 */
class PvVaultDocs internal constructor(
    private val store: PvBlobStore,
    val directory: PvVaultDocDirectory,
) {

    val vaultId: String get() = directory.vaultId

    /**
     * `GET /vaults/{vaultId}/docs/{docId}` — bytes plus the validator.
     *
     * [ifNoneMatch] makes it a conditional read: a match answers `304` without
     * ciphertext. Optional, and never required for correctness — the app's shared
     * conditional-GET interceptor covers `/search` and `/portfolios/…` only, so
     * nothing caches vault bytes behind this layer's back.
     */
    suspend fun read(ref: PvDocRef, ifNoneMatch: PvDocEtag? = null): PvDocReadOutcome =
        store.readDoc(directory, ref, ifNoneMatch)

    /**
     * `PUT /vaults/{vaultId}/docs/{docId}` — compare-and-swap.
     *
     * [precondition] has no "none": [PvDocPrecondition.CreateOnly] for a doc's
     * first write, [PvDocPrecondition.Replace] with the ETag a read handed back
     * for every write after it.
     */
    suspend fun write(
        ref: PvDocRef,
        precondition: PvDocPrecondition,
        envelope: ByteArray,
    ): PvDocWriteOutcome = store.writeDoc(directory, ref, precondition, envelope)

    /** `GET …/docs/{docId}/history` — the restore picker's index. */
    suspend fun history(
        ref: PvDocRef,
        cursor: Int? = null,
        limit: Int? = null,
    ): BtResult<VaultHistoryListResponse> = store.readDocHistory(directory, ref, cursor, limit)

    /** `GET …/docs/{docId}/history/{version}` — one retained ciphertext, for restore. */
    suspend fun historyVersion(ref: PvDocRef, version: Int): PvDocReadOutcome =
        store.readDocHistoryVersion(directory, ref, version)

    /**
     * `PUT …/media/server-candidate/{transitionId}/docs/{docId}` — stage one doc
     * of a full-document-set transition. Re-staging rotates the `candidateId`.
     */
    suspend fun stageCandidate(
        transitionId: String,
        ref: PvDocRef,
        envelope: ByteArray,
    ): PvCandidateStageOutcome = store.stageServerCandidate(directory, transitionId, ref, envelope)
}
