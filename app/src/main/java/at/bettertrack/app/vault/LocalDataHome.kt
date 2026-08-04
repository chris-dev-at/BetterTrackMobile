package at.bettertrack.app.vault

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The on-device encrypted vault cache — port of
 * `apps/web/src/user/vault/localDataHome.ts` (vendored at
 * `tools/domain-vectors/vendor/web-vault/localDataHome.ts`).
 *
 * This is the medium that makes Drive mode work **in airplane mode**: every
 * write lands here first and succeeds unconditionally, and the Drive push is a
 * later, coalesced, failable step (plan §2.4, §2.6 "Reads never wait on Drive").
 * It holds ciphertext only — no vault key, no plaintext, no key-derivation
 * material — so it is exactly as safe at rest as the Drive copy.
 *
 * ## Why one JSON file and not two files or a Room table
 *
 * The reference keeps `{envelope, version, updatedAt, pendingRemote,
 * lastKnownGood*}` as **one IndexedDB record** and mutates it inside one
 * transaction, so a compare-and-swap can never observe a half-updated record.
 * The same invariant on a filesystem needs the whole record in a single file
 * replaced by a single `rename(2)` — split the envelope into its own blob and a
 * crash between the two writes leaves metadata describing bytes that are not
 * there. Base64 costs ~33 % on a cache blob that is kilobytes; a torn record
 * costs the user their vault.
 *
 * Room was the other candidate and was rejected for a smaller reason: this class
 * must stay Android-free so the [DataHome] contract suite runs on both media as
 * a plain JVM test (there is no Robolectric in this project).
 *
 * @param directory an app-private directory; the caller supplies
 *   `context.filesDir` in production and a temp dir in tests.
 * @param scope the vault identity, so two accounts never share a record.
 */
