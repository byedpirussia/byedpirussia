package io.github.dovecoteescapee.byedpi.openflux

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
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.splittunnel.SplitTunnelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class OpenFluxVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    companion object {
        private const val TAG = "OpenFluxVpnService"
        private const val FOREGROUND_SERVICE_ID = 4001
        private const val NOTIFICATION_CHANNEL_ID = "OpenFluxVpnServiceChannel"
        const val SOCKS_PORT = 10885

        const val ACTION_START = "io.github.dovecoteescapee.byedpi.openflux.START"
        const val ACTION_STOP = "io.github.dovecoteescapee.byedpi.openflux.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _connectionStatus = MutableStateFlow("Остановлен")
        val connectionStatus: StateFlow<String> = _connectionStatus

        private val _bytesSent = MutableStateFlow(0L)
        val bytesSent: StateFlow<Long> = _bytesSent

        private val _bytesReceived = MutableStateFlow(0L)
        val bytesReceived: StateFlow<Long> = _bytesReceived

        private val _trafficStats = MutableStateFlow("")
        val trafficStats: StateFlow<String> = _trafficStats

        fun start(context: Context) {
            val intent = Intent(context, OpenFluxVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenFluxVpnService::class.java).apply {
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
                startForegroundNotification("Подключение OpenFLUX...")
                startOpenFlux()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopOpenFlux()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startOpenFlux() {
        if (_isRunning.value) return

        serviceScope.launch {
            try {
                val activeConfig = OpenFluxManager.getSelectedConfig(this@OpenFluxVpnService)
                if (activeConfig == null) {
                    Log.e(TAG, "No active OpenFLUX profile selected")
                    stopSelf()
                    return@launch
                }

                _connectionStatus.value = "Подключение (${activeConfig.name})..."
                startForegroundNotification("Подключение: ${activeConfig.name}...")

                // 1. Start local SOCKS5 proxy via native OpenFlux
                NativeOpenFlux.setDebugLevel(1)
                val listenAddr = "127.0.0.1:$SOCKS_PORT"
                val err = when (activeConfig.mode.lowercase()) {
                    "session" -> {
                        NativeOpenFlux.startSessionProxy(
                            specsJSON = activeConfig.specsJson,
                            encryptionSecret = activeConfig.encryptionSecret,
                            listenAddr = listenAddr,
                            bypassDomains = activeConfig.bypassDomains
                        )
                    }
                    "stream" -> {
                        NativeOpenFlux.startStreamProxy(
                            transportType = activeConfig.transportType,
                            url = activeConfig.documentUrl,
                            listenAddr = listenAddr,
                            bypassDomains = activeConfig.bypassDomains
                        )
                    }
                    else -> {
                        NativeOpenFlux.startProxy(
                            transportType = activeConfig.transportType,
                            documentURL = activeConfig.documentUrl,
                            encryptionSecret = activeConfig.encryptionSecret,
                            codec = activeConfig.codec,
                            maxToken = activeConfig.maxToken,
                            maxUid = activeConfig.maxUid,
                            listenAddr = listenAddr,
                            bypassDomains = activeConfig.bypassDomains
                        )
                    }
                }

                if (err.isNotBlank()) {
                    throw IllegalStateException(err)
                }

                // Give OpenFlux proxy a moment to bind SOCKS port
                delay(300)

                // 2. Setup TUN interface via Android VpnService if in VPN routing mode
                if (activeConfig.routingMode == "vpn") {
                    val builder = Builder()
                    builder.setSession("OpenFLUX: ${activeConfig.name}")
                    builder.setConfigureIntent(
                        PendingIntent.getActivity(
                            this@OpenFluxVpnService,
                            0,
                            Intent(this@OpenFluxVpnService, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                    )

                    builder.addAddress("10.0.0.2", 30)
                    builder.addDnsServer("1.1.1.1")
                    builder.addRoute("0.0.0.0", 0)
                    builder.setMtu(8500)

                    // Split tunneling
                    SplitTunnelManager.applySplitTunnel(builder, this@OpenFluxVpnService)

                    // Exclude this app so OpenFlux traffic is direct
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot exclude own package", e)
                    }

                    try {
                        builder.allowBypass()
                    } catch (_: Exception) {}

                    val pfd = builder.establish()
                    if (pfd == null) {
                        Log.e(TAG, "Failed to establish VPN interface")
                        stopOpenFlux()
                        return@launch
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        try {
                            setUnderlyingNetworks(null)
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot set underlying networks", e)
                        }
                    }

                    tunFd = pfd

                    // 3. Connect TUN to local SOCKS5 proxy via hev-socks5-tunnel
                    val tun2socksConfig = """
                    | misc:
                    |   task-stack-size: 81920
                    | socks5:
                    |   mtu: 8500
                    |   address: 127.0.0.1
                    |   port: $SOCKS_PORT
                    |   udp: tcp
                    """.trimMargin("| ")

                    val configFile = File(cacheDir, "openflux_tun2socks.tmp").apply {
                        writeText(tun2socksConfig)
                    }

                    TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
                    Log.i(TAG, "hev-socks5-tunnel bridged to OpenFLUX port $SOCKS_PORT")
                }

                _isRunning.value = true
                _connectionStatus.value = "Подключен (${activeConfig.name})"
                updateNotification("🟢 OpenFLUX подключен: ${activeConfig.name}")

                startStatsMonitor(activeConfig.name)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting OpenFluxVpnService", e)
                _isRunning.value = false
                _connectionStatus.value = "Ошибка: ${e.message}"
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "Ошибка запуска OpenFLUX: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startStatsMonitor(profileName: String) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                delay(1000)
                val sent = NativeOpenFlux.bytesSent()
                val rcvd = NativeOpenFlux.bytesReceived()
                val isConn = NativeOpenFlux.isConnected()
                _bytesSent.value = sent
                _bytesReceived.value = rcvd
                _trafficStats.value = "↑ ${formatBytes(sent)} • ↓ ${formatBytes(rcvd)}"

                val statusText = if (isConn) {
                    "🟢 OpenFLUX подключен: $profileName"
                } else {
                    "🟡 Подключение OpenFLUX..."
                }
                _connectionStatus.value = if (isConn) "Подключен ($profileName)" else "Соединение..."
                updateNotification(statusText)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun stopOpenFlux() {
        serviceScope.launch {
            cleanup()
            _isRunning.value = false
            _connectionStatus.value = "Остановлен"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        statsJob?.cancel()
        statsJob = null
        _trafficStats.value = ""
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TProxyService", e)
        }
        try {
            tunFd?.close()
            tunFd = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunFd", e)
        }
        try {
            NativeOpenFlux.stopProxy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native OpenFlux", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        _isRunning.value = false
        _connectionStatus.value = "Остановлен"
    }

    override fun onRevoke() {
        stopOpenFlux()
        super.onRevoke()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OpenFLUX VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления службы OpenFLUX туннелирования"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OpenFluxVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenFLUX (Обход Б/С)")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        io.github.dovecoteescapee.byedpi.island.NotificationIslandHelper.applyHyperOsFocus(
            builder,
            this,
            io.github.dovecoteescapee.byedpi.island.ServiceSwitchController.MODE_OPENFLUX
        )

        return builder.build()
    }

    private fun startForegroundNotification(initialStatus: String) {
        val notification = createNotification(initialStatus)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(FOREGROUND_SERVICE_ID, notification)
    }
}
