package com.antigulag.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.antigulag.vpn.CrashLogger
import com.antigulag.vpn.MainActivity
import com.antigulag.vpn.R
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

class XrayVpnService : VpnService() {

    companion object {
        var isRunning: Boolean = false
            private set

        private var activeController: CoreController? = null

        fun measureDelay(url: String = "http://connectivitycheck.gstatic.com/generate_204"): Long {
            return try {
                activeController?.measureDelay(url) ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }

        fun notifyTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, VpnTileService::class.java)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var speedThread: Thread? = null

    private val channelId = "xray_vpn"
    private val notificationId = 1

    private fun getContentPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        when (intent?.action) {
            "START" -> {
                val configJson = intent.getStringExtra("CONFIG_JSON")
                if (configJson.isNullOrEmpty()) {
                    Log.e("XrayLog", "Конфиг пуст, остановка")
                    stopTunnel()
                    return START_NOT_STICKY
                }
                val appMode = intent.getIntExtra("APP_MODE", 0)
                val selectedApps = intent.getStringArrayListExtra("SELECTED_APPS") ?: arrayListOf()

                startTunnel(configJson, appMode, selectedApps)
            }
            "STOP" -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(configJson: String, appMode: Int, selectedApps: ArrayList<String>) {
        if (coreController?.isRunning == true || isRunning) return

        try {
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")

            val callback = object : CoreCallbackHandler {
                override fun onEmitStatus(status: Long, msg: String?): Long {
                    Log.d("XrayLog", "[$status] $msg")
                    return 0
                }
                override fun shutdown(): Long = 0
                override fun startup(): Long = 0
            }

            coreController = Libv2ray.newCoreController(callback)
            activeController = coreController

            val builder = Builder()
                .setSession("Анти-Гулаг")
                .setMtu(1500)
                .addAddress("172.19.0.1", 24)
                .addRoute("0.0.0.0", 0)
                .allowBypass()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val routingPrefs = getSharedPreferences("routing_rules_prefs", Context.MODE_PRIVATE)
            val dnsString = routingPrefs.getString("custom_dns", "77.88.8.8, 8.8.8.8") ?: "77.88.8.8, 8.8.8.8"
            val dnsServers = dnsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            if (dnsServers.isNotEmpty()) {
                dnsServers.forEach { ip ->
                    try { builder.addDnsServer(ip) } catch (_: Exception) {}
                }
            } else {
                builder.addDnsServer("77.88.8.8")
                builder.addDnsServer("8.8.8.8")
            }

            when (appMode) {
                1 -> {
                    selectedApps.forEach { pkg ->
                        if (pkg != packageName) {
                            try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                        }
                    }
                }
                2 -> {
                    builder.addDisallowedApplication(packageName)
                    selectedApps.forEach { pkg ->
                        try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                    }
                }
                else -> {
                    builder.addDisallowedApplication(packageName)
                }
            }

            var pfd: ParcelFileDescriptor? = null
            for (i in 1..3) {
                pfd = builder.establish()
                if (pfd != null) break
                Thread.sleep(200)
            }

            vpnInterface = pfd ?: throw IllegalStateException("Система не готова предоставить TUN-интерфейс")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(null)
            }

            val fd = vpnInterface?.fd ?: throw IllegalStateException("Не удалось получить дескриптор TUN")

            // Взводим флаг ДО запуска рабочего потока, исключая состояние гонки
            isRunning = true

            thread(isDaemon = true) {
                Log.d("XrayLog", "Запуск рабочего цикла Xray (fd: $fd)")

                while (isRunning) {
                    try {
                        coreController?.startLoop(configJson, fd)
                    } catch (e: Exception) {
                        Log.e("XrayLog", "Исключение в startLoop", e)
                    }

                    if (isRunning) {
                        Log.w("XrayLog", "Разрыв сокета при смене сети. Быстрый перезапуск ядра...")
                        try {
                            Thread.sleep(200)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                Log.d("XrayLog", "Рабочий цикл Xray окончательно остановлен")
            }

            notifyTileUpdate(this)
            startSpeedMonitor()

        } catch (e: Exception) {
            Log.e("XrayLog", "Ошибка старта туннеля", e)
            stopTunnel()
        }
    }

    private fun getTrafficStatsMap(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val raw = try { coreController?.queryAllOutboundTrafficStats() } catch (_: Exception) { null } ?: return result

        try {
            if (raw.startsWith("{")) {
                val obj = JSONObject(raw)
                val statArr = obj.optJSONArray("stat")
                if (statArr != null) {
                    for (i in 0 until statArr.length()) {
                        val item = statArr.getJSONObject(i)
                        result[item.optString("name")] = item.optLong("value", 0L)
                    }
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        result[k] = obj.optLong(k, 0L)
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun startSpeedMonitor() {
        speedThread?.interrupt()
        speedThread = thread(isDaemon = true) {
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return@thread }

            var lastRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            var lastTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
            var lastTimestamp = System.currentTimeMillis()

            var lastProxyDown = 0L
            var lastProxyUp = 0L
            var lastDirectDown = 0L
            var lastDirectUp = 0L

            while (isRunning) {
                try {
                    Thread.sleep(1500)
                    if (!isRunning) break

                    val now = System.currentTimeMillis()
                    val dt = ((now - lastTimestamp) / 1000.0).coerceAtLeast(0.5)

                    val statsMap = getTrafficStatsMap()
                    val curPDown = statsMap["outbound>>>proxy>>>traffic>>>downlink"] ?: 0L
                    val curPUp = statsMap["outbound>>>proxy>>>traffic>>>uplink"] ?: 0L
                    val curDDown = statsMap["outbound>>>direct>>>traffic>>>downlink"] ?: 0L
                    val curDUp = statsMap["outbound>>>direct>>>traffic>>>uplink"] ?: 0L

                    val pDown = if (curPDown > lastProxyDown && lastProxyDown > 0) ((curPDown - lastProxyDown) / dt).toLong() else 0L
                    val pUp = if (curPUp > lastProxyUp && lastProxyUp > 0) ((curPUp - lastProxyUp) / dt).toLong() else 0L
                    val dDown = if (curDDown > lastDirectDown && lastDirectDown > 0) ((curDDown - lastDirectDown) / dt).toLong() else 0L
                    val dUp = if (curDUp > lastDirectUp && lastDirectUp > 0) ((curDUp - lastDirectUp) / dt).toLong() else 0L

                    lastProxyDown = curPDown
                    lastProxyUp = curPUp
                    lastDirectDown = curDDown
                    lastDirectUp = curDUp

                    val curRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
                    val curTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val totalRxSpeed = if (curRx > lastRx) ((curRx - lastRx) / dt).toLong() else 0L
                    val totalTxSpeed = if (curTx > lastTx) ((curTx - lastTx) / dt).toLong() else 0L

                    lastRx = curRx
                    lastTx = curTx
                    lastTimestamp = now

                    if (pDown > 0 || pUp > 0 || dDown > 0 || dUp > 0) {
                        val pStr = "Proxy: ↓ ${formatSpeed(pDown)}  ↑ ${formatSpeed(pUp)}"
                        val dStr = "Direct: ↓ ${formatSpeed(dDown)}  ↑ ${formatSpeed(dUp)}"
                        updateNotificationSpeed("$pStr | $dStr", "$pStr\n$dStr")
                    } else {
                        val vpnStr = "Трафик: ↓ ${formatSpeed(totalRxSpeed)}  ↑ ${formatSpeed(totalTxSpeed)}"
                        updateNotificationSpeed(vpnStr, vpnStr)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1000) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB/s", mb)
    }

    private fun updateNotificationSpeed(titleSub: String, bigText: String) {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("На свободе")
            .setContentText(titleSub)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentIntent(getContentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun stopTunnel() {
        // Сразу взводим false, чтобы рабочий поток не ушёл на реконнект
        isRunning = false

        speedThread?.interrupt()
        speedThread = null

        activeController = null

        try {
            if (coreController?.isRunning == true) {
                coreController?.stopLoop()
            }
            coreController = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            Log.e("XrayLog", "Ошибка при остановке туннеля", e)
        }

        notifyTileUpdate(this)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w("XrayLog", "Система отозвала права VPN (onRevoke)")
        stopTunnel()
        super.onRevoke()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("На свободе")
            .setContentText("Запуск туннеля...")
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentIntent(getContentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                notificationId,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}