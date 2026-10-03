package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
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
     */
    fun isPrivateDnsActive(context: Context): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val activeNetwork = cm?.activeNetwork
                if (activeNetwork != null) {
                    val lp = cm.getLinkProperties(activeNetwork)
                    if (lp != null) {
                        // In Android 9+, privateDnsServerName is set when Private DNS is active
                        if (!lp.privateDnsServerName.isNullOrBlank()) {
                            return true
                        }
                    }
                }
            }

            // Also check Global Settings for private_dns_mode
            val cr = context.contentResolver
            val mode = Settings.Global.getString(cr, "private_dns_mode")
            // Modes can be "off", "opportunistic" (auto), or "hostname" (strict)
            if (mode != null && mode != "off") {
                val specifier = Settings.Global.getString(cr, "private_dns_specifier")
                if (mode == "hostname" || !specifier.isNullOrBlank()) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore security or permission exceptions and fallback
        }
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
