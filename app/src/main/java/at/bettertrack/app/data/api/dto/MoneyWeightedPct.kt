package at.bettertrack.app.data.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * `moneyWeightedPct` on `GET /portfolios/{id}/history` (platform #1669, board
 * tick 2026-09-15): the Modified Dietz money-weighted return of the served
 * window, in percent units — computed by the SERVER over the same points and
 * external flows its time-weighted `performance` curve uses.
 *
 * ## Why this is three states and not a `Double?`
 *
 * The wire field is optional AND nullable, and the two "no number" spellings mean
 * different things that the owner's ruling renders differently:
 *
 *  · [Value] — a number: render it verbatim (2 dp, §7.1).
 *  · [NoCapital] — an explicit `null`: the window has no capital (nothing
 *    invested, or withdrawals outweighing the start value). Render a DASH, never 0.
 *  · [NotComputed] — the key is ABSENT: an older server, or a history row cached
 *    in Room before this field existed. Render NOTHING for it.
 *
 * A plain `Double? = null` cannot tell the last two apart (kotlinx decodes an
 * explicit `null` and a missing key into the same Kotlin `null`), so the DTO field
 * is this type, non-null, defaulted to [NotComputed]: the default only applies
 * when the key is missing, and when it is present [MoneyWeightedPctSerializer]
 * reads the raw JSON element — `JsonNull` included — itself.
 */
@Serializable(with = MoneyWeightedPctSerializer::class)
sealed interface MoneyWeightedPct {

    /** The key was absent: not computed by this server (or an older cache row). */
    data object NotComputed : MoneyWeightedPct

    /** Explicit `null` on the wire: the window holds no capital. */
    data object NoCapital : MoneyWeightedPct

    /** The server's money-weighted return for the window, percent units. */
    data class Value(val pct: Double) : MoneyWeightedPct
}

/**
 * The present-key half of the tri-state. Only ever invoked when the key is in the
 * object (absence is the property default), so a `null` here is the server's
 * deliberate "no capital".
 *
 * A present value that is not a finite JSON number (a string, an object, a value
 * this build does not understand) degrades to [MoneyWeightedPct.NotComputed]
 * rather than failing the decode: one malformed optional figure must not cost the
 * user the whole chart, and "render nothing" is the honest reading of a figure
 * this build cannot read.
 */
object MoneyWeightedPctSerializer : KSerializer<MoneyWeightedPct> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("at.bettertrack.app.MoneyWeightedPct", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): MoneyWeightedPct {
        val json = decoder as? JsonDecoder
            ?: return MoneyWeightedPct.Value(decoder.decodeDouble())
        return moneyWeightedFromElement(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: MoneyWeightedPct) {
        val element: JsonElement = when (value) {
            is MoneyWeightedPct.Value -> JsonPrimitive(value.pct)
            MoneyWeightedPct.NoCapital -> JsonNull
            // Absent has no spelling: the DTO never encodes its default, and
            // writing `null` here would silently turn "not computed" into "no
            // capital" — a different, false statement.
            MoneyWeightedPct.NotComputed ->
                throw SerializationException("NotComputed is the absence of moneyWeightedPct; it has no wire form")
        }
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("moneyWeightedPct is JSON-only")
        json.encodeJsonElement(element)
    }
}

internal fun moneyWeightedFromElement(element: JsonElement): MoneyWeightedPct = when {
    element is JsonNull -> MoneyWeightedPct.NoCapital
    element is JsonPrimitive && !element.isString ->
        element.doubleOrNull?.takeIf { it.isFinite() }
            ?.let { MoneyWeightedPct.Value(it) }
            ?: MoneyWeightedPct.NotComputed
    else -> MoneyWeightedPct.NotComputed
}

// ── The Room cache spelling (portfolio_history.moneyWeightedPctJson) ─────────
//
// The column holds the wire element VERBATIM, like the two series beside it:
// SQL NULL = the key was absent (and every row cached before v15), the text
// `null` = the server's explicit no-capital null, a number literal = the value.
// That keeps the tri-state across a cold start without inventing a sentinel.

/** The cache column for this figure; `null` (SQL NULL) means absent. */
fun MoneyWeightedPct.toCacheJson(): String? = when (this) {
    MoneyWeightedPct.NotComputed -> null
    MoneyWeightedPct.NoCapital -> "null"
    is MoneyWeightedPct.Value -> pct.toString()
}

/** Inverse of [toCacheJson]; an unreadable cell reads as not computed. */
fun moneyWeightedFromCacheJson(raw: String?): MoneyWeightedPct {
    if (raw == null) return MoneyWeightedPct.NotComputed
    return try {
        moneyWeightedFromElement(Json.parseToJsonElement(raw))
    } catch (_: SerializationException) {
        MoneyWeightedPct.NotComputed
    }
}
