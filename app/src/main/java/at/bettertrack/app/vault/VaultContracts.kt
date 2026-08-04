package at.bettertrack.app.vault

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The vault wire contract — port of the vault half of
 * `packages/contracts/src/vault.ts` (vendored at
 * `tools/domain-vectors/vendor/web-vault/contracts-vault.ts`).
 *
 * ## Why this is hand-written validation and not `@Serializable` data classes
 *
 * The reference validates with **zod**, and zod does two things a Kotlin
 * `@Serializable` class does not, both of which are load-bearing for byte
 * identity:
 *
 * 1. **It reorders.** `JSON.stringify(schema.parse(x))` emits members in the
 *    *schema declaration* order, not the input order. That is precisely why the
 *    fixture's canonical header starts `formatVersion, cipher, iv, keyId, …`
 *    while the same header's *object literal* in the fixture starts `keyId`.
 *    Every `toJson()` below therefore builds its object in schema order by hand.
 * 2. **It distinguishes absent from empty.** `mergeLog` is `.default([])` (absent
 *    becomes `[]`), but `mirrorProvenance` is `.optional()` with no default, and
 *    the contract comments spell out why: defaulting it in "would make
 *    re-encrypting it emit `"mirrorProvenance":[]` — changing the plaintext, and
 *    therefore the published envelope bytes, of every fork-free vault in
 *    existence". A nullable Kotlin field models that faithfully; a
 *    `= emptyList()` default would not.
 *
 * Entity payloads stay **opaque** [JsonObject]s. The contract says the per-kind
 * shapes are pinned elsewhere and "server code never sees this decrypted", so
 * modelling them as typed classes here would only add a way to lose fields.
 */
object VaultContract {
    /** `VAULT_MAGIC` (vault.ts:51). */
    const val MAGIC: String = "BTVAULT1"

    /** `VAULT_FORMAT_VERSION` (vault.ts:55) — the envelope framing version. */
    const val FORMAT_VERSION: Int = 1

    /** `VAULT_DOCUMENT_V1_VERSION` (vault.ts:57) — the version this client writes. */
    const val DOCUMENT_V1_VERSION: Int = 1

    /** `VAULT_DOCUMENT_VERSION` (vault.ts:59) — the newest version it can read. */
    const val DOCUMENT_VERSION: Int = 2

    /** `VAULT_CONTENT_CIPHER` (vault.ts:61). */
    const val CONTENT_CIPHER: String = "A256GCM"

    /** `VAULT_KDF_ALG` (vault.ts:63). */
    const val KDF_ALG: String = "argon2id"

    /** `VAULT_VERSION_MAX` (vault.ts:70) — `vaultVersionSchema` is `[1, this]`. */
    const val VERSION_MAX: Int = 2_147_483_647

    /**
     * `VAULT_ENTITY_KINDS` (vault.ts:566-593), in declaration order.
     *
     * `entities` is `z.record(vaultEntityKindSchema, …)`, so a kind outside this
     * set makes the whole document invalid — the app fails closed exactly where
     * the web client does rather than quietly dropping rows it does not know.
     */
    val ENTITY_KINDS: Set<String> = linkedSetOf(
        "portfolio",
        "transaction",
        "dividend",
        "cashSource",
        "cashMovement",
        "portfolioSetting",
        "taxSetting",
        "customAsset",
        "customAssetValue",
        "standingOrder",
        "standingOrderRun",
        "importBatch",
        "importRow",
        "portfolioDailySnapshot",
        "portfolioSnapshotState",
        "expenseCategory",
        "expenseTransaction",
        "expenseRule",
        "expenseBudget",
        "expenseBudgetFire",
        "cashTag",
        "cashMovementTag",
        "cashBudget",
        "cashBudgetFire",
        "cashRule",
        "cashRuleTag",
    )

    /** `mirrorRowKindSchema` members, for `vaultMirrorProvenanceSchema.kind`. */
    val MIRROR_ROW_KINDS: Set<String> =
        linkedSetOf("transaction", "dividend", "cash_movement", "cash_source")
}

// ---------------------------------------------------------------------------
// zod primitive validators, reproduced exactly
// ---------------------------------------------------------------------------
//
// These are transcribed from zod 3.24.1 (`packages/contracts` depends on
// ^3.24.1), not approximated. Being *stricter* than the reference would reject
// vaults the web client happily writes; being looser would accept vaults it
// rejects. Both are cross-client bugs, so the regexes are copied character for
// character from `zod/lib/types.js`.

