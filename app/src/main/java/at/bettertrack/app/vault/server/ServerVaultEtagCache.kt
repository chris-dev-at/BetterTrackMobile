package at.bettertrack.app.vault.server

import at.bettertrack.app.vault.DataHomeMedium

/**
 * The conditional-read cache behind `GET /vault` — one validator and **the exact
 * bytes it names**, per medium.
 *
 * The platform 304s a `GET /vault` whose `If-None-Match` matches the stored
 * version (`vaultRoutes.ts:427-431`, verified live on the dev backend during S5).
 * Adopting that saves re-downloading an unchanged envelope on every status probe
 * and every sync pass, which on a paranoid account is the single chattiest read
 * the app makes.
 *
 * ## The rules this class exists to enforce
 *
 * They are the same ones [at.bettertrack.app.data.api.ConditionalGetInterceptor]
 * spells out for the S2a endpoints, restated here because the vault is *not* on
 * that interceptor — its allowlist deliberately excludes `/vault`, since it
 * buffers bodies for search/portfolio reads and knows nothing about the version
 * invariant below.
 *
 *  1. **Validator and payload live and die together.** An ETag is only usable
 *     while we still hold the body it belongs to, so this store is in-memory
 *     only. Nothing is ever persisted: a validator whose body did not survive a
 *     process restart would ask the server to skip sending data we no longer
 *     have.
 *  2. **A 304 we cannot honour is not an empty read.** Dropping the entry and
 *     refetching *without* the validator is the caller's job
 *     ([ServerVaultDataHome.read]); this class only ever answers honestly about
 *     what it holds.
 *  3. **A 200 with a new ETag replaces the body.** [remember] overwrites, and a
 *     response that cannot satisfy the version invariant clears the entry rather
 *     than leaving a stale one behind.
 *  4. **Thread safety.** Vault reads race — a sync pass, a settings probe and a
 *     restore can all be in flight — so every access is synchronized.
 *  5. **It dies with the account.** [clear] is called from the account teardown
 *     that already clears the S2a interceptor and the paranoid flag; no
 *     ciphertext may outlive the session it was fetched under.
 *
 * ## The version invariant
 *
 * On this route the ETag **is** the vault version, and
 * [ServerVaultDataHome] treats an absent or disagreeing one as corruption rather
 * than a detail — a wrong CAS token silently overwrites another device's work.
 * So an entry is only ever stored when the caller has already proven the
 * envelope's own `header.vaultVersion` equals the version the ETag claims, and
 * [cached] refuses to answer a 304 whose ETag names a different version from the
 * one it holds. The bytes served from here therefore carry the version the ETag
 * claims, exactly as a live 200 does.
 *
 * ## Not the same header as `write()`
 *
 * `ServerVaultDataHome.write` also sends `If-None-Match`, as the RFC "create if
 * absent" wildcard (`*`) that makes a `PUT` a create rather than a replace. That
 * is a different use of the same header on a different verb; this cache is
 * consulted on `GET` only, and a write drops its entry before the `PUT` goes out
 * so no body can survive a mutation attempt.
 */
class ServerVaultEtagCache(
    /**
     * Bodies above this are simply not cached. The live cap on a vault is 16 MiB
     * (`413 VAULT_TOO_LARGE`); holding one of those forever to save a round trip
     * would be a bad trade, and a real portfolio's envelope is orders of
     * magnitude smaller.
     */
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {

    private class Entry(val etag: String, val version: Int, val envelope: ByteArray)

    /** Keyed by medium so a future second blob medium cannot share a validator. */
    private val entries = HashMap<DataHomeMedium, Entry>()

    /** The `If-None-Match` value to send, or `null` when nothing is held. */
    fun validator(medium: DataHomeMedium): String? = synchronized(entries) { entries[medium]?.etag }

    /**
     * The bytes a `304` may be answered with, or `null` when this cache cannot
     * honour it.
     *
     * [responseEtag] is the validator the *server* echoed. When it names a
     * different version from the body held here the answer is `null`, not the
     * body: a 304 that disagrees with its own validator is the one shape that
     * could hand the caller bytes under a CAS token they do not belong to.
     */
    fun cached(medium: DataHomeMedium, responseEtag: String?): CachedVaultBytes? = synchronized(entries) {
        val entry = entries[medium] ?: return null
        if (responseEtag != null && parseVaultEtag(responseEtag) != entry.version) return null
        CachedVaultBytes(etag = entry.etag, envelope = entry.envelope)
    }

    /**
     * Store the body a `200` produced, under the ETag it arrived with.
     *
     * [version] is the version the caller already proved the envelope carries.
     * Anything that cannot satisfy the invariant — no ETag, an unparseable one,
     * one that disagrees with the envelope, or a body too large to keep — clears
     * the entry instead, so the next read is an ordinary unconditional fetch.
     */
    fun remember(medium: DataHomeMedium, etag: String?, envelope: ByteArray, version: Int) {
        val claimed = parseVaultEtag(etag)
        if (etag == null || claimed == null || claimed != version || envelope.size > maxBytes) {
            forget(medium)
            return
        }
        // Copied on the way in: the cache must own its bytes outright, or a
        // caller that reused its buffer would poison a body we later serve as
        // though the server had just sent it.
        synchronized(entries) { entries[medium] = Entry(etag, version, envelope.copyOf()) }
    }

    /** Drops this medium's entry — an absent vault, a write, or a 304 we lost the body for. */
    fun forget(medium: DataHomeMedium) {
        synchronized(entries) { entries.remove(medium) }
    }

    /** Drops everything. Account teardown / logout — see rule 5. */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** How many bodies are held. Diagnostics and tests only. */
    fun size(): Int = synchronized(entries) { entries.size }

    private companion object {
        const val DEFAULT_MAX_BYTES = 4 * 1024 * 1024
    }
}

/**
 * A cached envelope and the validator it belongs to, handed out together because
 * neither is meaningful without the other.
 *
 * [envelope] is the cache's own array. Vault envelopes are opaque and immutable
 * everywhere in this package; nothing may write into it.
 */
class CachedVaultBytes(val etag: String, val envelope: ByteArray)
