package com.smsmonitor.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

class WebhookAdapter(
    private val onToggle: (WebhookConfig, Boolean) -> Unit,
    private val onEdit: (WebhookConfig) -> Unit,
    private val onDelete: (WebhookConfig) -> Unit
) : RecyclerView.Adapter<WebhookAdapter.ViewHolder>() {

    private var items: List<WebhookConfig> = emptyList()
    private var patternTypes: List<PatternType> = emptyList()

    fun submitList(newItems: List<WebhookConfig>, types: List<PatternType> = emptyList()) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(old: Int, new: Int) = items[old].id == newItems[new].id
            override fun areContentsTheSame(old: Int, new: Int) = items[old] == newItems[new]
        })
        items = newItems
        patternTypes = types
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_webhook, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvWebhookName)
        private val tvUrl: TextView = view.findViewById(R.id.tvWebhookUrl)
        private val tvFilters: TextView = view.findViewById(R.id.tvWebhookFilters)
        private val switchEnabled: MaterialSwitch = view.findViewById(R.id.switchWebhookEnabled)

        fun bind(config: WebhookConfig) {
            tvName.text = config.name.ifEmpty { "Unnamed" }
            tvUrl.text = "${config.httpMethod} ${config.url}"

            val filters = mutableListOf<String>()

            // Show content type / auth info
            val format = when {
                config.contentType.contains("form") -> "Form"
                else -> "JSON"
            }
            val auth = when (config.authType) {
                "none" -> "No Auth"
                "bearer" -> "Bearer"
                "custom_header" -> config.authHeaderName
                else -> "API Key"
            }
            filters.add("$format · $auth")

            when (config.simFilter) {
                1 -> filters.add("SIM 1")
                2 -> filters.add("SIM 2")
            }
            if (config.patternTypeIds.isNotEmpty()) {
                val typeNames = config.patternTypeIds.mapNotNull { id ->
                    patternTypes.find { it.id == id }?.name
                }
                if (typeNames.isNotEmpty()) filters.add(typeNames.joinToString(", "))
            } else if (config.methodFilter.isNotEmpty()) {
                filters.addAll(config.methodFilter.map { it.replaceFirstChar { c -> c.uppercase() } })
            }
            tvFilters.text = filters.joinToString(" · ")

            switchEnabled.setOnCheckedChangeListener(null)
            switchEnabled.isChecked = config.enabled
            switchEnabled.setOnCheckedChangeListener { _, checked ->
                onToggle(config, checked)
            }

            itemView.setOnClickListener { onEdit(config) }
            itemView.setOnLongClickListener {
                onDelete(config)
                true
            }
        }
    }
}
