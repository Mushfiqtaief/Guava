package com.smsmonitor.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic worker — the ULTIMATE fallback.
 * Runs every 15 minutes (WorkManager minimum) even when the app is killed.
 * 
 * Two jobs:
 * 1) Ensure SmsMonitorService is alive — restart it if killed
 * 2) Scan for any missed SMS payments and send them
 * 
 * WorkManager survives OEM app killing better than AlarmManager because
 * it is backed by Google Play Services / JobScheduler.
 */
class SmsWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsWorker"
        const val WORK_NAME = "sms_monitor_periodic"

        fun schedule(context: Context) {
            Log.w(TAG, "Scheduling periodic WorkManager task")
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SmsWorker>(
                15, TimeUnit.MINUTES  // minimum interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.w(TAG, "Periodic work scheduled (every 15 min)")
        }

        fun scheduleImmediate(context: Context) {
            Log.w(TAG, "Scheduling IMMEDIATE one-time WorkManager task")
            
            val workRequest = OneTimeWorkRequestBuilder<SmsWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "sms_monitor_immediate",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.w(TAG, "Periodic work cancelled")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.w(TAG, "=== WorkManager task FIRING ===")

        // Acquire temp wake lock
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsMonitor::WorkerLock"
        )
        wakeLock.acquire(60_000L) // 60 seconds max

        try {
            val prefs = appContext.getSharedPreferences("sms_monitor", Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", false)

            if (!serviceEnabled) {
                Log.w(TAG, "Service disabled by user, skipping")
                return@withContext Result.success()
            }

            // Job 1: Restart service if dead
            if (!isServiceRunning()) {
                Log.w(TAG, "Service NOT running! Restarting...")
                try {
                    val intent = Intent(appContext, SmsMonitorService::class.java)
                    appContext.startForegroundService(intent)
                    Log.w(TAG, "Service restart requested from WorkManager")
                } catch (e: Exception) {
                    Log.e(TAG, "Service restart failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "Service is running OK")
            }

            // Job 2: Scan for missed payments
            scanAndSendMissedPayments()

            // Job 3: Retry any pending (unsent) payments
            retryPendingPayments()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed: ${e.message}", e)
            Result.retry()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun isServiceRunning(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (SmsMonitorService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun scanAndSendMissedPayments() {
        try {
            val statePrefs = appContext.getSharedPreferences("sms_monitor_state", Context.MODE_PRIVATE)
            val lastId = statePrefs.getLong("last_processed_sms_id", 0L)
            val db = PaymentDatabase.getInstance(appContext)

            val cursor = appContext.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastId.toString()),
                "${Telephony.Sms._ID} ASC"
            ) ?: return

            var newMaxId = lastId
            var count = 0

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val address = c.getString(1) ?: "unknown"
                    val body = c.getString(2) ?: continue

                    if (id > newMaxId) newMaxId = id

                    val payment = SmsParser.parse(address, body)
                    if (payment != null) {
                        if (db.paymentExists(payment.trxid)) continue

                        Log.w(TAG, "MISSED payment found: ${payment.method} ${payment.amount}TK ${payment.trxid}")
                        db.insertPayment(payment, body, address, id)
                        
                        val success = WebhookSender.send(payment)
                        if (success) {
                            db.markPaymentSent(payment, id)
                            count++
                        }
                    }
                }
            }

            if (newMaxId > lastId) {
                statePrefs.edit().putLong("last_processed_sms_id", newMaxId).apply()
            }

            if (count > 0) {
                Log.w(TAG, "Worker sent $count missed payments")
                SmsReceiver.onPaymentDetected?.invoke("refresh")
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanAndSendMissedPayments failed: ${e.message}", e)
        }
    }

    private fun retryPendingPayments() {
        try {
            val db = PaymentDatabase.getInstance(appContext)
            val payments = db.getAllPayments()
            val pending = payments.filter { it.status == "pending" }

            if (pending.isEmpty()) return

            Log.w(TAG, "Retrying ${pending.size} pending payments...")

            for (record in pending) {
                val payment = SmsParser.Payment(
                    method = record.method,
                    amount = record.amount,
                    trxid = record.trxid
                )
                val success = WebhookSender.send(payment)
                if (success) {
                    db.markPaymentSent(payment)
                    Log.w(TAG, "Retry success: ${record.trxid}")
                }
            }

            SmsReceiver.onPaymentDetected?.invoke("refresh")
        } catch (e: Exception) {
            Log.e(TAG, "retryPendingPayments failed: ${e.message}", e)
        }
    }
}
