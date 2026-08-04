package at.bettertrack.app.vault.drive

import at.bettertrack.app.vault.DataHome
import at.bettertrack.app.vault.DataHomeAbsent
import at.bettertrack.app.vault.DataHomeBytes
import at.bettertrack.app.vault.DataHomeConflict
import at.bettertrack.app.vault.DataHomeCorrupt
import at.bettertrack.app.vault.DataHomeCorruptionReason
import at.bettertrack.app.vault.DataHomeFailureCode
import at.bettertrack.app.vault.DataHomeInfo
import at.bettertrack.app.vault.DataHomeInfoResult
import at.bettertrack.app.vault.DataHomeMedium
import at.bettertrack.app.vault.DataHomeOk
import at.bettertrack.app.vault.DataHomeReadResult
import at.bettertrack.app.vault.DataHomeTransport
import at.bettertrack.app.vault.DataHomeTransportFailure
import at.bettertrack.app.vault.DataHomeWriteResult
import at.bettertrack.app.vault.EnvelopeInspection
import at.bettertrack.app.vault.inspectEnvelopeBytes
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Google Drive `appDataFolder` medium — port of
 * `apps/web/src/user/vault/drive/driveDataHome.ts` over the Drive REST v3 files
 * API with **plain OkHttp**.
 *
 * ## No Play Services, no Drive SDK — on purpose
 *
 * The Google Drive Android SDK is deprecated, and `play-services-drive` would
 * drag a large closed dependency in to do four HTTP calls (`files.list`,
 * `files.get`, `files.get?alt=media`, multipart upload). Doing them with the
 * OkHttp client the app already ships means this whole adapter is testable
 * against MockWebServer as a plain JVM unit test — which is what makes the CAS,
 * quota, duplicate and absent-remote behaviours provable **before** the OAuth
 * client exists (see [GoogleAuthProvider]). The only Google-specific piece left
 * is minting a bearer token, and that is behind an interface.
 *
 * ## The approximated compare-and-swap (plan §2.6)
 *
 * Drive has no real CAS. The reference approximates one and this port keeps
 * every step, because each one closes a specific way two devices lose data:
 *
 * 1. Hold the observed `(vaultVersion, formatVersion, headRevisionId)` triple.
 * 2. Re-`GET` the file's metadata **immediately before** the PATCH; any movement
 *    in any of the three ⇒ [DataHomeConflict], which sends the caller to the
 *    merge path. Never force-overwrite.
 * 3. After the upload, re-list and **read the bytes back**, because a PATCH
 *    response describes the revision *this* request created, not necessarily the
 *    one that is current after somebody else's interleaved write.
 *
 * A lost race is safe to simply retry: the §4 merge rules are commutative and
 * idempotent (`VaultMerge`), so re-merging converges.
 *
 * ## Duplicates: detect, use the highest readable, never delete (plan §2.3)
 *
 * Two devices can both create the file before either sees the other's. The
 * reference converges such a set by uploading to one object and deleting the
 * losers behind a chain of re-validation barriers. **This version deliberately
 * stops at detection**: [observeReplicas] reports every replica, [read] returns
 * the highest readable one, and [write] refuses (conflict) rather than picking a
 * CAS target from metadata alone. [DriveReplicaCycle.converge] keeps the
 * reference's method name and returns an explicit deferral. Deleting a user's
 * only copy of an encrypted blob because two clients raced is not a v1 risk
 * worth taking; the plan defers convergence to S5 for exactly that reason.
 *
 * @param apiBase overridable so MockWebServer can stand in for Google.
 */
