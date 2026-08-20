package at.bettertrack.app.ui.vault.create

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the §21 ceremony and the §4 key core behind it — the
 * rules that are easy to state, easy to break by accident, and invisible in a
 * review of a later diff.
 *
 * Same shape as the surfaces it sits next to
 * ([at.bettertrack.app.ui.vault.qr.VaultQrDisciplineTest],
 * [at.bettertrack.app.vault.pv.custody.PvCustodySourceDisciplineTest],
 * [at.bettertrack.app.ui.theme.BtThemeDisciplineTest]): read the sources,
 * assert the property, name the offender.
 *
 * Two roots are covered, because the ceremony and the keys it mints are one
 * secret with two halves: `ui/vault/create` (the screens) and `vault/pv/keys`
 * (the mint and the blocked derivation).
 */
class PvCreateDisciplineTest {

    private fun repoFile(relative: String): File {
        // Unit tests run with the module dir as CWD; tolerate the repo root.
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("not found: ${candidates.map { it.absolutePath }}")
    }

    private fun sourcesUnder(relative: String): List<Pair<String, String>> =
        repoFile(relative).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()

    private fun createSources(): List<Pair<String, String>> =
        sourcesUnder("src/main/java/at/bettertrack/app/ui/vault/create")

    private fun keySources(): List<Pair<String, String>> =
        sourcesUnder("src/main/java/at/bettertrack/app/vault/pv/keys")

    /** Source with `//` comments and KDoc body lines stripped. */
    private fun code(text: String): String = text.lineSequence()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .filterNot { it.trimStart().startsWith("/*") }
        .joinToString("\n")

    // ── the flag is the only door ───────────────────────────────────────────

    /** Every composable a caller could mount. Each must refuse while the program is off. */
    private val gatedEntryPoints = setOf("PvCreateWizard.kt")

