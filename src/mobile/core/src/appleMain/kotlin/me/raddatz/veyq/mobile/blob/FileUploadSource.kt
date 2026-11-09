package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSURL
import platform.posix.FILE
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko

/**
 * A picked file on an Apple device read for an upload ([UploadSource]): opened at [url] on its first read and held until
 * [close]. A file the system's picker handed over is outside the app's sandbox: its security scope — [url]'s own, or
 * [scope]'s, the directory a folder upload picked — is entered while it is open, so the queue may read it long after the
 * pick.
 */
@OptIn(ExperimentalForeignApi::class)
class FileUploadSource(
    val url: NSURL, private val scope: NSURL?, name: String, size: Long, type: String?, modifiedAt: Long?,
) : UploadSource(name, size, type, modifiedAt) {
    private val lock = Mutex()
    private var file: CPointer<FILE>? = null
    private var scoped = false

    override suspend fun read(from: Long, length: Int): ByteArray = lock.withLock {
        val f = file ?: run {
            scoped = (scope ?: url).startAccessingSecurityScopedResource()
            val path = url.path ?: throw IllegalStateException("not a file: $url")
            (fopen(path, "rb") ?: throw IllegalStateException("cannot read $path")).also { file = it }
        }
        val buf = ByteArray(length)
        if (length == 0) return@withLock buf
        if (fseeko(f, from, SEEK_SET) != 0) throw IllegalStateException("cannot seek ${url.path}")
        val n = buf.usePinned { fread(it.addressOf(0), 1u, length.toULong(), f) }.toInt()
        if (n == length) buf else buf.copyOf(n)
    }

    override fun close() {
        file?.let { fclose(it) }
        file = null
        if (scoped) (scope ?: url).stopAccessingSecurityScopedResource()
        scoped = false
    }
}
