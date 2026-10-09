package io.github.dovecoteescapee.byedpi.tgproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.dovecoteescapee.byedpi.utility.getTgProxyBaseSecret
import io.github.dovecoteescapee.byedpi.utility.setTgProxyBaseSecret
import io.github.dovecoteescapee.byedpi.utility.getTgProxyEffectiveSecret
import io.github.dovecoteescapee.byedpi.utility.setTgProxyEffectiveSecret
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.time.Duration.Companion.milliseconds

class TgWsProxyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    @Volatile
    private var stopInProgress = false

    companion object {
        const val ACTION_START = "io.github.dovecoteescapee.byedpi.tgproxy.START"
        const val ACTION_STOP = "io.github.dovecoteescapee.byedpi.tgproxy.STOP"
        
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_SECRET = "EXTRA_SECRET"

        private const val NOTIFICATION_ID = 202
        private const val CHANNEL_ID = "tg_ws_proxy_service_channel"
        private const val TAG = "TgWsProxyService"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _trafficStats = MutableStateFlow("0 B / 0 соед.")
        val trafficStats: StateFlow<String> = _trafficStats

        private val _effectiveSecret = MutableStateFlow<String?>(null)
        val effectiveSecret: StateFlow<String?> = _effectiveSecret

        fun initSecret(context: Context) {
            if (_effectiveSecret.value.isNullOrBlank()) {
                val saved = context.getTgProxyEffectiveSecret()
                if (!saved.isNullOrBlank()) {
                    _effectiveSecret.value = saved
                }
            }
        }

        fun getEffectiveSecret(context: Context): String {
            initSecret(context)
            return _effectiveSecret.value ?: context.getTgProxyEffectiveSecret() ?: ""
        }

        fun resetSecret(context: Context): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val newBaseSecret = bytes.joinToString("") { "%02x".format(it) }
            context.setTgProxyBaseSecret(newBaseSecret)
            context.setTgProxyEffectiveSecret("")
            _effectiveSecret.value = null
            if (_isRunning.value) {
                stop(context)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    start(context, port = 1443, secret = newBaseSecret)
                }, 500)
            }
            return newBaseSecret
        }

        fun start(context: Context, port: Int = 1443, secret: String = "") {
            val finalSecret = if (secret.isNotBlank()) secret else context.getTgProxyBaseSecret()
            val intent = Intent(context, TgWsProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_SECRET, finalSecret)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TgWsProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initSecret(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && !io.github.dovecoteescapee.byedpi.security.TamperGuard.verifyExecutionPermitted(this)) {
            Log.e(TAG, "TG Proxy start rejected: app integrity compromised")
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1443)
                var secret = intent.getStringExtra(EXTRA_SECRET) ?: ""
                if (secret.isBlank()) {
                    secret = getTgProxyBaseSecret()
                }
                startProxyServer(port, secret)
            }
            ACTION_STOP -> {
                stopProxyServer()
            }
            else -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram MTProto Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Служба локального MTProto прокси для Telegram"
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
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Telegram MTProto Proxy")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun isPortAvailable(bindIp: String, port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindIp), port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureCfProxyCache() {
        try {
            val cacheFile = java.io.File(cacheDir, "cfproxy-domains-cache.txt")
            val defaultDomains = listOf(
                "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
                "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
                "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
                "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com"
            )
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                cacheFile.writeText(defaultDomains.joinToString("\n"))
                Log.i(TAG, "Initialized cfproxy-domains-cache.txt with fallback domains")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure cfproxy cache", e)
        }
    }

    private fun startProxyServer(port: Int, secretKey: String) {
        if (_isRunning.value || stopInProgress) return

        val initialNotification = createNotification("Запуск MTProto прокси (порт $port)...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        stopInProgress = false

        Thread({
            val bindIp = "127.0.0.1"
            if (!isPortAvailable(bindIp, port)) {
                Log.e(TAG, "Port $port is not available")
                serviceScope.launch(Dispatchers.Main) {
                    stopProxyServer()
                }
                return@Thread
            }

            try {
                ensureCfProxyCache()
                // Settings matching tg-ws-proxy defaults
                NativeTgWsProxy.setPoolSize(4)
                NativeTgWsProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeTgWsProxy.setCfProxyConfig(true, true, "")

                val finalSecret = if (secretKey.isNotBlank()) secretKey else getTgProxyBaseSecret()
                setTgProxyBaseSecret(finalSecret)
                val result = NativeTgWsProxy.startProxy(bindIp, port, "", finalSecret, 1)

                if (result == 0) {
                    val secretWithPrefix = NativeTgWsProxy.getSecretWithPrefix() ?: finalSecret
                    setTgProxyEffectiveSecret(secretWithPrefix)
                    _effectiveSecret.value = secretWithPrefix
                    _isRunning.value = true
                    updateNotification("MTProto работает на порту $port")
                    Log.i(TAG, "Native MTProto proxy started on 127.0.0.1:$port, secret: $secretWithPrefix")

                    startStatsUpdater()
                } else {
                    Log.e(TAG, "Native startProxy error: $result")
                    serviceScope.launch(Dispatchers.Main) {
                        stopProxyServer()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start tg ws proxy", e)
                serviceScope.launch(Dispatchers.Main) {
                    stopProxyServer()
                }
            }
        }, "TgWsProxyThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun startStatsUpdater() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && _isRunning.value) {
                delay(3000.milliseconds)
                try {
                    val rawStats = NativeTgWsProxy.getStats() ?: continue
                    val upRaw = extractStat(rawStats, "up=")
                    val downRaw = extractStat(rawStats, "down=")
                    val activeConns = extractStat(rawStats, "active=")
                    val totalBytes = parseHumanBytes(upRaw) + parseHumanBytes(downRaw)
                    val active = activeConns.toIntOrNull() ?: 0
                    val statStr = "${formatBytes(totalBytes)} / $active соед."
                    _trafficStats.value = statStr
                    updateNotification("Трафик: $statStr")
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching stats: ${e.message}")
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun stopProxyServer() {
        if (stopInProgress) return
        stopInProgress = true
        statsJob?.cancel()
        statsJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                NativeTgWsProxy.stopProxy()
            } catch (e: Throwable) {
                Log.e(TAG, "Error stopping native proxy", e)
            }
            _isRunning.value = false
            _trafficStats.value = "0 B / 0 соед."
            stopInProgress = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyServer()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun generateRandomSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractStat(raw: String, key: String): String {
        val idx = raw.indexOf(key)
        if (idx < 0) return ""
        val start = idx + key.length
        val end = raw.indexOfAny(charArrayOf(' ', ',', '\n', '\t'), start).let {
            if (it < 0) raw.length else it
        }
        return raw.substring(start, end).trim()
    }

    private fun parseHumanBytes(text: String): Long {
        if (text.isBlank()) return 0L
        val trimmed = text.trim()
        val numPart = trimmed.filter { it.isDigit() || it == '.' }
        val unitPart = trimmed.filter { it.isLetter() }.uppercase()
        val num = numPart.toDoubleOrNull() ?: return 0L
        return when {
            unitPart.startsWith("G") -> (num * 1024 * 1024 * 1024).toLong()
            unitPart.startsWith("M") -> (num * 1024 * 1024).toLong()
            unitPart.startsWith("K") -> (num * 1024).toLong()
            else -> num.toLong()
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f КБ", bytes / 1024.0)
            else -> "$bytes Б"
        }
    }
}
