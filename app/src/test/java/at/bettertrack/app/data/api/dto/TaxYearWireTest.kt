package at.bettertrack.app.data.api.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **The tax-year report row after the lock concept died (GO-LIVE #1425).**
 *
 * Verified against the DEPLOYED contract, `https://api.bettertrack.at/openapi.json`
 * on 2026-08-20 — openapi is authoritative over tick prose. `TaxYearListResponse`'s
 * row and `TaxYearReportResponse.summary` both declare exactly:
 *
 * ```
 * required: [year, lastChangedAt, realizedPnlEur, dividendsGrossEur,
 *            taxWithheldEur, taxRefundedEur, taxNetEur]
 * additionalProperties: false
 * lastChangedAt: { type: string, format: date-time, nullable: true }
 * ```
 *
 * There is no `locked`, no `currentYear`, no `unlockedYears` and no lock field of
 * any kind anywhere in the schema set, and `/settings/taxes/years` is a lone
 * `get` with no unlock/relock siblings.
 *
 * The DTO used to carry `locked: Boolean = false`, which was a mis-decode of an
 * older tri-state and is now moot twice over. What replaced it is nullable, and
 * the two cases below are the whole contract: a marker, or the honest absence of
 * one.
 */
class TaxYearWireTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val touchedYear = """
        {"year":2026,"lastChangedAt":"2026-08-14T09:31:07.482Z","realizedPnlEur":1240.5,
         "dividendsGrossEur":318.0,"taxWithheldEur":412.75,"taxRefundedEur":11.25,
         "taxNetEur":401.5}
    """.trimIndent()

    private val legacyYear = """
        {"year":2019,"lastChangedAt":null,"realizedPnlEur":-88.0,"dividendsGrossEur":0.0,
         "taxWithheldEur":0.0,"taxRefundedEur":0.0,"taxNetEur":0.0}
    """.trimIndent()

    @Test
    fun `a touched year carries its instant`() {
        val row = json.decodeFromString(TaxYearSummaryDto.serializer(), touchedYear)
        assertEquals(2026, row.year)
        assertEquals("2026-08-14T09:31:07.482Z", row.lastChangedAt)
        assertEquals(401.5, row.taxNetEur, 0.0)
        assertNull("no DE block on this row", row.de)
    }

    @Test
    fun `an untouched legacy year decodes its explicit null`() {
        // Null is REQUIRED-and-nullable on the wire, not omitted. Decoding it as
        // anything but null would invent a marker the server does not have.
        val row = json.decodeFromString(TaxYearSummaryDto.serializer(), legacyYear)
        assertEquals(2019, row.year)
        assertNull(row.lastChangedAt)
    }

    @Test
    fun `the list is enveloped under years, newest first`() {
        val wire = """{"years":[$touchedYear,$legacyYear]}"""
        val r = json.decodeFromString(TaxYearListResponse.serializer(), wire)
        assertEquals(listOf(2026, 2019), r.years.map { it.year })
        assertEquals("2026-08-14T09:31:07.482Z", r.years.first().lastChangedAt)
        assertNull(r.years.last().lastChangedAt)
    }

    @Test
    fun `the year report's summary is the same row shape`() {
        val wire = """{"year":2026,"summary":$touchedYear,"positions":[]}"""
        val r = json.decodeFromString(TaxYearReportResponse.serializer(), wire)
        assertEquals(2026, r.year)
        assertEquals("2026-08-14T09:31:07.482Z", r.summary.lastChangedAt)
    }

    @Test
    fun `a stray lock field from an old deployment is ignored, not decoded`() {
        // `ignoreUnknownKeys` is the house rule, so a server that has not rolled
        // forward cannot crash the report — and cannot resurrect the concept
        // either, because there is no property left for it to land in.
        val wire = touchedYear.dropLast(1) + ""","locked":true,"unlockedYears":[2026]}"""
        val row = json.decodeFromString(TaxYearSummaryDto.serializer(), wire)
        assertEquals(2026, row.year)
        assertEquals("2026-08-14T09:31:07.482Z", row.lastChangedAt)
    }

    @Test
    fun `the DE year-end block still rides on the row`() {
        val wire = touchedYear.dropLast(1) +
            ""","de":{"allowanceUsedEur":1000.0,"allowanceRemainingEur":0.0,
                "aktienPotInEur":0.0,"aktienPotOutEur":0.0,"sonstigePotInEur":0.0,
                "sonstigePotOutEur":0.0,"kapestEur":380.0,"soliEur":20.9}}"""
        val row = json.decodeFromString(TaxYearSummaryDto.serializer(), wire)
        assertEquals(1000.0, row.de!!.allowanceUsedEur, 0.0)
        assertEquals(20.9, row.de!!.soliEur, 0.0)
    }
}
