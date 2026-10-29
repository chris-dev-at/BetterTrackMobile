package at.bettertrack.app.data.auth

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refresh failure matrix and its pauses, as pure rules. */
class RefreshPolicyTest {

    @Test
    fun `the failure matrix`() {
        assertEquals(RefreshVerdict.DEFINITIVE, refreshVerdictFor(400, "INVALID_GRANT"))
        assertEquals(RefreshVerdict.DEFINITIVE, refreshVerdictFor(400, "INVALID_SCOPE"))
        assertEquals(RefreshVerdict.RATE_LIMITED, refreshVerdictFor(429, "RATE_LIMITED"))
        assertEquals("rotated, body unreadable", RefreshVerdict.ANSWER_LOST, refreshVerdictFor(200, "EMPTY_RESPONSE"))
        listOf(500, 502, 503, 504, 520, 524).forEach {
            assertEquals("$it may come from behind the rotation", RefreshVerdict.ANSWER_LOST, refreshVerdictFor(it, "X"))
        }
        listOf(521, 522, 523, 525, 526, 530).forEach {
            assertEquals("$it never reached the origin", RefreshVerdict.REFUSED, refreshVerdictFor(it, "X"))
        }
        listOf(400 to "VALIDATION_ERROR", 400 to "INVALID_CLIENT", 401 to "API_KEY_INVALID", 403 to "FORBIDDEN", 404 to "NOT_FOUND")
            .forEach { (status, code) -> assertEquals("$status $code", RefreshVerdict.REFUSED, refreshVerdictFor(status, code)) }
    }

    @Test
    fun `Retry-After is read from the header in both forms, then from the envelope`() {
        val now = 1_700_000_000_000L
        assertEquals(20_000L, retryAfterMs("20", null, now))
        assertEquals(20_000L, retryAfterMs(" 20 ", null, now))
        val inTwoMinutes = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now + 120_000L).atZone(java.time.ZoneOffset.UTC))
        assertEquals(120_000L, retryAfterMs(inTwoMinutes, null, now))
        assertEquals(30_000L, retryAfterMs(null, buildJsonObject { put("retryAfter", 30) }, now))
        assertEquals("header wins", 5_000L, retryAfterMs("5", buildJsonObject { put("retryAfter", 30) }, now))
        assertNull(retryAfterMs("soon", null, now))
        assertNull(retryAfterMs(null, JsonPrimitive("x"), now))
        assertEquals("a date in the past is 'now'", 0L, retryAfterMs("Thu, 01 Jan 2015 00:00:00 GMT", null, now))
    }

    @Test
    fun `backoff is exponential, jittered, capped, and never shorter than Retry-After`() {
        assertEquals(15_000L, refreshBackoffMs(RefreshBackoffKind.UNREACHABLE, 1, random = 0.0))
        assertEquals(30_000L, refreshBackoffMs(RefreshBackoffKind.UNREACHABLE, 1, random = 0.5))
        assertEquals(60_000L, refreshBackoffMs(RefreshBackoffKind.UNREACHABLE, 2, random = 0.5))
        assertEquals("capped", RETRY_CAP_MS, refreshBackoffMs(RefreshBackoffKind.UNREACHABLE, 30, random = 0.9))
        assertEquals("replay starts at 2 s", 2_000L, refreshBackoffMs(RefreshBackoffKind.REPLAY, 1, random = 0.5))
        assertEquals("replay cap", REPLAY_CAP_MS, refreshBackoffMs(RefreshBackoffKind.REPLAY, 40, random = 0.9))
        assertEquals(
            "Retry-After is the floor",
            120_000L,
            refreshBackoffMs(RefreshBackoffKind.RATE_LIMITED, 1, random = 0.0, retryAfterMs = 120_000L),
        )
        assertEquals(
            "an absurd Retry-After is capped",
            RETRY_AFTER_CAP_MS,
            refreshBackoffMs(RefreshBackoffKind.RATE_LIMITED, 1, random = 0.0, retryAfterMs = 86_400_000L),
        )
    }

    @Test
    fun `who may refresh during which pause`() {
        fun b(kind: RefreshBackoffKind) = RefreshBackoff(kind, failures = 1, sinceMs = 1_000L, durationMs = 10_000L)
        val inside = 5_000L
        val after = 11_000L
        val all = RefreshCaller.entries

        // Unreachable: only the proactive and the resume paths wait.
        val unreachable = all.filter { refreshBlocked(b(RefreshBackoffKind.UNREACHABLE), it, inside) }.toSet()
        assertEquals(setOf(RefreshCaller.PROACTIVE, RefreshCaller.RESUME), unreachable)
        // Rate limited: everyone waits, a person included.
        assertTrue(all.all { refreshBlocked(b(RefreshBackoffKind.RATE_LIMITED), it, inside) })
        // Refused / replay schedule: everyone but a person.
        listOf(RefreshBackoffKind.REFUSED, RefreshBackoffKind.REPLAY).forEach { kind ->
            assertEquals(all.toSet() - RefreshCaller.MANUAL, all.filter { refreshBlocked(b(kind), it, inside) }.toSet())
        }
        // Every pause ends.
        RefreshBackoffKind.entries.forEach { kind -> assertFalse(all.any { refreshBlocked(b(kind), it, after) }) }
    }

    @Test
    fun `a fingerprint names a token without carrying it`() {
        val fp = refreshTokenFingerprint("btr_secret_token_value")
        assertEquals(16, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(fp.contains("secret"))
        assertEquals(fp, refreshTokenFingerprint("btr_secret_token_value"))
        assertFalse(fp == refreshTokenFingerprint("btr_secret_token_valuf"))
    }
}
