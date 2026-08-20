package at.bettertrack.app.vault.pv.envelope

import at.bettertrack.app.vault.VaultEntity
import at.bettertrack.app.vault.VaultMergeRecord
import at.bettertrack.app.vault.VaultMirrorProvenance
import at.bettertrack.app.vault.pv.docs.PvDocBuckets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **The doc set of a vault** (`paranoid-design.md` §5, E0 `vaultHeaderDocSchema`
 * / `vaultCommonDocSchema` / `vaultPortfolioDocSchema`).
 *
 * Three payload shapes, one per `docKind`, each carried inside its own envelope:
 *
 * - **`header`** — vault metadata under encryption: the TRUE vault name (the
 *   server-visible one is config, §21 Q4), the member-portfolio roster, the
 *   keySlots echo and the Drive-connection identity echo (a device with only the
 *   words and any one medium can rebuild everything — §8 autonomy), and the
 *   creation record. Small, rewritten rarely.
 * - **`common`** — account-scoped material the member portfolios reference: the
 *   custom-asset bucket, severed-fork mirrorchain provenance, the merge log and
 *   the retirement-proof Ed25519 keypair whose PRIVATE half makes the §7 purge
 *   gate prove possession of the vault rather than of a session.
 * - **`portfolio`** — one per member portfolio: every portfolio-bucketed row of
 *   that portfolio.
 *
 * Member order below IS the wire order, for the same reason as everywhere else
 * in this rail: the plaintext is `JSON.stringify(schema.parse(doc))`, and the
 * envelope's AAD/GCM tag make "some other valid serialization" a decryption
 * failure for the next reader.
 *
 * Payload rules carried verbatim from v1 §2/§4 and unchanged here: uuidv7 entity
 * ids, per-entity monotonic `rev` + `editedAt` + writing `deviceId`, tombstones
 * kept ≥ [PvVaultContract.TOMBSTONE_RETENTION_DAYS] days, pure v(n)→v(n+1)
 * migrations on load, NEWER-version docs read-only with an "update the app"
 * notice — never best-effort parsed.
 */
sealed interface PvVaultDoc {
    val schemaVersion: Int

    /** The `docKind` this payload belongs under. */
    val docKind: String

    fun toJson(): JsonObject
}

private fun encodeEntities(entities: Map<String, List<VaultEntity>>): JsonObject =
    JsonObject(
        entities.entries.associateTo(LinkedHashMap()) { (kind, rows) ->
            kind to JsonArray(rows.map { it.toJson() })
        },
    )

/**
 * `z.record(vaultEntityKindSubset(kinds), …)` — a kind from the OTHER bucket is
 * a hard parse failure, not a quiet re-route. A portfolio doc that accepted
 * `customAsset` rows would fork the account-scoped bucket per portfolio, and the
 * copies would diverge with no merge rule to reconcile them.
 */
private fun decodeEntities(
    element: JsonElement?,
    allowed: Set<String>,
    bucket: String,
): Map<String, List<VaultEntity>> {
    val obj = element as? JsonObject
        ?: pvDocumentInvalid("A vault doc's 'entities' must be an object.")
    val out = LinkedHashMap<String, List<VaultEntity>>()
    for ((kind, rows) in obj) {
        if (kind !in allowed) {
            pvDocumentInvalid("Entity kind '$kind' does not belong to the $bucket doc bucket.")
        }
        val array = rows as? JsonArray
            ?: pvDocumentInvalid("Vault entity kind '$kind' must hold an array.")
        out[kind] = array.map { VaultEntity.parse(it) }
    }
    return out
}

/** `.default([])`: an absent merge log IS an empty merge log. */
private fun decodeMergeLog(element: JsonElement?): List<VaultMergeRecord> = when (element) {
    null, is JsonNull -> emptyList()
    else -> (element as? JsonArray ?: pvDocumentInvalid("A vault doc's 'mergeLog' must be an array."))
        .map { VaultMergeRecord.parse(it) }
}

