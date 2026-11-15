package me.raddatz.veyq.mobile.blob

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

// Android (and the JVM's tests): java.nio, a write forced to the device before it returns.

internal actual val deviceFiles: DeviceFiles = object : DeviceFiles {
    override val separator: Char get() = File.separatorChar

    override fun createDirectory(path: String): Boolean {
        val f = File(path)
        if (f.isDirectory) return true
        if (!f.mkdir()) return f.isDirectory
        // this user alone, where the file system has modes
        f.setReadable(false, false); f.setWritable(false, false); f.setExecutable(false, false)
        f.setReadable(true, true); f.setWritable(true, true); f.setExecutable(true, true)
        return true
    }

    override fun list(path: String): List<String>? = File(path).takeIf { it.isDirectory }?.list()?.toList()

    override fun read(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun read(path: String, offset: Long, length: Int): ByteArray? = runCatching {
        RandomAccessFile(path, "r").use { f ->
            if (offset + length > f.length()) return@use null
            ByteArray(length).also { f.seek(offset); f.readFully(it) }
        }
    }.getOrNull()

    override fun size(path: String): Long? = File(path).takeIf { it.isFile }?.length()

    override fun write(path: String, bytes: ByteArray) = put(path, bytes, StandardOpenOption.TRUNCATE_EXISTING).let { }

    override fun append(path: String, bytes: ByteArray): Long = put(path, bytes, StandardOpenOption.APPEND)

    private fun put(path: String, bytes: ByteArray, how: StandardOpenOption): Long =
        FileChannel.open(Paths.get(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE, how).use { c ->
            val at = c.size()
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) c.write(buf)
            c.force(true)
            at
        }

    override fun rename(from: String, to: String) {
        try {
            Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IllegalStateException("cannot rename $from in one step", e)
        }
        // the directory's own force makes the rename durable where the system allows opening one (not Windows)
        runCatching { FileChannel.open(Paths.get(to).parent, StandardOpenOption.READ).use { it.force(true) } }
    }

    override fun remove(path: String): Boolean = File(path).let { it.delete() || !it.exists() }

    override fun removeDirectory(path: String): Boolean = File(path).let { it.delete() || !it.exists() }

    override fun freeBytes(path: String): Long? = File(path).usableSpace.takeIf { it > 0 }
}
