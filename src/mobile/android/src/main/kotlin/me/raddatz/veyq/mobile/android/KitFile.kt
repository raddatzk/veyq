package me.raddatz.veyq.mobile.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.raddatz.veyq.mobile.account.KitImport
import me.raddatz.veyq.mobile.account.KitPictures
import me.raddatz.veyq.mobile.qr.QrFrame

/**
 * An Emergency Kit file the person picked (`OpenDocument`), read for the sign-in without a copy on disk (ADR 0047,
 * en.account.key-model § The Emergency Kit): the document read through the [ContentResolver] into memory, at most
 * [KitImport.FILE_CAP] bytes, never into `cacheDir` or a file of the app's. Where it is larger nothing more is read and
 * the answer is empty, which the core says as a kit it could not read.
 */
internal object KitFile {
    suspend fun read(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val size = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size > KitImport.FILE_CAP) return@withContext ByteArray(0)
        runCatching { resolver.openInputStream(uri)?.use { capped(it, size?.toInt()) } }.getOrNull() ?: ByteArray(0)
    }

    /**
     * The stream's bytes up to the cap: into one array of the size the provider said, grown by doubling where it said
     * none — each array let go zeroed. Past the cap: empty, what was read zeroed.
     */
    private fun capped(input: java.io.InputStream, hint: Int?): ByteArray {
        var buf = ByteArray((hint?.plus(1) ?: 64 * 1024).coerceIn(1, KitImport.FILE_CAP + 1)) // one more than said: the end seen without growing
        var n = 0
        while (true) {
            if (n == buf.size) {
                if (buf.size > KitImport.FILE_CAP) break
                val grown = buf.copyOf(minOf(buf.size * 2, KitImport.FILE_CAP + 1))
                buf.fill(0)
                buf = grown
            }
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n > KitImport.FILE_CAP) {
            buf.fill(0)
            return ByteArray(0)
        }
        if (n == buf.size) return buf
        val out = buf.copyOf(n)
        buf.fill(0)
        return out
    }
}

/**
 * What the core's kit read asks this platform to draw ([KitPictures]): a PDF's pages through `PdfRenderer` over an
 * anonymous in-memory file holding [bytes] (`OpenPdf`, a `memfd`, as every PDF the app shows), a picture through
 * `ImageDecoder` from the bytes themselves — each as 8-bit luminance. [bytes] are the core's read's own (zeroed by it
 * once read); [close] closes the renderer, and every bitmap is cleared and recycled once its luminance is taken.
 */
internal class AndroidKitPictures(private val bytes: ByteArray) : KitPictures, AutoCloseable {
    private var pdf: OpenPdf? = null
    private var opened = false

    private fun open(): OpenPdf? {
        if (!opened) {
            opened = true
            pdf = OpenPdf.of(bytes)
        }
        return pdf
    }

    override suspend fun pdfPages(): Int? = withContext(Dispatchers.IO) { open()?.renderer?.pageCount }

    override suspend fun pdfPage(page: Int, width: Int): QrFrame? = withContext(Dispatchers.IO) {
        val r = open()?.renderer ?: return@withContext null
        if (page < 1 || page > r.pageCount) return@withContext null
        runCatching {
            r.openPage(page - 1).use { p ->
                val h = (width.toLong() * p.height / p.width).toInt().coerceIn(1, 4 * width)
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                luminance(bmp)
            }
        }.getOrNull()
    }

    override suspend fun picture(longSide: Int): QrFrame? = withContext(Dispatchers.IO) {
        runCatching {
            val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = longSide.toFloat() / maxOf(w, h)
                decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            luminance(bmp)
        }.getOrNull()
    }

    override fun close() {
        pdf?.close()
        pdf = null
    }

    /** The bitmap's pixels as luminance (BT.601, over white where a pixel is transparent), a row at a time; then cleared. */
    private fun luminance(bmp: Bitmap): QrFrame {
        val w = bmp.width
        val h = bmp.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        try {
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    val a = c ushr 24 and 0xff
                    val lum = (299 * (c shr 16 and 0xff) + 587 * (c shr 8 and 0xff) + 114 * (c and 0xff)) / 1000
                    out[y * w + x] = ((lum * a + 255 * (255 - a)) / 255).toByte()
                }
            }
        } finally {
            row.fill(0)
            bmp.eraseColor(0)
            bmp.recycle()
        }
        return QrFrame(w, h, w, out)
    }
}
