package com.antigulag.vpn

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLDecoder

object ConfigBuilder {

    private fun buildStreamSettings(uri: Uri, serverDomain: String): JSONObject {
        val security = uri.getQueryParameter("security")?.lowercase() ?: "none"
        val sni = uri.getQueryParameter("sni")?.takeIf { it.isNotEmpty() } ?: serverDomain
        val fp = uri.getQueryParameter("fp")?.takeIf { it.isNotEmpty() } ?: "chrome"
        val pbk = uri.getQueryParameter("pbk") ?: ""
        val sid = uri.getQueryParameter("sid") ?: ""
        val spx = uri.getQueryParameter("spx") ?: ""

        val network = when {
            uri.getQueryParameter("type")?.isNotEmpty() == true -> uri.getQueryParameter("type")!!.lowercase()
            uri.getQueryParameter("mode") != null || uri.getQueryParameter("extra") != null -> "xhttp"
            else -> "tcp"
        }

        return JSONObject().apply {
            put("network", network)
            put("security", security)
            put("sockopt", JSONObject().apply {
                put("tcpUserTimeout", 5000)        // 5 секунд на ожидание ответа до сброса мёртвого сокета
                put("tcpKeepAliveInterval", 10)    // Интервал проверки живости канала (в секундах)
            })

            when (security) {
                "reality" -> {
                    put("realitySettings", JSONObject().apply {
                        put("show", false)
                        put("fingerprint", fp)
                        put("serverName", sni)
                        put("publicKey", pbk)
                        put("shortId", sid)
                        if (spx.isNotEmpty()) put("spiderX", spx)
                    })
                }
                "tls" -> {
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", sni)
                        put("fingerprint", fp)
                    })
                }
            }

