package me.raddatz.veyq.mobile.account

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.qr.QrFrame
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.posix.memcpy
import platform.posix.memset

/**
 * What the Apple app draws for a kit's read (`apple/Veyq/KitFile.swift`: PDFKit's pages, ImageIO's picture, each into
 * an 8-bit grey `CGContext` over a buffer it hands here). Swift implements no suspending Kotlin call, so these block the
 * calling thread, which is never the main one ([AppleKit.pictures] calls them on `Dispatchers.Default`). Each answers
 * null where it cannot; a page count of 0 says the PDF did not open.
 */
interface KitDrawing {
    fun pdfPages(): Int

    /** Page [page] (1-based) drawn [width] px wide. */
    fun pdfPage(page: Int, width: Int): KitDrawn?

    /** The file as an image, its longer side [longSide] px. */
    fun picture(longSide: Int): KitDrawn?
}

/** A drawn picture: [luminance] rows of [rowStride] bytes (0 black, 255 white); the core zeroes it once copied. */
class KitDrawn(val luminance: NSMutableData, val width: Int, val height: Int, val rowStride: Int)

/**
 * The kit's read as the Apple app reaches it: the picked file's bytes copied once into the core's array (the Swift side
 * clears its own), and [KitDrawing] as the core's [KitPictures]. `SessionModel.readKit` does the rest.
 */
object AppleKit {
    /** [data] copied into a Kotlin array in one copy, never byte by byte across the language boundary. */
    @OptIn(ExperimentalForeignApi::class)
    fun bytes(data: NSData): ByteArray {
        val n = data.length.toInt()
        if (n == 0) return ByteArray(0)
        return ByteArray(n).also { out -> out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) } }
    }

    fun pictures(drawing: KitDrawing): KitPictures = object : KitPictures {
        override suspend fun pdfPages(): Int? = withContext(Dispatchers.Default) { drawing.pdfPages().takeIf { it > 0 } }

        override suspend fun pdfPage(page: Int, width: Int): QrFrame? =
            withContext(Dispatchers.Default) { drawing.pdfPage(page, width)?.let(::frame) }

        override suspend fun picture(longSide: Int): QrFrame? =
            withContext(Dispatchers.Default) { drawing.picture(longSide)?.let(::frame) }
    }

    /** The drawn buffer copied into a frame, then zeroed where Swift drew it. */
    @OptIn(ExperimentalForeignApi::class)
    private fun frame(d: KitDrawn): QrFrame? {
        val size = d.luminance.length.toInt()
        try {
            if (d.width <= 0 || d.height <= 0 || d.rowStride < d.width || size.toLong() < d.rowStride.toLong() * d.height) return null
            val out = ByteArray(size)
            out.usePinned { memcpy(it.addressOf(0), d.luminance.bytes, d.luminance.length) }
            return QrFrame(d.width, d.height, d.rowStride, out)
        } finally {
            if (size > 0) memset(d.luminance.mutableBytes, 0, d.luminance.length)
        }
    }
}
