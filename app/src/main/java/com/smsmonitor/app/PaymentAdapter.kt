package com.smsmonitor.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PaymentAdapter(
    private val onClick: (PaymentRecord) -> Unit
) : RecyclerView.Adapter<PaymentAdapter.ViewHolder>() {

    private var items: List<PaymentRecord> = emptyList()

    fun submitList(newItems: List<PaymentRecord>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(old: Int, new: Int) = items[old].id == newItems[new].id
            override fun areContentsTheSame(old: Int, new: Int) = items[old] == newItems[new]
        })
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_payment, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == items.size - 1)
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivMethodIcon: ShapeableImageView = view.findViewById(R.id.ivMethodIcon)
        private val tvMethodIcon: TextView = view.findViewById(R.id.tvMethodIcon)
        private val tvMethod: TextView = view.findViewById(R.id.tvMethod)
        private val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        private val tvTrxId: TextView = view.findViewById(R.id.tvTrxId)
        private val tvTime: TextView = view.findViewById(R.id.tvTime)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatusBadge)
        private val divider: View = view.findViewById(R.id.paymentDivider)

        fun bind(record: PaymentRecord, isLast: Boolean) {
            // Set method icon based on method name
            val methodLower = record.method.lowercase()
            when {
                methodLower.contains("bkash") -> {
                    ivMethodIcon.setImageResource(R.drawable.ic_bkash)
                    ivMethodIcon.visibility = View.VISIBLE
                    tvMethodIcon.visibility = View.GONE
                }
                methodLower.contains("nagad") -> {
                    ivMethodIcon.setImageResource(R.drawable.ic_nagad)
                    ivMethodIcon.visibility = View.VISIBLE
                    tvMethodIcon.visibility = View.GONE
                }
                else -> {
                    // Fallback: show letter on default circle
                    ivMethodIcon.setImageResource(R.drawable.ic_method_default)
                    ivMethodIcon.visibility = View.VISIBLE
                    tvMethodIcon.text = record.method.first().uppercase()
                    tvMethodIcon.visibility = View.VISIBLE
                }
            }

            tvMethod.text = record.method.replaceFirstChar { it.uppercase() }

            val amountStr = if (record.amount == record.amount.toLong().toDouble()) {
                "৳${record.amount.toLong()}"
            } else {
                "৳${"%.2f".format(record.amount)}"
            }
            tvAmount.text = amountStr
            tvTrxId.text = record.trxid
            tvTime.text = formatTime(record.detectedAt)

            tvStatus.text = record.status.uppercase()
            val ctx = itemView.context
            when (record.status) {
                "sent" -> tvStatus.setTextColor(ctx.getColor(R.color.status_success))
                "pending" -> tvStatus.setTextColor(ctx.getColor(R.color.status_warning))
                "idle" -> tvStatus.setTextColor(ctx.getColor(R.color.status_idle))
                else -> tvStatus.setTextColor(ctx.getColor(R.color.md_on_surface_variant))
            }

            divider.visibility = if (isLast) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onClick(record) }
        }

        private fun formatTime(timestamp: Long): String {
            if (timestamp == 0L) return ""
            return try {
                SimpleDateFormat("hh:mm a · MMM dd", Locale.getDefault()).format(Date(timestamp))
            } catch (_: Exception) { "" }
        }
    }
}
