package io.github.dovecoteescapee.byedpi.warp

import android.util.Log
import com.hiddify.core.libbox.CommandServerHandler
import com.hiddify.core.libbox.SystemProxyStatus

class HiddifyCommandHandler : CommandServerHandler {
    companion object {
        private const val TAG = "HiddifyCoreHandler"
    }

    override fun connectSSHAgent(): Int = -1

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun serviceReload() {}

    override fun serviceStop() {}

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) {
            Log.d(TAG, message)
        }
    }
}
