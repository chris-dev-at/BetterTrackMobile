package at.bettertrack.app.ui.connections

import androidx.compose.ui.text.style.TextDecoration
import at.bettertrack.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        "feedback:write" to R.string.bt_scope_feedback_write,
    )

    @Test
    fun `every platform scope has plain-language copy`() {
        assertEquals(20, knownScopes.size)
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

    // ── The grant this install is running on ─────────────────────────────────

    /**
     * The list deliberately keeps first-party grants — that row is how a lost
     * phone is killed from a browser — so the screen picks its own row out of it
     * rather than expecting the server to have removed it.
     *
     * Until platform #1390 ships `current`, the only usable identifier is
     * `clientId` (`appName` is editable display copy, and the grant `id` is per
     * authorization, so it differs per device and after every re-consent).
     */
    @Test
    fun `the grant matching the configured client id is this install`() {
        assertTrue(isOwnGrant("btc_first_party", ownClientId = "btc_first_party"))
        assertFalse(isOwnGrant("btc_someone_else", ownClientId = "btc_first_party"))
    }

    @Test
    fun `client id matching is exact, never fuzzy`() {
        // A prefix, a case fold or a trim-equal would each hand a third-party app
        // the "This device" treatment and strip its revoke button — the one
        // mistake on this screen that removes a privacy control.
        assertFalse(isOwnGrant("btc_first_party_2", ownClientId = "btc_first_party"))
        assertFalse(isOwnGrant("BTC_FIRST_PARTY", ownClientId = "btc_first_party"))
        assertFalse(isOwnGrant(" btc_first_party", ownClientId = "btc_first_party"))
    }

    @Test
    fun `an unconfigured client id matches nothing`() {
        // A build with no OAUTH_CLIENT_ID must not claim a blank-clientId grant
        // as its own; failing "not ours" only ever costs an offered revoke, which
        // the user still has to confirm.
        assertFalse(isOwnGrant("", ownClientId = ""))
        assertFalse(isOwnGrant("btc_first_party", ownClientId = ""))
    }

    /**
     * Platform #1390's `current` is derived from the token the request presented,
     * so it is the only field that can tell this phone from the same app on the
     * user's tablet. When the server states it, it wins outright — including
     * `false` on a row whose `clientId` matches, which is exactly the tablet case
     * the fallback gets wrong.
     */
    @Test
    fun `the server's own verdict overrides the client id fallback`() {
        assertTrue(isOwnGrant("btc_someone_else", current = true, ownClientId = "btc_first_party"))
        assertFalse(isOwnGrant("btc_first_party", current = false, ownClientId = "btc_first_party"))
    }

    @Test
    fun `an absent flag falls back rather than defaulting to false`() {
        // #1390 is not live: a server that says nothing must not silently make
        // every row revocable, including the one in the user's hand.
        assertTrue(isOwnGrant("btc_first_party", current = null, ownClientId = "btc_first_party"))
    }

    // ── Paranoid mode: mark, never drop ──────────────────────────────────────

    /**
     * One-for-one with the web's `PARANOID_BLOCKED_SCOPES`
     * (`apps/web/src/ui/ScopePicker.tsx`), pinned the same way its own
     * `ScopePicker.test.tsx` pins it. A scope drifting out of this set client-side
     * would strike through a permission that is actually live, or fail to mark one
     * that is not — both misreport what a third party can currently do.
     */
    @Test
    fun `the paranoid blocked-scope set mirrors the web`() {
        for (scope in listOf(
            "portfolio:read",
            "portfolio:write",
            "cash:read",
            "cash:write",
            "mirrorchain:read",
            "mirrorchain:write",
        )) {
            assertTrue(scope, isParanoidBlockedScope(scope))
        }
        // The ciphertext sync is the mode's whole point; blocking it would break
        // the vault the mode exists to protect.
        assertFalse(isParanoidBlockedScope("vault:sync"))
        assertFalse(isParanoidBlockedScope("market:read"))
        assertFalse(isParanoidBlockedScope("account:security"))
    }

    @Test
    fun `a blocked scope is struck through and explained, not removed`() {
        val line = scopeLine("Read your portfolio", inactive = true, inactiveSuffix = "inactive")
        // The permission still reads in full — a shortened list would understate
        // what the app was granted.
        assertEquals("Read your portfolio (inactive)", line.text)
        val struck = line.spanStyles.single()
        assertEquals(TextDecoration.LineThrough, struck.item.textDecoration)
        // Only the LABEL is struck; the reason why is plain text after it.
        assertEquals(0, struck.start)
        assertEquals("Read your portfolio".length, struck.end)
    }

    @Test
    fun `an active scope carries no decoration at all`() {
        val line = scopeLine("Read your portfolio", inactive = false, inactiveSuffix = "inactive")
        assertEquals("Read your portfolio", line.text)
        assertTrue(line.spanStyles.isEmpty())
    }
}
