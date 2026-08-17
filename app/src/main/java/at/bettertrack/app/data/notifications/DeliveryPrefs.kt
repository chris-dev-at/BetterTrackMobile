package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.QuietHoursDto
import at.bettertrack.app.data.api.dto.QuietHoursPatchDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Digest cadence + quiet hours (platform v5 `GET|PATCH /settings/notifications`).
 *
 * Everything in this file is a PURE function of the last GET plus the user's
 * intent, so the exact PATCH body the app produces is unit-tested rather than
 * inferred from the UI. Two rules govern all of it:
 *
 *  1. **Echo-verbatim.** The app never invents a key the server did not send. A
 *     pre-v5 GET carries no `cadence` / no `quietHours` → both stay `null` → the
 *     Delivery section is hidden and neither key can reach a PATCH body (the
 *     server schema is `.strict()` at every level and would 400).
 *  2. **Send only what changed.** `matrix` and `cadence` are strict MAPS (a listed
 *     type must be complete), while `quietHours` is field-partial. Both diffs are
 *     computed here, and an empty diff means "don't PATCH at all" — an empty `{}`
 *     body is itself a 400.
 *
 * Cadence governs OUTBOUND channels only (email / push / Telegram / Discord). The
 * in-app inbox is always instant and quiet hours never touch it.
 */

/** The three cadences the v5 `cadence` map accepts. */
enum class DigestCadence(val wire: String) {
    Instant("instant"),
    Daily("daily"),
    Weekly("weekly"),
    ;

    companion object {
        /** `null` for an unknown/absent wire value — never a guessed default. */
        fun fromWire(wire: String?): DigestCadence? = entries.firstOrNull { it.wire == wire }
    }
}

// ── Where the digestible-type list went ────────────────────────────────────────
//
// There used to be a `DeliveryTypes` object here splitting the platform's types
// into an editorial `digestible` group of 18 and an `alwaysInstant` group of 7,
// with ONE cadence chooser governing the whole digestible group.
//
// It was wrong on both halves. The split was an app opinion the server does not
// share — the API accepts and returns a cadence for every type — so seven types
// silently lost a control the account genuinely has. And a single group chooser
// cannot express per-type state: an account configured on the web rendered as
// "mixed" and the control gave up.
//
// Cadence is now per type, for all 25 the web offers (everything but
// `account.invite`). The list lives in `NotifCatalog.cadenceTypes` alongside the
// rest of the taxonomy, and the PATCH builder is `typeCadencePatch`.

/** Server default: 22:00 → 07:00, an overnight window. */
const val QUIET_HOURS_DEFAULT_START = 1320
const val QUIET_HOURS_DEFAULT_END = 420

/**
 * The quiet-hours window as the app holds it.
 *
 * [timezone] `null` is the server's own "no zone stored" state, and it has ONE
 * meaning: the window is evaluated in **UTC**. It is not "unknown" and it is not
 * a slot for the app to fill in — the screen says UTC out loud and offers the
 * device zone as one pickable option among all the others. Nothing writes a zone
 * the user did not choose, and clearing back to `null` is a real, sendable
 * choice (see [quietHoursPatch]).
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = QUIET_HOURS_DEFAULT_START,
    val endMinute: Int = QUIET_HOURS_DEFAULT_END,
    val timezone: String? = null,
)

/**
 * The delivery half of the settings response, as last seen from the server.
 * A `null` member means "the last GET did not model this" (pre-v5) — NOT "off".
 */
data class DeliveryState(
    val cadence: Map<String, String>? = null,
    val quietHours: QuietHours? = null,
) {
    /** Whether the server modelled anything in the delivery family at all. */
    val supported: Boolean get() = cadence != null || quietHours != null
}

// ── Account-wide mute (the web's single "silence everything" switch) ───────────
//
// There is deliberately NO "should this look disabled" predicate here. The web
// greys its routing GRID under a mute and leaves everything else live; this
// screen defers the grid to the web entirely, so nothing is left for a mute to
// grey (coordinator ruling 2026-08-08). Mute stops delivery — it does not take
// the quiet-hours schedule away from the user.

/**
 * The `muted` value to PATCH for an account-mute change, or `null` for "send
 * nothing at all".
 *
 * Two reasons to send nothing, and they are different failures if confused:
 * the server never modelled the flag (`current == null`) — inventing the key
 * would be a `.strict()` 400 on an unknown property — or the value is already
 * what the user asked for, and an empty `{}` body is itself a 400.
 */
fun accountMutePatch(current: Boolean?, next: Boolean): Boolean? =
    if (current == null || current == next) null else next

/** Decode a server quiet-hours object; absent fields fall back to the server defaults. */
fun QuietHoursDto.toQuietHours(): QuietHours = QuietHours(
    enabled = enabled ?: false,
    startMinute = startMinute ?: QUIET_HOURS_DEFAULT_START,
    endMinute = endMinute ?: QUIET_HOURS_DEFAULT_END,
    timezone = timezone,
)

