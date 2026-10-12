package io.github.dovecoteescapee.byedpi.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import io.github.dovecoteescapee.byedpi.backup.BackupManager
import io.github.dovecoteescapee.byedpi.tv.TvNavigationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*

/**
 * P2P Data Sync & Remote Control Service for ByeDPI Russia.
 * Enables zero-config Wi-Fi / Hotspot data transfer between phones and Android TV / other phones,
 * plus remote control capabilities with explicit user consent.
 */
object DataSyncManager {
    private const val TAG = "DataSyncManager"
    const val SYNC_PORT = 19842
    const val DISCOVERY_PORT = 19843

    data class DiscoveredServer(
        val ip: String,
        val deviceName: String,
        val isTv: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    var serverSocket: ServerSocket? = null
    var discoverySocket: DatagramSocket? = null
    var isServerRunning = false

    // Pending incoming connection that requires user approval on the Import side
    data class PendingImportSession(
        val clientIp: String,
        val clientDeviceName: String,
        val socket: Socket,
        val onApproved: (Boolean) -> Unit
    )

    var pendingSession: PendingImportSession? = null

    fun getDeviceName(context: Context): String {
        val model = Build.MODEL ?: "Android Device"
        val brand = Build.BRAND ?: ""
        val type = if (TvNavigationHelper.isTv(context)) " (Android TV)" else ""
        return "$brand $model$type".trim()
    }

    /**
     * Finds local IP address of current device (Wi-Fi or AP hotspot).
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return "127.0.0.1"
    }

    /**
     * Start Import Server (waits for clients and broadcasts presence over UDP).
     */
    suspend fun startImportServer(
        context: Context,
        onClientPending: (clientName: String, clientIp: String, onDecision: (Boolean) -> Unit) -> Unit,
        onDataReceived: (backupJson: String) -> Unit,
        onRemoteCommand: (command: String, param: String?) -> String,
        onSessionClosed: () -> Unit
    ) = withContext(Dispatchers.IO) {
        stopServer()

        try {
            serverSocket = ServerSocket(SYNC_PORT).apply {
                reuseAddress = true
            }
            isServerRunning = true
            Log.d(TAG, "Import Server started on port $SYNC_PORT")

            // Start UDP discovery responder
            startDiscoveryResponder(context)

            while (isServerRunning && serverSocket?.isClosed == false) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    val clientIp = clientSocket.inetAddress.hostAddress ?: "Unknown"
                    Log.d(TAG, "Incoming connection from $clientIp")

                    handleClientConnection(
                        clientSocket,
                        clientIp,
                        onClientPending,
                        onDataReceived,
                        onRemoteCommand,
                        onSessionClosed
                    )
                } catch (e: Exception) {
                    if (isServerRunning) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopServer()
        }
    }

    private fun startDiscoveryResponder(context: Context) {
        Thread {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                }
                val buffer = ByteArray(1024)
                while (isServerRunning && discoverySocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)

                    if (message.startsWith("BYEDPI_DISCOVER")) {
                        val deviceName = getDeviceName(context)
                        val isTv = TvNavigationHelper.isTv(context)
                        val reply = "BYEDPI_OFFER:$deviceName:$isTv"
                        val replyBytes = reply.toByteArray()
                        val replyPacket = DatagramPacket(
                            replyBytes,
                            replyBytes.size,
                            packet.address,
                            packet.port
                        )
                        discoverySocket?.send(replyPacket)
                    }
                }
            } catch (e: Exception) {
                if (isServerRunning) {
                    Log.e(TAG, "Discovery responder error", e)
                }
            }
        }.start()
    }

    private fun handleClientConnection(
        socket: Socket,
        clientIp: String,
        onClientPending: (clientName: String, clientIp: String, onDecision: (Boolean) -> Unit) -> Unit,
        onDataReceived: (backupJson: String) -> Unit,
        onRemoteCommand: (command: String, param: String?) -> String,
        onSessionClosed: () -> Unit
    ) {
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // First line must be handshake: "HELLO:DeviceName"
                val helloLine = reader.readLine() ?: return@Thread
                val clientName = if (helloLine.startsWith("HELLO:")) {
                    helloLine.substringAfter("HELLO:")
                } else {
                    "Устройство $clientIp"
                }

                // Wait for user approval on Import side
                val approvedLock = Object()
                var isApproved = false
                var decisionMade = false

                onClientPending(clientName, clientIp) { decision ->
                    synchronized(approvedLock) {
                        isApproved = decision
                        decisionMade = true
                        approvedLock.notifyAll()
                    }
                }

                synchronized(approvedLock) {
                    while (!decisionMade) {
                        approvedLock.wait(1000)
                    }
                }

                if (!isApproved) {
                    writer.println("REJECTED")
                    socket.close()
                    return@Thread
                }

                writer.println("ACCEPTED")

                // Handle session loop
                while (isServerRunning && !socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("BACKUP_DATA:")) {
                        val backupPayload = line.substringAfter("BACKUP_DATA:")
                        onDataReceived(backupPayload)
                        writer.println("BACKUP_OK")
                    } else if (line.startsWith("CMD:")) {
                        val parts = line.substringAfter("CMD:").split(":")
                        val cmd = parts[0]
                        val arg = parts.getOrNull(1)
                        val res = onRemoteCommand(cmd, arg)
                        writer.println("CMD_RES:$res")
                    } else if (line == "CLOSE_SESSION") {
                        writer.println("BYE")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session error", e)
            } finally {
                try { socket.close() } catch (ignored: Exception) {}
                onSessionClosed()
            }
        }.start()
    }

    /**
     * Scans local network for available Import targets via UDP Broadcast.
     */
    suspend fun discoverImportTargets(timeoutMs: Long = 2500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<DiscoveredServer>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }

            val query = "BYEDPI_DISCOVER".toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(query, query.size, broadcastAddr, DISCOVERY_PORT)
            sock.send(packet)

            val startTime = System.currentTimeMillis()
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val recvPacket = DatagramPacket(buf, buf.size)
                    sock.receive(recvPacket)
                    val resp = String(recvPacket.data, 0, recvPacket.length)
                    if (resp.startsWith("BYEDPI_OFFER:")) {
                        val parts = resp.substringAfter("BYEDPI_OFFER:").split(":")
                        val name = parts.getOrNull(0) ?: "ByeDPI Receiver"
                        val isTv = parts.getOrNull(1)?.toBoolean() ?: false
                        val ip = recvPacket.address.hostAddress ?: continue
                        if (servers.none { it.ip == ip }) {
                            servers.add(DiscoveredServer(ip, name, isTv))
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // Continue listening until full timeout
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery error", e)
        } finally {
            sock?.close()
        }
        servers
    }

    fun stopServer() {
        isServerRunning = false
        try { serverSocket?.close() } catch (ignored: Exception) {}
        try { discoverySocket?.close() } catch (ignored: Exception) {}
        serverSocket = null
        discoverySocket = null
    }
}
