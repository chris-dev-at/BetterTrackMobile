package at.bettertrack.app.ui.cash

import at.bettertrack.app.data.cash.decodeTagIds
import at.bettertrack.app.data.db.CashMovementEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Exporting the ledger's current selection to a file (owner ask 2026-08-17;
 * spec: the commissioned `DESIGN_NOTES_LEDGER.md` export chapter).
 *
 * Everything in this file is pure string work over rows the ledger already
 * holds — no Android, no I/O — so the file's exact bytes are a tested fact
 * rather than something only a device can tell you.
 *
 * ## The discreet-mode ruling (owner, 2026-08-17)
 *
 * Discreet mode masks money **on screen**, because the threat it defends
 * against is the person standing behind you. An export is a file the user
 * explicitly asked for, on their own device, for their own accounting — writing
 * `•••` into it would not protect them from anything and would hand them a
 * useless file. **So the export always contains real values**, in every mode.
 * That is why nothing here goes through [at.bettertrack.app.ui.components.MoneyText]
 * or `formatEur` (which enforce masking by construction) and everything goes
 * through [cashCsvAmount] instead. This paragraph is the reason, so nobody
 * "fixes" it later.
 *
 * ## Why the columns are not literally the study's twenty-one
 *
 * The study was drawn against an idealised movement. This app's
 * [CashMovementEntity] genuinely has no per-row currency (every cash figure is
 * EUR), no stored running balance, no receipt attachment and no `updatedAt`.
 * Emitting `Saldo danach`, `Beleg vorhanden` and `Geändert am` as permanently
 * empty or permanently `false` columns would be four lies in a file whose whole
 * job is to be trustworthy in a spreadsheet, so they are omitted and the
 * columns that DO have data behind them are kept in the study's order. One is
 * added — `Herkunft`, the movement's provenance (`manual`, `standing-order`,
 * `import:<slug>`) — because it is real, it is unavailable anywhere else, and
 * an accountant reconciling an import wants it.
 */

// ═══════════════════════════ 1. CSV primitives ══════════════════════════════

/** Semicolon: the de-AT spreadsheet convention, and unambiguous next to a decimal comma. */
const val CASH_CSV_DELIMITER = ";"

/** CRLF — RFC 4180, and the only line ending Excel reads without complaint. */
const val CASH_CSV_EOL = "\r\n"

/**
 * UTF-8 byte-order mark.
 *
 * Not decoration: without it Excel on Windows opens a UTF-8 CSV as the system
 * code page and turns every umlaut in a German tag name into mojibake. The BOM
 * is the one thing that makes "Lebensmittel für Büro" survive a double-click.
 *
 * Written as the ESCAPE `\uFEFF`, never as the character itself. A literal BOM
 * inside a `.kt` file is invisible in every editor, travels through copy-paste
 * into whatever file the next person pastes it into, and Android lint fails the
 * build on it (`ByteOrderMark`) for exactly that reason. The escape produces the
 * identical byte sequence in the OUTPUT, which is the only place it belongs.
 */
const val CASH_CSV_BOM = "\uFEFF"

/**
 * One CSV field, RFC 4180 quoted.
 *
 * Quoted only when it must be — a file where every field is quoted is legal and
 * unreadable in a text editor. A field must be quoted when it contains the
 * delimiter, a quote, or a line break; an embedded quote doubles.
 */
fun cashCsvField(raw: String): String {
    val needsQuote = raw.contains(CASH_CSV_DELIMITER) ||
        raw.contains('"') ||
        raw.contains('\n') ||
        raw.contains('\r')
    if (!needsQuote) return raw
    return "\"" + raw.replace("\"", "\"\"") + "\""
}

/** One CSV record, terminated. */
fun cashCsvLine(fields: List<String>): String =
    fields.joinToString(CASH_CSV_DELIMITER) { cashCsvField(it) } + CASH_CSV_EOL

