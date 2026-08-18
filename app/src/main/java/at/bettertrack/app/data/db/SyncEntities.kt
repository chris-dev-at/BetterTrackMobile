package at.bettertrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The durable outbound operation queue (spec §7.3): every offline write is an
 * append-only ledger event stored here until it has provably reached the
 * server. The INTEGER autoincrement [id] is the strict FIFO drain order; the
 * [clientId] UUID is the operation's identity — generated at enqueue, persisted
 * for life, and sent as the server-side `Idempotency-Key` on every mutation
 * (platform #432), which makes a resend of an ambiguous op exactly-once. The
 * legacy `[bt:<uuid>]` note marker that used to prove landing is retired; see
 * ApiOpExecutor + SyncEngine's replay-reconcile.
 */
@Entity(
    tableName = "sync_ops",
    indices = [Index(value = ["clientId"], unique = true), Index("status")],
)
data class SyncOpEntity(
    /** Autoincrement enqueue sequence — the FIFO order. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Client-generated UUID; the (future) idempotency key + reconcile marker. */
    val clientId: String,
    /** One of [at.bettertrack.app.sync.OpType]'s wire names. */
    val opType: String,
    /** Affected portfolio (refetched after a successful drain); null for none. */
    val portfolioId: String?,
    /** kotlinx-serialized op payload (see sync/OpPayloads.kt). */
    val payloadJson: String,
    /** One of [at.bettertrack.app.sync.OpStatus]'s wire names. */
    val status: String,
    /** Completed send attempts (drives exponential backoff). */
    val attemptCount: Int,
    /** Earliest wall-clock ms the op may be (re)tried; 0 = immediately. */
    val nextAttemptAtMs: Long,
    /**
     * Diagnostic text for a parked op. Since DB v10 this is the SECONDARY half:
     * the server's own words, or the format argument for the few codes that take
     * one. The message the user actually reads comes from [errorCode].
     *
     * Rows parked BEFORE v10 have a null [errorCode] and English prose here —
     * they keep rendering that prose verbatim (see `RoomOpStore`), because
     * re-deriving a code from a sentence is guesswork and a wrong guess would
     * tell the user something untrue about their own queued money.
     */
    val serverError: String?,
    /** JSON of server-assigned ids once done (e.g. created transaction id). */
    val serverResultJson: String?,
    /** Owner account key at enqueue time (defense-in-depth; DB is single-owner). */
    val accountKey: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /**
     * Wall-clock ms this op began its CURRENT in-flight streak (0 = never sent /
     * not in-flight). Bounds the replay-reconcile window: an ambiguous op older
     * than [at.bettertrack.app.sync.SyncEngine.REPLAY_SAFE_WINDOW_MS] parks
     * instead of blind-replaying past the server's dedupe TTL. Added in DB v5.
     */
    val firstAttemptAtMs: Long = 0L,
    /**
     * Which storage backend this op was enqueued FOR — one of
     * [at.bettertrack.app.data.storage.BackendTag]'s wire names. The router
     * dispatches on this, never on the mode that happens to be active when the
     * op finally drains, so switching storage mode never re-points work that was
     * queued for the other backend (S3/S4 plan §1.2). Added in DB v7; every
     * pre-v7 row is backfilled `'server'`, which is what it was.
     */
    val backendTag: String = "server",
    /**
     * Stable error CODE for a parked op — a server code (`MIRROR_CONFLICT`) or
     * an app-local one (`APP_OP_ATTEMPT_TIMED_OUT`), resolved through
     * `BtErrorCopy` at RENDER time.
     *
     * Storing the code rather than the sentence is what makes a parked row
     * honour the device language: a row parked on an English device and read
     * after switching to German reads German, because nothing linguistic was
     * ever persisted. Added in DB v10; pre-v10 rows are null and fall back to
     * the English prose in [serverError].
     */
    val errorCode: String? = null,
)

/**
 * Tiny key-value store for database-scoped metadata: the owning account key
 * (drives the logout / account-switch wipe) and last-synced timestamps that
 * feed the offline banner's data age (§7.4).
 */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        /** Account key of the DB owner — mismatch on login ⇒ full wipe. */
        const val KEY_OWNER = "owner_account_key"
        /** Wall-clock ms of the last successful portfolio-scope sync. */
        const val KEY_PORTFOLIO_SYNCED_AT = "portfolio_synced_at_ms"
        /**
         * The user's selected portfolio (§6.1 switcher) — persisted here so the
         * choice sticks across every portfolio-scoped screen AND app restarts,
         * and is wiped with the rest of the account data on logout/switch
         * (the same lifecycle a separate DataStore file would need by hand).
         */
        const val KEY_SELECTED_PORTFOLIO = "selected_portfolio_id"

        /**
         * Sticky per-portfolio default of the §6.2 cash-coupling toggle
         * ("pay from cash" on buys / "add proceeds to cash" on sells) —
         * "true"/"false", set every time the user flips the toggle in the
         * transaction form. Absent ⇒ fall back to the portfolio's server-side
         * `defaultPayFromCash`. Account-scoped like everything in this table.
         */
        fun keyCashCouplingDefault(portfolioId: String) = "cash_coupling_default_$portfolioId"

        /**
         * LEGACY. The portfolio → icon-kind map, as a JSON object of
         * `{"<portfolioId>": "<kind wire name>"}`.
         *
         * This key is no longer written. The icon is a **server** field —
         * `PATCH /portfolios/{id}.kind`, five tokens, contract
         * `portfolio.ts:71` — and `PortfolioEntity.kind` now holds it. The old
         * claim recorded here, that no such field existed on either client, was
         * simply wrong: the web had been writing it to the API the whole time,
         * which is why an icon chosen in the browser never reached the phone.
         *
         * The key survives for exactly one job:
         * `PortfolioRepository.migrateLocalKinds` reads it after a successful
         * portfolio refresh and pushes any icon that only ever existed on this
         * device up to the account. Once the server answers with a `kind`, the
         * entry is inert — the read path prefers the server value — and the key
         * can be deleted in a later release without a migration.
         */
        const val KEY_PORTFOLIO_KINDS = "portfolio_kinds"
    }
}
