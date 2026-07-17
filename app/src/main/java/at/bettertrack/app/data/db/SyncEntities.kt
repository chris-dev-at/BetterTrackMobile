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
    /** Human-readable server rejection, set when status = needs-attention. */
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
    }
}
