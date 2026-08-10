package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Mirrorchain (group-portfolio) overlays — v5, READ-ONLY on the app side.
 *
 * These are additive fields the platform hangs off portfolio summaries and off
 * individual content rows when the portfolio is a chain copy. Ignoring them is
 * safe, so every field here is optional/nullable and the app never sends them
 * back. Chain ADMINISTRATION (create/rename/kick/dissolve) stays web-only this
 * sprint; the app only renders what it is told.
 *
 * Parsing is deliberately lenient: `profileIcon` is `z.string().nullable()` on
 * the mirror DTOs (NOT the 16-value curated enum that constrains the social
 * write paths), so it is decoded as a plain String and mapped with a fallback.
 */

/**
 * How far this copy has caught up with the chain.
 *
 * Note all FOUR keys — `appliedSeq`/`lastSeq` exist alongside the derived
 * `percent`/`synced` and are what a future catch-up UI would need.
 */
@Serializable
data class MirrorSyncStateDto(
    val appliedSeq: Int = 0,
    val lastSeq: Int = 0,
    /** 0..100, server-derived. */
    val percent: Int = 100,
    val synced: Boolean = true,
)

/** Who put a row into the chain. */
@Serializable
data class MirrorAttributionDto(
    /** Null on a deleted account, or when attribution is stripped for a non-member viewer. */
    val userId: String? = null,
    /** For a non-member viewer the server sends the literal "group member". */
    val username: String = "",
    /** One of the 16 curated profile-icon ids, or null. Unknown values must not crash. */
    val profileIcon: String? = null,
)

/**
 * Per-row chain provenance. Present on transactions, cash movements and cash
 * sources of a chain portfolio; absent entirely on normal portfolios.
 *
 * [version] is the row's latest op seq — it is what a write must echo back as
 * `baseSeq` to get optimistic-concurrency checking (mismatch → 409
 * `MIRROR_CONFLICT`, already mapped in the sync layer).
 */
@Serializable
data class MirrorRowInfoDto(
    val mirrorId: String,
    val version: Int = 0,
    val addedBy: MirrorAttributionDto? = null,
)

/**
 * Chain badge on a portfolio SUMMARY.
 *
 * Served on the summary-shaped endpoints (`GET /portfolios`, create, archive,
 * restore) — NOT on `GET /portfolios/{id}`, which carries holdings + totals and
 * no mirror field.
 */
@Serializable
data class PortfolioMirrorBadgeDto(
    val chainId: String,
    val chainName: String = "",
    /** "owner" | "manager" | "member". */
    val role: String = "member",
    val memberCount: Int = 0,
    val sync: MirrorSyncStateDto? = null,
)

/**
 * Marks a portfolio that USED to be a chain copy and has since been forked off.
 * Mutually exclusive with [PortfolioMirrorBadgeDto] on the same summary.
 */
@Serializable
data class PortfolioMirrorForkDto(
    val chainId: String,
    val chainName: String = "",
    val endedAt: String? = null,
)
