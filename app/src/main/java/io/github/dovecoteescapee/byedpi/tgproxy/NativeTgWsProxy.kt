package io.github.dovecoteescapee.byedpi.tgproxy

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface TgWsProxyLibrary : Library {
    companion object {
        val INSTANCE: TgWsProxyLibrary by lazy {
            Native.load("tgwsproxy", TgWsProxyLibrary::class.java) as TgWsProxyLibrary
        }
    }

    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetBufferSizeKb(kb: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetCfWorkerDomains(domains: String)
    fun SetFakeTlsDomain(domain: String)
    fun SetDisableSecure(v: Int)
    fun SetForceTestDc(v: Int)
    fun SetProxyProtocol(v: Int)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object NativeTgWsProxy {
    fun startProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int = 1): Int {
        return TgWsProxyLibrary.INSTANCE.StartProxy(host, port, dcIps, secret, verbose)
    }

    fun stopProxy(): Int {
        return TgWsProxyLibrary.INSTANCE.StopProxy()
    }

    fun setPoolSize(size: Int) {
        TgWsProxyLibrary.INSTANCE.SetPoolSize(size)
    }

    fun setBufferSizeKb(kb: Int) {
        TgWsProxyLibrary.INSTANCE.SetBufferSizeKb(kb)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        TgWsProxyLibrary.INSTANCE.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        TgWsProxyLibrary.INSTANCE.SetCfProxyConfig(
            if (enabled) 1 else 0,
            if (priority) 1 else 0,
            userDomain
        )
    }

    fun setCfWorkerDomains(domains: String) {
        TgWsProxyLibrary.INSTANCE.SetCfWorkerDomains(domains)
    }

    fun setFakeTlsDomain(domain: String) {
        TgWsProxyLibrary.INSTANCE.SetFakeTlsDomain(domain)
    }

    fun setDisableSecure(v: Boolean) {
        TgWsProxyLibrary.INSTANCE.SetDisableSecure(if (v) 1 else 0)
    }

    fun setForceTestDc(v: Boolean) {
        TgWsProxyLibrary.INSTANCE.SetForceTestDc(if (v) 1 else 0)
    }

    fun setProxyProtocol(v: Boolean) {
        TgWsProxyLibrary.INSTANCE.SetProxyProtocol(if (v) 1 else 0)
    }

    /** Returns full secret with ee + domain hex prefix */
    fun getSecretWithPrefix(): String? {
        val ptr = TgWsProxyLibrary.INSTANCE.GetSecretWithPrefix() ?: return null
        val res = ptr.getString(0)
        TgWsProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }

    fun getStats(): String? {
        val ptr = TgWsProxyLibrary.INSTANCE.GetStats() ?: return null
        val res = ptr.getString(0)
        TgWsProxyLibrary.INSTANCE.FreeString(ptr)
        return res
    }
}
