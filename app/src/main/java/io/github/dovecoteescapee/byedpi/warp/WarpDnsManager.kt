package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.dovecoteescapee.byedpi.utility.getWarpDnsKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

data class WarpDnsPreset(
    val key: String,
    val title: String,
    val hostOrIps: String,
    val staticIps: List<String>
)

object WarpDnsManager {

    private const val TAG = "WarpDnsManager"

    val presets = listOf(
        WarpDnsPreset("cloudflare", "Cloudflare DNS", "1.1.1.1, 1.0.0.1", listOf("1.1.1.1", "1.0.0.1")),
        WarpDnsPreset("google", "Google DNS", "8.8.8.8, 8.8.4.4", listOf("8.8.8.8", "8.8.4.4")),
        WarpDnsPreset("xbox", "Xbox DNS (xbox-dns.ru)", "xbox-dns.ru", listOf("111.88.96.57", "111.88.96.56")),
        WarpDnsPreset("comss", "COMSS DNS (dns.comss.one)", "dns.comss.one", listOf("92.223.109.31", "91.230.211.67")),
        WarpDnsPreset("malw", "DNS Malw Link (dns.malw.link)", "dns.malw.link", listOf("95.216.204.218", "193.23.209.189")),
        WarpDnsPreset("ai", "DNS AI (dns.dns-ai.ru)", "dns.dns-ai.ru", listOf("186.246.49.127", "185.251.90.181"))
    )

    fun getPresetByKey(key: String): WarpDnsPreset {
        return presets.firstOrNull { it.key == key } ?: presets[0]
    }

    /**
     * Checks if Android Private DNS (DoT / Частный DNS) is enabled in Android settings.
     * Uses multiple redundant detection methods:
     * 1. Settings.Global / Secure / System (private_dns_mode, private_dns_specifier)
     * 2. ConnectivityManager LinkProperties (privateDnsServerName & reflection methods)
     * 3. SystemProperties reflection (net.dns.private_provider)
     * 4. getprop execution fallback
     */
    fun isPrivateDnsActive(context: Context): Boolean {
        // 1. Check Settings.Global, Settings.System, Settings.Secure
        val cr = context.contentResolver
        for (table in listOf("global", "secure", "system")) {
            try {
                val mode = when (table) {
                    "secure" -> Settings.Secure.getString(cr, "private_dns_mode")
                    "system" -> Settings.System.getString(cr, "private_dns_mode")
                    else -> Settings.Global.getString(cr, "private_dns_mode")
                }
                if (!mode.isNullOrBlank() && mode.lowercase() != "off") {
                    Log.d(TAG, "Private DNS detected via $table: mode=$mode")
                    return true
                }
            } catch (_: Throwable) {}

            try {
                val specifier = when (table) {
                    "secure" -> Settings.Secure.getString(cr, "private_dns_specifier")
                    "system" -> Settings.System.getString(cr, "private_dns_specifier")
                    else -> Settings.Global.getString(cr, "private_dns_specifier")
                }
                if (!specifier.isNullOrBlank()) {
                    Log.d(TAG, "Private DNS detected via $table: specifier=$specifier")
                    return true
                }
            } catch (_: Throwable) {}
        }

        // 2. Check LinkProperties on all networks
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val networksToTest = mutableListOf<android.net.Network>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.activeNetwork?.let { networksToTest.add(it) }
                }
                networksToTest.addAll(cm.allNetworks)

                for (net in networksToTest) {
                    val lp = cm.getLinkProperties(net) ?: continue
                    if (checkLinkPropertiesForPrivateDns(lp)) {
                        Log.d(TAG, "Private DNS detected via LinkProperties on network: $net")
                        return true
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking LinkProperties for private DNS", e)
        }

        // 3. SystemProperties reflection
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            val provider = getMethod.invoke(null, "net.dns.private_provider") as? String
            if (!provider.isNullOrBlank()) {
                Log.d(TAG, "Private DNS detected via SystemProperties net.dns.private_provider: $provider")
                return true
            }
            val mode = getMethod.invoke(null, "net.dns.mode") as? String
            if (!mode.isNullOrBlank() && mode.lowercase() != "off") {
                Log.d(TAG, "Private DNS detected via SystemProperties net.dns.mode: $mode")
                return true
            }
        } catch (_: Throwable) {}

