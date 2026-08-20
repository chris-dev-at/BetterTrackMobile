package at.bettertrack.web

/**
 * Which language and which number conventions this browser tab renders in
 * (web port, Phase W1).
 *
 * ## Why the browser is asked, and asked only once
 *
 * Android has three sources of truth stacked on top of each other — the device
 * locale, the per-app language the user picked in Settings → Appearance
 * (`AppCompatDelegate.setApplicationLocales`), and the `res/values-de`
 * resolution that follows from them. A browser has one: `navigator.language`,
 * the ordered preference list the user set in their browser or their OS. It is
 * the honest equivalent of "the device locale", and it is what seeds this build
 * until W2 brings the in-app EN/DE switch across as a `CompositionLocal`
 * (docs/KMP_PLAN.md §14.3, third sharp risk).
 *
 * It is read once at startup rather than observed: a language change in the
 * browser reloads the tab anyway, and pretending to be reactive about it would
 * be inventing a lifecycle the platform does not have.
 *
 * ## Two, not eighteen
 *
 * The app ships exactly two languages, so the mapping is: anything whose primary
 * subtag is `de` renders German with the de-AT number conventions the money
 * formatter contract specifies (`1.234,56 €`, a space before `%`); everything
 * else falls back to English (`1,234.56 €`, no space). Regional German variants
 * (`de-DE`, `de-CH`) map to de-AT deliberately — the app has one German, and it
 * is Austrian.
 */
enum class BtWebLocale(
    /** The BCP-47 tag this build actually renders as; also the `<html lang>`. */
    val tag: String,
    /** Thousands separator (rule 1 of the money contract). */
    val groupSeparator: Char,
    /** Decimal separator. */
    val decimalSeparator: Char,
    /** Rule 2: DE puts a space before `%`, EN does not. */
    val spaceBeforePercent: Boolean,
) {
    DE_AT(tag = "de-AT", groupSeparator = '.', decimalSeparator = ',', spaceBeforePercent = true),
    EN(tag = "en", groupSeparator = ',', decimalSeparator = '.', spaceBeforePercent = false),
}

/**
 * Seed the UI locale from `navigator.language`.
 *
 * Falls back to [BtWebLocale.EN] when the browser reports nothing — the same
 * direction Android's `themeModeFromName`-style decoders fall in: an
 * unrecognised value is not an error, it is an unspecified preference.
 */
fun seedWebLocale(): BtWebLocale =
    if (navigatorLanguage().lowercase().startsWith("de")) BtWebLocale.DE_AT else BtWebLocale.EN

/**
 * The raw tag, e.g. `de-AT`, `de-DE`, `en-GB`. Guarded for a host with no
 * `navigator` (the Node harness never reaches this file, but the guard is one
 * expression and it means the module cannot be taken down by its absence).
 */
fun navigatorLanguage(): String =
    js("(typeof navigator !== 'undefined' && navigator.language) ? navigator.language : ''")
