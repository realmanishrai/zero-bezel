package com.realmanishrai.zero_bezel.viewer

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.collectLatest

class JsInterface(private val onEvent: (String) -> Unit) {
    @JavascriptInterface
    fun onSyncEvent(json: String) {
        onEvent(json)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewViewerScreen(
    fileUrl: String,
    fileType: String,
    isClient: Boolean,
    viewModel: WebViewSyncViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val url = remember(fileUrl, isClient) {
        val role = if (isClient) "client" else "host"
        val prefix = if (fileUrl.contains("?")) "&" else "?"
        "$fileUrl${prefix}role=$role"
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(viewModel, webViewRef) {
        viewModel.incomingSyncEvents.collectLatest { event ->
            val escapedJson = event.replace("\\", "\\\\").replace("'", "\\'")
            webViewRef?.post {
                webViewRef?.evaluateJavascript("applySync('$escapedJson')", null)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = false

                // Add JavaScript interface
                addJavascriptInterface(JsInterface { event ->
                    viewModel.sendSyncEvent(event)
                }, "Android")

                webViewRef = this
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { webView ->
        if (webView.url != url) {
            webView.loadUrl(url)
        }
        webViewRef = webView
    }

    // Back button handler
    BackHandler {
        // Notify other device BEFORE navigating back
        viewModel.sendSyncEvent("""{"type":"navigate_back"}""")
        onNavigateBack()
    }
}
