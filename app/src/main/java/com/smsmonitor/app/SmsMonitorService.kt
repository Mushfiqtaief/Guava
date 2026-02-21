package com.smsmonitor.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Bulletproof foreground service that reads SMS like native messaging apps do.
 *
 * Persistence strategies:
 * - START_STICKY: OS restarts the service if killed
 * - onTaskRemoved(): restarts via broadcast + AlarmManager + WorkManager when swiped
 * - onDestroy(): schedules restart via all available methods
 * - stopWithTask="false" in manifest
 * - Wake lock to prevent CPU sleep
 * - Wake lock auto-renewal every 3 hours
 * - WorkManager periodic task (every 15 min) ensures service stays alive
 *
 * SMS detection strategies:
 * 1. Dynamic BroadcastReceiver for real-time SMS_RECEIVED
 * 2. ContentObserver on content://sms for database change notifications
 * 3. Periodic polling every 3s as ultimate fallback
 *
 * Payment tracking:
 * - SQLite database tracks which payments have been sent to webhook
 * - Prevents duplicate sends on rescan/restart/reboot
 */
class SmsMonitorService : Service() {

    companion object {
        private const val TAG = "SmsMonitorSvc"
        private const val CHANNEL_ID = "sms_monitor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 3000L
        private const val WAKE_LOCK_RENEW_MS = 3 * 60 * 60 * 1000L  // 3 hours
        private const val WAKE_LOCK_DURATION_MS = 4 * 60 * 60 * 1000L  // 4 hours
        private const val ALARM_RESTART_MS = 60_000L  // 1 minute fallback
        private const val POLL_STALL_THRESHOLD_MS = 15_000L // If no poll for 15s, we're stalled
        private const val PREFS_NAME = "sms_monitor_state"
        private const val KEY_LAST_ID = "last_processed_sms_id"
        private const val KEY_FIRST_SCAN_DONE = "first_scan_done"
        private const val OBSERVER_DEBOUNCE_MS = 750L
        private const val MAX_TRACKED_SMS_IDS = 1200

    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var smsReceiver: SmsReceiver? = null
    private var smsObserver: SmsContentObserver? = null
    private var isReceiverRegistered = false
    private var isObserverRegistered = false

    // Silent audio player — prevents Griffin cgroup freeze by keeping audio thread active
    private var silentPlayer: MediaPlayer? = null

    // Use a dedicated HandlerThread for ContentObserver callbacks (not main looper)
    private var observerThread: HandlerThread? = null
    private var observerHandler: Handler? = null

    // Main looper handler for UI-only updates (notifications, etc)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observerScanRunnable = Runnable { serviceScope.launch { scanNewSms() } }

    private val processedIds = LinkedHashSet<Long>()
    private var lastProcessedSmsId: Long = 0L
    private var isScanning = false
    private var scanCount = 0

    // ScheduledExecutorService for polling — runs on its own thread, bypasses main Looper
    private var pollExecutor: ScheduledExecutorService? = null
    private var pollFuture: ScheduledFuture<*>? = null

    // Track last poll timestamp to detect stalls (Griffin cgroup freeze detection)
    private val lastPollTimestamp = AtomicLong(0L)

    private lateinit var paymentDb: PaymentDatabase
    private lateinit var patternStore: PatternStore

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize debug logger
        DebugLog.init(this)

        // Initialize payment database and pattern store
        paymentDb = PaymentDatabase.getInstance(this)
        patternStore = PatternStore.getInstance(this)

        // Load persisted state
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastProcessedSmsId = prefs.getLong(KEY_LAST_ID, 0L)
        DebugLog.w(TAG, "=== Service CREATED. lastId=$lastProcessedSmsId, sent=${paymentDb.getSentCount()}, pending=${paymentDb.getPendingCount()} ===")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.w(TAG, "=== Service onStartCommand ===")

        // Acquire wake lock
        acquireWakeLock()

        // Note: wake lock renewal is scheduled inside startPolling() on the executor thread

        // Start foreground with DATA_SYNC type (like Link to Windows uses CONNECTED_DEVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        }

        // Mark service as enabled
        getSharedPreferences("sms_monitor", MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()

        // Cancel any pending restart alarms since we're alive now
        cancelRestartAlarm()

        // Schedule WorkManager periodic task as ultimate safety net
        SmsWorker.schedule(this)

        // Strategy 1: Dynamic BroadcastReceiver
        registerSmsReceiver()

        // Strategy 2: ContentObserver (on dedicated thread, not main looper)
        registerSmsObserver()

        // Strategy 5: Silent audio — prevents Griffin cgroup freeze entirely
        startSilentAudio()

        // Always ensure polling is running — restart if stalled or not started
        val lastPoll = lastPollTimestamp.get()
        val now = SystemClock.elapsedRealtime()
        val pollStalled = lastPoll == 0L || (now - lastPoll) > POLL_STALL_THRESHOLD_MS

        if (pollStalled || pollFuture == null || pollFuture?.isCancelled == true) {
            DebugLog.w(TAG, "Poll stalled or not started — (re)starting polling + scan")
            serviceScope.launch {
                try {
                    initialScan()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Init scan CRASHED: ${e.message}")
                }
                // Retry any pending payments that failed before
                retryPendingPayments()
                startPolling()
            }
        } else {
            DebugLog.w(TAG, "Polling healthy, triggering scan...")
            serviceScope.launch { scanNewSms() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when user swipes the app from recents.
     * Schedule an immediate restart via broadcast + alarm.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.w(TAG, "=== onTaskRemoved: App swiped! Restarting... ===")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DebugLog.w(TAG, "=== Service DESTROYED! Scheduling restart... ===")

        // Cleanup
        smsReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        smsReceiver = null
        isReceiverRegistered = false
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        smsObserver = null
        isObserverRegistered = false
        observerHandler?.removeCallbacksAndMessages(null)
        stopSilentAudio()

        // Shutdown poll executor
        pollFuture?.cancel(false)
        pollExecutor?.shutdownNow()
        pollExecutor = null

        // Shutdown observer thread
        observerThread?.quitSafely()
        observerThread = null
        observerHandler = null

        uiHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()

        // Schedule restart before releasing wake lock
        scheduleRestart()

        // Release wake lock
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    // Wake Lock Management
    // ─────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SmsMonitor::BgLock"
        ).apply {
            setReferenceCounted(false)  // Avoid double-release issues
            acquire(WAKE_LOCK_DURATION_MS)
        }
        Log.w(TAG, "Wake lock acquired (${WAKE_LOCK_DURATION_MS / 3600000}h)")
    }

    private fun scheduleWakeLockRenewal() {
        // Use the poll executor for wake lock renewal too (not main looper)
        pollExecutor?.scheduleAtFixedRate({
            try {
                Log.w(TAG, "Renewing wake lock...")
                acquireWakeLock()
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock renewal failed: ${e.message}")
            }
        }, WAKE_LOCK_RENEW_MS, WAKE_LOCK_RENEW_MS, TimeUnit.MILLISECONDS)
    }

    // ─────────────────────────────────────────────────────────────
    // Service Restart (bulletproof background)
    // ─────────────────────────────────────────────────────────────
    private fun scheduleRestart() {
        Log.w(TAG, "Scheduling service restart (AGGRESSIVE)...")

        // Method 1: Direct startForegroundService (immediate restart attempt)
        try {
            val directIntent = Intent(this, SmsMonitorService::class.java)
            startForegroundService(directIntent)
            Log.w(TAG, "Direct restart attempted")
        } catch (e: Exception) {
            Log.e(TAG, "Direct restart failed: ${e.message}")
        }

        // Method 2: Send broadcast to RestartReceiver
        try {
            val broadcastIntent = Intent(this, RestartReceiver::class.java).apply {
                action = RestartReceiver.ACTION_RESTART
            }
            sendBroadcast(broadcastIntent)
            Log.w(TAG, "Restart broadcast sent")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast restart failed: ${e.message}")
        }

        // Method 3: AlarmManager fallback — fires in 10 seconds
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, RestartReceiver::class.java).apply {
                action = RestartReceiver.ACTION_RESTART
            }

            // Short-term alarm (10 seconds)
            val shortPending = PendingIntent.getBroadcast(
                this, 1, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 10_000L,
                shortPending
            )
            Log.w(TAG, "AlarmManager restart scheduled in 10s")

            // Medium-term alarm (1 minute) 
            val medPending = PendingIntent.getBroadcast(
                this, 2, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_RESTART_MS,
                medPending
            )
            Log.w(TAG, "AlarmManager restart scheduled in ${ALARM_RESTART_MS / 1000}s")

            // Long-term alarm (5 minutes)
            val longPending = PendingIntent.getBroadcast(
                this, 3, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5 * 60_000L,
                longPending
            )
            Log.w(TAG, "AlarmManager restart scheduled in 5min")
        } catch (e: Exception) {
            Log.e(TAG, "AlarmManager restart failed: ${e.message}")
        }

        // Method 4: WorkManager immediate one-time task (survives OEM killers best)
        try {
            SmsWorker.scheduleImmediate(this)
            Log.w(TAG, "WorkManager immediate task scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager restart failed: ${e.message}")
        }
    }

