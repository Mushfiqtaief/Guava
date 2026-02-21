package com.smsmonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var dashboardFragment: DashboardFragment
    private lateinit var webhooksFragment: WebhooksFragment
    private lateinit var settingsFragment: SettingsFragment
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DebugLog.init(this)
        checkPermissions()

        // Start service
        getSharedPreferences("sms_monitor", MODE_PRIVATE)
            .edit().putBoolean("service_enabled", true).apply()
        startForegroundService(Intent(this, SmsMonitorService::class.java))
        SmsWorker.schedule(this)

        // Initialize fragments
        dashboardFragment = DashboardFragment()
        webhooksFragment = WebhooksFragment()
        settingsFragment = SettingsFragment()

        // Add all fragments, show dashboard
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            .add(R.id.fragmentContainer, webhooksFragment, "webhooks").hide(webhooksFragment)
            .add(R.id.fragmentContainer, dashboardFragment, "dashboard")
            .commit()
        activeFragment = dashboardFragment

        // Bottom navigation
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> switchFragment(dashboardFragment)
                R.id.nav_webhooks -> switchFragment(webhooksFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
            }
            true
        }
    }

    private fun switchFragment(target: Fragment) {
        if (activeFragment === target) return
        supportFragmentManager.beginTransaction()
            .hide(activeFragment!!)
            .show(target)
            .commit()
        activeFragment = target
    }

    override fun onResume() {
        super.onResume()
        if (activeFragment is DashboardFragment) {
            (activeFragment as DashboardFragment).refreshData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SmsReceiver.onPaymentDetected = null
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.RECEIVE_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.READ_SMS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}
