package com.realmanishrai.zero_bezel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realmanishrai.zero_bezel.host.WebViewHostScreen
import com.realmanishrai.zero_bezel.network.NetworkService
import com.realmanishrai.zero_bezel.network.MediaStreamingServer
import com.realmanishrai.zero_bezel.ui.theme.GlassyBackground
import com.realmanishrai.zero_bezel.ui.theme.ZerobezelTheme
import com.realmanishrai.zero_bezel.ui.theme.glassCard
import kotlinx.coroutines.launch
import org.json.JSONObject as JsonObject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.asImageBitmap
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.lifecycle.lifecycleScope

sealed class Screen {
    object Entry : Screen()
    object Host : Screen()
    object Client : Screen()
    data class WebViewHost(
        val url: String, 
        val parent: Screen, 
        val hostWidth: Int? = null, 
        val hostHeight: Int? = null
    ) : Screen()
}

class MainActivity : ComponentActivity() {
    private var selectedFolderUri by mutableStateOf<String?>(null)
    private var connectedClientIp by mutableStateOf<String?>(null)
    private var hostLogs by mutableStateOf(listOf("Initializing..."))
    private var clientLogs by mutableStateOf(listOf("Enter Host IP above to begin."))
    private var connectionStatus by mutableStateOf("Disconnected")
    private var muteClientAudio by mutableStateOf(false)

