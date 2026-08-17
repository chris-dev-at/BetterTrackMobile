package at.bettertrack.app.data.notifications

import at.bettertrack.app.R
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.dto.TelegramSettingsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The pure half of the Telegram + Discord channel setup.
 *
 * Four rules carry this feature, and every one of them is a rule the UI would
 * otherwise re-invent (probably differently) at each call site:
 *
 *  1. the Discord webhook URL schema, mirrored from the server so a bad paste
 *     costs no round trip;
 *  2. the `t.me` deep link, which has exactly one correct spelling;
 *  3. the ten-minute life of a link code;
 *  4. which of three sentences a refused webhook save is answered with.
 *
 * Plus the merge that keeps the code alive across a refetch — the single trap
 * that makes this feature harder than it looks.
 */
class ChannelSetupTest {

    // ── 1. Discord webhook URL — the client's mirror of the server schema ──────

    @Test
    fun `all four discord hosts are accepted`() {
        DISCORD_WEBHOOK_HOSTS.forEach { host ->
            assertTrue(host, isDiscordWebhookUrl("https://$host/api/webhooks/123456789/abcdefTOKEN"))
        }
        // And the set is exactly the server's, so a fifth host cannot creep in
        // here without somebody noticing it is not in the platform schema.
        assertEquals(
            setOf("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com"),
            DISCORD_WEBHOOK_HOSTS,
        )
    }

    @Test
    fun `http is refused — the schema is https-only`() {
        assertFalse(isDiscordWebhookUrl("http://discord.com/api/webhooks/123/abc"))
    }

    @Test
    fun `a host outside the discord family is refused`() {
        assertFalse(isDiscordWebhookUrl("https://hooks.slack.com/api/webhooks/123/abc"))
        assertFalse(isDiscordWebhookUrl("https://example.com/api/webhooks/123/abc"))
        // The lookalike that a naive `contains` check would wave through.
        assertFalse(isDiscordWebhookUrl("https://discord.com.evil.test/api/webhooks/123/abc"))
        assertFalse(isDiscordWebhookUrl("https://notdiscord.com/api/webhooks/123/abc"))
    }

    @Test
    fun `a path outside api webhooks is refused`() {
        assertFalse(isDiscordWebhookUrl("https://discord.com/api/webhook/123/abc"))
        assertFalse(isDiscordWebhookUrl("https://discord.com/channels/123/abc"))
        assertFalse(isDiscordWebhookUrl("https://discord.com/"))
    }

    @Test
    fun `length outside 1 to 2048 is refused`() {
        assertFalse("empty", isDiscordWebhookUrl(""))
        assertFalse("blank", isDiscordWebhookUrl("   "))
        val head = "https://discord.com/api/webhooks/1/"
        val atCap = head + "t".repeat(DISCORD_WEBHOOK_URL_MAX - head.length)
        assertEquals(DISCORD_WEBHOOK_URL_MAX, atCap.length)
        assertTrue("exactly at the cap must pass", isDiscordWebhookUrl(atCap))
        assertFalse("one over the cap must fail", isDiscordWebhookUrl(atCap + "t"))
    }

    @Test
    fun `surrounding whitespace is tolerated because the caller submits the trim`() {
        // The repository sends `url.trim()`, so validating the untrimmed string
        // would refuse URLs that are about to be sent successfully.
        assertTrue(isDiscordWebhookUrl("  https://discord.com/api/webhooks/1/tok \n"))
    }

    @Test
    fun `garbage that is not a URL at all is refused rather than thrown`() {
        assertFalse(isDiscordWebhookUrl("not a url"))
        assertFalse(isDiscordWebhookUrl("https://"))
        assertFalse(isDiscordWebhookUrl("discord.com/api/webhooks/1/tok"))
    }

    // ── 2. The bot deep link ──────────────────────────────────────────────────

    @Test
    fun `the deep link has exactly one spelling`() {
        assertEquals(
            "https://t.me/BetterTrackBot?start=ABC123",
            telegramDeepLink("BetterTrackBot", "ABC123"),
        )
    }

