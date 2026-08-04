package at.bettertrack.app.ui.cash

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure logic for editing an already-synced cash movement (v5 correction ops).
 *
 * The PATCH body is built as an explicit [JsonObject] rather than a data class
 * on purpose. Two constraints force it:
 *
 * 1. The server schema is `.strict()` **and** demands at least one real field —
 *    an unknown key is a 400, and so is an empty body. So the app must send
 *    exactly the keys it means and nothing else.
 * 2. `note` is nullable-with-meaning: JSON `null` CLEARS the note, an OMITTED
 *    key leaves it alone. The app's shared `Json` runs `explicitNulls = false`,
 *    which drops Kotlin nulls from the wire — with a plain data class there
 *    would be no way to express "clear this note" at all.
 */

/** The user's intent for one edit, as captured by the form. */
data class CashEditIntent(
    /** New kind, or null to leave unchanged. Only deposit/withdrawal/fee are valid. */
    val kind: CashKind? = null,
    /** Positive MAGNITUDE — the server derives the sign from the kind. */
    val amountEur: Double? = null,
    val sourceId: String? = null,
    val executedAt: String? = null,
    /** New note text, or null when [clearNote] decides. */
    val note: String? = null,
    /** True to send `"note": null` and clear a previously-set note. */
    val clearNote: Boolean = false,
    /** Mirror optimistic concurrency — the row's `mirror.version`, when it has one. */
    val baseSeq: Int? = null,
)

/**
 * Build the PATCH body for [intent], omitting every field the user did not
 * touch. Returns `null` when there is nothing to send (which would be a 400) so
 * callers can short-circuit instead of firing a doomed request.
 *
 * `baseSeq` alone does not count as a change — the server's `.refine()`
 * explicitly requires at least one key that is not `baseSeq`.
 */
fun buildCashMovementPatch(intent: CashEditIntent): JsonObject? {
    val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
        intent.kind?.let { put("kind", JsonPrimitive(it.wire)) }
        intent.amountEur?.let { put("amountEur", JsonPrimitive(it)) }
        intent.sourceId?.let { put("sourceId", JsonPrimitive(it)) }
        intent.executedAt?.let { put("executedAt", JsonPrimitive(it)) }
        when {
            intent.clearNote -> put("note", JsonNull)
            intent.note != null -> put("note", JsonPrimitive(intent.note))
        }
    }
    if (fields.isEmpty()) return null
    val withGuard = fields + buildMap {
        intent.baseSeq?.let { put("baseSeq", JsonPrimitive(it)) }
    }
    return JsonObject(withGuard)
}

/**
 * Narrow an arbitrary wire kind to one the edit form may submit.
 *
 * The UI only offers edit on hand-typed rows, so this should never reject in
 * practice — it is the belt-and-braces check that keeps a future derived kind
 * from being sent into a guaranteed 409.
 */
fun editableKindOrNull(wire: String): CashKind? =
    CashKind.fromWire(wire)?.takeIf { it.handTyped }

/**
 * The magnitude to prefill an edit form with. Ledger amounts are SIGNED
 * (outflows negative) but the wire wants a positive magnitude, so the form
 * always works in absolute terms and lets the kind carry the direction.
 */
fun editAmountMagnitude(signedAmountEur: Double): Double = kotlin.math.abs(signedAmountEur)
