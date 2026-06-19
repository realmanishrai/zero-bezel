package com.realmanishrai.zero_bezel.host

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.MEDIA_HTTP_PORT
import com.realmanishrai.zero_bezel.network.MediaStreamingServer
import com.realmanishrai.zero_bezel.network.NetworkUtils
import com.realmanishrai.zero_bezel.network.ServedMediaFile
import com.realmanishrai.zero_bezel.viewer.VideoSyncState
import com.realmanishrai.zero_bezel.viewer.ViewerState
import com.realmanishrai.zero_bezel.viewer.ZoomPanState
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import com.realmanishrai.zero_bezel.viewer.WebViewSyncViewModel

data class HostMediaFile(
    val name: String,
    val encodedName: String,
    val kind: String
)

data class HostUiState(
    val ipAddress: String = "Finding IP address...",
    val status: String = "Starting servers...",
    val selectedFolderUri: String? = null,
    val files: List<HostMediaFile> = emptyList(),
    val selectedFile: HostMediaFile? = null,
    val connectedClients: Int = 0,
    val viewerState: ViewerState = ViewerState.FileList,
    val zoomPanState: ZoomPanState = ZoomPanState(),
    val videoSyncState: VideoSyncState = VideoSyncState()
) {
    val selectedFileUrl: String?
        get() = selectedFile?.let { "http://$ipAddress:$MEDIA_HTTP_PORT/file/${it.encodedName}" }
}

class HostViewModel(application: Application) : AndroidViewModel(application), WebViewSyncViewModel {
    private val preferences = application.getSharedPreferences(PREFS_NAME, 0)
    private val mediaServer = MediaStreamingServer(application)
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    private val _syncEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingSyncEvents = _syncEvents.asSharedFlow()

    override fun sendSyncEvent(json: String) {
        broadcast(json)
    }

    fun emitSyncEvent(event: String) {
        viewModelScope.launch {
            _syncEvents.emit(event)
        }
    }

    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private var controlServerJob: Job? = null
    private var controlServerSocket: ServerSocket? = null
    private var lastZoomSyncMs = 0L

    init {
        restoreFolder()
        startHttpServer()
        startControlServer()
    }

    fun onFolderSelected(uri: Uri) {
        preferences.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
        mediaServer.setFolder(uri)
        refreshFiles()
    }