private fun JsonObject.pvDocString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: pvDocumentInvalid("Vault doc member '$key' must be a string.")

private fun JsonObject.pvDocUuid(key: String): String =
    pvDocString(key).also { if (!pvIsUuid(it)) pvDocumentInvalid("Vault doc member '$key' must be a uuid.") }

private fun JsonObject.pvDocSchemaVersion(): Int {
    val value = pvJsIntOrNull(this["schemaVersion"])
        ?: pvDocumentInvalid("Vault doc 'schemaVersion' must be an integer.")
    if (value != PvVaultContract.DOC_SCHEMA_VERSION) {
        pvDocumentInvalid("Vault doc has an unsupported schemaVersion.")
    }
    return value
}

// ---------------------------------------------------------------------------
// header doc
// ---------------------------------------------------------------------------

/** One roster entry of the encrypted member-portfolio list. */
data class PvHeaderPortfolio(val id: String, val name: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name)),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("id", "name")

        fun parse(element: JsonElement): PvHeaderPortfolio {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A vault header roster entry must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault header roster entry")
            val name = obj.pvDocString("name")
            if (name.isEmpty()) pvDocumentInvalid("A roster entry's 'name' must not be empty.")
            return PvHeaderPortfolio(obj.pvDocUuid("id"), name)
        }
    }
}

/**
 * §8: the bound Drive connection's identity, echoed UNDER ENCRYPTION so the
 * server registry stays a convenience and never a discovery prerequisite.
 */
data class PvDriveConnectionEcho(val googleSub: String, val email: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf("googleSub" to JsonPrimitive(googleSub), "email" to JsonPrimitive(email)),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("googleSub", "email")

        fun parse(element: JsonElement): PvDriveConnectionEcho {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A vault header driveConnection must be an object or null.")
            pvRequireExactFields(obj, FIELDS, "vault header driveConnection")
            val googleSub = obj.pvDocString("googleSub").trim()
            if (googleSub.isEmpty() || googleSub.length > 255) {
                pvDocumentInvalid("Vault header 'googleSub' must be 1..255 characters.")
            }
            val email = obj.pvDocString("email").trim()
            if (email.length < 3 || email.length > 320) {
                pvDocumentInvalid("Vault header 'email' must be 3..320 characters.")
            }
            return PvDriveConnectionEcho(googleSub, email)
        }
    }
}

/** The vault's creation record — when, and by which device. */
data class PvVaultCreation(val at: String, val deviceId: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf("at" to JsonPrimitive(at), "deviceId" to JsonPrimitive(deviceId)),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("at", "deviceId")

        fun parse(element: JsonElement): PvVaultCreation {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A vault header 'created' must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault header created")
            return PvVaultCreation(
                at = obj.pvDocString("at").also {
                    if (!pvIsInstant(it)) pvDocumentInvalid("Vault header 'created.at' must be an instant.")
                },
                deviceId = obj.pvDocUuid("deviceId"),
            )
        }
    }
}

