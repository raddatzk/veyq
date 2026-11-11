@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fread

/**
 * A file on the device read for an upload ([UploadSource]) at the path the platform's open dialog handed over, at any
 * offset a frame at a time (the CR's D3): opened on its first read and held until [close]. A platform whose grant must be
 * held while the file is open (Apple's security scope) enters it in [opening] and leaves it in [closing].
 */
open class FileSource(
    val path: String, name: String, size: Long, type: String?, modifiedAt: Long?,
) : UploadSource(name, size, type, modifiedAt) {
    private val lock = Mutex()
    private var file: CPointer<FILE>? = null

    /** Called before the file is opened. */
    protected open fun opening() {}

    /** Called after the file was closed, and after an open that failed. */
    protected open fun closing() {}

    override suspend fun read(from: Long, length: Int): ByteArray = lock.withLock {
        val f = file ?: run {
            opening()
            openFile(path, "rb") ?: run {
                closing()
                throw IllegalStateException("cannot read $path")
            }
        }.also { file = it }
        val buf = ByteArray(length)
        if (length == 0) return@withLock buf
        if (!seekFile(f, from)) throw IllegalStateException("cannot seek $path")
        val n = buf.usePinned { fread(it.addressOf(0), 1.convert(), length.convert(), f) }.toInt()
        if (n == length) buf else buf.copyOf(n)
    }

    override fun close() {
        val f = file ?: return
        file = null
        fclose(f)
        closing()
    }
}