class LocalDataHome(
    private val directory: File,
    private val scope: String,
) : DataHome {

    override val medium: DataHomeMedium = DataHomeMedium.LOCAL

    /** `RECORD_VERSION` (localDataHome.ts:19). */
    private val recordFile: File get() = File(directory, "vault-$scope.json")

    private val mutex = Mutex()

    // ── DataHome ────────────────────────────────────────────────────────────

    override suspend fun read(): DataHomeReadResult = mutex.withLock {
        when (val loaded = loadRecord()) {
            is Loaded.None -> DataHomeAbsent(medium)
            is Loaded.Failure -> loaded.result
            is Loaded.Record -> readCurrent(loaded.record)
        }
    }

    /**
     * `write` (localDataHome.ts:82-113).
     *
     * The `parsed.version <= ifVersion` guard is not a nicety: a local write that
     * does not advance the version would make the record's own CAS token
     * ambiguous, and the next Drive push would compare against a version that
     * two different documents share.
     */
    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        val inspected = inspectEnvelopeBytes(envelope, medium)
        if (inspected is EnvelopeInspection.Unreadable) return inspected.corrupt
        val info = (inspected as EnvelopeInspection.Readable).info
        if (ifVersion != null && info.version <= ifVersion) {
            return DataHomeCorrupt(
                medium = medium,
                envelope = envelope,
                version = info.version,
                updatedAt = null,
                reason = DataHomeCorruptionReason.VERSION_MISMATCH,
                message = "A local vault write must advance the expected version.",
            )
        }

        val bytes = envelope.copyOf()
        return mutex.withLock {
            compareAndSwap(ifVersion, "Could not write the encrypted local vault cache.") { current ->
                LocalVaultRecord(
                    envelope = bytes,
                    version = info.version,
                    updatedAt = info.updatedAt ?: nowIso(),
                    lastKnownGood = current?.lastKnownGood,
                    lastKnownGoodVersion = current?.lastKnownGoodVersion,
                    lastKnownGoodUpdatedAt = current?.lastKnownGoodUpdatedAt,
                    pendingRemote = true,
                )
            }
        }.mapOk { DataHomeOk(medium, info.copy(pendingRemote = true)) }
    }

    override suspend fun info(): DataHomeInfoResult = when (val result = read()) {
        is DataHomeBytes -> DataHomeOk(medium, result.info)
        is DataHomeAbsent -> result
        is DataHomeCorrupt -> result
        is DataHomeTransport -> result
    }

    // ── Local-only extensions (localDataHome.ts:53-68) ──────────────────────

    /**
     * `markLastKnownGood` (localDataHome.ts:123-166) — promote bytes the CALLER
     * has already decrypted and validated to the rollback snapshot.
     *
     * The snapshot is the "corrupt bytes are never silently discarded" rule's
     * other half (plan §2.6 rule 4): if a later write or a Drive merge produces
     * an envelope this device cannot open, this is the copy it falls back to.
     * It is deliberately *not* promoted automatically on write — only a caller
     * that has actually decrypted the bytes may vouch for them.
     */
    suspend fun markLastKnownGood(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        if (ifVersion == null) return DataHomeConflict(medium, currentVersionOrNull())
        val inspected = inspectEnvelopeBytes(envelope, medium, metadataVersion = ifVersion)
        if (inspected is EnvelopeInspection.Unreadable) return inspected.corrupt
        val info = (inspected as EnvelopeInspection.Readable).info
        if (info.version != ifVersion) {
            return DataHomeCorrupt(
                medium = medium,
                envelope = envelope,
                version = info.version,
                updatedAt = null,
                reason = DataHomeCorruptionReason.VERSION_MISMATCH,
                message = "Last-known-good bytes do not match the expected local version.",
            )
        }

        val bytes = envelope.copyOf()
        var pendingRemote = false
        return mutex.withLock {
            compareAndSwap(ifVersion, "Could not preserve the encrypted rollback snapshot.") { current ->
                if (current == null || !equalBytes(current.envelope, bytes)) {
                    throw IOException("Last-known-good bytes are not the current local candidate.")
                }
                pendingRemote = current.pendingRemote
                current.copy(
                    lastKnownGood = bytes,
                    lastKnownGoodVersion = info.version,
                    lastKnownGoodUpdatedAt = info.updatedAt ?: current.updatedAt,
                )
            }
        }.mapOk { DataHomeOk(medium, info.copy(pendingRemote = pendingRemote)) }
    }

    /**
     * `setPendingRemote` (localDataHome.ts:168-192) — the durable "Drive has
     * acknowledged this exact version" bit that drives the sync chip.
     *
     * Versioned on purpose: a stale worker must not be able to clear the pending
     * flag of a *newer* local candidate it has never pushed.
     */
    suspend fun setPendingRemote(pending: Boolean, ifVersion: Int?): DataHomeWriteResult {
        if (ifVersion == null) return DataHomeConflict(medium, currentVersionOrNull())
        var info: DataHomeInfo? = null
        return mutex.withLock {
            compareAndSwap(ifVersion, "Could not update local vault acknowledgement state.") { current ->
                if (current == null) throw IOException("No encrypted local cache exists.")
                info = current.toInfo()
                current.copy(pendingRemote = pending)
            }
        }.mapOk { DataHomeOk(medium, info!!.copy(pendingRemote = pending)) }
    }

    /** `readLastKnownGood` (localDataHome.ts:194-224). */
    suspend fun readLastKnownGood(): DataHomeReadResult = mutex.withLock {
        val loaded = loadRecord()
        if (loaded is Loaded.None) return@withLock DataHomeAbsent(medium)
        if (loaded is Loaded.Failure) return@withLock loaded.result
        val record = (loaded as Loaded.Record).record
        val rollbackEnvelope = record.lastKnownGood
        val rollbackVersion = record.lastKnownGoodVersion
        val rollbackUpdatedAt = record.lastKnownGoodUpdatedAt
        if (rollbackEnvelope == null || rollbackVersion == null || rollbackUpdatedAt == null) {
            return@withLock DataHomeAbsent(medium)
        }

        val inspected = inspectEnvelopeBytes(rollbackEnvelope, medium, rollbackVersion, rollbackUpdatedAt)
        if (inspected is EnvelopeInspection.Unreadable) {
            return@withLock inspected.corrupt.withUpdatedAt(rollbackUpdatedAt)
        }
        val info = (inspected as EnvelopeInspection.Readable).info
        if (info.version != rollbackVersion) {
            return@withLock DataHomeCorrupt(
                medium = medium,
                envelope = rollbackEnvelope,
                version = rollbackVersion,
                updatedAt = rollbackUpdatedAt,
                reason = DataHomeCorruptionReason.VERSION_MISMATCH,
                message = "Last-known-good metadata does not match its encrypted envelope.",
            )
        }
        DataHomeBytes(medium, rollbackEnvelope.copyOf(), info.copy(pendingRemote = false))
    }

    /** `clearLocalVaultScope` (localDataHome.ts:585-604) — remove this scope only. */
    suspend fun clear(): Unit = mutex.withLock { recordFile.delete() }

    // ── Internals ───────────────────────────────────────────────────────────

    /** `readCurrent` (localDataHome.ts:288-305). */
    private fun readCurrent(record: LocalVaultRecord): DataHomeReadResult {
        val inspected = inspectEnvelopeBytes(record.envelope, medium, record.version, record.updatedAt)
        if (inspected is EnvelopeInspection.Unreadable) {
            return inspected.corrupt.withUpdatedAt(record.updatedAt)
        }
        val info = (inspected as EnvelopeInspection.Readable).info
        if (info.version != record.version) {
            return DataHomeCorrupt(
                medium = medium,
                envelope = record.envelope,
                version = record.version,
                updatedAt = null,
                reason = DataHomeCorruptionReason.VERSION_MISMATCH,
                message = "Local sync metadata does not match the encrypted envelope version.",
            )
        }
        return DataHomeBytes(medium, record.envelope.copyOf(), info.copy(pendingRemote = record.pendingRemote))
    }

    /** `compareAndSwap` (localDataHome.ts:307-336). Caller holds [mutex]. */
    private fun compareAndSwap(
        ifVersion: Int?,
        failureMessage: String,
        build: (LocalVaultRecord?) -> LocalVaultRecord,
    ): DataHomeWriteResult = try {
        val current = when (val loaded = loadRecord()) {
            is Loaded.Record -> loaded.record
            // A record the reader rejects is not a CAS target the writer may
            // match: `currentVersion` is reported as whatever version survived
            // parsing, exactly as `safeVersion` does in the reference.
            is Loaded.Failure -> return DataHomeConflict(medium, loaded.safeVersion)
            is Loaded.None -> null
        }
        if (current?.version != ifVersion) {
            DataHomeConflict(medium, current?.version)
        } else {
            writeAtomically(build(current))
            DataHomeOk(medium, DataHomeInfo(medium, ifVersion ?: 1, 0, null))
        }
    } catch (cause: IOException) {
        DataHomeTransport(medium, DataHomeTransportFailure(failureMessage, cause = cause))
    }

    /**
     * `rename(2)` over a fully-flushed temp file: the record either is the old
     * one or is the whole new one, never a prefix of it. `fd.sync()` before the
     * rename is what makes that true across a power loss and not merely across a
     * process crash.
     */
    private fun writeAtomically(record: LocalVaultRecord) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the local vault directory.")
        }
        val target = recordFile
        val temp = File(directory, "${target.name}.tmp")
        val bytes = utf8(jsJsonStringify(record.toJson()))
        FileOutputStream(temp).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not replace the local vault cache record.")
        }
    }

    private sealed interface Loaded {
        data object None : Loaded

        data class Record(val record: LocalVaultRecord) : Loaded

        data class Failure(val result: DataHomeReadResult, val safeVersion: Int?) : Loaded
    }

    /** `readRecord` + `isRecord` + `malformedRecord` (localDataHome.ts:262-286, 520-534). */
    private fun loadRecord(): Loaded {
        val file = recordFile
        if (!file.exists()) return Loaded.None
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (cause: IOException) {
            return Loaded.Failure(
                DataHomeTransport(
                    medium,
                    DataHomeTransportFailure("Could not read the encrypted local vault cache.", cause = cause),
                ),
                null,
            )
        }
        val element: JsonElement = try {
            VAULT_JSON.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return malformed(null, null)
        }
        val obj = element as? JsonObject ?: return malformed(null, null)
        return when (val parsed = LocalVaultRecord.parse(obj)) {
            null -> malformed(obj, LocalVaultRecord.safeVersion(obj))
            else -> Loaded.Record(parsed)
        }
    }

    private fun malformed(obj: JsonObject?, version: Int?): Loaded.Failure = Loaded.Failure(
        DataHomeCorrupt(
            medium = medium,
            envelope = obj?.let { LocalVaultRecord.envelopeOrNull(it) },
            version = version,
            updatedAt = null,
            reason = DataHomeCorruptionReason.INVALID_RESPONSE,
            message = "The local vault cache record is malformed.",
        ),
        version,
    )

    private fun currentVersionOrNull(): Int? = when (val loaded = loadRecord()) {
        is Loaded.Record -> loaded.record.version
        is Loaded.Failure -> loaded.safeVersion
        is Loaded.None -> null
    }

    private fun nowIso(): String = java.time.Instant.now()
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        .toString()

    private inline fun DataHomeWriteResult.mapOk(build: () -> DataHomeWriteResult): DataHomeWriteResult =
        if (this is DataHomeOk) build() else this
}

