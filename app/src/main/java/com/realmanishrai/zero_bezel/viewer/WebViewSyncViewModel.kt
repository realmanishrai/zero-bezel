package com.realmanishrai.zero_bezel.viewer

import kotlinx.coroutines.flow.SharedFlow

interface WebViewSyncViewModel {
    val incomingSyncEvents: SharedFlow<String>
    fun sendSyncEvent(json: String)
}
