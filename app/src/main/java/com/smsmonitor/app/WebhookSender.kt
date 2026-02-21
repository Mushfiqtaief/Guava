package com.smsmonitor.app

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Sends payment data to the webhook endpoint.
 * Full debug logging to file (since logcat is blocked on Transsion phones).
 */
object WebhookSender {

    private const val TAG = "Webhook"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 5000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * Generate SHA256 signature
     * message = payload_json + timestamp + signingKey
     * Must match PHP: hash('sha256', $rawData . $timestamp . $signingKey)
     */
    private fun generateSignature(payloadJson: String, timestamp: String, signingKey: String): String {
        val message = "$payloadJson$timestamp$signingKey"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(message.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build the JSON payload using a custom template if provided.
     * Placeholders: {method}, {amount}, {trxid}, {timestamp}, {sms_body}, and any custom extracted field names.
     */
    private fun buildPayload(payment: SmsParser.Payment, template: String = ""): String {
        if (template.isNotBlank()) {
            var result = template
                .replace("{method}", payment.method)
                .replace("{amount}", payment.amount.toString())
                .replace("{trxid}", payment.trxid)
                .replace("{timestamp}", java.time.LocalDateTime.now().toString())
            // Replace any extra field placeholders from pattern extraction
            for ((key, value) in payment.extras) {
                result = result.replace("{$key}", value)
            }
            return result
        }
        val paymentObj = JSONObject().apply {
            put("method", payment.method)
            put("amount", payment.amount)
            put("trxid", payment.trxid)
            // Include all extras in the payload
            for ((key, value) in payment.extras) {
                if (key != "method" && key != "sms_body") {
                    put(key, value)
                }
            }
        }
        val paymentsArray = JSONArray().apply { put(paymentObj) }
        val payload = JSONObject().apply {
            put("payments", paymentsArray)
            put("timestamp", java.time.LocalDateTime.now().toString())
        }
        return payload.toString()
    }

    /**
     * Send a test ping to a webhook. Returns a detailed result string.
     */
    fun testWebhook(config: WebhookConfig): String {
        if (config.url.isBlank()) return "\u2717 No URL configured"

        val testPayment = SmsParser.Payment(
            method = "bkash",
            amount = 1.00,
            trxid = "TEST" + System.currentTimeMillis().toString().takeLast(6),
            typeId = "type_payment",
            extras = mapOf("sms_body" to "You have received Tk 1.00 from 01XXXXXXXXX. Fee Tk 0.00. Balance Tk 1.00. TrxID TEST${System.currentTimeMillis().toString().takeLast(6)} at 21/02/2026 12:00.", "sender" to "bKash", "sim" to "0")
        )

        val payloadJson = buildPayload(testPayment, config.bodyTemplate)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signingKey = config.secret.ifEmpty { config.apiKey }
        val signature = if (signingKey.isNotEmpty()) generateSignature(payloadJson, timestamp, signingKey) else ""

        return try {
            val mediaType = config.contentType.toMediaType()
            val body = if (config.httpMethod == "GET") null else payloadJson.toRequestBody(mediaType)

            val builder = Request.Builder().url(config.url)
            when (config.httpMethod) {
                "GET" -> builder.get()
                "PUT" -> builder.put(body!!)
                else -> builder.post(body!!)
            }
            builder.header("Content-Type", config.contentType)

            when (config.authType) {
                "api_key" -> {
                    val hdrName = config.authHeaderName.ifEmpty { "X-API-Key" }
                    val hdrVal = config.authHeaderValue.ifEmpty { config.apiKey }
                    if (hdrVal.isNotEmpty()) builder.header(hdrName, hdrVal)
                }
                "bearer" -> {
                    val token = config.authHeaderValue.ifEmpty { config.apiKey }
                    if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
                }
                "custom_header" -> {
                    val hdrName = config.authHeaderName.ifEmpty { "Authorization" }
                    val hdrVal = config.authHeaderValue.ifEmpty { config.apiKey }
                    if (hdrVal.isNotEmpty()) builder.header(hdrName, hdrVal)
                }
            }
            if (config.includeSignature) {
                builder.header("X-Timestamp", timestamp)
                builder.header("X-Signature", signature)
            }
            for ((key, value) in config.customHeaders) {
                if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value)
            }

            val startMs = System.currentTimeMillis()
            client.newCall(builder.build()).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startMs
                val responseBody = response.body?.string() ?: "(empty)"
                val code = response.code

                val sb = StringBuilder()
                sb.appendLine("HTTP $code  ($elapsed ms)")
                sb.appendLine()

                // Show key response headers
                val ct = response.header("Content-Type")
                if (ct != null) sb.appendLine("Content-Type: $ct")

                sb.appendLine()
                // Attempt pretty-print JSON
                try {
                    val json = JSONObject(responseBody)
                    sb.appendLine(json.toString(2))
                } catch (_: Exception) {
                    sb.appendLine(responseBody.take(500))
                }

                when (code) {
                    200 -> "\u2713 SUCCESS\n\n$sb"
                    401 -> "\u2717 AUTH FAILED (401)\n\n$sb"
                    403 -> "\u2717 FORBIDDEN (403)\n\n$sb"
                    404 -> "\u2717 NOT FOUND (404)\n\n$sb"
                    else -> "\u2717 HTTP $code\n\n$sb"
                }
            }
        } catch (e: java.net.UnknownHostException) {
            "\u2717 DNS Error\nCannot resolve: ${config.url}\n\nCheck URL and internet connection."
        } catch (e: java.net.ConnectException) {
            "\u2717 Connection Refused\n${e.message}\n\nServer may be down."
        } catch (e: java.net.SocketTimeoutException) {
            "\u2717 Timeout\nServer did not respond within 15s."
        } catch (e: Exception) {
            "\u2717 Error: ${e.javaClass.simpleName}\n${e.message}"
        }
    }

