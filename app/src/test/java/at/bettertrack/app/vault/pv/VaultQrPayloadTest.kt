package at.bettertrack.app.vault.pv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** A schema-valid `f`: exactly 16 base64url characters (ruling 7, 2026-08-26). */
    private val fingerprint = "AbCdEfGhIjKlMn_o"

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
                "&f=Zm9vYmFy_ab-cdEF",
            buildVaultQrPayload(
                mnemonic = words,
                vaultId = vaultId,
                name = "Familie & Co",
                fingerprint = "Zm9vYmFy_ab-cdEF",
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
        // Ruling 7 (2026-08-26): `f` has a shape, and the builder must not be
        // able to emit a code this app's own parser would refuse.
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload(words, vaultId, fingerprint = "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload(words, vaultId, fingerprint = "AbCdEfGhIjKlMn_o!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildVaultQrPayload(words, vaultId, fingerprint = "AbCdEfGh IjKlMno")
        }
    }

    @Test
    fun `build and parse round trip every field`() {
        val payload = ok(
            buildVaultQrPayload(
                words,
                vaultId,
                name = "Öl & Gas – Depot",
                fingerprint = "aGVsbG8-_AbCdEfG",
            ),
        )
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
        assertEquals("Öl & Gas – Depot", payload.name)
        assertEquals("aGVsbG8-_AbCdEfG", payload.fingerprint)
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
        val payload = ok("btvault1:v=$vaultId&f=$fingerprint&m=${words.replace(" ", "+")}&")
        assertEquals(words, payload.mnemonic)
        assertEquals(fingerprint, payload.fingerprint)
    }

    @Test
    fun `an unknown key may repeat as often as it likes`() {
        // The other half of ruling 1, and the half that keeps the format
        // additively extensible: only KNOWN keys are policed.
        val payload = ok(
            "btvault1:x=1&x=2&x=3&m=${words.replace(" ", "+")}&v=$vaultId&future=a&future=b",
        )
        assertEquals(words, payload.mnemonic)
        assertEquals(vaultId, payload.vaultId)
    }

    @Test
    fun `a name at exactly the cap is accepted`() {
        val name = "x".repeat(64)
        assertEquals(name, ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&n=$name").name)
    }

    @Test
    fun `the name cap counts code points, so 64 emoji fit`() {
        // Ruling 3 (2026-08-26). 64 astral characters are 64 code points and 128
        // UTF-16 code units; the earlier code-unit reading rejected this.
        // §13's cap is 64 code points ⇒ at most 256 UTF-8 bytes.
        val emoji = "😀".repeat(64)
        val m = words.replace(" ", "+")
        assertEquals(emoji, ok("btvault1:m=$m&v=$vaultId&n=${"%F0%9F%98%80".repeat(64)}").name)
        assertEquals(64, emoji.codePointCount(0, emoji.length))
        assertEquals(256, emoji.toByteArray(Charsets.UTF_8).size)
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
            "btvault99:anything",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.UNSUPPORTED_VERSION, rejected(it))
        }
    }

    /**
     * **The version token is a canonical decimal integer — platform ruling,
     * 2026-09-01.** `^[1-9][0-9]*$`: no zero, no leading zeros.
     *
     * This test exists because the shape must be decided BEFORE any integer
     * conversion. This app never converted (it matched the family regex
     * `^btvault[0-9]+:`), but the effect was the same class of bug from the
     * other direction: every padded spelling answered
     * [VaultQrRejection.UNSUPPORTED_VERSION], i.e. "update the app" for a code
     * no BetterTrack client has ever minted. A client that *did* convert would
     * read `btvault007:` as version 7 and land in the same place, which is why
     * the platform pinned all four spellings as vectors.
     */
    @Test
    fun `a non-canonical version token is not our code at all, never an update prompt`() {
        val tail = "m=${words.replace(" ", "+")}&v=$vaultId"
        listOf(
            "btvault0:$tail", // zero is not a version anyone minted
            "btvault01:$tail", // zero-padded 1 — an integer parse would read it as ours
            "btvault02:$tail", // zero-padded 2 — an integer parse would say "update"
            "btvault007:$tail", // Kotlin's "007".toInt() is 7
            "btvault00:$tail",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.NOT_A_VAULT_CODE, rejected(it))
        }
        // And the canonical neighbours still answer the way they always did.
        assertEquals(words, ok("btvault1:$tail").mnemonic)
        assertEquals(VaultQrRejection.UNSUPPORTED_VERSION, rejected("btvault2:$tail"))
    }

    /**
     * **A leading `?` is `malformed` — platform ruling, 2026-09-01**, which
     * supersedes the earlier reading that answered with whichever required key
     * the `?` had swallowed.
     *
     * The `?` is the URL's query DELIMITER and is never part of form data, so
     * this is a break in the body grammar rather than a missing key — and a
     * missing-key answer made the verdict depend on which key the sender wrote
     * first, which is exactly the kind of order-dependence the wire contract
     * exists to remove. Both orderings are pinned.
     */
    @Test
    fun `a body starting with the query delimiter is malformed, whichever key leads`() {
        val m = words.replace(" ", "+")
        assertEquals(VaultQrRejection.MALFORMED, rejected("btvault1:?m=$m&v=$vaultId"))
        assertEquals(VaultQrRejection.MALFORMED, rejected("btvault1:?v=$vaultId&m=$m"))
        // A `?` anywhere else is an ordinary character in the value it lands in.
        assertEquals("Where?", ok("btvault1:m=$m&v=$vaultId&n=Where?").name)
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

    /**
     * The frozen vocabulary (2026-08-26) names the two required keys separately —
     * `missing-mnemonic` and `missing-vault-id` — so this test names which key,
     * not merely "one of them". Empty counts as missing on both, exactly as an
     * omitted key does.
     */
    @Test
    fun `a missing or empty required key is refused and the parser says which one`() {
        listOf(
            "btvault1:v=$vaultId" to VaultQrRejection.MISSING_MNEMONIC,
            "btvault1:m=&v=$vaultId" to VaultQrRejection.MISSING_MNEMONIC,
            "btvault1:m=${words.replace(" ", "+")}" to VaultQrRejection.MISSING_VAULT_ID,
            "btvault1:m=${words.replace(" ", "+")}&v=" to VaultQrRejection.MISSING_VAULT_ID,
        ).forEach { (payload, reason) ->
            assertEquals("for <$payload>", reason, rejected(payload))
        }
    }

    /**
     * The tie-break the fold never had to answer: with NEITHER required key
     * present, `m` is decided first, so an empty body is `missing-mnemonic`.
     *
     * Pinned because it is a wire-visible choice rather than an implementation
     * detail — the web parser checks `m` first as well
     * (`apps/web/src/user/vault/qr/payload.ts`), and a client that answered
     * `missing-vault-id` here would report a different outcome for identical
     * bytes.
     */
    @Test
    fun `a body with neither required key answers the mnemonic half, like the web`() {
        listOf(
            "btvault1:",
            "btvault1:n=Depot&f=abc",
            "btvault1:m=&v=",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.MISSING_MNEMONIC, rejected(it))
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
        val m = words.replace(" ", "+")
        assertEquals(
            VaultQrRejection.NAME_TOO_LONG,
            rejected("btvault1:m=$m&v=$vaultId&n=${"x".repeat(65)}"),
        )
        // The cap is code points, so the astral boundary is 65 emoji — not 33.
        assertEquals(
            VaultQrRejection.NAME_TOO_LONG,
            rejected("btvault1:m=$m&v=$vaultId&n=${"%F0%9F%98%80".repeat(65)}"),
        )
    }

    /**
     * **The named trim set — platform ruling, 2026-09-01.**
     *
     * `n`'s edges are trimmed against Unicode `White_Space` ∪ `Cc` ∪ U+FEFF,
     * spelled out as [isVaultQrTrimCodePoint] rather than delegated to a host
     * built-in. The two runtimes' built-ins each miss part of the other's set —
     * Kotlin's `trim()` strips U+001C–U+001F and leaves U+FEFF, ECMAScript's
     * does the exact opposite — so the built-ins would have made the two
     * clients disagree about whether a scanned code carries a name at all.
     * U+FEFF is the case that actually diverged here: this app used to accept
     * it as a one-character name.
     */
    @Test
    fun `the name trim set is the named union, not Kotlin's trim`() {
        val m = words.replace(" ", "+")
        fun name(encoded: String) = ok("btvault1:m=$m&v=$vaultId&n=$encoded").name

        // Only trim-set code points ⇒ the hint is ABSENT, not a blank name.
        assertNull("U+001F alone", name("%1F"))
        assertNull("U+FEFF alone — Kotlin's trim() would have kept this", name("%EF%BB%BF"))
        assertNull("NUL alone", name("%00"))
        assertNull("U+0085 NEL alone", name("%C2%85"))
        assertNull("U+00A0 NBSP alone — Character.isWhitespace() would have kept this", name("%C2%A0"))
        assertNull("U+2028 LINE SEPARATOR alone", name("%E2%80%A8"))
        assertNull("U+3000 IDEOGRAPHIC SPACE alone", name("%E3%80%80"))
        assertNull("mixed trim-set code points", name("%20%1F%EF%BB%BF%0A"))

        // Edges only. The interior is the wire's business and the render
        // sanitizer's problem, never the parser's.
        assertEquals("Urlaub", name("%1FUrlaub%1F"))
        assertEquals("Urlaub", name("%EF%BB%BFUrlaub%EF%BB%BF"))
        assertEquals("Urlaub", name("%00%20Urlaub%20%00"))
        assertEquals("Ur\u001Flaub", name("Ur%1Flaub"))
        assertEquals("Ur\uFEFFlaub", name("Ur%EF%BB%BFlaub"))

        // U+200B ZERO WIDTH SPACE is Cf with White_Space=No and is NOT U+FEFF,
        // so it is not in the set — a name is not silently reshaped by it.
        assertEquals("\u200BUrlaub\u200B", name("%E2%80%8BUrlaub%E2%80%8B"))
        // Neither is the RTL override; it is stripped at render, not at parse.
        assertEquals("\u202EUrlaub", name("%E2%80%AEUrlaub"))
    }

    /**
     * **Trim before cap — platform ruling, 2026-09-01.** The 64-code-point
     * limit applies to the TRIMMED value. Capping first would count the padding
     * a sender put on the wire, see 66 code points and refuse the whole phrase
     * transfer over two spaces.
     */
    @Test
    fun `the cap applies to the trimmed name, so padding a full-length name is fine`() {
        val m = words.replace(" ", "+")
        val full = "a".repeat(64)
        assertEquals(full, ok("btvault1:m=$m&v=$vaultId&n=%20$full%20").name)
        assertEquals(full, ok("btvault1:m=$m&v=$vaultId&n=%1F$full%EF%BB%BF").name)
        // 65 real code points is still too long, padded or not.
        assertEquals(
            VaultQrRejection.NAME_TOO_LONG,
            rejected("btvault1:m=$m&v=$vaultId&n=%20${"a".repeat(65)}%20"),
        )
    }

    /**
     * The same named set decides "blank" for the two REQUIRED keys: a
     * whitespace-only `m` or `v` is MISSING, not invalid. Kotlin's `isBlank()`
     * would have called `%00` a value and answered `invalid-mnemonic`.
     */
    @Test
    fun `a required value made only of trim-set code points is missing, not invalid`() {
        val m = words.replace(" ", "+")
        listOf("+", "%20%20", "%00", "%1F", "%EF%BB%BF").forEach {
            assertEquals(
                "for m=<$it>",
                VaultQrRejection.MISSING_MNEMONIC,
                rejected("btvault1:m=$it&v=$vaultId"),
            )
            assertEquals(
                "for v=<$it>",
                VaultQrRejection.MISSING_VAULT_ID,
                rejected("btvault1:m=$m&v=$it"),
            )
        }
    }

    @Test
    fun `a duplicate of any known key is refused`() {
        // INVERTED 2026-08-26, platform ruling 1. This case used to pin
        // first-wins ("a duplicate key takes the first value, like
        // URLSearchParams get") on the reasoning that §13 was silent. It is no
        // longer silent: a repeated KNOWN key is a reject, and the ruling widened
        // that past our own recommendation to cover `n` and `f` as well as the
        // two required keys. Duplicate keys are the one construction where
        // mainstream form parsers genuinely disagree, so one payload could
        // otherwise read as two different vaults on two clients.
        val m = words.replace(" ", "+")
        val other = otherWords.replace(" ", "+")
        listOf(
            "btvault1:m=$m&m=$other&v=$vaultId",
            "btvault1:m=$m&v=$vaultId&v=018f3c2a-7b41-7c3e-9f21-0a1b2c3d4e50",
            "btvault1:m=$m&v=$vaultId&n=first&n=second",
            "btvault1:m=$m&v=$vaultId&f=$fingerprint&f=$fingerprint",
            // Identical repeats are refused too — the ambiguity is the defect,
            // not the disagreement between the two values.
            "btvault1:m=$m&m=$m&v=$vaultId",
        ).forEach {
            assertEquals("for <$it>", VaultQrRejection.DUPLICATE_KEY, rejected(it))
        }
    }

    @Test
    fun `a malformed fingerprint is refused at parse, shape only`() {
        // Ruling 7 (2026-08-26). SHAPE is decidable offline — exactly 16
        // base64url characters — and a malformed one is certain to fail the
        // post-fetch comparison, so refusing it here costs a user nothing. The
        // VALUE still needs the fetch; see VaultQrPayload's KDoc.
        val m = words.replace(" ", "+")
        listOf(
            "aaaa", // too short
            "AbCdEfGhIjKlMn_oX", // one too long
            "AbCdEfGhIjKlMn+o", // '+' decodes to a space, not base64url
            "AbCdEfGhIjKlMn%3Do", // '=' padding is not in the base64url alphabet here
            "x".repeat(400),
        ).forEach {
            assertEquals(
                "for <$it>",
                VaultQrRejection.FINGERPRINT_INVALID,
                rejected("btvault1:m=$m&v=$vaultId&f=$it"),
            )
        }
        // Absent and blank both mean "no fingerprint", so neither is malformed.
        assertNull(ok("btvault1:m=$m&v=$vaultId&f=").fingerprint)
        assertNull(ok("btvault1:m=$m&v=$vaultId").fingerprint)
    }

    // ── the "never throws" property ─────────────────────────────────────────

    @Test
    fun `parse never throws on arbitrary camera input`() {
        val fuzz = buildList {
            add("btvault1:" + "%".repeat(500))
            add("btvault1:" + "&".repeat(500))
            add("btvault1:m=" + "\u0000\uFFFF😀")
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
        // 64 CODE POINTS since ruling 3 (2026-08-26) — see the constant's KDoc.
        assertEquals(64, VaultQrContract.MAX_NAME_LENGTH)
        // The platform's VAULT_KEY_FINGERPRINT_CHARS (ruling 7).
        assertEquals(16, VaultQrContract.FINGERPRINT_LENGTH)
        assertTrue(VaultQrContract.FINGERPRINT_SHAPE.matches("AbCdEfGhIjKlMn_o"))
        assertFalse(VaultQrContract.FINGERPRINT_SHAPE.matches("AbCdEfGhIjKlMn_"))
        assertFalse(VaultQrContract.FINGERPRINT_SHAPE.matches("AbCdEfGhIjKlMn_oo"))
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
            fingerprint = fingerprint,
        )
        assertTrue("payload got long: ${longest.length}", longest.length <= 260)
        assertTrue(buildVaultQrPayload(words, vaultId).length in 100..200)
    }

    @Test
    fun `a well-shaped fingerprint is carried, and its VALUE is still never judged offline`() {
        // INVERTED 2026-08-26, platform ruling 7. This case used to accept ANY
        // non-empty `f` ("the fingerprint is carried but never validated
        // offline") on the reasoning that nothing offline can judge it. The
        // ruling split the question: the SHAPE is decidable offline and is now
        // checked (see `a malformed fingerprint is refused at parse`); the VALUE
        // still is not, and the fetch→unwrap→compare→verified-open order is
        // untouched. So a schema-valid fingerprint that opens no vault is still
        // accepted here — it can only fail after the header fetch.
        val m = words.replace(" ", "+")
        listOf("AbCdEfGhIjKlMn_o", "aaaaaaaaaaaaaaaa", "----____--------").forEach { f ->
            assertEquals(f, ok("btvault1:m=$m&v=$vaultId&f=$f").fingerprint)
        }
    }
}
