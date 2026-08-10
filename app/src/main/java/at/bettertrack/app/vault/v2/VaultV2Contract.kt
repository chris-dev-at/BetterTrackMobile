package at.bettertrack.app.vault.v2

import at.bettertrack.app.vault.VaultContract
import at.bettertrack.app.vault.VaultCryptoError
import at.bettertrack.app.vault.VaultCryptoErrorCode
import at.bettertrack.app.vault.VaultKdfParams
import at.bettertrack.app.vault.jsJsonStringify
import at.bettertrack.app.vault.utf8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Vaults v2 wire contract — literal port of the platform's
 * `packages/contracts/src/vaultV2.ts` (`docs/VAULTS_V2_DESIGN.md` r3).
 *
 * Only what the six conformance families need is ported: the header doc and its
 * key slots, the portfolio index, the r3 §21 header MAC, the content-blob
 * header, and the entity-kind scoping that drives the v1→v2 split. Transport
 * DTOs and route shapes are P4 phase 2.
 *
 * **Serialization is byte-load-bearing.** A header's bytes are what the MAC
 * authenticates and what the vector pins; a blob header's bytes are AES-GCM
 * AAD. Every `toJson` below therefore emits members in the platform's schema
 * declaration order, because the platform serializes with
 * `JSON.stringify(schema.parse(value))` and zod rebuilds an object in shape
 * order. [jsJsonStringify] is insertion-ordered, so the order written here IS
 * the order on the wire.
 */
object VaultV2Contract {
    /** `formatVersion` of a v2 header doc. */
    const val HEADER_FORMAT_VERSION: Int = 2

    /** `formatVersion` of a v2 content blob, under the shared `BTVAULT1` magic. */
    const val BLOB_FORMAT_VERSION: Int = 2

    /** The v2 content-document `schemaVersion` — restarts at 1, per doc kind. */
    const val DOCUMENT_VERSION: Int = 1

    /** r3 §21 header-MAC HKDF info string. */
    const val HEADER_MAC_INFO: String = "btv2-header-mac-v1"

    /** Vault/portfolio display names are bounded exactly as v1 names are. */
    const val NAME_MAX_LENGTH: Int = 80

    /** The QR scheme prefix — names the crypto substrate, not the document version. */
    const val QR_PREFIX: String = "btvault1:"

    /** r2 §10: the whole two-screen handoff lives at most 120 seconds. */
    const val QR_TTL_MS: Long = 120_000

    /** Crockford base32 — no `I`, `L`, `O` or `U`. */
    const val QR_CODE_ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val QR_CODE_LENGTH: Int = 8
    const val QR_CODE_BITS: Int = 40

    /**
     * Entity kinds a per-portfolio content blob may contain: those belonging to
     * exactly one portfolio, directly or through a parent row (r2 §8).
     */
    val PORTFOLIO_SCOPED_KINDS: Set<String> = linkedSetOf(
        "portfolio",
        "transaction",
        "dividend",
        "cashSource",
        "cashMovement",
        "cashMovementTag",
        "portfolioSetting",
        "standingOrder",
        "standingOrderRun",
        "importBatch",
        "importRow",
        "portfolioDailySnapshot",
        "portfolioSnapshotState",
    )

    /**
     * Entity kinds the vault's `common` doc owns (r2 §8).
     *
     * The last four are NOT in r2's enumeration but have no portfolio linkage
     * at all, so a portfolio doc could never route them — they would orphan on
     * every migration. The platform placed them in `common` under r2's
     * governing sentence ("common owns every account/vault-scoped entity
     * kind") and flagged the question to its own chief; this port follows the
     * platform, which is the oracle the vectors pin.
     */
    val COMMON_SCOPED_KINDS: Set<String> = linkedSetOf(
        "taxSetting",
        "customAsset",
        "customAssetValue",
        "cashTag",
        "cashRule",
        "cashBudget",
        "expenseCategory",
        "expenseRule",
        "expenseBudget",
        // Derived placements — the parent lives in `common`, so the child must too.
        "expenseTransaction",
        "expenseBudgetFire",
        "cashBudgetFire",
        "cashRuleTag",
    )

