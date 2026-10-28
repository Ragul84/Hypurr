package com.ragul84.hypurr.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ragul84.hypurr.ui.theme.Hypurr
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/** One page or file from a dev server on the computer. */
data class PreviewResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

/** Fetches `path` from the dev server on the computer (over the encrypted channel). */
typealias PreviewFetch = suspend (port: Int, path: String, method: String, headers: List<Pair<String, String>>) -> PreviewResponse

/** Whether a WebView request is for the dev server being previewed (everything else loads normally). */
fun isPreviewRequest(host: String?, port: Int, requestPort: Int): Boolean =
    (host == "localhost" || host == "127.0.0.1") && requestPort == port

/** "Preview" buttons for the dev servers this task started; nothing when there are none. */
@Composable
fun PreviewButtons(ports: List<Int>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (ports.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ports.forEach { p -> SoftButton("Preview localhost:$p", Modifier.fillMaxWidth()) { onOpen(p) } }
    }
}

/** Full-screen live preview of `http://localhost:<port>` from the computer. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(port: Int, fetch: PreviewFetch, onClose: () -> Unit, path: String = "/") {
    val c = Hypurr.colors
    var web by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(signalIcon(SignalIcons.Back), "Close preview", onClick = onClose)
            Spacer(Modifier.width(8.dp))
            Text("localhost:$port" + path.takeIf { it != "/" }.orEmpty(), color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SoftButton("Reload") { error = null; web?.reload() }
        }
        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                            val url = req.url
                            val reqPort = if (url.port == -1) 80 else url.port
                            if (!isPreviewRequest(url.host, port, reqPort)) return null
                            val path = (url.encodedPath ?: "/").ifEmpty { "/" } + (url.encodedQuery?.let { "?$it" } ?: "")
                            return runCatching {
                                // Runs on a WebView worker thread, never the main thread.
                                val r = runBlocking { fetch(port, path, req.method, req.requestHeaders.toList()) }
                                val type = r.headers.firstOrNull { it.first.equals("content-type", true) }?.second ?: "application/octet-stream"
                                val mime = type.substringBefore(';').trim()
                                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
                                WebResourceResponse(mime, charset, r.status, reason(r.status),
                                    r.headers.toMap(), ByteArrayInputStream(r.body))
                            }.getOrElse { e ->
                                error = e.message ?: "The dev server didn't answer."
                                WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", emptyMap(),
                                    ByteArrayInputStream((e.message ?: "unreachable").toByteArray()))
                            }
                        }
                    }
                    loadUrl("http://localhost:$port" + (if (path.startsWith("/")) path else "/$path"))
                    web = this
                }
            },
        )
    }
}

private fun reason(status: Int) = when (status) {
    200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 500 -> "Internal Server Error"
    else -> "Status"
}
