package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object WarpGenerator {

    private const val TAG = "WarpGenerator"

    private val ENDPOINTS = listOf(
        "https://www.warp-generator.workers.dev/",
        "https://warp.sub-aggregator.workers.dev/",
        "https://warp-gen.netlify.app/",
        "https://warp-vercel-chi.vercel.app/api/warp-data",
        "https://warp-vercel-murex.vercel.app/api/warp-data"
    )

    private val PORTS = listOf(
        500, 4500, 2408, 1701, 8854, 854, 8886, 7156, 8319, 8742, 5279
    )

    private val PREFIXES = listOf(
        "162.159.192.",
        "162.159.195.",
        "engage.cloudflareclient.com",
        "8.6.112.",
        "8.34.70.",
        "8.34.146.",
        "8.35.211.",
        "8.39.125.",
        "8.39.204.",
        "8.39.214.",
        "8.47.69.",
        "188.114.96.",
        "188.114.97.",
        "188.114.98."
    )

    private const val I1_VAL = "I1 = <b 0xce000000010897a297ecc34cd6dd000044d0ec2e2e1ea2991f467ace4222129b5a098823784694b4897b9986ae0b7280135fa85e196d9ad980b150122129ce2a9379531b0fd3e871ca5fdb883c369832f730e272d7b8b74f393f9f0fa43f11e510ecb2219a52984410c204cf875585340c62238e14ad04dff382f2c200e0ee22fe743b9c6b8b043121c5710ec289f471c91ee414fca8b8be8419ae8ce7ffc53837f6ade262891895f3f4cecd31bc93ac5599e18e4f01b472362b8056c3172b513051f8322d1062997ef4a383b01706598d08d48c221d30e74c7ce000cdad36b706b1bf9b0607c32ec4b3203a4ee21ab64df336212b9758280803fcab14933b0e7ee1e04a7becce3e2633f4852585c567894a5f9efe9706a151b615856647e8b7dba69ab357b3982f554549bef9256111b2d67afde0b496f16962d4957ff654232aa9e845b61463908309cfd9de0a6abf5f425f577d7e5f6440652aa8da5f73588e82e9470f3b21b27b28c649506ae1a7f5f15b876f56abc4615f49911549b9bb39dd804fde182bd2dcec0c33bad9b138ca07d4a4a1650a2c2686acea05727e2a78962a840ae428f55627516e73c83dd8893b02358e81b524b4d99fda6df52b3a8d7a5291326e7ac9d773c5b43b8444554ef5aea104a738ed650aa979674bbed38da58ac29d87c29d387d80b526065baeb073ce65f075ccb56e47533aef357dceaa8293a523c5f6f790be90e4731123d3c6152a70576e90b4ab5bc5ead01576c68ab633ff7d36dcde2a0b2c68897e1acfc4d6483aaaeb635dd63c96b2b6a7a2bfe042f6aed82e5363aa850aace12ee3b1a93f30d8ab9537df483152a5527faca21efc9981b304f11fc95336f5b9637b174c5a0659e2b22e159a9fed4b8e93047371175b1d6d9cc8ab745f3b2281537d1c75fb9451871864efa5d184c38c185fd203de206751b92620f7c369e031d2041e152040920ac2c5ab5340bfc9d0561176abf10a147287ea90758575ac6a9f5ac9f390d0d5b23ee12af583383d994e22c0cf42383834bcd3ada1b3825a0664d8f3fb678261d57601ddf94a8a68a7c273a18c08aa99c7ad8c6c42eab67718843597ec9930457359dfdfbce024afc2dcf9348579a57d8d3490b2fa99f278f1c37d87dad9b221acd575192ffae1784f8e60ec7cee4068b6b988f0433d96d6a1b1865f4e155e9fe020279f434f3bf1bd117b717b92f6cd1cc9bea7d45978bcc3f24bda631a36910110a6ec06da35f8966c9279d130347594f13e9e07514fa370754d1424c0a1545c5070ef9fb2acd14233e8a50bfc5978b5bdf8bc1714731f798d21e2004117c61f2989dd44f0cf027b27d4019e81ed4b5c31db347c4a3a4d85048d7093cf16753d7b0d15e078f5c7a5205dc2f87e330a1f716738dce1c6180e9d02869b5546f1c4d2748f8c90d9693cba4e0079297d22fd61402dea32ff0eb69ebd65a5d0b687d87e3a8b2c42b648aa723c7c7daf37abcc4bb85caea2ee8f55bec20e913b3324ab8f5c3304f820d42ad1b9f2ffc1a3af9927136b4419e1e579ab4c2ae3c776d293d397d575df181e6cae0a4ada5d67ecea171cca3288d57c7bbdaee3befe745fb7d634f70386d873b90c4d6c6596bb65af68f9e5121e67ebf0d89d3c909ceedfb32ce9575a7758ff080724e1ab5d5f43074ecb53a479af21ed03d7b6899c36631c0166f9d47e5e1d4528a5d3d3f744029c4b1c190cbfbad06f5f83f7ad0429fa9a2719c56ffe3783460e166de2d8>"

    data class WarpProfile(
        val privKey: String,
        val peerPub: String,
        val clientIpv4: String,
        val clientIpv6: String,
        val endpoint: String,
        val rawAwgConfig: String = ""
    ) {
        fun toAmneziaWgConfig(version: String = "3.1"): String {
            if (rawAwgConfig.isNotBlank() && rawAwgConfig.contains("H1 =") && rawAwgConfig.contains("I1 =")) {
                val clean = rawAwgConfig.trim()
                return clean.replace("engage.cloudflareclient.com:500", "162.159.192.1:500")
            }
            val (h1, h2, h3, h4) = listOf(1, 2, 3, 4)
            // Новая динамическая стратегия AWG 3.1+ со случайным джиттером
            val jc = if (version == "3.1") Random.nextInt(5, 9) else 7
            val jmin = Random.nextInt(42, 65)
            val jmax = Random.nextInt(190, 260)
            return """[Interface]
PrivateKey = $privKey
Address = $clientIpv4, $clientIpv6
DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
MTU = 1280
S1 = 0
S2 = 0
S3 = 0
S4 = 0
Jc = $jc
Jmin = $jmin
Jmax = $jmax
H1 = $h1
H2 = $h2
H3 = $h3
H4 = $h4
$I1_VAL

[Peer]
PublicKey = $peerPub
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = $endpoint
""".trimIndent()
        }
    }

    const val WARP_API_ENDPOINT = "https://warp-gen.cyb-portal.org/api/warp"
    val MASQUE_API_ENDPOINTS = listOf(
        "https://masque-vercel.warp-api.cfd/",
        "https://masque-workers.warp-api.cfd/"
    )

    data class WarpMasqueProfile(
        val privKey: String,
        val peerPub: String,
        val clientIpv4: String,
        val clientIpv6: String,
        val endpoint: String = "engage.cloudflareclient.com:443",
        val sni: String = "engage.cloudflareclient.com",
        val path: String = "/cdn-cgi/warp/masque",
        val network: String = "h3",
        val rawYaml: String = "",
        val deliveryId: String = "",
        val fileName: String = "warp_masque.yaml",
        val workingAwgConfig: String = ""
    ) {
        fun toMasqueUri(): String {
            return "masque://$privKey@$endpoint/?pub=$peerPub&ip=$clientIpv4&sni=$sni&network=$network#Cloudflare+WARP+MASQUE"
        }

        fun toClashYaml(): String = rawYaml

        fun toMasqueConfig(): WarpMasqueClient.MasqueConfig {
            val parts = endpoint.split(":")
            val host = parts[0].ifBlank { "162.159.198.2" }
            val port = if (parts.size > 1) {
                parts[1].toIntOrNull() ?: 443
            } else 443
            return WarpMasqueClient.MasqueConfig(
                privateKey = privKey,
                peerPublicKey = peerPub,
                clientIpv4 = clientIpv4,
                clientIpv6 = clientIpv6,
                endpointHost = host,
                endpointPort = port,
                sni = sni,
                path = path,
                rawYaml = rawYaml
            )
        }
    }

    private fun generateRandomEndpoint(): String {
        val port = 500
        val prefix = "162.159.192."
        val num = Random.nextInt(1, 5)
        return "$prefix$num:$port"
    }

    suspend fun generateConfig(): Result<WarpProfile> = withContext(Dispatchers.IO) {
        // 1. Попытка получить свежий зарегистрированный конфиг Cloudflare
        try {
            val url = URL(WARP_API_ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "WARP-Generator/2.0")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(body)
                val contentJson = json.optJSONObject("content")
                val importText = contentJson?.optString("importText").orEmpty()
                if (importText.isNotBlank()) {
                    val privKey = Regex("""(?m)^\s*PrivateKey\s*=\s*(.+)$""").find(importText)?.groupValues?.get(1)?.trim().orEmpty()
                    val peerPub = Regex("""(?m)^\s*PublicKey\s*=\s*(.+)$""").find(importText)?.groupValues?.get(1)?.trim() ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
                    val addrMatch = Regex("""(?m)^\s*Address\s*=\s*(.+)$""").find(importText)?.groupValues?.get(1)?.trim().orEmpty()
                    val addrs = addrMatch.split(",").map { it.trim() }
                    val ipv4 = addrs.firstOrNull { !it.contains(":") } ?: "172.16.0.2"
                    val ipv6 = addrs.firstOrNull { it.contains(":") } ?: "2606:4700:110:8700:31d8:595c:36c3:8014"

                    if (privKey.isNotBlank()) {
                        val profile = WarpProfile(
                            privKey = privKey,
                            peerPub = peerPub,
                            clientIpv4 = ipv4,
                            clientIpv6 = ipv6,
                            endpoint = "162.159.192.1:500"
                        )
                        return@withContext Result.success(profile)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary WARP generation notice: ${e.message}")
        }

        for (endpointUrl in ENDPOINTS) {
            try {
                Log.d(TAG, "Trying WARP endpoint: $endpointUrl")
                val url = URL(endpointUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("X-Client", "WARP")
                    setRequestProperty("User-Agent", "WARP-Generator/2.0")
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val json = JSONObject(body)
                    val privKey = json.optString("privKey")
                    val peerPub = json.optString("peer_pub", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
                    val ipv4 = json.optString("client_ipv4", "172.16.0.2")
                    val ipv6 = json.optString("client_ipv6", "2606:4700:110:8700:31d8:595c:36c3:8014")

                    if (privKey.isNotBlank()) {
                        val profile = WarpProfile(
                            privKey = privKey,
                            peerPub = peerPub,
                            clientIpv4 = ipv4,
                            clientIpv6 = ipv6,
                            endpoint = generateRandomEndpoint()
                        )
                        return@withContext Result.success(profile)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from $endpointUrl: ${e.message}")
            }
        }

        // Резервный зарегистрированный профиль
        Result.success(
            WarpProfile(
                privKey = "KZhXqHc7P3P1ahO+iLjv2ZjEu4eUdRPU942JuaEB4X8=",
                peerPub = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                clientIpv4 = "172.16.0.2",
                clientIpv6 = "2606:4700:110:8655:e6ef:f746:2058:dac0",
                endpoint = "162.159.192.1:500"
            )
        )
    }

    /**
     * Генерирует профиль Cloudflare WARP для протокола MASQUE (HTTP/3 / HTTP/2 CONNECT-UDP)
     * и связывает его с рабочим VPN-ядром.
     */
    suspend fun generateMasqueConfig(): Result<WarpMasqueProfile> = withContext(Dispatchers.IO) {
        var awgConfigText = WarpConfigManager.DEFAULT_CONFIG

        // Получаем свежий рабочий WARP конфиг
        try {
            val warpRes = (URL(WARP_API_ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "ByeDPI-Russia-Android/1.5.4")
            }
            if (warpRes.responseCode in 200..299) {
                val body = warpRes.inputStream.bufferedReader().use(BufferedReader::readText)
                val json = JSONObject(body)
                val txt = json.optJSONObject("content")?.optString("importText").orEmpty()
                if (txt.isNotBlank()) {
                    awgConfigText = txt.replace("engage.cloudflareclient.com:500", "162.159.192.1:500")
                }
            }
        } catch (_: Exception) {}

        for (apiUrl in MASQUE_API_ENDPOINTS) {
            try {
                val url = URL(apiUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "ByeDPI-Russia-Android/1.6.9.9")
                    setRequestProperty("Accept", "application/json")
                }

                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val rootJson = JSONObject(body)
                    if (rootJson.optBoolean("success", false)) {
                        val privKey = rootJson.optString("privKey").trim()
                        val pubKey = rootJson.optString("peer_pub").trim()
                        val ipv4 = rootJson.optString("client_ipv4", "172.16.0.2").trim()
                        val ipv6 = rootJson.optString("client_ipv6", "2606:4700:110:8235:bb6c:bdd:e4e3:b8d7").trim()

                        if (privKey.length > 80 && pubKey.isNotBlank()) {
                            Log.i(TAG, "Успешно получен свежий MASQUE профиль с $apiUrl")
                            return@withContext Result.success(
                                WarpMasqueProfile(
                                    privKey = privKey,
                                    peerPub = pubKey,
                                    clientIpv4 = ipv4,
                                    clientIpv6 = ipv6,
                                    endpoint = "162.159.198.2:443",
                                    sni = "",
                                    network = "h3",
                                    workingAwgConfig = awgConfigText
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MASQUE fetch ($apiUrl) notice: ${e.message}")
            }
        }

        // Резервный профиль с рабочим конфигом
        val fallback = WarpMasqueProfile(
            privKey = "MHcCAQEEINapwsyWJkQhfBxP/uy7gdKCuuYov0LnnLD8VT8qrTa4oAoGCCqGSM49AwEHoUQDQgAEL+4x2m4bQU/OXbynrQMkcgZrZotDlWP99EKFtd0itGTcXwO/0DGlNemoE95ngbcFUHf9VqODh83m1urjO90l2Q==",
            peerPub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIaU7MToJm9NKp8YfGxR6r+/h4mcG7SxI8tsW8OR1A5tv/zCzVbCRRh2t87/kxnP6lAy0lkr7qYwu+ox+k3dr6w==",
            clientIpv4 = "172.16.0.2",
            clientIpv6 = "2606:4700:110:8531:949:ea2f:a22b:5b02",
            endpoint = "162.159.198.2:443",
            sni = "4pda.to",
            network = "h2",
            workingAwgConfig = awgConfigText
        )
        Result.success(fallback)
    }

    private fun acknowledgeDelivery(ackUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = (URL(ackUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                Log.d(TAG, "MASQUE delivery ACK status: $code")
            } catch (e: Exception) {
                Log.w(TAG, "ACK error: ${e.message}")
            }
        }
    }

    /**
     * Локальный генератор AmneziaWG 3.1 конфига без внешних сторонних API.
     * Генерирует Curve25519 ключи на устройстве и регистрирует их напрямую в Cloudflare API.
     * При неудаче прямого соединения автоматически пробует через локальный SOCKS5-прокси ByeDPI.
     */
    suspend fun generateLocalAwgConfig(socksPort: Int = 1080): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (privKey, pubKey) = generateCurve25519KeyPair()
            val payload = JSONObject().apply {
                put("key", pubKey)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()))
                put("model", "Android")
                put("serial_number", "")
                put("locale", "en_US")
            }.toString()

            val regUrl = URL("https://api.cloudflareclient.com/v0a2158/reg")

            fun tryRegister(proxy: java.net.Proxy?): String? {
                val conn = (if (proxy != null) regUrl.openConnection(proxy) else regUrl.openConnection()) as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.setRequestProperty("User-Agent", "okhttp/3.12.1")
                conn.setRequestProperty("CF-Client-Version", "a-6.11-2223")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    return conn.inputStream.bufferedReader().use(BufferedReader::readText)
                }
                return null
            }

            var responseBody = try {
                tryRegister(null)
            } catch (e: Exception) {
                Log.w(TAG, "Direct Cloudflare reg failed: ${e.message}, trying via ByeDPI SOCKS5...")
                null
            }

            if (responseBody == null) {
                val socksProxy = java.net.Proxy(
                    java.net.Proxy.Type.SOCKS,
                    java.net.InetSocketAddress("127.0.0.1", socksPort)
                )
                responseBody = tryRegister(socksProxy)
            }

            if (responseBody.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Не удалось зарегистрировать ключ в Cloudflare (ни напрямую, ни через ByeDPI)"))
            }

            val json = JSONObject(responseBody)
            val configObj = json.getJSONObject("config")
            val ifaceObj = configObj.getJSONObject("interface")
            val addresses = ifaceObj.getJSONObject("addresses")
            val v4 = addresses.optString("v4", "172.16.0.2")
            val v6 = addresses.optString("v6", "2606:4700:110:8000::2")
            val peerObj = configObj.getJSONArray("peers").getJSONObject(0)
            val peerPub = peerObj.optString("public_key", "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")
            val endpoint = WarpEndpointOptimizer.CANDIDATE_ENDPOINTS.firstOrNull() ?: "162.159.192.1:500"

            val awgConfig = """
                [Interface]
                PrivateKey = $privKey
                Address = $v4/32, $v6/128
                DNS = 1.1.1.1, 1.0.0.1
                MTU = 1280
                Jc = 4
                Jmin = 40
                Jmax = 70
                S1 = 0
                S2 = 0
                H1 = 1
                H2 = 2
                H3 = 3
                H4 = 4

                [Peer]
                PublicKey = $peerPub
                AllowedIPs = 0.0.0.0/0, ::/0
                Endpoint = $endpoint
                PersistentKeepalive = 25
            """.trimIndent()

            Result.success(awgConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Local AWG generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Генерация пары ключей Curve25519 (X25519, RFC 7748) локально на устройстве.
     */
    private fun generateCurve25519KeyPair(): Pair<String, String> {
        val priv = ByteArray(32)
        java.security.SecureRandom().nextBytes(priv)
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()

        val pub = x25519BasePoint(priv)
        val privB64 = android.util.Base64.encodeToString(priv, android.util.Base64.NO_WRAP)
        val pubB64 = android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP)
        return privB64 to pubB64
    }

    private fun x25519BasePoint(scalar: ByteArray): ByteArray {
        val p = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(19))
        val a24 = java.math.BigInteger.valueOf(121665)

        var x1 = java.math.BigInteger.valueOf(9)
        var x2 = java.math.BigInteger.ONE
        var z2 = java.math.BigInteger.ZERO
        var x3 = x1
        var z3 = java.math.BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kT = ((scalar[t / 8].toInt() shr (t and 7)) and 1)
            swap = swap xor kT
            if (swap != 0) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kT

            val a = x2.add(z2).mod(p)
            val aa = a.multiply(a).mod(p)
            val b = x2.subtract(z2).mod(p)
            val bb = b.multiply(b).mod(p)
            val e = aa.subtract(bb).mod(p)
            val c = x3.add(z3).mod(p)
            val d = x3.subtract(z3).mod(p)
            val da = d.multiply(a).mod(p)
            val cb = c.multiply(b).mod(p)
            x3 = da.add(cb).modPow(java.math.BigInteger.valueOf(2), p)
            z3 = x1.multiply(da.subtract(cb).modPow(java.math.BigInteger.valueOf(2), p)).mod(p)
            x2 = aa.multiply(bb).mod(p)
            z2 = e.multiply(aa.add(a24.multiply(e))).mod(p)
        }
        if (swap != 0) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        val res = x2.multiply(z2.modInverse(p)).mod(p)
        val out = ByteArray(32)
        val bytes = res.toByteArray()
        for (i in 0 until minOf(32, bytes.size)) {
            out[i] = bytes[bytes.size - 1 - i]
        }
        return out
    }
}
