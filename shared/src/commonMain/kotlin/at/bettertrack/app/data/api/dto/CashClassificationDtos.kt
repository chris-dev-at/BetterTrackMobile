package at.bettertrack.app.data.api.dto

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the v5 **cash classification** surface (`/cash/tags`,
 * `/cash/budgets`, `/cash/rules`, `/cash/summary`, `/cash/trends`), gated on the
 * `cash:read` / `cash:write` scopes. Field names follow the
 * `@bettertrack/contracts` `cash.ts` schemas exactly (camelCase).
 *
 * Two deliberate shape rules, both mirroring [PortfolioDtos]:
 *
 *  - **Responses are forgiving.** Every non-identity field carries a default so a
 *    pre-v5 server (or a v5 server that has not yet grown a key) reads as an
 *    empty/neutral value instead of throwing `MissingFieldException` mid-render.
 *    `ignoreUnknownKeys = true` covers the other direction.
 *  - **PATCH bodies are sparse.** Every field of an update request is
 *    `nullable-with-default-null`, and the app's `explicitNulls = false` drops
 *    the ones the caller never set — the server schemas are `.strict()` and
 *    require at least one key, so sending `{"name":null}` for a colour-only
 *    re-tint would be both wrong and rejected. Same discipline as
 *    [UpdateAccountSettingsRequest].
 */

// ── Field caps + closed vocabularies (mirrors of the contract constants) ─────

/** `name` cap on a tag (`CASH_TAG_NAME_MAX`). */
const val CASH_TAG_NAME_MAX = 60

/** `pattern` cap on a rule (`CASH_RULE_PATTERN_MAX`). */
const val CASH_RULE_PATTERN_MAX = 200

/** How many tags one movement or one rule may carry (`CASH_TAGS_PER_ITEM_MAX`). */
const val CASH_TAGS_PER_ITEM_MAX = 20

/** Trailing-month window `GET /cash/trends` will serve (`CASH_TREND_MONTHS_MAX`). */
const val CASH_TREND_MONTHS_MAX = 24

/**
 * The nine app-owned tag identities. A tag whose [CashTagDto.systemKey] is one of
 * these is assigned by the server's auto-tag engine; it is renameable and
 * re-tintable but **never deletable** (a DELETE answers 409
 * `CASH_TAG_SYSTEM_PROTECTED`). `systemKey` is null on every user tag.
 *
 * Kept as plain strings, not an enum: the platform may seed a tenth key and an
 * exhaustive `when` would then crash on a tag the user can plainly see.
 */
object CashSystemTagKeys {
    const val INVESTMENT = "investment"
    const val SALE_PROCEEDS = "sale_proceeds"
    const val DIVIDEND = "dividend"
    const val INTEREST = "interest"
    const val FEES = "fees"
    const val TAX = "tax"
    const val TRANSFER = "transfer"
    const val DEPOSIT = "deposit"
    const val WITHDRAWAL = "withdrawal"

    /** Seed order, verbatim from the contract — useful for a stable UI ordering. */
    val ALL: List<String> = listOf(
        INVESTMENT, SALE_PROCEEDS, DIVIDEND, INTEREST, FEES, TAX, TRANSFER, DEPOSIT, WITHDRAWAL,
    )
}

/** The four rule match modes (`CASH_RULE_MATCH_TYPES`), evaluated first-match-wins. */
object CashRuleMatchTypes {
    const val CONTAINS = "contains"
    const val EQUALS = "equals"
    const val STARTS_WITH = "starts_with"
    const val REGEX = "regex"

    val ALL: List<String> = listOf(CONTAINS, EQUALS, STARTS_WITH, REGEX)
}

// ── Tags: GET/POST /cash/tags, PATCH/DELETE /cash/tags/{tagId} ──────────────

/**
 * One flat tag, as returned to its owner. Names are unique per owner
 * CASE-INSENSITIVELY — a duplicate create/rename is a 409
 * `CASH_TAG_NAME_TAKEN` whose server message is surfaced verbatim.
 */
