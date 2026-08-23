package at.bettertrack.app.vault.pv.store

import at.bettertrack.app.data.api.BtApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PUT
import java.io.File

/**
 * The four structural rules this layer rests on, held by a test rather than by a
 * review note — because each of them is the kind of rule that decays one
 * defensible line at a time.
 *
 * 1. **No doc write without a precondition.** `A PUT with no precondition is
 *    refused 428`, and there is no situation in which the right thing to do is
 *    write a vault doc without saying what you expect to be there. The interface
 *    is what makes the mistake unspellable; this test is what keeps it that way.
 * 2. **No third way to spell a precondition.** `PvDocPrecondition` has exactly
 *    two inhabitants and neither of them means "none".
 * 3. **No mapping between a portfolio id and its doc id.** The identity is
 *    structural: one field, one value.
 * 4. **The epic stays dormant.** Nothing outside `vault/pv/…` may reach the
 *    store while `ParanoidVaultsFlags.enabled` is `false` — the flag is the
 *    promise, this is the proof.
 */
class PvBlobStoreDisciplineTest {

    private fun moduleRoot(): File =
        listOf(File("src"), File("app/src")).firstOrNull { it.isDirectory }
            ?: error("app sources not found from ${File(".").absolutePath}")

    private fun mainSources(): List<File> =
        moduleRoot().resolve("main/java/at/bettertrack/app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun source(relative: String): File =
        moduleRoot().resolve("main/java/at/bettertrack/app/$relative").also {
            assertTrue("missing source ${it.absolutePath}", it.isFile)
        }

    // ── 1. No doc write without a precondition ──────────────────────────────

    private class DocPut(val name: String, val path: String, val method: java.lang.reflect.Method)

    private fun docWriteMethods(): List<DocPut> = BtApi::class.java.declaredMethods.mapNotNull { method ->
        val path = method.getAnnotation(PUT::class.java)?.value
            ?: method.getAnnotation(HTTP::class.java)
                ?.takeIf { it.method.equals("PUT", ignoreCase = true) }
                ?.path
            ?: return@mapNotNull null
        if (!path.startsWith("vaults/") || !path.contains("/docs/{docId}")) return@mapNotNull null
        DocPut(method.name, path, method)
    }

    @Test
    fun `every doc PUT declares a precondition, and there is more than nothing to check`() {
        val writes = docWriteMethods()
        // The live-doc write path. Candidate staging also PUTs under a
        // `/docs/{docId}` suffix and is deliberately unconditional — a candidate
        // is not a live doc and re-staging rotates its id — so it is excluded by
        // its distinct prefix below rather than by being forgotten.
        val liveDocWrites = writes.filter { it.path == "vaults/{vaultId}/docs/{docId}" }
        assertEquals(
            "expected exactly the create and replace doc writes, found ${writes.map { it.name }}",
            2,
            liveDocWrites.size,
        )

        liveDocWrites.forEach { put ->
            val staticWildcard = put.method.getAnnotation(Headers::class.java)
                ?.value
                ?.any { it.trim().equals("If-None-Match: *", ignoreCase = true) } == true
            val ifMatchParameter = put.method.parameterAnnotations.any { annotations ->
                annotations.any { it is Header && it.value == "If-Match" }
            }
            assertTrue(
                "${put.name} PUTs a vault doc without a precondition — the server answers 428",
                staticWildcard || ifMatchParameter,
            )
            assertTrue(
                "${put.name} declares BOTH preconditions; the contract wants exactly one",
                staticWildcard != ifMatchParameter,
            )
        }
    }

    @Test
    fun `the replace write's If-Match parameter is not nullable`() {
        // Java reflection cannot see Kotlin nullability (the annotation has CLASS
        // retention), so this reads the declaration — the same idiom the theme and
        // custody discipline tests use. A `String?` here would let a call site pass
        // null and turn a compile error back into a 428.
        val declaration = source("data/api/BtApi.kt").readText()
        val marker = "@Header(\"If-Match\")"
        assertTrue("BtApi declares no If-Match parameter", marker in declaration)
        declaration.split(marker).drop(1).forEach { tail ->
            val parameter = tail.substringBefore(",").trim()
            assertTrue(
                "the If-Match parameter must be a non-null String, found: $parameter",
                Regex("^\\w+\\s*:\\s*String$").matches(parameter),
            )
        }
    }

    // ── 2. No third way to spell a precondition ─────────────────────────────

    @Test
    fun `the precondition type has exactly two inhabitants and neither means none`() {
        val declaration = source("vault/pv/store/PvDocPrecondition.kt").readText()
        // Anchored on `: PvDocPrecondition` so the file's other value class
        // (`PvDocEtag`) is not mistaken for a third way to spell a precondition.
        val inhabitants = Regex("(?:data object|value class)\\s+(\\w+)[^\\n]*:\\s*PvDocPrecondition")
            .findAll(declaration)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(setOf("CreateOnly", "Replace"), inhabitants)
        listOf("None", "Unconditional", "Force", "Overwrite").forEach { banned ->
            assertTrue(
                "a '$banned' precondition would re-open the blind overwrite this type prevents",
                banned !in inhabitants,
            )
        }
    }

    // ── 3. No mapping between a portfolio id and its doc id ─────────────────

    @Test
    fun `a portfolio doc reference holds exactly one value`() {
        val fields = PvDocRef.Portfolio::class.java.declaredFields
            .filterNot { it.isSynthetic }
        assertEquals(
            "a second field is where a docId-to-portfolioId mapping would live: ${fields.map { it.name }}",
            1,
            fields.size,
        )
        val id = "018f0000-0000-7000-8000-0000000000b7"
        assertEquals(id, PvDocRef.Portfolio(id).docId)
    }

    @Test
    fun `the store package holds no id-to-id mapping`() {
        val banned = listOf(
            "docIdFor",
            "docIdOf(",
            "portfolioIdFor",
            "portfolioIdOf(",
            "docIdToPortfolio",
            "portfolioToDocId",
            "docIdByPortfolio",
        )
        val storeSources = moduleRoot().resolve("main/java/at/bettertrack/app/vault/pv/store")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the store package is missing", storeSources.isNotEmpty())
        storeSources.forEach { file ->
            val text = file.readText()
            banned.forEach { needle ->
                assertTrue(
                    "${file.name} names '$needle' — a portfolio doc IS its portfolio's id, " +
                        "there is nothing to map",
                    needle !in text,
                )
            }
        }
    }

    // ── 4. The epic stays dormant ───────────────────────────────────────────

    /** Strip `//` line comments and block-comment bodies — KDoc prose is not code. */
    private fun codeOf(file: File): String {
        val out = StringBuilder()
        var inBlock = false
        file.readLines().forEach { raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) return@forEach
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
            val slashes = line.indexOf("//")
            if (slashes >= 0) line = line.substring(0, slashes)
            out.append(line).append('\n')
        }
        return out.toString()
    }

    @Test
    fun `nothing outside the paranoid-vaults package reaches the blob store`() {
        // Comments are stripped first: `BtApi` NAMES the store in a note that says
        // it is the only caller, and a rule that a doc comment can break is a rule
        // that gets deleted rather than obeyed.
        val offenders = mainSources()
            .filterNot { it.path.replace(File.separatorChar, '/').contains("/vault/pv/") }
            .filter { file ->
                val text = codeOf(file)
                "PvBlobStore" in text || "PvVaultDocDirectory" in text
            }
            .map { it.name }
        assertEquals(
            "the E1 store must stay unreachable while ParanoidVaultsFlags.enabled is false",
            emptyList<String>(),
            offenders,
        )
    }
}
