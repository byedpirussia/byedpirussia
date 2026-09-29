package io.github.dovecoteescapee.byedpi.vless

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
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

class VlessVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "VlessVpnService"
        private const val FOREGROUND_SERVICE_ID = 3001
        private const val NOTIFICATION_CHANNEL_ID = "VlessVpnServiceChannel"
        private const val SOCKS_PORT = 10855

        const val ACTION_START = "io.github.dovecoteescapee.byedpi.vless.START"
        const val ACTION_STOP = "io.github.dovecoteescapee.byedpi.vless.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _connectionStatus = MutableStateFlow("Остановлен")
        val connectionStatus: StateFlow<String> = _connectionStatus

        fun start(context: Context) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Init Xray core environment (assets directory for geoip/geosite)
        try {
            val assetDir = File(applicationContext.filesDir, "xray_assets")
            if (!assetDir.exists()) assetDir.mkdirs()
            Libv2ray.initCoreEnv(assetDir.absolutePath, "")
        } catch (e: Exception) {
            Log.e(TAG, "InitCoreEnv error", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startVless()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopVless()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startVless() {
        if (_isRunning.value) return

        serviceScope.launch {
            try {
                val activeConfig = VlessManager.getSelectedConfig(this@VlessVpnService)
                if (activeConfig == null) {
                    Log.e(TAG, "No active VLESS profile selected")
                    stopSelf()
                    return@launch
                }

                _connectionStatus.value = "Подключение к ${activeConfig.name}..."
                startForegroundNotification("Подключение к ${activeConfig.name}...")

                // 1. Initialize and Start Xray Core in SOCKS5 Mode on 127.0.0.1:SOCKS_PORT
                val callback = object : CoreCallbackHandler {
                    override fun onEmitStatus(status: Long, msg: String?): Long {
                        Log.d(TAG, "Xray status: $status, msg: $msg")
                        return 0
                    }

                    override fun startup(): Long {
                        Log.d(TAG, "Xray core started")
                        return 0
                    }

                    override fun shutdown(): Long {
                        Log.d(TAG, "Xray core stopped")
                        return 0
                    }
                }

                val controller = Libv2ray.newCoreController(callback)
                coreController = controller

                val configJson = activeConfig.toXrayConfigJson(localSocksPort = SOCKS_PORT)
                Log.d(TAG, "Starting Xray Core with config:\n$configJson")
                controller.startLoop(configJson, 0)

                // Give Xray a moment to bind SOCKS port
                delay(300)

                // 2. Setup TUN interface via Android VpnService
                val protoTitle = when (activeConfig.protocol.lowercase()) {
                    "hysteria2" -> "Hysteria2"
                    "shadowsocks" -> "Shadowsocks"
                    "vmess" -> "VMess"
                    "trojan" -> "Trojan"
                    else -> "VLESS"
                }
                val builder = Builder()
                builder.setSession("$protoTitle: ${activeConfig.name}")
                builder.setConfigureIntent(
                    PendingIntent.getActivity(
                        this@VlessVpnService,
                        0,
                        Intent(this@VlessVpnService, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )

                // Route traffic into TUN
                builder.addAddress("10.0.0.2", 30)
                builder.addDnsServer("1.1.1.1")
                builder.addRoute("0.0.0.0", 0)
                builder.setMtu(8500)

                // Split tunneling
                SplitTunnelManager.applySplitTunnel(builder, this@VlessVpnService)

                // Exclude this app so Xray traffic out to the remote server is direct
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
                    stopVless()
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

                // 3. Connect TUN to local SOCKS5 proxy via high-performance hev-socks5-tunnel
                val tun2socksConfig = """
                | misc:
                |   task-stack-size: 81920
                | socks5:
                |   mtu: 8500
                |   address: 127.0.0.1
                |   port: $SOCKS_PORT
                |   udp: udp
                """.trimMargin("| ")

                val configFile = File(cacheDir, "vless_tun2socks.tmp").apply {
                    writeText(tun2socksConfig)
                }

                TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)
                Log.i(TAG, "hev-socks5-tunnel bridging started successfully to port $SOCKS_PORT")

                _isRunning.value = true
                _connectionStatus.value = "Подключен (${activeConfig.name})"
                updateNotification("🟢 $protoTitle подключен: ${activeConfig.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting VlessVpnService", e)
                _isRunning.value = false
                _connectionStatus.value = "Ошибка: ${e.message}"
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "Ошибка запуска VLESS: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                cleanup()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopVless() {
        serviceScope.launch {
            cleanup()
            _isRunning.value = false
            _connectionStatus.value = "Остановлен"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanup() {
        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TProxyService", e)
        }

        try {
            File(cacheDir, "vless_tun2socks.tmp").delete()
        } catch (_: Exception) {}

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunFd", e)
        }
        tunFd = null

        try {
            coreController?.stopLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
        }
        coreController = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
        _isRunning.value = false
        _connectionStatus.value = "Остановлен"
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVless()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VLESS VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о статусе подключения VLESS туннеля"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VlessVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("VLESS / Reality")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Отключить", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundNotification(text: String) {
        val notification = createNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(FOREGROUND_SERVICE_ID, createNotification(text))
    }
}
