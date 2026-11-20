package me.raddatz.veyq.mobile.android.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex

/**
 * A PDF opened over an anonymous in-memory file and the renderer over it; both closed with the page. Every PDF the app
 * shows goes this way (a file's page, a receipt), never through a file on disk.
 */
internal class OpenPdf(val fd: android.os.ParcelFileDescriptor, val renderer: android.graphics.pdf.PdfRenderer) {
    val lock = Mutex()

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    /** What opening a PDF came to: [Open], or [Locked] (it needs its password — or another one), or [Failed]. */
    sealed interface Opening {
        class Open(val pdf: OpenPdf) : Opening
        data object Locked : Opening
        data object Failed : Opening
    }

    companion object {
        /** Whether this device's renderer takes a password (`LoadParams`, Android 15): below it a locked PDF is said, not asked. */
        val takesPassword: Boolean get() = android.os.Build.VERSION.SDK_INT >= 35

        /**
         * [bytes] written into a `memfd` (anonymous memory, no path, gone with its last descriptor: no plaintext at rest,
         * ADR 0047) and handed to `PdfRenderer`; null where the renderer refuses it.
         */
        fun of(bytes: ByteArray): OpenPdf? = (open(bytes, null) as? Opening.Open)?.pdf

        /**
         * [bytes] opened as [of] does, with [password] where one was typed (only the renderer on this device sees it: it is
         * neither kept nor sent): [Opening.Locked] where the PDF needs one, or another — the renderer refuses an encrypted
         * PDF it cannot open with a `SecurityException`.
         */
        fun open(bytes: ByteArray, password: String?): Opening {
            val fd = runCatching { android.system.Os.memfd_create("veyq-pdf", 0) }.getOrNull() ?: return Opening.Failed
            return runCatching {
                java.io.FileOutputStream(fd).let { out ->
                    out.write(bytes)
                    out.flush()
                }
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
                val pfd = android.os.ParcelFileDescriptor.dup(fd)
                runCatching { android.system.Os.close(fd) }
                try {
                    Opening.Open(OpenPdf(pfd, renderer(pfd, password)))
                } catch (e: SecurityException) {
                    runCatching { pfd.close() }
                    Opening.Locked
                } catch (e: Exception) {
                    runCatching { pfd.close() }
                    Opening.Failed
                }
            }.getOrElse {
                runCatching { android.system.Os.close(fd) }
                Opening.Failed
            }
        }

        private fun renderer(pfd: android.os.ParcelFileDescriptor, password: String?): android.graphics.pdf.PdfRenderer =
            if (password != null && takesPassword) {
                android.graphics.pdf.PdfRenderer(pfd, android.graphics.pdf.LoadParams.Builder().setPassword(password).build())
            } else android.graphics.pdf.PdfRenderer(pfd)
    }
}
