package io.github.dovecoteescapee.byedpi.vless.core

import android.content.Context
import android.util.Log
import io.github.dovecoteescapee.byedpi.vless.VlessConfig
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

object VlessCoreImpl : IVlessCore {
    private const val TAG = "VlessCoreXray"
    override val engineName: String = "Xray-core"
    private var coreController: CoreController? = null

    override fun init(context: Context) {
        try {
            val assetDir = File(context.applicationContext.filesDir, "xray_assets")
            if (!assetDir.exists()) assetDir.mkdirs()
            Libv2ray.initCoreEnv(assetDir.absolutePath, "")
        } catch (e: Exception) {
            Log.e(TAG, "InitCoreEnv error", e)
        }
    }

    override fun start(config: VlessConfig, localSocksPort: Int, isBlockQuic: Boolean) {
        val callback = object : CoreCallbackHandler {
            override fun onEmitStatus(status: Long, msg: String?): Long = 0
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
        }
        val controller = Libv2ray.newCoreController(callback)
        coreController = controller
        val configJson = config.toXrayConfigJson(localSocksPort = localSocksPort, blockQuic = isBlockQuic)
        Log.d(TAG, "Starting Xray Core with config:\n$configJson")
        controller.startLoop(configJson, 0)
    }

    override fun stop() {
        try {
            coreController?.stopLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Xray core", e)
        }
        coreController = null
    }

    override fun measureDelay(config: VlessConfig, testUrl: String): Long {
        val xrayJson = config.toXrayConfigJson(localSocksPort = 0)
        return Libv2ray.measureOutboundDelay(xrayJson, testUrl)
    }
}
