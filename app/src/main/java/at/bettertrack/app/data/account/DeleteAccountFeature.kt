package at.bettertrack.app.data.account

/**
 * The irreversible **delete-account** action (spec §6.12, #362). Play mandates a
 * working in-app account-deletion path, so this ships ARMED.
 *
 * The platform endpoint `DELETE /api/v1/account` is LIVE on production and
 * bearer-reachable with `account:security` (verified 2026-07-10). The real product
 * safety is the deletion screen itself: the user must (1) type their username
 * EXACTLY to confirm, (2) re-authenticate with their current password (the server
 * re-verifies it and rejects a wrong one with 401, rate-limited like login), and
 * (3) clear a final blocking confirm dialog. On success the token is dead → the
 * app performs a full local wipe and drops to the login screen.
 *
 * SAFE live verification (Step 20, on the owner's real account): type the correct
 * username but a deliberately WRONG password → the server returns 401 and the app
 * surfaces it inline, account intact. NEVER submit the real password in that probe.
 */
object DeleteAccountFeature {
    /** ARMED: the in-app deletion path calls the live endpoint (Play requirement). */
    const val armed: Boolean = true
}
