package at.bettertrack.app.data.api.dto

import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Public running-build info of the live server — `GET /api/v1/version` (no auth).
 * Wired into the About screen as a cosmetic "API build" row. All three fields are
 * non-empty strings in the contract; decoded tolerantly (defaults) so a shape
 * change never crashes this purely-cosmetic surface.
 */
@Serializable
data class VersionResponse(
    val commit: String = "",
    val shortCommit: String = "",
    /** ISO-8601 build timestamp. */
    val builtAt: String = "",
)

/**
 * Format the build timestamp down to a plain calendar date for the About row.
 * Fail-soft: an unparseable value falls back to its first 10 chars (the `yyyy-MM-dd`
 * prefix of any ISO string) so the row never shows an error for cosmetic info.
 *
 * Ported java.time -> kotlinx-datetime (KMP, Phase 2) preserving exact semantics:
 *  - OffsetDateTime.parse(iso).toLocalDate()  ->  ISO_DATE_TIME_OFFSET.parse(iso)
 *    .toLocalDate(): the calendar date AS WRITTEN in the offset, no zone shift.
 *  - Instant.parse(iso).atZone(systemDefault()).toLocalDate()  ->  Instant.parse(iso)
 *    .toLocalDateTime(TimeZone.currentSystemDefault()).date: the instant projected
 *    into the system zone, then its date.
 * kotlinx throws IllegalArgumentException where java.time threw DateTimeParseException;
 * both are subclasses of Exception, which the existing (already broad) catches cover,
 * so the fail-soft fallback chain is byte-for-byte unchanged.
 */
fun formatApiBuiltAtDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(iso).toLocalDate().toString()
    } catch (_: Exception) {
        try {
            Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        } catch (_: Exception) {
            iso.take(10)
        }
    }
}
