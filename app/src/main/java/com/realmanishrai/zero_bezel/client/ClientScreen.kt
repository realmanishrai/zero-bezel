package com.realmanishrai.zero_bezel.client

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.VIDEO_PORT
import kotlin.math.hypot

@Composable
fun ClientScreen(
    onBack: () -> Unit,
    viewModel: ClientViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.resetEvents.collect {
            Toast.makeText(context, "Host Reset Screen!", Toast.LENGTH_SHORT).show()
        }
    }

    if (uiState.isConnected) {
        TouchpadScreen(
            latestFrame = latestFrame,
            onTouchpadSizeChanged = viewModel::onTouchpadSizeChanged,
            onTap = viewModel::sendTap,
            onSwipe = viewModel::sendSwipe,
            onMove = viewModel::sendMove
        )
    } else {
        ClientConnectScreen(
            uiState = uiState,
            onBack = onBack,
            onHostIpChanged = viewModel::onHostIpChanged,
            onConnectClick = viewModel::connectToHost
        )
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
                    text = "Control $HANDSHAKE_PORT, video $VIDEO_PORT",
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
private fun TouchpadScreen(
    latestFrame: Bitmap?,
    onTouchpadSizeChanged: (Float, Float) -> Unit,
    onTap: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2B2B2B))
            .onSizeChanged {
                onTouchpadSizeChanged(
                    it.width.toFloat(),
                    it.height.toFloat()
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var startPosition: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change == null) continue

                        if (change.pressed && startPosition == null) {
                            startPosition = change.position
                        }

                        if (change.pressed && startPosition != null) {
                            onMove(change.position.x, change.position.y)
                        }

                        if (change.changedToUp()) {
                            val start = startPosition ?: change.position
                            val end = change.position
                            val distance = hypot(
                                (end.x - start.x).toDouble(),
                                (end.y - start.y).toDouble()
                            )

                            if (distance < TAP_DISTANCE_THRESHOLD_PX) {
                                onTap(end.x, end.y)
                            } else {
                                onSwipe(start.x, start.y, end.x, end.y)
                            }

                            startPosition = null
                        }

                        change.consume()
                    }
                }
            }
    ) {
        latestFrame?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Host screen stream",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

private const val TAP_DISTANCE_THRESHOLD_PX = 20.0
