package com.smsmonitor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.UUID

/**
 * Quick Add Pattern — paste a sample SMS, select text from it,
 * and auto-generate regex extraction fields without writing regex.
 */
class QuickAddPatternActivity : AppCompatActivity() {

    private lateinit var patternStore: PatternStore
    private lateinit var patternTypeStore: PatternTypeStore
    private lateinit var etSampleSms: TextInputEditText
    private lateinit var etQuickName: TextInputEditText
    private lateinit var etQuickSender: TextInputEditText
    private lateinit var chipGroupPatternType: ChipGroup
    private lateinit var fieldsContainer: LinearLayout
    private lateinit var tvNoFields: TextView
    private lateinit var tvQuickTestResult: TextView
    private val typeChipMap = mutableMapOf<String, Chip>()

    private data class FieldRow(
        val view: View,
        var fieldName: String,
        var regex: String
    )

    private val fieldRows = mutableListOf<FieldRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_add_pattern)

        patternStore = PatternStore.getInstance(this)
        patternTypeStore = PatternTypeStore.getInstance(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etSampleSms = findViewById(R.id.etSampleSms)
        etQuickName = findViewById(R.id.etQuickName)
        etQuickSender = findViewById(R.id.etQuickSender)
        chipGroupPatternType = findViewById(R.id.chipGroupQuickPatternType)
        fieldsContainer = findViewById(R.id.fieldsContainer)
        tvNoFields = findViewById(R.id.tvNoFields)
        tvQuickTestResult = findViewById(R.id.tvQuickTestResult)

        // Populate pattern type chips
        val types = patternTypeStore.getAll()
        for (type in types) {
            val chip = Chip(this).apply {
                text = type.name
                isCheckable = true
            }
            typeChipMap[type.id] = chip
            chipGroupPatternType.addView(chip)
        }
        // Select first type by default
        if (types.isNotEmpty()) typeChipMap[types[0].id]?.isChecked = true

        findViewById<MaterialButton>(R.id.btnAddField).setOnClickListener {
            addFieldFromSelection()
        }

        findViewById<MaterialButton>(R.id.btnTestQuick).setOnClickListener {
            testPattern()
        }

        findViewById<MaterialButton>(R.id.btnSavePattern).setOnClickListener {
            savePattern()
        }
    }

    private fun addFieldFromSelection() {
        val smsText = etSampleSms.text?.toString() ?: ""
        if (smsText.isBlank()) {
            Toast.makeText(this, "Paste a sample SMS first", Toast.LENGTH_SHORT).show()
            return
        }

        val start = etSampleSms.selectionStart
        val end = etSampleSms.selectionEnd

        if (start == end || start < 0 || end < 0 || start >= smsText.length) {
            Toast.makeText(this, "Select some text in the SMS first", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedText = smsText.substring(start, end).trim()
        if (selectedText.isEmpty()) {
            Toast.makeText(this, "Selection is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val generatedRegex = RegexHelper.generateRegex(smsText, selectedText)
        val suggestedName = RegexHelper.suggestFieldName(smsText, selectedText)

        showFieldConfirmDialog(suggestedName, generatedRegex, selectedText)
    }

    private fun showFieldConfirmDialog(name: String, regex: String, selectedText: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.item_extract_field, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etFieldName)
        val etRegex = dialogView.findViewById<TextInputEditText>(R.id.etFieldRegex)
        dialogView.findViewById<ImageButton>(R.id.btnDeleteField).visibility = View.GONE
        dialogView.findViewById<CheckBox>(R.id.cbInclude).visibility = View.GONE

        etName.setText(name)
        etRegex.setText(regex)

        AlertDialog.Builder(this)
            .setTitle("Add Field")
            .setMessage("Selected: \"$selectedText\"")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val fieldName = etName.text?.toString()?.trim() ?: name
                val fieldRegex = etRegex.text?.toString()?.trim() ?: regex
                if (fieldName.isNotEmpty() && fieldRegex.isNotEmpty()) {
                    addFieldRowToContainer(fieldName, fieldRegex)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addFieldRowToContainer(name: String, regex: String) {
        val rowView = LayoutInflater.from(this).inflate(R.layout.item_extract_field, fieldsContainer, false)
        val etName = rowView.findViewById<TextInputEditText>(R.id.etFieldName)
        val etRegex = rowView.findViewById<TextInputEditText>(R.id.etFieldRegex)
        val cbInclude = rowView.findViewById<CheckBox>(R.id.cbInclude)
        val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeleteField)

        etName.setText(name)
        etRegex.setText(regex)
        cbInclude.isChecked = true

        val fieldRow = FieldRow(rowView, name, regex)
        fieldRows.add(fieldRow)

        btnDelete.setOnClickListener {
            fieldsContainer.removeView(rowView)
            fieldRows.remove(fieldRow)
            updateNoFieldsVisibility()
        }

        fieldsContainer.addView(rowView)
        updateNoFieldsVisibility()
    }

    private fun updateNoFieldsVisibility() {
        tvNoFields.visibility = if (fieldRows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildCurrentConfig(): SmsPatternConfig? {
        val name = etQuickName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) return null

        val extractFields = fieldRows.map { row ->
            val etN = row.view.findViewById<TextInputEditText>(R.id.etFieldName)
            val etR = row.view.findViewById<TextInputEditText>(R.id.etFieldRegex)
            val cb = row.view.findViewById<CheckBox>(R.id.cbInclude)
            SmsPatternConfig.ExtractField(
                fieldName = etN.text?.toString()?.trim() ?: row.fieldName,
                regex = etR.text?.toString()?.trim() ?: row.regex,
                included = cb.isChecked
            )
        }

        val selectedTypeId = typeChipMap.entries.find { it.value.isChecked }?.key ?: ""

        return SmsPatternConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = true,
            senderPattern = etQuickSender.text?.toString()?.trim() ?: "",
            bodyKeyword = "",
            extractFields = extractFields,
            isBuiltIn = false,
            typeId = selectedTypeId
        )
    }

    private fun testPattern() {
        val smsText = etSampleSms.text?.toString()?.trim() ?: ""
        if (smsText.isBlank()) {
            Toast.makeText(this, "Paste a sample SMS first", Toast.LENGTH_SHORT).show()
            return
        }

        val config = buildCurrentConfig()
        if (config == null) {
            etQuickName.error = "Enter a pattern name"
            return
        }

        val senderTest = etQuickSender.text?.toString()?.trim().let {
            if (it.isNullOrEmpty()) "TestSender" else it
        }

        val result = SmsParser.parseWithConfig(senderTest, smsText, listOf(config))

        tvQuickTestResult.visibility = View.VISIBLE
        if (result != null) {
            val sb = StringBuilder("✓ MATCHED!\n")
            sb.append("Method: ${result.method}\n")
            if (result.amount > 0) sb.append("Amount: ${result.amount}\n")
            if (result.trxid.isNotEmpty()) sb.append("TrxID: ${result.trxid}\n")
            for ((key, value) in result.extras) {
                if (key != "method" && key != "sms_body" && key != "amount" && key != "trxid") {
                    sb.append("$key: $value\n")
                }
            }
            tvQuickTestResult.setTextColor(getColor(R.color.status_success))
            tvQuickTestResult.text = sb.toString().trimEnd()
        } else {
            tvQuickTestResult.setTextColor(getColor(R.color.status_error))
            tvQuickTestResult.text = "✗ No match — check name, sender, and fields"
        }
    }

    private fun savePattern() {
        val config = buildCurrentConfig()
        if (config == null) {
            etQuickName.error = "Enter a pattern name"
            return
        }

        if (fieldRows.isEmpty()) {
            Toast.makeText(this, "Add at least one extract field", Toast.LENGTH_SHORT).show()
            return
        }

        patternStore.add(config)
        Toast.makeText(this, "Pattern \"${config.name}\" saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
}
