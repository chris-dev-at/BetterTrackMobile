package at.bettertrack.app.vault.pv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §13 payload contract, exercised as a matrix.
 *
 * The spec exists because two implementations (a web renderer and this scanner)
 * are written against it independently, so every clause that could be read two
 * ways gets a case here — the exact wire bytes, the `+`/`%20` equivalence, the
 * ignored-unknown-key rule, and one case per rejection reason.
 */
class VaultQrPayloadTest {

    /** The canonical all-zero-entropy BIP-39 vector. Valid checksum. */
    private val words =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about"

    /** The all-ones vector — a second valid phrase, so nothing can pass by coincidence. */
    private val otherWords = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

    private val vaultId = "018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f"

    private fun ok(value: String): VaultQrPayload {
        val result = parseVaultQrPayload(value)
        assertTrue("expected Ok, got $result", result is VaultQrParseResult.Ok)
        return (result as VaultQrParseResult.Ok).payload
    }

    private fun rejected(value: String): VaultQrRejection {
        val result = parseVaultQrPayload(value)
        assertTrue("expected Failed, got $result", result is VaultQrParseResult.Failed)
        return (result as VaultQrParseResult.Failed).reason
    }

    // ── the wire format, pinned ─────────────────────────────────────────────

    @Test
    fun `build emits the exact wire string the spec describes`() {
        // The interop anchor: a web renderer using URLSearchParams produces this
        // byte for byte — '+' for spaces, uppercase percent escapes, unreserved
        // '-' and '_' left alone inside the base64url fingerprint.
        assertEquals(
            "btvault1:m=abandon+abandon+abandon+abandon+abandon+abandon+abandon+abandon+" +
                "abandon+abandon+abandon+about" +
                "&v=018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f" +
                "&n=Familie+%26+Co" +
                "&f=Zm9vYmFy_ab-cd",
            buildVaultQrPayload(
                mnemonic = words,
                vaultId = vaultId,
                name = "Familie & Co",
                fingerprint = "Zm9vYmFy_ab-cd",
            ),
        )
    }

    @Test
    fun `build omits the optional keys when they are absent or blank`() {
        assertEquals(
            "btvault1:m=$words&v=$vaultId".replace(" ", "+"),
            buildVaultQrPayload(words, vaultId),
        )
        assertEquals(
            buildVaultQrPayload(words, vaultId),
            buildVaultQrPayload(words, vaultId, name = "   ", fingerprint = ""),
        )
    }

    @Test
    fun `build normalizes the phrase before it encodes it`() {
        // Mixed case and ragged whitespace must produce the SAME code as the
        // canonical phrase — the two devices derive keys from these bytes.
        assertEquals(
            buildVaultQrPayload(words, vaultId),
            buildVaultQrPayload("  ABANDON   abandon\tabandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon ABOUT ", vaultId),
        )
    }

    @Test
    fun `build refuses input the sender should never have`() {
        // Asymmetry with parse is deliberate: build input is our own unlocked
        // vault, and a bad value there must not become a scannable code.
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload("abandon abandon", vaultId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload(words, "018F3C2A-7B41-7C3E-9F21-0A1B2C3D4E5F")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload(words, vaultId, name = "x".repeat(65))
        }
    }

    @Test
    fun `build and parse round trip every field`() {
        val payload = ok(
            buildVaultQrPayload(words, vaultId, name = "Öl & Gas – Depot", fingerprint = "aGVsbG8"),
        )
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
        assertEquals("Öl & Gas – Depot", payload.name)
        assertEquals("aGVsbG8", payload.fingerprint)
    }

    // ── accepted shapes ─────────────────────────────────────────────────────

    @Test
    fun `the two required keys alone are enough`() {
        val payload = ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId")
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
        assertNull(payload.name)
        assertNull(payload.fingerprint)
    }

    @Test
    fun `plus and percent-20 both mean space`() {
        // Form-urlencoded, not URI: '+' is a space. Both spellings must land on
        // the identical phrase or the two implementations derive different keys.
        val plus = ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId")
        val pct = ok("btvault1:m=${words.replace(" ", "%20")}&v=$vaultId")
        assertEquals(plus.mnemonic, pct.mnemonic)
        assertEquals(words, pct.mnemonic)
    }

