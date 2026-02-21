package com.smsmonitor.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

class WebhooksFragment : Fragment() {

    private lateinit var webhookStore: WebhookStore
    private lateinit var patternTypeStore: PatternTypeStore
    private lateinit var adapter: WebhookAdapter
    private lateinit var recyclerWebhooks: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_webhooks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        webhookStore = WebhookStore.getInstance(ctx)
        patternTypeStore = PatternTypeStore.getInstance(ctx)

        recyclerWebhooks = view.findViewById(R.id.recyclerWebhooks)
        tvEmpty = view.findViewById(R.id.tvEmptyWebhooks)
        val fab = view.findViewById<FloatingActionButton>(R.id.fabAddWebhook)

        adapter = WebhookAdapter(
            onToggle = { webhook, enabled ->
                webhookStore.update(webhook.copy(enabled = enabled))
                refreshList()
            },
            onEdit = { webhook -> showEditDialog(webhook) },
            onDelete = { webhook ->
                AlertDialog.Builder(ctx)
                    .setTitle("Delete Webhook")
                    .setMessage("Delete \"${webhook.name}\"?")
                    .setPositiveButton("Delete") { _, _ ->
                        webhookStore.delete(webhook.id)
                        refreshList()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        recyclerWebhooks.layoutManager = LinearLayoutManager(ctx)
        recyclerWebhooks.adapter = adapter

        fab.setOnClickListener { showEditDialog(null) }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val webhooks = webhookStore.getAll()
        adapter.submitList(webhooks, patternTypeStore.getAll())
        tvEmpty.visibility = if (webhooks.isEmpty()) View.VISIBLE else View.GONE
        recyclerWebhooks.visibility = if (webhooks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(webhook: WebhookConfig?) {
        val ctx = context ?: return
        val isNew = webhook == null

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_webhook_edit, null)

        // Basic fields
        val etName = view.findViewById<TextInputEditText>(R.id.etWebhookName)
        val etUrl = view.findViewById<TextInputEditText>(R.id.etWebhookUrl)
        val etApiKey = view.findViewById<TextInputEditText>(R.id.etWebhookApiKey)
        val etSecret = view.findViewById<TextInputEditText>(R.id.etWebhookSecret)

        // HTTP Method chips
        val chipPost = view.findViewById<Chip>(R.id.chipPost)
        val chipGet = view.findViewById<Chip>(R.id.chipGet)
        val chipPut = view.findViewById<Chip>(R.id.chipPut)

        // Content Type chips
        val chipJson = view.findViewById<Chip>(R.id.chipJson)
        val chipForm = view.findViewById<Chip>(R.id.chipForm)

        // Body template
        val etBodyTemplate = view.findViewById<TextInputEditText>(R.id.etBodyTemplate)

        // Auth chips and fields
        val chipGroupAuth = view.findViewById<ChipGroup>(R.id.chipGroupAuth)
        val chipAuthNone = view.findViewById<Chip>(R.id.chipAuthNone)
        val chipAuthApiKey = view.findViewById<Chip>(R.id.chipAuthApiKey)
        val chipAuthBearer = view.findViewById<Chip>(R.id.chipAuthBearer)
        val chipAuthCustom = view.findViewById<Chip>(R.id.chipAuthCustom)
        val layoutAuthFields = view.findViewById<LinearLayout>(R.id.layoutAuthFields)
        val tilAuthHeaderName = view.findViewById<TextInputLayout>(R.id.tilAuthHeaderName)
        val etAuthHeaderName = view.findViewById<TextInputEditText>(R.id.etAuthHeaderName)

        // Signature switch
        val switchSignature = view.findViewById<MaterialSwitch>(R.id.switchSignature)

        // Custom headers
        val etCustomHeaders = view.findViewById<TextInputEditText>(R.id.etCustomHeaders)

        // Pattern Types chips
        val chipGroupPatternTypes = view.findViewById<ChipGroup>(R.id.chipGroupPatternTypes)
        val types = patternTypeStore.getAll()
        val selectedTypeIds = webhook?.patternTypeIds?.toMutableSet() ?: mutableSetOf()
        val typeChipMap = mutableMapOf<String, Chip>()

        for (type in types) {
            val chip = Chip(ctx).apply {
                text = type.name
                isCheckable = true
                isChecked = selectedTypeIds.isEmpty() || type.id in selectedTypeIds
            }
            typeChipMap[type.id] = chip
            chipGroupPatternTypes.addView(chip)
        }

        // Filters
        val rgSim = view.findViewById<RadioGroup>(R.id.rgSimFilter)

        // Request Preview
        val tvRequestPreview = view.findViewById<TextView>(R.id.tvRequestPreview)

        // Auth type visibility logic
        fun updateAuthVisibility(authType: String) {
            when (authType) {
                "none" -> layoutAuthFields.visibility = View.GONE
                "api_key" -> {
                    layoutAuthFields.visibility = View.VISIBLE
                    tilAuthHeaderName.visibility = View.VISIBLE
                    etAuthHeaderName.setText(etAuthHeaderName.text?.toString()?.ifEmpty { "X-API-Key" } ?: "X-API-Key")
                }
                "bearer" -> {
                    layoutAuthFields.visibility = View.VISIBLE
                    tilAuthHeaderName.visibility = View.GONE
                }
                "custom_header" -> {
                    layoutAuthFields.visibility = View.VISIBLE
                    tilAuthHeaderName.visibility = View.VISIBLE
                }
            }
        }

        // Update preview based on current dialog state
        fun updatePreview() {
            val httpMethod = when {
                chipGet.isChecked -> "GET"
                chipPut.isChecked -> "PUT"
                else -> "POST"
            }
            val url = etUrl.text?.toString()?.trim().let { if (it.isNullOrEmpty()) "https://..." else it }
            val contentType = if (chipForm.isChecked) "application/x-www-form-urlencoded" else "application/json"
            val authType = when {
                chipAuthNone.isChecked -> "none"
                chipAuthBearer.isChecked -> "bearer"
                chipAuthCustom.isChecked -> "custom_header"
                else -> "api_key"
            }
            val apiKey = etApiKey.text?.toString()?.trim() ?: ""
            val authHeaderName = etAuthHeaderName.text?.toString()?.trim().let { if (it.isNullOrEmpty()) "X-API-Key" else it }

            val sb = StringBuilder()
            sb.appendLine("$httpMethod $url")
            sb.appendLine("Content-Type: $contentType")

            when (authType) {
                "api_key" -> if (apiKey.isNotEmpty()) sb.appendLine("$authHeaderName: ${apiKey.take(8)}...")
                "bearer" -> if (apiKey.isNotEmpty()) sb.appendLine("Authorization: Bearer ${apiKey.take(8)}...")
                "custom_header" -> if (apiKey.isNotEmpty()) sb.appendLine("$authHeaderName: ${apiKey.take(8)}...")
            }

            if (switchSignature.isChecked) {
                sb.appendLine("X-Timestamp: 1740100000")
                sb.appendLine("X-Signature: a1b2c3d4e5f6...")
            }

            val headersText = etCustomHeaders.text?.toString()?.trim() ?: ""
            if (headersText.isNotEmpty()) {
                for (line in headersText.lines()) {
                    if (line.contains(':')) sb.appendLine(line.trim())
                }
            }

            sb.appendLine()

            // Body
            val template = etBodyTemplate.text?.toString()?.trim() ?: ""
            if (template.isNotEmpty()) {
                // Show template with sample values
                val sampleBody = template
                    .replace("{method}", "bkash")
                    .replace("{amount}", "1500.00")
                    .replace("{trxid}", "ABC1234567")
                    .replace("{timestamp}", "2026-02-21T12:00:00")
                    .replace("{sms_body}", "You received Tk 1500.00...")
                    .replace("{sim}", "0")
                    .replace("{sender}", "16200")
                try {
                    val json = JSONObject(sampleBody)
                    sb.append(json.toString(2))
                } catch (_: Exception) {
                    sb.append(sampleBody)
                }
            } else {
                // Default format preview
                val defaultPayload = JSONObject().apply {
                    put("payments", JSONArray().apply {
                        put(JSONObject().apply {
                            put("method", "bkash")
                            put("amount", 1500.00)
                            put("trxid", "ABC1234567")
                        })
                    })
                    put("timestamp", "2026-02-21T12:00:00")
                }
                sb.append(defaultPayload.toString(2))
            }

            tvRequestPreview.text = sb.toString().trimEnd()
        }

        chipGroupAuth.setOnCheckedStateChangeListener { _, checkedIds ->
            val authType = when {
                checkedIds.contains(R.id.chipAuthNone) -> "none"
                checkedIds.contains(R.id.chipAuthBearer) -> "bearer"
                checkedIds.contains(R.id.chipAuthCustom) -> "custom_header"
                else -> "api_key"
            }
            updateAuthVisibility(authType)
            updatePreview()
        }

        // Listen for changes to update preview
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        etUrl.addTextChangedListener(textWatcher)
        etBodyTemplate.addTextChangedListener(textWatcher)
        etApiKey.addTextChangedListener(textWatcher)
        etAuthHeaderName.addTextChangedListener(textWatcher)
        etCustomHeaders.addTextChangedListener(textWatcher)
        switchSignature.setOnCheckedChangeListener { _, _ -> updatePreview() }

        // Chip change listeners for preview
        view.findViewById<ChipGroup>(R.id.chipGroupHttpMethod).setOnCheckedStateChangeListener { _, _ -> updatePreview() }
        view.findViewById<ChipGroup>(R.id.chipGroupContentType).setOnCheckedStateChangeListener { _, _ -> updatePreview() }

        // Pre-fill if editing
        webhook?.let { wh ->
            etName.setText(wh.name)
            etUrl.setText(wh.url)
            etApiKey.setText(wh.apiKey.ifEmpty { wh.authHeaderValue })
            etSecret.setText(wh.secret)

            when (wh.httpMethod) {
                "GET" -> chipGet.isChecked = true
                "PUT" -> chipPut.isChecked = true
                else -> chipPost.isChecked = true
            }

            when { wh.contentType.contains("form") -> chipForm.isChecked = true; else -> chipJson.isChecked = true }

            etBodyTemplate.setText(wh.bodyTemplate)

            when (wh.authType) {
                "none" -> chipAuthNone.isChecked = true
                "bearer" -> chipAuthBearer.isChecked = true
                "custom_header" -> chipAuthCustom.isChecked = true
                else -> chipAuthApiKey.isChecked = true
            }
            etAuthHeaderName.setText(wh.authHeaderName.ifEmpty { "X-API-Key" })

            switchSignature.isChecked = wh.includeSignature

            if (wh.customHeaders.isNotEmpty()) {
                etCustomHeaders.setText(wh.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            }

            when (wh.simFilter) {
                1 -> rgSim.check(R.id.rbSim1)
                2 -> rgSim.check(R.id.rbSim2)
                else -> rgSim.check(R.id.rbSimAny)
            }
        }

        val initialAuth = webhook?.authType ?: "api_key"
        updateAuthVisibility(initialAuth)
        updatePreview()

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (isNew) "Add Webhook" else "Edit Webhook")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)

        if (!isNew) {
            builder.setNeutralButton("Delete") { _, _ ->
                webhookStore.delete(webhook!!.id)
                refreshList()
            }
        }

        val alertDialog = builder.create()
        alertDialog.show()

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text?.toString()?.trim() ?: ""
            val url = etUrl.text?.toString()?.trim() ?: ""

            if (name.isEmpty() || url.isEmpty()) {
                if (name.isEmpty()) etName.error = "Required"
                if (url.isEmpty()) etUrl.error = "Required"
                return@setOnClickListener
            }

            val simFilter = when (rgSim.checkedRadioButtonId) {
                R.id.rbSim1 -> 1
                R.id.rbSim2 -> 2
                else -> 0
            }

            // Collect selected pattern type IDs
            val selectedTypes = mutableListOf<String>()
            var allTypesSelected = true
            for ((typeId, chip) in typeChipMap) {
                if (chip.isChecked) {
                    selectedTypes.add(typeId)
                } else {
                    allTypesSelected = false
                }
            }
            val patternTypeIds = if (allTypesSelected) emptyList() else selectedTypes

            val httpMethod = when {
                chipGet.isChecked -> "GET"
                chipPut.isChecked -> "PUT"
                else -> "POST"
            }

            val contentType = when {
                chipForm.isChecked -> "application/x-www-form-urlencoded"
                else -> "application/json"
            }

            val authType = when {
                chipAuthNone.isChecked -> "none"
                chipAuthBearer.isChecked -> "bearer"
                chipAuthCustom.isChecked -> "custom_header"
                else -> "api_key"
            }

            val authHeaderName = etAuthHeaderName.text?.toString()?.trim() ?: "X-API-Key"
            val apiKeyValue = etApiKey.text?.toString()?.trim() ?: ""

            val headersText = etCustomHeaders.text?.toString()?.trim() ?: ""
            val customHeaders = mutableMapOf<String, String>()
            if (headersText.isNotEmpty()) {
                for (line in headersText.lines()) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx > 0) {
                        val key = line.substring(0, colonIdx).trim()
                        val value = line.substring(colonIdx + 1).trim()
                        if (key.isNotEmpty()) customHeaders[key] = value
                    }
                }
            }

            val config = WebhookConfig(
                id = webhook?.id ?: java.util.UUID.randomUUID().toString(),
                name = name,
                url = url,
                apiKey = apiKeyValue,
                secret = etSecret.text?.toString()?.trim() ?: "",
                enabled = webhook?.enabled ?: true,
                simFilter = simFilter,
                methodFilter = emptyList(),
                patternTypeIds = patternTypeIds,
                httpMethod = httpMethod,
                contentType = contentType,
                bodyTemplate = etBodyTemplate.text?.toString()?.trim() ?: "",
                customHeaders = customHeaders,
                authType = authType,
                authHeaderName = authHeaderName,
                authHeaderValue = apiKeyValue,
                includeSignature = switchSignature.isChecked
            )

            if (isNew) webhookStore.add(config) else webhookStore.update(config)
            refreshList()
            alertDialog.dismiss()
        }
    }
}