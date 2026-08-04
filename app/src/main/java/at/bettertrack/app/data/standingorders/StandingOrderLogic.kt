package at.bettertrack.app.data.standingorders

import at.bettertrack.app.data.api.dto.CreateStandingOrderRequest
import at.bettertrack.app.data.api.dto.STANDING_ORDER_AMOUNT_MAX
import at.bettertrack.app.data.api.dto.STANDING_ORDER_LABEL_MAX
import at.bettertrack.app.data.api.dto.StandingOrderCadences
import at.bettertrack.app.data.api.dto.StandingOrderKinds
import at.bettertrack.app.data.api.dto.StandingOrderStatuses
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure, UI-free logic for standing orders: the typed vocabulary, the client-side
 * mirror of the server's shape rules, and the tri-state PATCH body builder.
 *
 * Everything here is a pure function so the form can validate on every keystroke
 * without a round trip, and so the rules are testable without a network. The
 * server re-validates all of it — this exists to keep a guaranteed 400 from ever
 * leaving the phone, not to replace the server's authority.
 */

// ── Typed vocabulary ────────────────────────────────────────────────────────

/**
 * What a standing order does when it fires. The sign is assigned by the kind and
 * is never client-supplied; [BuyAsset]'s amount is a share QUANTITY, the cash
 * kinds' a EUR magnitude.
 */
enum class StandingOrderKind(val wire: String) {
    BuyAsset(StandingOrderKinds.BUY_ASSET),
    CashAdd(StandingOrderKinds.CASH_ADD),
    CashDeduct(StandingOrderKinds.CASH_DEDUCT);

