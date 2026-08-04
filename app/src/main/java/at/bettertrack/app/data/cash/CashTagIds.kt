package at.bettertrack.app.data.cash

/**
 * The ONE place a movement's v5 tag set crosses between its wire form
 * (`List<String>`) and its cached form
 * ([at.bettertrack.app.data.db.CashMovementEntity.tagIds], a comma-separated
 * `TEXT NOT NULL DEFAULT ''`).
 *
 * Room could hold this as a JSON blob behind a `@TypeConverter`, but tag ids are
 * UUIDs — no commas, no escaping problem — and a plain joined string keeps the
 * column greppable in a DB dump and cheap to write from a single UPDATE. What it
 * does NOT survive is a hand-rolled `split(",")`, because Kotlin's split answers
 * `[""]` for the empty string: every untagged row would then render one phantom
 * chip with a blank id. Hence one tested pair, used by everything.
 */

/** Cache form of [tagIds]. Blank entries are dropped, so `[]` encodes to `""`. */
fun encodeTagIds(tagIds: List<String>): String =
    tagIds.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(SEPARATOR)

/**
 * Wire form of a cached [encoded] set. **`""` decodes to an EMPTY list**, never
 * to `listOf("")` — that is the classic bug this pair exists to prevent.
 */
fun decodeTagIds(encoded: String): List<String> =
    if (encoded.isBlank()) {
        emptyList()
    } else {
        encoded.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

private const val SEPARATOR = ","
