package at.bettertrack.app.domain

import at.bettertrack.app.data.repo.toRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaxSettingsDraft] against the six `superRefine` rules on
 * `updateTaxSettingsRequestSchema`.
 *
 * These are worth unit-testing rather than trusting to the screen because the
 * server's refusals are 400s whose message names a JSON path — useless to a user
 * and hard to reproduce on a device. Every rule below is a request the app must
 * never be able to compose in the first place; asserting that here means a
 * regression fails on a laptop instead of on the phone against real tax data.
 *
 * The mode/field consistency half is checked twice on purpose: once through
 * [TaxSettingsDraft.problem] (can the user press Save?) and once through
 * [toRequest] (does the body carry exactly the mode's own fields?). They are
 * different failures — the first ships a bad request, the second ships a
 * request that is rejected for a field the user never touched.
 */
class TaxSettingsDraftTest {

    // ── Rule 1 + 2: country belongs to country_specific, and only there ──────

    @Test
    fun `country_specific without a country cannot be saved`() {
        val draft = TaxSettingsDraft.CountrySpecific(country = null)
        assertFalse(draft.isValid)
        assertEquals(TaxDraftProblem.CountryRequired, draft.problem)
    }

    @Test
    fun `country_specific rejects a country the platform does not ship`() {
        // "US" is a plausible thing for a UI to hand over and is not in
        // SUPPORTED_TAX_COUNTRIES; the server would answer with an enum error.
        assertEquals(
            TaxDraftProblem.CountryRequired,
            TaxSettingsDraft.CountrySpecific("US").problem,
        )
    }

    @Test
    fun `each supported country is accepted`() {
        SUPPORTED_TAX_COUNTRIES.forEach { country ->
            val draft = TaxSettingsDraft.CountrySpecific(country)
            assertTrue("$country should be valid", draft.isValid)
            assertEquals(country, draft.toRequest().country)
        }
    }

    @Test
    fun `no other mode ever sends a country`() {
        // Rule 2 — the server rejects a country outside country_specific, so the
        // key must be ABSENT rather than null on every other branch.
        assertNull(TaxSettingsDraft.None.toRequest().country)
        assertNull(TaxSettingsDraft.Manual(defaultAmountEur = 10.0).toRequest().country)
        assertNull(TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS).toRequest().country)
    }

    // ── Rule 3 + 4: custom params belong to custom, and only there ───────────

    @Test
    fun `custom mode always carries its parameter set`() {
        val request = TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS).toRequest()
        assertEquals("custom", request.mode)
        val custom = requireNotNull(request.custom)
        assertEquals(27.5, custom.ratePct, 0.0)
        assertTrue(custom.lossOffset)
        assertTrue(custom.refund)
        assertTrue(custom.yearReset)
        assertFalse(custom.carryForward)
        assertEquals("moving-average", custom.costBasis)
    }

    @Test
    fun `no other mode ever sends custom params`() {
        assertNull(TaxSettingsDraft.None.toRequest().custom)
        assertNull(TaxSettingsDraft.CountrySpecific("AT").toRequest().custom)
        assertNull(TaxSettingsDraft.Manual(defaultRatePct = 25.0).toRequest().custom)
    }

    @Test
    fun `a custom rate outside 0-100 cannot be saved`() {
        val over = TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(ratePct = 100.01))
        val under = TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(ratePct = -0.01))
        assertEquals(TaxDraftProblem.RateOutOfRange, over.problem)
        assertEquals(TaxDraftProblem.RateOutOfRange, under.problem)
        // The boundaries themselves are legal — the server's bound is inclusive.
        assertTrue(TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(ratePct = 0.0)).isValid)
        assertTrue(TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(ratePct = 100.0)).isValid)
    }

    @Test
    fun `a non-finite custom rate cannot be saved`() {
        // A text field divided by zero, or parsed from "1e999". JSON cannot even
        // carry these, so they must never reach the serializer.
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals(
                TaxDraftProblem.RateOutOfRange,
                TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(ratePct = bad)).problem,
            )
        }
    }

    @Test
    fun `an unknown cost basis cannot be saved`() {
        assertEquals(
            TaxDraftProblem.CostBasisInvalid,
            TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(costBasis = "lifo")).problem,
        )
        COST_BASIS_STRATEGIES.forEach { strategy ->
            assertTrue(
                "$strategy should be valid",
                TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS.copy(costBasis = strategy)).isValid,
            )
        }
    }

    // ── Rule 5 + 6: the manual default ──────────────────────────────────────

    @Test
    fun `manual mode refuses an amount and a rate together`() {
        val both = TaxSettingsDraft.Manual(defaultAmountEur = 10.0, defaultRatePct = 25.0)
        assertEquals(TaxDraftProblem.ManualAmountAndRate, both.problem)
    }

    @Test
    fun `manual mode allows neither, meaning no prefill`() {
        val neither = TaxSettingsDraft.Manual()
        assertTrue(neither.isValid)
        val request = neither.toRequest()
        assertEquals("manual_per_trade", request.mode)
        assertNull(request.manualDefaultAmountEur)
        assertNull(request.manualDefaultRatePct)
    }

    @Test
    fun `manual mode allows exactly one of the two`() {
        val amount = TaxSettingsDraft.Manual(defaultAmountEur = 12.5)
        assertTrue(amount.isValid)
        assertEquals(12.5, amount.toRequest().manualDefaultAmountEur)
        assertNull(amount.toRequest().manualDefaultRatePct)

        val rate = TaxSettingsDraft.Manual(defaultRatePct = 27.5)
        assertTrue(rate.isValid)
        assertEquals(27.5, rate.toRequest().manualDefaultRatePct)
        assertNull(rate.toRequest().manualDefaultAmountEur)
    }

    @Test
    fun `a negative or non-finite manual amount cannot be saved`() {
        assertEquals(
            TaxDraftProblem.ManualAmountInvalid,
            TaxSettingsDraft.Manual(defaultAmountEur = -1.0).problem,
        )
        assertEquals(
            TaxDraftProblem.ManualAmountInvalid,
            TaxSettingsDraft.Manual(defaultAmountEur = Double.NaN).problem,
        )
    }

    @Test
    fun `a manual rate outside 0-100 cannot be saved`() {
        assertEquals(
            TaxDraftProblem.ManualRateOutOfRange,
            TaxSettingsDraft.Manual(defaultRatePct = 101.0).problem,
        )
        assertEquals(
            TaxDraftProblem.ManualRateOutOfRange,
            TaxSettingsDraft.Manual(defaultRatePct = -0.5).problem,
        )
    }

    @Test
    fun `no other mode ever sends a manual default`() {
        // Rule 5 keys its refusal on manualDefaultAmountEur, so a stray value on
        // a non-manual mode is rejected for a field the user never edited.
        val none = TaxSettingsDraft.None.toRequest()
        assertNull(none.manualDefaultAmountEur)
        assertNull(none.manualDefaultRatePct)
        val country = TaxSettingsDraft.CountrySpecific("DE").toRequest()
        assertNull(country.manualDefaultAmountEur)
        assertNull(country.manualDefaultRatePct)
    }

    // ── Round-tripping a server payload ─────────────────────────────────────

    @Test
    fun `fromSettings reproduces every known mode`() {
        assertEquals(
            TaxSettingsDraft.None,
            TaxSettingsDraft.fromSettings("none", null, null, null, null),
        )
        assertEquals(
            TaxSettingsDraft.CountrySpecific("FI"),
            TaxSettingsDraft.fromSettings("country_specific", "FI", null, null, null),
        )
        assertEquals(
            TaxSettingsDraft.Manual(defaultAmountEur = 9.0),
            TaxSettingsDraft.fromSettings("manual_per_trade", null, null, 9.0, null),
        )
        assertEquals(
            TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS),
            TaxSettingsDraft.fromSettings("custom", null, AT_AS_CUSTOM_PARAMS, null, null),
        )
    }

    @Test
    fun `an unknown mode degrades to none rather than throwing`() {
        // A mode a newer server introduced. Hard-failing here would strand the
        // user on a screen they cannot leave; the screen shows its own
        // "set on another device" state instead of this fallback.
        assertEquals(
            TaxSettingsDraft.None,
            TaxSettingsDraft.fromSettings("wealth_tax_2030", null, null, null, null),
        )
        assertEquals(
            TaxSettingsDraft.None,
            TaxSettingsDraft.fromSettings(null, null, null, null, null),
        )
    }

    @Test
    fun `a custom payload missing its params falls back to the AT set`() {
        // Malformed by contract. Showing the AT-equivalent parameters lets the
        // user see and correct the mode; crashing would not.
        assertEquals(
            TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS),
            TaxSettingsDraft.fromSettings("custom", null, null, null, null),
        )
    }

    @Test
    fun `every draft reports the mode string the contract names`() {
        assertEquals("none", TaxSettingsDraft.None.mode)
        assertEquals("manual_per_trade", TaxSettingsDraft.Manual().mode)
        assertEquals("country_specific", TaxSettingsDraft.CountrySpecific("AT").mode)
        assertEquals("custom", TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS).mode)
        // And the four of them are exactly TAX_MODES, in its declared order.
        assertEquals(
            TAX_MODES,
            listOf(
                TaxSettingsDraft.None.mode,
                TaxSettingsDraft.Manual().mode,
                TaxSettingsDraft.CountrySpecific("AT").mode,
                TaxSettingsDraft.Custom(AT_AS_CUSTOM_PARAMS).mode,
            ),
        )
    }
}
