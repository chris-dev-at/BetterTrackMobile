package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.db.CashMovementEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger export's file bytes (owner ask 2026-08-17).
 *
 * A CSV is read by a spreadsheet, not by a person forgiving a rendering
 * mistake: an unescaped semicolon inside a note shifts every later column of
 * that row, a thousands separator turns a numeric column into text, and a
 * missing BOM turns every umlaut into mojibake in Excel. None of those failures
 * are visible on the phone that produced the file, which is exactly why the
 * bytes are asserted here rather than eyeballed after a share.
 */
class CashExportTest {

    private val zone: ZoneId = ZoneId.of("Europe/Vienna")

    private val labels = CashExportLabels(
        columns = listOf(
            "Exportiert am", "Filter von", "Filter bis", "Filter Quellen", "Filter Tags",
            "Tag-Verknüpfung", "Buchungs-ID", "Datum", "Uhrzeit", "Quelle", "Art",
            "Beschreibung", "Gegenpartei", "Tags", "Betrag", "Währung", "Herkunft",
            "Erstellt am",
        ),
        allSources = "Alle Quellen",
        allTags = "Alle Tags",
        tagRule = "Mindestens ein ausgewählter Tag",
        kinds = mapOf("withdrawal" to "Bezahlt", "deposit" to "Erhalten"),
        rangeJoiner = "bis",
        fileStem = "BetterTrack_Bewegungen",
    )

    private fun movement(
        id: String,
        amountEur: Double,
        note: String? = null,
        tagIds: String = "",
        date: LocalDate = LocalDate.of(2026, 8, 16),
        hour: Int = 9,
        sourceId: String = "src-main",
    ) = CashMovementEntity(
        id = id,
        portfolioId = "p1",
        sourceId = sourceId,
        kind = if (amountEur >= 0) "deposit" else "withdrawal",
        amountEur = amountEur,
        transactionId = null,
        transferId = null,
        counterpartSourceId = null,
        executedAt = date.toString(),
        executedAtMs = date.atTime(hour, 30).atZone(zone).toInstant().toEpochMilli(),
        note = note,
        createdAt = "2026-08-16T07:30:00Z",
        tagIds = tagIds,
    )

    private val provenance = CashExportProvenance(
        exportedAt = "2026-08-17 21:00:00",
        from = "2026-06-01",
        to = "2026-08-16",
        sources = "Alltagskonto",
        tags = "Lebensmittel",
        tagRule = "Mindestens ein ausgewählter Tag",
    )

    private val sourceNames = mapOf("src-main" to "Alltagskonto", "src-cash" to "Bargeld")
    private val tagNames = mapOf("t-food" to "Lebensmittel", "t-fuel" to "Sprit")

    // ══════════════════════════ CSV primitives ══════════════════════════════

    @Test
    fun `a plain field is not quoted`() {
        assertEquals("Miete", cashCsvField("Miete"))
        assertEquals("", cashCsvField(""))
    }

    @Test
    fun `a field containing the delimiter is quoted`() {
        assertEquals("\"Rewe; Billa\"", cashCsvField("Rewe; Billa"))
    }

    @Test
    fun `an embedded quote is doubled inside a quoted field`() {
        assertEquals("\"He said \"\"hi\"\"\"", cashCsvField("He said \"hi\""))
    }

    @Test
    fun `a line break inside a note keeps the record rectangular`() {
        val field = cashCsvField("line one\nline two")
        assertTrue(field.startsWith("\"") && field.endsWith("\""))
        assertTrue(field.contains("\n"))
    }

    @Test
    fun `records end with CRLF`() {
        assertEquals("a;b\r\n", cashCsvLine(listOf("a", "b")))
    }

    // ══════════════════════════ Amounts ═════════════════════════════════════

    @Test
    fun `amounts use a decimal comma and no thousands separator`() {
        // The whole point: a spreadsheet must parse this as a NUMBER.
        assertEquals("2900,00", cashCsvAmount(2900.0))
        assertEquals("-1240,00", cashCsvAmount(-1240.0))
        assertEquals("1234567,89", cashCsvAmount(1234567.89))
        assertEquals("0,05", cashCsvAmount(0.05))
    }