/** zod `z.string().uuid()` — `uuidRegex`, types.js:394. Any version nibble. */
private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * zod `z.string().datetime()` — `datetimeRegex({})`, built from `dateRegexSource`
 * (*with* leap-year validation) and `timeRegexSource({})`. No `offset`, no
 * `local`: a trailing `Z` is mandatory.
 *
 * **Seconds are OPTIONAL**, and getting that right required checking which zod
 * the platform actually runs rather than which one its manifest names.
 * `packages/contracts` declares `zod: ^3.24.1`, but that range resolves to
 * 3.25.x, and the two disagree exactly here: 3.24.1's `timeRegexSource` hard-codes
 * `HH:MM:SS`, whereas 3.25.x wraps the seconds group in an optional quantifier
 * (`([01]\d|2[0-3]):[0-5]\d(:[0-5]\d(\.\d+)?)?`). Transcribing 3.24.1 made this
 * client reject `2026-07-25T10:00Z` — an instant the platform accepts, and one
 * its own merge suite exercises on purpose (`merge.test.ts`: "later instant
 * against a seconds-omitted instant", "sorts seconds-omitted history").
 * `merge.ts`'s own `INSTANT_PATTERN` independently makes seconds optional, which
 * corroborates that the looser form is the intended contract.
 *
 * Verified empirically against zod 3.25.76: seconds-omitted accepted, bare-hour
 * rejected, `2026-02-29` rejected, `2024-02-29` accepted, offsets rejected.
 */
private val DATETIME_REGEX = Regex(
    "^((\\d\\d[2468][048]|\\d\\d[13579][26]|\\d\\d0[48]|[02468][048]00|[13579][26]00)-02-29" +
        "|\\d{4}-((0[13578]|1[02])-(0[1-9]|[12]\\d|3[01])" +
        "|(0[469]|11)-(0[1-9]|[12]\\d|30)" +
        "|(02)-(0[1-9]|1\\d|2[0-8])))" +
        "T([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d(\\.\\d+)?)?(Z)$"
)

private fun invalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, message)

private fun documentInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.DOCUMENT_INVALID, message)

private fun JsonElement.asObjectOr(message: String): JsonObject =
    this as? JsonObject ?: invalid(message)

private fun JsonElement.asArrayOr(message: String): JsonArray =
    this as? JsonArray ?: invalid(message)

private fun JsonElement.asStringOr(message: String): String {
    val primitive = this as? JsonPrimitive ?: invalid(message)
    if (!primitive.isString) invalid(message)
    return primitive.content
}

/**
 * `z.number().int()` over a JSON value.
 *
 * JS has one number type, so `z.number().int()` means "a double that
 * `Number.isInteger` accepts". `1.0` therefore passes and `1.5` does not, and a
 * value beyond 2^53 is not safely an integer. Parsing through `Double` and then
 * checking reproduces that; parsing straight to `Int` would silently accept
 * `1e30` as garbage or reject the perfectly legal literal `1.0`.
 */
private fun JsonElement.asIntOr(message: String): Int {
    val primitive = this as? JsonPrimitive ?: invalid(message)
    if (primitive.isString) invalid(message)
    val value = primitive.content.toDoubleOrNull() ?: invalid(message)
    if (!value.isFinite() || value != Math.floor(value)) invalid(message)
    if (value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()) invalid(message)
    return value.toInt()
}

private fun requireUuid(value: String, field: String): String {
    if (!UUID_REGEX.matches(value)) invalid("Vault $field must be a uuid.")
    return value
}

private fun requireDatetime(value: String, field: String): String {
    if (!DATETIME_REGEX.matches(value)) invalid("Vault $field must be an ISO-8601 instant.")
    return value
}

/** `vaultVersionSchema` (vault.ts:83) — `z.number().int().min(1).max(VERSION_MAX)`. */
private fun requireVaultVersion(value: Int, field: String): Int {
    if (value < 1 || value > VaultContract.VERSION_MAX) {
        invalid("Vault $field must be a positive safe integer.")
    }
    return value
}

