package com.smsmonitor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class DashboardFragment : Fragment() {

    private lateinit var paymentDb: PaymentDatabase
    private lateinit var adapter: PaymentAdapter

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvStatSent: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatIdle: TextView
    private lateinit var tvActiveConfig: TextView
    private lateinit var recyclerRecent: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSectionHeader: TextView
    private lateinit var tvClearFilter: TextView

    private lateinit var cardStatSent: MaterialCardView
    private lateinit var cardStatPending: MaterialCardView
    private lateinit var cardStatTotal: MaterialCardView
    private lateinit var cardStatIdle: MaterialCardView

    // Current filter: null = show all, "sent"/"pending"/"idle" = filter
    private var activeFilter: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        paymentDb = PaymentDatabase.getInstance(ctx)

        tvServiceStatus = view.findViewById(R.id.tvServiceStatus)
        tvStatSent = view.findViewById(R.id.tvStatSent)
        tvStatPending = view.findViewById(R.id.tvStatPending)
        tvStatTotal = view.findViewById(R.id.tvStatTotal)
        tvStatIdle = view.findViewById(R.id.tvStatIdle)
        tvActiveConfig = view.findViewById(R.id.tvActiveConfig)
        recyclerRecent = view.findViewById(R.id.recyclerRecent)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvSectionHeader = view.findViewById(R.id.tvSectionHeader)
        tvClearFilter = view.findViewById(R.id.tvClearFilter)

        cardStatSent = view.findViewById(R.id.cardStatSent)
        cardStatPending = view.findViewById(R.id.cardStatPending)
        cardStatTotal = view.findViewById(R.id.cardStatTotal)
        cardStatIdle = view.findViewById(R.id.cardStatIdle)

        adapter = PaymentAdapter { }
        recyclerRecent.layoutManager = LinearLayoutManager(ctx)
        recyclerRecent.isNestedScrollingEnabled = false
        recyclerRecent.adapter = adapter

        // Stat card click listeners — toggle filter
        cardStatSent.setOnClickListener { toggleFilter("sent") }
        cardStatPending.setOnClickListener { toggleFilter("pending") }
        cardStatIdle.setOnClickListener { toggleFilter("idle") }
        cardStatTotal.setOnClickListener { toggleFilter(null) }
        tvClearFilter.setOnClickListener { toggleFilter(null) }

        // Live update callback
        SmsReceiver.onPaymentDetected = {
            activity?.runOnUiThread { refreshData() }
        }

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun toggleFilter(filter: String?) {
        activeFilter = if (activeFilter == filter) null else filter
        refreshData()
    }

    fun refreshData() {
        if (!isAdded) return
        val ctx = context ?: return

        val payments = paymentDb.getAllPayments()
        val sentCount = payments.count { it.status == "sent" }
        val pendingCount = payments.count { it.status == "pending" }
        val idleCount = payments.count { it.status == "idle" }

        tvStatSent.text = sentCount.toString()
        tvStatPending.text = pendingCount.toString()
        tvStatTotal.text = payments.size.toString()
        tvStatIdle.text = idleCount.toString()

        tvServiceStatus.text = "Service Active"

        val webhookCount = WebhookStore.getInstance(ctx).getEnabled().size
        val patternCount = PatternStore.getInstance(ctx).getEnabled().size
        tvActiveConfig.text = "$webhookCount webhooks · $patternCount patterns"

        // Apply filter
        val filtered = if (activeFilter != null) {
            payments.filter { it.status == activeFilter }
        } else {
            payments
        }
        val recent = filtered.take(50)
        adapter.submitList(recent)

        // Update section header and clear button
        if (activeFilter != null) {
            tvSectionHeader.text = "${activeFilter!!.replaceFirstChar { it.uppercase() }} Transactions"
            tvClearFilter.visibility = View.VISIBLE
        } else {
            tvSectionHeader.text = "Recent Transactions"
            tvClearFilter.visibility = View.GONE
        }

        // Highlight active stat card
        val selectedStroke = ctx.getColor(R.color.md_primary)
        val defaultStroke = 0
        cardStatSent.strokeColor = if (activeFilter == "sent") selectedStroke else defaultStroke
        cardStatSent.strokeWidth = if (activeFilter == "sent") 2 else 0
        cardStatPending.strokeColor = if (activeFilter == "pending") selectedStroke else defaultStroke
        cardStatPending.strokeWidth = if (activeFilter == "pending") 2 else 0
        cardStatIdle.strokeColor = if (activeFilter == "idle") selectedStroke else defaultStroke
        cardStatIdle.strokeWidth = if (activeFilter == "idle") 2 else 0
        cardStatTotal.strokeColor = if (activeFilter == null && false) selectedStroke else defaultStroke
        cardStatTotal.strokeWidth = 0

        if (recent.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = if (activeFilter != null) "No ${activeFilter} transactions" else "No transactions yet"
            recyclerRecent.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerRecent.visibility = View.VISIBLE
        }
    }
}
