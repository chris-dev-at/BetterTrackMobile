package at.bettertrack.app.vault.pv.custody

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing in the custody rail may log a secret.
 *
 * §16 makes the 12 words the only way into a vault — there is no escrow, no
 * reset, no support path. A phrase or its entropy in logcat is therefore not an
 * information leak of the usual kind; it is the whole vault, sitting in a
 * buffer any app with `READ_LOGS` on a rooted device, any bug report, and any
 * screen-shared terminal can read. The same goes for the device password and
 * `K_dev`.
 *
 * The production code follows the pattern `VaultKeyCustody` established —
 * presence-only diagnostics, e.g. `Log.d(TAG, "pv unlock rejected: ${cause.code}")`
 * — and this test is what keeps it that way. It is deliberately a source scan
 * rather than a review note, for the same reason `BtThemeDisciplineTest` scans
 * for colour literals: this is a rule that decays one defensible line at a
 * time.
 */
class PvCustodySourceDisciplineTest {

    /**
     * Identifiers that name a secret, or a value one byte of arithmetic away
     * from one. A `Log.` call mentioning any of them is the failure.
     */
    private val forbidden = listOf(
        "entropy",
        "payload",
        "password",
        "passphrase",
        "mnemonic",
        "phrase",
        "words",
        "seed",
        "secret",
        "deviceKey",
        "kdev",
        "wrapCheck",
        "salt",
    )

    private fun moduleRoot(): File =
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("app sources not found from ${File(".").absolutePath}")

    /** Both halves of the custody rail: the logic and its surfaces. */
    private fun custodySources(): List<File> {
        val roots = listOf(
            moduleRoot().resolve("java/at/bettertrack/app/vault/pv/custody"),
            moduleRoot().resolve("java/at/bettertrack/app/ui/vault/custody"),
        )
        roots.forEach { assertTrue("missing custody source root ${it.absolutePath}", it.isDirectory) }
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }

    /** Strip `//` line comments and block-comment bodies — KDoc prose is not code. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        file.readLines().forEachIndexed { index, raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEachIndexed
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            if (line.isNotBlank()) out += (index + 1) to line
        }
        return out
    }

    private val logCall = Regex("""\bLog\.[vdiwe]\s*\(""")

    @Test
    fun `no custody source logs anything that could be a secret`() {
        val offenders = custodySources().flatMap { file ->
            codeLines(file)
                .filter { (_, line) -> logCall.containsMatchIn(line) }
                .filter { (_, line) ->
                    val lower = line.lowercase()
                    forbidden.any { lower.contains(it.lowercase()) }
                }
                .map { (ln, line) -> "${file.name}:$ln  ${line.trim()}" }
        }
        assertTrue(
            "A custody log line must carry presence only — an error code, never a value. " +
                "Offending lines:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no custody source interpolates anything into a log at all`() {
        // The stronger half of the rule, and the one that survives a rename: the
        // only interpolation allowed inside a `Log.` call is an error code, so
        // anything else in a `${…}` is refused by name.
        val allowed = Regex("""\$\{?(cause\.code|code)}?""")
        val interpolation = Regex("""\$\{[^}]*}|\$\w+""")
        val offenders = custodySources().flatMap { file ->
            codeLines(file)
                .filter { (_, line) -> logCall.containsMatchIn(line) }
                .flatMap { (ln, line) ->
                    interpolation.findAll(line)
                        .map { it.value }
                        .filterNot { allowed.matches(it) }
                        .map { "${file.name}:$ln  $it" }
                        .toList()
                }
        }
        assertTrue(
            "Only an error code may be interpolated into a custody log:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the guard would actually catch a violation`() {
        // A source-scanning guard that silently stops matching is worse than no
        // guard, so prove it fires on the shapes it claims to cover — and stays
        // quiet on the sanctioned one.
        val caught = listOf(
            """Log.d(TAG, "unwrapped entropy: ${'$'}entropy")""",
            """Log.w(TAG, "payload was " + payload)""",
            """Log.i(TAG, "password length ${'$'}{password.length}")""",
            """Log.e(TAG, "seed words ${'$'}words")""",
        )
        caught.forEach { line ->
            val lower = line.lowercase()
            assertTrue(
                "the secret-name guard missed: $line",
                logCall.containsMatchIn(line) && forbidden.any { lower.contains(it) },
            )
        }

        val sanctioned = """Log.d(TAG, "pv unlock rejected: ${'$'}{cause.code}")"""
        val lower = sanctioned.lowercase()
        assertTrue(
            "the guard now rejects the presence-only form the code is supposed to use",
            forbidden.none { lower.contains(it) },
        )
    }
}
