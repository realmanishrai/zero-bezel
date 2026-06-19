package com.realmanishrai.zero_bezel.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = { actions() }
            )
        }
    ) { innerPadding ->
        content(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
fun ZoomableSplitImageViewer(
    url: String,
    showRightHalf: Boolean,
    zoomPanState: ZoomPanState,
    onZoomPanChanged: (ZoomPanState) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(zoomPanState) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onZoomPanChanged(
                        ZoomPanState(
                            scale = (zoomPanState.scale * zoom).coerceIn(1f, 5f),
                            offsetX = zoomPanState.offsetX + pan.x,
                            offsetY = zoomPanState.offsetY + pan.y
                        )
                    )
                }
            }
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val splitOffset = if (showRightHalf) -viewportWidthPx else 0f

        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 2f * zoomPanState.scale
                    scaleY = 2f * zoomPanState.scale
                    translationX = splitOffset + zoomPanState.offsetX
                    translationY = zoomPanState.offsetY
                }
        )
    }
}

@Composable
fun VideoViewer(
    url: String,
    showRightHalf: Boolean,
    isMaster: Boolean,
    syncState: VideoSyncState,
    onSyncUpdate: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(exoPlayer, isMaster) {
        if (isMaster) {
            while (isActive) {
                delay(500)
                onSyncUpdate(exoPlayer.currentPosition, exoPlayer.isPlaying)
            }
        }
    }

    LaunchedEffect(exoPlayer, syncState, isMaster) {
        if (!isMaster) {
            if (abs(exoPlayer.currentPosition - syncState.positionMs) > 1_000L) {
                exoPlayer.seekTo(syncState.positionMs)
            }
            if (syncState.isPlaying && !exoPlayer.isPlaying) exoPlayer.play()
            if (!syncState.isPlaying && exoPlayer.isPlaying) exoPlayer.pause()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black)
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = 2f
                    translationX = if (showRightHalf) -viewportWidthPx else 0f
                },
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    player = exoPlayer
                    useController = isMaster
                }
            },
            update = { it.player = exoPlayer }
        )
    }
}

@Composable
fun PdfViewer(
    url: String,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        bitmap = null
        error = null
        runCatching {
            withContext(Dispatchers.IO) { renderFirstPdfPage(url) }
        }.onSuccess {
            bitmap = it
        }.onFailure {
            error = it.message ?: "Unable to render PDF"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF202124)),
        contentAlignment = Alignment.Center
    ) {
        val page = bitmap
        if (page != null) {
            androidx.compose.foundation.Image(
                bitmap = page.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = error ?: "Loading PDF...",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }
    }
}

private fun renderFirstPdfPage(url: String): Bitmap {
    val tempFile = File.createTempFile("screen-extender-pdf", ".pdf")
    try {
        URL(url).openStream().use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            val page = renderer.openPage(0)
            page.use {
                val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    } finally {
        tempFile.delete()
    }
}
