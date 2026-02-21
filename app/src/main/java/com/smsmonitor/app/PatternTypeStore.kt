package com.smsmonitor.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages user-defined Pattern Types (e.g. Payment, OTP, Notification).
 * Each SMS pattern belongs to one type, and each webhook subscribes to one or more types.
 */
data class PatternType(
    val id: String = UUID.randomUUID().toString(),
    val name: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        fun fromJson(json: JSONObject): PatternType = PatternType(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "")
        )
    }
}

class PatternTypeStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("pattern_types", Context.MODE_PRIVATE)

    companion object {
        private const val KEY = "types"

        @Volatile
        private var instance: PatternTypeStore? = null

        fun getInstance(context: Context): PatternTypeStore {
            return instance ?: synchronized(this) {
                instance ?: PatternTypeStore(context).also { instance = it }
            }
        }
    }

    fun getAll(): List<PatternType> {
        val json = prefs.getString(KEY, null) ?: return seedDefaults()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { PatternType.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            seedDefaults()
        }
    }

    fun save(types: List<PatternType>) {
        val arr = JSONArray()
        types.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(type: PatternType) {
        val list = getAll().toMutableList()
        list.add(type)
        save(list)
    }

    fun update(type: PatternType) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == type.id }
        if (idx >= 0) list[idx] = type
        save(list)
    }

    fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        save(list)
    }

    fun findById(id: String): PatternType? = getAll().find { it.id == id }

    fun findByName(name: String): PatternType? = getAll().find {
        it.name.equals(name, ignoreCase = true)
    }

    private fun seedDefaults(): List<PatternType> {
        val defaults = listOf(
            PatternType(id = "type_payment", name = "Payment"),
            PatternType(id = "type_otp", name = "OTP")
        )
        save(defaults)
        return defaults
    }
}