    @Test
    fun `the deep link is null when either half is missing`() {
        assertNull("no bot", telegramDeepLink(null, "ABC123"))
        // The normal state after a refetch: a GET never returns the code.
        assertNull("no code", telegramDeepLink("BetterTrackBot", null))
        assertNull("neither", telegramDeepLink(null, null))
        // Blank counts as missing — `https://t.me/?start=X` is Telegram's home page.
        assertNull("blank bot", telegramDeepLink("  ", "ABC123"))
        assertNull("blank code", telegramDeepLink("BetterTrackBot", ""))
    }

    // ── 3. The ten-minute code ────────────────────────────────────────────────

    @Test
    fun `a code is alive until its deadline and dead after it`() {
        val now = 1_000_000L
        assertTrue("one ms to go", isPendingCodeAlive(now + 1, now))
        assertFalse("exactly at the deadline", isPendingCodeAlive(now, now))
        assertFalse("past it", isPendingCodeAlive(now - 1, now))
    }

    @Test
    fun `an unknown deadline reads as alive`() {
        // The server did not say, or said something unparseable. Refusing to show a
        // code on that basis would break the flow over a missing hint; the confirm
        // call is the authority either way.
        assertTrue(isPendingCodeAlive(null, Long.MAX_VALUE))
    }

    @Test
    fun `pendingExpiresAt parses as an ISO instant and survives nonsense`() {
        assertEquals(
            Instant.parse("2026-08-17T10:20:30Z").toEpochMilli(),
            parseIsoMillis("2026-08-17T10:20:30Z"),
        )
        assertNull(parseIsoMillis(null))
        assertNull(parseIsoMillis(""))
        assertNull(parseIsoMillis("in ten minutes"))
    }

    // ── The trap: a code the wire cannot re-send ──────────────────────────────

    @Test
    fun `the link POST is the only place a code comes from`() {
        val fresh = mergeTelegramState(
            previous = TelegramState(),
            dto = TelegramSettingsDto(
                available = true,
                pending = true,
                botUsername = "BetterTrackBot",
                pendingCode = "ABC123",
                pendingExpiresAt = "2026-08-17T10:10:00Z",
            ),
            nowMs = Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(),
        )
        assertEquals("ABC123", fresh.pendingCode)
        assertEquals(ChannelPhase.Ready, fresh.phase)
        assertNotNull(telegramDeepLink(fresh.botUsername, fresh.pendingCode))
    }

    @Test
    fun `a refetch keeps the held code — the GET cannot re-issue it`() {
        val afterLink = mergeTelegramState(
            TelegramState(),
            TelegramSettingsDto(
                available = true,
                pending = true,
                botUsername = "BetterTrackBot",
                pendingCode = "ABC123",
                pendingExpiresAt = "2026-08-17T10:10:00Z",
            ),
            Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(),
        )
        // Exactly what a plain GET answers: still pending, code null, no deadline.
        val afterGet = mergeTelegramState(
            afterLink,
            TelegramSettingsDto(available = true, pending = true, botUsername = "BetterTrackBot"),
            Instant.parse("2026-08-17T10:05:00Z").toEpochMilli(),
        )
        assertEquals("ABC123", afterGet.pendingCode)
        assertEquals(
            "https://t.me/BetterTrackBot?start=ABC123",
            telegramDeepLink(afterGet.botUsername, afterGet.pendingCode),
        )
    }

    @Test
    fun `an expired held code is dropped on the next merge`() {
        val afterLink = mergeTelegramState(
            TelegramState(),
            TelegramSettingsDto(
                available = true,
                pending = true,
                botUsername = "BetterTrackBot",
                pendingCode = "ABC123",
                pendingExpiresAt = "2026-08-17T10:10:00Z",
            ),
            Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(),
        )
        val elevenMinutesLater = mergeTelegramState(
            afterLink,
            TelegramSettingsDto(available = true, pending = true, botUsername = "BetterTrackBot"),
            Instant.parse("2026-08-17T10:11:00Z").toEpochMilli(),
        )
        assertNull(elevenMinutesLater.pendingCode)
        assertNull(elevenMinutesLater.pendingExpiresAtMs)
        assertNull(telegramDeepLink(elevenMinutesLater.botUsername, elevenMinutesLater.pendingCode))
    }

