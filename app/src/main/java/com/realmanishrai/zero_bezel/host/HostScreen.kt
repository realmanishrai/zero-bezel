package com.realmanishrai.zero_bezel.host

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.realmanishrai.zero_bezel.network.HANDSHAKE_PORT
import com.realmanishrai.zero_bezel.network.VIDEO_PORT

@Composable
fun HostScreen(
    onBack: () -> Unit,
    viewModel: HostViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val backgroundColor by viewModel.backgroundColor.collectAsState()
    val virtualCursor by viewModel.virtualCursorPosition.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(backgroundColor)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(color = backgroundColor)

                if (virtualCursor.first >= uiState.virtualWidth / 2f && virtualCursor.second >= 0f) {
                    val localPreviewX = ((virtualCursor.first - uiState.virtualWidth / 2f) /
                        (uiState.virtualWidth / 2f)) * size.width
                    val localPreviewY = (virtualCursor.second / uiState.virtualHeight) * size.height

                    drawCircle(
                        color = Color.Yellow,
                        radius = 34f,
                        center = Offset(
                            x = localPreviewX,
                            y = localPreviewY
                        )
                    )
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "HOST",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "LEFT SIDE",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFFFFD666)
                )
                Text(
                    text = "Virtual canvas: ${uiState.virtualWidth} x ${uiState.virtualHeight}",
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.86f)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(24.dp)
            ) {
                Text(
                    text = "Host: ${uiState.ipAddress}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Control $HANDSHAKE_PORT, video $VIDEO_PORT - ${uiState.status}",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.82f)
                )
                if (virtualCursor.first >= 0f) {
                    Text(
                        text = "Virtual cursor: ${virtualCursor.first.toInt()}, ${virtualCursor.second.toInt()}",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFD666)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            }
        }
    }
}
