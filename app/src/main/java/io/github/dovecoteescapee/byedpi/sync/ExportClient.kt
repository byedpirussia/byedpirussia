package io.github.dovecoteescapee.byedpi.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class ExportClient(
    val serverIp: String,
    val deviceName: String,
    val port: Int = DataSyncManager.SYNC_PORT
) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    var isConnected = false
        private set

    suspend fun connect(timeoutMs: Int = 5000): HandshakeResult = withContext(Dispatchers.IO) {
        try {
            socket = Socket().apply {
                connect(InetSocketAddress(serverIp, port), timeoutMs)
                soTimeout = 30000
            }
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            writer = PrintWriter(socket!!.getOutputStream(), true)

            // Send handshake
            writer?.println("HELLO:$deviceName")
            val reply = reader?.readLine()

            when (reply) {
                "ACCEPTED" -> {
                    isConnected = true
                    HandshakeResult.Accepted
                }
                "REJECTED" -> {
                    disconnect()
                    HandshakeResult.Rejected
                }
                else -> {
                    disconnect()
                    HandshakeResult.Error("Неверный ответ сервера: $reply")
                }
            }
        } catch (e: Exception) {
            disconnect()
            HandshakeResult.Error(e.message ?: "Ошибка подключения")
        }
    }

    suspend fun sendBackupData(json: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected || writer == null || reader == null) return@withContext false
        try {
            // Compress or send as single line string (JSON with removed newlines)
            val compact = json.replace("\r", "").replace("\n", "")
            writer?.println("BACKUP_DATA:$compact")
            val ack = reader?.readLine()
            ack == "BACKUP_OK"
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendRemoteCommand(command: String, arg: String? = null): String? = withContext(Dispatchers.IO) {
        if (!isConnected || writer == null || reader == null) return@withContext null
        try {
            val payload = if (arg != null) "CMD:$command:$arg" else "CMD:$command"
            writer?.println(payload)
            val reply = reader?.readLine()
            if (reply?.startsWith("CMD_RES:") == true) {
                reply.substringAfter("CMD_RES:")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        isConnected = false
        try {
            writer?.println("CLOSE_SESSION")
        } catch (ignored: Exception) {}
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
        reader = null
        writer = null
    }

    sealed class HandshakeResult {
        object Accepted : HandshakeResult()
        object Rejected : HandshakeResult()
        data class Error(val message: String) : HandshakeResult()
    }
}
