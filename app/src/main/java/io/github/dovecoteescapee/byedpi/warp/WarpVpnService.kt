package io.github.dovecoteescapee.byedpi.warp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.utility.isWarpAutoReconnectEnabled
import io.github.dovecoteescapee.byedpi.utility.isWarpDnsForceOverride
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.amnezia.awg.GoBackend
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class WarpVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelHandle: Int = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentPhysicalNetworkHandle: Long? = null
    private var isPhysicalNetworkLost = false
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var isUserExplicitStop = false

    companion object {
        private const val TAG = "WarpVpnService"
        private const val FOREGROUND_SERVICE_ID = 2001
        private const val NOTIFICATION_CHANNEL_ID = "WarpVpnServiceChannel"

        const val ACTION_START = "io.github.dovecoteescapee.byedpi.warp.START"
        const val ACTION_STOP = "io.github.dovecoteescapee.byedpi.warp.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _connectionStatus = MutableStateFlow("Остановлен")
        val connectionStatus: StateFlow<String> = _connectionStatus

        private val _warpPingMs = MutableStateFlow<Long?>(-1L)
        val warpPingMs: StateFlow<Long?> = _warpPingMs

        fun start(context: Context) {
            val intent = Intent(context, WarpVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WarpVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Pings through the active VPN tunnel. Uses HTTPS port 443 and HTTP 204 connectivity endpoints.
         */
        suspend fun pingTunnel(timeoutMs: Int = 3000): Long = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()

            // 1. Direct TCP handshake to port 443 (DoH servers)
            val fastTargets = listOf(
                InetSocketAddress("1.1.1.1", 443),
                InetSocketAddress("1.0.0.1", 443),
                InetSocketAddress("8.8.8.8", 443)
            )
            for (target in fastTargets) {
                try {
                    Socket().use { socket ->
                        socket.connect(target, timeoutMs)
                        val duration = System.currentTimeMillis() - start
                        if (duration >= 0) return@withContext duration
                    }
                } catch (_: Exception) {}
            }

            // 2. HTTP generate_204 check
            val httpUrls = listOf(
                "http://cp.cloudflare.com/generate_204",
                "http://connectivitycheck.gstatic.com/generate_204"
            )
            for (urlStr in httpUrls) {
                try {
                    val urlStart = System.currentTimeMillis()
                    val url = java.net.URL(urlStr)
                    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        useCaches = false
                    }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) {
                        return@withContext (System.currentTimeMillis() - urlStart)
                    }
                } catch (_: Exception) {}
            }

            -1L
        }

        fun checkPingAsync(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
            scope.launch {
                val ping = pingTunnel(3500)
                _warpPingMs.value = ping
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isUserExplicitStop = false
                startForegroundNotification("Подключение к Cloudflare WARP...")
                startTunnel()
                registerNetworkCallbackIfNeeded()
                return START_STICKY
            }
            ACTION_STOP -> {
                isUserExplicitStop = true
                unregisterNetworkCallback()
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                return START_NOT_STICKY
            }
        }
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (!isWarpAutoReconnectEnabled()) {
            unregisterNetworkCallback()
            return
        }
        if (defaultNetworkCallback != null) return

        try {
            val cm = connectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network) ?: return
                    // Ignore our own or any other VPN interface!
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.d(TAG, "Ignoring VPN transport network")
                        return
                    }

                    val networkHandle = network.networkHandle
                    val prevHandle = currentPhysicalNetworkHandle
                    currentPhysicalNetworkHandle = networkHandle
                    Log.i(TAG, "Physical network onAvailable: $networkHandle (prev: $prevHandle, wasLost: $isPhysicalNetworkLost)")

                    // Update underlying network for VPN
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(arrayOf(network))
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot set underlying network: $network", e)
                        }
                    }

                    // Initial connection on service startup
                    if (prevHandle == null && !isPhysicalNetworkLost) {
                        protectCurrentSockets()
                        return
                    }

                    // If it is the exact same network and wasn't lost, do nothing!
                    if (prevHandle == networkHandle && !isPhysicalNetworkLost) {
                        return
                    }

                    // Truly a new physical network (Wi-Fi <-> Cellular) OR network was restored after being lost
                    if (_isRunning.value && !isUserExplicitStop && this@WarpVpnService.isWarpAutoReconnectEnabled()) {
                        val reason = if (isPhysicalNetworkLost) "Восстановление сети" else "Смена сети"
                        isPhysicalNetworkLost = false
                        triggerAutoReconnect(reason)
                    }
                }

                override fun onLost(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return // Ignore VPN interface lost
                    }

                    if (network.networkHandle == currentPhysicalNetworkHandle) {
                        Log.i(TAG, "Physical network lost: ${network.networkHandle}")
                        isPhysicalNetworkLost = true
                        currentPhysicalNetworkHandle = null

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try { setUnderlyingNetworks(null) } catch (_: Exception) {}
                        }

                        if (!isUserExplicitStop && this@WarpVpnService.isWarpAutoReconnectEnabled() && _isRunning.value) {
                            _connectionStatus.value = "Ожидание сети..."
                            startForegroundNotification("Ожидание сети...")
                        }
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    // Do NOT trigger reconnects here - onAvailable & onLost handle connection state!
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && network.networkHandle == currentPhysicalNetworkHandle) {
                        try {
                            setUnderlyingNetworks(arrayOf(network))
                        } catch (_: Exception) {}
                    }
                }
            }

            cm.registerDefaultNetworkCallback(callback)
            defaultNetworkCallback = callback
            Log.i(TAG, "Registered default network callback for Warp auto-reconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register default network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            defaultNetworkCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
                defaultNetworkCallback = null
                currentPhysicalNetworkHandle = null
                isPhysicalNetworkLost = false
                Log.i(TAG, "Unregistered default network callback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering network callback", e)
        }
    }

    private fun protectCurrentSockets() {
        val handle = tunnelHandle
        if (handle >= 0) {
            try {
                val sockV4 = GoBackend.awgGetSocketV4(handle)
                if (sockV4 >= 0) protect(sockV4)
                val sockV6 = GoBackend.awgGetSocketV6(handle)
                if (sockV6 >= 0) protect(sockV6)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-protect sockets", e)
            }
        }
    }

    private fun triggerAutoReconnect(reason: String) {
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "Auto-reconnect already in progress, skipping trigger: $reason")
            return
        }

        watchdogJob?.cancel()
        watchdogJob = null
        _warpPingMs.value = -1L

        reconnectJob = serviceScope.launch {
            try {
                Log.i(TAG, "Triggering auto-reconnect due to: $reason")
                _connectionStatus.value = "Переподключение ($reason)..."
                startForegroundNotification("Переподключение ($reason)...")

                delay(800) // Brief debounce for routes to settle

                if (isUserExplicitStop) return@launch

                // Cleanly teardown previous tunnel handle
                if (tunnelHandle >= 0) {
                    try {
                        GoBackend.awgTurnOff(tunnelHandle)
                    } catch (_: Exception) {}
                    tunnelHandle = -1
                }
                tunFd?.close()
                tunFd = null
                _isRunning.value = false

                delay(300)
                if (isUserExplicitStop) return@launch

                // Attempt to establish tunnel with retry logic (waiting up to 25s for connection)
                val startTime = System.currentTimeMillis()
                val maxWaitMs = 25_000L
                var connected = false
                var attempt = 1

                while (!connected && (System.currentTimeMillis() - startTime) < maxWaitMs && !isUserExplicitStop) {
                    try {
                        val elapsed = System.currentTimeMillis() - startTime
                        val remainingSec = ((maxWaitMs - elapsed) / 1000L).coerceAtLeast(1)
                        Log.i(TAG, "Auto-reconnect attempt $attempt, waiting connection (${remainingSec}s left)")

                        if (attempt > 1) {
                            _connectionStatus.value = "Переподключение (${remainingSec}с)..."
                            startForegroundNotification("Переподключение (${remainingSec}с)...")
                        }

                        startTunnelInternal()
                        connected = true
                        Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-reconnect attempt $attempt failed: ${e.message}")
                        attempt++
                        delay(2000)
                    }
                }

                if (!connected && !isUserExplicitStop) {
                    Log.w(TAG, "Auto-reconnect timed out after 25s, awaiting network event")
                    _connectionStatus.value = "Ожидание сети..."
                    startForegroundNotification("Ожидание сети...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-reconnect", e)
            }
        }
    }

    /**
     * Background ping watchdog that monitors connection health and updates ping in real time.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            try {
                Log.i(TAG, "Starting WARP connection watchdog...")

                // Initial background ping check after start
                delay(2000)
                val initialPing = pingTunnel(3500)
                if (initialPing >= 0) {
                    _warpPingMs.value = initialPing
                    startForegroundNotification("WARP активен (${initialPing}ms)")
                }

                while (_isRunning.value && !isUserExplicitStop) {
                    delay(12000) // Regular check interval (12 seconds)

                    if (!_isRunning.value || isUserExplicitStop) break

                    val ping = pingTunnel(3500)
                    if (ping >= 0) {
                        _warpPingMs.value = ping
                        _connectionStatus.value = "Подключен (WARP активен)"
                        startForegroundNotification("WARP активен (${ping}ms)")
                    } else {
                        _warpPingMs.value = -1L
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog error", e)
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by OS")
        isUserExplicitStop = true
        watchdogJob?.cancel()
        watchdogJob = null
        unregisterNetworkCallback()
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        isUserExplicitStop = true
        watchdogJob?.cancel()
        watchdogJob = null
        unregisterNetworkCallback()
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTunnel() {
        if (_isRunning.value) {
            Log.w(TAG, "WARP tunnel already running")
            return
        }

        serviceScope.launch {
            try {
                startTunnelInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting WARP tunnel", e)
                _connectionStatus.value = "Ошибка: ${e.message}"
                if (isWarpAutoReconnectEnabled() && !isUserExplicitStop) {
                    _connectionStatus.value = "Ожидание сети..."
                    startForegroundNotification("Ожидание сети...")
                } else {
                    stopTunnel()
                }
            }
        }
    }

    private suspend fun startTunnelInternal() {
        _connectionStatus.value = "Подключение..."
        val rawConfig = WarpConfigManager.currentConfig.value
        val parsed = AwgUapiFormatter.parseConfig(rawConfig)

        if (parsed.privateKeyHex.isEmpty()) {
            throw IllegalStateException("В конфиге WARP отсутствует PrivateKey")
        }

        startForegroundNotification("Подключение к Cloudflare WARP...")

        // Resolve letter-based/domain endpoints before creating TUN (prevents DNS deadlock & Go panic)
        val resolvedEndpoint = resolveEndpointToIp(parsed.peerEndpoint)
        val finalParsed = parsed.copy(peerEndpoint = resolvedEndpoint)

        // Build TUN interface
        val builder = Builder()
        builder.setSession("Cloudflare WARP (AmneziaWG)")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this@WarpVpnService,
                0,
                Intent(this@WarpVpnService, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Add Addresses
        for (addr in finalParsed.addresses) {
            try {
                val parts = addr.split("/")
                val ip = parts[0].trim()
                val prefix = if (parts.size > 1) parts[1].trim().toInt() else if (ip.contains(":")) 128 else 32
                builder.addAddress(ip, prefix)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse address: $addr", e)
            }
        }

        // Add Routes (default route for VPN)
        var hasV4 = false
        var hasV6 = false
        if (finalParsed.allowedIps.isNotEmpty()) {
            for (aip in finalParsed.allowedIps) {
                try {
                    val parts = aip.split("/")
                    val ip = parts[0].trim()
                    val prefix = if (parts.size > 1) parts[1].trim().toInt() else if (ip.contains(":")) 128 else 32
                    builder.addRoute(ip, prefix)
                    if (ip.contains(":")) hasV6 = true else hasV4 = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse allowed ip route: $aip", e)
                }
            }
        }

        if (!hasV4) builder.addRoute("0.0.0.0", 0)
        if (!hasV6) {
            try {
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 route not supported on interface", e)
            }
        }

        // Add DNS (Use selected DNS preset if Private DNS is not active or force override is enabled, otherwise config DNS)
        val customDnsServers = WarpDnsManager.getDnsServers(this@WarpVpnService)
        val isForceOverride = isWarpDnsForceOverride()
        val dnsList = if (customDnsServers.isNotEmpty() && (!WarpDnsManager.isPrivateDnsActive(this@WarpVpnService) || isForceOverride)) {
            customDnsServers
        } else if (finalParsed.dnsServers.isNotEmpty()) {
            finalParsed.dnsServers
        } else {
            listOf("1.1.1.1", "1.0.0.1")
        }

        for (dns in dnsList) {
            try {
                builder.addDnsServer(dns.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add DNS: $dns", e)
            }
        }

        val effectiveMtu = if (io.github.dovecoteescapee.byedpi.experimental.ExperimentalConfigManager.isMtuClampEnabled(this@WarpVpnService)) {
            minOf(finalParsed.mtu, 1280)
        } else {
            finalParsed.mtu
        }
        builder.setMtu(effectiveMtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        try {
            builder.allowBypass()
        } catch (_: Exception) {}

        io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.applySplitTunnel(builder, this@WarpVpnService)

        // Establish TUN
        val pfd = builder.establish() ?: throw IllegalStateException("Не удалось создать TUN интерфейс")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val cm = connectivityManager
                val currentNet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm?.activeNetwork?.takeIf { net ->
                        val c = cm.getNetworkCapabilities(net)
                        c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    }
                } else null ?: cm?.allNetworks?.firstOrNull { net ->
                    val c = cm.getNetworkCapabilities(net)
                    c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                setUnderlyingNetworks(if (currentNet != null) arrayOf(currentNet) else null)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot set underlying networks", e)
            }
        }
        tunFd = pfd

        // Generate UAPI config string with resolved IP endpoint
        val uapi = AwgUapiFormatter.toUapi(finalParsed)
        Log.d(TAG, "Starting GoBackend.awgTurnOn with UAPI length: ${uapi.length} for endpoint: $resolvedEndpoint")

        // Detach FD for GoBackend
        val nativeFd = pfd.detachFd()

        // Call GoBackend
        val handle = GoBackend.awgTurnOn("awg0", nativeFd, uapi)
        if (handle < 0) {
            throw IllegalStateException("GoBackend.awgTurnOn завершился с ошибкой: $handle")
        }
        tunnelHandle = handle

        // Protect sockets
        val sockV4 = GoBackend.awgGetSocketV4(handle)
        if (sockV4 >= 0) protect(sockV4)
        val sockV6 = GoBackend.awgGetSocketV6(handle)
        if (sockV6 >= 0) protect(sockV6)

        _isRunning.value = true
        _connectionStatus.value = "Подключен (WARP активен)"
        startForegroundNotification("WARP активен: ${parsed.peerEndpoint ?: "Cloudflare"}")
        Log.i(TAG, "WARP tunnel successfully activated. Handle: $handle")

        // Start ping and health watchdog
        startWatchdog()
    }

    private fun stopTunnel() {
        watchdogJob?.cancel()
        watchdogJob = null
        reconnectJob?.cancel()
        _warpPingMs.value = -1L

        serviceScope.launch {
            try {
                if (tunnelHandle >= 0) {
                    Log.i(TAG, "Turning off GoBackend handle: $tunnelHandle")
                    GoBackend.awgTurnOff(tunnelHandle)
                    tunnelHandle = -1
                }
                tunFd?.close()
                tunFd = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tunnel", e)
            } finally {
                _isRunning.value = false
                _connectionStatus.value = "Остановлен"
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Cloudflare WARP VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления службы Cloudflare WARP туннеля"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(text: String) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cloudflare WARP (AmneziaWG)")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        io.github.dovecoteescapee.byedpi.island.NotificationIslandHelper.applyHyperOsFocus(
            builder,
            this,
            io.github.dovecoteescapee.byedpi.island.ServiceSwitchController.MODE_WARP
        )

        val notification: Notification = builder.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun resolveEndpointToIp(endpoint: String?): String? {
        if (endpoint.isNullOrBlank()) return endpoint
        return withContext(Dispatchers.IO) {
            try {
                val lastColon = endpoint.lastIndexOf(':')
                if (lastColon <= 0) return@withContext endpoint
                val host = endpoint.substring(0, lastColon).trim().removePrefix("[").removeSuffix("]")
                val port = endpoint.substring(lastColon + 1).trim()

                // If already IPv4 or IPv6
                if (host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) || host.contains(':')) {
                    return@withContext endpoint
                }

                // Resolve domain name using DNS before creating the VPN interface
                val addresses = InetAddress.getAllByName(host)
                val ip = addresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                    ?: addresses.firstOrNull()?.hostAddress
                    ?: host

                if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve endpoint domain: $endpoint", e)
                endpoint
            }
        }
    }
}
