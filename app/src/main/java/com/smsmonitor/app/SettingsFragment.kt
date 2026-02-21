package com.smsmonitor.app

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsFragment : Fragment() {

    private lateinit var patternStore: PatternStore
    private lateinit var patternTypeStore: PatternTypeStore
    private lateinit var patternContainer: LinearLayout
    private lateinit var chipGroupPatternTypes: ChipGroup
    private lateinit var tvVersion: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        patternStore = PatternStore.getInstance(ctx)
        patternTypeStore = PatternTypeStore.getInstance(ctx)
        patternContainer = view.findViewById(R.id.patternContainer)
        chipGroupPatternTypes = view.findViewById(R.id.chipGroupPatternTypes)
        tvVersion = view.findViewById(R.id.tvVersion)

        view.findViewById<View>(R.id.btnAddPatternType).setOnClickListener {
            showAddPatternTypeDialog()
        }

        view.findViewById<View>(R.id.btnAddPattern).setOnClickListener {
            showPatternDialog(null)
        }

        view.findViewById<View>(R.id.btnQuickAdd).setOnClickListener {
            startActivity(Intent(ctx, QuickAddPatternActivity::class.java))
        }

        view.findViewById<View>(R.id.btnDocs).setOnClickListener {
            showDocsDialog()
        }

        val switchDebug = view.findViewById<MaterialSwitch>(R.id.switchDebug)
        val prefs = ctx.getSharedPreferences("sms_monitor", android.content.Context.MODE_PRIVATE)
        switchDebug.isChecked = prefs.getBoolean("debug_logging", false)
        switchDebug.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("debug_logging", checked).apply()
        }

        view.findViewById<View>(R.id.btnCleanup).setOnClickListener {
            PaymentDatabase.getInstance(ctx).cleanup(1000)
            Toast.makeText(ctx, "Database cleaned up", Toast.LENGTH_SHORT).show()
        }

        try {
            val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            tvVersion.text = pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            tvVersion.text = "1.0"
        }

        refreshPatternTypes()
        refreshPatterns()
    }

    override fun onResume() {
        super.onResume()
        refreshPatternTypes()
        refreshPatterns()
    }

    // ─── Pattern Types ───

    private fun refreshPatternTypes() {
        chipGroupPatternTypes.removeAllViews()
        val ctx = context ?: return
        val types = patternTypeStore.getAll()

        for (type in types) {
            val chip = Chip(ctx).apply {
                text = type.name
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    AlertDialog.Builder(ctx)
                        .setTitle("Delete Type")
                        .setMessage("Delete \"${type.name}\"?\nPatterns using this type will become unlinked.")
                        .setPositiveButton("Delete") { _, _ ->
                            patternTypeStore.delete(type.id)
                            refreshPatternTypes()
                            refreshPatterns()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                setOnClickListener {
                    showEditPatternTypeDialog(type)
                }
            }
            chipGroupPatternTypes.addView(chip)
        }
    }

    private fun showAddPatternTypeDialog() {
        val ctx = context ?: return
        val et = TextInputEditText(ctx).apply {
            hint = "e.g. Payment, OTP, Alert"
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Add Pattern Type")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val name = et.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    patternTypeStore.add(PatternType(name = name))
                    refreshPatternTypes()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditPatternTypeDialog(type: PatternType) {
        val ctx = context ?: return
        val et = TextInputEditText(ctx).apply {
            setText(type.name)
            setPadding(48, 32, 48, 16)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Edit Pattern Type")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    patternTypeStore.update(type.copy(name = name))
                    refreshPatternTypes()
                    refreshPatterns()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─── Patterns ───

    private fun refreshPatterns() {
        patternContainer.removeAllViews()
        val ctx = context ?: return
        val patterns = patternStore.getAll()
        val types = patternTypeStore.getAll()

        for ((index, pattern) in patterns.withIndex()) {
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_pattern, patternContainer, false)

            val tvName = row.findViewById<TextView>(R.id.tvPatternName)
            val tvInfo = row.findViewById<TextView>(R.id.tvPatternInfo)
            val switchEnabled = row.findViewById<MaterialSwitch>(R.id.switchPatternEnabled)
            val divider = row.findViewById<View>(R.id.patternDivider)

            // Show type label alongside name
            val typeName = types.find { it.id == pattern.typeId }?.name
            tvName.text = if (typeName != null) "${pattern.name} [$typeName]" else pattern.name

            val infoFields = mutableListOf<String>()
            if (pattern.senderPattern.isNotEmpty()) infoFields.add("Sender: ${pattern.senderPattern}")
            val activeFields = pattern.extractFields.filter { it.included }
            if (activeFields.isNotEmpty()) {
                infoFields.add("Fields: ${activeFields.joinToString(", ") { it.fieldName }}")
            }
            tvInfo.text = if (infoFields.isNotEmpty()) infoFields.joinToString(" · ") else "No filters set"

            switchEnabled.isChecked = pattern.enabled
            divider.visibility = if (index < patterns.size - 1) View.VISIBLE else View.GONE

            switchEnabled.setOnCheckedChangeListener { _, checked ->
                patternStore.update(pattern.copy(enabled = checked))
            }

            row.setOnClickListener { showPatternDialog(pattern) }

            row.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Delete Pattern")
                    .setMessage("Delete \"${pattern.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        patternStore.delete(pattern.id)
                        refreshPatterns()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            patternContainer.addView(row)
        }
    }

    private fun showPatternDialog(pattern: SmsPatternConfig?) {
        val ctx = context ?: return
        val isNew = pattern == null

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_pattern_edit, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etPatternName)
        val etSender = view.findViewById<TextInputEditText>(R.id.etSenderPattern)
        val etKeyword = view.findViewById<TextInputEditText>(R.id.etBodyKeyword)
        val layoutKeyword = view.findViewById<TextInputLayout>(R.id.layoutBodyKeyword)
        val btnToggleKeyword = view.findViewById<MaterialButton>(R.id.btnToggleKeyword)
        val etTestSms = view.findViewById<TextInputEditText>(R.id.etTestSms)
        val btnTest = view.findViewById<MaterialButton>(R.id.btnTestPattern)
        val tvTestResult = view.findViewById<TextView>(R.id.tvTestResult)
        val extractFieldsContainer = view.findViewById<LinearLayout>(R.id.extractFieldsContainer)
        val btnAddExtractField = view.findViewById<MaterialButton>(R.id.btnAddExtractField)

        // Pattern Type chips
        val chipGroupType = view.findViewById<ChipGroup>(R.id.chipGroupPatternType)
        val types = patternTypeStore.getAll()
        val typeChipMap = mutableMapOf<String, Chip>() // typeId -> Chip

        for (type in types) {
            val chip = Chip(ctx).apply {
                text = type.name
                isCheckable = true
                isChecked = pattern?.typeId == type.id
            }
            typeChipMap[type.id] = chip
            chipGroupType.addView(chip)
        }

        val dialogFieldRows = mutableListOf<View>()

        fun addFieldRow(name: String = "", regex: String = "", included: Boolean = true) {
            val rowView = LayoutInflater.from(ctx).inflate(R.layout.item_extract_field, extractFieldsContainer, false)
            val etFieldName = rowView.findViewById<TextInputEditText>(R.id.etFieldName)
            val etFieldRegex = rowView.findViewById<TextInputEditText>(R.id.etFieldRegex)
            val cbInclude = rowView.findViewById<CheckBox>(R.id.cbInclude)
            val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeleteField)

            etFieldName.setText(name)
            etFieldRegex.setText(regex)
            cbInclude.isChecked = included

            btnDelete.setOnClickListener {
                extractFieldsContainer.removeView(rowView)
                dialogFieldRows.remove(rowView)
            }

            extractFieldsContainer.addView(rowView)
            dialogFieldRows.add(rowView)
        }

        btnToggleKeyword.setOnClickListener {
            if (layoutKeyword.visibility == View.GONE) {
                layoutKeyword.visibility = View.VISIBLE
                btnToggleKeyword.text = "− Hide Body Keyword"
            } else {
                layoutKeyword.visibility = View.GONE
                etKeyword.setText("")
                btnToggleKeyword.text = "+ Body Keyword"
            }
        }

        btnAddExtractField.setOnClickListener { addFieldRow() }

        pattern?.let {
            etName.setText(it.name)
            etSender.setText(it.senderPattern)
            if (it.bodyKeyword.isNotEmpty()) {
                etKeyword.setText(it.bodyKeyword)
                layoutKeyword.visibility = View.VISIBLE
                btnToggleKeyword.text = "− Hide Body Keyword"
            }
            for (field in it.extractFields) {
                addFieldRow(field.fieldName, field.regex, field.included)
            }
        }

        btnTest.setOnClickListener {
            val testSms = etTestSms.text?.toString()?.trim() ?: ""
            if (testSms.isEmpty()) {
                etTestSms.error = "Paste a sample SMS"
                return@setOnClickListener
            }

            val testConfig = buildConfigFromDialog(view, dialogFieldRows, typeChipMap, pattern?.id, pattern?.enabled ?: true)
            if (testConfig == null) {
                tvTestResult.text = "⚠ Fill in pattern name first"
                tvTestResult.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val senderTest = etSender.text?.toString()?.trim().let {
                if (it.isNullOrEmpty()) "TestSender" else it
            }
            val result = SmsParser.parseWithConfig(senderTest, testSms, listOf(testConfig))

            tvTestResult.visibility = View.VISIBLE
            if (result != null) {
                val sb = StringBuilder("✓ MATCHED!\n")
                sb.append("Method: ${result.method}\n")
                if (result.amount > 0) sb.append("Amount: ${result.amount}\n")
                if (result.trxid.isNotEmpty()) sb.append("TrxID: ${result.trxid}\n")
                for ((key, value) in result.extras) {
                    if (key != "method" && key != "sms_body") {
                        sb.append("$key: $value\n")
                    }
                }
                tvTestResult.setTextColor(resources.getColor(R.color.status_success, null))
                tvTestResult.text = sb.toString().trimEnd()
            } else {
                tvTestResult.setTextColor(resources.getColor(R.color.status_error, null))
                tvTestResult.text = "✗ No match — check sender and regex fields"
            }
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "Add Pattern" else "Edit Pattern")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)

        if (!isNew) {
            builder.setNeutralButton("Delete") { _, _ ->
                patternStore.delete(pattern!!.id)
                refreshPatterns()
            }
        }

        val alertDialog = builder.create()
        alertDialog.show()

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val config = buildConfigFromDialog(view, dialogFieldRows, typeChipMap, pattern?.id, pattern?.enabled ?: true)
            if (config == null) {
                etName.error = "Required"
                return@setOnClickListener
            }

            if (isNew) patternStore.add(config) else patternStore.update(config)
            refreshPatterns()
            alertDialog.dismiss()
        }
    }

    private fun buildConfigFromDialog(
        view: View,
        fieldRows: List<View>,
        typeChipMap: Map<String, Chip>,
        existingId: String?,
        enabled: Boolean
    ): SmsPatternConfig? {
        val name = view.findViewById<TextInputEditText>(R.id.etPatternName).text?.toString()?.trim() ?: ""
        if (name.isEmpty()) return null

        // Find selected type
        val selectedTypeId = typeChipMap.entries.firstOrNull { it.value.isChecked }?.key ?: ""

        val extractFields = fieldRows.map { rowView ->
            val fieldName = rowView.findViewById<TextInputEditText>(R.id.etFieldName).text?.toString()?.trim() ?: ""
            val regex = rowView.findViewById<TextInputEditText>(R.id.etFieldRegex).text?.toString()?.trim() ?: ""
            val included = rowView.findViewById<CheckBox>(R.id.cbInclude).isChecked
            SmsPatternConfig.ExtractField(fieldName, regex, included)
        }.filter { it.fieldName.isNotEmpty() && it.regex.isNotEmpty() }

        return SmsPatternConfig(
            id = existingId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            enabled = enabled,
            typeId = selectedTypeId,
            senderPattern = view.findViewById<TextInputEditText>(R.id.etSenderPattern).text?.toString()?.trim() ?: "",
            bodyKeyword = view.findViewById<TextInputEditText>(R.id.etBodyKeyword).text?.toString()?.trim() ?: "",
            extractFields = extractFields,
            isBuiltIn = false
        )
    }

    /** Show the in-app Docs / Tutorial dialog */
    private fun showDocsDialog() {
        val ctx = context ?: return
        val docsText = buildString {
            appendLine("📱 GUAVA — SMS MONITOR & WEBHOOK FORWARDER")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            appendLine("Guava listens to incoming SMS messages in the")
            appendLine("background, extracts data using regex patterns,")
            appendLine("and forwards it to your webhook endpoints — all")
            appendLine("in real-time, completely on-device.")
            appendLine()
            appendLine()
            appendLine("━━━ GETTING STARTED ━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("1. Grant SMS & notification permissions when asked")
            appendLine("2. Start the monitoring service from Dashboard")
            appendLine("3. Add at least one Pattern Type (e.g. Payment)")
            appendLine("4. Add an SMS Pattern (e.g. bKash) linked to that type")
            appendLine("5. Add a Webhook and subscribe it to the pattern type")
            appendLine("6. That's it! Guava will forward matched SMS data")
            appendLine()
            appendLine()
            appendLine("━━━ PATTERN TYPES ━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Pattern Types are categories you create to organize")
            appendLine("your SMS patterns and control webhook routing.")
            appendLine()
            appendLine("Examples: Payment, OTP, Notifications, Alerts")
            appendLine()
            appendLine("  • Tap \"+\" to add a new type")
            appendLine("  • Tap a chip to rename it")
            appendLine("  • Tap × on a chip to delete it")
            appendLine("  • Two defaults are seeded: Payment & OTP")
            appendLine()
            appendLine("Why types matter:")
            appendLine("  Each SMS pattern belongs to exactly one type.")
            appendLine("  Each webhook subscribes to one or more types.")
            appendLine("  So an OTP webhook won't receive payment data,")
            appendLine("  and your payment API won't get OTP messages.")
            appendLine()
            appendLine()
            appendLine("━━━ SMS PATTERNS ━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Patterns tell Guava how to recognize and parse SMS.")
            appendLine()
            appendLine("Each pattern has:")
            appendLine()
            appendLine("  Pattern Name")
            appendLine("    A label like \"bKash\" or \"GP OTP\". This becomes")
            appendLine("    the \"method\" field in webhook payloads.")
            appendLine()
            appendLine("  Pattern Type")
            appendLine("    Select which category this pattern belongs to.")
            appendLine("    This controls which webhooks receive the data.")
            appendLine()
            appendLine("  Sender Filter")
            appendLine("    Matches against SMS sender (case-insensitive,")
            appendLine("    partial match). E.g. \"bkash\" matches \"bKash-123\".")
            appendLine("    Leave empty to check ALL senders.")
            appendLine()
            appendLine("  Body Keyword (optional)")
            appendLine("    Extra filter — SMS body must contain this text.")
            appendLine("    Useful when sender alone isn't specific enough.")
            appendLine()
            appendLine("  Regex Extract Fields")
            appendLine("    The core of data extraction. Each field has:")
            appendLine()
            appendLine("    • Name — what you're extracting (amount, otp, trxid)")
            appendLine("    • Regex — pattern to find it. Group 1 is captured.")
            appendLine("    • Include ☑ — toggle to enable/disable the field")
            appendLine()
            appendLine("    Example:")
            appendLine("      Name:  amount")
            appendLine("      Regex: Tk\\s+([\\d,]+\\.?\\d*)")
            appendLine("      SMS:   \"You received Tk 1,500.00 from...\"")
            appendLine("      Result: amount = \"1,500.00\"")
            appendLine()
            appendLine("    Special field names:")
            appendLine("      \"amount\" → parsed as number in payload")
            appendLine("      \"trxid\"  → used as transaction ID for dedup")
            appendLine("      Any other name → included as string in extras")
            appendLine()
            appendLine()
            appendLine("━━━ QUICK ADD PATTERN ━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Can't write regex? No problem!")
            appendLine()
            appendLine("  1. Tap 'Quick Add Pattern' in Settings")
            appendLine("  2. Enter a pattern name and select a type")
            appendLine("  3. Paste a real sample SMS message")
            appendLine("  4. Select/highlight any value in the SMS text")
            appendLine("     (e.g. the amount \"1500.00\")")
            appendLine("  5. Tap 'Add Field from Selection'")
            appendLine("  6. Guava auto-generates the regex for you!")
            appendLine("  7. Repeat for each value (amount, trxid, OTP, etc.)")
            appendLine("  8. Tap 'Test' to verify, then 'Save'")
            appendLine()
            appendLine()
            appendLine("━━━ WEBHOOKS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("Webhooks define where and how to send extracted data.")
            appendLine("You can add multiple webhooks with different configs.")
            appendLine()
            appendLine("  Name & URL")
            appendLine("    Give it a label and your endpoint URL.")
            appendLine()
            appendLine("  HTTP Method")
            appendLine("    POST (default), GET, or PUT.")
            appendLine()
            appendLine("  Content Type")
            appendLine("    JSON (default) or Form URL-encoded.")
            appendLine()
            appendLine("  Authentication")
            appendLine("    • None — no auth headers sent")
            appendLine("    • API Key — sends X-API-Key header (or custom name)")
            appendLine("    • Bearer — sends Authorization: Bearer <token>")
            appendLine("    • Custom Header — any header name + value")
            appendLine()
            appendLine("  HMAC Signature (optional toggle)")
            appendLine("    When enabled, Guava sends:")
            appendLine("      X-Timestamp: <unix_epoch>")
            appendLine("      X-Signature: SHA256(body + timestamp + secret)")
            appendLine("    Your server can verify to prevent spoofing.")
            appendLine("    The signing key = Secret field, or API Key if empty.")
            appendLine()
            appendLine("  Custom Headers")
            appendLine("    Add extra headers, one per line:")
            appendLine("      X-Custom: myvalue")
            appendLine("      X-Source: guava-app")
            appendLine()
            appendLine("  Body Template (optional)")
            appendLine("    Customize the JSON payload with placeholders:")
            appendLine("      {method}    — pattern name (e.g. \"bkash\")")
            appendLine("      {amount}    — extracted amount")
            appendLine("      {trxid}     — transaction ID")
            appendLine("      {sms_body}  — full SMS text")
            appendLine("      {timestamp} — ISO timestamp")
            appendLine("      {sim}       — SIM slot number")
            appendLine("      {sender}    — SMS sender address")
            appendLine("      {yourfield} — any custom extract field name")
            appendLine()
            appendLine("    Leave empty for the default format:")
            appendLine("      {\"payments\":[{\"method\":\"bkash\",")
            appendLine("        \"amount\":1500,\"trxid\":\"ABC123\"}],")
            appendLine("       \"timestamp\":\"2026-02-21T12:00:00\"}")
            appendLine()
            appendLine("  Pattern Types")
            appendLine("    Select which types this webhook handles.")
            appendLine("    None selected = receives ALL types.")
            appendLine()
            appendLine("  SIM Filter")
            appendLine("    Restrict to SIM 1, SIM 2, or Any.")
            appendLine()
            appendLine("  Request Preview")
            appendLine("    See a live preview of the exact HTTP request")
            appendLine("    (method, headers, body) at the bottom of the dialog.")
            appendLine()
            appendLine()
            appendLine("━━━ DEFAULT PAYLOAD FORMAT ━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("If no body template is set, Guava sends:")
            appendLine()
            appendLine("  POST https://your-url.com/hook")
            appendLine("  Content-Type: application/json")
            appendLine("  X-API-Key: your-key")
            appendLine()
            appendLine("  {")
            appendLine("    \"payments\": [")
            appendLine("      {")
            appendLine("        \"method\": \"bkash\",")
            appendLine("        \"amount\": 1500.0,")
            appendLine("        \"trxid\": \"ABC1234567\"")
            appendLine("      }")
            appendLine("    ],")
            appendLine("    \"timestamp\": \"2026-02-21T12:00:00\"")
            appendLine("  }")
            appendLine()
            appendLine()
            appendLine("━━━ DASHBOARD ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("  • Start/Stop the monitoring service")
            appendLine("  • See total received & pending payments")
            appendLine("  • View recent payment history")
            appendLine("  • Service runs as a foreground notification")
            appendLine("    so Android won't kill it in the background")
            appendLine()
            appendLine()
            appendLine("━━━ TROUBLESHOOTING TIPS ━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("  Service keeps stopping?")
            appendLine("    → Disable battery optimization for Guava")
            appendLine("    → Enable auto-start in phone settings")
            appendLine("    → On Xiaomi/OPPO/Vivo: lock app in recents")
            appendLine()
            appendLine("  Webhook returns 401?")
            appendLine("    → Check API key matches your server")
            appendLine("    → If using signature, ensure secret matches")
            appendLine("    → Check server clock isn't off by >5 minutes")
            appendLine()
            appendLine("  Pattern not matching?")
            appendLine("    → Use the Test button with a real SMS")
            appendLine("    → Check sender filter (case-insensitive)")
            appendLine("    → Verify regex has a capture group: (value)")
            appendLine("    → Try Quick Add Pattern for auto-regex")
            appendLine()
            appendLine("  Enable Debug Logging toggle in Settings to")
            appendLine("  write detailed logs to:")
            appendLine("  /storage/emulated/0/Documents/SmsMonitor/debug.log")
        }

        val tv = TextView(ctx).apply {
            text = docsText
            setPadding(48, 32, 48, 32)
            textSize = 13f
            setTextColor(resources.getColor(R.color.md_on_surface, null))
            movementMethod = ScrollingMovementMethod.getInstance()
        }

        AlertDialog.Builder(ctx)
            .setTitle("Docs & Tutorial")
            .setView(tv)
            .setPositiveButton("Got it", null)
            .show()
    }
}