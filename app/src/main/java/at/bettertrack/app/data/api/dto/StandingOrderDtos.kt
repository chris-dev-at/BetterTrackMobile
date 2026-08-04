package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the v5 **standing orders** surface (`/standing-orders`) — the
 * scheduled recurring actions a daily server job auto-records. Gated on the same
 * `portfolio:read` / `portfolio:write` pair as the rest of the portfolio surface,
 * NOT on the cash-classification scopes.
 *
 * **Envelope note (checked against the platform monorepo, not guessed):** only
 * the LIST is wrapped — `GET /standing-orders` answers `{"orders":[…]}`. Every
 * single-order response (`POST`, `GET /{id}`, `PATCH`, `pause`, `resume`) returns
 * the **bare** [StandingOrderDto] object with no `{"order":…}` wrapper; see
 * `apps/api/src/services/standingOrders/standingOrderService.ts` (each handler
 * returns `toDto(record, today)` directly) and
 * `apps/api/src/http/openapi/document.ts` (`response: R.StandingOrder`).
 *
 * **No Idempotency-Key.** `standingOrdersRoutes.ts` mounts no `idempotency`
 * middleware on any of these handlers (the platform mounts it per-route), so the
 * header would be silently ignored. It is deliberately not sent.
 *
 * Response fields default so a pre-v5 server that omits a key reads sanely
 * instead of crashing; request bodies stay sparse (`explicitNulls = false` drops
 * the unset keys) because the server schemas are `.strict()`.
 */

// ── Field caps + closed vocabularies (mirrors of the contract constants) ─────

/** `label` cap (`STANDING_ORDER_LABEL_MAX`). */
const val STANDING_ORDER_LABEL_MAX = 120

/** `amount` ceiling, shared by share quantities and EUR amounts (`STANDING_ORDER_AMOUNT_MAX`). */
const val STANDING_ORDER_AMOUNT_MAX = 1_000_000_000.0

/**
 * The three order kinds. [BUY_ASSET] books a BUY of `amount` UNITS at the current
 * quote (priced in the asset's native currency); [CASH_ADD] / [CASH_DEDUCT] book
 * a cash deposit / withdrawal of `amount` EUR. The sign is assigned by kind and
 * never supplied by the client.
 */
object StandingOrderKinds {
    const val BUY_ASSET = "buy-asset"
    const val CASH_ADD = "cash-add"
    const val CASH_DEDUCT = "cash-deduct"

    val ALL: List<String> = listOf(BUY_ASSET, CASH_ADD, CASH_DEDUCT)
}

/** `daily` = every day from `startDate`; `monthly` = once on `anchorDay`, clamped to month-end. */
object StandingOrderCadences {
    const val DAILY = "daily"
    const val MONTHLY = "monthly"

    val ALL: List<String> = listOf(DAILY, MONTHLY)
}

object StandingOrderStatuses {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
}

// ── The order itself ────────────────────────────────────────────────────────

/**
 * One standing order as returned by the API. [nextRunDate] is COMPUTED per
 * request (never stored) and is null when the order is paused or past its end
 * date, so it is the honest "when does this fire next" the UI should render.
 *
 * [amount] means a share QUANTITY for `buy-asset` and a EUR magnitude for the two
 * cash kinds. [currency] is server-derived (EUR for cash; the asset's native
 * currency for a buy) and is display-only — it is never client-supplied.
 */
@Serializable
data class StandingOrderDto(
    val id: String,
    val portfolioId: String = "",
    /** One of [StandingOrderKinds]. */
    val kind: String = "",
    /** Set exactly for `buy-asset`; null for the cash kinds. */
    val assetId: String? = null,
    val assetSymbol: String? = null,
    val assetName: String? = null,
    val amount: Double = 0.0,
    val currency: String = "EUR",
    val label: String? = null,
    /** One of [StandingOrderCadences]. */
    val cadence: String = "",
    /** 1..31 for `monthly` (clamped to month-end in shorter months); null for `daily`. */
    val anchorDay: Int? = null,
    /** ISO `YYYY-MM-DD`. */
    val startDate: String = "",
    /** ISO `YYYY-MM-DD`, inclusive; null = runs forever. */
    val endDate: String? = null,
    /** One of [StandingOrderStatuses]. */
    val status: String = StandingOrderStatuses.ACTIVE,
    /** ISO-8601 instant of the last booked period, or null. */
    val lastRunAt: String? = null,
    /** The occurrence day last booked (`YYYY-MM-DD`), or null. */
    val lastPeriodKey: String? = null,
    /** The next day this order fires; null when paused or past [endDate]. */
    val nextRunDate: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `GET /standing-orders` — the ONLY wrapped response on this surface. */
@Serializable
data class StandingOrderListResponse(
    val orders: List<StandingOrderDto> = emptyList(),
)

/**
 * `POST /standing-orders` → 201 with a bare [StandingOrderDto].
 *
 * Server-enforced shape rules, mirrored client-side by
 * [at.bettertrack.app.data.standingorders.validateStandingOrder] so a guaranteed
 * 400 never leaves the phone:
 *  - [assetId] is REQUIRED iff [kind] is `buy-asset`, and REJECTED otherwise;
 *  - [anchorDay] is REQUIRED iff [cadence] is `monthly`, and REJECTED otherwise;
 *  - [endDate] must be on or after [startDate].
 *
 * [startDate] omitted ⇒ the server uses today. `currency` is absent by design —
 * it is never client-supplied.
 */
@Serializable
data class CreateStandingOrderRequest(
    val portfolioId: String,
    val kind: String,
    val assetId: String? = null,
    /** Share quantity (`buy-asset`) or EUR magnitude (cash kinds); > 0. */
    val amount: Double,
    val label: String? = null,
    val cadence: String,
    val anchorDay: Int? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)