    @Test
    fun `amounts round half away from zero, like every figure on screen`() {
        assertEquals("2,13", cashCsvAmount(2.125))
        assertEquals("-2,13", cashCsvAmount(-2.125))
    }

    @Test
    fun `an exact zero never carries a minus sign`() {
        assertEquals("0,00", cashCsvAmount(0.0))
        assertEquals("0,00", cashCsvAmount(-0.0))
        assertEquals("0,00", cashCsvAmount(-0.001))
    }

    @Test
    fun `a non-finite amount degrades to zero rather than writing NaN into a cell`() {
        assertEquals("0,00", cashCsvAmount(Double.NaN))
        assertEquals("0,00", cashCsvAmount(Double.POSITIVE_INFINITY))
    }

    // ══════════════════════════ Rows and order ══════════════════════════════

    @Test
    fun `the export is ordered oldest first, then by id`() {
        val rows = cashExportOrder(
            listOf(
                movement("c", 1.0, date = LocalDate.of(2026, 8, 16)),
                movement("a", 1.0, date = LocalDate.of(2026, 6, 1)),
                movement("b", 1.0, date = LocalDate.of(2026, 8, 16)),
            ),
        )
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
    }

    @Test
    fun `a record carries the selection's provenance and the movement's own facts`() {
        val row = cashExportRow(
            m = movement("mv-1", -1240.0, note = "Miete", tagIds = "t-food"),
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = true,
        )
        assertEquals(labels.columns.size, row.size)
        assertEquals("2026-08-17 21:00:00", row[0])
        assertEquals("2026-06-01", row[1])
        assertEquals("2026-08-16", row[2])
        assertEquals("mv-1", row[6])
        assertEquals("2026-08-16", row[7])
        assertEquals("09:30:00", row[8])
        assertEquals("Alltagskonto", row[9])
        assertEquals("Bezahlt", row[10])
        assertEquals("Miete", row[11])
        assertEquals("Lebensmittel", row[13])
        assertEquals("-1240,00", row[14])
        assertEquals("EUR", row[15])
    }

    @Test
    fun `an untagged row gets an empty tag cell, never a bucket label`() {
        // "Ohne Tag" is on-screen vocabulary. In the file it would be
        // indistinguishable from a user who named a tag that.
        val row = cashExportRow(
            m = movement("mv-untagged", -5.0, tagIds = ""),
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = true,
        )
        assertEquals("", row[13])
    }

    @Test
    fun `several tags are pipe-separated inside one field`() {
        val row = cashExportRow(
            m = movement("mv-2", -20.0, tagIds = "t-food,t-fuel"),
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = true,
        )
        assertEquals("Lebensmittel|Sprit", row[13])
    }

    @Test
    fun `an unknown kind or source falls back to its wire value, never to a blank`() {
        val odd = movement("mv-3", 5.0, sourceId = "src-gone").copy(kind = "some_future_kind")
        val row = cashExportRow(
            m = odd,
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = true,
        )
        assertEquals("src-gone", row[9])
        assertEquals("some_future_kind", row[10])
    }

    @Test
    fun `switching descriptions off blanks the column but keeps the shape`() {
        val row = cashExportRow(
            m = movement("mv-4", -9.0, note = "private"),
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = false,
        )
        assertEquals(labels.columns.size, row.size)
        assertEquals("", row[11])
    }

    @Test
    fun `provenance is blank on both ends for an unrestricted selection`() {
        val p = cashExportProvenance(
            range = null,
            sourceNames = emptyList(),
            tagNames = emptyList(),
            labels = labels,
            nowMs = LocalDate.of(2026, 8, 17).atTime(21, 0).atZone(zone).toInstant().toEpochMilli(),
            zone = zone,
        )
        assertEquals("", p.from)
        assertEquals("", p.to)
        assertEquals("Alle Quellen", p.sources)
        assertEquals("Alle Tags", p.tags)
    }

    // ══════════════════════════ The whole file ══════════════════════════════

