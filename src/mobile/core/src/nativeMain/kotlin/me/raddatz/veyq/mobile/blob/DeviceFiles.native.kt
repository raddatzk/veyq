@file:OptIn(ExperimentalForeignApi::class)

package me.raddatz.veyq.mobile.blob

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fread
import platform.posix.fwrite

// Apple, Linux and Windows: `Files.kt`'s calls, each platform's own below them (a path as UTF-16 on Windows).

internal actual val deviceFiles: DeviceFiles = object : DeviceFiles {
    override val separator: Char get() = pathSeparator

    override fun createDirectory(path: String): Boolean = me.raddatz.veyq.mobile.blob.createDirectory(path)

    override fun list(path: String): List<String>? = listDirectory(path)

    override fun read(path: String): ByteArray? {
        val size = size(path) ?: return null
        if (size > Int.MAX_VALUE) return null
        return read(path, 0, size.toInt())
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray? {
        val f = openFile(path, "rb") ?: return null
        try {
            val buf = ByteArray(length)
            if (length == 0) return buf
            if (!seekFile(f, offset)) return null
            val n = buf.usePinned { fread(it.addressOf(0), 1.convert(), length.convert(), f) }.toInt()
            return if (n == length) buf else null
        } finally {
            fclose(f)
        }
    }

    override fun size(path: String): Long? = pathFacts(path)?.takeIf { it.kind == PathKind.FILE }?.size

    override fun write(path: String, bytes: ByteArray) = put(path, "wb", bytes)

    override fun append(path: String, bytes: ByteArray): Long {
        val at = size(path) ?: 0L
        put(path, "ab", bytes)
        return at
    }

    private fun put(path: String, mode: String, bytes: ByteArray) {
        val f = openFile(path, mode) ?: throw IllegalStateException("cannot write $path")
        var ok = false
        try {
            val n = if (bytes.isEmpty()) 0L else bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), f) }.toLong()
            ok = n == bytes.size.toLong() && syncFile(f)
        } finally {
            if (fclose(f) != 0) ok = false
        }
        if (!ok) throw IllegalStateException("short write to $path")
    }

    override fun rename(from: String, to: String) {
        if (!renameFile(from, to)) throw IllegalStateException("cannot rename $from")
        syncDirectory(to.substringBeforeLast(pathSeparator))
    }

    override fun remove(path: String): Boolean = removeFile(path) || pathFacts(path) == null

    override fun removeDirectory(path: String): Boolean = me.raddatz.veyq.mobile.blob.removeDirectory(path) || pathFacts(path) == null

    override fun freeBytes(path: String): Long? = me.raddatz.veyq.mobile.blob.freeBytes(path)

    override fun totalBytes(path: String): Long? = me.raddatz.veyq.mobile.blob.totalBytes(path)
}
