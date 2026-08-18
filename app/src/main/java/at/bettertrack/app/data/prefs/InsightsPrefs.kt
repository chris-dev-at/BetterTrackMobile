package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the Insights page remembers its **layout** and its **per-card
 * overrides**.
 *
 * Deliberately a sibling of [VizPrefs] rather than an extension of it. The two
 * answer different questions and must be able to disagree:
 *
 *  - [VizPrefs] holds the *family* default — "allocation charts in this app look
 *    like a treemap" — and is read by the portfolio page, the cash page and the
 *    widget builder alike.
 *  - This store holds one insight CARD's override of that default, plus the
 *    page's visible order.
 *
 * Keeping them in separate key spaces is what makes the precedence rule in
 * `InsightsConfig.kt` true rather than merely intended: there is no code path
 * by which configuring an insight card writes a family preference, because this
 * class cannot address one.
 *
 * Device-scoped, in the shared `bt_device_prefs` file, for the same reason the
 * chart preference is: a page layout carries no account data, and a phone that
 * reopens on the page you arranged is simply friendlier. Nothing here is a
 * money value, so nothing here needs the vault.
 *
 * ## What is deliberately NOT stored
 *
 * `Beträge ausblenden` for shared images. The study's privacy ruling requires it
 * to default on *every time image sharing starts*, and an off choice must never
 * become the next default — so it has no key here, and `InsightsPrivacyRulingTest`
 * asserts that no key with that meaning ever appears.
 */
class InsightsPrefs internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    private val _cards = MutableStateFlow(loadCards())
    private val _page = MutableStateFlow(prefs.getString(KEY_PAGE, null))

    /** Every configured card, keyed by `BtInsight.name`. Absent = inherits. */
    val cards: StateFlow<Map<String, String>> = _cards.asStateFlow()

    /** The encoded page order, or null while the page is the default five. */
    val page: StateFlow<String?> = _page.asStateFlow()

    /** The stored override for one insight, or null when it was never configured. */
    fun cardFor(insight: String): String? = _cards.value[insight]

    /**
     * Save (or, with a null [encoded], forget) one card's override.
     *
     * A null removes the key rather than writing a "default" string, so
     * `Auf Familienstandard zurücksetzen` genuinely restores "never chose" and
     * the card resumes following the family — including future changes to it.
     */
    fun setCard(insight: String, encoded: String?) {
        val key = KEY_CARD_PREFIX + insight
        prefs.edit { if (encoded == null) remove(key) else putString(key, encoded) }
        _cards.value = _cards.value.toMutableMap().apply {
            if (encoded == null) remove(insight) else put(insight, encoded)
        }
    }

    /**
     * Save (or, with a null [encoded], forget) the page order.
     *
     * `Standardansicht wiederherstellen` passes null. Note what it does not
     * touch: every `KEY_CARD_PREFIX` entry survives, because restoring the
     * default VIEW must not discard saved card configuration — the confirmation
     * dialog promises exactly that, and this is where the promise is kept.
     */
    fun setPage(encoded: String?) {
        prefs.edit { if (encoded == null) remove(KEY_PAGE) else putString(KEY_PAGE, encoded) }
        _page.value = encoded
    }

    private fun loadCards(): Map<String, String> = prefs.all
        .asSequence()
        .filter { it.key.startsWith(KEY_CARD_PREFIX) }
        .mapNotNull { entry ->
            val value = entry.value as? String ?: return@mapNotNull null
            entry.key.removePrefix(KEY_CARD_PREFIX) to value
        }
        .toMap()

    private companion object {
        /** Shared with [DevicePrefs] and [VizPrefs]: device-scoped view preferences. */
        const val PREFS = "bt_device_prefs"
        const val KEY_CARD_PREFIX = "insight_card_"
        const val KEY_PAGE = "insight_page_order"
    }
}
