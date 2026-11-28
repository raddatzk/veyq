package me.raddatz.veyq.mobile.android.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.raddatz.veyq.mobile.media.SvgCover
import kotlin.coroutines.resume

/**
 * A cover drawn from an SVG (core's `SvgCover`, uc.platform.node-cover main flow 3; the web's `rasterizeSvg`): the core's one
 * page — the SVG as an `<img>` of a `data:` URL under a policy that loads only `data:` images — drawn by the system's
 * `WebView` with script off and every load blocked, laid out behind the window's content at the core's size or, where the
 * window is smaller, at the largest size of that aspect it holds (a `WebView` draws only what lies in its window), and
 * drawn in software into a bitmap. The core encodes the WebP from it.
 */
internal object SvgCoverRaster {
    /** How long a drawing may take before the pick is said unreadable. */
    private const val WAIT_MS = 8_000L

    /** [page] drawn at most [plan]'s size inside [host] (a window's root); null where the web view did not draw it. */
    suspend fun draw(host: ViewGroup, page: String, plan: SvgCover.Plan): Bitmap? = withContext(Dispatchers.Main) {
        val room = minOf(1.0, host.width.coerceAtLeast(1).toDouble() / plan.width, host.height.coerceAtLeast(1).toDouble() / plan.height)
        val w = maxOf(1, (plan.width * room).toInt())
        val h = maxOf(1, (plan.height * room).toInt())
        val web = WebView(host.context)
        try {
            web.settings.apply {
                javaScriptEnabled = false
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
            }
            web.setBackgroundColor(Color.TRANSPARENT)
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web.isVerticalScrollBarEnabled = false
            web.isHorizontalScrollBarEnabled = false
            web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // behind the window's content: drawn, never seen
            host.addView(web, 0, ViewGroup.LayoutParams(w, h))
            withTimeoutOrNull(WAIT_MS) {
                suspendCancellableCoroutine { done ->
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
                        override fun onPageFinished(view: WebView, url: String) {
                            // the page's next frame holds the decoded picture
                            view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if (done.isActive) done.resume(Unit)
                                }
                            })
                        }
                    }
                    web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.TRANSPARENT)
                web.draw(Canvas(bitmap))
                bitmap
            }
        } catch (e: Exception) {
            android.util.Log.w("veyq", "svg cover: not drawn (${e.javaClass.simpleName})")
            null
        } finally {
            host.removeView(web)
            web.destroy()
        }
    }

    /** [bitmap]'s pixels as straight RGBA, 4 bytes a pixel (`getPixels` hands them unpremultiplied): what the core encodes. */
    fun rgba(bitmap: Bitmap): ByteArray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = ByteArray(px.size * 4)
        for (i in px.indices) {
            val c = px[i]
            out[i * 4] = (c shr 16).toByte()
            out[i * 4 + 1] = (c shr 8).toByte()
            out[i * 4 + 2] = c.toByte()
            out[i * 4 + 3] = (c ushr 24).toByte()
        }
        return out
    }
}