    @Test
    fun `the file opens with a BOM and a header row and holds one record per movement`() {
        val csv = buildCashCsv(
            movements = listOf(
                movement("b", -20.0, note = "Sprit", date = LocalDate.of(2026, 8, 10)),
                movement("a", 2900.0, note = "Gehalt", date = LocalDate.of(2026, 8, 1)),
            ),
            provenance = provenance,
            labels = labels,
            sourceNames = sourceNames,
            tagNames = tagNames,
            zone = zone,
            includeDescription = true,
        )
        assertTrue("missing UTF-8 BOM", csv.startsWith(CASH_CSV_BOM))
        val lines = csv.removePrefix(CASH_CSV_BOM).split(CASH_CSV_EOL).filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("Exportiert am;Filter von;"))
        // Oldest first: the salary precedes the fuel.
        assertTrue(lines[1].contains("Gehalt"))
        assertTrue(lines[2].contains("Sprit"))
    }

    @Test
    fun `the same selection exported twice is byte-identical`() {
        val rows = listOf(movement("b", -20.0), movement("a", 2900.0))
        val first = buildCashCsv(rows, provenance, labels, sourceNames, tagNames, zone, true)
        val second = buildCashCsv(rows.reversed(), provenance, labels, sourceNames, tagNames, zone, true)
        assertEquals(first, second)
    }

    // ══════════════════════════ Naming and size ═════════════════════════════

    @Test
    fun `the filename states the range it covers`() {
        assertEquals(
            "BetterTrack_Bewegungen_2026-06-01_bis_2026-08-16.csv",
            cashExportFileName(
                labels.fileStem, labels.rangeJoiner,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 16), "csv",
            ),
        )
    }

    @Test
    fun `a single-day selection collapses to one date`() {
        assertEquals(
            "BetterTrack_Bewegungen_2026-08-16.pdf",
            cashExportFileName(
                labels.fileStem, labels.rangeJoiner,
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 16), "pdf",
            ),
        )
    }

    @Test
    fun `an unknown span still produces a usable name`() {
        assertEquals(
            "BetterTrack_Bewegungen.csv",
            cashExportFileName(labels.fileStem, labels.rangeJoiner, null, null, "csv"),
        )
    }

    @Test
    fun `a name can never carry a path separator into the save picker`() {
        val sanitized = sanitizeCashFileName("BetterTrack/../etc:passwd?.csv")
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("?"))
    }

    @Test
    fun `the size estimate grows with the row count and reads in units`() {
        assertTrue(cashCsvEstimateBytes(1000) > cashCsvEstimateBytes(10))
        assertEquals("512 B", cashFormatBytes(512))
        assertEquals("2 kB", cashFormatBytes(2048))
        assertEquals("1.5 MB", cashFormatBytes((1.5 * 1024 * 1024).toLong()))
    }

    // ══════════════════════════ PDF pagination ══════════════════════════════

    @Test
    fun `pagination gives the first page fewer rows, for the filter block`() {
        val slices = cashPdfPageSlices(rowCount = 100, firstPageRows = 40, laterPageRows = 52)
        assertEquals(3, slices.size)
        assertEquals(0..39, slices[0])
        assertEquals(40..91, slices[1])
        assertEquals(92..99, slices[2])
    }

    @Test
    fun `a short export is exactly one page and never a blank second one`() {
        val slices = cashPdfPageSlices(rowCount = 40, firstPageRows = 40, laterPageRows = 52)
        assertEquals(1, slices.size)
        assertEquals(0..39, slices[0])
    }

    @Test
    fun `every row lands on exactly one page`() {
        val slices = cashPdfPageSlices(rowCount = 137, firstPageRows = 40, laterPageRows = 52)
        assertEquals(137, slices.sumOf { it.last - it.first + 1 })
        assertEquals(0, slices.first().first)
        assertEquals(136, slices.last().last)
    }

    @Test
    fun `zero rows produce no pages at all`() {
        assertTrue(cashPdfPageSlices(0, 40, 52).isEmpty())
    }
}
