package at.bettertrack.app.data.api.dto

import at.bettertrack.app.data.repo.toPortfolioMirror
import at.bettertrack.app.data.repo.toRowMirror
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for the v5 cash + mirror payloads, using the app's real Json
 * configuration. Cash DTOs had no wire coverage at all before S2b, which is
 * uncomfortable for the one module where a parse slip shows the user wrong money.
 *
 * The fixtures below are shaped from the running v5 backend's serializers.
 */
class CashWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test
    fun `a v5 movement parses with provenance tags and mirror`() {
        val wire = """
            {"id":"11111111-1111-1111-1111-111111111111","kind":"fee","amountEur":-12.5,
             "sourceId":"22222222-2222-2222-2222-222222222222","transactionId":null,
             "transferId":null,"counterpartSourceId":null,"dividendId":null,"taxYear":null,
             "executedAt":"2026-08-01T10:00:00.000Z","note":"custody","source":"sync:mirrorchain",
             "createdAt":"2026-08-01T10:00:01.000Z","tags":["33333333-3333-3333-3333-333333333333"],
             "mirror":{"mirrorId":"44444444-4444-4444-4444-444444444444","version":9,
                       "addedBy":{"userId":"55555555-5555-5555-5555-555555555555",
                                  "username":"chris","profileIcon":"fox"}}}
        """.trimIndent()
        val m = json.decodeFromString(CashMovementDto.serializer(), wire)

        assertEquals("fee", m.kind)
        // Outflows arrive SIGNED negative — the app must not re-sign them.
        assertEquals(-12.5, m.amountEur, 0.0001)
        assertEquals("sync:mirrorchain", m.source)
        assertEquals(1, m.tags?.size)
        assertEquals(9, m.mirror?.version)
        assertEquals("chris", m.mirror?.addedBy?.username)
        assertEquals("fox", m.mirror?.addedBy?.profileIcon)
    }

    @Test
    fun `a pre-v5 movement still parses with everything absent`() {
        val wire = """
            {"id":"a","kind":"deposit","amountEur":100.0,"sourceId":"s",
             "executedAt":"2026-01-01T00:00:00.000Z","createdAt":"2026-01-01T00:00:00.000Z"}
        """.trimIndent()
        val m = json.decodeFromString(CashMovementDto.serializer(), wire)

        assertNull(m.source)
        assertNull(m.tags)
        assertNull(m.mirror)
        // A null source must read as manual, not as an unknown provenance badge.
        assertEquals("manual", m.source ?: "manual")
    }

    @Test
    fun `an unknown future kind does not break parsing`() {
        val wire = """
            {"id":"a","kind":"interest_credit","amountEur":1.0,"sourceId":"s",
             "executedAt":"2026-01-01T00:00:00.000Z","createdAt":"2026-01-01T00:00:00.000Z"}
        """.trimIndent()
        assertEquals(
            "interest_credit",
            json.decodeFromString(CashMovementDto.serializer(), wire).kind,
        )
    }

    @Test
    fun `write response carries both balances`() {
        val wire = """
            {"movement":{"id":"a","kind":"fee","amountEur":-5.0,"sourceId":"s",
             "executedAt":"2026-01-01T00:00:00.000Z","createdAt":"2026-01-01T00:00:00.000Z"},
             "sourceBalanceEur":95.0,"balanceEur":195.0}
        """.trimIndent()
        val r = json.decodeFromString(CashMovementResponse.serializer(), wire)
        assertEquals(95.0, r.sourceBalanceEur!!, 0.0001)
        assertEquals(195.0, r.balanceEur, 0.0001)
    }

    @Test
    fun `delete response carries what the ui repaints from`() {
        val wire = """{"sourceId":"s","sourceBalanceEur":80.0,"balanceEur":180.0}"""
        val r = json.decodeFromString(CashDeletionResponse.serializer(), wire)
        assertEquals("s", r.sourceId)
        assertEquals(80.0, r.sourceBalanceEur!!, 0.0001)
        assertEquals(180.0, r.balanceEur, 0.0001)
    }

    @Test
    fun `portfolio summary parses the chain badge`() {
        val wire = """
            {"id":"p","name":"Team","visibility":"private","sortOrder":0,"isDefault":false,
             "defaultPayFromCash":false,
             "mirror":{"chainId":"c","chainName":"Our chain","role":"owner","memberCount":3,
                       "sync":{"appliedSeq":10,"lastSeq":12,"percent":83,"synced":false}}}
        """.trimIndent()
        val p = json.decodeFromString(PortfolioDto.serializer(), wire)

        assertEquals("Our chain", p.mirror?.chainName)
        assertEquals("owner", p.mirror?.role)
        assertEquals(3, p.mirror?.memberCount)
        // All four sync keys exist on the wire; percent/synced drive the badge.
        assertEquals(10, p.mirror?.sync?.appliedSeq)
        assertEquals(12, p.mirror?.sync?.lastSeq)
        assertEquals(83, p.mirror?.sync?.percent)
        assertEquals(false, p.mirror?.sync?.synced)
    }

    @Test
    fun `a portfolio without a chain has no badge`() {
        val wire = """
            {"id":"p","name":"Main","visibility":"private","sortOrder":0,"isDefault":true,
             "defaultPayFromCash":false}
        """.trimIndent()
        val p = json.decodeFromString(PortfolioDto.serializer(), wire)
        assertNull(p.mirror)
        assertNull(p.mirror.toPortfolioMirror())
    }

    @Test
    fun `stripped attribution for a non-member viewer yields no dangling chip`() {
        // The server sends username "group member" with nulls elsewhere; a BLANK
        // username must map to absent so the UI renders no "added by @".
        val stripped = MirrorRowInfoDto(
            mirrorId = "m",
            version = 1,
            addedBy = MirrorAttributionDto(userId = null, username = "", profileIcon = null),
        )
        assertNull(stripped.toRowMirror()?.mirrorAddedByName)

        val named = MirrorRowInfoDto(
            mirrorId = "m",
            version = 1,
            addedBy = MirrorAttributionDto(userId = "u", username = "chris", profileIcon = "fox"),
        )
        assertEquals("chris", named.toRowMirror()?.mirrorAddedByName)
    }

    @Test
    fun `mirror mapping tolerates a missing sync block`() {
        val badge = PortfolioMirrorBadgeDto(chainId = "c", chainName = "n", role = "member", memberCount = 2)
        val mapped = badge.toPortfolioMirror()
        assertNotNull(mapped)
        assertEquals(100, mapped?.mirrorSyncPercent)
        assertEquals(true, mapped?.mirrorSynced)
    }

    @Test
    fun `an unknown profile icon is carried not rejected`() {
        // profileIcon is z.string().nullable() on the mirror DTOs — NOT the
        // curated 16-value enum — so a value outside the set must survive.
        val info = MirrorRowInfoDto(
            mirrorId = "m",
            version = 0,
            addedBy = MirrorAttributionDto(userId = "u", username = "x", profileIcon = "narwhal"),
        )
        assertEquals("narwhal", info.toRowMirror()?.mirrorAddedByIcon)
    }

    @Test
    fun `a transaction row carries provenance too`() {
        val wire = """
            {"id":"t","assetId":"a","side":"buy","quantity":1.0,"price":2.0,"fee":0.0,
             "executedAt":"2026-01-01T00:00:00.000Z",
             "asset":{"id":"a","symbol":"AAPL","name":"Apple","currency":"USD","type":"stock"},
             "source":"standing-order",
             "mirror":{"mirrorId":"m","version":3,"addedBy":{"userId":null,"username":"group member","profileIcon":null}}}
        """.trimIndent()
        val t = json.decodeFromString(TransactionDto.serializer(), wire)
        assertEquals("standing-order", t.source)
        assertEquals(3, t.mirror?.version)
        assertTrue(t.mirror?.addedBy?.username == "group member")
    }
}
