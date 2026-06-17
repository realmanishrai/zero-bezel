package com.realmanishrai.zero_bezel.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ClientUiState(
    val hostIp: String = "",
    val status: String = "Enter the Host IP address."
)

class ClientViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    private var connectJob: Job? = null
    private var socket: Socket? = null

    fun onHostIpChanged(hostIp: String) {
        _uiState.value = _uiState.value.copy(hostIp = hostIp.trim())
    }

    fun connectToHost() {
        val hostIp = _uiState.value.hostIp
        if (hostIp.isBlank()) {
            _uiState.value = _uiState.value.copy(status = "Please enter a Host IP address.")
            return
        }

        connectJob?.cancel()
        socket.closeQuietly()
        _uiState.value = _uiState.value.copy(status = "Connecting...")

        connectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(hostIp, HANDSHAKE_PORT), CONNECT_TIMEOUT_MS)
                socket = newSocket
                _uiState.value = _uiState.value.copy(status = "Connected to Host!")
            } catch (_: ConnectException) {
                _uiState.value = _uiState.value.copy(status = "Connection Refused")
            } catch (_: SocketTimeoutException) {
                _uiState.value = _uiState.value.copy(status = "Connection Timed Out")
            } catch (exception: IOException) {
                _uiState.value = _uiState.value.copy(
                    status = exception.message ?: "Connection error"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectJob?.cancel()
        socket.closeQuietly()
    }

    private fun Socket?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
