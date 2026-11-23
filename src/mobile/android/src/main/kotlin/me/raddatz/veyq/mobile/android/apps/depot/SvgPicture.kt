package me.raddatz.veyq.mobile.android.apps.depot

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import me.raddatz.veyq.mobile.android.R
import me.raddatz.veyq.mobile.depot.FileModel
import me.raddatz.veyq.mobile.depot.SvgView
import java.io.ByteArrayInputStream

/**
 * An SVG on a file's page (inbox #1059; ADR 0155 § The file facts' slice D15): drawn by the system's `WebView` in its image
 * mode — the SVG is only the `<img>` of core's page ([SvgView.document]), and a browser's image mode runs no script and loads
 * nothing an SVG names. Around it, the WebView is closed: no script at all, no network (`blockNetworkLoads`), no file or
 * content access, no Safe Browsing lookup, no navigation, no window, no long-press menu; the page and the picture are served
 * from memory by [WebViewClient.shouldInterceptRequest] under a host that resolves nowhere, every other request answered 403.
 * A `foreignObject` never gets here (`SvgView.refused`, in the core's open). The zoom is the WebView's own — pinch, and the
 * buttons stepping through the web's steps — which paints the vectors again at each scale, only the tiles in view, so it is
 * sharp and raises no bitmap past the view.
 */
@SuppressLint("SetJavaScriptEnabled") // the opposite: script is turned off, explicitly
@Composable
internal fun ShownSvg(s: FileModel.Shown.Image, name: String) {
    val bytes = s.bytes
    var zoom by remember(s) { mutableDoubleStateOf(1.0) }
    val held = remember(s) { arrayOfNulls<WebView>(1) }
    // the WebView's scale at zoom ×1: the one it had before its first change (its page's density-based scale)
    val base = remember(s) { doubleArrayOf(0.0) }
    fun zoomTo(next: Double) {
        val view = held[0] ?: return
        val z = next.coerceIn(SvgView.ZOOM_STEPS.first(), SvgView.ZOOM_STEPS.last())
        view.zoomBy((z / zoom).toFloat().coerceIn(0.02f, 99f))
        zoom = z
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { zoomTo(SvgView.zoomOut(zoom)) }, enabled = zoom > SvgView.ZOOM_STEPS.first()) {
                Icon(Icons.Outlined.ZoomOut, stringResource(R.string.file_zoom_out))
            }
            Text("${Math.round(zoom * 100)} %")
            IconButton(onClick = { zoomTo(SvgView.zoomIn(zoom)) }, enabled = zoom < SvgView.ZOOM_STEPS.last()) {
                Icon(Icons.Outlined.ZoomIn, stringResource(R.string.file_zoom_in))
            }
            IconButton(onClick = { zoomTo(1.0) }, enabled = zoom > 1.0) {
                Icon(Icons.Outlined.FitScreen, stringResource(R.string.file_zoom_fit))
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = name },
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = false
                        blockNetworkLoads = true
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        safeBrowsingEnabled = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = false
                    }
                    isLongClickable = false
                    setOnLongClickListener { true }
                    isHapticFeedbackEnabled = false
                    webViewClient = SvgClient(bytes) { before, scale ->
                        if (base[0] == 0.0) base[0] = before.toDouble()
                        // a pinch moves the zoom too: the nearest step at or below it, so the buttons go on from there
                        val now = scale / base[0]
                        zoom = SvgView.ZOOM_STEPS.lastOrNull { it <= now + 1e-3 } ?: 1.0
                    }
                    held[0] = this
                    loadUrl(SvgView.DOCUMENT)
                }
            },
        )
    }
    DisposableEffect(s) {
        onDispose {
            held[0]?.apply {
                stopLoading()
                destroy()
            }
            held[0] = null
        }
    }
}

/** Serves [SvgView.DOCUMENT] and the picture from memory, refuses every other request and every navigation. */
private class SvgClient(private val picture: ByteArray, private val scaled: (before: Float, after: Float) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        val headers = mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to SvgView.CONTENT_SECURITY_POLICY)
        return when {
            request.method == "GET" && url == SvgView.DOCUMENT ->
                WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(SvgView.document().encodeToByteArray()))
            request.method == "GET" && url == SvgView.PICTURE ->
                WebResourceResponse("image/svg+xml", null, 200, "OK", headers, ByteArrayInputStream(picture))
            else -> WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", headers, ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) = scaled(oldScale, newScale)
}
