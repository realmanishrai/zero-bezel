package com.realmanishrai.zero_bezel.viewer

data class ZoomPanState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

data class VideoSyncState(
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false
)

sealed class ViewerState {
    data object FileList : ViewerState()
    data class ViewingImage(val url: String, val title: String) : ViewerState()
    data class ViewingPdf(val url: String, val title: String) : ViewerState()
    data class ViewingVideo(val url: String, val title: String) : ViewerState()
}
