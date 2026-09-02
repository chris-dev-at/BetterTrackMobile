package at.bettertrack.app.vault.pv.custody

import android.util.Log
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.pv.keys.pvBip39Seed
import at.bettertrack.app.vault.pv.keys.pvUnwrapContentKey
import at.bettertrack.app.vault.pv.keys.pvVaultWrapKey
import at.bettertrack.app.vault.pv.sync.PvUnlockedVault
import at.bettertrack.app.vault.pv.sync.PvVaultHeaderFacts
import at.bettertrack.app.vault.pv.sync.PvVaultKeyFacts
import at.bettertrack.app.vault.pv.sync.PvVaultKeys
import at.bettertrack.app.vault.zeroBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **The open vaults of this endpoint** — [PvVaultKeys] over §12 custody.
 *
 * ## What it actually does
 *
 * It runs the §4 chain, once per vault, and remembers the result for as long as
 * the session lasts:
 *
 * ```
 * custody entropy (§12)  ──► 12 words ──► BIP-39 seed ──► K_wrap ──► unwrap keySlot ──► K_c
 *                                          PvBip39Seed   PvVaultKey     PvKeySlotWrap
 *                                                        Derivation
 * ```
 *
 * Every step is a CALL into `vault/pv/keys`, never a re-derivation: those
 * functions are the ones the platform's E3 vectors pin byte for byte, and a
 * second implementation of a key chain is wrong the first time the first one
 * changes. `PvVaultKeyRegistryTest` replays the same E3 fixture through THIS
 * class, so the registry is proven to reproduce the platform's `K_c` rather than
 * merely to call something that once did.
 *
 * ## Where the key slots come from, and why that is not circular
 *
 * `K_c` is wrapped inside `keySlots[]`, so a device must read the slots before it
 * holds any key. It can: `keySlots`, `keyId` and `accountBinding` all ride in the
 * envelope's CLEARTEXT header (§4/§8), which is exactly what makes a vault
 * openable from any medium by anyone with the words. [PvVaultHeaderFacts] is that
 * source, and it is a seam because the facts can equally come from a header doc
 * just pulled off a medium or from a creation ceremony that has not stored
 * anything yet.
 *
 * ## Lifetime
 *
 * - [unlocked] hands out a **copy** of `K_c` and the caller zeroes it
 *   ([PvUnlockedVault.close]). Handing out the registry's own buffer would let
 *   the first sync pass zero every later one.
 * - [close] drops one vault; [clear] drops all and zeroes as it goes.
 * - [bindToCustody] clears the whole registry the moment the §12 session ends.
 *   That covers a wrapped phrase for the obvious reason, and a PLAIN one for a
 *   subtler one: clearing it costs nothing, because the next call re-derives
 *   from an entropy plain custody can still read. Session end therefore always
 *   means "no vault key is in memory", with no per-entry bookkeeping to get
 *   wrong.
 *
 * There is deliberately no "keep this vault open" setting. §12 allows exactly
 * three session terminators and this class introduces no fourth.
 *
 * ## What it cannot zero
 *
 * The twelve words exist as a `String` for the length of one derivation, because
 * BIP-39's PBKDF2 takes text. A `String` cannot be wiped, which is a property of
 * the JVM rather than of this design; the entropy it came from, the seed and
 * `K_wrap` are all zeroed in `finally`, and the same limitation already applies
 * to [PvDeviceCustody.wordsFor].
 */
