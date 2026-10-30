package io.github.dovecoteescapee.byedpi.vless.core

import android.content.Context
import io.github.dovecoteescapee.byedpi.vless.VlessConfig

interface IVlessCore {
    val engineName: String
    fun init(context: Context)
    fun start(config: VlessConfig, localSocksPort: Int, isBlockQuic: Boolean)
    fun stop()
    fun measureDelay(config: VlessConfig, testUrl: String): Long
}