/** Rejects any member outside [allowed] — the zod `.strict()` behaviour. */
private fun requireExactFields(obj: JsonObject, allowed: Set<String>, what: String) {
    for (key in obj.keys) {
        if (key !in allowed) invalid("Vault $what has an unexpected field '$key'.")
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/** `vaultKdfParamsSchema` (vault.ts:505-511). Field order is schema order. */
data class VaultKdfParams(
    val alg: String,
    val m: Int,
    val t: Int,
    val p: Int,
    val salt: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "alg" to JsonPrimitive(alg),
            "m" to JsonPrimitive(m),
            "t" to JsonPrimitive(t),
            "p" to JsonPrimitive(p),
            "salt" to JsonPrimitive(salt),
        )
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("alg", "m", "t", "p", "salt")

        fun parse(element: JsonElement): VaultKdfParams {
            val obj = element.asObjectOr("Vault kdf must be an object.")
            val alg = (obj["alg"] ?: invalid("Vault kdf is missing 'alg'."))
                .asStringOr("Vault kdf 'alg' must be a string.")
            if (alg != VaultContract.KDF_ALG) invalid("Vault kdf 'alg' must be argon2id.")
            val m = (obj["m"] ?: invalid("Vault kdf is missing 'm'.")).asIntOr("Vault kdf 'm' must be an integer.")
            val t = (obj["t"] ?: invalid("Vault kdf is missing 't'.")).asIntOr("Vault kdf 't' must be an integer.")
            val p = (obj["p"] ?: invalid("Vault kdf is missing 'p'.")).asIntOr("Vault kdf 'p' must be an integer.")
            if (m <= 0 || t <= 0 || p <= 0) invalid("Vault kdf parameters must be positive.")
            val salt = (obj["salt"] ?: invalid("Vault kdf is missing 'salt'."))
                .asStringOr("Vault kdf 'salt' must be a string.")
            if (salt.isEmpty()) invalid("Vault kdf 'salt' must not be empty.")
            return VaultKdfParams(alg, m, t, p, salt)
        }
    }
}

/** `vaultWrappedKeySchema` (vault.ts:514-519). */
data class VaultWrappedKey(
    val keyId: String,
    val kdf: VaultKdfParams,
    val wrappedVk: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "keyId" to JsonPrimitive(keyId),
            "kdf" to kdf.toJson(),
            "wrappedVk" to JsonPrimitive(wrappedVk),
        )
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("keyId", "kdf", "wrappedVk")

        fun parse(element: JsonElement): VaultWrappedKey {
            val obj = element.asObjectOr("Vault wrapped key must be an object.")
            val keyId = requireUuid(
                (obj["keyId"] ?: invalid("Vault wrapped key is missing 'keyId'."))
                    .asStringOr("Vault wrapped key 'keyId' must be a string."),
                "wrapped key 'keyId'",
            )
            val kdf = VaultKdfParams.parse(obj["kdf"] ?: invalid("Vault wrapped key is missing 'kdf'."))
            val wrappedVk = (obj["wrappedVk"] ?: invalid("Vault wrapped key is missing 'wrappedVk'."))
                .asStringOr("Vault wrapped key 'wrappedVk' must be a string.")
            if (wrappedVk.isEmpty()) invalid("Vault wrapped key 'wrappedVk' must not be empty.")
            return VaultWrappedKey(keyId, kdf, wrappedVk)
        }
    }
}

/**
 * `vaultEnvelopeHeaderSchema` (vault.ts:531-542) — the full cleartext header.
 *
 * It carries **only** counters, ids and crypto parameters, never portfolio
 * information, and the whole thing is bound as AES-GCM additional authenticated
 * data, so editing `vaultVersion` or swapping a wrapped key breaks decryption.
 *
 * The declaration order below IS the canonical serialization order.
 */
