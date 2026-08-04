package at.bettertrack.app.vault

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The in-memory vault entity graph and the payload vocabulary the app authors —
 * the layer between the opaque [VaultDocument] and the domain engine.
 *
 * ## Field shapes are copied from a real vault, not invented
 *
 * Every payload builder below reproduces the exact member set the platform's own
 * published `clientMoney.fixture.json` carries for that kind, decrypted and read
 * off the wire (see `VaultEntityGraphTest`). That matters more than it looks: the
 * web PWA validates each kind against a zod schema, so an app-authored
 * `transaction` missing `taxMode` or spelling `type` where the contract says
 * `side` is not "slightly different" — it is a row the other client refuses to
 * load, in a vault only the user can decrypt, with no server anywhere to notice.
 *
 * ## Money is decimal STRINGS
 *
 * `quantity`, `price`, `fee`, `amountEur` are strings (`"1000"`, `"40.2"`) in the
 * real fixture, and this file keeps them strings. The engine converts to `Double`
 * at the point of computation, exactly like the reference does. Writing them as
 * JSON numbers would round-trip 0.1 through a binary double and back into a
 * decimal literal that is *not* what the user typed.
 */

/** The entity kinds this build authors (plan §2.4 "first Drive cut"). */
object VaultKinds {
    const val PORTFOLIO = "portfolio"
    const val TRANSACTION = "transaction"
    const val CASH_SOURCE = "cashSource"
    const val CASH_MOVEMENT = "cashMovement"
    const val CUSTOM_ASSET = "customAsset"
    const val CUSTOM_ASSET_VALUE = "customAssetValue"
    const val PORTFOLIO_SETTING = "portfolioSetting"

    /** Kinds the app writes. Everything else is carried through untouched. */
    val AUTHORED: Set<String> = linkedSetOf(
        PORTFOLIO,
        TRANSACTION,
        CASH_SOURCE,
        CASH_MOVEMENT,
        CUSTOM_ASSET,
        CUSTOM_ASSET_VALUE,
        PORTFOLIO_SETTING,
    )
}

/**
 * A mutable snapshot of the vault's entities, keyed `kind → id → entity`.
 *
 * Insertion-ordered throughout ([LinkedHashMap]) because the document's own
 * serialization order is observable in the envelope bytes, and because plan §3.3
 * rule 4 forbids re-ordering anything that feeds the engine.
 */
class VaultEntityGraph(entities: Map<String, List<VaultEntity>> = emptyMap()) {

    private val byKind: LinkedHashMap<String, LinkedHashMap<String, VaultEntity>> =
        LinkedHashMap<String, LinkedHashMap<String, VaultEntity>>().apply {
            for ((kind, rows) in entities) {
                put(kind, rows.associateByTo(LinkedHashMap()) { it.id })
            }
        }

    /** Every row of a kind, tombstones INCLUDED. */
    fun all(kind: String): List<VaultEntity> = byKind[kind]?.values?.toList().orEmpty()

    /** Live rows of a kind — the projection's input. */
    fun live(kind: String): List<VaultEntity> = all(kind).filter { it.deletedAt == null }

    fun find(kind: String, id: String): VaultEntity? = byKind[kind]?.get(id)

    fun kinds(): Set<String> = byKind.keys.toSet()

    /**
     * Creates a row at `rev = 0`.
     *
     * `rev` starts at 0 and not 1 because that is what the platform's own vault
     * carries for freshly-authored rows, and merge rule 1 compares revs across
     * clients.
     */
    fun create(kind: String, id: String, data: JsonObject, editedAt: String, editedBy: String): VaultEntity {
        val entity = VaultEntity(id = id, rev = 0, editedAt = editedAt, editedBy = editedBy, deletedAt = null, data = data)
        put(kind, entity)
        return entity
    }

