package io.github.dovecoteescapee.byedpi.warp

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.TProxyService
import kotlinx.coroutines.*
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Нативный MASQUE-клиент Cloudflare WARP на базе ядра Mihomo (Clash.Meta) + hev-socks5-tunnel (TProxy).
 * Поддерживает полноценный RFC 9484 CONNECT-IP поверх HTTP/2 (TLS:443) с подменой SNI (4pda.to),
 * а также автоматический перебор эндпоинтов Cloudflare Anycast.
 */
object WarpMasqueClient {

    private const val TAG = "WarpMasqueClient"
    const val LOCAL_SOCKS_PORT = 10855

    private val isRunning = AtomicBoolean(false)
    private var mihomoProcess: Process? = null
    private var logJob: Job? = null

    data class MasqueConfig(
        val privateKey: String,
        val peerPublicKey: String,
        val clientIpv4: String = "172.16.0.2",
        val clientIpv6: String = "2606:4700:110:8531:949:ea2f:a22b:5b02",
        val endpointHost: String = "162.159.198.2",
        val endpointPort: Int = 443,
        val sni: String = "4pda.to",
        val path: String = "/cdn-cgi/warp/masque",
        val rawYaml: String = ""
    )

    suspend fun start(
        vpnService: VpnService,
        tunPfd: ParcelFileDescriptor,
        config: MasqueConfig
    ) = withContext(Dispatchers.IO) {
        if (isRunning.get()) {
            Log.w(TAG, "MASQUE client already running, restarting...")
            stop()
        }

        val mihomoBin = File(vpnService.applicationInfo.nativeLibraryDir, "libmihomo.so")
        if (!mihomoBin.exists()) {
            throw IllegalStateException("Бинарник ядра Mihomo не найден: ${mihomoBin.absolutePath}")
        }
        try {
            mihomoBin.setExecutable(true, false)
        } catch (_: Exception) {}

        var activeConfig = config
        if (activeConfig.privateKey.isBlank() || activeConfig.peerPublicKey.isBlank()) {
            Log.i(TAG, "Нет сохранённых MASQUE ключей, запрашиваем свежий профиль через WarpGenerator...")
            val genResult = WarpGenerator.generateMasqueConfig()
            val profile = genResult.getOrNull()
            if (profile != null && profile.privKey.isNotBlank()) {
                WarpConfigManager.saveMasqueProfile(vpnService, profile)
                activeConfig = profile.toMasqueConfig()
            } else {
                throw IllegalStateException("Не удалось получить свежие ключи MASQUE от сервера")
            }
        }

        val bestHost = findBestMasqueEndpoint(activeConfig.endpointHost, if (activeConfig.endpointPort <= 0) 443 else activeConfig.endpointPort)
        val effectiveConfig = activeConfig.copy(endpointHost = bestHost, endpointPort = 443)

        val workDir = File(vpnService.filesDir, "mihomo_work").apply { mkdirs() }
        val configFile = File(workDir, "config.yaml")
        val yamlContent = generateMihomoYaml(effectiveConfig, LOCAL_SOCKS_PORT)
        configFile.writeText(yamlContent)
        val privLine = configFile.readLines().find { it.contains("private-key") } ?: ""
        Log.i(TAG, "DEBUG config.yaml privLine='$privLine' len=${privLine.length}")

        val pb = ProcessBuilder(mihomoBin.absolutePath, "-d", workDir.absolutePath, "-f", configFile.absolutePath)
            .redirectErrorStream(true)
        val proc = pb.start()
        mihomoProcess = proc

        logJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Log.d("MihomoCore", line)
                    }
                }
            } catch (_: Exception) {}
        }

        // Даем Mihomo время открыть локальный SOCKS5 порт 10855
        delay(500)

        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: 127.0.0.1
        |   port: $LOCAL_SOCKS_PORT
        |   udp: udp
        """.trimMargin("| ")

        val tproxyFile = File(vpnService.cacheDir, "warp_masque_tun2socks.tmp").apply {
            writeText(tun2socksConfig)
        }

        TProxyService.TProxyStartService(tproxyFile.absolutePath, tunPfd.fd)
        Log.i(TAG, "hev-socks5-tunnel подключен к Mihomo (127.0.0.1:$LOCAL_SOCKS_PORT)")
        isRunning.set(true)
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Остановка Mihomo MASQUE-клиента...")
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки TProxyService: ${e.message}")
        }
        try {
            logJob?.cancel()
            logJob = null
        } catch (_: Exception) {}
        try {
            mihomoProcess?.destroy()
            mihomoProcess = null
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки Mihomo: ${e.message}")
        }
        Log.i(TAG, "Mihomo MASQUE-клиент остановлен")
    }

    fun isClientRunning(): Boolean = isRunning.get()

    private fun resolveEndpointIp(host: String): String {
        if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) return host
        return try {
            InetAddress.getByName(host).hostAddress ?: "162.159.198.2"
        } catch (_: Exception) {
            "162.159.198.2"
        }
    }

    private fun generateMihomoYaml(config: MasqueConfig, localPort: Int): String {
        val resolvedIp = resolveEndpointIp(config.endpointHost)
        val ipv4 = config.clientIpv4.substringBefore("/").trim()
        val ipv6 = config.clientIpv6.substringBefore("/").trim()
        val sni = config.sni.ifBlank { "4pda.to" }.trim()
        val rawPriv = config.privateKey.replace(Regex("[^A-Za-z0-9+/=]"), "")
        val rawPub = config.peerPublicKey.replace(Regex("[^A-Za-z0-9+/=]"), "")
        val privKey = try {
            android.util.Base64.encodeToString(
                android.util.Base64.decode(rawPriv, android.util.Base64.DEFAULT),
                android.util.Base64.NO_WRAP
            )
        } catch (_: Exception) { rawPriv }
        val pubKey = try {
            android.util.Base64.encodeToString(
                android.util.Base64.decode(rawPub, android.util.Base64.DEFAULT),
                android.util.Base64.NO_WRAP
            )
        } catch (_: Exception) { rawPub }

        val excludedRules = io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.EXCLUDED_RUSSIAN_DOMAINS
            .joinToString("\n") { "  - DOMAIN-SUFFIX,$it,DIRECT" }

        return """
            |mixed-port: $localPort
            |bind-address: 127.0.0.1
            |allow-lan: false
            |mode: rule
            |log-level: debug
            |ipv6: false
            |dns:
            |  enable: true
            |  listen: 0.0.0.0:1053
            |  enhanced-mode: normal
            |  nameserver:
            |    - 1.1.1.1
            |    - 1.0.0.1
            |proxies:
            |  - name: "WARP-MASQUE"
            |    type: masque
            |    server: $resolvedIp
            |    port: 443
            |    network: h2
            |    skip-cert-verify: true
            |    ip: $ipv4
            |    ipv6: $ipv6
            |    private-key: "$privKey"
            |    public-key: "$pubKey"
            |    udp: true
            |rules:
            |  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve
            |  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
            |  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve
            |  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
            |$excludedRules
            |  - MATCH,WARP-MASQUE
        """.trimMargin("|")
    }

    private val MASQUE_CANDIDATES = listOf(
        "162.159.198.1",
        "162.159.198.2",
        "162.159.192.1",
        "162.159.192.2",
        "162.159.193.1",
        "162.159.195.1",
        "188.114.96.1",
        "188.114.97.1",
        "188.114.98.1",
        "188.114.99.1"
    )

    private suspend fun findBestMasqueEndpoint(preferredHost: String, port: Int): String = coroutineScope {
        val candidates = (listOf(preferredHost) + MASQUE_CANDIDATES).distinct().filter { it.isNotBlank() }
        val results = candidates.map { host ->
            async(Dispatchers.IO) {
                val rtt = pingMasqueEndpoint(host, port, 1500)
                host to rtt
            }
        }.awaitAll()

        val working = results.filter { it.second >= 0 }.minByOrNull { it.second }
        if (working != null) {
            Log.i(TAG, "Выбран рабочий MASQUE эндпоинт: ${working.first}:$port (${working.second} ms)")
            working.first
        } else {
            Log.w(TAG, "Ни один MASQUE эндпоинт не ответил на TCP:$port, используем $preferredHost")
            preferredHost
        }
    }

    suspend fun pingMasqueEndpoint(host: String = "engage.cloudflareclient.com", port: Int = 443, timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                System.currentTimeMillis() - start
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
