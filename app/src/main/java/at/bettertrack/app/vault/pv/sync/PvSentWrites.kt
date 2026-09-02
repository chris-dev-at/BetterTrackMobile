package at.bettertrack.app.vault.pv.sync

/**
 * **The `writeId`s this process has SENT at each address** — the half of the
 * `412` disambiguation that `PvWriteIdLedger` deliberately cannot answer.
 *
 * ## The case this exists for
 *
 * A write goes out and the response is lost. `PvDocWriteOutcome.Transport`
 * declares itself `indeterminate` for exactly this reason: *"a lost response may
 * still have committed"*. On the next pass the doc is sealed again and CAS'd
 * against the cursor the client still holds — which, if the earlier attempt DID
 * commit, is now stale. The server answers `412`, and that `412` is
 * indistinguishable from another device having written in between.
 *
 * It is not indistinguishable to this client, though, because the envelope
 * header is cleartext: the remote doc carries the `writeId` of whoever wrote it.
 * If that key is one THIS process sent at THIS address, the earlier attempt
 * landed and the remedy is to advance the cursor — not to merge with, and
 * re-merge into, this device's own bytes.
 *
 * ## Why not `PvWriteIdLedger`
 *
 * That ledger answers a different question (*"would this write reuse a key
 * against DIFFERENT bytes?"*) and it records only after a **successful**
 * response — which is precisely the case that did not happen here. Widening it
 * would blur two questions with two remedies into one object; a second, tiny,
 * clearly-named memory is cheaper to reason about than a general one.
 *
 * Process-local and bounded, like the ledger and for the same reasons: keys
 * minted by another device are different uuids and cannot collide, and a key
 * from an earlier process is simply not recognised — which costs one unnecessary
 * merge of a doc against its own content, and the §6 rules make that a no-op.
 */
class PvSentWrites(
    /** How many `(vaultId, docId, medium)` addresses to remember. */
    private val addressCapacity: Int = DEFAULT_ADDRESS_CAPACITY,
    /** How many recent keys to remember per address. */
    private val perAddressCapacity: Int = DEFAULT_PER_ADDRESS_CAPACITY,
) {

    private data class Key(val vaultId: String, val docId: String, val medium: PvMedium)

    private val entries = object : LinkedHashMap<Key, LinkedHashSet<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, LinkedHashSet<String>>?): Boolean =
            size > addressCapacity
    }

    /** Called BEFORE the request goes out — a lost response must still be recognisable. */
    @Synchronized
    fun record(vaultId: String, docId: String, medium: PvMedium, writeId: String) {
        val held = entries.getOrPut(Key(vaultId, docId, medium)) { LinkedHashSet() }
        held.add(writeId)
        while (held.size > perAddressCapacity) held.remove(held.first())
    }

    /** True when [writeId] is one this process sent at this address. */
    @Synchronized
    fun sentHere(vaultId: String, docId: String, medium: PvMedium, writeId: String): Boolean =
        entries[Key(vaultId, docId, medium)]?.contains(writeId) == true

    /** Account teardown / vault removal. */
    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun forgetVault(vaultId: String) {
        entries.keys.filter { it.vaultId == vaultId }.forEach { entries.remove(it) }
    }

    private companion object {
        const val DEFAULT_ADDRESS_CAPACITY = 64
        const val DEFAULT_PER_ADDRESS_CAPACITY = 4
    }
}
