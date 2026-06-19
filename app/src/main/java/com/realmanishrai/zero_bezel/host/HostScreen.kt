package com.realmanishrai.zero_bezel.host

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.MEDIA_HTTP_PORT
import com.realmanishrai.zero_bezel.viewer.PdfViewer
import com.realmanishrai.zero_bezel.viewer.VideoViewer
import com.realmanishrai.zero_bezel.viewer.ViewerScaffold
import com.realmanishrai.zero_bezel.viewer.ViewerState
import com.realmanishrai.zero_bezel.viewer.ZoomPanState
import com.realmanishrai.zero_bezel.viewer.ZoomableSplitImageViewer

@Composable
fun HostScreen(
    onBack: () -> Unit,
    viewModel: HostViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.onFolderSelected(uri)
        }
    }

    when (val viewerState = uiState.viewerState) {
        ViewerState.FileList -> HostFileListScreen(
            uiState = uiState,
            onBack = onBack,
            onPickFolder = { folderPicker.launch(null) },
            onFileClick = viewModel::selectFile
        )

        is ViewerState.ViewingImage -> ViewerScaffold(
            title = viewerState.title,
            onBack = viewModel::showFileList
        ) { modifier ->
            ZoomableSplitImageViewer(
                url = viewerState.url,
                showRightHalf = false,
                zoomPanState = uiState.zoomPanState,
                onZoomPanChanged = { viewModel.updateZoomPan(it.scale, it.offsetX, it.offsetY) },
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
                showRightHalf = false,
                isMaster = true,
                syncState = uiState.videoSyncState,
                onSyncUpdate = viewModel::updateVideoSync,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun HostFileListScreen(
    uiState: HostUiState,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onFileClick: (HostMediaFile) -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF071B33))
                .padding(20.dp)
        ) {
            Text(
                text = "Host Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Host ${uiState.ipAddress} | HTTP $MEDIA_HTTP_PORT | Control $HANDSHAKE_PORT",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.76f)
            )
            Text(
                text = "Clients: ${uiState.connectedClients}",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFD666)
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
                Button(onClick = onPickFolder) {
                    Text("Select Content Folder")
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
                        Text(
                            text = file.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
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
