package at.bettertrack.app.vault

import at.bettertrack.app.domain.jsNumberToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON semantics the vault depends on, reproduced exactly.
 *
 * The vault's bytes are not "some serialization of a document" — they are
 * `utf8(JSON.stringify(x))`, authenticated by AES-GCM. Two clients that disagree
 * about how to render a number, or about member order, write different vaults.
 * So the two renderers here are ports, not conveniences:
 *
 * - [jsJsonStringify] is ECMAScript `JSON.stringify` — insertion order preserved,
 *   numbers rendered by `Number::toString`. It produces the plaintext that gets
 *   compressed and encrypted, and the header bytes that become the GCM AAD.
 * - [canonicalJson] is the port of `merge.ts:340-401` — the same values with
 *   object members sorted, used only for *comparison* and tie-breaking during a
 *   merge, never for anything that reaches the wire.
 */

/** Shared lenient-free parser. `isLenient = false` is the default and is required. */
internal val VAULT_JSON = Json {
    // The vault must reject anything a browser's JSON.parse would reject.
    isLenient = false
    allowSpecialFloatingPointValues = false
    allowStructuredMapKeys = false
}

/**
 * Renders a JSON number the way JavaScript does.
 *
 * A JSON *text* and a JavaScript *number* are not the same thing: `1.0`, `1e0`
 * and `1` are three different texts for one double, and `JSON.parse` →
 * `JSON.stringify` collapses them all to `1`. The web client always goes through
 * that round trip (parse → zod → stringify), so the app must too, or a document
 * written by one and re-encrypted by the other would differ in bytes while
 * meaning the same thing.
 *
 * Delegates to the W2 shim [jsNumberToString] (`domain/DomainTypes.kt`) — the
 * same `Number::toString` implementation the domain port already needed for its
 * error messages. Reusing it is deliberate: two copies of a shortest-round-trip
 * algorithm are two chances to disagree.
 */
private fun jsNumber(primitive: JsonPrimitive): String {
    val value = primitive.content.toDoubleOrNull()
        ?: throw VaultCryptoError(
            VaultCryptoErrorCode.DOCUMENT_INVALID,
            "Vault documents may contain only finite JSON numbers.",
        )
    if (!value.isFinite()) {
        throw VaultCryptoError(
            VaultCryptoErrorCode.DOCUMENT_INVALID,
            "Vault documents may contain only finite JSON numbers.",
        )
    }
    return jsNumberToString(value)
}

/** True for a primitive that is a JSON number (not a string, boolean or null). */
private fun JsonPrimitive.isJsonNumber(): Boolean =
    !isString && this !== JsonNull && content != "true" && content != "false"

/**
 * ECMAScript `JSON.stringify(value)` — compact, insertion-ordered.
 *
 * String escaping is delegated to `kotlinx.serialization`, which escapes exactly
 * the set `JSON.stringify` escapes (`"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t` and
 * C0 controls as `\uXXXX`) and leaves everything else — including `/` and
 * non-ASCII — as raw UTF-8. Numbers are re-rendered by [jsNumber] because
 * kotlinx would otherwise echo the source literal verbatim.
 */
internal fun jsJsonStringify(element: JsonElement): String {
    val out = StringBuilder()
    writeJs(element, out)
    return out.toString()
}

private fun writeJs(element: JsonElement, out: StringBuilder) {
    when (element) {
        is JsonNull -> out.append("null")
        is JsonPrimitive ->
            if (element.isJsonNumber()) out.append(jsNumber(element))
            else out.append(VAULT_JSON.encodeToString(JsonPrimitive.serializer(), element))
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                writeJs(item, out)
            }
            out.append(']')
        }
        is JsonObject -> {
            out.append('{')
            var first = true
            for ((key, value) in element) {
                if (!first) out.append(',')
                first = false
                out.append(VAULT_JSON.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)))
                out.append(':')
                writeJs(value, out)
            }
            out.append('}')
        }
    }
}

/**
 * Port of `canonicalJson` (`merge.ts:340-401`).
 *
 * Same output as [jsJsonStringify] except that object members are emitted in
 * ascending key order, so two structurally equal documents compare equal as
 * strings regardless of how their writers ordered members. Used by the merge
 * engine for equality, dominance and the final content tie-break.
 *
 * Three of the reference's guards are *unreachable* in Kotlin and are therefore
 * asserted structurally rather than ported line for line — noted here so the
 * omission is a decision and not an oversight:
 *
 * - cycle detection (`assertAcyclic`): a `JsonElement` tree is built by a parser
 *   or by construction and cannot alias itself.
 * - sparse/exotic arrays and non-plain prototypes: `JsonArray` is a dense `List`
 *   and `JsonObject` is a plain `Map`; there are no property descriptors,
 *   getters or prototypes to inspect.
 * - non-string keys: `JsonObject` keys are `String` by type.
 *
 * The guard that *is* reachable — non-finite numbers — is ported, in [jsNumber].
 *
 * Key ordering uses the reference's `compareText`, i.e. a plain `<`/`>` on the
 * string, which for Kotlin `String` is the same UTF-16 code-unit ordering
 * JavaScript uses.
 */
internal fun canonicalJson(element: JsonElement): String {
    val out = StringBuilder()
    writeCanonical(element, out)
    return out.toString()
}

private fun writeCanonical(element: JsonElement, out: StringBuilder) {
    when (element) {
        is JsonNull -> out.append("null")
        is JsonPrimitive ->
            if (element.isJsonNumber()) {
                // merge.ts:347 — `Object.is(value, -0)` renders as `-0`, unlike
                // JSON.stringify which renders it `0`. Canonical JSON therefore
                // distinguishes the two zeros; the wire form does not.
                val value = element.content.toDoubleOrNull()
                if (value != null && value == 0.0 && 1.0 / value < 0) out.append("-0")
                else out.append(jsNumber(element))
            } else {
                out.append(VAULT_JSON.encodeToString(JsonPrimitive.serializer(), element))
            }
        is JsonArray -> {
            out.append('[')
            element.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                writeCanonical(item, out)
            }
            out.append(']')
        }
        is JsonObject -> {
            out.append('{')
            var first = true
            for (key in element.keys.sortedWith(compareBy { it })) {
                if (!first) out.append(',')
                first = false
                out.append(VAULT_JSON.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)))
                out.append(':')
                writeCanonical(element.getValue(key), out)
            }
            out.append('}')
        }
    }
}

/** `compareText` (`merge.ts:410-412`) — JS relational string comparison. */
internal fun compareText(left: String, right: String): Int =
    if (left < right) -1 else if (left > right) 1 else 0
