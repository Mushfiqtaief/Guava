package com.smsmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Starts the SMS monitor service automatically when the phone boots up.
 * Handles both regular boot and quick boot (some manufacturers).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.w(TAG, "=== Boot receiver triggered (action=$action) ===")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // Acquire a temporary wake lock to ensure we can start the service
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsMonitor::BootLock"
            )
            wakeLock.acquire(30_000L) // 30 seconds max

            try {
                // Mark service as enabled (in case prefs were reset)
                context.getSharedPreferences("sms_monitor", Context.MODE_PRIVATE)
                    .edit().putBoolean("service_enabled", true).apply()

                // Start the foreground service
                val serviceIntent = Intent(context, SmsMonitorService::class.java)
                context.startForegroundService(serviceIntent)
                Log.w(TAG, "Service start requested after boot")

                // Also schedule WorkManager as safety net
                SmsWorker.schedule(context)
                Log.w(TAG, "WorkManager scheduled after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service after boot: ${e.message}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }
}
