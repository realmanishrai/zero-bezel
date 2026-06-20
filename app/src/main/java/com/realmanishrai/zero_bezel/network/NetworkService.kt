package com.realmanishrai.zero_bezel.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.net.Inet4Address

object NetworkService {
    private const val PORT = 8080

    /**
     * Holds the active connection's writer so that [sendSync] can be called
     * from any thread (SyncBridge calls it from the WebView JS thread).
     * PrintWriter.println() is synchronized, so no extra locking is needed.
     */
    @Volatile private var remoteWriter: PrintWriter? = null

    /** Send a JSON sync event to the connected remote device. No-ops if not connected. */
    fun sendSync(json: String) {
        try {
            remoteWriter?.println(json)
        } catch (_: Exception) { /* connection dropped, ignore */ }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (!ip.isNullOrEmpty() && ip != "127.0.0.1") {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }
        return "127.0.0.1"
    }

    private suspend fun runOnMain(block: () -> Unit) {
        withContext(Dispatchers.Main) { block() }
    }

    /* ── HOST SERVER ─────────────────────────────────────────────────────── */

    suspend fun startHostServer(
        onClientConnected: (String) -> Unit,
        onClientDisconnected: () -> Unit,
        onLog: (String) -> Unit,
        onSyncReceived: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            runOnMain { onLog("Server listening on port $PORT...") }

            val job = coroutineContext[kotlinx.coroutines.Job]
            job?.invokeOnCompletion {
                try { serverSocket?.close() } catch (_: Exception) {}
            }

            while (true) {
                val clientSocket = serverSocket.accept()
                val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
                runOnMain { onLog("Client connected: $clientIp") }

                launch {
                    var pingJob: kotlinx.coroutines.Job? = null
                    try {
                        val writer = PrintWriter(clientSocket.getOutputStream(), true)
                        writer.println("I am ready")
                        remoteWriter = writer                          // ← expose for sendSync
                        runOnMain { onClientConnected(clientIp) }

                        pingJob = launch {
                            while (true) {
                                kotlinx.coroutines.delay(5000)
                                writer.println("ping")
                            }
                        }

                        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            when {
                                line == "pong"          -> { /* keep-alive, ignore */ }
                                line.startsWith("{")    -> runOnMain { onSyncReceived(line) }
                            }
                        }
                    } catch (e: Exception) {
                        runOnMain { onLog("Client error: ${e.message}") }
                    } finally {
                        remoteWriter = null                            // ← clear on disconnect
                        pingJob?.cancel()
                        try { clientSocket.close() } catch (_: Exception) {}
                        runOnMain {
                            onClientDisconnected()
                            onLog("Client disconnected: $clientIp")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runOnMain { onLog("Server stopped: ${e.message}") }
        } finally {
            try { serverSocket?.close() } catch (_: Exception) {}
        }
    }

    /* ── CLIENT CONNECTION ───────────────────────────────────────────────── */

    suspend fun connectToHost(
        hostIp: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onLog: (String) -> Unit,
        onSyncReceived: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            runOnMain { onLog("Connecting to host $hostIp:$PORT...") }
            socket = Socket()

            val job = coroutineContext[kotlinx.coroutines.Job]
            job?.invokeOnCompletion {
                try { socket?.close() } catch (_: Exception) {}
            }

            socket.connect(InetSocketAddress(hostIp, PORT), 5000)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val handshake = reader.readLine()

            if (handshake == "I am ready") {
                val writer = PrintWriter(socket.getOutputStream(), true)
                remoteWriter = writer                                  // ← expose for sendSync
                runOnMain {
                    onLog("Received handshake: $handshake")
                    onConnected()
                }

                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line == "ping"          -> writer.println("pong")
                            line.startsWith("{")    -> runOnMain { onSyncReceived(line) }
                        }
                    }
                } catch (_: Exception) { /* disconnected */ }
                finally {
                    remoteWriter = null                                // ← clear on disconnect
                }
                true
            } else {
                runOnMain { onLog("Invalid handshake response: $handshake") }
                false
            }
        } catch (e: Exception) {
            runOnMain { onLog("Connection error: ${e.message}") }
            false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            runOnMain {
                onDisconnected()
                onLog("Disconnected from host")
            }
        }
    }
}
