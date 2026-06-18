package com.realmanishrai.zero_bezel.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.VIDEO_PORT
import java.io.BufferedReader
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class ClientUiState(
    val hostIp: String = "",
    val status: String = "Enter the Host IP address.",
    val isConnected: Boolean = false
)

class ClientViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    private val _resetEvents = MutableSharedFlow<Unit>()
    val resetEvents: SharedFlow<Unit> = _resetEvents

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private var connectJob: Job? = null
    private var controlListenJob: Job? = null
    private var videoListenJob: Job? = null
    private var controlSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var videoInput: DataInputStream? = null
    private var touchpadWidth: Float = 0f
    private var touchpadHeight: Float = 0f

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
        closeConnection()
        _uiState.value = _uiState.value.copy(status = "Connecting...")

        connectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val newControlSocket = Socket()
                newControlSocket.tcpNoDelay = true
                newControlSocket.connect(
                    InetSocketAddress(hostIp, HANDSHAKE_PORT),
                    CONNECT_TIMEOUT_MS
                )

                val newVideoSocket = Socket()
                newVideoSocket.tcpNoDelay = true
                newVideoSocket.connect(
                    InetSocketAddress(hostIp, VIDEO_PORT),
                    CONNECT_TIMEOUT_MS
                )

                controlSocket = newControlSocket
                videoSocket = newVideoSocket
                reader = BufferedReader(InputStreamReader(newControlSocket.getInputStream()))
                writer = PrintWriter(newControlSocket.getOutputStream(), true)
                videoInput = DataInputStream(newVideoSocket.getInputStream())
                _uiState.value = _uiState.value.copy(
                    status = "Connected to Host!",
                    isConnected = true
                )
                startControlListener()
                startVideoListener()
            } catch (_: ConnectException) {
                _uiState.value = _uiState.value.copy(
                    status = "Connection Refused",
                    isConnected = false
                )
            } catch (_: SocketTimeoutException) {
                _uiState.value = _uiState.value.copy(
                    status = "Connection Timed Out",
                    isConnected = false
                )
            } catch (exception: IOException) {
                _uiState.value = _uiState.value.copy(
                    status = exception.message ?: "Connection error",
                    isConnected = false
                )
            }
        }
    }

    fun sendColorChange(hexColor: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val colorMessage = JSONObject()
                .put(KEY_TYPE, TYPE_COLOR_CHANGE)
                .put(KEY_DATA, hexColor)
                .toString()

            writer?.println(colorMessage)
                ?: run { _uiState.value = _uiState.value.copy(status = "Not connected.") }
        }
    }

    fun onTouchpadSizeChanged(width: Float, height: Float) {
        touchpadWidth = width
        touchpadHeight = height
    }

    fun sendTap(x: Float, y: Float) {
        val normX = normalizeX(x)
        val normY = normalizeY(y)

        viewModelScope.launch(Dispatchers.IO) {
            val tapMessage = JSONObject()
                .put(KEY_TYPE, TYPE_TAP)
                .put(KEY_X, normX.coerceIn(0f, 1f))
                .put(KEY_Y, normY.coerceIn(0f, 1f))
                .toString()

            writer?.println(tapMessage)
                ?: run { _uiState.value = _uiState.value.copy(status = "Not connected.") }
        }
    }

    fun sendSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val swipeMessage = JSONObject()
                .put(KEY_TYPE, TYPE_SWIPE)
                .put(KEY_START_X, normalizeX(startX).coerceIn(0f, 1f))
                .put(KEY_START_Y, normalizeY(startY).coerceIn(0f, 1f))
                .put(KEY_END_X, normalizeX(endX).coerceIn(0f, 1f))
                .put(KEY_END_Y, normalizeY(endY).coerceIn(0f, 1f))
                .toString()

            writer?.println(swipeMessage)
                ?: run { _uiState.value = _uiState.value.copy(status = "Not connected.") }
        }
    }

    fun sendMove(x: Float, y: Float) {
        val normX = normalizeX(x)
        val normY = normalizeY(y)

        viewModelScope.launch(Dispatchers.IO) {
            val moveMessage = JSONObject()
                .put(KEY_TYPE, TYPE_MOVE)
                .put(KEY_X, normX.coerceIn(0f, 1f))
                .put(KEY_Y, normY.coerceIn(0f, 1f))
                .toString()

            writer?.println(moveMessage)
                ?: run { _uiState.value = _uiState.value.copy(status = "Not connected.") }
        }
    }

    private fun normalizeX(x: Float): Float {
        return if (touchpadWidth > 0f) x / touchpadWidth else 0f
    }

    private fun normalizeY(y: Float): Float {
        return if (touchpadHeight > 0f) y / touchpadHeight else 0f
    }

    private fun startControlListener() {
        controlListenJob?.cancel()
        controlListenJob = viewModelScope.launch(Dispatchers.IO) {
            listenForHostMessages()
        }
    }

    private fun startVideoListener() {
        videoListenJob?.cancel()
        videoListenJob = viewModelScope.launch(Dispatchers.IO) {
            listenForVideoFrames()
        }
    }

    private suspend fun listenForHostMessages() {
        val activeReader = reader ?: return

        try {
            while (controlListenJob?.isActive == true && controlSocket?.isClosed == false) {
                val incomingLine = activeReader.readLine() ?: break
                handleIncomingJson(incomingLine)
            }
        } catch (_: IOException) {
            _uiState.value = _uiState.value.copy(status = "Disconnected", isConnected = false)
        } finally {
            closeConnection()
            if (controlListenJob?.isActive == true) {
                _uiState.value = _uiState.value.copy(status = "Disconnected", isConnected = false)
            }
        }
    }

    private suspend fun listenForVideoFrames() {
        val activeVideoInput = videoInput ?: return

        try {
            while (videoListenJob?.isActive == true && videoSocket?.isClosed == false) {
                val frameSize = activeVideoInput.readInt()
                if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES) {
                    throw IOException("Invalid frame size: $frameSize")
                }

                val frameBytes = ByteArray(frameSize)
                activeVideoInput.readFully(frameBytes)

                decodeFrame(frameBytes)?.let { bitmap ->
                    val oldBitmap = _latestFrame.value
                    oldBitmap?.recycle()
                    _latestFrame.value = bitmap
                }
            }
        } catch (_: IOException) {
            _uiState.value = _uiState.value.copy(status = "Video disconnected", isConnected = false)
        } finally {
            closeConnection()
        }
    }

    private suspend fun handleIncomingJson(rawMessage: String) {
        try {
            val message = JSONObject(rawMessage)
            val type = message.optString(KEY_TYPE)

            if (type == TYPE_RESET) {
                _resetEvents.emit(Unit)
            }
        } catch (_: JSONException) {
            _uiState.value = _uiState.value.copy(status = "Ignored malformed message.")
        }
    }

    private suspend fun decodeFrame(frameBytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
        try {
            BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectJob?.cancel()
        controlListenJob?.cancel()
        videoListenJob?.cancel()
        closeConnection()
        _latestFrame.value?.recycle()
        _latestFrame.value = null
    }

    private fun closeConnection() {
        controlListenJob?.cancel()
        videoListenJob?.cancel()
        reader.closeQuietly()
        writer.closeQuietly()
        videoInput.closeQuietly()
        controlSocket.closeQuietly()
        videoSocket.closeQuietly()
        reader = null
        writer = null
        videoInput = null
        controlSocket = null
        videoSocket = null
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val KEY_TYPE = "type"
        const val KEY_DATA = "data"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_START_X = "startX"
        const val KEY_START_Y = "startY"
        const val KEY_END_X = "endX"
        const val KEY_END_Y = "endY"
        const val TYPE_COLOR_CHANGE = "COLOR_CHANGE"
        const val TYPE_RESET = "RESET"
        const val TYPE_TAP = "TAP"
        const val TYPE_SWIPE = "SWIPE"
        const val TYPE_MOVE = "MOVE"
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
