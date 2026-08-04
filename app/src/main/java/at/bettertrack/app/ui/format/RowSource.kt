package at.bettertrack.app.ui.format

/**
 * Provenance of a ledger row (v5 `source` field on transactions and cash
 * movements).
 *
 * The platform validates this with a REGEX, not a closed enum:
 * `^(?:manual|standing-order|(?:import|sync):[a-z0-9][a-z0-9_-]*)$`
 * — new broker and sync slugs appear without any app release. So this parses
 * structurally and keeps an [Unknown] arm rather than pretending to know the
 * full set.
 */
sealed interface RowSource {
    /** Typed by the user. The overwhelming majority — deliberately renders NO badge. */
    data object Manual : RowSource

    /** Booked automatically by a standing order. */
    data object StandingOrder : RowSource

    /** Came in from a broker/CSV import. */
    data class Import(val slug: String) : RowSource

    /** Came in from a live sync — e.g. `sync:mirrorchain` for a group portfolio. */
    data class Sync(val slug: String) : RowSource

    /** Anything the regex allows that this build does not model. Shown verbatim. */
    data class Unknown(val raw: String) : RowSource
}

/** Parse a wire `source` token. A blank/absent value reads as [RowSource.Manual]. */
fun parseRowSource(source: String?): RowSource {
    val raw = source?.trim().orEmpty()
    return when {
        raw.isEmpty() || raw == "manual" -> RowSource.Manual
        raw == "standing-order" -> RowSource.StandingOrder
        raw.startsWith("import:") -> raw.removePrefix("import:")
            .takeIf { it.isNotEmpty() }
            ?.let { RowSource.Import(it) }
            ?: RowSource.Unknown(raw)

        raw.startsWith("sync:") -> raw.removePrefix("sync:")
            .takeIf { it.isNotEmpty() }
            ?.let { RowSource.Sync(it) }
            ?: RowSource.Unknown(raw)

        else -> RowSource.Unknown(raw)
    }
}

/**
 * Human label for a slug: `trade_republic` → "Trade Republic".
 *
 * Slugs are lowercase with `_`/`-` separators, so title-casing the words gets
 * the common cases right without shipping a lookup table that would go stale
 * the moment the platform adds a broker.
 */
fun prettySourceSlug(slug: String): String =
    slug.split('_', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** True when the row deserves a badge at all — manual rows stay unadorned. */
fun RowSource.isBadgeWorthy(): Boolean = this !is RowSource.Manual
