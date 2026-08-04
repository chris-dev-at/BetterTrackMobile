package at.bettertrack.app.vault.server

import android.util.Log
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The **server medium** — the third [DataHome], over the platform's `vault:sync`
 * bearer surface (platform PR #1049, migration `0081`; board tick "S5 UNBLOCKED").
 *
 * ## What this class is, in one sentence
 *
 * It is [at.bettertrack.app.vault.drive.DriveDataHome]'s twin against a store that
 * is *actually* compare-and-swap instead of an approximation: where Drive must
 * re-`GET` before a `PATCH` and hope, the platform gives real HTTP preconditions
 * and answers `412` when they lose. The envelope bytes, the CAS discipline and
 * every merge rule are byte-identical to the Drive path — the platform's own
 * commitment ("Transport/auth only — envelope, CAS, ciphertext handling
 * byte-identical", `PLATFORM_ASKS.md` reply #41 item 4) — so the merge engine,
 * the projection and the money never learn which medium they came from.
 *
 * ## Route truth (read from platform source, not from prose)
 *
 * Every shape below is cited from `apps/api/src/http/routes/vaultRoutes.ts` at
 * `origin/main`, cross-checked against the web reference client
 * `apps/web/src/user/vault/serverBlobDataHome.ts`, and then verified on the live
 * dev backend (transcript in the S5 report):
 *
 * | Call | Server behaviour |
 * |---|---|
 * | `GET /vault` | `200` raw envelope + `ETag: "<version>"` (`vaultRoutes.ts:422-434`); `304` when `If-None-Match` matches; `404 VAULT_NOT_FOUND` when no vault is stored |
 * | `PUT /vault` | body = raw envelope; **precondition mandatory** — `If-None-Match: *` creates, `If-Match: "<v>"` replaces, neither ⇒ `428 VAULT_PRECONDITION_REQUIRED` (`:436-449`); `204` + `ETag` on success; `412 VAULT_PRECONDITION_FAILED` (with the *current* version in `ETag`) on a lost race; `400 VAULT_MALFORMED`; `413 VAULT_TOO_LARGE`; `409 VAULT_SERVER_MEDIUM_INACTIVE` |
 * | `GET /vault/media` | JSON `{privacyMode, mediaState}` (`:213-218`) |
 * | `GET /vault/history` | JSON `{items,nextCursor}`, paranoid-only (`:187-192`, gate `:100-111`) |
 * | `GET /vault/history/{version}` | raw bytes + `ETag` + three `X-BetterTrack-Vault-*` headers (`:194-209`) |
 *
 * ## The header we deliberately never send
 *
 * The web client stamps `X-BetterTrack-Vault-Retirement-Proof-Public-Key` on a
 * create (`serverBlobDataHome.ts:83-86`). This client never does, and not only
 * because it would be ignored: `vaultRoutes.ts:126-135` forces the value to
 * `null` on any bearer request *by design*, because whoever writes that key
 * first pins a value nobody can reproduce, and #1043 grants a bearer opaque byte
 * sync rather than that authority. Sending it would be a request the app cannot
 * mean. Enrolling a retirement verifier stays a browser act.
 *
 * ## Why plain OkHttp and not Retrofit
 *
 * Same reason `DriveDataHome` gives: the payload is opaque bytes with meaning
 * carried in HTTP headers (`ETag`, `If-Match`), which is exactly the shape
 * Retrofit's converter layer exists to hide. It *is* on the app's authenticated
 * client, though — so `AuthInterceptor` supplies the bearer and
 * `TokenAuthenticator` re-mints it on a `401` for free, and no vault code ever
 * touches a token.
 */
class ServerVaultDataHome(
    private val client: OkHttpClient,
    /** The app's `/api/v1/` base — the same value `BtApi` is built on. */
    private val apiBase: HttpUrl,
    private val json: Json,
    /**
     * Whether a BetterTrack session exists at all. Checked *before* the call so a
     * signed-out user gets the honest "not signed in" state instead of a `401`
     * dressed up as a server fault.
     */
    private val hasSession: () -> Boolean = { true },
    private val isOnline: () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DataHome {

    override val medium: DataHomeMedium = DataHomeMedium.SERVER

    private val vaultUrl: HttpUrl = apiBase.newBuilder().addPathSegment(PATH_VAULT).build()

    // ── DataHome ────────────────────────────────────────────────────────────

    override suspend fun read(): DataHomeReadResult {
        val fetched = fetch(Request.Builder().url(vaultUrl).get().build(), mutating = false)
        return when (fetched) {
            is Fetched.Failure -> DataHomeTransport(medium, fetched.failure)
            is Fetched.Ok -> when (fetched.code) {
                404 -> DataHomeAbsent(medium)
                200 -> readEnvelope(fetched.body, fetched.etag)
                else -> DataHomeTransport(medium, unexpected("GET vault failed.", fetched))
            }
        }
    }

    /**
     * `PUT /vault` with the platform's mandatory precondition.
     *
     * The outgoing-version guard is the reference's (`serverBlobDataHome.ts:70-77`)
     * and it matters more than it looks: the server *also* refuses a
     * non-advancing envelope (`paranoidVaultService.ts:168-173`), so without this
     * check a client bug surfaces as an opaque `400` from a remote machine rather
     * than as the local invariant violation it actually is.
     */
    override suspend fun write(envelope: ByteArray, ifVersion: Int?): DataHomeWriteResult {
        val outgoing = when (val inspected = inspectEnvelopeBytes(envelope, medium)) {
            is EnvelopeInspection.Unreadable -> return inspected.corrupt
            is EnvelopeInspection.Readable -> inspected.info
        }
        if (ifVersion != null && outgoing.version <= ifVersion) {
            return corrupt(
                envelope = envelope,
                version = outgoing.version,
                reason = DataHomeCorruptionReason.MALFORMED_ENVELOPE,
                message = "The vault envelope version must advance the If-Match version.",
            )
        }

        val builder = Request.Builder()
            .url(vaultUrl)
            .put(envelope.toRequestBody(OCTET_STREAM))
        // Create vs replace — never both, never neither (the 428 branch).
        if (ifVersion == null) builder.header(HEADER_IF_NONE_MATCH, "*")
        else builder.header(HEADER_IF_MATCH, vaultEtag(ifVersion))

        val fetched = fetch(builder.build(), mutating = true)
        return when (fetched) {
            is Fetched.Failure -> DataHomeTransport(medium, fetched.failure)
            is Fetched.Ok -> when (fetched.code) {
                // 204 No Content is the documented success (`vaultRoutes.ts:457-459`);
                // 200 is accepted defensively so a future body cannot break sync.
                204, 200 -> acknowledged(envelope, outgoing.version, fetched.etag)

                412 -> DataHomeConflict(medium, currentVersion = parseVaultEtag(fetched.etag))

                400 -> corrupt(
                    envelope = envelope,
                    version = outgoing.version,
                    reason = DataHomeCorruptionReason.MALFORMED_ENVELOPE,
                    message = "The server rejected the vault write as a malformed or non-advancing envelope.",
                )

                else -> DataHomeTransport(medium, unexpected("PUT vault failed.", fetched, indeterminate = true))
            }
        }
    }

    override suspend fun info(): DataHomeInfoResult = when (val result = read()) {
        is DataHomeBytes -> DataHomeOk(medium, result.info)
        is DataHomeAbsent -> result
        is DataHomeCorrupt -> result
        is DataHomeTransport -> result
    }

    // ── The restore net + the account's own disposition ─────────────────────

    /**
     * `GET /vault/media` — the account's privacy mode and, for a paranoid
     * account, its durable media selection.
     *
     * This is the app's **pre-flight**: a normal account answers
     * `{"privacyMode":"normal","mediaState":null}` (verified live), which is the
     * difference between "there is no server vault to sync" and "something went
     * wrong" — and the plan is unambiguous that those are different sentences.
     */
    suspend fun mediaState(): ServerVaultMediaResult {
        val url = apiBase.newBuilder().addPathSegment(PATH_VAULT).addPathSegment(PATH_MEDIA).build()
        val fetched = fetch(Request.Builder().url(url).get().build(), mutating = false)
        return when (fetched) {
            is Fetched.Failure -> ServerVaultMediaResult.Failure(fetched.failure)
            is Fetched.Ok -> when (fetched.code) {
                200 -> parseMediaState(fetched.body)
                404 -> ServerVaultMediaResult.Failure(
                    DataHomeTransportFailure("This account no longer exists.", httpStatus = 404)
                )

                else -> ServerVaultMediaResult.Failure(unexpected("GET vault media failed.", fetched))
            }
        }
    }

    /**
     * `GET /vault/history` — the restore picker's index, newest version first.
     *
     * Paranoid-only by server gate, and that `403` is [ServerVaultHistoryResult.ModeRequired]
     * rather than an error: a normal account has no retained versions to show,
     * which is a fact about the account, not a failure of the request.
     */
    suspend fun history(cursor: Int? = null, limit: Int? = null): ServerVaultHistoryResult {
        val url = apiBase.newBuilder()
            .addPathSegment(PATH_VAULT)
            .addPathSegment(PATH_HISTORY)
            .apply {
                if (cursor != null) addQueryParameter("cursor", cursor.toString())
                if (limit != null) addQueryParameter("limit", limit.toString())
            }
            .build()
        val fetched = fetch(Request.Builder().url(url).get().build(), mutating = false)
        return when (fetched) {
            is Fetched.Failure -> when (fetched.failure.code) {
                DataHomeFailureCode.MODE_REQUIRED -> ServerVaultHistoryResult.ModeRequired
                else -> ServerVaultHistoryResult.Failure(fetched.failure)
            }

            is Fetched.Ok -> when (fetched.code) {
                200 -> parseHistoryPage(fetched.body)
                else -> ServerVaultHistoryResult.Failure(unexpected("GET vault history failed.", fetched))
            }
        }
    }

    /**
     * `GET /vault/history/{version}` — one retained ciphertext, for restore.
     *
     * Returns a [DataHomeReadResult] rather than a bespoke type on purpose: a
     * historical envelope is decrypted, inspected and merged by exactly the same
     * code as a live one, so giving it a different result type would fork the one
     * path that must never fork.
     */
    suspend fun historyVersion(version: Int): DataHomeReadResult {
        val url = apiBase.newBuilder()
            .addPathSegment(PATH_VAULT)
            .addPathSegment(PATH_HISTORY)
            .addPathSegment(version.toString())
            .build()
        val fetched = fetch(Request.Builder().url(url).get().build(), mutating = false)
        return when (fetched) {
            is Fetched.Failure -> DataHomeTransport(medium, fetched.failure)
            is Fetched.Ok -> when (fetched.code) {
                200 -> readEnvelope(fetched.body, fetched.etag, fetched.createdAt)
                404 -> DataHomeAbsent(medium)
                else -> DataHomeTransport(medium, unexpected("GET vault history version failed.", fetched))
            }
        }
    }

    // ── Response handling ───────────────────────────────────────────────────

    private fun readEnvelope(
        envelope: ByteArray,
        etag: String?,
        metadataUpdatedAt: String? = null,
    ): DataHomeReadResult {
        val responseVersion = parseVaultEtag(etag)
        val info = when (val inspected = inspectEnvelopeBytes(envelope, medium, responseVersion, metadataUpdatedAt)) {
            is EnvelopeInspection.Unreadable -> return inspected.corrupt
            is EnvelopeInspection.Readable -> inspected.info
        }
        // Below: the ETag is the CAS token every subsequent write is built on, so
        // an absent or disagreeing one is corruption, not a detail to paper over
        // — a wrong token silently overwrites another device's work.
        if (responseVersion == null) {
            return corrupt(
                envelope, null, DataHomeCorruptionReason.MISSING_VERSION,
                "The server returned vault bytes without a valid ETag.",
            )
        }
        if (responseVersion != info.version) {
            return corrupt(
                envelope, responseVersion, DataHomeCorruptionReason.VERSION_MISMATCH,
                "The server ETag does not match the vault envelope version.",
            )
        }
        return DataHomeBytes(medium, envelope, info)
    }

    private fun acknowledged(envelope: ByteArray, outgoingVersion: Int, etag: String?): DataHomeWriteResult {
        val responseVersion = parseVaultEtag(etag)
            ?: return corrupt(
                envelope, null, DataHomeCorruptionReason.MISSING_VERSION,
                "The server acknowledged a vault write without a valid ETag.",
            )
        if (responseVersion != outgoingVersion) {
            return corrupt(
                envelope, responseVersion, DataHomeCorruptionReason.VERSION_MISMATCH,
                "The server ETag does not match the vault envelope version.",
            )
        }
        return DataHomeOk(
            medium,
            DataHomeInfo(
                medium = medium,
                version = responseVersion,
                sizeBytes = envelope.size.toLong(),
                updatedAt = null,
            ),
        )
    }

    private fun parseMediaState(body: ByteArray): ServerVaultMediaResult = try {
        val root = json.parseToJsonElement(body.decodeToString()).jsonObject
        val privacyMode = root["privacyMode"]?.jsonPrimitive?.content
        val mediaState = root["mediaState"]
        ServerVaultMediaResult.Ok(
            ServerVaultMediaState(
                privacyMode = privacyMode,
                // Kept as raw JSON text: the app reasons about `mediaSet` only,
                // and re-modelling a shape it can never *change* (PATCH /vault/media
                // is session-only, `vaultRoutes.ts:220`) would be dead weight that
                // breaks the day the platform adds a field.
                mediaSetContainsServer = mediaState?.let(::mediaSetContainsServer) ?: false,
                hasMediaState = mediaState != null && mediaState.toString() != "null",
            )
        )
    } catch (cause: Exception) {
        Log.w(TAG, "vault media state unparseable")
        ServerVaultMediaResult.Failure(
            DataHomeTransportFailure(
                "The server's vault status could not be read.",
                code = DataHomeFailureCode.API_FAILURE,
                cause = cause,
            )
        )
    }

    private fun parseHistoryPage(body: ByteArray): ServerVaultHistoryResult = try {
        val root = json.parseToJsonElement(body.decodeToString()).jsonObject
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            val version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
            ServerVaultHistoryEntry(
                version = version,
                createdAt = obj["createdAt"]?.jsonPrimitive?.content,
                sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        ServerVaultHistoryResult.Ok(
            items = items,
            nextCursor = root["nextCursor"]?.jsonPrimitive?.content?.toIntOrNull(),
        )
    } catch (cause: Exception) {
        Log.w(TAG, "vault history page unparseable")
        ServerVaultHistoryResult.Failure(
            DataHomeTransportFailure(
                "The list of earlier vault versions could not be read.",
                code = DataHomeFailureCode.API_FAILURE,
                cause = cause,
            )
        )
    }

    // ── Transport ───────────────────────────────────────────────────────────

    /**
     * One call, with every *classified* failure turned into a typed outcome here
     * so the four call sites above only ever handle statuses that mean something
     * about the vault.
     *
     * `401` is not in this list on purpose: the client is the app's authenticated
     * one, so `TokenAuthenticator` has already tried a refresh and failed by the
     * time a `401` reaches us — at which point it is a session problem the auth
     * layer owns, and reporting it as "signed out" is both true and actionable.
     */
    private suspend fun fetch(request: Request, mutating: Boolean): Fetched {
        if (!hasSession()) {
            return Fetched.Failure(
                DataHomeTransportFailure(
                    "You are not signed in to BetterTrack, so changes are saved on this device.",
                    code = DataHomeFailureCode.CONSENT_REQUIRED,
                )
            )
        }
        if (!isOnline()) {
            return Fetched.Failure(
                DataHomeTransportFailure(
                    "BetterTrack is offline — changes are saved on this device.",
                    code = DataHomeFailureCode.OFFLINE,
                )
            )
        }

        val response = try {
            withContext(ioDispatcher) {
                client.newCall(request).execute().use { raw ->
                    Fetched.Ok(
                        code = raw.code,
                        body = raw.body?.bytes() ?: ByteArray(0),
                        etag = raw.header(HEADER_ETAG),
                        createdAt = raw.header(HEADER_CREATED_AT),
                    )
                }
            }
        } catch (cause: IOException) {
            val offline = !isOnline()
            return Fetched.Failure(
                DataHomeTransportFailure(
                    message = if (offline) {
                        "BetterTrack is offline — changes are saved on this device."
                    } else {
                        "BetterTrack could not be reached."
                    },
                    code = if (offline) DataHomeFailureCode.OFFLINE else DataHomeFailureCode.API_FAILURE,
                    // A lost response on a PUT may still have committed server-side;
                    // a blind retry would then lose the CAS race against our own write.
                    indeterminate = mutating,
                    cause = cause,
                )
            )
        }

        val code = errorCodeOf(response)
        return when {
            response.code == 401 -> Fetched.Failure(
                DataHomeTransportFailure(
                    "Sign in to BetterTrack again to sync your vault.",
                    code = DataHomeFailureCode.TOKEN_EXPIRED,
                    httpStatus = 401,
                )
            )

            // The single most important classification in this file: a token
            // minted before `vault:sync` existed is not broken, it is *stale*.
            code == CODE_INSUFFICIENT_SCOPE -> Fetched.Failure(
                DataHomeTransportFailure(
                    "Sign out and back in to let BetterTrack sync your vault.",
                    code = DataHomeFailureCode.SCOPE_MISSING,
                    httpStatus = response.code,
                )
            )

            code == CODE_MODE_REQUIRED -> Fetched.Failure(
                DataHomeTransportFailure(
                    "This account does not use paranoid mode, so there is no server vault history.",
                    code = DataHomeFailureCode.MODE_REQUIRED,
                    httpStatus = response.code,
                )
            )

            code == CODE_MEDIUM_INACTIVE -> Fetched.Failure(
                DataHomeTransportFailure(
                    "BetterTrack is not yet one of this vault's storage places. Add it in the web app.",
                    code = DataHomeFailureCode.MEDIUM_INACTIVE,
                    httpStatus = response.code,
                )
            )

            // A bearer reaching a session-only route (PATCH /vault/media). The app
            // never calls one; classified so a future caller gets a truthful state.
            code == CODE_API_KEY_FORBIDDEN -> Fetched.Failure(
                DataHomeTransportFailure(
                    "This change can only be made in the BetterTrack web app.",
                    code = DataHomeFailureCode.PERMISSION_DENIED,
                    httpStatus = response.code,
                )
            )

            response.code == 413 -> Fetched.Failure(
                DataHomeTransportFailure(
                    "This vault is too large for BetterTrack to store.",
                    code = DataHomeFailureCode.TOO_LARGE,
                    httpStatus = 413,
                )
            )

            else -> response
        }
    }

    /** The platform error envelope is always `{ error: { code, message } }`. */
    private fun errorCodeOf(response: Fetched.Ok): String? {
        if (response.code < 400 || response.body.isEmpty()) return null
        return try {
            json.parseToJsonElement(response.body.decodeToString())
                .jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    private fun unexpected(
        message: String,
        fetched: Fetched.Ok,
        indeterminate: Boolean = false,
    ): DataHomeTransportFailure = DataHomeTransportFailure(
        message = message,
        code = DataHomeFailureCode.API_FAILURE,
        httpStatus = fetched.code,
        indeterminate = indeterminate,
    )

    private fun corrupt(
        envelope: ByteArray?,
        version: Int?,
        reason: DataHomeCorruptionReason,
        message: String,
    ) = DataHomeCorrupt(medium, envelope, version, updatedAt = null, reason = reason, message = message)

    private sealed interface Fetched {
        class Ok(
            val code: Int,
            val body: ByteArray,
            val etag: String?,
            /** `X-BetterTrack-Vault-Created-At`, present only on a history read. */
            val createdAt: String?,
        ) : Fetched

        class Failure(val failure: DataHomeTransportFailure) : Fetched
    }

    companion object {
        private const val TAG = "BtServerVault"

        private const val PATH_VAULT = "vault"
        private const val PATH_MEDIA = "media"
        private const val PATH_HISTORY = "history"

        private const val HEADER_ETAG = "ETag"
        private const val HEADER_IF_MATCH = "If-Match"
        private const val HEADER_IF_NONE_MATCH = "If-None-Match"

        /** `VAULT_HISTORY_CREATED_AT_HEADER` (`packages/contracts/src/vault.ts:1572`). */
        private const val HEADER_CREATED_AT = "X-BetterTrack-Vault-Created-At"

        /** `VAULT_CONTENT_TYPE` (`packages/contracts/src/vault.ts:1570`). */
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** `bearerAuth.ts:468` — the audited scope refusal. */
        const val CODE_INSUFFICIENT_SCOPE = "INSUFFICIENT_SCOPE"

        /** `VAULT_ERROR_CODES.modeRequired` (`vault.ts:1554`). */
        const val CODE_MODE_REQUIRED = "VAULT_PARANOID_MODE_REQUIRED"

        /** `VAULT_ERROR_CODES.serverMediumInactive` (`vault.ts:1557`). */
        const val CODE_MEDIUM_INACTIVE = "VAULT_SERVER_MEDIUM_INACTIVE"

        /** `vaultRoutes.ts:93-96` — a bearer on a session-only vault route. */
        const val CODE_API_KEY_FORBIDDEN = "API_KEY_FORBIDDEN"
    }
}

/** `vaultEtag(version)` — `ETag: "<version>"` (`packages/contracts/src/vault.ts:1585`). */
internal fun vaultEtag(version: Int): String = "\"$version\""

/**
 * `parseVaultEtag` (`packages/contracts/src/vault.ts:1595`) — ported literally,
 * including its refusals: an optional weak marker and quotes are stripped, and
 * anything that is not a bare non-negative integer (so `*` and comma lists)
 * returns `null`, because the vault CAS is only ever against one concrete version.
 */
internal fun parseVaultEtag(value: String?): Int? {
    if (value == null) return null
    var bare = value.trim()
    if (bare.length >= 2 && bare.substring(0, 2).equals("W/", ignoreCase = true)) bare = bare.substring(2)
    if (bare.length >= 2 && bare.startsWith('"') && bare.endsWith('"')) bare = bare.substring(1, bare.length - 1)
    if (bare.isEmpty() || bare.any { it !in '0'..'9' }) return null
    return bare.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
}
