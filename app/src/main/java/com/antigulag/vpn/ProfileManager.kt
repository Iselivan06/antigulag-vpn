package com.antigulag.vpn

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import kotlin.concurrent.thread

class ProfileManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vpn_profiles_prefs", Context.MODE_PRIVATE)

    fun getSubscriptionUrl(): String? {
        return prefs.getString("custom_subscription_url", null)
    }

    fun setSubscriptionUrl(url: String) {
        var cleanUrl = url.trim()

        // 1. Превращаем веб-ссылку gist.github.com в raw-ссылку
        if (cleanUrl.contains("gist.github.com") && !cleanUrl.contains("gist.githubusercontent.com")) {
            cleanUrl = cleanUrl.replace("gist.github.com", "gist.githubusercontent.com")
            if (!cleanUrl.contains("/raw")) {
                cleanUrl = if (cleanUrl.endsWith("/")) "${cleanUrl}raw" else "$cleanUrl/raw"
            }
        }

        // 2. ВЫРЕЗАЕМ ХЭШ КОММИТА: /raw/<40-значный sha>/ -> /raw/
        // Теперь ссылка ВСЕГДА указывает на самый последний коммит (ветку HEAD)
        cleanUrl = cleanUrl.replace(Regex("/raw/[a-f0-9]{40}/", RegexOption.IGNORE_CASE), "/raw/")

        prefs.edit().putString("custom_subscription_url", cleanUrl).apply()
    }

    private fun extractProfileName(url: String, fallback: String): String {
        return try {
            val uri = Uri.parse(url)
            val fragment = uri.fragment
            if (!fragment.isNullOrEmpty()) {
                URLDecoder.decode(fragment, "UTF-8")
            } else {
                uri.host ?: fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    fun getProfiles(): List<ProfileItem> {
        val selectedId = prefs.getString("selected_profile_id", null)
        val remoteProfiles = loadProfiles("remote_sub_profiles_json", isPreset = true)
        val customProfiles = loadProfiles("custom_profiles_json", isPreset = false)

        val all = mutableListOf<ProfileItem>()
        remoteProfiles.forEach {
            all.add(it.copy(isSelected = (it.id == selectedId), isPreset = true))
        }
        customProfiles.forEach {
            all.add(it.copy(isSelected = (it.id == selectedId), isPreset = false))
        }

        if (all.none { it.isSelected }) {
            all.firstOrNull()?.let {
                it.isSelected = true
                selectProfile(it.id)
            }
        }

        return all
    }

    fun updateCustomProfile(id: String, newUrl: String): Boolean {
        val custom = loadProfiles("custom_profiles_json", isPreset = false).toMutableList()
        val index = custom.indexOfFirst { it.id == id }
        if (index == -1) return false

        val cleanUrl = newUrl.trim()
        val name = extractProfileName(cleanUrl, custom[index].name)

        custom[index] = custom[index].copy(name = name, url = cleanUrl)
        saveProfiles("custom_profiles_json", custom)
        return true
    }

    fun getSelectedProfile(): ProfileItem? {
        val list = getProfiles()
        return list.firstOrNull { it.isSelected } ?: list.firstOrNull()
    }

    fun selectProfile(id: String) {
        prefs.edit().putString("selected_profile_id", id).apply()
    }

    fun deleteProfile(id: String) {
        val remoteProfiles = loadProfiles("remote_sub_profiles_json", isPreset = true)
        if (remoteProfiles.any { it.id == id }) return

        val custom = loadProfiles("custom_profiles_json", isPreset = false).toMutableList()
        custom.removeAll { it.id == id }
        saveProfiles("custom_profiles_json", custom)

        val currentSelected = prefs.getString("selected_profile_id", null)
        if (currentSelected == id) {
            remoteProfiles.firstOrNull()?.let { selectProfile(it.id) }
        }
    }

    fun addProfileFromUrl(vlessUrl: String): ProfileItem {
        val cleanUrl = vlessUrl.trim()
        val name = extractProfileName(cleanUrl, "Явка")

        val newItem = ProfileItem(
            id = UUID.randomUUID().toString(),
            name = name,
            url = cleanUrl,
            isSelected = false,
            isPreset = false
        )

        val custom = loadProfiles("custom_profiles_json", isPreset = false).toMutableList()
        custom.add(newItem)
        saveProfiles("custom_profiles_json", custom)
        return newItem
    }

    fun syncSubscription(onSuccess: (Int) -> Unit, onError: (String) -> Unit) {
        val rawSubUrl = getSubscriptionUrl()
        if (rawSubUrl.isNullOrBlank()) {
            onError("Ссылка на подписку не задана")
            return
        }

        // Автоматически чистим сохранённый URL от хэша коммита
        val subUrl = rawSubUrl.replace(Regex("/raw/[a-f0-9]{40}/", RegexOption.IGNORE_CASE), "/raw/")

        thread {
            var conn: HttpURLConnection? = null
            try {
                // Добавляем микросекунды для пробития CDN-кэша Fastly
                val separator = if (subUrl.contains("?")) "&" else "?"
                val noCacheUrl = "$subUrl${separator}_ts=${System.nanoTime()}"

                val url = URL(noCacheUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("Expires", "0")
                    setRequestProperty("User-Agent", "v2rayng/1.8.0")
                }

                if (conn.responseCode != 200) {
                    throw Exception("Сервер вернул код ${conn.responseCode}")
                }

                var rawData = conn.inputStream.bufferedReader().use { it.readText() }.trim()

                if (!rawData.startsWith("vless://", ignoreCase = true)) {
                    try {
                        val decodedBytes = Base64.decode(rawData, Base64.DEFAULT)
                        rawData = String(decodedBytes, Charsets.UTF_8).trim()
                    } catch (_: Exception) {}
                }

                val lines = rawData.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("vless://", ignoreCase = true) }

                if (lines.isEmpty()) {
                    throw Exception("В подписке не найдено рабочих VLESS ссылок")
                }

                val newRemoteList = lines.mapIndexed { index, link ->
                    val cleanLink = "vless://" + link.substring(8)
                    val name = extractProfileName(cleanLink, "Сервер ${index + 1}")

                    ProfileItem(
                        id = "sub_" + cleanLink.hashCode(),
                        name = name,
                        url = cleanLink,
                        isSelected = false,
                        isPreset = true
                    )
                }

                // Перезаписываем список серверов подписки
                saveProfiles("remote_sub_profiles_json", newRemoteList)

                // Если удалённый сервер случайно завис в custom_profiles — вычищаем его и оттуда
                val remoteUrls = newRemoteList.map { it.url }.toSet()
                val custom = loadProfiles("custom_profiles_json", isPreset = false).toMutableList()
                val initialCustomSize = custom.size
                custom.removeAll { remoteUrls.contains(it.url) }
                if (custom.size != initialCustomSize) {
                    saveProfiles("custom_profiles_json", custom)
                }

                // Если активный сервер был удалён из подписки — переключаем выбор на первый доступный
                val currentSelectedId = prefs.getString("selected_profile_id", null)
                val allRemaining = newRemoteList + custom
                if (allRemaining.none { it.id == currentSelectedId }) {
                    allRemaining.firstOrNull()?.let { selectProfile(it.id) }
                }

                onSuccess(newRemoteList.size)

            } catch (e: Exception) {
                onError(e.message ?: "Сбой сети")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun loadProfiles(prefKey: String, isPreset: Boolean): List<ProfileItem> {
        val jsonStr = prefs.getString(prefKey, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ProfileItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ProfileItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        isSelected = false,
                        isPreset = isPreset
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveProfiles(prefKey: String, list: List<ProfileItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("url", it.url)
            })
        }
        prefs.edit().putString(prefKey, arr.toString()).apply()
    }

    // --- Управление базами правил RunetFreedom ---

    fun hasGeoAssets(): Boolean {
        val geoip = File(context.filesDir, "geoip.dat")
        val geosite = File(context.filesDir, "geosite.dat")
        return geoip.exists() && geosite.exists()
    }

    fun downloadGeoAssets(onProgress: (String) -> Unit): Result<String> {
        return try {
            onProgress("GeoIP...")
            downloadFile(
                "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat",
                File(context.filesDir, "geoip.dat")
            )
            onProgress("GeoSite...")
            downloadFile(
                "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat",
                File(context.filesDir, "geosite.dat")
            )
            Result.success("Базы RunetFreedom обновлены")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadFile(urlString: String, destFile: File) {
        val temp = File(destFile.parent, destFile.name + ".tmp")
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        try {
            conn.inputStream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (temp.exists() && temp.length() > 0) {
                if (destFile.exists()) destFile.delete()
                temp.renameTo(destFile)
            }
        } finally {
            conn.disconnect()
            if (temp.exists()) temp.delete()
        }
    }
}