        // 4. getprop execution fallback
        try {
            val p = Runtime.getRuntime().exec("getprop net.dns.private_provider")
            val out = p.inputStream.bufferedReader().readText().trim()
            if (out.isNotEmpty()) {
                Log.d(TAG, "Private DNS detected via getprop: $out")
                return true
            }
        } catch (_: Throwable) {}

        try {
            val p = Runtime.getRuntime().exec("getprop net.dns.mode")
            val out = p.inputStream.bufferedReader().readText().trim()
            if (out.isNotEmpty() && out.lowercase() != "off") {
                Log.d(TAG, "Private DNS detected via getprop mode: $out")
                return true
            }
        } catch (_: Throwable) {}

        return false
    }

    /**
     * Attempts to find the configured Private DNS provider hostname if one was set.
     */
    fun getPrivateDnsServerName(context: Context): String? {
        val cr = context.contentResolver
        for (table in listOf("global", "secure", "system")) {
            try {
                val specifier = when (table) {
                    "secure" -> Settings.Secure.getString(cr, "private_dns_specifier")
                    "system" -> Settings.System.getString(cr, "private_dns_specifier")
                    else -> Settings.Global.getString(cr, "private_dns_specifier")
                }
                if (!specifier.isNullOrBlank()) return specifier.trim()
            } catch (_: Throwable) {}
        }

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val networksToTest = mutableListOf<android.net.Network>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.activeNetwork?.let { networksToTest.add(it) }
                }
                networksToTest.addAll(cm.allNetworks)

                for (net in networksToTest) {
                    val lp = cm.getLinkProperties(net) ?: continue
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val server = lp.privateDnsServerName
                        if (!server.isNullOrBlank()) return server.trim()
                    }
                }
            }
        } catch (_: Throwable) {}

        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            val provider = getMethod.invoke(null, "net.dns.private_provider") as? String
            if (!provider.isNullOrBlank()) return provider.trim()
        } catch (_: Throwable) {}

        try {
            val p = Runtime.getRuntime().exec("getprop net.dns.private_provider")
            val out = p.inputStream.bufferedReader().readText().trim()
            if (out.isNotEmpty()) return out
        } catch (_: Throwable) {}

        return null
    }

    private fun checkLinkPropertiesForPrivateDns(lp: android.net.LinkProperties): Boolean {
        // 1. Check privateDnsServerName (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                if (!lp.privateDnsServerName.isNullOrBlank()) return true
            } catch (_: Throwable) {}
        }

        // 2. Check isPrivateDnsActive() via reflection (AOSP LinkProperties method)
        try {
            val method = lp.javaClass.getMethod("isPrivateDnsActive")
            val active = method.invoke(lp) as? Boolean
            if (active == true) return true
        } catch (_: Throwable) {}

        // 3. Check getValidatedPrivateDnsServers() via reflection
        try {
            val method = lp.javaClass.getMethod("getValidatedPrivateDnsServers")
            val servers = method.invoke(lp) as? Collection<*>
            if (!servers.isNullOrEmpty()) return true
        } catch (_: Throwable) {}

        return false
    }

    /**
     * Resolves and returns DNS IP addresses for WARP tunnel setup.
     */
    suspend fun getDnsServers(context: Context): List<String> = withContext(Dispatchers.IO) {
        val key = context.getWarpDnsKey()
        val preset = getPresetByKey(key)

        // If the preset has a domain name, try to dynamically resolve it first, with static fallback
        if (preset.hostOrIps.contains(".ru") || preset.hostOrIps.contains(".one") || preset.hostOrIps.contains(".link")) {
            try {
                val resolved = InetAddress.getAllByName(preset.hostOrIps)
                val ips = resolved.mapNotNull {
                    if (it is Inet4Address) it.hostAddress else null
                }
                if (ips.isNotEmpty()) {
                    return@withContext ips
                }
            } catch (e: Exception) {
                // Use fallback
            }
        }

        preset.staticIps
    }
}
