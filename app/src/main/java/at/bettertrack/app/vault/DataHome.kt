package at.bettertrack.app.vault

/**
 * The blind-blob storage seam — literal port of
 * `apps/web/src/user/vault/dataHome.ts` (vendored at
 * `tools/domain-vectors/vendor/web-vault/dataHome.ts`, platform `origin/main`
 * @ `8ac3c6a2`).
 *
 * A [DataHome] receives **only encrypted envelopes**. It never sees a vault key,
 * a passphrase or a decrypted document, and it has no opinion about what the
 * bytes mean — that separation is what lets the Drive medium be an untrusted
 * remote and the local medium a plain file, with one identical contract.
 *
 * ## Why every outcome is its own type
 *
 * The reference's central design claim is in its own doc comment: *"absent data,
 * corruption, CAS loss and transport failure are not interchangeable."* Each of
 * those four demands a different response from the caller, and collapsing any
 * pair of them is a data-loss bug rather than a tidiness question:
 *
 *  - [DataHomeAbsent] with a local vault present means **re-create the remote**
 *    (plan §4.4: *never* wipe local on absent-remote).
 *  - [DataHomeCorrupt] means keep the bytes for a restore picker and refuse to
 *    overwrite — the unreadable blob may be the user's only copy.
 *  - [DataHomeConflict] means somebody else advanced the vault: go to the merge
 *    path, never force-overwrite.
 *  - [DataHomeTransport] means try again later; the local write already
 *    succeeded and the user is not blocked.
 *
 * Kotlin models the TypeScript unions as three sealed interfaces that share
 * implementors, so a `when` over any of them is exhaustive and a caller cannot
 * quietly forget a branch.
 */

/** `DataHomeMedium` (dataHome.ts:4) — `VaultMedium | 'local'`. */
enum class DataHomeMedium(val wire: String) {
    LOCAL("local"),
    DRIVE("drive"),

    /**
     * The platform's blind server blob store, reached over the `vault:sync`
     * bearer surface (S5). Shipped 2026-08-04 as platform PR #1049; the plan's
     * §6.4 reservation is discharged.
     */
    SERVER("server"),
    ;

    companion object {
        fun fromWire(wire: String?): DataHomeMedium? = entries.firstOrNull { it.wire == wire }
    }
}

/** `DataHomeInfo` (dataHome.ts:6-14). */
data class DataHomeInfo(
    val medium: DataHomeMedium,
    /** Monotonic envelope/CAS version — the vault's own `header.vaultVersion`. */
    val version: Int,
    /** Encrypted envelope size. **Never** a decrypted-content size. */
    val sizeBytes: Long,
    val updatedAt: String?,
    /** Local-only durable acknowledgement metadata; `null` on remote media. */
    val pendingRemote: Boolean? = null,
)

/**
 * `DataHomeTransportFailure.code` (dataHome.ts:19-25), plus one app-side member.
 *
 * [QUOTA_EXCEEDED] does **not** exist in the reference, which folds a Drive 403
 * into `permission-denied`. The app needs the two apart because they are
 * different sentences to a user and different recoveries: a full Drive is
 * "changes saved on this device, free up space" and retries on the next push
 * (plan §4.4), whereas a denied scope needs the consent flow again. The
 * distinguishing signal is Drive's own `error.errors[].reason` =
 * `storageQuotaExceeded`.
 */
enum class DataHomeFailureCode(val wire: String) {
    OFFLINE("offline"),
    CONSENT_REQUIRED("consent-required"),
    TOKEN_EXPIRED("token-expired"),
    GESTURE_REQUIRED("gesture-required"),
    PERMISSION_DENIED("permission-denied"),

    /** App-side extension — see the enum doc. Drive `storageQuotaExceeded`. */
    QUOTA_EXCEEDED("quota-exceeded"),

    /**
     * App-side extension for the S5 server medium: the bearer is valid but was
     * minted before `vault:sync` existed, so the platform answers
     * `403 INSUFFICIENT_SCOPE` naming the missing scope (verified on the dev
     * backend, 2026-08-05).
     *
     * It is deliberately **not** [PERMISSION_DENIED]. A denied permission is a
     * consent problem the user solves in a picker; this one is solved by exactly
     * one act — signing out and back in, so the token is re-minted with the
     * scope. Collapsing the two would send the user to a screen that cannot fix
     * it.
     */
    SCOPE_MISSING("scope-missing"),

