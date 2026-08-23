package at.bettertrack.app.vault.pv

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
 * **The platform's E7 QR conformance vectors, run against this scanner.**
 *
 * Their adversarial review of the `btvault1:` implementation found no
 * phrase-exposure path and then named the one gap a code review cannot close:
 * *their vectors were exported and our scanner was shipped, and the two had
 * never been run against each other.* [VaultQrPayloadTest] proves this parser
 * agrees with the spec **as this app read it**; this file proves it agrees with
 * the bytes the **other implementation actually emits and accepts** — which is a
 * different claim, and the only one that catches a shared misreading.
 *
 * `vault-vectors/pv-qr-e7.fixture.json` therefore carries two things per vector,
 * and the `_source` block says which is which:
 *
 *  - `payload` + `web` — transcribed from
 *    `apps/web/src/user/vault/qr/conformanceVectors.ts`, byte for byte, nothing
 *    computed here;
 *  - `android` — what THIS parser does with those exact bytes, recorded beside
 *    theirs so neither side can drift without a red test.
 *
 * ## Divergence is recorded, not silently reconciled
 *
 * Two vectors genuinely disagree (`duplicateMnemonic`, `duplicateVaultId`) and
 * one agrees on the decision but not on the reason (`bareString`). They are
 * pinned as divergences with the §13 verdict in the fixture's `note`, and the
 * divergence set itself is a tripwire: "fix" this parser to match theirs and the
 * test goes red until the fixture is updated in the same change. §13 does not
 * settle the duplicate-key question, so
 * matching them here would be a unilateral spec decision wearing a bug fix's
 * clothes — the board decides, not this file.
 *
 * ## The four leniency questions, answered by execution
 *
 * The second half answers the four cases they asked about (leading `?`,
 * duplicate `n`/`f`, a trailing `#fragment`, and control/RTL characters inside
 * `n`) with tests rather than with a reading of the code. The fragment case is
 * the one they predicted this app would accept — reasoning that an Android
 * scanner is probably built on `Uri.parse`, which strips fragments. It is not:
 * [parseVaultQrPayload] does its own form decoding for exactly this class of
 * reason, so the fragment stays glued to the last value and is rejected.
 */
class VaultQrE7ConformanceTest {