    fun refreshFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = mediaServer.listFiles().map { it.toUiFile() }
            _uiState.value = _uiState.value.copy(
                selectedFolderUri = preferences.getString(KEY_FOLDER_URI, null),
                files = files,
                selectedFile = files.firstOrNull { it.name == _uiState.value.selectedFile?.name }
            )
        }
    }

    fun selectFile(file: HostMediaFile) {
        val hostIp = NetworkUtils.getLocalWifiIpv4Address() ?: "127.0.0.1"
        val fileUrl = "http://$hostIp:$MEDIA_HTTP_PORT/file/${file.encodedName}"
        val encodedFileUrl = URLEncoder.encode(fileUrl, "UTF-8")
        val viewerUrl = "http://$hostIp:$MEDIA_HTTP_PORT/viewer.html?file=$encodedFileUrl&type=${file.kind}"

        println("🎬 Host selecting file: ${file.name}")
        println("🎬 File URL: $fileUrl")
        println("🎬 Viewer URL: $viewerUrl")
        println("🎬 Host IP: $hostIp")

        _uiState.value = _uiState.value.copy(
            selectedFile = file,
            viewerState = file.toViewerState(viewerUrl),
            zoomPanState = ZoomPanState()
        )
        broadcast(
            JSONObject()
                .put(KEY_TYPE, TYPE_OPEN_FILE)
                .put(KEY_URL, viewerUrl)
                .put(KEY_FILENAME, file.name)
                .put(KEY_MEDIA_TYPE, file.kind)
                .toString()
        )
    }

    fun showFileList() {
        _uiState.value = _uiState.value.copy(viewerState = ViewerState.FileList)
        broadcast(JSONObject().put(KEY_TYPE, TYPE_NAV_BACK).toString())
    }

    fun updateZoomPan(scale: Float, offsetX: Float, offsetY: Float) {
        val zoomPan = ZoomPanState(
            scale = scale.coerceIn(1f, 5f),
            offsetX = offsetX,
            offsetY = offsetY
        )
        _uiState.value = _uiState.value.copy(zoomPanState = zoomPan)
        val now = System.currentTimeMillis()
        if (now - lastZoomSyncMs < ZOOM_SYNC_INTERVAL_MS) return
        lastZoomSyncMs = now
        broadcast(
            JSONObject()
                .put(KEY_TYPE, TYPE_ZOOM_PAN)
                .put(KEY_SCALE, zoomPan.scale)
                .put(KEY_OFFSET_X, zoomPan.offsetX)
                .put(KEY_OFFSET_Y, zoomPan.offsetY)
                .toString()
        )
    }

    fun broadcastPlay(timestamp: Long = 0L) {
        broadcast(JSONObject().put(KEY_TYPE, TYPE_PLAY).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun broadcastPause(timestamp: Long = 0L) {
        broadcast(JSONObject().put(KEY_TYPE, TYPE_PAUSE).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun broadcastSeek(timestamp: Long) {
        broadcast(JSONObject().put(KEY_TYPE, TYPE_SEEK).put(KEY_TIMESTAMP, timestamp).toString())
    }

    fun broadcastScroll(offsetY: Int) {
        broadcast(JSONObject().put(KEY_TYPE, TYPE_SCROLL).put(KEY_OFFSET_Y, offsetY).toString())
    }

    fun broadcastPage(number: Int) {
        broadcast(JSONObject().put(KEY_TYPE, TYPE_PAGE).put(KEY_NUMBER, number).toString())
    }

    fun updateVideoSync(positionMs: Long, isPlaying: Boolean) {
        _uiState.value = _uiState.value.copy(
            videoSyncState = VideoSyncState(positionMs = positionMs, isPlaying = isPlaying)
        )
        broadcast(
            JSONObject()
                .put(KEY_TYPE, TYPE_VIDEO_SYNC)
                .put(KEY_POSITION, positionMs)
                .put(KEY_IS_PLAYING, isPlaying)
                .toString()
        )
    }

    private fun restoreFolder() {
        val savedUri = preferences.getString(KEY_FOLDER_URI, null)?.let(Uri::parse)
        mediaServer.setFolder(savedUri)
        _uiState.value = _uiState.value.copy(selectedFolderUri = savedUri?.toString())
        refreshFiles()
    }

    private fun startHttpServer() {
        runCatching {
            mediaServer.start(NanoStartTimeoutMs, false)
        }.onFailure { exception ->
            _uiState.value = _uiState.value.copy(
                status = "HTTP server failed: ${exception.message}"
            )
        }
        
        // Verify server is running
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            try {
                val testUrl = java.net.URL("http://127.0.0.1:$MEDIA_HTTP_PORT/test")
                val connection = testUrl.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                if (response != "Server is working!") {
                    _uiState.value = _uiState.value.copy(status = "HTTP server returned unexpected response")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = "HTTP server verification failed: ${e.message}"
                )
            }
        }
    }

    private fun startControlServer() {
        if (controlServerJob?.isActive == true) return

        _uiState.value = _uiState.value.copy(
            ipAddress = NetworkUtils.getLocalWifiIpv4Address() ?: "Wi-Fi IPv4 address unavailable",
            status = "HTTP $MEDIA_HTTP_PORT, control $HANDSHAKE_PORT"
        )

        controlServerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(HANDSHAKE_PORT))
                }.use { server ->
                    controlServerSocket = server

                    while (isActive && !server.isClosed) {
                        val socket = server.accept()
                        socket.tcpNoDelay = true
                        val connection = ClientConnection(
                            socket = socket,
                            reader = BufferedReader(InputStreamReader(socket.getInputStream())),
                            writer = PrintWriter(socket.getOutputStream(), true)
                        )
                        clients += connection
                        updateClientCount()
                        launch { listenToClient(connection) }
                    }
                }
            } catch (exception: IOException) {
                if (controlServerJob?.isActive == true) {
                    _uiState.value = _uiState.value.copy(
                        status = exception.message ?: "Control server error"
                    )
                }
            }
        }
    }

    private suspend fun listenToClient(connection: ClientConnection) {
        try {
            while (controlServerJob?.isActive == true && !connection.socket.isClosed) {
                val line = connection.reader.readLine() ?: break
                handleClientMessage(connection, line)
            }
        } catch (_: IOException) {
        } finally {
            clients -= connection
            connection.closeQuietly()
            updateClientCount()
        }
    }

    private fun handleClientMessage(connection: ClientConnection, rawMessage: String) {
        try {
            val message = JSONObject(rawMessage)
            when (message.optString(KEY_TYPE)) {
                TYPE_HELLO -> {
                    connection.clientId = message.optString(KEY_ID, connection.clientId)
                    _uiState.value.selectedFile?.let { file ->
                        connection.send(
                            JSONObject()
                                .put(KEY_TYPE, TYPE_OPEN_FILE)
                                .put(KEY_URL, file.toUrl())
                                .put(KEY_FILENAME, file.name)
                                .put(KEY_MEDIA_TYPE, file.kind)
                                .toString()
                        )
                    }
                }

                TYPE_OPEN_FILE,
                TYPE_LOAD -> {
                    val name = message.optString(KEY_NAME, message.optString(KEY_FILENAME))
                    val file = _uiState.value.files.firstOrNull { it.name == name } ?: return
                    _uiState.value = _uiState.value.copy(
                        selectedFile = file,
                        viewerState = file.toViewerState(file.toUrl()),
                        zoomPanState = ZoomPanState()
                    )
                    broadcast(message.toString())
                }

                "navigate_back",
                TYPE_NAV_BACK -> showFileList()

                TYPE_ZOOM_PAN,
                TYPE_ZOOM -> {
                    val zoomPan = ZoomPanState(
                        scale = message.optDouble(KEY_SCALE, 1.0).toFloat().coerceIn(1f, 5f),
                        offsetX = message.optDouble(KEY_OFFSET_X, 0.0).toFloat(),
                        offsetY = message.optDouble(KEY_OFFSET_Y, 0.0).toFloat()
                    )
                    _uiState.value = _uiState.value.copy(zoomPanState = zoomPan)
                    broadcast(message.toString())
                }

                TYPE_PLAY,
                TYPE_PAUSE,
                TYPE_SEEK,
                TYPE_SCROLL,
                TYPE_PAGE -> broadcast(message.toString())

                "scroll", "zoom", "video" -> {
                    broadcast(rawMessage)
                    emitSyncEvent(rawMessage)
                }
            }
        } catch (_: JSONException) {
        }
    }

    private fun broadcast(message: String) {
        clients.forEach { client ->
            if (!client.send(message)) {
                clients -= client
                client.closeQuietly()
            }
        }
        updateClientCount()
    }

    private fun updateClientCount() {
        _uiState.value = _uiState.value.copy(connectedClients = clients.size)
    }

    private fun ServedMediaFile.toUiFile(): HostMediaFile {
        return HostMediaFile(
            name = name,
            encodedName = URLEncoder.encode(name, Charsets.UTF_8.name()),
            kind = when {
                mimeType.startsWith("image/") -> "image"
                mimeType.startsWith("video/") -> "video"
                mimeType == "application/pdf" -> "pdf"
                else -> "other"
            }
        )
    }

    private fun HostMediaFile.toUrl(): String {
        return "http://${_uiState.value.ipAddress}:$MEDIA_HTTP_PORT/file/$encodedName"
    }

    private fun HostMediaFile.toViewerState(url: String): ViewerState {
        return when (kind) {
            "image" -> ViewerState.ViewingImage(url, name)
            "pdf" -> ViewerState.ViewingPdf(url, name)
            "video" -> ViewerState.ViewingVideo(url, name)
            else -> ViewerState.FileList
        }
    }

    override fun onCleared() {
        super.onCleared()
        controlServerJob?.cancel()
        controlServerSocket.closeQuietly()
        clients.forEach { it.closeQuietly() }
        clients.clear()
        mediaServer.stop()
    }

    private fun Closeable?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
        }
    }

    private class ClientConnection(
        val socket: Socket,
        val reader: BufferedReader,
        val writer: PrintWriter,
        var clientId: String = "unknown"
    ) {
        fun send(message: String): Boolean {
            writer.println(message)
            writer.flush()
            return !writer.checkError()
        }

        fun closeQuietly() {
            try {
                reader.close()
            } catch (_: IOException) {
            }
            writer.close()
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "screen_extender_host"
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_TYPE = "type"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_MEDIA_TYPE = "mediaType"
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
        const val ZOOM_SYNC_INTERVAL_MS = 30L
        const val NanoStartTimeoutMs = 5_000
    }
}
