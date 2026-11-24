package io.github.yhzion.velo

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] for JVM tests. A null string or set removes the key, as Android
 * does. As on Android, [SharedPreferences.Editor.commit] writes the file before it returns, and
 * [SharedPreferences.Editor.apply] changes the values at once but writes the file later
 * ([flush]); [crash] is the process dying before that: the values are the file's again.
 * While [commitsFail], a commit fails as Android's does when the file cannot be written (a
 * full disk): the values change, the file does not, and it returns false.
 */
class FakePrefs : SharedPreferences {
    private var values = HashMap<String, Any>()
    /** What the file holds. */
    private var file = HashMap<String, Any>()
    var commitsFail = false

    /** The writes [SharedPreferences.Editor.apply] left reach the file. */
    fun flush() {
        file = HashMap(values)
    }

    /** The process dies: what was applied but not written yet is gone. */
    fun crash() {
        values = HashMap(file)
    }

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = HashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { puts[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            if (commitsFail) return false
            flush()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            for ((k, v) in puts) if (v == null) values.remove(k) else values[k] = v
        }
    }
}
