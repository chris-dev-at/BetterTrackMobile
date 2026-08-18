package at.bettertrack.app.data.repo

/**
 * The purpose category a portfolio is filed under — a literal port of the web's
 * `PORTFOLIO_KINDS` (`apps/web/src/user/portfolio/portfolioKinds.ts:33`), in the
 * same picker order.
 *
 * ⚠️ **NAMING.** `kind` is the internal name only. To the user this is the
 * portfolio's **Icon** — a colour plus a glyph, not a taxonomy they have to
 * reason about. The web's own file carries the same warning, and the settings
 * section, the labels and the helper copy all say "Icon". Rename the copy, never
 * the type or [wire] (that would need a migration).
 *
 * **Server-backed since 2026-08-18.** [wire] matches the platform's
 * `portfolioKindSchema` tokens 1:1, and the value round-trips through
 * `PATCH /portfolios/{id}.kind` like any other portfolio field. It was
 * client-only before that, on the mistaken belief that the API had no field for
 * it; see [at.bettertrack.app.data.db.MetaEntity.KEY_PORTFOLIO_KINDS] for the
 * one-time migration that carries old local choices up.
 */
enum class BtPortfolioKind(val wire: String) {
    Private("private"),
    Family("family"),
    Business("business"),
    Savings("savings"),
    Property("property"),
    ;

    companion object {
        /** The kind an unclassified portfolio falls back to (web `DEFAULT_PORTFOLIO_KIND`). */
        val Default = Private

        /**
         * Parse a stored wire name, falling back to [Default].
         *
         * Unknown values are *ignored rather than rejected*: `PORTFOLIO_KINDS`
         * can grow on the web, and a portfolio filed under a kind this build has
         * never heard of must still render — as the default icon, not as a hole.
         */
        fun fromWire(value: String?): BtPortfolioKind =
            entries.firstOrNull { it.wire == value } ?: Default
    }
}

/**
 * The `group` marker's tint slot in
 * [at.bettertrack.app.ui.theme.BtColors.kindTints] — last, after the five kinds.
 *
 * A group (MIRRORCHAIN) copy gets its own hue for a small corner marker, and for
 * nothing else. Being shared never overrides the glyph or the chip hue the user
 * chose: the web learned that the hard way — forcing group portfolios onto the
 * group glyph made the Icon setting a silent no-op for exactly the portfolios
 * people most want to tell apart. A kind's own tint is always `kind.ordinal`.
 */
const val BT_KIND_GROUP_TINT_SLOT = 5
