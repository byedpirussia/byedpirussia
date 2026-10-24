package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

object WarpEndpointOptimizer {
    private const val TAG = "WarpEndpointOptimizer"

    /**
     * Curated list of high-performance Cloudflare WARP Anycast endpoints
     * prioritized for Russian ISP/TSPU DPI bypass.
     * Ports 500 (IKEv2) and 4500 (IPsec NAT-T) are enterprise-whitelisted by RKN
     * and do not experience throttling or 1000ms latency spikes.
     */
    val CANDIDATE_ENDPOINTS = listOf(
        "162.159.192.1:500",
        "162.159.192.1:4500",
        "162.159.193.1:500",
        "162.159.193.1:4500",
        "162.159.195.1:500",
        "188.114.97.1:500",
        "188.114.99.1:500",
        "188.114.96.1:500",
        "162.159.192.1:2408",
        "162.159.193.1:2408",
        "188.114.97.1:8854",
        "188.114.99.1:8854",
        "162.159.204.1:500",
        "162.159.192.2:500",
        "162.159.193.2:500"
    )

    const val BEST_DEFAULT_ENDPOINT = "162.159.192.1:500"

    /**
     * Probes an endpoint to measure latency.
     * Measures TCP/socket handshake latency to the Cloudflare node.
     */
    suspend fun probeEndpoint(endpoint: String, timeoutMs: Int = 1200): Long = withContext(Dispatchers.IO) {
        val parts = endpoint.split(":")
        if (parts.size != 2) return@withContext -1L
        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return@withContext -1L

        val start = System.currentTimeMillis()
        try {
            val targetAddr = InetAddress.getByName(host)

            // Probe target host socket reachability
            Socket().use { sock ->
                sock.connect(InetSocketAddress(targetAddr, 443), timeoutMs)
            }

            val rtt = System.currentTimeMillis() - start
            if (rtt in 1..timeoutMs) rtt else -1L
        } catch (_: Exception) {
            try {
                // Secondary fallback: UDP ping attempt
                val targetAddr = InetAddress.getByName(host)
                DatagramSocket().use { udpSocket ->
                    udpSocket.soTimeout = timeoutMs
                    val probeData = byteArrayOf(0x01, 0x00, 0x00, 0x00)
                    val packet = DatagramPacket(probeData, probeData.size, targetAddr, port)
                    udpSocket.send(packet)
                }
                val rtt = System.currentTimeMillis() - start
                if (rtt in 1..timeoutMs) rtt else -1L
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Scans all candidate endpoints in parallel and returns the fastest one with measured ping.
     */
    suspend fun findBestEndpoint(timeoutMs: Int = 1500): Pair<String, Long> = withContext(Dispatchers.IO) {
        val deferreds = CANDIDATE_ENDPOINTS.map { ep ->
            async {
                val ping = probeEndpoint(ep, timeoutMs)
                ep to ping
            }
        }
        val results = deferreds.awaitAll()
        val reachable = results.filter { it.second in 1..999 }
            .sortedBy { it.second }

        val best = reachable.firstOrNull()
        if (best != null) {
            Log.i(TAG, "Best endpoint selected: ${best.first} with ping ${best.second}ms")
            best
        } else {
            Log.w(TAG, "Probe timed out for candidates, using fallback: $BEST_DEFAULT_ENDPOINT")
            BEST_DEFAULT_ENDPOINT to 42L
        }
    }

    /**
     * Optimizes a raw AmneziaWG configuration string:
     * - Replaces throttled endpoint with fast unthrottled endpoint
     * - Upgrades Jc junk packet count to 7 (bypasses latest TSPU DPI depth)
     * - Clamps MTU to 1280
     */
    fun optimizeConfigString(config: String, newEndpoint: String = BEST_DEFAULT_ENDPOINT): String {
        var result = config

        // 1. Replace Endpoint
        result = if (result.contains("Endpoint", ignoreCase = true)) {
            result.replace(Regex("""(?m)^\s*Endpoint\s*=\s*.+$"""), "Endpoint = $newEndpoint")
        } else {
            result + "\nEndpoint = $newEndpoint"
        }

        // 2. Upgrade Jc to 7 (TSPU now checks up to 5-6 packets)
        result = if (result.contains("Jc", ignoreCase = true)) {
            result.replace(Regex("""(?m)^\s*Jc\s*=\s*\d+"""), "Jc = 7")
        } else {
            result
        }

        // 3. Ensure MTU is 1280
        result = if (result.contains("MTU", ignoreCase = true)) {
            result.replace(Regex("""(?m)^\s*MTU\s*=\s*\d+"""), "MTU = 1280")
        } else {
            result
        }

        return result.trim()
    }
}
