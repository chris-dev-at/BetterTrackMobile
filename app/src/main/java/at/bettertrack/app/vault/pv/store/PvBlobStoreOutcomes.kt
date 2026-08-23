package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.dto.PerVaultServerCandidateDto
import at.bettertrack.app.vault.pv.envelope.PvDocEnvelopeHeader

/**
 * **What the blind store can answer, as designed states rather than exceptions.**
 *
 * Every case below exists because its REMEDY differs from every other case's.
 * That is the whole selection rule: two failures that a caller would repair the
 * same way are one case here, and two that it must repair differently are never
 * allowed to collapse into one — which is the specific mistake that would send a
 * sync loop into a retry it can never win.
 *
 * Dormant behind `ParanoidVaultsFlags.enabled`.
 */

/** Who refused an over-size write. */
enum class PvSizeRefusedBy {
    /** This client, before the request went out. Nothing was sent. */
    CLIENT,

    /** The server's `413`. The bytes crossed the wire and were rejected. */
    SERVER,
}

/** The outcome of reading one doc (live or historical). */
sealed interface PvDocReadOutcome {

    /**
     * The bytes, plus the validator every subsequent write is built on.
     *
     * [etag] is mandatory on this branch by construction: a read that came back
     * without a usable validator answers [Corrupt] instead, because a write built
     * on a missing token silently overwrites another device's work.
     */
    data class Loaded(
        val ref: PvDocRef,
        val envelope: ByteArray,
        val etag: PvDocEtag,
        /** The cleartext envelope header — already parsed, so callers do not re-parse. */
        val header: PvDocEnvelopeHeader,
    ) : PvDocReadOutcome {
        /** The precondition that replaces exactly these bytes. */
        val precondition: PvDocPrecondition.Replace get() = PvDocPrecondition.Replace(etag)

        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Loaded && ref == other.ref && etag == other.etag &&
                    header == other.header && envelope.contentEquals(other.envelope)
                )

        override fun hashCode(): Int {
            var result = ref.hashCode()
            result = 31 * result + etag.hashCode()
            result = 31 * result + header.hashCode()
            return 31 * result + envelope.contentHashCode()
        }
    }

    /** `404` — this vault has no doc at that address yet. A first write creates it. */
    data object Absent : PvDocReadOutcome

    /** `304` — the caller's validator still names the current bytes. */
    data class NotModified(val etag: PvDocEtag) : PvDocReadOutcome

    /**
     * Written by a NEWER app version (§5). Surfaced read-only with an "update the
     * app" notice; the header was version-peeked and never best-effort parsed,
     * and nothing may write over it — those bytes are the user's only copy.
     */
    data class UpdateRequired(val formatVersion: Int, val schemaVersion: Int?) : PvDocReadOutcome

    /**
     * Bytes arrived but this client will not build on them: no usable `ETag`, an
     * envelope that does not decode, or a header addressing a different vault,
     * doc or kind than the route that fetched it.
     */
    data class Corrupt(val reason: String) : PvDocReadOutcome

    /** A refusal the code of which is preserved verbatim (scope, medium state, …). */
    data class Refused(val error: BtApiError) : PvDocReadOutcome

    /** No HTTP answer, or an answer this client could not read at all. */
    data class Transport(val error: BtApiError) : PvDocReadOutcome
}

/**
 * The outcome of writing one doc.
 *
 * ## The two `412`s, and why they are two cases
 *
 * The contract answers `412` for two different reasons with two opposite
 * remedies:
 *
 * - **a stale precondition** — someone else wrote first. Remedy: re-read,
 *   re-merge, write again.
 * - **a `writeId` replayed with DIFFERENT bytes** — the idempotency key is
 *   already bound to another byte string, and the server refuses deliberately so
 *   a replayed old write cannot clobber current state when a client-owned
 *   `docVersion` cycles back. Remedy: mint a **new** `writeId`. Re-sending these
 *   bytes under this key can never succeed, however many times it is tried.
 *
 * Conflating them is a livelock: the stale remedy applied to a replay refusal
 * loops forever. So they are [PreconditionStale] and [WriteIdReplayRefused], and
 * `PvBlobStore` classifies with a rule that never needs an error string the
 * deployed OpenAPI does not publish — see `PvBlobStore.classifyPreconditionFailure`.
 */
sealed interface PvDocWriteOutcome {

    /** `204` — committed. [etag] is the validator of the bytes just written. */
    data class Written(val etag: PvDocEtag, val docVersion: Int) : PvDocWriteOutcome {
        /** The precondition that would replace what was just written. */
        val precondition: PvDocPrecondition.Replace get() = PvDocPrecondition.Replace(etag)
    }

    /**
     * `412`, stale precondition. Re-read, re-merge, retry — with a fresh
     * `writeId`, which is what keeps the retry from turning into the OTHER 412.
     * [currentEtag] is the server's current validator when it sent one.
     */
    data class PreconditionStale(val currentEtag: PvDocEtag?) : PvDocWriteOutcome

    /**
     * `412`, this `writeId` is already bound to different bytes. Mint a new one;
     * retrying as written can never succeed.
     *
     * [detectedLocally] is true when this client's own ledger proved the reuse
     * and no request was sent at all.
     */
    data class WriteIdReplayRefused(
        val writeId: String,
        val detectedLocally: Boolean,
    ) : PvDocWriteOutcome

