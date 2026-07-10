package at.bettertrack.app.data.account

/**
 * Safety gate for the irreversible **delete-account** action (spec §6.12, #362).
 *
 * The platform endpoint `DELETE /api/v1/account` is LIVE on production and
 * bearer-reachable with `account:security` (verified 2026-07-10). But this debug
 * build is pointed at Christian's REAL production account, and deletion is
 * permanent — so the destructive submit is hard-gated OFF here. The full
 * type-to-confirm UI is built and wired to the real endpoint; with [armed] `false`
 * the submit shows an honest "disabled in this build" state and the network call
 * is refused at BOTH the ViewModel and the repository (defence in depth), so it can
 * never fire against the live account during testing.
 *
 * For the signed release build (Step 20), flip [armed] to `true` and verify the
 * full flow against a THROWAWAY account — never the owner's.
 */
object DeleteAccountFeature {
    /** OFF: the delete call is impossible to trigger against the production account. */
    const val armed: Boolean = false
}
