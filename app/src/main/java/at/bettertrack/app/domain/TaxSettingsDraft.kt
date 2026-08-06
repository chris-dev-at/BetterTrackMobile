package at.bettertrack.app.domain

/**
 * A tax-settings form in progress, and the six consistency rules that decide
 * whether it may be sent.
 *
 * ## Why this is a domain type and not form state inside a ViewModel
 *
 * `updateTaxSettingsRequestSchema` is `.strict()` and carries a `superRefine`
 * with six cross-field rules. Five of them are *mode/field consistency* — a
 * `country` may only travel with `country_specific`, a `custom` block only with
 * `custom`, a manual default only with `manual_per_trade` — and the sixth
 * forbids sending a manual amount AND a rate together.
 *
 * The naive client shape (one nullable field per wire field, all live at once)
 * makes every one of those rules violable, and violating them is a 400 the user
 * cannot act on: the server's message names a JSON path, not a control. So the
 * draft keeps the mode-specific data in a [Mode]-shaped union where the illegal
 * combinations are *unrepresentable*, and the only way to reach the wire is
 * [toRequest], which emits exactly the field set the chosen mode allows.
 *
 * The result is that "which fields does this mode send?" is answered once, here,
 * in code the unit tests can reach without a device — rather than four times
 * across a screen, a ViewModel, a mapper and a retry path.
 *
 * ## What this deliberately does NOT do
 *
 * It does not decide anything financial. The server is the only calculator: this
 * type's entire job is the shape of one request body. [at.bettertrack.app.domain]
 * already carries the real engine port ([CustomTaxParams] and friends) for
 * conformance testing; nothing here computes a tax.
 */
sealed interface TaxSettingsDraft {

    /** The wire `mode` this draft will send. */
    val mode: TaxMode

    /** No tax treatment — the default, and the exact pre-V3-P4 behavior. */
    data object None : TaxSettingsDraft {
        override val mode: TaxMode get() = "none"
    }

    /**
     * Optional user-entered tax on every sell/dividend, zero automation.
     *
     * [defaultAmountEur] and [defaultRatePct] are the prefill for entry-less
     * rows and are mutually exclusive on the wire — which is why they are two
     * nullable fields guarded by [isValid] rather than a sealed pair: the user
     * genuinely types into one of two adjacent inputs, and a UI that made the
     * other vanish mid-edit would be worse than one that reports the conflict.
     * Neither set is legal too (= no default), so this is not a required choice.
     */
    data class Manual(
        val defaultAmountEur: Double? = null,
        val defaultRatePct: Double? = null,
    ) : TaxSettingsDraft {
        override val mode: TaxMode get() = "manual_per_trade"
    }

    /** Automated per country. [country] is required — see [isValid]. */
    data class CountrySpecific(val country: String?) : TaxSettingsDraft {
        override val mode: TaxMode get() = "country_specific"
    }

    /** The user-parameterized rule-built engine (V5-P4c). */
    data class Custom(val params: CustomTaxParams) : TaxSettingsDraft {
        override val mode: TaxMode get() = "custom"
    }

    /**
     * Why this draft cannot be sent, or `null` when it can.
     *
     * Returns the FIRST problem rather than a list: the form shows one blocking
     * reason at a time, and a draft can only ever hold one of these at once
     * (each rule guards a different mode).
     */
    val problem: TaxDraftProblem?
        get() = when (this) {
            is None -> null
            is CountrySpecific ->
                if (country == null || country !in SUPPORTED_TAX_COUNTRIES) {
                    TaxDraftProblem.CountryRequired
                } else {
                    null
                }
            is Custom -> when {
                !params.ratePct.isFinite() || params.ratePct < 0.0 || params.ratePct > 100.0 ->
                    TaxDraftProblem.RateOutOfRange
                params.costBasis !in COST_BASIS_STRATEGIES ->
                    TaxDraftProblem.CostBasisInvalid
                else -> null
            }
            is Manual -> when {
                // Rule 6 of the superRefine. Both-set is the one manual-mode
                // state the server rejects outright; neither-set is fine and
                // means "no prefill".
                defaultAmountEur != null && defaultRatePct != null ->
                    TaxDraftProblem.ManualAmountAndRate
                defaultAmountEur != null &&
                    (!defaultAmountEur.isFinite() || defaultAmountEur < 0.0) ->
                    TaxDraftProblem.ManualAmountInvalid
                defaultRatePct != null &&
                    (!defaultRatePct.isFinite() || defaultRatePct < 0.0 || defaultRatePct > 100.0) ->
                    TaxDraftProblem.ManualRateOutOfRange
                else -> null
            }
        }

    /** Convenience for enabling a Save control. */
    val isValid: Boolean get() = problem == null

    companion object {
        /**
         * Rebuild a draft from a settings payload the server sent back.
         *
         * Unknown / unsupported values fall back to [None] rather than throwing:
         * a client that hard-fails on a mode a newer server introduced would
         * strand the user on a screen they cannot leave, whereas showing "none"
         * is merely stale and self-corrects on the next save. The mode string is
         * carried by the caller when it needs to say "this came from a newer
         * app version" — see the settings screen's unknown-mode state.
         */
        fun fromSettings(
            mode: TaxMode?,
            country: String?,
            custom: CustomTaxParams?,
            manualDefaultAmountEur: Double?,
            manualDefaultRatePct: Double?,
        ): TaxSettingsDraft = when (mode) {
            "manual_per_trade" -> Manual(manualDefaultAmountEur, manualDefaultRatePct)
            "country_specific" -> CountrySpecific(country)
            // A `custom` payload with no params is malformed by contract; treat
            // it as the AT-equivalent set rather than crashing, so the user can
            // see and correct it.
            "custom" -> Custom(custom ?: AT_AS_CUSTOM_PARAMS)
            else -> None
        }
    }
}

/** The blocking reasons a [TaxSettingsDraft] can carry, one per superRefine rule. */
enum class TaxDraftProblem {
    /** `country_specific` mode requires a supported country. */
    CountryRequired,

    /** The custom engine's flat rate must be a finite 0–100 percent. */
    RateOutOfRange,

    /** The custom engine's cost basis must be one of [COST_BASIS_STRATEGIES]. */
    CostBasisInvalid,

    /** Manual mode takes a default amount OR a rate, never both. */
    ManualAmountAndRate,

    /** A manual default amount must be finite and non-negative. */
    ManualAmountInvalid,

    /** A manual default rate must be a finite 0–100 percent. */
    ManualRateOutOfRange,
}