    private val fixture: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream(E7_FIXTURE) ?: error("$E7_FIXTURE missing")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val vectors: List<JsonObject> by lazy {
        fixture["vectors"]!!.jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBoolean()

    /** The all-zero-entropy BIP-39 vector phrase the platform's fixture uses. */
    private val words =
        "abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon about"

    private val vaultId = "018f6a3e-1111-7000-8000-000000000001"

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

    // ── provenance ──────────────────────────────────────────────────────────

    @Test
    fun `the fixture names the platform file, branch and commit it was transcribed from`() {
        // A vendored vector file with no provenance is indistinguishable from one
        // this app invented for itself, and an invented fixture ends an
        // investigation that should have continued.
        val source = fixture["_source"]!!.jsonObject
        assertEquals(
            "the fixture must name the platform file it was transcribed from",
            "apps/web/src/user/vault/qr/conformanceVectors.ts",
            source.str("file"),
        )
        assertTrue(
            "the fixture must carry the platform commit it was read at",
            source.str("commit").startsWith("e1882d3a"),
        )
        assertTrue(
            "E7 is not on origin/main yet - the fixture must say which branch it came off, " +
                "so a later reader does not go looking for it in main and conclude it was withdrawn",
            source.str("branch").contains("task/1417"),
        )
        assertTrue(
            "the fixture must say, in writing, that the payload/web bytes are the platform's",
            source.str("provenance").contains("PLATFORM E7 conformance vectors"),
        )
        assertFalse(
            "a platform fixture must never describe itself as self-derived",
            source.str("provenance").contains("self-derived", ignoreCase = true),
        )
    }

    @Test
    fun `the exported vector set is complete and the golden is one of them`() {
        assertEquals(
            "the platform exported 16 vectors; a missing one is a hole in the cross-check",
            16,
            vectors.size,
        )
        val golden = fixture.str("golden")
        val roundTrip = vectors.single { it.str("name") == "validRoundTrip" }
        assertEquals(
            "the golden payload and the validRoundTrip vector are the same string on their side",
            golden,
            roundTrip.str("payload"),
        )
        // Their serializer's fixed key order, verbatim - the interop anchor.
        assertEquals(
            "btvault1:m=abandon+abandon+abandon+abandon+abandon+abandon+abandon+abandon+" +
                "abandon+abandon+abandon+about" +
                "&v=018f6a3e-1111-7000-8000-000000000001" +
                "&n=Phone+vault" +
                "&f=AbCdEfGhIjKlMn_o",
            golden,
        )
    }

    @Test
    fun `this app's serializer reproduces their golden payload byte for byte`() {
        // The other direction of the same claim: not only can we read what they
        // write, what we write is the string they wrote, not merely one that
        // decodes the same.
        assertEquals(
            fixture.str("golden"),
            buildVaultQrPayload(
                mnemonic = words,
                vaultId = vaultId,
                name = "Phone vault",
                fingerprint = "AbCdEfGhIjKlMn_o",
            ),
        )
    }

    // ── the replay ──────────────────────────────────────────────────────────

    @Test
    fun `every exported vector produces the recorded result in this parser`() {
        vectors.forEach { vector ->
            val name = vector.str("name")
            val payload = vector.str("payload")
            val expected = vector["android"]!!.jsonObject
            val result = parseVaultQrPayload(payload)

            if (expected.bool("accepts")) {
                assertTrue("[$name] expected Ok, got $result", result is VaultQrParseResult.Ok)
                val actual = (result as VaultQrParseResult.Ok).payload
                val want = expected["payload"]!!.jsonObject
                assertEquals("[$name] mnemonic", want.str("mnemonic"), actual.mnemonic)
                assertEquals("[$name] vaultId", want.str("vaultId"), actual.vaultId)
                assertEquals("[$name] name", want.strOrNull("name"), actual.name)
                assertEquals("[$name] fingerprint", want.strOrNull("fingerprint"), actual.fingerprint)
            } else {
                assertTrue("[$name] expected Failed, got $result", result is VaultQrParseResult.Failed)
                assertEquals(
                    "[$name] rejection reason",
                    expected.str("reason"),
                    (result as VaultQrParseResult.Failed).reason.name,
                )
            }
        }
    }

    @Test
    fun `every vector the two clients agree on decides the same way on both sides`() {
        vectors.filter { it.str("agreement") != "different-decision" }.forEach { vector ->
            val name = vector.str("name")
            val web = vector["web"]!!.jsonObject
            val android = vector["android"]!!.jsonObject
            assertEquals(
                "[$name] accept/reject decision must match the web client",
                web.bool("accepts"),
                android.bool("accepts"),
            )
            if (web.bool("accepts")) {
                assertEquals(
                    "[$name] the two clients must parse the same payload, not merely both accept",
                    web["payload"]!!.jsonObject,
                    android["payload"]!!.jsonObject,
                )
            }
        }
    }

    @Test
    fun `the recorded divergences are exactly these, and no others`() {
        // The tripwire. Converging this parser onto theirs (or theirs onto ours)
        // is a wire-contract decision; whichever way it goes, this list changes
        // in the SAME change as the behaviour, or the change is not finished.
        assertEquals(
            "§13 is silent on repeated required keys, so the duplicate-key split is a board " +
                "question, not a bug to be fixed on one side unilaterally. If the board rules, " +
                "update the fixture and this list together.",
            listOf("duplicateMnemonic", "duplicateVaultId"),
            vectors.filter { it.str("agreement") == "different-decision" }.map { it.str("name") },
        )
        assertEquals(
            "one vector agrees on the decision but not on the reason shown to the user",
            listOf("bareString"),
            vectors.filter { it.str("agreement") == "same-decision-different-cause" }
                .map { it.str("name") },
        )
        vectors.filter { it.str("agreement") != "same-decision-same-cause" }.forEach {
            assertNotNull(
                "[${it.str("name")}] a divergence without a written §13 verdict is a landmine",
                it["note"],
            )
        }
    }

    @Test
    fun `a divergent vector really does diverge, so the record cannot go stale`() {
        // Guards the opposite failure of the tripwire above: a fixture that still
        // CLAIMS a divergence after the parser quietly stopped diverging is a lie
        // the board would act on.
        vectors.filter { it.str("agreement") == "different-decision" }.forEach { vector ->
            val name = vector.str("name")
            val web = vector["web"]!!.jsonObject
            val result = parseVaultQrPayload(vector.str("payload"))
            assertFalse(
                "[$name] is recorded as a divergence but the web rejects and so do we now - " +
                    "the divergence is gone and the fixture must be re-recorded",
                !web.bool("accepts") && result is VaultQrParseResult.Failed,
            )
        }
    }

    // ── Part 2: the four leniency questions, settled by execution ───────────

    /**
     * **Case 1 — a leading `?`.** The web accepts it (`URLSearchParams` strips a
     * leading `?` before parsing). This app REJECTS: `?m` is decoded as a key
     * literally named `?m`, so the required `m` is simply absent.
     *
     * §13 says everything after the first `:` is *one
     * `application/x-www-form-urlencoded` query string* — and a `?` is the URL's
     * query DELIMITER, never part of the form data itself. So the strict reading
     * is this app's, and the web's acceptance is `URLSearchParams` leniency
     * leaking through the spec. Nothing is unsafe either way and no serializer on
     * either side emits a `?`, so no real code is affected; it wants one sentence
     * in §13.
     */
    @Test
    fun `leniency case 1 - a leading question mark is rejected here and accepted on the web`() {
        assertEquals(
            VaultQrRejection.MISSING_REQUIRED_KEY,
            rejected("btvault1:?m=${words.replace(" ", "+")}&v=$vaultId"),
        )
        // Not a prefix problem and not a malformed body: the ? is inside the key.
        assertEquals(
            VaultQrRejection.MISSING_REQUIRED_KEY,
            rejected("btvault1:?v=$vaultId&m=${words.replace(" ", "+")}"),
        )
        // A ? anywhere else is just a character in a value, exactly as on the web.
        assertEquals("Where?", ok("btvault1:m=${words.replace(" ", "+")}&v=$vaultId&n=Where?").name)
    }

    /**
     * **Case 2 — a duplicate key.** For the OPTIONAL keys both clients agree:
     * first occurrence wins, silently (`URLSearchParams.get()` on their side,
     * `putIfAbsent` on ours).
     *
     * For the REQUIRED keys they diverge, and this is the one place the coordinator's
     * brief was wrong about this app: the web rejects a repeated `m` or `v`, while
     * this app applies the same first-wins rule and accepts — a behaviour
     * [VaultQrPayloadTest] already pins deliberately for `m`
     * (`a duplicate key takes the first value, like URLSearchParams get`).
     *
     * §13's letter is silent on repeated keys. §13's stated *rationale* is not:
     * the format is form-urlencoded "which every platform parses identically",
     * and duplicate keys are precisely where platform form parsers do NOT agree
     * (first-wins, last-wins and collect-all are all in the wild). Rejecting
     * costs nothing — no legitimate sender emits a duplicate — and removes an
     * ambiguity a hostile code could aim at two clients at once. That is a
     * recommendation, not a licence: the change belongs to the board.
     */
    @Test
    fun `leniency case 2 - duplicate keys take the first value, required ones included`() {
        val m = words.replace(" ", "+")
        // Optional keys: both clients agree, first wins.
        assertEquals("first", ok("btvault1:m=$m&v=$vaultId&n=first&n=second").name)
        assertEquals("first", ok("btvault1:m=$m&v=$vaultId&f=first&f=second").fingerprint)
        // Required keys: the web rejects both of these; this app does not.
        val otherWords = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"
        assertEquals(words, ok("btvault1:m=$m&m=${otherWords.replace(" ", "+")}&v=$vaultId").mnemonic)
        assertEquals(
            vaultId,
            ok("btvault1:m=$m&v=$vaultId&v=018f6a3e-1111-7000-8000-000000000002").vaultId,
        )
    }

    /**
     * **Case 3 — a trailing `#fragment`.** Their prediction was that this app
     * ACCEPTS, on the theory that an Android scanner is `Uri.parse`-based and
     * `Uri` strips the fragment. The prediction is wrong, and the reason is in
     * [VaultQrPayload]'s KDoc: this parser is deliberately self-contained and
     * never touches `android.net.Uri` or `java.net.URLDecoder`, so `#` is an
     * ordinary character in the value it lands in.
     *
     * Both clients therefore land in the same place for the shape §13 actually
     * specifies (`m` then `v`, fragment glued to the vault id): `invalid-vault-id`
     * on the web, [VaultQrRejection.VAULT_ID_INVALID] here. Where the fragment
     * lands on a trailing OPTIONAL key both clients also agree — and both accept a
     * name with `#frag` welded on, which is a display-hint wart on both sides and
     * not a divergence.
     */
    @Test
    fun `leniency case 3 - a trailing fragment is not stripped, and both clients reject it`() {
        val m = words.replace(" ", "+")
        // The specified key order: the fragment sticks to `v` and breaks the UUID.
        assertEquals(
            VaultQrRejection.VAULT_ID_INVALID,
            rejected("btvault1:m=$m&v=$vaultId#frag"),
        )
        // Even a bare '#' is enough - no Uri.parse anywhere in this path.
        assertEquals(VaultQrRejection.VAULT_ID_INVALID, rejected("btvault1:m=$m&v=$vaultId#"))
        // A fragment on the phrase breaks the last word, not the UUID.
        assertEquals(
            VaultQrRejection.PHRASE_INVALID,
            rejected("btvault1:m=$m%23frag&v=$vaultId"),
        )
        // A fragment on a trailing optional key is absorbed into that value -
        // identical to URLSearchParams, which is also not a URL parser.
        assertEquals(
            "Phone vault#frag",
            ok("btvault1:m=$m&v=$vaultId&n=Phone+vault#frag").name,
        )
    }

    /**
     * **Case 4 — NUL, newline and RTL overrides inside `n`.** Both clients ACCEPT
     * them. §13 caps `n` at 64 characters and says nothing at all about which
     * characters are legal, so neither side is violating it.
     *
     * The asymmetry the platform themselves flagged is that this is inert for
     * them (the web ignores the name hint) and NOT inert for us: this app renders
     * the scanned `n` verbatim in the scan-result card
     * (`VaultQrScanScreen.ScanResult`, `titleLarge`) as the answer to "which
     * vault am I about to adopt?". A `%0A` turns one label into three lines; a
     * `%E2%80%AE` RIGHT-TO-LEFT OVERRIDE reverses everything after it, so a
     * hostile code can render a label that reads as a vault the user trusts. The
     * label is attacker-controlled text in a security decision — the exact shape
     * of a spoofing surface.
     *
     * Recommendation (spec, not a unilateral change): **sanitize at render, and
     * say so in §13.** Rejecting at parse throws away a phrase transfer over a
     * cosmetic hint, which is the wrong trade for the one screen whose job is to
     * get the words onto the phone; and a parser that rejects protects only the
     * clients that implement the rejection. Rendering the hint through one
     * shared "untrusted label" treatment — strip C0/C1 and U+2028/U+2029, strip
     * or isolate the bidi control range U+202A–U+202E and U+2066–U+2069, collapse
     * runs of whitespace, single line, ellipsized — protects every consumer of
     * the field regardless of who parsed it. §13 should state that `n` is
     * untrusted display text and that a renderer must neutralize formatting
     * controls; that sentence is missing today.
     */
    @Test
    fun `leniency case 4 - control and bidi characters survive parsing into the rendered name`() {
        val m = words.replace(" ", "+")
        // NUL: not whitespace, so neither isNotBlank() nor trim() drops it.
        assertEquals("\u0000pwn", ok("btvault1:m=$m&v=$vaultId&n=%00pwn").name)
        // Newline: kept when interior. The scan card renders it as a real break.
        assertEquals("Phone\nvault", ok("btvault1:m=$m&v=$vaultId&n=Phone%0Avault").name)
        // RIGHT-TO-LEFT OVERRIDE: the spoofing primitive. Parsed, kept, rendered.
        assertEquals("safe‮tluav", ok("btvault1:m=$m&v=$vaultId&n=safe%E2%80%AEtluav").name)
        // Bidi isolates too - U+2066..U+2069 are equally effective and equally kept.
        assertEquals("a⁦b⁩c", ok("btvault1:m=$m&v=$vaultId&n=a%E2%81%A6b%E2%81%A9c").name)

        // Two places where this app is stricter than the web, both from trimming:
        // a hint that is nothing but whitespace becomes "absent" rather than a
        // present empty/blank name, and surrounding spaces are dropped. The web
        // preserves the decoded value exactly (their README: "preserve its
        // decoded value exactly without normalization"), so `n=` yields "" there
        // and null here. §13 is silent; neither behaviour is unsafe.
        assertNull(ok("btvault1:m=$m&v=$vaultId&n=%0A").name)
        assertNull(ok("btvault1:m=$m&v=$vaultId&n=").name)
        assertEquals("pad", ok("btvault1:m=$m&v=$vaultId&n=%20pad%20").name)
    }

    /**
     * The name cap is measured in different units on the two sides. Their vectors
     * cannot see it — `maxLengthComposedName` uses U+00E9, one code unit and one
     * code point at the same time — so it is pinned here on purpose.
     */
    @Test
    fun `the name cap is 64 UTF-16 code units here and 64 code points on the web`() {
        val m = words.replace(" ", "+")
        // Their own vector's boundary, agreed.
        assertEquals("é".repeat(64), ok("btvault1:m=$m&v=$vaultId&n=${"%C3%A9".repeat(64)}").name)
        // The boundary their vector cannot reach: 64 astral characters are 64 code
        // points (the web accepts) and 128 UTF-16 code units (this app refuses).
        assertEquals(
            VaultQrRejection.NAME_TOO_LONG,
            rejected("btvault1:m=$m&v=$vaultId&n=${"%F0%9F%98%80".repeat(64)}"),
        )
        // 32 astral characters = 64 code units: the last length this app accepts.
        assertEquals(
            "😀".repeat(32),
            ok("btvault1:m=$m&v=$vaultId&n=${"%F0%9F%98%80".repeat(32)}").name,
        )
    }

    /**
     * `f` is validated at parse time on the web (base64url, exactly 16 chars) and
     * carried verbatim here. Not a §13 conflict: §13 gives `f` no offline job at
     * all once its "before any network fetch" wording is corrected (#1500), and a
     * junk fingerprint simply fails the post-fetch comparison. Pinned so the
     * asymmetry is on the record rather than discovered later.
     */
    @Test
    fun `an f that the web would reject as malformed is carried through here`() {
        val m = words.replace(" ", "+")
        assertEquals("short", ok("btvault1:m=$m&v=$vaultId&f=short").fingerprint)
        assertEquals("AbCdEfGhIjKlMn_o", ok("btvault1:m=$m&v=$vaultId&f=AbCdEfGhIjKlMn_o").fingerprint)
    }

    private companion object {
        const val E7_FIXTURE = "/vault-vectors/pv-qr-e7.fixture.json"
    }
}
