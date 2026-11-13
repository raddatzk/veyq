package me.raddatz.veyq.mobile.qr

import kotlin.time.TimeSource

/**
 * What a camera's frame becomes before a [QrScan] reads it (D3 of the QR CR): the luminance plane a host's camera hands
 * over — the Y of YUV, with its row stride — scaled down to at most [QrScan.MAX_EDGE] px on its long edge. Every app's
 * camera calls this, so the scaling is one rule, not one per platform.
 */
object QrFrames {
    /**
     * The plane as a frame of at most [maxEdge] px on its long edge: the plane itself when it fits (no copy — the host
     * hands over a plane it no longer touches), else averaged down by the smallest whole factor that fits, each output
     * pixel the mean of its factor × factor block (a box filter: a module keeps its edge, a camera's noise averages out).
     */
    fun fit(width: Int, height: Int, rowStride: Int, luminance: ByteArray, maxEdge: Int = QrScan.MAX_EDGE): QrFrame {
        require(maxEdge > 0) { "a long edge of $maxEdge" }
        val long = maxOf(width, height)
        if (long <= maxEdge) return QrFrame(width, height, rowStride, luminance)
        val factor = (long + maxEdge - 1) / maxEdge
        val outWidth = width / factor
        val outHeight = height / factor
        require(outWidth > 0 && outHeight > 0) { "a ${width}x$height plane scaled by $factor" }
        QrFrame(width, height, rowStride, luminance) // the plane's own bounds, checked before it is read
        val out = ByteArray(outWidth * outHeight)
        val block = factor * factor
        for (y in 0 until outHeight) {
            val top = y * factor
            for (x in 0 until outWidth) {
                val left = x * factor
                var sum = 0
                for (dy in 0 until factor) {
                    val row = (top + dy) * rowStride + left
                    for (dx in 0 until factor) sum += luminance[row + dx].toInt() and 0xff
                }
                out[y * outWidth + x] = (sum / block).toByte()
            }
        }
        return QrFrame(outWidth, outHeight, outWidth, out)
    }

    /**
     * The core's decoder, timed: [report] hears each frame's size, what the decode took in milliseconds and whether it
     * read a code — never what the code says. A camera view logs it, so a device's time per frame is measured in the
     * build that ships, not only in a test binary.
     */
    fun timedDecode(report: (width: Int, height: Int, millis: Double, read: Boolean) -> Unit): suspend (QrFrame) -> String? = { frame ->
        val start = TimeSource.Monotonic.markNow()
        val text = QrDecoder.decode(frame)
        report(frame.width, frame.height, start.elapsedNow().inWholeMicroseconds / 1000.0, text != null)
        text
    }
}
