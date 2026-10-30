package at.bettertrack.app.vault.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `hasRevokedGrant` replayed against answers the PLATFORM function computed:
 * `vault-vectors/drive-revoked-grant.fixture.json` is written by
 * `tools/domain-vectors/generate-drive-revoked-grant.mjs`, which slices the
 * function out of the vendored `drive/driveDataHome.ts` (@ef73d282) and runs it
 * on `new Response(body)` for each hand-chosen body. Nothing here is a typed
 * expectation; the cases pin the JS semantics the port has to reproduce (JSON
 * `null`/array payloads, `??` on null members, template stringification of
 * arrays/objects, `/iu` simple case folding: Kelvin folds to `k`, `ı`/`İ` do not
 * fold to `i`, the BOM `response.json()` drops).
 */
class DriveRevokedGrantVectorTest {

    private val fixture = Json.parseToJsonElement(
        (javaClass.getResourceAsStream("/vault-vectors/drive-revoked-grant.fixture.json")
            ?: error("vault-vectors/drive-revoked-grant.fixture.json missing from test resources"))
            .bufferedReader().use { it.readText() }
    ).jsonObject

    @Test
    fun replaysEveryCaseExactly() {
        val cases = fixture.getValue("cases").jsonArray
        assertTrue("the fixture is not trivially one-sided", cases.size >= 20)
        val mismatches = cases.mapNotNull { element ->
            val case = element.jsonObject
            val body = case.getValue("body").jsonPrimitive.content
            val expected = case.getValue("revoked").jsonPrimitive.boolean
            val actual = DriveResponse(400, body.toByteArray(Charsets.UTF_8)).hasRevokedGrant()
            if (actual == expected) null else "${case.getValue("name").jsonPrimitive.content}: expected $expected"
        }
        assertEquals("mismatches: $mismatches", emptyList<String>(), mismatches)
        val revoked = cases.count { it.jsonObject.getValue("revoked").jsonPrimitive.boolean }
        assertTrue("both answers are represented", revoked in 1 until cases.size)
    }
}
