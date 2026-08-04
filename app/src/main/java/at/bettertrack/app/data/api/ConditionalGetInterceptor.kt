package at.bettertrack.app.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * V5 S2a — conditional GETs on the three endpoints the platform ETags:
 * `GET /portfolios/{id}`, `GET /portfolios/{id}/history`, `GET /search`.
 *
 * The app remembers the weak ETag it last saw for a URL together with that
 * response's body. The next GET to the same URL carries `If-None-Match`; when
 * the server answers **304 Not Modified** the interceptor replays the stored
 * body as a normal 200, so:
 *
 *  - every repository keeps caching verbatim exactly as before — **no call site
 *    changes**, and "304" means precisely "keep what you have";
 *  - the win is real: the server sends headers only, not a 50 KB history blob.
 *
 * **`If-None-Match` ONLY.** `If-Modified-Since` is deliberately never sent: the
 * platform does not 304 on it for live-today data (the second-granularity clock
 * would make a same-second change invisible), so sending it would be at best
 * useless and at worst a stale read.
 *
 * The store is **in-memory and bounded**. That is a correctness requirement, not
 * just thrift: an ETag is only usable while we still hold the body it belongs
 * to, so validator and payload must live and die together. After a process
 * restart the cache is empty, no `If-None-Match` goes out, and the first call
 * simply fetches normally.
 */
class ConditionalGetInterceptor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : Interceptor {

    private class Entry(val etag: String, val body: ByteArray, val contentType: MediaType?)

    /** Access-ordered LRU; synchronized because OkHttp calls this from many threads. */
    private val store = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            // Count-cap evictions must keep the byte accounting honest too —
            // otherwise totalBytes drifts up until the budget loop evicts every
            // insert and the cache silently stops producing 304 hits.
            val evict = size > maxEntries
            if (evict) totalBytes -= eldest.value.body.size
            return evict
        }
    }

    private var totalBytes: Long = 0

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (request.method != "GET" || !isConditionalGetTarget(request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val cached = synchronized(store) { store[url] }
        val toSend = if (cached != null && request.header(HEADER_IF_NONE_MATCH) == null) {
            request.newBuilder().header(HEADER_IF_NONE_MATCH, cached.etag).build()
        } else {
            request
        }

        val response = chain.proceed(toSend)

        // 304 → replay the stored body as a 200 so callers never see a null body.
        if (response.code == 304 && cached != null) {
            Log.d(TAG, "304 Not Modified — serving cached body for ${request.url.encodedPath}")
            response.close()
            return response.newBuilder()
                .code(200)
                .message("OK (cached, 304)")
                .protocol(response.protocol ?: Protocol.HTTP_1_1)
                .removeHeader(HEADER_CONTENT_LENGTH)
                .header(HEADER_BT_FROM_ETAG_CACHE, "1")
                .body(cached.body.toResponseBody(cached.contentType))
                .build()
        }

        // A 304 we cannot honour (cache evicted between request and response):
        // drop the validator and refetch, rather than handing back an empty body.
        if (response.code == 304) {
            Log.w(TAG, "304 without a cached body — refetching ${request.url.encodedPath}")
            response.close()
            synchronized(store) { remove(url) }
            return chain.proceed(request.newBuilder().removeHeader(HEADER_IF_NONE_MATCH).build())
        }

        if (response.isSuccessful) {
            val etag = response.header(HEADER_ETAG)
            if (!etag.isNullOrBlank()) rememberBody(url, etag, response)?.let { return it }
        }
        return response
    }

    /**
     * Buffer a successful body so a later 304 can replay it, and return a
     * replacement response carrying an equivalent body (the original is
     * single-shot). Returns null when the body is absent or too large to keep.
     */
    private fun rememberBody(url: String, etag: String, response: Response): Response? {
        val body = response.body ?: return null
        val bytes = try {
            body.bytes()
        } catch (e: Exception) {
            Log.w(TAG, "Could not buffer body for ETag storage: ${e.message}")
            return null
        }
        val contentType = body.contentType()

        if (bytes.size <= maxTotalBytes) {
            synchronized(store) {
                remove(url)
                store[url] = Entry(etag, bytes, contentType)
                totalBytes += bytes.size
                // Evict oldest until the byte budget holds (the count cap is
                // handled by removeEldestEntry, which does not know sizes).
                val it = store.entries.iterator()
                while (totalBytes > maxTotalBytes && it.hasNext()) {
                    val e = it.next()
                    if (e.key == url) continue
                    totalBytes -= e.value.body.size
                    it.remove()
                }
            }
        }

        return response.newBuilder()
            .body(bytes.toResponseBody(contentType))
            .build()
    }

    /** Must be called with [store] held. */
    private fun remove(url: String) {
        store.remove(url)?.let { totalBytes -= it.body.size }
    }

    /** Drops everything — used on logout/account-switch so no body outlives a session. */
    fun clear() {
        synchronized(store) {
            store.clear()
            totalBytes = 0
        }
    }

    /** Diagnostics for the hidden dev screen: how many bodies are held. */
    fun size(): Int = synchronized(store) { store.size }

    companion object {
        private const val TAG = "BtETag"
        const val HEADER_ETAG = "ETag"
        const val HEADER_IF_NONE_MATCH = "If-None-Match"
        const val HEADER_CONTENT_LENGTH = "Content-Length"

        /** Marks a response the interceptor replayed from a 304 (tests + logs). */
        const val HEADER_BT_FROM_ETAG_CACHE = "X-BT-From-ETag-Cache"

        private const val DEFAULT_MAX_ENTRIES = 24
        private const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024

        /**
         * Only the endpoints the platform actually ETags. Kept an explicit
         * allowlist: buffering every GET body would cost memory for nothing, and
         * a surprise ETag on a mutation-adjacent read must not become a stale read.
         *
         * Paths are matched on the `/api/v1` prefix the app's base URL produces:
         *   `/api/v1/portfolios/{id}`, `/api/v1/portfolios/{id}/history`, `/api/v1/search`
         */
        fun isConditionalGetTarget(encodedPath: String): Boolean {
            val path = encodedPath.removeSuffix("/")
            if (path.endsWith("/search")) return true
            val idx = path.indexOf("/portfolios/")
            if (idx < 0) return false
            val rest = path.substring(idx + "/portfolios/".length)
            if (rest.isEmpty()) return false
            val segments = rest.split("/")
            return when (segments.size) {
                1 -> segments[0].isNotEmpty()                       // /portfolios/{id}
                2 -> segments[0].isNotEmpty() && segments[1] == "history"
                else -> false
            }
        }
    }
}
