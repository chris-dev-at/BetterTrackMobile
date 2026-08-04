package at.bettertrack.app.data.cash

import at.bettertrack.app.data.api.BtApi
import at.bettertrack.app.data.api.BtApiError
import at.bettertrack.app.data.api.BtResult
import at.bettertrack.app.data.api.apiCall
import at.bettertrack.app.data.api.dto.CashBudgetDto
import at.bettertrack.app.data.api.dto.CashBudgetListResponse
import at.bettertrack.app.data.api.dto.CashRuleDto
import at.bettertrack.app.data.api.dto.CashRulePreviewRequest
import at.bettertrack.app.data.api.dto.CashSummaryResponse
import at.bettertrack.app.data.api.dto.CashTagDto
import at.bettertrack.app.data.api.dto.CashTrendResponse
import at.bettertrack.app.data.api.dto.CreateCashBudgetRequest
import at.bettertrack.app.data.api.dto.CreateCashRuleRequest
import at.bettertrack.app.data.api.dto.CreateCashTagRequest
import at.bettertrack.app.data.api.dto.SetCashMovementTagsRequest
import at.bettertrack.app.data.api.dto.UpdateCashBudgetRequest
import at.bettertrack.app.data.api.dto.UpdateCashRuleRequest
import at.bettertrack.app.data.api.dto.UpdateCashTagRequest
import at.bettertrack.app.data.api.parseApiError
import at.bettertrack.app.data.db.CashDao
import at.bettertrack.app.data.db.CashTagDao
import at.bettertrack.app.data.db.CashTagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import retrofit2.Response
import java.io.IOException

/**
 * The v5 **cash classification** repository: tags, per-movement tagging, budgets,
 * auto-tagging rules and the two dashboard reads.
 *
 * **Server-only, deliberately outside the storage seam.** Everything portfolio
 * shaped goes through [at.bettertrack.app.data.storage.PortfolioBackend] (W1) so
 * a Drive-backed store can replace the network bodies without touching a single
 * ViewModel. Classification has NO Drive equivalent yet — there is no vault
 * schema for tags, budgets or rules, and no client-side matcher (rule evaluation
 * is RE2 on the server precisely so a pathological pattern can't stall the
 * phone). Routing it through the seam would therefore mean inventing a backend
 * interface with exactly one implementation and one permanently-failing stub.
 * So this is a plain server repository, and **Drive mode simply has no
 * classification layer** — the surfaces built on it must be hidden or disabled
 * in that mode until the platform ships a vault representation.
 *
 * The one thing that IS cached is the tag set itself ([observeTags]): the ledger
 * renders tag chips per movement, and chips with no names offline would be worse
 * than no chips at all. Everything else is read live — budgets and summaries are
 * server-computed figures (§7.1: the server is the only calculator) and a stale
 * cached budget is a wrong number, not a degraded one.
 *
 * Errors follow the app's single currency, [BtResult] / [BtApiError], mapped by
 * [apiCall]. Three refusals the UI should branch on rather than surface raw:
 * [BtApiError.isCashTagSystemProtected] (409 on deleting an app-owned tag),
 * [BtApiError.isCashTagNameTaken] (409 duplicate name) and
 * [BtApiError.isCashBudgetExists] (409 second budget for the same triple).
 *
 * Takes the two DAOs rather than the whole [at.bettertrack.app.data.db.BtDatabase]
 * (unlike the older repositories) for one reason: those are exactly the two
 * surfaces it touches, and interfaces can be faked in a plain JVM test — this
 * module has no Robolectric, so a `BtDatabase` parameter would make the
 * cache-writing half of the class untestable.
 */
