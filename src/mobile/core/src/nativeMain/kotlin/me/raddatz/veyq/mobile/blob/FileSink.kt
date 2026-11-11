@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fwrite

/**
 * A file on the device written as its bytes are produced (a download, an archive), at the path the platform's save
 * dialog handed over: the dialog and its grant are the platform's, the bytes the core's (the CR's D3). Nothing is held
 * whole. A write that does not finish leaves nothing at [path]: [fill] removes the partial file whenever its producer
 * throws or its answer is not one to keep.
 */
class FileSink private constructor(val path: String, private var file: CPointer<FILE>?) {
    /** Appends [bytes]; throws when the device took fewer (a full disk). */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val f = file ?: throw IllegalStateException("closed: $path")
        val n = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), f) }.toLong()
        if (n != bytes.size.toLong()) throw IllegalStateException("short write to $path")
    }

    /** Closes the file; it stays at [path] only when [keep], else it is removed. */
    fun close(keep: Boolean) {
        val f = file ?: return
        file = null
        val closed = fclose(f) == 0
        if (!keep || !closed) removeFile(path)
    }

    /**
     * Hands [produce] this file's [write] as its sink and closes it: kept when [produce] returned an answer [kept] holds
     * for, removed when it threw or did not.
     */
    suspend fun <T> fill(kept: (T) -> Boolean = { true }, produce: suspend (sink: (ByteArray) -> Unit) -> T): T {
        var keep = false
        try {
            val answer = produce(::write)
            keep = kept(answer)
            return answer
        } finally {
            close(keep)
        }
    }

    companion object {
        /** [path] opened for writing (emptied when it exists); null when it does not open. */
        fun open(path: String): FileSink? = openFile(path, "wb")?.let { FileSink(path, it) }

        /** [fill] on [path] opened for writing; throws when it does not open. */
        suspend fun <T> write(path: String, kept: (T) -> Boolean = { true }, produce: suspend (sink: (ByteArray) -> Unit) -> T): T =
            (open(path) ?: throw IllegalStateException("cannot write $path")).fill(kept, produce)
    }
}
