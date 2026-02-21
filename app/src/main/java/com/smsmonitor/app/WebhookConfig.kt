package com.smsmonitor.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WebhookConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val secret: String = "",
    val enabled: Boolean = true,
    val simFilter: Int = 0,             // 0=Any, 1=SIM1, 2=SIM2
    val methodFilter: List<String> = emptyList(),  // legacy — kept for migration
    val patternTypeIds: List<String> = emptyList(), // empty = all types

    // --- Payload customization ---
    val httpMethod: String = "POST",               // POST, GET, PUT
    val contentType: String = "application/json",  // application/json, application/x-www-form-urlencoded
    val bodyTemplate: String = "",                  // custom JSON template, empty = default format
    // Placeholders: {method}, {amount}, {trxid}, {timestamp}, {sender}, {sim}
    val customHeaders: Map<String, String> = emptyMap(), // extra headers
    val authType: String = "api_key",              // none, api_key, bearer, custom_header
    val authHeaderName: String = "X-API-Key",      // header name for auth
    val authHeaderValue: String = "",              // value for auth header (fallback to apiKey)
    val includeSignature: Boolean = true           // include X-Signature / X-Timestamp
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("url", url)
            put("apiKey", apiKey)
            put("secret", secret)
            put("enabled", enabled)
            put("simFilter", simFilter)
            put("methodFilter", JSONArray(methodFilter))
            put("patternTypeIds", JSONArray(patternTypeIds))
            put("httpMethod", httpMethod)
            put("contentType", contentType)
            put("bodyTemplate", bodyTemplate)
            put("customHeaders", JSONObject(customHeaders))
            put("authType", authType)
            put("authHeaderName", authHeaderName)
            put("authHeaderValue", authHeaderValue)
            put("includeSignature", includeSignature)
        }
    }

    companion object {
        /** Default body template shown as hint/example */
        const val DEFAULT_BODY_HINT = """{"payments":[{"method":"{method}","amount":{amount},"trxid":"{trxid}"}],"timestamp":"{timestamp}"}"""

        fun fromJson(json: JSONObject): WebhookConfig {
            val methodArray = json.optJSONArray("methodFilter")
            val methods = mutableListOf<String>()
            if (methodArray != null) {
                for (i in 0 until methodArray.length()) {
                    methods.add(methodArray.getString(i))
                }
            }

            val headersObj = json.optJSONObject("customHeaders")
            val headers = mutableMapOf<String, String>()
            if (headersObj != null) {
                headersObj.keys().forEach { key ->
                    headers[key] = headersObj.optString(key, "")
                }
            }

            return WebhookConfig(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", ""),
                url = json.optString("url", ""),
                apiKey = json.optString("apiKey", ""),
                secret = json.optString("secret", ""),
                enabled = json.optBoolean("enabled", true),
                simFilter = json.optInt("simFilter", 0),
                methodFilter = methods,
                patternTypeIds = run {
                    val arr = json.optJSONArray("patternTypeIds")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                    else emptyList()
                },
                httpMethod = json.optString("httpMethod", "POST"),
                contentType = json.optString("contentType", "application/json"),
                bodyTemplate = json.optString("bodyTemplate", ""),
                customHeaders = headers,
                authType = json.optString("authType", "api_key"),
                authHeaderName = json.optString("authHeaderName", "X-API-Key"),
                authHeaderValue = json.optString("authHeaderValue", ""),
                includeSignature = json.optBoolean("includeSignature", true)
            )
        }
    }
}
