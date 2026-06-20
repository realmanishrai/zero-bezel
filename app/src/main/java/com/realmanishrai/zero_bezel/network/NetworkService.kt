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
        } catch (e: Exception) {
            // ignore
        }
        return "127.0.0.1"
    }

    private suspend fun runOnMain(block: () -> Unit) {
        withContext(Dispatchers.Main) {
            block()
        }
    }

    suspend fun startHostServer(
        onClientConnected: (String) -> Unit,
        onClientDisconnected: () -> Unit,
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            runOnMain { onLog("Server listening on port $PORT...") }
            
            // Ensure server socket closes on coroutine cancellation
            val job = coroutineContext[kotlinx.coroutines.Job]
            job?.invokeOnCompletion {
                try {
                    serverSocket?.close()
                } catch (e: Exception) {}
            }

            while (true) {
                val clientSocket = serverSocket.accept()
                val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
                runOnMain { onLog("Client connected: $clientIp") }
                
                // Handle client in a concurrent coroutine so accept() is not blocked
                launch {
                    var pingJob: kotlinx.coroutines.Job? = null
                    try {
                        val writer = PrintWriter(clientSocket.getOutputStream(), true)
                        writer.println("I am ready")
                        runOnMain { onClientConnected(clientIp) }
                        
                        // Start a ping sender job every 5 seconds
                        pingJob = launch {
                            while (true) {
                                kotlinx.coroutines.delay(5000)
                                writer.println("ping")
                            }
                        }
                        
                        val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                        while (true) {
                            val line = reader.readLine() ?: break
                            // Client responds with pong, which we ignore
                        }
                    } catch (e: Exception) {
                        runOnMain { onLog("Client error: ${e.message}") }
                    } finally {
                        pingJob?.cancel()
                        try {
                            clientSocket.close()
                        } catch (e: Exception) {}
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
            try {
                serverSocket?.close()
            } catch (e: Exception) {}
        }
    }

    suspend fun connectToHost(
        hostIp: String,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            runOnMain { onLog("Connecting to host $hostIp:$PORT...") }
            socket = Socket()
            
            // Ensure socket closes on coroutine cancellation
            val job = coroutineContext[kotlinx.coroutines.Job]
            job?.invokeOnCompletion {
                try {
                    socket?.close()
                } catch (e: Exception) {}
            }

            socket.connect(InetSocketAddress(hostIp, PORT), 5000)
            
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val handshake = reader.readLine()
            if (handshake == "I am ready") {
                runOnMain {
                    onLog("Received handshake: $handshake")
                    onConnected()
                }
                
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "ping") {
                            writer.println("pong")
                        }
                    }
                } catch (e: Exception) {
                    // disconnected
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
            try {
                socket?.close()
            } catch (e: Exception) {}
            runOnMain {
                onDisconnected()
                onLog("Disconnected from host")
            }
        }
    }
}
