package at.bettertrack.app.data.api.dto

import at.bettertrack.app.di.AppGraph
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The **import-wizard additive-key ack** (platform PR #1558, board tick
 * 2026-08-29).
 *
 * The platform added two optional fields to `.strict()` schemas — `understanding`
 * (an object on the import PREVIEW response, generic-path batches only) and
 * `resolvedBy` (a string on import ROWS) — and asked mobile to confirm that its
 * import-payload DTOs still parse, so the mobile-safety claim could be marked
 * verified rather than assumed.
 *
 * ## The precise answer
 *
 * **This app has no import DTOs at all.** The whole `/imports/…` family
 * (`POST /imports`, `GET /imports/brokers`, `GET|DELETE /imports/{batchId}`,
 * `POST /imports/{batchId}/apply`, `PATCH /imports/{batchId}/rows/{rowId}`) and
 * the separate `/expenses/import/…` family are absent from
 * [at.bettertrack.app.data.api.BtApi], and no DTO in this package models a
 * preview, a batch or a row. The import wizard is a web surface; the app links
 * to it. `importBatch` / `importRow` do appear in the app, but as VAULT document
 * buckets (`vault/v2/VaultV2Contract.kt`) — client-side entity type names in
 * encrypted blobs, not HTTP payloads, and nothing the platform's REST schemas
 * touch.
 *
 * So the claim cannot rest on an import DTO passing a decode. It rests on two
 * facts, and this class pins both rather than asserting them in prose:
 *
 *  1. there is nothing to break — [noImportSurfaceExistsYet], which is also the
 *     tripwire that fires the day somebody adds one;
 *  2. when one is added it is tolerant by construction, because the ONE shared
 *     `Json` every Retrofit response decodes through has `ignoreUnknownKeys` —
 *     [theSharedInstanceSkipsAnAdditiveObjectAndAnAdditiveRowField], which
 *     exercises the real instance from [AppGraph] against the real shapes of
 *     `understanding` and `resolvedBy` rather than a copy of the settings.
 */
class ImportAdditiveKeyToleranceTest {

    /** The same working-directory-agnostic lookup the other source-scan tests use. */
    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("main sources not found; tried ${candidates.map { it.absolutePath }}")
    }

    // ── 1. There is no import surface to break ──────────────────────────────

    /**
     * A tripwire, not a prohibition. If a later milestone gives the app a real
     * `/imports/…` client, this fails and points its author here — at which
     * point the honest ack is a decode of the actual preview DTO, and this test
     * is replaced by one.
     */
    @Test
    fun noImportSurfaceExistsYet() {
        val btApi = File(sourceRoot(), "at/bettertrack/app/data/api/BtApi.kt")
        assertTrue("BtApi.kt not found at ${btApi.absolutePath}", btApi.isFile)
        val routes = Regex("""@(?:GET|POST|PUT|PATCH|DELETE|HTTP)\("([^"]*)"""")
            .findAll(btApi.readText())
            .map { it.groupValues[1] }
            .filter { it.contains("import", ignoreCase = true) }
            .toList()
        assertEquals(
            "BtApi now declares import route(s) $routes. The import-payload " +
                "additive-key ack (platform #1558: `understanding`, `resolvedBy`) can " +
                "and must now be made against the real DTO — replace this test with a " +
                "decode of it.",
            emptyList<String>(),
            routes,
        )

        val dtoDir = File(sourceRoot(), "at/bettertrack/app/data/api/dto")
        val importDtos = dtoDir.listFiles().orEmpty()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                Regex("""(?:data\s+)?class\s+(\w*Import\w*)""")
                    .findAll(file.readText())
                    .map { "${file.name}:${it.groupValues[1]}" }
            }
        assertEquals(
            "An import DTO appeared: $importDtos. See the message above.",
            emptyList<String>(),
            importDtos,
        )
    }

    // ── 2. The one shared Json tolerates the additive keys ──────────────────

    /**
     * A stand-in for the platform's `ImportPreviewResponse`, declaring exactly
     * the fields a client would model today and NONE of the two the platform
     * added — which is the whole point. If the shared instance were strict, this
     * decode would throw on `understanding` and on the rows' `resolvedBy`, and
     * that is the failure mode the ack is about.
     */
    @Serializable
    private data class PreviewStandIn(val batch: BatchStandIn, val rows: List<RowStandIn>)

    @Serializable
    private data class BatchStandIn(val id: String, val status: String)

    @Serializable
    private data class RowStandIn(val id: String, val rowIndex: Int, val flag: String)

    /**
     * The real payload shapes, taken from the deployed `openapi.json`:
     * `understanding` is an OBJECT carrying an array of objects (mappings, each
     * with an optional nested `alternative` object), and `resolvedBy` is a
     * string enum (`"user"`) on each row. Nested skipping is the part worth
     * proving — a scalar is the easy case.
     */
    @Test
    fun theSharedInstanceSkipsAnAdditiveObjectAndAnAdditiveRowField() {
        val raw = """
            {
              "batch": {"id":"b1","status":"preview"},
              "understanding": {
                "mappings": [
                  {"header":"Datum","field":"date","confidence":0.98,
                   "reason":"header match","needsReview":false,
                   "alternative":{"header":"Valuta","confidence":0.41},
                   "source":"ai"}
                ],
                "unmappedHeaders": ["Notiz"],
                "delimiter": ";", "encoding": "utf-8",
                "dateLocale": "de-AT", "numberLocale": "de-AT",
                "dateLocaleAmbiguous": false
              },
              "rows": [
                {"id":"r1","rowIndex":0,"flag":"ok","resolvedBy":"user"},
                {"id":"r2","rowIndex":1,"flag":"review","resolvedBy":"user"}
              ]
            }
        """.trimIndent()

        val preview = AppGraph.json.decodeFromString(PreviewStandIn.serializer(), raw)

        assertEquals("b1", preview.batch.id)
        assertEquals(2, preview.rows.size)
        assertEquals(listOf("r1", "r2"), preview.rows.map { it.id })
        assertEquals("review", preview.rows[1].flag)
    }

    /**
     * The same guarantee on a REAL response DTO — `/auth/me`, which took its own
     * additive key (`paranoidFreshStartPending`) in the same E9 deploy. Both
     * halves of the 2026-08-29 tick reduce to this one property of the one
     * instance.
     */
    @Test
    fun theSharedInstanceTakesTheE9AdditionToTheMeResponse() {
        val raw = """
            {"id":"u1","email":"a@b.c","username":"u","role":"user","status":"active",
             "baseCurrency":"EUR","paranoidFreshStartPending":true,
             "understanding":{"mappings":[]},"resolvedBy":"user"}
        """.trimIndent()
        val me = AppGraph.json.decodeFromString(MeResponse.serializer(), raw)
        assertEquals(true, me.paranoidFreshStartPending)
        assertEquals("u1", me.id)
        assertNull(me.privacyMode)
    }
}