/**
 * The field-partial `quietHours` object for a change: ONLY the fields that differ.
 * Returns `null` when nothing differs (an empty `{}` body is a 400).
 *
 * ## The timezone is diffed in BOTH directions
 *
 * It used to be diffed one way only — a zone could be set but never cleared —
 * with the stated reason that "an explicit null would be dropped by
 * `explicitNulls = false` anyway". That reason was real but it was a property of
 * the DTO, not of the server: clearing the zone is a legitimate choice (it means
 * *run the window on UTC*, which is exactly what the picker's "None (UTC)" entry
 * offers), and the app had no way to express it.
 *
 * [QuietHoursPatchDto.timezone] is a `JsonElement`, so all three intents survive
 * encoding: absent ⇒ key dropped, [JsonNull] ⇒ literal `"timezone":null` on the
 * wire, [JsonPrimitive] ⇒ the id. That is what makes the clear real rather than a
 * silently-empty patch.
 */
fun quietHoursPatch(current: QuietHours, next: QuietHours): QuietHoursPatchDto? {
    val dto = QuietHoursPatchDto(
        enabled = next.enabled.takeIf { it != current.enabled },
        startMinute = next.startMinute.takeIf { it != current.startMinute },
        endMinute = next.endMinute.takeIf { it != current.endMinute },
        timezone = when {
            next.timezone == current.timezone -> null
            next.timezone == null -> JsonNull
            else -> JsonPrimitive(next.timezone)
        },
    )
    return dto.takeIf { it != QuietHoursPatchDto() }
}

// ── The time-zone picker list ────────────────────────────────────────────────

/**
 * Fixed-offset ids (`Etc/GMT+7`, `Etc/UTC`). Valid, and deliberately not offered:
 * see [timeZonePickerOptions].
 */
private const val FIXED_OFFSET_ZONE_PREFIX = "Etc/"

/** Obsolete System V ids carrying pre-1987 US daylight rules. See [timeZonePickerOptions]. */
private const val LEGACY_ZONE_PREFIX = "SystemV/"

/**
 * The zones the picker offers, mirroring the web's `timeZoneOptions()`
 * (`NotificationsPanel.tsx`) exactly: the runtime's canonical region zones, plus
 * the DETECTED zone and the CURRENTLY-SET one, deduped and sorted.
 *
 * ## Why [current] and [detected] are force-added
 *
 * This is the half of the web's function that matters and is easy to miss. The
 * account's stored zone may be one the runtime does not list — set from another
 * client, or on an older tz database. The web adds it unconditionally so the
 * dropdown can always show what is actually selected. Without that, the app's
 * picker would open on a zone it does not contain, with nothing highlighted, and
 * scrolling to "the current one" would be impossible.
 *
 * ## Why the two prefixes are still filtered
 *
 * The web reads `Intl.supportedValuesOf('timeZone')`, which is NOT the same list
 * as the JVM's `ZoneId.getAvailableZoneIds()`: ECMA-402 returns only CANONICAL
 * zone names, while the JVM returns every id including links and legacy families.
 * Probed on the dev stack's runtime, `supportedValuesOf` returns **418 zones,
 * zero of them `Etc/`-prefixed, zero `SystemV/`-prefixed, and every one containing
 * a `/`** — so dropping those three groups is not a bespoke app opinion, it is
 * what makes the JVM list EQUAL the list the web shows.
 *
 * That also answers the obvious objection, "but UTC?": the web does not offer a
 * bare UTC entry in the list either. It is the `<option value="">` above it, which
 * clears the zone — the same "UTC (no timezone set)" row this app pins on top,
 * wired to an explicit null (see [quietHoursPatch]). Nobody loses UTC; they just
 * reach it through the option that actually means it.
 */
fun timeZonePickerOptions(
    available: Iterable<String>,
    current: String?,
    detected: String?,
): List<String> {
    val zones = sortedSetOf<String>()
    if (detected != null) zones += detected
    available.filterTo(zones) {
        it.contains('/') &&
            !it.startsWith(FIXED_OFFSET_ZONE_PREFIX) &&
            !it.startsWith(LEGACY_ZONE_PREFIX)
    }
    if (current != null) zones += current
    return zones.toList()
}

// ── SharedPreferences codec for the cadence map (pure, so it is unit-tested) ────
// The tri-state matters: NO stored set ⇒ the server never modelled cadence ⇒ the
// Delivery section stays hidden on a cold open, exactly as on a fresh GET.

/** `{type: value}` → `{"type=value"}` for `SharedPreferences.putStringSet`. */
fun encodeCadence(cadence: Map<String, String>): Set<String> =
    cadence.map { (type, value) -> "$type=$value" }.toSet()

/** Inverse of [encodeCadence]; `null` in ⇒ `null` out (never modelled). */
fun decodeCadence(raw: Set<String>?): Map<String, String>? = raw?.mapNotNull { entry ->
    val split = entry.indexOf('=')
    if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
}?.toMap()
