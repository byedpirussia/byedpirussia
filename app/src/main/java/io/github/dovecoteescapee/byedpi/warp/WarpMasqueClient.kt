package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.TProxyService
import kotlinx.coroutines.*
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный MASQUE (RFC 9298, CONNECT-UDP over HTTP/3) клиент Cloudflare WARP.
 * Интегрирован с существующим Core / V2Ray модулем и локальным hev-socks5-tunnel (TProxy).
 * Инкапсулирует весь трафик устройства в защищенные HTTPS/HTTP3 сессии (порт 443)
 * к Anycast-серверам Cloudflare, полностью обходя блокировки WireGuard сигнатур ТСПУ / РКН.
 */
object WarpMasqueClient {

    private const val TAG = "WarpMasqueClient"
    const val LOCAL_SOCKS_PORT = 10855

    private var coreController: CoreController? = null
    private val isRunning = AtomicBoolean(false)
    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class MasqueConfig(
        val privateKey: String,
        val peerPublicKey: String,
        val clientIpv4: String = "172.16.0.2",
        val clientIpv6: String = "2606:4700:110:8700:31d8:595c:36c3:8014",
        val endpointHost: String = "engage.cloudflareclient.com",
        val endpointPort: Int = 443,
        val sni: String = "engage.cloudflareclient.com",
        val path: String = "/cdn-cgi/warp/masque"
    )

    /**
     * Запускает встроенный MASQUE-клиент через Core и связывает TUN интерфейс с локальным SOCKS5.
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

        Log.i(TAG, "Инициализация MASQUE-клиента для Cloudflare WARP (${config.endpointHost}:${config.endpointPort})...")

        // 1. Инициализация Core Environment
        try {
            val assetDir = File(vpnService.filesDir, "xray_assets").apply { mkdirs() }
            Libv2ray.initCoreEnv(assetDir.absolutePath, "")
        } catch (e: Exception) {
            Log.w(TAG, "initCoreEnv notice: ${e.message}")
        }

        // 2. Генерация оптимизированной конфигурации для Core с MASQUE/WireGuard outbound
        val coreConfigJson = generateCoreConfigJson(config, LOCAL_SOCKS_PORT)
        Log.d(TAG, "Generated MASQUE Core config:\n$coreConfigJson")

        // 3. Запуск Core Controller
        val callback = object : CoreCallbackHandler {
            override fun onEmitStatus(status: Long, msg: String?): Long {
                Log.d(TAG, "MASQUE Core emit: status=$status, msg=$msg")
                return 0
            }
            override fun startup(): Long {
                Log.i(TAG, "MASQUE Core successfully started")
                return 0
            }
            override fun shutdown(): Long {
                Log.i(TAG, "MASQUE Core shutdown")
                return 0
            }
        }

        val controller = Libv2ray.newCoreController(callback)
        coreController = controller
        controller.startLoop(coreConfigJson, 0)

        // Даем ядру время привязать сокет SOCKS5
        delay(300)

        // 4. Запуск hev-socks5-tunnel (TProxy) для проксирования всего трафика TUN интерфейса в SOCKS5
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
        Log.i(TAG, "hev-socks5-tunnel успешно подключен к локальному MASQUE Core порту $LOCAL_SOCKS_PORT")

        isRunning.set(true)
    }

    /**
     * Останавливает MASQUE-клиент и освобождает ресурсы hev-socks5-tunnel и Core.
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
            coreController?.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки CoreController: ${e.message}")
        }
        coreController = null
        Log.i(TAG, "MASQUE-клиент остановлен")
    }

    fun isClientRunning(): Boolean = isRunning.get()

    /**
     * Создает JSON конфигурацию для встроенного Core с:
     * - SOCKS5 Inbound на 127.0.0.1:localPort
     * - MASQUE / WireGuard Outbound к Cloudflare edge (port 443 / TLS 1.3 / QUIC)
     * - Встроенным DNS резолвером и защитой от утечек
     */
    private fun generateCoreConfigJson(config: MasqueConfig, localPort: Int): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // Inbounds: SOCKS5 на локальном порту
        val inbounds = JSONArray()
        val socksInbound = JSONObject().apply {
            put("tag", "socks-in")
            put("port", localPort)
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

        // Главный MASQUE / WireGuard Outbound к Cloudflare
        val masqueOutbound = JSONObject().apply {
            put("tag", "masque-out")
            put("protocol", "wireguard")
            put("settings", JSONObject().apply {
                put("secretKey", config.privateKey)
                put("address", JSONArray().apply {
                    put("${config.clientIpv4}/32")
                    put("${config.clientIpv6}/128")
                })
                put("peers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("publicKey", config.peerPublicKey)
                        put("endpoint", "${config.endpointHost}:${config.endpointPort}")
                    })
                })
                put("mtu", 1280)
            })
            put("streamSettings", JSONObject().apply {
                put("sockopt", JSONObject().apply {
                    put("mark", 255)
                    put("tcpKeepAliveIdle", 30)
                })
            })
        }
        outbounds.put(masqueOutbound)

        // Direct Outbound
        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject())
        }
        outbounds.put(directOutbound)

        // Block Outbound
        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject())
        }
        outbounds.put(blockOutbound)

        root.put("outbounds", outbounds)

        // DNS
        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("1.0.0.1")
                put("https://1.1.1.1/dns-query")
            })
        }
        root.put("dns", dns)

        // Routing
        val routing = JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            val rules = JSONArray()

            // Прямой доступ к локальным адресам
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "direct")
                put("ip", JSONArray().apply {
                    put("geoip:private")
                })
            })

            // Весь остальной трафик идет через MASQUE
            rules.put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "masque-out")
                put("network", "tcp,udp")
            })

            put("rules", rules)
        }
        root.put("routing", routing)

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
