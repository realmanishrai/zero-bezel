package com.realmanishrai.zero_bezel.host

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.NetworkUtils
import com.realmanishrai.zero_bezel.network.VIDEO_PORT
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class HostUiState(
    val ipAddress: String = "Finding IP address...",
    val status: String = "Starting server...",
    val virtualWidth: Int = 0,
    val virtualHeight: Int = 0
)

class HostViewModel(application: Application) : AndroidViewModel(application) {
    private val displayMetrics = application.resources.displayMetrics
    private val screenWidth = displayMetrics.widthPixels
    private val screenHeight = displayMetrics.heightPixels

    private val _uiState = MutableStateFlow(
        HostUiState(
            virtualWidth = screenWidth * 2,
            virtualHeight = screenHeight
        )
    )
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private val _backgroundColor = MutableStateFlow(Color(0xFF0B2A4A))
    val backgroundColor: StateFlow<Color> = _backgroundColor.asStateFlow()

    private val _virtualCursorPosition = MutableStateFlow(-1f to -1f)
    val virtualCursorPosition: StateFlow<Pair<Float, Float>> = _virtualCursorPosition.asStateFlow()

    private var controlServerJob: Job? = null
    private var videoServerJob: Job? = null
    private var rightHalfStreamJob: Job? = null
    private var controlServerSocket: ServerSocket? = null
    private var videoServerSocket: ServerSocket? = null
    private var controlClientSocket: Socket? = null
    private var videoClientSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var videoOutput: DataOutputStream? = null
    private val writerLock = Any()
    private val videoWriterLock = Any()

    init {
        startControlServer()
        startVideoServer()
    }