data class VaultEnvelopeHeader(
    val formatVersion: Int,
    val cipher: String,
    val iv: String,
    val keyId: String,
    val wrappedKeys: List<VaultWrappedKey>,
    val vaultVersion: Int,
    val schemaVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "formatVersion" to JsonPrimitive(formatVersion),
            "cipher" to JsonPrimitive(cipher),
            "iv" to JsonPrimitive(iv),
            "keyId" to JsonPrimitive(keyId),
            "wrappedKeys" to JsonArray(wrappedKeys.map { it.toJson() }),
            "vaultVersion" to JsonPrimitive(vaultVersion),
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "deviceId" to JsonPrimitive(deviceId),
            "writeId" to JsonPrimitive(writeId),
            "writtenAt" to JsonPrimitive(writtenAt),
        )
    )

    companion object {
        /** `HEADER_FIELDS` (envelope.ts:15-26) — the `exactHeaderShape` allow-list. */
        val FIELDS: Set<String> = linkedSetOf(
            "formatVersion",
            "cipher",
            "iv",
            "keyId",
            "wrappedKeys",
            "vaultVersion",
            "schemaVersion",
            "deviceId",
            "writeId",
            "writtenAt",
        )

        fun parse(element: JsonElement): VaultEnvelopeHeader {
            val obj = element.asObjectOr("Vault header must be an object.")
            val formatVersion =
                (obj["formatVersion"] ?: invalid("Vault header is missing 'formatVersion'."))
                    .asIntOr("Vault header 'formatVersion' must be an integer.")
            if (formatVersion != VaultContract.FORMAT_VERSION) {
                invalid("Vault header 'formatVersion' must be ${VaultContract.FORMAT_VERSION}.")
            }
            val cipher = (obj["cipher"] ?: invalid("Vault header is missing 'cipher'."))
                .asStringOr("Vault header 'cipher' must be a string.")
            if (cipher != VaultContract.CONTENT_CIPHER) {
                invalid("Vault header 'cipher' must be ${VaultContract.CONTENT_CIPHER}.")
            }
            val iv = (obj["iv"] ?: invalid("Vault header is missing 'iv'."))
                .asStringOr("Vault header 'iv' must be a string.")
            if (iv.isEmpty()) invalid("Vault header 'iv' must not be empty.")
            val keyId = requireUuid(
                (obj["keyId"] ?: invalid("Vault header is missing 'keyId'."))
                    .asStringOr("Vault header 'keyId' must be a string."),
                "header 'keyId'",
            )
            val wrappedKeysJson = (obj["wrappedKeys"] ?: invalid("Vault header is missing 'wrappedKeys'."))
                .asArrayOr("Vault header 'wrappedKeys' must be an array.")
            if (wrappedKeysJson.isEmpty()) invalid("Vault header 'wrappedKeys' must not be empty.")
            val wrappedKeys = wrappedKeysJson.map { VaultWrappedKey.parse(it) }
            val vaultVersion = requireVaultVersion(
                (obj["vaultVersion"] ?: invalid("Vault header is missing 'vaultVersion'."))
                    .asIntOr("Vault header 'vaultVersion' must be an integer."),
                "header 'vaultVersion'",
            )
            val schemaVersion =
                (obj["schemaVersion"] ?: invalid("Vault header is missing 'schemaVersion'."))
                    .asIntOr("Vault header 'schemaVersion' must be an integer.")
            if (schemaVersion < 1) invalid("Vault header 'schemaVersion' must be positive.")
            val deviceId = requireUuid(
                (obj["deviceId"] ?: invalid("Vault header is missing 'deviceId'."))
                    .asStringOr("Vault header 'deviceId' must be a string."),
                "header 'deviceId'",
            )
            val writeId = requireUuid(
                (obj["writeId"] ?: invalid("Vault header is missing 'writeId'."))
                    .asStringOr("Vault header 'writeId' must be a string."),
                "header 'writeId'",
            )
            val writtenAt = requireDatetime(
                (obj["writtenAt"] ?: invalid("Vault header is missing 'writtenAt'."))
                    .asStringOr("Vault header 'writtenAt' must be a string."),
                "header 'writtenAt'",
            )
            return VaultEnvelopeHeader(
                formatVersion = formatVersion,
                cipher = cipher,
                iv = iv,
                keyId = keyId,
                wrappedKeys = wrappedKeys,
                vaultVersion = vaultVersion,
                schemaVersion = schemaVersion,
                deviceId = deviceId,
                writeId = writeId,
                writtenAt = writtenAt,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Document
// ---------------------------------------------------------------------------

/** `vaultMergeRecordSchema` (vault.ts:1207-1212). */
data class VaultMergeRecord(
    val mergedAt: String,
    val parents: List<Int>,
    val into: Int,
    val deviceId: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "mergedAt" to JsonPrimitive(mergedAt),
            "parents" to JsonArray(parents.map { JsonPrimitive(it) }),
            "into" to JsonPrimitive(into),
            "deviceId" to JsonPrimitive(deviceId),
        )
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("mergedAt", "parents", "into", "deviceId")

        fun parse(element: JsonElement): VaultMergeRecord {
            val obj = element.asObjectOr("Vault merge record must be an object.")
            val mergedAt = requireDatetime(
                (obj["mergedAt"] ?: documentInvalid("Vault merge record is missing 'mergedAt'."))
                    .asStringOr("Vault merge record 'mergedAt' must be a string."),
                "merge record 'mergedAt'",
            )
            val parentsJson = (obj["parents"] ?: documentInvalid("Vault merge record is missing 'parents'."))
                .asArrayOr("Vault merge record 'parents' must be an array.")
            if (parentsJson.isEmpty()) invalid("Vault merge record 'parents' must not be empty.")
            val parents = parentsJson.map {
                requireVaultVersion(it.asIntOr("Vault merge record parent must be an integer."), "merge record parent")
            }
            val into = requireVaultVersion(
                (obj["into"] ?: documentInvalid("Vault merge record is missing 'into'."))
                    .asIntOr("Vault merge record 'into' must be an integer."),
                "merge record 'into'",
            )
            val deviceId = requireUuid(
                (obj["deviceId"] ?: documentInvalid("Vault merge record is missing 'deviceId'."))
                    .asStringOr("Vault merge record 'deviceId' must be a string."),
                "merge record 'deviceId'",
            )
            return VaultMergeRecord(mergedAt, parents, into, deviceId)
        }
    }
}

/**
 * `vaultEntitySchema` (vault.ts:611) = `vaultEntityMetaSchema` (vault.ts:604-610)
 * extended with an opaque `data` record.
 *
 * These five metadata fields are exactly what the §4 merge rules key off:
 * a monotonic [rev], the [editedAt] instant, the writing [editedBy] device, and
 * a [deletedAt] tombstone (retained ≥ 180 days) so long-offline merges stay
 * correct. [data] is intentionally untyped — see the class doc on
 * [VaultContract].
 */
data class VaultEntity(
    val id: String,
    val rev: Int,
    val editedAt: String,
    val editedBy: String,
    val deletedAt: String?,
    val data: JsonObject,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(id),
            "rev" to JsonPrimitive(rev),
            "editedAt" to JsonPrimitive(editedAt),
            "editedBy" to JsonPrimitive(editedBy),
            "deletedAt" to (deletedAt?.let { JsonPrimitive(it) } ?: JsonNull),
            "data" to data,
        )
    )

    companion object {
        val FIELDS: Set<String> = linkedSetOf("id", "rev", "editedAt", "editedBy", "deletedAt", "data")

        fun parse(element: JsonElement): VaultEntity {
            val obj = element as? JsonObject
                ?: documentInvalid("Vault entity does not match the current schema.")
            val id = (obj["id"] ?: documentInvalid("Vault entity is missing 'id'."))
                .asStringOr("Vault entity 'id' must be a string.")
            if (!UUID_REGEX.matches(id)) documentInvalid("Vault entity 'id' must be a uuid.")
            val rev = (obj["rev"] ?: documentInvalid("Vault entity is missing 'rev'."))
                .asIntOr("Vault entity 'rev' must be an integer.")
            if (rev < 0) documentInvalid("Vault entity 'rev' must be non-negative.")
            val editedAt = (obj["editedAt"] ?: documentInvalid("Vault entity is missing 'editedAt'."))
                .asStringOr("Vault entity 'editedAt' must be a string.")
            if (!DATETIME_REGEX.matches(editedAt)) {
                documentInvalid("Vault entity 'editedAt' must be an ISO-8601 instant.")
            }
            val editedBy = (obj["editedBy"] ?: documentInvalid("Vault entity is missing 'editedBy'."))
                .asStringOr("Vault entity 'editedBy' must be a string.")
            if (!UUID_REGEX.matches(editedBy)) documentInvalid("Vault entity 'editedBy' must be a uuid.")
            val deletedAtJson = obj["deletedAt"] ?: documentInvalid("Vault entity is missing 'deletedAt'.")
            val deletedAt = if (deletedAtJson is JsonNull) {
                null
            } else {
                val text = deletedAtJson.asStringOr("Vault entity 'deletedAt' must be a string or null.")
                if (!DATETIME_REGEX.matches(text)) {
                    documentInvalid("Vault entity 'deletedAt' must be an ISO-8601 instant.")
                }
                text
            }
            val data = obj["data"] as? JsonObject
                ?: documentInvalid("Vault entity 'data' must be an object.")
            return VaultEntity(id, rev, editedAt, editedBy, deletedAt, data)
        }
    }
}

