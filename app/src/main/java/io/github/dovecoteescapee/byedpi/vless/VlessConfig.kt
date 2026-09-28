package io.github.dovecoteescapee.byedpi.vless

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

data class VlessConfig(
    val id: String = UUID.randomUUID().toString(),
    val subscriptionUrl: String = "", // Пустая строка = вручную добавленный ключ, иначе URL подписки
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val protocol: String = "vless", // vless, hysteria2
    val flow: String = "",
    val encryption: String = "none",
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
    val rawUri: String = ""
) {
    companion object {
        fun parse(uriString: String, subscriptionUrl: String = ""): VlessConfig? {
            val trimmed = uriString.trim()
            val isVless = trimmed.startsWith("vless://", ignoreCase = true)
            val isHy2 = trimmed.startsWith("hy2://", ignoreCase = true) || trimmed.startsWith("hysteria2://", ignoreCase = true)
            if (!isVless && !isHy2) return null

            return try {
                val uri = Uri.parse(trimmed)
                val userInfo = uri.userInfo ?: ""
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                val fragment = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: host

                if (isHy2) {
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
                } else {
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
                }
            } catch (e: Exception) {
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

        // Main proxy outbound
        val proxyOutbound = JSONObject().apply {
            put("tag", "proxy")

            if (protocol.equals("hysteria2", ignoreCase = true)) {
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
            } else {
                put("protocol", "vless")

                // VLESS settings
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
        outbounds.put(proxyOutbound)

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
}
