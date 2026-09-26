package io.github.dovecoteescapee.byedpi.warp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.amnezia.awg.GoBackend
import java.net.InetAddress

class WarpVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var tunnelHandle: Int = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startTunnel()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                return START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by OS")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
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
                _connectionStatus.value = "Подключение..."
                val rawConfig = WarpConfigManager.currentConfig.value
                val parsed = AwgUapiFormatter.parseConfig(rawConfig)

                if (parsed.privateKeyHex.isEmpty()) {
                    throw IllegalStateException("В конфиге WARP отсутствует PrivateKey")
                }

                startForegroundNotification("Подключение к Cloudflare WARP...")

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
                for (addr in parsed.addresses) {
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
                if (parsed.allowedIps.isNotEmpty()) {
                    for (aip in parsed.allowedIps) {
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

                // Add DNS
                for (dns in parsed.dnsServers) {
                    try {
                        builder.addDnsServer(dns.trim())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add DNS: $dns", e)
                    }
                }
                if (parsed.dnsServers.isEmpty()) {
                    builder.addDnsServer("1.1.1.1")
                }

                builder.setMtu(parsed.mtu)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager.applySplitTunnel(builder, this@WarpVpnService)

                // Establish TUN
                val pfd = builder.establish() ?: throw IllegalStateException("Не удалось создать TUN интерфейс")
                tunFd = pfd

                // Generate UAPI config string
                val uapi = AwgUapiFormatter.toUapi(parsed)
                Log.d(TAG, "Starting GoBackend.awgTurnOn with UAPI length: ${uapi.length}")

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

            } catch (e: Exception) {
                Log.e(TAG, "Error starting WARP tunnel", e)
                _connectionStatus.value = "Ошибка: ${e.message}"
                stopTunnel()
            }
        }
    }

    private fun stopTunnel() {
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
}