    /**
     * Replaces a row's payload and **bumps its `rev`**.
     *
     * The bump is the merge protocol, not bookkeeping: an edit that left `rev`
     * alone would tie with the other device's copy and fall through to the
     * `editedAt` tie-break, where a device with a skewed clock could lose an edit
     * it made later.
     */
    fun edit(kind: String, id: String, editedAt: String, editedBy: String, transform: (JsonObject) -> JsonObject): VaultEntity? {
        val current = find(kind, id) ?: return null
        val updated = current.copy(
            rev = current.rev + 1,
            editedAt = editedAt,
            editedBy = editedBy,
            data = transform(current.data),
        )
        put(kind, updated)
        return updated
    }

    /**
     * Tombstones a row — it is **never** removed.
     *
     * A row deleted by removal would be silently resurrected by the next merge
     * with a device that still has it (merge rule 1 has nothing to compare
     * against an absent id). The tombstone is what carries "this was deleted" to
     * the other replica, and it is retained ≥ 180 days by contract.
     */
    fun tombstone(kind: String, id: String, deletedAt: String, editedBy: String): VaultEntity? {
        val current = find(kind, id) ?: return null
        val updated = current.copy(
            rev = current.rev + 1,
            editedAt = deletedAt,
            editedBy = editedBy,
            deletedAt = deletedAt,
        )
        put(kind, updated)
        return updated
    }

    fun put(kind: String, entity: VaultEntity) {
        byKind.getOrPut(kind) { LinkedHashMap() }[entity.id] = entity
    }

    fun toEntities(): Map<String, List<VaultEntity>> = LinkedHashMap<String, List<VaultEntity>>().apply {
        for ((kind, rows) in byKind) if (rows.isNotEmpty()) put(kind, rows.values.toList())
    }

    fun copy(): VaultEntityGraph = VaultEntityGraph(toEntities())
}

// ── Payload access ──────────────────────────────────────────────────────────

/** A payload string field, or null when absent/JSON-null. */
fun VaultEntity.text(field: String): String? {
    val element = data[field] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.content
}

/**
 * A decimal-string money field as a [Double].
 *
 * Tolerates a JSON number too: a conforming producer *should* write the string
 * form, but reading is where this client meets other people's bytes, and
 * refusing a numerically identical value would be strictness with no safety
 * behind it.
 */
fun VaultEntity.decimal(field: String): Double? = text(field)?.toDoubleOrNull()

fun VaultEntity.flag(field: String): Boolean? {
    val element = data[field] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
}

private fun str(value: String?) = value?.let { JsonPrimitive(it) } ?: JsonNull

/**
 * Renders a `Double` the way the platform writes money into a vault payload.
 *
 * `10.0` must serialize as `"10"`, not `"10.0"` — the fixture's own `quantity`
 * is `"10"` — because the string IS the value: two clients that disagree on the
 * spelling produce two different payloads for the same trade, and every
 * content-addressed comparison in the merge path (`canonicalJson`) then reports
 * a difference where there is none.
 */
internal fun moneyString(value: Double): String {
    require(value.isFinite()) { "A vault money field must be finite." }
    if (value == Math.floor(value) && Math.abs(value) < 1e15) return value.toLong().toString()
    return value.toBigDecimal().stripTrailingZeros().toPlainString()
}

// ── Payload builders (shapes verified against clientMoney.fixture.json) ─────

object VaultPayloads {

