package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The E1 vault DTOs against the shapes the DEPLOYED `openapi.json` declares
 * (read 2026-08-23).
 *
 * Three properties are worth a test rather than a review:
 *
 * 1. **Required means required — as far as the shared `Json` allows.** Every
 *    member the schema lists in `required` is declared without a default, so a
 *    non-nullable one that the server omits fails the decode instead of becoming
 *    a silent default. The measured exception is nullable members, which
 *    `explicitNulls = false` lets decode as `null` when absent; that boundary is
 *    pinned by its own test below rather than assumed either way.
 * 2. **Requests carry exactly the contract's keys.** `additionalProperties:
 *    false` is a two-sided rule and this is the side the client controls.
 * 3. **`explicitNulls = false` does not eat a required-and-nullable member.**
 *    That is a live trap on this surface, and the last test in this file is the
 *    proof that it is real — a plain `String?` null IS dropped, so the three
 *    members that must transmit an explicit `null` are typed `JsonElement`.
 */
class VaultDtoContractTest {

    /** Matches the app's production Json config (see `di/AppGraph`). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun keysOf(encoded: String): Set<String> =
        json.parseToJsonElement(encoded).jsonObject.keys

    // ── VaultConfig ─────────────────────────────────────────────────────────

    private val vaultConfigJson = """
        {
          "id": "018f0000-0000-7000-8000-000000000001",
          "name": "Family",
          "headerDocId": "018f0000-0000-7000-8000-0000000000a1",
          "commonDocId": "018f0000-0000-7000-8000-0000000000a2",
          "media": ["server"],
          "driveConnectionId": null,
          "keyFingerprint": "AbCdEfGhIjKlMnOp",
          "retirementProofPublicKey": "MCowBQYDK2VwAyEAsdfghjklqwertyuiopzxcvbnmASDFGHJKLQWERTYUI",
          "retirementGeneration": 0,
          "mediaAttestedAt": null,
          "mediaAttestedDriveConnectionId": null,
          "createdAt": "2026-08-23T09:00:00.000Z",
          "updatedAt": "2026-08-23T09:00:00.000Z"
        }
    """.trimIndent()

    @Test
    fun `a vault config decodes with every declared member`() {
        val config = json.decodeFromString(VaultConfigDto.serializer(), vaultConfigJson)
        assertEquals("018f0000-0000-7000-8000-000000000001", config.id)
        assertEquals("Family", config.name)
        assertEquals("018f0000-0000-7000-8000-0000000000a1", config.headerDocId)
        assertEquals("018f0000-0000-7000-8000-0000000000a2", config.commonDocId)
        assertEquals(listOf("server"), config.media)
        assertNull(config.driveConnectionId)
        assertEquals(0, config.retirementGeneration)
        assertNull(config.mediaAttestedAt)
    }

    @Test
    fun `an omitted required member fails the decode instead of defaulting`() {
        val without = vaultConfigJson.lines()
            .filterNot { it.contains("\"keyFingerprint\"") }
            .joinToString("\n")
            // the member before it now has a trailing comma the parser would reject
            .replace("\"media\": [\"server\"],", "\"media\": [\"server\"],")
        try {
            json.decodeFromString(VaultConfigDto.serializer(), without)
            fail("a VaultConfig without keyFingerprint must not decode")
        } catch (_: Exception) {
            // expected
        }
    }

    /**
     * The measured limit of this client's `.strict()` fidelity, recorded rather
     * than wished away.
     *
     * `explicitNulls = false` is a two-way setting: it drops nulls on encode AND
     * it treats an ABSENT nullable member as `null` on decode. So for the
     * required-**and**-nullable members of these responses — `driveConnectionId`,
     * `mediaAttestedAt`, `mediaAttestedDriveConnectionId`, `nextCursor`,
     * `server.retirement` — this client cannot tell "the server sent null" from
     * "the server sent nothing".
     *
     * Judged and accepted rather than worked around: every one of those members
     * means an absence anyway (no Drive binding, no attestation, no next page, no
     * retirement row), so the two readings coincide, and the alternative would be
     * a second `Json` instance whose settings drift from the app's. What is NOT
     * relaxed is the case that matters — a missing NON-nullable member still
     * fails the decode, as the test above proves — so the server can never hand
     * this client a vault row with no `keyFingerprint` or no `headerDocId` and
     * have it silently become a default.
     */
    @Test
    fun `an omitted required-but-nullable member decodes as null, and that is known`() {
        val absent = vaultConfigJson.lines()
            .filterNot { it.contains("\"driveConnectionId\"") }
            .joinToString("\n")
        val decoded = json.decodeFromString(VaultConfigDto.serializer(), absent)
        assertNull(decoded.driveConnectionId)

        // The half that is still enforced, stated beside it so the boundary is
        // visible in one place.
        val noHeaderDoc = vaultConfigJson.lines()
            .filterNot { it.contains("\"headerDocId\"") }
            .joinToString("\n")
        try {
            json.decodeFromString(VaultConfigDto.serializer(), noHeaderDoc)
            fail("a non-nullable required member must still fail the decode when absent")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun `unknown server members are tolerated, as the shared Json is configured to`() {
        val widened = vaultConfigJson.replace(
            "\"name\": \"Family\",",
            "\"name\": \"Family\", \"somethingTheServerAddedLater\": 7,",
        )
        val config = json.decodeFromString(VaultConfigDto.serializer(), widened)
        assertEquals("Family", config.name)
    }

    @Test
    fun `the list, create, read and patch responses all carry the same vault object`() {
        val list = json.decodeFromString(
            VaultListResponse.serializer(),
            """{"vaults":[$vaultConfigJson]}""",
        )
        assertEquals(1, list.vaults.size)

        // `CreateVaultResponse`, the `GET /vaults/{id}` body and `PatchVaultResponse`
        // are three names for one shape — one Kotlin type is what keeps them from
        // drifting apart in this client.
        val wrapped = """{"vault":$vaultConfigJson}"""
        val created = json.decodeFromString(VaultConfigResponse.serializer(), wrapped)
        val patched = json.decodeFromString(VaultConfigResponse.serializer(), wrapped)
        assertEquals(created, patched)
        assertEquals("Family", created.vault.name)
    }

    // ── Requests ────────────────────────────────────────────────────────────

    @Test
    fun `a create-vault request sends exactly the contract's members`() {
        val request = CreateVaultRequest(
            name = "Family",
            headerDocId = "018f0000-0000-7000-8000-0000000000a1",
            commonDocId = "018f0000-0000-7000-8000-0000000000a2",
            media = listOf("server"),
            driveConnectionId = null,
            keyFingerprint = "AbCdEfGhIjKlMnOp",
            retirementProofPublicKey = "MCowBQYDK2VwAyEAsdfghjklqwertyuiopzxcvbnmASDFGHJKLQWERTYUI",
        )
        val encoded = json.encodeToString(CreateVaultRequest.serializer(), request)
        // `driveConnectionId` is OPTIONAL in the contract (`default: null`), so
        // omitting it is what the schema asks for — unlike the three
        // required-and-nullable members tested at the bottom of this file.
        assertEquals(
            setOf(
                "name",
                "headerDocId",
                "commonDocId",
                "media",
                "keyFingerprint",
                "retirementProofPublicKey",
            ),
            keysOf(encoded),
        )
        val bound = json.encodeToString(
            CreateVaultRequest.serializer(),
            request.copy(
                media = listOf("server", "drive"),
                driveConnectionId = "018f0000-0000-7000-8000-0000000000d1",
            ),
        )
        assertTrue("driveConnectionId" in keysOf(bound))
    }

    @Test
    fun `a delete-vault request carries only the credential it was given`() {
        val encoded = json.encodeToString(
            DeleteVaultRequest.serializer(),
            DeleteVaultRequest(VaultStepUpDto(password = "hunter2")),
        )
        assertEquals(setOf("stepUp"), keysOf(encoded))
        val stepUp = json.parseToJsonElement(encoded).jsonObject["stepUp"]!!.jsonObject
        assertEquals(setOf("password"), stepUp.keys)

        val totp = json.encodeToString(
            DeleteVaultRequest.serializer(),
            DeleteVaultRequest(VaultStepUpDto(code = "123456")),
        )
        assertEquals(
            setOf("code"),
            json.parseToJsonElement(totp).jsonObject["stepUp"]!!.jsonObject.keys,
        )
    }

    @Test
    fun `a delete-vault response is the contract's ok flag`() {
        val decoded = json.decodeFromString(DeleteVaultResponse.serializer(), """{"ok":true}""")
        assertTrue(decoded.ok)
    }

    // ── History ─────────────────────────────────────────────────────────────

    @Test
    fun `a history page decodes both cursor states`() {
        val page = json.decodeFromString(
            VaultHistoryListResponse.serializer(),
            """
            {
              "items": [
                {"version": 7, "createdAt": "2026-08-23T09:00:00.000Z", "sizeBytes": 4096, "medium": "server"}
              ],
              "nextCursor": null
            }
            """.trimIndent(),
        )
        assertEquals(1, page.items.size)
        assertEquals(7, page.items.single().version)
        assertEquals(4096L, page.items.single().sizeBytes)
        assertEquals("server", page.items.single().medium)
        assertNull(page.nextCursor)

        val more = json.decodeFromString(
            VaultHistoryListResponse.serializer(),
            """{"items":[],"nextCursor":6}""",
        )
        assertEquals(6, more.nextCursor)
    }

    // ── Media state ─────────────────────────────────────────────────────────

    @Test
    fun `the media state decodes an empty server disposition and a retired one`() {
        val empty = json.decodeFromString(
            PerVaultMediaStateResponse.serializer(),
            """
            {
              "vaultId": "018f0000-0000-7000-8000-000000000001",
              "media": ["server"],
              "driveConnectionId": null,
              "mediaAttestedAt": null,
              "mediaAttestedDriveConnectionId": null,
              "server": {"disposition": "empty", "candidates": [], "retirement": null}
            }
            """.trimIndent(),
        )
        assertEquals("empty", empty.server.disposition)
        assertTrue(empty.server.candidates.isEmpty())
        assertNull(empty.server.retirement)

        val retired = json.decodeFromString(
            PerVaultMediaStateResponse.serializer(),
            """
            {
              "vaultId": "018f0000-0000-7000-8000-000000000001",
              "media": ["drive"],
              "driveConnectionId": "018f0000-0000-7000-8000-0000000000d1",
              "mediaAttestedAt": "2026-08-23T09:00:00.000Z",
              "mediaAttestedDriveConnectionId": "018f0000-0000-7000-8000-0000000000d1",
              "server": {
                "disposition": "retired",
                "candidates": [
                  {
                    "candidateId": "018f0000-0000-7000-8000-0000000000c1",
                    "transitionId": "018f0000-0000-7000-8000-0000000000t1",
                    "docId": "018f0000-0000-7000-8000-0000000000a1",
                    "docKind": "header",
                    "docVersion": 3,
                    "formatVersion": 2,
                    "writeId": "018f0000-0000-7000-8000-0000000000w1",
                    "sizeBytes": 512,
                    "expiresAt": "2026-08-24T09:00:00.000Z"
                  }
                ],
                "retirement": {
                  "generation": 1,
                  "versionSetHash": "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE",
                  "retiredAt": "2026-08-23T09:00:00.000Z",
                  "purgeAfter": "2026-08-30T09:00:00.000Z"
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals("retired", retired.server.disposition)
        assertEquals("header", retired.server.candidates.single().docKind)
        assertEquals(1, retired.server.retirement!!.generation)
        assertEquals(43, retired.server.retirement!!.versionSetHash.length)
    }

    @Test
    fun `a candidate metadata body decodes as the standalone schema too`() {
        val metadata = json.decodeFromString(
            PerVaultServerCandidateDto.serializer(),
            """
            {
              "candidateId": "018f0000-0000-7000-8000-0000000000c1",
              "transitionId": "018f0000-0000-7000-8000-0000000000t1",
              "docId": "018f0000-0000-7000-8000-0000000000a1",
              "docKind": "portfolio",
              "docVersion": 3,
              "formatVersion": 2,
              "writeId": "018f0000-0000-7000-8000-0000000000w1",
              "sizeBytes": 512,
              "expiresAt": "2026-08-24T09:00:00.000Z"
            }
            """.trimIndent(),
        )
        assertEquals("portfolio", metadata.docKind)
        assertEquals(512L, metadata.sizeBytes)
    }

    // ── The media transition request, branch by branch ──────────────────────

    private fun transition(verification: PerVaultMediaVerificationDto) = PerVaultMediaTransitionRequest(
        transitionId = "018f0000-0000-7000-8000-0000000000t1",
        expected = PerVaultMediaExpectedDto.of(
            media = listOf("server"),
            driveConnectionId = null,
            mediaAttestedAt = null,
        ),
        next = PerVaultMediaNextDto.of(media = listOf("server", "drive"), driveConnectionId = null),
        verification = verification,
    )

    @Test
    fun `each verification branch serializes to exactly its own members`() {
        val candidates = json.encodeToString(
            PerVaultMediaVerificationDto.serializer(),
            PerVaultMediaVerificationDto.serverCandidates(
                listOf(
                    PerVaultCandidateReadbackDto(
                        candidateId = "018f0000-0000-7000-8000-0000000000c1",
                        docId = "018f0000-0000-7000-8000-0000000000a1",
                        readback = "0123456789abcdef0123456789abcdef",
                    ),
                ),
            ),
        )
        assertEquals(setOf("kind", "readbacks"), keysOf(candidates))

        val drive = json.encodeToString(
            PerVaultMediaVerificationDto.serializer(),
            PerVaultMediaVerificationDto.drive(
                driveConnectionId = "018f0000-0000-7000-8000-0000000000d1",
                docs = emptyList(),
            ),
        )
        assertEquals(setOf("kind", "driveConnectionId", "docs"), keysOf(drive))

        val server = json.encodeToString(
            PerVaultMediaVerificationDto.serializer(),
            PerVaultMediaVerificationDto.server(emptyList()),
        )
        assertEquals(setOf("kind", "docs"), keysOf(server))
    }

    @Test
    fun `a mixed verification branch is refused before it is sent`() {
        val mixed = PerVaultMediaVerificationDto(
            kind = PerVaultMediaVerificationDto.KIND_DRIVE,
            driveConnectionId = "018f0000-0000-7000-8000-0000000000d1",
            docs = emptyList(),
            readbacks = emptyList(),
        )
        assertNotNull(mixed.problem())
        assertNull(PerVaultMediaVerificationDto.server(emptyList()).problem())
        assertNotNull(PerVaultMediaVerificationDto(kind = "something-else").problem())
    }

    /**
     * The trap, proven in both directions.
     *
     * A plain `String?` holding null is DROPPED by `explicitNulls = false`. The
     * contract lists `expected.driveConnectionId`, `expected.mediaAttestedAt` and
     * `next.driveConnectionId` in `required`, and a zod `.nullable()` member with
     * the key absent fails the parse — so those three had to stop being
     * `String?`. This test fails the day someone "simplifies" them back.
     */
    @Test
    fun `required-but-nullable transition members travel as explicit nulls`() {
        val encoded = json.encodeToString(
            PerVaultMediaTransitionRequest.serializer(),
            transition(PerVaultMediaVerificationDto.server(emptyList())),
        )
        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("transitionId", "expected", "next", "verification"), root.keys)

        val expected = root["expected"]!!.jsonObject
        assertEquals(setOf("media", "driveConnectionId", "mediaAttestedAt"), expected.keys)
        assertEquals("null", expected["driveConnectionId"].toString())
        assertEquals("null", expected["mediaAttestedAt"].toString())

        val next = root["next"]!!.jsonObject
        assertEquals(setOf("media", "driveConnectionId"), next.keys)
        assertEquals("null", next["driveConnectionId"].toString())

        // …and the reason those three are not `String?`: a `String?` null vanishes.
        val patch = json.encodeToString(PatchVaultRequest.serializer(), PatchVaultRequest(null))
        assertEquals(emptySet<String>(), keysOf(patch))
    }

    @Test
    fun `a bound drive transition still transmits the id it is binding`() {
        val encoded = json.encodeToString(
            PerVaultMediaNextDto.serializer(),
            PerVaultMediaNextDto.of(
                media = listOf("drive"),
                driveConnectionId = "018f0000-0000-7000-8000-0000000000d1",
            ),
        )
        val next = json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            "\"018f0000-0000-7000-8000-0000000000d1\"",
            next["driveConnectionId"].toString(),
        )
    }

    // ── Retirement purge ────────────────────────────────────────────────────

    @Test
    fun `the purge challenge and purge bodies match the contract`() {
        val challenge = json.encodeToString(
            PerVaultRetiredServerPurgeChallengeRequest.serializer(),
            PerVaultRetiredServerPurgeChallengeRequest(
                vaultId = "018f0000-0000-7000-8000-000000000001",
                generation = 1,
                versionSetHash = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE",
            ),
        )
        assertEquals(setOf("vaultId", "generation", "versionSetHash"), keysOf(challenge))

        val purge = json.encodeToString(
            PerVaultRetiredServerPurgeRequest.serializer(),
            PerVaultRetiredServerPurgeRequest(
                vaultId = "018f0000-0000-7000-8000-000000000001",
                generation = 1,
                versionSetHash = "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE",
                observedDocs = listOf(
                    PerVaultDocRefDto(
                        docId = "018f0000-0000-7000-8000-0000000000a1",
                        docVersion = 3,
                        writeId = "018f0000-0000-7000-8000-0000000000w1",
                    ),
                ),
                challenge = "0123456789abcdef0123456789abcdef",
                signature = "c2lnbmF0dXJlLWJ5dGVzLWJhc2U2NHVybC1lbmNvZGVkLWhlcmU",
            ),
        )
        assertEquals(
            setOf(
                "vaultId",
                "generation",
                "versionSetHash",
                "observedDocs",
                "challenge",
                "signature",
            ),
            keysOf(purge),
        )

        val decoded = json.decodeFromString(
            PerVaultRetiredServerPurgeResponse.serializer(),
            """
            {
              "purged": true,
              "vaultId": "018f0000-0000-7000-8000-000000000001",
              "generation": 1,
              "versionSetHash": "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE"
            }
            """.trimIndent(),
        )
        assertTrue(decoded.purged)

        val issued = json.decodeFromString(
            PerVaultRetiredServerPurgeChallengeResponse.serializer(),
            """
            {
              "vaultId": "018f0000-0000-7000-8000-000000000001",
              "generation": 1,
              "versionSetHash": "abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE",
              "challenge": "0123456789abcdef0123456789abcdef",
              "expiresAt": "2026-08-23T09:05:00.000Z"
            }
            """.trimIndent(),
        )
        assertEquals("0123456789abcdef0123456789abcdef", issued.challenge)
    }
}
