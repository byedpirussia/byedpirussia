package io.github.dovecoteescapee.byedpi.openflux

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

interface OpenFluxLibrary : Library {
    companion object {
        val INSTANCE: OpenFluxLibrary by lazy {
            Native.load("openflux", OpenFluxLibrary::class.java) as OpenFluxLibrary
        }
    }

    fun OpenFluxStartProxy(
        transportType: String,
        documentURL: String,
        encryptionSecret: String,
        codec: String,
        maxToken: String,
        maxUid: String,
        listenAddr: String,
        username: String,
        password: String,
        bypassDomains: String
    ): Pointer?

    fun OpenFluxStartSessionProxy(
        specsJSON: String,
        encryptionSecret: String,
        listenAddr: String,
        username: String,
        password: String,
        bypassDomains: String
    ): Pointer?

    fun OpenFluxStartStreamProxy(
        transportType: String,
        url: String,
        listenAddr: String,
        username: String,
        password: String,
        bypassDomains: String
    ): Pointer?

    fun OpenFluxStopProxy()
    fun OpenFluxProxyIsConnected(): Int
    fun OpenFluxProxyIsRunning(): Int
    fun OpenFluxProxyBytesSent(): Long
    fun OpenFluxProxyBytesReceived(): Long
    fun OpenFluxReadLogs(): Pointer?
    fun OpenFluxReadShareLink(link: String): Pointer?
    fun OpenFluxParseShareLink(link: String): Pointer?
    fun OpenFluxShareSessionSpecs(link: String): Pointer?
    fun OpenFluxMakeShareLink(configJSON: String): Pointer?
    fun OpenFluxSetDebugLevel(level: Int)
    fun OpenFluxSetLowMemory(on: Int)
    fun OpenFluxFreeString(p: Pointer)
}

object NativeOpenFlux {

    private fun ptrToStringAndFree(ptr: Pointer?): String {
        if (ptr == null) return ""
        val s = ptr.getString(0) ?: ""
        OpenFluxLibrary.INSTANCE.OpenFluxFreeString(ptr)
        return s
    }

    fun startProxy(
        transportType: String,
        documentURL: String,
        encryptionSecret: String,
        codec: String = "batched",
        maxToken: String = "",
        maxUid: String = "",
        listenAddr: String = "127.0.0.1:10885",
        username: String = "",
        password: String = "",
        bypassDomains: String = ""
    ): String {
        val ptr = OpenFluxLibrary.INSTANCE.OpenFluxStartProxy(
            transportType, documentURL, encryptionSecret, codec,
            maxToken, maxUid, listenAddr, username, password, bypassDomains
        )
        return ptrToStringAndFree(ptr)
    }

    fun startSessionProxy(
        specsJSON: String,
        encryptionSecret: String,
        listenAddr: String = "127.0.0.1:10885",
        username: String = "",
        password: String = "",
        bypassDomains: String = ""
    ): String {
        val ptr = OpenFluxLibrary.INSTANCE.OpenFluxStartSessionProxy(
            specsJSON, encryptionSecret, listenAddr, username, password, bypassDomains
        )
        return ptrToStringAndFree(ptr)
    }

    fun startStreamProxy(
        transportType: String,
        url: String,
        listenAddr: String = "127.0.0.1:10885",
        username: String = "",
        password: String = "",
        bypassDomains: String = ""
    ): String {
        val ptr = OpenFluxLibrary.INSTANCE.OpenFluxStartStreamProxy(
            transportType, url, listenAddr, username, password, bypassDomains
        )
        return ptrToStringAndFree(ptr)
    }

    fun stopProxy() {
        try {
            OpenFluxLibrary.INSTANCE.OpenFluxStopProxy()
        } catch (_: Exception) {}
    }

    fun isConnected(): Boolean {
        return try {
            OpenFluxLibrary.INSTANCE.OpenFluxProxyIsConnected() != 0
        } catch (_: Exception) {
            false
        }
    }

    fun isRunning(): Boolean {
        return try {
            OpenFluxLibrary.INSTANCE.OpenFluxProxyIsRunning() != 0
        } catch (_: Exception) {
            false
        }
    }

    fun bytesSent(): Long {
        return try {
            OpenFluxLibrary.INSTANCE.OpenFluxProxyBytesSent()
        } catch (_: Exception) {
            0L
        }
    }

    fun bytesReceived(): Long {
        return try {
            OpenFluxLibrary.INSTANCE.OpenFluxProxyBytesReceived()
        } catch (_: Exception) {
            0L
        }
    }

    fun readLogs(): String {
        return try {
            ptrToStringAndFree(OpenFluxLibrary.INSTANCE.OpenFluxReadLogs())
        } catch (_: Exception) {
            ""
        }
    }

    fun readShareLink(link: String): String {
        return try {
            ptrToStringAndFree(OpenFluxLibrary.INSTANCE.OpenFluxReadShareLink(link))
        } catch (_: Exception) {
            ""
        }
    }

    fun parseShareLink(link: String): String {
        return try {
            ptrToStringAndFree(OpenFluxLibrary.INSTANCE.OpenFluxParseShareLink(link))
        } catch (_: Exception) {
            ""
        }
    }

    fun shareSessionSpecs(link: String): String {
        return try {
            ptrToStringAndFree(OpenFluxLibrary.INSTANCE.OpenFluxShareSessionSpecs(link))
        } catch (_: Exception) {
            ""
        }
    }

    fun makeShareLink(configJSON: String): String {
        return try {
            ptrToStringAndFree(OpenFluxLibrary.INSTANCE.OpenFluxMakeShareLink(configJSON))
        } catch (_: Exception) {
            ""
        }
    }

    fun setDebugLevel(level: Int) {
        try {
            OpenFluxLibrary.INSTANCE.OpenFluxSetDebugLevel(level)
        } catch (_: Exception) {}
    }

    fun setLowMemory(on: Boolean) {
        try {
            OpenFluxLibrary.INSTANCE.OpenFluxSetLowMemory(if (on) 1 else 0)
        } catch (_: Exception) {}
    }
}