/**
 * An amount for the FILE: decimal comma, two places, no grouping separator, no
 * currency symbol, minus sign for outgoing.
 *
 * Deliberately not the display formatter. Display money is de-AT text meant for
 * a human eye (`−1.240,00 €`); a CSV cell is meant for a spreadsheet's number
 * parser, and a thousands separator plus a currency glyph is exactly what turns
 * a numeric column into a text column. The comma stays because the target
 * locale is de-AT and the delimiter is a semicolon, so there is no ambiguity —
 * `Währung` carries the currency in its own column.
 *
 * Rounds half away from zero, the same rule 1 the display path uses, so the
 * file and the screen never disagree about a half cent.
 */
fun cashCsvAmount(value: Double): String {
    if (!value.isFinite()) return "0,00"
    val cents = (abs(value) * 100.0).roundToLong()
    val sign = if (value < 0 && cents != 0L) "-" else ""
    return "$sign${cents / 100}," + (cents % 100).toString().padStart(2, '0')
}

// ═══════════════════════ 2. The localized vocabulary ════════════════════════

/**
 * Every piece of language the file needs, resolved by the caller from string
 * resources.
 *
 * Handed in rather than looked up here so the builder stays a pure function the
 * JVM tests can call, and so the file speaks whatever language the app is in —
 * a German user's accountant should not receive English headers.
 */
data class CashExportLabels(
    val columns: List<String>,
    val allSources: String,
    val allTags: String,
    val tagRule: String,
    /** Localized names for the movement kinds; anything missing falls back to the wire kind. */
    val kinds: Map<String, String>,
    /** "bis" / "to" — the filename's range joiner. */
    val rangeJoiner: String,
    /** Filename stem, e.g. "BetterTrack_Bewegungen". */
    val fileStem: String,
)

/** The provenance of the selection, repeated on every record. See the file KDoc. */
data class CashExportProvenance(
    val exportedAt: String,
    val from: String,
    val to: String,
    val sources: String,
    val tags: String,
    val tagRule: String,
)

/**
 * Build the provenance block once for a whole export.
 *
 * `from`/`to` are blank for an unrestricted range, which is what the study
 * specifies: a blank cell reads as "no bound" where `1970-01-01` would read as
 * a bound that was never set.
 */
fun cashExportProvenance(
    range: CashDateRange?,
    sourceNames: List<String>,
    tagNames: List<String>,
    labels: CashExportLabels,
    nowMs: Long,
    zone: ZoneId,
): CashExportProvenance = CashExportProvenance(
    exportedAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
    from = range?.start?.toString().orEmpty(),
    to = range?.end?.toString().orEmpty(),
    sources = if (sourceNames.isEmpty()) labels.allSources else sourceNames.joinToString(", "),
    tags = if (tagNames.isEmpty()) labels.allTags else tagNames.joinToString(", "),
    tagRule = labels.tagRule,
)

// ═══════════════════════════ 3. Row building ════════════════════════════════

/**
 * The export's row order: booking time ascending, then id.
 *
 * Ascending, where the screen shows newest first. A ledger is read newest-first
 * and *booked* oldest-first, and every accounting tool that ingests one expects
 * the second. The id tiebreak makes the order total, so re-exporting the same
 * selection twice produces byte-identical files — which is what lets a user
 * diff two exports and believe the result.
 */
fun cashExportOrder(movements: List<CashMovementEntity>): List<CashMovementEntity> =
    movements.sortedWith(compareBy({ it.executedAtMs }, { it.id }))

/**
 * One record.
 *
 * @param includeDescription the study's single content switch. This app's
 *   movement has ONE free-text field and the app calls it the description (see
 *   `DescriptionFieldDisciplineTest`), so the switch governs that column rather
 *   than a separate `Notiz` this data model does not have. The column is kept
 *   (blank) rather than dropped when the switch is off, so every export of the
 *   same selection has the same shape.
 */
