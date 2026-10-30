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
     * Whether the notice surface is reachable. **`true` since 2026-10-30 — E9 is
     * live on prod `c4f4ae2`.**
     *
     * Until then the deployed `openapi.json` declared
     * `POST /api/v1/auth/fresh-start-notice/acknowledge` session-cookie-only, so
     * this app's bearer could not spend the notice and the whole surface waited
     * here (showing a notice whose only primary action can only fail would be
     * worse than not showing it). The route now accepts the bearer with
     * `account:security` — no body, no CSRF header — and answers `403
     * INSUFFICIENT_SCOPE` without that scope; the sheet renders that like any
     * other failure (error + retry), and nothing local ever stands in for the
     * server's receipt (see [freshStartNoticeDue]).
     *
     * A `val` and not a `const val` on purpose, matching
     * [at.bettertrack.app.vault.pv.ParanoidVaultsFlags]: a compile-time constant
     * turns every gated block into dead code the compiler complains about, and
     * those complaints push the next author to delete the guard.
     */
    val enabled: Boolean = true
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