    /**
     * Send a payment to a specific webhook config using its custom format settings.
     * Returns true on success (HTTP 200 + saved > 0 or duplicates > 0), false on failure.
     */
    fun send(payment: SmsParser.Payment, config: WebhookConfig): Boolean {
        if (config.url.isBlank()) {
            DebugLog.e(TAG, "✗ Skipping webhook [${config.name}]: no URL configured")
            return false
        }
        val payloadJson = buildPayload(payment, config.bodyTemplate)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signingKey = config.secret.ifEmpty { config.apiKey }
        val signature = if (signingKey.isNotEmpty()) generateSignature(payloadJson, timestamp, signingKey) else ""
        val url = config.url
        val apiKey = config.apiKey

        DebugLog.w(TAG, "=== SENDING to [${config.name}]: ${payment.method} ${payment.amount}TK ${payment.trxid} ===")
        DebugLog.w(TAG, "URL: $url | Method: ${config.httpMethod} | ContentType: ${config.contentType}")

        for (attempt in 1..MAX_RETRIES) {
            try {
                val mediaType = config.contentType.toMediaType()
                val body = if (config.httpMethod == "GET") null
                           else payloadJson.toRequestBody(mediaType)

                val builder = Request.Builder().url(url)

                // Set HTTP method
                when (config.httpMethod) {
                    "GET" -> builder.get()
                    "PUT" -> builder.put(body!!)
                    else -> builder.post(body!!)
                }

                // Content-Type header
                builder.header("Content-Type", config.contentType)

                // Auth headers based on authType
                when (config.authType) {
                    "api_key" -> {
                        val headerName = config.authHeaderName.ifEmpty { "X-API-Key" }
                        val headerVal = config.authHeaderValue.ifEmpty { apiKey }
                        if (headerVal.isNotEmpty()) builder.header(headerName, headerVal)
                    }
                    "bearer" -> {
                        val token = config.authHeaderValue.ifEmpty { apiKey }
                        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
                    }
                    "custom_header" -> {
                        val headerName = config.authHeaderName.ifEmpty { "Authorization" }
                        val headerVal = config.authHeaderValue.ifEmpty { apiKey }
                        if (headerVal.isNotEmpty()) builder.header(headerName, headerVal)
                    }
                    // "none" - no auth header
                }

                // Signature headers (optional)
                if (config.includeSignature) {
                    builder.header("X-Timestamp", timestamp)
                    builder.header("X-Signature", signature)
                }

                // Custom headers
                for ((key, value) in config.customHeaders) {
                    if (key.isNotBlank() && value.isNotBlank()) {
                        builder.header(key, value)
                    }
                }

                val request = builder.build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: "(empty)"
                    DebugLog.w(TAG, "HTTP ${response.code} — Body: $responseBody")
                    when (response.code) {
                        200 -> {
                            try {
                                val json = org.json.JSONObject(responseBody)
                                val saved = json.optInt("saved", 0)
                                val duplicates = json.optInt("duplicates", 0)
                                val status = json.optString("status", "")
                                return (saved > 0 || duplicates > 0 || status == "success")
                            } catch (e: Exception) {
                                return true
                            }
                        }
                        401, 403 -> return false
                        else -> DebugLog.w(TAG, "HTTP ${response.code}, retrying...")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "Attempt $attempt failed: ${e.message}")
            }
            if (attempt < MAX_RETRIES) Thread.sleep(RETRY_DELAY_MS)
        }
        return false
    }