class PvVaultKeyRegistry(
    private val custody: PvDeviceCustody,
    private val headerFacts: PvVaultHeaderFacts,
    /** The BIP-39 PBKDF2 pass runs here, off whatever thread asked. */
    private val kdfDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PvVaultKeys {

    private class Held(val contentKey: ByteArray, val facts: PvVaultKeyFacts) {
        fun zero() = zeroBytes(contentKey)
    }

    private val mutex = Mutex()
    private val held = LinkedHashMap<String, Held>()

    private val _openVaultIds = MutableStateFlow<Set<String>>(emptySet())

    /** Which vaults hold an open `K_c` right now — the §14 chip's LOCKED input. */
    val openVaultIds: StateFlow<Set<String>> = _openVaultIds.asStateFlow()

    /**
     * Open one vault, or confirm it is already open.
     *
     * The explicit entry point for the unlock path (`PvUnlockSheet` → custody
     * session → this). [unlocked] would do the same derivation lazily; having a
     * named act means the UI can report *"that phrase does not open this vault"*
     * at the moment the user asked, instead of on some later sync pass.
     */
    suspend fun open(vaultId: String): Boolean = mutex.withLock { openLocked(vaultId) != null }

    override suspend fun unlocked(vaultId: String): PvUnlockedVault? = mutex.withLock {
        val vault = openLocked(vaultId) ?: return@withLock null
        // A COPY: the caller closes what it is given. See the class KDoc.
        PvUnlockedVault(
            contentKey = vault.contentKey.copyOf(),
            keyId = vault.facts.keyId,
            keySlots = vault.facts.keySlots,
            accountBinding = vault.facts.accountBinding,
        )
    }

    /** Close one vault. The phrase stays in custody; only `K_c` goes. */
    suspend fun close(vaultId: String) = mutex.withLock {
        held.remove(vaultId)?.zero()
        _openVaultIds.value = held.keys.toSet()
    }

    /** Every vault. Called on lock, logout and account teardown. */
    suspend fun clear() = mutex.withLock {
        held.values.forEach { it.zero() }
        held.clear()
        _openVaultIds.value = emptySet()
    }

    /**
     * Follow the §12 session: when custody locks, every `K_c` goes with it.
     *
     * Takes the flow rather than the controller for [PvDeviceCustody
     * .bindToAppLock]'s reason — testable without Android, and unable to grow a
     * timer of its own.
     */
    fun bindToCustody(custodyUnlocked: Flow<Boolean>, scope: CoroutineScope): Job = scope.launch {
        custodyUnlocked.collect { unlocked -> if (!unlocked) clear() }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /** Caller holds [mutex]. */
    private suspend fun openLocked(vaultId: String): Held? {
        held[vaultId]?.let { return it }
        val derived = derive(vaultId) ?: return null
        held[vaultId] = derived
        _openVaultIds.value = held.keys.toSet()
        return derived
    }

    private suspend fun derive(vaultId: String): Held? {
        val facts = headerFacts.facts(vaultId) ?: return null
        val slot = facts.keySlots.firstOrNull { it.keyId == facts.keyId } ?: run {
            // An envelope whose active key has no slot is one nobody can open —
            // the codec refuses to WRITE such a header, so meeting one means the
            // bytes came from somewhere else. Presence only in the line: a vault
            // id is a value, and custody log lines carry none.
            Log.w(TAG, "A vault names an active key with no matching slot; it cannot be opened.")
            return null
        }
        val entropy = custody.entropyFor(vaultId) ?: return null
        return withContext(kdfDispatcher) {
            var seed: ByteArray? = null
            var wrapKey: ByteArray? = null
            try {
                seed = pvBip39Seed(pvEntropyToPhrase(entropy))
                wrapKey = pvVaultWrapKey(seed, vaultId)
                Held(pvUnwrapContentKey(slot, wrapKey, vaultId), facts)
            } catch (cause: VaultCryptoError) {
                // Code only — never the phrase, never a payload, not even which
                // vault it was: a vault id is a value, and §12's log discipline
                // allows presence and an error code, nothing else.
                Log.d(TAG, "A vault did not open: ${cause.code}")
                null
            } finally {
                zeroBytes(entropy)
                seed?.let { zeroBytes(it) }
                wrapKey?.let { zeroBytes(it) }
            }
        }
    }

    private companion object {
        const val TAG = "BtPvVaultKeys"
    }
}