    /**
     * App-side extension: `403 VAULT_PARANOID_MODE_REQUIRED` — the route exists
     * and the scope is held, but this account is not in paranoid mode. Only
     * `/vault/history` answers this way (`vaultRoutes.ts:100-111`); it is a
     * designed explainer, never an error the user can retry into.
     */
    MODE_REQUIRED("mode-required"),

    /**
     * App-side extension: `409 VAULT_SERVER_MEDIUM_INACTIVE` — the account is
     * paranoid and holds a vault, but has not activated the *server* medium, so
     * bytes may only be staged as a candidate (a session-only flow, web-side).
     * Writing anyway is impossible; saying so is the designed state.
     */
    MEDIUM_INACTIVE("medium-inactive"),

    /** App-side extension: `413 VAULT_TOO_LARGE` — the envelope exceeds the server cap. */
    TOO_LARGE("too-large"),
    API_FAILURE("api-failure"),
}

/** `DataHomeTransportFailure` (dataHome.ts:16-26). */
data class DataHomeTransportFailure(
    val message: String,
    val code: DataHomeFailureCode? = null,
    val httpStatus: Int? = null,
    /** The remote may have committed before the response was lost. */
    val indeterminate: Boolean = false,
    val cause: Throwable? = null,
)

/** `DataHomeCorruptionReason` (dataHome.ts:28-35). */
enum class DataHomeCorruptionReason(val wire: String) {
    MALFORMED_ENVELOPE("malformed-envelope"),
    MISSING_VERSION("missing-version"),
    VERSION_MISMATCH("version-mismatch"),
    UNSUPPORTED_VERSION("unsupported-version"),
    INVALID_RESPONSE("invalid-response"),
    MALFORMED_METADATA("malformed-metadata"),
    CORRUPT_BYTES("corrupt-bytes"),
}

// ── The three result unions ─────────────────────────────────────────────────

/** `DataHomeReadResult` (dataHome.ts:47-51). */
sealed interface DataHomeReadResult {
    val medium: DataHomeMedium
}

/** `DataHomeWriteResult` (dataHome.ts:63-67). */
sealed interface DataHomeWriteResult {
    val medium: DataHomeMedium
}

/** `DataHomeInfoResult` (dataHome.ts:69-73). */
sealed interface DataHomeInfoResult {
    val medium: DataHomeMedium
}

/** `{ status: 'ok', envelope, info }` — a read that produced bytes. */
class DataHomeBytes(
    override val medium: DataHomeMedium,
    val envelope: ByteArray,
    val info: DataHomeInfo,
) : DataHomeReadResult {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DataHomeBytes &&
                medium == other.medium &&
                envelope.contentEquals(other.envelope) &&
                info == other.info)

    override fun hashCode(): Int = (medium.hashCode() * 31 + envelope.contentHashCode()) * 31 + info.hashCode()

    override fun toString(): String = "DataHomeBytes(medium=$medium, ${envelope.size}B, info=$info)"
}

/** `{ status: 'ok', info }` — a write/info call that has metadata but no bytes. */
data class DataHomeOk(
    override val medium: DataHomeMedium,
    val info: DataHomeInfo,
) : DataHomeWriteResult, DataHomeInfoResult

/** `{ status: 'absent' }` — the medium holds no vault at all. */
data class DataHomeAbsent(override val medium: DataHomeMedium) : DataHomeReadResult, DataHomeInfoResult

/** `{ status: 'conflict', currentVersion }` — the CAS token no longer matches. */
data class DataHomeConflict(
    override val medium: DataHomeMedium,
    val currentVersion: Int?,
) : DataHomeWriteResult

/**
 * `DataHomeCorruptCandidate` (dataHome.ts:37-45).
 *
 * [envelope] carries the original opaque bytes whenever the medium returned
 * any — plan §2.6 rule 4 is that corrupt material is *kept* for a restore
 * picker, never silently discarded, so throwing it away here would destroy the
 * only evidence.
 */
