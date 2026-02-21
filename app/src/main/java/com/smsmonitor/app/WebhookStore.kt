package com.smsmonitor.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class WebhookStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("webhook_configs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WEBHOOKS = "webhooks"

        @Volatile
        private var instance: WebhookStore? = null

        fun getInstance(context: Context): WebhookStore {
            return instance ?: synchronized(this) {
                instance ?: WebhookStore(context).also { instance = it }
            }
        }
    }

    fun getAll(): List<WebhookConfig> {
        val json = prefs.getString(KEY_WEBHOOKS, null) ?: return seedDefaults()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { WebhookConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            seedDefaults()
        }
    }

    fun getEnabled(): List<WebhookConfig> = getAll().filter { it.enabled }

    fun save(webhooks: List<WebhookConfig>) {
        val arr = JSONArray()
        webhooks.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_WEBHOOKS, arr.toString()).apply()
    }

    fun add(webhook: WebhookConfig) {
        val list = getAll().toMutableList()
        list.add(webhook)
        save(list)
    }

    fun update(webhook: WebhookConfig) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == webhook.id }
        if (idx >= 0) list[idx] = webhook
        save(list)
    }

    fun delete(id: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == id }
        save(list)
    }

    private fun seedDefaults(): List<WebhookConfig> {
        // Seed with the existing hardcoded webhook on first run
        val default = WebhookConfig(
            id = "default",
            name = "Main Webhook",
            url = "https://ecatsbd.xyz/DDR5/index.php",
            apiKey = "3XPERiMEnTCAtS",
            secret = "",
            enabled = true,
            simFilter = 0,
            methodFilter = emptyList()
        )
        save(listOf(default))
        return listOf(default)
    }
}
