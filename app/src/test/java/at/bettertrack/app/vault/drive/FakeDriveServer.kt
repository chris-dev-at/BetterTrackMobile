package at.bettertrack.app.vault.drive

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * A tiny in-memory Google Drive `appDataFolder`, served over MockWebServer.
 *
 * ## Why a stateful fake and not enqueued canned responses
 *
 * [DriveDataHome]'s whole value is a *sequence*: list → re-GET → PATCH →
 * re-list → download-and-compare. Canned responses would let that sequence be
 * asserted only as "N requests happened", and would keep passing if the adapter
 * built a wrong URL, sent a malformed multipart body, or compared the wrong
 * revision. A fake that actually stores files makes those failures real: a
 * mis-parsed multipart body stores wrong bytes and the round-trip comparison
 * catches it, a wrong `files.list` query returns nothing and the write reports
 * absent.
 *
 * It models only what the adapter uses — `files.list` (with a `name = '…'`
 * filter), `files.get` (metadata and `alt=media`), and multipart create/update —
 * plus the failure injections the plan names: a full Drive
 * (`storageQuotaExceeded`), an expired token, and duplicate replicas.
 *
 * Real HTTP throughout, so URL construction, query encoding, header placement
 * and body framing are all exercised rather than assumed.
 */
class FakeDriveServer(private val expectedFileName: String) {

    val server = MockWebServer()

    /** `id → file`, in insertion order so duplicate ordering is deterministic. */
    private val files = LinkedHashMap<String, DriveFileState>()

    private val nextId = AtomicInteger(1)
    private val nextRevision = AtomicInteger(1)

    /** Set to make every subsequent request answer 403 `storageQuotaExceeded`. */
    var quotaExceeded: Boolean = false

    /** Set to make every subsequent request answer 401. */
    var tokenExpired: Boolean = false

    /** Set to make `files.list` answer a body that is not a file list at all. */
    var malformedListing: Boolean = false

    /**
     * Simulates another device winning the CAS race: runs once, immediately
     * before the next metadata re-read, so the adapter observes movement between
     * its `list` and its `get` exactly as it would in life.
     */
    var onBeforeMetadataRefresh: (() -> Unit)? = null

    /** Every Authorization header the adapter sent, for the token assertions. */
    val authorizations = mutableListOf<String?>()

    /** Requests received, as `METHOD path`. */
    val requestLog = mutableListOf<String>()

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    fun shutdown() = server.shutdown()

    fun apiBase() = server.url("/drive/v3")

    fun uploadBase() = server.url("/upload/drive/v3")

    // ── Seeding / inspection ────────────────────────────────────────────────

    /** Puts a file into the folder as if another device had written it. */
    fun seed(envelope: ByteArray, version: Int, name: String = expectedFileName): String {
        val id = "file-${nextId.getAndIncrement()}"
        files[id] = DriveFileState(
            id = id,
            name = name,
            bytes = envelope,
            version = version,
            formatVersion = 1,
            headRevisionId = "rev-${nextRevision.getAndIncrement()}",
            modifiedTime = "2026-08-04T10:0${files.size}:00.000Z",
        )
        return id
    }

    /**
     * Replaces an existing file's bytes, version and revision — another device
     * writing to the SAME object, which is the movement the adapter's re-`GET`
     * before its `PATCH` exists to catch.
     */
    fun mutate(id: String, envelope: ByteArray, version: Int) {
        val existing = files.getValue(id)
        files[id] = existing.copy(
            bytes = envelope,
            version = version,
            headRevisionId = "rev-${nextRevision.getAndIncrement()}",
            modifiedTime = "2026-08-04T13:00:00.000Z",
        )
    }

    fun fileCount(): Int = files.size

    fun storedBytes(): List<ByteArray> = files.values.map { it.bytes }

    fun storedVersions(): List<Int> = files.values.map { it.version }

    fun appPropertiesOf(id: String): Map<String, String> =
        files.getValue(id).let { mapOf("vaultVersion" to it.version.toString(), "formatVersion" to it.formatVersion.toString()) }

    // ── Dispatch ────────────────────────────────────────────────────────────

