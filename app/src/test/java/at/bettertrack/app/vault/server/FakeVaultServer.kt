package at.bettertrack.app.vault.server

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * The platform's `vault:sync` bearer surface, in memory, over MockWebServer.
 *
 * ## Fidelity, and how it was earned
 *
 * Every status, header and error code below was first read out of the platform
 * source (`apps/api/src/http/routes/vaultRoutes.ts`,
 * `apps/api/src/services/account/paranoidVaultService.ts`,
 * `packages/contracts/src/vault.ts`) and then **confirmed against the live dev
 * backend** on 2026-08-05 with a real 19-scope bearer, including a full CAS
 * round trip on a throwaway account that was taken all the way into paranoid
 * mode. Three behaviours came out of that run rather than out of the source, and
 * they are the reason this fake exists instead of canned responses:
 *
 * 1. **A `412` carries the winner's version in `ETag`.** `If-Match: "1"` against
 *    a v2 vault answered `412` with `ETag: "2"`. The client's merge branch reads
 *    the current version straight off the conflict, with no follow-up `GET` —
 *    a fake that omitted the header would let that regress silently.
 * 2. **The server enforces monotonic versions.** An envelope whose
 *    `header.vaultVersion` does not exceed the `If-Match` version is refused
 *    `400 VAULT_MALFORMED` ("envelope vaultVersion does not advance the If-Match
 *    version"), *before* any CAS comparison. So the store is not fully blind: it
 *    parses the envelope header.
 * 3. **History lists only superseded versions.** Asking for the *current*
 *    version answers `404`, which is the difference between an empty restore
 *    picker and a broken one.
 *
 * Two further live findings are modelled by omission, deliberately: the server
 * does **not** validate `schemaVersion` (a schema-2 envelope was accepted with
 * `204`) and does **not** verify AEAD integrity (a tampered ciphertext was
 * accepted and served back verbatim). Both are purely client-side determinations,
 * so this fake stores whatever bytes it is given — which is what makes the
 * client's own `update-required` and corrupt-bytes branches worth testing.
 */
class FakeVaultServer {

    val server = MockWebServer()

    /** `version → envelope bytes`, oldest first. The last entry is live. */
    private val versions = LinkedHashMap<Int, StoredVault>()

    /** Requests received, as `METHOD path`. */
    val requestLog = mutableListOf<String>()

    /** Every `If-Match` / `If-None-Match` the client sent, in order. */
    val preconditions = mutableListOf<String?>()

    /** Set to answer every vault route `403 INSUFFICIENT_SCOPE` (a pre-`0081` token). */
    var scopeMissing: Boolean = false

    /** Set to answer `403 VAULT_PARANOID_MODE_REQUIRED` on the history routes. */
    var privacyMode: String = PRIVACY_NORMAL

    /** Set to answer `409 VAULT_SERVER_MEDIUM_INACTIVE` on writes. */
    var serverMediumInactive: Boolean = false

    /** Set to answer `413 VAULT_TOO_LARGE` on writes (live cap: 16 MiB). */
    var tooLarge: Boolean = false

    /** Set to answer `401`, i.e. the session died mid-sync. */
    var unauthenticated: Boolean = false

    /** Set to answer a `200` whose `ETag` disagrees with the envelope. */
    var lieAboutEtag: Boolean = false

    /**
     * Set to stamp the `X-BetterTrack-Vault-*` metadata headers onto the **main**
     * `GET /vault` as well, carrying values that contradict the envelope.
     *
     * The live server never does this — verified 2026-08-05 against the paranoid
     * account, where `GET /vault` answered `200` with `ETag: "2"` and no `X-`
     * header at all, while `GET /vault/history/1` carried all three. Platform
     * called it out as integration note #1 precisely because a client that took
     * its version or its timestamp from those headers would read `null` on every
     * live main GET. This switch exists so the client's indifference to them is
     * *asserted* rather than merely true by accident.
     */
    var metadataHeadersOnMainGet: Boolean = false

    /** The durable media set reported by `GET /vault/media` for a paranoid account. */
    var mediaSet: List<String> = listOf(MEDIUM_SERVER)

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    fun shutdown() = server.shutdown()

    /** The `/api/v1/` base the app builds its vault URLs on. */
    fun apiBase() = server.url("/api/v1/")

    // ── Seeding + inspection ────────────────────────────────────────────────

    /** Places [envelope] as version [version] without going through the CAS path. */
    fun seed(envelope: ByteArray, version: Int) {
        versions[version] = StoredVault(envelope, createdAt = "2026-08-04T22:00:0${version % 10}.000Z")
    }

    val currentVersion: Int? get() = versions.keys.maxOrNull()

    fun storedBytes(version: Int? = null): ByteArray? {
        val wanted = version ?: currentVersion ?: return null
        return versions[wanted]?.envelope
    }

    fun versionCount(): Int = versions.size

    // ── Dispatch ────────────────────────────────────────────────────────────

    private fun handle(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        requestLog += "${request.method} $path"

        if (unauthenticated) return error(401, "UNAUTHENTICATED", "Authentication required.")
        // `bearerAuth.ts:468` — route-aware AND scope-aware, so EVERY vault route
        // refuses, not only the writes.
        if (scopeMissing && path.startsWith(VAULT_PATH)) {
            return error(403, "INSUFFICIENT_SCOPE", "API key is missing the required scope \"vault:sync\".")
        }

        return when {
            path == "$VAULT_PATH/media" && request.method == "GET" -> mediaState()
            path == "$VAULT_PATH/history" && request.method == "GET" -> historyList()
            path.startsWith("$VAULT_PATH/history/") && request.method == "GET" ->
                historyVersion(path.removePrefix("$VAULT_PATH/history/").toIntOrNull())

            path == VAULT_PATH && request.method == "GET" -> readVault(request)
            path == VAULT_PATH && request.method == "PUT" -> writeVault(request)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun readVault(request: RecordedRequest): MockResponse {
        val version = currentVersion ?: return error(404, CODE_NOT_FOUND, "No vault stored.")
        val stored = versions.getValue(version)
        val ifNoneMatch = request.getHeader(IF_NONE_MATCH)
        preconditions += ifNoneMatch
        // `vaultRoutes.ts:427-431` — a matching If-None-Match short-circuits.
        if (ifNoneMatch != null && parseEtag(ifNoneMatch) == version) {
            return MockResponse().setResponseCode(304).setHeader(ETAG, etag(version))
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader(ETAG, etag(if (lieAboutEtag) version + 99 else version))
            .setHeader("Content-Type", OCTET_STREAM)
            .setHeader("Cache-Control", "private, no-store")
            .apply {
                if (metadataHeadersOnMainGet) {
                    setHeader(HEADER_CREATED_AT, CONTRADICTORY_CREATED_AT)
                    setHeader("X-BetterTrack-Vault-Size-Bytes", (stored.envelope.size + 1).toString())
                    setHeader("X-BetterTrack-Vault-Medium", "drive")
                }
            }
            .setBody(okio.Buffer().write(stored.envelope))
    }

    private fun writeVault(request: RecordedRequest): MockResponse {
        val ifMatch = request.getHeader(IF_MATCH)
        val ifNoneMatch = request.getHeader(IF_NONE_MATCH)
        preconditions += ifMatch ?: ifNoneMatch

        if (tooLarge) return error(413, "VAULT_TOO_LARGE", "The vault ciphertext exceeds the configured size cap.")
        if (serverMediumInactive) {
            return error(
                409, "VAULT_SERVER_MEDIUM_INACTIVE",
                "The server vault medium is inactive; stage and promote a candidate instead.",
            )
        }

        val body = request.body.readByteArray()
        if (body.isEmpty()) return error(400, CODE_MALFORMED, "The vault write body must be non-empty envelope bytes.")

        // `vaultRoutes.ts:440-449` — a precondition is mandatory, and this 428 is
        // the one branch the client must never be able to reach.
        val expected: Int? = when {
            ifNoneMatch?.trim() == "*" -> null
            ifMatch != null -> parseEtag(ifMatch)
                ?: return error(412, CODE_PRECONDITION_FAILED, "The vault precondition did not match.")

            else -> return error(
                428, "VAULT_PRECONDITION_REQUIRED",
                "A vault write requires an If-Match (replace) or If-None-Match: * (create) precondition.",
            )
        }

        val incomingVersion = envelopeVersionOf(body)
            ?: return error(400, CODE_MALFORMED, "bad vault envelope magic")

        // Live finding 2: the version gate runs BEFORE the CAS comparison.
        if (expected != null && incomingVersion <= expected) {
            return error(400, CODE_MALFORMED, "envelope vaultVersion does not advance the If-Match version")
        }

        val current = currentVersion
        val lost = (expected == null && current != null) || (expected != null && expected != current)
        if (lost) {
            // Live finding 1: the loser is told the winner's version.
            return error(412, CODE_PRECONDITION_FAILED, "The vault precondition did not match the current version.")
                .apply { if (current != null) setHeader(ETAG, etag(current)) }
        }

        versions[incomingVersion] = StoredVault(body, createdAt = "2026-08-04T22:30:00.000Z")
        return MockResponse().setResponseCode(204).setHeader(ETAG, etag(incomingVersion))
    }

    private fun mediaState(): MockResponse {
        // `paranoidMediaStateResponseSchema` enforces the pairing: a normal
        // account has no media state, a paranoid one always does.
        val body = if (privacyMode == PRIVACY_PARANOID) {
            val set = mediaSet.joinToString(",") { "\"$it\"" }
            """{"privacyMode":"paranoid","mediaState":{"mediaSet":[$set],""" +
                """"driveAttestedVersion":null,""" +
                """"server":{"disposition":"active","candidate":null,"retired":null}}}"""
        } else {
            """{"privacyMode":"normal","mediaState":null}"""
        }
        return json(200, body)
    }

    private fun historyList(): MockResponse {
        if (privacyMode != PRIVACY_PARANOID) {
            return error(403, CODE_MODE_REQUIRED, "Vault history is available only while paranoid mode is active.")
        }
        // Live finding 3: only SUPERSEDED versions are retained, newest first.
        val live = currentVersion
        val items = versions.entries
            .filter { it.key != live }
            .sortedByDescending { it.key }
            .joinToString(",") { (version, stored) ->
                """{"version":$version,"createdAt":"${stored.createdAt}",""" +
                    """"sizeBytes":${stored.envelope.size},"medium":"server"}"""
            }
        return json(200, """{"items":[$items],"nextCursor":null}""")
    }

    private fun historyVersion(version: Int?): MockResponse {
        if (privacyMode != PRIVACY_PARANOID) {
            return error(403, CODE_MODE_REQUIRED, "Vault history is available only while paranoid mode is active.")
        }
        val stored = version?.takeIf { it != currentVersion }?.let { versions[it] }
            ?: return error(404, CODE_NOT_FOUND, "No retained vault version found.")
        return MockResponse()
            .setResponseCode(200)
            .setHeader(ETAG, etag(version))
            .setHeader("Content-Type", OCTET_STREAM)
            .setHeader("Cache-Control", "private, no-store")
            .setHeader(HEADER_CREATED_AT, stored.createdAt)
            .setHeader("X-BetterTrack-Vault-Size-Bytes", stored.envelope.size.toString())
            .setHeader("X-BetterTrack-Vault-Medium", "server")
            .setBody(okio.Buffer().write(stored.envelope))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun error(code: Int, errorCode: String, message: String) =
        json(code, """{"error":{"code":"$errorCode","message":"${message.replace("\"", "\\\"")}"}}""")

    private class StoredVault(val envelope: ByteArray, val createdAt: String)

    companion object {
        /** `VAULT_HISTORY_CREATED_AT_HEADER` — history responses only, on the live server. */
        const val HEADER_CREATED_AT = "X-BetterTrack-Vault-Created-At"

        /** What [metadataHeadersOnMainGet] claims; nothing in the app may believe it. */
        const val CONTRADICTORY_CREATED_AT = "1999-12-31T23:59:59.000Z"

        private const val VAULT_PATH = "/api/v1/vault"
        private const val ETAG = "ETag"
        private const val IF_MATCH = "If-Match"
        private const val IF_NONE_MATCH = "If-None-Match"
        private const val OCTET_STREAM = "application/octet-stream"

        private const val PRIVACY_NORMAL = "normal"
        private const val PRIVACY_PARANOID = "paranoid"
        private const val MEDIUM_SERVER = "server"

        private const val CODE_NOT_FOUND = "VAULT_NOT_FOUND"
        private const val CODE_MALFORMED = "VAULT_MALFORMED"
        private const val CODE_PRECONDITION_FAILED = "VAULT_PRECONDITION_FAILED"
        private const val CODE_MODE_REQUIRED = "VAULT_PARANOID_MODE_REQUIRED"

        private fun etag(version: Int) = "\"$version\""

        private fun parseEtag(value: String): Int? =
            value.trim().removePrefix("W/").trim('"').toIntOrNull()

        /**
         * Reads `header.vaultVersion` the way the server does — the fake must
         * refuse a non-advancing envelope, so it has to actually parse one.
         * `BTVAULT1` ‖ uint32 BE headerLength ‖ headerJson ‖ ciphertext.
         */
        private fun envelopeVersionOf(bytes: ByteArray): Int? {
            if (bytes.size < 12 || bytes.decodeToString(0, 8) != "BTVAULT1") return null
            val headerLength = ((bytes[8].toInt() and 0xFF) shl 24) or
                ((bytes[9].toInt() and 0xFF) shl 16) or
                ((bytes[10].toInt() and 0xFF) shl 8) or
                (bytes[11].toInt() and 0xFF)
            if (headerLength <= 0 || 12 + headerLength > bytes.size) return null
            val header = bytes.decodeToString(12, 12 + headerLength)
            return Regex("\"vaultVersion\"\\s*:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
