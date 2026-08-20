package at.bettertrack.app.ui.vault.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guards for the §13 surface — the rules that are easy to state, easy
 * to break by accident, and invisible in a code review of a later diff.
 *
 * Same shape as the app's other discipline tests
 * ([at.bettertrack.app.ui.theme.BtThemeDisciplineTest],
 * [at.bettertrack.app.i18n.StringParityTest]): read the sources, assert the
 * property, name the offender.
 */
class VaultQrDisciplineTest {

    private fun repoFile(relative: String): File {
        // Unit tests run with the module dir as CWD; tolerate the repo root.
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.exists() }
            ?: error("not found: ${candidates.map { it.absolutePath }}")
    }

    private fun qrSources(): List<Pair<String, String>> {
        val root = repoFile("src/main/java/at/bettertrack/app/ui/vault/qr")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }

    /** Source with `//` comments and KDoc body lines stripped. */
    private fun code(text: String): String = text.lineSequence()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") }
        .joinToString("\n")

    // ── the flag is the only door ───────────────────────────────────────────

    /**
     * Composables that are entry points a caller would mount. Each one is a
     * user-reachable surface and must refuse to render while the program is off.
     */
    private val gatedEntryPoints = setOf(
        "VaultQrShowScreen.kt",
        "VaultQrScanScreen.kt",
        "VaultQrEntrySheet.kt",
    )

    /**
     * The one public composable that is deliberately NOT gated: it paints
     * nothing, it only sets a window flag, and it is composed exclusively by the
     * gated screens above. Gating it would add a second place to forget.
     */
    private val ungatedHelpers = setOf("SecureScreenEffect.kt")

    @Test
    fun `every paranoid-vaults entry point refuses to render while the flag is off`() {
        val guard = "if (!ParanoidVaultsFlags.enabled) return"
        val offenders = qrSources()
            .filter { (name, _) -> name in gatedEntryPoints }
            .filterNot { (_, text) -> code(text).contains(guard) }
            .map { (name, _) -> name }
        assertTrue(
            "These surfaces are reachable without the program flag. Add `$guard` " +
                "as the first statement:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // Both directions: a file listed here that has vanished would silently
        // stop being checked.
        val present = qrSources().map { it.first }.toSet()
        assertTrue(
            "gated entry point missing from the source tree: ${gatedEntryPoints - present}",
            present.containsAll(gatedEntryPoints),
        )
    }

    @Test
    fun `no new public composable slips in ungated`() {
        val publicComposable = Regex("""@Composable\s*\n\s*fun\s+([A-Z]\w*)""")
        val offenders = qrSources().flatMap { (name, text) ->
            if (name in gatedEntryPoints || name in ungatedHelpers) return@flatMap emptyList()
            publicComposable.findAll(text).map { "$name:${it.groupValues[1]}" }.toList()
        }
        assertTrue(
            "A new public composable in ui/vault/qr is a new door into a program " +
                "that is not finished. Gate it on ParanoidVaultsFlags.enabled and " +
                "list it in gatedEntryPoints:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ── the seed phrase never leaves the screen ─────────────────────────────

    @Test
    fun `nothing in the QR surface logs, clipboards or persists`() {
        // §13: display → camera is the whole channel. A log line, a clipboard
        // write or a saved-state handle each turn a secret into a stored one.
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
            "SharedPreferences" to "the phrase is never persisted by this surface",
        )
        val offenders = qrSources().flatMap { (name, text) ->
            val stripped = code(text)
            banned.filterKeys { stripped.contains(it) }.map { (token, why) -> "$name: $token — $why" }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `both legs hold the secure-screen flag`() {
        // §13 names FLAG_SECURE on the show AND the scan screen; forgetting one
        // makes a screen recording of the receiver as good as one of the sender.
        listOf("VaultQrShowScreen.kt", "VaultQrScanScreen.kt").forEach { name ->
            val text = qrSources().first { it.first == name }.second
            assertTrue("$name does not call SecureScreenEffect()", code(text).contains("SecureScreenEffect()"))
        }
    }

    // ── the manifest half of the receiver leg ───────────────────────────────

    @Test
    fun `the manifest declares the camera permission and keeps the feature optional`() {
        val manifest = repoFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "CAMERA permission missing — the scanner cannot bind a provider without it",
            manifest.contains("""android:name="android.permission.CAMERA""""),
        )
        val feature = Regex(
            """<uses-feature[^>]*android:name="android\.hardware\.camera\.any"[^>]*?android:required="(\w+)"""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(manifest)
        assertEquals(
            "camera.any must stay optional so a camera-less device can still install",
            "false",
            feature?.groupValues?.get(1),
        )
    }

    // ── the retired v2 QR is untouched ──────────────────────────────────────

    @Test
    fun `the v2 QR file is left alone and still owns the colliding prefix`() {
        // Board ask #83 is with the platform. Until it answers, both files use
        // `btvault1:` and the pv parser discriminates by body shape — which only
        // works while the v2 body stays JSON.
        val v2 = repoFile("src/main/java/at/bettertrack/app/vault/v2/VaultQr.kt").readText()
        assertTrue("v2 no longer serializes a JSON body", v2.contains("jsJsonStringify"))
        val contract = repoFile("src/main/java/at/bettertrack/app/vault/v2/VaultV2Contract.kt").readText()
        assertTrue(
            "the prefix collision this parser works around has changed shape",
            contract.contains("""QR_PREFIX: String = "btvault1:""""),
        )
    }

    @Test
    fun `the live v1 envelope version is not touched by this arc`() {
        val contracts = repoFile("src/main/java/at/bettertrack/app/vault/VaultContracts.kt").readText()
        assertTrue(
            "VaultContract.FORMAT_VERSION is frozen for the live vault rail",
            contracts.contains("const val FORMAT_VERSION: Int = 1"),
        )
    }
}