            when (network) {
                "xhttp" -> {
                    val path = uri.getQueryParameter("path") ?: "/"
                    val host = uri.getQueryParameter("host") ?: serverDomain
                    val mode = uri.getQueryParameter("mode") ?: "auto"
                    val extraParam = uri.getQueryParameter("extra")
                    val extraJson = if (!extraParam.isNullOrEmpty()) {
                        try { JSONObject(URLDecoder.decode(extraParam, "UTF-8")) } catch (_: Exception) { JSONObject() }
                    } else {
                        JSONObject()
                    }

                    put("xhttpSettings", JSONObject().apply {
                        put("path", path)
                        put("host", host)
                        put("mode", mode)
                        put("extra", extraJson)
                        val keys = extraJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, extraJson.get(key))
                        }
                    })
                }
                "ws" -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", uri.getQueryParameter("path") ?: "/")
                        put("headers", JSONObject().apply {
                            put("Host", uri.getQueryParameter("host") ?: sni)
                        })
                    })
                }
                "tcp" -> {
                    val headerType = uri.getQueryParameter("headerType")
                    if (!headerType.isNullOrEmpty() && headerType != "none") {
                        put("tcpSettings", JSONObject().apply {
                            put("header", JSONObject().apply { put("type", headerType) })
                        })
                    }
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", uri.getQueryParameter("serviceName") ?: "")
                    })
                }
            }
        }
    }

    private fun buildUserObject(uri: Uri): JSONObject {
        val uuid = uri.userInfo ?: throw IllegalArgumentException("UUID не найден")
        val encryption = uri.getQueryParameter("encryption")?.takeIf { it.isNotEmpty() } ?: "none"
        val flow = uri.getQueryParameter("flow")

        return JSONObject().apply {
            put("id", uuid)
            put("encryption", encryption)
            if (!flow.isNullOrEmpty()) put("flow", flow)
        }
    }

    fun buildConfig(context: Context, vlessUrl: String, enableGeoRouting: Boolean = true): String {
        val routingPrefs = context.getSharedPreferences("routing_rules_prefs", Context.MODE_PRIVATE)
        val profileManager = ProfileManager(context)

        val cleanUrl = if (vlessUrl.startsWith("vless://", ignoreCase = true)) {
            "vless://" + vlessUrl.substring(8)
        } else {
            vlessUrl
        }

        val uri = Uri.parse(cleanUrl)
        val serverDomain = uri.host ?: throw IllegalArgumentException("Хост сервера не найден")
        val port = if (uri.port != -1) uri.port else 443
        val packetEncoding = uri.getQueryParameter("packetEncoding")?.takeIf { it.isNotEmpty() } ?: "xudp"

        val customProxyDomains = routingPrefs.getString("custom_proxy_domains", "") ?: ""
        val customDirectDomains = routingPrefs.getString("custom_direct_domains", "") ?: ""
        val customDns = routingPrefs.getString("custom_dns", "77.88.8.8, 8.8.8.8") ?: "77.88.8.8, 8.8.8.8"

        val serverIp = try {
            InetAddress.getByName(serverDomain).hostAddress ?: serverDomain
        } catch (_: Exception) {
            serverDomain
        }

        val streamSettings = buildStreamSettings(uri, serverDomain)
        val userObj = buildUserObject(uri)

        val dnsList = customDns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val dnsServersJson = JSONArray().apply {
            if (dnsList.isNotEmpty()) {
                dnsList.forEach { put(it) }
            } else {
                put("77.88.8.8")
                put("8.8.8.8")
            }
        }

        val rulesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("tun-in").put("socks-in").put("http-in"))
                put("port", "53")
                put("outboundTag", "dns-out")
            })

            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray().put("77.88.8.8"))
            })

            val isNumericIp = serverIp.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                if (isNumericIp) {
                    put("ip", JSONArray().put(serverIp))
                } else {
                    put("domain", JSONArray().put(serverDomain))
                }
            })

            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("domain", JSONArray().put("domain:connectivitycheck.gstatic.com"))
            })

            val customProxyList = customProxyDomains.split(",")
                .map { it.trim().removePrefix("domain:").removePrefix("full:") }
                .filter { it.isNotEmpty() }

            if (customProxyList.isNotEmpty()) {
                val proxyDomainsArray = JSONArray()
                customProxyList.forEach { proxyDomainsArray.put("domain:$it") }
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "proxy")
                    put("domain", proxyDomainsArray)
                })
            }

            val customDirectList = customDirectDomains.split(",")
                .map { it.trim().removePrefix("domain:").removePrefix("full:") }
                .filter { it.isNotEmpty() }

            if (customDirectList.isNotEmpty()) {
                val directDomainsArray = JSONArray()
                customDirectList.forEach { directDomainsArray.put("domain:$it") }
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", directDomainsArray)
                })
            }

            if (enableGeoRouting && profileManager.hasGeoAssets()) {
                if (routingPrefs.getBoolean("rule_ads", true)) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "block")
                        put("domain", JSONArray().put("geosite:category-ads-all"))
                    })
                }
                if (routingPrefs.getBoolean("rule_block_quic", true)) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "block")
                        put("network", "udp")
                        put("port", "443")
                    })
                }
                if (routingPrefs.getBoolean("rule_private_domain", true)) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("domain", JSONArray().put("geosite:private"))
                    })
                }
                if (routingPrefs.getBoolean("rule_private_ip", true)) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("ip", JSONArray().apply {
                            put("geoip:private")
                            put("224.0.0.0/4")        // Мультикаст (SSDP / mDNS для поиска Smart View, ТВ, колонок)
                            put("255.255.255.255/32")  // Локальный broadcast
                        })
                    })
                }
                if (routingPrefs.getBoolean("rule_ru_direct", true)) {
                    put(JSONObject().apply {
                        put("type", "field")
                        put("outboundTag", "direct")
                        put("domain", JSONArray().put("geosite:ru-available-only-inside"))
                    })
                }
            }

            put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().put("tun-in").put("socks-in").put("http-in"))
                put("outboundTag", "proxy")
            })
        }

        return """
        {
          "log": { "loglevel": "warning" },
          "stats": {},
          "policy": {
            "system": {
              "statsOutboundUplink": true,
              "statsOutboundDownlink": true
            }
          },
          "dns": {
            "queryStrategy": "UseIPv4",
            "servers": $dnsServersJson
          },
          "inbounds": [
            {
              "tag": "tun-in",
              "port": 0,
              "protocol": "tun",
              "settings": {
                "name": "tun0",
                "mtu": 1500
              },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
              }
            },
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": 10808,
              "protocol": "socks",
              "settings": {
                "udp": true,
                "auth": "noauth"
              }
            },
            {
              "tag": "http-in",
              "listen": "127.0.0.1",
              "port": 10809,
              "protocol": "http"
            }
          ],
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$serverIp",
                    "port": $port,
                    "users": [
                      $userObj
                    ]
                  }
                ],
                "packetEncoding": "$packetEncoding"
              },
              "streamSettings": $streamSettings
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            },
            {
              "tag": "block",
              "protocol": "blackhole"
            },
            {
              "tag": "dns-out",
              "protocol": "dns"
            }
          ],
          "routing": {
            "domainStrategy": "IPIfNonMatch",
            "rules": $rulesArray
          }
        }
        """.trimIndent()
    }

    // Изолированный конфиг для нативного замера RTT (с DNS и полной поддержкой транспорта)
    fun buildPingConfig(vlessUrl: String): String {
        val cleanUrl = if (vlessUrl.startsWith("vless://", ignoreCase = true)) {
            "vless://" + vlessUrl.substring(8)
        } else {
            vlessUrl
        }

        val uri = try { Uri.parse(cleanUrl) } catch (_: Exception) { return "" }
        val serverDomain = uri.host ?: return ""
        val port = if (uri.port != -1) uri.port else 443
        val packetEncoding = uri.getQueryParameter("packetEncoding")?.takeIf { it.isNotEmpty() } ?: "xudp"

        val serverIp = try {
            InetAddress.getByName(serverDomain).hostAddress ?: serverDomain
        } catch (_: Exception) {
            serverDomain
        }

        val streamSettings = try {
            buildStreamSettings(uri, serverDomain)
        } catch (_: Exception) {
            return ""
        }

        val userObj = try {
            buildUserObject(uri)
        } catch (_: Exception) {
            return ""
        }

        return """
        {
          "log": { "loglevel": "none" },
          "dns": {
            "queryStrategy": "UseIPv4",
            "servers": [ "8.8.8.8", "77.88.8.8", "1.1.1.1" ]
          },
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "$serverIp",
                    "port": $port,
                    "users": [
                      $userObj
                    ]
                  }
                ],
                "packetEncoding": "$packetEncoding"
              },
              "streamSettings": $streamSettings
            },
            {
              "tag": "direct",
              "protocol": "freedom"
            }
          ]
        }
        """.trimIndent()
    }
}