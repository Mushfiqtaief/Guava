package com.smsmonitor.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based debug logger for Transsion/Tecno phones that suppress logcat.
 * Writes to app's internal files directory: /data/data/com.smsmonitor.app/files/debug.log
 * 
 * Read via ADB: adb shell run-as com.smsmonitor.app cat files/debug.log
 * Clear via ADB: adb shell run-as com.smsmonitor.app truncate -s 0 files/debug.log
 */
object DebugLog {

    private const val LOG_FILE = "debug.log"
    private const val MAX_SIZE = 512 * 1024  // 512KB max, then rotate
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var context: Context? = null

    fun init(ctx: Context) {
        context = ctx.applicationContext
        log("INIT", "DebugLog initialized")
    }

    fun log(tag: String, message: String) {
        try {
            val ctx = context ?: return
            val file = File(ctx.filesDir, LOG_FILE)

            // Rotate if too large
            if (file.exists() && file.length() > MAX_SIZE) {
                val oldFile = File(ctx.filesDir, "debug_old.log")
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
            }

            val timestamp = dateFormat.format(Date())
            val line = "$timestamp [$tag] $message\n"
            file.appendText(line)
        } catch (_: Exception) {
            // Silently fail - don't want logging to crash the app
        }
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        log(tag, message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        log("ERROR/$tag", message)
    }

    /**
     * Read the full log content (for UI display)
     */
    fun readLog(): String {
        return try {
            val ctx = context ?: return "(no context)"
            val file = File(ctx.filesDir, LOG_FILE)
            if (file.exists()) file.readText() else "(empty)"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * Read last N lines
     */
    fun readLastLines(n: Int = 100): String {
        return try {
            val ctx = context ?: return "(no context)"
            val file = File(ctx.filesDir, LOG_FILE)
            if (!file.exists()) return "(empty)"
            val lines = file.readLines()
            lines.takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * Clear the log
     */
    fun clear() {
        try {
            val ctx = context ?: return
            File(ctx.filesDir, LOG_FILE).writeText("")
            log("INIT", "Log cleared")
        } catch (_: Exception) {}
    }
}
