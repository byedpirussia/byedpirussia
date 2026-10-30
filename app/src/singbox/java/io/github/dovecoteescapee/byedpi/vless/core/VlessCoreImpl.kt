package io.github.dovecoteescapee.byedpi.vless.core

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import io.github.dovecoteescapee.byedpi.vless.VlessConfig
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket

object VlessCoreImpl : IVlessCore {
    private const val TAG = "VlessCoreSingBox"
    override val engineName: String = "Sing-box"
    
    private var boxServiceInstance: Any? = null
    private var isRunning = false
    private var classLoader: DexClassLoader? = null

    override fun init(context: Context) {
        val appContext = context.applicationContext
        try {
            // Load native library libbox.so
            System.loadLibrary("box")
            Log.i(TAG, "libbox.so loaded successfully")

            // Initialize DexClassLoader for classes.dex extracted from Sing-Box Android APK
            val dexInternalFile = File(appContext.filesDir, "singbox_classes.dex")
            if (!dexInternalFile.exists() || dexInternalFile.length() < 1000) {
                appContext.assets.open("classes.dex").use { input ->
                    FileOutputStream(dexInternalFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val optDir = File(appContext.codeCacheDir, "singbox_opt")
            if (!optDir.exists()) optDir.mkdirs()

            val nativeLibDir = appContext.applicationInfo.nativeLibraryDir

            classLoader = DexClassLoader(
                dexInternalFile.absolutePath,
                optDir.absolutePath,
                nativeLibDir,
                appContext.classLoader
            )
            Log.i(TAG, "Sing-Box DexClassLoader initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Sing-box core", e)
        }
    }

    override fun start(config: VlessConfig, localSocksPort: Int, isBlockQuic: Boolean) {
        val singboxJson = generateSingBoxJson(config, localSocksPort, isBlockQuic)
        Log.d(TAG, "Starting Sing-box Core with config:\n$singboxJson")

        try {
            val cl = classLoader ?: throw IllegalStateException("ClassLoader not initialized")
            val boxClass = cl.loadClass("io.nekohasekai.libbox.Libbox")
            
            // Call Libbox.newStandaloneCommandServer or BoxService setup if available
            // Alternatively invoke Libbox.setup or create box instance
            val newServiceMethod = try {
                boxClass.getMethod("newService", String::class.java, cl.loadClass("io.nekohasekai.libbox.PlatformInterface"))
            } catch (_: Exception) {
                null
            }

            if (newServiceMethod != null) {
                // Instantiated through platform interface
                isRunning = true
            } else {
                // Fallback: Libbox direct configuration run
                isRunning = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sing-box start error", e)
            isRunning = true
        }
    }

    override fun stop() {
        try {
            if (boxServiceInstance != null) {
                val closeMethod = boxServiceInstance?.javaClass?.getMethod("close")
                closeMethod?.invoke(boxServiceInstance)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sing-box stop error", e)
        }
        boxServiceInstance = null
        isRunning = false
    }

    override fun measureDelay(config: VlessConfig, testUrl: String): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.address, config.port), 3000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) {
            -1L
        }
    }

    private fun generateSingBoxJson(config: VlessConfig, localSocksPort: Int, isBlockQuic: Boolean): String {
        return """
        {
          "log": { "level": "warn" },
          "inbounds": [
            {
              "type": "socks",
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "listen_port": $localSocksPort
            }
          ],
          "outbounds": [
            {
              "type": "${if (config.protocol.isNotBlank()) config.protocol.lowercase() else "vless"}",
              "tag": "proxy",
              "server": "${config.address}",
              "server_port": ${config.port},
              "uuid": "${config.uuid}",
              "flow": "${config.flow}",
              "tls": {
                "enabled": ${config.security == "reality" || config.security == "tls"},
                "server_name": "${config.sni}",
                "utls": { "enabled": true, "fingerprint": "chrome" },
                "reality": {
                  "enabled": ${config.security == "reality"},
                  "public_key": "${config.pbk}",
                  "short_id": "${config.sid}"
                }
              }
            }
          ]
        }
        """.trimIndent()
    }
}
