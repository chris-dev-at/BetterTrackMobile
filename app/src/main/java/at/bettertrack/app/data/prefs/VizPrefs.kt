package at.bettertrack.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where each chart surface's chosen `Darstellung` is remembered.
 *
 * ## Why this stores opaque strings
 *
 * The chart-form vocabulary (`BtVizForm`, `BtVizLabels`, `BtVizScope`, …) is a
 * presentation concern and lives in `ui/charts/viz`. Teaching the data layer
 * that vocabulary just to persist it would invert the dependency for no gain, so
 * this store deals in `family → encoded string` and knows nothing about what is
 * inside. The codec that produces those strings is pure and unit-tested next to
 * the enums it encodes.
 *
 * ## Why it is device-scoped, and keyed per family
 *
 * Same reasoning as [DevicePrefs], whose file it shares: a chart form is a view
 * preference, it carries no account data, and it should survive a logout because
 * the phone reopening on the shape you left it on is simply the friendlier
 * behaviour.
 *
 * The per-family key is the load-bearing part. The study is explicit that a
 * preference is remembered *per data family, not globally* — choosing bubbles
 * for asset classes must not turn signed movers into bubbles. Storing one
 * global form would be less code and a worse product.
 */
class VizPrefs internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    private val _configs = MutableStateFlow(load())

    /** Every saved surface configuration, keyed by data-family name. */
    val configs: StateFlow<Map<String, String>> = _configs.asStateFlow()

    /** The encoded configuration for [family], or null when the user never chose. */
    fun configFor(family: String): String? = _configs.value[family]

    /**
     * Save (or, with a null [encoded], forget) one family's configuration.
     *
     * A null clears the key rather than writing a "default" string, so
     * `Zurücksetzen` genuinely restores "never chose" — which matters because
     * `Automatisch` is allowed to resolve differently as the study's
     * recommendations evolve, and only an absent key should follow it.
     */
    fun setConfig(family: String, encoded: String?) {
        val key = KEY_PREFIX + family
        prefs.edit {
            if (encoded == null) remove(key) else putString(key, encoded)
        }
        _configs.value = _configs.value.toMutableMap().apply {
            if (encoded == null) remove(family) else put(family, encoded)
        }
    }

    private fun load(): Map<String, String> = prefs.all
        .asSequence()
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { entry ->
            val value = entry.value as? String ?: return@mapNotNull null
            entry.key.removePrefix(KEY_PREFIX) to value
        }
        .toMap()

    private companion object {
        /** Shared with [DevicePrefs]: both are device-scoped view preferences. */
        const val PREFS = "bt_device_prefs"
        const val KEY_PREFIX = "viz_config_"
    }
}
