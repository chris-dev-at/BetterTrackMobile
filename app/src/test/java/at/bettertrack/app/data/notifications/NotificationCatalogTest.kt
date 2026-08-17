package at.bettertrack.app.data.notifications

import at.bettertrack.app.data.api.dto.NotificationChannelsConfigurableDto
import at.bettertrack.app.data.api.dto.NotificationChannelsDto
import at.bettertrack.app.data.api.dto.UpdateNotificationSettingsRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing matrix: taxonomy, locked cells, and the exact PATCH body every
 * control produces.
 *
 * These matter more than usual because the server schema is `.strict()` at every
 * level and rejects an empty `{}` body. A matrix cell write that omits one of the
 * six booleans is a 400, and a control that produces no diff must produce no
 * request at all.
 */
class NotificationCatalogTest {

    /** Mirror AppGraph.json exactly. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** A v4+ cell: all six channels modelled, everything on. */
    private fun allOn() = TypePrefs(
        inApp = true, email = true, push = true, webpush = true,
        telegram = true, discord = true,
    )

    private val serverTypes = NotifCatalog.allTypes
    private fun rows(): Map<String, TypePrefs> = serverTypes.associateWith { allOn() }

    private val allVisible = NotifChannel.entries.toList()

    // ── Taxonomy ────────────────────────────────────────────────────────────────

    @Test
    fun `the catalogue carries the platform's 26 types in 8 categories`() {
        assertEquals(8, NotifCatalog.categories.size)
        assertEquals(26, NotifCatalog.allTypes.size)
        // No type belongs to two categories, and none is listed twice.
        assertEquals(26, NotifCatalog.allTypes.toSet().size)
    }

    @Test
    fun `the type set matches the platform contract exactly`() {
        // Verbatim from `packages/contracts/src/notifications.ts` NOTIFICATION_TYPES.
        // A drift here means the app is either hiding a setting the account has or
        // offering one the server will reject.
        val expected = setOf(
            "friend.request", "friend.accepted",
            "portfolio.shared", "watchlist.shared", "conglomerate.shared", "friend.activity",
            "follow.published", "follow.alert.created", "follow.alert.fired",
            "chat.message",
            "alert.triggered", "standing_order.skipped",
            "budget.exceeded",
            "earnings.reminder", "dividend.event",
            "mirror.invite", "mirror.member_joined", "mirror.member_left",
            "mirror.member_removed", "mirror.removed", "mirror.ownership_transferred",
            "mirror.chain_dissolved", "mirror.sync_stalled",
            "account.invite", "account.temp_password", "account.data_export",
        )
        assertEquals(expected, NotifCatalog.allTypes.toSet())
    }

    @Test
    fun `categories are in the platform's display order`() {
        assertEquals(
            listOf("social", "sharing", "chat", "alerts", "budgets", "markets", "mirrorchain", "account"),
            NotifCatalog.categories.map { it.key },
        )
    }

    @Test
    fun `the channel columns are in the platform's display order`() {
        assertEquals(
            listOf("inapp", "email", "telegram", "discord", "push", "webpush"),
            NotifChannel.entries.map { it.wire },
        )
    }

    @Test
    fun `the eight mirror types collapse into one rendered row`() {
        assertEquals(8, NotifCatalog.mirrorTypes.size)
        assertTrue(NotifCatalog.mirrorTypes.all { it.startsWith("mirror.") })
        // 26 wire types − 8 collapsed + 1 group row = 19 rows on screen.
        assertEquals(19, NotifCatalog.allTypes.size - NotifCatalog.mirrorTypes.size + 1)
    }

    @Test
    fun `a type the catalogue has never heard of is appended, not hidden`() {
        val ordered = NotifCatalog.orderTypes(listOf("standing_order.retried", "friend.request"))
        assertEquals(listOf("friend.request", "standing_order.retried"), ordered)
    }

    // ── Locked cells ────────────────────────────────────────────────────────────

    @Test
    fun `the locked-cell rule matches the web's four branches exactly`() {
        // account.invite: every channel.
        NotifChannel.entries.forEach {
            assertTrue("account.invite/$it must be locked", NotifCatalog.cellLocked("account.invite", it))
        }
        // Three types lock EMAIL only.
        listOf("account.temp_password", "account.data_export", "budget.exceeded").forEach { type ->
            assertTrue("$type/email must be locked", NotifCatalog.cellLocked(type, NotifChannel.Email))
            NotifChannel.entries.filter { it != NotifChannel.Email }.forEach {
                assertFalse("$type/$it must NOT be locked", NotifCatalog.cellLocked(type, it))
            }
        }
        // Nothing else is locked anywhere.
        val lockedTypes = setOf(
            "account.invite", "account.temp_password", "account.data_export", "budget.exceeded",
        )
        NotifCatalog.allTypes.filter { it !in lockedTypes }.forEach { type ->
            NotifChannel.entries.forEach {
                assertFalse("$type/$it must NOT be locked", NotifCatalog.cellLocked(type, it))
            }
        }
    }

