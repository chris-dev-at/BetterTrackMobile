package at.bettertrack.app.vault.pv.custody

import at.bettertrack.app.vault.FakeSharedPreferences
import at.bettertrack.app.vault.pv.envelope.PvKeySlot
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import at.bettertrack.app.vault.pv.sync.PvVaultHeaderFacts
import at.bettertrack.app.vault.pv.sync.PvVaultKeyFacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The unlocked-vault registry, proven against the platform's E3 bytes.**
 *
 * The registry is a composition — custody entropy, the BIP-39 step, `K_wrap`,
 * the slot unwrap — and a composition can be wrong while every part is right:
 * the wrong entropy, the wrong vault id in the info string, the wrong slot
 * picked out of a rotated set. So this suite does not stub the chain. It stores
 * the fixture's own phrase in a REAL [PvDeviceCustody], hands the registry the
 * fixture's own `keySlot`, and asserts the `K_c` that comes out is byte-for-byte
 * the platform's `000102…1e1f`.
 *
 * The fixture is `vault-vectors/pv-derivation.e3.fixture.json`, transcribed from
 * `apps/web/src/user/vault/keys/keys.test.ts` (`chris-dev-at/BetterTrack`,
 * `origin/main` `970a5f1f`) — the same file `PvVaultKeyDerivationTest` replays
 * one level down. Nothing here is self-derived, and the fixture's phrase is the
 * published all-zero-entropy BIP-39 vector, which opens no real vault.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PvVaultKeyRegistryTest {

    // ── the platform's fixture ──────────────────────────────────────────────

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(E3_FIXTURE)
            ?: error("$E3_FIXTURE missing from test resources")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val chain: JsonObject by lazy { fixture["chain"]!!.jsonArray.first().jsonObject }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun unhex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val vaultId: String get() = chain.str("vaultId")
    private val keyId: String get() = chain.str("keyId")
    private val entropy: ByteArray get() = unhex(chain.str("entropy"))

    /** The header facts the platform's own vector describes. */
    private fun facts(
        keyId: String = this.keyId,
        wrappedKc: String = chain.str("wrappedKc"),
    ) = PvVaultKeyFacts(
        keyId = keyId,
        keySlots = listOf(PvKeySlot(keyId, PvVaultContract.KEY_SLOT_SEED_V1, wrappedKc)),
        accountBinding = fixture["accountBinding"]!!.jsonObject.str("binding"),
    )

    private class FixedFacts(private val facts: PvVaultKeyFacts?) : PvVaultHeaderFacts {
        override suspend fun facts(vaultId: String): PvVaultKeyFacts? = facts
    }

    /**
     * A real keystore over fake preferences, with the phrase in PLAIN custody.
     *
     * Plain rather than wrapped so no Argon2id runs: the device-password half is
     * `PvDeviceCustodyTest`'s subject, and this suite is about what happens to a
     * phrase once custody can serve it.
     */
    private fun custodyHolding(entropy: ByteArray?): PvDeviceCustody {
        val custody = PvDeviceCustody(
            keystore = PvEndpointKeystore(FakeSharedPreferences()),
            kdfDispatcher = UnconfinedTestDispatcher(),
            clock = PvElapsedClock { 0L },
        )
        if (entropy != null) {
            assertTrue(custody.storeEntropy(vaultId, entropy, PvCustodyMode.PLAIN))
        }
        return custody
    }

    private fun registry(
        custody: PvDeviceCustody = custodyHolding(entropy),
        headerFacts: PvVaultHeaderFacts = FixedFacts(facts()),
    ) = PvVaultKeyRegistry(custody, headerFacts, UnconfinedTestDispatcher())

    // ── the vector ──────────────────────────────────────────────────────────

    @Test
    fun `the registry yields the platform's K_c for the platform's phrase and slot`() = runTest {
        val opened = registry().unlocked(vaultId)
        assertNotNull("the fixture's phrase must open the fixture's slot", opened)
        assertEquals(
            "K_c must be the platform's E3 content key, byte for byte",
            chain.str("contentKey"),
            hex(opened!!.contentKey),
        )
        assertEquals(keyId, opened.keyId)
        assertEquals(
            fixture["accountBinding"]!!.jsonObject.str("binding"),
            opened.accountBinding,
        )
        assertEquals(1, opened.keySlots.size)
        assertEquals(chain.str("wrappedKc"), opened.keySlots.first().wrappedKc)
    }

    @Test
    fun `every handout is its own copy, so closing one does not close the next`() = runTest {
        val registry = registry()
        val first = registry.unlocked(vaultId)!!
        first.close()
        assertEquals("the closed copy is zeroed", "00".repeat(32), hex(first.contentKey))

        val second = registry.unlocked(vaultId)!!
        assertEquals(
            "a second pass must still get the real key — the registry's own buffer " +
                "must not be what the first caller zeroed",
            chain.str("contentKey"),
            hex(second.contentKey),
        )
    }

    @Test
    fun `clearing the registry zeroes what it held and re-derivation still works`() = runTest {
        val registry = registry()
        assertTrue(registry.open(vaultId))
        assertEquals(setOf(vaultId), registry.openVaultIds.value)

        registry.clear()
        assertEquals(emptySet<String>(), registry.openVaultIds.value)
        // Plain custody can still serve the entropy, so the next call re-derives
        // rather than failing — clearing is about what is in memory, not about
        // what the endpoint holds.
        assertEquals(chain.str("contentKey"), hex(registry.unlocked(vaultId)!!.contentKey))
    }

    @Test
    fun `closing one vault leaves the others open`() = runTest {
        val registry = registry()
        assertTrue(registry.open(vaultId))
        registry.close("018f6a3e-1111-7000-8000-00000000ffff")
        assertEquals(setOf(vaultId), registry.openVaultIds.value)
        registry.close(vaultId)
        assertEquals(emptySet<String>(), registry.openVaultIds.value)
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    fun `a vault whose phrase this endpoint does not hold is locked, not an error`() = runTest {
        assertNull(registry(custody = custodyHolding(null)).unlocked(vaultId))
    }

    @Test
    fun `no header facts means no key, because the slots are what wrap it`() = runTest {
        assertNull(registry(headerFacts = FixedFacts(null)).unlocked(vaultId))
    }

    @Test
    fun `an active keyId with no matching slot cannot be opened`() = runTest {
        // The codec refuses to WRITE such a header, so meeting one means the
        // bytes came from somewhere else. Refusing beats unwrapping whichever
        // slot happens to be first.
        val mismatched = PvVaultKeyFacts(
            keyId = "018f6a3e-3333-7000-8000-0000000000ff",
            keySlots = facts().keySlots,
            accountBinding = facts().accountBinding,
        )
        assertNull(registry(headerFacts = FixedFacts(mismatched)).unlocked(vaultId))
    }

    @Test
    fun `a slot from another vault does not open this one`() = runTest {
        // The slot AAD binds `vaultId`, so the platform's own bytes must fail
        // under any other vault id — the §8 anti-swap property, one level below
        // the document envelope.
        val other = "018f6a3e-1111-7000-8000-000000000002"
        val custody = PvDeviceCustody(
            keystore = PvEndpointKeystore(FakeSharedPreferences()),
            kdfDispatcher = UnconfinedTestDispatcher(),
            clock = PvElapsedClock { 0L },
        )
        assertTrue(custody.storeEntropy(other, entropy, PvCustodyMode.PLAIN))
        val registry = PvVaultKeyRegistry(custody, FixedFacts(facts()), UnconfinedTestDispatcher())
        assertNull(registry.unlocked(other))
    }

    @Test
    fun `the session ending clears every key`() = runTest {
        val unlocked = MutableStateFlow(true)
        val registry = registry()
        val job = registry.bindToCustody(unlocked, this)
        assertTrue(registry.open(vaultId))

        unlocked.value = false
        runCurrentUntilCleared(registry)
        assertEquals(
            "a §12 session end must leave no vault key in memory",
            emptySet<String>(),
            registry.openVaultIds.value,
        )
        job.cancel()
    }

    @Test
    fun `an open session is not disturbed by the binding`() = runTest {
        val unlocked = MutableStateFlow(true)
        val registry = registry()
        val job = registry.bindToCustody(unlocked, this)
        assertTrue(registry.open(vaultId))
        assertFalse(registry.openVaultIds.value.isEmpty())
        job.cancel()
    }

    /** The bound collector runs on the test scheduler; give it its turn. */
    private suspend fun runCurrentUntilCleared(registry: PvVaultKeyRegistry) {
        repeat(20) {
            if (registry.openVaultIds.value.isEmpty()) return
            kotlinx.coroutines.yield()
        }
    }

    private companion object {
        const val E3_FIXTURE = "/vault-vectors/pv-derivation.e3.fixture.json"
    }
}