    @Test
    fun `linking or unlinking clears the code`() {
        val pending = mergeTelegramState(
            TelegramState(),
            TelegramSettingsDto(
                available = true,
                pending = true,
                botUsername = "Bot",
                pendingCode = "ABC123",
                pendingExpiresAt = "2026-08-17T10:10:00Z",
            ),
            Instant.parse("2026-08-17T10:00:00Z").toEpochMilli(),
        )
        val now = Instant.parse("2026-08-17T10:01:00Z").toEpochMilli()

        val linked = mergeTelegramState(
            pending,
            TelegramSettingsDto(available = true, linked = true, chatIdMasked = "…1234", botUsername = "Bot"),
            now,
        )
        assertTrue(linked.linked)
        assertNull("a linked account has no outstanding code", linked.pendingCode)

        val unlinked = mergeTelegramState(
            linked,
            TelegramSettingsDto(available = true, botUsername = "Bot"),
            now,
        )
        assertFalse(unlinked.linked)
        assertFalse(unlinked.pending)
        assertNull(unlinked.pendingCode)
    }

    // ── 4. Which sentence a refusal gets ──────────────────────────────────────

    @Test
    fun `the save refusal maps to three distinct sentences`() {
        assertEquals(
            R.string.bt_notif_dc_err_invalid_webhook,
            discordSaveFailureRes(400, DISCORD_INVALID_WEBHOOK),
        )
        assertEquals(
            R.string.bt_notif_dc_err_send_failed,
            discordSaveFailureRes(400, DISCORD_SEND_FAILED),
        )
        assertEquals(
            R.string.bt_notif_dc_err_not_webhook,
            discordSaveFailureRes(400, BtApiError.Codes.VALIDATION_ERROR),
        )
        assertEquals(
            "an unknown code still gets the schema sentence, not a blank",
            R.string.bt_notif_dc_err_not_webhook,
            discordSaveFailureRes(400, "SOMETHING_NEW"),
        )
        // Three genuinely different resources, not the same one three times.
        assertEquals(
            3,
            setOf(
                discordSaveFailureRes(400, DISCORD_INVALID_WEBHOOK),
                discordSaveFailureRes(400, DISCORD_SEND_FAILED),
                discordSaveFailureRes(400, null),
            ).size,
        )
    }

    @Test
    fun `the client-side refusal speaks with the server's schema voice`() {
        // The repository raises this code without a round trip; the user must not be
        // able to tell the difference from the server saying the same thing.
        assertEquals(
            discordSaveFailureRes(400, BtApiError.Codes.VALIDATION_ERROR),
            discordSaveFailureRes(400, DISCORD_CLIENT_INVALID_URL),
        )
    }

    @Test
    fun `failures that are not Discord's verdict fall through to the error catalogue`() {
        // Transport (0), the app's unexpected class (-1), 5xx and the kill-switch
        // are not opinions about the URL, and must not be dressed as one.
        listOf(0, -1, 500, 503, 404).forEach { status ->
            assertNull("status $status", discordSaveFailureRes(status, BtApiError.Codes.NETWORK))
            assertNull("status $status", discordTestFailureRes(status, DISCORD_SEND_FAILED))
        }
    }

    @Test
    fun `a refused test send gets the test sentence, whichever code arrives`() {
        assertEquals(
            R.string.bt_notif_dc_err_test_failed,
            discordTestFailureRes(400, DISCORD_NO_WEBHOOK),
        )
        assertEquals(
            R.string.bt_notif_dc_err_test_failed,
            discordTestFailureRes(400, DISCORD_SEND_FAILED),
        )
        // And it is NOT the save path's sentence — the two failures are different
        // events and read differently.
        assertTrue(
            discordTestFailureRes(400, DISCORD_SEND_FAILED) !=
                discordSaveFailureRes(400, DISCORD_SEND_FAILED),
        )
    }

    // ── The kill switch ───────────────────────────────────────────────────────

    @Test
    fun `only a 404 means the deployment flag is off`() {
        assertTrue(isChannelKillSwitchOff(404))
        listOf(0, -1, 400, 401, 403, 409, 500).forEach {
            assertFalse("status $it", isChannelKillSwitchOff(it))
        }
    }
}
