package at.bettertrack.app.domain

/**
 * Per-portfolio settings scoping — a **literal** Kotlin port of
 * `packages/domain/src/settingsScope.ts` at the commit pinned in
 * `tools/domain-vectors/PINNED_AT`.
 *
 * Any setting that can sensibly be scoped per portfolio resolves through one
 * cascade:
 *
 *     effective(setting, portfolio) = portfolio override ?? user default ?? system default
 *
 * This is the pure heart of the framework — the three layers are read by the
 * caller (each `null` when that layer is unset) and folded here into the
 * effective value plus the layer it came from. The [ResolvedSetting.source]
 * powers the "inheriting default / overridden" UI: a portfolio with no override
 * tracks the user's LIVE default (link semantics), so later changing a default
 * retro-affects portfolios that never overrode, and a reset-to-default simply
 * drops the override back to inheriting.
 *
 * Domain code: no I/O, imports nothing.
 */

/** Which scope a resolved per-portfolio setting was taken from. */
enum class SettingSource(val wire: String) {
    PORTFOLIO("portfolio"),
    USER("user"),
    SYSTEM("system"),
}

/** A resolved setting: its effective value and the layer that supplied it. */
data class ResolvedSetting<T>(val value: T, val source: SettingSource)

/**
 * Fold the three scoping layers into the effective value. A layer counts as
 * "set" when it is not `null`; the first set layer, walked
 * override → user default → system default, wins. The system default is always
 * a concrete value, so a [ResolvedSetting] never has an absent `value`.
 *
 * **Translation note (plan §3.3 rule 6).** The TypeScript guards with
 * `!== null && !== undefined` because JavaScript has two distinct absent values.
 * Kotlin has one, so both collapse to a single `!= null` check — semantically
 * identical, and the reason the vector suite's "treats undefined like null"
 * case is indistinguishable from its "falls back on null" sibling here.
 *
 * The cascade keys off absence ONLY: a legitimately falsy value like `0`, `""`
 * or `false` is a real override and must not fall through. Kotlin's null-check
 * gives that for free, where a truthiness test would not.
 */
fun <T : Any> resolvePortfolioSetting(
    override: T?,
    userDefault: T?,
    systemDefault: T,
): ResolvedSetting<T> {
    if (override != null) {
        return ResolvedSetting(override, SettingSource.PORTFOLIO)
    }
    if (userDefault != null) {
        return ResolvedSetting(userDefault, SettingSource.USER)
    }
    return ResolvedSetting(systemDefault, SettingSource.SYSTEM)
}
