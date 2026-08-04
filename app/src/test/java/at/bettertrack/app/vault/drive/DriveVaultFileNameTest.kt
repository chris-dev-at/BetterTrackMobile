package at.bettertrack.app.vault.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive object-name derivation, pinned against the **reference function
 * itself**.
 *
 * ## Where these expectations come from
 *
 * Not from re-reading the TypeScript and agreeing it looks right. The vendored
 * `tools/domain-vectors/vendor/web-vault/driveDataHome.ts` was loaded, its
 * `driveVaultFileName` + `base64url` + the three name constants extracted from
 * the file text, and the result executed under node with the real
 * `crypto.subtle` — the values below are that program's output (platform
 * `origin/main` @ `8ac3c6a2`).
 *
 * ## Why this test is load-bearing
 *
 * `appDataFolder` is one flat namespace with no directories and no listing the
 * user can inspect, shared by every app granted appdata access under one Google
 * principal. **The name is the entire selector.** If Android and the web PWA
 * derived even slightly different names, each would quietly create and maintain
 * its own file: both clients would work, no error would appear anywhere, and the
 * user would have two silently diverging vaults. There is no server in this mode
 * to notice, and no UI that could show it. A byte-level pin is the only defence.
 */
class DriveVaultFileNameTest {

    @Test
    fun matchesTheReferenceDerivationForAUuidAccountId() {
        assertEquals(
            "bettertrack-vault-37_BXqwHq-Ea8Pa7lBZEfp7xCNVdIDq7LogrDpEKJqc.btenc",
            driveVaultFileName("018f0000-0000-7000-8000-000000000101"),
        )
        assertEquals(
            "bettertrack-vault-0zT61UO86ba3aSOkPS02z-tC69fiNUnFpLQebhWRFsU.btenc",
            driveVaultFileName("018f0000-0000-7000-8000-000000000102"),
        )
    }

    /** The empty and single-character cases pin the hashing, not just the happy path. */
    @Test
    fun matchesTheReferenceDerivationForEdgeCaseScopes() {
        assertEquals(
            "bettertrack-vault-pKUZyl2Ir_Fg_IOhMRehDVi_xaQ7cCXR4QoQLqtr40g.btenc",
            driveVaultFileName(""),
        )
        assertEquals(
            "bettertrack-vault-kIqdBh7J8yEaUG24kv1rViZBeKgW1taqvjxmVIkFmN4.btenc",
            driveVaultFileName("a"),
        )
        assertEquals(
            "bettertrack-vault-8nTbFGGXym-DEZ5m3kUHM8UtjE_SwLVBBkJIXcSRktM.btenc",
            driveVaultFileName("chris@example.com"),
        )
    }

    /**
     * base64**url**, unpadded: `+` and `/` would both be legal in a Drive file
     * name but would not match what the web writes, and a trailing `=` changes
     * the string outright.
     */
    @Test
    fun usesTheUnpaddedUrlSafeAlphabet() {
        val name = driveVaultFileName("018f0000-0000-7000-8000-000000000101")
        val digest = name.removePrefix("bettertrack-vault-").removeSuffix(".btenc")
        assertTrue("no '+'", '+' !in digest)
        assertTrue("no '/'", '/' !in digest)
        assertTrue("no padding", '=' !in digest)
        // SHA-256 is 32 bytes → ceil(32 * 4 / 3) = 43 unpadded base64 characters.
        assertEquals(43, digest.length)
    }

    @Test
    fun isStableAndScopeSeparating() {
        val first = driveVaultFileName("018f0000-0000-7000-8000-000000000101")
        assertEquals("the same scope always derives the same name", first, driveVaultFileName("018f0000-0000-7000-8000-000000000101"))
        assertNotEquals(
            "two scopes never share a file",
            first,
            driveVaultFileName("018f0000-0000-7000-8000-000000000102"),
        )
    }

    /**
     * The context string is a domain separator: hashing a bare account id would
     * let the same digest be produced by any other protocol that hashes the same
     * value, and the reference prefixes it for exactly that reason.
     */
    @Test
    fun isDomainSeparatedFromABareAccountIdHash() {
        val accountId = "018f0000-0000-7000-8000-000000000101"
        val bare = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray()),
        )
        assertNotEquals(
            "the context prefix must be part of the hashed input",
            "bettertrack-vault-$bare.btenc",
            driveVaultFileName(accountId),
        )
    }
}