    companion object {
        /** null for a kind this app version doesn't know — never a crash. */
        fun fromWire(wire: String): StandingOrderKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** How often it fires. `Monthly` fires on `anchorDay`, clamped to month-end. */
enum class StandingOrderCadence(val wire: String) {
    Daily(StandingOrderCadences.DAILY),
    Monthly(StandingOrderCadences.MONTHLY);

    companion object {
        fun fromWire(wire: String): StandingOrderCadence? = entries.firstOrNull { it.wire == wire }
    }
}

enum class StandingOrderStatus(val wire: String) {
    Active(StandingOrderStatuses.ACTIVE),
    Paused(StandingOrderStatuses.PAUSED);

    companion object {
        /** An unknown status reads as Active — the safe, visible default. */
        fun fromWire(wire: String): StandingOrderStatus =
            entries.firstOrNull { it.wire == wire } ?: Active
    }
}

// ── Validation ──────────────────────────────────────────────────────────────

/** The form fields a [StandingOrderProblem] can be attached to. */
enum class StandingOrderField {
    Amount,
    AssetId,
    Label,
    AnchorDay,
    StartDate,
    EndDate,
}

/**
 * What is wrong with a field. **Enums, not strings** — the UI owns the wording
 * and resolves them against the string resources for the user's language.
 */
enum class StandingOrderProblem {
    /** `buy-asset` with no asset chosen. */
    AssetRequired,
    /** A cash kind carrying an asset — the server REJECTS the key, it isn't ignored. */
    AssetNotAllowed,
    /** Missing, zero, negative, NaN or infinite. */
    AmountNotPositive,
    /** Above the ledger's representable ceiling ([STANDING_ORDER_AMOUNT_MAX]). */
    AmountTooLarge,
    /** Longer than [STANDING_ORDER_LABEL_MAX] after trimming. */
    LabelTooLong,
    /** `monthly` with no anchor day. */
    AnchorDayRequired,
    /** `daily` carrying an anchor day — again REJECTED, not ignored. */
    AnchorDayNotAllowed,
    /** Outside 1..31. */
    AnchorDayOutOfRange,
    /** Not an ISO `YYYY-MM-DD` day. */
    StartDateMalformed,
    /** Not an ISO `YYYY-MM-DD` day. */
    EndDateMalformed,
    /** An end before the start — the order could never fire. */
    EndDateBeforeStart,
}

/**
 * The outcome of [validateStandingOrder]: at most one problem per field, so a
 * form can bind `validation[Field]` straight to a field's error slot.
 */
data class StandingOrderValidation(
    val problems: Map<StandingOrderField, StandingOrderProblem> = emptyMap(),
) {
    val isValid: Boolean get() = problems.isEmpty()

    operator fun get(field: StandingOrderField): StandingOrderProblem? = problems[field]

    companion object {
        val VALID = StandingOrderValidation()
    }
}

/**
 * Everything needed to create a standing order, in typed form. [amount] is
 * nullable because an empty amount field is the form's normal starting state,
 * not a zero.
 */
data class StandingOrderDraft(
    val portfolioId: String,
    val kind: StandingOrderKind,
    val cadence: StandingOrderCadence,
    val amount: Double?,
    /** Required iff [kind] is [StandingOrderKind.BuyAsset]. */
    val assetId: String? = null,
    /** Free text ("salary", "Netflix"); blank counts as absent. */
    val label: String? = null,
    /** Required iff [cadence] is [StandingOrderCadence.Monthly]; 1..31. */
    val anchorDay: Int? = null,
    /** ISO `YYYY-MM-DD`; null ⇒ the server uses today. */
    val startDate: String? = null,
    /** ISO `YYYY-MM-DD`, inclusive; null ⇒ runs forever. */
    val endDate: String? = null,
)

/**
 * The client-side mirror of the server's create rules (contract
 * `createStandingOrderRequestSchema`):
 *
 *  - `assetId` REQUIRED iff kind == `buy-asset`, REJECTED otherwise;
 *  - `anchorDay` REQUIRED iff cadence == `monthly`, REJECTED otherwise, 1..31;
 *  - `amount` positive, finite, ≤ [STANDING_ORDER_AMOUNT_MAX];
 *  - `label` ≤ [STANDING_ORDER_LABEL_MAX] trimmed (blank = absent, not an error);
 *  - dates ISO `YYYY-MM-DD`, `endDate` on or after `startDate`.
 *
 * **The end-vs-start check only fires when BOTH dates are given** — that is
 * exactly what the contract's `superRefine` does. When `startDate` is omitted the
 * server substitutes ITS today and re-checks, so a past-dated `endDate` alone can
 * still be refused server-side; the app can't pre-empt that without inventing a
 * timezone the server owns.
 */
fun validateStandingOrder(draft: StandingOrderDraft): StandingOrderValidation {
    val problems = buildMap {
        // Amount.
        val amount = draft.amount
        when {
            amount == null || !amount.isFinite() || amount <= 0.0 ->
                put(StandingOrderField.Amount, StandingOrderProblem.AmountNotPositive)

            amount > STANDING_ORDER_AMOUNT_MAX ->
                put(StandingOrderField.Amount, StandingOrderProblem.AmountTooLarge)
        }

        // Asset — required exactly for buy-asset, rejected for the cash kinds.
        val hasAsset = !draft.assetId.isNullOrBlank()
        if (draft.kind == StandingOrderKind.BuyAsset && !hasAsset) {
            put(StandingOrderField.AssetId, StandingOrderProblem.AssetRequired)
        } else if (draft.kind != StandingOrderKind.BuyAsset && hasAsset) {
            put(StandingOrderField.AssetId, StandingOrderProblem.AssetNotAllowed)
        }

        // Label — a blank one is simply absent.
        val label = draft.label?.trim()
        if (!label.isNullOrEmpty() && label.length > STANDING_ORDER_LABEL_MAX) {
            put(StandingOrderField.Label, StandingOrderProblem.LabelTooLong)
        }

        // Anchor day — required exactly for monthly, rejected for daily.
        val anchorDay = draft.anchorDay
        when {
            draft.cadence == StandingOrderCadence.Monthly && anchorDay == null ->
                put(StandingOrderField.AnchorDay, StandingOrderProblem.AnchorDayRequired)

            draft.cadence != StandingOrderCadence.Monthly && anchorDay != null ->
                put(StandingOrderField.AnchorDay, StandingOrderProblem.AnchorDayNotAllowed)

            anchorDay != null && anchorDay !in 1..31 ->
                put(StandingOrderField.AnchorDay, StandingOrderProblem.AnchorDayOutOfRange)
        }

        // Dates.
        val start = draft.startDate?.takeIf { it.isNotBlank() }
        val end = draft.endDate?.takeIf { it.isNotBlank() }
        val startOk = start == null || isIsoDay(start)
        val endOk = end == null || isIsoDay(end)
        if (!startOk) put(StandingOrderField.StartDate, StandingOrderProblem.StartDateMalformed)
        if (!endOk) put(StandingOrderField.EndDate, StandingOrderProblem.EndDateMalformed)
        // ISO days sort lexicographically, so a string compare IS a date compare.
        if (startOk && endOk && start != null && end != null && end < start) {
            put(StandingOrderField.EndDate, StandingOrderProblem.EndDateBeforeStart)
        }
    }
    return if (problems.isEmpty()) StandingOrderValidation.VALID else StandingOrderValidation(problems)
}

/** `YYYY-MM-DD`, the only calendar-day shape the schedule speaks. */
private val ISO_DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")

private fun isIsoDay(value: String): Boolean = ISO_DAY.matches(value)

/**
 * Wire body for a validated [StandingOrderDraft].
 *
 * Normalises the two "REJECTED otherwise" keys away rather than trusting the
 * caller: a cash-kind draft never sends `assetId`, a daily draft never sends
 * `anchorDay`. The server's schema is `.strict()`, so a stray key is a 400 — not
 * something it politely ignores. A blank label/date becomes null, which
 * `explicitNulls = false` then drops from the JSON entirely.
 */
fun StandingOrderDraft.toCreateRequest(): CreateStandingOrderRequest = CreateStandingOrderRequest(
    portfolioId = portfolioId,
    kind = kind.wire,
    assetId = assetId?.takeIf { it.isNotBlank() && kind == StandingOrderKind.BuyAsset },
    amount = amount ?: 0.0,
    label = label?.trim()?.takeIf { it.isNotEmpty() },
    cadence = cadence.wire,
    anchorDay = anchorDay?.takeIf { cadence == StandingOrderCadence.Monthly },
    startDate = startDate?.takeIf { it.isNotBlank() },
    endDate = endDate?.takeIf { it.isNotBlank() },
)

// ── PATCH body ──────────────────────────────────────────────────────────────

/**
 * Body for `PATCH /standing-orders/{id}` — only `amount`, `label` and `endDate`
 * are editable at all.
 *
 * A raw [JsonObject] rather than a DTO because `label` and `endDate` are
 * **nullish** server-side: the app must be able to say `"label": null` (CLEAR the
 * label) distinctly from omitting it (LEAVE it), and `explicitNulls = false`
 * makes a nullable DTO field unable to express the difference. Same reason and
 * same shape as the cash-movement correction patch.
 *
 * Returns **null when nothing changed** — the schema is `.strict()` and an empty
 * body is a 400, so the caller must skip the request instead of sending `{}`.
 */
fun buildStandingOrderPatch(
    amount: Double? = null,
    label: String? = null,
    clearLabel: Boolean = false,
    endDate: String? = null,
    clearEndDate: Boolean = false,
): JsonObject? {
    val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
        amount?.let { put("amount", JsonPrimitive(it)) }
        when {
            clearLabel -> put("label", JsonNull)
            !label.isNullOrBlank() -> put("label", JsonPrimitive(label.trim()))
        }
        when {
            clearEndDate -> put("endDate", JsonNull)
            !endDate.isNullOrBlank() -> put("endDate", JsonPrimitive(endDate))
        }
    }
    return if (fields.isEmpty()) null else JsonObject(fields)
}