    @Test
    fun `unknown query keys are ignored, which is the forward-compatibility rule`() {
        val payload = ok(
            "btvault1:x=1&m=${words.replace(" ", "+")}&zz=hello+world&v=$vaultId&q=",
        )
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
    }

    @Test
    fun `key order does not matter and a trailing separator is tolerated`() {
        val payload = ok("btvault1:v=$vaultId&f=abc&m=${words.replace(" ", "+")}&")
        assertEquals(words, payload.mnemonic)
        assertEquals("abc", payload.fingerprint)
    }

    @Test
    fun `a duplicate key takes the first value, like URLSearchParams get`() {
        val payload = ok(
            "btvault1:m=${words.replace(" ", "+")}&m=${otherWords.replace(" ", "+")}&v=$vaultId",
        )
        assertEquals(words, payload.mnemonic)
    }

    @Test
    fun `a name at exactly the cap is accepted`() {
        val name = "x".repeat(64)
        assertEquals(name, ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&n=$name").name)
    }

    @Test
    fun `a percent-encoded non-ascii name decodes as UTF-8`() {
        // "Müller" → the two-byte U+00FC escape.
        val payload = ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&n=M%C3%BCller")
        assertEquals("Müller", payload.name)
    }

    @Test
    fun `the returned phrase is always the normalized form`() {
        val payload = ok("btvault1:m=${words.uppercase().replace(" ", "+")}&v=$vaultId")
        assertEquals(words, payload.mnemonic)
    }

    // ── one case per rejection reason ───────────────────────────────────────

    @Test
    fun `a foreign code is named as foreign, not as an app-version problem`() {
        listOf(
            "",
            "https://web.bettertrack.at/vaults",
            "WIFI:S:Home;T:WPA;P:secret;;",
            "otpauth://totp/BetterTrack:me?secret=ABC",
            "btvault:m=x", // no version digit at all
            "BTVAULT1:m=x", // byte mode preserves case; an uppercased code is not ours
            " btvault1:m=x", // a leading space is not the prefix
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.NOT_A_VAULT_CODE, rejected(it))
        }
    }

