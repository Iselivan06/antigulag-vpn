package com.antigulag.vpn.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.antigulag.vpn.ConfigBuilder
import com.antigulag.vpn.CrashLogger
import com.antigulag.vpn.ProfileManager
import com.antigulag.vpn.service.XrayVpnService
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val appPrefs = context.getSharedPreferences("app_routing_prefs", Context.MODE_PRIVATE)
        val isAutoStartEnabled = appPrefs.getBoolean("auto_start_on_boot", false)

        if (!isAutoStartEnabled) return

        // Проверяем, дано ли системное разрешение на VPN (если нет, фоновый старт невозможен)
        if (VpnService.prepare(context) != null) return

        val profileManager = ProfileManager(context)
        val activeProfile = profileManager.getSelectedProfile() ?: return

        val routingPrefs = context.getSharedPreferences("routing_rules_prefs", Context.MODE_PRIVATE)
        val useGeo = routingPrefs.getBoolean("enable_geo_routing", false) && profileManager.hasGeoAssets()

        val selectedAppsSet = appPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        val isAppRoutingEnabled = appPrefs.getBoolean("app_routing_enabled", false)
        val appMode = if (isAppRoutingEnabled && selectedAppsSet.isNotEmpty()) {
            appPrefs.getInt("app_mode", 0)
        } else {
            0
        }
        val selectedApps = ArrayList(selectedAppsSet)

        // Удерживаем процесс живым до завершения запуска сервиса
        val pendingResult = goAsync()

        thread {
            try {
                val configJson = ConfigBuilder.buildConfig(context, activeProfile.url, useGeo)

                val serviceIntent = Intent(context, XrayVpnService::class.java).apply {
                    action = "START"
                    putExtra("CONFIG_JSON", configJson)
                    putExtra("APP_MODE", appMode)
                    putStringArrayListExtra("SELECTED_APPS", selectedApps)
                }

                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                CrashLogger.log("BootReceiver: " + e.stackTraceToString())
            } finally {
                pendingResult.finish()
            }
        }
    }
}