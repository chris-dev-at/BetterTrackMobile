package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.dto.BT_PASSKEY_NAME_MAX
import at.bettertrack.app.data.api.dto.PasskeyDto
import at.bettertrack.app.data.api.dto.RememberedDeviceDto
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

// ── Passkeys (from GET /auth/passkeys) ───────────────────────────────────────

/**
 * One registered passkey, timestamps already parsed.
 *
 * [lastUsedAtMs] is null for a passkey that has never completed a login — a real
 * state the contract models explicitly, not a parse failure, and the screen must
 * say "never used" rather than leave the clause blank.
 */
data class AccountPasskey(
    val id: String,
    val name: String,
    val createdAtMs: Long?,
    val lastUsedAtMs: Long?,
)

object PasskeyMapper {
    fun from(dto: PasskeyDto): AccountPasskey = AccountPasskey(
        id = dto.id,
        name = dto.name.trim(),
        createdAtMs = SessionMapper.parseIsoMs(dto.createdAt),
        lastUsedAtMs = SessionMapper.parseIsoMs(dto.lastUsedAt),
    )

    /**
     * Is [name] something the server will accept as a rename?
     *
     * `passkeyNameSchema` is `z.string().trim().min(1).max(64)`, so the trim
     * happens server-side too: a name of nothing but spaces is a 400, and the
     * length ceiling applies to the TRIMMED value. Both are checked here so the
     * Save button can be honest instead of the user learning it from a round
     * trip.
     */
    fun isValidName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed.length <= BT_PASSKEY_NAME_MAX
    }
}

// ── Remembered devices (from GET /auth/remembered-devices) ───────────────────

/**
 * One remembered-device binding — a BROWSER that may skip the sign-in step.
 *
 * [handle] is an opaque base64url digest. It is the revocation token and nothing
 * else: it is never rendered, because a 43-character hash tells a human nothing
 * and would read as a device name.
 */
data class RememberedDevice(
    val handle: String,
    val createdAtMs: Long?,
    val lastSeenAtMs: Long?,
    val expiresAtMs: Long?,
)

/**
 * One phrase of a remembered device's label.
 *
 * The row title has to be BUILT, because the only identifying facts a binding
 * carries are its timestamps and every one of them can be absent (bindings
 * created before the metadata columns existed carry no history at all). A clause
 * list keeps the "drop what is null" rule pure and testable, and leaves the
 * date formatting — which is locale work — to the composable.
 */
sealed interface RememberedDeviceClause {
    /** "Remembered <date>". */
    data class Remembered(val epochMs: Long) : RememberedDeviceClause

    /** "last seen <date>". */
    data class LastSeen(val epochMs: Long) : RememberedDeviceClause

    /** "expires <date>". */
    data class Expires(val epochMs: Long) : RememberedDeviceClause
}

object RememberedDeviceMapper {
    fun from(dto: RememberedDeviceDto): RememberedDevice = RememberedDevice(
        handle = dto.handle,
        createdAtMs = SessionMapper.parseIsoMs(dto.createdAt),
        lastSeenAtMs = SessionMapper.parseIsoMs(dto.lastSeenAt),
        expiresAtMs = SessionMapper.parseIsoMs(dto.expiresAt),
    )

    /**
     * The clauses this binding can actually support, in render order. Empty when
     * the binding carries no timestamp at all — the screen then falls back to a
     * generic name, which is still better than printing the digest.
     */
    fun clauses(device: RememberedDevice): List<RememberedDeviceClause> = buildList {
        device.createdAtMs?.let { add(RememberedDeviceClause.Remembered(it)) }
        device.lastSeenAtMs?.let { add(RememberedDeviceClause.LastSeen(it)) }
        device.expiresAtMs?.let { add(RememberedDeviceClause.Expires(it)) }
    }

    /**
     * Did a revoke actually take effect?
     *
     * The API is idempotent — unknown, expired and foreign handles all answer
     * 200 — so "Forgotten." must be decided by the RE-READ, never by the status
     * code. [after] is the freshly fetched list.
     */
    fun wasForgotten(handle: String, after: List<RememberedDevice>): Boolean =
        after.none { it.handle == handle }
}

/**
 * How the two account-security lists join the phrases that make up a row's
 * subline.
 *
 * Both new screens build their label out of clauses that may each be absent — a
 * passkey that has never been used, a remembered device from before the metadata
 * columns existed — so "drop the empty ones and separate the rest" is a rule
 * shared by both and worth having in exactly one place. The separator is the
 * middle dot the rest of the app already uses between facts on one line.
 */
object SecurityLabel {
    const val SEPARATOR: String = " · "

    /** Join already-localized clause phrases, dropping the blanks. */
    fun join(parts: List<String>): String =
        parts.filter { it.isNotBlank() }.joinToString(SEPARATOR)
}