    @Test
    fun `another btvault version says update the app and is never best-effort parsed`() {
        listOf(
            "btvault2:m=${words.replace(" ", "+")}&v=$vaultId",
            "btvault10:m=${words.replace(" ", "+")}&v=$vaultId",
            "btvault0:anything",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.UNSUPPORTED_VERSION, rejected(it))
        }
    }

    @Test
    fun `the v2 code on the same prefix is named as an old version`() {
        // The retired vault.v2 handoff shares `btvault1:` (board ask #83). Its
        // body is JSON, which is how the two are told apart until the platform
        // answers.
        val v2 = """btvault1:{"qr":1,"vaultId":"$vaultId","name":"Depot","w":"AAAABBBBCCCC"}"""
        assertEquals(VaultQrRejection.LEGACY_CODE, rejected(v2))
    }

    @Test
    fun `a broken body is malformed`() {
        listOf(
            "btvault1:m=%zz&v=$vaultId", // non-hex escape
            "btvault1:m=abc%2", // truncated escape
            "btvault1:m=%", // truncated escape at the very end
            "btvault1:{not json at all", // JSON-looking but not JSON
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.MALFORMED, rejected(it))
        }
    }

    @Test
    fun `a missing or empty required key is refused`() {
        listOf(
            "btvault1:v=$vaultId",
            "btvault1:m=${words.replace(" ", "+")}",
            "btvault1:m=&v=$vaultId",
            "btvault1:m=${words.replace(" ", "+")}&v=",
            "btvault1:",
            "btvault1:n=Depot&f=abc",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.MISSING_REQUIRED_KEY, rejected(it))
        }
    }

    @Test
    fun `every phrase defect collapses into one reason`() {
        val eleven = words.split(" ").drop(1).joinToString("+")
        val badChecksum = words.replace("about", "abandon").replace(" ", "+")
        val offList = words.replace("about", "bettertrack").replace(" ", "+")
        listOf(eleven, badChecksum, offList).forEach {
            assertEquals("for <$it>", VaultQrRejection.PHRASE_INVALID, rejected("btvault1:m=$it&v=$vaultId"))
        }
    }

    @Test
    fun `a vault id that is not a lowercase hyphenated uuid is refused`() {
        listOf(
            "018F3C2A-7B41-7C3E-9F21-0A1B2C3D4E5F", // uppercase: it is HKDF input, case is contract
            "018f3c2a7b417c3e9f210a1b2c3d4e5f", // unhyphenated
            "018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5", // one digit short
            "not-a-uuid",
            "018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e5f ", // trailing space
        ).forEach {
            assertEquals(
                "for <$it>",
                VaultQrRejection.VAULT_ID_INVALID,
                rejected("btvault1:m=${words.replace(" ", "+")}&v=$it"),
            )
        }
    }

    @Test
    fun `a name over the cap is refused rather than truncated`() {
        // Truncating would change what the receiver shows the user about which
        // vault they are adopting, silently.
        assertEquals(
            VaultQrRejection.NAME_TOO_LONG,
            rejected("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&n=${"x".repeat(65)}"),
        )
    }

    // ── the "never throws" property ─────────────────────────────────────────

    @Test
    fun `parse never throws on arbitrary camera input`() {
        val fuzz = buildList {
            add("btvault1:" + "%".repeat(500))
            add("btvault1:" + "&".repeat(500))
            add("btvault1:m=" + " ￿😀")
            add("btvault1:=" )
            add("btvault1:====")
            add("btvault1:m")
            add("😀")
            add("btvault1:n=" + "ä".repeat(300))
            addAll((0..255).map { "btvault1:m=${it.toChar()}&v=$vaultId" })
        }
        fuzz.forEach { input ->
            // The real assertion is that no exception escapes this call; the
            // reason it fails is beside the point, only that it fails cleanly.
            val result = parseVaultQrPayload(input)
            assertTrue("unexpectedly accepted <$input>", result is VaultQrParseResult.Failed)
        }
    }

    @Test
    fun `the contract constants are the ones the spec names`() {
        assertEquals("btvault1:", VaultQrContract.PREFIX)
        assertEquals("m", VaultQrContract.KEY_MNEMONIC)
        assertEquals("v", VaultQrContract.KEY_VAULT_ID)
        assertEquals("n", VaultQrContract.KEY_NAME)
        assertEquals("f", VaultQrContract.KEY_FINGERPRINT)
        assertEquals(64, VaultQrContract.MAX_NAME_LENGTH)
        // §13's TTL. The 120 s of the retired v2 handoff is dead.
        assertEquals(60, VaultQrContract.DISPLAY_TTL_SECONDS)
    }

    @Test
    fun `the payload stays inside a comfortably scannable size`() {
        // §13 sizes it at ~150-220 characters. A code that quietly grew past a
        // few hundred would still encode and would scan badly on a phone.
        val longest = buildVaultQrPayload(
            mnemonic = otherWords,
            vaultId = vaultId,
            name = "x".repeat(64),
            fingerprint = "A".repeat(24),
        )
        assertTrue("payload got long: ${longest.length}", longest.length <= 260)
        assertTrue(buildVaultQrPayload(words, vaultId).length in 100..200)
    }

    @Test
    fun `the fingerprint is carried but never validated offline`() {
        // The spec's own `f` bullet implies a pre-fetch check; it is a known
        // wording defect (see VaultQrPayload's KDoc). Any non-empty value is
        // accepted here precisely because nothing offline can judge it — the
        // comparison happens after the header fetch or not at all.
        listOf("aaaa", "!!!not-base64!!!", "x".repeat(400)).forEach { f ->
            val payload = ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&f=$f")
            assertEquals(f, payload.fingerprint)
        }
    }
}
