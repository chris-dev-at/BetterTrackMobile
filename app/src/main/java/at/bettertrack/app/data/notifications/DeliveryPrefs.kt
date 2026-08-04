package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.QuietHoursDto

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

/**
 * Which of the 25 platform notification types may be batched into a digest.
 *
 * The split is editorial, not server-driven: batching only makes sense for
 * non-urgent, informational events. Anything the user is expected to ACT on, or
 * that is time-critical or security-relevant, stays on `instant` and is never
 * offered a cadence — the settings copy says so out loud rather than quietly
 * delaying a security code.
 */
object DeliveryTypes {

    /** Urgent / transactional / actionable — never batched, never offered a cadence. */
    val alwaysInstant: Set<String> = setOf(
        "alert.triggered",          // your own price alert fired — time-critical
        "account.temp_password",    // security code
        "account.invite",           // actionable invitation
        "account.data_export",      // one-off, user-initiated, expires
        "chat.message",             // a conversation, not a report
        "mirror.invite",            // actionable invitation
        "mirror.sync_stalled",      // something is broken and needs attention
    )

    /**
     * The digestible group, in wire order. A cadence change PATCHes exactly these
     * keys — intersected with the types the last GET actually carried.
     */
    val digestible: List<String> = listOf(
        "friend.request",
        "friend.accepted",
        "portfolio.shared",
        "watchlist.shared",
        "conglomerate.shared",
        "friend.activity",
        "follow.published",
        "follow.alert.created",
        "follow.alert.fired",
        "earnings.reminder",
        "dividend.event",
        "budget.exceeded",
        "mirror.member_joined",
        "mirror.member_left",
        "mirror.member_removed",
        "mirror.removed",
        "mirror.ownership_transferred",
        "mirror.chain_dissolved",
    )
}

/** Server default: 22:00 → 07:00, an overnight window. */
const val QUIET_HOURS_DEFAULT_START = 1320
const val QUIET_HOURS_DEFAULT_END = 420

/**
 * The quiet-hours window as the app holds it. [timezone] is `null` when the server
 * has none stored (it then falls back to UTC) — the UI offers the device zone as
 * the fill-in, but the app only ever SENDS a zone the user actually confirmed.
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
    /** Whether to render the Delivery section at all. */
    val supported: Boolean get() = cadence != null || quietHours != null
}

/** Decode a server quiet-hours object; absent fields fall back to the server defaults. */
fun QuietHoursDto.toQuietHours(): QuietHours = QuietHours(
    enabled = enabled ?: false,
    startMinute = startMinute ?: QUIET_HOURS_DEFAULT_START,
    endMinute = endMinute ?: QUIET_HOURS_DEFAULT_END,
    timezone = timezone,
)

/**
 * The cadence shown on the segmented chooser: the single value shared by every
 * digestible type the server carried, or `null` when the group is MIXED (the web
 * can set types individually) or when the server modelled no cadence at all. A
 * `null` renders as "mixed" rather than a lie about which segment is active.
 */
fun groupCadence(serverCadence: Map<String, String>?): DigestCadence? {
    if (serverCadence == null) return null
    val values = DeliveryTypes.digestible.mapNotNull { serverCadence[it] }.distinct()
    return if (values.size == 1) DigestCadence.fromWire(values.single()) else null
}

/**
 * The `cadence` map for a group cadence change: every digestible type the last GET
 * carried whose value actually differs. Returns `null` when nothing changes (an
 * empty `{}` PATCH body is a 400) or when the server modelled no cadence.
 *
 * Types outside [DeliveryTypes.digestible] are never touched, so the urgent ones
 * keep whatever the server has (they ship `instant`).
 */
fun cadencePatch(serverCadence: Map<String, String>?, choice: DigestCadence): Map<String, String>? {
    if (serverCadence == null) return null
    val changed = DeliveryTypes.digestible
        .filter { serverCadence.containsKey(it) && serverCadence[it] != choice.wire }
        .associateWith { choice.wire }
    return changed.ifEmpty { null }
}

/**
 * The field-partial `quietHours` object for a change: ONLY the fields that differ.
 * Returns `null` when nothing differs. [timezone] is never diffed down to `null` —
 * the app can set a zone but never clears one it did not set (and an explicit null
 * would be dropped by `explicitNulls = false` anyway).
 */
fun quietHoursPatch(current: QuietHours, next: QuietHours): QuietHoursDto? {
    val dto = QuietHoursDto(
        enabled = next.enabled.takeIf { it != current.enabled },
        startMinute = next.startMinute.takeIf { it != current.startMinute },
        endMinute = next.endMinute.takeIf { it != current.endMinute },
        timezone = next.timezone?.takeIf { it != current.timezone },
    )
    return dto.takeIf { it != QuietHoursDto() }
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
