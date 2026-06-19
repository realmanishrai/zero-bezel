package com.realmanishrai.zero_bezel.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.MEDIA_HTTP_PORT
import com.realmanishrai.zero_bezel.viewer.PdfViewer
import com.realmanishrai.zero_bezel.viewer.VideoViewer
import com.realmanishrai.zero_bezel.viewer.ViewerScaffold
import com.realmanishrai.zero_bezel.viewer.ViewerState
import com.realmanishrai.zero_bezel.viewer.ZoomableSplitImageViewer

@Composable
fun ClientScreen(
    onBack: () -> Unit,
    viewModel: ClientViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    if (!uiState.isConnected) {
        ClientConnectScreen(
            uiState = uiState,
            onBack = onBack,
            onHostIpChanged = viewModel::onHostIpChanged,
            onConnectClick = viewModel::connectToHost
        )
        return
    }

    when (val viewerState = uiState.viewerState) {
        ViewerState.FileList -> ClientFileListScreen(
            uiState = uiState,
            onBack = onBack,
            onRefresh = viewModel::refreshFiles,
            onFileClick = viewModel::selectFile
        )

        is ViewerState.ViewingImage -> ViewerScaffold(
            title = viewerState.title,
            onBack = viewModel::showFileList
        ) { modifier ->
            ZoomableSplitImageViewer(
                url = viewerState.url,
                showRightHalf = true,
                zoomPanState = uiState.zoomPanState,
                onZoomPanChanged = { viewModel.sendZoomPan(it.scale, it.offsetX, it.offsetY) },
                modifier = modifier
            )
        }

        is ViewerState.ViewingPdf -> ViewerScaffold(
            title = viewerState.title,
            onBack = viewModel::showFileList
        ) { modifier ->
            PdfViewer(url = viewerState.url, modifier = modifier)
        }

        is ViewerState.ViewingVideo -> ViewerScaffold(
            title = viewerState.title,
            onBack = viewModel::showFileList
        ) { modifier ->
            VideoViewer(
                url = viewerState.url,
                showRightHalf = true,
                isMaster = false,
                syncState = uiState.videoSyncState,
                onSyncUpdate = { _, _ -> },
                modifier = modifier
            )
        }
    }
}

@Composable
private fun ClientConnectScreen(
    uiState: ClientUiState,
    onBack: () -> Unit,
    onHostIpChanged: (String) -> Unit,
    onConnectClick: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Client Mode",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Control $HANDSHAKE_PORT, media HTTP $MEDIA_HTTP_PORT",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.hostIp,
                        onValueChange = onHostIpChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Host IP Address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    Button(
                        onClick = onConnectClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text("Connect")
                    }

                    Text(
                        text = uiState.status,
                        modifier = Modifier.padding(top = 24.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun ClientFileListScreen(
    uiState: ClientUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFileClick: (ClientMediaFile) -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF2D1117))
                .padding(20.dp)
        ) {
            Text(
                text = "Client Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Files stream from http://${uiState.hostIp}:$MEDIA_HTTP_PORT",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                Button(onClick = onRefresh) {
                    Text("Refresh")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp)
            ) {
                items(uiState.files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileClick(file) }
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${file.kind.icon()} ${file.name}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            Text(
                                text = file.size.formatBytes(),
                                modifier = Modifier.padding(top = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.66f)
                            )
                        }
                        Text(
                            text = file.kind.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFFFD666)
                        )
                    }
                }
            }
        }
    }
}

private fun String.icon(): String {
    return when (this) {
        "pdf" -> "[PDF]"
        "image" -> "[IMG]"
        "video" -> "[VID]"
        else -> "[FILE]"
    }
}

private fun Long.formatBytes(): String {
    if (this < 1024L) return "$this B"
    val kb = this / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
