package com.smsmonitor.app

/**
 * Parses SMS messages using configurable patterns.
 * Each pattern has a sender filter, optional body keyword,
 * and named regex fields with include/exclude toggles.
 */
object SmsParser {

    data class Payment(
        val method: String,
        val amount: Double,
        val trxid: String,
        val typeId: String = "",            // matched pattern's typeId
        val extras: Map<String, String> = emptyMap()
    )

    // ── Built-in bKash patterns ──
    private val BKASH_AMOUNT = Regex("""(?i)received\s+Tk\s+([\d,]+\.?\d*)""")
    private val BKASH_TRXID = Regex("""(?i)TrxID\s+([A-Z0-9]{10})""")

    // ── Built-in Nagad patterns ──
    private val NAGAD_AMOUNT = Regex("""(?i)Amount:\s*Tk\s*([\d,]+\.?\d*)""")
    private val NAGAD_TRXID = Regex("""(?i)TxnID:\s*([A-Z0-9]+)""")

    fun isBkash(address: String, body: String): Boolean {
        return address.lowercase().contains("bkash") ||
               body.lowercase().contains("you have received tk")
    }

    fun isNagad(address: String, body: String): Boolean {
        return address.lowercase().contains("nagad") ||
               body.lowercase().contains("money received")
    }

    fun parseBkash(body: String): Payment? {
        val amountMatch = BKASH_AMOUNT.find(body) ?: return null
        val trxidMatch = BKASH_TRXID.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val trxid = trxidMatch.groupValues[1].uppercase()
        return Payment("bkash", amount, trxid, "type_payment",
            mapOf("method" to "bkash", "amount" to amount.toString(), "trxid" to trxid))
    }

    fun parseNagad(body: String): Payment? {
        val amountMatch = NAGAD_AMOUNT.find(body) ?: return null
        val trxidMatch = NAGAD_TRXID.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val trxid = trxidMatch.groupValues[1].uppercase()
        return Payment("nagad", amount, trxid, "type_payment",
            mapOf("method" to "nagad", "amount" to amount.toString(), "trxid" to trxid))
    }

    /** Quick parse without configs (for backward compat) */
    fun parse(address: String, body: String): Payment? {
        return when {
            isBkash(address, body) -> parseBkash(body)
            isNagad(address, body) -> parseNagad(body)
            else -> null
        }
    }

    /**
     * Parse using configurable patterns.
     * All patterns (built-in and user-defined) go through the same tryPattern() path
     * so that user edits to regex fields always take effect.
     */
    fun parseWithConfig(address: String, body: String, patterns: List<SmsPatternConfig>): Payment? {
        for (pattern in patterns) {
            if (!pattern.enabled) continue
            tryPattern(address, body, pattern)?.let { return it }
        }
        return null
    }

    /**
     * Universal pattern matching.
     * Checks sender (contains), optional body keyword,
     * then runs all included regex fields.
     */
    private fun tryPattern(address: String, body: String, pattern: SmsPatternConfig): Payment? {
        try {
            // Sender filter (simple contains, case-insensitive)
            if (pattern.senderPattern.isNotEmpty()) {
                if (!address.lowercase().contains(pattern.senderPattern.lowercase())) return null
            }

            // Body keyword filter
            if (pattern.bodyKeyword.isNotEmpty()) {
                if (!body.lowercase().contains(pattern.bodyKeyword.lowercase())) return null
            }

            val extracted = mutableMapOf<String, String>()
            extracted["method"] = pattern.name.lowercase()
            extracted["sms_body"] = body

            // Run all included extract fields
            for (field in pattern.extractFields) {
                if (!field.included) continue
                if (field.regex.isBlank()) continue
                try {
                    val match = Regex(field.regex).find(body)
                    if (match != null) {
                        val value = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        extracted[field.fieldName] = value
                    }
                } catch (_: Exception) { }
            }

            // Need at least one extracted field beyond method + sms_body
            if (extracted.size <= 2) return null

            return Payment(
                method = pattern.name.lowercase(),
                amount = extracted["amount"]?.replace(",", "")?.toDoubleOrNull() ?: 0.0,
                trxid = extracted["trxid"] ?: extracted.entries
                    .firstOrNull { it.key != "method" && it.key != "sms_body" && it.key != "amount" }
                    ?.value ?: "",
                typeId = pattern.typeId,
                extras = extracted
            )
        } catch (_: Exception) {
            return null
        }
    }

    /** Generate unique SMS ID for dedup */
    fun generateSmsId(address: String, body: String, timestamp: Long): String {
        return "$address:$timestamp:${body.hashCode()}"
    }
}
