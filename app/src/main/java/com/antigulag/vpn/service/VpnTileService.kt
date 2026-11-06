package com.antigulag.vpn.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.antigulag.vpn.ConfigBuilder
import com.antigulag.vpn.CrashLogger
import com.antigulag.vpn.MainActivity
import com.antigulag.vpn.ProfileManager
import com.antigulag.vpn.R
import kotlin.concurrent.thread

class VpnTileService : TileService() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        if (XrayVpnService.isRunning) {
            val stopIntent = Intent(this, XrayVpnService::class.java).apply { action = "STOP" }
            ContextCompat.startForegroundService(this, stopIntent)
            updateTileState(false)
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                openMainActivity()
                return
            }

            val profileManager = ProfileManager(this)
            val activeProfile = profileManager.getSelectedProfile()
            if (activeProfile == null) {
                Toast.makeText(this, "Выберите явку в приложении Анти-Гулаг", Toast.LENGTH_SHORT).show()
                openMainActivity()
                return
            }

            val routingPrefs = getSharedPreferences("routing_rules_prefs", Context.MODE_PRIVATE)
            val useGeo = routingPrefs.getBoolean("enable_geo_routing", false) && profileManager.hasGeoAssets()

            val appPrefs = getSharedPreferences("app_routing_prefs", Context.MODE_PRIVATE)
            val isAppRoutingEnabled = appPrefs.getBoolean("app_routing_enabled", false)
            val selectedAppsSet = appPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            val appMode = if (isAppRoutingEnabled && selectedAppsSet.isNotEmpty()) {
                appPrefs.getInt("app_mode", 0)
            } else {
                0
            }

            thread {
                try {
                    val configJson = ConfigBuilder.buildConfig(this, activeProfile.url, useGeo)
                    val serviceIntent = Intent(this, XrayVpnService::class.java).apply {
                        action = "START"
                        putExtra("CONFIG_JSON", configJson)
                        putExtra("APP_MODE", appMode)
                        putStringArrayListExtra("SELECTED_APPS", ArrayList(selectedAppsSet))
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                } catch (e: Exception) {
                    CrashLogger.log("VpnTileService start error: ${e.message}")
                    updateTileState(false)
                }
            }

            updateTileState(true)
        }
    }

    private fun updateTileState(forcedState: Boolean? = null) {
        val tile = qsTile ?: return
        val active = forcedState ?: XrayVpnService.isRunning

        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "На свободе" else "Отключен"
        }
        tile.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}