/**
 * `vaultMirrorProvenanceSchema` (vault.ts:1149-1173) — a `.strict()` six-field
 * identity map for §7.1 severed MIRRORCHAIN forks.
 *
 * The app never *authors* one (a Drive-only lineage has no MIRRORCHAIN), but it
 * must carry them through a read/merge/write cycle without loss, so the fields
 * are validated and re-emitted in schema order.
 */
data class VaultMirrorProvenance(
    val chainId: String,
    val membershipId: String,
    val kind: String,
    val mirrorId: String,
    val portfolioId: String,
    val localId: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "chainId" to JsonPrimitive(chainId),
            "membershipId" to JsonPrimitive(membershipId),
            "kind" to JsonPrimitive(kind),
            "mirrorId" to JsonPrimitive(mirrorId),
            "portfolioId" to JsonPrimitive(portfolioId),
            "localId" to JsonPrimitive(localId),
        )
    )

    companion object {
        val FIELDS: Set<String> =
            linkedSetOf("chainId", "membershipId", "kind", "mirrorId", "portfolioId", "localId")

        fun parse(element: JsonElement): VaultMirrorProvenance {
            val obj = element as? JsonObject
                ?: documentInvalid("Vault mirror provenance must be an object.")
            requireExactFields(obj, FIELDS, "mirror provenance")
            fun uuid(field: String): String {
                val text = (obj[field] ?: documentInvalid("Vault mirror provenance is missing '$field'."))
                    .asStringOr("Vault mirror provenance '$field' must be a string.")
                if (!UUID_REGEX.matches(text)) {
                    documentInvalid("Vault mirror provenance '$field' must be a uuid.")
                }
                return text
            }
            val kind = (obj["kind"] ?: documentInvalid("Vault mirror provenance is missing 'kind'."))
                .asStringOr("Vault mirror provenance 'kind' must be a string.")
            if (kind !in VaultContract.MIRROR_ROW_KINDS) {
                documentInvalid("Vault mirror provenance 'kind' is not a mirror row kind.")
            }
            return VaultMirrorProvenance(
                chainId = uuid("chainId"),
                membershipId = uuid("membershipId"),
                kind = kind,
                mirrorId = uuid("mirrorId"),
                portfolioId = uuid("portfolioId"),
                localId = uuid("localId"),
            )
        }
    }
}

