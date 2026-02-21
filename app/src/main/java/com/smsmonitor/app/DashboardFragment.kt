package com.smsmonitor.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DashboardFragment : Fragment() {

    private lateinit var paymentDb: PaymentDatabase
    private lateinit var adapter: PaymentAdapter

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvStatSent: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatAmount: TextView
    private lateinit var tvActiveConfig: TextView
    private lateinit var recyclerRecent: RecyclerView
    private lateinit var tvEmpty: TextView

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
        tvStatAmount = view.findViewById(R.id.tvStatAmount)
        tvActiveConfig = view.findViewById(R.id.tvActiveConfig)
        recyclerRecent = view.findViewById(R.id.recyclerRecent)
        tvEmpty = view.findViewById(R.id.tvEmpty)

        adapter = PaymentAdapter { }
        recyclerRecent.layoutManager = LinearLayoutManager(ctx)
        recyclerRecent.isNestedScrollingEnabled = false
        recyclerRecent.adapter = adapter

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

    fun refreshData() {
        if (!isAdded) return
        val ctx = context ?: return

        val payments = paymentDb.getAllPayments()
        val sentCount = payments.count { it.status == "sent" }
        val pendingCount = payments.count { it.status == "pending" }
        val totalAmount = payments.sumOf { it.amount }

        tvStatSent.text = sentCount.toString()
        tvStatPending.text = pendingCount.toString()
        tvStatTotal.text = payments.size.toString()
        tvStatAmount.text = if (totalAmount == totalAmount.toLong().toDouble()) {
            "৳${totalAmount.toLong()}"
        } else {
            "৳${"%.2f".format(totalAmount)}"
        }

        tvServiceStatus.text = "Service Active"

        val webhookCount = WebhookStore.getInstance(ctx).getEnabled().size
        val patternCount = PatternStore.getInstance(ctx).getEnabled().size
        tvActiveConfig.text = "$webhookCount webhooks · $patternCount patterns"

        val recent = payments.take(20)
        adapter.submitList(recent)

        if (recent.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            recyclerRecent.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerRecent.visibility = View.VISIBLE
        }
    }
}