@Serializable
data class CashTagDto(
    val id: String,
    val name: String = "",
    /** `#RRGGBB` tint. The server always assigns one, so a blank read is a bug, not a state. */
    val color: String = "",
    /** True for an app-owned tag — see [CashSystemTagKeys]. */
    val system: Boolean = false,
    /** Stable identity of a system tag; null on every user tag. */
    val systemKey: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `GET /cash/tags` — the caller's tags, system tags included. */
@Serializable
data class CashTagListResponse(
    val tags: List<CashTagDto> = emptyList(),
)

/** `POST` / `PATCH` tag response. */
@Serializable
data class CashTagResponse(
    val tag: CashTagDto,
)

/** `POST /cash/tags`. Colour optional — the server assigns one when omitted. */
@Serializable
data class CreateCashTagRequest(
    /** 1..[CASH_TAG_NAME_MAX], trimmed server-side. */
    val name: String,
    /** `#RRGGBB`; omitted (null) ⇒ server-assigned. */
    val color: String? = null,
)

/**
 * `PATCH /cash/tags/{tagId}` — rename and/or re-tint. Both optional, at least one
 * required; `system` / `systemKey` are server-owned and not settable. A system tag
 * accepts both edits (it is addressed by its `systemKey`, not its name).
 */
@Serializable
data class UpdateCashTagRequest(
    val name: String? = null,
    val color: String? = null,
)

// ── Movement tags: PUT /cash/movements/{movementId}/tags ────────────────────

/**
 * Whole-set replace of one movement's tags. `[]` clears them (the "untagged"
 * state), max [CASH_TAGS_PER_ITEM_MAX] ids. Duplicates in the list are accepted —
 * de-duplication is the server's job.
 */
@Serializable
data class SetCashMovementTagsRequest(
    val tagIds: List<String>,
)

/** The tag set now on a movement, after a replace. */
@Serializable
data class CashMovementTagsResponse(
    val movementId: String = "",
    val tags: List<CashTagDto> = emptyList(),
)

// ── Budgets: /cash/budgets ──────────────────────────────────────────────────

/**
 * One raw budget row: a spend target for one tag inside one portfolio.
 *
 * [period] is the whole design — `null` means the RECURRING monthly target
 * (re-evaluated every month), `"YYYY-MM"` means that single month only.
 */
@Serializable
data class CashBudgetDto(
    val id: String,
    val portfolioId: String = "",
    val tagId: String = "",
    /** `null` = recurring every month; `YYYY-MM` = that month only. */
    val period: String? = null,
    val amount: Double = 0.0,
    val currency: String = "EUR",
    val createdAt: String = "",
    val updatedAt: String = "",
)

/**
 * A budget with its evaluated progress for one month — what the budgets surface
 * lists. [spent] is the OUTFLOW magnitude of the period's movements carrying the
 * tag; [exceeded] is `spent > amount`, the exact condition that fires the alert
 * once per period. [remaining] goes negative once over budget.
 */
@Serializable
data class CashBudgetProgressDto(
    val id: String,
    val portfolioId: String = "",
    val tagId: String = "",
    /** Tag snapshot for the row — a budget always targets a live tag. */
    val tagName: String = "",
    val tagColor: String = "",
    val amount: Double = 0.0,
    val currency: String = "EUR",
    /** The month this row was evaluated for (`YYYY-MM`), never null. */
    val period: String = "",
    /** Whether the target came from the recurring row rather than a month-specific one. */
    val recurring: Boolean = false,
    val spent: Double = 0.0,
    val remaining: Double = 0.0,
    val exceeded: Boolean = false,
)

/** `GET /cash/budgets?portfolioId=&month=` — omitted month ⇒ the current one. */
@Serializable
data class CashBudgetListResponse(
    /** The month the rows were evaluated for (`YYYY-MM`). */
    val period: String = "",
    val budgets: List<CashBudgetProgressDto> = emptyList(),
)

/** `POST` / `PATCH` budget response. */
@Serializable
data class CashBudgetResponse(
    val budget: CashBudgetDto,
)

/**
 * `POST /cash/budgets`. One budget per (portfolio, tag, period) — a second create
 * for the same triple is a 409 `CASH_BUDGET_EXISTS`. [period] omitted (null)
 * creates the recurring monthly target, which is the common case.
 */
@Serializable
data class CreateCashBudgetRequest(
    val portfolioId: String,
    val tagId: String,
    /** `YYYY-MM` for a single-month override; null ⇒ recurring monthly. */
    val period: String? = null,
    /** Must be > 0. */
    val amount: Double,
    val currency: String = "EUR",
)

/**
 * `PATCH /cash/budgets/{budgetId}` — retarget the amount (and optionally the
 * currency). Portfolio, tag and period are fixed at creation, so a budget can
 * never drift onto another ledger or another month.
 */
@Serializable
data class UpdateCashBudgetRequest(
    val amount: Double? = null,
    val currency: String? = null,
)

// ── Rules: /cash/rules (+ /apply, /preview) ─────────────────────────────────

/**
 * One auto-tagging rule: it tests a movement's note and, on a match, applies ALL
 * of [tagIds] at once. **FIRST MATCH WINS** — rules run in ascending [priority]
 * (then age) and the first match applies its set and stops. `GET /cash/rules`
 * already returns them in that evaluation order, so the app must not re-sort.
 */
@Serializable
data class CashRuleDto(
    val id: String,
    /** The tags a matching movement receives (server guarantees ≥ 1). */
    val tagIds: List<String> = emptyList(),
    /** One of [CashRuleMatchTypes]. */
    val matchType: String = CashRuleMatchTypes.CONTAINS,
    val pattern: String = "",
    /** Evaluation order — lower runs first. 0..10000. */
    val priority: Int = 0,
    val enabled: Boolean = true,
    val createdAt: String = "",
    val updatedAt: String = "",
)

/** `GET /cash/rules` — already in evaluation order (ascending priority, then age). */
@Serializable
data class CashRuleListResponse(
    val rules: List<CashRuleDto> = emptyList(),
)

/** `POST` / `PATCH` rule response. */
@Serializable
data class CashRuleResponse(
    val rule: CashRuleDto,
)

/** `POST /cash/rules`. A rule with no tag could never do anything, so ≥ 1 id. */
@Serializable
data class CreateCashRuleRequest(
    val tagIds: List<String>,
    val matchType: String,
    /** 1..[CASH_RULE_PATTERN_MAX], trimmed server-side. */
    val pattern: String,
    val priority: Int,
    val enabled: Boolean,
)

/** `PATCH /cash/rules/{ruleId}` — every field optional; [tagIds] REPLACES the set. */
@Serializable
data class UpdateCashRuleRequest(
    val tagIds: List<String>? = null,
    val matchType: String? = null,
    val pattern: String? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
)

/**
 * `POST /cash/rules/apply` (no body) — how many MOVEMENTS gained a tag, not how
 * many labels were written: a movement matched by a three-tag rule counts once.
 * The run is additive and idempotent, so a second press honestly reports 0.
 */
@Serializable
data class CashRuleApplyResponse(
    val movementsTagged: Int = 0,
)

/**
 * `POST /cash/rules/preview` — what the caller's rules WOULD tag this note as, so
 * the entry form can answer while the user is still typing. Matching runs
 * server-side (RE2) precisely so the app never re-implements the matcher. An
 * empty note is a legal request answering `[]`.
 */
@Serializable
data class CashRulePreviewRequest(
    /** Max 1000 chars. */
    val note: String,
)

@Serializable
data class CashRulePreviewResponse(
    val tagIds: List<String> = emptyList(),
)

// ── Summary + trends: /cash/summary, /cash/trends ───────────────────────────

/**
 * One per-tag row of the monthly summary.
 *
 * **[tagId] null is the UNTAGGED bucket** — [name] and [color] are null with it
 * and the UI supplies the label. It is the one row disjoint from every other.
 */
@Serializable
data class CashTagSummaryDto(
    val tagId: String? = null,
    val name: String? = null,
    val color: String? = null,
    /** Whether this row is an app-owned tag (false for the untagged bucket). */
    val system: Boolean = false,
    /** Positive magnitude of the period's outflows carrying this tag. */
    val outflow: Double = 0.0,
    /** Positive magnitude of the period's inflows carrying this tag. */
    val inflow: Double = 0.0,
    val movements: Int = 0,
)

/**
 * `GET /cash/summary?portfolioId=&month=` — one portfolio's month.
 *
 * **THE PER-TAG ROWS DO NOT SUM TO THE TOTALS.** A movement carrying both `Food`
 * and `Groceries` contributes its FULL magnitude to both rows, because "how much
 * did I spend on Food" must not depend on what else that row was labelled. So
 * `tags.sumOf { it.outflow } >= totalOutflow`, with equality only when no row
 * carries two tags. [totalInflow] / [totalOutflow] / [net] are the authoritative
 * portfolio figures — computed once from the movements themselves and reconciling
 * to the ledger (`net == totalInflow - totalOutflow`); the tag rows are a
 * breakdown, never an addend. Never derive a total by summing [tags].
 */
@Serializable
data class CashSummaryResponse(
    val portfolioId: String = "",
    val month: String = "",
    val totalInflow: Double = 0.0,
    val totalOutflow: Double = 0.0,
    /** `totalInflow - totalOutflow`. */
    val net: Double = 0.0,
    /** Per-tag breakdown, outflow-heaviest first; the untagged bucket last. */
    val tags: List<CashTagSummaryDto> = emptyList(),
)

/** One month's inflow + outflow magnitude for the trend chart. */
@Serializable
data class CashTrendPointDto(
    /** `YYYY-MM`. */
    val month: String = "",
    val inflow: Double = 0.0,
    val outflow: Double = 0.0,
)

/**
 * `GET /cash/trends?portfolioId=&months=` (1..[CASH_TREND_MONTHS_MAX]) —
 * oldest→newest, one point per month in the window, gaps filled with zeros.
 * `portfolioId` is REQUIRED: an aggregate over "all cash" would be a choice this
 * endpoint deliberately does not make silently.
 */
@Serializable
data class CashTrendResponse(
    val portfolioId: String = "",
    val points: List<CashTrendPointDto> = emptyList(),
)
