package io.github.dovecoteescapee.byedpi.vless

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object VlessManager {
    private const val TAG = "VlessManager"
    private const val PREFS_NAME = "vless_preferences"
    private const val KEY_PROFILES = "vless_profiles_json"
    private const val KEY_SELECTED_ID = "vless_selected_profile_id"
    private const val KEY_SUBSCRIPTIONS = "vless_subscriptions_json"
    private const val KEY_SERVER_PINGS = "vless_server_pings_map"
    private const val KEY_SUB_USER_INFOS = "vless_sub_user_infos_map"
    private const val KEY_PING_METHOD = "vless_ping_method_setting"

    data class SubUserInfo(
        val uploadBytes: Long = 0L,
        val downloadBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val expireEpochSec: Long = 0L
    ) {
        val remainingBytes: Long
            get() = if (totalBytes > 0) (totalBytes - (uploadBytes + downloadBytes)).coerceAtLeast(0L) else 0L

        fun formatRemainingTraffic(): String {
            if (totalBytes <= 0) return ""
            val remGb = remainingBytes.toDouble() / (1024 * 1024 * 1024)
            val totalGb = totalBytes.toDouble() / (1024 * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1f / %.1f ГБ", remGb, totalGb)
        }

        fun formatExpireDays(): String {
            if (expireEpochSec <= 0) return ""
            val nowSec = System.currentTimeMillis() / 1000
            val diffSec = expireEpochSec - nowSec
            if (diffSec <= 0) return "Срок истек"
            val days = diffSec / 86400
            return if (days > 0) "$days дн." else "${diffSec / 3600} ч."
        }

        fun formatShortSummary(): String {
            val parts = mutableListOf<String>()
            val traf = formatRemainingTraffic()
            if (traf.isNotBlank()) parts.add("📊 $traf")
            val exp = formatExpireDays()
            if (exp.isNotBlank()) parts.add("⏳ $exp")
            return parts.joinToString("  •  ")
        }
    }

    fun getPingMethod(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PING_METHOD, "proxy_get") ?: "proxy_get"
    }

    fun setPingMethod(context: Context, method: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PING_METHOD, method).apply()
    }

    fun setSubUserInfo(context: Context, subUrl: String, info: SubUserInfo) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SUB_USER_INFOS, null) ?: "{}"
        try {
            val obj = JSONObject(jsonStr)
            val subObj = JSONObject().apply {
                put("upload", info.uploadBytes)
                put("download", info.downloadBytes)
                put("total", info.totalBytes)
                put("expire", info.expireEpochSec)
            }
            obj.put(subUrl.trim(), subObj)
            prefs.edit().putString(KEY_SUB_USER_INFOS, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getSubUserInfo(context: Context, subUrl: String): SubUserInfo? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SUB_USER_INFOS, null) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            val trimmed = subUrl.trim()
            if (obj.has(trimmed)) {
                val subObj = obj.getJSONObject(trimmed)
                SubUserInfo(
                    uploadBytes = subObj.optLong("upload", 0L),
                    downloadBytes = subObj.optLong("download", 0L),
                    totalBytes = subObj.optLong("total", 0L),
                    expireEpochSec = subObj.optLong("expire", 0L)
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun setPing(context: Context, serverId: String, pingMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SERVER_PINGS, null) ?: "{}"
        try {
            val obj = JSONObject(jsonStr)
            obj.put(serverId, pingMs)
            prefs.edit().putString(KEY_SERVER_PINGS, obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getPing(context: Context, serverId: String): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SERVER_PINGS, null) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has(serverId)) obj.getLong(serverId) else null
        } catch (_: Exception) {
            null
        }
    }

    fun getSubscriptions(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SUBSCRIPTIONS, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val s = arr.getString(i).trim()
                if (s.isNotBlank() && !list.contains(s)) list.add(s)
            }
        } catch (_: Exception) {}
        return list
    }

    fun addSubscription(context: Context, url: String) {
        val current = getSubscriptions(context).toMutableList()
        val trimmed = url.trim()
        if (!current.contains(trimmed)) {
            current.add(trimmed)
            saveSubscriptions(context, current)
        }
    }

    fun removeSubscription(context: Context, url: String) {
        val current = getSubscriptions(context).toMutableList()
        current.remove(url.trim())
        saveSubscriptions(context, current)

        // Also remove all configs associated with this subscription
        val configs = getConfigs(context).toMutableList()
        configs.removeAll { it.subscriptionUrl == url.trim() }
        saveConfigs(context, configs)
    }

    private fun saveSubscriptions(context: Context, list: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray(list)
        prefs.edit().putString(KEY_SUBSCRIPTIONS, arr.toString()).apply()
    }

    fun getConfigs(context: Context): List<VlessConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val list = mutableListOf<VlessConfig>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    VlessConfig(
                        id = obj.optString("id"),
                        subscriptionUrl = obj.optString("subscriptionUrl", ""),
                        name = obj.optString("name"),
                        address = obj.optString("address"),
                        port = obj.optInt("port", 443),
                        uuid = obj.optString("uuid"),
                        protocol = obj.optString("protocol", "vless"),
                        flow = obj.optString("flow"),
                        encryption = obj.optString("encryption", "none"),
                        transport = obj.optString("transport", "tcp"),
                        security = obj.optString("security", "none"),
                        sni = obj.optString("sni"),
                        pbk = obj.optString("pbk"),
                        sid = obj.optString("sid"),
                        fp = obj.optString("fp", "chrome"),
                        path = obj.optString("path"),
                        host = obj.optString("host"),
                        serviceName = obj.optString("serviceName"),
                        obfs = obj.optString("obfs", ""),
                        obfsPassword = obj.optString("obfsPassword", ""),
                        allowInsecure = obj.optBoolean("allowInsecure", false),
                        alterId = obj.optInt("alterId", 0),
                        rawUri = obj.optString("rawUri"),
                        isChain = obj.optBoolean("isChain", false),
                        chainMode = obj.optString("chainMode", ""),
                        chainHop1ConfigJson = obj.optString("chainHop1ConfigJson", ""),
                        chainWarpConfigText = obj.optString("chainWarpConfigText", ""),
                        desyncStrategyId = obj.optString("desyncStrategyId", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (list.isEmpty()) {
            list.add(DEFAULT_MASQUE_CONFIG)
            saveConfigs(context, list)
        }
        return list
    }

    val DEFAULT_MASQUE_CONFIG = VlessConfig(
        id = "cloudflare_warp_masque_default",
        name = "⚡ Cloudflare WARP MASQUE (HTTP/3 Anti-DPI)",
        address = "engage.cloudflareclient.com",
        port = 443,
        uuid = "Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc=",
        protocol = "masque",
        transport = "quic",
        security = "tls",
        sni = "engage.cloudflareclient.com",
        pbk = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
        path = "/cdn-cgi/warp/masque"
    )

    fun saveConfigs(context: Context, configs: List<VlessConfig>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (cfg in configs) {
            val obj = JSONObject().apply {
                put("id", cfg.id)
                put("subscriptionUrl", cfg.subscriptionUrl)
                put("name", cfg.name)
                put("address", cfg.address)
                put("port", cfg.port)
                put("uuid", cfg.uuid)
                put("protocol", cfg.protocol)
                put("flow", cfg.flow)
                put("encryption", cfg.encryption)
                put("transport", cfg.transport)
                put("security", cfg.security)
                put("sni", cfg.sni)
                put("pbk", cfg.pbk)
                put("sid", cfg.sid)
                put("fp", cfg.fp)
                put("path", cfg.path)
                put("host", cfg.host)
                put("serviceName", cfg.serviceName)
                put("obfs", cfg.obfs)
                put("obfsPassword", cfg.obfsPassword)
                put("allowInsecure", cfg.allowInsecure)
                put("alterId", cfg.alterId)
                put("rawUri", cfg.rawUri)
                put("isChain", cfg.isChain)
                put("chainMode", cfg.chainMode)
                put("chainHop1ConfigJson", cfg.chainHop1ConfigJson)
                put("chainWarpConfigText", cfg.chainWarpConfigText)
                put("desyncStrategyId", cfg.desyncStrategyId)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES, jsonArray.toString()).apply()
    }

    fun getSelectedConfig(context: Context): VlessConfig? {
        val configs = getConfigs(context)
        if (configs.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(KEY_SELECTED_ID, null)
        return configs.find { it.id == selectedId } ?: configs.first()
    }

    fun setSelectedConfigId(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
    }

    fun addConfig(context: Context, config: VlessConfig) {
        val current = getConfigs(context).toMutableList()
        current.removeAll { it.rawUri == config.rawUri || (it.address == config.address && it.port == config.port && it.uuid == config.uuid) }
        current.add(0, config)
        saveConfigs(context, current)
        setSelectedConfigId(context, config.id)
    }

    fun removeConfig(context: Context, id: String) {
        val current = getConfigs(context).toMutableList()
        current.removeAll { it.id == id }
        saveConfigs(context, current)
        val selected = getSelectedConfig(context)
        if (selected?.id == id) {
            if (current.isNotEmpty()) {
                setSelectedConfigId(context, current.first().id)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_SELECTED_ID).apply()
            }
        }
    }

    /**
     * Replaces all configs for a specific subscription URL with fresh ones from remote
     */
    fun replaceSubscriptionConfigs(context: Context, subUrl: String, newConfigs: List<VlessConfig>) {
        val current = getConfigs(context).toMutableList()
        current.removeAll { it.subscriptionUrl == subUrl.trim() }
        current.addAll(newConfigs)
        saveConfigs(context, current)
        addSubscription(context, subUrl)

        if (getSelectedConfig(context) == null && newConfigs.isNotEmpty()) {
            setSelectedConfigId(context, newConfigs.first().id)
        }
    }

    /**
     * Imports multiple proxy links (VLESS, Hysteria2, SS, VMess, Trojan) from raw text or Base64
     */
    fun parseSubscriptionContent(content: String, subscriptionUrl: String = ""): List<VlessConfig> {
        var text = content.trim()
        val hasKnownScheme = text.contains("vless://", ignoreCase = true) ||
                text.contains("masque://", ignoreCase = true) ||
                text.contains("warp+masque://", ignoreCase = true) ||
                text.contains("hy2://", ignoreCase = true) ||
                text.contains("hysteria2://", ignoreCase = true) ||
                text.contains("ss://", ignoreCase = true) ||
                text.contains("vmess://", ignoreCase = true) ||
                text.contains("trojan://", ignoreCase = true)

        if (!hasKnownScheme) {
            try {
                val clean = text.replace('-', '+').replace('_', '/')
                val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
                val decodedStr = String(decodedBytes, Charsets.UTF_8).trim()
                if (decodedStr.isNotBlank()) {
                    text = decodedStr
                }
            } catch (_: Exception) {
                // Not base64
            }
        }

        val results = mutableListOf<VlessConfig>()
        val lines = text.split("\r\n", "\n", "\r")
        for (line in lines) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()
            if (lower.startsWith("vless://") ||
                lower.startsWith("masque://") ||
                lower.startsWith("warp+masque://") ||
                lower.startsWith("hy2://") ||
                lower.startsWith("hysteria2://") ||
                lower.startsWith("ss://") ||
                lower.startsWith("vmess://") ||
                lower.startsWith("trojan://")) {
                VlessConfig.parse(trimmed, subscriptionUrl)?.let { results.add(it) }
            }
        }
        return results
    }

    suspend fun fetchSubscription(context: Context, subscriptionUrl: String): Result<List<VlessConfig>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(subscriptionUrl.trim())
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "v2rayNG/2.2.6")
                    instanceFollowRedirects = true
                }

                // Читаем заголовок Subscription-Userinfo: upload=1234; download=5678; total=99999; expire=1732000000
                val userInfoHeader = connection.getHeaderField("Subscription-Userinfo") ?: connection.getHeaderField("subscription-userinfo")
                if (!userInfoHeader.isNullOrBlank()) {
                    try {
                        var up = 0L
                        var down = 0L
                        var total = 0L
                        var exp = 0L
                        val tokens = userInfoHeader.split(";")
                        for (token in tokens) {
                            val kv = token.trim().split("=")
                            if (kv.size == 2) {
                                val k = kv[0].trim().lowercase()
                                val v = kv[1].trim().toLongOrNull() ?: 0L
                                when (k) {
                                    "upload" -> up = v
                                    "download" -> down = v
                                    "total" -> total = v
                                    "expire" -> exp = v
                                }
                            }
                        }
                        if (total > 0 || exp > 0) {
                            setSubUserInfo(context, subscriptionUrl, SubUserInfo(up, down, total, exp))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Subscription-Userinfo header", e)
                    }
                }

                if (connection.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP error ${connection.responseCode}"))
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseSubscriptionContent(body, subscriptionUrl.trim())
                if (parsed.isEmpty()) {
                    Result.failure(Exception("Не найдено серверов (vless, hy2, ss, vmess, trojan)"))
                } else {
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun fetchSubscription(subscriptionUrl: String): Result<List<VlessConfig>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(subscriptionUrl.trim())
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "v2rayNG/2.2.6")
                    instanceFollowRedirects = true
                }

                if (connection.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("HTTP error ${connection.responseCode}"))
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseSubscriptionContent(body, subscriptionUrl.trim())
                if (parsed.isEmpty()) {
                    Result.failure(Exception("Не найдено серверов (vless, hy2, ss, vmess, trojan)"))
                } else {
                    Result.success(parsed)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
