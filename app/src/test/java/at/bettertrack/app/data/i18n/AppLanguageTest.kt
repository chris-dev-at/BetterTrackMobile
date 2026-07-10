package at.bettertrack.app.data.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tag↔language mapping for the Step-18 language switch (spec §6.12). */
class AppLanguageTest {

    @Test fun empty_or_null_is_system() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("   "))
    }

    @Test fun english_variants() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("EN"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en_GB"))
    }

    @Test fun german_variants() {
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de-AT"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("DE"))
        assertEquals(AppLanguage.German, AppLanguage.fromTag("de_CH"))
    }

    @Test fun unknown_language_follows_system() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("es-ES"))
    }

    @Test fun tags_are_stable() {
        assertEquals("", AppLanguage.System.tag)
        assertEquals("en", AppLanguage.English.tag)
        assertEquals("de", AppLanguage.German.tag)
    }
}
