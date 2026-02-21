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
    private const val WEBHOOK_URL = "https://ecatsbd.xyz/DDR5/index.php"
    private const val API_KEY = "3XPERiMEnTCAtS"
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
     * message = payload_json + timestamp + API_KEY
     * Must match PHP: hash('sha256', $rawData . $timestamp . API_KEY)
     */
    private fun generateSignature(payloadJson: String, timestamp: String, signingKey: String = API_KEY): String {
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
     * Send a single payment to the webhook.
     * Returns true on success (HTTP 200 + saved > 0 or duplicates > 0), false on failure.
     */
    fun send(payment: SmsParser.Payment): Boolean {
        val payloadJson = buildPayload(payment)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signature = generateSignature(payloadJson, timestamp, API_KEY)

        DebugLog.w(TAG, "=== SENDING: ${payment.method} ${payment.amount}TK ${payment.trxid} ===")
        DebugLog.w(TAG, "URL: $WEBHOOK_URL")
        DebugLog.w(TAG, "Body: $payloadJson")
        DebugLog.w(TAG, "X-Timestamp: $timestamp")
        DebugLog.w(TAG, "X-Signature: ${signature.take(16)}...")

        for (attempt in 1..MAX_RETRIES) {
            try {
                val request = Request.Builder()
                    .url(WEBHOOK_URL)
                    .post(payloadJson.toRequestBody(JSON_MEDIA))
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", API_KEY)
                    .header("X-Timestamp", timestamp)
                    .header("X-Signature", signature)
                    .build()

                DebugLog.w(TAG, "Attempt $attempt/$MAX_RETRIES — connecting...")

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: "(empty)"
                    val code = response.code

                    DebugLog.w(TAG, "HTTP $code — Body: $responseBody")

                    when (code) {
                        200 -> {
                            // Parse response to check if actually saved
                            try {
                                val json = JSONObject(responseBody)
                                val status = json.optString("status", "")
                                val saved = json.optInt("saved", 0)
                                val duplicates = json.optInt("duplicates", 0)
                                val errors = json.optInt("errors", 0)
                                val msg = json.optString("message", "")

                                DebugLog.w(TAG, "✓ SUCCESS: status=$status saved=$saved dupes=$duplicates errors=$errors msg=$msg")

                                // Consider success if saved or was duplicate
                                return (saved > 0 || duplicates > 0 || status == "success")
                            } catch (e: Exception) {
                                DebugLog.w(TAG, "✓ HTTP 200 (couldn't parse body): $responseBody")
                                return true
                            }
                        }
                        401 -> {
                            DebugLog.e(TAG, "✗ AUTH FAILED (401): $responseBody")
                            // Don't retry auth failures
                            return false
                        }
                        403 -> {
                            DebugLog.e(TAG, "✗ FORBIDDEN (403): $responseBody")
                            return false
                        }
                        else -> {
                            DebugLog.w(TAG, "✗ HTTP $code, will retry...")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "✗ EXCEPTION attempt $attempt: ${e.javaClass.simpleName}: ${e.message}")
            }

            if (attempt < MAX_RETRIES) {
                DebugLog.w(TAG, "Waiting ${RETRY_DELAY_MS}ms before retry...")
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        DebugLog.e(TAG, "✗✗✗ FAILED after $MAX_RETRIES retries for ${payment.trxid}")
        return false
    }

    /**
     * Send a payment to a specific webhook config using its custom format settings.
     */
    fun send(payment: SmsParser.Payment, config: WebhookConfig): Boolean {
        val payloadJson = buildPayload(payment, config.bodyTemplate)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val signingKey = config.secret.ifEmpty { config.apiKey.ifEmpty { API_KEY } }
        val signature = generateSignature(payloadJson, timestamp, signingKey)
        val url = config.url.ifEmpty { WEBHOOK_URL }
        val apiKey = config.apiKey.ifEmpty { API_KEY }

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
    fun sendToMatchingWebhooks(payment: SmsParser.Payment, simSlot: Int, context: android.content.Context): Boolean {
        val webhooks = WebhookStore.getInstance(context).getEnabled()
        var anySuccess = false

        for (config in webhooks) {
            // SIM filter: simFilter 1=SIM1(slot0), 2=SIM2(slot1)
            if (config.simFilter > 0 && simSlot >= 0) {
                if (config.simFilter != (simSlot + 1)) continue
            }

            // Pattern Type filter — empty means accept all
            if (config.patternTypeIds.isNotEmpty() && payment.typeId.isNotEmpty()) {
                if (payment.typeId !in config.patternTypeIds) continue
            }

            // Legacy method filter (backward compat)
            if (config.methodFilter.isNotEmpty() && config.patternTypeIds.isEmpty()) {
                if (payment.method !in config.methodFilter) continue
            }

            if (send(payment, config)) anySuccess = true
        }

        return anySuccess
    }
}
