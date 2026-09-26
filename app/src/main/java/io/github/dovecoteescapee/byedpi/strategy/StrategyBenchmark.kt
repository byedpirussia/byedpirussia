package io.github.dovecoteescapee.byedpi.strategy

import android.content.Context
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyCmdPreferences
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class StrategyResult(
    val strategy: Strategy,
    val successCount: Int,
    val averageLatencyMs: Long,
    val details: Map<String, Long?>
)

class StrategyBenchmark(private val context: Context) {
    companion object {
        private const val TAG = "StrategyBenchmark"
        private const val TEST_PORT = 11080
        private const val CONNECT_TIMEOUT_MS = 2500
        private const val HANDSHAKE_TIMEOUT_MS = 3000

        val TEST_TARGETS = listOf(
            "www.youtube.com" to "YouTube",
            "discord.com" to "Discord"
        )

        private val FALLBACK_IPS = mapOf(
            "www.youtube.com" to "142.251.152.4",
            "discord.com" to "162.159.137.232"
        )
    }

    suspend fun runBenchmark(
        onProgress: (current: Int, total: Int, strategy: Strategy, status: String) -> Unit,
        onStrategyTested: (result: StrategyResult) -> Unit = {}
    ): StrategyResult? = withContext(Dispatchers.IO) {
        val strategies = StrategyCatalog.strategies
        val results = mutableListOf<StrategyResult>()

        for ((index, strategy) in strategies.withIndex()) {
            if (!coroutineContext.isActive) break

            withContext(Dispatchers.Main) {
                onProgress(
                    index + 1,
                    strategies.size,
                    strategy,
                    "${strategy.name}..."
                )
            }

            val proxy = ByeDpiProxy()
            // -X отключает IPv6, так как на многих мобильных операторах в РФ IPv6 недоступен и приводит к задержкам
            val cmd = "-i 127.0.0.1 -p $TEST_PORT -X ${strategy.args}"
            val prefs = ByeDpiProxyCmdPreferences(cmd)

            val proxyJob = launch(Dispatchers.IO) {
                try {
                    proxy.startProxy(prefs)
                } catch (e: Exception) {
                    Log.d(TAG, "Test proxy ended: ${e.message}")
                }
            }

            // Даем прокси 150мс на открытие сокета
            delay(150)

            try {
                val targetResults = mutableMapOf<String, Long?>()
                var totalLatency = 0L
                var successes = 0

                for ((targetIdx, target) in TEST_TARGETS.withIndex()) {
                    if (!coroutineContext.isActive) break

                    val (host, label) = target
                    withContext(Dispatchers.Main) {
                        onProgress(
                            index + 1,
                            strategies.size,
                            strategy,
                            "${strategy.name}: $label (${targetIdx + 1}/${TEST_TARGETS.size})"
                        )
                    }

                    val latency = testTlsHandshake(host, TEST_PORT)
                    targetResults[label] = latency
                    if (latency != null) {
                        successes++
                        totalLatency += latency
                        Log.i(TAG, "[OK] $label (${latency}ms) - ${strategy.name}")
                    } else {
                        Log.d(TAG, "[FAIL] $label - ${strategy.name}")
                    }
                }

                val avgLatency = if (successes > 0) totalLatency / successes else 9999L
                val res = StrategyResult(strategy, successes, avgLatency, targetResults)
                results.add(res)

                withContext(Dispatchers.Main) {
                    onStrategyTested(res)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Benchmark error on ${strategy.name}", e)
            } finally {
                try {
                    proxy.stopProxy()
                } catch (_: Exception) {}
                proxyJob.cancel()
                delay(100)
            }
        }

        // Выбираем стратегию с наибольшим числом доступных сервисов и наименьшей задержкой
        val best = results
            .filter { it.successCount > 0 }
            .minWithOrNull(compareByDescending<StrategyResult> { it.successCount }.thenBy { it.averageLatencyMs })

        best?.let {
            applyStrategy(it.strategy)
        }

        best
    }

    fun applyStrategy(strategy: Strategy) {
        val sp = context.getPreferences()
        sp.edit()
            .putBoolean("byedpi_enable_cmd_settings", true)
            .putString("byedpi_cmd_args", strategy.args)
            .putString("selected_strategy_id", strategy.id)
            .apply()
        Log.i(TAG, "Applied strategy: ${strategy.name} (${strategy.args})")
    }

    private fun testTlsHandshake(host: String, proxyPort: Int): Long? {
        val startTime = System.currentTimeMillis()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            val ipAddress = resolveIpv4(host)

            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
            rawSocket = Socket(socksProxy)
            rawSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(ipAddress, 443), CONNECT_TIMEOUT_MS)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslSocket = sslFactory.createSocket(rawSocket, host, 443, true) as SSLSocket
            sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS

            val sslParams = SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(host))
            sslSocket.sslParameters = sslParams

            sslSocket.startHandshake()
            val latency = System.currentTimeMillis() - startTime
            latency
        } catch (e: Exception) {
            null
        } finally {
            try {
                sslSocket?.close()
                rawSocket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun resolveIpv4(host: String): String {
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
                ?: FALLBACK_IPS[host]
                ?: host
        } catch (e: Exception) {
            FALLBACK_IPS[host] ?: host
        }
    }
}
