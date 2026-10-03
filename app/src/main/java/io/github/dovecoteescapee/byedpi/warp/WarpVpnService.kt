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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.amnezia.awg.GoBackend
import java.net.InetAddress

class WarpVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelHandle: Int = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectivityManager: ConnectivityManager? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeDefaultNetwork: Network? = null
    private var reconnectJob: Job? = null
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
                    val prev = activeDefaultNetwork
                    activeDefaultNetwork = network
                    Log.i(TAG, "Default network onAvailable: $network (prev: $prev)")

                    // Update underlying network for VPN
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(arrayOf(network))
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot set underlying network: $network", e)
                        }
                    }

                    if (prev == null) {
                        // Initial network binding
                        protectCurrentSockets()
                        return
                    }

                    if (prev != network) {
                        Log.i(TAG, "Underlying network changed from $prev to $network, triggering auto-reconnect")
                        if (!isUserExplicitStop && this@WarpVpnService.isWarpAutoReconnectEnabled()) {
                            triggerAutoReconnect("Смена сети")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Default network onLost: $network")
                    if (activeDefaultNetwork == network) {
                        activeDefaultNetwork = null
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try {
                                setUnderlyingNetworks(null)
                            } catch (_: Exception) {}
                        }

                        if (!isUserExplicitStop && this@WarpVpnService.isWarpAutoReconnectEnabled()) {
                            _connectionStatus.value = "Ожидание сети..."
                            startForegroundNotification("Ожидание сети...")
                        }
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    Log.d(TAG, "Capabilities changed: network=$network, internet=$hasInternet, validated=$isValidated")

                    if (hasInternet && !isUserExplicitStop && this@WarpVpnService.isWarpAutoReconnectEnabled()) {
                        if (!_isRunning.value || tunnelHandle < 0) {
                            activeDefaultNetwork = network
                            triggerAutoReconnect("Восстановление интернета")
                        }
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
                activeDefaultNetwork = null
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
        reconnectJob?.cancel()
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

                // Attempt to establish tunnel with retry logic
                var connected = false
                var attempt = 1
                val maxAttempts = 4

                while (!connected && attempt <= maxAttempts && !isUserExplicitStop) {
                    try {
                        Log.i(TAG, "Auto-reconnect attempt $attempt of $maxAttempts")
                        if (attempt > 1) {
                            _connectionStatus.value = "Переподключение (попытка $attempt)..."
                            startForegroundNotification("Переподключение (попытка $attempt)...")
                        }

                        startTunnelInternal()
                        connected = true
                        Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-reconnect attempt $attempt failed: ${e.message}")
                        attempt++
                        if (attempt <= maxAttempts && !isUserExplicitStop) {
                            delay(1500)
                        }
                    }
                }

                if (!connected && !isUserExplicitStop) {
                    Log.w(TAG, "All auto-reconnect attempts failed, awaiting network event")
                    _connectionStatus.value = "Ожидание сети..."
                    startForegroundNotification("Ожидание сети...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-reconnect", e)
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by OS")
        isUserExplicitStop = true
        unregisterNetworkCallback()
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        isUserExplicitStop = true
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

        // Add DNS (Use selected DNS preset if Private DNS is not active, otherwise config DNS)
        val customDnsServers = WarpDnsManager.getDnsServers(this@WarpVpnService)
        val dnsList = if (customDnsServers.isNotEmpty() && !WarpDnsManager.isPrivateDnsActive(this@WarpVpnService)) {
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

        builder.setMtu(finalParsed.mtu)
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
                val net = activeDefaultNetwork
                setUnderlyingNetworks(if (net != null) arrayOf(net) else null)
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
    }

    private fun stopTunnel() {
        reconnectJob?.cancel()
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

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cloudflare WARP (AmneziaWG)")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
