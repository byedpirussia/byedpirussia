package io.github.dovecoteescapee.byedpi.vless

import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

data class VlessConfig(
    val id: String = UUID.randomUUID().toString(),
    val subscriptionUrl: String = "", // Пустая строка = вручную добавленный ключ, иначе URL подписки
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String, // uuid, password for trojan/hy2/ss
    val protocol: String = "vless", // vless, hysteria2, shadowsocks, vmess, trojan
    val flow: String = "",
    val encryption: String = "none", // cipher / security
    val transport: String = "tcp", // tcp, ws, grpc, httpupgrade, udp
    val security: String = "none", // none, tls, reality
    val sni: String = "",
    val pbk: String = "", // reality public key
    val sid: String = "", // reality short id
    val fp: String = "chrome", // fingerprint
    val path: String = "", // ws or http path
    val host: String = "", // ws or http host header
    val serviceName: String = "", // grpc serviceName
    val obfs: String = "", // hysteria2 obfs
    val obfsPassword: String = "", // hysteria2 obfs password
    val allowInsecure: Boolean = false, // hysteria2 / tls insecure
    val alterId: Int = 0, // vmess alterId
    val rawUri: String = "",
    // Proxy Chaining fields:
    val isChain: Boolean = false,
    val chainMode: String = "", // "warp_over_proxy" (WARP -> Proxy -> Site), "proxy_over_warp" (Proxy -> WARP -> Site)
    val chainHop1ConfigJson: String = "", // JSON serialized VlessConfig for hop
    val chainWarpConfigText: String = "" // AmneziaWG / WireGuard config text for WARP hop
) {
    companion object {
        private const val TAG = "VlessConfig"

        private fun decodeBase64Safe(input: String): String {
            val clean = input.trim().replace('-', '+').replace('_', '/')
            val padded = when (clean.length % 4) {
                2 -> "$clean=="
                3 -> "$clean="
                else -> clean
            }
            return try {
                String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }

        fun fromJson(obj: JSONObject): VlessConfig {
            return VlessConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                subscriptionUrl = obj.optString("subscriptionUrl", ""),
                name = obj.optString("name", "VLESS"),
                address = obj.optString("address", ""),
                port = obj.optInt("port", 443),
                uuid = obj.optString("uuid", ""),
                protocol = obj.optString("protocol", "vless"),
                flow = obj.optString("flow", ""),
                encryption = obj.optString("encryption", "none"),
                transport = obj.optString("transport", "tcp"),
                security = obj.optString("security", "none"),
                sni = obj.optString("sni", ""),
                pbk = obj.optString("pbk", ""),
                sid = obj.optString("sid", ""),
                fp = obj.optString("fp", "chrome"),
                path = obj.optString("path", ""),
                host = obj.optString("host", ""),
                serviceName = obj.optString("serviceName", ""),
                obfs = obj.optString("obfs", ""),
                obfsPassword = obj.optString("obfsPassword", ""),
                allowInsecure = obj.optBoolean("allowInsecure", false),
                alterId = obj.optInt("alterId", 0),
                rawUri = obj.optString("rawUri", ""),
                isChain = obj.optBoolean("isChain", false),
                chainMode = obj.optString("chainMode", ""),
                chainHop1ConfigJson = obj.optString("chainHop1ConfigJson", ""),
                chainWarpConfigText = obj.optString("chainWarpConfigText", "")
            )
        }

        fun parse(uriString: String, subscriptionUrl: String = ""): VlessConfig? {
            val trimmed = uriString.trim()
            val lower = trimmed.lowercase()

            return when {
                lower.startsWith("vless://") -> parseVless(trimmed, subscriptionUrl)
                lower.startsWith("hy2://") || lower.startsWith("hysteria2://") -> parseHysteria2(trimmed, subscriptionUrl)
                lower.startsWith("ss://") -> parseShadowsocks(trimmed, subscriptionUrl)
                lower.startsWith("trojan://") -> parseTrojan(trimmed, subscriptionUrl)
                lower.startsWith("vmess://") -> parseVmess(trimmed, subscriptionUrl)
                else -> null
            }
        }

        private fun parseVless(trimmed: String, subscriptionUrl: String): VlessConfig? {
            return try {
                val uri = Uri.parse(trimmed)
                val userInfo = uri.userInfo ?: ""
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                val fragment = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: host

                val flow = uri.getQueryParameter("flow") ?: ""
                val encryption = uri.getQueryParameter("encryption") ?: "none"
                val transport = uri.getQueryParameter("type") ?: "tcp"
                val security = uri.getQueryParameter("security") ?: "none"
                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: ""
                val pbk = uri.getQueryParameter("pbk") ?: ""
                val sid = uri.getQueryParameter("sid") ?: ""
                val fp = uri.getQueryParameter("fp") ?: "chrome"
                val path = uri.getQueryParameter("path") ?: ""
                val wsHost = uri.getQueryParameter("host") ?: ""
                val serviceName = uri.getQueryParameter("serviceName") ?: ""

                VlessConfig(
                    subscriptionUrl = subscriptionUrl,
                    name = fragment.ifBlank { "$host:$port" },
                    address = host,
                    port = port,
                    uuid = userInfo,
                    protocol = "vless",
                    flow = flow,
                    encryption = encryption,
                    transport = transport,
                    security = security,
                    sni = sni,
                    pbk = pbk,
                    sid = sid,
                    fp = fp,
                    path = path,
                    host = wsHost,
                    serviceName = serviceName,
                    rawUri = trimmed
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing VLESS", e)
                null
            }
        }

        private fun parseHysteria2(trimmed: String, subscriptionUrl: String): VlessConfig? {
            return try {
                val uri = Uri.parse(trimmed)
                val userInfo = uri.userInfo ?: ""
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                val fragment = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: host

                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
                val obfs = uri.getQueryParameter("obfs") ?: ""
                val obfsPassword = uri.getQueryParameter("obfs-password") ?: ""
                val insecure = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1"

                VlessConfig(
                    subscriptionUrl = subscriptionUrl,
                    name = fragment.ifBlank { "Hysteria2 - $host:$port" },
                    address = host,
                    port = port,
                    uuid = userInfo,
                    protocol = "hysteria2",
                    transport = "udp",
                    security = "tls",
                    sni = sni,
                    obfs = obfs,
                    obfsPassword = obfsPassword,
                    allowInsecure = insecure,
                    rawUri = trimmed
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Hysteria2", e)
                null
            }
        }

        private fun parseShadowsocks(trimmed: String, subscriptionUrl: String): VlessConfig? {
            return try {
                // ss://[base64(method:password)]@host:port#name
                // or ss://[base64(method:password@host:port)]#name
                val noScheme = trimmed.removePrefix("ss://")
                val fragmentIdx = noScheme.indexOf('#')
                val mainPart = if (fragmentIdx != -1) noScheme.substring(0, fragmentIdx) else noScheme
                val fragment = if (fragmentIdx != -1) {
                    URLDecoder.decode(noScheme.substring(fragmentIdx + 1), "UTF-8")
                } else ""

                if (mainPart.contains('@')) {
                    val atParts = mainPart.split('@', limit = 2)
                    val userBase64 = atParts[0]
                    val hostPort = atParts[1].split('?')[0] // remove query params if any
                    val decodedUser = decodeBase64Safe(userBase64)
                    val colonIdx = decodedUser.indexOf(':')
                    val method = if (colonIdx != -1) decodedUser.substring(0, colonIdx) else "aes-256-gcm"
                    val password = if (colonIdx != -1) decodedUser.substring(colonIdx + 1) else decodedUser

                    val lastColon = hostPort.lastIndexOf(':')
                    val host = if (lastColon != -1) hostPort.substring(0, lastColon).removePrefix("[").removeSuffix("]") else hostPort
                    val port = if (lastColon != -1) hostPort.substring(lastColon + 1).toIntOrNull() ?: 8388 else 8388

                    VlessConfig(
                        subscriptionUrl = subscriptionUrl,
                        name = fragment.ifBlank { "SS - $host:$port" },
                        address = host,
                        port = port,
                        uuid = password,
                        protocol = "shadowsocks",
                        encryption = method,
                        transport = "tcp",
                        security = "none",
                        rawUri = trimmed
                    )
                } else {
                    // Entire body base64 encoded
                    val decoded = decodeBase64Safe(mainPart.split('?')[0])
                    val atParts = decoded.split('@', limit = 2)
                    if (atParts.size < 2) return null
                    val creds = atParts[0].split(':', limit = 2)
                    val method = creds[0]
                    val password = creds.getOrElse(1) { "" }
                    val lastColon = atParts[1].lastIndexOf(':')
                    val host = if (lastColon != -1) atParts[1].substring(0, lastColon).removePrefix("[").removeSuffix("]") else atParts[1]
                    val port = if (lastColon != -1) atParts[1].substring(lastColon + 1).toIntOrNull() ?: 8388 else 8388

                    VlessConfig(
                        subscriptionUrl = subscriptionUrl,
                        name = fragment.ifBlank { "SS - $host:$port" },
                        address = host,
                        port = port,
                        uuid = password,
                        protocol = "shadowsocks",
                        encryption = method,
                        transport = "tcp",
                        security = "none",
                        rawUri = trimmed
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Shadowsocks", e)
                null
            }
        }

        private fun parseTrojan(trimmed: String, subscriptionUrl: String): VlessConfig? {
            return try {
                val uri = Uri.parse(trimmed)
                val password = uri.userInfo ?: ""
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                val fragment = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: host

                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: host
                val transport = uri.getQueryParameter("type") ?: "tcp"
                val security = uri.getQueryParameter("security") ?: "tls"
                val path = uri.getQueryParameter("path") ?: ""
                val wsHost = uri.getQueryParameter("host") ?: ""
                val serviceName = uri.getQueryParameter("serviceName") ?: ""
                val allowInsecure = uri.getQueryParameter("allowInsecure") == "1"

                VlessConfig(
                    subscriptionUrl = subscriptionUrl,
                    name = fragment.ifBlank { "Trojan - $host:$port" },
                    address = host,
                    port = port,
                    uuid = password,
                    protocol = "trojan",
                    transport = transport,
                    security = security,
                    sni = sni,
                    path = path,
                    host = wsHost,
                    serviceName = serviceName,
                    allowInsecure = allowInsecure,
                    rawUri = trimmed
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing Trojan", e)
                null
            }
        }

        private fun parseVmess(trimmed: String, subscriptionUrl: String): VlessConfig? {
            return try {
                val b64 = trimmed.removePrefix("vmess://").trim()
                val jsonStr = decodeBase64Safe(b64)
                val json = JSONObject(jsonStr)

                val name = json.optString("ps", "")
                val address = json.optString("add", "")
                val port = json.optInt("port", 443)
                val id = json.optString("id", "")
                val aid = json.optInt("aid", 0)
                val net = json.optString("net", "tcp")
                val tls = json.optString("tls", "none")
                val sni = json.optString("sni", "")
                val host = json.optString("host", "")
                val path = json.optString("path", "")
                val scy = json.optString("scy", "auto")

                if (address.isBlank() || id.isBlank()) return null

                VlessConfig(
                    subscriptionUrl = subscriptionUrl,
                    name = name.ifBlank { "VMess - $address:$port" },
                    address = address,
                    port = port,
                    uuid = id,
                    protocol = "vmess",
                    encryption = scy,
                    transport = net,
                    security = tls,
                    sni = sni,
                    host = host,
                    path = path,
                    alterId = aid,
                    rawUri = trimmed
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing VMess", e)
                null
            }
        }
    }

    fun toXrayConfigJson(localSocksPort: Int = 10808): String {
        val root = JSONObject()

        // Log config
        val log = JSONObject()
        log.put("loglevel", "warning")
        root.put("log", log)

        // Inbounds - SOCKS5 inbound on 127.0.0.1:localSocksPort
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", localSocksPort)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
            })
            put("sniffing", JSONObject().apply {
                put("enabled", true)
                put("destOverride", JSONArray().apply {
                    put("http")
                    put("tls")
                    put("quic")
                })
            })
        }
        inbounds.put(socksInbound)
        root.put("inbounds", inbounds)

        // Outbounds
        val outbounds = JSONArray()

        if (isChain) {
            val hopConfig = if (chainHop1ConfigJson.isNotBlank()) {
                try {
                    val obj = JSONObject(chainHop1ConfigJson)
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
                        rawUri = obj.optString("rawUri")
                    )
                } catch (e: Exception) {
                    null
                }
            } else null

            val warpOutbound = createWarpOutbound(tag = "warp-out", proxyTag = null)

            if (chainMode == "warp_over_proxy") {
                // WARP traffic routes through the Proxy:
                // socks-in -> warp-out (proxySettings: tag = "proxy-out") -> proxy-out -> Internet
                // warp-out is the primary outbound tagged "proxy"
                val proxyOut = hopConfig?.createOutbound(tag = "hop-proxy", proxyTag = null)
                    ?: createOutbound(tag = "hop-proxy", proxyTag = null)

                val warpMain = createWarpOutbound(tag = "proxy", proxyTag = "hop-proxy")
                outbounds.put(warpMain)
                outbounds.put(proxyOut)
            } else {
                // proxy_over_warp:
                // socks-in -> proxy-out (proxySettings: tag = "warp-hop") -> warp-hop -> Internet
                val proxyMain = (hopConfig ?: this).createOutbound(tag = "proxy", proxyTag = "warp-hop")
                val warpHop = createWarpOutbound(tag = "warp-hop", proxyTag = null)
                outbounds.put(proxyMain)
                outbounds.put(warpHop)
            }
        } else {
            val proxyOutbound = createOutbound(tag = "proxy", proxyTag = null)
            outbounds.put(proxyOutbound)
        }

        // Direct outbound
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }
        outbounds.put(directOutbound)

        // Block outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // DNS configuration
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("https://dns.google/dns-query")
            })
        }
        root.put("dns", dns)

        // Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "AsIs")
            val rules = JSONArray()

            // DNS rule
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("port", "53")
            })

            // Default route to proxy
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
            })

            put("rules", rules)
        }
        root.put("routing", routing)

        return root.toString(2)
    }

    private fun createWarpOutbound(tag: String, proxyTag: String?): JSONObject {
        // Parse WARP config text or use default
        val configText = chainWarpConfigText.ifBlank {
            io.github.dovecoteescapee.byedpi.warp.WarpConfigManager.currentConfig.value
        }
        val matchPriv = Regex("""(?m)^\s*PrivateKey\s*=\s*(.+)$""").find(configText)?.groupValues?.get(1)?.trim()
            ?: "Wli/uh1ka24/qHSysIhhvdIqOKz58Gid0cEwlI0Nfhc="
        val matchPub = Regex("""(?m)^\s*PublicKey\s*=\s*(.+)$""").find(configText)?.groupValues?.get(1)?.trim()
            ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        val matchEndpoint = Regex("""(?m)^\s*Endpoint\s*=\s*(.+)$""").find(configText)?.groupValues?.get(1)?.trim()
            ?: "188.114.98.8:854"

        val peerObj = JSONObject().apply {
            put("publicKey", matchPub)
            put("endpoint", matchEndpoint)
        }

        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", matchPriv)
                put("address", JSONArray().apply {
                    put("172.16.0.2/32")
                    put("2606:4700:110:8700:31d8:595c:36c3:8014/128")
                })
                put("peers", JSONArray().apply {
                    put(peerObj)
                })
            })
            if (proxyTag != null) {
                put("streamSettings", JSONObject().apply {
                    put("sockopt", JSONObject().apply {
                        put("dialerProxy", proxyTag)
                    })
                })
            }
        }
    }

    fun createOutbound(tag: String, proxyTag: String?): JSONObject {
        val proxyOutbound = JSONObject().apply {
            put("tag", tag)

            when (protocol.lowercase()) {
                "hysteria2" -> {
                    put("protocol", "hysteria2")
                    val serversArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", address)
                            put("port", port)
                            put("password", uuid)
                        })
                    }
                    put("settings", JSONObject().apply {
                        put("servers", serversArray)
                    })

                    val streamSettings = JSONObject().apply {
                        put("network", "udp")
                        put("security", "tls")
                        val tlsSettings = JSONObject().apply {
                            val serverName = if (sni.isNotBlank()) sni else address
                            put("serverName", serverName)
                            if (allowInsecure) {
                                put("allowInsecure", true)
                            }
                        }
                        put("tlsSettings", tlsSettings)

                        if (obfs.isNotBlank()) {
                            put("hy2Settings", JSONObject().apply {
                                put("password", obfsPassword)
                                put("type", obfs)
                            })
                        }
                    }
                    put("streamSettings", streamSettings)
                }

                "shadowsocks" -> {
                    put("protocol", "shadowsocks")
                    val serversArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", address)
                            put("port", port)
                            put("method", if (encryption.isNotBlank() && encryption != "none") encryption else "aes-256-gcm")
                            put("password", uuid)
                            put("ota", false)
                        })
                    }
                    put("settings", JSONObject().apply {
                        put("servers", serversArray)
                    })
                }

                "trojan" -> {
                    put("protocol", "trojan")
                    val serversArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", address)
                            put("port", port)
                            put("password", uuid)
                        })
                    }
                    put("settings", JSONObject().apply {
                        put("servers", serversArray)
                    })

                    val streamSettings = JSONObject().apply {
                        put("network", transport)
                        put("security", "tls")
                        val tlsSettings = JSONObject().apply {
                            val serverName = if (sni.isNotBlank()) sni else address
                            put("serverName", serverName)
                            if (allowInsecure) put("allowInsecure", true)
                            if (fp.isNotBlank()) put("fingerprint", fp)
                        }
                        put("tlsSettings", tlsSettings)

                        if (transport.equals("ws", ignoreCase = true)) {
                            put("wsSettings", JSONObject().apply {
                                if (path.isNotBlank()) put("path", path)
                                if (host.isNotBlank()) {
                                    put("headers", JSONObject().apply { put("Host", host) })
                                }
                            })
                        } else if (transport.equals("grpc", ignoreCase = true)) {
                            put("grpcSettings", JSONObject().apply {
                                if (serviceName.isNotBlank()) put("serviceName", serviceName)
                                put("multiMode", true)
                            })
                        }
                    }
                    put("streamSettings", streamSettings)
                }

                "vmess" -> {
                    put("protocol", "vmess")
                    val vnextArray = JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", address)
                            put("port", port)
                            val users = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", uuid)
                                    put("alterId", alterId)
                                    put("security", if (encryption.isNotBlank() && encryption != "none") encryption else "auto")
                                })
                            }
                            put("users", users)
                        })
                    }
                    put("settings", JSONObject().apply {
                        put("vnext", vnextArray)
                    })

                    val streamSettings = JSONObject().apply {
                        put("network", transport)
                        if (security.equals("tls", ignoreCase = true)) {
                            put("security", "tls")
                            val tlsSettings = JSONObject().apply {
                                val serverName = if (sni.isNotBlank()) sni else if (host.isNotBlank()) host else address
                                put("serverName", serverName)
                                if (fp.isNotBlank()) put("fingerprint", fp)
                            }
                            put("tlsSettings", tlsSettings)
                        } else {
                            put("security", "none")
                        }

                        if (transport.equals("ws", ignoreCase = true)) {
                            put("wsSettings", JSONObject().apply {
                                if (path.isNotBlank()) put("path", path)
                                if (host.isNotBlank()) {
                                    put("headers", JSONObject().apply { put("Host", host) })
                                }
                            })
                        } else if (transport.equals("grpc", ignoreCase = true)) {
                            put("grpcSettings", JSONObject().apply {
                                if (serviceName.isNotBlank()) put("serviceName", serviceName)
                                put("multiMode", true)
                            })
                        }
                    }
                    put("streamSettings", streamSettings)
                }

                else -> { // vless
                    put("protocol", "vless")
                    val vnextArray = JSONArray()
                    val vnextItem = JSONObject().apply {
                        put("address", address)
                        put("port", port)
                        val users = JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", uuid)
                                put("encryption", encryption)
                                if (flow.isNotBlank()) {
                                    put("flow", flow)
                                }
                            })
                        }
                        put("users", users)
                    }
                    vnextArray.put(vnextItem)
                    put("settings", JSONObject().apply {
                        put("vnext", vnextArray)
                    })

                    // StreamSettings
                    val streamSettings = JSONObject()
                    streamSettings.put("network", transport)

                    val effectiveSecurity = when {
                        security.equals("reality", ignoreCase = true) || pbk.isNotBlank() -> "reality"
                        security.equals("tls", ignoreCase = true) || sni.isNotBlank() || port == 443 -> "tls"
                        else -> if (security.equals("none", ignoreCase = true)) "tls" else security
                    }

                    if (effectiveSecurity.equals("reality", ignoreCase = true)) {
                        streamSettings.put("security", "reality")
                        val realitySettings = JSONObject().apply {
                            if (sni.isNotBlank()) put("serverName", sni)
                            if (fp.isNotBlank()) put("fingerprint", fp)
                            if (pbk.isNotBlank()) put("publicKey", pbk)
                            if (sid.isNotBlank()) put("shortId", sid)
                            put("show", false)
                        }
                        streamSettings.put("realitySettings", realitySettings)
                    } else if (effectiveSecurity.equals("tls", ignoreCase = true)) {
                        streamSettings.put("security", "tls")
                        val tlsSettings = JSONObject().apply {
                            val serverName = if (sni.isNotBlank()) sni else if (host.isNotBlank()) host else address
                            put("serverName", serverName)
                            if (fp.isNotBlank()) put("fingerprint", fp)
                        }
                        streamSettings.put("tlsSettings", tlsSettings)
                    } else {
                        streamSettings.put("security", "none")
                    }

                    // Transport details
                    when (transport.lowercase()) {
                        "ws" -> {
                            val wsSettings = JSONObject().apply {
                                if (path.isNotBlank()) put("path", path)
                                if (host.isNotBlank()) {
                                    put("headers", JSONObject().apply {
                                        put("Host", host)
                                    })
                                }
                            }
                            streamSettings.put("wsSettings", wsSettings)
                        }
                        "grpc" -> {
                            val grpcSettings = JSONObject().apply {
                                if (serviceName.isNotBlank()) put("serviceName", serviceName)
                                put("multiMode", true)
                            }
                            streamSettings.put("grpcSettings", grpcSettings)
                        }
                        "httpupgrade" -> {
                            val httpUpgradeSettings = JSONObject().apply {
                                if (path.isNotBlank()) put("path", path)
                                if (host.isNotBlank()) put("host", host)
                            }
                            streamSettings.put("httpupgradeSettings", httpUpgradeSettings)
                        }
                    }

                    put("streamSettings", streamSettings)
                }
            }

            if (proxyTag != null) {
                var streamSettings = optJSONObject("streamSettings")
                if (streamSettings == null) {
                    streamSettings = JSONObject()
                    put("streamSettings", streamSettings)
                }
                var sockopt = streamSettings.optJSONObject("sockopt")
                if (sockopt == null) {
                    sockopt = JSONObject()
                    streamSettings.put("sockopt", sockopt)
                }
                sockopt.put("dialerProxy", proxyTag)
            }
        }
        return proxyOutbound
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("subscriptionUrl", subscriptionUrl)
            put("name", name)
            put("address", address)
            put("port", port)
            put("uuid", uuid)
            put("protocol", protocol)
            put("flow", flow)
            put("encryption", encryption)
            put("transport", transport)
            put("security", security)
            put("sni", sni)
            put("pbk", pbk)
            put("sid", sid)
            put("fp", fp)
            put("path", path)
            put("host", host)
            put("serviceName", serviceName)
            put("obfs", obfs)
            put("obfsPassword", obfsPassword)
            put("allowInsecure", allowInsecure)
            put("alterId", alterId)
            put("rawUri", rawUri)
            put("isChain", isChain)
            put("chainMode", chainMode)
            put("chainHop1ConfigJson", chainHop1ConfigJson)
            put("chainWarpConfigText", chainWarpConfigText)
        }
    }
}
