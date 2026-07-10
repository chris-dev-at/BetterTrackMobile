package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.dto.SessionSummaryDto
import java.time.Instant

/**
 * Domain models + PURE mapping/validation logic for Settings → Account & Security
 * (spec §6.12). Kept free of Android/Compose so it is exhaustively unit-testable
 * (password policy, session recency, device labels) — the screens are thin over it.
 */

// ── Password policy (mirrors @bettertrack/contracts passwordSchema) ──────────
// Length floor 8, ceiling 200; the server additionally enforces a blocklist. The
// client validates length + confirmation and offers a strength HINT only (never a
// hard gate beyond the server's own rules — the server is the authority).
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 200

    enum class Strength { EMPTY, WEAK, FAIR, GOOD, STRONG }

    /** Why a new-password form can't be submitted yet (null = OK to submit). */
    enum class Error { CURRENT_EMPTY, TOO_SHORT, TOO_LONG, MISMATCH, SAME_AS_CURRENT }

    /**
     * A coarse strength bucket from length + character-class variety. Purely a UI
     * hint; the ceiling for "strong" is deliberately reachable so the meter feels
     * honest rather than nagging.
     */
    fun strength(password: String): Strength {
        if (password.isEmpty()) return Strength.EMPTY
        var classes = 0
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        return when {
            password.length < MIN_LENGTH -> Strength.WEAK
            password.length >= 12 && classes >= 3 -> Strength.STRONG
            password.length >= 10 && classes >= 2 -> Strength.GOOD
            classes >= 2 -> Strength.FAIR
            else -> Strength.WEAK
        }
    }

    /**
     * Validate a voluntary change: current must be present, the new password must
     * satisfy the length bounds, match its confirmation, and differ from current.
     * Returns the first blocking [Error], or null when the form may submit.
     */
    fun validateChange(current: String, new: String, confirm: String): Error? = when {
        current.isEmpty() -> Error.CURRENT_EMPTY
        new.length < MIN_LENGTH -> Error.TOO_SHORT
        new.length > MAX_LENGTH -> Error.TOO_LONG
        new != confirm -> Error.MISMATCH
        new == current -> Error.SAME_AS_CURRENT
        else -> null
    }
}

// ── Two-factor state (from GET /auth/2fa/status) ─────────────────────────────
data class TwoFactorState(
    val totpEnabled: Boolean,
    val totpPending: Boolean,
    val emailEnabled: Boolean,
    val recoveryCodesRemaining: Int,
) {
    /** Any method on ⇒ 2FA challenges login. */
    val anyEnabled: Boolean get() = totpEnabled || emailEnabled
}

/** The one-time TOTP enrollment payload (secret + otpauth URI for the QR). */
data class TwoFactorEnrollment(val otpauthUri: String, val secret: String) {
    /**
     * The base32 secret grouped in 4s for readable manual entry
     * (e.g. `JBSW Y3DP EHPK 3PXP`).
     */
    fun formattedSecret(): String = secret.chunked(4).joinToString(" ")
}

// ── Active sessions (from GET /auth/sessions) ────────────────────────────────
data class AccountSession(
    val id: String,
    val deviceLabel: String,
    val createdAtMs: Long?,
    val lastSeenAtMs: Long?,
    val current: Boolean,
)

/** How recently a session was last seen — a UI-agnostic bucket the screen labels. */
sealed interface SessionRecency {
    data object JustNow : SessionRecency
    data class MinutesAgo(val minutes: Int) : SessionRecency
    data class HoursAgo(val hours: Int) : SessionRecency
    data class DaysAgo(val days: Int) : SessionRecency
    /** Older than a week — show the absolute date instead. */
    data class OnDate(val epochMs: Long) : SessionRecency
    /** No timestamp available. */
    data object Unknown : SessionRecency
}

object SessionMapper {
    /** Parse an ISO-8601 instant (e.g. `2026-07-09T23:11:37.371Z`) to epoch ms, or null. */
    fun parseIsoMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** A safe device label — the server's parsed User-Agent, or a neutral fallback. */
    fun deviceLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { "Unknown device" }
    }

    fun from(dto: SessionSummaryDto): AccountSession = AccountSession(
        id = dto.id,
        deviceLabel = deviceLabel(dto.device),
        createdAtMs = parseIsoMs(dto.createdAt),
        lastSeenAtMs = parseIsoMs(dto.lastSeenAt),
        current = dto.current,
    )

    /**
     * Bucket "last seen" relative to now. < 1 min = JustNow; < 1 h = minutes;
     * < 24 h = hours; < 7 d = days; else the absolute date. A future/None stamp
     * degrades gracefully.
     */
    fun recency(lastSeenMs: Long?, nowMs: Long): SessionRecency {
        if (lastSeenMs == null) return SessionRecency.Unknown
        val delta = nowMs - lastSeenMs
        if (delta < 0) return SessionRecency.JustNow
        val minutes = delta / 60_000L
        val hours = delta / 3_600_000L
        val days = delta / 86_400_000L
        return when {
            minutes < 1 -> SessionRecency.JustNow
            minutes < 60 -> SessionRecency.MinutesAgo(minutes.toInt())
            hours < 24 -> SessionRecency.HoursAgo(hours.toInt())
            days < 7 -> SessionRecency.DaysAgo(days.toInt())
            else -> SessionRecency.OnDate(lastSeenMs)
        }
    }
}