    private fun cancelRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val restartIntent = Intent(this, RestartReceiver::class.java).apply {
                action = RestartReceiver.ACTION_RESTART
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            )
            pendingIntent?.let { alarmManager.cancel(it) }
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy 1: Dynamic BroadcastReceiver
    // ─────────────────────────────────────────────────────────────
    private fun registerSmsReceiver() {
        if (isReceiverRegistered) return
        try {
            smsReceiver = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 999
            }
            registerReceiver(smsReceiver, filter)
            isReceiverRegistered = true
            Log.w(TAG, "Dynamic SMS receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy 2: ContentObserver (on dedicated HandlerThread)
    // Runs on its own Looper — survives main thread freezing
    // ─────────────────────────────────────────────────────────────
    private fun registerSmsObserver() {
        if (isObserverRegistered) return
        try {
            // Create a dedicated HandlerThread for ContentObserver callbacks
            if (observerThread == null) {
                observerThread = HandlerThread("SmsObserverThread").apply {
                    start()
                }
                observerHandler = Handler(observerThread!!.looper)
            }

            smsObserver = SmsContentObserver(observerHandler!!)
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                smsObserver!!
            )
            isObserverRegistered = true
            Log.w(TAG, "SMS ContentObserver registered (dedicated thread)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register observer: ${e.message}")
        }
    }

    inner class SmsContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            observerHandler?.removeCallbacks(observerScanRunnable)
            observerHandler?.postDelayed(observerScanRunnable, OBSERVER_DEBOUNCE_MS)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            observerHandler?.removeCallbacks(observerScanRunnable)
            observerHandler?.postDelayed(observerScanRunnable, OBSERVER_DEBOUNCE_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy 5: Silent Audio — prevents Griffin cgroup freeze
    // Playing silent audio keeps the AudioFlinger thread active,
    // which signals to the kernel/OEM power manager that this
    // process is actively producing audio and MUST NOT be frozen.
    // Used by WhatsApp, Telegram for background call persistence.
    // ─────────────────────────────────────────────────────────────
    private fun startSilentAudio() {
        if (silentPlayer != null) return  // Already playing

        try {
            silentPlayer = MediaPlayer.create(this, R.raw.silent)?.apply {
                isLooping = true
                setVolume(0f, 0f)  // Completely silent
                // Use USAGE_VOICE_COMMUNICATION for highest priority 
                // (same as phone calls — maximum protection from freezing)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                start()
            }
            DebugLog.w(TAG, "Silent audio started (anti-freeze)")
        } catch (e: Exception) {
            DebugLog.e(TAG, "Silent audio failed: ${e.message}")
        }
    }

    private fun stopSilentAudio() {
        silentPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        silentPlayer = null
    }

    // ─────────────────────────────────────────────────────────────
    // Strategy 3: Polling via ScheduledExecutorService
    // Uses a dedicated thread pool — bypasses main Looper entirely.
    // The kernel's nanosleep() (used by ScheduledExecutorService)
    // is more reliable than Handler+MessageQueue under cgroup
    // throttling and OEM power management.
    // ─────────────────────────────────────────────────────────────

    private fun startPolling() {
        // Always restart — no guard. If Griffin froze us, the old executor is dead.
        // Shutdown old executor if exists
        pollFuture?.cancel(false)
        pollExecutor?.shutdownNow()

        // Create a single-thread executor with a daemon thread
        pollExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "SmsPollThread").apply {
                isDaemon = false  // Non-daemon: survives longer
                priority = Thread.MAX_PRIORITY  // Hint to scheduler
            }
        }

        lastPollTimestamp.set(SystemClock.elapsedRealtime())

        pollFuture = pollExecutor!!.scheduleAtFixedRate({
            try {
                lastPollTimestamp.set(SystemClock.elapsedRealtime())
                scanNewSms()
            } catch (e: Exception) {
                DebugLog.e(TAG, "Poll scan error: ${e.message}")
            }
        }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)

        // Schedule wake lock renewal on this executor too
        scheduleWakeLockRenewal()

        DebugLog.w(TAG, ">>> POLLING STARTED via ScheduledExecutor (${POLL_INTERVAL_MS}ms) <<<")
    }

