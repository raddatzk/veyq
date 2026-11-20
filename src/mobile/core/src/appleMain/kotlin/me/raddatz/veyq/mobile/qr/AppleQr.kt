package me.raddatz.veyq.mobile.qr

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * The scan as the Apple app's camera reaches it (`apple/Veyq/Core/QrCamera.swift`): Swift sees neither Kotlin's default
 * arguments nor a suspending decoder, so the scan is made here, and a frame is made from the camera's luminance plane
 * with one copy, never byte by byte across the language boundary.
 */
object AppleQr {
    /**
     * A scan whose [accepts] maps a code's text to what the caller's model made of it (null: not ours, skipped).
     * [timing] hears each decode's frame size, milliseconds and whether it read a code — never the text.
     */
    fun scan(accepts: (String) -> Any?, timing: (width: Int, height: Int, millis: Double, read: Boolean) -> Unit): QrScan<Any> =
        QrScan(accepts, QrFrames.timedDecode(timing))

    /**
     * The luminance plane [plane] (the Y of a `420YpCbCr8BiPlanar` pixel buffer, [rowStride] its bytes per row) copied
     * once and fitted to the scan's long edge ([QrFrames.fit]). The caller keeps the buffer locked for this call only.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun frame(plane: NSData, width: Int, height: Int, rowStride: Int): QrFrame {
        val size = plane.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), plane.bytes, plane.length) }
        return QrFrames.fit(width, height, rowStride, bytes)
    }
}
