package com.smsmonitor.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * OEM-specific helper to navigate users to the autostart/background
 * permission screens that many Chinese-brand phones require.
 * 
 * On phones like Transsion (Tecno, Infinix, itel), Xiaomi, OPPO, Vivo, 
 * Huawei, etc., Android's standard battery optimization isn't enough.
 * These OEMs have their own app killers that must be disabled manually.
 */
object OemHelper {

    private const val TAG = "OemHelper"

    data class OemInfo(
        val brand: String,
        val title: String,
        val instructions: String,
        val intents: List<Intent>
    )

    /**
     * Returns OEM-specific info for the current phone, or null if generic Android.
     */
    fun getOemInfo(): OemInfo? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        
        Log.w(TAG, "Detected: manufacturer=$manufacturer, brand=$brand, model=${Build.MODEL}")

        return when {
            // Transsion group: Tecno, Infinix, itel
            manufacturer.contains("transsion") || brand.contains("tecno") || 
            brand.contains("infinix") || brand.contains("itel") ||
            manufacturer.contains("tecno") || manufacturer.contains("infinix") -> {
                OemInfo(
                    brand = "Transsion (${Build.BRAND})",
                    title = "Enable Auto-Start & Background Running",
                    instructions = """
                        Your ${Build.BRAND} phone aggressively kills background apps.
                        
                        You MUST do ALL of these steps:
                        
                        1. Open "Phone Master" (or "Security" app)
                           → App Management → Auto-start Manager
                           → Enable auto-start for "SMS Monitor"
                        
                        2. Phone Master → Battery Manager (or Power Saving)
                           → App Freeze → Make sure "SMS Monitor" is NOT in freeze list
                        
                        3. Phone Master → App Management → Background Running
                           → Allow "SMS Monitor" to run in background
                        
                        4. Settings → Battery → Battery Optimization
                           → Find "SMS Monitor" → Select "Don't Optimize"
                        
                        5. Lock in Recents: Open SMS Monitor, then go to
                           Recent Apps → Swipe DOWN on SMS Monitor card
                           (or tap the lock icon) to LOCK it
                        
                        6. Settings → Apps → SMS Monitor → Battery
                           → Allow Background Activity → ON
                           → Remove Restrictions → ON
                    """.trimIndent(),
                    intents = buildTranssionIntents()
                )
            }

            // Xiaomi / Redmi / POCO
            manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                OemInfo(
                    brand = "Xiaomi",
                    title = "Enable Auto-Start",
                    instructions = """
                        1. Go to Settings → Apps → Manage Apps
                           → Find "SMS Monitor" → Auto-start → Enable
                        
                        2. Settings → Battery → App Battery Saver
                           → SMS Monitor → No Restrictions
                        
                        3. Lock in Recents: Swipe down on the app card
                    """.trimIndent(),
                    intents = listOf(
                        Intent().setComponent(ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )),
                        Intent().setComponent(ComponentName(
                            "com.miui.powerkeeper",
                            "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                        ))
                    )
                )
            }

            // OPPO / Realme / OnePlus (ColorOS)
            manufacturer.contains("oppo") || brand.contains("realme") || 
            manufacturer.contains("realme") || brand.contains("oneplus") -> {
                OemInfo(
                    brand = "OPPO/Realme",
                    title = "Allow Auto-Start & Background",
                    instructions = """
                        1. Settings → App Management → App List
                           → SMS Monitor → Auto-start → Enable
                        
                        2. Settings → Battery → Energy Saving
                           → SMS Monitor → Allow Background Running
                        
                        3. Lock in Recents: Swipe down on the app card
                    """.trimIndent(),
                    intents = listOf(
                        Intent().setComponent(ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.startupapp.StartupAppListActivity"
                        )),
                        Intent().setComponent(ComponentName(
                            "com.coloros.oppoguardelf",
                            "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                        ))
                    )
                )
            }

            // Vivo / iQOO
            manufacturer.contains("vivo") || brand.contains("iqoo") -> {
                OemInfo(
                    brand = "Vivo",
                    title = "Allow Background & Auto-Start",
                    instructions = """
                        1. Settings → Battery → Background Power Consumption
                           → SMS Monitor → Don't Restrict
                        
                        2. i Manager → App Manager → Auto-start Manager
                           → Enable SMS Monitor
                    """.trimIndent(),
                    intents = listOf(
                        Intent().setComponent(ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        ))
                    )
                )
            }

            // Huawei / Honor
            manufacturer.contains("huawei") || brand.contains("honor") -> {
                OemInfo(
                    brand = "Huawei",
                    title = "Enable Auto-Launch",
                    instructions = """
                        1. Settings → Battery → App Launch
                           → SMS Monitor → Toggle OFF automatic management
                           → Enable all three toggles (Auto-launch, Secondary launch, Run in background)
                        
                        2. Lock in Recents: Swipe down on the app card
                    """.trimIndent(),
                    intents = listOf(
                        Intent().setComponent(ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        ))
                    )
                )
            }

            // Samsung
            manufacturer.contains("samsung") -> {
                OemInfo(
                    brand = "Samsung",
                    title = "Disable Battery Optimization",
                    instructions = """
                        1. Settings → Battery and Device Care → Battery
                           → Background Usage Limits → Sleeping/Deep Sleeping Apps
                           → Remove "SMS Monitor" from these lists
                        
                        2. Settings → Apps → SMS Monitor → Battery
                           → Unrestricted
                    """.trimIndent(),
                    intents = listOf(
                        Intent().setComponent(ComponentName(
                            "com.samsung.android.lool",
                            "com.samsung.android.sm.battery.ui.BatteryActivity"
                        ))
                    )
                )
            }

            else -> null
        }
    }

    private fun buildTranssionIntents(): List<Intent> {
        return listOf(
            // Tecno Phone Master - Auto Start
            Intent().setComponent(ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity"
            )),
            // Tecno Phone Master
            Intent().setComponent(ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.MainSettingActivity"
            )),
            // Transsion Security App
            Intent("com.transsion.phonemaster.action.AUTO_START"),
            // itel Smart Power
            Intent().setComponent(ComponentName(
                "com.itel.smartpower",
                "com.itel.smartpower.activity.SmartPowerActivity"
            )),
            // Infinix XOS 
            Intent().setComponent(ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.backgroundcheck.BackgroundCheckActivity"
            )),
            // Generic security center
            Intent().setComponent(ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.appmanager.activity.AppManagerActivity"
            )),
            // Fallback: App info page
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:com.smsmonitor.app")
            }
        )
    }

    /**
     * Try to open OEM autostart/background settings.
     * Returns true if any intent resolved successfully.
     */
    fun tryOpenAutoStartSettings(context: Context): Boolean {
        val info = getOemInfo() ?: return false

        for (intent in info.intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Log.w(TAG, "Opened OEM settings: ${intent.component ?: intent.action}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Intent failed: ${e.message}")
            }
        }

        // Last resort: open app's own settings page
        try {
            val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            return true
        } catch (_: Exception) {}

        return false
    }

    /**
     * Check if this is a phone brand known to aggressively kill background apps.
     */
    fun isAggressiveOem(): Boolean {
        return getOemInfo() != null
    }
}
