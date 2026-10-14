package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

object WarpConfigManager {

    private const val TAG = "WarpConfigManager"
    private const val PREFS_NAME = "warp_config_prefs"
    private const val KEY_CONFIG = "saved_warp_config"
    private const val KEY_WARP_MODE = "pref_warp_mode" // "masque" or "awg"
    private const val KEY_MASQUE_ENDPOINT = "pref_masque_endpoint"

    // Оптимизированный рабочий конфиг с зарегистрированными ключами Cloudflare и незаблокированным портом 500
    const val DEFAULT_CONFIG = """[Interface]
PrivateKey = NkcUHbXlY52ibVKOCsO0idZuXwc7flbbc6PrPDyHpLM=
Address = 172.16.0.2, 2606:4700:110:8ec3:971:2447:f1a1:cdf3
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
Jc = 4
Jmin = 48
Jmax = 290

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 162.159.192.1:500
PersistentKeepalive = 25"""

    const val DEFAULT_MASQUE_ENDPOINT = "162.159.192.1:500"

    private val _currentConfig = MutableStateFlow(DEFAULT_CONFIG)
    val currentConfig: StateFlow<String> = _currentConfig

    private val _isMasqueMode = MutableStateFlow(false)
    val isMasqueModeFlow: StateFlow<Boolean> = _isMasqueMode

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CONFIG, null)
        if (saved.isNullOrBlank() || saved.contains("Wli/uh1ka24") || saved.contains("8HRm8YSHu+jcewZJA6+SN4o5mF+yRteV7NDQTfEiUiw=") || saved.contains("H1 = 1")) {
            _currentConfig.value = DEFAULT_CONFIG
            prefs.edit { putString(KEY_CONFIG, DEFAULT_CONFIG) }
        } else {
            // Авто-миграция с заблокированных и замедленных РКН эндпоинтов (2408, 854, 8854, 188.114.x)
            if (saved.contains(":2408") || saved.contains("188.114.98.8") || saved.contains(":854") || saved.contains(":8854") || saved.contains("engage.cloudflareclient.com:500")) {
                val upgraded = WarpEndpointOptimizer.optimizeConfigString(
                    saved,
                    WarpEndpointOptimizer.BEST_DEFAULT_ENDPOINT
                )
                _currentConfig.value = upgraded
                prefs.edit { putString(KEY_CONFIG, upgraded) }
                Log.i(TAG, "Автоматически обновлен сервер WARP на надежный IP Anycast")
            } else {
                _currentConfig.value = saved
            }
        }

        // По умолчанию стабильный AmneziaWG 3.1 (GoBackend) с обходом ТСПУ
        val isMasque = prefs.getBoolean(KEY_WARP_MODE, false)
        _isMasqueMode.value = isMasque
    }

    fun isMasqueMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WARP_MODE, false)
    }

    fun setMasqueMode(context: Context, enabled: Boolean) {
        _isMasqueMode.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_WARP_MODE, enabled) }
    }

    fun getMasqueEndpoint(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MASQUE_ENDPOINT, DEFAULT_MASQUE_ENDPOINT) ?: DEFAULT_MASQUE_ENDPOINT
    }

    fun setMasqueEndpoint(context: Context, endpoint: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_MASQUE_ENDPOINT, endpoint.trim()) }
    }

    fun getMasqueConfig(context: Context): WarpMasqueClient.MasqueConfig {
        val raw = _currentConfig.value
        val privKey = Regex("""(?m)^\s*PrivateKey\s*=\s*(.+)$""").find(raw)?.groupValues?.get(1)?.trim()
            ?: "Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc="
        val pubKey = Regex("""(?m)^\s*PublicKey\s*=\s*(.+)$""").find(raw)?.groupValues?.get(1)?.trim()
            ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

        val endpoint = getMasqueEndpoint(context)
        val parts = endpoint.split(":")
        val host = parts[0].ifBlank { "engage.cloudflareclient.com" }
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 443 else 443

        return WarpMasqueClient.MasqueConfig(
            privateKey = privKey,
            peerPublicKey = pubKey,
            endpointHost = host,
            endpointPort = port
        )
    }

    fun saveConfig(context: Context, newConfig: String) {
        val trimmed = newConfig.trim()
        _currentConfig.value = trimmed
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CONFIG, trimmed) }
    }

    fun extractEndpoint(config: String): String {
        val match = Regex("""(?m)^\s*Endpoint\s*=\s*(.+)$""").find(config)
        return match?.groupValues?.get(1)?.trim() ?: WarpEndpointOptimizer.BEST_DEFAULT_ENDPOINT
    }

    /**
     * Сканирует эндпоинты Cloudflare и мгновенно применяет сервер с минимальным пингом
     */
    suspend fun optimizeEndpoint(context: Context): Pair<String, Long> = withContext(Dispatchers.IO) {
        val (bestEndpoint, ping) = WarpEndpointOptimizer.findBestEndpoint()
        val current = _currentConfig.value
        val optimized = WarpEndpointOptimizer.optimizeConfigString(current, bestEndpoint)
        saveConfig(context, optimized)
        Log.i(TAG, "WARP endpoint optimized to $bestEndpoint (ping: ${ping}ms)")
        bestEndpoint to ping
    }
}