/**
 * `LocalVaultRecord` (localDataHome.ts:25-35) — the complete persisted shape.
 *
 * Every byte field is an encrypted envelope; everything else is non-sensitive
 * synchronization metadata.
 */
internal data class LocalVaultRecord(
    val envelope: ByteArray,
    val version: Int,
    val updatedAt: String,
    val lastKnownGood: ByteArray?,
    val lastKnownGoodVersion: Int?,
    val lastKnownGoodUpdatedAt: String?,
    val pendingRemote: Boolean,
) {
    fun toInfo(): DataHomeInfo = DataHomeInfo(
        medium = DataHomeMedium.LOCAL,
        version = version,
        sizeBytes = envelope.size.toLong(),
        updatedAt = updatedAt,
        pendingRemote = pendingRemote,
    )

    fun toJson(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "recordVersion" to JsonPrimitive(RECORD_VERSION),
            "envelope" to JsonPrimitive(bytesToBase64(envelope)),
            "version" to JsonPrimitive(version),
            "updatedAt" to JsonPrimitive(updatedAt),
            "pendingRemote" to JsonPrimitive(pendingRemote),
        )
        // Absent stays absent: a half-present rollback tuple is what
        // `lastKnownGoodTuple` reports as corrupt, so all three move together.
        if (lastKnownGood != null && lastKnownGoodVersion != null && lastKnownGoodUpdatedAt != null) {
            members["lastKnownGood"] = JsonPrimitive(bytesToBase64(lastKnownGood))
            members["lastKnownGoodVersion"] = JsonPrimitive(lastKnownGoodVersion)
            members["lastKnownGoodUpdatedAt"] = JsonPrimitive(lastKnownGoodUpdatedAt)
        }
        return JsonObject(members)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is LocalVaultRecord &&
                envelope.contentEquals(other.envelope) &&
                version == other.version &&
                updatedAt == other.updatedAt &&
                (lastKnownGood?.contentEquals(other.lastKnownGood ?: ByteArray(0))
                    ?: (other.lastKnownGood == null)) &&
                lastKnownGoodVersion == other.lastKnownGoodVersion &&
                lastKnownGoodUpdatedAt == other.lastKnownGoodUpdatedAt &&
                pendingRemote == other.pendingRemote)

    override fun hashCode(): Int {
        var result = envelope.contentHashCode()
        result = result * 31 + version
        result = result * 31 + updatedAt.hashCode()
        result = result * 31 + (lastKnownGood?.contentHashCode() ?: 0)
        result = result * 31 + (lastKnownGoodVersion ?: 0)
        result = result * 31 + (lastKnownGoodUpdatedAt?.hashCode() ?: 0)
        return result * 31 + pendingRemote.hashCode()
    }

    companion object {
        const val RECORD_VERSION: Int = 1

        /** `isRecord` (localDataHome.ts:520-534) — all-or-nothing validation. */
        fun parse(obj: JsonObject): LocalVaultRecord? {
            if (intOrNull(obj["recordVersion"]) != RECORD_VERSION) return null
            val envelope = envelopeOrNull(obj) ?: return null
            val version = intOrNull(obj["version"])?.takeIf { it >= 1 } ?: return null
            val updatedAt = stringOrNull(obj["updatedAt"]) ?: return null
            val pendingRemote = (obj["pendingRemote"] as? JsonPrimitive)?.booleanOrNull ?: return null

            // `lastKnownGoodTuple`: absent OR complete. A partial tuple is corrupt.
            val rollbackBytes = obj["lastKnownGood"]
            val rollbackVersion = obj["lastKnownGoodVersion"]
            val rollbackUpdatedAt = obj["lastKnownGoodUpdatedAt"]
            val anyRollback = rollbackBytes != null || rollbackVersion != null || rollbackUpdatedAt != null
            var decodedRollback: ByteArray? = null
            var decodedRollbackVersion: Int? = null
            var decodedRollbackUpdatedAt: String? = null
            if (anyRollback) {
                decodedRollback = base64OrNull(rollbackBytes) ?: return null
                decodedRollbackVersion = intOrNull(rollbackVersion)?.takeIf { it >= 1 } ?: return null
                decodedRollbackUpdatedAt = stringOrNull(rollbackUpdatedAt) ?: return null
            }

            return LocalVaultRecord(
                envelope = envelope,
                version = version,
                updatedAt = updatedAt,
                lastKnownGood = decodedRollback,
                lastKnownGoodVersion = decodedRollbackVersion,
                lastKnownGoodUpdatedAt = decodedRollbackUpdatedAt,
                pendingRemote = pendingRemote,
            )
        }

        /** `safeVersion` (localDataHome.ts:571-575). */
        fun safeVersion(obj: JsonObject): Int? = intOrNull(obj["version"])?.takeIf { it >= 1 }

        fun envelopeOrNull(obj: JsonObject): ByteArray? = base64OrNull(obj["envelope"])

        private fun base64OrNull(element: JsonElement?): ByteArray? {
            val text = stringOrNull(element) ?: return null
            return try {
                base64ToBytes(text, VaultCryptoErrorCode.ENVELOPE_INVALID)
            } catch (_: VaultCryptoError) {
                null
            }
        }

        private fun stringOrNull(element: JsonElement?): String? {
            val primitive = element as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.content else null
        }

        private fun intOrNull(element: JsonElement?): Int? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val value = primitive.content.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value != Math.floor(value)) return null
            if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) return null
            return value.toInt()
        }
    }
}
