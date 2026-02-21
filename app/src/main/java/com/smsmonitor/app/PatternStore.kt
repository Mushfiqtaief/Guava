package com.smsmonitor.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class PatternStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("sms_patterns", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PATTERNS = "patterns"

        @Volatile
        private var instance: PatternStore? = null

        fun getInstance(context: Context): PatternStore {
            return instance ?: synchronized(this) {
                instance ?: PatternStore(context).also { instance = it }
            }
        }
    }

    fun getAll(): List<SmsPatternConfig> {
        val json = prefs.getString(KEY_PATTERNS, null) ?: return seedDefaults()
        return try {
            val arr = JSONArray(json)
            val loaded = (0 until arr.length()).map { SmsPatternConfig.fromJson(arr.getJSONObject(it)) }
            ensureBuiltInFields(loaded)
        } catch (e: Exception) {
            seedDefaults()
        }
    }

    /**
     * If built-in patterns were stored without extractFields (from an older version),
     * merge them with the current defaults so the regex fields are always present.
     */
    private fun ensureBuiltInFields(patterns: List<SmsPatternConfig>): List<SmsPatternConfig> {
        val defaults = mapOf(
            SmsPatternConfig.DEFAULT_BKASH.id to SmsPatternConfig.DEFAULT_BKASH,
            SmsPatternConfig.DEFAULT_NAGAD.id to SmsPatternConfig.DEFAULT_NAGAD
        )
        var changed = false
        val result = patterns.map { p ->
            val default = defaults[p.id]
            if (default != null && p.extractFields.isEmpty()) {
                changed = true
                p.copy(extractFields = default.extractFields, typeId = p.typeId.ifEmpty { default.typeId })
            } else {
                p
            }
        }
        if (changed) save(result)
        return result
    }

    fun getEnabled(): List<SmsPatternConfig> = getAll().filter { it.enabled }

    fun save(patterns: List<SmsPatternConfig>) {
        val arr = JSONArray()
        patterns.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PATTERNS, arr.toString()).apply()
    }

    fun add(pattern: SmsPatternConfig) {
        val list = getAll().toMutableList()
        list.add(pattern)
        save(list)
    }

    fun update(pattern: SmsPatternConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == pattern.id }
        if (idx >= 0) list[idx] = pattern
        save(list)
    }

    fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        save(list)
    }

    private fun seedDefaults(): List<SmsPatternConfig> {
        val defaults = listOf(
            SmsPatternConfig.DEFAULT_BKASH,
            SmsPatternConfig.DEFAULT_NAGAD
        )
        save(defaults)
        return defaults
    }
}