    /** `portfolio` — fixture members exactly. */
    fun portfolio(
        userId: String?,
        name: String,
        visibility: String = "private",
        sortOrder: Int = 0,
        defaultPayFromCash: Boolean = false,
        archivedAt: String? = null,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "userId" to str(userId),
            "name" to JsonPrimitive(name),
            "visibility" to JsonPrimitive(visibility),
            "sortOrder" to JsonPrimitive(sortOrder),
            "defaultPayFromCash" to JsonPrimitive(defaultPayFromCash),
            "archivedAt" to str(archivedAt),
        )
    )

    /** `cashSource`. `type` (not `kind`) is the contract's spelling. */
    fun cashSource(
        portfolioId: String,
        name: String,
        type: String,
        isMain: Boolean,
        createdAt: String,
        archivedAt: String? = null,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "portfolioId" to JsonPrimitive(portfolioId),
            "name" to JsonPrimitive(name),
            "type" to JsonPrimitive(type),
            "isMain" to JsonPrimitive(isMain),
            "archivedAt" to str(archivedAt),
            "createdAt" to JsonPrimitive(createdAt),
        )
    )

    /** `transaction`. Tax members are written as nulls — Drive mode is `taxMode: none` (plan §3.2). */
    fun transaction(
        portfolioId: String,
        assetId: String,
        side: String,
        quantity: Double,
        price: Double,
        fee: Double,
        executedAt: String,
        note: String? = null,
        allowUncovered: Boolean = false,
        uncoveredEntryPrice: Double? = null,
        source: String = "manual",
    ): JsonObject = JsonObject(
        linkedMapOf(
            "portfolioId" to JsonPrimitive(portfolioId),
            "assetId" to JsonPrimitive(assetId),
            "side" to JsonPrimitive(side),
            "quantity" to JsonPrimitive(moneyString(quantity)),
            "price" to JsonPrimitive(moneyString(price)),
            "fee" to JsonPrimitive(moneyString(fee)),
            "executedAt" to JsonPrimitive(executedAt),
            "note" to str(note),
            "taxMode" to JsonNull,
            "taxCountry" to JsonNull,
            "taxAmountEur" to JsonNull,
            "taxParams" to JsonNull,
            "allowUncovered" to JsonPrimitive(allowUncovered),
            "uncoveredEntryPrice" to (uncoveredEntryPrice?.let { JsonPrimitive(moneyString(it)) } ?: JsonNull),
            "source" to JsonPrimitive(source),
        )
    )

    /** `cashMovement`. [kind] must be a `CASH_MOVEMENT_KINDS` member with the right sign. */
    fun cashMovement(
        portfolioId: String,
        sourceId: String,
        kind: String,
        amountEur: Double,
        executedAt: String,
        createdAt: String,
        note: String? = null,
        transactionId: String? = null,
        transferId: String? = null,
        counterpartSourceId: String? = null,
        dividendId: String? = null,
        source: String = "manual",
    ): JsonObject = JsonObject(
        linkedMapOf(
            "portfolioId" to JsonPrimitive(portfolioId),
            "sourceId" to JsonPrimitive(sourceId),
            "kind" to JsonPrimitive(kind),
            "amountEur" to JsonPrimitive(moneyString(amountEur)),
            "transactionId" to str(transactionId),
            "transferId" to str(transferId),
            "counterpartSourceId" to str(counterpartSourceId),
            "dividendId" to str(dividendId),
            "taxYear" to JsonNull,
            "executedAt" to JsonPrimitive(executedAt),
            "note" to str(note),
            "source" to JsonPrimitive(source),
            "createdAt" to JsonPrimitive(createdAt),
            "dedupHash" to JsonNull,
            "originalCurrency" to JsonNull,
        )
    )

    /**
     * `customAsset` — the asset IDENTITY record, despite the kind's name.
     *
     * The fixture's `customAsset` rows carry `providerId`/`providerRef`/`symbol`/
     * `currency`: this kind is where a Drive vault keeps every asset it
     * references, not only user-invented ones. `ownerId` distinguishes them —
     * non-null means the user authored it.
     */
    fun customAsset(
        ownerId: String?,
        type: String,
        symbol: String,
        name: String,
        currency: String,
        exchange: String? = null,
        providerId: String? = null,
        providerRef: String? = null,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "providerId" to str(providerId),
            "providerRef" to str(providerRef),
            "ownerId" to str(ownerId),
            "type" to JsonPrimitive(type),
            "symbol" to JsonPrimitive(symbol),
            "name" to JsonPrimitive(name),
            "exchange" to str(exchange),
            "currency" to JsonPrimitive(currency),
            "meta" to JsonNull,
            "searchText" to JsonPrimitive("$symbol $name"),
        )
    )

    /** `customAssetValue` — one manually-entered value point, keyed by calendar date. */
    fun customAssetValue(assetId: String, date: String, value: Double): JsonObject = JsonObject(
        linkedMapOf(
            "assetId" to JsonPrimitive(assetId),
            "date" to JsonPrimitive(date),
            "value" to JsonPrimitive(moneyString(value)),
        )
    )
}
