package io.github.dovecoteescapee.byedpi.warp

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.util.Base64
import android.util.Log
import com.hiddify.core.libbox.*
import java.security.KeyStore

class HiddifyPlatformInterface(private val context: Context) : PlatformInterface {

    companion object {
        private const val TAG = "HiddifyPlatform"
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        if (context is VpnService) {
            context.protect(fd)
        }
    }

    override fun cancelNotification(tag: String?, id: Int) {}

    override fun checkPlatformShell() {}

    override fun clearDNSCache() {}

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession {
        throw UnsupportedOperationException("AutoRedirect not supported")
    }

    override fun createBridge(options: BridgeOptions?): BridgeSession {
        throw UnsupportedOperationException("Bridge not supported")
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        return ConnectionOwner()
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = false
            override fun next(): NetworkInterface = NetworkInterface()
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun lookupSFTPServer(): String = ""

    override fun lookupUser(userName: String?): PlatformUser = PlatformUser()

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession {
        throw UnsupportedOperationException("Shell not supported")
    }

    override fun openTun(options: TunOptions?): Int {
        // Мы используем SOCKS5 + hev-socks5-tunnel для максимальной надежности
        return -1
    }

    override fun readSystemSSHHostKey(): String = ""

    override fun readWIFIState(): WIFIState? = null

    override fun registerMyInterface(name: String?) {}

    override fun sendNotification(notification: Notification?) {}

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        try {
            val keyStore = KeyStore.getInstance("AndroidCAStore")
            if (keyStore != null) {
                keyStore.load(null, null)
                val aliases = keyStore.aliases()
                while (aliases.hasMoreElements()) {
                    val cert = keyStore.getCertificate(aliases.nextElement()) ?: continue
                    certificates.add(
                        "-----BEGIN CERTIFICATE-----\n" +
                            Base64.encodeToString(cert.encoded, Base64.NO_WRAP) +
                            "\n-----END CERTIFICATE-----"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "systemCertificates error", e)
        }
        return object : StringIterator {
            private val iter = certificates.iterator()
            override fun len(): Int = certificates.size
            override fun hasNext(): Boolean = iter.hasNext()
            override fun next(): String = iter.next()
        }
    }

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false
}
