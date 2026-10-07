package io.github.dovecoteescapee.byedpi.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxConfig
import io.github.dovecoteescapee.byedpi.openflux.OpenFluxManager
import io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.vless.VlessConfig
import io.github.dovecoteescapee.byedpi.vless.VlessManager
import io.github.dovecoteescapee.byedpi.warp.WarpConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RestoreSummary(
    val vlessCount: Int,
    val subscriptionsCount: Int,
    val warpRestored: Boolean,
    val openFluxCount: Int,
    val splitTunnelAppsCount: Int,
    val preferencesCount: Int
)

object BackupManager {
    private const val TAG = "BackupManager"
    const val BACKUP_VERSION = 1

    /**
     * Генерирует имя файла по умолчанию для экспорта резервной копии.
     */
    fun generateFileName(): String {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "byedpi_russia_backup_$dateStr.json"
    }

    /**
     * Экспорт всех настроек приложения в форматированную JSON-строку.
     */
    fun createBackupJson(context: Context): String {
        val root = JSONObject().apply {
            put("format", "byedpi_russia_backup")
            put("version", BACKUP_VERSION)
            put("timestamp", System.currentTimeMillis())
            put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

            // 1. Общие настройки (SharedPreferences)
            val prefsObj = JSONObject().apply {
                val defPrefs = context.getPreferences()
                put("byedpi_mode", defPrefs.getString("byedpi_mode", "vpn"))
                put("accent_color", defPrefs.getString("accent_color", "dynamic"))
                put("app_language", defPrefs.getString("app_language", "system"))
                put("dynamic_island_enabled", context.isDynamicIslandEnabled())
                put("initial_setup_completed", context.isInitialSetupDone())
                put("tg_proxy_base_secret", context.getTgProxyBaseSecret())
                context.getTgProxyEffectiveSecret()?.let { put("tg_proxy_effective_secret", it) }
            }
            put("preferences", prefsObj)

            // Экспериментальные настройки
            val expObj = JSONObject().apply {
                put("battery_saver", io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isBatterySaverEnabled(context))
                put("block_quic", io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isBlockQuicEnabled(context))
                put("tcp_fast_open", io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isTcpFastOpenEnabled(context))
                put("mtu_clamp", io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isMtuClampEnabled(context))
                put("aggressive_keepalive", io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isAggressiveKeepAliveEnabled(context))
            }
            put("experimental", expObj)

            // 2. VLESS / Xray серверы и подписки
            val vlessObj = JSONObject().apply {
                val subscriptions = VlessManager.getSubscriptions(context)
                val subsArr = JSONArray()
                subscriptions.forEach { subsArr.put(it) }
                put("subscriptions", subsArr)

                val configs = VlessManager.getConfigs(context)
                val configsArr = JSONArray()
                configs.forEach { configsArr.put(it.toJson()) }
                put("configs", configsArr)

                VlessManager.getSelectedConfig(context)?.id?.let {
                    put("selected_id", it)
                }
            }
            put("vless", vlessObj)

            // 3. WARP / AmneziaWG конфигурация (.conf)
            val warpObj = JSONObject().apply {
                put("config", WarpConfigManager.currentConfig.value)
            }
            put("warp", warpObj)

            // 4. OpenFLUX профили
            val openFluxObj = JSONObject().apply {
                val configs = OpenFluxManager.getConfigs(context)
                val configsArr = JSONArray()
                configs.forEach { configsArr.put(it.toJson()) }
                put("configs", configsArr)

                OpenFluxManager.getSelectedId(context)?.let {
                    put("selected_id", it)
                }
                put("routing_mode", OpenFluxManager.getRoutingMode(context))
            }
            put("openflux", openFluxObj)

            // 5. Раздельное туннелирование (Split Tunneling)
            val splitTunnelObj = JSONObject().apply {
                put("mode", SplitTunnelManager.getMode(context))
                put("exclude_russian", SplitTunnelManager.isExcludeRussianAppsEnabled(context))
                val pkgsArr = JSONArray()
                SplitTunnelManager.getSelectedPackages(context).forEach { pkgsArr.put(it) }
                put("selected_packages", pkgsArr)
            }
            put("split_tunneling", splitTunnelObj)
        }

        return root.toString(2)
    }

