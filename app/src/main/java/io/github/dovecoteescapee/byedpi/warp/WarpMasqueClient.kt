package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.TProxyService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный MASQUE (RFC 9484, CONNECT-IP over HTTP/3 & HTTP/2) клиент Cloudflare WARP
 * на базе нативного движка Hiddify Core (`hiddify-core.aar`) и локального hev-socks5-tunnel (TProxy).
 * Инкапсулирует трафик устройства в защищенные HTTPS/HTTP3 сессии (порт 443) к Anycast-серверам Cloudflare.
 */
object WarpMasqueClient {

    private const val TAG = "WarpMasqueClient"
    const val LOCAL_SOCKS_PORT = 10855

    private val isRunning = AtomicBoolean(false)

    data class MasqueConfig(
        val privateKey: String,
        val peerPublicKey: String,
        val clientIpv4: String = "172.16.0.2",
        val clientIpv6: String = "2606:4700:110:8700:31d8:595c:36c3:8014",
        val endpointHost: String = "162.159.198.2",
        val endpointPort: Int = 443,
        val sni: String = "4pda.to",
        val path: String = "/cdn-cgi/warp/masque",
        val rawYaml: String = ""
    )

    /**
     * Запускает нативный MASQUE-клиент через Hiddify Core и связывает TUN интерфейс с локальным SOCKS5.
     */
    suspend fun start(
        vpnService: VpnService,
        tunPfd: ParcelFileDescriptor,
        config: MasqueConfig
    ) = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "MASQUE client already running, restarting...")
            stop()
        }

        Log.i(TAG, "Инициализация нативного MASQUE-клиента Hiddify Core (${config.endpointHost}:${config.endpointPort})...")

        // 1. Генерация sing-box JSON конфигурации для Hiddify Core с нативным outbound/masque
        val singBoxConfigJson = generateMasqueConfigJson(config, LOCAL_SOCKS_PORT)
        Log.d(TAG, "Generated Hiddify MASQUE config:\n$singBoxConfigJson")

        // 2. Запуск Hiddify CommandServer
        WarpMasqueHiddify.start(vpnService, singBoxConfigJson)

        // Даем ядру время привязать сокет SOCKS5
        delay(350)

        // 3. Запуск hev-socks5-tunnel (TProxy) для проксирования всего трафика TUN интерфейса в SOCKS5
        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: 127.0.0.1
        |   port: $LOCAL_SOCKS_PORT
        |   udp: udp
        """.trimMargin("| ")

        val configFile = File(vpnService.cacheDir, "warp_masque_tun2socks.tmp").apply {
            writeText(tun2socksConfig)
        }

        TProxyService.TProxyStartService(configFile.absolutePath, tunPfd.fd)
        Log.i(TAG, "hev-socks5-tunnel успешно подключен к локальному MASQUE Hiddify порту $LOCAL_SOCKS_PORT")

        isRunning.set(true)
    }

    /**
     * Останавливает MASQUE-клиент и освобождает ресурсы hev-socks5-tunnel и Hiddify Core.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "Остановка MASQUE-клиента...")
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки TProxyService: ${e.message}")
        }

        try {
            WarpMasqueHiddify.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки WarpMasqueHiddify: ${e.message}")
        }
        Log.i(TAG, "MASQUE-клиент остановлен")
    }

    fun isClientRunning(): Boolean = isRunning.get()

    private fun resolveEndpointIp(host: String): String {
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return host
        return try {
            InetAddress.getByName(host).hostAddress ?: "162.159.192.1"
        } catch (_: Exception) {
            "162.159.192.1"
        }
    }

    /**
     * Создает sing-box JSON конфигурацию для Hiddify Core с:
     * - SOCKS5 Inbound на 127.0.0.1:localPort
     * - Нативным MASQUE Outbound к Cloudflare edge (port 443)
     * - Встроенным DNS резолвером и правилами маршрутизации
     */
    private fun generateMasqueConfigJson(config: MasqueConfig, localPort: Int): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("level", "info")
        })

        // DNS
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("address", "https://1.1.1.1/dns-query")
                    put("detour", "masque-out")
                })
                put(JSONObject().apply {
                    put("tag", "dns-direct")
                    put("address", "local")
                    put("detour", "direct")
                })
            })
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("outbound", JSONArray().apply { put("any") })
                    put("server", "dns-direct")
                })
            })
        })

        // Inbounds: SOCKS5 на локальном порту
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("listen_port", localPort)
                put("sniff", true)
            })
        })

        val resolvedIp = resolveEndpointIp(config.endpointHost)
        val effectivePort = if (config.endpointPort <= 0) 443 else config.endpointPort
        val endpointStr = "$resolvedIp:$effectivePort"

        // Outbounds
        root.put("outbounds", JSONArray().apply {
            // Нативный MASQUE Outbound (RFC 9484 CONNECT-IP over HTTP/3 & HTTP/2)
            put(JSONObject().apply {
                put("type", "masque")
                put("tag", "masque-out")
                put("server", resolvedIp)
                put("server_port", effectivePort)
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", config.sni.ifBlank { "engage.cloudflareclient.com" })
                })
                put("device", JSONObject().apply {
                    put("private_key", config.privateKey)
                    if (config.peerPublicKey.isNotBlank()) {
                        put("endpoint_pub_key", config.peerPublicKey)
                    }
                    put("ipv4", "${config.clientIpv4}/32")
                    put("ipv6", "${config.clientIpv6}/128")
                    put("endpoint_v4", endpointStr)
                    put("endpoint_h2_v4", endpointStr)
                })
            })

            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })

            put(JSONObject().apply {
                put("type", "dns")
                put("tag", "dns-out")
            })

            put(JSONObject().apply {
                put("type", "block")
                put("tag", "block")
            })
        })

        // Route
        root.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            put("final", "masque-out")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("outbound", "dns-out")
                })
                put(JSONObject().apply {
                    put("ip_is_private", true)
                    put("outbound", "direct")
                })
                val excludedDomains = JSONArray().apply {
                    io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.EXCLUDED_RUSSIAN_DOMAINS.forEach {
                        put(it)
                    }
                }
                put(JSONObject().apply {
                    put("domain_suffix", excludedDomains)
                    put("outbound", "direct")
                })
            })
        })

        return root.toString(2)
    }

    /**
     * Быстрая проверка доступности Cloudflare MASQUE эндпоинта (HTTPS/443).
     */
    suspend fun pingMasqueEndpoint(host: String = "engage.cloudflareclient.com", port: Int = 443, timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val duration = System.currentTimeMillis() - start
                return@withContext duration
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
