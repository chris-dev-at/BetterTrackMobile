package at.bettertrack.app.ui.connections

import at.bettertrack.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * The Authorized-apps screen's two pure decisions.
 *
 * Both matter for the same reason: this screen is a PRIVACY control, so anything
 * it silently drops is a permission the user is not told a third party holds.
 * The scope mapping must therefore never lose a scope, and the meta line must
 * never invent a date it does not have.
 */
class AuthorizedAppsFormatTest {

    private val zone = ZoneId.of("Europe/Vienna")

    // ── Scope → plain language ───────────────────────────────────────────────

    /**
     * Every scope the platform's `OAUTH_SCOPE_LABELS` defines, and the resource
     * each must map to. Written out rather than derived, so adding a scope
     * platform-side without adding its copy here fails HERE instead of silently
     * rendering `cash:write` at a user.
     */
    private val knownScopes = mapOf(
        "portfolio:read" to R.string.bt_scope_portfolio_read,
        "portfolio:write" to R.string.bt_scope_portfolio_write,
        "workboard:read" to R.string.bt_scope_workboard_read,
        "workboard:write" to R.string.bt_scope_workboard_write,
        "market:read" to R.string.bt_scope_market_read,
        "social:read" to R.string.bt_scope_social_read,
        "social:write" to R.string.bt_scope_social_write,
        "notifications:read" to R.string.bt_scope_notifications_read,
        "notifications:write" to R.string.bt_scope_notifications_write,
        "chat:read" to R.string.bt_scope_chat_read,
        "chat:write" to R.string.bt_scope_chat_write,
        "account:security" to R.string.bt_scope_account_security,
        "alerts:read" to R.string.bt_scope_alerts_read,
        "alerts:write" to R.string.bt_scope_alerts_write,
        "cash:read" to R.string.bt_scope_cash_read,
        "cash:write" to R.string.bt_scope_cash_write,
        "mirrorchain:read" to R.string.bt_scope_mirrorchain_read,
        "mirrorchain:write" to R.string.bt_scope_mirrorchain_write,
        "vault:sync" to R.string.bt_scope_vault_sync,
    )

    @Test
    fun `every platform scope has plain-language copy`() {
        assertEquals(19, knownScopes.size)
        knownScopes.forEach { (scope, res) ->
            assertEquals("scope $scope", res, scopeLabelRes(scope))
        }
    }

    @Test
    fun `each scope gets its own sentence`() {
        // A copy-paste in the `when` would make two scopes describe the same
        // permission, which on a consent list is worse than no copy at all.
        assertEquals(knownScopes.size, knownScopes.values.toSet().size)
    }

    @Test
    fun `an unknown scope falls back to its raw wire name`() {
        // null is the signal to render the scope string verbatim — the grant
        // really does carry it, so it must not vanish from the list.
        assertNull(scopeLabelRes("something:new"))
        assertNull(scopeLabelRes(""))
        assertNull(scopeLabelRes("PORTFOLIO:READ"))
    }

    // ── The "Authorized … · last used …" line ────────────────────────────────

    @Test
    fun `both dates present yields both`() {
        val meta = grantMeta("2026-01-02T03:04:05Z", "2026-02-03T04:05:06Z", Locale.US, zone)
        assertNotNull(meta)
        assertEquals("Jan 2, 2026", meta!!.created)
        assertEquals("Feb 3, 2026", meta.lastUsed)
    }

    @Test
    fun `a never-used grant keeps its created date and reports no last use`() {
        val meta = grantMeta("2026-01-02T03:04:05Z", null, Locale.US, zone)
        assertNotNull(meta)
        assertEquals("Jan 2, 2026", meta!!.created)
        assertNull(meta.lastUsed)
    }

    @Test
    fun `an unparseable last use is the same as none, not a broken line`() {
        val meta = grantMeta("2026-01-02T03:04:05Z", "not-a-date", Locale.US, zone)
        assertNull(meta!!.lastUsed)
    }

    @Test
    fun `no created date means no line at all`() {
        // The sentence is ABOUT the authorization date; "Authorized — " would
        // claim a value that was lost. Saying nothing claims nothing.
        assertNull(grantMeta(null, "2026-02-03T04:05:06Z", Locale.US, zone))
        assertNull(grantMeta("", null, Locale.US, zone))
        assertNull(grantMeta("nonsense", null, Locale.US, zone))
    }

    @Test
    fun `dates render in the device locale and zone`() {
        val us = grantMeta("2026-01-02T03:04:05Z", null, Locale.US, zone)!!.created
        val de = grantMeta("2026-01-02T03:04:05Z", null, Locale.GERMANY, zone)!!.created
        assertEquals("Jan 2, 2026", us)
        assertEquals("02.01.2026", de)
        // An instant just before UTC midnight is already the NEXT day in Vienna —
        // the honest answer is the wall clock the user was looking at.
        val vienna = grantMeta("2026-01-02T23:30:00Z", null, Locale.US, zone)!!.created
        assertEquals("Jan 3, 2026", vienna)
    }
}