class DataHomeCorrupt(
    override val medium: DataHomeMedium,
    val envelope: ByteArray?,
    val version: Int?,
    val updatedAt: String?,
    val reason: DataHomeCorruptionReason,
    val message: String,
) : DataHomeReadResult, DataHomeWriteResult, DataHomeInfoResult {
    fun withUpdatedAt(updatedAt: String?): DataHomeCorrupt =
        DataHomeCorrupt(medium, envelope, version, updatedAt, reason, message)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is DataHomeCorrupt &&
                medium == other.medium &&
                (envelope?.contentEquals(other.envelope ?: ByteArray(0)) ?: (other.envelope == null)) &&
                version == other.version &&
                updatedAt == other.updatedAt &&
                reason == other.reason &&
                message == other.message)

    override fun hashCode(): Int {
        var result = medium.hashCode()
        result = result * 31 + (envelope?.contentHashCode() ?: 0)
        result = result * 31 + (version ?: 0)
        result = result * 31 + (updatedAt?.hashCode() ?: 0)
        result = result * 31 + reason.hashCode()
        return result * 31 + message.hashCode()
    }

    override fun toString(): String = "DataHomeCorrupt(medium=$medium, reason=$reason, message=$message)"
}

/** `{ status: 'transport-failure', failure }`. */
data class DataHomeTransport(
    override val medium: DataHomeMedium,
    val failure: DataHomeTransportFailure,
) : DataHomeReadResult, DataHomeWriteResult, DataHomeInfoResult

// ── The seam ────────────────────────────────────────────────────────────────

/**
 * `DataHome` (dataHome.ts:75-84).
 *
 * `async` became `suspend` (plan §3.3 rule 6). Implementations must be safe to
 * call from any dispatcher; the ones in this package do their own IO confinement.
 */
interface DataHome {
    val medium: DataHomeMedium

    suspend fun read(): DataHomeReadResult

    /**
     * `write` (dataHome.ts:53-61, 79).
     *
     * @param ifVersion the version the caller reasoned about. `null` is
     *   **create-only**; a number replaces only that exact version. *A DataHome
     *   write never discovers its own compare-and-swap token* — that rule is what
     *   makes a lost race a [DataHomeConflict] the merge path can resolve rather
     *   than a silent overwrite of the other device's work.
     */
    suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult

    suspend fun info(): DataHomeInfoResult
}

// ── Shared envelope inspection ──────────────────────────────────────────────

/**
 * `inspectOutgoing` (driveDataHome.ts:938-963) / `inspect` (localDataHome.ts).
 *
 * Reads the CAS version straight out of the envelope header rather than trusting
 * whatever the medium claims in its metadata. A newer `formatVersion` comes back
 * as [DataHomeCorruptionReason.UNSUPPORTED_VERSION] — read-only, never
 * destructive (plan §2.2).
 *
 * The two media disagree on one detail and the reference is followed rather than
 * unified: unparseable bytes are `corrupt-bytes` on Drive and
 * `malformed-envelope` locally.
 */
internal sealed interface EnvelopeInspection {
    data class Readable(val info: DataHomeInfo) : EnvelopeInspection

    data class Unreadable(val corrupt: DataHomeCorrupt) : EnvelopeInspection
}

internal fun inspectEnvelopeBytes(
    envelope: ByteArray,
    medium: DataHomeMedium,
    metadataVersion: Int? = null,
    metadataUpdatedAt: String? = null,
): EnvelopeInspection = try {
    when (val inspected = inspectVaultEnvelope(envelope)) {
        is EnvelopeVersionResult.UpdateRequired -> EnvelopeInspection.Unreadable(
            DataHomeCorrupt(
                medium = medium,
                envelope = envelope,
                version = metadataVersion,
                updatedAt = null,
                reason = DataHomeCorruptionReason.UNSUPPORTED_VERSION,
                message = "The ${medium.wire} vault was written by a newer app version.",
            )
        )

        is EnvelopeVersionResult.Supported -> EnvelopeInspection.Readable(
            DataHomeInfo(
                medium = medium,
                version = inspected.envelope.header.vaultVersion,
                sizeBytes = envelope.size.toLong(),
                updatedAt = metadataUpdatedAt ?: inspected.envelope.header.writtenAt,
            )
        )
    }
} catch (cause: VaultCryptoError) {
    EnvelopeInspection.Unreadable(
        DataHomeCorrupt(
            medium = medium,
            envelope = envelope,
            version = metadataVersion,
            updatedAt = null,
            reason = if (medium == DataHomeMedium.DRIVE) {
                DataHomeCorruptionReason.CORRUPT_BYTES
            } else {
                DataHomeCorruptionReason.MALFORMED_ENVELOPE
            },
            message = cause.message ?: "The ${medium.wire} vault envelope is malformed.",
        )
    )
}
