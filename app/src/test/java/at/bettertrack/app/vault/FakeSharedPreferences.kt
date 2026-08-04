package at.bettertrack.app.vault

import android.content.SharedPreferences

/**
 * An in-memory [SharedPreferences].
 *
 * [VaultKeyCustody] takes its prefs as a constructor parameter precisely so the
 * custody logic — wrap, persist, unwrap, lock, forget — can be gated on the JVM
 * without Keystore, without `EncryptedSharedPreferences`, and without an
 * emulator. Only the two methods this project's code paths touch are
 * implemented; the rest throw so an unnoticed new dependency shows up as a loud
 * failure rather than a silent empty value.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val staged = LinkedHashMap<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { staged[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { staged[key] = value }

        override fun putInt(key: String, value: Int) = apply { staged[key] = value }

        override fun putLong(key: String, value: Long) = apply { staged[key] = value }

        override fun putFloat(key: String, value: Float) = apply { staged[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }

        override fun remove(key: String) = apply { removed += key }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            write()
            return true
        }

        override fun apply() = write()

        private fun write() {
            if (clearAll) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(staged)
        }
    }
}
