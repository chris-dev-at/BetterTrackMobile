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
     * ON since 2026-07-12 (PLATFORM_ASKS #17): the header-500 we found was a
     * missing `idempotency_keys` table on prod (migration 0034 silently skipped
     * by a future-dated journal stamp) — fixed by platform PR #441, live on prod
     * `230d510`, wire semantics unchanged. Kept as a kill-switch: if
     * header-bearing mutations ever 500 again, flip to `false` and mutations
     * keep draining exactly-once via the legacy `[bt:<uuid>]` note-marker
     * reconcile (HISTORY in git: the full 2026-07-11 A/B evidence).
     */
    const val IDEMPOTENCY_KEYS_ENABLED: Boolean = true
}