    /**
     * Past the kind's ceiling — refusal, never truncation. [limitBytes] is
     * selected by the VALIDATED [kind], so an envelope claiming a bigger kind
     * borrows nothing.
     */
    data class TooLarge(
        val kind: PvDocKind,
        val limitBytes: Int,
        val actualBytes: Int,
        val refusedBy: PvSizeRefusedBy,
    ) : PvDocWriteOutcome

    /**
     * These bytes must not be sent to this address, decided locally: the envelope
     * does not decode, it addresses another vault/doc, its `docKind` disagrees
     * with the kind this vault gives the address, or it was written by a newer
     * app version. A programming error, surfaced as a state so it is testable.
     */
    data class NotWritable(val reason: String) : PvDocWriteOutcome

    /**
     * `428` — the server saw no precondition.
     *
     * Unreachable through this API by construction (see [PvDocPrecondition]), so
     * reaching it means something between the app and the server stripped the
     * header — a proxy, an interceptor. Named rather than folded into a generic
     * refusal precisely because "impossible" states are the ones worth being able
     * to recognise in a bug report.
     */
    data class PreconditionMissing(val error: BtApiError) : PvDocWriteOutcome

    /** Any other server refusal, with its code preserved verbatim. */
    data class Refused(val error: BtApiError) : PvDocWriteOutcome

    /**
     * No HTTP answer — or an answer this client cannot continue FROM, which on a
     * write means exactly one thing: a success without an `ETag`. The bytes may
     * well be stored, but the validator the next write has to carry is missing,
     * and inventing one would be the blind overwrite this layer exists to
     * prevent. Both remedies are the same: re-read the doc.
     *
     * [indeterminate] is always true for a write: a lost response may still have
     * committed, so a blind retry would race the client's own earlier write.
     */
    data class Transport(val error: BtApiError, val indeterminate: Boolean = true) : PvDocWriteOutcome
}

/** The outcome of staging one server candidate inside a media transition. */
sealed interface PvCandidateStageOutcome {
    data class Staged(val metadata: PerVaultServerCandidateDto) : PvCandidateStageOutcome

    data class TooLarge(
        val kind: PvDocKind,
        val limitBytes: Int,
        val actualBytes: Int,
        val refusedBy: PvSizeRefusedBy,
    ) : PvCandidateStageOutcome

    data class NotWritable(val reason: String) : PvCandidateStageOutcome

    data class Refused(val error: BtApiError) : PvCandidateStageOutcome

    data class Transport(val error: BtApiError) : PvCandidateStageOutcome
}

/**
 * The outcome of reading one staged candidate back.
 *
 * The point of the call is [readback]: the opaque receipt the media commit hands
 * back per doc. Re-staging rotates `candidateId`, so a receipt collected under an
 * earlier attempt is not reusable — which is what makes the commit's
 * every-live-doc-under-one-`transitionId` rule enforceable rather than
 * advisory.
 */
sealed interface PvCandidateReadOutcome {
    data class Loaded(
        val envelope: ByteArray,
        val candidateId: String?,
        val expiresAt: String?,
        val readback: String?,
    ) : PvCandidateReadOutcome {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Loaded && candidateId == other.candidateId &&
                    expiresAt == other.expiresAt && readback == other.readback &&
                    envelope.contentEquals(other.envelope)
                )

        override fun hashCode(): Int {
            var result = candidateId?.hashCode() ?: 0
            result = 31 * result + (expiresAt?.hashCode() ?: 0)
            result = 31 * result + (readback?.hashCode() ?: 0)
            return 31 * result + envelope.contentHashCode()
        }
    }

    data class Refused(val error: BtApiError) : PvCandidateReadOutcome

    data class Transport(val error: BtApiError) : PvCandidateReadOutcome
}

/**
 * The outcome of deleting a vault configuration.
 *
 * §15 step-up is not a case here — it is a **parameter**: `PvBlobStore.deleteVault`
 * takes a non-null credential, so "forgot to re-authenticate" cannot be
 * expressed, the same trick [PvDocPrecondition] plays on the write path. What IS
 * a case is the server rejecting the credential it was given, which is
 * decidable from the HTTP status without inventing an error code.
 */
sealed interface PvVaultDeleteOutcome {
    data object Deleted : PvVaultDeleteOutcome

    /**
     * Refused HERE, before any request: the credential carries none of
     * `password` / `code` / `recoveryCode`, which the contract's `.refine`
     * requires at least one of. Named separately from [StepUpRejected] because
     * the remedy is to ASK the user for a credential, not to tell them the one
     * they gave was wrong.
     */
    data class StepUpMissing(val reason: String) : PvVaultDeleteOutcome

    /** `403` — the credential did not verify, or the throttle refused the attempt. */
    data class StepUpRejected(val error: BtApiError) : PvVaultDeleteOutcome

    /** `403 INSUFFICIENT_SCOPE` — the token predates the scope; sign out and in. */
    data class ScopeMissing(val error: BtApiError) : PvVaultDeleteOutcome

    /** `403 API_KEY_FORBIDDEN` — a bearer reached a session-only route. */
    data class SessionRequired(val error: BtApiError) : PvVaultDeleteOutcome

    /**
     * Any other refusal, code preserved. The contract refuses a delete while a
     * portfolio still references the vault and while any retirement row exists;
     * the deployed OpenAPI publishes no code for either (`ApiError.code` is a
     * free-form string), so this client carries the server's own code and
     * message through instead of guessing at names for them.
     */
    data class Refused(val error: BtApiError) : PvVaultDeleteOutcome

    data class Transport(val error: BtApiError) : PvVaultDeleteOutcome
}