    /** Emits JSON sync events received from the remote device over TCP. */
    private val syncEventFlow: MutableSharedFlow<String> = MutableSharedFlow(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var mediaServer: MediaStreamingServer? = null
    private var hostServerJob: kotlinx.coroutines.Job? = null
    private var clientConnectionJob: kotlinx.coroutines.Job? = null

    private fun startHostServers() {
        if (mediaServer == null) {
            mediaServer = MediaStreamingServer(this) { selectedFolderUri }.apply {
                try {
                    start()
                    hostLogs = hostLogs + "🌐 HTTP Server started on port 8082"
                } catch (e: Exception) {
                    hostLogs = hostLogs + "❌ HTTP Server start failed: ${e.message}"
                }
            }
        }

        if (hostServerJob == null) {
            hostLogs = hostLogs + "Initializing TCP socket..."
            hostServerJob = lifecycleScope.launch {
                NetworkService.startHostServer(
                    onClientConnected = { ip ->
                        connectedClientIp = ip
                    },
                    onClientDisconnected = {
                        connectedClientIp = null
                    },
                    onLog = { log ->
                        hostLogs = (hostLogs + log).takeLast(10)
                    },
                    onSyncReceived = { json ->
                        lifecycleScope.launch { syncEventFlow.emit(json) }
                    }
                )
            }
        }
    }

    private fun stopHostServers() {
        hostServerJob?.cancel()
        hostServerJob = null
        connectedClientIp = null

        try {
            mediaServer?.stop()
        } catch (e: Exception) {}
        mediaServer = null

        selectedFolderUri = null
        hostLogs = listOf("Initializing...")
    }

    private fun startClientConnection(hostIp: String) {
        clientConnectionJob?.cancel()
        connectionStatus = "Connecting..."
        clientConnectionJob = lifecycleScope.launch {
            val success = NetworkService.connectToHost(
                hostIp = hostIp,
                onConnected = {
                    connectionStatus = "Connected"
                },
                onDisconnected = {
                    connectionStatus = "Disconnected"
                },
                onLog = { log ->
                    clientLogs = (clientLogs + log).takeLast(10)
                },
                onSyncReceived = { json ->
                    lifecycleScope.launch { syncEventFlow.emit(json) }
                }
            )
            if (!success) {
                connectionStatus = "Connection Failed"
            }
        }
    }

    private fun stopClientConnection() {
        clientConnectionJob?.cancel()
        clientConnectionJob = null
        connectionStatus = "Disconnected"
        clientLogs = listOf("Enter Host IP above to begin.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHostServers()
        stopClientConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZerobezelTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Entry) }
                var extendDisplay by remember { mutableStateOf(false) }
                var hostWidth by remember { mutableStateOf<Int?>(null) }
                var hostHeight by remember { mutableStateOf<Int?>(null) }
                val context = LocalContext.current

                /* When remote taps an app icon or changes mode, update locally */
                LaunchedEffect(Unit) {
                    syncEventFlow.collect { json ->
                        try {
                            val obj = JsonObject(json)
                            val action = obj.optString("action")
                            when (action) {
                                "open_app" -> {
                                    val url = obj.optString("url")
                                    val hW = if (obj.has("hostWidth")) obj.getInt("hostWidth") else null
                                    val hH = if (obj.has("hostHeight")) obj.getInt("hostHeight") else null
                                    
                                    if (hW != null && hW > 0) hostWidth = hW
                                    if (hH != null && hH > 0) hostHeight = hH
                                    
                                    val current = (currentScreen as? Screen.WebViewHost)?.url
                                    if (url.isNotEmpty() && url != current) {
                                        val parent = if (currentScreen is Screen.WebViewHost)
                                            (currentScreen as Screen.WebViewHost).parent
                                        else currentScreen
                                        currentScreen = Screen.WebViewHost(url, parent, hostWidth, hostHeight)
                                    }
                                }
                                "host_dimensions" -> {
                                    val w = obj.optInt("width")
                                    val h = obj.optInt("height")
                                    if (w > 0) hostWidth = w
                                    if (h > 0) hostHeight = h
                                }
                                "extend_display" -> {
                                    extendDisplay = obj.optBoolean("enabled")
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val screen = currentScreen) {
                        is Screen.Entry -> EntryScreen(
                            onNavigateToHost = {
                                startHostServers()
                                currentScreen = Screen.Host
                            },
                            onNavigateToClient = { currentScreen = Screen.Client }
                        )
                        is Screen.Host -> HostScreen(
                            selectedFolderUri = selectedFolderUri,
                            connectedClientIp = connectedClientIp,
                            hostLogs = hostLogs,
                            onSelectFolder = { uri ->
                                selectedFolderUri = uri.toString()
                                hostLogs = hostLogs + "📁 Folder selected successfully"
                            },
                            onNavigateBack = {
                                stopHostServers()
                                currentScreen = Screen.Entry
                            },
                            onOpenApp = { url ->
                                val widthDp = context.resources.configuration.screenWidthDp
                                val heightDp = context.resources.configuration.screenHeightDp
                                currentScreen = Screen.WebViewHost(url, Screen.Host, widthDp, heightDp)
                                NetworkService.sendSync("{\"action\":\"open_app\",\"url\":\"${url.replace("\"", "\\\"")}\",\"hostWidth\":$widthDp,\"hostHeight\":$heightDp}") 
                            },
                            extendDisplay = extendDisplay,
                            onExtendDisplayToggle = {
                                val newVal = !extendDisplay
                                extendDisplay = newVal
                                NetworkService.sendSync("{\"action\":\"extend_display\",\"enabled\":$newVal}")
                                if (newVal) {
                                    val config = context.resources.configuration
                                    val w = config.screenWidthDp
                                    val h = config.screenHeightDp
                                    hostWidth = w
                                    hostHeight = h
                                    NetworkService.sendSync("{\"action\":\"host_dimensions\",\"width\":$w,\"height\":$h}")
                                }
                            }
                        )
                        is Screen.Client -> ClientScreen(
                            connectionStatus = connectionStatus,
                            clientLogs = clientLogs,
                            muteClientAudio = muteClientAudio,
                            extendDisplay = extendDisplay,
                            onConnect = { ip -> startClientConnection(ip) },
                            onNavigateBack = {
                                stopClientConnection()
                                currentScreen = Screen.Entry
                            },
                            onOpenApp = { url ->
                                currentScreen = Screen.WebViewHost(url, Screen.Client, hostWidth, hostHeight)
                                NetworkService.sendSync("{\"action\":\"open_app\",\"url\":\"${url.replace("\"", "\\\"")}\"}") 
                            },
                            onMuteToggle = { muteClientAudio = !muteClientAudio },
                            onExtendDisplayToggle = {
                                val newVal = !extendDisplay
                                extendDisplay = newVal
                                NetworkService.sendSync("{\"action\":\"extend_display\",\"enabled\":$newVal}")
                            }
                        )
                        is Screen.WebViewHost -> WebViewHostScreen(
                            url = screen.url,
                            onBack = { currentScreen = screen.parent },
                            onSyncSend = { json -> NetworkService.sendSync(json) },
                            syncEvents = syncEventFlow as SharedFlow<String>,
                            isClient = screen.parent is Screen.Client,
                            muteClientAudio = muteClientAudio,
                            extendDisplay = extendDisplay,
                            hostWidth = screen.hostWidth,
                            hostHeight = screen.hostHeight
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryScreen(
    onNavigateToHost: () -> Unit,
    onNavigateToClient: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    GlassyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant Glass Title Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ZeroBezel",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Dual-Screen WebView Extender",
                        fontSize = 14.sp,
                        color = Color(0xFF475569),
                        modifier = Modifier.padding(top = 8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Action Selection Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .glassCard()
                        .clickable { onNavigateToHost() }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🖥️",
                            fontSize = 32.sp
                        )
                        Text(
                            text = "Be Host",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .glassCard()
                        .clickable { onNavigateToClient() }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "📱",
                            fontSize = 32.sp
                        )
                        Text(
                            text = "Be Client",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF0F172A),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HostScreen(
    selectedFolderUri: String?,
    connectedClientIp: String?,
    hostLogs: List<String>,
    onSelectFolder: (Uri) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    extendDisplay: Boolean,
    onExtendDisplayToggle: () -> Unit
) {
    val context = LocalContext.current
    val hostIp = remember { NetworkService.getLocalIpAddress() }
    var showQrCode by remember { mutableStateOf(false) }

    val qrBitmap = remember(hostIp) {
        generateQrCodeBitmap(hostIp)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onSelectFolder(uri)
        }
    }

    GlassyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "ZeroBezel Host",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Local IP: $hostIp | Port: 8080",
                        fontSize = 13.sp,
                        color = Color(0xFF475569),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = if (connectedClientIp != null) "Connected: $connectedClientIp" else "Awaiting client...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (connectedClientIp != null) Color(0xFF16A34A) else Color(0xFFD97706),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Media & Utilities Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "File Loaders",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Select Folder")
                        }
                        Button(
                            onClick = { showQrCode = !showQrCode },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (showQrCode) "Hide QR" else "Show QR")
                        }
                    }

