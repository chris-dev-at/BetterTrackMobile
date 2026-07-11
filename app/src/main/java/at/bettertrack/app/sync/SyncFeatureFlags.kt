package at.bettertrack.app.sync

/**
 * Runtime kill-switches for offline-queue behaviour that depends on a
 * platform-side feature being healthy on prod (mirrors the
 * `OAuthConfig.ALERTS_SCOPES_ENABLED` pattern).
 */
object SyncFeatureFlags {
    /**
     * Attach the server `Idempotency-Key` header to queued portfolio mutations
     * (platform #432, PLATFORM_ASKS #9). ON ⇒ the queue sends the op's client
     * UUID as the key so a replayed retry runs exactly once (byte-identical 2xx).
     *
     * ⚠️ HELD OFF (2026-07-11) — prod REGRESSION: with the header present,
     * `POST /portfolios/{id}/transactions` returns **HTTP 500** every time
     * (device A/B proven: the byte-identical body 500s WITH the key and 201s
     * WITHOUT it; reproduced on a fresh op + fresh key). #432 is NOT safely live
     * for transaction-create despite the #9 note, so enabling this would break
     * offline buy/sell sync on prod. Held `false` (kill-switch, mirroring
     * `OAuthConfig.ALERTS_SCOPES_ENABLED`) so mutations still drain — exactly-once
     * is preserved by the legacy `[bt:<uuid>]` note-marker reconcile, just without
     * server-side memoization. Flip back to `true` the moment the platform
     * confirms the fix (see PLATFORM_ASKS.md + docs/TODO.md); the whole client
     * path (persist/attach/classify/regenerate) is built, unit-tested, and ready.
     */
    const val IDEMPOTENCY_KEYS_ENABLED: Boolean = false
}