    fun isPortfolioScopedKind(kind: String): Boolean = kind in PORTFOLIO_SCOPED_KINDS

    fun isCommonScopedKind(kind: String): Boolean = kind in COMMON_SCOPED_KINDS

    /**
     * The two scopes must partition every entity kind exactly. A kind added to
     * [VaultContract.ENTITY_KINDS] without a scope would otherwise be dropped
     * silently by the v1→v2 split; the conformance suite asserts this, and it
     * is why the split can guarantee it never loses a row.
     */
    val UNSCOPED_KINDS: Set<String> =
        VaultContract.ENTITY_KINDS.filterNot { isPortfolioScopedKind(it) || isCommonScopedKind(it) }
            .toSet()
}

private fun envelopeInvalid(message: String): Nothing =
    throw VaultCryptoError(VaultCryptoErrorCode.ENVELOPE_INVALID, message)

private fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: envelopeInvalid("Vault v2 header member '$key' must be a string.")

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
        ?: envelopeInvalid("Vault v2 header member '$key' must be an integer.")

/** One wrapped content key (`vaultKeySlotSchema`). */
data class VaultKeySlot(val slotId: String, val kind: String, val wrappedKey: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "slotId" to JsonPrimitive(slotId),
            "kind" to JsonPrimitive(kind),
            "wrappedKey" to JsonPrimitive(wrappedKey),
        ),
    )

    companion object {
        /** The only slot kind this arc writes or reads. */
        const val KIND_PASSPHRASE: String = "passphrase"

        fun parse(element: JsonElement): VaultKeySlot {
            val obj = element as? JsonObject ?: envelopeInvalid("A vault key slot must be an object.")
            return VaultKeySlot(obj.str("slotId"), obj.str("kind"), obj.str("wrappedKey"))
        }
    }
}

/** One cleartext portfolio index entry (`vaultPortfolioIndexEntrySchema`). */
data class VaultPortfolioIndexEntry(val portfolioId: String, val alias: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "portfolioId" to JsonPrimitive(portfolioId),
            "alias" to JsonPrimitive(alias),
        ),
    )

    companion object {
        fun parse(element: JsonElement): VaultPortfolioIndexEntry {
            val obj = element as? JsonObject
                ?: envelopeInvalid("A vault portfolio index entry must be an object.")
            return VaultPortfolioIndexEntry(obj.str("portfolioId"), obj.str("alias"))
        }
    }
}

/** The r3 §21 header integrity tag (`vaultHeaderMacSchema`). */
data class VaultHeaderMac(val v: Int, val tag: String) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf("v" to JsonPrimitive(v), "tag" to JsonPrimitive(tag)),
    )

    companion object {
        const val VERSION: Int = 1

        fun parse(element: JsonElement): VaultHeaderMac {
            val obj = element as? JsonObject
                ?: envelopeInvalid("The vault header integrity tag must be an object.")
            return VaultHeaderMac(obj.int("v"), obj.str("tag"))
        }
    }
}

/**
 * The v2 vault header document (`vaultHeaderDocSchema`, `formatVersion: 2`).
 *
 * Member order below IS the wire order — see the note on [VaultV2Contract].
 * `mac` is last and OPTIONAL: a pre-r3 header carries none and is tolerated as
 * `unsealed`, upgraded on the next header write.
 */