                    if (showQrCode && qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Scan to Connect",
                                modifier = Modifier
                                    .size(140.dp)
                                    .padding(4.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                    Text(
                        text = "WebView WebApps",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )

                    // 4 app icons grid
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("https://www.google.com") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🌐 Browser", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://localhost:8082/pdf_viewer.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📄 PDF Viewer", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://localhost:8082/video_player.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎬 Video Player", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://localhost:8082/image_gallery.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🖼️ Image Gallery", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                        }
                    }

                    HorizontalDivider(color = Color.Black.copy(alpha = 0.05f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🖥️ Extend Display (Split View)",
                            fontSize = 13.sp,
                            color = Color(0xFF475569)
                        )
                        Switch(
                            checked = extendDisplay,
                            onCheckedChange = { onExtendDisplayToggle() }
                        )
                    }
                }
            }

            // Logs view box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .glassCard()
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(hostLogs) { log ->
                        Text(log, fontSize = 11.sp, color = Color(0xFF334155))
                    }
                }
            }

            Button(
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun ClientScreen(
    connectionStatus: String,
    clientLogs: List<String>,
    muteClientAudio: Boolean = false,
    extendDisplay: Boolean = false,
    onConnect: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onMuteToggle: () -> Unit = {},
    onExtendDisplayToggle: () -> Unit = {}
) {
    val context = LocalContext.current
    var hostIpInput by remember { mutableStateOf("") }
    
    val scanner = remember {
        GmsBarcodeScanning.getClient(context)
    }

    GlassyBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "ZeroBezel Client",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Status: $connectionStatus",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (connectionStatus == "Connected") Color(0xFF16A34A) else Color(0xFFD97706),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Connection Settings Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassCard()
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = hostIpInput,
                        onValueChange = { hostIpInput = it.trim() },
                        label = { Text("Host IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onConnect(hostIpInput) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect")
                        }

                        Button(
                            onClick = {
                                scanner.startScan()
                                    .addOnSuccessListener { barcode ->
                                        barcode.rawValue?.let { scannedIp ->
                                            hostIpInput = scannedIp
                                            onConnect(scannedIp)
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        // Simple non-crashing log
                                    }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scan QR")
                        }
                    }
                }
            }

            // Connected Library Card
            if (connectionStatus == "Connected") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard()
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Connected Library",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )

                        // Mute Client Audio toggle (prevents echo during video sync)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔇 Mute Client Audio",
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                            Switch(
                                checked = muteClientAudio,
                                onCheckedChange = { onMuteToggle() }
                            )
                        }

                        // Extend Display toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🖥️ Extend Display (Split View)",
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                            Switch(
                                checked = extendDisplay,
                                onCheckedChange = { onExtendDisplayToggle() }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("https://www.google.com") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🌐 Browser", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://$hostIpInput:8082/pdf_viewer.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📄 PDF Viewer", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://$hostIpInput:8082/video_player.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🎬 Video Player", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .glassCard()
                                    .clickable { onOpenApp("http://$hostIpInput:8082/image_gallery.html") }
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🖼️ Image Gallery", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            }
                        }
                    }
                }
            }

            // Logs Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .glassCard()
                    .padding(12.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(clientLogs) { log ->
                        Text(log, fontSize = 11.sp, color = Color(0xFF334155))
                    }
                }
            }

            Button(
                onClick = onNavigateBack,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Back")
            }
        }
    }
}

private fun generateQrCodeBitmap(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
