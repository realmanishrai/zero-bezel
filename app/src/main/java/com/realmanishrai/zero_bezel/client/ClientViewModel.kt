package com.realmanishrai.zero_bezel.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.MEDIA_HTTP_PORT
import com.realmanishrai.zero_bezel.viewer.VideoSyncState
import com.realmanishrai.zero_bezel.viewer.ViewerState
import com.realmanishrai.zero_bezel.viewer.ZoomPanState
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.realmanishrai.zero_bezel.viewer.WebViewSyncViewModel

data class ClientMediaFile(
    val name: String,
    val encodedName: String,
    val size: Long,
    val mimeType: String,
    val kind: String
)

data class ClientUiState(
    val hostIp: String = "",
    val status: String = "Enter the Host IP address.",
    val isConnected: Boolean = false,
    val files: List<ClientMediaFile> = emptyList(),
    val selectedFile: ClientMediaFile? = null,
    val scrollOffsetY: Int = 0,
    val pageNumber: Int = 1,
    val playbackTimestamp: Long = 0L,
    val isPlaying: Boolean = false,
    val viewerState: ViewerState = ViewerState.FileList,
    val zoomPanState: ZoomPanState = ZoomPanState(),
    val videoSyncState: VideoSyncState = VideoSyncState()
) {
    val selectedFileUrl: String?
        get() = selectedFile?.let { "http://$hostIp:$MEDIA_HTTP_PORT/file/${it.encodedName}" }
}

class ClientViewModel : ViewModel(), WebViewSyncViewModel {
    private val clientId = UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    private val _syncEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingSyncEvents = _syncEvents.asSharedFlow()

    override fun sendSyncEvent(json: String) {
        sendJson(json)
    }

    fun emitSyncEvent(event: String) {
        viewModelScope.launch {
            _syncEvents.emit(event)
        }
    }

