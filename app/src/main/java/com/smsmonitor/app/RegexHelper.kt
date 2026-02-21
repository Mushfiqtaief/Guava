package com.smsmonitor.app

/**
 * Utility to auto-generate regex patterns from a sample SMS and selected text.
 * Used by Quick Add Pattern to let users select text and auto-create extraction regex.
 */
object RegexHelper {

    /**
     * Generate a regex that matches the selected text within the SMS body.
     * Uses surrounding context (words before/after) to create a precise pattern.
     * Group 1 captures the value.
     */
    fun generateRegex(fullBody: String, selectedText: String): String {
        if (selectedText.isBlank() || !fullBody.contains(selectedText)) return Regex.escape(selectedText)

        val idx = fullBody.indexOf(selectedText)
        val before = fullBody.substring(0, idx)
        val after = fullBody.substring(idx + selectedText.length)

        // Get context words before and after the selection
        val prefixContext = getContextBefore(before)
        val suffixContext = getContextAfter(after)

        // Determine the type of the selected text and build an appropriate capture group
        val captureGroup = buildCaptureGroup(selectedText)

        return buildString {
            if (prefixContext.isNotEmpty()) {
                append("(?i)")
                append(Regex.escape(prefixContext))
                append("\\s*")
            }
            append("(")
            append(captureGroup)
            append(")")
            if (suffixContext.isNotEmpty()) {
                append("\\s*")
                // Don't include suffix in regex if it would be too restrictive
            }
        }
    }

    /**
     * Suggest a field name based on the selected text and its context in the SMS.
     */
    fun suggestFieldName(fullBody: String, selectedText: String): String {
        val lower = fullBody.lowercase()
        val idx = fullBody.indexOf(selectedText)
        val before = if (idx > 0) fullBody.substring(maxOf(0, idx - 50), idx).lowercase() else ""

        return when {
            // OTP / verification code
            selectedText.matches(Regex("\\d{4,8}")) && (lower.contains("otp") || lower.contains("code") || lower.contains("verif")) -> "otp"
            // Amount / money
            before.contains("tk") || before.contains("amount") || before.contains("taka") || before.contains("bdt") -> "amount"
            // Transaction ID
            before.contains("trx") || before.contains("txn") || before.contains("transaction") || before.contains("ref") -> "trxid"
            // Phone number
            selectedText.matches(Regex("\\+?\\d{10,14}")) -> "phone"
            // PIN
            before.contains("pin") -> "pin"
            // Name
            before.contains("from") || before.contains("name") -> "sender_name"
            // Balance
            before.contains("balance") || before.contains("bal") -> "balance"
            // Date/time
            before.contains("date") || before.contains("time") || selectedText.contains("/") -> "date"
            // Account
            before.contains("a/c") || before.contains("account") -> "account"
            // Numeric: likely an amount or code
            selectedText.matches(Regex("[\\d,.]+")) -> {
                if (selectedText.length <= 8) "code" else "amount"
            }
            // Alphanumeric: likely a reference/id
            selectedText.matches(Regex("[A-Za-z0-9]+")) && selectedText.length in 5..15 -> "ref_id"
            else -> "value"
        }
    }

    /**
     * Get up to N words/characters of context before the selection for prefix matching.
     */
    private fun getContextBefore(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return ""

        // Get the last meaningful chunk (word + separator) before selection
        // Try to capture a label like "Tk ", "TrxID ", "Amount: ", "OTP is ", etc.
        val lastPart = trimmed.takeLast(30)
        // Find the last "label-like" segment: words + optional punctuation
        val labelMatch = Regex("""([\w.]+[:\s]+)$""").find(lastPart)
        if (labelMatch != null) return labelMatch.groupValues[1]

        // Fallback: last word and separator
        val wordMatch = Regex("""(\S+\s*)$""").find(lastPart)
        return wordMatch?.groupValues?.get(1) ?: trimmed.takeLast(10)
    }

    /**
     * Get context after the selection.
     */
    private fun getContextAfter(text: String): String {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return ""
        val firstWord = Regex("""^(\s*\S+)""").find(trimmed)
        return firstWord?.groupValues?.get(1)?.take(10) ?: ""
    }

    /**
     * Build a regex capture group pattern based on the type of selected text.
     */
    private fun buildCaptureGroup(text: String): String {
        return when {
            // Pure digits (OTP, PIN, amounts without decimals)
            text.matches(Regex("\\d+")) -> "\\d{${text.length}}"
            // Decimal number (amount like 1500.00)
            text.matches(Regex("[\\d,]+\\.\\d+")) -> "[\\d,]+\\.?\\d*"
            // Number with commas
            text.matches(Regex("[\\d,]+")) -> "[\\d,]+"
            // Alphanumeric uppercase (TrxID like ABC1234XYZ)
            text.matches(Regex("[A-Z0-9]+")) -> "[A-Z0-9]+"
            // Alphanumeric mixed case
            text.matches(Regex("[A-Za-z0-9]+")) -> "[A-Za-z0-9]+"
            // Phone number format
            text.matches(Regex("\\+?\\d[\\d\\s-]+")) -> "[\\d+\\s-]+"
            // Words (names, etc.)
            text.matches(Regex("[A-Za-z\\s]+")) -> "[A-Za-z\\s]+"
            // Anything else: use a general match but limit to approximate length
            else -> ".{${maxOf(1, text.length - 2)},${text.length + 5}}"
        }
    }
}