    private fun handle(request: RecordedRequest): MockResponse {
        authorizations += request.getHeader("Authorization")
        requestLog += "${request.method} ${request.path?.substringBefore('?')}"

        if (tokenExpired) return MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}""")
        if (quotaExceeded) return quotaResponse()

        val path = request.requestUrl?.encodedPath.orEmpty()
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> list(request)
            request.method == "GET" && path.startsWith("/drive/v3/files/") -> get(request, path)
            request.method == "POST" && path == "/upload/drive/v3/files" -> create(request)
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> update(request, path)
            request.method == "DELETE" && path.startsWith("/drive/v3/files/") -> delete(path)
            else -> MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
        }
    }

    private fun list(request: RecordedRequest): MockResponse {
        if (malformedListing) return json("""{"files":"not-an-array"}""")
        val url = request.requestUrl ?: return json("""{"files":[]}""")
        // The adapter must scope its query to appDataFolder; a fake that ignored
        // that would hide a least-privilege regression.
        if (url.queryParameter("spaces") != "appDataFolder") {
            return MockResponse().setResponseCode(400).setBody("""{"error":{"code":400}}""")
        }
        val query = url.queryParameter("q").orEmpty()
        val name = Regex("name = '([^']*)'").find(query)?.groupValues?.get(1)
        val matches = files.values.filter { name == null || it.name == name }
        return json("""{"files":[${matches.joinToString(",") { it.toJson() }}]}""")
    }

    private fun get(request: RecordedRequest, path: String): MockResponse {
        val id = path.removePrefix("/drive/v3/files/")
        if (request.requestUrl?.queryParameter("alt") == "media") {
            val file = files[id] ?: return MockResponse().setResponseCode(404).setBody("")
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(Buffer().write(file.bytes))
        }
        onBeforeMetadataRefresh?.let { hook ->
            onBeforeMetadataRefresh = null
            hook()
        }
        val file = files[id] ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
        return json(file.toJson())
    }

    private fun create(request: RecordedRequest): MockResponse {
        val parsed = parseMultipart(request) ?: return badRequest()
        val id = "file-${nextId.getAndIncrement()}"
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(parsed.metadataJson)?.groupValues?.get(1)
            ?: return badRequest()
        files[id] = DriveFileState(
            id = id,
            name = name,
            bytes = parsed.body,
            version = parsed.vaultVersion,
            formatVersion = parsed.formatVersion,
            headRevisionId = "rev-${nextRevision.getAndIncrement()}",
            modifiedTime = "2026-08-04T11:00:00.000Z",
        )
        return json(files.getValue(id).toJson())
    }

    private fun update(request: RecordedRequest, path: String): MockResponse {
        val id = path.removePrefix("/upload/drive/v3/files/")
        val existing = files[id] ?: return MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
        val parsed = parseMultipart(request) ?: return badRequest()
        files[id] = existing.copy(
            bytes = parsed.body,
            version = parsed.vaultVersion,
            formatVersion = parsed.formatVersion,
            headRevisionId = "rev-${nextRevision.getAndIncrement()}",
            modifiedTime = "2026-08-04T12:00:00.000Z",
        )
        return json(files.getValue(id).toJson())
    }

    private fun delete(path: String): MockResponse {
        val id = path.removePrefix("/drive/v3/files/")
        return if (files.remove(id) != null) {
            MockResponse().setResponseCode(204)
        } else {
            MockResponse().setResponseCode(404).setBody("""{"error":{"code":404}}""")
        }
    }

    /**
     * Parses `multipart/related` the way Drive does — which is the assertion
     * that matters most in this file. If [DriveDataHome] got a CRLF, a boundary
     * marker or a part order wrong, this returns null and the write fails.
     */
    private fun parseMultipart(request: RecordedRequest): ParsedUpload? {
        val contentType = request.getHeader("Content-Type") ?: return null
        val boundary = contentType.substringAfter("boundary=", "").takeIf { it.isNotEmpty() } ?: return null
        if (!contentType.startsWith("multipart/related")) return null

        val body = request.body.readByteArray()
        val delimiter = "\r\n--$boundary".toByteArray()
        val opening = "--$boundary\r\n".toByteArray()
        if (!body.startsWith(opening)) return null

        val headerEnd = body.indexOf("\r\n\r\n".toByteArray(), opening.size).takeIf { it >= 0 } ?: return null
        val metadataStart = headerEnd + 4
        val metadataEnd = body.indexOf(delimiter, metadataStart).takeIf { it >= 0 } ?: return null
        val metadataJson = String(body, metadataStart, metadataEnd - metadataStart, Charsets.UTF_8)

        val secondHeaderEnd = body.indexOf("\r\n\r\n".toByteArray(), metadataEnd).takeIf { it >= 0 } ?: return null
        val bodyStart = secondHeaderEnd + 4
        val bodyEnd = body.indexOf(delimiter, bodyStart).takeIf { it >= 0 } ?: return null

        val vaultVersion = Regex("\"vaultVersion\"\\s*:\\s*\"(\\d+)\"").find(metadataJson)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val formatVersion = Regex("\"formatVersion\"\\s*:\\s*\"(\\d+)\"").find(metadataJson)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null

        return ParsedUpload(
            metadataJson = metadataJson,
            body = body.copyOfRange(bodyStart, bodyEnd),
            vaultVersion = vaultVersion,
            formatVersion = formatVersion,
        )
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun badRequest() = MockResponse().setResponseCode(400).setBody("""{"error":{"code":400}}""")

    private fun quotaResponse() = MockResponse()
        .setResponseCode(403)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"error":{"code":403,"message":"The user's Drive storage quota has been exceeded.",""" +
                """"errors":[{"domain":"usageLimits","reason":"storageQuotaExceeded",""" +
                """"message":"The user's Drive storage quota has been exceeded."}]}}"""
        )

    private data class ParsedUpload(
        val metadataJson: String,
        val body: ByteArray,
        val vaultVersion: Int,
        val formatVersion: Int,
    )

    private data class DriveFileState(
        val id: String,
        val name: String,
        val bytes: ByteArray,
        val version: Int,
        val formatVersion: Int,
        val headRevisionId: String,
        val modifiedTime: String,
    ) {
        fun toJson(): String = """
            {"id":"$id","name":"$name","size":"${bytes.size}","modifiedTime":"$modifiedTime",
             "headRevisionId":"$headRevisionId",
             "appProperties":{"vaultVersion":"$version","formatVersion":"$formatVersion"}}
        """.trimIndent().replace("\n", "")
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from
    outer@ for (start in from..size - needle.size) {
        for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
        return start
    }
    return -1
}
