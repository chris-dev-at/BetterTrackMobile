package at.bettertrack.app.vault.pv.store

import java.security.MessageDigest

/**
 * **The `writeId` → bytes binding, held locally.**
 *
 * `writeId` is the store's idempotency key, and the contract binds it to a byte
 * string rather than to an attempt:
 *
 * - same `writeId` + **same** bytes ⇒ converges; no duplicate history row. This
 *   is what makes retrying a write whose response was lost SAFE, and it is the
 *   reason a retry must NOT invent a new key.
 * - same `writeId` + **different** bytes ⇒ refused `412`, deliberately: it stops
 *   a replayed old write from clobbering current state when a client-owned
 *   `docVersion` cycles back.
 *
 * This ledger is the client side of that rule. It remembers, per
 * `(vaultId, docId)`, which `writeId` was last sent and the digest of the bytes
 * it was sent with, and answers one question: *would this write reuse a key
 * against different bytes?*
 *
 * ## Why it earns its place
 *
 * Two reasons, and the second is the one that matters.
 *
 * 1. It turns the dangerous case into a **local, immediate refusal**: the caller
 *    is told to mint a new key before a request is sent, instead of after a round
 *    trip that could never have succeeded.
 * 2. It lets `PvBlobStore` tell the two `412`s apart **without an error string
 *    the deployed OpenAPI does not publish**. `ApiError.code` is typed as a bare
 *    `string` in the schema, so this client has no contract-backed name for the
 *    replay refusal; guessing one would be exactly the invented-field mistake the
 *    build rules forbid. The ledger supplies the answer from a fact this client
 *    owns instead.
 *
 * ## What it deliberately is not
 *
 * Process-local and bounded, not durable. A key minted on another device is a
 * different uuid and cannot collide; a key minted by this device in an earlier
 * process is not remembered, and the classification falls back to the
 * response-shaped rule in `PvBlobStore.classifyPreconditionFailure` — which
 * defaults to the replay verdict precisely because its remedy also repairs a
 * stale precondition, while the converse loops forever.
 *
 * Persisting it would be a Room table this round is explicitly not scoped to
 * build, and one whose value is small: a fresh `writeId` per attempt (which
 * [PvBlobStore] callers get by re-encrypting) already makes the cross-restart
 * case vanishingly rare.
 */
class PvWriteIdLedger(
    /** How many `(vaultId, docId)` addresses to remember. Bounded, LRU-evicted. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private data class Key(val vaultId: String, val docId: String)

    private data class Entry(val writeId: String, val digest: String)

    private val entries = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
            size > capacity
    }

    /**
     * True when [writeId] was already used at this address for **different**
     * bytes — the case the server refuses and a caller must not retry as written.
     *
     * False for a first use and false for an identical re-send, which is the
     * convergent retry the idempotency key exists to allow.
     */
    @Synchronized
    fun isReplayWithDifferentBytes(vaultId: String, docId: String, writeId: String, envelope: ByteArray): Boolean {
        val held = entries[Key(vaultId, docId)] ?: return false
        return held.writeId == writeId && held.digest != digestOf(envelope)
    }

    /** Bind [writeId] to these bytes at this address. Called once a write is sent. */
    @Synchronized
    fun record(vaultId: String, docId: String, writeId: String, envelope: ByteArray) {
        entries[Key(vaultId, docId)] = Entry(writeId, digestOf(envelope))
    }

    /** Drop everything — account teardown: no ciphertext digest outlives its session. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** How many addresses are currently remembered. Diagnostics and tests only. */
    @Synchronized
    fun size(): Int = entries.size

    private fun digestOf(envelope: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(envelope).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            HEX[value ushr 4].toString() + HEX[value and 0x0f]
        }

    companion object {
        private const val DEFAULT_CAPACITY: Int = 64
        private const val HEX: String = "0123456789abcdef"
    }
}