class CashClassificationRepository(
    private val api: BtApi,
    private val tagDao: CashTagDao,
    private val cashDao: CashDao,
    private val json: Json,
) {

    // ── Tags ────────────────────────────────────────────────────────────────

    /** The cached tag set: user tags first, then app-owned ones, alphabetical within each. */
    fun observeTags(): Flow<List<CashTagEntity>> = tagDao.observeTags()

    /** Pull the server's tag set and replace the cache with it wholesale. */
    suspend fun refreshTags(): BtResult<Unit> =
        when (val r = apiCall(json) { api.cashTags() }) {
            is BtResult.Ok -> {
                cacheTags(r.value.tags)
                BtResult.Ok(Unit)
            }

            is BtResult.Err -> r
        }

    /**
     * Create a user tag. [color] omitted ⇒ the server assigns a tint. A
     * case-insensitively duplicate name is a 409 whose server message is already
     * user-ready ("You already have a tag with that name.") — surface it verbatim.
     */
    suspend fun createTag(name: String, color: String? = null): BtResult<CashTagDto> =
        when (
            val r = apiCall(json) {
                api.createCashTag(CreateCashTagRequest(name.trim(), color))
            }
        ) {
            is BtResult.Ok -> {
                refreshTags()
                BtResult.Ok(r.value.tag)
            }

            is BtResult.Err -> r
        }

    /**
     * Rename and/or re-tint. Both optional (the server needs at least one);
     * a SYSTEM tag accepts both edits — only its deletion is refused.
     */
    suspend fun updateTag(
        id: String,
        name: String? = null,
        color: String? = null,
    ): BtResult<CashTagDto> =
        when (
            val r = apiCall(json) {
                api.updateCashTag(id, UpdateCashTagRequest(name?.trim(), color))
            }
        ) {
            is BtResult.Ok -> {
                refreshTags()
                BtResult.Ok(r.value.tag)
            }

            is BtResult.Err -> r
        }

    /**
     * Delete a user tag (204). An app-owned tag answers **409
     * `CASH_TAG_SYSTEM_PROTECTED`** — check [BtApiError.isCashTagSystemProtected]
     * and offer a rename instead of reporting a failure.
     */
    suspend fun deleteTag(id: String): BtResult<Unit> =
        unitCall { api.deleteCashTag(id) }.also { if (it is BtResult.Ok) refreshTags() }

    // ── Per-movement tagging ────────────────────────────────────────────────

    /**
     * Replace one movement's tag set wholesale (`[]` clears it, max 20 ids).
     *
     * On success the new set is written straight into the cached `cash_movements`
     * row, so the chips repaint from Room immediately — no ledger refetch, no
     * flicker. Returns the ids the SERVER now holds (which may differ from what
     * was sent: it de-duplicates).
     */
    suspend fun setMovementTags(
        movementId: String,
        tagIds: List<String>,
    ): BtResult<List<String>> =
        when (
            val r = apiCall(json) {
                api.setCashMovementTags(movementId, SetCashMovementTagsRequest(tagIds))
            }
        ) {
            is BtResult.Ok -> {
                val applied = r.value.tags.map { it.id }
                cashDao.updateMovementTags(movementId, encodeTagIds(applied))
                // The response carries the full tag objects, so the name/tint cache
                // can be refreshed for free from a set we already have in hand.
                if (r.value.tags.isNotEmpty()) cacheTags(r.value.tags)
                BtResult.Ok(applied)
            }

            is BtResult.Err -> r
        }

    // ── Budgets ─────────────────────────────────────────────────────────────

    /**
     * The portfolio's budgets with progress for [month] (`YYYY-MM`); null ⇒ the
     * current month. The response's `period` echoes which month was evaluated.
     */
    suspend fun budgets(portfolioId: String, month: String? = null): BtResult<CashBudgetListResponse> =
        apiCall(json) { api.cashBudgets(portfolioId, month) }

    /**
     * Create a budget. [period] null ⇒ the RECURRING monthly target (the common
     * case); `YYYY-MM` ⇒ that month only. A second budget for the same
     * (portfolio, tag, period) is 409 — see [BtApiError.isCashBudgetExists].
     */
    suspend fun createBudget(
        portfolioId: String,
        tagId: String,
        amount: Double,
        period: String? = null,
        currency: String = "EUR",
    ): BtResult<CashBudgetDto> =
        when (
            val r = apiCall(json) {
                api.createCashBudget(
                    CreateCashBudgetRequest(portfolioId, tagId, period, amount, currency),
                )
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(r.value.budget)
            is BtResult.Err -> r
        }

    /** Retarget a budget's amount. Portfolio, tag and period are fixed at creation. */
    suspend fun updateBudgetAmount(id: String, amount: Double): BtResult<CashBudgetDto> =
        when (
            val r = apiCall(json) { api.updateCashBudget(id, UpdateCashBudgetRequest(amount = amount)) }
        ) {
            is BtResult.Ok -> BtResult.Ok(r.value.budget)
            is BtResult.Err -> r
        }

    suspend fun deleteBudget(id: String): BtResult<Unit> = unitCall { api.deleteCashBudget(id) }

    // ── Auto-tagging rules ──────────────────────────────────────────────────

    /**
     * The caller's rules, **already in evaluation order** (ascending priority,
     * then age). Do not re-sort — first match wins, and the order IS the answer.
     */
    suspend fun rules(): BtResult<List<CashRuleDto>> =
        when (val r = apiCall(json) { api.cashRules() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.rules)
            is BtResult.Err -> r
        }

    suspend fun createRule(
        tagIds: List<String>,
        matchType: String,
        pattern: String,
        priority: Int = 0,
        enabled: Boolean = true,
    ): BtResult<CashRuleDto> =
        when (
            val r = apiCall(json) {
                api.createCashRule(
                    CreateCashRuleRequest(tagIds, matchType, pattern.trim(), priority, enabled),
                )
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(r.value.rule)
            is BtResult.Err -> r
        }

    /** Every field optional; a non-null [tagIds] REPLACES the rule's set. */
    suspend fun updateRule(
        id: String,
        tagIds: List<String>? = null,
        matchType: String? = null,
        pattern: String? = null,
        priority: Int? = null,
        enabled: Boolean? = null,
    ): BtResult<CashRuleDto> =
        when (
            val r = apiCall(json) {
                api.updateCashRule(
                    id,
                    UpdateCashRuleRequest(tagIds, matchType, pattern?.trim(), priority, enabled),
                )
            }
        ) {
            is BtResult.Ok -> BtResult.Ok(r.value.rule)
            is BtResult.Err -> r
        }

    suspend fun deleteRule(id: String): BtResult<Unit> = unitCall { api.deleteCashRule(id) }

    /**
     * Run every enabled rule over the existing movements; returns how many
     * MOVEMENTS gained a tag (not how many labels). Additive and idempotent, so a
     * second press honestly reports 0 — render that as "nothing left to tag",
     * never as a failure.
     */
    suspend fun applyRules(): BtResult<Int> =
        when (val r = apiCall(json) { api.applyCashRules() }) {
            is BtResult.Ok -> BtResult.Ok(r.value.movementsTagged)
            is BtResult.Err -> r
        }

    /**
     * What the rules WOULD tag [note] as — for the live hint under an entry
     * form's note field. First match wins, so this is 0 or 1 rule's tag set. An
     * empty note is a legal request answering an empty list.
     */
    suspend fun previewRules(note: String): BtResult<List<String>> =
        when (val r = apiCall(json) { api.previewCashRules(CashRulePreviewRequest(note)) }) {
            is BtResult.Ok -> BtResult.Ok(r.value.tagIds)
            is BtResult.Err -> r
        }

    // ── Dashboards ──────────────────────────────────────────────────────────

    /**
     * One portfolio's month. **The per-tag rows do not sum to the totals** — see
     * [CashSummaryResponse]; render `totalInflow`/`totalOutflow`/`net` as the
     * authoritative figures and never derive them from `tags`.
     */
    suspend fun summary(portfolioId: String, month: String? = null): BtResult<CashSummaryResponse> =
        apiCall(json) { api.cashSummary(portfolioId, month) }

    /** Trailing [months] (1..24) of inflow/outflow, oldest→newest, gaps as zeros. */
    suspend fun trends(portfolioId: String, months: Int? = null): BtResult<CashTrendResponse> =
        apiCall(json) { api.cashTrends(portfolioId, months) }

    // ── Internals ───────────────────────────────────────────────────────────

    private suspend fun cacheTags(tags: List<CashTagDto>) {
        tagDao.replaceAll(
            tags.map { CashTagEntity(it.id, it.name, it.color, it.system, it.systemKey) },
        )
    }

    /**
     * A 204 endpoint. [apiCall] insists on a non-null body, so the bodyless
     * writes are mapped by hand — the same shape
     * [at.bettertrack.app.data.repo.AlertsRepository.delete] uses.
     */
    private suspend fun unitCall(
        call: suspend () -> Response<Unit>,
    ): BtResult<Unit> {
        val resp = try {
            call()
        } catch (_: IOException) {
            return BtResult.Err(
                BtApiError(
                    0,
                    BtApiError.Codes.NETWORK,
                    "No connection. Check your network and try again.",
                ),
            )
        }
        return if (resp.isSuccessful) BtResult.Ok(Unit)
        else BtResult.Err(parseApiError(json, resp.code(), resp.errorBody()))
    }
}
