package com.realmanishrai.zero_bezel.host

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * WebView screen used by both Host and Client.
 *
 * @param url            Initial URL to load.
 * @param onBack         Called when the user wants to leave this screen.
 * @param onSyncSend     Called with a JSON string whenever a sync-able event occurs locally.
 *                       Routes to [NetworkService.sendSync] via MainActivity.
 * @param syncEvents     SharedFlow emitting JSON sync events received from the remote device.
 * @param isClient       True when running on the Client device.
 * @param muteClientAudio If [isClient] and true, mute all <video> elements after page load.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewHostScreen(
    url: String,
    onBack: () -> Unit,
    onSyncSend: (String) -> Unit = {},
    syncEvents: SharedFlow<String>? = null,
    isClient: Boolean = false,
    muteClientAudio: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // rememberUpdatedState so the factory lambda can always read the latest values
    // without being recreated on every recomposition.
    val latestOnSyncSend      = rememberUpdatedState(onSyncSend)
    val latestIsClient        = rememberUpdatedState(isClient)
    val latestMuteClientAudio = rememberUpdatedState(muteClientAudio)

    var webView by remember { mutableStateOf<WebView?>(null) }

    /* ── Apply incoming sync events from the remote device ─────────────── */
    LaunchedEffect(syncEvents) {
        syncEvents?.collect { json ->
            val wv = webView ?: return@collect
            try {
                val obj    = JSONObject(json)
                val action = obj.optString("action")
                when (action) {
                    // JS-handled: scroll position, zoom, video controls
                    "scroll", "zoom", "play", "pause", "seek" -> {
                        // Escape single-quotes so the JSON can be safely embedded in a JS string
                        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                        wv.post {
                            wv.evaluateJavascript(
                                "if(window.applySync){window.applySync('$escaped');}", null)
                        }
                    }
                    // Kotlin-handled: URL navigation
                    "load_url" -> {
                        val target = obj.optString("url")
                        if (target.isNotEmpty()) wv.post { wv.loadUrl(target) }
                    }
                    // Kotlin-handled: back navigation
                    "go_back" -> {
                        wv.post { if (wv.canGoBack()) wv.goBack() }
                    }
                }
            } catch (_: Exception) { /* malformed JSON, ignore */ }
        }
    }

    /* ── React to muteClientAudio toggle changes while page is open ─────── */
    LaunchedEffect(muteClientAudio) {
        val wv = webView ?: return@LaunchedEffect
        if (isClient) {
            val muteJs = if (muteClientAudio)
                "document.querySelectorAll('video').forEach(function(v){v.muted=true;});"
            else
                "document.querySelectorAll('video').forEach(function(v){v.muted=false;});"
            wv.evaluateJavascript(muteJs, null)
        }
    }

    /* ── Back navigation helper ─────────────────────────────────────────── */
    fun navigateBack(wv: WebView?) {
        if (wv?.canGoBack() == true) {
            wv.goBack()
            latestOnSyncSend.value("""{"app":"browser","action":"go_back"}""")
        } else {
            onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    /* ── JavascriptInterface ─────────────────────────────── */
                    addJavascriptInterface(
                        SyncBridge { json -> latestOnSyncSend.value(json) },
                        "AndroidBridge"
                    )

                    /* ── WebViewClient ───────────────────────────────────── */
                    webViewClient = object : WebViewClient() {

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            // Inject sync.js
                            try {
                                val js = ctx.assets.open("sync.js")
                                    .bufferedReader().use { it.readText() }
                                view?.evaluateJavascript(js, null)
                            } catch (_: Exception) {}
                            // Mute videos on Client if the toggle is on
                            if (latestIsClient.value && latestMuteClientAudio.value) {
                                view?.evaluateJavascript(
                                    "document.querySelectorAll('video').forEach(function(v){v.muted=true;});",
                                    null)
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val linkUrl = request?.url?.toString() ?: return false
                            // Sync the navigation to the remote device.
                            // We return false so THIS WebView also loads it.
                            // loadUrl() called by the remote device does NOT re-trigger this,
                            // so there is no feedback loop.
                            if (linkUrl.startsWith("http")) {
                                val escaped = linkUrl.replace("\"", "\\\"")
                                latestOnSyncSend.value(
                                    """{"app":"browser","action":"load_url","url":"$escaped"}""")
                            }
                            return false
                        }
                    }

                    /* ── WebView settings ────────────────────────────────── */
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowUniversalAccessFromFileURLs = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        mediaPlaybackRequiresUserGesture = false
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        /* ── Back button overlay ──────────────────────────────────────────── */
        Button(
            onClick = { navigateBack(webView) },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text("Back")
        }
    }

    BackHandler { navigateBack(webView) }
}