    @Test
    fun `a locked cell renders checked only in the email column`() {
        assertTrue(NotifCatalog.lockedCellChecked(NotifChannel.Email))
        NotifChannel.entries.filter { it != NotifChannel.Email }.forEach {
            assertFalse(NotifCatalog.lockedCellChecked(it))
        }
    }

    @Test
    fun `a locked cell can never be written`() {
        // Even asked directly. The UI disables them; this is the layer below.
        assertNull(cellPatch("account.invite", rows(), NotifChannel.Push, false, serverTypes))
        assertNull(cellPatch("account.temp_password", rows(), NotifChannel.Email, false, serverTypes))
        assertNull(cellPatch("budget.exceeded", rows(), NotifChannel.Email, false, serverTypes))
        // …but the same type's UNLOCKED channels still write.
        assertEquals(
            setOf("account.temp_password"),
            cellPatch("account.temp_password", rows(), NotifChannel.Push, false, serverTypes)?.keys,
        )
    }

    // ── Single-cell writes ──────────────────────────────────────────────────────

    @Test
    fun `a cell write carries ALL SIX booleans for that type and no other type`() {
        val patch = cellPatch("friend.request", rows(), NotifChannel.Email, false, serverTypes)!!
        assertEquals(setOf("friend.request"), patch.keys)
        assertEquals(
            """{"matrix":{"friend.request":{"inapp":true,"email":false,"push":true,""" +
                """"webpush":true,"telegram":true,"discord":true}}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(matrix = patch)),
        )
    }

    @Test
    fun `a no-op cell tap produces no request`() {
        assertNull(cellPatch("friend.request", rows(), NotifChannel.Email, true, serverTypes))
    }

    @Test
    fun `a type the server never sent is never written`() {
        // No complete six-key routing exists to echo, so naming it would be a
        // round-trip violation as well as a likely 400.
        assertNull(cellPatch("friend.request", rows(), NotifChannel.Email, false, emptyList()))
    }

    @Test
    fun `a pre-v4 cell omits telegram and discord rather than inventing them`() {
        val preV4 = TypePrefs(inApp = true, email = true, push = true, webpush = true)
        val patch = cellPatch("friend.request", mapOf("friend.request" to preV4), NotifChannel.Push, false, serverTypes)!!
        val body = json.encodeToString(UpdateNotificationSettingsRequest(matrix = patch))
        assertFalse("a pre-v4 strict schema 400s on an unknown key", body.contains("telegram"))
        assertFalse(body.contains("discord"))
    }

    // ── The mirrorchain group row ───────────────────────────────────────────────

    @Test
    fun `the mirror row reads on, off and mixed`() {
        val on = rows()
        assertEquals(TriState.On, mirrorTriState(on, NotifChannel.Push, serverTypes))

        val off = on + NotifCatalog.mirrorTypes.associateWith { allOn().set(NotifChannel.Push, false) }
        assertEquals(TriState.Off, mirrorTriState(off, NotifChannel.Push, serverTypes))

        val mixed = off + mapOf("mirror.invite" to allOn())
        assertEquals(TriState.Mixed, mirrorTriState(mixed, NotifChannel.Push, serverTypes))
    }

    @Test
    fun `toggling the mirror row writes all eight types in one body`() {
        val patch = mirrorPatch(rows(), NotifChannel.Push, false, serverTypes)!!
        assertEquals(NotifCatalog.mirrorTypes.toSet(), patch.keys)
        assertTrue(patch.values.all { !it.push })
        // Still six booleans per type.
        assertTrue(patch.values.all { it.telegram != null && it.discord != null })
    }

    @Test
    fun `a mirror toggle only names the members that actually change`() {
        val partlyOff = rows() + mapOf("mirror.invite" to allOn().set(NotifChannel.Push, false))
        val patch = mirrorPatch(partlyOff, NotifChannel.Push, false, serverTypes)!!
        assertFalse("already off — nothing to say", "mirror.invite" in patch.keys)
        assertEquals(7, patch.size)
    }

    // ── Category masters ────────────────────────────────────────────────────────

    private fun category(key: String) = NotifCatalog.categories.first { it.key == key }

    @Test
    fun `a category master never writes account invite`() {
        val patch = categoryPatch(category("account"), rows(), allVisible, false, serverTypes)!!
        assertFalse(NotifCatalog.ACCOUNT_INVITE in patch.keys)
        assertEquals(setOf("account.temp_password", "account.data_export"), patch.keys)
    }

    @Test
    fun `a category master leaves locked cells at their stored value`() {
        val patch = categoryPatch(category("account"), rows(), allVisible, false, serverTypes)!!
        // email is locked on both remaining account types ⇒ untouched (still true),
        // everything else off.
        val tempPw = patch.getValue("account.temp_password")
        assertTrue("the locked email cell must keep its stored value", tempPw.email)
        assertFalse(tempPw.push)
        assertFalse(tempPw.inapp)
    }

    @Test
    fun `a category master only touches VISIBLE channels`() {
        // Telegram is not deliverable on this deployment: turning the category off
        // must not silently wipe a Telegram column the user set elsewhere.
        val visible = listOf(NotifChannel.InApp, NotifChannel.Email, NotifChannel.Push)
        val patch = categoryPatch(category("social"), rows(), visible, false, serverTypes)!!
        val friendRequest = patch.getValue("friend.request")
        assertFalse(friendRequest.inapp)
        assertFalse(friendRequest.push)
        assertEquals(true, friendRequest.telegram)
        assertEquals(true, friendRequest.discord)
        assertEquals(true, friendRequest.webpush)
    }

    @Test
    fun `the category master reads on when any unlocked visible cell is on`() {
        val social = category("social")
        assertTrue(categoryEnabled(social, rows(), allVisible, serverTypes))

        val silenced = rows() + social.types.associateWith { TypePrefs(false, false, false, false, false, false) }
        assertFalse(categoryEnabled(social, silenced, allVisible, serverTypes))

        // One cell back on ⇒ the master is on again.
        val one = silenced + mapOf("friend.request" to TypePrefs(false, false, true, false, false, false))
        assertTrue(categoryEnabled(social, one, allVisible, serverTypes))
    }

    @Test
    fun `the account category master ignores its locked-only signal`() {
        // account.invite is locked on every channel, so it must never make the
        // Account master read "on" by itself.
        val account = category("account")
        val silenced = rows() + account.types.associateWith { TypePrefs(false, false, false, false, false, false) }
        assertFalse(categoryEnabled(account, silenced, allVisible, serverTypes))
    }

    // ── Per-type mute ───────────────────────────────────────────────────────────

    @Test
    fun `a muted type is all channels off, which is the platform's own definition`() {
        assertFalse(allOn().isMuted())
        assertTrue(allOn().allChannelsOff().isMuted())
        // The tri-state survives: a channel the server never modelled stays null so
        // it is omitted from the PATCH rather than invented as false.
        val preV4 = TypePrefs(inApp = true, email = true, push = true, webpush = true).allChannelsOff()
        assertNull(preV4.telegram)
        assertNull(preV4.discord)
        assertTrue(preV4.isMuted())
    }

    @Test
    fun `muting writes six false booleans for exactly that type`() {
        val patch = mutePatch("chat.message", rows(), muted = true, snapshot = null, serverTypes = serverTypes)!!
        assertEquals(setOf("chat.message"), patch.keys)
        assertEquals(
            """{"matrix":{"chat.message":{"inapp":false,"email":false,"push":false,""" +
                """"webpush":false,"telegram":false,"discord":false}}}""",
            json.encodeToString(UpdateNotificationSettingsRequest(matrix = patch)),
        )
    }

    @Test
    fun `unmuting restores the snapshot taken before the mute`() {
        val before = TypePrefs(inApp = true, email = false, push = true, webpush = false, telegram = true, discord = false)
        val muted = rows() + mapOf("chat.message" to before.allChannelsOff())
        val patch = mutePatch("chat.message", muted, muted = false, snapshot = before, serverTypes = serverTypes)!!
        assertEquals(before.toChannelPrefs(), patch.getValue("chat.message"))
    }

    @Test
    fun `unmuting without a snapshot turns the in-app bell back on and nothing else`() {
        // Muted from the web, or from a build before the snapshot existed. Guessing
        // the platform's five other defaults would be inventing state; one visible
        // switch moving is explainable.
        val muted = rows() + mapOf("chat.message" to allOn().allChannelsOff())
        val patch = mutePatch("chat.message", muted, muted = false, snapshot = null, serverTypes = serverTypes)!!
        val restored = patch.getValue("chat.message")
        assertTrue(restored.inapp)
        assertFalse(restored.email)
        assertFalse(restored.push)
        assertFalse(restored.webpush)
        assertEquals(false, restored.telegram)
        assertEquals(false, restored.discord)
    }

    @Test
    fun `a snapshot that is itself muted is refused as a restore target`() {
        // Otherwise unmuting would "restore" the muted state and the switch would
        // bounce straight back with nothing to show for the round trip.
        val muted = rows() + mapOf("chat.message" to allOn().allChannelsOff())
        val patch = mutePatch(
            "chat.message", muted, muted = false,
            snapshot = allOn().allChannelsOff(), serverTypes = serverTypes,
        )!!
        assertTrue(patch.getValue("chat.message").inapp)
    }

    @Test
    fun `account invite can never be muted`() {
        assertNull(mutePatch(NotifCatalog.ACCOUNT_INVITE, rows(), true, null, serverTypes))
    }

    @Test
    fun `muting an already-muted type sends nothing`() {
        val muted = rows() + mapOf("chat.message" to allOn().allChannelsOff())
        assertNull(mutePatch("chat.message", muted, muted = true, snapshot = null, serverTypes = serverTypes))
    }

    // ── Channel availability ────────────────────────────────────────────────────

    @Test
    fun `channel availability is read straight from the server object`() {
        val a = channelAvailabilityOf(
            NotificationChannelsDto(
                inapp = true, email = true, telegram = false,
                discord = true, push = true, webpush = false,
            ),
        )
        assertEquals(
            listOf(NotifChannel.InApp, NotifChannel.Email, NotifChannel.Discord, NotifChannel.Push),
            a.visible,
        )
    }

    @Test
    fun `a pre-v4 GET with no channels object still renders the four it always modelled`() {
        // The alternative — treating "absent" as "none" — would render an empty
        // grid on an older deployment whose cells clearly carry four channels.
        val a = channelAvailabilityOf(null)
        assertEquals(
            listOf(NotifChannel.InApp, NotifChannel.Email, NotifChannel.Push, NotifChannel.WebPush),
            a.visible,
        )
    }

    @Test
    fun `the setup kill-switch is separate from per-user availability`() {
        // channelsConfigurable = the deployment switch (gates the SETUP cards).
        // channels = per-user "is it linked" (gates the matrix COLUMN). Conflating
        // them means the card that links Telegram only shows once Telegram is
        // already linked.
        val configurable = channelsConfigurableOf(
            NotificationChannelsConfigurableDto(telegram = true, discord = true),
        )
        val availability = channelAvailabilityOf(
            NotificationChannelsDto(inapp = true, telegram = false, discord = false),
        )
        assertTrue(configurable.telegram)
        assertTrue(configurable.any)
        assertFalse(availability.telegram)
        assertFalse(NotifChannel.Telegram in availability.visible)
    }

    @Test
    fun `an absent channelsConfigurable means the kill-switch is off`() {
        assertFalse(channelsConfigurableOf(null).any)
    }

    // ── The pre-mute snapshot codec ─────────────────────────────────────────────

    @Test
    fun `routing encodes and decodes round-trip, tri-state included`() {
        val full = TypePrefs(inApp = true, email = false, push = true, webpush = false, telegram = true, discord = false)
        assertEquals(full, decodeRouting(encodeRouting(full)))

        val preV4 = TypePrefs(inApp = false, email = true, push = false, webpush = true)
        val round = decodeRouting(encodeRouting(preV4))!!
        assertEquals(preV4, round)
        assertNull("a never-modelled channel must not come back as false", round.telegram)
        assertNull(round.discord)
    }

    @Test
    fun `the snapshot codec writes one character per channel in display order`() {
        // inapp, email, telegram, discord, push, webpush
        val p = TypePrefs(inApp = true, email = false, push = true, webpush = false, telegram = null, discord = true)
        assertEquals("10-1" + "1" + "0", encodeRouting(p))
    }

    @Test
    fun `a malformed or absent snapshot decodes to null rather than a guess`() {
        assertNull(decodeRouting(null))
        assertNull(decodeRouting(""))
        assertNull(decodeRouting("101"))
        assertNull(decodeRouting("1010101"))
        assertNull(decodeRouting("10101x"))
    }
}