    // ─────────────────────────────────────────────────────────────
    // Initial Scan: Read ALL existing SMS on first run
    // ─────────────────────────────────────────────────────────────
    private fun initialScan() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val firstScanDone = prefs.getBoolean(KEY_FIRST_SCAN_DONE, false)

        if (firstScanDone && lastProcessedSmsId > 0) {
            DebugLog.w(TAG, "Initial scan already done. Scanning new (id > $lastProcessedSmsId)")
            scanNewSms()
            return
        }

        DebugLog.w(TAG, "=== FIRST RUN: Scanning ALL inbox SMS ===")
        updateNotification("Scanning inbox...")

        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.SUBSCRIPTION_ID
                ),
                null,
                null,
                "${Telephony.Sms._ID} ASC"
            )

            if (cursor == null) {
                DebugLog.e(TAG, "Initial scan: cursor is null!")
                return
            }

            var totalCount = 0
            var paymentCount = 0
            var skippedCount = 0
            var maxId = 0L

            cursor.use { c ->
                Log.w(TAG, "Initial scan: ${c.count} total SMS in inbox")
                totalCount = c.count

                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val address = c.getString(1) ?: "unknown"
                    val body = c.getString(2) ?: continue
                    val subId = c.getInt(4)

                    if (id > maxId) maxId = id
                    trackProcessedId(id)

                    val simSlot = subIdToSlot(subId)
                    val patterns = patternStore.getEnabled()
                    val payment = SmsParser.parseWithConfig(address, body, patterns)
                    if (payment != null) {
                        // Check if already exists in DB
                        if (paymentDb.paymentExists(payment.trxid)) {
                            skippedCount++
                            Log.w(TAG, "Already in DB [id=$id]: ${payment.trxid} (skipping)")
                            continue
                        }
                        paymentCount++
                        Log.w(TAG, "Found payment [id=$id]: ${payment.method} ${payment.amount}TK ${payment.trxid} (SIM${simSlot+1})")
                        handlePayment(payment, id, body, address, simSlot)
                    }
                }
            }

            lastProcessedSmsId = maxId
            prefs.edit()
                .putLong(KEY_LAST_ID, maxId)
                .putBoolean(KEY_FIRST_SCAN_DONE, true)
                .apply()

            Log.w(TAG, "=== Initial scan complete: $totalCount SMS, $paymentCount new payments, $skippedCount already sent, lastId=$maxId ===")
            updateNotification("Active - ${paymentDb.getSentCount()} payments tracked")

        } catch (e: Exception) {
            DebugLog.e(TAG, "Initial scan failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Incremental Scan: Only read SMS with _id > lastProcessedSmsId
    // ─────────────────────────────────────────────────────────────
    @Synchronized
    private fun scanNewSms() {
        if (isScanning) return
        isScanning = true
        scanCount++
        // Log heartbeat every 10 scans (~30 seconds)
        if (scanCount % 10 == 1) {
            DebugLog.w(TAG, "[heartbeat] scan #$scanCount, lastId=$lastProcessedSmsId")
        }

        try {
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.SUBSCRIPTION_ID
                ),
                "${Telephony.Sms._ID} > ?",
                arrayOf(lastProcessedSmsId.toString()),
                "${Telephony.Sms._ID} ASC"
            )

            if (cursor == null) return
            var lastIdChanged = false

            cursor.use { c ->
                if (c.count > 0) {
                    DebugLog.w(TAG, "Scan: ${c.count} new SMS (id > $lastProcessedSmsId)")
                }

                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val address = c.getString(1) ?: "unknown"
                    val body = c.getString(2) ?: continue
                    val subId = c.getInt(4)
                    val simSlot = subIdToSlot(subId)

                    if (!trackProcessedId(id)) continue

                    if (id > lastProcessedSmsId) {
                        lastProcessedSmsId = id
                        lastIdChanged = true
                    }

                    DebugLog.w(TAG, "New SMS [id=$id] from=$address SIM${simSlot+1} body=${body.take(60)}...")

                    val patterns = patternStore.getEnabled()
                    val payment = SmsParser.parseWithConfig(address, body, patterns)
                    if (payment != null) {
                        // Check SQLite DB to avoid duplicate sends
                        if (paymentDb.paymentExists(payment.trxid)) {
                            DebugLog.w(TAG, "Already in DB [id=$id]: ${payment.trxid}")
                            continue
                        }
                        DebugLog.w(TAG, ">>> PAYMENT [id=$id]: ${payment.method} ${payment.amount}TK ${payment.trxid} (SIM${simSlot+1})")
                        handlePayment(payment, id, body, address, simSlot)
                    }
                }
            }

            if (lastIdChanged) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_ID, lastProcessedSmsId).apply()
            }

        } catch (e: Exception) {
            DebugLog.e(TAG, "scanNewSms failed: ${e.message}")
        } finally {
            isScanning = false
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Handle a detected payment - send to webhook + record in DB
    // ─────────────────────────────────────────────────────────────
    private fun handlePayment(payment: SmsParser.Payment, smsId: Long = 0L, smsBody: String = "", smsAddress: String = "", simSlot: Int = -1) {
        // Insert into DB as pending first (with SIM slot for retry)
        paymentDb.insertPayment(payment, smsBody, smsAddress, smsId, simSlot)
        DebugLog.w(TAG, "Inserted ${payment.trxid} as PENDING, sending to webhooks (SIM${simSlot+1})...")

        serviceScope.launch {
            val status = WebhookSender.sendToMatchingWebhooks(payment, simSlot, this@SmsMonitorService)

            DebugLog.w(TAG, ">>> ${payment.method.uppercase()} ${payment.amount}TK [${payment.trxid}] [$status]")

            // Update DB status
            when (status) {
                "sent" -> paymentDb.markPaymentSent(payment, smsId)
                "idle" -> paymentDb.markPaymentIdle(payment)
                // "pending" stays as inserted
            }

            // Notify UI to refresh list
            SmsReceiver.onPaymentDetected?.invoke("refresh")

            // Update notification with DB count
            val totalSent = paymentDb.getSentCount()
            updateNotification("Last: ${payment.method.uppercase()} ${payment.amount}TK | Total: $totalSent sent")
        }
    }

    /**
     * Retry all pending (unsent) payments from the database.
     * Called on service startup to clear any backlog from when webhooks weren't configured yet.
     */
    private suspend fun retryPendingPayments() {
        try {
            val pending = paymentDb.getAllPayments().filter { it.status == "pending" }
            if (pending.isEmpty()) return

            DebugLog.w(TAG, "=== Retrying ${pending.size} pending payments ===")
            var sent = 0

            for (record in pending) {
                val payment = SmsParser.Payment(
                    method = record.method,
                    amount = record.amount,
                    trxid = record.trxid
                )
                // Use stored SIM slot from DB instead of -1
                val status = WebhookSender.sendToMatchingWebhooks(payment, record.simSlot, this@SmsMonitorService)
                when (status) {
                    "sent" -> {
                        paymentDb.markPaymentSent(payment)
                        sent++
                        DebugLog.w(TAG, "  Retry OK: ${record.trxid}")
                    }
                    "idle" -> {
                        paymentDb.markPaymentIdle(payment)
                        DebugLog.w(TAG, "  Retry idle: ${record.trxid} (no matching webhook)")
                    }
                }
            }

            if (sent > 0) {
                DebugLog.w(TAG, "=== Retry complete: $sent/${pending.size} sent ===")
                SmsReceiver.onPaymentDetected?.invoke("refresh")
                updateNotification("Retried $sent pending — ${paymentDb.getSentCount()} total sent")
            } else {
                DebugLog.w(TAG, "=== Retry complete: 0/${pending.size} sent (check webhook config) ===")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "retryPendingPayments failed: ${e.message}")
        }
    }

    private fun trackProcessedId(id: Long): Boolean {
        if (processedIds.contains(id)) return false
        processedIds.add(id)
        if (processedIds.size > MAX_TRACKED_SMS_IDS) {
            val oldest = processedIds.firstOrNull()
            if (oldest != null) {
                processedIds.remove(oldest)
            }
        }
        return true
    }

    /**
     * Convert a subscription ID (sub_id) from the SMS content provider
     * to a SIM slot index (0 = SIM1, 1 = SIM2).
     *
     * Uses getActiveSubscriptionInfoList() to build a reliable subId→slot map.
     * Requires READ_PHONE_STATE permission.
     */
    private fun subIdToSlot(subId: Int): Int {
        if (subId <= 0) return -1
        return try {
            val sm = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE)
                    as? android.telephony.SubscriptionManager
            // Build a complete subId → slotIndex map from all active subscriptions
            val subs = sm?.activeSubscriptionInfoList
            if (subs != null) {
                for (info in subs) {
                    if (info.subscriptionId == subId) {
                        DebugLog.w(TAG, "subIdToSlot($subId) → slot ${info.simSlotIndex} (from list)")
                        return info.simSlotIndex
                    }
                }
            }
            // Fallback: try direct lookup
            val info = sm?.getActiveSubscriptionInfo(subId)
            if (info != null) {
                DebugLog.w(TAG, "subIdToSlot($subId) → slot ${info.simSlotIndex} (direct)")
                return info.simSlotIndex
            }
            DebugLog.w(TAG, "subIdToSlot($subId) → -1 (no subscription info found)")
            -1
        } catch (e: Exception) {
            DebugLog.e(TAG, "subIdToSlot($subId) exception: ${e.message}")
            -1
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Monitor",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps SMS monitoring active in background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String = "Running"): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }
}
