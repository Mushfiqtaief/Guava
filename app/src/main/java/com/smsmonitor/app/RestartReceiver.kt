package com.smsmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the restart broadcast when the service is killed (swiped from recents).
 * This is a safety net to bring the service back to life.
 */
class RestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RestartReceiver"
        const val ACTION_RESTART = "com.smsmonitor.app.RESTART_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.w(TAG, "=== Restart broadcast received (action=${intent.action}) ===")
        try {
            val serviceIntent = Intent(context, SmsMonitorService::class.java)
            context.startForegroundService(serviceIntent)
            Log.w(TAG, "Service restart triggered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}")
        }
    }
}
