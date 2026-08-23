package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.dto.VaultConfigDto
import at.bettertrack.app.vault.pv.envelope.PvVaultContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Doc addressing: the identity rule, and the resolution the size ceiling depends
 * on.
 *
 * `docId` IS the portfolio uuid for a portfolio doc. Everything downstream —
 * which cap applies, whether an envelope's `docKind` claim is honest, whether a
 * write may go out at all — is decided from that plus the two singleton ids the
 * vault row registers. This test is what keeps a mapping table from creeping in
 * between them.
 */
class PvDocAddressTest {

    private val vaultId = "018f0000-0000-7000-8000-000000000001"
    private val headerDocId = "018f0000-0000-7000-8000-0000000000a1"
    private val commonDocId = "018f0000-0000-7000-8000-0000000000a2"
    private val portfolioId = "018f0000-0000-7000-8000-0000000000b7"

    private val directory = PvVaultDocDirectory(vaultId, headerDocId, commonDocId)

    @Test
    fun `a portfolio doc's id is the portfolio's id, with nothing in between`() {
        listOf(
            "018f0000-0000-7000-8000-0000000000b7",
            "3f2a1c88-0000-4000-8000-abcdefabcdef",
            "00000000-0000-0000-0000-000000000000",
        ).forEach { id ->
            assertEquals(id, PvDocRef.Portfolio(id).docId)
            assertEquals(id, PvDocRef.Portfolio(id).portfolioId)
        }
    }

    @Test
    fun `the two singletons resolve to their kinds and everything else is a portfolio`() {
        assertEquals(PvDocRef.Header(headerDocId), directory.refOf(headerDocId))
        assertEquals(PvDocRef.Common(commonDocId), directory.refOf(commonDocId))
        assertEquals(PvDocRef.Portfolio(portfolioId), directory.refOf(portfolioId))

        assertEquals(PvDocKind.HEADER, directory.refOf(headerDocId).kind)
        assertEquals(PvDocKind.COMMON, directory.refOf(commonDocId).kind)
        assertEquals(PvDocKind.PORTFOLIO, directory.refOf(portfolioId).kind)
    }

    @Test
    fun `a reference is accepted only at the kind this vault gives its address`() {
        assertTrue(directory.accepts(directory.header))
        assertTrue(directory.accepts(directory.common))
        assertTrue(directory.accepts(directory.portfolio(portfolioId)))

        // The same id, claimed as the wrong kind — the shape that would let 8 MiB
        // of portfolio ciphertext be written over a vault's header.
        assertFalse(directory.accepts(PvDocRef.Portfolio(headerDocId)))
        assertFalse(directory.accepts(PvDocRef.Header(portfolioId)))
        assertFalse(directory.accepts(PvDocRef.Common(headerDocId)))
    }

    @Test
    fun `a portfolio id that collides with a singleton is refused, not resolved`() {
        listOf(headerDocId, commonDocId).forEach { id ->
            try {
                directory.portfolio(id)
                fail("a portfolio id colliding with a singleton must be refused")
            } catch (_: PvDocAddressError) {
                // expected
            }
        }
    }

    @Test
    fun `a vault whose two singletons are the same id cannot make a directory`() {
        try {
            PvVaultDocDirectory(vaultId, headerDocId, headerDocId)
            fail("an ambiguous directory must not be constructible")
        } catch (_: PvDocAddressError) {
            // expected — an ambiguous address in a blind store resolves silently
            // and wrongly, which is worse than not resolving at all.
        }
    }

    @Test
    fun `a directory is built from the vault row the server actually holds`() {
        val config = VaultConfigDto(
            id = vaultId,
            name = "Family",
            headerDocId = headerDocId,
            commonDocId = commonDocId,
            media = listOf("server"),
            driveConnectionId = null,
            keyFingerprint = "AbCdEfGhIjKlMnOp",
            retirementProofPublicKey = "MCowBQYDK2VwAyEAsdfghjklqwertyuiopzxcvbnmASDFGHJKLQWERTYUI",
            retirementGeneration = 0,
            mediaAttestedAt = null,
            mediaAttestedDriveConnectionId = null,
            createdAt = "2026-08-23T09:00:00.000Z",
            updatedAt = "2026-08-23T09:00:00.000Z",
        )
        val built = PvVaultDocDirectory.of(config)
        assertEquals(vaultId, built.vaultId)
        assertEquals(PvDocRef.Header(headerDocId), built.header)
        assertEquals(PvDocRef.Common(commonDocId), built.common)
    }

    @Test
    fun `the per-kind ceilings are the contract's, read from one table`() {
        assertEquals(1 * 1024 * 1024, PvDocKind.HEADER.maxBytes)
        assertEquals(4 * 1024 * 1024, PvDocKind.COMMON.maxBytes)
        assertEquals(8 * 1024 * 1024, PvDocKind.PORTFOLIO.maxBytes)

        // Not a second copy of the numbers — the same map the envelope contract
        // carries, so a re-tuned cap moves in exactly one place.
        PvDocKind.entries.forEach { kind ->
            assertEquals(
                PvVaultContract.DOC_MAX_BYTES_DEFAULTS.getValue(kind.wire),
                kind.maxBytes,
            )
        }
        assertEquals(PvVaultContract.DOC_KINDS, PvDocKind.entries.map { it.wire }.toSet())
    }

    @Test
    fun `wire names round-trip through the kind enum`() {
        PvDocKind.entries.forEach { kind ->
            assertSame(kind, PvDocKind.ofWire(kind.wire))
        }
        assertNull(PvDocKind.ofWire("snapshot"))
    }
}
