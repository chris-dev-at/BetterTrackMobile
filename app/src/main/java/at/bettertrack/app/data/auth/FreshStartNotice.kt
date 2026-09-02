package at.bettertrack.app.data.auth

/**
 * The one-time **paranoid fresh-start notice** — `docs/paranoid-design.md` §17
 * step 3 (PARANOID E9, platform tick 2026-08-29).
 *
 * §17 ruled the transition for the live account-level paranoid accounts as
 * *(C) backup + wipe*: an owner-run verified ciphertext backup, then a migration
 * that retires the account-level rows and flips `privacy_mode` back to `normal`.
 * The account comes back whole; the vaulted content and the legacy passphrase do
 * not. So the account is owed one calm explanation of what happened, once —
 * "no conversion ceremony, no legacy passphrase prompt", and per §21 ruling 4
 * stated as a plain fact rather than as an alarm.
 *
 * Everything here is pure and free of Compose, Retrofit and Android so the two
 * rules that decide whether a user sees the notice are unit-testable on their
 * own: [freshStartNoticeDue] and [FreshStartNoticeSession].
 */
object FreshStartNoticeFlags {

    /**
     * Whether the notice surface is reachable. **`false` — blocked on the
     * platform, not on this app.**
     *
     * The deployed `openapi.json` declares
     * `POST /api/v1/auth/fresh-start-notice/acknowledge` with
     * `security: [{ "sessionCookie": [] }]` and nothing else, while every
     * sibling this app already calls — `/auth/me`, `/auth/first-run/complete`,
     * `/auth/pin/…` — declares `sessionCookie` **and** `apiKeyBearer`. That list
     * is generated from the real bearer middleware policy rather than written by
     * hand, so it describes enforcement: the route is not in the `/auth/…` bearer
     * carve-outs and falls through to the group's `session-only` default. A
     * bearer — a personal key or this app's delegated OAuth access token, which
     * travel the same rail — is refused.
     *
     * Showing a notice whose only primary action can only fail would be worse
     * than not showing it, and no local dismissal may stand in for the server's
     * receipt (see [freshStartNoticeDue]). So the whole surface waits here. When
     * the platform widens the allowlist — one line, exactly the one it already
     * added for `/auth/first-run/complete` — this becomes `true` and nothing
     * else has to change.
     *
     * A `val` and not a `const val` on purpose, matching
     * [at.bettertrack.app.vault.pv.ParanoidVaultsFlags]: a compile-time constant
     * turns every gated block into dead code the compiler complains about, and
     * those complaints push the next author to delete the guard.
     */
    val enabled: Boolean = false
}

/**
 * Whether the fresh-start notice should be presented right now.
 *
 * Four independent reasons not to, and each one is load-bearing:
 *
 *  1. **The surface is flagged off** — see [FreshStartNoticeFlags.enabled].
 *  2. **Nobody is signed in.** The notice is about a server account's history;
 *     there is nothing to say on the login screen, and nothing to acknowledge.
 *  3. **The server did not say `true`.** `null` is a server that never sent the
 *     key, and telling an account that was never wiped that its data was retired
 *     would be a fabricated event. Only a declared `true` shows anything — the
 *     same `!== true` guard the web's `FreshStartNotice` uses.
 *  4. **It was already presented in this app session.** Not a memory of having
 *     been *acknowledged* — a memory of having been *shown*, so the sheet does
 *     not reappear the moment the user dismisses it.
 *
 * The fourth is deliberately the ONLY local state in the feature, it lives in
 * memory only (see [FreshStartNoticeSession]), and it can never mask an
 * unacknowledged account: a dismissal or a failed acknowledgement leaves the
 * server flag standing, so the next app start reads `true` again and asks again.
 * The server's set-once receipt is the only thing that ends the notice for good.
 */
fun freshStartNoticeDue(
    signedIn: Boolean,
    pending: Boolean?,
    shownThisSession: Boolean,
    enabled: Boolean = FreshStartNoticeFlags.enabled,
): Boolean = enabled && signedIn && pending == true && !shownThisSession

/**
 * The in-memory "already shown" marker behind [freshStartNoticeDue]'s fourth
 * rule, keyed by account id.
 *
 * Process-scoped by construction — it is a plain field, nothing writes it to
 * disk, and process death re-arms it. That is exactly the requested lifetime:
 * once per session start, never a persisted "seen" flag that could outlive an
 * unacknowledged server state.
 *
 * Keyed by account id rather than a bare boolean so that signing out and in as a
 * different account in the same process still gets its own notice, and so a
 * marker set for one account can never silence another's.
 */
object FreshStartNoticeSession {

    private var shownForAccountId: String? = null

    /** Whether [markShown] has already fired for this account in this process. */
    fun wasShown(accountId: String): Boolean =
        accountId.isNotEmpty() && shownForAccountId == accountId

    /** Record that the notice has been put on screen for this account. */
    fun markShown(accountId: String) {
        if (accountId.isNotEmpty()) shownForAccountId = accountId
    }

    /** Re-arm — used by tests and by a sign-out, which ends the session. */
    fun reset() {
        shownForAccountId = null
    }
}
