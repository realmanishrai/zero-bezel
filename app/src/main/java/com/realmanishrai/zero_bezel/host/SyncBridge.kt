package com.realmanishrai.zero_bezel.host

import android.webkit.JavascriptInterface

/**
 * JavaScript bridge exposed to sync.js as `window.AndroidBridge`.
 * NOTE: @JavascriptInterface methods are called on a BACKGROUND thread by WebView.
 * [onEvent] must be thread-safe (NetworkService.sendSync uses a volatile PrintWriter).
 */
class SyncBridge(private val onEvent: (String) -> Unit) {

    @JavascriptInterface
    fun sendEvent(json: String) {
        onEvent(json)
    }
}
