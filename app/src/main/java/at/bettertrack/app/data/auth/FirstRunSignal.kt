package at.bettertrack.app.data.auth

import at.bettertrack.app.data.api.dto.MeResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The account's first-run-setup signal, as a **tri-state**.
 *
 * ## Why three values and not a nullable timestamp
 *
 * `/auth/me` carries `firstRunCompletedAt` (platform migration 0074): a
 * timestamp when the account has finished — or dismissed — setup, and an
 * explicit `null` when it never has. The field is *optional* on the contract
 * (`packages/contracts/src/auth.ts`), and the contract's own KDoc spells out the
 * consequence a client must honour:
 *
 * > A client must therefore read `undefined` as "unknown", never as "not
 * > completed" — guessing the latter would send every established user of an
 * > older server back through setup.
 *
 * So the app needs to tell **absent** from **explicit null**, which a plain
 * `String?` cannot do: kotlinx-serialization collapses both onto Kotlin `null`
 * (an absent key takes the property default, and `null` decodes as `null`).
 * [FirstRunStamp] is the type that keeps them apart, and [FirstRunState] is the
 * three-valued answer every consumer actually wants.
 */
enum class FirstRunState {
    /**
     * The server did not say. A pre-0074 deployment, a body captured before the
     * field existed, or a session cached by an older build of this app.
     *
     * **Never gates.** This is the whole reason the tri-state exists.
     */
    UNKNOWN,

    /** The server said `null`: this account has never been through setup. */
    PENDING,

    /** The server sent a timestamp: setup is finished or was dismissed. */
    DONE,
}

/**
 * The wire value of `firstRunCompletedAt`, preserving the absent/null/timestamp
 * distinction that the property type alone cannot express.
 *
 * Decoded through [FirstRunStampSerializer] on a **non-nullable** property with
 * [Absent] as its default — that combination is what makes the three cases
 * distinguishable:
 *
 *  - key missing  → the property default is used, i.e. [Absent] → [FirstRunState.UNKNOWN]
 *  - `null`       → the serializer sees `JsonNull`          → [Never] → [FirstRunState.PENDING]
 *  - `"2026-…Z"`  → the serializer sees a string            → [At]    → [FirstRunState.DONE]
 *
 * (A *nullable* property would be decoded through `decodeNullableSerializableElement`,
 * which consumes the null itself and never reaches the serializer — which is
 * exactly the trap this type is here to avoid.)
 *
 * The serializer is bound at the PROPERTY (`@Serializable(with = …)` on
 * [at.bettertrack.app.data.api.dto.MeResponse.firstRunCompletedAt]) rather than
 * on this type, so nothing here competes with the plugin's own handling of a
 * sealed hierarchy — this is a wire-shape adapter, not a polymorphic model.
 */
sealed interface FirstRunStamp {

    val state: FirstRunState

    /** The key was not in the body at all. */
    data object Absent : FirstRunStamp {
        override val state: FirstRunState get() = FirstRunState.UNKNOWN
    }

    /** The key was present and explicitly `null`. */
    data object Never : FirstRunStamp {
        override val state: FirstRunState get() = FirstRunState.PENDING
    }

    /** The key carried an ISO-8601 instant. */
    data class At(val iso: String) : FirstRunStamp {
        override val state: FirstRunState get() = FirstRunState.DONE
    }
}

/**
 * Reads `firstRunCompletedAt` as a [FirstRunStamp].
 *
 * Encoding is lossy on purpose and never exercised: [MeResponse] is a *response*
 * DTO that the app only ever decodes. [FirstRunStamp.Absent] cannot be written
 * as "no key" from inside a value serializer, so it encodes as JSON null — the
 * same shape as [FirstRunStamp.Never]. Anything that needs to persist the
 * distinction persists [FirstRunState] instead (see [SessionUser.firstRun]).
 */
object FirstRunStampSerializer : KSerializer<FirstRunStamp> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("at.bettertrack.FirstRunStamp", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): FirstRunStamp {
        // Non-JSON decoders (none in this app today) degrade to the string form.
        val json = decoder as? JsonDecoder ?: return FirstRunStamp.At(decoder.decodeString())
        val element = json.decodeJsonElement()
        if (element is JsonNull) return FirstRunStamp.Never
        // Only a JSON *string* is an instant. A number, a boolean or an object is
        // not something this app can interpret, and the safe reading of anything
        // it cannot interpret is "the server did not say" — UNKNOWN never gates,
        // so a malformed body can never march someone into setup.
        val primitive = element as? JsonPrimitive
        val iso = primitive?.takeIf { it.isString }?.contentOrNull
        return if (iso.isNullOrBlank()) FirstRunStamp.Absent else FirstRunStamp.At(iso)
    }

    override fun serialize(encoder: Encoder, value: FirstRunStamp) {
        val json = encoder as? JsonEncoder
        when (value) {
            is FirstRunStamp.At -> encoder.encodeString(value.iso)
            else -> json?.encodeJsonElement(JsonNull) ?: encoder.encodeString("")
        }
    }
}

/** The three-valued reading of `/auth/me`'s `firstRunCompletedAt`. */
val MeResponse.firstRunState: FirstRunState get() = firstRunCompletedAt.state

/** The stamp's instant, or null when the account has not completed setup. */
val MeResponse.firstRunCompletedIso: String?
    get() = (firstRunCompletedAt as? FirstRunStamp.At)?.iso

// ── The gate (below RootGate, below the app lock) ───────────────────────────

/** What the first-run gate renders once the auth + app-lock gates have passed. */
enum class FirstRunGate {
    /** The ordinary app shell. */
    APP,

    /** The native first-run wizard. */
    WIZARD,
}

/**
 * The gate decision, as a pure function — the same shape (and the same no-flash
 * discipline) as [at.bettertrack.app.data.storage.rootGate].
 *
 * Three independent reasons never to show the wizard, and each one matters:
 *
 *  1. **No server account.** Drive-autonomous installs have no BetterTrack
 *     account, so there is no setup to run and no endpoint to complete it with.
 *  2. **The signal is not PENDING.** [FirstRunState.UNKNOWN] is the dangerous
 *     one: an older server, or a session cached by a build that did not know the
 *     field, must behave exactly like DONE. Guessing "pending" there would march
 *     every established user through setup — see [FirstRunState].
 *  3. **This account dismissed the run on this device.** The wizard is an offer,
 *     never a trap; a dismissal must survive until the user asks for it again
 *     from Settings (or the server flag flips).
 */
fun firstRunGate(
    hasServerAccount: Boolean,
    state: FirstRunState,
    dismissedForAccount: Boolean,
): FirstRunGate = when {
    !hasServerAccount -> FirstRunGate.APP
    state != FirstRunState.PENDING -> FirstRunGate.APP
    dismissedForAccount -> FirstRunGate.APP
    else -> FirstRunGate.WIZARD
}

/**
 * Whether Settings' account area shows the "Finish setup" escape row.
 *
 * Deliberately NOT the same predicate as [firstRunGate]: the row exists *because*
 * a dismissal hid the wizard, so it ignores the dismissal entirely and keys only
 * on the server's own signal. Hidden for DONE and for UNKNOWN — a row offering to
 * finish a setup the server has never mentioned would be a fabricated task.
 */
fun firstRunEscapeRowVisible(hasServerAccount: Boolean, state: FirstRunState): Boolean =
    hasServerAccount && state == FirstRunState.PENDING
