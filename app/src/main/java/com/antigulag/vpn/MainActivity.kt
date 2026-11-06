package com.antigulag.vpn

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antigulag.vpn.service.XrayVpnService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import libv2ray.Libv2ray
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var isConnected = false
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var gestureDetector: GestureDetector
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnAddProfile: MaterialButton
    private lateinit var btnUpdateGeo: MaterialButton
    private lateinit var btnRoutingSettings: MaterialButton
    private lateinit var btnCustomDnsAndDomains: MaterialButton
    private lateinit var cbBypassRu: MaterialSwitch
    private lateinit var cbAutoStart: MaterialSwitch
    private lateinit var rvProfiles: RecyclerView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView

    private lateinit var profileManager: ProfileManager
    private lateinit var adapter: ProfileAdapter
    private lateinit var routingPrefs: SharedPreferences
    private lateinit var appPrefs: SharedPreferences
    private lateinit var cbAppRouting: MaterialSwitch
    private lateinit var btnAppRoutingSettings: MaterialButton

    private var pingThread: Thread? = null
    private var lastCheckedClip: String? = null
    private var isMeasuringAll = false
    private var isUpdatingSwitchProgrammatically = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startTunnelService()
        } else {
            Toast.makeText(this, "Доступ отклонен", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        checkVpnPermissionAndStart()
    }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        val rawText = result.contents?.trim()
        if (!rawText.isNullOrEmpty()) {
            handleScannedUrl(rawText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        CrashLogger.init(applicationContext)
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.parseColor("#1A1C20")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        setContentView(R.layout.activity_main)

        profileManager = ProfileManager(this)
        routingPrefs = getSharedPreferences("routing_rules_prefs", MODE_PRIVATE)
        appPrefs = getSharedPreferences("app_routing_prefs", MODE_PRIVATE)

        drawerLayout = findViewById(R.id.drawerLayout)
        drawerLayout.setStatusBarBackgroundColor(Color.parseColor("#1A1C20"))

        topAppBar = findViewById(R.id.topAppBar)
        topAppBar.setNavigationOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        topAppBar.menu.clear()
        val pingAllItem = topAppBar.menu.add(0, 101, 0, "Замер пинга")
        pingAllItem.setIcon(R.drawable.ic_refresh)
        pingAllItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        topAppBar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 101) {
                measureAllProfilesPing()
                true
            } else false
        }

        btnToggle = findViewById(R.id.btnToggleVpn)
        btnAddProfile = findViewById(R.id.btnAddProfile)
        btnUpdateGeo = findViewById(R.id.btnUpdateGeo)
        btnRoutingSettings = findViewById(R.id.btnRoutingSettings)
        btnCustomDnsAndDomains = findViewById(R.id.btnCustomDnsAndDomains)
        cbBypassRu = findViewById(R.id.cbBypassRu)
        cbAutoStart = findViewById(R.id.cbAutoStart)
        rvProfiles = findViewById(R.id.rvProfiles)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        cbAppRouting = findViewById(R.id.cbAppRouting)
        btnAppRoutingSettings = findViewById(R.id.btnAppRoutingSettings)

        updateGeoButtonText()

        isUpdatingSwitchProgrammatically = true
        cbBypassRu.isChecked = routingPrefs.getBoolean("enable_geo_routing", false) && profileManager.hasGeoAssets()
        isUpdatingSwitchProgrammatically = false

        cbBypassRu.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener

            if (isChecked && !profileManager.hasGeoAssets()) {
                isUpdatingSwitchProgrammatically = true
                buttonView.isChecked = false
                isUpdatingSwitchProgrammatically = false

                showGeoDownloadDialog()
                return@setOnCheckedChangeListener
            }

            routingPrefs.edit().putBoolean("enable_geo_routing", isChecked).apply()
            if (isConnected) {
                Toast.makeText(this, "Переподключитесь для применения настроек", Toast.LENGTH_SHORT).show()
            }
        }

        cbAppRouting.isChecked = appPrefs.getBoolean("app_routing_enabled", false)
        btnAppRoutingSettings.isEnabled = true
        cbAppRouting.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("app_routing_enabled", isChecked).apply()
            if (isConnected) {
                Toast.makeText(this, "Переподключите туннель для применения настроек", Toast.LENGTH_SHORT).show()
            }
        }
        btnAppRoutingSettings.setOnClickListener { showAppRoutingDialog() }

        cbAutoStart.isChecked = appPrefs.getBoolean("auto_start_on_boot", false)
        cbAutoStart.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("auto_start_on_boot", isChecked).apply()
        }

        btnCustomDnsAndDomains.setOnClickListener { showCustomDnsAndDomainsDialog() }

        tvStatusSub.setOnClickListener {
            if (isConnected) runAutoPing()
        }

        btnAddProfile.setOnClickListener { showAddProfileDialog() }
        btnAddProfile.setOnLongClickListener {
            val currentSub = profileManager.getSubscriptionUrl()
            if (currentSub.isNullOrEmpty()) {
                showAddProfileDialog()
                return@setOnLongClickListener true
            }

            Toast.makeText(this, "Синхронизация с сервером подписки...", Toast.LENGTH_SHORT).show()
            profileManager.syncSubscription(
                onSuccess = { count ->
                    runOnUiThread {
                        adapter.updateData(profileManager.getProfiles())
                        updateActiveServerInfo()
                        Toast.makeText(this@MainActivity, "Подписка обновлена ($count серверов)", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: $err", Toast.LENGTH_SHORT).show() }
                }
            )
            true
        }

        btnToggle.setOnClickListener {
            if (!isConnected) requestPermissionsAndConnect() else stopTunnelService()
        }

        btnRoutingSettings.setOnClickListener { showRoutingRulesDialog() }
        btnUpdateGeo.setOnClickListener { downloadGeoFiles() }

        setupRecyclerView()
        updateActiveServerInfo()
        syncSubscriptionSilently()
        setupSwipeGesture()
    }

    private fun showGeoDownloadDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Геофайлы не найдены")
            .setMessage("Для работы раздельной маршрутизации необходимо скачать базы правил RunetFreedom.")
            .setPositiveButton("Скачать") { _, _ -> downloadGeoFiles() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.post { checkClipboardForVless() }
        }
    }

    private fun checkClipboardForVless() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (!clipboard.hasPrimaryClip()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return

        val rawText = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
        val text = rawText.trim('"', '\'', '`', ' ')

        if (!text.startsWith("vless://", ignoreCase = true)) return
        if (text == lastCheckedClip) return

        val normalizedNew = "vless://" + text.substring(8)

        val alreadyExists = profileManager.getProfiles().any {
            it.url.equals(normalizedNew, ignoreCase = true) || it.url.equals(text, ignoreCase = true)
        }
        if (alreadyExists) return

        lastCheckedClip = text

        val serverName = try {
            val uri = Uri.parse(normalizedNew)
            val frag = uri.fragment
            if (!frag.isNullOrEmpty()) {
                java.net.URLDecoder.decode(frag, "UTF-8")
            } else {
                uri.host ?: "Новая явка"
            }
        } catch (_: Exception) {
            "Новая явка"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Найдена явка в буфере")
            .setMessage("Ссылка:\n$serverName\n\nДобавить в реестр?")
            .setPositiveButton("Добавить") { _, _ ->
                handleScannedUrl(normalizedNew)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun measureAllProfilesPing() {
        if (isMeasuringAll) {
            Toast.makeText(this, "Замер уже выполняется...", Toast.LENGTH_SHORT).show()
            return
        }
        val profiles = profileManager.getProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Список явок пуст", Toast.LENGTH_SHORT).show()
            return
        }

        isMeasuringAll = true
        adapter.setAllPingsLoading()
        Toast.makeText(this, "Замер пинга серверов...", Toast.LENGTH_SHORT).show()

        val testUrl = "http://connectivitycheck.gstatic.com/generate_204"

        thread {
            try {
                Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            } catch (_: Exception) {}

            val activeProfile = profileManager.getSelectedProfile()

            for (p in profiles) {
                var delay = -1L
                if (isConnected && activeProfile?.id == p.id) {
                    delay = XrayVpnService.measureDelay(testUrl)
                } else {
                    try {
                        val pingConfig = ConfigBuilder.buildPingConfig(p.url)
                        if (pingConfig.isNotEmpty()) {
                            delay = Libv2ray.measureOutboundDelay(pingConfig, testUrl)
                        }
                    } catch (_: Exception) {
                        delay = -1L
                    }
                }

                val finalDelay = delay
                runOnUiThread {
                    adapter.setProfilePing(p.id, finalDelay)
                }
            }

            runOnUiThread {
                isMeasuringAll = false
                Toast.makeText(this@MainActivity, "Замер пинга завершён", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSwipeGesture() {
        val density = resources.displayMetrics.density
        val swipeThreshold = (60 * density).toInt()
        val swipeVelocityThreshold = (80 * density).toInt()

        val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false

                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (diffX > swipeThreshold &&
                    Math.abs(diffX) > Math.abs(diffY) * 1.5 &&
                    velocityX > swipeVelocityThreshold
                ) {
                    if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.openDrawer(GravityCompat.START)
                        return true
                    }
                }
                return false
            }
        }

        gestureDetector = GestureDetector(this, gestureListener)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestureDetector.isInitialized) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapter(
            profileManager.getProfiles(),
            onSelect = { profile ->
                val activeProfile = profileManager.getSelectedProfile()

                if (isConnected && activeProfile?.id == profile.id) {
                    return@ProfileAdapter
                }

                profileManager.selectProfile(profile.id)
                adapter.updateData(profileManager.getProfiles())
                updateActiveServerInfo()

                if (isConnected) {
                    btnToggle.isEnabled = false
                    btnToggle.text = "ПЕРЕКЛЮЧЕНИЕ..."
                    tvStatusSub.text = "Смена шлюза на: ${profile.name}..."

                    stopTunnelService()

                    rvProfiles.postDelayed({
                        startTunnelService()
                    }, 300)
                }
            },
            onDelete = { profile ->
                if (!isConnected) {
                    profileManager.deleteProfile(profile.id)
                    adapter.updateData(profileManager.getProfiles())
                    updateActiveServerInfo()
                } else {
                    Toast.makeText(this, "Нельзя удалить профиль во время работы", Toast.LENGTH_SHORT).show()
                }
            },
            onEdit = { profile ->
                if (!isConnected) {
                    showEditProfileDialog(profile)
                } else {
                    Toast.makeText(this, "Сначала отключите туннель для редактирования", Toast.LENGTH_SHORT).show()
                }
            }
        )
        rvProfiles.layoutManager = LinearLayoutManager(this)
        rvProfiles.adapter = adapter

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                adapter.onItemMove(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        itemTouchHelper.attachToRecyclerView(rvProfiles)
    }

    private fun showEditProfileDialog(profile: ProfileItem) {
        val cleanUrl = if (profile.url.startsWith("vless://", ignoreCase = true)) {
            "vless://" + profile.url.substring(8)
        } else {
            profile.url
        }

        val uri = try {
            Uri.parse(cleanUrl)
        } catch (_: Exception) {
            Toast.makeText(this, "Ошибка структуры URL", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_profile, null)

        val etName = dialogView.findViewById<TextInputEditText>(R.id.etEditName)
        val etHost = dialogView.findViewById<TextInputEditText>(R.id.etEditHost)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.etEditPort)
        val etUuid = dialogView.findViewById<TextInputEditText>(R.id.etEditUuid)
        val etSecurity = dialogView.findViewById<TextInputEditText>(R.id.etEditSecurity)
        val etFp = dialogView.findViewById<TextInputEditText>(R.id.etEditFp)
        val etSni = dialogView.findViewById<TextInputEditText>(R.id.etEditSni)
        val etPbk = dialogView.findViewById<TextInputEditText>(R.id.etEditPbk)
        val etSid = dialogView.findViewById<TextInputEditText>(R.id.etEditSid)
        val etSpx = dialogView.findViewById<TextInputEditText>(R.id.etEditSpx)
        val etType = dialogView.findViewById<TextInputEditText>(R.id.etEditType)
        val flowVal = dialogView.findViewById<TextInputEditText>(R.id.etEditFlow)
        val etPath = dialogView.findViewById<TextInputEditText>(R.id.etEditPath)

        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelEdit)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSaveEdit)
        val scrollView = dialogView.findViewById<androidx.core.widget.NestedScrollView>(R.id.scrollEditProfile)

        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.52).toInt()
        scrollView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxScrollHeight
        )

        val currentName = try {
            val frag = uri.fragment
            if (!frag.isNullOrEmpty()) java.net.URLDecoder.decode(frag, "UTF-8") else profile.name
        } catch (_: Exception) { profile.name }

        etName.setText(currentName)
        etHost.setText(uri.host ?: "")
        etPort.setText(if (uri.port != -1) uri.port.toString() else "443")
        etUuid.setText(uri.userInfo ?: "")
        etSecurity.setText(uri.getQueryParameter("security") ?: "reality")
        etFp.setText(uri.getQueryParameter("fp") ?: "chrome")
        etSni.setText(uri.getQueryParameter("sni") ?: "")
        etPbk.setText(uri.getQueryParameter("pbk") ?: "")
        etSid.setText(uri.getQueryParameter("sid") ?: "")
        etSpx.setText(uri.getQueryParameter("spx") ?: "")
        etType.setText(uri.getQueryParameter("type") ?: "tcp")
        flowVal.setText(uri.getQueryParameter("flow") ?: "")
        etPath.setText(uri.getQueryParameter("path") ?: "")

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(android.view.Gravity.CENTER)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newHost = etHost.text.toString().trim()
            val newPort = etPort.text.toString().trim().toIntOrNull() ?: 443
            val newUuid = etUuid.text.toString().trim()
            val newName = etName.text.toString().trim().ifEmpty { newHost }

            if (newHost.isEmpty() || newUuid.isEmpty()) {
                Toast.makeText(this, "Хост и UUID обязательны для заполнения", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val queryParams = mutableMapOf<String, String>()
            try {
                uri.queryParameterNames.forEach { key ->
                    val value = uri.getQueryParameter(key)
                    if (!value.isNullOrEmpty()) {
                        queryParams[key] = value
                    }
                }
            } catch (_: Exception) {}

            val securitySetting = etSecurity.text.toString().trim()
            if (securitySetting.isNotEmpty()) queryParams["security"] = securitySetting else queryParams.remove("security")

            val fpSetting = etFp.text.toString().trim()
            if (fpSetting.isNotEmpty()) queryParams["fp"] = fpSetting else queryParams.remove("fp")

            val sniSetting = etSni.text.toString().trim()
            if (sniSetting.isNotEmpty()) queryParams["sni"] = sniSetting else queryParams.remove("sni")

            val pbkSetting = etPbk.text.toString().trim()
            if (pbkSetting.isNotEmpty()) queryParams["pbk"] = pbkSetting else queryParams.remove("pbk")

            val sidSetting = etSid.text.toString().trim()
            if (sidSetting.isNotEmpty()) queryParams["sid"] = sidSetting else queryParams.remove("sid")

            val spxSetting = etSpx.text.toString().trim()
            if (spxSetting.isNotEmpty()) queryParams["spx"] = spxSetting else queryParams.remove("spx")

            val typeSetting = etType.text.toString().trim()
            if (typeSetting.isNotEmpty()) queryParams["type"] = typeSetting else queryParams.remove("type")

            val flowSetting = flowVal.text.toString().trim()
            if (flowSetting.isNotEmpty()) queryParams["flow"] = flowSetting else queryParams.remove("flow")

            val pathSetting = etPath.text.toString().trim()
            if (pathSetting.isNotEmpty()) queryParams["path"] = pathSetting else queryParams.remove("path")

            val queryString = queryParams.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }

            val encodedName = java.net.URLEncoder.encode(newName, "UTF-8")
            val assembledUrl = "vless://$newUuid@$newHost:$newPort?$queryString#$encodedName"

            profileManager.updateCustomProfile(profile.id, assembledUrl)
            adapter.updateData(profileManager.getProfiles())
            updateActiveServerInfo()
            Toast.makeText(this, "Параметры явки сохранены", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCustomDnsAndDomainsDialog() {
        val defaultDns = "77.88.8.8, 8.8.8.8"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
        }

        val tvDns = TextView(this).apply {
            text = "DNS-сервера (через запятую):"
            setTextColor(Color.parseColor("#E3E2E6"))
            textSize = 13f
        }
        val etDns = EditText(this).apply {
            hint = defaultDns
            setText(routingPrefs.getString("custom_dns", defaultDns))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val tvProxy = TextView(this).apply {
            text = "Домены через VPN (Proxy, наивысший приоритет):"
            setTextColor(Color.parseColor("#E3E2E6"))
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        val etProxy = EditText(this).apply {
            hint = "domain.com, api.service.org"
            setText(routingPrefs.getString("custom_proxy_domains", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val tvDirect = TextView(this).apply {
            text = "Домены напрямую в обход (Direct, наивысший приоритет):"
            setTextColor(Color.parseColor("#E3E2E6"))
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }
        val etDirect = EditText(this).apply {
            hint = "mysite.ru, work.corp"
            setText(routingPrefs.getString("custom_direct_domains", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        layout.addView(tvDns)
        layout.addView(etDns)
        layout.addView(tvProxy)
        layout.addView(etProxy)
        layout.addView(tvDirect)
        layout.addView(etDirect)

        MaterialAlertDialogBuilder(this)
            .setTitle("DNS и свои правила доменов")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val dnsVal = etDns.text.toString().trim().ifEmpty { defaultDns }
                routingPrefs.edit()
                    .putString("custom_dns", dnsVal)
                    .putString("custom_proxy_domains", etProxy.text.toString().trim())
                    .putString("custom_direct_domains", etDirect.text.toString().trim())
                    .apply()

                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
                if (isConnected) {
                    Toast.makeText(this, "Переподключите туннель для применения", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Сброс") { _, _ ->
                routingPrefs.edit()
                    .putString("custom_dns", defaultDns)
                    .putString("custom_proxy_domains", "")
                    .putString("custom_direct_domains", "")
                    .apply()
                Toast.makeText(this, "Сброшено: Яндекс + Google", Toast.LENGTH_SHORT).show()
                if (isConnected) {
                    Toast.makeText(this, "Переподключите туннель для применения", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        syncVpnUiState()
        updateGeoButtonText()
        checkGeoUpdateReminder()
        syncSubscriptionSilently()
    }

    override fun onDestroy() {
        pingThread?.interrupt()
        super.onDestroy()
    }

    private var lastSubSyncTime = 0L

    private fun syncSubscriptionSilently() {
        val subUrl = profileManager.getSubscriptionUrl()
        if (subUrl.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastSubSyncTime < 10_000L) return
        lastSubSyncTime = now

        profileManager.syncSubscription(
            onSuccess = {
                runOnUiThread {
                    adapter.updateData(profileManager.getProfiles())
                    updateActiveServerInfo()
                }
            },
            onError = {}
        )
    }

    private fun updateGeoButtonText() {
        if (::btnUpdateGeo.isInitialized) {
            if (!profileManager.hasGeoAssets()) {
                btnUpdateGeo.text = "Скачать"
            } else {
                val now = System.currentTimeMillis()
                val lastUpdate = routingPrefs.getLong("last_geo_update_time", 0L)
                val isOverdue = lastUpdate > 0L && (now - lastUpdate >= 30L * 24 * 60 * 60 * 1000L)
                btnUpdateGeo.text = if (isOverdue) "Обновить (!)" else "Обновить"
            }
        }
    }

    private fun downloadGeoFiles() {
        if (isConnected) {
            Toast.makeText(this, "Отключите туннель перед обновлением", Toast.LENGTH_SHORT).show()
            return
        }

        btnUpdateGeo.isEnabled = false

        thread {
            val result = profileManager.downloadGeoAssets { status ->
                runOnUiThread { btnUpdateGeo.text = status }
            }

            runOnUiThread {
                btnUpdateGeo.isEnabled = true
                updateGeoButtonText()
                result.onSuccess { msg ->
                    routingPrefs.edit().putLong("last_geo_update_time", System.currentTimeMillis()).apply()
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Ошибка: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkGeoUpdateReminder() {
        if (!profileManager.hasGeoAssets()) return

        val now = System.currentTimeMillis()
        val lastUpdate = routingPrefs.getLong("last_geo_update_time", 0L)

        if (lastUpdate == 0L) {
            routingPrefs.edit().putLong("last_geo_update_time", now).apply()
            return
        }

        val thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000L
        val oneDayMillis = 24L * 60 * 60 * 1000L
        val lastPrompt = routingPrefs.getLong("last_geo_prompt_time", 0L)

        if (now - lastUpdate >= thirtyDaysMillis && now - lastPrompt >= oneDayMillis) {
            routingPrefs.edit().putLong("last_geo_prompt_time", now).apply()

            MaterialAlertDialogBuilder(this)
                .setTitle("Обновление геобаз")
                .setMessage("Базы правил и маршрутизации (geoip / geosite) не обновлялись больше месяца. Рекомендуется обновить их для актуального обхода блокировок.")
                .setPositiveButton("Обновить") { _, _ ->
                    if (isConnected) {
                        Toast.makeText(this, "Отключите туннель перед обновлением", Toast.LENGTH_SHORT).show()
                    } else {
                        downloadGeoFiles()
                    }
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun syncVpnUiState() {
        val wasConnected = isConnected
        isConnected = XrayVpnService.isRunning
        if (isConnected) {
            btnToggle.isEnabled = true
            btnToggle.text = "СВЕРНУТЬ ГОЙДУ (ОТКЛЮЧИТЬ)"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
            tvStatusTitle.text = "НА СВОБОДЕ (ЗАЩИЩЕНО)"
            tvStatusTitle.setTextColor(Color.parseColor("#4CAF50"))
            if (!wasConnected) runAutoPing()
        } else {
            btnToggle.isEnabled = true
            btnToggle.text = "ОБЪЯВИТЬ ГОЙДУ (ПОДКЛЮЧИТЬ)"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5449"))
            tvStatusTitle.text = "ПОД КОЛПАКОМ"
            tvStatusTitle.setTextColor(Color.parseColor("#FF5449"))
            updateActiveServerInfo()
        }
    }

    private fun showAppRoutingDialog() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_app_routing, null)
        dialog.setContentView(sheetView)

        var hasChanged = false

        val rgAppMode = sheetView.findViewById<RadioGroup>(R.id.rgAppMode)
        val rbAll = sheetView.findViewById<RadioButton>(R.id.rbModeAll)
        val rbProxy = sheetView.findViewById<RadioButton>(R.id.rbModeProxyOnly)
        val rbBypass = sheetView.findViewById<RadioButton>(R.id.rbModeBypass)
        val rvApps = sheetView.findViewById<RecyclerView>(R.id.rvApps)
        val pbLoading = sheetView.findViewById<ProgressBar>(R.id.pbLoadingApps)
        val btnClose = sheetView.findViewById<MaterialButton>(R.id.btnCloseAppRouting)
        val etSearchApps = sheetView.findViewById<EditText>(R.id.etSearchApps)

        val currentMode = appPrefs.getInt("app_mode", 0)
        when (currentMode) {
            1 -> rbProxy.isChecked = true
            2 -> rbBypass.isChecked = true
            else -> rbAll.isChecked = true
        }

        rgAppMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbModeProxyOnly -> 1
                R.id.rbModeBypass -> 2
                else -> 0
            }
            appPrefs.edit().putInt("app_mode", mode).apply()
            hasChanged = true
        }

        rvApps.layoutManager = LinearLayoutManager(this)
        pbLoading.visibility = View.VISIBLE

        thread {
            val pm = packageManager
            val selectedSet = appPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val appItems = installed.filter {
                it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null)
            }.map { appInfo ->
                AppItem(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo),
                    isSelected = selectedSet.contains(appInfo.packageName)
                )
            }.sortedBy { it.name.lowercase() }

            runOnUiThread {
                pbLoading.visibility = View.GONE

                val routingAdapter = AppRoutingAdapter(appItems) { item, isChecked ->
                    val currentSelected = appPrefs.getStringSet("selected_apps", emptySet())?.toMutableSet() ?: mutableSetOf()
                    if (isChecked) currentSelected.add(item.packageName) else currentSelected.remove(item.packageName)
                    appPrefs.edit().putStringSet("selected_apps", currentSelected).apply()
                    hasChanged = true
                }
                rvApps.adapter = routingAdapter

                etSearchApps?.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString()?.trim()?.lowercase().orEmpty()
                        val filteredList = if (query.isEmpty()) {
                            appItems
                        } else {
                            appItems.filter { it.name.lowercase().contains(query) }
                        }
                        routingAdapter.updateData(filteredList)
                    }
                })
            }
        }

        dialog.setOnDismissListener {
            if (isConnected && hasChanged) {
                Toast.makeText(this, "Переподключите туннель для применения настроек", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRoutingRulesDialog() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_routing_rules, null)
        dialog.setContentView(sheetView)

        val rulesList = listOf(
            RoutingRuleItem("rule_ads", "Реклама", "[geosite:category-ads-all] -> block", isRuleActive("rule_ads")),
            RoutingRuleItem("rule_block_quic", "Блокировка QUIC", "udp:443 -> block", isRuleActive("rule_block_quic")),
            RoutingRuleItem("rule_ru_direct", "РФ Domains", "[geosite:ru-available-only-inside] -> direct", isRuleActive("rule_ru_direct")),
            RoutingRuleItem("rule_private_ip", "Локальные IP", "[geoip:private] -> direct", isRuleActive("rule_private_ip")),
            RoutingRuleItem("rule_private_domain", "Локальные домены", "[geosite:private] -> direct", isRuleActive("rule_private_domain"))
        )

        val rvRules = sheetView.findViewById<RecyclerView>(R.id.rvRules)
        val btnClose = sheetView.findViewById<MaterialButton>(R.id.btnCloseRules)

        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = RoutingRuleAdapter(rulesList) { item, isChecked ->
            routingPrefs.edit().putBoolean(item.key, isChecked).apply()
            if (isConnected) {
                Toast.makeText(this@MainActivity, "Переподключите туннель для применения правил", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun isRuleActive(key: String): Boolean = routingPrefs.getBoolean(key, true)

    private fun updateActiveServerInfo() {
        val active = profileManager.getSelectedProfile()
        if (active != null) {
            val host = try { Uri.parse(active.url).host ?: active.name } catch (_: Exception) { active.name }
            tvStatusSub.text = "Шлюз: $host | ${active.name}"
        } else {
            tvStatusSub.text = "Шлюз не выбран"
        }
    }

    private fun showAddProfileDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_profile, null)
        val etUrl = dialogView.findViewById<EditText>(R.id.etProfileUrl)
        val btnScanQr = dialogView.findViewById<MaterialButton>(R.id.btnScanQr)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelAdd)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmAdd)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(android.view.Gravity.CENTER)
        }

        btnScanQr.setOnClickListener {
            dialog.dismiss()
            startQrScan()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val link = etUrl.text.toString().trim()
            when {
                link.startsWith("vless://", ignoreCase = true) -> {
                    val normalizedLink = "vless://" + link.substring(8)
                    profileManager.addProfileFromUrl(normalizedLink)
                    val profiles = profileManager.getProfiles()
                    if (profiles.size == 1) {
                        profileManager.selectProfile(profiles[0].id)
                    }
                    adapter.updateData(profileManager.getProfiles())
                    updateActiveServerInfo()
                    dialog.dismiss()
                }
                link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) -> {
                    profileManager.setSubscriptionUrl(link)
                    dialog.dismiss()
                    Toast.makeText(this, "Подписка сохранена. Загрузка явок...", Toast.LENGTH_SHORT).show()

                    profileManager.syncSubscription(
                        onSuccess = { count ->
                            runOnUiThread {
                                val profiles = profileManager.getProfiles()
                                if (profileManager.getSelectedProfile() == null && profiles.isNotEmpty()) {
                                    profileManager.selectProfile(profiles[0].id)
                                }
                                adapter.updateData(profiles)
                                updateActiveServerInfo()
                                Toast.makeText(this@MainActivity, "Загружено явок: $count", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { err ->
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Ошибка: $err", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                else -> {
                    Toast.makeText(this, "Вставьте ссылку vless:// или адрес подписки (https://...)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun requestPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            checkVpnPermissionAndStart()
        }
    }

    private fun checkVpnPermissionAndStart() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            startTunnelService()
        }
    }

    private fun startTunnelService() {
        val activeProfile = profileManager.getSelectedProfile()
        if (activeProfile == null) {
            Toast.makeText(this, "Добавьте и выберите явку из списка", Toast.LENGTH_SHORT).show()
            return
        }

        btnToggle.isEnabled = false
        btnToggle.text = "МАСКИРОВКА..."

        val useGeo = cbBypassRu.isChecked

        val selectedAppsSet = appPrefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
        val appMode = if (cbAppRouting.isChecked && selectedAppsSet.isNotEmpty()) {
            appPrefs.getInt("app_mode", 0)
        } else {
            0
        }
        val selectedApps = ArrayList(selectedAppsSet)

        thread {
            try {
                val configJson = ConfigBuilder.buildConfig(this@MainActivity, activeProfile.url, useGeo)

                val serviceIntent = Intent(this@MainActivity, XrayVpnService::class.java).apply {
                    action = "START"
                    putExtra("CONFIG_JSON", configJson)
                    putExtra("APP_MODE", appMode)
                    putStringArrayListExtra("SELECTED_APPS", selectedApps)
                }
                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                runOnUiThread {
                    isConnected = true
                    btnToggle.isEnabled = true
                    btnToggle.text = "СВЕРНУТЬ ГОЙДУ (ОТКЛЮЧИТЬ)"
                    btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                    tvStatusTitle.text = "НА СВОБОДЕ (ЗАЩИЩЕНО)"
                    tvStatusTitle.setTextColor(Color.parseColor("#4CAF50"))
                    runAutoPing()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    btnToggle.isEnabled = true
                    btnToggle.text = "ОБЪЯВИТЬ ГОЙДУ (ПОДКЛЮЧИТЬ)"
                    btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5449"))
                    Toast.makeText(this@MainActivity, "Сбой: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runAutoPing() {
        val active = profileManager.getSelectedProfile() ?: return
        val host = try { Uri.parse(active.url).host ?: active.name } catch (_: Exception) { active.name }

        tvStatusSub.text = "Шлюз: $host | Замер пинга..."

        pingThread?.interrupt()
        pingThread = thread {
            try { Thread.sleep(600) } catch (_: InterruptedException) { return@thread }

            var realDelay = XrayVpnService.measureDelay("http://connectivitycheck.gstatic.com/generate_204")

            if (realDelay <= 0) {
                try { Thread.sleep(300) } catch (_: InterruptedException) { return@thread }
                realDelay = XrayVpnService.measureDelay("http://connectivitycheck.gstatic.com/generate_204")
            }

            runOnUiThread {
                if (isConnected) {
                    if (realDelay > 0) {
                        tvStatusSub.text = "Шлюз: $host | $realDelay мс"
                    } else {
                        tvStatusSub.text = "Шлюз: $host | Таймаут (нажмите для повтора)"
                    }
                }
            }
        }
    }

    private fun stopTunnelService() {
        val serviceIntent = Intent(this, XrayVpnService::class.java).apply {
            action = "STOP"
        }
        startService(serviceIntent)

        isConnected = false
        btnToggle.text = "ОБЪЯВИТЬ ГОЙДУ (ПОДКЛЮЧИТЬ)"
        btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5449"))
        tvStatusTitle.text = "ПОД КОЛПАКОМ"
        tvStatusTitle.setTextColor(Color.parseColor("#FF5449"))
    }

    private fun startQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите камеру на QR-код с явкой VLESS")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(CaptureActivityPortrait::class.java)
        }
        qrScannerLauncher.launch(options)
    }

    private fun handleScannedUrl(urlText: String) {
        when {
            urlText.startsWith("vless://", ignoreCase = true) -> {
                val normalizedUrl = "vless://" + urlText.substring(8)
                val newProfile = profileManager.addProfileFromUrl(normalizedUrl)
                adapter.updateData(profileManager.getProfiles())
                updateActiveServerInfo()
                Toast.makeText(this, "Добавлена явка: ${newProfile.name}", Toast.LENGTH_SHORT).show()
            }
            urlText.startsWith("http://", ignoreCase = true) || urlText.startsWith("https://", ignoreCase = true) -> {
                profileManager.setSubscriptionUrl(urlText)
                Toast.makeText(this, "Подписка распознана. Загрузка серверов...", Toast.LENGTH_SHORT).show()
                profileManager.syncSubscription(
                    onSuccess = { count ->
                        runOnUiThread {
                            adapter.updateData(profileManager.getProfiles())
                            updateActiveServerInfo()
                            Toast.makeText(this@MainActivity, "Загружено серверов: $count", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { err ->
                        runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: $err", Toast.LENGTH_SHORT).show() }
                    }
                )
            }
            else -> {
                Toast.makeText(this, "QR-код не содержит VLESS или ссылку на подписку", Toast.LENGTH_LONG).show()
            }
        }
    }
}