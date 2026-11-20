package me.raddatz.veyq.mobile.android.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.raddatz.veyq.mobile.qr.QrFrames
import me.raddatz.veyq.mobile.qr.QrScan
import java.util.concurrent.Executors

/**
 * A QR scan's camera (the QR CR, D4): CameraX's preview and its analysis stream feeding core's [QrScan] — the Y plane of
 * each frame, at most 720p asked for and [QrFrames.fit] to the scan's edge, copied only when the scan would take it
 * ([QrScan.ready]). The decoding and what a code means are core's; this view only says what the camera did: no camera on
 * the device ([QrScan.Camera.NONE]), the permission refused ([QrScan.Camera.DENIED]), or running.
 *
 * It draws the preview while the scan is [QrScan.State.Scanning] or [QrScan.State.Starting]; for every other state the
 * caller draws ([other]) — what a device without a camera does instead, where to allow the camera, what was found
 * (D3: the model carries no words of its own). The camera is bound to the screen's lifecycle and unbound once the scan
 * leaves scanning. Each decode's time is logged (`veyq-scan`), never what a code says.
 */
@Composable
fun <T : Any> QrCamera(scan: QrScan<T>, modifier: Modifier = Modifier, other: @Composable (QrScan.State<T>) -> Unit) {
    val context = LocalContext.current
    val state by scan.state.collectAsState()
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    // held as state: the grant must recompose this view, or the preview never binds after "Allow" (the scan stayed
    // Starting, measured 2026-11-13 on the emulator: nothing else changes when the permission dialog closes)
    var allowed by remember { mutableStateOf(granted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted // granted: the preview below binds and says RUNNING
        if (!granted) scan.camera(QrScan.Camera.DENIED)
    }
    LaunchedEffect(scan) {
        when {
            !hasCamera -> scan.camera(QrScan.Camera.NONE)
            allowed -> Unit
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }
    val previewing = hasCamera && (state == QrScan.State.Scanning || (state == QrScan.State.Starting && allowed))
    if (previewing) Preview(scan, modifier) else if (state != QrScan.State.Starting) other(state)
}

private fun granted(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun <T : Any> Preview(scan: QrScan<T>, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(scan, owner) {
        val analysis = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var gone = false
        future.addListener({
            if (gone) return@addListener
            // CameraX's start fails where the device names a camera feature but has none that opens (an emulator
            // started without one, measured 2026-11-13: an InitializationException out of get(), which crashed the app)
            val p = try {
                future.get().also { provider = it }
            } catch (e: Exception) {
                Log.w(TAG, "no camera: ${(e.cause ?: e).javaClass.simpleName}")
                scan.camera(QrScan.Camera.NONE)
                return@addListener
            }
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(resolution).build().also { it.surfaceProvider = view.surfaceProvider }
            val frames = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            frames.setAnalyzer(analysis) { image -> image.use { offer(scan, it) } }
            val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, frames)
                scan.camera(QrScan.Camera.RUNNING)
            } catch (e: Exception) {
                // no camera the selector reaches after all (an emulator without one, a camera another app holds)
                Log.w(TAG, "camera not bound: ${e.javaClass.simpleName}")
                scan.camera(QrScan.Camera.NONE)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            gone = true
            provider?.unbindAll()
            analysis.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

/** The frame's Y plane, copied out of the camera's buffer only when the scan would take it. */
private fun offer(scan: QrScan<*>, image: ImageProxy) {
    if (!scan.ready) return
    val y = image.planes[0]
    val buffer = y.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    scan.offer(QrFrames.fit(image.width, image.height, y.rowStride, bytes))
}

/** The time each decode took on this device, never what it read (the QR CR, phase 4's measurement). */
fun qrDecodeTiming(width: Int, height: Int, millis: Double, read: Boolean) {
    Log.i(TAG, "decoded ${width}x$height in ${"%.1f".format(java.util.Locale.ROOT, millis)} ms, ${if (read) "a code" else "nothing"} read")
}

private const val TAG = "veyq-scan"
