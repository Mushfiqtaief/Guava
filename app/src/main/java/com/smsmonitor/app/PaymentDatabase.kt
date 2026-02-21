package com.smsmonitor.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * Data class representing a payment record for the UI.
 */
data class PaymentRecord(
    val id: Long,
    val trxid: String,
    val method: String,
    val amount: Double,
    val status: String,        // "sent" or "pending"
    val smsBody: String,
    val smsAddress: String,
    val detectedAt: Long,
    val sentAt: Long
)

/**
 * Local SQLite database to track all detected payments.
 * Stores full SMS body for detail view. Tracks sent/pending status.
 */
class PaymentDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "PaymentDB"
        private const val DB_NAME = "payments.db"
        private const val DB_VERSION = 2  // Upgraded from 1

        private const val TABLE = "payments"
        private const val COL_ID = "id"
        private const val COL_TRXID = "trxid"
        private const val COL_METHOD = "method"
        private const val COL_AMOUNT = "amount"
        private const val COL_STATUS = "status"       // "sent" or "pending"
        private const val COL_SMS_BODY = "sms_body"
        private const val COL_SMS_ADDRESS = "sms_address"
        private const val COL_SMS_ID = "sms_id"
        private const val COL_DETECTED_AT = "detected_at"
        private const val COL_SENT_AT = "sent_at"

        @Volatile
        private var instance: PaymentDatabase? = null

        fun getInstance(context: Context): PaymentDatabase {
            return instance ?: synchronized(this) {
                instance ?: PaymentDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TRXID TEXT NOT NULL UNIQUE,
                $COL_METHOD TEXT NOT NULL,
                $COL_AMOUNT REAL NOT NULL,
                $COL_STATUS TEXT NOT NULL DEFAULT 'pending',
                $COL_SMS_BODY TEXT DEFAULT '',
                $COL_SMS_ADDRESS TEXT DEFAULT '',
                $COL_SMS_ID INTEGER DEFAULT 0,
                $COL_DETECTED_AT INTEGER NOT NULL,
                $COL_SENT_AT INTEGER DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX idx_trxid ON $TABLE ($COL_TRXID)")
        Log.w(TAG, "Database v$DB_VERSION created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Drop and recreate - clean slate for new schema
        db.execSQL("DROP TABLE IF EXISTS sent_payments")
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
        Log.w(TAG, "Database upgraded from v$oldVersion to v$newVersion")
    }

    /**
     * Check if a payment with the given trxid already exists in the DB.
     */
    fun isPaymentSent(trxid: String): Boolean {
        return try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE, arrayOf(COL_STATUS),
                "$COL_TRXID = ?", arrayOf(trxid),
                null, null, null, "1"
            )
            val sent = if (cursor.moveToFirst()) cursor.getString(0) == "sent" else false
            cursor.close()
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Error checking payment: ${e.message}")
            false
        }
    }

    /**
     * Check if a payment trxid exists at all (sent or pending).
     */
    fun paymentExists(trxid: String): Boolean {
        return try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE, arrayOf(COL_ID),
                "$COL_TRXID = ?", arrayOf(trxid),
                null, null, null, "1"
            )
            val exists = cursor.count > 0
            cursor.close()
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Error checking exists: ${e.message}")
            false
        }
    }

    /**
     * Insert a newly detected payment as "pending".
     */
    fun insertPayment(
        payment: SmsParser.Payment,
        smsBody: String = "",
        smsAddress: String = "",
        smsId: Long = 0L
    ): Long {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_TRXID, payment.trxid)
                put(COL_METHOD, payment.method)
                put(COL_AMOUNT, payment.amount)
                put(COL_STATUS, "pending")
                put(COL_SMS_BODY, smsBody)
                put(COL_SMS_ADDRESS, smsAddress)
                put(COL_SMS_ID, smsId)
                put(COL_DETECTED_AT, System.currentTimeMillis())
                put(COL_SENT_AT, 0L)
            }
            val result = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (result != -1L) {
                Log.w(TAG, "Payment inserted (pending): ${payment.trxid}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting payment: ${e.message}")
            -1L
        }
    }

    /**
     * Mark a payment as successfully sent to webhook.
     */
    fun markPaymentSent(payment: SmsParser.Payment, smsId: Long = 0L): Boolean {
        return try {
            val db = writableDatabase
            // First try to update existing pending record
            val values = ContentValues().apply {
                put(COL_STATUS, "sent")
                put(COL_SENT_AT, System.currentTimeMillis())
            }
            val updated = db.update(TABLE, values, "$COL_TRXID = ?", arrayOf(payment.trxid))
            if (updated > 0) {
                Log.w(TAG, "Payment marked sent: ${payment.trxid}")
                return true
            }
            // If not found, insert as sent directly
            val insertValues = ContentValues().apply {
                put(COL_TRXID, payment.trxid)
                put(COL_METHOD, payment.method)
                put(COL_AMOUNT, payment.amount)
                put(COL_STATUS, "sent")
                put(COL_SMS_BODY, "")
                put(COL_SMS_ADDRESS, "")
                put(COL_SMS_ID, smsId)
                put(COL_DETECTED_AT, System.currentTimeMillis())
                put(COL_SENT_AT, System.currentTimeMillis())
            }
            val result = db.insertWithOnConflict(TABLE, null, insertValues, SQLiteDatabase.CONFLICT_IGNORE)
            result != -1L
        } catch (e: Exception) {
            Log.e(TAG, "Error marking sent: ${e.message}")
            false
        }
    }

    /**
     * Get all payment records ordered by newest first.
     */
    fun getAllPayments(): List<PaymentRecord> {
        val list = mutableListOf<PaymentRecord>()
        try {
            val db = readableDatabase
            val cursor = db.query(
                TABLE,
                arrayOf(COL_ID, COL_TRXID, COL_METHOD, COL_AMOUNT, COL_STATUS,
                    COL_SMS_BODY, COL_SMS_ADDRESS, COL_DETECTED_AT, COL_SENT_AT),
                null, null, null, null,
                "$COL_ID DESC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    list.add(
                        PaymentRecord(
                            id = c.getLong(0),
                            trxid = c.getString(1),
                            method = c.getString(2),
                            amount = c.getDouble(3),
                            status = c.getString(4),
                            smsBody = c.getString(5) ?: "",
                            smsAddress = c.getString(6) ?: "",
                            detectedAt = c.getLong(7),
                            sentAt = c.getLong(8)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting payments: ${e.message}")
        }
        return list
    }

    fun getSentCount(): Int = getCountByStatus("sent")
    fun getPendingCount(): Int = getCountByStatus("pending")

    private fun getCountByStatus(status: String): Int {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS = ?", arrayOf(status))
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error getting count: ${e.message}")
            0
        }
    }

    fun getTotalAmount(): Double {
        return try {
            val db = readableDatabase
            val cursor = db.rawQuery("SELECT SUM($COL_AMOUNT) FROM $TABLE", null)
            val total = if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
            cursor.close()
            total
        } catch (e: Exception) {
            Log.e(TAG, "Error getting total: ${e.message}")
            0.0
        }
    }

    fun cleanup(keepCount: Int = 5000) {
        try {
            val db = writableDatabase
            db.execSQL("""
                DELETE FROM $TABLE WHERE $COL_ID NOT IN (
                    SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT $keepCount
                )
            """.trimIndent())
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }
}