data class VaultHeaderDoc(
    val formatVersion: Int,
    val vaultId: String,
    val name: String,
    val kdfSalt: String,
    val kdf: VaultKdfParams,
    val keySlots: List<VaultKeySlot>,
    val portfolios: List<VaultPortfolioIndexEntry>,
    val backends: String,
    val headerVersion: Int,
    val deviceId: String,
    val writeId: String,
    val writtenAt: String,
    val mac: VaultHeaderMac? = null,
) {
    fun toJson(): JsonObject {
        val members = linkedMapOf<String, JsonElement>(
            "formatVersion" to JsonPrimitive(formatVersion),
            "vaultId" to JsonPrimitive(vaultId),
            "name" to JsonPrimitive(name),
            "kdfSalt" to JsonPrimitive(kdfSalt),
            "kdf" to kdf.toJson(),
            "keySlots" to JsonArray(keySlots.map { it.toJson() }),
            "portfolios" to JsonArray(portfolios.map { it.toJson() }),
            "backends" to JsonPrimitive(backends),
            "headerVersion" to JsonPrimitive(headerVersion),
            "deviceId" to JsonPrimitive(deviceId),
            "writeId" to JsonPrimitive(writeId),
            "writtenAt" to JsonPrimitive(writtenAt),
        )
        if (mac != null) members["mac"] = mac.toJson()
        return JsonObject(members)
    }

    companion object {
        /** `server`, `drive` or `both` (`vaultBackendsSchema`). */
        val BACKENDS: Set<String> = linkedSetOf("server", "drive", "both")

        fun parse(element: JsonElement): VaultHeaderDoc {
            val obj = element as? JsonObject
                ?: envelopeInvalid("A vault v2 header must be an object.")
            val formatVersion = obj.int("formatVersion")
            if (formatVersion != VaultV2Contract.HEADER_FORMAT_VERSION) {
                throw VaultCryptoError(
                    VaultCryptoErrorCode.UPDATE_REQUIRED,
                    "This vault header needs a newer app version.",
                )
            }
            val backends = obj.str("backends")
            if (backends !in BACKENDS) {
                envelopeInvalid("Vault header 'backends' must be server, drive or both.")
            }
            val slots = (obj["keySlots"] as? JsonArray
                ?: envelopeInvalid("Vault header 'keySlots' must be an array."))
                .map { VaultKeySlot.parse(it) }
            val portfolios = (obj["portfolios"] as? JsonArray
                ?: envelopeInvalid("Vault header 'portfolios' must be an array."))
                .map { VaultPortfolioIndexEntry.parse(it) }
            return VaultHeaderDoc(
                formatVersion = formatVersion,
                vaultId = obj.str("vaultId"),
                name = obj.str("name"),
                kdfSalt = obj.str("kdfSalt"),
                kdf = VaultKdfParams.parse(
                    obj["kdf"] as? JsonObject ?: envelopeInvalid("Vault header 'kdf' must be an object."),
                ),
                keySlots = slots,
                portfolios = portfolios,
                backends = backends,
                headerVersion = obj.int("headerVersion"),
                deviceId = obj.str("deviceId"),
                writeId = obj.str("writeId"),
                writtenAt = obj.str("writtenAt"),
                mac = obj["mac"]?.let { VaultHeaderMac.parse(it) },
            )
        }
    }
}

/**
 * `encodeHeaderDoc` (`v2/api.ts`) — the exact transported header bytes.
 *
 * Note this is [jsJsonStringify], NOT canonical JSON: the platform transports
 * `JSON.stringify(schema.parse(header))`, i.e. schema order, not sorted order.
 * Canonical JSON appears only inside the MAC ([headerMacInputBytes]).
 */
internal fun encodeHeaderDoc(header: VaultHeaderDoc): ByteArray =
    utf8(jsJsonStringify(header.toJson()))

/** `decodeHeaderDoc` (`v2/api.ts`). */
internal fun decodeHeaderDoc(bytes: ByteArray): VaultHeaderDoc =
    VaultHeaderDoc.parse(
        at.bettertrack.app.vault.VAULT_JSON.parseToJsonElement(
            at.bettertrack.app.vault.decodeUtf8(bytes, VaultCryptoErrorCode.ENVELOPE_INVALID),
        ),
    )
