package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

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
 */
fun formatApiBuiltAtDate(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        OffsetDateTime.parse(iso).toLocalDate().toString()
    } catch (_: Exception) {
        try {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } catch (_: Exception) {
            iso.take(10)
        }
    }
}