/**
 * The decrypted vault document — `vaultDocumentSchema` (vault.ts:1277-1280), the
 * discriminated union of v1 (vault.ts:1239-1252) and v2 (vault.ts:1261-1273).
 *
 * ## The v1/v2 rule this class encodes (board decision #40.2)
 *
 * **v1 is what this client writes, permanently, for Drive-only lineages.** v2
 * exists to bind `clientSecurity` — "browser-only proof material" whose whole
 * purpose is retiring a *server* medium — which a Drive-only vault has no use
 * for and no way to produce.
 *
 * **But a v2 document must still be readable and must survive a round trip.** A
 * user whose vault the web PWA upgraded to v2 must not have that material
 * destroyed by opening the app. So [clientSecurity] is preserved verbatim as an
 * opaque tree and re-emitted unchanged, [schemaVersion] is carried rather than
 * rewritten, and nothing in this package silently downgrades a v2 document to
 * v1. Implementing v2's semantics is explicitly out of scope here; not losing
 * v2's bytes is not.
 *
 * @property mirrorProvenance `null` means the key was **absent**, which is not
 *   the same as an empty list — see the class doc on [VaultContract].
 */
class VaultDocument(
    val schemaVersion: Int,
    val entities: Map<String, List<VaultEntity>>,
    val mergeLog: List<VaultMergeRecord>,
    val mirrorProvenance: List<VaultMirrorProvenance>?,
    val clientSecurity: JsonObject?,
) {
    init {
        if (schemaVersion == VaultContract.DOCUMENT_VERSION && clientSecurity == null) {
            documentInvalid("A schemaVersion 2 vault document requires clientSecurity.")
        }
        if (schemaVersion == VaultContract.DOCUMENT_V1_VERSION && clientSecurity != null) {
            documentInvalid("A schemaVersion 1 vault document must not carry clientSecurity.")
        }
    }

    /** Serialization order = zod schema declaration order. See the class doc. */
    fun toJson(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(schemaVersion),
            "entities" to JsonObject(
                entities.entries.associateTo(LinkedHashMap()) { (kind, rows) ->
                    kind to JsonArray(rows.map { it.toJson() })
                }
            ),
            "mergeLog" to JsonArray(mergeLog.map { it.toJson() }),
        )
        if (mirrorProvenance != null) {
            members["mirrorProvenance"] = JsonArray(mirrorProvenance.map { it.toJson() })
        }
        if (clientSecurity != null) members["clientSecurity"] = clientSecurity
        return JsonObject(members)
    }

    fun copy(
        schemaVersion: Int = this.schemaVersion,
        entities: Map<String, List<VaultEntity>> = this.entities,
        mergeLog: List<VaultMergeRecord> = this.mergeLog,
        mirrorProvenance: List<VaultMirrorProvenance>? = this.mirrorProvenance,
        clientSecurity: JsonObject? = this.clientSecurity,
    ): VaultDocument = VaultDocument(schemaVersion, entities, mergeLog, mirrorProvenance, clientSecurity)

    companion object {
        /** `.max(20)` on `mergeLog` (vault.ts:1242). */
        const val MERGE_LOG_MAX: Int = 20

        fun parse(element: JsonElement): VaultDocument {
            val obj = element as? JsonObject
                ?: documentInvalid("Vault document does not match the current schema.")
            val schemaVersion = (obj["schemaVersion"]
                ?: documentInvalid("Vault document is missing 'schemaVersion'."))
                .asIntOr("Vault document 'schemaVersion' must be an integer.")
            if (schemaVersion != VaultContract.DOCUMENT_V1_VERSION &&
                schemaVersion != VaultContract.DOCUMENT_VERSION
            ) {
                // The discriminated union has no branch for it. Note that a NEWER
                // schemaVersion is caught earlier, at the envelope, as
                // `update-required` — reaching here means the value is not a
                // version at all.
                documentInvalid("Vault document does not match the current schema.")
            }

            val entitiesJson = (obj["entities"] ?: documentInvalid("Vault document is missing 'entities'."))
                as? JsonObject ?: documentInvalid("Vault document 'entities' must be an object.")
            val entities = LinkedHashMap<String, List<VaultEntity>>()
            for ((kind, rows) in entitiesJson) {
                if (kind !in VaultContract.ENTITY_KINDS) {
                    documentInvalid("Vault document has an unknown entity kind '$kind'.")
                }
                val array = rows as? JsonArray
                    ?: documentInvalid("Vault entity kind '$kind' must hold an array.")
                entities[kind] = array.map { VaultEntity.parse(it) }
            }

            // `.default([])`: an absent mergeLog IS an empty mergeLog.
            val mergeLog = when (val raw = obj["mergeLog"]) {
                null, is JsonNull -> emptyList()
                else -> (raw as? JsonArray
                    ?: documentInvalid("Vault document 'mergeLog' must be an array."))
                    .map { VaultMergeRecord.parse(it) }
            }
            if (mergeLog.size > MERGE_LOG_MAX) {
                documentInvalid("Vault document 'mergeLog' may hold at most $MERGE_LOG_MAX records.")
            }

            // `.optional()` with NO default: absent must stay absent.
            val mirrorProvenance = obj["mirrorProvenance"]?.let { raw ->
                (raw as? JsonArray
                    ?: documentInvalid("Vault document 'mirrorProvenance' must be an array."))
                    .map { VaultMirrorProvenance.parse(it) }
            }

            val clientSecurity = if (schemaVersion == VaultContract.DOCUMENT_VERSION) {
                obj["clientSecurity"] as? JsonObject
                    ?: documentInvalid("A schemaVersion 2 vault document requires clientSecurity.")
            } else {
                null
            }

            return VaultDocument(schemaVersion, entities, mergeLog, mirrorProvenance, clientSecurity)
        }

        /** Convenience for the common write shape: a v1 document. */
        fun v1(
            entities: Map<String, List<VaultEntity>>,
            mergeLog: List<VaultMergeRecord> = emptyList(),
            mirrorProvenance: List<VaultMirrorProvenance>? = null,
        ): VaultDocument = VaultDocument(
            schemaVersion = VaultContract.DOCUMENT_V1_VERSION,
            entities = entities,
            mergeLog = mergeLog,
            mirrorProvenance = mirrorProvenance,
            clientSecurity = null,
        )
    }
}

/** `exactHeaderShape` support (envelope.ts:199-208) — exposed for the envelope codec. */
internal fun hasOnlyFields(value: JsonElement?, fields: Set<String>): Boolean {
    val obj = value as? JsonObject ?: return false
    return obj.keys.all { it in fields }
}
