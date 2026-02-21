package com.smsmonitor.app

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that catches incoming SMS in real-time.
 * This is the core of the app - it fires whenever a new SMS arrives.
 * 
 * IMPORTANT: This is a manifest-registered static receiver, so it can
 * fire even when the app process is dead (on most phones). When it fires,
 * it also restarts the foreground service if it's not running.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        // Track processed SMS IDs to avoid duplicates
        private val processedSms = LinkedHashSet<String>()
        private const val MAX_PROCESSED = 1000

        // Callback for UI updates
        var onPaymentDetected: ((String) -> Unit)? = null

        fun clearProcessed() {
            processedSms.clear()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Log.w(TAG, "=== SMS_RECEIVED broadcast fired ===")

        // CRITICAL: Always try to restart the service if it's dead
        ensureServiceRunning(context)

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Detect which SIM slot received this SMS
        val simSlot = detectSimSlot(context, intent)
        Log.w(TAG, "SMS received on SIM slot: $simSlot (0=SIM1, 1=SIM2, -1=unknown)")

        // Group message parts by sender (multi-part SMS)
        val groupedMessages = mutableMapOf<String, StringBuilder>()
        var latestTimestamp = 0L

        for (msg in messages) {
            val address = msg.displayOriginatingAddress ?: "unknown"
            val body = msg.displayMessageBody ?: continue
            val timestamp = msg.timestampMillis

            groupedMessages.getOrPut(address) { StringBuilder() }.append(body)
            if (timestamp > latestTimestamp) latestTimestamp = timestamp
        }

        // Process each complete message
        for ((address, bodyBuilder) in groupedMessages) {
            val body = bodyBuilder.toString()
            val smsId = SmsParser.generateSmsId(address, body, latestTimestamp)

            // Skip if already processed (dedup)
            if (smsId in processedSms) continue

            // Try to parse
            val patterns = PatternStore.getInstance(context).getEnabled()
            val payment = SmsParser.parseWithConfig(address, body, patterns)

            if (payment != null) {
                processedSms.add(smsId)

                // Check SQLite DB to avoid duplicates
                val db = PaymentDatabase.getInstance(context)
                if (db.paymentExists(payment.trxid)) {
                    Log.w(TAG, "Payment already in DB: ${payment.trxid}, skipping")
                    continue
                }

                Log.w(TAG, "Payment detected: ${payment.method} ${payment.amount}TK ${payment.trxid}")

                // Insert as pending, then send
                db.insertPayment(payment, body, address)
                val appContext = context.applicationContext

                CoroutineScope(Dispatchers.IO).launch {
                    val success = WebhookSender.sendToMatchingWebhooks(payment, simSlot, appContext)
                    Log.w(TAG, "${payment.trxid}: ${if (success) "sent" else "pending"} (SIM${simSlot + 1})")

                    if (success) {
                        PaymentDatabase.getInstance(appContext).markPaymentSent(payment)
                    }

                    // Notify UI to refresh
                    onPaymentDetected?.invoke("refresh")
                }
            } else {
                processedSms.add(smsId)
            }

            if (processedSms.size > MAX_PROCESSED) {
                val oldest = processedSms.firstOrNull()
                if (oldest != null) processedSms.remove(oldest)
            }
        }
    }

    /**
     * Detect which SIM slot received the SMS.
     * Tries multiple intent extras used by different OEMs.
     * Returns 0 for SIM1, 1 for SIM2, -1 if unknown.
     */
    private fun detectSimSlot(context: Context, intent: Intent): Int {
        // Try standard Android subscription ID first
        val subId = intent.extras?.let { extras ->
            // Standard key
            var id = extras.getInt("subscription", -1)
            if (id < 0) id = extras.getInt("android.telephony.extra.SUBSCRIPTION_INDEX", -1)
            if (id < 0) id = extras.getInt("sub_id", -1)
            // OEM-specific keys
            if (id < 0) id = extras.getInt("slot", -1)
            if (id < 0) id = extras.getInt("simSlot", -1)
            if (id < 0) id = extras.getInt("phone", -1)
            if (id < 0) id = extras.getInt("com.android.phone.extra.slot", -1)
            id
        } ?: -1

        if (subId < 0) return -1

        // Convert subscription ID to SIM slot index
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val info = sm?.getActiveSubscriptionInfo(subId)
            info?.simSlotIndex ?: subId  // fallback: some OEMs put slot directly
        } catch (e: Exception) {
            // On some devices subId IS the slot index (0 or 1)
            if (subId in 0..1) subId else -1
        }
    }

    /**
     * Check if the foreground service is running and restart it if not.
     * This is critical for phones that kill the service when app is swiped.
     */
    private fun ensureServiceRunning(context: Context) {
        try {
            val prefs = context.getSharedPreferences("sms_monitor", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("service_enabled", false)) return

            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val isRunning = manager.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == SmsMonitorService::class.java.name
            }

            if (!isRunning) {
                Log.w(TAG, "Service is DEAD! Restarting from SmsReceiver...")
                val serviceIntent = Intent(context, SmsMonitorService::class.java)
                context.startForegroundService(serviceIntent)
                Log.w(TAG, "Service restart triggered from SmsReceiver")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureServiceRunning failed: ${e.message}")
        }
    }
}