    /**
     * Восстановление настроек из JSON-строки.
     */
    fun restoreBackupJson(context: Context, jsonStr: String): Result<RestoreSummary> {
        return try {
            val root = JSONObject(jsonStr)

            // Проверка формата
            val format = root.optString("format", "")
            if (format != "byedpi_russia_backup" && !root.has("vless") && !root.has("warp")) {
                return Result.failure(IllegalArgumentException("Файл не является резервной копией ByeDPI Russia"))
            }

            var prefCount = 0
            var vlessCount = 0
            var subsCount = 0
            var warpRestored = false
            var openFluxCount = 0
            var splitPkgsCount = 0

            // 1. Восстановление preferences
            root.optJSONObject("preferences")?.let { prefs ->
                val editor = context.getPreferences().edit()
                if (prefs.has("byedpi_mode")) {
                    editor.putString("byedpi_mode", prefs.getString("byedpi_mode"))
                    prefCount++
                }
                if (prefs.has("accent_color")) {
                    editor.putString("accent_color", prefs.getString("accent_color"))
                    prefCount++
                }
                if (prefs.has("app_language")) {
                    editor.putString("app_language", prefs.getString("app_language"))
                    prefCount++
                }
                if (prefs.has("dynamic_island_enabled")) {
                    context.setDynamicIslandEnabled(prefs.getBoolean("dynamic_island_enabled"))
                    prefCount++
                }
                if (prefs.has("tg_proxy_base_secret")) {
                    context.setTgProxyBaseSecret(prefs.getString("tg_proxy_base_secret"))
                    prefCount++
                }
                editor.apply()
            }

            // 2. Восстановление VLESS
            root.optJSONObject("vless")?.let { vlessObj ->
                // Подписки
                vlessObj.optJSONArray("subscriptions")?.let { subsArr ->
                    for (i in 0 until subsArr.length()) {
                        val subUrl = subsArr.optString(i)
                        if (subUrl.isNotBlank()) {
                            VlessManager.addSubscription(context, subUrl)
                            subsCount++
                        }
                    }
                }

                // Серверы
                vlessObj.optJSONArray("configs")?.let { configsArr ->
                    val existing = VlessManager.getConfigs(context).toMutableList()
                    val existingIds = existing.map { it.id }.toSet()
                    for (i in 0 until configsArr.length()) {
                        val cfgJson = configsArr.optJSONObject(i) ?: continue
                        val cfg = VlessConfig.fromJson(cfgJson)
                        if (!existingIds.contains(cfg.id)) {
                            existing.add(cfg)
                            vlessCount++
                        }
                    }
                    VlessManager.saveConfigs(context, existing)
                }

                val selectedId = vlessObj.optString("selected_id", "")
                if (selectedId.isNotBlank()) {
                    VlessManager.setSelectedConfigId(context, selectedId)
                }
            }

            // 3. Восстановление WARP
            root.optJSONObject("warp")?.let { warpObj ->
                val warpConf = warpObj.optString("config", "")
                if (warpConf.isNotBlank() && warpConf.contains("[Interface]") && warpConf.contains("[Peer]")) {
                    WarpConfigManager.saveConfig(context, warpConf)
                    warpRestored = true
                }
            }

            // 4. Восстановление OpenFLUX
            root.optJSONObject("openflux")?.let { ofObj ->
                ofObj.optJSONArray("configs")?.let { ofArr ->
                    val existing = OpenFluxManager.getConfigs(context).toMutableList()
                    val existingIds = existing.map { it.id }.toSet()
                    for (i in 0 until ofArr.length()) {
                        val cfgJson = ofArr.optJSONObject(i) ?: continue
                        val cfg = OpenFluxConfig.fromJson(cfgJson)
                        if (!existingIds.contains(cfg.id)) {
                            existing.add(cfg)
                            openFluxCount++
                        }
                    }
                    OpenFluxManager.saveConfigs(context, existing)
                }

                val selectedId = ofObj.optString("selected_id", "")
                if (selectedId.isNotBlank()) {
                    OpenFluxManager.setSelectedId(context, selectedId)
                }
                val routingMode = ofObj.optString("routing_mode", "")
                if (routingMode.isNotBlank()) {
                    OpenFluxManager.setRoutingMode(context, routingMode)
                }
            }

            // 5. Восстановление Split Tunneling
            root.optJSONObject("split_tunneling")?.let { stObj ->
                if (stObj.has("mode")) {
                    SplitTunnelManager.setMode(context, stObj.getInt("mode"))
                }
                if (stObj.has("exclude_russian")) {
                    SplitTunnelManager.setExcludeRussianAppsEnabled(context, stObj.getBoolean("exclude_russian"))
                }
                stObj.optJSONArray("selected_packages")?.let { pkgsArr ->
                    val set = mutableSetOf<String>()
                    for (i in 0 until pkgsArr.length()) {
                        set.add(pkgsArr.getString(i))
                    }
                    SplitTunnelManager.setSelectedPackages(context, set)
                    splitPkgsCount = set.size
                }
            }

            // 6. Восстановление Экспериментальных настроек
            root.optJSONObject("experimental")?.let { expObj ->
                if (expObj.has("battery_saver")) io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.setBatterySaverEnabled(context, expObj.getBoolean("battery_saver"))
                if (expObj.has("block_quic")) io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.setBlockQuicEnabled(context, expObj.getBoolean("block_quic"))
                if (expObj.has("tcp_fast_open")) io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.setTcpFastOpenEnabled(context, expObj.getBoolean("tcp_fast_open"))
                if (expObj.has("mtu_clamp")) io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.setMtuClampEnabled(context, expObj.getBoolean("mtu_clamp"))
                if (expObj.has("aggressive_keepalive")) io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.setAggressiveKeepAliveEnabled(context, expObj.getBoolean("aggressive_keepalive"))
            }

            Result.success(
                RestoreSummary(
                    vlessCount = vlessCount,
                    subscriptionsCount = subsCount,
                    warpRestored = warpRestored,
                    openFluxCount = openFluxCount,
                    splitTunnelAppsCount = splitPkgsCount,
                    preferencesCount = prefCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup", e)
            Result.failure(e)
        }
    }
}
