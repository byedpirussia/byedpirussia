package io.github.dovecoteescapee.byedpi.warp

import android.util.Base64
import android.util.Log

object AwgUapiFormatter {
    private const val TAG = "AwgUapiFormatter"

    data class ParsedAwgConfig(
        val privateKeyHex: String,
        val listenPort: Int? = null,
        val addresses: List<String> = emptyList(),
        val dnsServers: List<String> = emptyList(),
        val mtu: Int = 1280,
        // AWG Obfuscation params
        val jc: Int? = null,
        val jmin: Int? = null,
        val jmax: Int? = null,
        val s1: Int? = null,
        val s2: Int? = null,
        val s3: Int? = null,
        val s4: Int? = null,
        val h1: String? = null,
        val h2: String? = null,
        val h3: String? = null,
        val h4: String? = null,
        val i1: String? = null,
        val i2: String? = null,
        val i3: String? = null,
        val i4: String? = null,
        val i5: String? = null,
        // Peer
        val peerPublicKeyHex: String? = null,
        val peerPresharedKeyHex: String? = null,
        val peerEndpoint: String? = null,
        val peerPersistentKeepalive: Int? = null,
        val allowedIps: List<String> = emptyList()
    )

    private fun base64ToHex(b64: String): String {
        val trimmed = b64.trim()
        val decoded = Base64.decode(trimmed, Base64.DEFAULT)
        val sb = StringBuilder(decoded.size * 2)
        for (b in decoded) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun parseConfig(rawConfig: String): ParsedAwgConfig {
        var privateKeyHex = ""
        var listenPort: Int? = null
        val addresses = mutableListOf<String>()
        val dnsServers = mutableListOf<String>()
        var mtu = 1280

        var jc: Int? = null
        var jmin: Int? = null
        var jmax: Int? = null
        var s1: Int? = null
        var s2: Int? = null
        var s3: Int? = null
        var s4: Int? = null
        var h1: String? = null
        var h2: String? = null
        var h3: String? = null
        var h4: String? = null
        var i1: String? = null
        var i2: String? = null
        var i3: String? = null
        var i4: String? = null
        var i5: String? = null

        var peerPublicKeyHex: String? = null
        var peerPresharedKeyHex: String? = null
        var peerEndpoint: String? = null
        var peerPersistentKeepalive: Int? = null
        val allowedIps = mutableListOf<String>()

        var currentSection = ""

        rawConfig.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                return@forEach
            }

            val eqIdx = line.indexOf('=')
            if (eqIdx == -1) return@forEach

            val key = line.substring(0, eqIdx).trim().lowercase()
            val value = line.substring(eqIdx + 1).trim()

            when (currentSection) {
                "interface" -> {
                    when (key) {
                        "privatekey" -> {
                            try {
                                privateKeyHex = base64ToHex(value)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode private key", e)
                            }
                        }
                        "address" -> {
                            addresses.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        }
                        "dns" -> {
                            dnsServers.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        }
                        "mtu" -> mtu = value.toIntOrNull() ?: 1280
                        "listenport" -> listenPort = value.toIntOrNull()
                        "jc" -> jc = value.toIntOrNull()
                        "jmin" -> jmin = value.toIntOrNull()
                        "jmax" -> jmax = value.toIntOrNull()
                        "s1" -> s1 = value.toIntOrNull()
                        "s2" -> s2 = value.toIntOrNull()
                        "s3" -> s3 = value.toIntOrNull()
                        "s4" -> s4 = value.toIntOrNull()
                        "h1" -> h1 = value
                        "h2" -> h2 = value
                        "h3" -> h3 = value
                        "h4" -> h4 = value
                        "i1" -> i1 = value
                        "i2" -> i2 = value
                        "i3" -> i3 = value
                        "i4" -> i4 = value
                        "i5" -> i5 = value
                    }
                }
                "peer" -> {
                    when (key) {
                        "publickey" -> {
                            try {
                                peerPublicKeyHex = base64ToHex(value)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode public key", e)
                            }
                        }
                        "presharedkey" -> {
                            try {
                                peerPresharedKeyHex = base64ToHex(value)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode preshared key", e)
                            }
                        }
                        "endpoint" -> peerEndpoint = value
                        "allowedips" -> {
                            allowedIps.addAll(value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        }
                        "persistentkeepalive" -> peerPersistentKeepalive = value.toIntOrNull()
                    }
                }
            }
        }

        return ParsedAwgConfig(
            privateKeyHex = privateKeyHex,
            listenPort = listenPort,
            addresses = addresses,
            dnsServers = dnsServers,
            mtu = mtu,
            jc = jc,
            jmin = jmin,
            jmax = jmax,
            s1 = s1,
            s2 = s2,
            s3 = s3,
            s4 = s4,
            h1 = h1,
            h2 = h2,
            h3 = h3,
            h4 = h4,
            i1 = i1,
            i2 = i2,
            i3 = i3,
            i4 = i4,
            i5 = i5,
            peerPublicKeyHex = peerPublicKeyHex,
            peerPresharedKeyHex = peerPresharedKeyHex,
            peerEndpoint = peerEndpoint,
            peerPersistentKeepalive = peerPersistentKeepalive,
            allowedIps = allowedIps
        )
    }

    /**
     * Converts a ParsedAwgConfig to the exact UAPI format expected by libwg-go.so (IpcSet).
     */
    fun toUapi(config: ParsedAwgConfig): String {
        val sb = StringBuilder()

        // Device configuration
        sb.append("private_key=").append(config.privateKeyHex).append('\n')
        config.listenPort?.let { sb.append("listen_port=").append(it).append('\n') }

        // AWG Obfuscation parameters
        config.jc?.let { sb.append("jc=").append(it).append('\n') }
        config.jmin?.let { sb.append("jmin=").append(it).append('\n') }
        config.jmax?.let { sb.append("jmax=").append(it).append('\n') }
        config.s1?.let { sb.append("s1=").append(it).append('\n') }
        config.s2?.let { sb.append("s2=").append(it).append('\n') }
        config.s3?.let { sb.append("s3=").append(it).append('\n') }
        config.s4?.let { sb.append("s4=").append(it).append('\n') }
        config.h1?.let { sb.append("h1=").append(it).append('\n') }
        config.h2?.let { sb.append("h2=").append(it).append('\n') }
        config.h3?.let { sb.append("h3=").append(it).append('\n') }
        config.h4?.let { sb.append("h4=").append(it).append('\n') }
        config.i1?.let { sb.append("i1=").append(it).append('\n') }
        config.i2?.let { sb.append("i2=").append(it).append('\n') }
        config.i3?.let { sb.append("i3=").append(it).append('\n') }
        config.i4?.let { sb.append("i4=").append(it).append('\n') }
        config.i5?.let { sb.append("i5=").append(it).append('\n') }

        // Peer configuration
        if (!config.peerPublicKeyHex.isNullOrEmpty()) {
            sb.append("replace_peers=true\n")
            sb.append("public_key=").append(config.peerPublicKeyHex).append('\n')

            config.peerPresharedKeyHex?.let {
                sb.append("preshared_key=").append(it).append('\n')
            }

            config.peerEndpoint?.let {
                sb.append("endpoint=").append(it).append('\n')
            }

            config.peerPersistentKeepalive?.let {
                sb.append("persistent_keepalive_interval=").append(it).append('\n')
            }

            for (aip in config.allowedIps) {
                sb.append("allowed_ip=").append(aip).append('\n')
            }
        }

        return sb.toString()
    }
}