    /**
     * Send to all matching webhooks based on SIM and pattern type filters.
     * Returns true if at least one webhook accepted the payment.
     */
    /**
     * Returns: "sent" if at least one webhook succeeded,
     *          "idle" if no webhook matched (filtered out or none enabled),
     *          "pending" if webhook matched but send failed.
     */
    fun sendToMatchingWebhooks(payment: SmsParser.Payment, simSlot: Int, context: android.content.Context): String {
        val webhooks = WebhookStore.getInstance(context).getEnabled()
        DebugLog.w(TAG, "sendToMatchingWebhooks: ${webhooks.size} enabled webhook(s), payment.typeId='${payment.typeId}', simSlot=$simSlot")

        if (webhooks.isEmpty()) {
            DebugLog.w(TAG, "  No enabled webhooks — marking idle")
            return "idle"
        }

        var anyMatched = false
        var anySuccess = false

        for (config in webhooks) {
            // SIM filter: simFilter 1=SIM1(slot0), 2=SIM2(slot1)
            if (config.simFilter > 0 && simSlot >= 0) {
                if (config.simFilter != (simSlot + 1)) {
                    DebugLog.w(TAG, "  [${config.name}] SKIP: SIM filter ${config.simFilter} != slot ${simSlot+1}")
                    continue
                }
            }

            // Pattern Type filter — empty means accept all
            if (config.patternTypeIds.isNotEmpty() && payment.typeId.isNotEmpty()) {
                if (payment.typeId !in config.patternTypeIds) {
                    DebugLog.w(TAG, "  [${config.name}] SKIP: typeId '${payment.typeId}' not in ${config.patternTypeIds}")
                    continue
                }
            }

            // Legacy method filter (backward compat)
            if (config.methodFilter.isNotEmpty() && config.patternTypeIds.isEmpty()) {
                if (payment.method !in config.methodFilter) {
                    DebugLog.w(TAG, "  [${config.name}] SKIP: method '${payment.method}' not in ${config.methodFilter}")
                    continue
                }
            }

            anyMatched = true
            DebugLog.w(TAG, "  [${config.name}] MATCH — sending...")
            if (send(payment, config)) anySuccess = true
        }

        return when {
            anySuccess -> "sent"
            anyMatched -> "pending"   // matched but HTTP failed
            else -> {
                DebugLog.w(TAG, "  No webhook matched filters for ${payment.trxid} — idle")
                "idle"
            }
        }
    }
}