    private var connectJob: Job? = null
    private var controlListenJob: Job? = null
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null

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
                val newSocket = Socket()
                newSocket.tcpNoDelay = true
                newSocket.connect(InetSocketAddress(hostIp, HANDSHAKE_PORT), CONNECT_TIMEOUT_MS)
                socket = newSocket
                reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))
                writer = PrintWriter(newSocket.getOutputStream(), true)

                sendJson(
                    JSONObject()
                        .put(KEY_TYPE, TYPE_HELLO)
                        .put(KEY_ID, clientId)
                        .toString()
                )
                fetchFileList(hostIp)

                _uiState.value = _uiState.value.copy(
                    status = "Connected to Host!",
                    isConnected = true
                )
                startControlListener()
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

    fun refreshFiles() {
        val hostIp = _uiState.value.hostIp
        if (hostIp.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            fetchFileList(hostIp)
        }
    }

    fun selectFile(file: ClientMediaFile) {
        val url = file.toUrl()
        _uiState.value = _uiState.value.copy(
            selectedFile = file,
            viewerState = file.toViewerState(url),
            zoomPanState = ZoomPanState()
        )
        sendJson(
            JSONObject()
                .put(KEY_TYPE, TYPE_OPEN_FILE)
                .put(KEY_URL, url)
                .put(KEY_FILENAME, file.name)
                .put(KEY_MEDIA_TYPE, file.kind)
                .toString()
        )
    }

    fun showFileList() {
        _uiState.value = _uiState.value.copy(viewerState = ViewerState.FileList)
    }

    fun sendZoomPan(scale: Float, offsetX: Float, offsetY: Float) {
        val zoomPan = ZoomPanState(
            scale = scale.coerceIn(1f, 5f),
            offsetX = offsetX,
            offsetY = offsetY
        )
        _uiState.value = _uiState.value.copy(zoomPanState = zoomPan)
        sendJson(
            JSONObject()
                .put(KEY_TYPE, TYPE_ZOOM_PAN)
                .put(KEY_SCALE, zoomPan.scale)
                .put(KEY_OFFSET_X, zoomPan.offsetX)
                .put(KEY_OFFSET_Y, zoomPan.offsetY)
                .toString()
        )
    }

    fun sendPlay(timestamp: Long) {
        sendJson(JSONObject().put(KEY_TYPE, TYPE_PLAY).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun sendPause(timestamp: Long) {
        sendJson(JSONObject().put(KEY_TYPE, TYPE_PAUSE).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun sendSeek(timestamp: Long) {
        sendJson(JSONObject().put(KEY_TYPE, TYPE_SEEK).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun sendScroll(offsetY: Int) {
        sendJson(JSONObject().put(KEY_TYPE, TYPE_SCROLL).put(KEY_OFFSET_Y, offsetY).toString())
    }

    fun sendPage(number: Int) {
        sendJson(JSONObject().put(KEY_TYPE, TYPE_PAGE).put(KEY_NUMBER, number).toString())
    }

    private fun startControlListener() {
        controlListenJob?.cancel()
        controlListenJob = viewModelScope.launch(Dispatchers.IO) {
            val activeReader = reader ?: return@launch
            try {
                while (controlListenJob?.isActive == true && socket?.isClosed == false) {
                    val line = activeReader.readLine() ?: break
                    handleHostMessage(line)
                }
            } catch (_: IOException) {
                _uiState.value = _uiState.value.copy(status = "Disconnected", isConnected = false)
            } finally {
                closeConnection()
            }
        }
    }

    private fun handleHostMessage(rawMessage: String) {
        try {
            val message = JSONObject(rawMessage)
            when (message.optString(KEY_TYPE)) {
                TYPE_OPEN_FILE,
                TYPE_LOAD -> {
                    val name = message.optString(KEY_NAME, message.optString(KEY_FILENAME))
                    val url = message.optString(KEY_URL)
                    val mediaType = message.optString(KEY_MEDIA_TYPE, message.optString(KEY_KIND, "image"))
                    val file = _uiState.value.files.firstOrNull { it.name == name }
                        ?: ClientMediaFile(
                            name = name,
                            encodedName = encode(name),
                            size = 0L,
                            mimeType = "$mediaType/*",
                            kind = mediaType
                        )
                    _uiState.value = _uiState.value.copy(
                        selectedFile = file,
                        viewerState = file.toViewerState(url.ifBlank { file.toUrl() }),
                        zoomPanState = ZoomPanState()
                    )
                }

                "navigate_back",
                TYPE_NAV_BACK -> showFileList()

                TYPE_ZOOM_PAN,
                TYPE_ZOOM -> _uiState.value = _uiState.value.copy(
                    zoomPanState = ZoomPanState(
                        scale = message.optDouble(KEY_SCALE, 1.0).toFloat().coerceIn(1f, 5f),
                        offsetX = message.optDouble(KEY_OFFSET_X, 0.0).toFloat(),
                        offsetY = message.optDouble(KEY_OFFSET_Y, 0.0).toFloat()
                    )
                )

                TYPE_VIDEO_SYNC -> _uiState.value = _uiState.value.copy(
                    videoSyncState = VideoSyncState(
                        positionMs = message.optLong(KEY_POSITION),
                        isPlaying = message.optBoolean(KEY_IS_PLAYING)
                    )
                )

                TYPE_PLAY -> _uiState.value = _uiState.value.copy(
                    isPlaying = true,
                    playbackTimestamp = message.optLong(KEY_TIMESTAMP)
                )

                TYPE_PAUSE -> _uiState.value = _uiState.value.copy(
                    isPlaying = false,
                    playbackTimestamp = message.optLong(KEY_TIMESTAMP)
                )

                TYPE_SEEK -> _uiState.value = _uiState.value.copy(
                    playbackTimestamp = message.optLong(KEY_TIMESTAMP)
                )

                TYPE_SCROLL -> _uiState.value = _uiState.value.copy(
                    scrollOffsetY = message.optInt(KEY_OFFSET_Y)
                )

                TYPE_PAGE -> _uiState.value = _uiState.value.copy(
                    pageNumber = message.optInt(KEY_NUMBER, 1)
                )

                "scroll", "zoom", "video" -> {
                    emitSyncEvent(rawMessage)
                }
            }
        } catch (_: JSONException) {
            _uiState.value = _uiState.value.copy(status = "Ignored malformed sync command.")
        }
    }

    private fun fetchFileList(hostIp: String) {
        val url = URL("http://$hostIp:$MEDIA_HTTP_PORT/list")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val files = JSONArray(body).let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    ClientMediaFile(
                        name = item.getString(KEY_NAME),
                        encodedName = item.getString(KEY_ENCODED_NAME),
                        size = item.optLong(KEY_SIZE),
                        mimeType = item.optString(KEY_MIME_TYPE),
                        kind = item.optString(KEY_TYPE, item.optString(KEY_KIND))
                    )
                }
            }
            _uiState.value = _uiState.value.copy(files = files)
        } finally {
            connection.disconnect()
        }
    }

    private fun sendJson(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            writer?.println(message)
                ?: run { _uiState.value = _uiState.value.copy(status = "Not connected.") }
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun ClientMediaFile.toUrl(): String {
        return "http://${_uiState.value.hostIp}:$MEDIA_HTTP_PORT/file/$encodedName"
    }

    private fun ClientMediaFile.toViewerState(url: String): ViewerState {
        return when (kind) {
            "image" -> ViewerState.ViewingImage(url, name)
            "pdf" -> ViewerState.ViewingPdf(url, name)
            "video" -> ViewerState.ViewingVideo(url, name)
            else -> ViewerState.FileList
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectJob?.cancel()
        controlListenJob?.cancel()
        closeConnection()
    }

    private fun closeConnection() {
        controlListenJob?.cancel()
        reader.closeQuietly()
        writer?.close()
        socket.closeQuietly()
        reader = null
        writer = null
        socket = null
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
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_MEDIA_TYPE = "mediaType"
        const val KEY_ENCODED_NAME = "encodedName"
        const val KEY_SIZE = "size"
        const val KEY_MIME_TYPE = "mimeType"
        const val KEY_KIND = "kind"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_POSITION = "position"
        const val KEY_IS_PLAYING = "isPlaying"
        const val KEY_OFFSET_Y = "offsetY"
        const val KEY_NUMBER = "number"
        const val KEY_SCALE = "scale"
        const val KEY_OFFSET_X = "offsetX"
        const val TYPE_HELLO = "HELLO"
        const val TYPE_LOAD = "LOAD"
        const val TYPE_OPEN_FILE = "OPEN_FILE"
        const val TYPE_NAV_BACK = "NAV_BACK"
        const val TYPE_ZOOM = "ZOOM"
        const val TYPE_ZOOM_PAN = "ZOOM_PAN"
        const val TYPE_PLAY = "PLAY"
        const val TYPE_PAUSE = "PAUSE"
        const val TYPE_SEEK = "SEEK"
        const val TYPE_SCROLL = "SCROLL"
        const val TYPE_PAGE = "PAGE"
        const val TYPE_VIDEO_SYNC = "VIDEO_SYNC"
    }
}