/** `vaultHeaderDocSchema`. */
data class PvHeaderDoc(
    val name: String,
    val portfolios: List<PvHeaderPortfolio> = emptyList(),
    val keySlots: List<PvKeySlot>,
    val driveConnection: PvDriveConnectionEcho?,
    val created: PvVaultCreation,
) : PvVaultDoc {
    override val schemaVersion: Int get() = PvVaultContract.DOC_SCHEMA_VERSION
    override val docKind: String get() = PvVaultContract.KIND_HEADER

    override fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "name" to JsonPrimitive(name),
            "portfolios" to JsonArray(portfolios.map { it.toJson() }),
            "keySlots" to JsonArray(keySlots.map { it.toJson() }),
            "driveConnection" to (driveConnection?.toJson() ?: JsonNull),
            "created" to created.toJson(),
        ),
    )

    companion object {
        val FIELDS: Set<String> =
            linkedSetOf("schemaVersion", "name", "portfolios", "keySlots", "driveConnection", "created")

        fun parse(element: JsonElement): PvHeaderDoc {
            val obj = element as? JsonObject ?: pvDocumentInvalid("A vault header doc must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault header doc")
            obj.pvDocSchemaVersion()
            val name = obj.pvDocString("name").trim()
            if (name.isEmpty() || name.length > PvVaultContract.NAME_MAX) {
                pvDocumentInvalid("Vault header doc 'name' must be 1..${PvVaultContract.NAME_MAX} characters.")
            }
            val portfolios = when (val raw = obj["portfolios"]) {
                null, is JsonNull -> emptyList()
                else -> (raw as? JsonArray
                    ?: pvDocumentInvalid("Vault header doc 'portfolios' must be an array."))
                    .map { PvHeaderPortfolio.parse(it) }
            }
            val slotsJson = obj["keySlots"] as? JsonArray
                ?: pvDocumentInvalid("Vault header doc 'keySlots' must be an array.")
            if (slotsJson.isEmpty()) pvDocumentInvalid("Vault header doc 'keySlots' must not be empty.")
            val driveConnection = when (val raw = obj["driveConnection"]) {
                null -> pvDocumentInvalid("Vault header doc is missing 'driveConnection'.")
                is JsonNull -> null
                else -> PvDriveConnectionEcho.parse(raw)
            }
            return PvHeaderDoc(
                name = name,
                portfolios = portfolios,
                keySlots = slotsJson.map { PvKeySlot.parse(it) },
                driveConnection = driveConnection,
                created = PvVaultCreation.parse(
                    obj["created"] ?: pvDocumentInvalid("Vault header doc is missing 'created'."),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// common doc
// ---------------------------------------------------------------------------

/**
 * `vaultClientSecuritySchema` — reused v1 shape,
 * `{ retirementProof: { publicKey, privateKey } }`.
 *
 * The two halves are validated as non-empty base64url text and nothing more,
 * and that stays true even though the public half's schema IS now answered
 * (2026-08-20: the 44-byte DER SPKI Ed25519 shape, enforced at the request
 * boundary by [PvVaultConfig.retirementProofPublicKeyProblem]).
 *
 * The asymmetry is the point. Refusing to SEND a malformed key costs a retry;
 * refusing to OPEN a doc costs the vault, and there is no escrow behind it
 * (§16). So the tight rule lives where a rejection is recoverable, and the doc
 * parse keeps the loose one — which also covers the PRIVATE half, whose
 * encoding the extracted E0 contract still does not pin.
 */
data class PvRetirementProof(val publicKey: String, val privateKey: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "publicKey" to JsonPrimitive(publicKey),
            "privateKey" to JsonPrimitive(privateKey),
        ),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("publicKey", "privateKey")

        fun parse(element: JsonElement): PvRetirementProof {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A retirement proof must be an object.")
            pvRequireExactFields(obj, FIELDS, "retirement proof")
            fun key(field: String): String {
                val value = obj.pvDocString(field)
                if (value.isEmpty() || !PV_BASE64URL_REGEX.matches(value)) {
                    pvDocumentInvalid("Retirement proof '$field' must be non-empty base64url.")
                }
                return value
            }
            return PvRetirementProof(key("publicKey"), key("privateKey"))
        }
    }
}

/** `vaultClientSecuritySchema`'s wrapper object. */
data class PvClientSecurity(val retirementProof: PvRetirementProof) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf("retirementProof" to retirementProof.toJson()))

    companion object {
        val FIELDS: Set<String> = linkedSetOf("retirementProof")

        fun parse(element: JsonElement): PvClientSecurity {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A vault doc's 'clientSecurity' must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault clientSecurity")
            return PvClientSecurity(
                PvRetirementProof.parse(
                    obj["retirementProof"] ?: pvDocumentInvalid("clientSecurity is missing 'retirementProof'."),
                ),
            )
        }
    }
}