class DriveDataHome(
    accountId: String,
    private val auth: GoogleAuthProvider,
    private val client: OkHttpClient,
    private val apiBase: HttpUrl = DRIVE_API.toHttpUrl(),
    private val uploadBase: HttpUrl = DRIVE_UPLOAD_API.toHttpUrl(),
    private val isOnline: () -> Boolean = { true },
    private val boundary: () -> String = { "bettertrack-${UUID.randomUUID()}" },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DataHome {

    private val fileName: String = driveVaultFileName(
        accountId.trim().also {
            require(it.isNotEmpty()) { "A Drive vault account scope is required." }
        }
    )

    override val medium: DataHomeMedium = DataHomeMedium.DRIVE

    // ── DataHome ────────────────────────────────────────────────────────────

    /**
     * `read` (driveDataHome.ts:110-118) — the **highest readable** replica.
     *
     * `findFile` orders candidates highest-version-first, so "the first `ok`
     * observation" is exactly plan §2.6 rule 4's "highest readable version wins".
     * When none is readable the first observation is returned so the caller sees
     * the real reason (corrupt / transport) rather than a bare "absent".
     */
    override suspend fun read(): DataHomeReadResult {
        val cycle = observeReplicas()
        return cycle.observations.firstOrNull { it is DataHomeBytes } ?: cycle.observations.first()
    }

    /** `write` (driveDataHome.ts:122-181). */
    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        val outgoing = when (val inspected = inspectEnvelopeBytes(envelope, medium)) {
            is EnvelopeInspection.Unreadable -> return inspected.corrupt
            is EnvelopeInspection.Readable -> inspected.info
        }
        if (ifVersion != null && outgoing.version <= ifVersion) {
            return corrupt(
                envelope,
                outgoing.version,
                DataHomeCorruptionReason.CORRUPT_BYTES,
                "The Drive vault version must advance its compare-and-swap version.",
            )
        }

        val observed = when (val found = findFile()) {
            is DriveFileResult.Failure -> return transport(found.failure)
            is DriveFileResult.Corrupt -> return found.result
            is DriveFileResult.Absent ->
                // Absent remote + a CAS token means the file the caller reasoned
                // about is gone. That is a conflict, NOT a licence to wipe: plan
                // §4.4 requires the caller to re-create at the local version, and
                // `currentVersion = null` is precisely the signal that says so.
                return if (ifVersion != null) {
                    DataHomeConflict(medium, null)
                } else {
                    upload(envelope, outgoing, null)
                }

            is DriveFileResult.Ok -> found
        }

        // A duplicate set is not one CAS target — see the class doc.
        if (observed.files.size > 1) return DataHomeConflict(medium, observed.file.version)
        if (ifVersion == null || observed.file.version != ifVersion) {
            return DataHomeConflict(medium, observed.file.version)
        }

        // Step 2 of the approximated CAS: re-read metadata immediately before the
        // update and refuse on ANY movement.
        val refreshed = when (val result = getFile(observed.file.id)) {
            is DriveFileResult.Failure -> return transport(result.failure)
            is DriveFileResult.Corrupt -> return result.result
            is DriveFileResult.Absent -> return DataHomeConflict(medium, null)
            is DriveFileResult.Ok -> result.file
        }
        if (refreshed.version != observed.file.version ||
            refreshed.formatVersion != observed.file.formatVersion ||
            refreshed.headRevisionId != observed.file.headRevisionId
        ) {
            return DataHomeConflict(medium, refreshed.version)
        }
        return upload(envelope, outgoing, observed.file.id)
    }

    /** `info` (driveDataHome.ts:183-186). */
    override suspend fun info(): DataHomeInfoResult = when (val result = read()) {
        is DataHomeBytes -> DataHomeOk(medium, result.info)
        is DataHomeAbsent -> result
        is DataHomeCorrupt -> result
        is DataHomeTransport -> result
    }

    /**
     * Removes the vault object from `appDataFolder` — the "remove the Drive
     * medium" half of plan §1.4 row 4.
     *
     * **Best effort, and honest about it.** `false` means the bytes are still in
     * the user's Drive, and the caller must say so out loud (plan §5 rule 2):
     * this is the user's own ciphertext in the user's own storage, and telling
     * them a copy is gone when it is not is exactly the kind of quiet
     * mis-statement that makes a privacy feature worthless.
     *
     * An absent file counts as deleted. Nothing here throws — a failure to reach
     * Drive is an ordinary outcome, not an exception.
     */
    suspend fun delete(): Boolean {
        val target = when (val found = findFile()) {
            is DriveFileResult.Absent -> return true
            is DriveFileResult.Ok -> found.files
            else -> return false
        }
        // Every replica, not just the winner: leaving the duplicates behind would
        // mean "removed" was true of one object and false of the data.
        var allGone = true
        for (file in target) {
            val url = apiBase.newBuilder()
                .addPathSegment("files")
                .addPathSegment(file.id)
                .build()
            val response = when (val fetched = driveFetch(Request.Builder().url(url).delete().build())) {
                is Fetched.Failure -> {
                    allGone = false
                    continue
                }

                is Fetched.Ok -> fetched.response
            }
            // 404 = someone else already removed it, which is the state we wanted.
            if (!response.isSuccessful && response.code != 404) allGone = false
        }
        return allGone
    }

    // ── Replica observation (detection only — see the class doc) ────────────

    /** `observeReplicas` (driveDataHome.ts:188-212). */
    suspend fun observeReplicas(): DriveReplicaCycle = when (val found = findFile()) {
        is DriveFileResult.Absent -> DriveReplicaCycle(listOf(DataHomeAbsent(medium)), 0)
        is DriveFileResult.Failure -> DriveReplicaCycle(listOf(transport(found.failure)), 0)
        is DriveFileResult.Corrupt -> DriveReplicaCycle(listOf(found.result), 1)
        is DriveFileResult.Ok -> DriveReplicaCycle(found.files.map { download(it) }, found.files.size)
    }

    // ── Drive plumbing ──────────────────────────────────────────────────────

    /** `findFile` (driveDataHome.ts:307-372). */
    private suspend fun findFile(): DriveFileResult {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addQueryParameter("spaces", APPDATA_SPACE)
            .addQueryParameter("q", "name = '$fileName' and trashed = false")
            .addQueryParameter("fields", "files($FILE_FIELDS)")
            .addQueryParameter("pageSize", DUPLICATE_SCAN_LIMIT.toString())
            .build()

        val response = when (val fetched = driveFetch(Request.Builder().url(url).get().build())) {
            is Fetched.Failure -> return DriveFileResult.Failure(fetched.failure)
            is Fetched.Ok -> fetched.response
        }
        if (!response.isSuccessful) {
            return DriveFileResult.Failure(httpFailure(response, "Drive appdata lookup failed."))
        }

        val payload = response.json()
            ?: return DriveFileResult.Failure(
                DataHomeTransportFailure(
                    "Drive appdata lookup returned invalid JSON.",
                    code = DataHomeFailureCode.API_FAILURE,
                )
            )
        val files = payload["files"] as? JsonArray
            ?: return malformedMetadata("Drive appdata contains invalid vault metadata.")
        if (files.isEmpty()) return DriveFileResult.Absent

        val validated = ArrayList<ValidDriveFile>(files.size)
        for (file in files) {
            when (val result = validateFile(file)) {
                is DriveFileResult.Ok -> validated += result.file
                else -> return result
            }
        }
        val ordered = validated.sortedWith(::compareDriveFiles)
        return DriveFileResult.Ok(ordered.first(), ordered)
    }

    /** `getFile` (driveDataHome.ts:374-399). */
    private suspend fun getFile(id: String): DriveFileResult {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(id)
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val response = when (val fetched = driveFetch(Request.Builder().url(url).get().build())) {
            is Fetched.Failure -> return DriveFileResult.Failure(fetched.failure)
            is Fetched.Ok -> fetched.response
        }
        if (response.code == 404) return DriveFileResult.Absent
        if (!response.isSuccessful) {
            return DriveFileResult.Failure(httpFailure(response, "Drive metadata refresh failed."))
        }
        val payload = response.json()
            ?: return DriveFileResult.Failure(
                DataHomeTransportFailure(
                    "Drive metadata refresh returned invalid JSON.",
                    code = DataHomeFailureCode.API_FAILURE,
                )
            )
        return validateFile(payload)
    }

    /** `download` (driveDataHome.ts:401-434). */
    private suspend fun download(file: ValidDriveFile): DataHomeReadResult {
        val url = apiBase.newBuilder()
            .addPathSegment("files")
            .addPathSegment(file.id)
            .addQueryParameter("alt", "media")
            .build()
        val response = when (val fetched = driveFetch(Request.Builder().url(url).get().build())) {
            is Fetched.Failure -> return transport(fetched.failure)
            is Fetched.Ok -> fetched.response
        }
        if (response.code == 404) return DataHomeAbsent(medium)
        if (!response.isSuccessful) {
            return transport(httpFailure(response, "Drive vault download failed."))
        }

        val envelope = response.body
        // `inspectEnvelope` (driveDataHome.ts:965-981): the metadata Drive
        // advertises must AGREE with the authenticated envelope header. A file
        // whose appProperties claim a version its bytes do not is not usable as a
        // CAS target, however well-formed each half looks alone.
        val inspected = inspectEnvelopeBytes(envelope, medium)
        if (inspected is EnvelopeInspection.Unreadable) return inspected.corrupt
        val info = (inspected as EnvelopeInspection.Readable).info
        if (info.version != file.version || file.formatVersion != FORMAT_VERSION) {
            return corrupt(
                envelope,
                file.version,
                DataHomeCorruptionReason.VERSION_MISMATCH,
                "Drive appProperties do not match the opaque vault envelope.",
            )
        }
        return DataHomeBytes(medium, envelope, info.copy(updatedAt = file.updatedAt ?: info.updatedAt))
    }

    /** `upload` (driveDataHome.ts:436-497) — send, re-list, read back, compare. */
    private suspend fun upload(
        envelope: ByteArray,
        outgoing: DataHomeInfo,
        fileId: String?,
    ): DataHomeWriteResult {
        val acknowledged = when (val sent = sendUpload(envelope, outgoing, fileId)) {
            is DriveFileResult.Failure -> return transport(sent.failure)
            is DriveFileResult.Corrupt -> return sent.result
            is DriveFileResult.Absent -> return transport(
                DataHomeTransportFailure(
                    "Drive upload returned no file metadata.",
                    code = DataHomeFailureCode.API_FAILURE,
                    indeterminate = true,
                )
            )

            is DriveFileResult.Ok -> sent.file
        }

        val confirmed = when (val result = findFile()) {
            is DriveFileResult.Failure ->
                return transport(result.failure.copy(indeterminate = true))

            is DriveFileResult.Corrupt -> return result.result
            is DriveFileResult.Absent -> return transport(
                DataHomeTransportFailure(
                    "Drive could not confirm the written vault file.",
                    code = DataHomeFailureCode.API_FAILURE,
                    indeterminate = true,
                )
            )

            is DriveFileResult.Ok -> result
        }
        if (confirmed.files.size > 1 ||
            confirmed.file.id != acknowledged.id ||
            confirmed.file.version != outgoing.version
        ) {
            return DataHomeConflict(medium, confirmed.file.version)
        }

        // The verified round trip plan §1.4 requires of every vault write: the
        // bytes Drive hands back must be the bytes we sent, or this was somebody
        // else's revision wearing our version number.
        return when (val roundTrip = download(confirmed.file)) {
            is DataHomeBytes ->
                if (roundTrip.envelope.contentEquals(envelope)) {
                    DataHomeOk(medium, roundTrip.info)
                } else {
                    DataHomeConflict(medium, roundTrip.info.version)
                }

            is DataHomeCorrupt -> roundTrip
            is DataHomeTransport -> transport(roundTrip.failure.copy(indeterminate = true))
            is DataHomeAbsent -> transport(
                DataHomeTransportFailure(
                    "Drive could not read back the written vault file.",
                    code = DataHomeFailureCode.API_FAILURE,
                    indeterminate = true,
                )
            )
        }
    }

    /** `sendUpload` (driveDataHome.ts:499-562) — `uploadType=multipart`. */
    private suspend fun sendUpload(
        envelope: ByteArray,
        outgoing: DataHomeInfo,
        fileId: String?,
    ): DriveFileResult {
        val marker = boundary()
        val metadata = JsonObject(
            buildMap {
                if (fileId == null) {
                    put("name", JsonPrimitive(fileName))
                    put("parents", JsonArray(listOf(JsonPrimitive(APPDATA_SPACE))))
                }
                put(
                    "appProperties",
                    JsonObject(
                        linkedMapOf(
                            "vaultVersion" to JsonPrimitive(outgoing.version.toString()),
                            "formatVersion" to JsonPrimitive(FORMAT_VERSION.toString()),
                        )
                    ),
                )
            }
        )
        val body = multipartRelated(marker, DRIVE_JSON.encodeToString(JsonObject.serializer(), metadata), envelope)

        val url = uploadBase.newBuilder()
            .addPathSegment("files")
            .apply { if (fileId != null) addPathSegment(fileId) }
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .method(
                if (fileId == null) "POST" else "PATCH",
                body.toRequestBody("multipart/related; boundary=$marker".toMediaType()),
            )
            .build()

        val response = when (val fetched = driveFetch(request)) {
            is Fetched.Failure -> return DriveFileResult.Failure(fetched.failure.copy(indeterminate = true))
            is Fetched.Ok -> fetched.response
        }
        if (!response.isSuccessful) {
            return DriveFileResult.Failure(
                httpFailure(response, "Drive vault upload failed.", indeterminate = true)
            )
        }
        val payload = response.json()
            ?: return DriveFileResult.Failure(
                DataHomeTransportFailure(
                    "Drive upload returned invalid metadata.",
                    code = DataHomeFailureCode.API_FAILURE,
                    indeterminate = true,
                )
            )
        val acknowledged = validateFile(payload)
        if (acknowledged !is DriveFileResult.Ok) return acknowledged
        if (acknowledged.file.version != outgoing.version) {
            return DriveFileResult.Corrupt(
                corrupt(
                    envelope,
                    acknowledged.file.version,
                    DataHomeCorruptionReason.VERSION_MISMATCH,
                    "Drive acknowledged a different vault version.",
                )
            )
        }
        return acknowledged
    }

    /**
     * The multipart/related body, byte for byte as the reference's `Blob`
     * (driveDataHome.ts:512-518). CRLF placement is part of the format, so it is
     * assembled explicitly rather than through OkHttp's `MultipartBody`, which
     * would emit its own `Content-Disposition` headers Drive does not expect here.
     */
    private fun multipartRelated(marker: String, metadataJson: String, envelope: ByteArray): ByteArray {
        val head = (
            "--$marker\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                metadataJson +
                "\r\n--$marker\r\nContent-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$marker--".toByteArray(Charsets.UTF_8)
        return head + envelope + tail
    }

    /** `validateFile` (driveDataHome.ts:875-914). */
    private fun validateFile(value: kotlinx.serialization.json.JsonElement): DriveFileResult {
        val file = value as? JsonObject
            ?: return malformedMetadata("Drive returned a non-object vault file.")
        val appProperties = file["appProperties"] as? JsonObject
        val version = intOrNull(appProperties?.get("vaultVersion"))
        val formatVersion = intOrNull(appProperties?.get("formatVersion"))
        val sizeBytes = longOrNull(file["size"]) ?: 0L
        val id = stringOrNull(file["id"])
        val headRevisionId = stringOrNull(file["headRevisionId"])

        if (id.isNullOrEmpty() ||
            stringOrNull(file["name"]) != fileName ||
            version == null ||
            version < 1 ||
            formatVersion != FORMAT_VERSION ||
            sizeBytes <= 0 ||
            headRevisionId.isNullOrEmpty()
        ) {
            return malformedMetadata("Drive vault appProperties or revision metadata is malformed.")
        }

        val valid = ValidDriveFile(
            id = id,
            version = version,
            formatVersion = formatVersion,
            sizeBytes = sizeBytes,
            updatedAt = stringOrNull(file["modifiedTime"])?.takeIf { isParseableInstant(it) },
            headRevisionId = headRevisionId,
        )
        return DriveFileResult.Ok(valid, listOf(valid))
    }

    /**
     * `driveFetch` (driveDataHome.ts:807-865) — the one place a token is used
     * and the one place HTTP status becomes a typed recovery state.
     */
    private suspend fun driveFetch(request: Request): Fetched {
        if (!isOnline()) {
            return Fetched.Failure(
                DataHomeTransportFailure("Google Drive is offline.", code = DataHomeFailureCode.OFFLINE)
            )
        }
        val token = auth.accessToken()
            ?: return Fetched.Failure(
                DataHomeTransportFailure(
                    "Sign in to Google to sync this vault.",
                    code = DataHomeFailureCode.CONSENT_REQUIRED,
                )
            )

        val mutating = request.method == "POST" || request.method == "PATCH"
        val response = try {
            withContext(ioDispatcher) {
                client.newCall(request.newBuilder().header("Authorization", "Bearer $token").build())
                    .execute()
                    .use { raw -> DriveResponse(raw.code, raw.body?.bytes() ?: ByteArray(0)) }
            }
        } catch (cause: IOException) {
            val offline = !isOnline()
            return Fetched.Failure(
                DataHomeTransportFailure(
                    message = if (offline) "Google Drive is offline." else "Google Drive could not be reached.",
                    code = if (offline) DataHomeFailureCode.OFFLINE else DataHomeFailureCode.API_FAILURE,
                    // A lost response on a mutating request means the write may
                    // well have landed. Reporting it as a clean failure would
                    // invite a blind retry that double-writes.
                    indeterminate = mutating,
                    cause = cause,
                )
            )
        }

        if (response.code == 401) {
            auth.markExpired()
            return Fetched.Failure(
                DataHomeTransportFailure(
                    "The Google Drive access token expired.",
                    code = DataHomeFailureCode.TOKEN_EXPIRED,
                    httpStatus = 401,
                )
            )
        }
        if (response.code == 403) {
            // App-side split the reference does not make: a FULL Drive and a
            // DENIED scope are different sentences and different recoveries
            // (plan §4.4). Drive distinguishes them only in the error body.
            val quota = response.hasReason(QUOTA_REASON)
            return Fetched.Failure(
                DataHomeTransportFailure(
                    message = if (quota) {
                        "Your Google Drive is full — changes are saved on this device."
                    } else {
                        "Google Drive appdata access was denied."
                    },
                    code = if (quota) {
                        DataHomeFailureCode.QUOTA_EXCEEDED
                    } else {
                        DataHomeFailureCode.PERMISSION_DENIED
                    },
                    httpStatus = 403,
                    indeterminate = false,
                )
            )
        }
        return Fetched.Ok(response)
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private fun transport(failure: DataHomeTransportFailure) = DataHomeTransport(medium, failure)

    private fun corrupt(
        envelope: ByteArray?,
        version: Int?,
        reason: DataHomeCorruptionReason,
        message: String,
    ) = DataHomeCorrupt(medium, envelope, version, null, reason, message)

    private fun malformedMetadata(message: String): DriveFileResult = DriveFileResult.Corrupt(
        corrupt(null, null, DataHomeCorruptionReason.MALFORMED_METADATA, message)
    )

    private fun httpFailure(
        response: DriveResponse,
        message: String,
        indeterminate: Boolean = false,
    ) = DataHomeTransportFailure(
        message = message,
        code = DataHomeFailureCode.API_FAILURE,
        httpStatus = response.code,
        indeterminate = indeterminate,
    )

    private sealed interface Fetched {
        data class Ok(val response: DriveResponse) : Fetched

        data class Failure(val failure: DataHomeTransportFailure) : Fetched
    }

    private sealed interface DriveFileResult {
        data class Ok(val file: ValidDriveFile, val files: List<ValidDriveFile>) : DriveFileResult

        data object Absent : DriveFileResult

        data class Corrupt(val result: DataHomeCorrupt) : DriveFileResult

        data class Failure(val failure: DataHomeTransportFailure) : DriveFileResult
    }

    companion object {
        /** `DRIVE_API` (driveDataHome.ts:17). */
        const val DRIVE_API: String = "https://www.googleapis.com/drive/v3"

        /** `DRIVE_UPLOAD_API` (driveDataHome.ts:18). */
        const val DRIVE_UPLOAD_API: String = "https://www.googleapis.com/upload/drive/v3"

        /** `FILE_FIELDS` (driveDataHome.ts:19) — plan §2.3's exact field list. */
        const val FILE_FIELDS: String = "id,name,size,modifiedTime,headRevisionId,appProperties"

        /** `DUPLICATE_SCAN_LIMIT` (driveDataHome.ts:20). */
        const val DUPLICATE_SCAN_LIMIT: Int = 100

        const val APPDATA_SPACE: String = "appDataFolder"

        /** Drive's machine-readable "this Drive is full" reason. */
        const val QUOTA_REASON: String = "storageQuotaExceeded"

        /** `VAULT_FORMAT_VERSION` — the app's own constant, not re-declared. */
        private val FORMAT_VERSION: Int = at.bettertrack.app.vault.VaultContract.FORMAT_VERSION

        private val DRIVE_JSON = Json { encodeDefaults = true }
    }
}

/** `ValidDriveFile` (driveDataHome.ts:36-43). */
internal data class ValidDriveFile(
    val id: String,
    val version: Int,
    val formatVersion: Int,
    val sizeBytes: Long,
    val updatedAt: String?,
    val headRevisionId: String,
)

/**
 * `DriveReplicaCycle` (driveDataHome.ts:55-73) — detection only in v1.
 *
 * [observations] is in deterministic metadata order (highest version first), so
 * `observations.first()` is the candidate plan §2.6 rule 4 calls "the highest
 * readable version" once the readable ones are filtered.
 */
class DriveReplicaCycle(
    val observations: List<DataHomeReadResult>,
    /** How many same-name Drive objects exist. `> 1` is the duplicate signal. */
    val replicaCount: Int,
) {
    val hasDuplicates: Boolean get() = replicaCount > 1

    /**
     * Kept by name from the reference so S5 can fill it in without a rename
     * rippling through callers. Convergence deletes user data and is deferred
     * (plan §2.3); saying so explicitly beats a method that silently does nothing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun converge(envelope: ByteArray): DataHomeWriteResult = DataHomeTransport(
        DataHomeMedium.DRIVE,
        DataHomeTransportFailure(
            "Drive duplicate convergence is not available in this version.",
            code = DataHomeFailureCode.API_FAILURE,
        ),
    )
}

/** One fully-read Drive response — the body is drained so no connection leaks. */
internal class DriveResponse(val code: Int, val body: ByteArray) {
    val isSuccessful: Boolean get() = code in 200..299

    fun json(): JsonObject? = try {
        Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Drive error bodies are `{error:{errors:[{reason,…}],…}}`; `status` also carries it. */
    fun hasReason(reason: String): Boolean {
        val error = json()?.get("error") as? JsonObject ?: return false
        val errors = error["errors"] as? JsonArray
        val inErrors = errors?.any { (it as? JsonObject)?.get("reason")?.let(::stringOrNull) == reason } == true
        return inErrors || stringOrNull(error["status"]) == reason || stringOrNull(error["reason"]) == reason
    }
}

/** `compareDriveFiles` (driveDataHome.ts:838-842) — highest version, newest, then id. */
internal fun compareDriveFiles(left: ValidDriveFile, right: ValidDriveFile): Int {
    if (left.version != right.version) return right.version - left.version
    val updated = (right.updatedAt ?: "").compareTo(left.updatedAt ?: "")
    return if (updated != 0) updated else left.id.compareTo(right.id)
}

private fun stringOrNull(element: kotlinx.serialization.json.JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

/** `Number(x)` + `Number.isInteger` — a JSON string like `"3"` is a number in JS. */
private fun intOrNull(element: kotlinx.serialization.json.JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value != Math.floor(value)) return null
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
    return value.toInt()
}

private fun longOrNull(element: kotlinx.serialization.json.JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value != Math.floor(value)) return null
    return value.toLong()
}

/** `!Number.isNaN(Date.parse(modifiedTime))` — a timestamp Drive can actually mean. */
private fun isParseableInstant(value: String): Boolean = try {
    java.time.OffsetDateTime.parse(value)
    true
} catch (_: java.time.format.DateTimeParseException) {
    false
}
