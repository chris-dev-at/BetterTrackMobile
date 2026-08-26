package at.bettertrack.app.ui.vault.qr

import at.bettertrack.app.vault.pv.NotAvailableVaultHeaderProbe
import at.bettertrack.app.vault.pv.VaultHeaderProbe
import at.bettertrack.app.vault.pv.VaultQrPayload
import at.bettertrack.app.vault.pv.VaultQrRejection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two receiver-leg decisions that are security properties rather than
 * styling: what the screen is allowed to reveal about a failure, and what it is
 * allowed to conclude without proof.
 */
class VaultQrScanStateTest {

    private val payload = VaultQrPayload(
        mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about",
        vaultId = "018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f",
    )

    // ── one generic failure message ─────────────────────────────────────────

    @Test
    fun `every content-level rejection collapses into the same message`() {
        // §13's shoulder-surfer rule: a bystander watching a scan fail must not
        // learn whether the code was nearly right. A wrong BIP-39 checksum and a
        // truncated payload therefore say the identical sentence.
        val generic = listOf(
            VaultQrRejection.MALFORMED,
            // Both halves of the frozen vocabulary's missing-key split
            // (2026-08-26). The split is semantic, for the parser and the
            // cross-client record; the copy stays collapsed, so the screen never
            // tells a bystander which half of the code was there.
            VaultQrRejection.MISSING_MNEMONIC,
            VaultQrRejection.MISSING_VAULT_ID,
            VaultQrRejection.PHRASE_INVALID,
            VaultQrRejection.VAULT_ID_INVALID,
            VaultQrRejection.NAME_TOO_LONG,
            // The two reasons the 2026-08-26 rulings added. They earned no copy
            // of their own: "you sent `m` twice" or "that fingerprint is the
            // wrong length" tells a bystander how close the code was.
            VaultQrRejection.DUPLICATE_KEY,
            VaultQrRejection.FINGERPRINT_INVALID,
        ).map { vaultQrRejectionMessage(it) }.toSet()
        assertEquals("these must be indistinguishable on screen", 1, generic.size)
    }

    @Test
    fun `only the provenance reasons may ever have their own copy`() {
        // Stated as a property over the whole enum rather than a list, so a
        // rejection reason added later is generic by default and gets its own
        // message only by a deliberate edit here.
        val ownCopy = setOf(
            VaultQrRejection.NOT_A_VAULT_CODE,
            VaultQrRejection.UNSUPPORTED_VERSION,
            VaultQrRejection.LEGACY_CODE,
        )
        val generic = vaultQrRejectionMessage(VaultQrRejection.PHRASE_INVALID)
        VaultQrRejection.entries.filterNot { it in ownCopy }.forEach {
            assertEquals(
                "$it must not be distinguishable from any other content-level failure",
                generic,
                vaultQrRejectionMessage(it),
            )
        }
    }

    @Test
    fun `the provenance rejections keep their own message, because each has its own fix`() {
        val update = vaultQrRejectionMessage(VaultQrRejection.UNSUPPORTED_VERSION)
        val legacy = vaultQrRejectionMessage(VaultQrRejection.LEGACY_CODE)
        val foreign = vaultQrRejectionMessage(VaultQrRejection.NOT_A_VAULT_CODE)
        val generic = vaultQrRejectionMessage(VaultQrRejection.PHRASE_INVALID)
        assertEquals(4, setOf(update, legacy, foreign, generic).size)
    }

    @Test
    fun `every rejection reason has a message`() {
        // A reason added without a message would resolve to 0 and render nothing.
        VaultQrRejection.entries.forEach { reason ->
            assertNotEquals("no message for $reason", 0, vaultQrRejectionMessage(reason))
        }
    }

    // ── nothing reaches Verified on this build ──────────────────────────────

    @Test
    fun `the stub probe yields the honest can-not-verify state`() = runTest {
        assertSame(
            VaultQrVerification.Unavailable,
            verifyScannedPhrase(payload, NotAvailableVaultHeaderProbe),
        )
    }

    @Test
    fun `even a probe that returns bytes must not be treated as proof`() = runTest {
        // Bytes coming back is not the same as those words opening the vault.
        // Until the derivation chain (epic E3) exists there is no prover, and
        // "the server answered" must never be promoted to Verified — that would
        // persist unverified words, the exact failure §13 forbids.
        val probe = object : VaultHeaderProbe {
            override suspend fun fetch(vaultId: String) = ByteArray(64) { 0x42 }
        }
        assertSame(VaultQrVerification.Unavailable, verifyScannedPhrase(payload, probe))
    }

    @Test
    fun `an empty header is unavailable, not an accidental success`() = runTest {
        val probe = object : VaultHeaderProbe {
            override suspend fun fetch(vaultId: String) = ByteArray(0)
        }
        assertSame(VaultQrVerification.Unavailable, verifyScannedPhrase(payload, probe))
    }

    @Test
    fun `the probe is asked about the scanned vault and nothing else`() = runTest {
        var asked: String? = null
        val probe = object : VaultHeaderProbe {
            override suspend fun fetch(vaultId: String): ByteArray? {
                asked = vaultId
                return null
            }
        }
        verifyScannedPhrase(payload, probe)
        assertEquals(payload.vaultId, asked)
    }

    // ── the accepted state always carries all four checks ───────────────────

    @Test
    fun `an accepted scan means all four offline checks passed`() {
        val checks = VaultQrChecks.ALL_PASSED
        assertTrue(checks.prefix && checks.requiredKeys && checks.phraseChecksum && checks.vaultIdShape)
    }
}
