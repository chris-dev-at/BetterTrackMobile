package at.bettertrack.app.data.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iOS mirror of Android `ConditionalGetInterceptor`'s store: an in-memory,
 * bounded, ACCESS-ORDERED LRU of (weak ETag, body bytes, content-type) keyed by
 * full URL. Same bounds as Android — 24 entries / 2 MB — because the ETag is only
 * usable while we still hold the body it validates, so validator and payload must
 * live and die together. In-memory ONLY: after process restart it is empty.
 *
 * Kotlin/Native `LinkedHashMap` has no access-order constructor (unlike the JVM
 * one Android relies on), so access-ordering is maintained by hand: a read HIT
 * re-inserts the entry at the tail (most-recently-used); eviction removes from
 * the head (least-recently-used). A coroutines [Mutex] guards it — the plugin
 * touches it from suspend context on possibly-many dispatcher threads.
 */
class ConditionalGetCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {
    class Entry(val etag: String, val body: ByteArray, val contentType: String?)

    private val store = LinkedHashMap<String, Entry>()
    private var totalBytes: Long = 0
    private val mutex = Mutex()

    /** A read hit moves the entry to MRU, mirroring the JVM access-order LRU. */
    suspend fun get(url: String): Entry? = mutex.withLock {
        val e = store.remove(url) ?: return@withLock null
        store[url] = e
        e
    }

    suspend fun size(): Int = mutex.withLock { store.size }

    /** Drops everything — logout/account-switch, so no body outlives a session. */
    suspend fun clear() = mutex.withLock {
        store.clear()
        totalBytes = 0
    }

    suspend fun remove(url: String) = mutex.withLock { removeLocked(url) }

    /** Must hold [mutex]. Keeps the byte accounting honest on every removal. */
    private fun removeLocked(url: String) {
        store.remove(url)?.let { totalBytes -= it.body.size }
    }

    /**
     * Buffer a successful body so a later 304 can replay it. Mirrors Android's
     * `rememberBody`: gated on 2xx by the CALLER (the plugin), stores only when
     * the single body fits the budget, then evicts oldest — first on the count
     * cap, then on the byte cap, never evicting the entry just inserted.
     */
    suspend fun put(url: String, etag: String, body: ByteArray, contentType: String?) =
        mutex.withLock {
            removeLocked(url)
            if (body.size > maxTotalBytes) return@withLock // too large to keep at all
            store[url] = Entry(etag, body, contentType)
            totalBytes += body.size
            // Count cap: evict eldest (head) until within maxEntries.
            while (store.size > maxEntries) {
                val eldest = store.keys.firstOrNull() ?: break
                if (eldest == url) break
                removeLocked(eldest)
            }
            // Byte cap: evict oldest (skipping the just-inserted url) until it holds.
            val it = store.keys.iterator()
            while (totalBytes > maxTotalBytes && it.hasNext()) {
                val key = it.next()
                if (key == url) continue
                totalBytes -= store.getValue(key).body.size
                it.remove()
            }
        }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 24
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024
    }
}
