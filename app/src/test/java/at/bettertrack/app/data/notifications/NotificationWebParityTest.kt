package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.NotificationSettingsResponse
import at.bettertrack.app.data.api.dto.QuietHoursDto
import at.bettertrack.app.data.api.dto.QuietHoursPatchDto
import at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web-parity pass on notification settings (2026-08-08): the account-wide
 * mute, the explicit-null time zone, and the two "send nothing" guards.
 *
 * Every wire assertion here is a LITERAL byte comparison against the app's real
 * Json configuration, because both new behaviours are exactly the kind that pass
 * review and fail silently in production: a key that is quietly dropped, and a
 * key that is quietly invented.
 */
class NotificationWebParityTest {

    // Mirror AppGraph.json exactly.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** JUnit4's assertNotNull returns void; this one narrows the type so the test can go on. */
    private fun <T : Any> T?.orFail(what: String): T = this ?: throw AssertionError("expected a $what, got null")

    private val withZone = QuietHours(
        enabled = true,
        startMinute = 1320,
        endMinute = 420,
        timezone = "Europe/Vienna",
    )

    // ── The explicitNulls trap, and the fix ─────────────────────────────────────

    /**
     * The trap this whole DTO split exists for. If [QuietHoursPatchDto.timezone]
     * were a `String?` — as the READ shape [QuietHoursDto] still is — then asking
     * to clear the zone would produce a body with NO timezone key, i.e. a no-op the
     * user would read as a saved change. This test pins the broken behaviour on the
     * String-typed DTO so the reason for the JsonElement is not lost.
     */
    @Test
    fun `a String-typed null timezone is silently DROPPED by explicitNulls`() {
        assertEquals("{}", json.encodeToString(QuietHoursDto(timezone = null)))
        assertFalse(json.encodeToString(QuietHoursDto(enabled = true, timezone = null)).contains("timezone"))
    }

    @Test
    fun `clearing the time zone reaches the wire as an EXPLICIT json null`() {
        val patch = quietHoursPatch(withZone, withZone.copy(timezone = null)).orFail("quiet-hours patch")
        val body = json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch))
        assertEquals("""{"quietHours":{"timezone":null}}""", body)
        // Said twice on purpose: the key must be PRESENT and its value must be null.
        assertTrue(body.contains("\"timezone\""))
        assertTrue(body.contains("\"timezone\":null"))
    }

    @Test
    fun `picking a zone sends the id, and re-picking the same one sends nothing`() {
        val patch = quietHoursPatch(withZone.copy(timezone = null), withZone.copy(timezone = "Etc/UTC"))
            .orFail("quiet-hours patch")
        assertEquals(
            """{"quietHours":{"timezone":"Etc/UTC"}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch)),
        )
        assertNull(quietHoursPatch(withZone, withZone.copy(timezone = "Europe/Vienna")))
    }

    /**
     * The auto-injection that was removed: enabling the window must patch `enabled`
     * and NOTHING else, whatever zone state the account is in.
     */
    @Test
    fun `enabling quiet hours never smuggles a time zone into the patch`() {
        val off = QuietHours(enabled = false, startMinute = 1320, endMinute = 420, timezone = null)
        val patch = quietHoursPatch(off, off.copy(enabled = true)).orFail("quiet-hours patch")
        val body = json.encodeToString(UpdateNotificationSettingsRequest(quietHours = patch))
        assertEquals("""{"quietHours":{"enabled":true}}""", body)
        assertFalse(body.contains("timezone"))
    }

    // ── Account-wide mute: the wire shape ───────────────────────────────────────

    @Test
    fun `muting the account sends exactly the muted key and nothing else`() {
        assertEquals("""{"muted":true}""", json.encodeToString(UpdateNotificationSettingsRequest(muted = true)))
        assertEquals("""{"muted":false}""", json.encodeToString(UpdateNotificationSettingsRequest(muted = false)))
        // A mute patch must never restate settings it did not change.
        val body = json.encodeToString(UpdateNotificationSettingsRequest(muted = true))
        assertFalse(body.contains("matrix"))
        assertFalse(body.contains("cadence"))
        assertFalse(body.contains("quietHours"))
    }

    @Test
    fun `a GET that carries muted decodes it and one that omits it leaves the tri-state null`() {
        val v5 = """
            {"matrix":{"friend.request":{"inapp":true,"email":true,"push":true,"webpush":true}},
             "muted":true,
             "channels":{"inapp":true,"email":true,"push":true,"webpush":true},
             "webPushPublicKey":null}
        """.trimIndent()
        assertEquals(true, json.decodeFromString<NotificationSettingsResponse>(v5).muted)

        // No `muted` key at all ⇒ null ⇒ the app renders no switch (never a false).
        val without = """{"matrix":{"friend.request":{"inapp":true,"email":true,"push":true,"webpush":true}}}"""
        assertNull(json.decodeFromString<NotificationSettingsResponse>(without).muted)
    }

    // ── Account-wide mute: the two "send nothing" guards ────────────────────────

    @Test
    fun `the mute patch is never invented on a server that did not send the key`() {
        // `current == null` is "this deployment has no account mute" — sending the
        // key would be an unknown property against a .strict() schema.
        assertNull(accountMutePatch(current = null, next = true))
        assertNull(accountMutePatch(current = null, next = false))
    }

    @Test
    fun `a no-op mute flip produces no patch at all`() {
        // An empty `{}` body is itself a 400.
        assertNull(accountMutePatch(current = true, next = true))
        assertNull(accountMutePatch(current = false, next = false))
        assertEquals(true, accountMutePatch(current = false, next = true))
        assertEquals(false, accountMutePatch(current = true, next = false))
    }

    // ── The time-zone option list (mirrors the web's timeZoneOptions) ──────────

    /** A JVM-shaped id list: canonical region zones plus the families Intl omits. */
    private val jvmZones = listOf(
        "Europe/Vienna", "America/New_York", "Australia/Sydney",
        "Etc/UTC", "Etc/GMT+7", "SystemV/EST5", "UTC", "CET", "PST8PDT", "Zulu",
    )

    @Test
    fun `the option list is the canonical region zones, sorted and deduped`() {
        val options = timeZonePickerOptions(jvmZones, current = null, detected = "Europe/Vienna")
        assertEquals(listOf("America/New_York", "Australia/Sydney", "Europe/Vienna"), options)
        // The three groups `Intl.supportedValuesOf('timeZone')` does not return.
        assertFalse(options.any { it.startsWith("Etc/") })
        assertFalse(options.any { it.startsWith("SystemV/") })
        assertFalse(options.any { !it.contains('/') })
        // Detected appears once even though it is also in the available list.
        assertEquals(1, options.count { it == "Europe/Vienna" })
    }

    /**
     * The half of the web's function that matters most: a stored zone the runtime
     * would not otherwise list is force-added, so the picker can always show what
     * is actually selected rather than opening with nothing highlighted.
     */
    @Test
    fun `a currently-set zone is always offered, even one the filter would drop`() {
        val options = timeZonePickerOptions(jvmZones, current = "Etc/GMT+7", detected = "Europe/Vienna")
        assertTrue("the selected zone must be visible in its own picker", "Etc/GMT+7" in options)
        // …and it is the ONLY member of that family that gets in.
        assertEquals(listOf("Etc/GMT+7"), options.filter { it.startsWith("Etc/") })
    }

    @Test
    fun `the detected zone is offered even when the runtime list is empty`() {
        assertEquals(
            listOf("Pacific/Auckland"),
            timeZonePickerOptions(emptyList(), current = null, detected = "Pacific/Auckland"),
        )
    }

}
