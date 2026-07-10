package at.bettertrack.app.data.i18n

/**
 * The user-selectable UI languages for the in-app switch (spec §6.12, §13.3).
 * [System] follows the device language; [English]/[German] force a per-app locale.
 * Money/date formatting is already locale-driven elsewhere — this only changes the
 * string language + the formatting locale, never the formatting logic.
 */
enum class AppLanguage(val tag: String) {
    System(""),
    English("en"),
    German("de");

    companion object {
        /**
         * Resolve a BCP-47 language tag (as stored by AppCompatDelegate) to an
         * [AppLanguage]. Empty/blank ⇒ [System]; anything starting `de` ⇒ [German];
         * anything starting `en` ⇒ [English]; otherwise [System] (unknown → follow
         * device). Case/region-insensitive ("de-AT", "DE", "en_US" all resolve).
         */
        fun fromTag(tag: String?): AppLanguage {
            val primary = tag?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-').orEmpty()
            return when (primary) {
                "" -> System
                "de" -> German
                "en" -> English
                else -> System
            }
        }
    }
}
