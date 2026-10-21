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

    // Оптимизированный рабочий конфиг с зарегистрированными ключами Cloudflare, AWG 3.1 обфускацией и портом 4500
    const val DEFAULT_CONFIG = """[Interface]
PrivateKey = KZhXqHc7P3P1ahO+iLjv2ZjEu4eUdRPU942JuaEB4X8=
Address = 172.16.0.2, 2606:4700:110:8655:e6ef:f746:2058:dac0
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
S1 = 0
S2 = 0
S3 = 0
S4 = 0
Jc = 7
Jmin = 47
Jmax = 213
H1 = 1
H2 = 2
H3 = 3
H4 = 4
I1 = <b 0xce00000001013d000044a565301964bc4072b08c0db5c8c575ff132906877747a3f1275722b31e82526e5e90a7b10281136ef6bcdcd9e491d7c2ec94068bc792f650fce1de023ba416397464929307332ce0662ab37c5b92e954d8ac0497a6f368a1c7bab5a515d40f13cc6af5b084204e6bc3aa69a76a3bb43cadf16a5cca2c067310e55d17bb76a9ee64a62f5a20c55a8c1714e778ff4917b6d1069526b5037b7b4b05c80e6843da0b23fa7a3611626d4e75aecc75bb87782e19f4019d969546612993da81db7cdc2b359e20887a357da6ebd99a28e7eb5c14b7ea71d93fe06f980e794ac28aaf35a897dfd7851bb9a2b76a1df41f63c7f1b627edd49a77c56cd08bb20be3655a646c9acbe883e9e9e7444f5a4ea7b90a40aaa1120f11d074ddb3c7927c0419b6b225b457a25d44618d388164c24fe1041fd6422cc936fc617d8ae15cb8b7dfe636f3ff2e969b03ca4951309e0dd17d43178dfff5f21f80851b022338e160074edf275aab42eaf2be1fa41c92a894155448b5b7cad2966ecb018104cae7368b6b1a64679a386026f14156a6b6e5cf2f5a235116f15d27a26d67e08077d95682e25a99fa59965cf86bc405f59ac6e12df700ee340fafa3dedc9caf8af984ebc7b93caf145b6461169b9024891346f2f53ddf19656f9dbd266acbac29adf84283643a5efc98bebe61acc38f0b18a01c0c60a4271900020fe7ba06577e4a5ba5ef155f10cc70f7f45b7fe2292a0159629b0a7f7c48150308833dc221a3c674026e77e9df8716aa283f02be18e7e17c11a911267172084895ab3973129f73bbee19437f46aec74f2380fae85cd7ec5f58bf523e95a06c587a30cc25e6d2c39e3ed7a4704fbed5938e4a5e062de159d95349493c61c7b7a6554d53c04e1d5d9b12a9ad6e6c92c5184b0c8d2c74f4800c37f3ffa2c2e610e2e738c452a4bbf7027eef9b9ebb3c8de9d6555fc37f5c5ee89df2c6ad0b6ec9e7baa0e6ac9044d508d2bd818900c77cf92ed564efb641f7c6bd93f5326e2ae92906931c997cc0483cf48fb3815b3c1de74b515f5bbeea9bfb4bcf10c57e03c62e9e7d56e8a26ba068f51c8ac685355d881573085acdb20866ee2d2abade43dd98a889b2e911ea9bc19a170bba318d22f3e76df07a5875b1b8de2b207982eca46f2dcdac326862be583db523fd774bf989d59ff31fd76061e51f67e3a20bdf9098158de879c30ba339d4ee2983e5a8d4904b4568cd9a818d50f809134a65ba4bab2f25a04e18f56d35181073d7d5f2a86ffff08cc1c2953ee8fe47e17deb534e9433a5e6ed9c4b37b3d950c1bcb6ad5d3a9ba13f58cd123292c2c2a32d9373a4be8f834b5b5a9483bd048f29e8cc8270bfc733a52d87c394be2da098c76ff6e4c9f78d00111887917d2138e59bedbfafe46c0ce38b728f8cec15cba48518a9fb625810dd72d8515b670fb4cd6206730666a907c6e79a1f33d9ba907589e06fd19ee672e848aab7ed7ef641ed64764dbef61efcceda2f75329a2395f0ed155587c2332c7932d1ae17abc592cc6df01ff7e52bc2d9557efe0f05a8da3fe75432345747953cd540f533f48648980d01e4d3aea63a2da38186b344659ba21149e2d63f3a3a75455acc2789fd53aa273645612717012f445fb845cdf5799932b1ea48776ca4b5907c61dbca9fb2b4e297ba358a3766e3>
I2 = <b 0x82269606be662e4097dbd06112b920a3fed95c86de4ea9c3a7>
I3 = <r 18>
I4 = <b 0xe74f2cf83b7cfd54abf2ee3c8ecc>
I5 = <r 9>

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 162.159.192.1:4500
PersistentKeepalive = 25"""

    const val DEFAULT_MASQUE_ENDPOINT = "162.159.192.1:4500"

    private val _currentConfig = MutableStateFlow(DEFAULT_CONFIG)
    val currentConfig: StateFlow<String> = _currentConfig

    private val _isMasqueMode = MutableStateFlow(false)
    val isMasqueModeFlow: StateFlow<Boolean> = _isMasqueMode

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CONFIG, null)
        if (saved.isNullOrBlank() || saved.contains("Wli/uh1ka24") || saved.contains("8HRm8YSHu+jcewZJA6+SN4o5mF+yRteV7NDQTfEiUiw=") || !saved.contains("H1 = 1") || !saved.contains("I1 =")) {
            _currentConfig.value = DEFAULT_CONFIG
            prefs.edit { putString(KEY_CONFIG, DEFAULT_CONFIG) }
        } else {
            _currentConfig.value = saved
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