    private fun startControlServer() {
        if (controlServerJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(
            ipAddress = NetworkUtils.getLocalWifiIpv4Address() ?: "Wi-Fi IPv4 address unavailable",
            status = "Waiting for control client..."
        )

        controlServerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerSocket(HANDSHAKE_PORT).use { server ->
                    controlServerSocket = server

                    while (isActive && !server.isClosed) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        closeControlConnection()
                        controlClientSocket = socket
                        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        writer = PrintWriter(socket.getOutputStream(), true)
                        _uiState.value = _uiState.value.copy(status = "Control connected. Waiting for video...")
                        listenForClientMessages()
                    }
                }
            } catch (exception: IOException) {
                if (controlServerJob?.isActive == true) {
                    _uiState.value = _uiState.value.copy(
                        status = exception.message ?: "Control server socket error"
                    )
                }
            }
        }
    }

    private fun startVideoServer() {
        if (videoServerJob?.isActive == true) return

        videoServerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerSocket(VIDEO_PORT).use { server ->
                    videoServerSocket = server

                    while (isActive && !server.isClosed) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        closeVideoConnection()
                        videoClientSocket = socket
                        videoOutput = DataOutputStream(socket.getOutputStream())
                        _uiState.value = _uiState.value.copy(status = "Client Connected!")
                        startRightHalfStreaming()
                    }
                }
            } catch (exception: IOException) {
                if (videoServerJob?.isActive == true) {
                    _uiState.value = _uiState.value.copy(
                        status = exception.message ?: "Video server socket error"
                    )
                }
            }
        }
    }

    fun sendReset() {
        viewModelScope.launch(Dispatchers.IO) {
            val resetMessage = JSONObject()
                .put(KEY_TYPE, TYPE_RESET)
                .put(KEY_DATA, "")
                .toString()

            if (!sendJson(resetMessage)) {
                _uiState.value = _uiState.value.copy(status = "No client connected.")
            }
        }
    }

    private suspend fun listenForClientMessages() {
        val activeReader = reader ?: return

        try {
            while (controlServerJob?.isActive == true && controlClientSocket?.isClosed == false) {
                val incomingLine = activeReader.readLine() ?: break
                handleIncomingJson(incomingLine)
            }
        } catch (_: IOException) {
            _uiState.value = _uiState.value.copy(status = "Disconnected")
        } finally {
            closeClientConnection()
            if (controlServerJob?.isActive == true) {
                _uiState.value = _uiState.value.copy(status = "Disconnected")
            }
        }
    }

    private suspend fun handleIncomingJson(rawMessage: String) {
        try {
            val message = JSONObject(rawMessage)
            val type = message.optString(KEY_TYPE)
            val data = message.optString(KEY_DATA)

            when {
                type == TYPE_COLOR_CHANGE && data.isNotBlank() -> {
                    _backgroundColor.value = Color(AndroidColor.parseColor(data))
                }

                type == TYPE_TAP -> {
                    val normX = message.getDouble(KEY_X).toFloat().coerceIn(0f, 1f)
                    val normY = message.getDouble(KEY_Y).toFloat().coerceIn(0f, 1f)
                    updateVirtualCursor(normX, normY)
                }

                type == TYPE_SWIPE -> {
                    val endX = message.getDouble(KEY_END_X).toFloat().coerceIn(0f, 1f)
                    val endY = message.getDouble(KEY_END_Y).toFloat().coerceIn(0f, 1f)
                    updateVirtualCursor(endX, endY)
                }

                type == TYPE_MOVE -> {
                    val normX = message.getDouble(KEY_X).toFloat().coerceIn(0f, 1f)
                    val normY = message.getDouble(KEY_Y).toFloat().coerceIn(0f, 1f)
                    updateVirtualCursor(normX, normY)
                }
            }
        } catch (_: JSONException) {
            _uiState.value = _uiState.value.copy(status = "Ignored malformed message.")
        } catch (_: IllegalArgumentException) {
            _uiState.value = _uiState.value.copy(status = "Ignored invalid message.")
        }
    }

    private suspend fun updateVirtualCursor(normalizedX: Float, normalizedY: Float) {
        val virtualX = screenWidth + (normalizedX * screenWidth)
        val virtualY = normalizedY * screenHeight

        withContext(Dispatchers.Main) {
            _virtualCursorPosition.value = virtualX to virtualY
        }
    }

    private fun startRightHalfStreaming() {
        rightHalfStreamJob?.cancel()
        rightHalfStreamJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && videoClientSocket?.isClosed == false) {
                val bitmap = renderRightHalfToBitmap()
                try {
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, RIGHT_HALF_JPEG_QUALITY, outputStream)
                    val frameBytes = outputStream.toByteArray()

                    val sent = withContext(Dispatchers.IO) {
                        runCatching { sendVideoFrame(frameBytes) }.getOrDefault(false)
                    }
                    if (!sent) {
                        closeVideoConnection()
                    }
                } finally {
                    bitmap.recycle()
                }

                delay(RIGHT_HALF_FRAME_INTERVAL_MS)
            }
        }
    }

    fun renderRightHalfToBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(94, 19, 27))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            textSize = screenWidth * 0.11f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(255, 214, 102)
            textAlign = Paint.Align.CENTER
            textSize = screenWidth * 0.052f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(90, 255, 255, 255)
            strokeWidth = 5f
        }

        canvas.drawText("CLIENT", screenWidth / 2f, screenHeight * 0.42f, titlePaint)
        canvas.drawText("RIGHT SIDE", screenWidth / 2f, screenHeight * 0.54f, titlePaint)
        canvas.drawText("Virtual X: ${screenWidth} - ${screenWidth * 2}", screenWidth / 2f, screenHeight * 0.64f, subtitlePaint)
        canvas.drawLine(0f, 0f, 0f, screenHeight.toFloat(), linePaint)

        val cursor = _virtualCursorPosition.value
        if (cursor.first >= screenWidth && cursor.second >= 0f) {
            val localX = cursor.first - screenWidth
            val localY = cursor.second
            val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.YELLOW
            }
            canvas.drawCircle(localX, localY, 34f, cursorPaint)
        }

        return bitmap
    }

    private fun sendJson(message: String): Boolean {
        val activeWriter = writer ?: return false
        synchronized(writerLock) {
            activeWriter.println(message)
            activeWriter.flush()
        }
        return true
    }

    private fun sendVideoFrame(bytes: ByteArray): Boolean {
        val activeVideoOutput = videoOutput ?: return false
        synchronized(videoWriterLock) {
            activeVideoOutput.writeInt(bytes.size)
            activeVideoOutput.write(bytes)
            activeVideoOutput.flush()
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        controlServerJob?.cancel()
        videoServerJob?.cancel()
        closeClientConnection()
        controlServerSocket.closeQuietly()
        videoServerSocket.closeQuietly()
    }

    private fun closeClientConnection() {
        closeControlConnection()
        closeVideoConnection()
    }

    private fun closeControlConnection() {
        reader.closeQuietly()
        writer.closeQuietly()
        controlClientSocket.closeQuietly()
        reader = null
        writer = null
        controlClientSocket = null
    }

    private fun closeVideoConnection() {
        rightHalfStreamJob?.cancel()
        rightHalfStreamJob = null
        videoOutput.closeQuietly()
        videoClientSocket.closeQuietly()
        videoOutput = null
        videoClientSocket = null
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }

    private companion object {
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
        const val RIGHT_HALF_JPEG_QUALITY = 70
        const val RIGHT_HALF_FRAME_INTERVAL_MS = 66L
    }
}