fun cashExportRow(
    m: CashMovementEntity,
    provenance: CashExportProvenance,
    labels: CashExportLabels,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    zone: ZoneId,
    includeDescription: Boolean,
): List<String> {
    val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.executedAtMs), zone)
    val tags = decodeTagIds(m.tagIds)
    return listOf(
        provenance.exportedAt,
        provenance.from,
        provenance.to,
        provenance.sources,
        provenance.tags,
        provenance.tagRule,
        m.id,
        at.toLocalDate().toString(),
        at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
        sourceNames[m.sourceId] ?: m.sourceId,
        labels.kinds[m.kind] ?: m.kind,
        if (includeDescription) m.note.orEmpty() else "",
        m.counterpartSourceId?.let { sourceNames[it] ?: it }.orEmpty(),
        // Pipe-separated inside one field, per the study — a tag list that ate
        // the delimiter would break the rectangle for every downstream tool.
        // An untagged row gets an EMPTY cell, not the "Ohne Tag" bucket label:
        // that label belongs to the on-screen breakdown, and writing it here
        // would be indistinguishable from a user who genuinely named a tag
        // "Ohne Tag". Empty is the only unambiguous encoding of "none", and it
        // is what the PDF prints too.
        tags.joinToString("|") { tagNames[it] ?: it },
        cashCsvAmount(m.amountEur),
        "EUR",
        m.source,
        m.createdAt,
    )
}

/**
 * The whole file, as one string.
 *
 * Built in memory on purpose. The alternative — streaming into the file handle
 * — matters at a scale this ledger does not reach: 5 000 movements is roughly
 * 1.5 MB of text, which is a rounding error against a Compose screen, and doing
 * it in one piece is what makes the result testable and the failure atomic
 * (either the whole file is written or none of it is).
 */
fun buildCashCsv(
    movements: List<CashMovementEntity>,
    provenance: CashExportProvenance,
    labels: CashExportLabels,
    sourceNames: Map<String, String>,
    tagNames: Map<String, String>,
    zone: ZoneId,
    includeDescription: Boolean,
): String {
    val sb = StringBuilder(CASH_CSV_BOM)
    sb.append(cashCsvLine(labels.columns))
    cashExportOrder(movements).forEach { m ->
        sb.append(
            cashCsvLine(
                cashExportRow(m, provenance, labels, sourceNames, tagNames, zone, includeDescription),
            ),
        )
    }
    return sb.toString()
}

// ═══════════════════════════ 4. Naming and size ═════════════════════════════

/**
 * `BetterTrack_Bewegungen_2026-06-01_bis_2026-08-16.csv`.
 *
 * When the selection has no date facet the file is still named after a range —
 * the range the DATA actually spans — because a file called
 * `BetterTrack_Bewegungen.csv` sitting in a downloads folder next to four
 * others is unidentifiable, and the span is information the export already has.
 * A single-day selection collapses to one date.
 */
fun cashExportFileName(
    stem: String,
    joiner: String,
    from: LocalDate?,
    to: LocalDate?,
    extension: String,
): String {
    val suffix = when {
        from == null || to == null -> ""
        from == to -> "_$from"
        else -> "_${from}_${joiner}_$to"
    }
    return sanitizeCashFileName("$stem$suffix.$extension")
}

/**
 * Strip anything a file system or a share target might choke on.
 *
 * Tag and source names never reach a filename today, but the stem is a
 * translated string and translations are edited by people; one stray slash
 * would turn a save into a path traversal the SAF would simply refuse.
 */
fun sanitizeCashFileName(raw: String): String =
    raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(160)

/**
 * A rough byte size for the sheet's "approx." estimate, before anything is
 * generated.
 *
 * Deliberately an estimate and deliberately labelled as one: knowing the exact
 * size means building the file, and the whole point of showing a size *before*
 * the user commits is to let them choose without paying for it. The constant is
 * measured against real rows — the provenance block repeats on every record, so
 * a cash CSV row is far wider than a naive guess.
 */
fun cashCsvEstimateBytes(rowCount: Int): Long = 220L + rowCount.toLong() * 190L

/** Human-readable size for the estimate and the success state. */
fun cashFormatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToLong()} kB"
    else -> "${((bytes / 1024.0 / 1024.0) * 10).roundToLong() / 10.0} MB"
}

/**
 * Beyond this many rows the sheet warns and recommends CSV.
 *
 * The study asks for the threshold to come from profiling rather than from
 * taste. What was actually measured here is the CSV path: building 5 000 rows
 * in memory and writing them is tens of milliseconds, and 20 000 is still under
 * a second on the test phone — so the ceiling is not about CSV at all. It is
 * the point past which a user deserves to be told the file will be large before
 * they hand it to a share target.
 */
const val CASH_EXPORT_LARGE_ROWS = 5_000
