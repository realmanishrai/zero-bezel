package com.realmanishrai.zero_bezel.host

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.NetworkUtils
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HostUiState(
    val ipAddress: String = "Finding IP address...",
    val status: String = "Starting server..."
)

class HostViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    init {
        startServer()
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return

        _uiState.value = HostUiState(
            ipAddress = NetworkUtils.getLocalWifiIpv4Address() ?: "Wi-Fi IPv4 address unavailable",
            status = "Waiting for client..."
        )

        serverJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerSocket(HANDSHAKE_PORT).use { server ->
                    serverSocket = server

                    while (!server.isClosed) {
                        val socket = server.accept()
                        clientSocket?.close()
                        clientSocket = socket
                        _uiState.value = _uiState.value.copy(status = "Client Connected!")
                    }
                }
            } catch (exception: IOException) {
                if (serverJob?.isActive == true) {
                    _uiState.value = _uiState.value.copy(
                        status = exception.message ?: "Server socket error"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        serverJob?.cancel()
        clientSocket.closeQuietly()
        serverSocket.closeQuietly()
    }

    private fun Socket?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }

    private fun ServerSocket?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }
}
