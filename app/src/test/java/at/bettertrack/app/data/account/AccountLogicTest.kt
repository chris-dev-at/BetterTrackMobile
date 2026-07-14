package at.bettertrack.app.data.account

import at.bettertrack.app.data.api.dto.SessionSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the Step-18 account/security layer (spec §6.12). */
class AccountLogicTest {

    // ── PasswordPolicy.validateChange ────────────────────────────────────────
    @Test fun change_requires_current() {
        assertEquals(
            PasswordPolicy.Error.CURRENT_EMPTY,
            PasswordPolicy.validateChange(current = "", new = "abcdefgh1", confirm = "abcdefgh1"),
        )
    }

    @Test fun change_rejects_too_short() {
        assertEquals(
            PasswordPolicy.Error.TOO_SHORT,
            PasswordPolicy.validateChange(current = "oldpass1", new = "short", confirm = "short"),
        )
    }

    @Test fun change_rejects_too_long() {
        val long = "a".repeat(PasswordPolicy.MAX_LENGTH + 1)
        assertEquals(
            PasswordPolicy.Error.TOO_LONG,
            PasswordPolicy.validateChange(current = "oldpass1", new = long, confirm = long),
        )
    }

    @Test fun change_rejects_mismatch() {
        assertEquals(
            PasswordPolicy.Error.MISMATCH,
            PasswordPolicy.validateChange(current = "oldpass1", new = "newpass12", confirm = "newpass99"),
        )
    }

    @Test fun change_rejects_same_as_current() {
        assertEquals(
            PasswordPolicy.Error.SAME_AS_CURRENT,
            PasswordPolicy.validateChange(current = "samepass1", new = "samepass1", confirm = "samepass1"),
        )
    }

    @Test fun change_accepts_valid() {
        assertNull(PasswordPolicy.validateChange(current = "oldpass1", new = "Brand-New9", confirm = "Brand-New9"))
    }

    @Test fun boundary_exactly_min_length_ok() {
        val eight = "abcdEf12" // 8 chars
        assertEquals(PasswordPolicy.MIN_LENGTH, eight.length)
        assertNull(PasswordPolicy.validateChange(current = "different", new = eight, confirm = eight))
    }

    // ── PasswordPolicy.strength ──────────────────────────────────────────────
    @Test fun strength_empty() {
        assertEquals(PasswordPolicy.Strength.EMPTY, PasswordPolicy.strength(""))
    }

    @Test fun strength_short_is_weak() {
        assertEquals(PasswordPolicy.Strength.WEAK, PasswordPolicy.strength("ab1"))
    }

    @Test fun strength_long_mixed_is_strong() {
        assertEquals(PasswordPolicy.Strength.STRONG, PasswordPolicy.strength("Sup3r-Secret!Pass"))
    }

    @Test fun strength_medium_is_good() {
        // 10+ chars, two classes (lower + digit), no symbol/upper -> GOOD
        assertEquals(PasswordPolicy.Strength.GOOD, PasswordPolicy.strength("abcdefgh12"))
    }

    // ── SessionMapper.parseIsoMs ─────────────────────────────────────────────
    @Test fun parses_iso_epoch_anchors() {
        assertEquals(0L, SessionMapper.parseIsoMs("1970-01-01T00:00:00.000Z"))
        assertEquals(1_000L, SessionMapper.parseIsoMs("1970-01-01T00:00:01.000Z"))
        // Millisecond precision preserved.
        assertEquals(371L, SessionMapper.parseIsoMs("1970-01-01T00:00:00.371Z"))
        // A one-second gap between two real-shaped stamps is exactly 1000ms.
        val a = SessionMapper.parseIsoMs("2026-07-09T23:11:37.371Z")!!
        val b = SessionMapper.parseIsoMs("2026-07-09T23:11:36.371Z")!!
        assertEquals(1_000L, a - b)
    }

    @Test fun bad_iso_is_null() {
        assertNull(SessionMapper.parseIsoMs("not-a-date"))
        assertNull(SessionMapper.parseIsoMs(null))
        assertNull(SessionMapper.parseIsoMs(""))
    }

    // ── SessionMapper.deviceLabel ────────────────────────────────────────────
    @Test fun device_label_falls_back() {
        assertEquals("Unknown device", SessionMapper.deviceLabel(""))
        assertEquals("Unknown device", SessionMapper.deviceLabel("   "))
        assertEquals("Unknown device", SessionMapper.deviceLabel(null))
        assertEquals("Chrome on Windows", SessionMapper.deviceLabel("Chrome on Windows"))
    }

    // ── SessionMapper.from ───────────────────────────────────────────────────
    @Test fun maps_dto_to_domain() {
        val s = SessionMapper.from(
            SessionSummaryDto(
                id = "handle123",
                device = "Chrome on Android",
                createdAt = "2026-07-09T22:00:00.000Z",
                lastSeenAt = "2026-07-09T23:11:37.371Z",
                current = true,
            ),
        )
        assertEquals("handle123", s.id)
        assertEquals("Chrome on Android", s.deviceLabel)
        assertTrue(s.current)
        assertEquals(SessionMapper.parseIsoMs("2026-07-09T23:11:37.371Z"), s.lastSeenAtMs)
    }

    // ── SessionMapper.recency ────────────────────────────────────────────────
    @Test fun recency_buckets() {
        val now = 1_000_000_000_000L
        assertTrue(SessionMapper.recency(now, now) is SessionRecency.JustNow)
        assertEquals(
            SessionRecency.MinutesAgo(5),
            SessionMapper.recency(now - 5 * 60_000L, now),
        )
        assertEquals(
            SessionRecency.HoursAgo(3),
            SessionMapper.recency(now - 3 * 3_600_000L, now),
        )
        assertEquals(
            SessionRecency.DaysAgo(2),
            SessionMapper.recency(now - 2 * 86_400_000L, now),
        )
        assertTrue(SessionMapper.recency(now - 30L * 86_400_000L, now) is SessionRecency.OnDate)
        assertTrue(SessionMapper.recency(null, now) is SessionRecency.Unknown)
        // Future stamp degrades to JustNow (never negative).
        assertTrue(SessionMapper.recency(now + 60_000L, now) is SessionRecency.JustNow)
    }

    // ── TwoFactorEnrollment.formattedSecret ──────────────────────────────────
    @Test fun secret_is_grouped_in_fours() {
        assertEquals("JBSW Y3DP EHPK 3PXP", TwoFactorEnrollment("otpauth://x", "JBSWY3DPEHPK3PXP").formattedSecret())
    }

    @Test fun two_factor_any_enabled() {
        assertTrue(TwoFactorState(totpEnabled = true, totpPending = false, emailEnabled = false, recoveryCodesRemaining = 8).anyEnabled)
        assertTrue(TwoFactorState(totpEnabled = false, totpPending = false, emailEnabled = true, recoveryCodesRemaining = 8).anyEnabled)
        // Pending-only is NOT enabled (2FA not armed).
        assertTrue(!TwoFactorState(totpEnabled = false, totpPending = true, emailEnabled = false, recoveryCodesRemaining = 0).anyEnabled)
    }

    // ── Delete-account gate ──────────────────────────────────────────────────
    @Test fun delete_account_ships_armed() {
        // Play mandates a working in-app deletion path (Step 20/Task B3), so the
        // feature ships ARMED. The real product safety is the deletion screen's
        // type-to-confirm + password re-auth, not this flag.
        assertTrue(DeleteAccountFeature.armed)
    }
}