/** `vaultCommonDocSchema`. */
data class PvCommonDoc(
    val entities: Map<String, List<VaultEntity>>,
    val mergeLog: List<VaultMergeRecord> = emptyList(),
    val mirrorProvenance: List<VaultMirrorProvenance> = emptyList(),
    val clientSecurity: PvClientSecurity,
) : PvVaultDoc {
    override val schemaVersion: Int get() = PvVaultContract.DOC_SCHEMA_VERSION
    override val docKind: String get() = PvVaultContract.KIND_COMMON

    override fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "entities" to encodeEntities(entities),
            "mergeLog" to JsonArray(mergeLog.map { it.toJson() }),
            "mirrorProvenance" to JsonArray(mirrorProvenance.map { it.toJson() }),
            "clientSecurity" to clientSecurity.toJson(),
        ),
    )

    companion object {
        val FIELDS: Set<String> =
            linkedSetOf("schemaVersion", "entities", "mergeLog", "mirrorProvenance", "clientSecurity")

        fun parse(element: JsonElement): PvCommonDoc {
            val obj = element as? JsonObject ?: pvDocumentInvalid("A vault common doc must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault common doc")
            obj.pvDocSchemaVersion()
            val mirrorProvenance = when (val raw = obj["mirrorProvenance"]) {
                null, is JsonNull -> emptyList()
                else -> (raw as? JsonArray
                    ?: pvDocumentInvalid("Vault common doc 'mirrorProvenance' must be an array."))
                    .map { VaultMirrorProvenance.parse(it) }
            }
            return PvCommonDoc(
                entities = decodeEntities(obj["entities"], PvDocBuckets.COMMON_DOC_KINDS, "common"),
                mergeLog = decodeMergeLog(obj["mergeLog"]),
                mirrorProvenance = mirrorProvenance,
                clientSecurity = PvClientSecurity.parse(
                    obj["clientSecurity"] ?: pvDocumentInvalid("A vault common doc requires clientSecurity."),
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// portfolio doc
// ---------------------------------------------------------------------------

/** `vaultPortfolioDocSchema`. */
data class PvPortfolioDoc(
    val portfolioId: String,
    val entities: Map<String, List<VaultEntity>>,
    val mergeLog: List<VaultMergeRecord> = emptyList(),
) : PvVaultDoc {
    override val schemaVersion: Int get() = PvVaultContract.DOC_SCHEMA_VERSION
    override val docKind: String get() = PvVaultContract.KIND_PORTFOLIO

    override fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "portfolioId" to JsonPrimitive(portfolioId),
            "entities" to encodeEntities(entities),
            "mergeLog" to JsonArray(mergeLog.map { it.toJson() }),
        ),
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("schemaVersion", "portfolioId", "entities", "mergeLog")

        fun parse(element: JsonElement): PvPortfolioDoc {
            val obj = element as? JsonObject
                ?: pvDocumentInvalid("A vault portfolio doc must be an object.")
            pvRequireExactFields(obj, FIELDS, "vault portfolio doc")
            obj.pvDocSchemaVersion()
            return PvPortfolioDoc(
                portfolioId = obj.pvDocUuid("portfolioId"),
                entities = decodeEntities(obj["entities"], PvDocBuckets.PORTFOLIO_DOC_KINDS, "portfolio"),
                mergeLog = decodeMergeLog(obj["mergeLog"]),
            )
        }
    }
}

/** Parse a decrypted payload under the `docKind` its envelope authenticated. */
internal fun parsePvVaultDoc(docKind: String, element: JsonElement): PvVaultDoc = when (docKind) {
    PvVaultContract.KIND_HEADER -> PvHeaderDoc.parse(element)
    PvVaultContract.KIND_COMMON -> PvCommonDoc.parse(element)
    PvVaultContract.KIND_PORTFOLIO -> PvPortfolioDoc.parse(element)
    else -> pvDocumentInvalid("A vault doc has an unknown docKind '$docKind'.")
}