    @Test
    fun `every ceremony entry point refuses to render while the flag is off`() {
        val guard = "if (!ParanoidVaultsFlags.enabled) return"
        val offenders = createSources()
            .filter { (name, _) -> name in gatedEntryPoints }
            .filterNot { (_, text) -> code(text).contains(guard) }
            .map { (name, _) -> name }
        assertTrue(
            "These surfaces are reachable without the program flag. Add `$guard` " +
                "as the first statement:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        val present = createSources().map { it.first }.toSet()
        assertTrue(
            "gated entry point missing from the source tree: ${gatedEntryPoints - present}",
            present.containsAll(gatedEntryPoints),
        )
    }

    @Test
    fun `both public composables in the file carry their own guard`() {
        // PvCreateWizard.kt holds two: the route that owns the state and the
        // stateless wizard. One guard covering "the file" is not a thing — a
        // caller mounts a function, so every function is a door.
        val text = code(createSources().first { it.first == "PvCreateWizard.kt" }.second)
        assertEquals(
            "each public composable in the ceremony needs its own flag guard",
            2,
            Regex("""if \(!ParanoidVaultsFlags\.enabled\) return""").findAll(text).count(),
        )
    }

    @Test
    fun `no new public composable slips in ungated`() {
        val publicComposable = Regex("""@Composable\s*\n\s*fun\s+([A-Z]\w*)""")
        val offenders = createSources().flatMap { (name, text) ->
            if (name in gatedEntryPoints) return@flatMap emptyList()
            publicComposable.findAll(text).map { "$name:${it.groupValues[1]}" }.toList()
        }
        assertTrue(
            "A new public composable in ui/vault/create is a new door into a " +
                "program that is not finished. Make it internal, or gate it on " +
                "ParanoidVaultsFlags.enabled and list it in gatedEntryPoints:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ── the phrase never leaves the screen ──────────────────────────────────

    @Test
    fun `nothing in the ceremony or the key core logs, clipboards or persists`() {
        // The words are the whole vault (§16). A log line, a clipboard write or
        // a saved-state handle each turn a secret that dies with the screen into
        // one that outlives it.
        val banned = mapOf(
            "android.util.Log" to "a seed phrase must never reach logcat",
            "Log.d(" to "a seed phrase must never reach logcat",
            "Log.i(" to "a seed phrase must never reach logcat",
            "Log.w(" to "a seed phrase must never reach logcat",
            "Log.e(" to "a seed phrase must never reach logcat",
            "println(" to "a seed phrase must never reach stdout",
            "ClipboardManager" to "the phrase never touches the clipboard",
            "LocalClipboard" to "the phrase never touches the clipboard",
            "rememberSaveable" to "saved instance state is disk; the phrase dies with the screen",
            "SharedPreferences" to "the ceremony persists nothing itself",
        )
        val offenders = (createSources() + keySources()).flatMap { (name, text) ->
            val stripped = code(text)
            banned.filterKeys { stripped.contains(it) }.map { (token, why) -> "$name: $token — $why" }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the ceremony holds the secure-screen flag`() {
        // The words are rendered as plain text on one step, but they are alive
        // in the composition for the whole flow — and the recents thumbnail is
        // taken when the user leaves from any of them.
        val text = code(createSources().first { it.first == "PvCreateWizard.kt" }.second)
        assertTrue("PvCreateWizard.kt does not call SecureScreenEffect()", text.contains("SecureScreenEffect()"))
    }

    @Test
    fun `the ceremony reuses the shared secure-screen effect rather than its own`() {
        // Two owners of FLAG_SECURE is how protection gets cleared out from
        // under someone who still needs it; the shared effect restores what it
        // found. See SecureScreenEffect's KDoc.
        val offenders = createSources()
            .filter { (_, text) -> code(text).contains("FLAG_SECURE") }
            .map { it.first }
        assertTrue(
            "the ceremony must not touch the window flag directly: $offenders",
            offenders.isEmpty(),
        )
    }

    // ── the shared BIP-39 path is shared, not copied ────────────────────────

    @Test
    fun `the key core imports the one wordlist and the one entropy path`() {
        // A second copy of either would be a second thing to keep in step with a
        // digest, and the first divergence presents itself to a user as "the
        // words from my other device are wrong" — with no way back (§16).
        val issuance = keySources().first { it.first == "PvMnemonicIssuance.kt" }.second
        assertTrue(
            "issuance must call the custody package's entropy → words renderer",
            code(issuance).contains("import at.bettertrack.app.vault.pv.custody.pvEntropyToWords"),
        )
        val offenders = keySources()
            .filter { (_, text) -> code(text).contains("BIP39_ENGLISH") }
            .map { it.first }
        assertTrue(
            "the key core must reach the wordlist through the shared renderer, " +
                "not index it itself: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the ceremony's word check uses the same normalisation as the QR path`() {
        val state = createSources().first { it.first == "PvCreateFlowState.kt" }.second
        assertTrue(
            "the one-word check must normalise exactly like manual entry and the scan",
            code(state).contains("import at.bettertrack.app.vault.v2.normalizeVaultPassphrase"),
        )
    }

    // ── the E3 stop line, from the outside ──────────────────────────────────

    @Test
    fun `the vault derivation takes every salt from the one blocked function`() {
        // The one thing that would break cross-client key agreement silently: a
        // salt written straight into an HKDF call. Board ask #83 Q4 is open, so
        // until it answers, every `salt =` in the derivation file must be the
        // call that refuses.
        val derivation = code(keySources().first { it.first == "PvVaultKeyDerivation.kt" }.second)
        val arguments = Regex("""[^a-zA-Z]salt\s*=\s*([^,\n]+)""")
            .findAll(derivation)
            .map { it.groupValues[1].trim().removeSuffix(",") }
            .toList()
        assertEquals("both derivations must pass a salt explicitly", 2, arguments.size)
        assertTrue(
            "a salt reached HKDF from somewhere other than pvDerivationSalt(): $arguments",
            arguments.all { it == "pvDerivationSalt()" },
        )
        // …and BIP-39's own salt, which IS pinned by the standard, is built from
        // the fixed prefix rather than from a literal typed at the call site.
        val seed = code(keySources().first { it.first == "PvBip39Seed.kt" }.second)
        assertTrue(
            "the BIP-39 salt must come from the standard's fixed prefix",
            seed.contains("val salt = utf8(nfkd(PV_BIP39_SALT_PREFIX + passphrase))"),
        )
    }

    // ── the copy exists in both languages ───────────────────────────────────

    @Test
    fun `every ceremony string is declared in both languages`() {
        // StringParityTest already proves EN↔DE parity for the whole file; this
        // asserts the family is COMPLETE — that a step's copy was not written
        // into the composable as a literal instead.
        val en = repoFile("src/main/res/values/strings.xml").readText()
        val de = repoFile("src/main/res/values-de/strings.xml").readText()
        val used = createSources().flatMap { (_, text) ->
            Regex("""R\.string\.(bt_pv_create_\w+)""").findAll(text).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("the ceremony renders no strings at all — something is wrong", used.size > 20)
        used.forEach { key ->
            assertTrue("$key missing from values/strings.xml", en.contains("""name="$key""""))
            assertTrue("$key missing from values-de/strings.xml", de.contains("""name="$key""""))
        }
    }
}
