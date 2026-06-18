package com.realmanishrai.zero_bezel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ScreenFrameBus {
    private val _frames = MutableSharedFlow<String>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val frames: SharedFlow<String> = _frames.asSharedFlow()

    fun publish(frameJson: String) {
        _frames.tryEmit(frameJson)
    }
}
